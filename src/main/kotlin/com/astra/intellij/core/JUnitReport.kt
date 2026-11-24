package com.astra.intellij.core

import java.time.Instant
import java.util.Locale

/**
 * One executed request, expressed as a JUnit testcase.
 *
 * A transport-level [error] is reported as a JUnit `<error>` (the request never completed).
 * Assertion failures are reported as `<failure>` entries so CI systems can point at the request.
 */
data class JUnitCase(
    val name: String,
    val className: String = "Astra Runner",
    val timeMs: Long = 0L,
    val error: String? = null,
    val failures: List<String> = emptyList(),
    val assertions: List<AssertionResult> = emptyList(),
) {
    val passed: Boolean
        get() = error == null && failures.isEmpty()
}

/** Serializes runner results to JUnit XML, the format consumed by most CI test reports. */
object JUnitReport {
    fun build(
        suiteName: String,
        cases: List<JUnitCase>,
        timestamp: Instant = Instant.now(),
    ): String {
        val failures = cases.count { it.error == null && it.failures.isNotEmpty() }
        val errors = cases.count { it.error != null }
        val totalSeconds = cases.sumOf { it.timeMs } / 1000.0
        val builder = StringBuilder()
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        builder.append("<testsuites name=\"").append(escape(suiteName))
            .append("\" tests=\"").append(cases.size)
            .append("\" failures=\"").append(failures)
            .append("\" errors=\"").append(errors)
            .append("\" time=\"").append(seconds(totalSeconds))
            .append("\">\n")
        builder.append("  <testsuite name=\"").append(escape(suiteName))
            .append("\" tests=\"").append(cases.size)
            .append("\" failures=\"").append(failures)
            .append("\" errors=\"").append(errors)
            .append("\" time=\"").append(seconds(totalSeconds))
            .append("\" timestamp=\"").append(escape(timestamp.toString()))
            .append("\">\n")
        cases.forEach { case -> appendCase(builder, case) }
        builder.append("  </testsuite>\n")
        builder.append("</testsuites>\n")
        return builder.toString()
    }

    private fun appendCase(builder: StringBuilder, case: JUnitCase) {
        val hasChildren = case.error != null || case.failures.isNotEmpty() || case.assertions.isNotEmpty()
        builder.append("    <testcase name=\"").append(escape(case.name))
            .append("\" classname=\"").append(escape(case.className))
            .append("\" time=\"").append(seconds(case.timeMs / 1000.0)).append('"')
        if (!hasChildren) {
            builder.append("/>\n")
            return
        }
        builder.append(">\n")
        case.error?.let {
            builder.append("      <error message=\"").append(escape(it)).append("\">")
                .append(escape(it)).append("</error>\n")
        }
        case.failures.forEach { failure ->
            builder.append("      <failure message=\"").append(escape(failure)).append("\">")
                .append(escape(failure)).append("</failure>\n")
        }
        if (case.assertions.isNotEmpty()) {
            builder.append("      <system-out>")
                .append(escape(case.assertions.joinToString("\n") { assertion ->
                    if (assertion.passed) {
                        "PASS ${assertion.assertion}"
                    } else {
                        "FAIL ${assertion.assertion}: ${assertion.detail}"
                    }
                }))
                .append("</system-out>\n")
        }
        builder.append("    </testcase>\n")
    }

    private fun seconds(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    /** Escapes the five XML entities; also drops control characters that XML 1.0 rejects. */
    private fun escape(text: String): String {
        val builder = StringBuilder(text.length)
        text.forEach { char ->
            when {
                char == '&' -> builder.append("&amp;")
                char == '<' -> builder.append("&lt;")
                char == '>' -> builder.append("&gt;")
                char == '"' -> builder.append("&quot;")
                char == '\'' -> builder.append("&apos;")
                char == '\n' -> builder.append('\n')
                char == '\t' -> builder.append('\t')
                char == '\r' -> builder.append('\r')
                char.code < 0x20 -> Unit
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }
}
