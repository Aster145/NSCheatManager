package com.nscheatmanager.app.core.binary

import com.nscheatmanager.app.core.model.ValueType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LittleEndianCodecTest {
    @Test
    fun encodesInt32LittleEndian() {
        assertArrayEquals(
            byteArrayOf(0x64, 0, 0, 0),
            LittleEndianCodec.encode(ValueType.Int32, "100"),
        )
    }

    @Test
    fun encodesSignedAndUnsignedIntegerWidthsLittleEndian() {
        assertArrayEquals(byteArrayOf(0xFF.toByte()), LittleEndianCodec.encode(ValueType.Int8, "-1"))
        assertArrayEquals(byteArrayOf(0xFF.toByte()), LittleEndianCodec.encode(ValueType.UInt8, "255"))
        assertArrayEquals(byteArrayOf(0x34, 0x12), LittleEndianCodec.encode(ValueType.Int16, "4660"))
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), LittleEndianCodec.encode(ValueType.UInt32, "305419896"))
        assertArrayEquals(
            byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01),
            LittleEndianCodec.encode(ValueType.UInt64, "72623859790382856"),
        )
    }

    @Test
    fun rejectsValuesOutsideTheirDeclaredIntegerRanges() {
        assertInvalid { LittleEndianCodec.encode(ValueType.Int8, "128") }
        assertInvalid { LittleEndianCodec.encode(ValueType.UInt8, "-1") }
        assertInvalid { LittleEndianCodec.encode(ValueType.UInt16, "65536") }
        assertInvalid { LittleEndianCodec.encode(ValueType.Int64, "9223372036854775808") }
    }

    @Test
    fun encodesFloatingPointValuesByTheirLittleEndianBits() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0xC0.toByte(), 0x3F),
            LittleEndianCodec.encode(ValueType.Float, "1.5"),
        )
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0xF8.toByte(), 0x3F),
            LittleEndianCodec.encode(ValueType.Double, "1.5"),
        )
    }

    @Test
    fun parsesAndUppercasesRawHexBytes() {
        val bytes = LittleEndianCodec.encode(ValueType.Hex, "0a ff 10")

        assertArrayEquals(byteArrayOf(0x0A, 0xFF.toByte(), 0x10), bytes)
        assertEquals("0AFF10", LittleEndianCodec.decode(ValueType.Hex, bytes))
    }

    @Test
    fun decodesTypedValuesFromLittleEndianBytes() {
        assertEquals("-2", LittleEndianCodec.decode(ValueType.Int16, byteArrayOf(0xFE.toByte(), 0xFF.toByte())))
        assertEquals("4294967295", LittleEndianCodec.decode(ValueType.UInt32, byteArrayOf(-1, -1, -1, -1)))
        assertEquals("1.5", LittleEndianCodec.decode(ValueType.Float, byteArrayOf(0, 0, 0xC0.toByte(), 0x3F)))
        assertEquals("1.5", LittleEndianCodec.decode(ValueType.Double, byteArrayOf(0, 0, 0, 0, 0, 0, 0xF8.toByte(), 0x3F)))
    }

    @Test
    fun rejectsMalformedHexAndWrongFixedWidthBuffers() {
        assertInvalid { LittleEndianCodec.encode(ValueType.Hex, "") }
        assertInvalid { LittleEndianCodec.encode(ValueType.Hex, "0AF") }
        assertInvalid { LittleEndianCodec.encode(ValueType.Hex, "0AZZ") }
        assertInvalid { LittleEndianCodec.encode(ValueType.Hex, "0A١F") }
        assertInvalid { LittleEndianCodec.decode(ValueType.Int32, byteArrayOf(1, 2, 3)) }
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid value to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
