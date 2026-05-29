package org.hyperledger.identus.walletsdk.edgeagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.hyperledger.identus.apollo.base64.base64UrlDecoded
import org.hyperledger.identus.walletsdk.domain.buildingblocks.Castor
import org.hyperledger.identus.walletsdk.domain.buildingblocks.Mercury
import org.hyperledger.identus.walletsdk.domain.buildingblocks.Pluto
import org.hyperledger.identus.walletsdk.domain.buildingblocks.Pollux
import org.hyperledger.identus.walletsdk.domain.models.AttachmentData.AttachmentBase64
import org.hyperledger.identus.walletsdk.domain.models.CredentialType
import org.hyperledger.identus.walletsdk.domain.models.DID
import org.hyperledger.identus.walletsdk.domain.models.DIDPair
import org.hyperledger.identus.walletsdk.domain.models.Message
import org.hyperledger.identus.walletsdk.edgeagent.connectionsmanager.ConnectionsManager
import org.hyperledger.identus.walletsdk.edgeagent.connectionsmanager.DIDCommConnection
import org.hyperledger.identus.walletsdk.edgeagent.mediation.MediationHandler
import org.hyperledger.identus.walletsdk.edgeagent.protocols.ProtocolType
import org.hyperledger.identus.walletsdk.edgeagent.protocols.issueCredential.IssueCredential
import org.hyperledger.identus.walletsdk.edgeagent.protocols.revocation.RevocationNotification
import kotlin.time.Duration.Companion.seconds

interface ConnectionManager : ConnectionsManager, DIDCommConnection {

    val mediationHandler: MediationHandler

    suspend fun startMediator()

    suspend fun registerMediator(host: DID)

    override suspend fun addConnection(paired: DIDPair)

    override suspend fun removeConnection(pair: DIDPair): DIDPair?

    override suspend fun awaitMessages(): Flow<Array<Pair<String, Message>>>

    override suspend fun awaitMessageResponse(id: String): Message?

    override suspend fun sendMessage(message: Message): Message?

    fun startFetchingMessages(requestInterval: Int = 5)
}

/**
 * ConnectionManager is responsible for managing connections and communication between entities.
 *
 * @property mercury The instance of the Mercury interface used for sending and receiving messages.
 * @property castor The instance of the Castor interface used for working with DIDs.
 * @property pluto The instance of the Pluto interface used for storing messages and connection information.
 * @property mediationHandler The instance of the MediationHandler interface used for handling mediation.
 * @property experimentLiveModeOptIn Flag to opt in or out of the experimental feature mediator live mode, using websockets.
 * @property pairings The mutable list of DIDPair representing the connections managed by the ConnectionManager.
 */
