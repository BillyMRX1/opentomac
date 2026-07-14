@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.opentomac.shared.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.generichash.GenericHash
import com.ionspin.kotlin.crypto.signature.Signature
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thrown for any cryptographic failure: signature verification, pinned-key mismatch,
 * pairing-token mismatch, malformed key material, or AEAD decryption/authentication
 * failure (SEC-004).
 */
class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

private val libsodiumInitMutex = Mutex()

/**
 * Awaits libsodium initialization exactly once; safe to call from concurrent coroutines.
 * Every suspend entry point into the crypto package calls this before touching libsodium.
 */
internal suspend fun ensureLibsodiumInitialized() {
    if (LibsodiumInitializer.isInitialized()) return
    libsodiumInitMutex.withLock {
        if (!LibsodiumInitializer.isInitialized()) {
            LibsodiumInitializer.initialize()
        }
    }
}

/** Hex-encodes [bytes] as lowercase. */
internal fun ByteArray.toHex(): String = joinToString("") { byte ->
    byte.toUByte().toString(16).padStart(2, '0')
}

/**
 * A device's long-term Ed25519 identity. [deviceId] is the lowercase hex encoding of
 * BLAKE2b-16 of the public key, so it is stable for the lifetime of the key material.
 *
 * Key material is created via [generate] or restored from platform storage via
 * [fromKeys]; both are suspend so libsodium initialization can be awaited.
 */
class Identity private constructor(
    val publicKey: ByteArray,
    val secretKey: ByteArray,
    val deviceId: String,
) {
    companion object {
        private const val ED25519_PUBLIC_KEY_BYTES = 32
        private const val ED25519_SECRET_KEY_BYTES = 64
        private const val DEVICE_ID_HASH_BYTES = 16

        /** Generates a fresh Ed25519 keypair. */
        suspend fun generate(): Identity {
            ensureLibsodiumInitialized()
            val keyPair = Signature.keypair()
            return build(keyPair.publicKey.toByteArray(), keyPair.secretKey.toByteArray())
        }

        /** Restores an identity from stored key material (platform storage arrives later). */
        suspend fun fromKeys(publicKey: ByteArray, secretKey: ByteArray): Identity {
            ensureLibsodiumInitialized()
            if (publicKey.size != ED25519_PUBLIC_KEY_BYTES) {
                throw CryptoException(
                    "Ed25519 public key must be $ED25519_PUBLIC_KEY_BYTES bytes, got ${publicKey.size}",
                )
            }
            if (secretKey.size != ED25519_SECRET_KEY_BYTES) {
                throw CryptoException(
                    "Ed25519 secret key must be $ED25519_SECRET_KEY_BYTES bytes, got ${secretKey.size}",
                )
            }
            return build(publicKey.copyOf(), secretKey.copyOf())
        }

        private fun build(publicKey: ByteArray, secretKey: ByteArray): Identity {
            val idHash = GenericHash.genericHash(
                message = publicKey.toUByteArray(),
                requestedHashLength = DEVICE_ID_HASH_BYTES,
            )
            return Identity(publicKey, secretKey, idHash.toByteArray().toHex())
        }
    }
}
