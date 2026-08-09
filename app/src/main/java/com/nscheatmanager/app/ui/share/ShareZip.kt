package com.nscheatmanager.app.ui.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.nscheatmanager.app.ui.game.ShareArchive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ZipDocumentReader(
    private val context: Context,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    init {
        require(maxBytes > 0) { "ZIP size limit must be positive" }
    }

    fun read(uri: Uri): ByteArray {
        val resolver = context.contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeColumn >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeColumn)) {
                require(cursor.getLong(sizeColumn) in 0..maxBytes.toLong()) {
                    "ZIP exceeds the $maxBytes-byte import limit"
                }
            }
        }
        resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.length >= 0) {
                require(descriptor.length <= maxBytes) { "ZIP exceeds the $maxBytes-byte import limit" }
            }
        }
        val input = requireNotNull(resolver.openInputStream(uri)) { "Unable to open selected ZIP" }
        return input.use { source ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                require(total.toLong() + count <= maxBytes.toLong()) {
                    "ZIP exceeds the $maxBytes-byte import limit"
                }
                output.write(buffer, 0, count)
                total += count
            }
            output.toByteArray()
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 8 * 1024 * 1024
    }
}

class ZipShareService(private val context: Context) {
    fun createIntent(archive: ShareArchive): Intent {
        require(archive.bytes.isNotEmpty()) { "Shared ZIP must not be empty" }
        val directory = File(context.cacheDir, RELATIVE_DIRECTORY).apply { mkdirs() }
        require(directory.isDirectory) { "Unable to create the ZIP share directory" }
        val name = safeFileName(archive.fileName)
        val target = File(directory, name)
        val temporary = File.createTempFile(".share-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(archive.bytes)
                stream.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        return Intent(Intent.ACTION_SEND).apply {
            type = ZIP_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun safeFileName(requested: String): String {
        val normalized = requested.substringAfterLast('/').substringAfterLast('\\')
            .map { character ->
                if (character.isLetterOrDigit() || character in setOf('.', '_', '-')) character else '_'
            }
            .joinToString("")
            .take(128)
            .ifBlank { "NSCheatManager.zip" }
        return if (normalized.endsWith(".zip", ignoreCase = true)) normalized else "$normalized.zip"
    }

    companion object {
        const val RELATIVE_DIRECTORY = "shared/cheat-zips"
        private const val ZIP_MIME = "application/zip"
    }
}