class ConnectionManagerImpl(
    private val mercury: Mercury,
    private val castor: Castor,
    private val pluto: Pluto,
    override val mediationHandler: MediationHandler,
    private var pairings: MutableList<DIDPair>,
    private val pollux: Pollux,
    private val experimentLiveModeOptIn: Boolean = false,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : ConnectionManager, ConnectionsManager, DIDCommConnection {

    var fetchingMessagesJob: Job? = null

    /**
     * Starts the process of fetching messages at a regular interval.
     *
     * @param requestInterval The time interval (in seconds) between message fetch requests.
     *                        Defaults to 5 seconds if not specified.
     */
    override fun startFetchingMessages(requestInterval: Int) {
        // Check if the job for fetching messages is already running
        if (fetchingMessagesJob == null) {
            // Launch a coroutine in the provided scope
            fetchingMessagesJob = scope.launch {
                // Retrieve the current mediator DID
                val currentMediatorDID = mediationHandler.mediatorDID
                // Resolve the DID document for the mediator
                val mediatorDidDoc = castor.resolveDID(currentMediatorDID.toString())
                var serviceEndpoint: String? = null
                if (experimentLiveModeOptIn) {
                    // Loop through the services in the DID document to find a WebSocket endpoint
                    mediatorDidDoc.services.forEach {
                        if (it.serviceEndpoint.uri.contains("wss://") || it.serviceEndpoint.uri.contains("ws://")) {
                            serviceEndpoint = it.serviceEndpoint.uri
                            return@forEach // Exit loop once the WebSocket endpoint is found
                        }
                    }
                }

                val liveModeEndpoint = serviceEndpoint
                if (liveModeEndpoint != null) {
                    // WebSocket live-mode 自动重连:listenUnreadMessages 会一直挂起到 socket 关闭。
                    // 用循环重新建立连接,避免静默断开(socket 正常关闭、不抛异常)后消息投递永久停止。
                    while (this.isActive) {
                        try {
                            mediationHandler.listenUnreadMessages(
                                liveModeEndpoint
                            ) { arrayMessages ->
                                processMessages(arrayMessages)
                            }
                        } catch (e: CancellationException) {
                            throw e // 协程被取消(stopConnection)时正常退出,不重连
                        } catch (e: Throwable) {
                            // 连接异常:吞掉并在下面按 requestInterval 退避后重连
                            println("WebSocket live-mode disconnected, will reconnect: ${e.message}")
                        }
                        if (!this.isActive) break
                        // socket 关闭或异常后,退避一个 requestInterval 再重连
                        delay(requestInterval.seconds.inWholeMilliseconds)
                    }
                } else {
                    // Fallback mechanism if no WebSocket service endpoint is available
                    while (this.isActive) {
                        // Continuously await and process new messages
                        awaitMessages().collect { array ->
                            processMessages(array)
                        }
                        // Wait for the specified request interval before fetching new messages
                        delay(requestInterval.seconds.inWholeMilliseconds)
                    }
                }
            }

            // Start the coroutine if it's not already active
            fetchingMessagesJob?.let {
                if (it.isActive) return
                it.start()
            }
        }
    }

    override fun stopConnection() {
        fetchingMessagesJob?.cancel()
    }

    /**
     * Suspends the current coroutine and boots the registered mediator associated with the mediator handler.
     * If no mediator is available, a [EdgeAgentError.NoMediatorAvailableError] is thrown.
     *
     * @throws EdgeAgentError.NoMediatorAvailableError if no mediator is available.
     */
    override suspend fun startMediator() {
        mediationHandler.bootRegisteredMediator()
            ?: throw EdgeAgentError.NoMediatorAvailableError()
    }

    /**
     * Registers a mediator with the given host DID.
     *
     * @param host The DID of the entity to mediate with.
     */
    override suspend fun registerMediator(host: DID) {
        mediationHandler.achieveMediation(host).collect {
            println("Achieve mediation")
        }
    }

    /**
     * Sends a message over the connection.
     *
     * @param message The message to send.
     * @return The response message, if one is received.
     */
    @Throws(EdgeAgentError.NoMediatorAvailableError::class)
    override suspend fun sendMessage(message: Message): Message? {
        if (mediationHandler.mediator == null) {
            throw EdgeAgentError.NoMediatorAvailableError()
        }
        val msg = Message(
            id = message.id,
            piuri = message.piuri,
            from = message.from,
            to = message.to,
            fromPrior = message.fromPrior,
            body = message.body,
            extraHeaders = message.extraHeaders,
            createdTime = message.createdTime,
            expiresTimePlus = message.expiresTimePlus,
            attachments = message.attachments,
            thid = message.thid,
            pthid = message.pthid,
            ack = message.ack,
            direction = Message.Direction.SENT
        )
        pluto.storeMessage(msg)
        return mercury.sendMessageParseResponse(msg)
    }

    /**
     * Awaits messages from the connection.
     *
     * @return An array of messages received from the connection.
     */
    override suspend fun awaitMessages(): Flow<Array<Pair<String, Message>>> {
        return mediationHandler.pickupUnreadMessages(NUMBER_OF_MESSAGES)
    }

    /**
     * Adds a connection to the manager.
     *
     * @param paired The [DIDPair] representing the connection to be added.
     */
    override suspend fun addConnection(paired: DIDPair) {
        if (pairings.contains(paired)) return
        pluto.storeDIDPair(paired.holder, paired.receiver, paired.name ?: "")
        pairings.add(paired)
    }

    /**
     * Removes a connection from the manager.
     *
     * @param pair The [DIDPair] representing the connection to be removed.
     * @return The [DIDPair] object that was removed from the manager, or null if the connection was not found.
     */
    override suspend fun removeConnection(pair: DIDPair): DIDPair? {
        val index = pairings.indexOf(pair)
        if (index > -1) {
            pairings.removeAt(index)
        }
        return null
    }

    internal fun processMessages(arrayMessages: Array<Pair<String, Message>>) {
        scope.launch {
            val messagesIds = mutableListOf<String>()
            val messages = mutableListOf<Message>()
            arrayMessages.forEach { pair ->
                messagesIds.add(pair.first)
                messages.add(pair.second)
            }
            val revokedMessages = messages.filter { it.piuri == ProtocolType.PrismRevocation.value }

            // Extract thread IDs from revocation messages
            val threadIds = revokedMessages.mapNotNull { msg ->
                try {
                    val revokedMessage = RevocationNotification.fromMessage(msg)
                    revokedMessage.body.threadId
                } catch (e: Exception) {
                    println("Error processing revocation message: ${e.message}")
                    null
                }
            }

            if (threadIds.isNotEmpty()) {
                // Batch query for all matching messages
                val matchingMessages = pluto.getMessagesInThidsAndPiuri(
                    threadIds,
                    ProtocolType.DidcommIssueCredential.value
                ).firstOrNull() ?: emptyList()

                matchingMessages.forEach { matchingMessage ->
                    try {
                        val issueMessage = IssueCredential.fromMessage(matchingMessage)

                        // Check if it is JWT format
                        if (pollux.extractCredentialFormatFromMessage(issueMessage.attachments) == CredentialType.JWT) {
                            val attachment = issueMessage.attachments.firstOrNull()?.data as? AttachmentBase64

                            attachment?.let {
                                try {
                                    val credentialId = it.base64.base64UrlDecoded
                                    pluto.revokeCredential(credentialId)
                                } catch (e: Exception) {
                                    println("Error decoding credential ID: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("Error processing matching message: ${e.message}")
                    }
                }
            }

            if (messagesIds.isNotEmpty()) {
                mediationHandler.registerMessagesAsRead(
                    messagesIds.toTypedArray()
                )
                pluto.storeMessages(messages)
            }
        }
    }

    /**
     * Awaits a response to a specified message ID from the connection.
     *
     * @param id The ID of the message for which to await a response.
     * @return The response message, if one is received.
     */
    override suspend fun awaitMessageResponse(id: String): Message? {
        return awaitMessages().firstOrNull()?.firstOrNull { it.second.thid == id }?.second
    }

    companion object {
        const val NUMBER_OF_MESSAGES = 10
    }
}
