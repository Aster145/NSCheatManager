package com.nscheatmanager.app.core.model

import java.util.Locale

private val CanonicalIdPattern = Regex("[0-9A-F]{16}")

@JvmInline
value class TitleId private constructor(val hex: String) {
    companion object {
        fun parse(raw: String): TitleId = TitleId(normalizeCanonicalId(raw, "Title ID"))
    }
}

@JvmInline
value class BuildId private constructor(val hex: String) {
    companion object {
        fun parse(raw: String): BuildId = BuildId(normalizeCanonicalId(raw, "Build ID"))
    }
}

private fun normalizeCanonicalId(raw: String, label: String): String =
    raw.uppercase(Locale.ROOT).also { normalized ->
        require(CanonicalIdPattern.matches(normalized)) {
            "$label must be exactly 16 hexadecimal characters"
        }
    }
