package com.nscheatmanager.app.core.binary

import com.nscheatmanager.app.core.model.ValueType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
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

    @Test fun exactBoundaryTableRoundTripsEverySupportedScalarType() {
        val cases = listOf(
            Triple(ValueType.Int8, "-128", "80"), Triple(ValueType.Int8, "127", "7F"),
            Triple(ValueType.UInt8, "0", "00"), Triple(ValueType.UInt8, "255", "FF"),
            Triple(ValueType.Int16, "-32768", "0080"), Triple(ValueType.Int16, "32767", "FF7F"),
            Triple(ValueType.UInt16, "0", "0000"), Triple(ValueType.UInt16, "65535", "FFFF"),
            Triple(ValueType.Int32, "-2147483648", "00000080"), Triple(ValueType.Int32, "2147483647", "FFFFFF7F"),
            Triple(ValueType.UInt32, "0", "00000000"), Triple(ValueType.UInt32, "4294967295", "FFFFFFFF"),
            Triple(ValueType.Int64, "-9223372036854775808", "0000000000000080"), Triple(ValueType.Int64, "9223372036854775807", "FFFFFFFFFFFFFF7F"),
            Triple(ValueType.UInt64, "0", "0000000000000000"), Triple(ValueType.UInt64, "18446744073709551615", "FFFFFFFFFFFFFFFF"),
        )
        cases.forEach { (type, value, hex) ->
            val bytes = LittleEndianCodec.encode(type, value)
            assertEquals("$type $value bytes", hex, bytes.joinToString("") { "%02X".format(it.toInt() and 255) })
            assertEquals("$type $value decode", value, LittleEndianCodec.decode(type, bytes))
        }
    }

    @Test fun ieeeSpecialPolicyPreservesNegativeZeroAndInfinityAndClassifiesNan() {
        assertEquals("00000080", LittleEndianCodec.encode(ValueType.Float, "-0.0").toHex())
        assertEquals("0000000000000080", LittleEndianCodec.encode(ValueType.Double, "-0.0").toHex())
        assertEquals("0000807F", LittleEndianCodec.encode(ValueType.Float, "Infinity").toHex())
        assertEquals("000080FF", LittleEndianCodec.encode(ValueType.Float, "-Infinity").toHex())
        assertTrue(LittleEndianCodec.decode(ValueType.Float, LittleEndianCodec.encode(ValueType.Float, "NaN")).toFloat().isNaN())
        assertTrue(LittleEndianCodec.decode(ValueType.Double, LittleEndianCodec.encode(ValueType.Double, "NaN")).toDouble().isNaN())
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

private fun ByteArray.toHex() = joinToString("") { "%02X".format(it.toInt() and 255) }
