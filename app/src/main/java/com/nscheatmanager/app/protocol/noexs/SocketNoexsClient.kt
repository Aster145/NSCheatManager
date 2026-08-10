package com.nscheatmanager.app.protocol.noexs

import com.nscheatmanager.app.protocol.ProtocolError
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SocketNoexsClient(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = 5_000,
    private val responseTimeoutMillis: Int = 5_000,
) : Noexs {
    init {
        require(port in 1..65_535) { "Port must be between 1 and 65535" }
        require(connectTimeoutMillis > 0) { "Connect timeout must be positive" }
        require(responseTimeoutMillis > 0) { "Response timeout must be positive" }
    }

    override suspend fun detachDmnt() = withContext(dispatcher) {
        Socket().use { socket ->
            connect(socket)
            // PointerSearcher-compatible "Ask dmnt to detach" handshake. Noexs is
            // request/response; every response must be consumed before the next opcode.
            send(socket, ATTACH_DMNT)
            readResult(socket) // Intentionally ignored: dmnt may not be attached yet.

            send(socket, LIST_PIDS)
            val pidCount = readInt(socket, "Noexs PID count")
            if (pidCount !in 1..MAX_PID_COUNT) {
                throw ProtocolError.MalformedResponse("Invalid Noexs PID count: $pidCount")
            }
            val pidBytes = readExact(socket, pidCount * PID_BYTES, "Noexs PID list")
            checkResult(readResult(socket))
            val dmntPid = ByteBuffer.wrap(pidBytes, pidBytes.size - PID_BYTES, PID_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).long

            send(socket, STATUS)
            readInt(socket, "Noexs status")
            drainStatusTail(socket)

            send(socket, CURRENT_PID)
            readExact(socket, PID_BYTES, "Noexs current PID")
            checkResult(readResult(socket))

            command(socket, DETACH)
            send(socket, ATTACH, ByteBuffer.allocate(PID_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(dmntPid).array())
            checkResult(readResult(socket))
            command(socket, DETACH)
            command(socket, DETACH_DMNT)
            command(socket, DETACH) // Leave the Noexs debugger itself detached.
        }
    }

    private fun command(socket: Socket, opcode: Byte) {
        send(socket, opcode)
        checkResult(readResult(socket))
    }

    private fun send(socket: Socket, opcode: Byte, payload: ByteArray = byteArrayOf()) {
        try {
            socket.getOutputStream().apply {
                write(byteArrayOf(opcode))
                if (payload.isNotEmpty()) write(payload)
                flush()
            }
        } catch (error: IOException) {
            throw ProtocolError.Disconnected(error)
        }
    }

    private fun readInt(socket: Socket, operation: String): Int = ByteBuffer.wrap(
        readExact(socket, RESULT_BYTES, operation),
    ).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readExact(socket: Socket, size: Int, operation: String): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        val deadlineNanos = System.nanoTime() + responseTimeoutMillis * NANOS_PER_MILLISECOND
        try {
            val input = socket.getInputStream()
            while (offset < size) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) throw SocketTimeoutException("$operation deadline exceeded")
                socket.soTimeout = ((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val count = input.read(bytes, offset, size - offset)
                if (count == -1) throw ProtocolError.MalformedResponse(
                    "Expected $size-byte $operation response, received $offset bytes",
                    EOFException("$operation ended after $offset bytes"),
                )
                offset += count
            }
        } catch (error: SocketTimeoutException) {
            throw ProtocolError.Timeout(operation, error)
        } catch (error: ProtocolError) {
            throw error
        } catch (error: IOException) {
            throw ProtocolError.Disconnected(error)
        }
        return bytes
    }

    private fun drainStatusTail(socket: Socket) {
        val originalTimeout = socket.soTimeout
        try {
            socket.soTimeout = STATUS_DRAIN_TIMEOUT_MILLIS
            val input = socket.getInputStream()
            while (input.read() != -1) Unit
        } catch (_: SocketTimeoutException) {
            // A short quiet period terminates the variable-length Status response.
        } catch (error: IOException) {
            throw ProtocolError.Disconnected(error)
        } finally {
            socket.soTimeout = originalTimeout
        }
    }

    private fun checkResult(rawCode: Int) {
        if (rawCode != 0) throw NoexsResultError(
            module = rawCode and MODULE_MASK,
            description = (rawCode ushr DESCRIPTION_SHIFT) and DESCRIPTION_MASK,
            rawCode = rawCode,
        )
    }

    private fun connect(socket: Socket) {
        try {
            val remoteAddress = InetSocketAddress(host, port)
            socket.apply {
                if (remoteAddress.address?.isLoopbackAddress == true) {
                    bind(InetSocketAddress(remoteAddress.address, 0))
                }
                connect(remoteAddress, connectTimeoutMillis)
                soTimeout = responseTimeoutMillis
                tcpNoDelay = true
            }
        } catch (error: SocketTimeoutException) {
            throw ProtocolError.Timeout("Noexs connection", error)
        } catch (error: IOException) {
            throw ProtocolError.Connection(error)
        }
    }

    private fun readResult(socket: Socket): Int {
        val bytes = readExact(socket, RESULT_BYTES, "Noexs result")
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private companion object {
        const val DEFAULT_PORT = 7331
        const val RESULT_BYTES = 4
        const val MODULE_MASK = 0x1FF
        const val DESCRIPTION_SHIFT = 9
        const val DESCRIPTION_MASK = 0x1FFF
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val PID_BYTES = 8
        const val MAX_PID_COUNT = 4096
        const val STATUS_DRAIN_TIMEOUT_MILLIS = 25
        const val STATUS: Byte = 0x01
        const val ATTACH: Byte = 0x0A
        const val DETACH: Byte = 0x0B
        const val CURRENT_PID: Byte = 0x0E
        const val LIST_PIDS: Byte = 0x10
        const val DETACH_DMNT: Byte = 0x18
        const val ATTACH_DMNT: Byte = 0x1A
    }
}
