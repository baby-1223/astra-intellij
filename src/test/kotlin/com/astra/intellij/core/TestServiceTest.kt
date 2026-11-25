package com.astra.intellij.core

import com.astra.intellij.model.HttpResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestServiceTest {
    private fun result(
        status: Int = 200,
        body: String = """{"code":0,"data":{"id":42,"name":"astra"},"tags":["a","b"]}""",
        elapsedMs: Long = 120,
        headers: Map<String, List<String>> = mapOf("Content-Type" to listOf("application/json")),
    ) = HttpResult(
        status = status,
        statusText = "OK",
        headers = headers,
        body = body.toByteArray(),
        elapsedMs = elapsedMs,
        cookies = mutableListOf(),
    )

    @Test
    fun `status assertions`() {
        assertTrue(TestService.evaluate(listOf("status 200"), result()).single().passed)
        assertTrue(TestService.evaluate(listOf("status == 200"), result()).single().passed)
        assertFalse(TestService.evaluate(listOf("status 201"), result()).single().passed)
        assertTrue(TestService.evaluate(listOf("status >= 200"), result()).single().passed)
        assertTrue(TestService.evaluate(listOf("status 404"), result(status = 404)).single().passed)
    }

    @Test
    fun `time assertions`() {
        assertTrue(TestService.evaluate(listOf("time < 500"), result()).single().passed)
        assertFalse(TestService.evaluate(listOf("time < 10"), result()).single().passed)
        assertTrue(TestService.evaluate(listOf("time >= 120"), result()).single().passed)
    }

    @Test
    fun `body assertions`() {
        assertTrue(TestService.evaluate(listOf("body contains astra"), result()).single().passed)
        assertFalse(TestService.evaluate(listOf("body contains missing"), result()).single().passed)
        assertTrue(TestService.evaluate(listOf("body matches \"id.:\\d+\""), result()).single().passed)
        val spaced = result(body = """{"message":"hello world"}""")
        assertTrue(TestService.evaluate(listOf("body contains \"hello world\""), spaced).single().passed)
    }

    @Test
    fun `json path assertions`() {
        assertTrue(TestService.evaluate(listOf("json $.code == 0"), result()).single().passed)
        assertTrue(TestService.evaluate(listOf("json $.data.id == 42"), result()).single().passed)
        assertTrue(TestService.evaluate(listOf("json $.data.name == astra"), result()).single().passed)
        assertTrue(TestService.evaluate(listOf("json $.tags[1] == b"), result()).single().passed)
        assertTrue(TestService.evaluate(listOf("json $.data.id > 10"), result()).single().passed)
        assertTrue(TestService.evaluate(listOf("json $.data exists"), result()).single().passed)
        assertFalse(TestService.evaluate(listOf("json $.missing exists"), result()).single().passed)
        assertFalse(TestService.evaluate(listOf("json $.code == 1"), result()).single().passed)
    }

    @Test
    fun `jsonpath assertions support the extended path syntax`() {
        val body = """{"data":{"items":[{"id":1,"name":"a"},{"id":2,"name":"b"}]},"meta":{"token":"t-1"}}"""
        assertTrue(TestService.evaluate(listOf("jsonpath $.meta.token == t-1"), result(body = body)).single().passed)
        assertTrue(TestService.evaluate(listOf("jsonpath \$['meta']['token'] exists"), result(body = body)).single().passed)
        assertTrue(TestService.evaluate(listOf("jsonpath $.data.items[0].id == 1"), result(body = body)).single().passed)
        assertTrue(TestService.evaluate(listOf("jsonpath $.data.items[-1].id == 2"), result(body = body)).single().passed)
        assertTrue(TestService.evaluate(listOf("jsonpath $.data.items[*].id > 1"), result(body = body)).single().passed)
        assertTrue(TestService.evaluate(listOf("jsonpath \$..token == t-1"), result(body = body)).single().passed)
        assertFalse(TestService.evaluate(listOf("jsonpath $.data.items[*].id > 5"), result(body = body)).single().passed)
    }

    @Test
    fun `extract lines capture json values and are skipped by assertions`() {
        val body = """{"data":{"token":"abc","items":[{"id":7}]}}"""
        val lines = listOf("extract token = $.data.token", "extract id = $.data.items[0].id", "status 200")

        val extracted = TestService.extract(lines, result(body = body))
        assertEquals(listOf("token" to "abc", "id" to "7"), extracted)
        assertEquals(1, TestService.evaluate(lines, result(body = body)).size)
    }

    @Test
    fun `header assertions`() {
        assertTrue(TestService.evaluate(listOf("header Content-Type exists"), result()).single().passed)
        assertTrue(TestService.evaluate(listOf("header content-type contains json"), result()).single().passed)
        assertFalse(TestService.evaluate(listOf("header X-Missing exists"), result()).single().passed)
    }

    @Test
    fun `invalid assertions fail gracefully`() {
        val results = TestService.evaluate(listOf("json $.code == 0", "unknown thing"), result(body = "not json"))
        assertFalse(results[0].passed)
        assertFalse(results[1].passed)
        assertEquals(2, results.size)
    }
}
