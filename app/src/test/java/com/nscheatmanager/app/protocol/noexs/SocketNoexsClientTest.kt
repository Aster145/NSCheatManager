package com.nscheatmanager.app.protocol.noexs

import com.nscheatmanager.app.protocol.ProtocolError
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SocketNoexsClientTest {
    @Test
    fun sendsOneByteDetachAndReadsLittleEndianResult() = runTest {
        FakeBinaryServer(responses = listOf(byteArrayOf(0, 0, 0, 0))).use { server ->
            val client = newClient(server)

            client.detachDmnt()

            assertArrayEquals(byteArrayOf(0x18), server.received.single())
        }
    }

    @Test
    fun decodesNonzeroLittleEndianResultFields() = runTest {
        val rawCode = (0x123 shl 9) or 0x1AB
        FakeBinaryServer(responses = listOf(littleEndian(rawCode))).use { server ->
            val client = newClient(server)

            val error = expectThrows<NoexsResultError> { client.detachDmnt() }

            assertEquals(rawCode, error.rawCode)
            assertEquals(0x1AB, error.module)
            assertEquals(0x123, error.description)
        }
    }

    @Test
    fun timesOutWhenResultDoesNotArrive() = runTest {
        FakeBinaryServer(responses = listOf(null)).use { server ->
            val client = newClient(server, responseTimeoutMillis = 30)

            val error = expectThrows<ProtocolError.Timeout> { client.detachDmnt() }

            assertEquals("Noexs result", error.operation)
            server.awaitReceivedCount(1)
            assertArrayEquals(byteArrayOf(0x18), server.received.single())
            assertEquals(listOf(true), server.remoteClosed)
        }
    }

    @Test
    fun responseTimeoutIsATotalDeadlineForSlowFragmentedResult() = runTest {
        FakeBinaryServer(
            responses = listOf(byteArrayOf(0, 0, 0, 0)),
            fragmentDelayMillis = 20,
        ).use { server ->
            val client = newClient(server, responseTimeoutMillis = 30)

            val error = expectThrows<ProtocolError.Timeout> { client.detachDmnt() }

            assertEquals("Noexs result", error.operation)
            server.awaitReceivedCount(1)
            assertEquals(listOf(true), server.remoteClosed)
        }
    }

    @Test
    fun rejectsShortResultAndClosesFailedSocketBeforeTheNextRequest() = runTest {
        FakeBinaryServer(
            responses = listOf(byteArrayOf(0, 0), byteArrayOf(0, 0, 0, 0)),
            closeAfterResponse = true,
        ).use { server ->
            val client = newClient(server)

            val error = expectThrows<ProtocolError.MalformedResponse> { client.detachDmnt() }

            assertTrue(error.response.contains("2"))
            client.detachDmnt()
            server.awaitReceivedCount(2)
            assertEquals(2, server.connectionCount)
            assertEquals(listOf(true, true), server.remoteClosed)
        }
    }

    @Test
    fun usesOneFreshSocketForEachDetachRequest() = runTest {
        FakeBinaryServer(
            responses = listOf(byteArrayOf(0, 0, 0, 0), byteArrayOf(0, 0, 0, 0)),
        ).use { server ->
            val client = newClient(server)

            client.detachDmnt()
            client.detachDmnt()
            server.awaitReceivedCount(2)

            assertEquals(2, server.connectionCount)
            assertEquals(listOf(true, true), server.remoteClosed)
        }
    }

    private fun newClient(
        server: FakeBinaryServer,
        responseTimeoutMillis: Int = 1_000,
    ) = SocketNoexsClient(
        host = server.host,
        port = server.port,
        dispatcher = Dispatchers.IO,
        connectTimeoutMillis = 1_000,
        responseTimeoutMillis = responseTimeoutMillis,
    )

    private fun littleEndian(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private suspend inline fun <reified T : Throwable> expectThrows(
        noinline block: suspend () -> Unit,
    ): T = try {
        block()
        fail("Expected ${T::class.java.simpleName}")
        throw AssertionError("unreachable")
    } catch (error: Throwable) {
        assertTrue("Expected ${T::class.java.name}, got ${error::class.java.name}", error is T)
        error as T
    }
}

private class FakeBinaryServer(
    private val responses: List<ByteArray?>,
    private val closeAfterResponse: Boolean = false,
    private val fragmentDelayMillis: Long = 0,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())

    val host: String = "127.0.0.1"
    val port: Int = serverSocket.localPort
    val received: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
    val remoteClosed: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    @Volatile var connectionCount: Int = 0
        private set

    init {
        executor.execute {
            while (!serverSocket.isClosed) {
                try {
                    val socket = serverSocket.accept()
                    sockets += socket
                    val index = connectionCount++
                    executor.execute { serve(socket, responses.getOrNull(index)) }
                } catch (error: Exception) {
                    if (!serverSocket.isClosed) throw error
                }
            }
        }
    }

    private fun serve(socket: Socket, response: ByteArray?) {
        socket.use {
            try {
                val request = it.getInputStream().readNBytes(1)
                if (request.size == 1) received += request
                if (response != null) {
                    it.getOutputStream().apply {
                        if (fragmentDelayMillis == 0L) {
                            write(response)
                            flush()
                        } else {
                            response.forEach { byte ->
                                write(byte.toInt())
                                flush()
                                Thread.sleep(fragmentDelayMillis)
                            }
                        }
                    }
                }
                if (!closeAfterResponse) {
                    it.soTimeout = 1_000
                    remoteClosed += try {
                        it.getInputStream().read() == -1
                    } catch (_: Exception) {
                        false
                    }
                } else if (closeAfterResponse) {
                    remoteClosed += true
                }
            } catch (_: IOException) {
                remoteClosed += true
            }
        }
    }

    fun awaitReceivedCount(count: Int, timeoutMillis: Long = 2_000) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (received.size < count && System.nanoTime() < deadline) Thread.sleep(5)
        check(received.size >= count) { "Expected $count requests, got ${received.size}" }
        val closeDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (remoteClosed.size < count && System.nanoTime() < closeDeadline) Thread.sleep(5)
    }

    override fun close() {
        serverSocket.close()
        synchronized(sockets) { sockets.forEach(Socket::close) }
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
