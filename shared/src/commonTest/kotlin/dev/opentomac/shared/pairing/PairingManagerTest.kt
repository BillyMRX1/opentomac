@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.opentomac.shared.pairing

import dev.opentomac.shared.crypto.CryptoException
import dev.opentomac.shared.crypto.Identity
import dev.opentomac.shared.protocol.FrameTransport
import dev.opentomac.shared.protocol.InMemoryFrameTransport
import dev.opentomac.shared.protocol.ProtocolCodec
import dev.opentomac.shared.protocol.ProtocolException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PairingManagerTest {

    @Test
    fun fullPairingShowsMatchingCodeAndPersistsConfirmedPeers() = runTest {
        val hostIdentity = Identity.generate()
        val joinerIdentity = Identity.generate()
        val hostStore = InMemoryTrustStore()
        val joinerStore = InMemoryTrustStore()
        val host = PairingManager(hostIdentity, hostStore, FakeClock(10_000))
        val joiner = PairingManager(joinerIdentity, joinerStore, FakeClock(20_000))
        val payload = host.startHosting(listOf("mac.local:24800"))
        val (hostTransport, joinerTransport) = InMemoryFrameTransport.pair()

        val hostPending = async { host.awaitPairing(hostTransport) }
        val joinerPending = async { joiner.join(payload, joinerTransport) }
        val hostResult = hostPending.await()
        val joinerResult = joinerPending.await()

        assertEquals(hostResult.verificationCode, joinerResult.verificationCode)
        assertContentEquals(joinerIdentity.publicKey, hostResult.peerIdentityKey)
        assertContentEquals(hostIdentity.publicKey, joinerResult.peerIdentityKey)
        assertContentEquals(hostResult.sessionKeys.txKey, joinerResult.sessionKeys.rxKey)
        assertContentEquals(hostResult.transcriptHash, joinerResult.transcriptHash)

        val trustedJoiner = hostResult.confirm("Pixel", "android")
        val trustedHost = joinerResult.confirm("Mac", "macos")

        assertEquals(joinerIdentity.deviceId, trustedJoiner.deviceId)
        assertContentEquals(joinerIdentity.publicKey, trustedJoiner.publicKey)
        assertEquals(10_000, trustedJoiner.pairedAt)
        assertEquals(hostIdentity.deviceId, trustedHost.deviceId)
        assertContentEquals(hostIdentity.publicKey, trustedHost.publicKey)
        assertEquals(20_000, trustedHost.pairedAt)
        assertEquals(trustedJoiner, hostStore.get(joinerIdentity.deviceId))
        assertEquals(trustedHost, joinerStore.get(hostIdentity.deviceId))
    }

    @Test
    fun expiredTokenFailsBeforeHandshakeStarts() = runTest {
        val clock = FakeClock(0)
        val manager = PairingManager(Identity.generate(), InMemoryTrustStore(), clock)
        manager.startHosting(listOf("localhost:24800"))
        clock.now = 120_001
        val transport = FailOnUseTransport()

        assertFailsWith<CryptoException> { manager.awaitPairing(transport) }
        assertEquals(0, transport.calls)
    }

    @Test
    fun hostingTokenCannotBeUsedForASecondHandshakeAttempt() = runTest {
        val host = PairingManager(Identity.generate(), InMemoryTrustStore(), FakeClock())
        val joiner = PairingManager(Identity.generate(), InMemoryTrustStore(), FakeClock())
        val payload = host.startHosting(listOf("localhost:24800"))
        val (hostTransport, joinerTransport) = InMemoryFrameTransport.pair()

        val responding = async { host.awaitPairing(hostTransport) }
        joiner.join(payload, joinerTransport)
        responding.await()

        val unusedTransport = FailOnUseTransport()
        assertFailsWith<CryptoException> { host.awaitPairing(unusedTransport) }
        assertEquals(0, unusedTransport.calls)
    }

    @Test
    fun rejectingPendingPairingLeavesBothSidesUntrusted() = runTest {
        val hostStore = InMemoryTrustStore()
        val joinerStore = InMemoryTrustStore()
        val host = PairingManager(Identity.generate(), hostStore, FakeClock())
        val joiner = PairingManager(Identity.generate(), joinerStore, FakeClock())
        val payload = host.startHosting(listOf("localhost:24800"))
        val (hostTransport, joinerTransport) = InMemoryFrameTransport.pair()

        val responding = async { host.awaitPairing(hostTransport) }
        val joining = async { joiner.join(payload, joinerTransport) }
        responding.await().reject()
        joining.await().reject()

        assertTrue(hostStore.list().isEmpty())
        assertTrue(joinerStore.list().isEmpty())
    }

    @Test
    fun verifyPeerReturnsKnownDeviceAndRejectsUnknownOrRemovedKeys() = runTest {
        val store = InMemoryTrustStore()
        val manager = PairingManager(Identity.generate(), store, FakeClock(999))
        val knownKey = Identity.generate().publicKey
        val known = TrustedDevice(
            deviceId = "known",
            displayName = "Known device",
            platform = "android",
            publicKey = knownKey,
            pairedAt = 10,
            lastSeen = 20,
        )
        store.save(known)

        assertEquals(known, manager.verifyPeer(knownKey))
        assertEquals(20, store.get("known")!!.lastSeen)
        assertFailsWith<CryptoException> { manager.verifyPeer(Identity.generate().publicKey) }

        store.remove("known")
        assertFailsWith<CryptoException> { manager.verifyPeer(knownKey) }
    }

    @Test
    fun revokeRemovesTrustAndEmitsDeviceId() = runTest {
        val store = InMemoryTrustStore()
        val manager = PairingManager(Identity.generate(), store, FakeClock())
        val device = TrustedDevice(
            deviceId = "revoked-id",
            displayName = "Old phone",
            platform = "android",
            publicKey = Identity.generate().publicKey,
            pairedAt = 10,
            lastSeen = 20,
        )
        store.save(device)
        val emitted = backgroundScope.async { manager.revocations.first() }
        runCurrent()

        manager.revoke(device.deviceId)

        assertEquals(device.deviceId, emitted.await())
        assertEquals(null, store.get(device.deviceId))
    }

    @Test
    fun concurrentHostingAttemptIsRefused() = runTest {
        val manager = PairingManager(Identity.generate(), InMemoryTrustStore(), FakeClock())
        manager.startHosting(listOf("first.local:24800"))

        assertFailsWith<IllegalStateException> {
            manager.startHosting(listOf("second.local:24800"))
        }
    }

    @Test
    fun joinRejectsWrongProtocolVersionBeforeHandshakeStarts() = runTest {
        val manager = PairingManager(Identity.generate(), InMemoryTrustStore(), FakeClock())
        val payload = PairingPayload(
            version = ProtocolCodec.PROTOCOL_VERSION + 1,
            publicKey = Identity.generate().publicKey,
            addresses = listOf("localhost:24800"),
            token = ByteArray(16),
        )
        val transport = FailOnUseTransport()

        assertFailsWith<ProtocolException> { manager.join(payload, transport) }
        assertEquals(0, transport.calls)
    }
}

private class FailOnUseTransport : FrameTransport {
    var calls: Int = 0
        private set

    override suspend fun send(bytes: ByteArray) {
        calls++
        error("handshake must not send on this transport")
    }

    override suspend fun receive(): ByteArray {
        calls++
        error("handshake must not receive from this transport")
    }

    override fun close() = Unit
}
