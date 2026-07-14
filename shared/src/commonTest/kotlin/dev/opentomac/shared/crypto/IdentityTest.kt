package dev.opentomac.shared.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdentityTest {

    @Test
    fun generatedDeviceIdIs32LowercaseHexChars() = runTest {
        val identity = Identity.generate()
        assertEquals(32, identity.deviceId.length)
        assertTrue(
            identity.deviceId.all { it in '0'..'9' || it in 'a'..'f' },
            "deviceId should be lowercase hex: ${identity.deviceId}",
        )
    }

    @Test
    fun deviceIdIsStableForSameKeyMaterial() = runTest {
        val identity = Identity.generate()
        val restored = Identity.fromKeys(identity.publicKey, identity.secretKey)
        assertEquals(identity.deviceId, restored.deviceId)
        assertContentEquals(identity.publicKey, restored.publicKey)
        assertContentEquals(identity.secretKey, restored.secretKey)
    }

    @Test
    fun deviceIdDiffersAcrossKeys() = runTest {
        val a = Identity.generate()
        val b = Identity.generate()
        assertNotEquals(a.deviceId, b.deviceId)
    }

    @Test
    fun generatedKeysHaveEd25519Lengths() = runTest {
        val identity = Identity.generate()
        assertEquals(32, identity.publicKey.size)
        assertEquals(64, identity.secretKey.size)
    }
}
