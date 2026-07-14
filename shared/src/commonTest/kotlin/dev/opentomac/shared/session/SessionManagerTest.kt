@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalSerializationApi::class)

package dev.opentomac.shared.session

import dev.opentomac.shared.crypto.Handshake
import dev.opentomac.shared.crypto.Identity
import dev.opentomac.shared.crypto.SecureSession
import dev.opentomac.shared.pairing.Clock
import dev.opentomac.shared.pairing.InMemoryTrustStore
import dev.opentomac.shared.pairing.PairingManager
import dev.opentomac.shared.pairing.TrustedDevice
import dev.opentomac.shared.protocol.ChannelId
import dev.opentomac.shared.protocol.Envelope
import dev.opentomac.shared.protocol.FrameTransport
import dev.opentomac.shared.protocol.Heartbeat
import dev.opentomac.shared.protocol.HeartbeatAck
import dev.opentomac.shared.protocol.InMemoryFrameTransport
import dev.opentomac.shared.protocol.ProtocolCodec
import dev.opentomac.shared.protocol.RevokeDevice
import dev.opentomac.shared.protocol.WireEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionManagerTest {

    @Test
    fun connectTransitionsIdleConnectingConnectedWithPinnedPeer() = runTest {
        val localIdentity = Identity.generate()
        val peerIdentity = Identity.generate()
        val peer = trusted(peerIdentity, "mac")
        val store = InMemoryTrustStore().also { it.save(peer) }
        val pairing = PairingManager(localIdentity, store, SchedulerClock(testScheduler))
        val (localTransport, peerTransport) = InMemoryFrameTransport.pair()
        val factory = FixedTransportFactory(localTransport)
        val manager = manager(localIdentity, pairing, store, factory)
        val states = mutableListOf<ConnectionState>()
        val collecting = backgroundScope.launch { manager.state.collect(states::add) }
        val responding = backgroundScope.async {
            val result = Handshake.respond(peerTransport, peerIdentity)
            SecureSession(peerTransport, result.sessionKeys, isInitiator = false)
        }
        runCurrent()

        manager.connect(peer)
        runCurrent()

        assertIs<ConnectionState.Idle>(states.first())
        assertTrue(states.any { it is ConnectionState.Connecting && it.attempt == 1 })
        val connected = assertIs<ConnectionState.Connected>(manager.state.value)
        assertEquals(peer, connected.peer)
        assertEquals("InMemoryFrameTransport", connected.transportLabel)
        assertEquals(1, factory.calls)

        manager.disconnect()
        collecting.cancelAndJoin()
        responding.await().close()
    }

    @Test
    fun concurrentConnectCallsAreSingleFlight() = runTest {
        val localIdentity = Identity.generate()
        val peerIdentity = Identity.generate()
        val peer = trusted(peerIdentity)
        val store = InMemoryTrustStore().also { it.save(peer) }
        val gate = CompletableDeferred<Unit>()
        val factory = object : TransportFactory {
            var calls = 0

            override suspend fun connect(): FrameTransport {
                calls++
                gate.await()
                error("gated connect released")
            }
        }
        val manager = manager(
            localIdentity,
            PairingManager(localIdentity, store, SchedulerClock(testScheduler)),
            store,
            factory,
        )

        val first = async { manager.connect(peer) }
        val second = async { manager.connect(peer) }
        runCurrent()

        assertEquals(1, factory.calls)
        first.await()
        second.await()
        manager.disconnect()
        gate.complete(Unit)
    }

    @Test
    fun pinnedKeyMismatchReconnectsAndIncrementsAttempt() = runTest {
        val localIdentity = Identity.generate()
        val expectedIdentity = Identity.generate()
        val impostorIdentity = Identity.generate()
        val peer = trusted(expectedIdentity)
        val store = InMemoryTrustStore().also { it.save(peer) }
        var calls = 0
        val factory = object : TransportFactory {
            override suspend fun connect(): FrameTransport {
                calls++
                val (local, remote) = InMemoryFrameTransport.pair()
                backgroundScope.launch {
                    runCatching { Handshake.respond(remote, impostorIdentity) }
                    remote.close()
                }
                return local
            }
        }
        val manager = manager(
            localIdentity,
            PairingManager(localIdentity, store, SchedulerClock(testScheduler)),
            store,
            factory,
            config = fastConfig(backoffBaseMs = 100),
        )

        manager.connect(peer)
        runCurrent()

        assertEquals(1, calls)
        assertEquals(2, assertIs<ConnectionState.Connecting>(manager.state.value).attempt)

        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(3, assertIs<ConnectionState.Connecting>(manager.state.value).attempt)

        manager.disconnect()
    }

    @Test
    fun reconnectBackoffDoublesAndCapsUsingVirtualTime() = runTest {
        val identity = Identity.generate()
        val peer = trusted(Identity.generate())
        val store = InMemoryTrustStore().also { it.save(peer) }
        val callTimes = mutableListOf<Long>()
        val factory = object : TransportFactory {
            override suspend fun connect(): FrameTransport {
                callTimes += testScheduler.currentTime
                error("network unavailable")
            }
        }
        val manager = manager(
            identity,
            PairingManager(identity, store, SchedulerClock(testScheduler)),
            store,
            factory,
            config = fastConfig(backoffBaseMs = 100, backoffCapMs = 400),
        )

        manager.connect(peer)
        runCurrent()
        assertEquals(listOf(0L), callTimes)

        advanceTimeBy(100)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        advanceTimeBy(400)
        runCurrent()

        assertEquals(listOf(0L, 100L, 300L, 700L, 1_100L), callTimes)
        manager.disconnect()
    }

    @Test
    fun heartbeatAcksKeepSessionAliveAndTwoMissesReconnect() = runTest {
        val localIdentity = Identity.generate()
        val peerIdentity = Identity.generate()
        val peer = trusted(peerIdentity)
        val store = InMemoryTrustStore().also { it.save(peer) }
        val (localTransport, peerTransport) = InMemoryFrameTransport.pair()
        val factory = FixedTransportFactory(localTransport)
        val manager = manager(
            localIdentity,
            PairingManager(localIdentity, store, SchedulerClock(testScheduler)),
            store,
            factory,
            config = fastConfig(heartbeatIntervalMs = 100, heartbeatTimeoutMisses = 2),
        )
        val peerSession = backgroundScope.async {
            val result = Handshake.respond(peerTransport, peerIdentity)
            SecureSession(peerTransport, result.sessionKeys, isInitiator = false)
        }

        manager.connect(peer)
        runCurrent()
        val securePeer = peerSession.await()
        var acknowledge = true
        val receivedHeartbeats = mutableListOf<Long>()
        val peerLoop = backgroundScope.launch {
            runCatching {
                while (true) {
                    val envelope = ProtocolCodec.decode(securePeer.receive())
                    val heartbeat = envelope.payload as? Heartbeat ?: continue
                    receivedHeartbeats += heartbeat.sentAtMs
                    if (acknowledge) {
                        securePeer.send(
                            ProtocolCodec.encode(
                                Envelope(
                                    ProtocolCodec.PROTOCOL_VERSION,
                                    ChannelId.CONTROL,
                                    envelope.seq,
                                    HeartbeatAck(heartbeat.sentAtMs),
                                ),
                            ),
                        )
                    }
                }
            }
        }

        advanceTimeBy(100)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(100L, 200L), receivedHeartbeats)
        assertIs<ConnectionState.Connected>(manager.state.value)

        acknowledge = false
        advanceTimeBy(100)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertIs<ConnectionState.Connected>(manager.state.value)
        advanceTimeBy(100)
        runCurrent()

        assertIs<ConnectionState.Connecting>(manager.state.value)
        assertEquals(1, factory.calls, "reconnect should honor its initial backoff")
        peerLoop.cancelAndJoin()
        manager.disconnect()
    }

    @Test
    fun keepaliveTrafficIsExemptFromControlRateLimit() = runTest {
        val identityA = Identity.generate()
        val identityB = Identity.generate()
        val trustedA = trusted(identityA)
        val trustedB = trusted(identityB, "mac")
        val storeA = InMemoryTrustStore().also { it.save(trustedB) }
        val storeB = InMemoryTrustStore().also { it.save(trustedA) }
        val pairingA = PairingManager(identityA, storeA, SchedulerClock(testScheduler))
        val pairingB = PairingManager(identityB, storeB, SchedulerClock(testScheduler))
        val (transportA, transportB) = InMemoryFrameTransport.pair()
        val config = fastConfig(
            heartbeatIntervalMs = 5_000,
            heartbeatTimeoutMisses = 2,
            rpcRateLimitPerMinute = 10,
        )
        val managerA = manager(identityA, pairingA, storeA, FixedTransportFactory(transportA), config)
        val managerB = manager(identityB, pairingB, storeB, ThrowingFactory(), config)

        managerB.listen(transportB)
        managerA.connect(trustedB)
        runCurrent()
        assertIs<ConnectionState.Connected>(managerA.state.value)
        assertIs<ConnectionState.Connected>(managerB.state.value)

        advanceTimeBy(5 * 60_000L)
        runCurrent()

        assertIs<ConnectionState.Connected>(managerA.state.value)
        assertIs<ConnectionState.Connected>(managerB.state.value)
        assertEquals(0, managerA.droppedEnvelopes)
        assertEquals(0, managerB.droppedEnvelopes)

        managerA.disconnect()
        managerB.disconnect()
    }

    @Test
    fun incomingHeartbeatGetsEchoingAck() = runTest {
        val localIdentity = Identity.generate()
        val peerIdentity = Identity.generate()
        val peer = trusted(peerIdentity)
        val store = InMemoryTrustStore().also { it.save(peer) }
        val (localTransport, peerTransport) = InMemoryFrameTransport.pair()
        val manager = manager(
            localIdentity,
            PairingManager(localIdentity, store, SchedulerClock(testScheduler)),
            store,
            FixedTransportFactory(localTransport),
        )
        val peerSession = backgroundScope.async {
            val result = Handshake.respond(peerTransport, peerIdentity)
            SecureSession(peerTransport, result.sessionKeys, isInitiator = false)
        }

        manager.connect(peer)
        runCurrent()
        val securePeer = peerSession.await()
        securePeer.send(
            ProtocolCodec.encode(
                Envelope(ProtocolCodec.PROTOCOL_VERSION, ChannelId.CONTROL, 44, Heartbeat(12_345)),
            ),
        )
        runCurrent()

        val ack = ProtocolCodec.decode(securePeer.receive())
        assertEquals(12_345, assertIs<HeartbeatAck>(ack.payload).sentAtMs)
        manager.disconnect()
    }

    @Test
    fun revocationClosesConnectedPeerWithoutReconnect() = runTest {
        val localIdentity = Identity.generate()
        val peerIdentity = Identity.generate()
        val peer = trusted(peerIdentity)
        val store = InMemoryTrustStore().also { it.save(peer) }
        val pairing = PairingManager(localIdentity, store, SchedulerClock(testScheduler))
        val (localTransport, peerTransport) = InMemoryFrameTransport.pair()
        val factory = FixedTransportFactory(localTransport)
        val manager = manager(localIdentity, pairing, store, factory)
        val responding = backgroundScope.launch {
            val result = Handshake.respond(peerTransport, peerIdentity)
            val session = SecureSession(peerTransport, result.sessionKeys, isInitiator = false)
            runCatching { session.receive() }
        }

        manager.connect(peer)
        runCurrent()
        assertIs<ConnectionState.Connected>(manager.state.value)

        pairing.revoke(peer.deviceId)
        runCurrent()
        assertIs<ConnectionState.Idle>(manager.state.value)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, factory.calls)
        responding.cancelAndJoin()
    }

    @Test
    fun listenRejectsUnknownPeerAndStaysIdle() = runTest {
        val localIdentity = Identity.generate()
        val unknownIdentity = Identity.generate()
        val store = InMemoryTrustStore()
        val pairing = PairingManager(localIdentity, store, SchedulerClock(testScheduler))
        val manager = manager(localIdentity, pairing, store, ThrowingFactory())
        val (listenerTransport, initiatorTransport) = InMemoryFrameTransport.pair()
        val trackedListener = CloseTrackingTransport(listenerTransport)

        manager.listen(trackedListener)
        val initiating = backgroundScope.async {
            Handshake.initiate(initiatorTransport, unknownIdentity, expectedPeerKey = localIdentity.publicKey)
        }
        runCurrent()
        initiating.await()
        runCurrent()

        assertIs<ConnectionState.Idle>(manager.state.value)
        assertTrue(trackedListener.closed)
    }

    @Test
    fun unknownMessageIsIgnoredWithoutClosingSession() = runTest {
        val localIdentity = Identity.generate()
        val peerIdentity = Identity.generate()
        val peer = trusted(peerIdentity)
        val store = InMemoryTrustStore().also { it.save(peer) }
        val (localTransport, peerTransport) = InMemoryFrameTransport.pair()
        val manager = manager(
            localIdentity,
            PairingManager(localIdentity, store, SchedulerClock(testScheduler)),
            store,
            FixedTransportFactory(localTransport),
        )
        val peerSession = backgroundScope.async {
            val result = Handshake.respond(peerTransport, peerIdentity)
            SecureSession(peerTransport, result.sessionKeys, isInitiator = false)
        }

        manager.connect(peer)
        runCurrent()
        val securePeer = peerSession.await()
        securePeer.send(unknownEnvelopeBytes())
        runCurrent()

        assertIs<ConnectionState.Connected>(manager.state.value)
        manager.disconnect()
    }

    @Test
    fun controlRateLimitDropsExcessAndKeepsSessionAlive() = runTest {
        val localIdentity = Identity.generate()
        val peerIdentity = Identity.generate()
        val peer = trusted(peerIdentity)
        val store = InMemoryTrustStore().also { it.save(peer) }
        val (localTransport, peerTransport) = InMemoryFrameTransport.pair()
        val manager = manager(
            localIdentity,
            PairingManager(localIdentity, store, SchedulerClock(testScheduler)),
            store,
            FixedTransportFactory(localTransport),
            config = fastConfig(rpcRateLimitPerMinute = 10),
        )
        var dispatched = 0
        manager.registerHandler(ChannelId.CONTROL) { dispatched++ }
        val peerSession = backgroundScope.async {
            val result = Handshake.respond(peerTransport, peerIdentity)
            SecureSession(peerTransport, result.sessionKeys, isInitiator = false)
        }

        manager.connect(peer)
        runCurrent()
        val securePeer = peerSession.await()
        repeat(30) { seq ->
            securePeer.send(
                ProtocolCodec.encode(
                    Envelope(
                        ProtocolCodec.PROTOCOL_VERSION,
                        ChannelId.CONTROL,
                        seq.toLong(),
                        RevokeDevice("remote-$seq"),
                    ),
                ),
            )
        }
        runCurrent()

        assertEquals(10, dispatched)
        assertEquals(20, manager.droppedEnvelopes)
        assertIs<ConnectionState.Connected>(manager.state.value)
        manager.disconnect()
    }

    @Test
    fun disconnectCancelsPendingReconnect() = runTest {
        val identity = Identity.generate()
        val peer = trusted(Identity.generate())
        val store = InMemoryTrustStore().also { it.save(peer) }
        val factory = ThrowingFactory()
        val manager = manager(
            identity,
            PairingManager(identity, store, SchedulerClock(testScheduler)),
            store,
            factory,
            config = fastConfig(backoffBaseMs = 100),
        )

        manager.connect(peer)
        runCurrent()
        assertEquals(1, factory.calls)
        assertIs<ConnectionState.Connecting>(manager.state.value)

        manager.disconnect()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(1, factory.calls)
        assertIs<ConnectionState.Idle>(manager.state.value)
    }

    private fun kotlinx.coroutines.test.TestScope.manager(
        identity: Identity,
        pairingManager: PairingManager,
        trustStore: InMemoryTrustStore,
        transportFactory: TransportFactory,
        config: SessionConfig = fastConfig(),
    ): SessionManager = SessionManager(
        identity = identity,
        pairingManager = pairingManager,
        trustStore = trustStore,
        transportFactory = transportFactory,
        clock = SchedulerClock(testScheduler),
        scope = backgroundScope,
        config = config,
    )

    private fun fastConfig(
        heartbeatIntervalMs: Long = 60_000,
        heartbeatTimeoutMisses: Int = 2,
        backoffBaseMs: Long = 1_000,
        backoffCapMs: Long = 60_000,
        rpcRateLimitPerMinute: Int = 10,
    ): SessionConfig = SessionConfig(
        heartbeatIntervalMs = heartbeatIntervalMs,
        heartbeatTimeoutMisses = heartbeatTimeoutMisses,
        backoffBaseMs = backoffBaseMs,
        backoffCapMs = backoffCapMs,
        backoffJitterFraction = 0.0,
        rpcRateLimitPerMinute = rpcRateLimitPerMinute,
    )

    private fun trusted(identity: Identity, platform: String = "android"): TrustedDevice = TrustedDevice(
        deviceId = identity.deviceId,
        displayName = "Peer",
        platform = platform,
        publicKey = identity.publicKey.copyOf(),
        pairedAt = 1,
        lastSeen = 1,
    )

    private fun unknownEnvelopeBytes(): ByteArray {
        val payload = ProtoBuf.encodeToByteArray(
            FuturePolymorphicMessage.serializer(),
            FuturePolymorphicMessage("message_from_the_future", FutureBody(42)),
        )
        return ProtoBuf.encodeToByteArray(
            WireEnvelope.serializer(),
            WireEnvelope(ProtocolCodec.PROTOCOL_VERSION, ChannelId.EVENT, 5, payload),
        )
    }
}

private class SchedulerClock(
    private val scheduler: TestCoroutineScheduler,
) : Clock {
    override fun nowMs(): Long = scheduler.currentTime
}

private class FixedTransportFactory(
    private val transport: FrameTransport,
) : TransportFactory {
    var calls: Int = 0
        private set

    override suspend fun connect(): FrameTransport {
        calls++
        return transport
    }
}

private class ThrowingFactory : TransportFactory {
    var calls: Int = 0
        private set

    override suspend fun connect(): FrameTransport {
        calls++
        error("network unavailable")
    }
}

private class CloseTrackingTransport(
    private val inner: FrameTransport,
) : FrameTransport {
    var closed: Boolean = false
        private set

    override suspend fun send(bytes: ByteArray) = inner.send(bytes)
    override suspend fun receive(): ByteArray = inner.receive()

    override fun close() {
        closed = true
        inner.close()
    }
}

@Serializable
private data class FuturePolymorphicMessage(
    @ProtoNumber(1) val type: String,
    @ProtoNumber(2) val value: FutureBody,
)

@Serializable
private data class FutureBody(
    @ProtoNumber(1) val value: Int,
)
