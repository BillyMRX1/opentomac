package dev.opentomac.shared.crypto

import dev.opentomac.shared.protocol.FrameTransport
import dev.opentomac.shared.protocol.InMemoryFrameTransport
import dev.opentomac.shared.protocol.PairAccept
import dev.opentomac.shared.protocol.PairConfirm
import dev.opentomac.shared.protocol.ProtocolCodec
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandshakeTest {

    @Test
    fun fullHandshakeDerivesMatchingKeysTranscriptAndCode() = runTest {
        val idA = Identity.generate()
        val idB = Identity.generate()
        val (ta, tb) = InMemoryFrameTransport.pair()

        val initiator = async { Handshake.initiate(ta, idA) }
        val responder = async { Handshake.respond(tb, idB) }
        val resultA = initiator.await()
        val resultB = responder.await()

        assertContentEquals(idB.publicKey, resultA.peerIdentityKey)
        assertContentEquals(idA.publicKey, resultB.peerIdentityKey)

        // A's tx key must be B's rx key and vice versa.
        assertContentEquals(resultA.sessionKeys.txKey, resultB.sessionKeys.rxKey)
        assertContentEquals(resultA.sessionKeys.rxKey, resultB.sessionKeys.txKey)
        assertEquals(32, resultA.sessionKeys.txKey.size)
        assertEquals(32, resultA.sessionKeys.rxKey.size)
        assertFalse(
            resultA.sessionKeys.txKey.contentEquals(resultA.sessionKeys.rxKey),
            "direction keys must differ",
        )

        assertContentEquals(resultA.transcriptHash, resultB.transcriptHash)
        assertEquals(32, resultA.transcriptHash.size)

        val codeA = VerificationCode.derive(idA.publicKey, idB.publicKey, resultA.transcriptHash)
        val codeB = VerificationCode.derive(idB.publicKey, idA.publicKey, resultB.transcriptHash)
        assertEquals(codeA, codeB)
        assertEquals(6, codeA.length)
        assertTrue(codeA.all { it in '0'..'9' })

        ta.close()
        tb.close()
    }

    @Test
    fun handshakeWithMatchingPairingTokenSucceeds() = runTest {
        val idA = Identity.generate()
        val idB = Identity.generate()
        val token = ByteArray(16) { (it * 7).toByte() }
        val (ta, tb) = InMemoryFrameTransport.pair()

        val initiator = async { Handshake.initiate(ta, idA, pairingToken = token) }
        val responder = async { Handshake.respond(tb, idB, pairingToken = token) }
        val resultA = initiator.await()
        val resultB = responder.await()

        assertContentEquals(resultA.transcriptHash, resultB.transcriptHash)
        assertContentEquals(resultA.sessionKeys.txKey, resultB.sessionKeys.rxKey)

        ta.close()
        tb.close()
    }

    @Test
    fun mismatchedPairingTokenFailsAtResponder() = runTest {
        val idA = Identity.generate()
        val idB = Identity.generate()
        val (ta, tb) = InMemoryFrameTransport.pair()

        val initiator = async {
            runCatching { Handshake.initiate(ta, idA, pairingToken = ByteArray(16) { 1 }) }
        }
        val failure = assertFailsWith<CryptoException> {
            Handshake.respond(tb, idB, pairingToken = ByteArray(16) { 2 })
        }
        assertTrue(failure.message!!.isNotBlank())

        ta.close()
        tb.close()
        initiator.await()
    }

    @Test
    fun tamperedResponderSignatureFailsAtInitiator() = runTest {
        val idA = Identity.generate()
        val idB = Identity.generate()
        val (ta, tb) = InMemoryFrameTransport.pair()

        // Man-in-the-middle view of the initiator's endpoint: flip one bit of the
        // responder's signature inside PairAccept before the initiator sees it.
        val tampering = InterceptingTransport(ta) { bytes ->
            val envelope = ProtocolCodec.decode(bytes)
            val payload = envelope.payload
            if (payload is PairAccept) {
                val badSignature = payload.signature.copyOf()
                badSignature[0] = (badSignature[0].toInt() xor 0x01).toByte()
                ProtocolCodec.encode(
                    envelope.copy(payload = payload.copy(signature = badSignature)),
                )
            } else {
                bytes
            }
        }

        val responder = async { runCatching { Handshake.respond(tb, idB) } }
        assertFailsWith<CryptoException> { Handshake.initiate(tampering, idA) }

        ta.close()
        tb.close()
        responder.await()
    }

    @Test
    fun reflectedResponderSignatureFailsAtResponder() = runTest {
        val idA = Identity.generate()
        val idB = Identity.generate()
        val (ta, tb) = InMemoryFrameTransport.pair()

        // Signature-reflection MITM: capture the responder's own frame2 signature and
        // identity key, then present them back to the responder inside frame3. Without
        // domain-separated signing transcripts the responder's signature would verify
        // as an initiator signature.
        var capturedAccept: PairAccept? = null
        val mitm = object : FrameTransport {
            override suspend fun send(bytes: ByteArray) {
                val envelope = ProtocolCodec.decode(bytes)
                (envelope.payload as? PairAccept)?.let { capturedAccept = it }
                tb.send(bytes)
            }

            override suspend fun receive(): ByteArray {
                val bytes = tb.receive()
                val envelope = ProtocolCodec.decode(bytes)
                if (envelope.payload !is PairConfirm) return bytes
                val accept = capturedAccept ?: return bytes
                return ProtocolCodec.encode(
                    envelope.copy(
                        payload = PairConfirm(signature = accept.signature, publicKey = accept.publicKey),
                    ),
                )
            }

            override fun close() = tb.close()
        }

        val initiator = async { runCatching { Handshake.initiate(ta, idA) } }
        assertFailsWith<CryptoException> { Handshake.respond(mitm, idB) }

        ta.close()
        tb.close()
        initiator.await()
    }

    @Test
    fun initiatorPinnedKeyMismatchFails() = runTest {
        val idA = Identity.generate()
        val idB = Identity.generate()
        val pinnedOther = Identity.generate().publicKey
        val (ta, tb) = InMemoryFrameTransport.pair()

        val responder = async { runCatching { Handshake.respond(tb, idB) } }
        assertFailsWith<CryptoException> {
            Handshake.initiate(ta, idA, expectedPeerKey = pinnedOther)
        }

        ta.close()
        tb.close()
        responder.await()
    }

    @Test
    fun responderPinnedKeyMismatchFails() = runTest {
        val idA = Identity.generate()
        val idB = Identity.generate()
        val pinnedOther = Identity.generate().publicKey
        val (ta, tb) = InMemoryFrameTransport.pair()

        val initiator = async { runCatching { Handshake.initiate(ta, idA) } }
        assertFailsWith<CryptoException> {
            Handshake.respond(tb, idB, expectedPeerKey = pinnedOther)
        }

        ta.close()
        tb.close()
        initiator.await()
    }

    @Test
    fun pinnedKeysMatchingBothSidesSucceeds() = runTest {
        val idA = Identity.generate()
        val idB = Identity.generate()
        val (ta, tb) = InMemoryFrameTransport.pair()

        val initiator = async { Handshake.initiate(ta, idA, expectedPeerKey = idB.publicKey) }
        val responder = async { Handshake.respond(tb, idB, expectedPeerKey = idA.publicKey) }
        val resultA = initiator.await()
        val resultB = responder.await()

        assertContentEquals(resultA.sessionKeys.txKey, resultB.sessionKeys.rxKey)
        assertContentEquals(resultA.sessionKeys.rxKey, resultB.sessionKeys.txKey)

        ta.close()
        tb.close()
    }
}

/** Test transport wrapper that can rewrite frames on receive. */
internal class InterceptingTransport(
    private val inner: FrameTransport,
    private val onReceive: (ByteArray) -> ByteArray,
) : FrameTransport {
    override suspend fun send(bytes: ByteArray) = inner.send(bytes)
    override suspend fun receive(): ByteArray = onReceive(inner.receive())
    override fun close() = inner.close()
}
