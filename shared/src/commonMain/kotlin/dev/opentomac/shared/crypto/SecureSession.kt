@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.opentomac.shared.crypto

import com.ionspin.kotlin.crypto.aead.AeadCorrupedOrTamperedDataException
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import dev.opentomac.shared.protocol.FrameTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Encrypting wrapper around a [FrameTransport]: every frame is sealed with
 * ChaCha20-Poly1305-IETF using the direction keys from a completed [Handshake].
 * Upper layers use it as a plain [FrameTransport] and stay encryption-agnostic.
 *
 * Nonces are 12 bytes: a 4-byte direction constant followed by an 8-byte little-endian
 * monotonic counter, so the two directions can never collide and no nonce repeats
 * within a session. Each direction is guarded by a [Mutex]: the tx lock covers nonce
 * assignment, encryption, and the inner send as one atomic step, so concurrent senders
 * can never reuse a nonce and frames always hit the wire in counter order; the rx lock
 * covers receive, decryption, and the counter increment symmetrically. On any
 * decryption/authentication failure the session throws [CryptoException] and closes
 * the underlying transport (SEC-004).
 */
class SecureSession(
    private val inner: FrameTransport,
    sessionKeys: SessionKeys,
    isInitiator: Boolean,
) : FrameTransport {

    private val txKey = sessionKeys.txKey.toUByteArray()
    private val rxKey = sessionKeys.rxKey.toUByteArray()
    private val txDirection = if (isInitiator) DIRECTION_INITIATOR_TO_RESPONDER else DIRECTION_RESPONDER_TO_INITIATOR
    private val rxDirection = if (isInitiator) DIRECTION_RESPONDER_TO_INITIATOR else DIRECTION_INITIATOR_TO_RESPONDER
    private val txMutex = Mutex()
    private val rxMutex = Mutex()
    private var txCounter = 0L
    private var rxCounter = 0L

    override suspend fun send(bytes: ByteArray) {
        txMutex.withLock {
            val nonce = nonce(txDirection, txCounter)
            val ciphertext = AuthenticatedEncryptionWithAssociatedData.chaCha20Poly1305IetfEncrypt(
                message = bytes.toUByteArray(),
                associatedData = EMPTY_ASSOCIATED_DATA,
                nonce = nonce,
                key = txKey,
            ).toByteArray()
            inner.send(ciphertext)
            txCounter++
        }
    }

    override suspend fun receive(): ByteArray {
        rxMutex.withLock {
            val ciphertext = inner.receive()
            val nonce = nonce(rxDirection, rxCounter)
            val plaintext = try {
                AuthenticatedEncryptionWithAssociatedData.chaCha20Poly1305IetfDecrypt(
                    ciphertextAndTag = ciphertext.toUByteArray(),
                    associatedData = EMPTY_ASSOCIATED_DATA,
                    nonce = nonce,
                    key = rxKey,
                )
            } catch (e: AeadCorrupedOrTamperedDataException) {
                inner.close()
                throw CryptoException(
                    "Frame decryption failed at rx counter $rxCounter (${ciphertext.size} bytes); session closed",
                    e,
                )
            }
            rxCounter++
            return plaintext.toByteArray()
        }
    }

    override fun close() = inner.close()

    private fun nonce(direction: UByteArray, counter: Long): UByteArray {
        val nonce = UByteArray(NONCE_BYTES)
        direction.copyInto(nonce)
        for (i in 0 until 8) {
            nonce[DIRECTION_BYTES + i] = ((counter ushr (8 * i)) and 0xFF).toUByte()
        }
        return nonce
    }

    private companion object {
        const val NONCE_BYTES = 12
        const val DIRECTION_BYTES = 4
        val DIRECTION_INITIATOR_TO_RESPONDER = ubyteArrayOf(0x00u, 0x00u, 0x00u, 0x01u)
        val DIRECTION_RESPONDER_TO_INITIATOR = ubyteArrayOf(0x00u, 0x00u, 0x00u, 0x02u)
        val EMPTY_ASSOCIATED_DATA = UByteArray(0)
    }
}
