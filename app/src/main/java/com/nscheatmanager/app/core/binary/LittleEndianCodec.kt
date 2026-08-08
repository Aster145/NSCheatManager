package com.nscheatmanager.app.core.binary

import com.nscheatmanager.app.core.model.ValueType

object LittleEndianCodec {
    fun encode(type: ValueType, value: String): ByteArray = when (type) {
        ValueType.Hex -> parseHex(value)
        ValueType.Int8 -> byteArrayOf(value.toByte())
        ValueType.UInt8 -> encodeUnsigned(value.toUByte().toULong(), 1)
        ValueType.Int16 -> encodeUnsigned(value.toShort().toULong(), 2)
        ValueType.UInt16 -> encodeUnsigned(value.toUShort().toULong(), 2)
        ValueType.Int32 -> encodeUnsigned(value.toInt().toUInt().toULong(), 4)
        ValueType.UInt32 -> encodeUnsigned(value.toUInt().toULong(), 4)
        ValueType.Int64 -> encodeUnsigned(value.toLong().toULong(), 8)
        ValueType.UInt64 -> encodeUnsigned(value.toULong(), 8)
        ValueType.Float -> encodeUnsigned(value.toFloat().toRawBits().toUInt().toULong(), 4)
        ValueType.Double -> encodeUnsigned(value.toDouble().toRawBits().toULong(), 8)
    }

    fun decode(type: ValueType, bytes: ByteArray): String = when (type) {
        ValueType.Hex -> bytes.joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xFF) }
        ValueType.Int8 -> {
            requireByteCount(type, bytes)
            bytes[0].toString()
        }
        ValueType.UInt8 -> {
            requireByteCount(type, bytes)
            bytes[0].toUByte().toString()
        }
        ValueType.Int16 -> readUnsigned(bytes, type).toShort().toString()
        ValueType.UInt16 -> readUnsigned(bytes, type).toUShort().toString()
        ValueType.Int32 -> readUnsigned(bytes, type).toInt().toString()
        ValueType.UInt32 -> readUnsigned(bytes, type).toUInt().toString()
        ValueType.Int64 -> readUnsigned(bytes, type).toLong().toString()
        ValueType.UInt64 -> readUnsigned(bytes, type).toString()
        ValueType.Float -> Float.fromBits(readUnsigned(bytes, type).toInt()).toString()
        ValueType.Double -> Double.fromBits(readUnsigned(bytes, type).toLong()).toString()
    }

    private fun parseHex(value: String): ByteArray {
        val compact = value.filterNot(Char::isWhitespace)
        require(compact.isNotEmpty()) { "Hex bytes must not be empty" }
        require(compact.length % 2 == 0) { "Hex bytes must contain an even number of digits" }
        require(compact.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) {
            "Hex bytes must contain only hexadecimal digits"
        }

        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun encodeUnsigned(value: ULong, byteCount: Int): ByteArray =
        ByteArray(byteCount) { index -> (value shr (index * 8)).toByte() }

    private fun readUnsigned(bytes: ByteArray, type: ValueType): ULong {
        requireByteCount(type, bytes)
        return bytes.foldIndexed(0uL) { index, result, byte ->
            result or (byte.toUByte().toULong() shl (index * 8))
        }
    }

    private fun requireByteCount(type: ValueType, bytes: ByteArray) {
        val expected = checkNotNull(type.byteSize)
        require(bytes.size == expected) { "$type requires exactly $expected bytes, got ${bytes.size}" }
    }
}
