@file:OptIn(ExperimentalEncodingApi::class, ExperimentalSerializationApi::class, ExperimentalUnsignedTypes::class)

package dev.opentomac.shared.pairing

import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import dev.opentomac.shared.crypto.ensureLibsodiumInitialized
import dev.opentomac.shared.protocol.ProtocolException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.TimeSource

/** Millisecond clock injected into pairing state for deterministic expiry and timestamps. */
interface Clock {
    fun nowMs(): Long
}

/**
 * Process-relative monotonic clock for the MVP. Values start near zero and are suitable
 * for token expiry; platforms can inject a wall clock when persisted timestamps need it.
 */
object SystemClock : Clock {
    private val origin = TimeSource.Monotonic.markNow()

    override fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds
}

/** A random, single-use pairing secret valid for two minutes (PAIR-001). */
class PairingToken private constructor(
    val bytes: ByteArray,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    consumed: Boolean = false,
) {
    var consumed: Boolean = consumed
        private set

    fun isExpired(clock: Clock = SystemClock): Boolean = clock.nowMs() >= expiresAtMs

    /** Marks the token consumed and returns false when a prior attempt already consumed it. */
    fun consume(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingToken) return false
        return bytes.contentEquals(other.bytes) &&
            createdAtMs == other.createdAtMs &&
            expiresAtMs == other.expiresAtMs &&
            consumed == other.consumed
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + createdAtMs.hashCode()
        result = 31 * result + expiresAtMs.hashCode()
        result = 31 * result + consumed.hashCode()
        return result
    }

    companion object {
        const val TOKEN_BYTES: Int = 16
        const val VALIDITY_MS: Long = 120_000

        /** Generates a fresh 16-byte token using libsodium's CSPRNG. */
        suspend fun generate(clock: Clock = SystemClock): PairingToken {
            ensureLibsodiumInitialized()
            val createdAt = clock.nowMs()
            return PairingToken(
                bytes = LibsodiumRandom.buf(TOKEN_BYTES).toByteArray(),
                createdAtMs = createdAt,
                expiresAtMs = createdAt + VALIDITY_MS,
            )
        }
    }
}

/** QR payload advertising a host identity, addresses, and one-time pairing token. */
@Serializable
data class PairingPayload(
    @ProtoNumber(1) val version: Int,
    @ProtoNumber(2) val publicKey: ByteArray,
    @ProtoNumber(3) val addresses: List<String>,
    @ProtoNumber(4) val token: ByteArray,
) {
    fun encode(): ByteArray = ProtoBuf.encodeToByteArray(serializer(), this)

    fun toBase64(): String = Base64.encode(encode())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingPayload) return false
        return version == other.version &&
            publicKey.contentEquals(other.publicKey) &&
            addresses == other.addresses &&
            token.contentEquals(other.token)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + addresses.hashCode()
        result = 31 * result + token.contentHashCode()
        return result
    }

    companion object {
        fun decode(bytes: ByteArray): PairingPayload = try {
            ProtoBuf.decodeFromByteArray(serializer(), bytes)
        } catch (e: SerializationException) {
            throw ProtocolException("Malformed pairing payload (${bytes.size} bytes): ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("Malformed pairing payload (${bytes.size} bytes): ${e.message}", e)
        }

        fun fromBase64(value: String): PairingPayload {
            val bytes = try {
                Base64.decode(value)
            } catch (e: IllegalArgumentException) {
                throw ProtocolException(
                    "Malformed base64 pairing payload (${value.length} characters): ${e.message}",
                    e,
                )
            }
            return decode(bytes)
        }
    }
}
