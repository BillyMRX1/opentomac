package dev.opentomac.shared.transport

import dev.opentomac.shared.protocol.FrameCodec
import dev.opentomac.shared.protocol.FrameTransport
import dev.opentomac.shared.protocol.ProtocolException
import dev.opentomac.shared.session.TransportFactory
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.port
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeByteArray
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Platform dispatcher suitable for Ktor's blocking selector loop. */
internal expect val tcpSelectorDispatcher: CoroutineDispatcher

/**
 * A length-prefixed [FrameTransport] over a Ktor TCP [Socket]. When [ownedSelector]
 * is supplied, [close] also releases it; accepted server sockets share their server's
 * selector and therefore leave this argument null.
 */
class TcpFrameTransport(
    private val socket: Socket,
    private val ownedSelector: SelectorManager? = null,
) : FrameTransport {
    private val input: ByteReadChannel = socket.openReadChannel()
    private val output: ByteWriteChannel = socket.openWriteChannel()
    private val sendMutex = Mutex()
    private val receiveMutex = Mutex()

    override suspend fun send(bytes: ByteArray) {
        FrameCodec.checkSendSize(bytes.size)
        sendMutex.withLock {
            try {
                output.writeInt(bytes.size)
                output.writeByteArray(bytes)
                output.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw ProtocolException("Cannot send frame: TCP transport is closed or failed", e)
            }
        }
    }

    override suspend fun receive(): ByteArray = receiveMutex.withLock {
        val length = try {
            input.readInt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw ProtocolException(
                "Truncated frame: stream ended inside the 4-byte length prefix",
                e,
            )
        }

        FrameCodec.checkReceiveSize(length)
        try {
            input.readByteArray(length)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw ProtocolException(
                "Truncated frame: declared $length payload bytes but stream ended early",
                e,
            )
        }
    }

    override fun close() {
        try {
            socket.close()
        } finally {
            ownedSelector?.close()
        }
    }
}

/** Dials a fresh TCP socket and selector for every [connect] attempt. */
class TcpTransportFactory(
    private val host: String,
    private val port: Int,
) : TransportFactory {
    override suspend fun connect(): FrameTransport {
        val selector = SelectorManager(tcpSelectorDispatcher)
        return try {
            val socket = aSocket(selector).tcp().connect(host, port)
            TcpFrameTransport(socket, selector)
        } catch (e: Throwable) {
            selector.close()
            throw e
        }
    }
}

/**
 * TCP listener whose [start] method binds once and whose suspend [accept] method
 * returns one transport per connection. Port zero requests an ephemeral port, made
 * available through [boundPort] after startup.
 */
class TcpServer(
    private val port: Int = 0,
) {
    private val selector = SelectorManager(tcpSelectorDispatcher)
    private var serverSocket: ServerSocket? = null

    var boundPort: Int = 0
        private set

    suspend fun start() {
        check(serverSocket == null) { "TCP server has already been started" }
        val bound = try {
            aSocket(selector).tcp().bind("0.0.0.0", port)
        } catch (e: Throwable) {
            selector.close()
            throw e
        }
        serverSocket = bound
        boundPort = bound.port
    }

    /** Waits for and returns the next accepted connection. */
    suspend fun accept(): FrameTransport {
        val server = checkNotNull(serverSocket) {
            "TCP server must be started before accepting connections"
        }
        return TcpFrameTransport(server.accept())
    }

    fun close() {
        try {
            serverSocket?.close()
        } finally {
            selector.close()
        }
    }
}
