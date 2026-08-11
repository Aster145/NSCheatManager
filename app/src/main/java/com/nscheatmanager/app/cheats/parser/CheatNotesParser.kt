package com.nscheatmanager.app.cheats.parser

/** Parses title-level notes.txt sections into cheat-name keyed remarks. */
object CheatNotesParser {
    private val header = Regex("^\\[([^]]+)]\\s*$")

    fun parse(text: String): Map<String, String> {
        val notes = linkedMapOf<String, String>()
        var name: String? = null
        var collecting = false
        val content = StringBuilder()

        fun commit() {
            val key = name ?: return
            if (collecting) notes[key] = content.toString().trim()
        }

        text.lineSequence().forEach { line ->
            val match = header.matchEntire(line)
            if (match != null) {
                commit()
                name = match.groupValues[1]
                collecting = false
                content.clear()
            } else if (name != null && line.startsWith("note=")) {
                collecting = true
                content.clear()
                content.append(line.removePrefix("note="))
            } else if (collecting) {
                content.append('\n').append(line)
            }
        }
        commit()
        return notes
    }
}
