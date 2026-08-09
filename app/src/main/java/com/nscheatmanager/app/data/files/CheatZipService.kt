package com.nscheatmanager.app.data.files

import com.nscheatmanager.app.cheats.parser.CheatFileParser
import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ZipLimits(
    val maxEntries: Int = 100,
    val maxEntryBytes: Long = 2L * 1024 * 1024,
    val maxExpandedBytes: Long = 4L * 1024 * 1024,
    val maxArchiveBytes: Long = 8L * 1024 * 1024,
) {
    init {
        require(maxEntries in 1..0xFFFE)
        require(maxEntryBytes in 1..Int.MAX_VALUE.toLong())
        require(maxExpandedBytes in 1..Int.MAX_VALUE.toLong())
        require(maxArchiveBytes in 1..Int.MAX_VALUE.toLong())
    }
}

data class ZipInspectionEntry(
    val relativePath: String,
    val expandedSize: Long,
)

data class OverwriteImpact(
    val cheat: Boolean,
    val notes: Boolean,
)

/** An immutable, service-bound preview. It cannot itself provide archive content. */
class ZipInspection internal constructor(
    val titleId: TitleId,
    val buildId: BuildId,
    val entries: List<ZipInspectionEntry>,
    val groupCount: Int,
    val overwriteImpact: OverwriteImpact,
    internal val token: String,
)

class ZipImportError(
    message: String,
    cause: Throwable? = null,
    val parseDiagnostic: com.nscheatmanager.app.cheats.parser.CheatParseDiagnostic? = null,
) : Exception(message, cause)

class ZipExportError(message: String, cause: Throwable? = null) : Exception(message, cause)

internal fun interface ZipTransactionFileOps {
    fun moveReplacing(source: Path, target: Path)
}

