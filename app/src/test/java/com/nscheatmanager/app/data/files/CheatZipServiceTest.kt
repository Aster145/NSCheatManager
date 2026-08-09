package com.nscheatmanager.app.data.files

import com.nscheatmanager.app.cheats.parser.CheatFileParser
import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CheatZipServiceTest {
    private val titleId = TitleId.parse("0100F2C0115B6000")
    private val buildId = BuildId.parse("A4A8D3E7F29C81A2")
    private val otherBuildId = BuildId.parse("1111111111111111")
    private val cheatPath =
        "atmosphere/contents/0100F2C0115B6000/cheats/A4A8D3E7F29C81A2.txt"
    private val notesPath =
        "atmosphere/contents/0100F2C0115B6000/cheats/A4A8D3E7F29C81A2/notes.txt"
    private val validCheat = (
        "[Money]\n" +
            "04000000 00112233 00000063\n" +
            "[Health]\n" +
            "04000000 00445566 00000064\n"
        ).toByteArray()
    private val notes = "Use only in offline mode.\n".toByteArray()

    @Test
    fun inspectPreviewsCanonicalFilesGroupsAndOverwriteWithoutMutatingMirror() {
        val fixture = fixtureWithOldMirror()
        val archive = zipOf(cheatPath to validCheat, notesPath to notes)

        val inspection = fixture.service.inspect(archive)

        assertEquals(titleId, inspection.titleId)
        assertEquals(buildId, inspection.buildId)
        assertEquals(listOf(cheatPath, notesPath), inspection.entries.map { it.relativePath })
        assertEquals(listOf(validCheat.size.toLong(), notes.size.toLong()), inspection.entries.map { it.expandedSize })
        assertEquals(2, inspection.groupCount)
        assertEquals(OverwriteImpact(cheat = true, notes = true), inspection.overwriteImpact)
        fixture.assertOldMirrorUnchanged()
    }

    @Test
    fun inspectAcceptsDataDescriptorEntriesByEnforcingActualStreamedSizes() {
        val fixture = fixtureWithOldMirror()
        // ZipOutputStream's default DEFLATED entries use a local-header data descriptor.
        val archive = zipOf(cheatPath to validCheat)

        val inspection = fixture.service.inspect(ByteArrayInputStream(archive))

        assertEquals(1, inspection.entries.size)
        assertEquals(validCheat.size.toLong(), inspection.entries.single().expandedSize)
        fixture.assertOldMirrorUnchanged()
    }

    @Test
    fun inspectAcceptsADataDescriptorWithoutTheOptionalSignature() {
        val fixture = fixtureWithOldMirror()
        val archive = removeFirstDescriptorSignature(zipOf(cheatPath to validCheat))

        val inspection = fixture.service.inspect(archive)

        assertEquals(1, inspection.entries.size)
        assertEquals(validCheat.size.toLong(), inspection.entries.single().expandedSize)
        fixture.assertOldMirrorUnchanged()
    }

    @Test
    fun inspectRejectsUnsafeOrNonCanonicalArchivesWithoutChangingMirror() {
        val fixture = fixtureWithOldMirror()
        val lowercaseCheat = cheatPath.lowercase()
        val lowercaseNotes = notesPath.lowercase()
        val mismatchNotes =
            "atmosphere/contents/${titleId.hex}/cheats/${otherBuildId.hex}/notes.txt"
        val unsafeArchives = linkedMapOf(
            "parent traversal" to zipOf("../escape.txt" to validCheat),
            "embedded parent traversal" to zipOf(
                "atmosphere/contents/${titleId.hex}/cheats/../${buildId.hex}.txt" to validCheat,
            ),
            "absolute path" to zipOf("/$cheatPath" to validCheat),
            "windows drive path" to zipOf("C:/$cheatPath" to validCheat),
            "UNC path" to zipOf("//server/share/$cheatPath" to validCheat),
            "backslash path" to zipOf(cheatPath.replace('/', '\\') to validCheat),
            "dot segment" to zipOf(
                "atmosphere/contents/${titleId.hex}/cheats/./${buildId.hex}.txt" to validCheat,
            ),
            "empty segment" to zipOf(cheatPath.replace("/cheats/", "/cheats//") to validCheat),
            "unicode filename" to zipOf(cheatPath.replace("cheats", "cheats／hidden") to validCheat),
            "duplicate canonical cheat" to zipOf(
                cheatPath to validCheat,
                lowercaseCheat to validCheat,
            ),
            "duplicate canonical notes" to zipOf(
                cheatPath to validCheat,
                notesPath to notes,
                lowercaseNotes to notes,
            ),
            "extra file" to zipOf(cheatPath to validCheat, "$cheatPath.bak" to byteArrayOf(1)),
            "extra directory" to zipOf(
                cheatPath to validCheat,
                "atmosphere/contents/${titleId.hex}/cheats/" to byteArrayOf(),
            ),
            "mismatched Build ID notes" to zipOf(cheatPath to validCheat, mismatchNotes to notes),
            "missing cheat" to zipOf(notesPath to notes),
        )

        unsafeArchives.forEach { (label, archive) ->
            fixture.assertRejectedWithoutMutation(label, archive)
        }
    }

    @Test
    fun inspectRejectsEncryptedUnsupportedSymlinkAndUnknownSizeMetadataWithoutChangingMirror() {
        val fixture = fixtureWithOldMirror()
        val normal = zipOf(cheatPath to validCheat)
        val unsafeArchives = linkedMapOf(
            "encrypted flag" to patchFirstCentralEntry(normal) { bytes, central ->
                val flags = readU16(bytes, central + 8)
                writeU16(bytes, central + 8, flags or 0x0001)
            },
            "unsupported compression" to patchFirstCentralEntry(normal) { bytes, central ->
                writeU16(bytes, central + 10, 12)
            },
            "symbolic link" to patchFirstCentralEntry(normal) { bytes, central ->
                bytes[central + 5] = 3 // Unix creator system.
                writeU32(bytes, central + 38, (0xA1FFL shl 16))
            },
            "ZIP64 unknown size marker" to patchFirstCentralEntry(normal) { bytes, central ->
                writeU32(bytes, central + 24, 0xFFFF_FFFFL)
            },
            "ZIP64 extra field" to zip64ExtraArchive(),
        )

        unsafeArchives.forEach { (label, archive) ->
            fixture.assertRejectedWithoutMutation(label, archive)
        }
    }

    @Test
    fun inspectRejectsReservedAndMaskedHeaderFlagsWithoutChangingMirror() {
        val fixture = fixtureWithOldMirror()
        val normal = zipOf(cheatPath to validCheat)

        fixture.assertRejectedWithoutMutation("patched-data flag bit 5", patchFirstEntryFlags(normal, 0x0020))
        fixture.assertRejectedWithoutMutation("masked-header flag bit 13", patchFirstEntryFlags(normal, 0x2000))
    }

    @Test
    fun inspectRejectsDecoyLocalHeaderEmbeddedInExtraWithoutChangingMirror() {
        val fixture = fixtureWithOldMirror()

        fixture.assertRejectedWithoutMutation("central offset points into local extra", decoyLocalHeaderArchive())
    }

    @Test
    fun inspectRejectsMalformedDiskCentralCrcLocalAndDescriptorMetadataWithoutChangingMirror() {
        val fixture = fixtureWithOldMirror()
        val normal = zipOf(cheatPath to validCheat)
        val descriptor = findSignature(normal, 0x08074B50)
        check(descriptor >= 0)
        val malformed = linkedMapOf(
            "multi-disk EOCD" to normal.copyOf().also { bytes ->
                val eocd = findSignature(bytes, 0x06054B50)
                writeU16(bytes, eocd + 4, 1)
            },
            "truncated EOCD" to normal.copyOf(normal.size - 3),
            "truncated central entry" to normal.copyOf().also { bytes ->
                val central = findSignature(bytes, 0x02014B50)
                writeU16(bytes, central + 28, 0x7FFF)
            },
            "central CRC mismatch" to normal.copyOf().also { bytes ->
                val central = findSignature(bytes, 0x02014B50)
                writeU32(bytes, central + 16, readU32(bytes, central + 16) xor 1)
            },
            "local flags mismatch" to normal.copyOf().also { bytes ->
                writeU16(bytes, 6, readU16(bytes, 6) xor 0x0800)
            },
            "descriptor local CRC mismatch" to normal.copyOf().also { bytes ->
                val central = findSignature(bytes, 0x02014B50)
                writeU32(bytes, 14, readU32(bytes, central + 16) xor 2)
            },
            "bad data descriptor" to normal.copyOf().also { bytes ->
                writeU32(bytes, descriptor + 4, readU32(bytes, descriptor + 4) xor 1)
            },
        )

        malformed.forEach { (label, archive) -> fixture.assertRejectedWithoutMutation(label, archive) }
    }

    @Test
    fun inspectRejectsEntryCountAndExpandedSizeLimitsWithoutChangingMirror() {
        val fixture = fixtureWithOldMirror(
            limits = ZipLimits(
                maxEntries = 100,
                maxEntryBytes = validCheat.size.toLong(),
                maxExpandedBytes = (validCheat.size + notes.size - 1).toLong(),
                maxArchiveBytes = 2L * 1024 * 1024,
            ),
        )
        val tooManyEntries = zipOf(
            *((0..100).map { index -> "extra-$index" to byteArrayOf(index.toByte()) }.toTypedArray()),
        )
        val oversizedEntry = zipOf(cheatPath to (validCheat + byteArrayOf('\n'.code.toByte())))
        val excessiveTotal = zipOf(cheatPath to validCheat, notesPath to notes)

        fixture.assertRejectedWithoutMutation("101 entries", tooManyEntries)
        fixture.assertRejectedWithoutMutation("per-entry expanded limit", oversizedEntry)
        fixture.assertRejectedWithoutMutation("cumulative expanded limit", excessiveTotal)
    }

    @Test
    fun inspectRejectsOversizedArchiveBeforeZipParsingWithoutChangingMirror() {
        val fixture = fixtureWithOldMirror(
            limits = ZipLimits(
                maxEntries = 100,
                maxEntryBytes = 1024,
                maxExpandedBytes = 2048,
                maxArchiveBytes = 16,
            ),
        )

        fixture.assertRejectedWithoutMutation("archive byte limit", ByteArray(17) { 0x41 })
    }

    @Test
    fun inspectRejectsInvalidUtf8AndMalformedCheatWithoutChangingMirror() {
        val fixture = fixtureWithOldMirror()
        val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)
        val malformed = "04000000 00112233 00000063\n".toByteArray()

        fixture.assertRejectedWithoutMutation("invalid UTF-8", zipOf(cheatPath to invalidUtf8))
        fixture.assertRejectedWithoutMutation("parser diagnostics", zipOf(cheatPath to malformed))
    }

    @Test
    fun inspectRejectsSymlinkedMirrorParentWithoutReadingOutsideContent() {
        val root = Files.createTempDirectory("inspect-parent-link-")
        val outside = Files.createTempDirectory("inspect-parent-link-outside-")
        val outsideCheat = outside.resolve("contents/${titleId.hex}/cheats/${buildId.hex}.txt")
        Files.createDirectories(outsideCheat.parent)
        val sentinel = "[Outside]\n04000000 00000000 00000007\n".toByteArray()
        Files.write(outsideCheat, sentinel)
        try {
            Files.createSymbolicLink(root.resolve("atmosphere"), outside)
        } catch (error: Exception) {
            assumeNoException("Symbolic links are unavailable on this platform", error)
        }
        val service = CheatZipService(CheatMirror(root), Files.createTempDirectory("inspect-parent-link-cache-"))

        assertThrows(ZipImportError::class.java) {
            service.inspect(zipOf(cheatPath to validCheat))
        }

        assertArrayEquals(sentinel, Files.readAllBytes(outsideCheat))
    }

    @Test
    fun confirmedImportUsesInspectedBytesAndAtomicallyReplacesBothFiles() {
        val fixture = fixtureWithOldMirror()
        val archive = zipOf(cheatPath to validCheat, notesPath to notes)
        val inspection = fixture.service.inspect(archive)
        archive.fill(0) // Mutating caller-owned bytes must not change the pending import.

        fixture.service.importConfirmed(inspection)

        assertArrayEquals(validCheat, Files.readAllBytes(fixture.mirror.cheatPath(titleId, buildId)))
        assertArrayEquals(notes, Files.readAllBytes(fixture.mirror.notesPath(titleId, buildId)))
        assertNoTransactionArtifacts(fixture.mirror.root)
        assertThrows(ZipImportError::class.java) {
            fixture.service.importConfirmed(inspection)
        }
    }

    @Test
    fun confirmedImportWithoutNotesPreservesExistingNotes() {
        val fixture = fixtureWithOldMirror()
        val inspection = fixture.service.inspect(zipOf(cheatPath to validCheat))

        fixture.service.importConfirmed(inspection)

        assertArrayEquals(validCheat, Files.readAllBytes(fixture.mirror.cheatPath(titleId, buildId)))
        assertArrayEquals(fixture.oldNotes, Files.readAllBytes(fixture.mirror.notesPath(titleId, buildId)))
    }

    @Test
    fun confirmedImportRejectsInspectionFromAnotherServiceWithoutChangingMirror() {
        val fixture = fixtureWithOldMirror()
        val otherRoot = Files.createTempDirectory("other-mirror-")
        val otherService = CheatZipService(CheatMirror(otherRoot), Files.createTempDirectory("other-cache-"))
        val foreignInspection = otherService.inspect(zipOf(cheatPath to validCheat))

        assertThrows(ZipImportError::class.java) {
            fixture.service.importConfirmed(foreignInspection)
        }

        fixture.assertOldMirrorUnchanged()
    }

    @Test
    fun confirmedImportRejectsWhenMirrorChangedAfterPreview() {
        val fixture = fixtureWithOldMirror()
        val inspection = fixture.service.inspect(zipOf(cheatPath to validCheat, notesPath to notes))
        val newerCheat = "[Newer local edit]\n04000000 00000000 00000002\n".toByteArray()
        fixture.mirror.atomicReplace(fixture.mirror.cheatPath(titleId, buildId), newerCheat)

        assertThrows(ZipImportError::class.java) {
            fixture.service.importConfirmed(inspection)
        }

        assertArrayEquals(newerCheat, Files.readAllBytes(fixture.mirror.cheatPath(titleId, buildId)))
        assertArrayEquals(fixture.oldNotes, Files.readAllBytes(fixture.mirror.notesPath(titleId, buildId)))
    }

    @Test
    fun concurrentAtomicEditWaitsForImportAndItsNewerContentIsRetained() {
        val root = Files.createTempDirectory("import-edit-lock-")
        val mirror = CheatMirror(root)
        val oldCheat = "[Old]\n04000000 00000000 00000001\n".toByteArray()
        mirror.atomicReplace(mirror.cheatPath(titleId, buildId), oldCheat)
        val importEntered = CountDownLatch(1)
        val allowImport = CountDownLatch(1)
        val fileOps = ZipTransactionFileOps { source, target ->
            if (target == mirror.cheatPath(titleId, buildId) && ".stage-" in source.fileName.toString()) {
                importEntered.countDown()
                check(allowImport.await(5, TimeUnit.SECONDS))
            }
            mirror.moveReplacing(source, target)
        }
        val service = CheatZipService(
            mirror,
            Files.createTempDirectory("import-edit-cache-"),
            CheatFileParser(),
            ZipLimits(),
            fileOps,
        )
        val inspection = service.inspect(zipOf(cheatPath to validCheat))
        val newerEdit = "[Edited]\n04000000 00000000 00000009\n".toByteArray()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val importing = executor.submit<Unit> { service.importConfirmed(inspection) }
            assertTrue(importEntered.await(5, TimeUnit.SECONDS))
            val editing = executor.submit<Unit> {
                mirror.atomicReplace(mirror.cheatPath(titleId, buildId), newerEdit)
            }

            assertThrows(TimeoutException::class.java) { editing.get(100, TimeUnit.MILLISECONDS) }
            allowImport.countDown()
            importing.get(5, TimeUnit.SECONDS)
            editing.get(5, TimeUnit.SECONDS)
            assertArrayEquals(newerEdit, Files.readAllBytes(mirror.cheatPath(titleId, buildId)))
            assertNoTransactionArtifacts(root)
        } finally {
            allowImport.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun secondInterleavingImportWaitsThenRejectsItsStaleSnapshot() {
        val root = Files.createTempDirectory("two-import-lock-")
        val mirror = CheatMirror(root)
        mirror.atomicReplace(
            mirror.cheatPath(titleId, buildId),
            "[Old]\n04000000 00000000 00000001\n".toByteArray(),
        )
        val firstCheat = "[First]\n04000000 00000000 00000002\n".toByteArray()
        val secondCheat = "[Second]\n04000000 00000000 00000003\n".toByteArray()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val blockingOps = ZipTransactionFileOps { source, target ->
            if (target == mirror.cheatPath(titleId, buildId) && ".stage-" in source.fileName.toString()) {
                firstEntered.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
            }
            mirror.moveReplacing(source, target)
        }
        val firstService = CheatZipService(
            mirror,
            Files.createTempDirectory("two-import-cache-1-"),
            CheatFileParser(),
            ZipLimits(),
            blockingOps,
        )
        val secondService = CheatZipService(mirror, Files.createTempDirectory("two-import-cache-2-"))
        val firstInspection = firstService.inspect(zipOf(cheatPath to firstCheat))
        val secondInspection = secondService.inspect(zipOf(cheatPath to secondCheat))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstImport = executor.submit<Unit> { firstService.importConfirmed(firstInspection) }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val secondImport = executor.submit<Unit> { secondService.importConfirmed(secondInspection) }

            assertThrows(TimeoutException::class.java) { secondImport.get(100, TimeUnit.MILLISECONDS) }
            releaseFirst.countDown()
            firstImport.get(5, TimeUnit.SECONDS)
            val failure = assertThrows(ExecutionException::class.java) {
                secondImport.get(5, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is ZipImportError)
            assertArrayEquals(firstCheat, Files.readAllBytes(mirror.cheatPath(titleId, buildId)))
            assertNoTransactionArtifacts(root)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun transactionRollsBackCheatWhenPublishingNotesFails() {
        val root = Files.createTempDirectory("rollback-mirror-")
        val mirror = CheatMirror(root)
        val cache = Files.createTempDirectory("rollback-cache-")
        val oldCheat = "[Old]\n04000000 00000000 00000001\n".toByteArray()
        val oldNotes = "old notes".toByteArray()
        mirror.atomicReplace(mirror.cheatPath(titleId, buildId), oldCheat)
        mirror.atomicReplace(mirror.notesPath(titleId, buildId), oldNotes)
        val fileOps = object : ZipTransactionFileOps {
            override fun moveReplacing(source: Path, target: Path) {
                if (target == mirror.notesPath(titleId, buildId) && source.fileName.toString().contains(".stage-")) {
                    throw java.io.IOException("injected notes publish failure")
                }
                mirror.moveReplacing(source, target)
            }
        }
        val service = CheatZipService(mirror, cache, CheatFileParser(), ZipLimits(), fileOps)
        val inspection = service.inspect(zipOf(cheatPath to validCheat, notesPath to notes))

        assertThrows(ZipImportError::class.java) {
            service.importConfirmed(inspection)
        }

        assertArrayEquals(oldCheat, Files.readAllBytes(mirror.cheatPath(titleId, buildId)))
        assertArrayEquals(oldNotes, Files.readAllBytes(mirror.notesPath(titleId, buildId)))
        assertNoTransactionArtifacts(root)
    }

    @Test
    fun failedImportRemovesDirectoriesItCreatedInAnEmptyMirror() {
        val root = Files.createTempDirectory("rollback-empty-mirror-")
        val mirror = CheatMirror(root)
        val fileOps = ZipTransactionFileOps { source, target ->
            if (source.fileName.toString().contains(".stage-")) {
                throw java.io.IOException("injected first publish failure")
            }
            mirror.moveReplacing(source, target)
        }
        val service = CheatZipService(
            mirror,
            Files.createTempDirectory("rollback-empty-cache-"),
            CheatFileParser(),
            ZipLimits(),
            fileOps,
        )
        val inspection = service.inspect(zipOf(cheatPath to validCheat))

        assertThrows(ZipImportError::class.java) {
            service.importConfirmed(inspection)
        }

        Files.list(root).use { children -> assertEquals(0L, children.count()) }
    }

    @Test
    fun failedRollbackPreservesJournalAndAFreshServiceCompletesRecovery() {
        val root = Files.createTempDirectory("rollback-backup-mirror-")
        val mirror = CheatMirror(root)
        val oldCheat = "[Old]\n04000000 00000000 00000001\n".toByteArray()
        val oldNotes = "old notes".toByteArray()
        mirror.atomicReplace(mirror.cheatPath(titleId, buildId), oldCheat)
        mirror.atomicReplace(mirror.notesPath(titleId, buildId), oldNotes)
        val fileOps = object : ZipTransactionFileOps {
            override fun moveReplacing(source: Path, target: Path) {
                val sourceName = source.fileName.toString()
                if (target == mirror.notesPath(titleId, buildId) && ".stage-" in sourceName) {
                    throw java.io.IOException("injected notes publish failure")
                }
                if (target == mirror.cheatPath(titleId, buildId) && ".backup-" in sourceName) {
                    throw java.io.IOException("injected rollback failure")
                }
                mirror.moveReplacing(source, target)
            }
        }
        val service = CheatZipService(
            mirror,
            Files.createTempDirectory("rollback-backup-cache-"),
            CheatFileParser(),
            ZipLimits(),
            fileOps,
        )
        val inspection = service.inspect(zipOf(cheatPath to validCheat, notesPath to notes))

        assertThrows(ZipImportError::class.java) {
            service.importConfirmed(inspection)
        }

        Files.walk(root).use { paths ->
            assertTrue(paths.anyMatch { it.fileName.toString().endsWith(".journal") })
        }

        CheatZipService(mirror, Files.createTempDirectory("rollback-recovery-cache-"))

        assertArrayEquals(oldCheat, Files.readAllBytes(mirror.cheatPath(titleId, buildId)))
        assertArrayEquals(oldNotes, Files.readAllBytes(mirror.notesPath(titleId, buildId)))
        assertNoTransactionArtifacts(root)
    }

    @Test
    fun newServiceRecoversOldPairAfterCrashFollowingBackups() {
        val crash = constructCrashState("BACKED_UP", publishedEntries = 0)

        CheatZipService(crash.mirror, crash.cache)

        crash.assertOldPair()
        assertNoTransactionArtifacts(crash.mirror.root)
    }

    @Test
    fun newServiceRecoversOldPairAfterCrashFollowingOnlyCheatPublish() {
        val crash = constructCrashState("BACKED_UP", publishedEntries = 1)

        CheatZipService(crash.mirror, crash.cache)

        crash.assertOldPair()
        assertNoTransactionArtifacts(crash.mirror.root)
    }

    @Test
    fun newServiceFinishesNewPairAfterCrashFollowingBothPublishes() {
        val crash = constructCrashState("PUBLISHED", publishedEntries = 2)

        CheatZipService(crash.mirror, crash.cache)

        crash.assertNewPair()
        assertNoTransactionArtifacts(crash.mirror.root)
    }

    @Test
    fun recoveryDiscardsOrphanAtomicJournalTempBeforeUsingDurableJournal() {
        val crash = constructCrashState("BACKED_UP", publishedEntries = 1)
        val journalDirectory = crash.mirror.root.resolve(".transactions")
        Files.write(
            journalDirectory.resolve(
                ".11111111-2222-3333-4444-555555555555.journal.tmp-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            ),
            "partial newer journal".toByteArray(),
        )

        CheatZipService(crash.mirror, crash.cache)

        crash.assertOldPair()
        assertNoTransactionArtifacts(crash.mirror.root)
    }

    @Test
    fun recoveryRejectsUntrustedJournalIdentifiersWithoutTouchingOutsideFile() {
        val base = Files.createTempDirectory("journal-untrusted-")
        val root = base.resolve("mirror")
        Files.createDirectories(root)
        val mirror = CheatMirror(root)
        val transactionId = "11111111-2222-3333-4444-555555555555"
        val journalDir = root.resolve(".transactions")
        Files.createDirectories(journalDir)
        Files.write(
            journalDir.resolve("$transactionId.journal"),
            (
                "version=1\n" +
                    "transactionId=$transactionId\n" +
                    "phase=BACKED_UP\n" +
                    "titleId=../../escape\n" +
                    "buildId=${buildId.hex}\n" +
                    "hasNotes=true\n" +
                    "oldCheat=true\n" +
                    "oldNotes=true\n"
                ).toByteArray(),
        )
        val outside = base.resolve("escape")
        Files.write(outside, "sentinel".toByteArray())

        assertThrows(ZipImportError::class.java) {
            CheatZipService(mirror, Files.createTempDirectory("journal-untrusted-cache-"))
        }

        assertArrayEquals("sentinel".toByteArray(), Files.readAllBytes(outside))
    }

    @Test
    fun exportIsDeterministicAndContainsOnlyCanonicalCheatThenNotes() {
        val root = Files.createTempDirectory("export-mirror-")
        val mirror = CheatMirror(root)
        val service = CheatZipService(mirror, Files.createTempDirectory("export-cache-"))
        mirror.atomicReplace(mirror.cheatPath(titleId, buildId), validCheat)
        mirror.atomicReplace(mirror.notesPath(titleId, buildId), notes)

        val first = service.export(titleId, buildId)
        val second = service.export(titleId, buildId)

        assertArrayEquals(first, second)
        val entries = readZip(first)
        assertEquals(listOf(cheatPath, notesPath), entries.map { it.first })
        assertArrayEquals(validCheat, entries[0].second)
        assertArrayEquals(notes, entries[1].second)
        assertFalse(entries.any { (name, _) -> name.endsWith('/') })
    }

    @Test
    fun exportUsesStoredEntriesWithFixedMetadataAndNoExtraFields() {
        val root = Files.createTempDirectory("export-metadata-")
        val mirror = CheatMirror(root)
        val service = CheatZipService(mirror, Files.createTempDirectory("export-metadata-cache-"))
        mirror.atomicReplace(mirror.cheatPath(titleId, buildId), validCheat)
        mirror.atomicReplace(mirror.notesPath(titleId, buildId), notes)

        ZipInputStream(ByteArrayInputStream(service.export(titleId, buildId))).use { zip ->
            repeat(2) {
                val entry = zip.nextEntry
                assertNotNull("Missing export entry $it", entry)
                requireNotNull(entry)
                assertEquals(ZipEntry.STORED, entry.method)
                assertEquals(315_532_800_000L, entry.time)
                assertTrue(entry.extra == null || entry.extra.isEmpty())
                assertEquals(null, entry.comment)
                zip.readBytes()
                zip.closeEntry()
            }
            assertEquals(null, zip.nextEntry)
        }
    }

    @Test
    fun exportWithoutNotesIncludesAnEmptyNotesFileOnlyWhenRequested() {
        val root = Files.createTempDirectory("export-optional-notes-")
        val mirror = CheatMirror(root)
        val service = CheatZipService(mirror, Files.createTempDirectory("export-cache-"))
        mirror.atomicReplace(mirror.cheatPath(titleId, buildId), validCheat)

        assertThrows(ZipExportError::class.java) {
            service.export(titleId, buildId)
        }
        val archive = service.export(titleId, buildId, includeEmptyNotes = true)

        val entries = readZip(archive)
        assertEquals(listOf(cheatPath, notesPath), entries.map { it.first })
        assertArrayEquals(byteArrayOf(), entries[1].second)
    }

    @Test
    fun exportFailsWhenCheatFileIsMissing() {
        val mirror = CheatMirror(Files.createTempDirectory("export-missing-cheat-"))
        val service = CheatZipService(mirror, Files.createTempDirectory("export-cache-"))

        assertThrows(ZipExportError::class.java) {
            service.export(titleId, buildId, includeEmptyNotes = true)
        }
    }

    @Test
    fun exportRejectsSymlinkedCheatWithoutReadingItsOutsideReferent() {
        val root = Files.createTempDirectory("export-target-link-")
        val mirror = CheatMirror(root)
        val target = mirror.cheatPath(titleId, buildId)
        Files.createDirectories(target.parent)
        val outside = Files.createTempFile("export-target-link-outside-", ".txt")
        Files.write(outside, validCheat)
        try {
            Files.createSymbolicLink(target, outside)
        } catch (error: Exception) {
            assumeNoException("Symbolic links are unavailable on this platform", error)
        }
        val service = CheatZipService(mirror, Files.createTempDirectory("export-target-link-cache-"))

        assertThrows(ZipExportError::class.java) {
            service.export(titleId, buildId, includeEmptyNotes = true)
        }

        assertArrayEquals(validCheat, Files.readAllBytes(outside))
    }

    private fun fixtureWithOldMirror(limits: ZipLimits = ZipLimits()): Fixture {
        val base = Files.createTempDirectory("zip-fixture-")
        val root = base.resolve("mirror")
        Files.createDirectories(root)
        val cache = Files.createTempDirectory("zip-cache-")
        val mirror = CheatMirror(root)
        val oldCheat = "[Old]\n04000000 00000000 00000001\n".toByteArray()
        val oldNotes = "old notes".toByteArray()
        mirror.atomicReplace(mirror.cheatPath(titleId, buildId), oldCheat)
        mirror.atomicReplace(mirror.notesPath(titleId, buildId), oldNotes)
        val outsideSentinel = base.resolve("outside-sentinel.txt")
        val outsideBytes = "outside must remain untouched".toByteArray()
        Files.write(outsideSentinel, outsideBytes)
        return Fixture(
            mirror = mirror,
            service = CheatZipService(mirror, cache, limits = limits),
            oldCheat = oldCheat,
            oldNotes = oldNotes,
            baselineTree = snapshotTree(root),
            outsideSentinel = outsideSentinel,
            outsideBytes = outsideBytes,
        )
    }

    private inner class Fixture(
        val mirror: CheatMirror,
        val service: CheatZipService,
        val oldCheat: ByteArray,
        val oldNotes: ByteArray,
        val baselineTree: Map<String, String>,
        val outsideSentinel: Path,
        val outsideBytes: ByteArray,
    ) {
        fun assertRejectedWithoutMutation(label: String, archive: ByteArray) {
            try {
                service.inspect(archive)
                fail("Expected rejection for $label")
            } catch (_: ZipImportError) {
                // Expected.
            }
            assertOldMirrorUnchanged()
        }

        fun assertOldMirrorUnchanged() {
            assertArrayEquals(oldCheat, Files.readAllBytes(mirror.cheatPath(titleId, buildId)))
            assertArrayEquals(oldNotes, Files.readAllBytes(mirror.notesPath(titleId, buildId)))
            assertEquals(baselineTree, snapshotTree(mirror.root))
            assertArrayEquals(outsideBytes, Files.readAllBytes(outsideSentinel))
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun zipOfWithExtra(name: String, content: ByteArray, extra: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name).apply { this.extra = extra })
            zip.write(content)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun zip64ExtraArchive(): ByteArray {
        val archive = zipOfWithExtra(
            cheatPath,
            validCheat,
            byteArrayOf(0xFE.toByte(), 0xCA.toByte(), 0x00, 0x00),
        )
        val local = findSignature(archive, 0x04034B50)
        val central = findSignature(archive, 0x02014B50)
        val localExtra = local + 30 + readU16(archive, local + 26)
        val centralExtra = central + 46 + readU16(archive, central + 28)
        check(readU16(archive, local + 28) >= 4 && readU16(archive, central + 30) >= 4)
        writeU16(archive, localExtra, 0x0001)
        writeU16(archive, centralExtra, 0x0001)
        return archive
    }

    private fun readZip(archive: ByteArray): List<Pair<String, ByteArray>> {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name to zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun patchFirstCentralEntry(
        source: ByteArray,
        patch: (ByteArray, Int) -> Unit,
    ): ByteArray = source.copyOf().also { bytes ->
        val central = findSignature(bytes, 0x02014B50)
        check(central >= 0)
        patch(bytes, central)
    }

    private fun patchFirstEntryFlags(source: ByteArray, addedFlags: Int): ByteArray =
        source.copyOf().also { bytes ->
            val central = findSignature(bytes, 0x02014B50)
            val local = findSignature(bytes, 0x04034B50)
            check(central >= 0 && local >= 0)
            writeU16(bytes, central + 8, readU16(bytes, central + 8) or addedFlags)
            writeU16(bytes, local + 6, readU16(bytes, local + 6) or addedFlags)
        }

    private fun removeFirstDescriptorSignature(source: ByteArray): ByteArray {
        val descriptor = findSignature(source, 0x08074B50)
        val oldCentral = findSignature(source, 0x02014B50)
        val oldEocd = findSignature(source, 0x06054B50)
        check(descriptor in 0 until oldCentral && oldCentral < oldEocd)
        val result = ByteArray(source.size - 4)
        source.copyInto(result, 0, 0, descriptor)
        source.copyInto(result, descriptor, descriptor + 4, source.size)
        val newEocd = oldEocd - 4
        writeU32(result, newEocd + 16, (oldCentral - 4).toLong())
        return result
    }

    private fun findSignature(bytes: ByteArray, signature: Int): Int {
        for (index in 0..bytes.size - 4) {
            if (readU32(bytes, index) == signature.toLong()) return index
        }
        return -1
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        (readU16(bytes, offset).toLong() or (readU16(bytes, offset + 2).toLong() shl 16)) and 0xFFFF_FFFFL

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        writeU16(bytes, offset, (value and 0xFFFF).toInt())
        writeU16(bytes, offset + 2, ((value ushr 16) and 0xFFFF).toInt())
    }

    private fun assertNoTransactionArtifacts(root: Path) {
        Files.walk(root).use { paths ->
            assertTrue(
                paths.noneMatch { path ->
                    val name = path.fileName?.toString().orEmpty()
                    ".stage-" in name || ".backup-" in name || ".tmp-" in name || name.endsWith(".journal")
                },
            )
        }
        assertFalse(Files.exists(root.resolve(".transactions"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    private fun constructCrashState(phase: String, publishedEntries: Int): CrashFixture {
        val root = Files.createTempDirectory("journal-crash-mirror-")
        val cache = Files.createTempDirectory("journal-crash-cache-")
        val mirror = CheatMirror(root)
        val oldCheat = "[Old]\n04000000 00000000 00000001\n".toByteArray()
        val oldNotes = "old notes".toByteArray()
        val newCheat = "[New]\n04000000 00000000 00000002\n".toByteArray()
        val newNotes = "new notes".toByteArray()
        val cheatTarget = mirror.cheatPath(titleId, buildId)
        val notesTarget = mirror.notesPath(titleId, buildId)
        mirror.atomicReplace(cheatTarget, oldCheat)
        mirror.atomicReplace(notesTarget, oldNotes)
        val transactionId = "11111111-2222-3333-4444-555555555555"
        val cheatStage = cheatTarget.parent.resolve(".${cheatTarget.fileName}.stage-$transactionId")
        val notesStage = notesTarget.parent.resolve(".${notesTarget.fileName}.stage-$transactionId")
        val cheatBackup = cheatTarget.parent.resolve(".${cheatTarget.fileName}.backup-$transactionId")
        val notesBackup = notesTarget.parent.resolve(".${notesTarget.fileName}.backup-$transactionId")
        Files.write(cheatStage, newCheat)
        Files.write(notesStage, newNotes)
        Files.move(cheatTarget, cheatBackup, StandardCopyOption.REPLACE_EXISTING)
        Files.move(notesTarget, notesBackup, StandardCopyOption.REPLACE_EXISTING)
        if (publishedEntries >= 1) Files.move(cheatStage, cheatTarget)
        if (publishedEntries >= 2) Files.move(notesStage, notesTarget)
        val journalDir = root.resolve(".transactions")
        Files.createDirectories(journalDir)
        Files.write(
            journalDir.resolve("$transactionId.journal"),
            (
                "version=1\n" +
                    "transactionId=$transactionId\n" +
                    "phase=$phase\n" +
                    "titleId=${titleId.hex}\n" +
                    "buildId=${buildId.hex}\n" +
                    "hasNotes=true\n" +
                    "oldCheat=true\n" +
                    "oldNotes=true\n"
                ).toByteArray(),
        )
        return CrashFixture(mirror, cache, oldCheat, oldNotes, newCheat, newNotes)
    }

    private inner class CrashFixture(
        val mirror: CheatMirror,
        val cache: Path,
        private val oldCheat: ByteArray,
        private val oldNotes: ByteArray,
        private val newCheat: ByteArray,
        private val newNotes: ByteArray,
    ) {
        fun assertOldPair() {
            assertArrayEquals(oldCheat, Files.readAllBytes(mirror.cheatPath(titleId, buildId)))
            assertArrayEquals(oldNotes, Files.readAllBytes(mirror.notesPath(titleId, buildId)))
        }

        fun assertNewPair() {
            assertArrayEquals(newCheat, Files.readAllBytes(mirror.cheatPath(titleId, buildId)))
            assertArrayEquals(newNotes, Files.readAllBytes(mirror.notesPath(titleId, buildId)))
        }
    }

    private fun snapshotTree(root: Path): Map<String, String> =
        Files.walk(root).use { paths ->
            val snapshot = linkedMapOf<String, String>()
            paths.sorted().forEach { path ->
                val relative = root.relativize(path).toString().replace('\\', '/')
                snapshot[relative] = if (Files.isDirectory(path)) {
                    "<directory>"
                } else {
                    Files.readAllBytes(path).joinToString("") { "%02x".format(it) }
                }
            }
            snapshot
        }

    private fun decoyLocalHeaderArchive(): ByteArray {
        val content = validCheat
        val name = cheatPath.toByteArray(Charsets.US_ASCII)
        val crc = java.util.zip.CRC32().apply { update(content) }.value
        val fake = ByteArrayOutputStream().apply {
            writeLe32(0x04034B50)
            writeLe16(20)
            writeLe16(0)
            writeLe16(ZipEntry.STORED)
            writeLe16(0)
            writeLe16(0)
            writeLe32(crc)
            writeLe32(content.size.toLong())
            writeLe32(content.size.toLong())
            writeLe16(name.size)
            writeLe16(0)
            write(name)
            write(content)
        }.toByteArray()
        val local = ByteArrayOutputStream().apply {
            writeLe32(0x04034B50)
            writeLe16(20)
            writeLe16(0)
            writeLe16(ZipEntry.STORED)
            writeLe16(0)
            writeLe16(0)
            writeLe32(crc)
            writeLe32(content.size.toLong())
            writeLe32(content.size.toLong())
            writeLe16(name.size)
            writeLe16(fake.size)
            write(name)
            write(fake)
            write(content)
        }.toByteArray()
        val decoyOffset = (30 + name.size).toLong()
        val central = ByteArrayOutputStream().apply {
            writeLe32(0x02014B50)
            writeLe16(20)
            writeLe16(20)
            writeLe16(0)
            writeLe16(ZipEntry.STORED)
            writeLe16(0)
            writeLe16(0)
            writeLe32(crc)
            writeLe32(content.size.toLong())
            writeLe32(content.size.toLong())
            writeLe16(name.size)
            writeLe16(0)
            writeLe16(0)
            writeLe16(0)
            writeLe16(0)
            writeLe32(0)
            writeLe32(decoyOffset)
            write(name)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(local)
            write(central)
            writeLe32(0x06054B50)
            writeLe16(0)
            writeLe16(0)
            writeLe16(1)
            writeLe16(1)
            writeLe32(central.size.toLong())
            writeLe32(local.size.toLong())
            writeLe16(0)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLe32(value: Long) {
        writeLe16((value and 0xFFFF).toInt())
        writeLe16(((value ushr 16) and 0xFFFF).toInt())
    }
}
