package org.hyperledger.identus.walletsdk.edgeagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import org.hyperledger.identus.walletsdk.logger.LogComponent
import org.hyperledger.identus.walletsdk.logger.Logger
import org.hyperledger.identus.walletsdk.logger.LoggerImpl
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

    private val logger: Logger = LoggerImpl(LogComponent.EDGE_AGENT)

    // live-mode 下 WS 推送与安全网 pickup 并行,可能在 ack 删库前重复投递同一条;
    // 按消息 id 做有界去重,避免重复处理。
    private val processedMessageIds = ArrayDeque<String>()
    private val processedIdsMutex = Mutex()

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
                    // live-mode = WS 推送(低延迟)+ 并行的低频"安全网补拉"(可靠性兜底)。
                    // mediator 只在收件方有已注册 live 连接的瞬间才推送,否则消息只存库不投递;
                    // 而 listenUnreadMessages 不轮询,重连退避空档/静默断开/注册竞态期间到达的消息会永久丢失。
                    // 安全网持续按 requestInterval 做 pickup,把任何未被推送的存库消息补回来。
                    logger.debug("[VP-LiveMode] start: WS push + safety-net poll, endpoint=$liveModeEndpoint, interval=${requestInterval}s")
                    coroutineScope {
                        // 子协程:安全网补拉,独立于 WS 重连,随整个 job 取消而退出
                        launch {
                            var pollCycle = 0
                            while (isActive) {
                                try {
                                    awaitMessages().collect { array ->
                                        if (array.isNotEmpty()) {
                                            logger.debug("[VP-LiveMode] safety-net poll #$pollCycle pulled ${array.size} message(s)")
                                        }
                                        processMessages(array, "poll")
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    // 单次补拉失败,下个周期重试
                                    logger.debug("[VP-LiveMode] safety-net poll #$pollCycle failed: ${e.message}")
                                }
                                pollCycle++
                                delay(requestInterval.seconds.inWholeMilliseconds)
                            }
                        }

                        // WS 推送监听 + 自动重连:listenUnreadMessages 挂起到 socket 关闭后退避重连
                        var wsAttempt = 0
                        while (isActive) {
                            try {
                                logger.debug("[VP-LiveMode] WS connecting (attempt #$wsAttempt)")
                                mediationHandler.listenUnreadMessages(
                                    liveModeEndpoint
                                ) { arrayMessages ->
                                    logger.debug("[VP-LiveMode] WS push received ${arrayMessages.size} message(s)")
                                    processMessages(arrayMessages, "push")
                                }
                                logger.debug("[VP-LiveMode] WS session closed (attempt #$wsAttempt), will reconnect")
                            } catch (e: CancellationException) {
                                throw e // 协程被取消(stopConnection)时正常退出,不重连
                            } catch (e: Throwable) {
                                // 连接异常:吞掉并按 requestInterval 退避后重连
                                logger.warning("[VP-LiveMode] WS disconnected (attempt #$wsAttempt), will reconnect: ${e.message}")
                                println("WebSocket live-mode disconnected, will reconnect: ${e.message}")
                            }
                            wsAttempt++
                            if (!isActive) break
                            delay(requestInterval.seconds.inWholeMilliseconds)
                        }
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

    internal fun processMessages(arrayMessages: Array<Pair<String, Message>>, source: String = "poll") {
        scope.launch {
            // 去重:过滤掉最近已处理过的消息(WS 推送 + 安全网 pickup 并行可能重复投递)
            val fresh = processedIdsMutex.withLock {
                arrayMessages.filter { pair ->
                    if (processedMessageIds.contains(pair.first)) {
                        false
                    } else {
                        processedMessageIds.addLast(pair.first)
                        if (processedMessageIds.size > MAX_TRACKED_PROCESSED_IDS) {
                            processedMessageIds.removeFirst()
                        }
                        true
                    }
                }
            }
            if (arrayMessages.isNotEmpty()) {
                val dup = arrayMessages.size - fresh.size
                logger.debug(
                    "[VP-LiveMode] processMessages(source=$source): received=${arrayMessages.size}, fresh=${fresh.size}, duplicatesSkipped=$dup" +
                        if (fresh.isNotEmpty()) ", piuris=${fresh.map { it.second.piuri }}" else ""
                )
            }
            if (fresh.isEmpty()) return@launch

            val messagesIds = mutableListOf<String>()
            val messages = mutableListOf<Message>()
            fresh.forEach { pair ->
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
                logger.debug("[VP-LiveMode] stored ${messages.size} message(s) to Pluto + acked (source=$source)")
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
        const val MAX_TRACKED_PROCESSED_IDS = 256
    }
}
