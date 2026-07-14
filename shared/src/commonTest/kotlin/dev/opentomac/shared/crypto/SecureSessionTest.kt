package dev.opentomac.shared.crypto

import dev.opentomac.shared.protocol.FrameTransport
import dev.opentomac.shared.protocol.InMemoryFrameTransport
import dev.opentomac.shared.protocol.ProtocolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SecureSessionTest {

    private val keyAtoB = ByteArray(32) { 1 }
    private val keyBtoA = ByteArray(32) { 2 }

    private suspend fun sessionPair(): Triple<SecureSession, SecureSession, RecordingTransport> {
        ensureLibsodiumInitialized()
        val (ta, tb) = InMemoryFrameTransport.pair()
        val recording = RecordingTransport(tb)
        val sessionA = SecureSession(ta, SessionKeys(txKey = keyAtoB, rxKey = keyBtoA), isInitiator = true)
        val sessionB = SecureSession(recording, SessionKeys(txKey = keyBtoA, rxKey = keyAtoB), isInitiator = false)
        return Triple(sessionA, sessionB, recording)
    }

    @Test
    fun roundTripEncryptsOnTheWireInBothDirections() = runTest {
        val (sessionA, sessionB, recording) = sessionPair()
        val fromA = listOf("first frame", "second frame", "third frame").map { it.encodeToByteArray() }
        val fromB = listOf("reply one", "reply two").map { it.encodeToByteArray() }

        fromA.forEachIndexed { i, plaintext ->
            sessionA.send(plaintext)
            val received = sessionB.receive()
            assertContentEquals(plaintext, received)
            val wire = recording.received[i]
            assertFalse(wire.contentEquals(plaintext), "frame $i must be encrypted on the wire")
            assertEquals(plaintext.size + 16, wire.size, "ciphertext should be plaintext + 16-byte tag")
        }
        fromB.forEach { plaintext ->
            sessionB.send(plaintext)
            assertContentEquals(plaintext, sessionA.receive())
        }

        sessionA.close()
        sessionB.close()
    }

    @Test
    fun samePlaintextTwiceProducesDifferentCiphertexts() = runTest {
        val (sessionA, sessionB, recording) = sessionPair()
        val plaintext = "same bytes".encodeToByteArray()

        sessionA.send(plaintext)
        sessionA.send(plaintext)
        assertContentEquals(plaintext, sessionB.receive())
        assertContentEquals(plaintext, sessionB.receive())

        assertFalse(
            recording.received[0].contentEquals(recording.received[1]),
            "nonce must advance so identical plaintexts encrypt differently",
        )

        sessionA.close()
        sessionB.close()
    }

    @Test
    fun concurrentSendsAllDecryptExactlyOnce() = runTest {
        ensureLibsodiumInitialized()
        val (ta, tb) = InMemoryFrameTransport.pair()
        val sessionA = SecureSession(ta, SessionKeys(txKey = keyAtoB, rxKey = keyBtoA), isInitiator = true)
        val sessionB = SecureSession(tb, SessionKeys(txKey = keyBtoA, rxKey = keyAtoB), isInitiator = false)
        val frameCount = 100

        // Real multi-threaded concurrency: without serialized nonce assignment two
        // coroutines can reuse a counter (nonce reuse) or emit frames out of counter order.
        withContext(Dispatchers.Default) {
            (0 until frameCount).map { i ->
                launch { sessionA.send("frame-$i".encodeToByteArray()) }
            }.joinAll()
        }

        val received = List(frameCount) { sessionB.receive().decodeToString() }
        assertEquals(frameCount, received.toSet().size, "every plaintext must arrive exactly once")
        assertEquals((0 until frameCount).map { "frame-$it" }.toSet(), received.toSet())

        sessionA.close()
        sessionB.close()
    }

    @Test
    fun tamperedCiphertextThrowsCryptoExceptionAndClosesSession() = runTest {
        ensureLibsodiumInitialized()
        val (ta, tb) = InMemoryFrameTransport.pair()
        val tampering = InterceptingTransport(tb) { bytes ->
            val corrupted = bytes.copyOf()
            corrupted[corrupted.lastIndex] = (corrupted[corrupted.lastIndex].toInt() xor 0x01).toByte()
            corrupted
        }
        val sessionA = SecureSession(ta, SessionKeys(txKey = keyAtoB, rxKey = keyBtoA), isInitiator = true)
        val sessionB = SecureSession(tampering, SessionKeys(txKey = keyBtoA, rxKey = keyAtoB), isInitiator = false)

        sessionA.send("payload".encodeToByteArray())
        assertFailsWith<CryptoException> { sessionB.receive() }

        // SEC-004: the session must have closed its transport after the auth failure.
        assertFailsWith<ProtocolException> { sessionB.send("after failure".encodeToByteArray()) }

        sessionA.close()
    }
}

/** Test transport wrapper that records every frame passing through receive(). */
internal class RecordingTransport(
    private val inner: FrameTransport,
) : FrameTransport {
    val received = mutableListOf<ByteArray>()

    override suspend fun send(bytes: ByteArray) = inner.send(bytes)

    override suspend fun receive(): ByteArray {
        val bytes = inner.receive()
        received += bytes
        return bytes
    }

    override fun close() = inner.close()
}
