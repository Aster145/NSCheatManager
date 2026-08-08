package com.nscheatmanager.app.core.model

sealed interface MemoryTarget {
    data class Absolute(val address: ULong) : MemoryTarget
    data class MainRelative(val offset: ULong) : MemoryTarget
    data class HeapRelative(val offset: ULong) : MemoryTarget
}

enum class ValueType(val byteSize: Int?) {
    Hex(null),
    Int8(1),
    UInt8(1),
    Int16(2),
    UInt16(2),
    Int32(4),
    UInt32(4),
    Int64(8),
    UInt64(8),
    Float(4),
    Double(8),
}

fun checkedAdd(left: ULong, right: ULong): ULong {
    if (left > ULong.MAX_VALUE - right) {
        throw ArithmeticException("Unsigned 64-bit address overflow")
    }
    return left + right
}
