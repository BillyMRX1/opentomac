@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.opentomac.shared.crypto

import com.ionspin.kotlin.crypto.generichash.GenericHash
import com.ionspin.kotlin.crypto.scalarmult.ScalarMultiplication
import com.ionspin.kotlin.crypto.signature.InvalidSignatureException
import com.ionspin.kotlin.crypto.signature.Signature
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import dev.opentomac.shared.protocol.ChannelId
import dev.opentomac.shared.protocol.Envelope
import dev.opentomac.shared.protocol.FrameTransport
import dev.opentomac.shared.protocol.Message
import dev.opentomac.shared.protocol.PairAccept
import dev.opentomac.shared.protocol.PairConfirm
import dev.opentomac.shared.protocol.PairInit
import dev.opentomac.shared.protocol.ProtocolCodec

/** Per-direction 32-byte session keys, relative to the local role. */
class SessionKeys(
    val txKey: ByteArray,
    val rxKey: ByteArray,
)

/** Outcome of a completed, mutually authenticated handshake. */
class HandshakeResult(
    val peerIdentityKey: ByteArray,
    val sessionKeys: SessionKeys,
    val transcriptHash: ByteArray,
)

/**
 * Noise-XX-flavored mutual authentication over a [FrameTransport], carried as protocol
 * messages (PairInit / PairAccept / PairConfirm) on the CONTROL channel:
 *
 * 1. Initiator -> responder: ephemeral X25519 key `eA` (plus the pairing token, if any).
 * 2. Responder -> initiator: ephemeral `eB`, its Ed25519 identity key, and a signature
 *    over the domain-separated transcript `("opentomac-resp" || eA || eB || token)`.
 * 3. Initiator verifies (against `expectedPeerKey` when pinned), then sends its own
 *    identity key and a signature over `("opentomac-init" || eA || eB || token)`; the
 *    responder verifies symmetrically. The role-specific domain prefixes prevent
 *    signature reflection: one side's signature can never verify as the other role's.
 *
 * The session secret is X25519 DH between the ephemerals; the two 32-byte direction
 * keys are a keyed BLAKE2b-64 of the transcript hash (key = DH secret) split in half.
 * The transcript hash is BLAKE2b-32 over the three encoded wire frames in order.
 *
 * During pairing `expectedPeerKey` is null and the user confirms the peer afterwards
 * via [VerificationCode]; on reconnect the caller passes the pinned peer key and the
 * handshake fails with [CryptoException] if the peer presents a different identity.
 */
object Handshake {
    private const val X25519_KEY_BYTES = 32
    private const val ED25519_PUBLIC_KEY_BYTES = 32
    private const val ED25519_SIGNATURE_BYTES = 64
    private const val TRANSCRIPT_HASH_BYTES = 32
    private const val SESSION_KEY_BYTES = 32

    /** Role-specific signing domains so a responder signature never verifies as an initiator's. */
    private val INITIATOR_SIGNATURE_DOMAIN = "opentomac-init".encodeToByteArray()
    private val RESPONDER_SIGNATURE_DOMAIN = "opentomac-resp".encodeToByteArray()

    suspend fun initiate(
        transport: FrameTransport,
        identity: Identity,
        pairingToken: ByteArray? = null,
        expectedPeerKey: ByteArray? = null,
    ): HandshakeResult {
        ensureLibsodiumInitialized()
        val ephemeralSecret = LibsodiumRandom.buf(X25519_KEY_BYTES)
        val ephemeralPublic = ScalarMultiplication.scalarMultiplicationBase(ephemeralSecret).toByteArray()
        val token = pairingToken ?: ByteArray(0)

        val frame1 = encode(PairInit(token = token, publicKey = ephemeralPublic), seq = 0)
        transport.send(frame1)

        val frame2 = transport.receive()
        val accept = decode<PairAccept>(frame2, "PairAccept")
        val peerEphemeral = requireLength(accept.ephemeralKey, X25519_KEY_BYTES, "responder ephemeral key")
        val peerIdentityKey = requireLength(accept.publicKey, ED25519_PUBLIC_KEY_BYTES, "responder identity key")
        requireLength(accept.signature, ED25519_SIGNATURE_BYTES, "responder signature")
        checkPinnedKey(expectedPeerKey, peerIdentityKey, "responder")

        val coreTranscript = ephemeralPublic + peerEphemeral + token
        verifySignature(
            accept.signature,
            RESPONDER_SIGNATURE_DOMAIN + coreTranscript,
            peerIdentityKey,
            "responder",
        )

        val mySignature = Signature.detached(
            (INITIATOR_SIGNATURE_DOMAIN + coreTranscript).toUByteArray(),
            identity.secretKey.toUByteArray(),
        ).toByteArray()
        val frame3 = encode(PairConfirm(signature = mySignature, publicKey = identity.publicKey), seq = 1)
        transport.send(frame3)

        val transcriptHash = transcriptHash(frame1, frame2, frame3)
        val (initiatorToResponder, responderToInitiator) =
            deriveDirectionKeys(ephemeralSecret, peerEphemeral.toUByteArray(), transcriptHash)
        return HandshakeResult(
            peerIdentityKey = peerIdentityKey,
            sessionKeys = SessionKeys(txKey = initiatorToResponder, rxKey = responderToInitiator),
            transcriptHash = transcriptHash,
        )
    }

