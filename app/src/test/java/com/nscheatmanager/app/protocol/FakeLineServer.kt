package com.nscheatmanager.app.protocol

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FakeLineServer(
    private val responses: Map<String, String> = emptyMap(),
    private val fragmentResponses: Boolean = false,
    private val fragmentDelayMillis: Long = 2,
    private val responseDelayMillis: Long = 0,
    private val closeOnCommand: String? = null,
    private val handler: ((String) -> String?)? = null,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val clients = Collections.synchronizedList(mutableListOf<Socket>())

    val host: String = "127.0.0.1"
    val port: Int = serverSocket.localPort
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val lineEndings: MutableList<String> = Collections.synchronizedList(mutableListOf())

    init {
        executor.execute {
            while (!serverSocket.isClosed) {
                try {
                    val socket = serverSocket.accept()
                    clients += socket
                    executor.execute { serve(socket) }
                } catch (error: Exception) {
                    if (!serverSocket.isClosed) throw error
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            val input = it.getInputStream()
            val output = it.getOutputStream()
            while (true) {
                val incoming = readCommand(input) ?: return
                val command = incoming.command
                commands += command
                lineEndings += incoming.lineEnding
                if (command == closeOnCommand) return
                val response = handler?.invoke(command) ?: responses[command]
                if (response != null) {
                    if (responseDelayMillis > 0) Thread.sleep(responseDelayMillis)
                    if (fragmentResponses) {
                        response.toByteArray(Charsets.US_ASCII).forEach { byte ->
                            output.write(byte.toInt())
                            output.flush()
                            Thread.sleep(fragmentDelayMillis)
                        }
                    } else {
                        output.write(response.toByteArray(Charsets.US_ASCII))
                        output.flush()
                    }
                }
            }
        }
    }

    private fun readCommand(input: InputStream): IncomingCommand? {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val next = input.read()
            if (next == -1) return null
            if (next == '\n'.code) {
                val hasCarriageReturn = bytes.lastOrNull() == '\r'.code.toByte()
                if (hasCarriageReturn) bytes.removeAt(bytes.lastIndex)
                return IncomingCommand(
                    command = bytes.toByteArray().toString(Charsets.US_ASCII),
                    lineEnding = if (hasCarriageReturn) "\r\n" else "\n",
                )
            }
            bytes += next.toByte()
        }
    }

    fun awaitCommandCount(count: Int, timeoutMillis: Long = 2_000) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (commands.size < count && System.nanoTime() < deadline) Thread.sleep(5)
        check(commands.size >= count) { "Expected $count commands, got ${commands.size}" }
    }

    override fun close() {
        serverSocket.close()
        synchronized(clients) { clients.forEach(Socket::close) }
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    private data class IncomingCommand(val command: String, val lineEnding: String)
}
