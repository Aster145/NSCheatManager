package com.nscheatmanager.app.domain

import com.nscheatmanager.app.core.binary.LittleEndianCodec
import com.nscheatmanager.app.core.model.MemoryTarget
import com.nscheatmanager.app.core.model.ValueType
import com.nscheatmanager.app.core.model.checkedAdd
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import com.nscheatmanager.app.protocol.sysbot.SysBotbase

/** Defensive byte value: callers can obtain a copy but cannot mutate session-owned payloads. */
class ImmutableBytes private constructor(private val payload: ByteArray) {
    val size: Int get() = payload.size

    fun copyToByteArray(): ByteArray = payload.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ImmutableBytes && payload.contentEquals(other.payload)

    override fun hashCode(): Int = payload.contentHashCode()

    override fun toString(): String = LittleEndianCodec.decode(ValueType.Hex, payload)

    companion object {
        fun copyOf(bytes: ByteArray): ImmutableBytes = ImmutableBytes(bytes.copyOf())
    }
}

data class MemoryReadResult(
    val target: MemoryTarget,
    val absoluteAddress: ULong,
    val type: ValueType,
    val bytes: ImmutableBytes,
    val value: String,
)

data class MemoryWriteResult(
    val target: MemoryTarget,
    val absoluteAddress: ULong,
    val type: ValueType,
    val bytes: ImmutableBytes,
)

data class LockedValue(
    val target: MemoryTarget,
    val absoluteAddress: ULong,
    val type: ValueType,
    val bytes: ImmutableBytes,
)

class MemoryUseCases(
    private val maxReadBytes: Int = DEFAULT_MAX_READ_BYTES,
    private val maxWriteBytes: Int = DEFAULT_MAX_WRITE_BYTES,
) {
    init {
        require(maxReadBytes > 0) { "Read limit must be positive" }
        require(maxWriteBytes > 0) { "Write limit must be positive" }
    }

    suspend fun readValue(
        client: SysBotbase,
        identity: GameIdentity,
        target: MemoryTarget,
        type: ValueType,
        hexByteCount: Int? = null,
    ): MemoryReadResult {
        val byteCount = readByteCount(type, hexByteCount)
        require(byteCount in 1..maxReadBytes) { "Read size must be in 1..$maxReadBytes bytes" }
        val absolute = resolveAbsolute(target, identity, byteCount)
        val bytes = client.read(MemoryTarget.Absolute(absolute), byteCount)
        require(bytes.size == byteCount) {
            "Memory response must contain exactly $byteCount bytes, got ${bytes.size}"
        }
        return MemoryReadResult(
            target = target,
            absoluteAddress = absolute,
            type = type,
            bytes = ImmutableBytes.copyOf(bytes),
            value = LittleEndianCodec.decode(type, bytes),
        )
    }

    suspend fun writeValue(
        client: SysBotbase,
        identity: GameIdentity,
        target: MemoryTarget,
        type: ValueType,
        value: String,
    ): MemoryWriteResult {
        val bytes = LittleEndianCodec.encode(type, value)
        validateWrite(bytes)
        val absolute = resolveAbsolute(target, identity, bytes.size)
        client.write(MemoryTarget.Absolute(absolute), bytes)
        return MemoryWriteResult(
            target = target,
            absoluteAddress = absolute,
            type = type,
            bytes = ImmutableBytes.copyOf(bytes),
        )
    }

    suspend fun lockValue(
        client: SysBotbase,
        identity: GameIdentity,
        target: MemoryTarget,
        type: ValueType,
        value: String,
    ): LockedValue {
        val lock = prepareLock(identity, target, type, value)
        freezePrepared(client, lock)
        return lock
    }

    fun prepareLock(
        identity: GameIdentity,
        target: MemoryTarget,
        type: ValueType,
        value: String,
    ): LockedValue {
        val bytes = LittleEndianCodec.encode(type, value)
        validateWrite(bytes)
        val absolute = resolveAbsolute(target, identity, bytes.size)
        return LockedValue(
            target = target,
            absoluteAddress = absolute,
            type = type,
            bytes = ImmutableBytes.copyOf(bytes),
        )
    }

    suspend fun freezePrepared(client: SysBotbase, lock: LockedValue) {
        client.freeze(lock.absoluteAddress, lock.bytes.copyToByteArray())
    }

    suspend fun unlockValue(client: SysBotbase, lock: LockedValue) {
        client.unfreeze(lock.absoluteAddress)
    }

    fun resolveAbsolute(target: MemoryTarget, identity: GameIdentity, widthBytes: Int): ULong {
        require(widthBytes > 0) { "Memory width must be positive" }
        val absolute = when (target) {
            is MemoryTarget.Absolute -> target.address
            is MemoryTarget.MainRelative -> checkedAdd(identity.mainBase, target.offset)
            is MemoryTarget.HeapRelative -> checkedAdd(identity.heapBase, target.offset)
        }
        require(absolute != 0uL) { "Memory address must not be zero" }
        checkedAdd(absolute, (widthBytes - 1).toULong())
        return absolute
    }

    private fun readByteCount(type: ValueType, requested: Int?): Int = when (type) {
        ValueType.Hex -> requireNotNull(requested) { "Hex reads require an explicit byte count" }
        else -> type.byteSize.also { size ->
            require(requested == null || requested == size) {
                "$type reads require exactly $size bytes"
            }
        } ?: error("Typed value did not define a byte size")
    }

    private fun validateWrite(bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Memory writes must not be empty" }
        require(bytes.size <= maxWriteBytes) { "Write size must not exceed $maxWriteBytes bytes" }
    }

    companion object {
        const val DEFAULT_MAX_READ_BYTES = 4 * 1024
        const val DEFAULT_MAX_WRITE_BYTES = 4 * 1024
    }
}
