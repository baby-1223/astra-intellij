package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AuthConfig
import com.astra.intellij.model.KeyValue
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.RequestFolder
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenApiServiceTest {
    private val service = OpenApiService()

    @Test
    fun `parses openapi operations into folders with base url environment`() {
        val imported = service.parse(SPEC)

        assertEquals("Pet API", imported.collection.name)
        assertEquals("https://api.example.com/v1", imported.environment?.variables?.get("baseUrl"))
        assertEquals(1, imported.collection.folders.size)

        val pets = imported.collection.folders[0]
        assertEquals("pets", pets.name)
        assertEquals(2, pets.requests.size)

        val get = pets.requests.single { it.method == "GET" }
        assertEquals("Get pet", get.name)
        assertEquals("{{baseUrl}}/pets/{{petId}}", get.url)
        assertTrue(get.params.any { it.key == "verbose" })
        assertEquals("bearer", get.auth.type)

        val post = pets.requests.single { it.method == "POST" }
        assertEquals("Update pet", post.name)
        assertEquals("json", post.bodyMode)
        assertTrue(post.body.contains("\"Rex\""))
    }

    @Test
    fun `parses swagger 2 servers`() {
        val imported = service.parse(
            """
            {
              "swagger": "2.0",
              "info": {"title": "Legacy", "version": "1.0"},
              "host": "legacy.example.com",
              "basePath": "/api",
              "schemes": ["http"],
              "paths": {"/ping": {"get": {"summary": "Ping"}}}
            }
            """.trimIndent(),
        )

        assertEquals("http://legacy.example.com/api", imported.environment?.variables?.get("baseUrl"))
        assertEquals("{{baseUrl}}/ping", imported.collection.requests.single().url)
    }

    @Test
    fun `parses openapi yaml documents`() {
        val imported = service.parse(
            """
            openapi: 3.0.0
            info:
              title: YAML Pets
              version: '1'
            servers:
              - url: https://yaml.example.com
            paths:
              /pets:
                get:
                  summary: List pets
                  responses:
                    '200':
                      description: ok
            """.trimIndent(),
        )

        assertEquals("YAML Pets", imported.collection.name)
        assertEquals("https://yaml.example.com", imported.environment?.variables?.get("baseUrl"))
        val request = imported.collection.requests.single()
        assertEquals("List pets", request.name)
        assertEquals("{{baseUrl}}/pets", request.url)
    }

    @Test
    fun `exports openapi json with paths parameters and security`() {
        val collection = RequestCollection(
            name = "Users",
            folders = mutableListOf(
                RequestFolder(
                    name = "users",
                    requests = mutableListOf(
                        AstraRequest(
                            name = "Get user",
                            method = "GET",
                            url = "https://api.example.com/users/{{id}}?verbose=true",
                            params = mutableListOf(KeyValue(key = "verbose", value = "true")),
                            headers = mutableListOf(KeyValue(key = "X-Team", value = "astra")),
                            auth = AuthConfig(type = "bearer"),
                        ),
                    ),
                ),
            ),
        )

        val root = JsonParser.parseString(service.export(listOf(collection), "Users")).asJsonObject

        assertEquals("3.0.3", root.get("openapi").asString)
        assertEquals("Users", root.getAsJsonObject("info").get("title").asString)
        assertEquals(
            "https://api.example.com",
            root.getAsJsonArray("servers").first().asJsonObject.get("url").asString,
        )

        val operation = root.getAsJsonObject("paths")
            .getAsJsonObject("/users/{id}")
            .getAsJsonObject("get")
        assertEquals("Get user", operation.get("summary").asString)
        assertNotNull(operation.getAsJsonArray("parameters"))
        assertTrue(operation.getAsJsonArray("parameters").any {
            it.asJsonObject.get("name").asString == "verbose" &&
                it.asJsonObject.get("in").asString == "query"
        })
        assertEquals(
            "bearerAuth",
            operation.getAsJsonArray("security")
                .first()
                .asJsonObject
                .keySet()
                .first(),
        )
        assertNotNull(root.getAsJsonObject("components").getAsJsonObject("securitySchemes"))
    }

    @Test
    fun `exports collection that can be parsed back`() {
        val original = RequestCollection(
            name = "Round Trip",
            folders = mutableListOf(
                RequestFolder(
                    name = "pets",
                    requests = mutableListOf(
                        AstraRequest(
                            name = "List pets",
                            method = "GET",
                            url = "https://api.example.com/pets",
                        ),
                    ),
                ),
            ),
        )

        val parsed = service.parse(service.export(listOf(original), "Round Trip"))

        val request = parsed.collection.folders.single().requests.single()
        assertEquals("List pets", request.name)
        assertEquals("/pets", request.url.removePrefix("{{baseUrl}}"))
        assertEquals("https://api.example.com", parsed.environment?.variables?.get("baseUrl"))
    }

    private companion object {
        private val SPEC = """
            {
              "openapi": "3.0.3",
              "info": {"title": "Pet API", "version": "1.0"},
              "servers": [
                {"url": "https://{host}/v1", "variables": {"host": {"default": "api.example.com"}}}
              ],
              "components": {
                "securitySchemes": {"bearerAuth": {"type": "http", "scheme": "bearer"}}
              },
              "paths": {
                "/pets/{petId}": {
                  "get": {
                    "summary": "Get pet",
                    "tags": ["pets"],
                    "parameters": [
                      {"name": "petId", "in": "path", "required": true, "schema": {"type": "string"}},
                      {"name": "verbose", "in": "query", "schema": {"type": "boolean", "default": false}}
                    ],
                    "security": [{"bearerAuth": []}]
                  },
                  "post": {
                    "summary": "Update pet",
                    "tags": ["pets"],
                    "requestBody": {
                      "content": {
                        "application/json": {
                          "schema": {
                            "type": "object",
                            "properties": {"name": {"type": "string"}}
                          },
                          "example": {"name": "Rex"}
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
