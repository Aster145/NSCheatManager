package com.nscheatmanager.app.data.files

import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** App-controlled mirror of Atmosphere's per-title cheat layout. */
class CheatMirror(root: Path) {
    constructor(root: File) : this(root.toPath())

    val root: Path = root.toAbsolutePath().normalize()

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
        val canonicalTarget = requireInsideRoot(target)
        val parent = requireNotNull(canonicalTarget.parent) { "Mirror target must have a parent" }
        Files.createDirectories(parent)
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

    internal fun moveReplacing(source: Path, target: Path) {
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

    internal fun requireInsideRoot(path: Path): Path {
        val canonical = path.toAbsolutePath().normalize()
        require(canonical.startsWith(root) && canonical != root) {
            "Target must stay below the cheat mirror root"
        }
        return canonical
    }

    private fun resolveRelative(relative: String): Path = requireInsideRoot(root.resolve(relative))
}
