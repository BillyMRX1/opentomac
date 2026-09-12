package dev.opentomac.shared.session

import dev.opentomac.shared.crypto.Handshake
import dev.opentomac.shared.crypto.Identity
import dev.opentomac.shared.crypto.SecureSession
import dev.opentomac.shared.pairing.Clock
import dev.opentomac.shared.pairing.PairingManager
import dev.opentomac.shared.pairing.TrustStore
import dev.opentomac.shared.pairing.TrustedDevice
import dev.opentomac.shared.protocol.ChannelId
import dev.opentomac.shared.protocol.Envelope
import dev.opentomac.shared.protocol.FrameTransport
import dev.opentomac.shared.protocol.Heartbeat
import dev.opentomac.shared.protocol.HeartbeatAck
import dev.opentomac.shared.protocol.Hello
import dev.opentomac.shared.protocol.Message
import dev.opentomac.shared.protocol.ProtocolCodec
import dev.opentomac.shared.protocol.ProtocolException
import dev.opentomac.shared.protocol.UnknownMessageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.random.Random

/** Creates a fresh transport for each initiator connection attempt. */
interface TransportFactory {
    suspend fun connect(): FrameTransport
}

/** Timing, retry, and inbound-control limits for a [SessionManager]. */
data class SessionConfig(
    val heartbeatIntervalMs: Long = 10_000,
    val heartbeatTimeoutMisses: Int = 2,
    val backoffBaseMs: Long = 1_000,
    val backoffCapMs: Long = 60_000,
    val backoffJitterFraction: Double = 0.2,
    val rpcRateLimitPerMinute: Int = 10,
) {
    init {
        require(heartbeatIntervalMs > 0) { "heartbeatIntervalMs must be positive" }
        require(heartbeatTimeoutMisses > 0) { "heartbeatTimeoutMisses must be positive" }
        require(backoffBaseMs > 0) { "backoffBaseMs must be positive" }
        require(backoffCapMs > 0) { "backoffCapMs must be positive" }
        require(backoffJitterFraction in 0.0..1.0) {
            "backoffJitterFraction must be between 0.0 and 1.0"
        }
        require(rpcRateLimitPerMinute > 0) { "rpcRateLimitPerMinute must be positive" }
    }
}

/** Minimal logging hook for hosts and tests; null is intentionally a no-op. */
internal object SessionLog {
    var sink: ((String) -> Unit)? = null

    fun write(message: String) {
        sink?.invoke(message)
    }
}

/**
 * Owns at most one authenticated, encrypted peer session. Initiator failures are
 * retried with bounded exponential backoff; accepted responder transports never
 * reconnect because only the accepting host can supply their next transport.
 */
