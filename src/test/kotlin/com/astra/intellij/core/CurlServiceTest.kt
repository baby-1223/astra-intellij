package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AuthConfig
import com.astra.intellij.model.KeyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CurlServiceTest {
    private val service = CurlService()

    @Test
    fun `parses options quotes continuations and basic authentication`() {
        val command = """
            curl -X POST --url 'https://example.com/items' \
              -H 'Content-Type: application/json' \
              -H "X-Title: it's \"fine\"" \
              --data-raw '{"name":"Astra"}' \
              -u 'user:p:a:ss' --compressed -k
        """.trimIndent()

        val request = service.parse(command)

        assertEquals("POST", request.method)
        assertEquals("https://example.com/items", request.url)
        assertEquals("json", request.bodyMode)
        assertEquals("{\"name\":\"Astra\"}", request.body)
        assertEquals("application/json", request.headers.single { it.key == "Content-Type" }.value)
        assertEquals("it's \"fine\"", request.headers.single { it.key == "X-Title" }.value)
        assertEquals("basic", request.auth.type)
        assertEquals("user", request.auth.username)
        assertEquals("p:a:ss", request.auth.password)
    }

    @Test
    fun `exports escaped shell arguments and parses them back`() {
        val original = AstraRequest(
            method = "POST",
            url = "https://example.com/{{path}}",
            params = mutableListOf(
                KeyValue(key = "q", value = "{{query}}"),
                KeyValue(enabled = false, key = "ignored", value = "yes"),
            ),
            headers = mutableListOf(KeyValue(key = "X-Text", value = "{{text}}")),
            bodyMode = "json",
            body = """{"name":"{{name}}","quote":"it's fine","slash":"a\\b"}""",
            auth = AuthConfig(type = "basic", username = "{{user}}", password = "p'ass"),
        )
        val vars = mapOf(
            "path" to "items",
            "query" to "a b",
            "text" to "it's \"ok\"",
            "name" to "Astra",
            "user" to "me",
        )

        val exported = service.export(original, vars)
        val parsed = service.parse(exported)

        assertTrue(exported.contains("'\\''"))
        assertEquals("POST", parsed.method)
        assertEquals("https://example.com/items?q=a+b", parsed.url)
        assertFalse(parsed.url.contains("ignored"))
        assertEquals("it's \"ok\"", parsed.headers.single { it.key == "X-Text" }.value)
        assertEquals("application/json", parsed.headers.single { it.key == "Content-Type" }.value)
        assertEquals(original.body.replace("{{name}}", "Astra"), parsed.body)
        assertEquals("me", parsed.auth.username)
        assertEquals("p'ass", parsed.auth.password)
    }

    @Test
    fun `exports bearer and query api key authentication`() {
        val bearer = service.export(
            AstraRequest(auth = AuthConfig(type = "bearer", token = "{{token}}")),
            mapOf("token" to "secret"),
        )
        val apiKey = service.export(
            AstraRequest(
                url = "https://example.com",
                auth = AuthConfig(
                    type = "apikey",
                    apiKeyName = "api key",
                    apiKeyValue = "a b",
                    apiKeyIn = "query",
                ),
            ),
        )

        assertTrue(bearer.contains("Authorization: Bearer secret"))
        assertTrue(apiKey.contains("https://example.com?api+key=a+b"))
    }
}
