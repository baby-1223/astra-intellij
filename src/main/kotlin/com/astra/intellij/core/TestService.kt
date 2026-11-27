package com.astra.intellij.core

import com.astra.intellij.model.HttpResult
import com.google.gson.JsonElement
import com.google.gson.JsonParser

data class AssertionResult(
    val assertion: String,
    val passed: Boolean,
    val detail: String,
)

/** `extract <name> = <json path>` line from the Tests tab. */
data class Extraction(val name: String, val path: String)

object TestService {
    private val operators = setOf("==", "!=", "<", "<=", ">", ">=")

    fun evaluate(assertions: List<String>, result: HttpResult): List<AssertionResult> =
        assertions.filter { it.isNotBlank() && !isExtraction(it) }.map { line ->
            runCatching { check(line.trim(), result) }
                .getOrElse { AssertionResult(line, false, it.message ?: "Invalid assertion") }
        }

    fun parseExtractions(lines: List<String>): List<Extraction> =
        lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (!isExtraction(trimmed)) return@mapNotNull null
            val body = trimmed.substring(7).trim()
            val separator = body.indexOf('=')
            val name: String
            val path: String
            if (separator >= 0) {
                name = body.substring(0, separator).trim()
                path = body.substring(separator + 1).trim()
            } else {
                val tokens = tokenize(body)
                name = tokens.getOrNull(0).orEmpty()
                path = tokens.getOrNull(1).orEmpty()
            }
            if (name.isBlank() || path.isBlank()) null else Extraction(name, path)
        }

    /** Extracts JSON values declared in the Tests tab, e.g. `extract token = $.data.token`. */
    fun extract(lines: List<String>, result: HttpResult): List<Pair<String, String>> {
        val extractions = parseExtractions(lines)
        if (extractions.isEmpty()) return emptyList()
        val root = runCatching { JsonParser.parseString(ResponseCharset.decode(result)) }.getOrNull()
            ?: return emptyList()
        return extractions.mapNotNull { extraction ->
            val element = jsonAllAt(root, extraction.path)
                .firstOrNull { !it.isJsonNull }
                ?: return@mapNotNull null
            val value = if (element.isJsonPrimitive) element.asString else element.toString()
            extraction.name to value
        }
    }

    private fun isExtraction(line: String): Boolean =
        line.startsWith("extract ", ignoreCase = true) ||
            line.startsWith("extract\t", ignoreCase = true)

    private fun check(line: String, result: HttpResult): AssertionResult {
        val tokens = tokenize(line)
        if (tokens.isEmpty()) return AssertionResult(line, true, "")
        val body = ResponseCharset.decode(result)
        return when (tokens[0].lowercase()) {
            "status" -> {
                val (op, expected) = comparison(tokens, 1)
                val expectedNumber = expected.toDoubleOrNull()
                    ?: return fail(line, "Expected a numeric status, got '$expected'")
                val passed = compare(result.status.toDouble(), op, expectedNumber)
                AssertionResult(line, passed, "expected $op ${expectedNumber.toInt()}, actual ${result.status}")
            }
            "time" -> {
                val (op, expected) = comparison(tokens, 1)
                val expectedNumber = expected.toDoubleOrNull()
                    ?: return fail(line, "Expected milliseconds, got '$expected'")
                val passed = compare(result.elapsedMs.toDouble(), op, expectedNumber)
                AssertionResult(line, passed, "expected $op ${expectedNumber.toLong()} ms, actual ${result.elapsedMs} ms")
            }
            "body" -> when (tokens.getOrNull(1)?.lowercase()) {
                "contains" -> {
                    val expected = tokens.drop(2).joinToString(" ")
                    AssertionResult(line, body.contains(expected), "expected body to contain \"$expected\"")
                }
                "matches" -> {
                    val expected = tokens.drop(2).joinToString(" ")
                    val passed = runCatching { Regex(expected).containsMatchIn(body) }.getOrDefault(false)
                    AssertionResult(line, passed, "expected body to match /$expected/")
                }
                else -> fail(line, "Use 'body contains <text>' or 'body matches <regex>'")
            }
            "header" -> {
                val name = tokens.getOrNull(1) ?: return fail(line, "Missing header name")
                val values = result.headers.entries
                    .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()
                val actual = values.joinToString(", ")
                when (val op = tokens.getOrNull(2)?.lowercase() ?: "exists") {
                    "exists" -> AssertionResult(line, values.isNotEmpty(), "expected header $name to exist")
                    "contains" -> {
                        val expected = tokens.drop(3).joinToString(" ")
                        AssertionResult(line, actual.contains(expected, ignoreCase = true), "actual: $actual")
                    }
                    else -> {
                        val expected = tokens.drop(3).joinToString(" ")
                        AssertionResult(line, compareText(actual, op, expected), "actual: $actual")
                    }
                }
            }
            "json", "jsonpath" -> jsonAssertion(line, tokens, body)
            else -> fail(line, "Unknown assertion '${tokens[0]}'")
        }
    }

    private fun jsonAssertion(line: String, tokens: List<String>, body: String): AssertionResult {
        val path = tokens.getOrNull(1) ?: return fail(line, "Missing JSON path, e.g. $.data.id")
        val root = runCatching { JsonParser.parseString(body) }.getOrNull()
            ?: return fail(line, "Response body is not valid JSON")
        val elements = jsonAllAt(root, path).filter { !it.isJsonNull }
        val op = tokens.getOrNull(2) ?: "exists"
        if (op == "exists") {
            return AssertionResult(line, elements.isNotEmpty(), "expected $path to exist")
        }
        if (elements.isEmpty()) return fail(line, "$path does not exist")
        val expected = tokens.drop(3).joinToString(" ")
        val actualValues = elements.map { if (it.isJsonPrimitive) it.asString else it.toString() }
        // A wildcard path can match several values; the assertion passes when any value matches.
        val passed = actualValues.any { compareText(it, op, expected) }
        val actual = if (actualValues.size == 1) actualValues.first() else actualValues.joinToString(", ")
        return AssertionResult(line, passed, "actual: $actual")
    }

    private fun comparison(tokens: List<String>, start: Int): Pair<String, String> {
        val first = tokens.getOrNull(start) ?: return "==" to ""
        return if (first in operators) first to tokens.getOrNull(start + 1).orEmpty() else "==" to first
    }

    private fun compare(actual: Double, op: String, expected: Double): Boolean = when (op) {
        "==" -> actual == expected
        "!=" -> actual != expected
        "<" -> actual < expected
        "<=" -> actual <= expected
        ">" -> actual > expected
        ">=" -> actual >= expected
        else -> false
    }

    private fun compareText(actual: String, op: String, expected: String): Boolean {
        val actualNumber = actual.toDoubleOrNull()
        val expectedNumber = expected.toDoubleOrNull()
        if (actualNumber != null && expectedNumber != null) return compare(actualNumber, op, expectedNumber)
        return when (op) {
            "==" -> actual == expected || actual.equals(expected, ignoreCase = true)
            "!=" -> actual != expected && !actual.equals(expected, ignoreCase = true)
            "contains" -> actual.contains(expected, ignoreCase = true)
            else -> false
        }
    }

    /** First match for [path]; kept for callers that only need a single value. */
    internal fun jsonAt(root: JsonElement, path: String): JsonElement? =
        JsonPathSupport.firstAt(root, path)

    internal fun jsonAllAt(root: JsonElement, path: String): List<JsonElement> =
        JsonPathSupport.allAt(root, path)

    private fun tokenize(line: String): List<String> =
        Regex("\"([^\"]*)\"|(\\S+)").findAll(line)
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            .toList()

    private fun fail(line: String, detail: String) = AssertionResult(line, false, detail)
}
