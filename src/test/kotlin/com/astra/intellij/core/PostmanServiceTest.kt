package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AuthConfig
import com.astra.intellij.model.KeyValue
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.RequestFolder
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PostmanServiceTest {
    private val service = PostmanService()

    @Test
    fun `parses collection folders requests headers body and auth`() {
        val collection = service.parseCollection(COLLECTION)

        assertEquals("Demo", collection.name)
        assertEquals(1, collection.folders.size)
        assertEquals(1, collection.requests.size)
        assertEquals("Ping", collection.requests[0].name)
        assertEquals("GET", collection.requests[0].method)

        val folder = collection.folders[0]
        assertEquals("Auth", folder.name)
        val login = folder.requests[0]
        assertEquals("Login", login.name)
        assertEquals("POST", login.method)
        assertEquals("https://api.example.com/users/{{id}}?page=2", login.url)
        assertEquals(2, login.headers.size)
        assertFalse(login.headers[1].enabled)
        assertEquals("json", login.bodyMode)
        assertEquals("{\"a\":1}", login.body)
        assertEquals("bearer", login.auth.type)
        assertEquals("{{token}}", login.auth.token)
        assertEquals(1, login.params.size)
        assertEquals("page", login.params[0].key)
        assertEquals("2", login.params[0].value)
    }

    @Test
    fun `exports a collection that can be parsed back`() {
        val original = RequestCollection(
            name = "Round Trip",
            folders = mutableListOf(
                RequestFolder(
                    name = "Users",
                    requests = mutableListOf(
                        AstraRequest(
                            name = "Create User",
                            method = "POST",
                            url = "https://api.example.com/users",
                            params = mutableListOf(KeyValue(key = "verbose", value = "true")),
                            headers = mutableListOf(KeyValue(key = "X-Team", value = "astra")),
                            bodyMode = "json",
                            body = "{\"name\":\"Ada\"}",
                            auth = AuthConfig(type = "bearer", token = "{{token}}"),
                        ),
                    ),
                ),
            ),
        )

        val parsed = service.parseCollection(service.exportCollection(listOf(original), "Round Trip"))

        assertEquals("Round Trip", parsed.name)
        val request = parsed.folders[0].requests[0]
        assertEquals("Create User", request.name)
        assertEquals("POST", request.method)
        assertEquals("https://api.example.com/users?verbose=true", request.url)
        assertEquals("json", request.bodyMode)
        assertEquals("{\"name\":\"Ada\"}", request.body)
        assertEquals("bearer", request.auth.type)
        assertTrue(request.headers.any { it.key == "X-Team" && it.value == "astra" })
    }

    @Test
    fun `parses and exports postman environment json`() {
        val environment = service.parseEnvironment(
            """
            {
              "name": "Dev",
              "values": [
                {"key": "baseUrl", "value": "https://api.example.com", "enabled": true},
                {"key": "token", "value": "abc", "enabled": true},
                {"key": "ignored", "value": "x", "enabled": false}
              ]
            }
            """.trimIndent(),
        )

        requireNotNull(environment)
        assertEquals("Dev", environment.name)
        assertEquals("https://api.example.com", environment.variables["baseUrl"])
        assertFalse(environment.variables.containsKey("ignored"))

        val exported = JsonParser.parseString(service.exportEnvironment(environment)).asJsonObject
        assertEquals("environment", exported.get("_postman_variable_scope").asString)
        assertEquals(2, exported.getAsJsonArray("values").size())

        val roundTrip = requireNotNull(service.parseEnvironment(service.exportEnvironment(environment)))
        assertEquals(environment.variables, roundTrip.variables)
    }

    @Test
    fun `imports an environment with no variables as empty environment`() {
        val environment = service.parseEnvironment("""{"name":"Empty","values":[]}""")

        assertEquals(AstraEnvironment(name = "Empty").variables, requireNotNull(environment).variables)
        assertEquals("Empty", environment.name)
    }

    private companion object {
        private val COLLECTION = """
            {
              "info": {"name": "Demo"},
              "item": [
                {
                  "name": "Auth",
                  "item": [
                    {
                      "name": "Login",
                      "request": {
                        "method": "post",
                        "header": [
                          {"key": "X-Token", "value": "abc"},
                          {"key": "Skip", "value": "1", "disabled": true}
                        ],
                        "url": {
                          "raw": "https://api.example.com/users/:id?page=2",
                          "query": [{"key": "page", "value": "2"}]
                        },
                        "body": {
                          "mode": "raw",
                          "raw": "{\"a\":1}",
                          "options": {"raw": {"language": "json"}}
                        },
                        "auth": {
                          "type": "bearer",
                          "bearer": [{"key": "token", "value": "{{token}}"}]
                        }
                      }
                    }
                  ]
                },
                {"name": "Ping", "request": "https://api.example.com/ping"}
              ]
            }
        """.trimIndent()
    }
}
