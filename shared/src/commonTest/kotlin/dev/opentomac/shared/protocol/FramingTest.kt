package dev.opentomac.shared.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FramingTest {

    @Test
    fun writeThenReadFrameRoundTrips() {
        val buffer = Buffer()
        val payload = ByteArray(1000) { (it % 256).toByte() }
        FrameCodec.writeFrame(buffer, payload)
        val read = FrameCodec.readFrame(buffer)
        assertContentEquals(payload, read)
        assertEquals(0, buffer.size, "buffer should be fully consumed")
    }

    @Test
    fun multipleFramesReadInOrder() {
        val buffer = Buffer()
        val frames = listOf(byteArrayOf(1), byteArrayOf(2, 2), byteArrayOf(3, 3, 3), ByteArray(0))
        frames.forEach { FrameCodec.writeFrame(buffer, it) }
        frames.forEach { assertContentEquals(it, FrameCodec.readFrame(buffer)) }
    }

    @Test
    fun lengthPrefixIsFourByteBigEndian() {
        val buffer = Buffer()
        FrameCodec.writeFrame(buffer, byteArrayOf(0x41, 0x42, 0x43))
        val raw = buffer.readByteArray()
        assertContentEquals(byteArrayOf(0, 0, 0, 3), raw.copyOfRange(0, 4))
        assertContentEquals(byteArrayOf(0x41, 0x42, 0x43), raw.copyOfRange(4, 7))
    }

    @Test
    fun senderRefusesOversizedFrame() {
        val buffer = Buffer()
        val oversized = ByteArray(FrameCodec.MAX_FRAME_BYTES + 1)
        val e = assertFailsWith<ProtocolException> { FrameCodec.writeFrame(buffer, oversized) }
        assertTrue(e.message!!.contains("${FrameCodec.MAX_FRAME_BYTES}"), "message should mention limit: ${e.message}")
        assertEquals(0, buffer.size, "nothing should be written for a refused frame")
    }

    @Test
    fun maxSizeFrameIsAllowed() {
        val buffer = Buffer()
        val payload = ByteArray(FrameCodec.MAX_FRAME_BYTES)
        FrameCodec.writeFrame(buffer, payload)
        assertEquals(payload.size, FrameCodec.readFrame(buffer).size)
    }

    @Test
    fun receiverRejectsOversizedDeclaredLength() {
        val buffer = Buffer()
        buffer.writeInt(FrameCodec.MAX_FRAME_BYTES + 1)
        buffer.write(ByteArray(16))
        assertFailsWith<ProtocolException> { FrameCodec.readFrame(buffer) }
    }

    @Test
    fun receiverRejectsNegativeDeclaredLength() {
        val buffer = Buffer()
        buffer.writeInt(-1)
        assertFailsWith<ProtocolException> { FrameCodec.readFrame(buffer) }
    }

    @Test
    fun truncatedPayloadFailsWithProtocolException() {
        val buffer = Buffer()
        buffer.writeInt(100)
        buffer.write(ByteArray(10)) // only 10 of the declared 100 bytes
        val e = assertFailsWith<ProtocolException> { FrameCodec.readFrame(buffer) }
        assertTrue(e.message!!.isNotBlank())
    }

    @Test
    fun truncatedLengthPrefixFailsWithProtocolException() {
        val buffer = Buffer()
        buffer.write(byteArrayOf(0, 0)) // only 2 of 4 length bytes
        assertFailsWith<ProtocolException> { FrameCodec.readFrame(buffer) }
    }

    @Test
    fun inMemoryPairDeliversFramesInOrderBothDirections() = runTest {
        val (a, b) = InMemoryFrameTransport.pair()
        val fromA = listOf(byteArrayOf(1), byteArrayOf(2, 2), byteArrayOf(3, 3, 3))
        val fromB = listOf(byteArrayOf(9, 9), byteArrayOf(8))

        val receivedAtB = async { List(fromA.size) { b.receive() } }
        fromA.forEach { a.send(it) }
        receivedAtB.await().forEachIndexed { i, bytes -> assertContentEquals(fromA[i], bytes) }

        val receivedAtA = async { List(fromB.size) { a.receive() } }
        fromB.forEach { b.send(it) }
        receivedAtA.await().forEachIndexed { i, bytes -> assertContentEquals(fromB[i], bytes) }

        a.close()
        b.close()
    }

    @Test
    fun inMemoryTransportCarriesEncodedEnvelopes() = runTest {
        val (a, b) = InMemoryFrameTransport.pair()
        val envelope = Envelope(ProtocolCodec.PROTOCOL_VERSION, ChannelId.CONTROL, 1, Heartbeat(sentAtMs = 123))
        a.send(ProtocolCodec.encode(envelope))
        val decoded = ProtocolCodec.decode(b.receive())
        assertEquals(envelope, decoded)
        a.close()
        b.close()
    }

    @Test
    fun inMemoryTransportRefusesOversizedSend() = runTest {
        val (a, b) = InMemoryFrameTransport.pair()
        assertFailsWith<ProtocolException> { a.send(ByteArray(FrameCodec.MAX_FRAME_BYTES + 1)) }
        a.close()
        b.close()
    }

    @Test
    fun receiveAfterPeerCloseFailsWithProtocolException() = runTest {
        val (a, b) = InMemoryFrameTransport.pair()
        a.close()
        assertFailsWith<ProtocolException> { b.receive() }
        b.close()
    }
}
