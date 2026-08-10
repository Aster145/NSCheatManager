package com.nscheatmanager.app.protocol.sysbot

import com.nscheatmanager.app.core.binary.LittleEndianCodec
import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.MemoryTarget
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.core.model.ValueType
import com.nscheatmanager.app.protocol.ProtocolError
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SocketSysBotbaseClient(
    private val host: String,
    private val port: Int,
    private val dispatcher: CoroutineDispatcher,
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 5_000,
    private val maxResponseBytes: Int = 1_048_576,
) : SysBotbase {
    private val mutex = Mutex()
    private var socket: Socket? = null
    private var explicitlyDisconnected = false

    init {
        require(port in 1..65_535) { "Port must be between 1 and 65535" }
        require(connectTimeoutMillis > 0) { "Connect timeout must be positive" }
        require(readTimeoutMillis > 0) { "Read timeout must be positive" }
        require(maxResponseBytes > 0) { "Maximum response size must be positive" }
    }

    override suspend fun connect() = mutex.withLock {
        withContext(dispatcher) {
            explicitlyDisconnected = false
            ensureConnectedLocked()
        }
    }

    override suspend fun disconnect() = mutex.withLock {
        withContext(dispatcher) {
            explicitlyDisconnected = true
            closeSocketLocked()
        }
    }

    override suspend fun recognizeGame(): GameIdentity = mutex.withLock {
        withContext(dispatcher) {
            ensureConnectedLocked()
            GameIdentity(
                titleId = parseResponse(queryLocked("getTitleID"), TitleId::parse),
                buildId = parseResponse(queryLocked("getBuildID"), BuildId::parse),
                mainBase = parseAddress(queryLocked("getMainNsoBase")),
                heapBase = parseAddress(queryLocked("getHeapBase")),
            )
        }
    }

    override suspend fun read(target: MemoryTarget, size: Int): ByteArray {
        require(size > 0) { "Read size must be positive" }
        return mutex.withLock {
            withContext(dispatcher) {
                ensureConnectedLocked()
                val result = ByteArray(size)
                var copied = 0
                while (copied < size) {
                    val chunkSize = minOf(MAX_TRANSFER_BYTES, size - copied)
                    val chunkTarget = target.incrementedBy(copied)
                    val response = queryLocked(readCommand(chunkTarget, chunkSize))
                    val bytes = parseBytes(response)
                    if (bytes.size != chunkSize) throw ProtocolError.MalformedResponse(response)
                    bytes.copyInto(result, copied)
                    copied += chunkSize
                    if (copied < size) delay(CHUNK_DELAY_MILLIS)
                }
                result
            }
        }
    }

    override suspend fun write(target: MemoryTarget, bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Write bytes must not be empty" }
        mutex.withLock {
            withContext(dispatcher) {
                ensureConnectedLocked()
                var copied = 0
                while (copied < bytes.size) {
                    val chunkSize = minOf(MAX_TRANSFER_BYTES, bytes.size - copied)
                    val chunkTarget = target.incrementedBy(copied)
                    val chunk = bytes.copyOfRange(copied, copied + chunkSize)
                    val prefix = writePrefix(chunkTarget)
                    validatePayloadSize(prefix, chunkTarget, chunk.size)
                    sendLocked(writeCommand(prefix, chunkTarget, chunk))
                    copied += chunkSize
                    if (copied < bytes.size) delay(CHUNK_DELAY_MILLIS)
                }
            }
        }
    }

    override suspend fun freeze(absoluteAddress: ULong, bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Freeze bytes must not be empty" }
        validatePayloadSize("freeze", MemoryTarget.Absolute(absoluteAddress), bytes.size)
        mutex.withLock {
            withContext(dispatcher) {
                ensureConnectedLocked()
                sendLocked("freeze ${formatAddress(absoluteAddress, 16)} ${formatBytes(bytes)}")
            }
        }
    }

    override suspend fun unfreeze(absoluteAddress: ULong) {
        mutex.withLock {
            withContext(dispatcher) {
                ensureConnectedLocked()
                sendLocked("unFreeze ${formatAddress(absoluteAddress, 16)}")
            }
        }
    }

    private fun ensureConnectedLocked() {
        if (explicitlyDisconnected) throw ProtocolError.Disconnected()
        if (socket?.isConnected == true && socket?.isClosed == false) return
        val candidate = Socket()
        try {
            val remoteAddress = InetSocketAddress(host, port)
            candidate.apply {
                if (remoteAddress.address?.isLoopbackAddress == true) {
                    bind(InetSocketAddress(remoteAddress.address, 0))
                }
                connect(remoteAddress, connectTimeoutMillis)
                soTimeout = readTimeoutMillis
                tcpNoDelay = true
            }
            socket = candidate
        } catch (error: SocketTimeoutException) {
            candidate.closeQuietly()
            throw ProtocolError.Timeout("connection", error)
        } catch (error: IOException) {
            candidate.closeQuietly()
            throw ProtocolError.Connection(error)
        }
    }

    private fun readCommand(target: MemoryTarget, size: Int): String {
        val prefix = when (target) {
            is MemoryTarget.Absolute -> "peekAbsolute"
            is MemoryTarget.MainRelative -> "peekMain"
            is MemoryTarget.HeapRelative -> "peek"
        }
        return "$prefix ${formatAddress(target)} $size"
    }

    private fun writePrefix(target: MemoryTarget): String = when (target) {
        is MemoryTarget.Absolute -> "pokeAbsolute"
        is MemoryTarget.MainRelative -> "pokeMain"
        is MemoryTarget.HeapRelative -> "poke"
    }

    private fun writeCommand(prefix: String, target: MemoryTarget, bytes: ByteArray): String =
        "$prefix ${formatAddress(target)} ${formatBytes(bytes)}"

    private fun validatePayloadSize(prefix: String, target: MemoryTarget, payloadBytes: Int) {
        val fixedWireBytes =
            prefix.length + 1 + formatAddress(target).length + 1 + HEX_PREFIX_BYTES + CRLF_BYTES
        val actualWireBytes = fixedWireBytes.toLong() + payloadBytes.toLong() * HEX_CHARS_PER_BYTE
        if (actualWireBytes > MAX_COMMAND_BYTES) {
            throw ProtocolError.CommandTooLarge(MAX_COMMAND_BYTES, actualWireBytes)
        }
    }

    private fun queryLocked(command: String): String {
        sendLocked(command)
        return readLineLocked()
    }

    private fun sendLocked(command: String) {
        val wireBytes = "$command\r\n".toByteArray(Charsets.US_ASCII)
        if (wireBytes.size > MAX_COMMAND_BYTES) {
            throw ProtocolError.CommandTooLarge(MAX_COMMAND_BYTES, wireBytes.size.toLong())
        }
        try {
            checkNotNull(socket).getOutputStream().apply {
                write(wireBytes)
                flush()
            }
        } catch (error: IOException) {
            closeSocketLocked()
            throw ProtocolError.Disconnected(error)
        }
    }

    private fun readLineLocked(): String {
        val bytes = ArrayList<Byte>()
        var responseByteCount = 0
        val deadlineNanos = System.nanoTime() + readTimeoutMillis * 1_000_000L
        try {
            val activeSocket = checkNotNull(socket)
            val input = activeSocket.getInputStream()
            while (true) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) throw SocketTimeoutException("Read deadline exceeded")
                activeSocket.soTimeout =
                    ((remainingNanos + 999_999L) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val next = input.read()
                if (next == -1) {
                    closeSocketLocked()
                    throw ProtocolError.Disconnected()
                }
                if (next == '\n'.code) break
                responseByteCount += 1
                if (responseByteCount > maxResponseBytes) {
                    throw ProtocolError.ResponseTooLarge(maxResponseBytes)
                }
                if (next != '\r'.code) bytes += next.toByte()
            }
        } catch (error: SocketTimeoutException) {
            closeSocketLocked()
            throw ProtocolError.Timeout("response", error)
        } catch (error: ProtocolError) {
            if (error is ProtocolError.ResponseTooLarge) closeSocketLocked()
            throw error
        } catch (error: IOException) {
            closeSocketLocked()
            throw ProtocolError.Disconnected(error)
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun parseAddress(response: String): ULong = try {
        response.removePrefix("0x").removePrefix("0X").toULong(16)
    } catch (error: IllegalArgumentException) {
        throw ProtocolError.MalformedResponse(response, error)
    }

    private fun parseBytes(response: String): ByteArray = try {
        LittleEndianCodec.encode(ValueType.Hex, response)
    } catch (error: IllegalArgumentException) {
        throw ProtocolError.MalformedResponse(response, error)
    }

    private fun <T> parseResponse(response: String, parser: (String) -> T): T = try {
        parser(response)
    } catch (error: IllegalArgumentException) {
        throw ProtocolError.MalformedResponse(response, error)
    }

    private fun closeSocketLocked() {
        try {
            socket?.close()
        } catch (_: IOException) {
            // The original transport failure is more useful than a close failure.
        } finally {
            socket = null
        }
    }

    private fun formatAddress(target: MemoryTarget): String = when (target) {
        is MemoryTarget.HeapRelative -> formatAddress(target.offset, 8)
        is MemoryTarget.MainRelative -> formatAddress(target.offset, 16)
        is MemoryTarget.Absolute -> formatAddress(target.address, 16)
    }

    private fun formatAddress(value: ULong, minimumDigits: Int): String =
        "0x${value.toString(16).uppercase().padStart(minimumDigits, '0')}"

    private fun formatBytes(bytes: ByteArray): String =
        "0x${LittleEndianCodec.decode(ValueType.Hex, bytes)}"

    private fun Socket.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
            // Preserve the connection error.
        }
    }

    private val MemoryTarget.wireAddress: ULong
        get() = when (this) {
            is MemoryTarget.Absolute -> address
            is MemoryTarget.MainRelative -> offset
            is MemoryTarget.HeapRelative -> offset
        }

    private fun MemoryTarget.incrementedBy(byteOffset: Int): MemoryTarget {
        val increment = byteOffset.toULong()
        return when (this) {
            is MemoryTarget.Absolute -> MemoryTarget.Absolute(Math.addExact(address.toLong(), increment.toLong()).toULong())
            is MemoryTarget.MainRelative -> MemoryTarget.MainRelative(Math.addExact(offset.toLong(), increment.toLong()).toULong())
            is MemoryTarget.HeapRelative -> MemoryTarget.HeapRelative(Math.addExact(offset.toLong(), increment.toLong()).toULong())
        }
    }

    private companion object {
        const val MAX_COMMAND_BYTES = 344 * 32 * 2
        const val CRLF_BYTES = 2
        const val HEX_PREFIX_BYTES = 2
        const val HEX_CHARS_PER_BYTE = 2
        const val MAX_TRANSFER_BYTES = 0x1C0
        const val CHUNK_DELAY_MILLIS = 65L
    }
}
