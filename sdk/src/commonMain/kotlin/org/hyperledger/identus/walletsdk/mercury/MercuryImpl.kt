package org.hyperledger.identus.walletsdk.mercury

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import org.didcommx.didcomm.common.Typ
import org.didcommx.didcomm.utils.isDID
import org.hyperledger.identus.walletsdk.domain.DIDCOMM_MESSAGING
import org.hyperledger.identus.walletsdk.domain.buildingblocks.Castor
import org.hyperledger.identus.walletsdk.domain.buildingblocks.Mercury
import org.hyperledger.identus.walletsdk.domain.models.Api
import org.hyperledger.identus.walletsdk.domain.models.DID
import org.hyperledger.identus.walletsdk.domain.models.DIDDocument
import org.hyperledger.identus.walletsdk.domain.models.KeyValue
import org.hyperledger.identus.walletsdk.domain.models.MercuryError
import org.hyperledger.identus.walletsdk.domain.models.Message
import org.hyperledger.identus.walletsdk.logger.LogComponent
import org.hyperledger.identus.walletsdk.logger.LogLevel
import org.hyperledger.identus.walletsdk.logger.Logger
import org.hyperledger.identus.walletsdk.logger.LoggerImpl
import org.hyperledger.identus.walletsdk.logger.Metadata
import org.hyperledger.identus.walletsdk.mercury.forward.ForwardMessage
import kotlin.time.TimeSource

/**
 * The DIDCommProtocol interface provides methods for packing and unpacking DIDComm messages.
 */
interface DIDCommProtocol {
    /**
     * Packs a given message object into a string representation.
     *
     * @param message The message object to pack.
     * @return The string representation of the packed message.
     * @throws [MercuryError.NoDIDReceiverSetError] if DIDReceiver is invalid.
     * @throws [MercuryError.NoDIDSenderSetError] if DIDSender is invalid.
     */
    fun packEncrypted(message: Message): String

    /**
     * Unpacks a given string representation of a message into a [Message] object.
     *
     * @param message The string representation of the message to unpack.
     * @return The unpacked [Message] object.
     */
    fun unpack(message: String): Message
}

/**
 * Mercury is a powerful and flexible library for working with decentralized identifiers and secure communications
 * protocols. Whether you are a developer looking to build a secure and private messaging app or a more complex
 * decentralized system requiring trusted peer-to-peer connections, Mercury provides the tools and features you need to
 * establish, manage, and secure your communications easily.
 */
