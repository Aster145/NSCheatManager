package com.nscheatmanager.app.cheats.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CheatNotesParserTest {
    @Test
    fun mapsNoteSectionsAndPreservesMultilineText() {
        val notes = CheatNotesParser.parse(
            "[纯栈版本]\nnote=测试测试\n第二行\n[解决方案1：更换寄存器]\nnote=速度\n[未知]\nignored=true",
        )

        assertEquals("测试测试\n第二行", notes["纯栈版本"])
        assertEquals("速度", notes["解决方案1：更换寄存器"])
        assertFalse(notes.containsKey("未知"))
    }
}
