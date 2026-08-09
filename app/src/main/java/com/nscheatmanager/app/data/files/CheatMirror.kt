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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** App-controlled mirror of Atmosphere's per-title cheat layout. */
class CheatMirror(root: Path) {
    constructor(root: File) : this(root.toPath())

    val root: Path = root.toAbsolutePath().normalize()
    private val writeLock: ReentrantLock = Locks.computeIfAbsent(this.root) { ReentrantLock() }

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
        withWriteTransaction {
            val canonicalTarget = requireInsideRoot(target)
            val parent = requireNotNull(canonicalTarget.parent) { "Mirror target must have a parent" }
            createDirectoriesSecure(parent)
            requireRegularOrMissing(canonicalTarget)
            val temporary = parent.resolve(".${canonicalTarget.fileName}.tmp-${UUID.randomUUID()}")

            try {
                FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { channel ->
                    val buffer = ByteBuffer.wrap(content)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                moveReplacing(temporary, canonicalTarget)
            } finally {
                Files.deleteIfExists(temporary)
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
            Files.move(
                canonicalSource,
                canonicalTarget,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(canonicalSource, canonicalTarget, StandardCopyOption.REPLACE_EXISTING)
        }
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
    }
}
