package dev.opentomac.shared.protocol

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import okio.BufferedSink
import okio.BufferedSource
import okio.EOFException

/**
 * Length-prefixed framing: 4-byte big-endian unsigned-ish length followed by that many
 * payload bytes. Both directions enforce [MAX_FRAME_BYTES] (SEC-009 oversized-payload
 * defense): the sender refuses to emit an oversized frame, and the receiver rejects an
 * oversized declared length before reading the payload.
 */
object FrameCodec {
    const val MAX_FRAME_BYTES: Int = 4 * 1024 * 1024

    /**
     * Throws [ProtocolException] if a frame of [byteCount] bytes exceeds [MAX_FRAME_BYTES].
     * Every frame-sending path (codec, in-memory transport, network transports) must call
     * this before emitting a frame.
     */
    fun checkSendSize(byteCount: Int) {
        if (byteCount > MAX_FRAME_BYTES) {
            throw ProtocolException(
                "Refusing to send frame of $byteCount bytes; limit is $MAX_FRAME_BYTES bytes",
            )
        }
    }

    /** Validates a peer-declared frame length for every receiving transport. */
    internal fun checkReceiveSize(byteCount: Int) {
        if (byteCount < 0 || byteCount > MAX_FRAME_BYTES) {
            throw ProtocolException(
                "Rejecting frame with declared length $byteCount; must be between 0 and $MAX_FRAME_BYTES bytes",
            )
        }
    }

    /**
     * Writes one frame to [sink]. Throws [ProtocolException] if [bytes] exceeds [MAX_FRAME_BYTES].
     * The caller is responsible for flushing [sink]; this method only buffers the frame.
     */
    fun writeFrame(sink: BufferedSink, bytes: ByteArray) {
        checkSendSize(bytes.size)
        sink.writeInt(bytes.size)
        sink.write(bytes)
    }

    /**
     * Reads one frame from [source]. Throws [ProtocolException] if the declared length is
     * negative or exceeds [MAX_FRAME_BYTES], or if the stream ends mid-frame (truncation).
     */
    fun readFrame(source: BufferedSource): ByteArray {
        val length = try {
            source.readInt()
        } catch (e: EOFException) {
            throw ProtocolException("Truncated frame: stream ended inside the 4-byte length prefix", e)
        }
        checkReceiveSize(length)
        return try {
            source.readByteArray(length.toLong())
        } catch (e: EOFException) {
            throw ProtocolException("Truncated frame: declared $length payload bytes but stream ended early", e)
        }
    }
}

/** A bidirectional transport that delivers whole frames. */
interface FrameTransport {
    /** Sends one frame. Throws [ProtocolException] if the frame is oversized or the transport is closed. */
    suspend fun send(bytes: ByteArray)

    /** Receives the next frame in order. Throws [ProtocolException] once the peer has closed and no frames remain. */
    suspend fun receive(): ByteArray

    fun close()
}

/**
 * In-memory [FrameTransport] pair backed by coroutine [Channel]s, for tests and
 * loopback wiring. Frames are delivered in order in both directions.
 */
class InMemoryFrameTransport private constructor(
    private val incoming: Channel<ByteArray>,
    private val outgoing: Channel<ByteArray>,
) : FrameTransport {

    override suspend fun send(bytes: ByteArray) {
        FrameCodec.checkSendSize(bytes.size)
        try {
            outgoing.send(bytes)
        } catch (e: ClosedSendChannelException) {
            throw ProtocolException("Cannot send: transport is closed", e)
        }
    }

    override suspend fun receive(): ByteArray = try {
        incoming.receive()
    } catch (e: ClosedReceiveChannelException) {
        throw ProtocolException("Cannot receive: transport is closed", e)
    }

    override fun close() {
        outgoing.close()
        incoming.close()
    }

    companion object {
        /** Returns two connected endpoints: frames sent on one are received on the other. */
        fun pair(): Pair<FrameTransport, FrameTransport> {
            val aToB = Channel<ByteArray>(Channel.UNLIMITED)
            val bToA = Channel<ByteArray>(Channel.UNLIMITED)
            val a = InMemoryFrameTransport(incoming = bToA, outgoing = aToB)
            val b = InMemoryFrameTransport(incoming = aToB, outgoing = bToA)
            return a to b
        }
    }
}
