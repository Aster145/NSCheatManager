package com.nscheatmanager.app.cheats.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatFileParserTest {
    private val parser = CheatFileParser()

    @Test
    fun parsesGroupsAndLines() {
        val file = parser.parse(
            "[无限生命]\n" +
                "580F0000 046A12B0\n" +
                "640F0000 00000000 00000064\n",
        )

        assertEquals("无限生命", file.groups.single().name)
        assertEquals(listOf(2, 3), file.groups.single().instructions.map { it.sourceLine })
        assertTrue(file.diagnostics.isEmpty())
    }

    @Test
    fun acceptsBomCrLfWhitespaceCommentsAndBlankLines() {
        val file = parser.parse(
            "\uFEFF  # file comment\r\n" +
                "  [  Infinite Health  ]  \r\n" +
                "\r\n" +
                "  # ignored comment\r\n" +
                "  580F0000   046A12B0  \r\n",
        )

        val group = file.groups.single()
        assertEquals("Infinite Health", group.name)
        assertEquals(2, group.startLine)
        assertEquals(5, group.instructions.single().sourceLine)
        assertEquals("  580F0000   046A12B0  ", group.instructions.single().sourceText)
        assertTrue(file.diagnostics.isEmpty())
    }

    @Test
    fun preservesDuplicateGroupNamesIndependently() {
        val file = parser.parse(
            "[same]\n580F0000 00000001\n" +
                "[same]\n580F0000 00000002\n",
        )

        assertEquals(listOf("same", "same"), file.groups.map { it.name })
        assertEquals(listOf(1, 3), file.groups.map { it.startLine })
        assertEquals(listOf(2, 4), file.groups.map { it.instructions.single().sourceLine })
    }

    @Test
    fun reportsInstructionBeforeFirstHeader() {
        val file = parser.parse("580F0000 046A12B0\n[group]\n580F0000 00000000")

        assertEquals(1, file.diagnostics.single().line)
        assertEquals(CheatParseDiagnosticKind.InstructionBeforeGroup, file.diagnostics.single().kind)
        assertEquals(listOf(3), file.groups.single().instructions.map { it.sourceLine })
    }

    @Test
    fun reportsMalformedHeaderWithoutTreatingItAsCode() {
        val file = parser.parse("[broken\n[group]\n580F0000 046A12B0")

        assertEquals(1, file.diagnostics.single().line)
        assertEquals(CheatParseDiagnosticKind.MalformedGroupHeader, file.diagnostics.single().kind)
        assertEquals("group", file.groups.single().name)
    }

    @Test
    fun reportsHeaderWithExtraBracketAsMalformed() {
        val file = parser.parse("[group] extra]\n[valid]\n580F0000 046A12B0")

        assertEquals(listOf(1), file.diagnostics.map { it.line })
        assertEquals("valid", file.groups.single().name)
    }

    @Test
    fun invalidatesCurrentGroupAfterMalformedHeader() {
        val file = parser.parse(
            "[first]\n" +
                "580F0000 046A12B0\n" +
                "[broken\n" +
                "580F0000 00000001\n" +
                "[next]\n" +
                "580F0000 00000002",
        )

        assertEquals(listOf(3, 4), file.diagnostics.map { it.line })
        assertEquals(listOf(2), file.groups.first().instructions.map { it.sourceLine })
        assertEquals(listOf(6), file.groups.last().instructions.map { it.sourceLine })
    }

    @Test
    fun reportsBadHexWithoutApplyingOpcodeDependentArityRules() {
        val file = parser.parse(
            "[group]\n" +
                "580F000G 046A12B0\n" +
                "580F0000\n" +
                "640F0000 00000000 00000064",
        )

        assertEquals(listOf(2), file.diagnostics.map { it.line })
        assertEquals(CheatParseDiagnosticKind.InvalidInstructionWord, file.diagnostics[0].kind)
        assertEquals(listOf(3, 4), file.groups.single().instructions.map { it.sourceLine })
    }

    @Test
    fun keepsSyntacticallyValidUnsupportedOpcodeForValidator() {
        val file = parser.parse("[future opcode]\n10000000 00000000")

        assertTrue(file.diagnostics.isEmpty())
        assertEquals(listOf(0x10000000u, 0u), file.groups.single().instructions.single().words)
    }

    @Test
    fun keepsSyntacticallyValidSingleWordInstructionsForValidator() {
        val file = parser.parse("[single word]\n20000000\nA4010000")

        assertTrue(file.diagnostics.isEmpty())
        assertEquals(
            listOf(listOf(0x20000000u), listOf(0xA4010000u)),
            file.groups.single().instructions.map { it.words },
        )
    }
}
