package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.HttpResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScriptServiceTest {
    @Test
    fun `pm sendRequest runs the callback synchronously and stores response values`() {
        val environment = AstraEnvironment(name = "dev")
        val request = AstraRequest(name = "Main", method = "GET", url = "https://api.test/me")
        val sent = mutableListOf<AstraRequest>()

        val vars = PreRequestService.execute(
            listOf(
                "pm.sendRequest({",
                "  url: pm.variables.replaceIn('{{baseUrl}}/auth/login'),",
                "  method: 'POST',",
                "  header: {'Content-Type': 'application/json'},",
                "  body: {mode: 'raw', raw: JSON.stringify({user: 'astra'})}",
                "}, function (err, res) {",
                "  if (err) { pm.environment.set('error', err.message); return; }",
                "  pm.environment.set('token', res.json().data.token);",
                "  pm.variables.set('code', String(res.code));",
                "  pm.variables.set('session', res.headers.get('X-Session'));",
                "});",
            ),
            mapOf("baseUrl" to "https://api.test"),
            environment,
            request,
        ) { call, _ ->
            sent.add(call)
            HttpResult(
                status = 200,
                headers = mapOf("X-Session" to listOf("s-1")),
                body = """{"data":{"token":"t-9"}}""".toByteArray(),
            )
        }

        assertEquals(1, sent.size)
        assertEquals("POST", sent.single().method)
        assertEquals("https://api.test/auth/login", sent.single().url)
        assertEquals("Content-Type", sent.single().headers.single().key)
        assertEquals("""{"user":"astra"}""", sent.single().body)
        assertEquals("json", sent.single().bodyMode)
        assertEquals("t-9", vars["token"])
        assertEquals("t-9", environment.variables["token"])
        assertEquals("200", vars["code"])
        assertEquals("s-1", vars["session"])
    }

    @Test
    fun `pm request changes are applied to the outgoing request`() {
        val request = AstraRequest(name = "Main", method = "GET", url = "{{host}}/old")

        val vars = PreRequestService.execute(
            listOf(
                "pm.request.method = 'post';",
                "pm.request.url = pm.variables.replaceIn('{{host}}/new');",
                "pm.request.headers.add({key: 'X-Token', value: pm.environment.get('token')});",
            ),
            mapOf("host" to "https://api.test"),
            AstraEnvironment(name = "dev", variables = mutableMapOf("token" to "abc")),
            request,
        )

        assertEquals("POST", request.method)
        assertEquals("https://api.test/new", request.url)
        assertTrue(request.headers.any { it.key == "X-Token" && it.value == "abc" })
        assertEquals("https://api.test", vars["host"])
    }

    @Test
    fun `post-response scripts expose pm jsonPath over the response body`() {
        val environment = AstraEnvironment(name = "dev")
        val result = HttpResult(
            status = 200,
            statusText = "OK",
            body = """{"data":{"token":"t-1"},"items":[{"id":1},{"id":2}]}""".toByteArray(),
            elapsedMs = 12,
        )

        val vars = ScriptService.runPostResponse(
            """
            pm.test('token exists', function () {
              if (!pm.jsonPath('${'$'}.data.token')) throw new Error('missing token');
            });
            pm.environment.set('token', pm.jsonPath('${'$'}.data.token'));
            var ids = pm.jsonPath('${'$'}.items[*].id');
            pm.environment.set('first', String(ids[0]));
            pm.environment.set('count', String(ids.length));
            """.trimIndent(),
            result,
            emptyMap(),
            environment,
        )

        assertEquals("t-1", vars["token"])
        assertEquals("t-1", environment.variables["token"])
        assertEquals("1", environment.variables["first"])
        assertEquals("2", environment.variables["count"])
    }

    @Test
    fun `post-response scripts fail when pm test throws`() {
        val result = HttpResult(status = 500, body = "{}".toByteArray())
        val error = assertThrows(IllegalStateException::class.java) {
            ScriptService.runPostResponse(
                "pm.test('status', function () { if (pm.response.code !== 200) throw new Error('bad status'); });",
                result,
                emptyMap(),
                AstraEnvironment(name = "dev"),
            )
        }
        assertTrue(error.message.orEmpty().contains("bad status"))
    }

    @Test
    fun `es6 syntax such as const arrow functions and template literals is supported`() {
        val vars = PreRequestService.execute(
            listOf(
                "const base = pm.variables.replaceIn('{{host}}/v1');",
                "const join = (a, b) => `\${a}/\${b}`;",
                "pm.variables.set('endpoint', join(base, 'users'));",
            ),
            mapOf("host" to "https://api.test"),
            null,
        )

        assertEquals("https://api.test/v1/users", vars["endpoint"])
    }

    @Test
    fun `scripts without pm api keep using the simple declarative lines`() {
        val vars = PreRequestService.execute(
            listOf("set host api.test", "set base https://{{host}}"),
            emptyMap(),
            null,
        )

        assertEquals("https://api.test", vars["base"])
    }

    @Test
    fun `script errors report the failing line`() {
        val error = assertThrows(IllegalStateException::class.java) {
            PreRequestService.execute(
                listOf("pm.environment.set('a', 'b');", "throw new Error('boom');"),
                emptyMap(),
                AstraEnvironment(name = "dev"),
            )
        }

        assertTrue(error.message!!.contains("line 2"), error.message)
        assertTrue(error.message!!.contains("boom"), error.message)
    }
}
