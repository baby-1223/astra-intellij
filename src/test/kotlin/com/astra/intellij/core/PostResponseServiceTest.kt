package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.HttpResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class PostResponseServiceTest {
    @Test
    fun `stores json header regex status and literal values`() {
        val environment = AstraEnvironment(name = "dev")
        val result = HttpResult(
            status = 201,
            headers = mapOf(
                "X-Trace" to listOf("abc-123"),
                "Set-Cookie" to listOf("sid=xyz; Path=/"),
            ),
            body = """{"data":{"token":"t-42","user":{"id":7}},"raw":"sid=xyz; Path=/"}"""
                .toByteArray(StandardCharsets.UTF_8),
        )

        val updates = PostResponseService.execute(
            listOf(
                "set token json $.data.token",
                "set userId json $.data.user.id",
                "set trace header X-Trace",
                "set sid body \"sid=([^;]+)\"",
                "set code status",
                "set home value https://{{host}}/v1",
                "# ignored",
            ),
            result,
            mutableMapOf("host" to "api.test"),
            environment,
        )

        assertEquals("t-42", environment.variables["token"])
        assertEquals("7", environment.variables["userId"])
        assertEquals("abc-123", environment.variables["trace"])
        assertEquals("xyz", environment.variables["sid"])
        assertEquals("201", environment.variables["code"])
        assertEquals("https://api.test/v1", environment.variables["home"])
        assertEquals(6, updates.size)
    }

    @Test
    fun `unset removes variables and missing values are skipped`() {
        val environment = AstraEnvironment(name = "dev").apply { variables["old"] = "1" }
        val result = HttpResult(body = "{\"a\":1}".toByteArray())

        PostResponseService.execute(
            listOf("unset old", "set missing json $.nope", "set nothing header Nope"),
            result,
            mutableMapOf(),
            environment,
        )

        assertFalse(environment.variables.containsKey("old"))
        assertFalse(environment.variables.containsKey("missing"))
        assertFalse(environment.variables.containsKey("nothing"))
    }

    @Test
    fun `pm style scripts run with pm jsonPath and report changed variables`() {
        val environment = AstraEnvironment(name = "dev")
        val result = HttpResult(
            status = 200,
            body = """{"data":{"token":"t-7"}}""".toByteArray(StandardCharsets.UTF_8),
        )

        val updates = PostResponseService.execute(
            listOf("pm.environment.set('token', pm.jsonPath('${'$'}.data.token'));"),
            result,
            mutableMapOf(),
            environment,
        )

        assertEquals("t-7", environment.variables["token"])
        assertEquals(listOf(PostResponseService.Update("token", "t-7")), updates)
    }
}
