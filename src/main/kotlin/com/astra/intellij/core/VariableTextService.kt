package com.astra.intellij.core

/** Parses and renders the `.env` / CSV variable formats used by "Import / Copy as .env". */
object VariableTextService {
    private val headerNames = setOf("key", "name", "variable", "variable name")

    /**
     * Parses pasted text into ordered key/value pairs.
     *
     * Supports `.env` syntax (`KEY=VALUE`, optional `export`, quoted values) and two-column CSV
     * with an optional `key,value` header. Blank lines and `#` comments are ignored.
     */
    fun parse(text: String): List<Pair<String, String>> {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        if (lines.isEmpty()) return emptyList()
        val first = lines.first()
        val looksCsv = first.contains(',') && !first.contains('=')
        return if (looksCsv) parseCsv(lines) else parseDotEnv(lines)
    }

    /** Renders enabled variables as `.env` lines, quoting values that need it. */
    fun toDotEnv(variables: Map<String, String>): String = buildString {
        variables.forEach { (key, value) ->
            val name = key.trim()
            if (name.isEmpty()) return@forEach
            append(name).append('=').append(envValue(value)).append('\n')
        }
    }

    private fun parseDotEnv(lines: List<String>): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        lines.forEach { raw ->
            val line = if (raw.startsWith("export ")) raw.removePrefix("export ").trim() else raw
            val index = line.indexOf('=')
            if (index <= 0) return@forEach
            val key = line.substring(0, index).trim()
            if (key.isEmpty()) return@forEach
            var value = line.substring(index + 1).trim()
            if (value.length >= 2) {
                val quote = value.first()
                if ((quote == '"' || quote == '\'') && value.last() == quote) {
                    value = value.substring(1, value.length - 1)
                    if (quote == '"') {
                        value = value
                            .replace("\\n", "\n")
                            .replace("\\r", "\r")
                            .replace("\\t", "\t")
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                    }
                }
            }
            result += key to value
        }
        return result
    }

    private fun parseCsv(lines: List<String>): List<Pair<String, String>> {
        val header = csvFields(lines.first()).map { it.lowercase() }
        val start = if (header.size >= 2 && header.first() in headerNames) 1 else 0
        val result = mutableListOf<Pair<String, String>>()
        for (index in start until lines.size) {
            val fields = csvFields(lines[index])
            val key = fields.firstOrNull()?.trim().orEmpty()
            if (key.isEmpty()) continue
            result += key to fields.getOrElse(1) { "" }.trim()
        }
        return result
    }

    /** Minimal CSV field splitter: comma separated, double quotes with `""` escapes. */
    private fun csvFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val buffer = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                quoted && char == '"' && line.getOrNull(index + 1) == '"' -> {
                    buffer.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    fields += buffer.toString()
                    buffer.setLength(0)
                }
                else -> buffer.append(char)
            }
            index++
        }
        fields += buffer.toString()
        return fields
    }

    private fun envValue(value: String): String {
        if (value.isEmpty()) return ""
        if (value.none { it.isWhitespace() || it == '#' || it == '\'' || it == '"' || it == '\\' }) {
            return value
        }
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
