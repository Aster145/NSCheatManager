package com.nscheatmanager.app.domain

import com.nscheatmanager.app.cheats.parser.CheatFileParser
import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.data.files.CheatMirror
import com.nscheatmanager.app.protocol.ftp.CommonsNetSwitchFtp
import com.nscheatmanager.app.protocol.ftp.CurrentGameFiles
import com.nscheatmanager.app.protocol.ftp.DirectOverwriteAuthorization
import com.nscheatmanager.app.protocol.ftp.FtpSizeLimitError
import com.nscheatmanager.app.protocol.ftp.FtpTransferError
import com.nscheatmanager.app.protocol.ftp.FtpUploadResult
import com.nscheatmanager.app.protocol.ftp.SwitchFtp
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DownloadOverwriteConfirmation internal constructor(internal val id: String) {
    override fun toString(): String = "download:$id"
}

class UploadConfirmation internal constructor(internal val id: String) {
    override fun toString(): String = "upload:$id"
}

class DirectOverwriteConfirmation internal constructor(internal val id: String) {
    override fun toString(): String = "direct:$id"
}

data class UploadPreview(
    val confirmation: UploadConfirmation,
    val cheatBytes: Int,
    val notesBytes: Int?,
)

enum class NotesDownloadDisposition {
    DownloadedRemote,
    MissingRemotePreservedLocal,
    MissingRemoteNoLocalFile,
}

sealed interface TransferReport {
    data object RemoteCheatMissing : TransferReport

    data class RequiresLocalOverwriteConfirmation(
        val confirmation: DownloadOverwriteConfirmation,
        val cheatBytes: Int,
        val notesBytes: Int?,
    ) : TransferReport

    data class Downloaded(
        val cheatBytes: Int,
        val notesBytes: Int?,
        val notes: NotesDownloadDisposition,
    ) : TransferReport

    data class RequiresDirectOverwriteConfirmation(
        val confirmation: DirectOverwriteConfirmation,
    ) : TransferReport

    data class Uploaded(
        val cheatBytes: Int,
        val notesBytes: Int?,
        val retainedRecoveryArtifacts: Boolean,
    ) : TransferReport

    data object StaleLocalSnapshot : TransferReport
}

class DownloadedCheatParseError(
    val diagnostic: com.nscheatmanager.app.cheats.parser.CheatParseDiagnostic?,
    cause: Throwable? = null,
) : IOException("Downloaded cheat file is malformed", cause)

class NotesEncodingError : IOException("notes.txt must contain valid UTF-8 text")

class LocalCheatMissingError : IOException("The current local cheat file is missing")

class InvalidSyncConfirmation : IllegalArgumentException("The synchronization confirmation is invalid or expired")

/**
 * Coordinates exact current-game FTP synchronization with the app-controlled mirror.
 *
 * A missing remote notes file intentionally preserves an existing local notes file. Upload preview
 * tokens bind the complete local snapshot; confirmation re-reads it while holding CheatMirror's
 * root-shared transaction lock before any network I/O.
 */
