package com.nscheatmanager.app.protocol.ftp

import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply

/** Commons Net adapter compatible with the small anonymous passive FTP servers used on Switch. */
class CommonsNetSwitchFtp(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val dataTimeoutMillis: Int = DEFAULT_DATA_TIMEOUT_MILLIS,
    private val maxFileBytes: Int = DEFAULT_MAX_FILE_BYTES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clientFactory: () -> FTPClient = ::FTPClient,
) : SwitchFtp {
    init {
        require(host.isNotBlank()) { "FTP host must not be blank" }
        require(port in 1..65535) { "FTP port must be in 1..65535" }
        require(connectTimeoutMillis > 0) { "FTP connect timeout must be positive" }
        require(dataTimeoutMillis > 0) { "FTP data timeout must be positive" }
        require(maxFileBytes > 0) { "FTP file limit must be positive" }
    }

    override suspend fun downloadCurrent(titleId: TitleId, buildId: BuildId): DownloadedCurrentGameFiles =
        withContext(dispatcher) {
            withClient { client ->
                DownloadedCurrentGameFiles(
                    cheat = retrieveOptional(client, RemotePaths.cheat(titleId, buildId)),
                    notes = retrieveOptional(client, RemotePaths.notes(titleId, buildId)),
                )
            }
        }

    override suspend fun uploadCurrent(
        titleId: TitleId,
        buildId: BuildId,
        files: CurrentGameFiles,
        directOverwriteAuthorization: DirectOverwriteAuthorization?,
    ): FtpUploadResult = withContext(dispatcher) {
        require(files.cheat.size <= maxFileBytes) { "Cheat content exceeds the FTP file limit" }
        require(files.notes == null || files.notes.size <= maxFileBytes) {
            "Notes content exceeds the FTP file limit"
        }
        withClient { client ->
            ensureRemoteDirectories(client, titleId, buildId, includeNotes = files.notes != null)
            val entries = buildList {
                add(UploadEntry(RemotePaths.cheat(titleId, buildId), files.cheat))
                files.notes?.let { add(UploadEntry(RemotePaths.notes(titleId, buildId), it)) }
            }
            if (directOverwriteAuthorization == null) safeUpload(client, entries) else directUpload(client, entries)
        }
    }

    private fun safeUpload(client: FTPClient, requested: List<UploadEntry>): FtpUploadResult {
        val transaction = UUID.randomUUID().toString()
        val entries = requested.map { entry ->
            entry.copy(
                temporary = "${entry.target}.nscheatmanager-upload-$transaction.tmp",
                backup = "${entry.target}.nscheatmanager-backup-$transaction.bak",
                oldBytes = retrieveOptional(client, entry.target),
            )
        }
        try {
            entries.forEach { entry ->
                store(client, requireNotNull(entry.temporary), entry.bytes)
                verifyRemote(client, entry.temporary, entry.bytes)
            }

            entries.filter { it.oldBytes != null }.forEach { entry ->
                renameOrThrow(client, entry.target, requireNotNull(entry.backup))
                entry.backedUp = true
            }
            entries.forEach { entry ->
                renameOrThrow(client, requireNotNull(entry.temporary), entry.target)
                entry.published = true
            }
            entries.forEach { entry -> verifyRemote(client, entry.target, entry.bytes) }

            var retainedRecovery = false
            entries.filter { it.backedUp }.forEach { entry ->
                if (!deleteIfPresent(client, requireNotNull(entry.backup))) retainedRecovery = true
            }
            return uploaded(entries, retainedRecovery)
        } catch (_: RenameUnsupported) {
            rollbackSafe(client, entries)
            return FtpUploadResult.RequiresDirectOverwriteConfirmation
        } catch (error: Throwable) {
            try {
                rollbackSafe(client, entries)
            } catch (rollback: Throwable) {
                error.addSuppressed(rollback)
                throw FtpRollbackError("FTP staged upload failed and rollback was incomplete", error)
            }
            throw error
        } finally {
            cleanupArtifacts(client, entries)
        }
    }

    private fun directUpload(client: FTPClient, requested: List<UploadEntry>): FtpUploadResult {
        val entries = requested.map { entry -> entry.copy(oldBytes = retrieveOptional(client, entry.target)) }
        try {
            entries.forEach { entry ->
                // STOR may truncate an existing target before returning a failure reply.
                entry.published = true
                store(client, entry.target, entry.bytes)
                verifyRemote(client, entry.target, entry.bytes)
            }
            return uploaded(entries, retainedRecovery = false)
        } catch (error: Throwable) {
            try {
                entries.asReversed().filter { it.published }.forEach { entry ->
                    val old = entry.oldBytes
                    if (old == null) {
                        deleteForRollback(client, entry.target)
                    } else {
                        store(client, entry.target, old)
                        verifyRemote(client, entry.target, old)
                    }
                }
            } catch (rollback: Throwable) {
                error.addSuppressed(rollback)
                throw FtpRollbackError("FTP direct overwrite failed and recovery was incomplete", error)
            }
            throw error
        }
    }

    private fun rollbackSafe(client: FTPClient, entries: List<UploadEntry>) {
        entries.asReversed().forEach { entry ->
            if (entry.published) {
                deleteForRollback(client, entry.target)
                entry.published = false
            }
            if (entry.backedUp) {
                try {
                    renameOrThrow(client, requireNotNull(entry.backup), entry.target)
                } catch (_: RenameUnsupported) {
                    store(client, entry.target, requireNotNull(entry.oldBytes))
                    deleteIfPresent(client, requireNotNull(entry.backup))
                }
                entry.backedUp = false
            }
        }
    }

    private fun cleanupArtifacts(client: FTPClient, entries: List<UploadEntry>) {
        entries.forEach { entry ->
            entry.temporary?.let { runCatching { deleteIfPresent(client, it) } }
            entry.backup?.let { backup ->
                if (!entry.backedUp) runCatching { deleteIfPresent(client, backup) }
            }
        }
    }

    private fun uploaded(entries: List<UploadEntry>, retainedRecovery: Boolean): FtpUploadResult.Uploaded =
        FtpUploadResult.Uploaded(
            cheatBytes = entries.first().bytes.size,
            notesBytes = entries.getOrNull(1)?.bytes?.size,
            retainedRecoveryArtifacts = retainedRecovery,
        )

    private fun verifyRemote(client: FTPClient, path: String, expected: ByteArray) {
        remoteSize(client, path)?.let { actual ->
            if (actual != expected.size.toLong()) {
                throw FtpVerificationError("FTP size verification failed for the current-game file")
            }
        }
        val actual = retrieveOptional(client, path)
            ?: throw FtpVerificationError("FTP content verification could not retrieve the current-game file")
        if (!actual.contentEquals(expected)) {
            throw FtpVerificationError("FTP content verification failed for the current-game file")
        }
    }

    private fun remoteSize(client: FTPClient, path: String): Long? {
        val code = client.sendCommand("SIZE", path)
        if (code == 213) {
            return client.replyString.substringAfter(' ', "").trim().toLongOrNull()
                ?: throw FtpReplyError("SIZE", code, sanitizeReply(client.replyString))
        }
        if (code in SIZE_UNSUPPORTED_REPLIES) return null
        throw replyError(client, "SIZE")
    }

    private fun retrieveOptional(client: FTPClient, path: String): ByteArray? {
        val output = LimitedOutputStream(maxFileBytes)
        val retrieved = client.retrieveFile(path, output)
        if (!retrieved) {
            if (client.replyCode == 550) return null
            throw replyError(client, "RETR")
        }
        return output.toByteArray()
    }

    private fun store(client: FTPClient, path: String, bytes: ByteArray) {
        if (!client.storeFile(path, ByteArrayInputStream(bytes))) throw replyError(client, "STOR")
    }

    private fun renameOrThrow(client: FTPClient, source: String, destination: String) {
        if (client.rename(source, destination)) return
        if (client.replyCode in RENAME_UNSUPPORTED_REPLIES) throw RenameUnsupported()
        throw replyError(client, "rename")
    }

    private fun deleteIfPresent(client: FTPClient, path: String): Boolean {
        if (client.deleteFile(path)) return true
        if (client.replyCode == 550) return true
        return false
    }

    private fun deleteForRollback(client: FTPClient, path: String) {
        if (!deleteIfPresent(client, path)) throw replyError(client, "rollback DELE")
    }

    private fun ensureRemoteDirectories(
        client: FTPClient,
        titleId: TitleId,
        buildId: BuildId,
        includeNotes: Boolean,
    ) {
        val directories = buildList {
            add("/atmosphere")
            add("/atmosphere/contents")
            add("/atmosphere/contents/${titleId.hex}")
            add("/atmosphere/contents/${titleId.hex}/cheats")
            if (includeNotes) add("/atmosphere/contents/${titleId.hex}/cheats/${buildId.hex}")
        }
        directories.forEach { directory ->
            if (!client.makeDirectory(directory) && !client.changeWorkingDirectory(directory)) {
                throw replyError(client, "MKD")
            }
        }
    }

    private fun <T> withClient(operation: (FTPClient) -> T): T {
        val client = clientFactory()
        try {
            client.connectTimeout = connectTimeoutMillis
            client.defaultTimeout = connectTimeoutMillis
            client.dataTimeout = Duration.ofMillis(dataTimeoutMillis.toLong())
            client.setUseEPSVwithIPv4(false)
            client.connect(host, port)
            client.soTimeout = dataTimeoutMillis
            if (!FTPReply.isPositiveCompletion(client.replyCode)) throw replyError(client, "connect")
            if (!client.login(ANONYMOUS_USER, ANONYMOUS_PASSWORD)) throw replyError(client, "login")
            client.enterLocalPassiveMode()
            if (!client.setFileType(FTP.BINARY_FILE_TYPE)) throw replyError(client, "TYPE I")
            return operation(client)
        } catch (error: FtpTransferError) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw FtpTimeoutError("FTP operation timed out", error)
        } catch (error: IOException) {
            causeOfType<FtpTransferError>(error)?.let { throw it }
            causeOfType<SocketTimeoutException>(error)?.let {
                throw FtpTimeoutError("FTP operation timed out", it)
            }
            throw FtpConnectionError("FTP operation failed", error)
        } finally {
            if (client.isConnected) {
                runCatching { client.logout() }
                runCatching { client.disconnect() }
            }
        }
    }

    private fun replyError(client: FTPClient, operation: String) = FtpReplyError(
        operation = operation,
        replyCode = client.replyCode,
        sanitizedReply = sanitizeReply(client.replyString),
    )

    private fun sanitizeReply(reply: String?): String = reply.orEmpty()
        .replace("\r", " ")
        .replace("\n", " ")
        .trim()
        .take(160)

    private inline fun <reified T : Throwable> causeOfType(error: Throwable): T? {
        var cursor: Throwable? = error
        while (cursor != null) {
            if (cursor is T) return cursor
            cursor = cursor.cause
        }
        return null
    }

    private data class UploadEntry(
        val target: String,
        val bytes: ByteArray,
        val temporary: String? = null,
        val backup: String? = null,
        val oldBytes: ByteArray? = null,
        var backedUp: Boolean = false,
        var published: Boolean = false,
    )

    private class RenameUnsupported : IOException()

    private class LimitedOutputStream(private val limit: Int) : OutputStream() {
        private val delegate = ByteArrayOutputStream(minOf(limit, 8192))
        private var count = 0

        override fun write(value: Int) {
            ensureCapacity(1)
            delegate.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            ensureCapacity(length)
            delegate.write(bytes, offset, length)
        }

        fun toByteArray(): ByteArray = delegate.toByteArray()

        private fun ensureCapacity(additional: Int) {
            if (additional < 0 || count.toLong() + additional > limit.toLong()) {
                throw FtpSizeLimitError(limit)
            }
            count += additional
        }
    }

    private object RemotePaths {
        fun cheat(titleId: TitleId, buildId: BuildId): String =
            "/atmosphere/contents/${titleId.hex}/cheats/${buildId.hex}.txt"

        fun notes(titleId: TitleId, buildId: BuildId): String =
            "/atmosphere/contents/${titleId.hex}/cheats/${buildId.hex}/notes.txt"
    }

    companion object {
        const val DEFAULT_PORT = 21
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
        const val DEFAULT_DATA_TIMEOUT_MILLIS = 10_000
        const val DEFAULT_MAX_FILE_BYTES = 1024 * 1024
        private const val ANONYMOUS_USER = "anonymous"
        private const val ANONYMOUS_PASSWORD = "anonymous@"
        private val RENAME_UNSUPPORTED_REPLIES = setOf(500, 501, 502, 504)
        private val SIZE_UNSUPPORTED_REPLIES = setOf(500, 501, 502, 504)
    }
}
