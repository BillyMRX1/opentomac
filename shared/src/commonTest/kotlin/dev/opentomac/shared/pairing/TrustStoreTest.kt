package dev.opentomac.shared.pairing

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrustStoreTest {

    @Test
    fun inMemoryStoreSavesFindsListsAndRemovesDevices() = runTest {
        val store = InMemoryTrustStore()
        val phone = trustedDevice("phone", 1)
        val tablet = trustedDevice("tablet", 2)

        store.save(phone)
        store.save(tablet)

        assertEquals(phone, store.get(phone.deviceId))
        assertEquals(listOf(phone, tablet), store.list())
        assertEquals(tablet, store.findByPublicKey(tablet.publicKey))
        assertNull(store.findByPublicKey(ByteArray(32) { 9 }))

        store.remove(phone.deviceId)
        assertNull(store.get(phone.deviceId))
        assertEquals(listOf(tablet), store.list())
    }

    @Test
    fun savingSameDeviceIdReplacesTheRecord() = runTest {
        val store = InMemoryTrustStore()
        val original = trustedDevice("phone", 1)
        val renamed = original.copy(displayName = "Renamed phone", lastSeen = 500)

        store.save(original)
        store.save(renamed)

        assertEquals(listOf(renamed), store.list())
    }

    @Test
    fun trustedDeviceUsesByteArrayContentEquality() {
        val first = trustedDevice("phone", 1)
        val same = first.copy(publicKey = first.publicKey.copyOf())

        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
    }

    @Test
    fun persistentStoreRoundTripsRecordsAndUsesTrustedPrefix() = runTest {
        val kv = FakeKeyValueStore()
        val store = PersistentTrustStore(kv)
        val device = trustedDevice("phone", 3)

        store.save(device)

        assertEquals(listOf("trusted/${device.deviceId}"), kv.keys())
        val restored = PersistentTrustStore(kv).get(device.deviceId)
        assertEquals(device, restored)
        assertContentEquals(device.publicKey, restored!!.publicKey)
        assertEquals(device, store.findByPublicKey(device.publicKey))

        store.remove(device.deviceId)
        assertNull(store.get(device.deviceId))
    }

    private fun trustedDevice(id: String, keyByte: Byte): TrustedDevice = TrustedDevice(
        deviceId = id,
        displayName = "Device $id",
        platform = "test",
        publicKey = ByteArray(32) { keyByte },
        pairedAt = 100,
        lastSeen = 200,
    )
}

private class FakeKeyValueStore : KeyValueStore {
    private val values = linkedMapOf<String, ByteArray>()

    override suspend fun put(key: String, value: ByteArray) {
        values[key] = value.copyOf()
    }

    override suspend fun get(key: String): ByteArray? = values[key]?.copyOf()

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    override suspend fun keys(): List<String> = values.keys.toList()
}
