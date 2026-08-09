package com.nscheatmanager.app.data.files

import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal fun interface MirrorMoveOps {
    fun move(source: Path, target: Path)
}

internal enum class MirrorTransactionCut { INIT, STAGED, BACKED_UP, TARGET_MOVED, PUBLISHED, CLEANUP }

internal fun interface MirrorTransactionHook {
    fun reached(cut: MirrorTransactionCut, index: Int?)

    companion object {
        val None = MirrorTransactionHook { _, _ -> }
    }
}

/** App-controlled mirror of Atmosphere's per-title cheat layout. */
class CheatMirror internal constructor(
    root: Path,
    private val moveOps: MirrorMoveOps,
    private val durability: FileDurability,
    private val transactionHook: MirrorTransactionHook,
) {
    internal constructor(root: Path, moveOps: MirrorMoveOps) :
        this(root, moveOps, platformFileDurability(), MirrorTransactionHook.None)
    constructor(root: Path) :
        this(root, MirrorMoveOps(::defaultMoveReplacing), platformFileDurability(), MirrorTransactionHook.None)
    constructor(root: File) : this(root.toPath())

    val root: Path = root.toAbsolutePath().normalize()
    private val writeLock: ReentrantLock = Locks.computeIfAbsent(this.root) { ReentrantLock() }

    init {
        withWriteTransaction { recoverTransactionsLocked() }
    }

    fun cheatRelative(titleId: TitleId, buildId: BuildId): String =
        "atmosphere/contents/${titleId.hex}/cheats/${buildId.hex}.txt"

    fun notesRelative(titleId: TitleId, buildId: BuildId): String =
        "atmosphere/contents/${titleId.hex}/cheats/${buildId.hex}/notes.txt"

    fun cheatPath(titleId: TitleId, buildId: BuildId): Path =
        resolveRelative(cheatRelative(titleId, buildId))

    fun notesPath(titleId: TitleId, buildId: BuildId): Path =
        resolveRelative(notesRelative(titleId, buildId))

    /**
     * Publishes [content] without exposing a partially written destination.
     *
     * The temporary file is a sibling so an atomic rename remains possible. Filesystems without
     * atomic-move support fall back to a replace move after the complete temporary file is synced.
     */
    fun atomicReplace(target: Path, content: ByteArray) {
        atomicReplaceAll(mapOf(target to content))
    }

    /** Crash-recoverable all-or-nothing publication for related mirror files. */
    fun atomicReplaceAll(replacements: Map<Path, ByteArray>) {
        require(replacements.isNotEmpty()) { "At least one mirror replacement is required" }
        withWriteTransaction {
            recoverTransactionsLocked()
            val transactionId = UUID.randomUUID().toString()
            val entries = replacements.entries.map { (target, content) ->
                val canonical = requireInsideRoot(target)
                val parent = requireNotNull(canonical.parent) { "Mirror target must have a parent" }
                createDirectoriesSecure(parent)
                requireRegularOrMissing(canonical)
                TransactionEntry(
                    target = canonical,
                    content = content,
                    oldExisted = Files.exists(canonical, LinkOption.NOFOLLOW_LINKS),
                    stage = parent.resolve(".${canonical.fileName}.stage-$transactionId"),
                    backup = parent.resolve(".${canonical.fileName}.backup-$transactionId"),
                )
            }
            require(entries.map(TransactionEntry::target).distinct().size == entries.size) {
                "Mirror transaction targets must be unique"
            }
            val journal = journalPath(transactionId)
            createDirectoriesSecure(requireNotNull(journal.parent))
            writeJournal(journal, TransactionPhase.INIT, entries)
            transactionHook.reached(MirrorTransactionCut.INIT, null)
            try {
                entries.forEach { writeCompleteFile(it.stage, it.content) }
                writeJournal(journal, TransactionPhase.STAGED, entries)
                transactionHook.reached(MirrorTransactionCut.STAGED, null)
                entries.filter(TransactionEntry::oldExisted).forEach { entry ->
                    writeCompleteFile(entry.backup, Files.readAllBytes(entry.target))
                }
                writeJournal(journal, TransactionPhase.BACKED_UP, entries)
                transactionHook.reached(MirrorTransactionCut.BACKED_UP, null)
                entries.forEachIndexed { index, entry ->
                    moveReplacing(entry.stage, entry.target)
                    transactionHook.reached(MirrorTransactionCut.TARGET_MOVED, index)
                }
                writeJournal(journal, TransactionPhase.PUBLISHED, entries)
                transactionHook.reached(MirrorTransactionCut.PUBLISHED, null)
                cleanupTransaction(entries, journal, emitHooks = true)
            } catch (error: Exception) {
                try {
                    rollbackTransaction(entries)
                    cleanupTransaction(entries, journal, emitHooks = false)
                } catch (rollback: Exception) {
                    error.addSuppressed(rollback)
                }
                throw error
            }
        }
    }

    /** Serializes all mirror mutations, including callers using another instance for this root. */
    fun <T> withWriteTransaction(action: () -> T): T = writeLock.withLock(action)

    internal fun moveReplacing(source: Path, target: Path) {
        check(writeLock.isHeldByCurrentThread) { "Mirror moves require withWriteTransaction" }
        val canonicalSource = requireInsideRoot(source)
        val canonicalTarget = requireInsideRoot(target)
        validateNoSymlinkComponents(requireNotNull(canonicalSource.parent))
        validateNoSymlinkComponents(requireNotNull(canonicalTarget.parent))
        require(!Files.isSymbolicLink(canonicalSource)) { "Mirror source may not be a symbolic link" }
        require(!Files.isSymbolicLink(canonicalTarget)) { "Mirror target may not be a symbolic link" }
        try {
            moveOps.move(canonicalSource, canonicalTarget)
        } catch (error: AtomicMoveNotSupportedException) {
            Files.move(canonicalSource, canonicalTarget, StandardCopyOption.REPLACE_EXISTING)
        }
        durability.forceDirectory(requireNotNull(canonicalTarget.parent))
    }

    internal fun createDirectoriesSecure(directory: Path, created: MutableList<Path>? = null) {
        check(writeLock.isHeldByCurrentThread) { "Mirror directory creation requires withWriteTransaction" }
        val canonical = directory.toAbsolutePath().normalize()
        require(canonical == root || canonical.startsWith(root)) {
            "Directory must stay below the cheat mirror root"
        }
        ensureRootDirectory()
        var cursor = root
        root.relativize(canonical).forEach { segment ->
            cursor = cursor.resolve(segment)
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(cursor) && Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    "Mirror parent components must be real directories"
                }
            } else {
                try {
                    Files.createDirectory(cursor)
                    created?.add(cursor)
                    durability.forceDirectory(requireNotNull(cursor.parent))
                    durability.forceDirectory(cursor)
                } catch (_: FileAlreadyExistsException) {
                    require(!Files.isSymbolicLink(cursor) && Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                        "Mirror parent components must be real directories"
                    }
                }
            }
        }
    }

    internal fun validateNoSymlinkComponents(path: Path) {
        val canonical = path.toAbsolutePath().normalize()
        require(canonical == root || canonical.startsWith(root)) { "Path must stay below the mirror root" }
        ensureRootDirectory()
        var cursor = root
        require(!Files.isSymbolicLink(cursor)) { "Mirror root may not be a symbolic link" }
        root.relativize(canonical).forEach { segment ->
            cursor = cursor.resolve(segment)
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(cursor)) { "Mirror paths may not contain symbolic links" }
            }
        }
    }

    internal fun requireRegularOrMissing(path: Path) {
        val canonical = requireInsideRoot(path)
        validateNoSymlinkComponents(requireNotNull(canonical.parent))
        if (Files.exists(canonical, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(canonical) && Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS)) {
                "Mirror targets must be regular files"
            }
        }
    }

    internal fun requireInsideRoot(path: Path): Path {
        val canonical = path.toAbsolutePath().normalize()
        require(canonical.startsWith(root) && canonical != root) {
            "Target must stay below the cheat mirror root"
        }
        return canonical
    }

    private fun resolveRelative(relative: String): Path = requireInsideRoot(root.resolve(relative))

    private fun recoverTransactionsLocked() {
        val directory = root.resolve(TRANSACTION_DIRECTORY)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        validateNoSymlinkComponents(directory)
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "Transaction path must be a directory" }
        Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(JOURNAL_SUFFIX) }.forEach { journal ->
                val recovered = readJournal(journal)
                if (recovered.phase != TransactionPhase.PUBLISHED) rollbackTransaction(recovered.entries)
                cleanupTransaction(recovered.entries, journal, emitHooks = false)
            }
        }
    }

    private fun rollbackTransaction(entries: List<TransactionEntry>) {
        entries.asReversed().forEach { entry ->
            if (entry.oldExisted) {
                if (Files.exists(entry.backup, LinkOption.NOFOLLOW_LINKS)) {
                    val rollback = entry.target.parent.resolve(".${entry.target.fileName}.rollback-${UUID.randomUUID()}")
                    try {
                        writeCompleteFile(rollback, Files.readAllBytes(entry.backup))
                        moveReplacing(rollback, entry.target)
                    } finally {
                        Files.deleteIfExists(rollback)
                    }
                }
            } else {
                deleteDurably(entry.target)
            }
        }
    }

    private fun cleanupTransaction(entries: List<TransactionEntry>, journal: Path, emitHooks: Boolean) {
        entries.forEachIndexed { index, entry ->
            deleteDurably(entry.stage)
            deleteDurably(entry.backup)
            if (emitHooks) transactionHook.reached(MirrorTransactionCut.CLEANUP, index)
        }
        deleteDurably(journal)
    }

    private fun writeJournal(journal: Path, phase: TransactionPhase, entries: List<TransactionEntry>) {
        val bytes = buildString {
            append("version=1\nphase=").append(phase.name).append("\ncount=").append(entries.size).append('\n')
            entries.forEachIndexed { index, entry ->
                val relative = root.relativize(entry.target).toString().replace('\\', '/')
                append("path.").append(index).append('=').append(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(relative.toByteArray(Charsets.UTF_8)),
                ).append('\n')
                append("old.").append(index).append('=').append(entry.oldExisted).append('\n')
            }
        }.toByteArray(Charsets.UTF_8)
        val temporary = journal.parent.resolve(".${journal.fileName}.tmp-${UUID.randomUUID()}")
        try {
            writeCompleteFile(temporary, bytes)
            moveReplacing(temporary, journal)
        } finally {
            deleteDurably(temporary)
        }
    }

    private fun readJournal(journal: Path): RecoveredTransaction {
        requireRegularOrMissing(journal)
        require(Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) { "Transaction journal is missing" }
        val fields = String(Files.readAllBytes(journal), Charsets.UTF_8).lineSequence().filter(String::isNotEmpty)
            .associate { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "Malformed transaction journal" }
                line.substring(0, separator) to line.substring(separator + 1)
            }
        require(fields["version"] == "1") { "Unsupported transaction journal" }
        val count = requireNotNull(fields["count"]).toInt()
        val transactionId = journal.fileName.toString().removeSuffix(JOURNAL_SUFFIX)
        val entries = (0 until count).map { index ->
            val relative = String(
                Base64.getUrlDecoder().decode(requireNotNull(fields["path.$index"])),
                Charsets.UTF_8,
            )
            val target = requireInsideRoot(root.resolve(relative))
            val parent = requireNotNull(target.parent)
            TransactionEntry(
                target = target,
                content = byteArrayOf(),
                oldExisted = requireNotNull(fields["old.$index"]).toBooleanStrict(),
                stage = parent.resolve(".${target.fileName}.stage-$transactionId"),
                backup = parent.resolve(".${target.fileName}.backup-$transactionId"),
            )
        }
        return RecoveredTransaction(TransactionPhase.valueOf(requireNotNull(fields["phase"])), entries)
    }

    private fun journalPath(transactionId: String): Path =
        requireInsideRoot(root.resolve(TRANSACTION_DIRECTORY).resolve("$transactionId$JOURNAL_SUFFIX"))

    private fun writeCompleteFile(path: Path, content: ByteArray) {
        requireInsideRoot(path)
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(content)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
        durability.forceFile(path)
        durability.forceDirectory(requireNotNull(path.parent))
    }

    private fun deleteDurably(path: Path) {
        if (Files.deleteIfExists(path)) durability.forceDirectory(requireNotNull(path.parent))
    }

    private fun ensureRootDirectory() {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(root) && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                "Mirror root must be a real directory"
            }
        } else {
            Files.createDirectories(root)
            require(!Files.isSymbolicLink(root)) { "Mirror root may not be a symbolic link" }
        }
    }

    private companion object {
        val Locks = ConcurrentHashMap<Path, ReentrantLock>()
        const val TRANSACTION_DIRECTORY = ".mirror-transactions"
        const val JOURNAL_SUFFIX = ".journal"

        fun defaultMoveReplacing(source: Path, target: Path) {
            try {
                Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private data class TransactionEntry(
        val target: Path,
        val content: ByteArray,
        val oldExisted: Boolean,
        val stage: Path,
        val backup: Path,
    )

    private data class RecoveredTransaction(
        val phase: TransactionPhase,
        val entries: List<TransactionEntry>,
    )

    private enum class TransactionPhase { INIT, STAGED, BACKED_UP, PUBLISHED }
}
