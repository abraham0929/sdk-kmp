package org.hyperledger.identus.walletsdk.edgeagent.helpers

import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.hyperledger.identus.apollo.base64.base64UrlDecodedBytes
import org.hyperledger.identus.apollo.base64.base64UrlEncoded
import org.hyperledger.identus.protos.AtalaOperation
import org.hyperledger.identus.protos.SignedAtalaOperation
import org.hyperledger.identus.walletsdk.castor.did.prismdid.LongFormPrismDID
import org.hyperledger.identus.walletsdk.castor.did.prismdid.PrismDIDPublicKey
import org.hyperledger.identus.walletsdk.castor.did.prismdid.defaultId
import org.hyperledger.identus.walletsdk.domain.models.Api
import org.hyperledger.identus.walletsdk.domain.models.DID
import org.hyperledger.identus.walletsdk.domain.models.Signature
import org.hyperledger.identus.walletsdk.edgeagent.EdgeAgentError
import org.hyperledger.identus.walletsdk.logger.LogComponent
import org.hyperledger.identus.walletsdk.logger.Logger
import org.hyperledger.identus.walletsdk.logger.LoggerImpl
import pbandk.ByteArr
import pbandk.decodeFromByteArray
import pbandk.encodeToByteArray

/**
 * Handles publishing Prism DIDs to the blockchain via cloudagent API
 */
