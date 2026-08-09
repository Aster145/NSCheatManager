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
import java.nio.file.StandardCopyOption
import java.nio.file.Path
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
            "atmosphere/contents/0100F2C0115B6000/cheats/notes.txt",
            mirror.notesRelative(titleId, buildId),
        )
        assertEquals(
            root.resolve("atmosphere/contents/0100F2C0115B6000/cheats/A4A8D3E7F29C81A2.txt"),
            mirror.cheatPath(titleId, buildId),
        )
        assertEquals(
            root.resolve("atmosphere/contents/0100F2C0115B6000/cheats/notes.txt"),
            mirror.notesPath(titleId, buildId),
        )
    }

    @Test
    fun currentBuildLegacyNotesAreCopiedWhenCanonicalNotesAreMissing() {
        val root = Files.createTempDirectory("mirror-legacy-notes-")
        val mirror = CheatMirror(root)
        val legacy = root.resolve(
            "atmosphere/contents/${titleId.hex}/cheats/${buildId.hex}/notes.txt",
        )
        val otherLegacy = root.resolve(
            "atmosphere/contents/${titleId.hex}/cheats/1111111111111111/notes.txt",
        )
        Files.createDirectories(legacy.parent)
        Files.createDirectories(otherLegacy.parent)
        Files.write(legacy, "current notes".toByteArray())
        Files.write(otherLegacy, "other notes".toByteArray())

        val canonical = mirror.notesPath(titleId, buildId)

        assertArrayEquals("current notes".toByteArray(), Files.readAllBytes(canonical))
        assertTrue(Files.exists(legacy))
        assertArrayEquals("other notes".toByteArray(), Files.readAllBytes(otherLegacy))
    }

    @Test
    fun canonicalNotesTakePriorityOverLegacyNotes() {
        val root = Files.createTempDirectory("mirror-canonical-notes-")
        val mirror = CheatMirror(root)
        val canonical = root.resolve("atmosphere/contents/${titleId.hex}/cheats/notes.txt")
        val legacy = root.resolve(
            "atmosphere/contents/${titleId.hex}/cheats/${buildId.hex}/notes.txt",
        )
        Files.createDirectories(legacy.parent)
        Files.write(canonical, "canonical".toByteArray())
        Files.write(legacy, "legacy".toByteArray())

        assertEquals(canonical, mirror.notesPath(titleId, buildId))
        assertArrayEquals("canonical".toByteArray(), Files.readAllBytes(canonical))
        assertArrayEquals("legacy".toByteArray(), Files.readAllBytes(legacy))
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
    fun atomicReplaceAllRollsBackBothOriginalsWhenSecondPublishFails() {
        val root = Files.createTempDirectory("mirror-pair-rollback-")
        var stagePublishes = 0
        var failed = false
        val mirror = CheatMirror(root, MirrorMoveOps { source, target ->
            if (source.fileName.toString().contains(".stage-") && ++stagePublishes == 2 && !failed) {
                failed = true
                throw java.io.IOException("injected second publish failure")
            }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        })
        val cheat = mirror.cheatPath(titleId, buildId)
        val notes = mirror.notesPath(titleId, buildId)
        Files.createDirectories(cheat.parent)
        Files.createDirectories(notes.parent)
        Files.write(cheat, "old cheat".toByteArray())
        Files.write(notes, "old notes".toByteArray())

        assertThrows(java.io.IOException::class.java) {
            mirror.atomicReplaceAll(
                linkedMapOf(
                    cheat to "new cheat".toByteArray(),
                    notes to "new notes".toByteArray(),
                ),
            )
        }

        assertArrayEquals("old cheat".toByteArray(), Files.readAllBytes(cheat))
        assertArrayEquals("old notes".toByteArray(), Files.readAllBytes(notes))
    }

    @Test
    fun pairTransactionForcesEveryPublicationBoundaryInOrder() {
        val root = Files.createTempDirectory("mirror-durable-order-")
        val events = mutableListOf<String>()
        val durability = object : FileDurability {
            override fun forceFile(path: Path) { events += "file:${path.fileName}" }
            override fun forceDirectory(path: Path) { events += "dir:${path.fileName}" }
        }
        val mirror = CheatMirror(
            root,
            MirrorMoveOps { source, target ->
                events += "move:${source.fileName}->${target.fileName}"
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            },
            durability,
            MirrorTransactionHook { cut, index -> events += "cut:$cut:${index ?: -1}" },
        )
        val cheat = mirror.cheatPath(titleId, buildId)
        val notes = mirror.notesPath(titleId, buildId)
        Files.createDirectories(notes.parent)
        Files.write(cheat, "old cheat".toByteArray())
        Files.write(notes, "old notes".toByteArray())

        mirror.atomicReplaceAll(linkedMapOf(cheat to "new cheat".toByteArray(), notes to "new notes".toByteArray()))

        assertOrdered(
            events,
            "cut:INIT:-1",
            "cut:STAGED:-1",
            "cut:BACKED_UP:-1",
            "cut:TARGET_MOVED:0",
            "cut:TARGET_MOVED:1",
            "cut:PUBLISHED:-1",
            "cut:CLEANUP:0",
        )
        assertTrue(events.any { it.startsWith("file:") && it.contains("journal") })
        assertTrue(events.count { it.startsWith("dir:") } >= 10)
    }

    @Test
    fun reopeningAfterEveryCrashCutNeverExposesMixedPair() {
        val points = listOf(
            CrashPoint(MirrorTransactionCut.INIT, null, expectedNew = false),
            CrashPoint(MirrorTransactionCut.STAGED, null, expectedNew = false),
            CrashPoint(MirrorTransactionCut.BACKED_UP, null, expectedNew = false),
            CrashPoint(MirrorTransactionCut.TARGET_MOVED, 0, expectedNew = false),
            CrashPoint(MirrorTransactionCut.TARGET_MOVED, 1, expectedNew = false),
            CrashPoint(MirrorTransactionCut.PUBLISHED, null, expectedNew = true),
            CrashPoint(MirrorTransactionCut.CLEANUP, 0, expectedNew = true),
        )
        points.forEach { point ->
            val root = Files.createTempDirectory("mirror-crash-${point.cut}-")
            val seed = CheatMirror(root)
            val cheat = seed.cheatPath(titleId, buildId)
            val notes = seed.notesPath(titleId, buildId)
            Files.createDirectories(notes.parent)
            Files.write(cheat, "old cheat".toByteArray())
            Files.write(notes, "old notes".toByteArray())
            val crashing = CheatMirror(
                root,
                MirrorMoveOps { source, target -> Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) },
                NoOpFileDurability,
                MirrorTransactionHook { cut, index ->
                    if (cut == point.cut && index == point.index) throw SimulatedProcessCrash()
                },
            )

            assertThrows(SimulatedProcessCrash::class.java) {
                crashing.atomicReplaceAll(
                    linkedMapOf(cheat to "new cheat".toByteArray(), notes to "new notes".toByteArray()),
                )
            }

            CheatMirror(
                root,
                MirrorMoveOps { source, target -> Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) },
                NoOpFileDurability,
                MirrorTransactionHook.None,
            )
            val expectedCheat = if (point.expectedNew) "new cheat" else "old cheat"
            val expectedNotes = if (point.expectedNew) "new notes" else "old notes"
            assertEquals(expectedCheat, String(Files.readAllBytes(cheat)))
            assertEquals(expectedNotes, String(Files.readAllBytes(notes)))
        }
    }

    @Test
    fun requiredFileSyncFailureFailsTheTransactionWithoutPublishingMixedContent() {
        val root = Files.createTempDirectory("mirror-sync-failure-")
        val seed = CheatMirror(root)
        val cheat = seed.cheatPath(titleId, buildId)
        val notes = seed.notesPath(titleId, buildId)
        Files.createDirectories(notes.parent)
        Files.write(cheat, "old cheat".toByteArray())
        Files.write(notes, "old notes".toByteArray())
        val mirror = CheatMirror(
            root,
            MirrorMoveOps { source, target -> Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) },
            object : FileDurability {
                override fun forceFile(path: Path) = throw java.io.IOException("fsync failed")
                override fun forceDirectory(path: Path) = Unit
            },
            MirrorTransactionHook.None,
        )

        assertThrows(java.io.IOException::class.java) {
            mirror.atomicReplaceAll(linkedMapOf(cheat to "new cheat".toByteArray(), notes to "new notes".toByteArray()))
        }
        assertEquals("old cheat", String(Files.readAllBytes(cheat)))
        assertEquals("old notes", String(Files.readAllBytes(notes)))
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

    private fun assertOrdered(events: List<String>, vararg expected: String) {
        var cursor = -1
        expected.forEach { event ->
            val relative = events.subList(cursor + 1, events.size).indexOf(event)
            cursor = if (relative < 0) -1 else cursor + 1 + relative
            assertTrue("Missing ordered event $event in $events", cursor >= 0)
        }
    }

    private data class CrashPoint(
        val cut: MirrorTransactionCut,
        val index: Int?,
        val expectedNew: Boolean,
    )

    private class SimulatedProcessCrash : Error("simulated abrupt process death")
}
