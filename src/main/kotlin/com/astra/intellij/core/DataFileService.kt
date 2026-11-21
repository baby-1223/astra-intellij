package com.astra.intellij.core

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets

object DataFileService {
    fun parse(file: File): List<Map<String, String>> {
        require(file.exists()) { "Data file does not exist: ${file.path}" }
        require(file.isFile) { "Data file is not a regular file: ${file.path}" }
        val text = runCatching { file.readText(StandardCharsets.UTF_8).trimStart('\uFEFF') }
            .getOrElse { throw IllegalArgumentException("Unable to read data file: ${it.message}") }
        return when (file.extension.lowercase()) {
            "csv" -> parseCsv(text)
            "json" -> parseJson(text)
            else -> throw IllegalArgumentException("Unsupported data file type. Choose a .csv or .json file.")
        }
    }

    fun parseCsv(text: String): List<Map<String, String>> {
        val records = parseCsvRecords(text).filterNot { row -> row.size == 1 && row.single().isBlank() }
        require(records.isNotEmpty()) { "CSV file is empty" }
        val header = records.first().map { it.trim() }
        require(header.isNotEmpty()) { "CSV header is missing" }
        require(header.all { it.isNotBlank() }) { "CSV header contains an empty column name" }
        return records.drop(1).map { row ->
            header.indices.associate { column ->
                header[column] to row.getOrElse(column) { "" }.trim()
            }
        }
    }

    fun parseJson(text: String): List<Map<String, String>> {
        require(text.isNotBlank()) { "JSON file is empty" }
        val root = runCatching { JsonParser.parseString(text) }
            .getOrElse { throw IllegalArgumentException("Invalid JSON: ${it.message ?: "parse error"}") }
        require(root.isJsonArray) { "JSON data file must be an array of objects" }
        return root.asJsonArray.mapIndexed { index, element ->
            require(element.isJsonObject) { "JSON row ${index + 1} must be an object" }
            element.asJsonObject.entrySet().associate { (key, value) -> key to valueToString(value) }
        }
    }

    private fun parseCsvRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val char = text[i]
            if (inQuotes) {
                when {
                    char == '"' && text.getOrNull(i + 1) == '"' -> {
                        field.append('"')
                        i += 2
                    }
                    char == '"' -> {
                        inQuotes = false
                        i++
                    }
                    else -> {
                        field.append(char)
                        i++
                    }
                }
            } else {
                when (char) {
                    '"' -> {
                        inQuotes = true
                        i++
                    }
                    ',' -> {
                        row.add(field.toString())
                        field.setLength(0)
                        i++
                    }
                    '\n' -> {
                        row.add(field.toString())
                        records.add(row.toList())
                        row.clear()
                        field.setLength(0)
                        i++
                    }
                    '\r' -> {
                        row.add(field.toString())
                        records.add(row.toList())
                        row.clear()
                        field.setLength(0)
                        i += if (text.getOrNull(i + 1) == '\n') 2 else 1
                    }
                    else -> {
                        field.append(char)
                        i++
                    }
                }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            records.add(row.toList())
        }
        return records
    }

    private fun valueToString(element: JsonElement): String = when {
        element.isJsonNull -> ""
        element.isJsonPrimitive -> element.asString
        else -> element.toString()
    }
}
