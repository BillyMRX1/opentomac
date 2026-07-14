package dev.opentomac.shared.pairing

import dev.opentomac.shared.protocol.ProtocolCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PairingTokenTest {

    @Test
    fun generatedTokenHasSixteenRandomBytesAndTwoMinuteLifetime() = runTest {
        val clock = FakeClock(1_000)
        val first = PairingToken.generate(clock)
        val second = PairingToken.generate(clock)

        assertEquals(16, first.bytes.size)
        assertEquals(1_000, first.createdAtMs)
        assertEquals(121_000, first.expiresAtMs)
        assertFalse(first.consumed)
        assertFalse(first.bytes.contentEquals(second.bytes), "independent tokens should differ")
    }

    @Test
    fun tokenExpiresAtItsExpiryAndCanOnlyBeConsumedOnce() = runTest {
        val clock = FakeClock(5_000)
        val token = PairingToken.generate(clock)

        clock.now = token.expiresAtMs - 1
        assertFalse(token.isExpired(clock))
        clock.now = token.expiresAtMs
        assertTrue(token.isExpired(clock))

        assertTrue(token.consume())
        assertTrue(token.consumed)
        assertFalse(token.consume())
    }

    @Test
    fun pairingPayloadProtoAndBase64RoundTrip() {
        val payload = PairingPayload(
            version = ProtocolCodec.PROTOCOL_VERSION,
            publicKey = ByteArray(32) { it.toByte() },
            addresses = listOf("192.168.1.4:24800", "phone.local:24800"),
            token = ByteArray(16) { (it * 7).toByte() },
        )

        val encoded = payload.encode()
        val protoDecoded = PairingPayload.decode(encoded)
        val base64 = payload.toBase64()
        val base64Decoded = PairingPayload.fromBase64(base64)

        assertEquals(payload, protoDecoded)
        assertEquals(payload, base64Decoded)
        assertContentEquals(payload.publicKey, base64Decoded.publicKey)
        assertContentEquals(payload.token, base64Decoded.token)
        assertNotEquals(payload.token.toString(), base64)
    }
}

internal class FakeClock(var now: Long = 0) : Clock {
    override fun nowMs(): Long = now
}
