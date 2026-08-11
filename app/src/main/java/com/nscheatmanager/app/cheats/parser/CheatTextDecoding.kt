package com.nscheatmanager.app.cheats.parser

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Cheat files are frequently produced by homebrew that writes legacy single-byte
 * title text.  Keep their bytes untouched on disk and only use replacement
 * characters for non-UTF-8 display text.
 */
object CheatTextDecoding {
    fun decodeForParsing(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        String(bytes, StandardCharsets.UTF_8)
    }
}
