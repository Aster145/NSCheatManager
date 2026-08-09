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
            try {
                socket.getOutputStream().apply {
                    write(byteArrayOf(DETACH_DMNT))
                    flush()
                }
            } catch (error: IOException) {
                throw ProtocolError.Disconnected(error)
            }

            val rawCode = readResult(socket)
            if (rawCode != 0) {
                throw NoexsResultError(
                    module = rawCode and MODULE_MASK,
                    description = (rawCode ushr DESCRIPTION_SHIFT) and DESCRIPTION_MASK,
                    rawCode = rawCode,
                )
            }
        }
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
        val bytes = ByteArray(RESULT_BYTES)
        var offset = 0
        try {
            val input = socket.getInputStream()
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count == -1) {
                    throw ProtocolError.MalformedResponse(
                        "Expected $RESULT_BYTES-byte Noexs result, received $offset bytes",
                        EOFException("Noexs result ended after $offset bytes"),
                    )
                }
                offset += count
            }
        } catch (error: SocketTimeoutException) {
            throw ProtocolError.Timeout("Noexs result", error)
        } catch (error: ProtocolError) {
            throw error
        } catch (error: IOException) {
            throw ProtocolError.Disconnected(error)
        }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private companion object {
        const val DEFAULT_PORT = 7331
        const val RESULT_BYTES = 4
        const val MODULE_MASK = 0x1FF
        const val DESCRIPTION_SHIFT = 9
        const val DESCRIPTION_MASK = 0x1FFF
    }
}
