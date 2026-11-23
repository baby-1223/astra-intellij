package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.HttpResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreRequestServiceTest {
    @Test
    fun `set stores resolved value in environment and run variables`() {
        val environment = AstraEnvironment(name = "dev")
        val vars = PreRequestService.execute(
            listOf("set token {{\$guid}}", "set host api.test", "set full https://{{host}}/v1"),
            emptyMap(),
            environment,
        )

        assertTrue(environment.variables["token"]!!.matches(Regex("[0-9a-f-]{36}")))
        assertEquals(vars["token"], environment.variables["token"])
        assertEquals("api.test", environment.variables["host"])
        assertEquals("https://api.test/v1", vars["full"])
    }

    @Test
    fun `unset removes variable and comments are ignored`() {
        val environment = AstraEnvironment(name = "dev").apply {
            variables["debug"] = "true"
            variables["keep"] = "yes"
        }
        val vars = PreRequestService.execute(
            listOf("# unset keep", "", "unset debug"),
            mapOf("keep" to "yes"),
            environment,
        )

        assertFalse(vars.containsKey("debug"))
        assertFalse(environment.variables.containsKey("debug"))
        assertEquals("yes", vars["keep"])
        assertEquals("yes", environment.variables["keep"])
    }

    @Test
    fun `base variables are not mutated`() {
        val base = mapOf("a" to "1")
        val vars = PreRequestService.execute(listOf("set b 2"), base, null)
        assertEquals(mapOf("a" to "1"), base)
        assertEquals(mapOf("a" to "1", "b" to "2"), vars)
    }

    @Test
    fun `pre-request block sends a sub request and captures its response`() {
        val environment = AstraEnvironment(name = "dev")
        val sent = mutableListOf<com.astra.intellij.model.AstraRequest>()

        val vars = PreRequestService.execute(
            listOf(
                "request POST https://{{host}}/auth/login",
                "  header Content-Type: application/json",
                "  body {\"user\":\"{{user}}\"}",
                "  set token json $.data.token",
                "  set session header X-Session-Id",
                "set host api.test",
            ),
            mapOf("host" to "api.test", "user" to "astra"),
            environment,
        ) { request, _ ->
            sent.add(request)
            HttpResult(
                status = 200,
                headers = mapOf("X-Session-Id" to listOf("s-1")),
                body = """{"data":{"token":"t-9"}}""".toByteArray(),
            )
        }

        assertEquals(1, sent.size)
        assertEquals("POST", sent.single().method)
        assertEquals("https://{{host}}/auth/login", sent.single().url)
        assertEquals("Content-Type", sent.single().headers.single().key)
        assertEquals("json", sent.single().bodyMode)
        assertEquals("t-9", vars["token"])
        assertEquals("t-9", environment.variables["token"])
        assertEquals("s-1", vars["session"])
        assertEquals("s-1", environment.variables["session"])
        assertEquals("api.test", vars["host"])
    }

    @Test
    fun `variables set before a block are visible to its request and captures`() {
        val environment = AstraEnvironment(name = "dev")
        val vars = PreRequestService.execute(
            listOf(
                "set base https://api.test",
                "request GET {{base}}/token",
                "  set token json $.token",
            ),
            emptyMap(),
            environment,
        ) { request, resolved ->
            assertEquals("{{base}}/token", request.url)
            assertEquals("https://api.test", resolved["base"])
            HttpResult(body = """{"token":"abc"}""".toByteArray())
        }

        assertEquals("abc", vars["token"])
    }
}