/** Strict one-game Atmosphere ZIP interchange. */
class CheatZipService internal constructor(
    private val mirror: CheatMirror,
    cacheDir: Path,
    private val parser: CheatFileParser,
    private val limits: ZipLimits,
    private val fileOps: ZipTransactionFileOps,
) {
    constructor(
        mirror: CheatMirror,
        cacheDir: Path,
        parser: CheatFileParser = CheatFileParser(),
        limits: ZipLimits = ZipLimits(),
    ) : this(
        mirror = mirror,
        cacheDir = cacheDir,
        parser = parser,
        limits = limits,
        fileOps = ZipTransactionFileOps(mirror::moveReplacing),
    )

    private val cacheDir = cacheDir.toAbsolutePath().normalize()
    private val pendingLock = Any()
    private val pendingImports = mutableMapOf<String, PendingImport>()

    init {
        mirror.withWriteTransaction { recoverPendingTransactionsLocked() }
    }

    fun inspect(archive: ByteArray): ZipInspection =
        inspect(ByteArrayInputStream(archive))

    fun inspect(input: InputStream): ZipInspection {
        val archive = try {
            readArchiveBounded(input)
        } catch (error: ZipImportError) {
            throw error
        } catch (error: Exception) {
            throw ZipImportError("Unable to read ZIP archive", error)
        }

        val validated = validateArchive(archive)
        val cheatText = decodeUtf8(validated.cheat.bytes, "cheat file")
        val parsed = parser.parse(cheatText)
        if (parsed.diagnostics.isNotEmpty()) {
            val first = parsed.diagnostics.first()
            throw ZipImportError("Cheat file is malformed", parseDiagnostic = first)
        }

        return mirror.withWriteTransaction {
            recoverPendingTransactionsLocked()
            val token = UUID.randomUUID().toString()
            val inspection = ZipInspection(
                titleId = validated.titleId,
                buildId = validated.buildId,
                entries = validated.orderedEntries.map { entry ->
                    ZipInspectionEntry(entry.canonicalPath, entry.bytes.size.toLong())
                },
                groupCount = parsed.groups.size,
                overwriteImpact = OverwriteImpact(
                    cheat = Files.exists(mirror.cheatPath(validated.titleId, validated.buildId)),
                    notes = validated.notes != null &&
                        Files.exists(mirror.notesPath(validated.titleId, validated.buildId)),
                ),
                token = token,
            )
            val targetSnapshots = linkedMapOf(
                mirror.cheatPath(validated.titleId, validated.buildId) to
                    captureSnapshot(mirror.cheatPath(validated.titleId, validated.buildId)),
            )
            if (validated.notes != null) {
                val notesPath = mirror.notesPath(validated.titleId, validated.buildId)
                targetSnapshots[notesPath] = captureSnapshot(notesPath)
            }
            val pending = PendingImport(
                archiveBytes = archive.copyOf(),
                digest = sha256(archive),
                inspection = inspection,
                targetSnapshots = targetSnapshots,
            )
            synchronized(pendingLock) {
                // The UI supports one confirmation dialog at a time. Invalidating an older preview also
                // bounds retained attacker-controlled bytes.
                pendingImports.clear()
                pendingImports[token] = pending
            }
            inspection
        }
    }

    /** Imports exactly the immutable bytes associated with a previously returned [inspection]. */
    fun importConfirmed(inspection: ZipInspection) {
        mirror.withWriteTransaction { importConfirmedLocked(inspection) }
    }

    private fun importConfirmedLocked(inspection: ZipInspection) {
        recoverPendingTransactionsLocked()
        val pending = synchronized(pendingLock) {
            pendingImports[inspection.token]
                ?.takeIf { it.inspection === inspection }
                ?.also { pendingImports.remove(inspection.token) }
        } ?: throw ZipImportError("ZIP inspection is unknown, expired, or already imported")

        if (!MessageDigest.isEqual(pending.digest, sha256(pending.archiveBytes))) {
            throw ZipImportError("Inspected ZIP content changed before confirmation")
        }
        pending.targetSnapshots.forEach { (path, expected) ->
            if (captureSnapshot(path) != expected) {
                throw ZipImportError("Local mirror changed after ZIP inspection; inspect again")
            }
        }

        val validated = validateArchive(pending.archiveBytes)
        if (validated.titleId != inspection.titleId || validated.buildId != inspection.buildId) {
            throw ZipImportError("Inspected ZIP identity changed before confirmation")
        }

        Files.createDirectories(cacheDir)
        val extractionRoot = try {
            Files.createTempDirectory(cacheDir, "zip-import-")
        } catch (error: IOException) {
            throw ZipImportError("Unable to create ZIP import staging directory", error)
        }

        try {
            val staged = linkedMapOf<Path, Path>()
            validated.orderedEntries.forEach { entry ->
                val extracted = safeResolve(extractionRoot, entry.canonicalPath)
                Files.createDirectories(requireNotNull(extracted.parent))
                writeCompleteFile(extracted, entry.bytes)
                val destination = when (entry.kind) {
                    EntryKind.CHEAT -> mirror.cheatPath(validated.titleId, validated.buildId)
                    EntryKind.NOTES -> mirror.notesPath(validated.titleId, validated.buildId)
                }
                staged[destination] = extracted
            }
            publishTransaction(
                extracted = staged,
                titleId = validated.titleId,
                buildId = validated.buildId,
                hasNotes = validated.notes != null,
            )
        } catch (error: ZipImportError) {
            throw error
        } catch (error: Exception) {
            throw ZipImportError("Unable to publish imported ZIP", error)
        } finally {
            deleteTree(extractionRoot)
        }
    }

    /**
     * Produces byte-for-byte deterministic output with the cheat first and notes second.
     * Missing notes require an explicit [includeEmptyNotes] confirmation.
     */
    fun export(
        titleId: TitleId,
        buildId: BuildId,
        includeEmptyNotes: Boolean = false,
    ): ByteArray = mirror.withWriteTransaction {
        recoverPendingTransactionsLocked()
        val cheat = readExportFile(mirror.cheatPath(titleId, buildId), required = true)
        val notesPath = mirror.notesPath(titleId, buildId)
        val notes = if (Files.exists(notesPath, LinkOption.NOFOLLOW_LINKS)) {
            readExportFile(notesPath, required = true)
        } else {
            if (!includeEmptyNotes) {
                throw ZipExportError("notes.txt is missing and empty-notes export was not confirmed")
            }
            byteArrayOf()
        }

        try {
            ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zip ->
                    writeDeterministicEntry(zip, mirror.cheatRelative(titleId, buildId), cheat)
                    writeDeterministicEntry(zip, mirror.notesRelative(titleId, buildId), notes)
                }
                output.toByteArray()
            }
        } catch (error: IOException) {
            throw ZipExportError("Unable to create cheat ZIP", error)
        }
    }

    private fun readArchiveBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (read == 0) continue
            total += read
            if (total > limits.maxArchiveBytes) {
                throw ZipImportError("ZIP archive exceeds the compressed-size limit")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun validateArchive(archive: ByteArray): ValidatedArchive {
        try {
            val centralEntries = parseCentralDirectory(archive)
            val centralByCanonical = linkedMapOf<String, CentralEntry>()
            centralEntries.forEach { entry ->
                val candidate = classifyPath(entry.rawName)
                if (centralByCanonical.put(candidate.canonicalPath, entry.copy(candidate = candidate)) != null) {
                    throw ZipImportError("ZIP contains duplicate normalized path: ${candidate.canonicalPath}")
                }
            }

            val extractedByCanonical = readAndBoundEntries(archive, centralByCanonical)
            val cheatEntries = extractedByCanonical.values.filter { it.kind == EntryKind.CHEAT }
            val notesEntries = extractedByCanonical.values.filter { it.kind == EntryKind.NOTES }
            if (cheatEntries.size != 1) {
                throw ZipImportError("ZIP must contain exactly one canonical cheat file")
            }
            if (notesEntries.size > 1 || extractedByCanonical.size !in 1..2) {
                throw ZipImportError("ZIP contains extra entries")
            }

            val cheat = cheatEntries.single()
            val notes = notesEntries.singleOrNull()
            if (notes != null && notes.titleId != cheat.titleId) {
                throw ZipImportError("notes.txt must use the cheat file's Title ID")
            }
            decodeUtf8(cheat.bytes, "cheat file")
            notes?.let { decodeUtf8(it.bytes, "notes.txt") }
            return ValidatedArchive(
                titleId = cheat.titleId,
                buildId = requireNotNull(cheat.buildId),
                cheat = cheat,
                notes = notes,
            )
        } catch (error: ZipImportError) {
            throw error
        } catch (error: ZipException) {
            throw ZipImportError("Malformed or unsupported ZIP archive", error)
        } catch (error: IOException) {
            throw ZipImportError("Unable to expand ZIP archive", error)
        } catch (error: IllegalArgumentException) {
            throw ZipImportError("Invalid ZIP identity or path", error)
        } catch (error: IndexOutOfBoundsException) {
            throw ZipImportError("Truncated ZIP metadata", error)
        }
    }

    private fun parseCentralDirectory(archive: ByteArray): List<CentralEntry> {
        val eocd = findEocd(archive)
        val diskNumber = archive.u16(eocd + 4)
        val centralDisk = archive.u16(eocd + 6)
        val entriesOnDisk = archive.u16(eocd + 8)
        val entryCount = archive.u16(eocd + 10)
        val centralSize = archive.u32(eocd + 12)
        val centralOffset = archive.u32(eocd + 16)
        val commentLength = archive.u16(eocd + 20)
        if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != entryCount) {
            throw ZipImportError("Multi-disk ZIP archives are not supported")
        }
        if (entryCount == 0xFFFF || centralSize == UINT_MAX || centralOffset == UINT_MAX) {
            throw ZipImportError("ZIP64 or unknown central-directory sizes are not supported")
        }
        if (entryCount > limits.maxEntries) {
            throw ZipImportError("ZIP contains too many entries")
        }
        if (eocd.toLong() + EOCD_MIN_SIZE + commentLength != archive.size.toLong()) {
            throw ZipImportError("ZIP has trailing or malformed end metadata")
        }
        if (centralOffset + centralSize != eocd.toLong() || centralOffset > Int.MAX_VALUE) {
            throw ZipImportError("ZIP central directory is inconsistent")
        }

        val result = ArrayList<CentralEntry>(entryCount)
        var cursor = centralOffset.toInt()
        var expandedTotal = 0L
        repeat(entryCount) {
            archive.requireRange(cursor, CENTRAL_FIXED_SIZE)
            if (archive.u32(cursor) != CENTRAL_SIGNATURE) {
                throw ZipImportError("Invalid ZIP central-directory entry")
            }
            val creatorSystem = archive[cursor + 5].toInt() and 0xFF
            val flags = archive.u16(cursor + 8)
            val method = archive.u16(cursor + 10)
            val crc = archive.u32(cursor + 16)
            val compressedSize = archive.u32(cursor + 20)
            val expandedSize = archive.u32(cursor + 24)
            val nameLength = archive.u16(cursor + 28)
            val extraLength = archive.u16(cursor + 30)
            val entryCommentLength = archive.u16(cursor + 32)
            val diskStart = archive.u16(cursor + 34)
            val externalAttributes = archive.u32(cursor + 38)
            val localOffset = archive.u32(cursor + 42)
            val variableLength = nameLength.toLong() + extraLength + entryCommentLength
            if (variableLength > Int.MAX_VALUE || cursor.toLong() + CENTRAL_FIXED_SIZE + variableLength > eocd) {
                throw ZipImportError("Truncated ZIP central-directory fields")
            }
            if (nameLength == 0) throw ZipImportError("ZIP entry has an empty name")
            validateFlags(method, flags)
            if (compressedSize == UINT_MAX || expandedSize == UINT_MAX || localOffset == UINT_MAX) {
                throw ZipImportError("ZIP64 or unknown entry sizes are not supported")
            }
            if (expandedSize > limits.maxEntryBytes) {
                throw ZipImportError("ZIP entry exceeds the expanded-size limit")
            }
            if (expandedTotal > limits.maxExpandedBytes - expandedSize) {
                throw ZipImportError("ZIP exceeds the cumulative expanded-size limit")
            }
            expandedTotal += expandedSize
            if (diskStart != 0) throw ZipImportError("Multi-disk ZIP entries are not supported")

            val rawName = archive.ascii(cursor + CENTRAL_FIXED_SIZE, nameLength)
            validateExtraFields(archive, cursor + CENTRAL_FIXED_SIZE + nameLength, extraLength)
            validateExternalType(creatorSystem, externalAttributes, rawName)
            val localEnd = validateLocalHeader(
                archive = archive,
                centralOffset = centralOffset,
                rawName = rawName,
                flags = flags,
                method = method,
                crc = crc,
                compressedSize = compressedSize,
                expandedSize = expandedSize,
                localOffset = localOffset,
            )
            result += CentralEntry(
                rawName = rawName,
                flags = flags,
                method = method,
                crc = crc,
                compressedSize = compressedSize,
                expandedSize = expandedSize,
                localOffset = localOffset,
                localEnd = localEnd,
            )
            cursor += CENTRAL_FIXED_SIZE + variableLength.toInt()
        }
        if (cursor != eocd) throw ZipImportError("ZIP central-directory size does not match its entries")
        val localOrder = result.sortedBy(CentralEntry::localOffset)
        if (localOrder.isNotEmpty() && localOrder.first().localOffset != 0L) {
            throw ZipImportError("ZIP local entries must begin at the archive start")
        }
        localOrder.zipWithNext().forEach { (current, next) ->
            if (current.localEnd != next.localOffset) {
                throw ZipImportError("ZIP local entries overlap or contain unbound bytes")
            }
        }
        if (localOrder.isNotEmpty() && localOrder.last().localEnd != centralOffset) {
            throw ZipImportError("ZIP has unbound bytes before its central directory")
        }
        return result
    }

    private fun validateLocalHeader(
        archive: ByteArray,
        centralOffset: Long,
        rawName: String,
        flags: Int,
        method: Int,
        crc: Long,
        compressedSize: Long,
        expandedSize: Long,
        localOffset: Long,
    ): Long {
        if (localOffset > Int.MAX_VALUE) throw ZipImportError("ZIP local-header offset is invalid")
        val offset = localOffset.toInt()
        archive.requireRange(offset, LOCAL_FIXED_SIZE)
        if (archive.u32(offset) != LOCAL_SIGNATURE) throw ZipImportError("Invalid ZIP local header")
        val localFlags = archive.u16(offset + 6)
        val localMethod = archive.u16(offset + 8)
        val localCrc = archive.u32(offset + 14)
        val localCompressed = archive.u32(offset + 18)
        val localExpanded = archive.u32(offset + 22)
        val nameLength = archive.u16(offset + 26)
        val extraLength = archive.u16(offset + 28)
        archive.requireRange(offset + LOCAL_FIXED_SIZE, nameLength + extraLength)
        val localName = archive.ascii(offset + LOCAL_FIXED_SIZE, nameLength)
        if (localName != rawName || localMethod != method || localFlags != flags) {
            throw ZipImportError("ZIP local and central entry metadata disagree")
        }
        validateFlags(localMethod, localFlags)
        validateExtraFields(archive, offset + LOCAL_FIXED_SIZE + nameLength, extraLength)
        val usesDescriptor = localFlags and DATA_DESCRIPTOR_FLAG != 0
        if (!usesDescriptor &&
            (localCrc != crc || localCompressed != compressedSize || localExpanded != expandedSize)
        ) {
            throw ZipImportError("ZIP local and central entry sizes disagree")
        }
        if (usesDescriptor &&
            ((localCrc != 0L && localCrc != crc) ||
                (localCompressed != 0L && localCompressed != compressedSize) ||
                (localExpanded != 0L && localExpanded != expandedSize))
        ) {
            throw ZipImportError("ZIP descriptor entry has contradictory local CRC or sizes")
        }
        val dataStart = offset.toLong() + LOCAL_FIXED_SIZE + nameLength + extraLength
        if (dataStart + compressedSize > centralOffset) {
            throw ZipImportError("ZIP entry data overlaps the central directory")
        }
        val dataEnd = dataStart + compressedSize
        if (!usesDescriptor) return dataEnd
        if (method != ZipEntry.DEFLATED) {
            throw ZipImportError("Data descriptors are supported only for DEFLATED entries")
        }
        if (dataEnd > Int.MAX_VALUE) throw ZipImportError("ZIP descriptor offset is invalid")
        var descriptorOffset = dataEnd.toInt()
        val hasSignature = archive.u32(descriptorOffset) == DATA_DESCRIPTOR_SIGNATURE
        if (hasSignature) descriptorOffset += 4
        archive.requireRange(descriptorOffset, DATA_DESCRIPTOR_BODY_SIZE)
        val descriptorCrc = archive.u32(descriptorOffset)
        val descriptorCompressed = archive.u32(descriptorOffset + 4)
        val descriptorExpanded = archive.u32(descriptorOffset + 8)
        if (descriptorCrc != crc ||
            descriptorCompressed != compressedSize ||
            descriptorExpanded != expandedSize
        ) {
            throw ZipImportError("ZIP data descriptor disagrees with central metadata")
        }
        return descriptorOffset.toLong() + DATA_DESCRIPTOR_BODY_SIZE
    }

    private fun validateFlags(method: Int, flags: Int) {
        if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
            throw ZipImportError("Unsupported ZIP compression method: $method")
        }
        val allowed = UTF8_FLAG or if (method == ZipEntry.DEFLATED) DATA_DESCRIPTOR_FLAG else 0
        if (flags and allowed.inv() != 0) {
            throw ZipImportError("ZIP entry uses unsupported or security-sensitive flags")
        }
    }

    private fun validateExtraFields(archive: ByteArray, offset: Int, length: Int) {
        var cursor = offset
        val end = offset + length
        archive.requireRange(offset, length)
        while (cursor < end) {
            if (end - cursor < 4) throw ZipImportError("Malformed ZIP extra field")
            val id = archive.u16(cursor)
            val fieldLength = archive.u16(cursor + 2)
            cursor += 4
            if (cursor + fieldLength > end) throw ZipImportError("Malformed ZIP extra field length")
            if (id == ZIP64_EXTRA_ID) throw ZipImportError("ZIP64 extra fields are not supported")
            cursor += fieldLength
        }
    }

    private fun validateExternalType(creatorSystem: Int, externalAttributes: Long, rawName: String) {
        if (rawName.endsWith('/')) throw ZipImportError("Directory entries are not allowed")
        val unixMode = ((externalAttributes ushr 16) and 0xFFFF).toInt()
        val fileType = unixMode and UNIX_FILE_TYPE_MASK
        if (fileType == UNIX_SYMLINK) throw ZipImportError("Symbolic links are not allowed")
        if ((creatorSystem == UNIX_CREATOR || unixMode != 0) &&
            fileType != 0 && fileType != UNIX_REGULAR_FILE
        ) {
            throw ZipImportError("Unsupported non-regular ZIP entry")
        }
    }

    private fun readAndBoundEntries(
        archive: ByteArray,
        centralByCanonical: Map<String, CentralEntry>,
    ): LinkedHashMap<String, ExtractedEntry> {
        val extracted = linkedMapOf<String, ExtractedEntry>()
        var expandedTotal = 0L
        ZipInputStream(ByteArrayInputStream(archive), StandardCharsets.UTF_8).use { zip ->
            while (true) {
                val zipEntry = zip.nextEntry ?: break
                if (extracted.size >= limits.maxEntries) throw ZipImportError("ZIP contains too many entries")
                if (zipEntry.isDirectory) throw ZipImportError("Directory entries are not allowed")
                val candidate = classifyPath(zipEntry.name)
                if (extracted.containsKey(candidate.canonicalPath)) {
                    throw ZipImportError("ZIP contains duplicate normalized path: ${candidate.canonicalPath}")
                }
                val central = centralByCanonical[candidate.canonicalPath]
                    ?: throw ZipImportError("ZIP local entry is absent from the central directory")
                if (zipEntry.method != central.method) {
                    throw ZipImportError("ZIP compression method changed between headers")
                }

                val output = ByteArrayOutputStream()
                val crc = CRC32()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var entryTotal = 0L
                while (true) {
                    val read = zip.read(buffer)
                    if (read == -1) break
                    if (read == 0) continue
                    entryTotal += read
                    expandedTotal += read
                    if (entryTotal > limits.maxEntryBytes) {
                        throw ZipImportError("ZIP entry exceeds the actual expanded-size limit")
                    }
                    if (expandedTotal > limits.maxExpandedBytes) {
                        throw ZipImportError("ZIP exceeds the actual cumulative expanded-size limit")
                    }
                    output.write(buffer, 0, read)
                    crc.update(buffer, 0, read)
                }
                zip.closeEntry()
                if (entryTotal != central.expandedSize || crc.value != central.crc) {
                    throw ZipImportError("ZIP entry size or checksum does not match central metadata")
                }
                extracted[candidate.canonicalPath] = ExtractedEntry(
                    kind = candidate.kind,
                    titleId = candidate.titleId,
                    buildId = candidate.buildId,
                    canonicalPath = candidate.canonicalPath,
                    bytes = output.toByteArray(),
                )
            }
        }
        if (extracted.keys != centralByCanonical.keys) {
            throw ZipImportError("ZIP local and central entry lists disagree")
        }
        return extracted
    }

    private fun classifyPath(rawName: String): PathCandidate {
        validateRawPath(rawName)
        CheatPath.matchEntire(rawName)?.let { match ->
            val titleId = TitleId.parse(match.groupValues[1])
            val buildId = BuildId.parse(match.groupValues[2])
            return PathCandidate(
                EntryKind.CHEAT,
                titleId,
                buildId,
                mirror.cheatRelative(titleId, buildId),
            )
        }
        NotesPath.matchEntire(rawName)?.let { match ->
            val titleId = TitleId.parse(match.groupValues[1])
            return PathCandidate(
                EntryKind.NOTES,
                titleId,
                null,
                "atmosphere/contents/${titleId.hex}/cheats/notes.txt",
            )
        }
        throw ZipImportError("ZIP contains an entry outside the canonical Atmosphere layout")
    }

    private fun validateRawPath(rawName: String) {
        if (rawName.isEmpty() || rawName.any { it.code !in 0x20..0x7E }) {
            throw ZipImportError("ZIP entry names must be printable ASCII")
        }
        if ('\\' in rawName || ':' in rawName || rawName.startsWith('/')) {
            throw ZipImportError("Absolute, drive, UNC, and backslash ZIP paths are not allowed")
        }
        val segments = rawName.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw ZipImportError("ZIP paths may not contain empty, dot, or parent segments")
        }
    }

    private fun decodeUtf8(bytes: ByteArray, label: String): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        throw ZipImportError("$label is not valid UTF-8", error)
    }

    private fun publishTransaction(
        extracted: LinkedHashMap<Path, Path>,
        titleId: TitleId,
        buildId: BuildId,
        hasNotes: Boolean,
    ) {
        val transactionId = UUID.randomUUID().toString()
        var journal = TransactionJournal(
            transactionId = transactionId,
            phase = TransactionPhase.INIT,
            titleId = titleId,
            buildId = buildId,
            hasNotes = hasNotes,
            oldCheat = Files.exists(mirror.cheatPath(titleId, buildId), LinkOption.NOFOLLOW_LINKS),
            oldNotes = hasNotes && Files.exists(mirror.notesPath(titleId, buildId), LinkOption.NOFOLLOW_LINKS),
        )
        val journalPath = transactionJournalPath(transactionId)
        var journalWritten = false

        try {
            transactionEntries(journal).forEach { entry -> mirror.requireRegularOrMissing(entry.target) }
            persistJournal(journal)
            journalWritten = true

            transactionEntries(journal).forEach { entry ->
                val extractedFile = extracted[entry.target]
                    ?: throw ZipImportError("Imported transaction is missing a staged entry")
                mirror.createDirectoriesSecure(requireNotNull(entry.stage.parent))
                writeCompleteFile(entry.stage, Files.readAllBytes(extractedFile))
            }
            journal = journal.copy(phase = TransactionPhase.STAGED)
            persistJournal(journal)

            transactionEntries(journal).forEach { entry ->
                if (entry.oldExisted) {
                    fileOps.moveReplacing(entry.target, entry.backup)
                }
            }
            forceTransactionParents(journal)
            journal = journal.copy(phase = TransactionPhase.BACKED_UP)
            persistJournal(journal)

            transactionEntries(journal).forEach { entry ->
                fileOps.moveReplacing(entry.stage, entry.target)
            }
            forceTransactionParents(journal)
            journal = journal.copy(phase = TransactionPhase.PUBLISHED)
            persistJournal(journal)
            finishPublishedTransaction(journal, journalPath)
        } catch (failure: Exception) {
            if (journalWritten) {
                try {
                    recoverJournal(journalPath)
                } catch (error: Throwable) {
                    failure.addSuppressed(error)
                }
            }
            throw ZipImportError("Atomic mirror replacement failed; recovery was attempted", failure)
        }
    }

    private fun recoverPendingTransactionsLocked() {
        val journalDirectory = mirror.root.resolve(TRANSACTION_DIRECTORY)
        if (!Files.exists(journalDirectory, LinkOption.NOFOLLOW_LINKS)) return
        mirror.validateNoSymlinkComponents(journalDirectory)
        if (!Files.isDirectory(journalDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw ZipImportError("Transaction journal location is not a directory")
        }
        val journals = mutableListOf<Path>()
        val directoryEntries = mutableListOf<Path>()
        Files.list(journalDirectory).use { paths -> paths.forEach(directoryEntries::add) }
        directoryEntries.sorted().forEach { path ->
            val name = path.fileName.toString()
            if (JOURNAL_TEMP_NAME.matches(name)) {
                deleteRegularOrMissing(path)
                return@forEach
            }
            if (Files.isSymbolicLink(path) ||
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                !name.endsWith(JOURNAL_SUFFIX)
            ) {
                throw ZipImportError("Transaction journal directory contains an unsupported entry")
            }
            journals.add(path)
        }
        journals.forEach(::recoverJournal)
        deleteEmptyDirectory(journalDirectory)
    }

    private fun recoverJournal(journalPath: Path) {
        val journal = readJournal(journalPath)
        when (journal.phase) {
            TransactionPhase.PUBLISHED -> {
                val entries = transactionEntries(journal)
                val canFinishNew = entries.all { entry ->
                    isRegularNoLinks(entry.target) || isRegularNoLinks(entry.stage)
                }
                if (canFinishNew) {
                    finishPublishedTransaction(journal, journalPath)
                } else {
                    restoreOldTransaction(journal, journalPath)
                }
            }

            TransactionPhase.INIT,
            TransactionPhase.STAGED,
            TransactionPhase.BACKED_UP,
            -> restoreOldTransaction(journal, journalPath)
        }
    }

    private fun restoreOldTransaction(journal: TransactionJournal, journalPath: Path) {
        transactionEntries(journal).forEach { entry ->
            mirror.validateNoSymlinkComponents(requireNotNull(entry.target.parent))
            if (entry.oldExisted) {
                if (isRegularNoLinks(entry.backup)) {
                    deleteRegularOrMissing(entry.target)
                    fileOps.moveReplacing(entry.backup, entry.target)
                } else if (!isRegularNoLinks(entry.target)) {
                    throw ZipImportError("Cannot restore an old mirror file from its transaction journal")
                }
            } else {
                deleteRegularOrMissing(entry.target)
            }
            deleteRegularOrMissing(entry.stage)
            deleteRegularOrMissing(entry.backup)
        }
        forceTransactionParents(journal)
        deleteJournal(journalPath)
        cleanupEmptyTransactionDirectories(journal)
    }

    private fun finishPublishedTransaction(journal: TransactionJournal, journalPath: Path) {
        transactionEntries(journal).forEach { entry ->
            mirror.validateNoSymlinkComponents(requireNotNull(entry.target.parent))
            if (!isRegularNoLinks(entry.target)) {
                if (!isRegularNoLinks(entry.stage)) {
                    throw ZipImportError("Published transaction is missing its new mirror file")
                }
                fileOps.moveReplacing(entry.stage, entry.target)
            }
            deleteRegularOrMissing(entry.stage)
            deleteRegularOrMissing(entry.backup)
        }
        forceTransactionParents(journal)
        deleteJournal(journalPath)
        cleanupEmptyTransactionDirectories(journal)
    }

    private fun persistJournal(journal: TransactionJournal) {
        val directory = mirror.root.resolve(TRANSACTION_DIRECTORY)
        mirror.createDirectoriesSecure(directory)
        val destination = transactionJournalPath(journal.transactionId)
        val temporary = directory.resolve(".${journal.transactionId}.journal.tmp-${UUID.randomUUID()}")
        try {
            writeCompleteFile(temporary, journal.serialize().toByteArray(StandardCharsets.UTF_8))
            mirror.moveReplacing(temporary, destination)
            forceDirectory(directory)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readJournal(path: Path): TransactionJournal {
        val canonical = mirror.requireInsideRoot(path)
        mirror.validateNoSymlinkComponents(requireNotNull(canonical.parent))
        if (!Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS) || Files.size(canonical) > MAX_JOURNAL_BYTES) {
            throw ZipImportError("Transaction journal is missing, linked, or oversized")
        }
        val fileName = canonical.fileName.toString()
        if (!fileName.endsWith(JOURNAL_SUFFIX)) throw ZipImportError("Invalid transaction journal name")
        val transactionId = fileName.removeSuffix(JOURNAL_SUFFIX)
        val parsedUuid = try {
            UUID.fromString(transactionId)
        } catch (error: IllegalArgumentException) {
            throw ZipImportError("Invalid transaction journal identifier", error)
        }
        if (parsedUuid.toString() != transactionId) throw ZipImportError("Non-canonical transaction identifier")

        val text = decodeUtf8(Files.readAllBytes(canonical), "transaction journal")
        val fields = linkedMapOf<String, String>()
        text.split('\n').filter(String::isNotEmpty).forEach { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) throw ZipImportError("Malformed transaction journal field")
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            if (fields.put(key, value) != null) throw ZipImportError("Duplicate transaction journal field")
        }
        if (fields.keys != JOURNAL_KEYS) throw ZipImportError("Transaction journal fields are incomplete or unknown")
        if (fields.getValue("version") != JOURNAL_VERSION) throw ZipImportError("Unsupported transaction journal version")
        if (fields.getValue("transactionId") != transactionId) throw ZipImportError("Journal filename and identifier disagree")
        val titleId = try {
            TitleId.parse(fields.getValue("titleId"))
        } catch (error: IllegalArgumentException) {
            throw ZipImportError("Invalid journal Title ID", error)
        }
        val buildId = try {
            BuildId.parse(fields.getValue("buildId"))
        } catch (error: IllegalArgumentException) {
            throw ZipImportError("Invalid journal Build ID", error)
        }
        return TransactionJournal(
            transactionId = transactionId,
            phase = try {
                TransactionPhase.valueOf(fields.getValue("phase"))
            } catch (error: IllegalArgumentException) {
                throw ZipImportError("Invalid transaction journal phase", error)
            },
            titleId = titleId,
            buildId = buildId,
            hasNotes = parseJournalBoolean(fields.getValue("hasNotes")),
            oldCheat = parseJournalBoolean(fields.getValue("oldCheat")),
            oldNotes = parseJournalBoolean(fields.getValue("oldNotes")),
        ).also { journal ->
            if (!journal.hasNotes && journal.oldNotes) {
                throw ZipImportError("Journal cannot retain notes outside its transaction")
            }
        }
    }

    private fun transactionEntries(journal: TransactionJournal): List<TransactionEntry> {
        fun entry(target: Path, oldExisted: Boolean): TransactionEntry = TransactionEntry(
            target = mirror.requireInsideRoot(target),
            stage = mirror.requireInsideRoot(
                target.parent.resolve(".${target.fileName}.stage-${journal.transactionId}"),
            ),
            backup = mirror.requireInsideRoot(
                target.parent.resolve(".${target.fileName}.backup-${journal.transactionId}"),
            ),
            oldExisted = oldExisted,
        )
        return buildList {
            add(entry(mirror.cheatPath(journal.titleId, journal.buildId), journal.oldCheat))
            if (journal.hasNotes) {
                add(entry(mirror.notesPath(journal.titleId, journal.buildId), journal.oldNotes))
            }
        }
    }

    private fun transactionJournalPath(transactionId: String): Path =
        mirror.requireInsideRoot(mirror.root.resolve(TRANSACTION_DIRECTORY).resolve("$transactionId$JOURNAL_SUFFIX"))

    private fun deleteJournal(path: Path) {
        deleteRegularOrMissing(path)
        forceDirectory(requireNotNull(path.parent))
    }

    private fun deleteRegularOrMissing(path: Path) {
        mirror.validateNoSymlinkComponents(requireNotNull(path.parent))
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw ZipImportError("Transaction artifact is not a regular file")
        }
        Files.delete(path)
    }

    private fun isRegularNoLinks(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

    private fun forceTransactionParents(journal: TransactionJournal) {
        transactionEntries(journal).mapNotNull { it.target.parent }.distinct().forEach(::forceDirectory)
    }

    private fun forceDirectory(directory: Path) {
        runCatching {
            FileChannel.open(directory, StandardOpenOption.READ).use { channel -> channel.force(true) }
        }
    }

    private fun cleanupEmptyTransactionDirectories(journal: TransactionJournal) {
        val candidates = mutableSetOf<Path>()
        transactionEntries(journal).forEach { entry ->
            var cursor: Path? = entry.target.parent
            while (cursor != null && cursor != mirror.root) {
                candidates.add(cursor)
                cursor = cursor.parent
            }
        }
        candidates.sortedByDescending { it.nameCount }.forEach(::deleteEmptyDirectory)
        deleteEmptyDirectory(mirror.root.resolve(TRANSACTION_DIRECTORY))
    }

    private fun deleteEmptyDirectory(directory: Path) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        mirror.validateNoSymlinkComponents(directory)
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw ZipImportError("Expected a transaction directory")
        }
        try {
            Files.delete(directory)
        } catch (_: java.nio.file.DirectoryNotEmptyException) {
            // Shared game or journal directories remain in use.
        }
    }

    private fun parseJournalBoolean(value: String): Boolean = when (value) {
        "true" -> true
        "false" -> false
        else -> throw ZipImportError("Invalid transaction journal boolean")
    }

    private fun readExportFile(path: Path, required: Boolean): ByteArray {
        try {
            mirror.validateNoSymlinkComponents(requireNotNull(path.parent))
        } catch (error: IllegalArgumentException) {
            throw ZipExportError("Export path contains an unsafe link or parent", error)
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            if (required) throw ZipExportError("Required cheat export file is missing")
            return byteArrayOf()
        }
        val size = try {
            Files.size(path)
        } catch (error: IOException) {
            throw ZipExportError("Unable to inspect export file", error)
        }
        if (size > limits.maxEntryBytes) throw ZipExportError("Export file exceeds the size limit")
        return try {
            Files.readAllBytes(path)
        } catch (error: IOException) {
            throw ZipExportError("Unable to read export file", error)
        }
    }

    private fun writeDeterministicEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
            time = FIXED_ZIP_TIME_MILLIS
            comment = null
            extra = null
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeCompleteFile(path: Path, bytes: ByteArray) {
        FileChannel.open(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun safeResolve(root: Path, relative: String): Path {
        val resolved = root.resolve(relative).normalize()
        if (!resolved.startsWith(root)) throw ZipImportError("ZIP extraction escaped its staging root")
        return resolved
    }

    private fun findEocd(archive: ByteArray): Int {
        if (archive.size < EOCD_MIN_SIZE) throw ZipImportError("ZIP is missing end metadata")
        val firstCandidate = maxOf(0, archive.size - EOCD_MIN_SIZE - MAX_ZIP_COMMENT)
        for (offset in archive.size - EOCD_MIN_SIZE downTo firstCandidate) {
            if (archive.u32(offset) == EOCD_SIGNATURE) return offset
        }
        throw ZipImportError("ZIP is missing end metadata")
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun captureSnapshot(path: Path): FileSnapshot {
        try {
            mirror.validateNoSymlinkComponents(requireNotNull(path.parent))
        } catch (error: IllegalArgumentException) {
            throw ZipImportError("Existing mirror path contains an unsafe link or parent", error)
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return FileSnapshot.Missing
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw ZipImportError("Existing mirror target is not a regular file")
        }
        return try {
            FileSnapshot.Regular(sha256(Files.readAllBytes(path)))
        } catch (error: IOException) {
            throw ZipImportError("Unable to snapshot existing mirror target", error)
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    private data class PendingImport(
        val archiveBytes: ByteArray,
        val digest: ByteArray,
        val inspection: ZipInspection,
        val targetSnapshots: Map<Path, FileSnapshot>,
    )

    private data class TransactionJournal(
        val transactionId: String,
        val phase: TransactionPhase,
        val titleId: TitleId,
        val buildId: BuildId,
        val hasNotes: Boolean,
        val oldCheat: Boolean,
        val oldNotes: Boolean,
    ) {
        fun serialize(): String = buildString {
            append("version=").append(JOURNAL_VERSION).append('\n')
            append("transactionId=").append(transactionId).append('\n')
            append("phase=").append(phase.name).append('\n')
            append("titleId=").append(titleId.hex).append('\n')
            append("buildId=").append(buildId.hex).append('\n')
            append("hasNotes=").append(hasNotes).append('\n')
            append("oldCheat=").append(oldCheat).append('\n')
            append("oldNotes=").append(oldNotes).append('\n')
        }
    }

    private data class TransactionEntry(
        val target: Path,
        val stage: Path,
        val backup: Path,
        val oldExisted: Boolean,
    )

    private enum class TransactionPhase { INIT, STAGED, BACKED_UP, PUBLISHED }

    private sealed interface FileSnapshot {
        data object Missing : FileSnapshot

        class Regular(private val digest: ByteArray) : FileSnapshot {
            override fun equals(other: Any?): Boolean =
                other is Regular && MessageDigest.isEqual(digest, other.digest)

            override fun hashCode(): Int = digest.contentHashCode()
        }
    }

    private data class CentralEntry(
        val rawName: String,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val expandedSize: Long,
        val localOffset: Long,
        val localEnd: Long,
        val candidate: PathCandidate? = null,
    )

    private data class PathCandidate(
        val kind: EntryKind,
        val titleId: TitleId,
        val buildId: BuildId?,
        val canonicalPath: String,
    )

    private data class ExtractedEntry(
        val kind: EntryKind,
        val titleId: TitleId,
        val buildId: BuildId?,
        val canonicalPath: String,
        val bytes: ByteArray,
    )

    private data class ValidatedArchive(
        val titleId: TitleId,
        val buildId: BuildId,
        val cheat: ExtractedEntry,
        val notes: ExtractedEntry?,
    ) {
        val orderedEntries: List<ExtractedEntry> = listOfNotNull(cheat, notes)
    }

    private enum class EntryKind { CHEAT, NOTES }

    private companion object {
        const val EOCD_MIN_SIZE = 22
        const val CENTRAL_FIXED_SIZE = 46
        const val LOCAL_FIXED_SIZE = 30
        const val MAX_ZIP_COMMENT = 65_535
        const val DATA_DESCRIPTOR_FLAG = 0x0008
        const val UTF8_FLAG = 0x0800
        const val ZIP64_EXTRA_ID = 0x0001
        const val DATA_DESCRIPTOR_BODY_SIZE = 12
        const val TRANSACTION_DIRECTORY = ".transactions"
        const val JOURNAL_SUFFIX = ".journal"
        const val JOURNAL_VERSION = "1"
        const val MAX_JOURNAL_BYTES = 4_096L
        const val UNIX_CREATOR = 3
        const val UNIX_FILE_TYPE_MASK = 0xF000
        const val UNIX_REGULAR_FILE = 0x8000
        const val UNIX_SYMLINK = 0xA000
        const val FIXED_ZIP_TIME_MILLIS = 315_532_800_000L
        const val UINT_MAX = 0xFFFF_FFFFL
        const val EOCD_SIGNATURE = 0x06054B50L
        const val CENTRAL_SIGNATURE = 0x02014B50L
        const val LOCAL_SIGNATURE = 0x04034B50L
        const val DATA_DESCRIPTOR_SIGNATURE = 0x08074B50L

        val JOURNAL_KEYS = linkedSetOf(
            "version",
            "transactionId",
            "phase",
            "titleId",
            "buildId",
            "hasNotes",
            "oldCheat",
            "oldNotes",
        )
        val JOURNAL_TEMP_NAME = Regex(
            "^\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" +
                "\\.journal\\.tmp-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )

        val CheatPath = Regex(
            "atmosphere/contents/([0-9A-Fa-f]{16})/cheats/([0-9A-Fa-f]{16})\\.txt",
        )
        val NotesPath = Regex(
            "atmosphere/contents/([0-9A-Fa-f]{16})/cheats/notes\\.txt",
        )
    }
}

private fun ByteArray.requireRange(offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset.toLong() + length > size.toLong()) {
        throw ZipImportError("Truncated ZIP metadata")
    }
}

private fun ByteArray.u16(offset: Int): Int {
    requireRange(offset, 2)
    return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}

private fun ByteArray.u32(offset: Int): Long =
    (u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)) and 0xFFFF_FFFFL

private fun ByteArray.ascii(offset: Int, length: Int): String {
    requireRange(offset, length)
    for (index in offset until offset + length) {
        val value = this[index].toInt() and 0xFF
        if (value !in 0x20..0x7E) throw ZipImportError("ZIP entry names must be printable ASCII")
    }
    return String(this, offset, length, StandardCharsets.US_ASCII)
}
