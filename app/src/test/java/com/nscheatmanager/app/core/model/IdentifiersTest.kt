package com.nscheatmanager.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class IdentifiersTest {
    @Test
    fun parsesCanonicalIds() {
        assertEquals("0100F2C0115B6000", TitleId.parse("0100f2c0115b6000").hex)
        assertEquals("A4A8D3E7F29C81A2", BuildId.parse("a4a8d3e7f29c81a2").hex)
    }

    @Test
    fun titleIdRejectsAnythingOtherThanSixteenHexCharacters() {
        assertInvalid { TitleId.parse("0100F2C0115B600") }
        assertInvalid { TitleId.parse("0100F2C0115B600G") }
        assertInvalid { TitleId.parse(" 0100F2C0115B6000") }
    }

    @Test
    fun buildIdRejectsAnythingOtherThanSixteenHexCharacters() {
        assertInvalid { BuildId.parse("A4A8D3E7F29C81A") }
        assertInvalid { BuildId.parse("A4A8D3E7F29C81AZ") }
    }

    @Test(expected = ArithmeticException::class)
    fun rejectsAddressOverflow() {
        checkedAdd(ULong.MAX_VALUE, 1u)
    }

    @Test
    fun addsUnsignedAddressesWithoutOverflow() {
        assertEquals(ULong.MAX_VALUE, checkedAdd(ULong.MAX_VALUE - 1u, 1u))
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid identifier to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
