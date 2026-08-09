package com.nscheatmanager.app.ui.common

import androidx.annotation.StringRes

enum class ErrorCategory { CONNECTION, SESSION, PARSE, EXECUTION, FTP, ZIP, NOEXS, LINK, SETTINGS, MEMORY, UNKNOWN }

data class NetworkEndpoint(val host: String, val port: Int)

enum class OperationContext { SYSBOT, NOEXS, FTP, ZIP, SHARE, LINK, EDITOR, SETTINGS, MEMORY }

data class ErrorContext(val operation: OperationContext? = null, val endpoint: NetworkEndpoint? = null)

data class DiagnosticDetail(
    val category: ErrorCategory,
    val operation: OperationContext? = null,
    val line: Int? = null,
    val opcode: String? = null,
    val endpoint: NetworkEndpoint? = null,
)

data class UserMessage(@param:StringRes val messageRes: Int, val detail: DiagnosticDetail)

class NoLinkHandlerError : IllegalStateException()