class PublishPrismHandler(
    private val api: Api,
    private val cloudAgentUrl: String,
    private val logger: Logger = LoggerImpl(LogComponent.PUBLISHER)
) {

    /**
     * Data class for health check response
     */
    @Serializable
    data class HealthResponse(val version: String)

    /**
     * Result class to encapsulate either success data or error information
     */
    sealed class PublishResult<T> {
        data class Success<T>(val data: T) : PublishResult<T>()
        data class Error<T>(val message: String, val exception: Exception? = null) : PublishResult<T>()
    }

    /**
     * Checks if the cloud agent is accessible
     *
     * @return true if accessible, false otherwise
     */
    suspend fun isCloudAgentAccessible(): PublishResult<Boolean> {
        return try {
            logger.debug("Checking cloud agent accessibility: $cloudAgentUrl")
            val httpResponse = api.request(
                httpMethod = HttpMethod.Get.value,
                url = "$cloudAgentUrl/_system/health",
                body = null
            )

            // Check if status indicates success (2xx)
            if (httpResponse.status in 200..299) {
                // Try to parse the health response
                val isValidHealthResponse = runCatching {
                    Json.decodeFromString<HealthResponse>(httpResponse.jsonString)
                    true
                }.getOrElse {
                    logger.warning("Cloud agent responded with invalid health format: ${it.message}")
                    false
                }

                if (isValidHealthResponse) {
                    logger.debug("Cloud agent health check successful.")
                    PublishResult.Success(true)
                } else {
                    PublishResult.Success(false)
                }
            } else {
                logger.warning("Cloud agent returned non-success status: ${httpResponse.status}")
                PublishResult.Success(false)
            }
        } catch (e: Exception) {
            logger.error("Failed to access cloud agent: ${e.message}")
            PublishResult.Error("Failed to access cloud agent: ${e.message}", e)
        }
    }

    /**
     * Publishes a Prism DID to the blockchain via cloudagent API
     *
     * @param did The DID to publish
     * @param services Optional array of services to associate with the DID
     * @return The operation ID of the publish request
     * @throws EdgeAgentError If publishing fails
     */
    suspend fun publishPrismDid(
        did: DID,
        signWithFunction: suspend (DID, ByteArray) -> Signature
    ): PublishResult<RemoteDIDOperationResponse> {
        return try {
            logger.debug("Starting publishing process for DID: $did")

            // 将DID转换为base64url编码的SignedAtalaOperation
            val signedOperationResult = convertDidToSignedAtalaOperation(did, signWithFunction)

            // Handle potential error from convertDidToSignedAtalaOperation
            val signedOperation = when (signedOperationResult) {
                is PublishResult.Success -> signedOperationResult.data
                is PublishResult.Error -> return signedOperationResult as PublishResult.Error<RemoteDIDOperationResponse>
            }

            // 构建请求体
            val requestBody = PublishDidRequest(signedOperation)

            // 调用cloudagent的发布DID接口
            val httpResponse = api.request(
                httpMethod = HttpMethod.Post.value,
                url = "$cloudAgentUrl/dids/operations",
                body = requestBody
            )

            // Check if status indicates success (2xx)
            if (httpResponse.status in 200..299) {
                val response = Json.decodeFromString<RemoteDIDOperationResponse>(httpResponse.jsonString)
                logger.info("Successfully submitted DID publish request, operationId: ${response.operationId}, status: ${response.status}")
                PublishResult.Success<RemoteDIDOperationResponse>(response)
            } else {
                logger.error("Failed to publish DID. Cloud agent returned status: ${httpResponse.status}")
                PublishResult.Error<RemoteDIDOperationResponse>("Failed to publish DID. Cloud agent returned status: ${httpResponse.status}")
            }
        } catch (e: Exception) {
            // 返回错误信息而不是抛出异常
            logger.error("Failed to publish DID: ${e.message}")
            PublishResult.Error<RemoteDIDOperationResponse>("Failed to publish DID: ${e.message}", e)
        }
    }

    /**
     * Retrieves the status of a DID publish operation
     *
     * @param operationId The ID of the operation to check
     * @return The status of the operation
     * @throws EdgeAgentError If retrieving the status fails
     */
    suspend fun getOperationStatus(operationId: String): PublishResult<ScheduledDIDOperationStatus> {
        return try {
            logger.debug("Checking status for operation ID: $operationId")

            // 调用cloudagent的获取操作状态接口
            val httpResponse = api.request(
                httpMethod = HttpMethod.Get.value,
                url = "$cloudAgentUrl/dids/operations/$operationId",
                body = null
            )

            // Check if status indicates success (2xx)
            if (httpResponse.status in 200..299) {
                val response = Json.decodeFromString<OperationStatusResponse>(httpResponse.jsonString)
                logger.info("Retrieved status for operation $operationId: ${response.status}")
                PublishResult.Success<ScheduledDIDOperationStatus>(response.status)
            } else {
                logger.error("Failed to get operation status. Cloud agent returned status: ${httpResponse.status}")
                PublishResult.Error<ScheduledDIDOperationStatus>("Failed to get operation status. Cloud agent returned status: ${httpResponse.status}")
            }
        } catch (e: Exception) {
            // 返回错误信息而不是抛出异常
            logger.error("Failed to get operation status: ${e.message}")
            PublishResult.Error<ScheduledDIDOperationStatus>("Failed to get operation status: ${e.message}", e)
        }
    }

    /**
     * Converts a DID to a base64url encoded SignedAtalaOperation
     *
     * @param did The DID to convert
     * @param services Optional array of services to associate with the DID
     * @return Base64url encoded SignedAtalaOperation
     * @throws EdgeAgentError If conversion fails
     */
    private suspend fun convertDidToSignedAtalaOperation(
        did: DID,
        signWithFunction: suspend (DID, ByteArray) -> Signature
    ): PublishResult<String> {
        return try {
            // 1. 将DID解析为LongFormPrismDID以获取编码状态
            val longFormDID = LongFormPrismDID(did)

            // 2. 解码获取原始的AtalaOperation
            val encodedStateBytes = longFormDID.encodedState.base64UrlDecodedBytes
            val atalaOperation = AtalaOperation.decodeFromByteArray<AtalaOperation>(encodedStateBytes)

            // 3. 确保操作类型是CreateDIDOperation
            if (atalaOperation.operation !is AtalaOperation.Operation.CreateDid) {
                return PublishResult.Error("DID does not contain a CreateDIDOperation")
            }

            // 4. 使用EdgeAgent签名AtalaOperation
            val signature = signWithFunction(did, atalaOperation.encodeToByteArray())

            // 5. 创建SignedAtalaOperation
            val signedOperation = SignedAtalaOperation(
                signedWith = PrismDIDPublicKey.Usage.MASTER_KEY.defaultId(),
                signature = ByteArr(signature.value),
                operation = atalaOperation
            )

            // 6. 转换为base64url编码
            PublishResult.Success(signedOperation.encodeToByteArray().base64UrlEncoded)
        } catch (e: Exception) {
            // 返回错误信息而不是抛出异常
            logger.error("Failed to convert DID to SignedAtalaOperation: ${e.message}")
            PublishResult.Error("Failed to convert DID to SignedAtalaOperation: ${e.message}", e)
        }
    }

    /**
     * Data class representing the request body for publishing a DID
     */
    @kotlinx.serialization.Serializable
    private data class PublishDidRequest(val signedOperation: String)

    /**
     * Data class representing the response from cloudagent when publishing a DID
     */
    @kotlinx.serialization.Serializable
    data class RemoteDIDOperationResponse(val operationId: String, val status: String)

    /**
     * Data class representing the response from cloudagent when checking operation status
     */
    @Serializable
    private data class OperationStatusResponse(val operationId: String, val status: ScheduledDIDOperationStatus)

    /**
     * Enum representing the possible statuses of an operation
     */
    enum class ScheduledDIDOperationStatus {
        Pending,
        AwaitingConfirmation,
        Confirmed,
        Rejected
    }
}
