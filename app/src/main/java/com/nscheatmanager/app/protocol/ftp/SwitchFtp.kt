package com.nscheatmanager.app.protocol.ftp

import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import java.io.IOException

/** Exact current-game payload; an absent notes value means "leave remote notes unchanged". */
data class CurrentGameFiles(
    val cheat: ByteArray,
    val notes: ByteArray?,
)

/** Remote current-game snapshot. Missing notes are nonfatal; a missing cheat is reported as null. */
data class DownloadedCurrentGameFiles(
    val cheat: ByteArray?,
    val notes: ByteArray?,
)

/** Opaque protocol authorization created only after the domain confirms a direct overwrite. */
class DirectOverwriteAuthorization private constructor() {
    internal companion object {
        fun confirmed(): DirectOverwriteAuthorization = DirectOverwriteAuthorization()
    }
}

sealed interface FtpUploadResult {
    data class Uploaded(
        val cheatBytes: Int,
        val notesBytes: Int?,
        val retainedRecoveryArtifacts: Boolean = false,
    ) : FtpUploadResult

    /** The server rejected RNFR or RNTO; no current-game target was overwritten. */
    data object RequiresDirectOverwriteConfirmation : FtpUploadResult
}

/**
 * Constrained Switch FTP surface. There is deliberately no arbitrary-path operation.
 * Implementations derive both paths exclusively from validated [TitleId]/[BuildId] values.
 */
interface SwitchFtp {
    suspend fun downloadCurrent(titleId: TitleId, buildId: BuildId): DownloadedCurrentGameFiles

    suspend fun uploadCurrent(
        titleId: TitleId,
        buildId: BuildId,
        files: CurrentGameFiles,
        directOverwriteAuthorization: DirectOverwriteAuthorization? = null,
    ): FtpUploadResult
}

open class FtpTransferError(message: String, cause: Throwable? = null) : IOException(message, cause)

class FtpConnectionError(message: String, cause: Throwable? = null) : FtpTransferError(message, cause)

class FtpTimeoutError(message: String, cause: Throwable? = null) : FtpTransferError(message, cause)

class FtpReplyError(
    val operation: String,
    val replyCode: Int,
    val sanitizedReply: String,
) : FtpTransferError("FTP $operation failed with reply $replyCode: $sanitizedReply")

class FtpSizeLimitError(val limitBytes: Int) :
    FtpTransferError("FTP file exceeded the $limitBytes byte limit")

class FtpVerificationError(message: String) : FtpTransferError(message)

class FtpRollbackError(message: String, cause: Throwable? = null) : FtpTransferError(message, cause)
