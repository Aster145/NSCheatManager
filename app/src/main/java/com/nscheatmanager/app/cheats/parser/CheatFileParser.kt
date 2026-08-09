package com.nscheatmanager.app.cheats.parser

/**
 * Line-oriented parser for Atmosphère cheat files.
 *
 * This parser only checks text structure. In particular, an unknown opcode remains an
 * [EncodedInstruction] so that a later validation step can report its support status.
 */
class CheatFileParser {
    fun parse(source: String): CheatFile {
        val groups = mutableListOf<MutableCheatGroup>()
        val diagnostics = mutableListOf<CheatParseDiagnostic>()
        var currentGroup: MutableCheatGroup? = null

        source.removePrefix("\uFEFF")
            .split('\n')
            .forEachIndexed { index, rawLine ->
                val lineNumber = index + 1
                val line = rawLine.removeSuffix("\r")
                val trimmed = line.trim()

                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                    return@forEachIndexed
                }

                parseHeader(trimmed)?.let { name ->
                    currentGroup = MutableCheatGroup(name, lineNumber).also(groups::add)
                    return@forEachIndexed
                }

                if (trimmed.startsWith("[")) {
                    diagnostics += CheatParseDiagnostic(lineNumber, "Malformed group header")
                    return@forEachIndexed
                }

                val group = currentGroup
                if (group == null) {
                    diagnostics += CheatParseDiagnostic(lineNumber, "Instruction appears before a group header")
                    return@forEachIndexed
                }

                val words = trimmed.split(Whitespace)
                if (words.size < MinimumInstructionWords) {
                    diagnostics += CheatParseDiagnostic(
                        lineNumber,
                        "Instruction must contain at least two words",
                    )
                    return@forEachIndexed
                }
                if (words.any { !HexWord.matches(it) }) {
                    diagnostics += CheatParseDiagnostic(
                        lineNumber,
                        "Instruction words must be exactly eight hexadecimal characters",
                    )
                    return@forEachIndexed
                }

                group.instructions += EncodedInstruction(
                    words = words.map { it.toUInt(radix = 16) },
                    sourceLine = lineNumber,
                    sourceText = line,
                )
            }

        return CheatFile(
            groups = groups.map { group ->
                CheatGroup(group.name, group.instructions.toList(), group.startLine)
            },
            diagnostics = diagnostics.toList(),
        )
    }

    private fun parseHeader(trimmedLine: String): String? {
        if (!trimmedLine.startsWith('[') || !trimmedLine.endsWith(']')) return null

        val name = trimmedLine.substring(1, trimmedLine.lastIndex).trim()
        return name.takeIf { it.isNotEmpty() && '[' !in it && ']' !in it }
    }

    private data class MutableCheatGroup(
        val name: String,
        val startLine: Int,
        val instructions: MutableList<EncodedInstruction> = mutableListOf(),
    )

    private companion object {
        const val MinimumInstructionWords = 2
        val HexWord = Regex("[0-9A-Fa-f]{8}")
        val Whitespace = Regex("\\s+")
    }
}
