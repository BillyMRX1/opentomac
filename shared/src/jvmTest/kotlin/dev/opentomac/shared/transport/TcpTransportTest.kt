package dev.opentomac.shared.transport

import dev.opentomac.shared.crypto.Handshake
import dev.opentomac.shared.crypto.Identity
import dev.opentomac.shared.protocol.FrameCodec
import dev.opentomac.shared.protocol.ProtocolException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TcpTransportTest {

    @Test
    fun handshakeAndFramesRoundTripInBothDirections(): Unit = runBlocking {
        withTimeout(60_000) {
            val server = TcpServer()
            server.start()
            try {
                val accepting = async { server.accept() }
                val client = TcpTransportFactory(LOOPBACK, server.boundPort).connect()
                try {
                    val accepted = accepting.await()
                    try {
                        val clientIdentity = Identity.generate()
                        val serverIdentity = Identity.generate()
                        val (clientHandshake, serverHandshake) = awaitAll(
                            async { Handshake.initiate(client, clientIdentity) },
                            async { Handshake.respond(accepted, serverIdentity) },
                        )
                        assertContentEquals(clientHandshake.sessionKeys.txKey, serverHandshake.sessionKeys.rxKey)
                        assertContentEquals(clientHandshake.sessionKeys.rxKey, serverHandshake.sessionKeys.txKey)

                        val receivedAtServer = async {
                            repeat(FRAME_COUNT) { index ->
                                assertContentEquals(clientPayload(index), accepted.receive(), "client frame $index")
                            }
                        }
                        val receivedAtClient = async {
                            repeat(FRAME_COUNT) { index ->
                                assertContentEquals(serverPayload(index), client.receive(), "server frame $index")
                            }
                        }
                        val sentByClient = async {
                            repeat(FRAME_COUNT) { index -> client.send(clientPayload(index)) }
                        }
                        val sentByServer = async {
                            repeat(FRAME_COUNT) { index -> accepted.send(serverPayload(index)) }
                        }

                        awaitAll(receivedAtServer, receivedAtClient, sentByClient, sentByServer)
                    } finally {
                        accepted.close()
                    }
                } finally {
                    client.close()
                }
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun oversizedSendAndPeerDeclaredLengthAreRejected(): Unit = runBlocking {
        withTimeout(10_000) {
            val server = TcpServer()
            server.start()
            try {
                val acceptingClient = async { server.accept() }
                val client = TcpTransportFactory(LOOPBACK, server.boundPort).connect()
                val acceptedClient = acceptingClient.await()
                try {
                    val error = assertFailsWith<ProtocolException> {
                        client.send(ByteArray(FrameCodec.MAX_FRAME_BYTES + 1))
                    }
                    assertTrue(error.message!!.contains("${FrameCodec.MAX_FRAME_BYTES}"))
                } finally {
                    client.close()
                    acceptedClient.close()
                }

                val acceptingRaw = async { server.accept() }
                Socket(LOOPBACK, server.boundPort).use { rawSocket ->
                    val acceptedRaw = acceptingRaw.await()
                    try {
                        DataOutputStream(rawSocket.getOutputStream()).apply {
                            writeInt(FrameCodec.MAX_FRAME_BYTES + 1)
                            flush()
                        }
                        val error = assertFailsWith<ProtocolException> { acceptedRaw.receive() }
                        assertEquals(
                            "Rejecting frame with declared length ${FrameCodec.MAX_FRAME_BYTES + 1}; " +
                                "must be between 0 and ${FrameCodec.MAX_FRAME_BYTES} bytes",
                            error.message,
                        )
                    } finally {
                        acceptedRaw.close()
                    }
                }
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun abruptPeerCloseFailsReceiveWithoutHanging(): Unit = runBlocking {
        withTimeout(10_000) {
            val server = TcpServer()
            server.start()
            try {
                val accepting = async { server.accept() }
                val client = TcpTransportFactory(LOOPBACK, server.boundPort).connect()
                try {
                    val accepted = accepting.await()
                    accepted.close()
                    assertFailsWith<ProtocolException> {
                        withTimeout(5_000) { client.receive() }
                    }
                } finally {
                    client.close()
                }
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun factoryConnectToClosedPortThrows(): Unit = runBlocking {
        withTimeout(10_000) {
            val closedPort = ServerSocket(0).use { it.localPort }
            val failure = runCatching {
                TcpTransportFactory(LOOPBACK, closedPort).connect()
            }.exceptionOrNull()
            assertNotNull(failure, "connecting to a closed localhost port should fail")
        }
    }

    @Test
    fun ephemeralServersExposeDistinctRealPorts() = runBlocking {
        withTimeout(10_000) {
            val first = TcpServer()
            val second = TcpServer()
            try {
                first.start()
                second.start()
                assertTrue(first.boundPort in 1..65535)
                assertTrue(second.boundPort in 1..65535)
                assertNotEquals(first.boundPort, second.boundPort)
            } finally {
                first.close()
                second.close()
            }
        }
    }

    private fun clientPayload(index: Int): ByteArray = payload(index, LARGE_CLIENT_FRAME, 17)

    private fun serverPayload(index: Int): ByteArray = payload(index, LARGE_SERVER_FRAME, 93)

    private fun payload(index: Int, largeFrameIndex: Int, salt: Int): ByteArray {
        val size = if (index == largeFrameIndex) LARGE_FRAME_BYTES else index % 257
        return ByteArray(size) { offset -> ((index * 31 + offset * 7 + salt) and 0xff).toByte() }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val FRAME_COUNT = 1_000
        const val LARGE_CLIENT_FRAME = 499
        const val LARGE_SERVER_FRAME = 749
        const val LARGE_FRAME_BYTES = 2 * 1024 * 1024
    }
}