class SyncCurrentGameFiles(
    private val mirror: CheatMirror,
    private val stagingRoot: Path,
    private val ftpFactory: (DeviceProfile) -> SwitchFtp = { profile ->
        CommonsNetSwitchFtp(profile.host, profile.ftpPort)
    },
    private val parser: CheatFileParser = CheatFileParser(),
    private val maxFileBytes: Int = CommonsNetSwitchFtp.DEFAULT_MAX_FILE_BYTES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val pendingDownloads = ConcurrentHashMap<String, PendingDownload>()
    private val uploadStates = ConcurrentHashMap<String, UploadState>()
    private val operationMutex = Mutex()

    init {
        require(maxFileBytes > 0) { "FTP staging limit must be positive" }
    }

    suspend fun downloadCurrent(
        profile: DeviceProfile,
        titleId: TitleId,
        buildId: BuildId,
        confirmation: DownloadOverwriteConfirmation? = null,
        checkpoint: () -> Unit = {},
    ): TransferReport = withContext(dispatcher) {
        operationMutex.withLock {
            downloadCurrentOnDispatcher(profile, titleId, buildId, confirmation, checkpoint)
        }
    }

    private suspend fun downloadCurrentOnDispatcher(
        profile: DeviceProfile,
        titleId: TitleId,
        buildId: BuildId,
        confirmation: DownloadOverwriteConfirmation?,
        checkpoint: () -> Unit,
    ): TransferReport {
        if (confirmation != null) {
            val pending = pendingDownloads.remove(confirmation.id) ?: throw InvalidSyncConfirmation()
            if (pending.profile != profile || pending.titleId != titleId || pending.buildId != buildId) {
                pendingDownloads[confirmation.id] = pending
                throw InvalidSyncConfirmation()
            }
            return try {
                checkpoint()
                publishDownload(pending)
            } finally {
                deleteTree(pending.stageDirectory)
            }
        }

        val remote = ftpFactory(profile).downloadCurrent(titleId, buildId)
        val cheat = remote.cheat ?: return TransferReport.RemoteCheatMissing
        enforceLimit(cheat)
        remote.notes?.let(::enforceLimit)
        parseCheat(cheat)
        remote.notes?.let(::validateNotes)

        val stageDirectory = createStageDirectory("ftp-download-")
        try {
            val stagedCheat = stageDirectory.resolve("cheat.txt")
            Files.write(stagedCheat, cheat)
            val stagedNotes = remote.notes?.let { notes ->
                stageDirectory.resolve("notes.txt").also { Files.write(it, notes) }
            }
            val snapshots = mirror.withWriteTransaction { captureCurrentSnapshots(titleId, buildId) }
            val pending = PendingDownload(
                profile = profile,
                titleId = titleId,
                buildId = buildId,
                stageDirectory = stageDirectory,
                stagedCheat = stagedCheat,
                stagedNotes = stagedNotes,
                snapshots = snapshots,
            )
            val overwritesExisting = snapshots.cheat is FileSnapshot.Regular ||
                (stagedNotes != null && snapshots.notes is FileSnapshot.Regular)
            if (overwritesExisting) {
                val token = DownloadOverwriteConfirmation(UUID.randomUUID().toString())
                pendingDownloads[token.id] = pending
                return TransferReport.RequiresLocalOverwriteConfirmation(
                    confirmation = token,
                    cheatBytes = cheat.size,
                    notesBytes = remote.notes?.size,
                )
            }
            return try {
                checkpoint()
                publishDownload(pending)
            } finally {
                deleteTree(stageDirectory)
            }
        } catch (error: Throwable) {
            deleteTree(stageDirectory)
            throw error
        }
    }

    /** Cancels a local-overwrite prompt and removes its app-cache staging directory. */
    suspend fun discardDownload(confirmation: DownloadOverwriteConfirmation): Boolean =
        withContext(dispatcher) {
            operationMutex.withLock {
                pendingDownloads.remove(confirmation.id)?.let { pending ->
                    deleteTree(pending.stageDirectory)
                    true
                } ?: false
            }
        }

    suspend fun previewUpload(
        profile: DeviceProfile,
        titleId: TitleId,
        buildId: BuildId,
        checkpoint: () -> Unit = {},
    ): UploadPreview = withContext(dispatcher) {
        operationMutex.withLock {
            checkpoint()
            val snapshot = mirror.withWriteTransaction { captureUploadSnapshot(titleId, buildId) }
            val token = UploadConfirmation(UUID.randomUUID().toString())
            uploadStates[token.id] = UploadState.Previewed(PendingUpload(profile, titleId, buildId, snapshot))
            UploadPreview(token, snapshot.cheatBytes.size, snapshot.notesBytes?.size)
        }
    }

    suspend fun uploadConfirmed(
        confirmation: UploadConfirmation,
        directOverwriteConfirmation: DirectOverwriteConfirmation? = null,
        checkpoint: () -> Unit = {},
    ): TransferReport = withContext(dispatcher) {
        operationMutex.withLock {
            uploadConfirmedOnDispatcher(confirmation, directOverwriteConfirmation, checkpoint)
        }
    }

    private suspend fun uploadConfirmedOnDispatcher(
        confirmation: UploadConfirmation,
        directOverwriteConfirmation: DirectOverwriteConfirmation?,
        checkpoint: () -> Unit,
    ): TransferReport {
        val state = uploadStates[confirmation.id] ?: throw InvalidSyncConfirmation()
        val pending = when (state) {
            is UploadState.Previewed -> {
                if (directOverwriteConfirmation != null) throw InvalidSyncConfirmation()
                state.pending
            }
            is UploadState.AwaitingDirect -> {
                if (directOverwriteConfirmation?.id != state.confirmation.id) throw InvalidSyncConfirmation()
                state.pending
            }
        }
        if (!uploadStates.remove(confirmation.id, state)) throw InvalidSyncConfirmation()

        val files = mirror.withWriteTransaction {
            val current = captureUploadSnapshot(pending.titleId, pending.buildId)
            if (current != pending.snapshot) null else CurrentGameFiles(current.cheatBytes, current.notesBytes)
        } ?: run {
            return TransferReport.StaleLocalSnapshot
        }

        val authorization = if (state is UploadState.Previewed) {
            null
        } else {
            DirectOverwriteAuthorization.confirmed()
        }
        checkpoint()
        return when (val result = ftpFactory(pending.profile).uploadCurrent(
            pending.titleId,
            pending.buildId,
            files,
            authorization,
        )) {
            FtpUploadResult.RequiresDirectOverwriteConfirmation -> {
                if (state !is UploadState.Previewed) {
                    throw FtpTransferError("FTP direct overwrite unexpectedly requested another confirmation")
                }
                val token = DirectOverwriteConfirmation(UUID.randomUUID().toString())
                uploadStates[confirmation.id] = UploadState.AwaitingDirect(pending, token)
                TransferReport.RequiresDirectOverwriteConfirmation(token)
            }

            is FtpUploadResult.Uploaded -> {
                TransferReport.Uploaded(
                    cheatBytes = result.cheatBytes,
                    notesBytes = result.notesBytes,
                    retainedRecoveryArtifacts = result.retainedRecoveryArtifacts,
                )
            }
        }
    }

    private fun publishDownload(pending: PendingDownload): TransferReport {
        val cheat = Files.readAllBytes(pending.stagedCheat)
        val notes = pending.stagedNotes?.let(Files::readAllBytes)
        return mirror.withWriteTransaction {
            if (captureCurrentSnapshots(pending.titleId, pending.buildId) != pending.snapshots) {
                return@withWriteTransaction TransferReport.StaleLocalSnapshot
            }
            mirror.atomicReplace(mirror.cheatPath(pending.titleId, pending.buildId), cheat)
            notes?.let { mirror.atomicReplace(mirror.notesPath(pending.titleId, pending.buildId), it) }
            TransferReport.Downloaded(
                cheatBytes = cheat.size,
                notesBytes = notes?.size,
                notes = when {
                    notes != null -> NotesDownloadDisposition.DownloadedRemote
                    pending.snapshots.notes is FileSnapshot.Regular ->
                        NotesDownloadDisposition.MissingRemotePreservedLocal
                    else -> NotesDownloadDisposition.MissingRemoteNoLocalFile
                },
            )
        }
    }

    private fun captureUploadSnapshot(titleId: TitleId, buildId: BuildId): UploadSnapshot {
        val cheatPath = mirror.cheatPath(titleId, buildId)
        val cheatSnapshot = readRegularOrMissing(cheatPath)
        val cheat = (cheatSnapshot as? FileSnapshot.Regular)?.bytes ?: throw LocalCheatMissingError()
        enforceLimit(cheat)
        parseCheat(cheat)
        val notes = when (val snapshot = readRegularOrMissing(mirror.notesPath(titleId, buildId))) {
            FileSnapshot.Missing -> null
            is FileSnapshot.Regular -> snapshot.bytes.also {
                enforceLimit(it)
                validateNotes(it)
            }
        }
        return UploadSnapshot(cheat, notes)
    }

    private fun captureCurrentSnapshots(titleId: TitleId, buildId: BuildId) = CurrentSnapshots(
        cheat = readRegularOrMissing(mirror.cheatPath(titleId, buildId)),
        notes = readRegularOrMissing(mirror.notesPath(titleId, buildId)),
    )

    private fun readRegularOrMissing(path: Path): FileSnapshot {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return FileSnapshot.Missing
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Current mirror target is not a regular file")
        }
        return FileSnapshot.Regular(Files.readAllBytes(path))
    }

    private fun parseCheat(bytes: ByteArray) {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw DownloadedCheatParseError(null, error)
        }
        parser.parse(text).diagnostics.firstOrNull()?.let { diagnostic ->
            throw DownloadedCheatParseError(diagnostic)
        }
    }

    private fun validateNotes(bytes: ByteArray) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
        } catch (error: Exception) {
            throw NotesEncodingError()
        }
    }

    private fun enforceLimit(bytes: ByteArray) {
        if (bytes.size > maxFileBytes) throw FtpSizeLimitError(maxFileBytes)
    }

    private fun createStageDirectory(prefix: String): Path {
        val canonicalRoot = stagingRoot.toAbsolutePath().normalize()
        Files.createDirectories(canonicalRoot)
        return Files.createTempDirectory(canonicalRoot, prefix)
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private data class PendingDownload(
        val profile: DeviceProfile,
        val titleId: TitleId,
        val buildId: BuildId,
        val stageDirectory: Path,
        val stagedCheat: Path,
        val stagedNotes: Path?,
        val snapshots: CurrentSnapshots,
    )

    private data class PendingUpload(
        val profile: DeviceProfile,
        val titleId: TitleId,
        val buildId: BuildId,
        val snapshot: UploadSnapshot,
    )

    private sealed interface UploadState {
        data class Previewed(val pending: PendingUpload) : UploadState
        data class AwaitingDirect(
            val pending: PendingUpload,
            val confirmation: DirectOverwriteConfirmation,
        ) : UploadState
    }

    private data class CurrentSnapshots(val cheat: FileSnapshot, val notes: FileSnapshot)

    private class UploadSnapshot(
        val cheatBytes: ByteArray,
        val notesBytes: ByteArray?,
    ) {
        override fun equals(other: Any?): Boolean = other is UploadSnapshot &&
            cheatBytes.contentEquals(other.cheatBytes) &&
            when {
                notesBytes == null -> other.notesBytes == null
                other.notesBytes == null -> false
                else -> notesBytes.contentEquals(other.notesBytes)
            }

        override fun hashCode(): Int = 31 * cheatBytes.contentHashCode() + (notesBytes?.contentHashCode() ?: 0)
    }

    private sealed interface FileSnapshot {
        data object Missing : FileSnapshot

        class Regular(val bytes: ByteArray) : FileSnapshot {
            private val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

            override fun equals(other: Any?): Boolean =
                other is Regular && MessageDigest.isEqual(digest, other.digest)

            override fun hashCode(): Int = digest.contentHashCode()
        }
    }
}