    suspend fun respond(
        transport: FrameTransport,
        identity: Identity,
        pairingToken: ByteArray? = null,
        expectedPeerKey: ByteArray? = null,
    ): HandshakeResult {
        ensureLibsodiumInitialized()
        val frame1 = transport.receive()
        val init = decode<PairInit>(frame1, "PairInit")
        val peerEphemeral = requireLength(init.publicKey, X25519_KEY_BYTES, "initiator ephemeral key")
        if (pairingToken != null && !init.token.contentEquals(pairingToken)) {
            throw CryptoException("Pairing token mismatch: initiator presented a different token")
        }

        val ephemeralSecret = LibsodiumRandom.buf(X25519_KEY_BYTES)
        val ephemeralPublic = ScalarMultiplication.scalarMultiplicationBase(ephemeralSecret).toByteArray()

        val coreTranscript = peerEphemeral + ephemeralPublic + init.token
        val mySignature = Signature.detached(
            (RESPONDER_SIGNATURE_DOMAIN + coreTranscript).toUByteArray(),
            identity.secretKey.toUByteArray(),
        ).toByteArray()
        val frame2 = encode(
            PairAccept(publicKey = identity.publicKey, signature = mySignature, ephemeralKey = ephemeralPublic),
            seq = 0,
        )
        transport.send(frame2)

        val frame3 = transport.receive()
        val confirm = decode<PairConfirm>(frame3, "PairConfirm")
        val peerIdentityKey = requireLength(confirm.publicKey, ED25519_PUBLIC_KEY_BYTES, "initiator identity key")
        requireLength(confirm.signature, ED25519_SIGNATURE_BYTES, "initiator signature")
        checkPinnedKey(expectedPeerKey, peerIdentityKey, "initiator")
        verifySignature(
            confirm.signature,
            INITIATOR_SIGNATURE_DOMAIN + coreTranscript,
            peerIdentityKey,
            "initiator",
        )

        val transcriptHash = transcriptHash(frame1, frame2, frame3)
        val (initiatorToResponder, responderToInitiator) =
            deriveDirectionKeys(ephemeralSecret, peerEphemeral.toUByteArray(), transcriptHash)
        return HandshakeResult(
            peerIdentityKey = peerIdentityKey,
            sessionKeys = SessionKeys(txKey = responderToInitiator, rxKey = initiatorToResponder),
            transcriptHash = transcriptHash,
        )
    }

    private fun encode(message: Message, seq: Long): ByteArray =
        ProtocolCodec.encode(Envelope(ProtocolCodec.PROTOCOL_VERSION, ChannelId.CONTROL, seq, message))

    private inline fun <reified T : Message> decode(frame: ByteArray, expected: String): T {
        val payload = ProtocolCodec.decode(frame).payload
        return payload as? T
            ?: throw CryptoException("Handshake expected $expected but received ${payload::class.simpleName}")
    }

    private fun requireLength(bytes: ByteArray, expected: Int, what: String): ByteArray {
        if (bytes.size != expected) {
            throw CryptoException("Handshake $what must be $expected bytes, got ${bytes.size}")
        }
        return bytes
    }

    private fun checkPinnedKey(expectedPeerKey: ByteArray?, actual: ByteArray, role: String) {
        if (expectedPeerKey != null && !expectedPeerKey.contentEquals(actual)) {
            throw CryptoException("Pinned-key mismatch: $role identity key differs from the expected peer key")
        }
    }

    private fun verifySignature(signature: ByteArray, transcript: ByteArray, publicKey: ByteArray, role: String) {
        try {
            Signature.verifyDetached(
                signature.toUByteArray(),
                transcript.toUByteArray(),
                publicKey.toUByteArray(),
            )
        } catch (e: InvalidSignatureException) {
            throw CryptoException("Invalid $role signature over the handshake transcript", e)
        }
    }

    private fun transcriptHash(frame1: ByteArray, frame2: ByteArray, frame3: ByteArray): ByteArray =
        GenericHash.genericHash(
            message = (frame1 + frame2 + frame3).toUByteArray(),
            requestedHashLength = TRANSCRIPT_HASH_BYTES,
        ).toByteArray()

    /** Returns (initiator-to-responder key, responder-to-initiator key). */
    private fun deriveDirectionKeys(
        myEphemeralSecret: UByteArray,
        peerEphemeralPublic: UByteArray,
        transcriptHash: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val sharedSecret = ScalarMultiplication.scalarMultiplication(myEphemeralSecret, peerEphemeralPublic)
        if (sharedSecret.toByteArray().all { it == 0.toByte() }) {
            throw CryptoException("X25519 produced an all-zero shared secret (low-order peer ephemeral)")
        }
        val keyMaterial = GenericHash.genericHash(
            message = transcriptHash.toUByteArray(),
            requestedHashLength = 2 * SESSION_KEY_BYTES,
            key = sharedSecret,
        ).toByteArray()
        return keyMaterial.copyOfRange(0, SESSION_KEY_BYTES) to
            keyMaterial.copyOfRange(SESSION_KEY_BYTES, 2 * SESSION_KEY_BYTES)
    }
}