class SessionManager(
    private val identity: Identity,
    private val pairingManager: PairingManager,
    private val trustStore: TrustStore,
    private val transportFactory: TransportFactory,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val config: SessionConfig = SessionConfig(),
    private val deviceName: String = "opentomac",
    private val platform: String = "unknown",
    private val capabilities: Set<String> = emptySet(),
) {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    private val mutablePeerCapabilities = MutableStateFlow<Set<String>>(emptySet())
    val peerCapabilities: StateFlow<Set<String>> = mutablePeerCapabilities.asStateFlow()

    /** Returns false only for a non-empty peer list that omits [capability]. */
    fun supports(capability: String): Boolean = peerSupports(peerCapabilities.value, capability)

    /** Number of inbound CONTROL envelopes discarded by the token bucket. */
    var droppedEnvelopes: Long = 0
        private set

    private val lifecycleMutex = Mutex()
    private val handlers = mutableMapOf<ChannelId, suspend (Envelope) -> Unit>()
    private var generation = 0L
    private var connectionJob: Job? = null
    private var desiredPeer: TrustedDevice? = null
    private var openTransport: FrameTransport? = null
    private var activeSession: ConnectedSession? = null

    init {
        // Starting undispatched installs the SharedFlow subscription before the
        // constructor returns, so a revoke immediately after construction is not lost.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pairingManager.revocations.collect { deviceId ->
                handleRevocation(deviceId)
            }
        }
    }

    /** Starts one pinned initiator connection loop; concurrent calls are no-ops. */
    suspend fun connect(peer: TrustedDevice) {
        lifecycleMutex.withLock {
            if (connectionJob?.isActive == true || activeSession != null) return

            generation++
            val myGeneration = generation
            val peerSnapshot = peer.snapshot()
            desiredPeer = peerSnapshot
            mutablePeerCapabilities.value = emptySet()
            mutableState.value = ConnectionState.Connecting(attempt = 1)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                runInitiator(peerSnapshot, myGeneration)
            }
            connectionJob = job
            job.start()
        }
    }

    /** Accepts one responder transport; an already active manager rejects it. */
    suspend fun listen(transport: FrameTransport) {
        lifecycleMutex.withLock {
            if (connectionJob?.isActive == true || activeSession != null) {
                transport.close()
                return
            }

            generation++
            val myGeneration = generation
            desiredPeer = null
            mutablePeerCapabilities.value = emptySet()
            openTransport = transport
            val job = scope.launch(start = CoroutineStart.LAZY) {
                runResponder(transport, myGeneration)
            }
            connectionJob = job
            job.start()
        }
    }

    /** Replaces the handler for [channel]. Internal heartbeat messages are not dispatched. */
    fun registerHandler(channel: ChannelId, handler: suspend (Envelope) -> Unit) {
        handlers[channel] = handler
    }

    /** Sends one encrypted envelope with a per-session, wire-order sequence number. */
    suspend fun send(channel: ChannelId, message: Message) {
        val session = lifecycleMutex.withLock { activeSession }
            ?: throw IllegalStateException("Cannot send on $channel: no peer session is connected")
        session.send(channel, message)
    }

    /** Stops any handshake, live session, or scheduled retry and returns to [ConnectionState.Idle]. */
    suspend fun disconnect() {
        val job = lifecycleMutex.withLock {
            generation++
            desiredPeer = null
            activeSession?.close()
            openTransport?.close()
            activeSession = null
            openTransport = null
            mutablePeerCapabilities.value = emptySet()
            mutableState.value = ConnectionState.Idle
            connectionJob.also { connectionJob = null }
        }
        job?.cancelAndJoin()
    }

    private suspend fun runInitiator(peer: TrustedDevice, myGeneration: Long) {
        var attempt = 1
        while (isCurrent(myGeneration)) {
            currentCoroutineContext().ensureActive()
            mutableState.value = ConnectionState.Connecting(attempt)
            var transport: FrameTransport? = null
            var session: ConnectedSession? = null
            var reachedConnected = false
            try {
                transport = transportFactory.connect()
                if (!trackTransport(transport, myGeneration)) return

                val handshake = Handshake.initiate(
                    transport = transport,
                    identity = identity,
                    expectedPeerKey = peer.publicKey,
                )
                val secure = SecureSession(transport, handshake.sessionKeys, isInitiator = true)
                session = ConnectedSession(
                    transport = secure,
                    peer = peer,
                    rateLimiter = TokenBucket(config.rpcRateLimitPerMinute, clock.nowMs()),
                )
                if (!installConnected(session, transport, myGeneration)) return

                reachedConnected = true
                runConnected(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SessionLog.write(
                    "Initiator session with ${peer.deviceId} failed: ${e.message ?: e::class.simpleName}",
                )
            } finally {
                session?.close() ?: transport?.close()
                clearActive(session, transport, myGeneration)
            }

            if (!isCurrent(myGeneration)) return
            val delayAttempt = if (reachedConnected) 1 else attempt
            attempt = if (reachedConnected) 1 else attempt + 1
            mutableState.value = ConnectionState.Connecting(attempt)
            delay(reconnectDelayMs(delayAttempt))
        }
    }

    private suspend fun runResponder(transport: FrameTransport, myGeneration: Long) {
        var session: ConnectedSession? = null
        try {
            val handshake = Handshake.respond(
                transport = transport,
                identity = identity,
                expectedPeerKey = null,
            )
            val peer = pairingManager.verifyPeer(handshake.peerIdentityKey)
            val secure = SecureSession(transport, handshake.sessionKeys, isInitiator = false)
            session = ConnectedSession(
                transport = secure,
                peer = peer,
                rateLimiter = TokenBucket(config.rpcRateLimitPerMinute, clock.nowMs()),
            )
            if (!installConnected(session, transport, myGeneration)) return
            runConnected(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            SessionLog.write(
                "Responder session failed: ${e.message ?: e::class.simpleName}",
            )
        } finally {
            session?.close() ?: transport.close()
            lifecycleMutex.withLock {
                if (generation == myGeneration) {
                    if (activeSession === session) activeSession = null
                    if (openTransport === transport) openTransport = null
                    mutablePeerCapabilities.value = emptySet()
                    connectionJob = null
                    mutableState.value = ConnectionState.Idle
                }
            }
        }
    }

    private suspend fun runConnected(session: ConnectedSession) = coroutineScope {
        launch { heartbeatLoop(session) }
        while (true) {
            val bytes = session.transport.receive()
            val envelope = try {
                ProtocolCodec.decode(bytes)
            } catch (e: UnknownMessageException) {
                SessionLog.write("Ignoring unknown peer message: ${e.message}")
                continue
            }

            when (val payload = envelope.payload) {
                is Heartbeat -> session.send(ChannelId.CONTROL, HeartbeatAck(payload.sentAtMs))
                is HeartbeatAck -> session.acknowledgeHeartbeat(payload.sentAtMs)
                is Hello -> if (envelope.channel == ChannelId.CONTROL) {
                    mutablePeerCapabilities.value = payload.capabilities.toSet()
                } else {
                    dispatch(envelope)
                }
                else -> {
                    if (
                        envelope.channel == ChannelId.CONTROL &&
                        !session.rateLimiter.tryAcquire(clock.nowMs())
                    ) {
                        droppedEnvelopes++
                        SessionLog.write(
                            "Dropping rate-limited CONTROL envelope seq=${envelope.seq}; " +
                                "total=$droppedEnvelopes",
                        )
                        continue
                    }
                    dispatch(envelope)
                }
            }
        }
    }

    private suspend fun heartbeatLoop(session: ConnectedSession) {
        while (true) {
            delay(config.heartbeatIntervalMs)
            val sentAt = clock.nowMs()
            if (!session.beginHeartbeat(sentAt, config.heartbeatTimeoutMisses)) {
                throw ProtocolException(
                    "Peer ${session.peer.deviceId} missed ${config.heartbeatTimeoutMisses} heartbeat acknowledgements",
                )
            }
            session.send(ChannelId.CONTROL, Heartbeat(sentAt))
        }
    }

    private suspend fun dispatch(envelope: Envelope) {
        val handler = handlers[envelope.channel] ?: return
        try {
            handler(envelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            SessionLog.write(
                "Handler for ${envelope.channel} rejected seq=${envelope.seq}: ${e.message ?: e::class.simpleName}",
            )
        }
    }

    private suspend fun trackTransport(transport: FrameTransport, myGeneration: Long): Boolean =
        lifecycleMutex.withLock {
            if (generation != myGeneration) {
                transport.close()
                false
            } else {
                openTransport = transport
                true
            }
        }

    private suspend fun installConnected(
        session: ConnectedSession,
        transport: FrameTransport,
        myGeneration: Long,
    ): Boolean {
        val installed = lifecycleMutex.withLock {
            if (generation != myGeneration) {
                false
            } else {
                activeSession = session
                openTransport = transport
                true
            }
        }
        if (!installed) {
            session.close()
            return false
        }

        session.send(
            ChannelId.CONTROL,
            Hello(
                protocolVersion = ProtocolCodec.PROTOCOL_VERSION,
                deviceId = identity.deviceId,
                deviceName = deviceName,
                platform = platform,
                capabilities = capabilities.toList(),
            ),
        )

        return lifecycleMutex.withLock {
            if (generation != myGeneration || activeSession !== session) {
                false
            } else {
                mutableState.value = ConnectionState.Connected(
                    peer = session.peer.snapshot(),
                    transportLabel = transport::class.simpleName ?: "FrameTransport",
                )
                true
            }
        }.also { current ->
            if (!current) session.close()
        }
    }

    private suspend fun clearActive(
        session: ConnectedSession?,
        transport: FrameTransport?,
        myGeneration: Long,
    ) {
        lifecycleMutex.withLock {
            if (generation != myGeneration) return@withLock
            if (activeSession === session) activeSession = null
            if (openTransport === transport) openTransport = null
            if (activeSession == null) mutablePeerCapabilities.value = emptySet()
        }
    }

    private suspend fun handleRevocation(deviceId: String) {
        val job = lifecycleMutex.withLock {
            val affected = activeSession?.peer?.deviceId == deviceId || desiredPeer?.deviceId == deviceId
            if (!affected || trustStore.get(deviceId) != null) return

            generation++
            desiredPeer = null
            activeSession?.close()
            openTransport?.close()
            activeSession = null
            openTransport = null
            mutablePeerCapabilities.value = emptySet()
            mutableState.value = ConnectionState.Idle
            connectionJob.also { connectionJob = null }
        }
        job?.cancelAndJoin()
    }

    private suspend fun isCurrent(myGeneration: Long): Boolean = lifecycleMutex.withLock {
        generation == myGeneration
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        var capped = min(config.backoffBaseMs, config.backoffCapMs)
        repeat((attempt - 1).coerceAtLeast(0)) {
            capped = if (capped >= config.backoffCapMs / 2) {
                config.backoffCapMs
            } else {
                min(capped * 2, config.backoffCapMs)
            }
        }
        if (config.backoffJitterFraction == 0.0) return capped
        val jitter = Random.Default.nextDouble(
            from = -config.backoffJitterFraction,
            until = config.backoffJitterFraction,
        )
        return (capped.toDouble() * (1.0 + jitter)).toLong().coerceAtLeast(0)
    }

    private inner class ConnectedSession(
        val transport: SecureSession,
        val peer: TrustedDevice,
        val rateLimiter: TokenBucket,
    ) {
        private val sendMutex = Mutex()
        private val heartbeatMutex = Mutex()
        private var nextSeq = 0L
        private var awaitingHeartbeatAck: Long? = null
        private var heartbeatMisses = 0

        suspend fun send(channel: ChannelId, message: Message) {
            sendMutex.withLock {
                val envelope = Envelope(
                    version = ProtocolCodec.PROTOCOL_VERSION,
                    channel = channel,
                    seq = nextSeq++,
                    payload = message,
                )
                transport.send(ProtocolCodec.encode(envelope))
            }
        }

        suspend fun beginHeartbeat(sentAtMs: Long, timeoutMisses: Int): Boolean =
            heartbeatMutex.withLock {
                if (awaitingHeartbeatAck != null) heartbeatMisses++
                if (heartbeatMisses >= timeoutMisses) return@withLock false
                awaitingHeartbeatAck = sentAtMs
                true
            }

        suspend fun acknowledgeHeartbeat(sentAtMs: Long) {
            heartbeatMutex.withLock {
                if (awaitingHeartbeatAck == sentAtMs) {
                    awaitingHeartbeatAck = null
                    heartbeatMisses = 0
                }
            }
        }

        fun close() = transport.close()
    }
}

/** Continuously refilled token bucket with a one-minute rate and matching burst size. */
private class TokenBucket(
    limitPerMinute: Int,
    nowMs: Long,
) {
    private val capacity = limitPerMinute.toDouble()
    private val refillPerMs = capacity / 60_000.0
    private var tokens = capacity
    private var lastRefillMs = nowMs

    fun tryAcquire(nowMs: Long): Boolean {
        val elapsed = (nowMs - lastRefillMs).coerceAtLeast(0)
        tokens = min(capacity, tokens + elapsed * refillPerMs)
        lastRefillMs = nowMs
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}

private fun TrustedDevice.snapshot(): TrustedDevice = copy(publicKey = publicKey.copyOf())
