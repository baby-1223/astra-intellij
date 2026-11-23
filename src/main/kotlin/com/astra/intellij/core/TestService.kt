package com.astra.intellij.core

import com.astra.intellij.model.HttpResult
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets

data class AssertionResult(
    val assertion: String,
    val passed: Boolean,
    val detail: String,
)

object TestService {
    private val operators = setOf("==", "!=", "<", "<=", ">", ">=")

    fun evaluate(assertions: List<String>, result: HttpResult): List<AssertionResult> =
        assertions.filter { it.isNotBlank() }.map { line ->
            runCatching { check(line.trim(), result) }
                .getOrElse { AssertionResult(line, false, it.message ?: "Invalid assertion") }
        }

    private fun check(line: String, result: HttpResult): AssertionResult {
        val tokens = tokenize(line)
        if (tokens.isEmpty()) return AssertionResult(line, true, "")
        val body = String(result.body, StandardCharsets.UTF_8)
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
            "json" -> {
                val path = tokens.getOrNull(1) ?: return fail(line, "Missing JSON path, e.g. $.data.id")
                val root = runCatching { JsonParser.parseString(body) }.getOrNull()
                    ?: return fail(line, "Response body is not valid JSON")
                val element = jsonAt(root, path)
                val op = tokens.getOrNull(2) ?: "exists"
                if (op == "exists") {
                    return AssertionResult(line, element != null && !element.isJsonNull, "expected $path to exist")
                }
                if (element == null || element.isJsonNull) return fail(line, "$path does not exist")
                val expected = tokens.drop(3).joinToString(" ")
                val actual = if (element.isJsonPrimitive) element.asString else element.toString()
                AssertionResult(line, compareText(actual, op, expected), "actual: $actual")
            }
            else -> fail(line, "Unknown assertion '${tokens[0]}'")
        }
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

    internal fun jsonAt(root: JsonElement, path: String): JsonElement? {
        var current: JsonElement? = root
        val segments = Regex("\\.([^.\\[\\]]+)|\\[(\\d+)]").findAll(path.removePrefix("$"))
        for (segment in segments) {
            current = when {
                segment.groupValues[1].isNotEmpty() -> current
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.get(segment.groupValues[1])
                else -> {
                    val index = segment.groupValues[2].toInt()
                    current?.takeIf { it.isJsonArray }?.asJsonArray?.let { array ->
                        if (index < array.size()) array[index] else null
                    }
                }
            }
            if (current == null) return null
        }
        return current
    }

    private fun tokenize(line: String): List<String> =
        Regex("\"([^\"]*)\"|(\\S+)").findAll(line)
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            .toList()

    private fun fail(line: String, detail: String) = AssertionResult(line, false, detail)
}
