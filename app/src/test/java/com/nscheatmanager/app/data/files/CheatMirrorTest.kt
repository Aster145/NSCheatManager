package com.nscheatmanager.app.data.files

import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class CheatMirrorTest {
    private val titleId = TitleId.parse("0100F2C0115B6000")
    private val buildId = BuildId.parse("A4A8D3E7F29C81A2")

    @Test
    fun buildsExactCanonicalMirrorPaths() {
        val root = Files.createTempDirectory("mirror-path-test-")
        val mirror = CheatMirror(root)

        assertEquals(
            "atmosphere/contents/0100F2C0115B6000/cheats/A4A8D3E7F29C81A2.txt",
            mirror.cheatRelative(titleId, buildId),
        )
        assertEquals(
            "atmosphere/contents/0100F2C0115B6000/cheats/A4A8D3E7F29C81A2/notes.txt",
            mirror.notesRelative(titleId, buildId),
        )
        assertEquals(
            root.resolve("atmosphere/contents/0100F2C0115B6000/cheats/A4A8D3E7F29C81A2.txt"),
            mirror.cheatPath(titleId, buildId),
        )
        assertEquals(
            root.resolve("atmosphere/contents/0100F2C0115B6000/cheats/A4A8D3E7F29C81A2/notes.txt"),
            mirror.notesPath(titleId, buildId),
        )
    }

    @Test
    fun atomicReplaceCreatesParentsAndPublishesCompleteBytes() {
        val root = Files.createTempDirectory("mirror-write-test-")
        val mirror = CheatMirror(root)
        val target = mirror.cheatPath(titleId, buildId)
        val expected = "[Money]\n04000000 00112233 00000063\n".toByteArray()

        mirror.atomicReplace(target, expected)

        assertArrayEquals(expected, Files.readAllBytes(target))
        Files.list(target.parent).use { siblings ->
            assertFalse(siblings.anyMatch { it.fileName.toString().contains(".tmp-") })
        }
    }

    @Test
    fun atomicReplaceRejectsTargetsOutsideMirrorWithoutCreatingThem() {
        val parent = Files.createTempDirectory("mirror-escape-test-")
        val mirror = CheatMirror(parent.resolve("mirror"))
        val outside = parent.resolve("outside.txt")

        assertThrows(IllegalArgumentException::class.java) {
            mirror.atomicReplace(outside, byteArrayOf(1, 2, 3))
        }

        assertFalse(Files.exists(outside))
    }
}
