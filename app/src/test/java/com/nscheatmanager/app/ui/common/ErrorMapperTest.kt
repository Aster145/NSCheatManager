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
import com.nscheatmanager.app.domain.StaleGameOperationException
import com.nscheatmanager.app.domain.CheatGroupBusyException
import com.nscheatmanager.app.domain.SessionCloseTimeoutException
import com.nscheatmanager.app.domain.NotesEncodingError
import com.nscheatmanager.app.domain.InvalidSyncConfirmation
import com.nscheatmanager.app.cheats.parser.CheatParseDiagnostic
import com.nscheatmanager.app.cheats.parser.CheatParseDiagnosticKind
import com.nscheatmanager.app.ui.memory.MemoryError
import com.nscheatmanager.app.ui.settings.DeviceEditorError
import com.nscheatmanager.app.ui.settings.SettingsMessage
import com.nscheatmanager.app.protocol.ProtocolError
import com.nscheatmanager.app.protocol.ftp.FtpConnectionError
import com.nscheatmanager.app.protocol.ftp.FtpReplyError
import com.nscheatmanager.app.protocol.ftp.FtpRollbackError
import com.nscheatmanager.app.protocol.ftp.FtpTimeoutError
import com.nscheatmanager.app.protocol.ftp.FtpSizeLimitError
import com.nscheatmanager.app.protocol.ftp.FtpVerificationError
import com.nscheatmanager.app.protocol.ftp.FtpTransferError
import com.nscheatmanager.app.protocol.noexs.NoexsResultError
import java.net.ConnectException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorMapperTest {
    private val endpoint = ErrorContext(OperationContext.SYSBOT, NetworkEndpoint("192.168.1.35", 6000))

    @Test fun mapsProtocolFailuresWithoutLeakingPayloads() {
        assertMessage(ProtocolError.Timeout("read", Exception("secret")), R.string.error_connection_timeout)
        assertMessage(ProtocolError.Connection(ConnectException("Connection refused: credential=x")), R.string.error_connection_refused)
        val malformed = ErrorMapper.map(ProtocolError.MalformedResponse("SECRET PAYLOAD"), endpoint)!!
        assertEquals(R.string.error_malformed_response, malformed.messageRes)
        assertFalse(malformed.detail.toString().contains("SECRET"))
        assertEquals("192.168.1.35", malformed.detail.endpoint?.host)
    }

    @Test fun mapsSessionValidationAndPartialExecution() {
        assertMessage(SessionNotReadyException("No foreground game"), R.string.error_no_foreground_game)
        val unsupported = ErrorMapper.map(CheatValidationError.UnsupportedOpcode(18, 0x8))
        assertEquals(R.string.error_unsupported_opcode, unsupported.messageRes)
        assertEquals(18, unsupported.detail.line)
        assertEquals("0x8", unsupported.detail.opcode)
        val partial = ErrorMapper.map(ExecutionReport(ExecutionStatus.Partial, 1, 23))
        assertEquals(R.string.error_partial_execution, partial.messageRes)
        assertEquals(23, partial.detail.line)
    }

    @Test fun mapsFtpZipNoexsAndLinkFailures() {
        assertMessage(FtpConnectionError("bad", ConnectException()), R.string.error_ftp_connection)
        assertMessage(FtpTimeoutError("timeout"), R.string.error_ftp_timeout)
        assertMessage(LocalCheatMissingError(), R.string.error_ftp_local_missing)
        assertMessage(FtpReplyError("STOR", 553, "overwrite denied"), R.string.error_ftp_overwrite)
        assertMessage(FtpRollbackError("rollback failed"), R.string.error_ftp_rollback)
        assertMessage(DownloadedCheatParseError(null), R.string.error_cheat_malformed)
        assertMessage(ZipImportError("traversal ../secret"), R.string.error_zip_import)
        assertMessage(ZipExportError("disk payload"), R.string.error_zip_export)
        assertMessage(NoexsResultError(1, 2, 1025), R.string.error_noexs)
        assertMessage(NoLinkHandlerError(), R.string.qq_no_handler)
    }

    @Test fun cancellationNeverBecomesAUserError() {
        assertNull(ErrorMapper.map(CancellationException("cancelled"), endpoint))
    }

    @Test fun mapsEverySessionParserSettingsAndMemoryKind() {
        assertMessage(StaleGameOperationException(), R.string.memory_error_changed)
        assertMessage(CheatGroupBusyException("secret group"), R.string.error_cheat_busy)
        assertMessage(SessionCloseTimeoutException(100, Exception()), R.string.error_session_close)
        assertMessage(NotesEncodingError(), R.string.error_notes_encoding)
        assertMessage(InvalidSyncConfirmation(), R.string.error_confirmation_expired)
        CheatParseDiagnosticKind.entries.forEach {
            val mapped = ErrorMapper.map(CheatParseDiagnostic(7, it))
            assertEquals(R.string.error_cheat_malformed, mapped.messageRes)
            assertEquals(7, mapped.detail.line)
        }
        DeviceEditorError.entries.forEach { assertEquals(it.stringResourceForTest(), ErrorMapper.map(it).messageRes) }
        SettingsMessage.entries.forEach { assertEquals(ErrorCategory.SETTINGS, ErrorMapper.map(it).detail.category) }
        MemoryError.entries.forEach { assertEquals(ErrorCategory.MEMORY, ErrorMapper.map(it).detail.category) }
    }

    @Test fun stripsUntrustedOperationAndEndpointContext() {
        val mapped = ErrorMapper.map(
            IllegalStateException("PASSWORD=secret"),
            ErrorContext(OperationContext.FTP, NetworkEndpoint("user:pass@example.com", 70000)),
        )!!
        assertEquals(OperationContext.FTP, mapped.detail.operation)
        assertNull(mapped.detail.endpoint)
        assertFalse(mapped.toString().contains("PASSWORD"))
    }

    @Test fun noexsContextOverridesEveryTransportFailureAndRetainsOnlyNoexsEndpoint() {
        val context = ErrorContext(OperationContext.NOEXS, NetworkEndpoint("192.168.1.35", 7331))
        listOf<Throwable>(
            ProtocolError.Connection(ConnectException()), ProtocolError.Timeout("detach", Exception()),
            ProtocolError.Disconnected(), ProtocolError.MalformedResponse("secret"), NoexsResultError(1, 2, 3),
        ).forEach {
            val mapped = requireNotNull(ErrorMapper.map(it, context))
            assertEquals(R.string.error_noexs, mapped.messageRes)
            assertEquals(ErrorCategory.NOEXS, mapped.detail.category)
            assertEquals(7331, mapped.detail.endpoint?.port)
        }
    }

    @Test fun exhaustivelyMapsCurrentProtocolAndFtpSubtypes() {
        val cases = listOf(
            ProtocolError.Disconnected() to R.string.error_disconnected,
            ProtocolError.ResponseTooLarge(1) to R.string.error_protocol_limit,
            ProtocolError.CommandTooLarge(1, 2) to R.string.error_protocol_limit,
            FtpReplyError("LIST", 500, "safe") to R.string.error_ftp_reply,
            FtpSizeLimitError(1) to R.string.error_ftp_size,
            FtpVerificationError("secret") to R.string.error_ftp_transfer,
            FtpTransferError("secret") to R.string.error_ftp_transfer,
        )
        cases.forEach { (error, resource) -> assertEquals(resource, ErrorMapper.map(error, endpoint)?.messageRes) }
    }

    @Test fun exhaustivelyMapsCurrentCheatValidationSubtypes() {
        val errors = listOf<CheatValidationError>(
            CheatValidationError.UnsupportedOpcode(1, 8),
            CheatValidationError.UnsupportedForm(2, "secret"),
            CheatValidationError.UnsupportedMemoryRegion(3, 9),
            CheatValidationError.ArithmeticOverflow(4),
            CheatValidationError.InstructionLimitExceeded(5, 1),
            CheatValidationError.IoLimitExceeded(6, 1),
        )
        errors.forEach { error ->
            val mapped = ErrorMapper.map(error)
            assertEquals(ErrorCategory.EXECUTION, mapped.detail.category)
            assertEquals(error.line, mapped.detail.line)
            assertFalse(mapped.toString().contains("secret"))
        }
    }

    private fun DeviceEditorError.stringResourceForTest() = when (this) {
        DeviceEditorError.NAME_REQUIRED -> R.string.error_name_required
        DeviceEditorError.INVALID_IPV4 -> R.string.error_invalid_ipv4
        DeviceEditorError.INVALID_PORT -> R.string.error_invalid_port
        DeviceEditorError.DUPLICATE_NAME -> R.string.error_duplicate_name
        DeviceEditorError.DUPLICATE_HOST -> R.string.error_duplicate_host
        DeviceEditorError.SAVE_FAILED -> R.string.error_save_device
    }

    private fun assertMessage(error: Throwable, expected: Int) {
        assertEquals(expected, ErrorMapper.map(error, endpoint)?.messageRes)
    }
}
