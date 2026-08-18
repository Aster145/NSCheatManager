package com.nscheatmanager.app.cheats.parser

/** The parsed contents of one Atmosphère cheat text file. */
data class CheatFile(
    val groups: List<CheatGroup>,
    val diagnostics: List<CheatParseDiagnostic>,
)

/** One cheat group, retained in the order it occurred in the source file. */
data class CheatGroup(
    val name: String,
    val instructions: List<EncodedInstruction>,
    val startLine: Int,
    /** File-local identity. Names are display-only and are intentionally allowed to repeat. */
    val id: String = "line:$startLine",
)

/**
 * One syntactically well-formed code line.
 *
 * Opcode support is deliberately not decided here. The validator owns that policy.
 */
data class EncodedInstruction(
    val words: List<UInt>,
    val sourceLine: Int,
    val sourceText: String,
)

/** A syntax problem in the source file, with a one-based line number. */
enum class CheatParseDiagnosticKind {
    MalformedGroupHeader,
    InstructionBeforeGroup,
    InvalidInstructionWord,
}

data class CheatParseDiagnostic(
    val line: Int,
    val kind: CheatParseDiagnosticKind,
)