class MercuryImpl
@JvmOverloads
constructor(
    private val castor: Castor,
    private val protocol: DIDCommProtocol,
    private val api: Api,
    private val logger: Logger = LoggerImpl(LogComponent.MERCURY)
) : Mercury {

    /**
     * Asynchronously packs a given message object into a string representation. This function may throw an error if the
     * message object is invalid.
     *
     * @param message The message object to pack
     * @return The string representation of the packed message
     * @throws [MercuryError.NoDIDReceiverSetError] if DIDReceiver is invalid.
     * @throws [MercuryError.NoDIDSenderSetError] if DIDSender is invalid.
     */
    @Throws(MercuryError.NoDIDReceiverSetError::class, MercuryError.NoDIDSenderSetError::class)
    override fun packMessage(message: Message): String {
        if (message.to !is DID) {
            throw MercuryError.NoDIDReceiverSetError()
        }

        if (message.from !is DID) {
            throw MercuryError.NoDIDSenderSetError()
        }

        return protocol.packEncrypted(message)
    }

    /**
     * Asynchronously unpacks a given string representation of a message into a message object. This
     * function may throw an error if the string is not a valid message representation.
     *
     * @param message The string representation of the message to unpack
     * @return The message object
     */
    override fun unpackMessage(message: String): Message {
        return protocol.unpack(message)
    }

    /**
     * Asynchronously sends a given message and returns the response data.
     *
     * @param message The message to send
     * @return The response data
     * @throws [MercuryError.NoDIDReceiverSetError] if DIDReceiver is invalid.
     * @throws [MercuryError.NoDIDSenderSetError] if DIDSender is invalid.
     * @throws [MercuryError.NoValidServiceFoundError] if no valid service is found for the receiver DID.
     */
    @Throws(MercuryError.NoDIDReceiverSetError::class, MercuryError.NoDIDSenderSetError::class, MercuryError.NoValidServiceFoundError::class)
    override suspend fun sendMessage(message: Message): ByteArray? {
        if (message.to !is DID) {
            throw MercuryError.NoDIDReceiverSetError()
        }

        if (message.from !is DID) {
            throw MercuryError.NoDIDSenderSetError()
        }

        var perfMark = TimeSource.Monotonic.markNow()
        val document = castor.resolveDID(message.to.toString())
        logger.debug("[VP-Perf] mercury.resolveDID(to) ${perfMark.elapsedNow().inWholeMilliseconds}ms")
        perfMark = TimeSource.Monotonic.markNow()
        val packedMessage = packMessage(message)
        logger.debug("[VP-Perf] mercury.packMessage(orig) ${perfMark.elapsedNow().inWholeMilliseconds}ms")
        val service = document.services.find { it.type.contains(DIDCOMM_MESSAGING) }

        getMediatorDID(service)?.let { mediatorDid ->
            val mediatorDocument = castor.resolveDID(mediatorDid.toString())
            val mediatorUri =
                mediatorDocument.services.find { it.type.contains(DIDCOMM_MESSAGING) }?.serviceEndpoint?.uri
            try {
                val forwardMsg = prepareForwardMessage(message, packedMessage, mediatorDid)
                logger.debug(
                    message = "Sending forward message with internal message type ${message.piuri}",
                    metadata = arrayOf(
                        Metadata.MaskedMetadataByLevel(
                            key = "Sender",
                            value = forwardMsg.from.toString(),
                            level = LogLevel.DEBUG
                        ),
                        Metadata.MaskedMetadataByLevel(
                            key = "Receiver",
                            value = forwardMsg.to.toString(),
                            level = LogLevel.DEBUG
                        )
                    )
                )
                perfMark = TimeSource.Monotonic.markNow()
                val packedForwardMsg = packMessage(forwardMsg.makeMessage())
                logger.debug("[VP-Perf] mercury.packMessage(forward) ${perfMark.elapsedNow().inWholeMilliseconds}ms")
                logger.debug(
                    message = "Sending message with type ${message.piuri}",
                    metadata = arrayOf(
                        Metadata.MaskedMetadataByLevel(
                            key = "Sender",
                            value = message.from.toString(),
                            level = LogLevel.DEBUG
                        ),
                        Metadata.MaskedMetadataByLevel(
                            key = "Receiver",
                            value = message.to.toString(),
                            level = LogLevel.DEBUG
                        )
                    )
                )
                perfMark = TimeSource.Monotonic.markNow()
                val forwardResponse = makeRequest(mediatorUri, packedForwardMsg)
                logger.debug("[VP-Perf] mercury.makeRequest(POST) ${perfMark.elapsedNow().inWholeMilliseconds}ms")
                return forwardResponse
            } catch (e: Throwable) {
                throw MercuryError.NoValidServiceFoundError(did = mediatorDid.toString())
            }
        }
        logger.debug(
            message = "Sending message with type ${message.piuri}",
            metadata = arrayOf(
                Metadata.MaskedMetadataByLevel(
                    key = "Sender",
                    value = message.from.toString(),
                    level = LogLevel.DEBUG
                ),
                Metadata.MaskedMetadataByLevel(
                    key = "Receiver",
                    value = message.to.toString(),
                    level = LogLevel.DEBUG
                )
            )
        )
        return makeRequest(service, packedMessage)
    }

    /**
     * Asynchronously sends a given message and returns the response message object.
     *
     * @param message The message to send
     * @return The response message object or null
     */
    @Throws(MercuryError::class)
    override suspend fun sendMessageParseResponse(message: Message): Message? {
        val msg = try {
            sendMessage(message)
        } catch (e: MercuryError) {
            logger.error(
                "Error sending message: ${e.message}",
                arrayOf(
                    Metadata.PrivateMetadataByLevel(category = LogComponent.MERCURY, "message", message.toJsonString(), LogLevel.DEBUG),
                    Metadata.PublicMetadata("message", message.toJsonString())
                )
            )
            throw e
        }

        msg?.let {
            val msgString = String(msg)
            if (msgString != "null" && msgString != "") {
                return try {
                    unpackMessage(msgString)
                } catch (e: Exception) {
                    logger.error(
                        "Error unpacking message response: ${e.message}",
                        arrayOf(
                            Metadata.PrivateMetadataByLevel(category = LogComponent.MERCURY, "response", msgString, LogLevel.DEBUG),
                            Metadata.PublicMetadata("response", msgString),
                            Metadata.PublicMetadata("error", e.toString())
                        )
                    )
                    null
                }
            }
        }
        return null
    }

    /**
     * Prepares a [ForwardMessage] object to forward a message.
     *
     * @param message The original message to be forwarded.
     * @param encrypted The encrypted representation of the message.
     * @param mediatorDid The DID of the mediator.
     * @return The [ForwardMessage] object with the necessary information for forwarding the message.
     */
    private fun prepareForwardMessage(message: Message, encrypted: String, mediatorDid: DID): ForwardMessage {
        return ForwardMessage(
            body = ForwardMessage.ForwardBody(message.to.toString()),
            encryptedMessage = encrypted,
            from = message.from!!,
            to = mediatorDid
        )
    }

    /**
     * Makes a request to the specified service.
     *
     * @param service The service to make the request to
     * @param message The message to send in the request
     * @return The response data as a byte array
     * @throws MercuryError.NoValidServiceFoundError if no valid service is found for the given service
     */
    @Throws(MercuryError.NoValidServiceFoundError::class)
    private suspend fun makeRequest(service: DIDDocument.Service?, message: String): ByteArray? {
        if (service !is DIDDocument.Service) {
            throw MercuryError.NoValidServiceFoundError()
        }

        val result = api.request(
            HttpMethod.Post.value,
            service.serviceEndpoint.uri,
            emptyArray(),
            arrayOf(KeyValue(HttpHeaders.ContentType, Typ.Encrypted.typ)),
            message
        )

        if (result.status >= 400) {
            logger.error(
                "Calling api result in ${result.status} error",
                arrayOf(
                    Metadata.PublicMetadata("statusCode", "${result.status}"),
                    Metadata.PublicMetadata("uri", service.serviceEndpoint.uri),
                    Metadata.PrivateMetadataByLevel(category = LogComponent.MERCURY, "body", message, LogLevel.DEBUG),
                    Metadata.PrivateMetadataByLevel(category = LogComponent.MERCURY, "response", result.jsonString, LogLevel.DEBUG),
                    Metadata.PublicMetadata("body", message),
                    Metadata.PublicMetadata("response", result.jsonString),

                )
            )
        } else {
            logger.info("Calling api result in ${result.status} success")
        }

        return result.jsonString.toByteArray()
    }

    /**
     * Makes a request to the specified URI.
     *
     * @param uri The URI of the service to make the request to
     * @param message The message to send in the request
     * @return The response data as a byte array, or null if the request fails
     * @throws MercuryError.NoValidServiceFoundError if no valid service is found for the given service
     */
    @Throws(MercuryError.NoValidServiceFoundError::class)
    private suspend fun makeRequest(uri: String?, message: String): ByteArray? {
        if (uri.isNullOrBlank()) {
            throw MercuryError.NoValidServiceFoundError("Invalid or empty URI provided")
        }

        val result = try {
            api.request(
                HttpMethod.Post.value,
                uri,
                emptyArray(),
                arrayOf(KeyValue(HttpHeaders.ContentType, Typ.Encrypted.typ)),
                message
            )
        } catch (e: Exception) {
            throw MercuryError.NoValidServiceFoundError("Network request failed: ${e.message}")
        }
        // 2. HTTP状态码检查
        if (result.status !in 200..299) {
            logger.error(
                "API call failed with status ${result.status}",
                arrayOf(
                    Metadata.PublicMetadata("statusCode", "${result.status}"),
                    Metadata.PublicMetadata("uri", uri),
                    Metadata.PrivateMetadataByLevel(category = LogComponent.MERCURY, "response", result.jsonString, LogLevel.DEBUG),
                    Metadata.PublicMetadata("response", result.jsonString),
                )
            )
            throw MercuryError.NoValidServiceFoundError("API returned error status: ${result.status}")
        }
        // 3. 成功响应
        logger.info(
            "API call succeeded",
            arrayOf(
                Metadata.PublicMetadata("statusCode", "${result.status}"),
                Metadata.PublicMetadata("uri", uri)
            )
        )
        // 4. 返回数据
        return result.jsonString.toByteArray(Charsets.UTF_8)
    }

    /**
     * Retrieves the Mediator DID (Decentralized Identifier) from the given service.
     *
     * @param service The service for which to retrieve the Mediator DID.
     * @return The Mediator DID, or null if it could not be found or is invalid.
     */
    private fun getMediatorDID(service: DIDDocument.Service?): DID? {
        return service?.serviceEndpoint?.uri?.let { uri ->
            if (isDID(uri)) {
                castor.parseDID(uri)
            } else {
                null
            }
        }
    }
}
