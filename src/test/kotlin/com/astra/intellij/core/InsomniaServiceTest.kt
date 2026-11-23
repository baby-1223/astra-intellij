package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AuthConfig
import com.astra.intellij.model.KeyValue
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.RequestFolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class InsomniaServiceTest {
    private val service = InsomniaService()

    @Test
    fun `exports and imports a single request with its folder chain`() {
        val request = AstraRequest(
            name = "Login",
            method = "POST",
            url = "{{baseUrl}}/login",
            params = mutableListOf(KeyValue(key = "lang", value = "en")),
            headers = mutableListOf(KeyValue(key = "X-Team", value = "astra")),
            bodyMode = "json",
            body = "{\"user\":\"{{user}}\"}",
            auth = AuthConfig(type = "bearer", token = "{{token}}"),
        )

        val json = service.exportRequest(
            request,
            listOf("Auth", "Session"),
            "Demo",
            AstraEnvironment(name = "Dev", variables = mutableMapOf("baseUrl" to "https://api.test")),
        )
        val parsed = service.parse(json)

        val collection = parsed.collections.single()
        assertEquals("Demo", collection.name)
        val auth = collection.folders.single()
        assertEquals("Auth", auth.name)
        val session = auth.folders.single()
        assertEquals("Session", session.name)
        val imported = session.requests.single()
        assertEquals("Login", imported.name)
        assertEquals("POST", imported.method)
        assertEquals("{{baseUrl}}/login", imported.url)
        assertEquals("en", imported.params.single().value)
        assertEquals("astra", imported.headers.single().value)
        assertEquals("json", imported.bodyMode)
        assertEquals("{\"user\":\"{{user}}\"}", imported.body)
        assertEquals("bearer", imported.auth.type)
        assertEquals("{{token}}", imported.auth.token)

        val environment = parsed.environments.single()
        assertEquals("Dev", environment.name)
        assertEquals("https://api.test", environment.variables["baseUrl"])
    }

    @Test
    fun `exports all collections with folders and environments`() {
        val collection = RequestCollection(
            name = "Demo",
            folders = mutableListOf(
                RequestFolder(
                    name = "Users",
                    requests = mutableListOf(AstraRequest(name = "List Users", url = "https://api.test/users")),
                ),
            ),
            requests = mutableListOf(AstraRequest(name = "Ping", url = "https://api.test/ping")),
        )

        val json = service.exportAll(
            listOf(collection),
            listOf(AstraEnvironment(name = "Dev", variables = mutableMapOf("baseUrl" to "https://api.test"))),
            "Astra",
        )
        val parsed = service.parse(json)

        val workspace = parsed.collections.single()
        assertEquals("Astra", workspace.name)
        val demo = workspace.folders.single()
        assertEquals("Demo", demo.name)
        assertEquals("Users", demo.folders.single().name)
        assertEquals("List Users", demo.folders.single().requests.single().name)
        assertEquals("Ping", demo.requests.single().name)
        assertEquals("https://api.test", parsed.environments.single().variables["baseUrl"])
    }

    @Test
    fun `parses a single request resource without a workspace`() {
        val json = """
            {
              "_type": "request",
              "_id": "req_1",
              "name": "Ping",
              "method": "GET",
              "url": "https://api.test/{{ _.version }}/ping"
            }
        """.trimIndent()

        val parsed = service.parse(json)

        val collection = parsed.collections.single()
        val request = collection.requests.single()
        assertEquals("Ping", request.name)
        assertEquals("https://api.test/{{version}}/ping", request.url)
    }

    @Test
    fun `round trips urlencoded bodies and api key auth`() {
        val request = AstraRequest(
            name = "Form",
            method = "POST",
            url = "https://api.test/form",
            bodyMode = "urlencoded",
            formFields = mutableListOf(
                KeyValue(key = "a", value = "1"),
                KeyValue(enabled = false, key = "b", value = "2"),
            ),
            auth = AuthConfig(
                type = "apikey",
                apiKeyName = "X-Key",
                apiKeyValue = "{{key}}",
                apiKeyIn = "query",
            ),
        )

        val parsed = service.parse(service.exportRequest(request, emptyList(), "Demo", null))
        val imported = parsed.collections.single().requests.single()

        assertEquals("urlencoded", imported.bodyMode)
        assertEquals(2, imported.formFields.size)
        assertFalse(imported.formFields[1].enabled)
        assertEquals("apikey", imported.auth.type)
        assertEquals("query", imported.auth.apiKeyIn)
        assertEquals("{{key}}", imported.auth.apiKeyValue)
    }
}
