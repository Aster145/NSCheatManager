package com.nscheatmanager.app.ui.common

import com.nscheatmanager.app.R
import com.nscheatmanager.app.cheats.vm.CheatValidationError
import com.nscheatmanager.app.cheats.vm.ExecutionReport
import com.nscheatmanager.app.cheats.vm.ExecutionStatus
import com.nscheatmanager.app.data.files.ZipExportError
import com.nscheatmanager.app.data.files.ZipImportError
import com.nscheatmanager.app.domain.DownloadedCheatParseError
import com.nscheatmanager.app.domain.LocalCheatMissingError
import com.nscheatmanager.app.domain.SessionNotReadyException
import com.nscheatmanager.app.domain.*
import com.nscheatmanager.app.cheats.parser.CheatParseDiagnostic
import com.nscheatmanager.app.ui.memory.MemoryError
import com.nscheatmanager.app.ui.settings.DeviceEditorError
import com.nscheatmanager.app.ui.settings.SettingsMessage
import com.nscheatmanager.app.protocol.ProtocolError
import com.nscheatmanager.app.protocol.ftp.*
import com.nscheatmanager.app.protocol.noexs.NoexsResultError
import java.net.ConnectException
import java.util.concurrent.CancellationException

object ErrorMapper {
    fun map(error: Throwable, context: ErrorContext = ErrorContext()): UserMessage? {
        if (error is CancellationException) return null
        val (resource, category) = if (context.operation == OperationContext.NOEXS &&
            (error is ProtocolError || error is NoexsResultError)
        ) {
            R.string.error_noexs to ErrorCategory.NOEXS
        } else when (error) {
            is ProtocolError.Timeout -> R.string.error_connection_timeout to ErrorCategory.CONNECTION
            is ProtocolError.Connection -> (if (error.hasCause<ConnectException>()) R.string.error_connection_refused else R.string.error_connection) to ErrorCategory.CONNECTION
            is ProtocolError.Disconnected -> R.string.error_disconnected to ErrorCategory.CONNECTION
            is ProtocolError.MalformedResponse -> R.string.error_malformed_response to ErrorCategory.CONNECTION
            is ProtocolError.ResponseTooLarge, is ProtocolError.CommandTooLarge -> R.string.error_protocol_limit to ErrorCategory.CONNECTION
            is SessionNotReadyException -> R.string.error_no_foreground_game to ErrorCategory.SESSION
            is StaleGameOperationException -> R.string.memory_error_changed to ErrorCategory.SESSION
            is CheatGroupBusyException -> R.string.error_cheat_busy to ErrorCategory.EXECUTION
            is SessionCloseTimeoutException -> R.string.error_session_close to ErrorCategory.SESSION
            is NotesEncodingError -> R.string.error_notes_encoding to ErrorCategory.FTP
            is InvalidSyncConfirmation -> R.string.error_confirmation_expired to ErrorCategory.FTP
            is DownloadedCheatParseError -> R.string.error_cheat_malformed to ErrorCategory.PARSE
            is LocalCheatMissingError -> R.string.error_ftp_local_missing to ErrorCategory.FTP
            is FtpTimeoutError -> R.string.error_ftp_timeout to ErrorCategory.FTP
            is FtpConnectionError -> R.string.error_ftp_connection to ErrorCategory.FTP
            is FtpRollbackError -> R.string.error_ftp_rollback to ErrorCategory.FTP
            is FtpReplyError -> (if (error.replyCode in setOf(450, 550, 553)) R.string.error_ftp_overwrite else R.string.error_ftp_reply) to ErrorCategory.FTP
            is FtpSizeLimitError -> R.string.error_ftp_size to ErrorCategory.FTP
            is FtpVerificationError, is FtpTransferError -> R.string.error_ftp_transfer to ErrorCategory.FTP
            is ZipImportError -> R.string.error_zip_import to ErrorCategory.ZIP
            is ZipExportError -> R.string.error_zip_export to ErrorCategory.ZIP
            is NoexsResultError -> R.string.error_noexs to ErrorCategory.NOEXS
            is NoLinkHandlerError -> R.string.qq_no_handler to ErrorCategory.LINK
            else -> R.string.operation_failed to ErrorCategory.UNKNOWN
        }
        return UserMessage(resource, DiagnosticDetail(category, context.operation, endpoint = context.safeEndpoint()))
    }

    fun map(error: CheatValidationError): UserMessage {
        val resource = if (error is CheatValidationError.UnsupportedOpcode) R.string.error_unsupported_opcode else R.string.error_unsupported_cheat
        val opcode = (error as? CheatValidationError.UnsupportedOpcode)?.opcode?.let { "0x${it.toString(16).uppercase()}" }
        return UserMessage(resource, DiagnosticDetail(ErrorCategory.EXECUTION, line = error.line, opcode = opcode))
    }

    fun map(report: ExecutionReport): UserMessage {
        report.validationError?.let { return map(it) }
        val resource = if (report.status == ExecutionStatus.Partial) R.string.error_partial_execution else R.string.operation_failed
        return UserMessage(resource, DiagnosticDetail(ErrorCategory.EXECUTION, line = report.failureLine))
    }

    fun map(diagnostic: CheatParseDiagnostic) = UserMessage(
        R.string.error_cheat_malformed,
        DiagnosticDetail(ErrorCategory.PARSE, line = diagnostic.line),
    )

    fun map(error: DeviceEditorError): UserMessage = UserMessage(
        when (error) {
            DeviceEditorError.NAME_REQUIRED -> R.string.error_name_required
            DeviceEditorError.INVALID_IPV4 -> R.string.error_invalid_ipv4
            DeviceEditorError.INVALID_PORT -> R.string.error_invalid_port
            DeviceEditorError.DUPLICATE_NAME -> R.string.error_duplicate_name
            DeviceEditorError.DUPLICATE_HOST -> R.string.error_duplicate_host
            DeviceEditorError.SAVE_FAILED -> R.string.error_save_device
        }, DiagnosticDetail(ErrorCategory.SETTINGS),
    )

    fun map(message: SettingsMessage): UserMessage = UserMessage(
        when (message) {
            SettingsMessage.DELETE_FAILED -> R.string.error_delete_device
            SettingsMessage.DEFAULT_FAILED -> R.string.error_default_device
            SettingsMessage.LANGUAGE_FAILED -> R.string.error_language_change
            SettingsMessage.MEMORY_VISIBILITY_FAILED -> R.string.error_memory_visibility_change
        }, DiagnosticDetail(ErrorCategory.SETTINGS),
    )

    fun map(error: MemoryError): UserMessage = UserMessage(
        when (error) {
            MemoryError.SessionRequired -> R.string.memory_error_session
            MemoryError.InvalidAddress -> R.string.memory_error_address
            MemoryError.InvalidLength -> R.string.memory_error_length
            MemoryError.InvalidValue -> R.string.memory_error_value
            MemoryError.OperationFailed -> R.string.memory_error_operation
            MemoryError.SessionChanged -> R.string.memory_error_changed
        }, DiagnosticDetail(ErrorCategory.MEMORY),
    )

    private fun ErrorContext.safeEndpoint() = endpoint?.takeIf {
        it.port in 1..65535 && it.host.split('.').let { parts ->
            parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { octet -> octet in 0..255 } == true }
        }
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean = generateSequence(this) { it.cause }.any { it is T }
}
