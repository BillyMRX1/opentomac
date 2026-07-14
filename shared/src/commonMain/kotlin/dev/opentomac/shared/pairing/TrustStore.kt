@file:OptIn(ExperimentalSerializationApi::class)

package dev.opentomac.shared.pairing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/** A paired peer whose long-term identity key is trusted locally. */
data class TrustedDevice(
    val deviceId: String,
    val displayName: String,
    val platform: String,
    val publicKey: ByteArray,
    val pairedAt: Long,
    val lastSeen: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedDevice) return false
        return deviceId == other.deviceId &&
            displayName == other.displayName &&
            platform == other.platform &&
            publicKey.contentEquals(other.publicKey) &&
            pairedAt == other.pairedAt &&
            lastSeen == other.lastSeen
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + platform.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + pairedAt.hashCode()
        result = 31 * result + lastSeen.hashCode()
        return result
    }
}

/** Storage for locally trusted peer identities. */
interface TrustStore {
    suspend fun save(device: TrustedDevice)
    suspend fun get(deviceId: String): TrustedDevice?
    suspend fun list(): List<TrustedDevice>
    suspend fun remove(deviceId: String)
    suspend fun findByPublicKey(publicKey: ByteArray): TrustedDevice?
}

/**
 * Mutex-protected trust store used until platform persistence is wired and for
 * deterministic tests.
 */
class InMemoryTrustStore : TrustStore {
    private val mutex = Mutex()
    private val devices = linkedMapOf<String, TrustedDevice>()

    override suspend fun save(device: TrustedDevice) {
        mutex.withLock {
            devices[device.deviceId] = device.snapshot()
        }
    }

    override suspend fun get(deviceId: String): TrustedDevice? = mutex.withLock {
        devices[deviceId]?.snapshot()
    }

    override suspend fun list(): List<TrustedDevice> = mutex.withLock {
        devices.values.map(TrustedDevice::snapshot)
    }

    override suspend fun remove(deviceId: String) {
        mutex.withLock {
            devices.remove(deviceId)
        }
    }

    override suspend fun findByPublicKey(publicKey: ByteArray): TrustedDevice? = mutex.withLock {
        devices.values.firstOrNull { it.publicKey.contentEquals(publicKey) }?.snapshot()
    }
}

/** Minimal byte-oriented persistence primitive supplied by each platform later. */
interface KeyValueStore {
    suspend fun put(key: String, value: ByteArray)
    suspend fun get(key: String): ByteArray?
    suspend fun remove(key: String)
    suspend fun keys(): List<String>
}

/** ProtoBuf-backed trust store persisted under stable `trusted/<deviceId>` keys. */
class PersistentTrustStore(
    private val kv: KeyValueStore,
) : TrustStore {

    override suspend fun save(device: TrustedDevice) {
        val record = TrustedDeviceRecord.from(device)
        kv.put(key(device.deviceId), ProtoBuf.encodeToByteArray(TrustedDeviceRecord.serializer(), record))
    }

    override suspend fun get(deviceId: String): TrustedDevice? {
        val bytes = kv.get(key(deviceId)) ?: return null
        return try {
            ProtoBuf.decodeFromByteArray(TrustedDeviceRecord.serializer(), bytes).toTrustedDevice()
        } catch (e: SerializationException) {
            throw IllegalStateException(
                "Malformed trusted-device record for '$deviceId' (${bytes.size} bytes): ${e.message}",
                e,
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "Malformed trusted-device record for '$deviceId' (${bytes.size} bytes): ${e.message}",
                e,
            )
        }
    }

    override suspend fun list(): List<TrustedDevice> = kv.keys()
        .filter { it.startsWith(TRUSTED_PREFIX) }
        .sorted()
        .mapNotNull { storedKey -> get(storedKey.removePrefix(TRUSTED_PREFIX)) }

    override suspend fun remove(deviceId: String) {
        kv.remove(key(deviceId))
    }

    override suspend fun findByPublicKey(publicKey: ByteArray): TrustedDevice? =
        list().firstOrNull { it.publicKey.contentEquals(publicKey) }

    private fun key(deviceId: String): String = "$TRUSTED_PREFIX$deviceId"

    private companion object {
        const val TRUSTED_PREFIX = "trusted/"
    }
}

@Serializable
internal data class TrustedDeviceRecord(
    @ProtoNumber(1) val deviceId: String,
    @ProtoNumber(2) val displayName: String,
    @ProtoNumber(3) val platform: String,
    @ProtoNumber(4) val publicKey: ByteArray,
    @ProtoNumber(5) val pairedAt: Long,
    @ProtoNumber(6) val lastSeen: Long,
) {
    fun toTrustedDevice(): TrustedDevice = TrustedDevice(
        deviceId = deviceId,
        displayName = displayName,
        platform = platform,
        publicKey = publicKey.copyOf(),
        pairedAt = pairedAt,
        lastSeen = lastSeen,
    )

    companion object {
        fun from(device: TrustedDevice): TrustedDeviceRecord = TrustedDeviceRecord(
            deviceId = device.deviceId,
            displayName = device.displayName,
            platform = device.platform,
            publicKey = device.publicKey.copyOf(),
            pairedAt = device.pairedAt,
            lastSeen = device.lastSeen,
        )
    }
}

private fun TrustedDevice.snapshot(): TrustedDevice = copy(publicKey = publicKey.copyOf())
