package com.nscheatmanager.app.ui.common

import androidx.annotation.StringRes

enum class ErrorCategory { CONNECTION, SESSION, PARSE, EXECUTION, FTP, ZIP, NOEXS, LINK, SETTINGS, MEMORY, UNKNOWN }

data class NetworkEndpoint(val host: String, val port: Int)

data class ErrorContext(val operation: String? = null, val endpoint: NetworkEndpoint? = null)

data class DiagnosticDetail(
    val category: ErrorCategory,
    val operation: String? = null,
    val line: Int? = null,
    val opcode: String? = null,
    val endpoint: NetworkEndpoint? = null,
)

data class UserMessage(@param:StringRes val messageRes: Int, val detail: DiagnosticDetail)

class NoLinkHandlerError : IllegalStateException()
