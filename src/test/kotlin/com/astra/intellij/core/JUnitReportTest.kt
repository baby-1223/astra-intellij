package com.astra.intellij.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class JUnitReportTest {
    @Test
    fun `passing case is self closing`() {
        val xml = JUnitReport.build(
            "Smoke",
            listOf(JUnitCase(name = "GET /users", timeMs = 250)),
            Instant.parse("2026-01-02T03:04:05Z"),
        )
        assertTrue(xml.contains("<testcase name=\"GET /users\" classname=\"Astra Runner\" time=\"0.250\"/>"))
        assertTrue(xml.contains("tests=\"1\" failures=\"0\" errors=\"0\""))
        assertTrue(xml.contains("timestamp=\"2026-01-02T03:04:05Z\""))
    }

    @Test
    fun `assertion failures become failure nodes with system-out`() {
        val xml = JUnitReport.build(
            "Collection",
            listOf(
                JUnitCase(
                    name = "POST /login",
                    timeMs = 1234,
                    failures = listOf("status == 200"),
                    assertions = listOf(
                        AssertionResult("status == 200", false, "expected == 200, actual 401"),
                    ),
                ),
            ),
        )
        assertTrue(xml.contains("<failure message=\"status == 200\">status == 200</failure>"))
        assertTrue(xml.contains("<system-out>FAIL status == 200: expected == 200, actual 401</system-out>"))
        assertTrue(xml.contains("failures=\"1\" errors=\"0\""))
        assertTrue(xml.contains("time=\"1.234\""))
    }

    @Test
    fun `transport errors are counted as errors and escaped`() {
        val xml = JUnitReport.build(
            "Collection",
            listOf(JUnitCase(name = "GET <broken>", error = "Connection & reset")),
        )
        assertTrue(xml.contains("name=\"GET &lt;broken&gt;\""))
        assertTrue(xml.contains("<error message=\"Connection &amp; reset\">Connection &amp; reset</error>"))
        assertTrue(xml.contains("errors=\"1\""))
        assertFalse(xml.contains("Connection & reset"))
    }

    @Test
    fun `empty result still emits a valid empty suite`() {
        val xml = JUnitReport.build("Empty", emptyList())
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("tests=\"0\" failures=\"0\" errors=\"0\""))
        assertEquals(1, Regex("<testsuite ").findAll(xml).count())
    }
}
