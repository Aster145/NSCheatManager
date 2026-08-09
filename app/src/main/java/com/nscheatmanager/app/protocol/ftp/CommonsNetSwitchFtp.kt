package com.nscheatmanager.app.protocol.ftp

import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.Socket
import java.time.Duration
import java.util.UUID
import java.util.Timer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.schedule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPCmd

/** Commons Net adapter compatible with the small anonymous passive FTP servers used on Switch. */
class CommonsNetSwitchFtp(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val dataTimeoutMillis: Int = DEFAULT_DATA_TIMEOUT_MILLIS,
    private val totalTransferTimeoutMillis: Int = DEFAULT_TOTAL_TRANSFER_TIMEOUT_MILLIS,
    private val maxFileBytes: Int = DEFAULT_MAX_FILE_BYTES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SwitchFtp {
    init {
        require(host.isNotBlank()) { "FTP host must not be blank" }
        require(port in 1..65535) { "FTP port must be in 1..65535" }
        require(connectTimeoutMillis > 0) { "FTP connect timeout must be positive" }
        require(dataTimeoutMillis > 0) { "FTP data timeout must be positive" }
        require(totalTransferTimeoutMillis > 0) { "FTP total timeout must be positive" }
        require(maxFileBytes > 0) { "FTP file limit must be positive" }
    }

    override suspend fun downloadCurrent(titleId: TitleId, buildId: BuildId): DownloadedCurrentGameFiles =
        withClient { client ->
            DownloadedCurrentGameFiles(
                cheat = retrieveOptional(client, RemotePaths.cheat(titleId, buildId)),
                notes = retrieveOptional(client, RemotePaths.notes(titleId, buildId)),
            )
        }

    override suspend fun uploadCurrent(
        titleId: TitleId,
        buildId: BuildId,
        files: CurrentGameFiles,
        directOverwriteAuthorization: DirectOverwriteAuthorization?,
    ): FtpUploadResult {
        require(files.cheat.size <= maxFileBytes) { "Cheat content exceeds the FTP file limit" }
        require(files.notes == null || files.notes.size <= maxFileBytes) {
            "Notes content exceeds the FTP file limit"
        }
        return withClient { client ->
            ensureRemoteDirectories(client, titleId)
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
            recoverSafeUpload(client, entries, error)
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
            recoverDirectUpload(client, entries, error)
            throw error
        }
    }

    private fun recoverSafeUpload(client: FTPClient, entries: List<UploadEntry>, original: Throwable) {
        val firstFailure = runCatching {
            rollbackSafe(client, entries)
            cleanupArtifactsStrict(client, entries)
        }.exceptionOrNull()
        if (firstFailure == null && (client as? AbortableFtpClient)?.wasAborted() != true) return
        firstFailure?.let(original::addSuppressed)
        val freshFailure = runCatching {
            withFreshBoundedClient { fresh ->
                rollbackSafe(fresh, entries)
                cleanupArtifactsStrict(fresh, entries)
            }
        }.exceptionOrNull()
        if (freshFailure != null) {
            original.addSuppressed(freshFailure)
            throw FtpRollbackError("FTP staged upload failed and rollback was incomplete", original)
        }
    }

    private fun recoverDirectUpload(client: FTPClient, entries: List<UploadEntry>, original: Throwable) {
        val firstFailure = runCatching { rollbackDirect(client, entries) }.exceptionOrNull()
        if (firstFailure == null && (client as? AbortableFtpClient)?.wasAborted() != true) return
        firstFailure?.let(original::addSuppressed)
        val freshFailure = runCatching { withFreshBoundedClient { rollbackDirect(it, entries) } }.exceptionOrNull()
        if (freshFailure != null) {
            original.addSuppressed(freshFailure)
            throw FtpRollbackError("FTP direct overwrite failed and recovery was incomplete", original)
        }
    }

    private fun rollbackDirect(client: FTPClient, entries: List<UploadEntry>) {
        entries.asReversed().filter { it.published }.forEach { entry ->
            val old = entry.oldBytes
            if (old == null) {
                deleteForRollback(client, entry.target)
            } else {
                store(client, entry.target, old)
                verifyRemote(client, entry.target, old)
            }
            entry.published = false
        }
    }

    private fun withFreshBoundedClient(operation: (FTPClient) -> Unit) {
        val fresh = AbortableFtpClient()
        val watchdog = Timer("nscheatmanager-ftp-recovery", true)
        watchdog.schedule(minOf(totalTransferTimeoutMillis, MAX_RECOVERY_TIMEOUT_MILLIS).toLong()) {
            fresh.abortNow()
        }
        try {
            withClientBlocking(fresh, operation)
        } finally {
            watchdog.cancel()
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

    private fun cleanupArtifactsStrict(client: FTPClient, entries: List<UploadEntry>) {
        entries.forEach { entry ->
            entry.temporary?.let { deleteForRollback(client, it) }
            entry.backup?.let { backup -> if (!entry.backedUp) deleteForRollback(client, backup) }
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
            val retrievalError = replyError(client, "RETR")
            if (client.replyCode != 550) throw retrievalError
            return when (probeExistence(client, path)) {
                RemoteExistence.Absent -> null
                RemoteExistence.Present,
                RemoteExistence.Ambiguous,
                -> throw retrievalError
            }
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
        if (client.replyCode != 550) return false
        return probeExistence(client, path) == RemoteExistence.Absent
    }

    private fun deleteForRollback(client: FTPClient, path: String) {
        if (!client.deleteFile(path)) {
            val deletionError = replyError(client, "rollback DELE")
            if (client.replyCode != 550 || probeExistence(client, path) != RemoteExistence.Absent) {
                throw deletionError
            }
        }
    }

    private fun probeExistence(client: FTPClient, path: String): RemoteExistence {
        val mlstCode = client.sendCommand("MLST", path)
        classifyExistenceReply(mlstCode, client.replyString)?.let { return it }

        val sizeCode = client.sendCommand("SIZE", path)
        classifyExistenceReply(sizeCode, client.replyString)?.let { return it }

        return RemoteExistence.Ambiguous
    }

    private fun classifyExistenceReply(code: Int, reply: String?): RemoteExistence? = when {
        FTPReply.isPositiveCompletion(code) -> RemoteExistence.Present
        code == 550 && replyIndicatesMissing(reply) -> RemoteExistence.Absent
        code == 550 -> null
        code in PROBE_UNSUPPORTED_REPLIES -> null
        else -> null
    }

    private fun replyIndicatesMissing(reply: String?): Boolean {
        val normalized = reply.orEmpty().lowercase()
        if (PERMISSION_MARKERS.any(normalized::contains)) return false
        return MISSING_MARKERS.any(normalized::contains)
    }

    private fun ensureRemoteDirectories(
        client: FTPClient,
        titleId: TitleId,
    ) {
        val directories = buildList {
            add("/sdmc:/switch")
            add("/sdmc:/switch/breeze")
            add("/sdmc:/switch/breeze/cheats")
            add("/sdmc:/switch/breeze/cheats/${titleId.hex}")
        }
        directories.forEach { directory ->
            if (!client.makeDirectory(directory) && !client.changeWorkingDirectory(directory)) {
                throw replyError(client, "MKD")
            }
        }
    }

    private suspend fun <T> withClient(operation: (FTPClient) -> T): T {
        val client = AbortableFtpClient()
        val timedOut = AtomicBoolean(false)
        val watchdog = Timer("nscheatmanager-ftp-deadline", true)
        watchdog.schedule(totalTransferTimeoutMillis.toLong()) {
            timedOut.set(true)
            client.abortNow()
        }
        try {
            val result = runCatching {
                runCancellableBlocking(client) { withClientBlocking(client, operation) }
            }
            if (timedOut.get()) {
                val timeout = FtpTimeoutError("FTP total transfer deadline exceeded")
                result.exceptionOrNull()?.let(timeout::addSuppressed)
                throw timeout
            }
            return result.getOrThrow()
        } finally {
            watchdog.cancel()
        }
    }

    private suspend fun <T> runCancellableBlocking(
        client: AbortableFtpClient,
        operation: () -> T,
    ): T = supervisorScope {
        val workerFailure = AtomicReference<Throwable?>()
        val worker = async(dispatcher) {
            withContext(NonCancellable) {
                try {
                    operation()
                } catch (error: Throwable) {
                    workerFailure.set(error)
                    throw error
                }
            }
        }
        try {
            worker.await()
        } catch (cancellation: CancellationException) {
            client.abortNow()
            val cleanupFailure = withContext(NonCancellable) {
                runCatching {
                    withTimeout(cancellationCleanupTimeoutMillis()) {
                        worker.join()
                    }
                }.exceptionOrNull()
            }
            workerFailure.get()
                ?.takeUnless { it === cancellation || it is CancellationException }
                ?.let(cancellation::addSuppressed)
            cleanupFailure?.let(cancellation::addSuppressed)
            throw cancellation
        }
    }

    private fun cancellationCleanupTimeoutMillis(): Long =
        minOf(totalTransferTimeoutMillis, MAX_RECOVERY_TIMEOUT_MILLIS).toLong() +
            connectTimeoutMillis.toLong() + CLEANUP_TIMEOUT_GRACE_MILLIS

    private fun <T> withClientBlocking(client: AbortableFtpClient, operation: (FTPClient) -> T): T {
        try {
            client.requireNotAborted()
            client.connectTimeout = connectTimeoutMillis
            client.defaultTimeout = connectTimeoutMillis
            client.dataTimeout = Duration.ofMillis(dataTimeoutMillis.toLong())
            client.setUseEPSVwithIPv4(false)
            client.connect(host, port)
            client.requireNotAborted()
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
                if (!client.wasAborted()) runCatching { client.logout() }
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

    private enum class RemoteExistence { Present, Absent, Ambiguous }

    @Suppress("OVERRIDE_DEPRECATION")
    private class AbortableFtpClient : FTPClient() {
        private val activeDataSocket = AtomicReference<Socket?>()
        private val aborted = AtomicBoolean(false)

        override fun _openDataConnection_(command: String, arg: String): Socket? =
            track(super._openDataConnection_(command, arg))

        override fun _openDataConnection_(command: FTPCmd, arg: String): Socket? =
            track(super._openDataConnection_(command, arg))

        @Suppress("DEPRECATION")
        override fun _openDataConnection_(command: Int, arg: String): Socket? =
            track(super._openDataConnection_(command, arg))

        private fun track(socket: Socket?): Socket? = socket?.also { candidate ->
            if (aborted.get()) {
                runCatching { candidate.close() }
            } else {
                activeDataSocket.set(candidate)
                if (aborted.get() && activeDataSocket.compareAndSet(candidate, null)) {
                    runCatching { candidate.close() }
                }
            }
        }

        fun abortNow() {
            if (!aborted.compareAndSet(false, true)) return
            runCatching { activeDataSocket.getAndSet(null)?.close() }
            runCatching { disconnect() }
        }

        fun wasAborted(): Boolean = aborted.get()

        fun requireNotAborted() {
            if (aborted.get()) throw IOException("FTP operation was cancelled")
        }

        override fun _connectAction_() {
            super._connectAction_()
            if (aborted.get()) {
                runCatching { disconnect() }
                throw IOException("FTP operation was cancelled during connection")
            }
        }
    }

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
            "/sdmc:/switch/breeze/cheats/${titleId.hex}/${buildId.hex}.txt"

        fun notes(titleId: TitleId, @Suppress("UNUSED_PARAMETER") buildId: BuildId): String =
            "/sdmc:/switch/breeze/cheats/${titleId.hex}/notes.txt"
    }

    companion object {
        const val DEFAULT_PORT = 21
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
        const val DEFAULT_DATA_TIMEOUT_MILLIS = 10_000
        const val DEFAULT_TOTAL_TRANSFER_TIMEOUT_MILLIS = 30_000
        const val DEFAULT_MAX_FILE_BYTES = 1024 * 1024
        private const val MAX_RECOVERY_TIMEOUT_MILLIS = 5_000
        private const val CLEANUP_TIMEOUT_GRACE_MILLIS = 1_000L
        private const val ANONYMOUS_USER = "anonymous"
        private const val ANONYMOUS_PASSWORD = "anonymous@"
        private val RENAME_UNSUPPORTED_REPLIES = setOf(500, 501, 502, 504)
        private val SIZE_UNSUPPORTED_REPLIES = setOf(500, 501, 502, 504)
        private val PROBE_UNSUPPORTED_REPLIES = setOf(500, 501, 502, 504)
        private val MISSING_MARKERS = setOf("not found", "no such file", "does not exist", "missing")
        private val PERMISSION_MARKERS = setOf("permission", "denied", "not allowed", "access")
    }
}
