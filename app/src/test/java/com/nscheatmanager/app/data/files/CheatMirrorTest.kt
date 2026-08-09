package com.nscheatmanager.app.data.files

import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    @Test
    fun atomicReplaceRejectsSymlinkedParentWithoutTouchingOutsideSentinel() {
        val root = Files.createTempDirectory("mirror-parent-link-")
        val outside = Files.createTempDirectory("mirror-parent-link-outside-")
        val sentinel = outside.resolve("sentinel.txt")
        Files.write(sentinel, "outside".toByteArray())
        try {
            Files.createSymbolicLink(root.resolve("atmosphere"), outside)
        } catch (error: Exception) {
            assumeNoException("Symbolic links are unavailable on this platform", error)
        }
        val mirror = CheatMirror(root)

        assertThrows(IllegalArgumentException::class.java) {
            mirror.atomicReplace(mirror.cheatPath(titleId, buildId), "replacement".toByteArray())
        }

        assertArrayEquals("outside".toByteArray(), Files.readAllBytes(sentinel))
        assertFalse(Files.exists(outside.resolve("contents")))
    }

    @Test
    fun atomicReplaceRejectsSymlinkTargetWithoutChangingItsReferent() {
        val root = Files.createTempDirectory("mirror-target-link-")
        val mirror = CheatMirror(root)
        val target = mirror.cheatPath(titleId, buildId)
        Files.createDirectories(target.parent)
        val outside = Files.createTempFile("mirror-target-outside-", ".txt")
        Files.write(outside, "outside".toByteArray())
        try {
            Files.createSymbolicLink(target, outside)
        } catch (error: Exception) {
            assumeNoException("Symbolic links are unavailable on this platform", error)
        }

        assertThrows(IllegalArgumentException::class.java) {
            mirror.atomicReplace(target, "replacement".toByteArray())
        }

        assertArrayEquals("outside".toByteArray(), Files.readAllBytes(outside))
    }

    @Test
    fun writeLockIsSharedByMirrorInstancesForTheSameRoot() {
        val root = Files.createTempDirectory("mirror-shared-lock-")
        val first = CheatMirror(root)
        val second = CheatMirror(root)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val holder = executor.submit<Unit> {
                first.withWriteTransaction {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val waiter = executor.submit<Unit> { second.atomicReplace(second.cheatPath(titleId, buildId), byteArrayOf(7)) }

            assertThrows(TimeoutException::class.java) { waiter.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            holder.get(5, TimeUnit.SECONDS)
            waiter.get(5, TimeUnit.SECONDS)
            assertArrayEquals(byteArrayOf(7), Files.readAllBytes(second.cheatPath(titleId, buildId)))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
