package com.nscheatmanager.app.data.files

import com.nscheatmanager.app.cheats.parser.CheatFileParser
import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
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
        )

        unsafeArchives.forEach { (label, archive) ->
            fixture.assertRejectedWithoutMutation(label, archive)
        }
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
    fun failedRollbackPreservesTheLastRecoverableBackup() {
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

        val recoverableBackups = Files.walk(root).use { paths ->
            paths.filter { ".backup-" in it.fileName.toString() }.toList()
        }
        assertEquals(1, recoverableBackups.size)
        assertArrayEquals(oldCheat, Files.readAllBytes(recoverableBackups.single()))
        assertArrayEquals(oldNotes, Files.readAllBytes(mirror.notesPath(titleId, buildId)))
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

    private fun fixtureWithOldMirror(limits: ZipLimits = ZipLimits()): Fixture {
        val root = Files.createTempDirectory("zip-mirror-")
        val cache = Files.createTempDirectory("zip-cache-")
        val mirror = CheatMirror(root)
        val oldCheat = "[Old]\n04000000 00000000 00000001\n".toByteArray()
        val oldNotes = "old notes".toByteArray()
        mirror.atomicReplace(mirror.cheatPath(titleId, buildId), oldCheat)
        mirror.atomicReplace(mirror.notesPath(titleId, buildId), oldNotes)
        return Fixture(
            mirror = mirror,
            service = CheatZipService(mirror, cache, limits = limits),
            oldCheat = oldCheat,
            oldNotes = oldNotes,
        )
    }

    private inner class Fixture(
        val mirror: CheatMirror,
        val service: CheatZipService,
        val oldCheat: ByteArray,
        val oldNotes: ByteArray,
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
                    ".stage-" in name || ".backup-" in name || ".tmp-" in name
                },
            )
        }
    }
}
