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
    ): RemoteDIDOperationResponse {
        try {
            logger.debug("Starting publishing process for DID: $did")

            // 将DID转换为base64url编码的SignedAtalaOperation
            val signedOperation = convertDidToSignedAtalaOperation(did, signWithFunction)

            // 构建请求体
            val requestBody = PublishDidRequest(signedOperation)

            // 调用cloudagent的发布DID接口
            val httpResponse = api.request(
                httpMethod = HttpMethod.Post.value,
                url = "$cloudAgentUrl/dids/operations",
                body = requestBody
            )
            val response = Json.decodeFromString<RemoteDIDOperationResponse>(httpResponse.jsonString)
            logger.info("Successfully submitted DID publish request, operationId: ${response.operationId}, status: ${response.status}")
            return response
        } catch (e: Exception) {
            // 修复：只传递错误消息，不传递Exception对象
            logger.error("Failed to publish DID: ${e.message}")
            throw EdgeAgentError.PublishPrismError("Failed to publish DID: ${e.message}")
        }
    }

    /**
     * Retrieves the status of a DID publish operation
     *
     * @param operationId The ID of the operation to check
     * @return The status of the operation
     * @throws EdgeAgentError If retrieving the status fails
     */
    suspend fun getOperationStatus(operationId: String): ScheduledDIDOperationStatus {
        try {
            logger.debug("Checking status for operation ID: $operationId")

            // 调用cloudagent的获取操作状态接口
            val httpResponse = api.request(
                httpMethod = HttpMethod.Get.value,
                url = "$cloudAgentUrl/dids/operations/$operationId",
                body = null
            )
            val response = Json.decodeFromString<OperationStatusResponse>(httpResponse.jsonString)

            logger.info("Retrieved status for operation $operationId: ${response.status}")
            return response.status
        } catch (e: Exception) {
            // 修复：只传递错误消息，不传递Exception对象
            logger.error("Failed to get operation status: ${e.message}")
            throw EdgeAgentError.PublishPrismError("Failed to get operation status: ${e.message}")
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
    ): String {
        try {
            // 1. 将DID解析为LongFormPrismDID以获取编码状态
            val longFormDID = LongFormPrismDID(did)

            // 2. 解码获取原始的AtalaOperation
            val encodedStateBytes = longFormDID.encodedState.base64UrlDecodedBytes
            val atalaOperation = AtalaOperation.decodeFromByteArray<AtalaOperation>(encodedStateBytes)

            // 3. 确保操作类型是CreateDIDOperation
            if (atalaOperation.operation !is AtalaOperation.Operation.CreateDid) {
                throw EdgeAgentError.PublishPrismError("DID does not contain a CreateDIDOperation")
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
            return signedOperation.encodeToByteArray().base64UrlEncoded
        } catch (e: Exception) {
            // 修复：只传递错误消息，不传递Exception对象
            logger.error("Failed to convert DID to SignedAtalaOperation: ${e.message}")
            throw EdgeAgentError.PublishPrismError("Failed to convert DID to SignedAtalaOperation: ${e.message}")
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
    private data class OperationStatusResponse(val status: ScheduledDIDOperationStatus)

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
