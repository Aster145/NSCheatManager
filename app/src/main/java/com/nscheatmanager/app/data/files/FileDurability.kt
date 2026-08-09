package com.nscheatmanager.app.data.files

import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Durability boundary used after file writes and namespace mutations. */
internal interface FileDurability {
    @Throws(IOException::class)
    fun forceFile(path: Path)

    @Throws(IOException::class)
    fun forceDirectory(path: Path)
}

/** Android API 26 implementation backed by Linux fsync(2), including directory descriptors. */
private object AndroidFileDurability : FileDurability {
    override fun forceFile(path: Path) = force(path)
    override fun forceDirectory(path: Path) = force(path)

    private fun force(path: Path) {
        val descriptor = try {
            Os.open(path.toString(), OsConstants.O_RDONLY, 0)
        } catch (error: Exception) {
            throw IOException("Unable to open durability target $path", error)
        }
        try {
            Os.fsync(descriptor)
        } catch (error: Exception) {
            throw IOException("Unable to fsync $path", error)
        } finally {
            runCatching { Os.close(descriptor) }
        }
    }
}

/**
 * Host-JVM fallback. Files are always forced. Windows does not expose directory handles through
 * NIO, so only that known unsupported directory case is skipped; other sync failures propagate.
 */
private object NioFileDurability : FileDurability {
    override fun forceFile(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
    }

    override fun forceDirectory(path: Path) {
        try {
            FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        } catch (error: Exception) {
            val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
            if (windows && (error is AccessDeniedException || error is FileSystemException)) return
            throw IOException("Unable to fsync directory $path", error)
        }
    }
}

internal object NoOpFileDurability : FileDurability {
    override fun forceFile(path: Path) = Unit
    override fun forceDirectory(path: Path) = Unit
}

internal fun platformFileDurability(): FileDurability =
    if (System.getProperty("java.runtime.name").orEmpty().contains("Android", ignoreCase = true)) {
        AndroidFileDurability
    } else {
        NioFileDurability
    }
