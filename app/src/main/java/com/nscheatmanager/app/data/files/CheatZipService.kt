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

class ZipImportError(message: String, cause: Throwable? = null) : Exception(message, cause)

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
            throw ZipImportError("Cheat parse failed at line ${first.line}: ${first.message}")
        }

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
        return inspection
    }

    /** Imports exactly the immutable bytes associated with a previously returned [inspection]. */
    fun importConfirmed(inspection: ZipInspection) {
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
            publishTransaction(staged)
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
    ): ByteArray {
        val cheat = readExportFile(mirror.cheatPath(titleId, buildId), required = true)
        val notesPath = mirror.notesPath(titleId, buildId)
        val notes = if (Files.isRegularFile(notesPath)) {
            readExportFile(notesPath, required = true)
        } else {
            if (!includeEmptyNotes) {
                throw ZipExportError("notes.txt is missing and empty-notes export was not confirmed")
            }
            byteArrayOf()
        }

        return try {
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
            if (notes != null && (notes.titleId != cheat.titleId || notes.buildId != cheat.buildId)) {
                throw ZipImportError("notes.txt must use the cheat file's Title ID and Build ID")
            }
            decodeUtf8(cheat.bytes, "cheat file")
            notes?.let { decodeUtf8(it.bytes, "notes.txt") }
            return ValidatedArchive(
                titleId = cheat.titleId,
                buildId = cheat.buildId,
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
            if (flags and ENCRYPTION_FLAGS != 0) throw ZipImportError("Encrypted ZIP entries are not supported")
            if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                throw ZipImportError("Unsupported ZIP compression method: $method")
            }
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
            validateExternalType(creatorSystem, externalAttributes, rawName)
            validateLocalHeader(
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
            )
            cursor += CENTRAL_FIXED_SIZE + variableLength.toInt()
        }
        if (cursor != eocd) throw ZipImportError("ZIP central-directory size does not match its entries")
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
    ) {
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
        if (localFlags and ENCRYPTION_FLAGS != 0) throw ZipImportError("Encrypted ZIP entries are not supported")
        val usesDescriptor = localFlags and DATA_DESCRIPTOR_FLAG != 0
        if (!usesDescriptor &&
            (localCrc != crc || localCompressed != compressedSize || localExpanded != expandedSize)
        ) {
            throw ZipImportError("ZIP local and central entry sizes disagree")
        }
        val dataStart = offset.toLong() + LOCAL_FIXED_SIZE + nameLength + extraLength
        if (dataStart + compressedSize > centralOffset) {
            throw ZipImportError("ZIP entry data overlaps the central directory")
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
            val buildId = BuildId.parse(match.groupValues[2])
            return PathCandidate(
                EntryKind.NOTES,
                titleId,
                buildId,
                mirror.notesRelative(titleId, buildId),
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

    private fun publishTransaction(extracted: LinkedHashMap<Path, Path>) {
        val transactionId = UUID.randomUUID().toString()
        val stages = linkedMapOf<Path, Path>()
        val backups = linkedMapOf<Path, Path>()
        val committed = mutableListOf<Path>()
        val createdDirectories = mutableListOf<Path>()
        var published = false

        try {
            extracted.forEach { (rawTarget, extractedFile) ->
                val target = mirror.requireInsideRoot(rawTarget)
                val parent = requireNotNull(target.parent)
                createDirectoriesTracked(parent, createdDirectories)
                val stage = parent.resolve(".${target.fileName}.stage-$transactionId")
                writeCompleteFile(stage, Files.readAllBytes(extractedFile))
                stages[target] = stage
            }

            stages.keys.forEach { target ->
                if (Files.exists(target)) {
                    val backup = target.parent.resolve(".${target.fileName}.backup-$transactionId")
                    fileOps.moveReplacing(target, backup)
                    backups[target] = backup
                }
            }

            stages.forEach { (target, stage) ->
                fileOps.moveReplacing(stage, target)
                committed.add(target)
            }
            published = true
        } catch (failure: Exception) {
            val rollbackFailures = mutableListOf<Throwable>()
            committed.asReversed().forEach { target ->
                try {
                    Files.deleteIfExists(target)
                } catch (error: Throwable) {
                    rollbackFailures += error
                }
            }
            backups.entries.toList().asReversed().forEach { (target, backup) ->
                try {
                    if (Files.exists(backup)) fileOps.moveReplacing(backup, target)
                } catch (error: Throwable) {
                    rollbackFailures += error
                }
            }
            rollbackFailures.forEach(failure::addSuppressed)
            throw ZipImportError("Atomic mirror replacement failed and was rolled back", failure)
        } finally {
            stages.values.forEach { runCatching { Files.deleteIfExists(it) } }
            if (published) {
                backups.values.forEach { runCatching { Files.deleteIfExists(it) } }
            } else {
                createdDirectories.asReversed().forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun createDirectoriesTracked(directory: Path, created: MutableList<Path>) {
        val missing = mutableListOf<Path>()
        var cursor: Path? = directory
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(cursor)
            cursor = cursor.parent
        }
        Files.createDirectories(directory)
        created.addAll(missing.asReversed())
    }

    private fun readExportFile(path: Path, required: Boolean): ByteArray {
        if (!Files.isRegularFile(path)) {
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
        val candidate: PathCandidate? = null,
    )

    private data class PathCandidate(
        val kind: EntryKind,
        val titleId: TitleId,
        val buildId: BuildId,
        val canonicalPath: String,
    )

    private data class ExtractedEntry(
        val kind: EntryKind,
        val titleId: TitleId,
        val buildId: BuildId,
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
        const val ENCRYPTION_FLAGS = 0x0041
        const val DATA_DESCRIPTOR_FLAG = 0x0008
        const val UNIX_CREATOR = 3
        const val UNIX_FILE_TYPE_MASK = 0xF000
        const val UNIX_REGULAR_FILE = 0x8000
        const val UNIX_SYMLINK = 0xA000
        const val FIXED_ZIP_TIME_MILLIS = 0L
        const val UINT_MAX = 0xFFFF_FFFFL
        const val EOCD_SIGNATURE = 0x06054B50L
        const val CENTRAL_SIGNATURE = 0x02014B50L
        const val LOCAL_SIGNATURE = 0x04034B50L

        val CheatPath = Regex(
            "atmosphere/contents/([0-9A-Fa-f]{16})/cheats/([0-9A-Fa-f]{16})\\.txt",
        )
        val NotesPath = Regex(
            "atmosphere/contents/([0-9A-Fa-f]{16})/cheats/([0-9A-Fa-f]{16})/notes\\.txt",
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
