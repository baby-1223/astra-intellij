package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.ResponseExample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MockSpecLoaderTest {
    @Test
    fun `loads routes from an openapi json document`() {
        val spec = MockSpecLoader.fromOpenApi(
            """
            {
              "openapi": "3.0.0",
              "info": { "title": "Pet API", "version": "1" },
              "paths": {
                "/pets/{id}": {
                  "get": {
                    "responses": {
                      "200": {
                        "content": {
                          "application/json": {
                            "example": { "id": 7, "name": "Rex" }
                          }
                        }
                      }
                    }
                  }
                },
                "/pets": {
                  "post": {
                    "responses": {
                      "201": {
                        "content": {
                          "application/json": {
                            "schema": {
                              "type": "object",
                              "properties": { "name": { "type": "string" } }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("Pet API", spec.name)
        assertEquals(2, spec.routes.size)
        val get = spec.routes.first { it.method == "GET" }
        assertEquals("/pets/{id}", get.path)
        assertTrue(get.body.contains("\"id\": 7"))
        val post = spec.routes.first { it.method == "POST" }
        assertEquals(201, post.status)
        assertTrue(post.body.contains("\"name\": \"string\""))
    }

    @Test
    fun `loads routes from an openapi yaml document`() {
        val spec = MockSpecLoader.fromOpenApi(
            """
            openapi: 3.0.0
            info:
              title: YAML API
              version: '1'
            paths:
              /health:
                get:
                  responses:
                    '200':
                      content:
                        application/json:
                          example:
                            status: up
            """.trimIndent(),
        )
        assertEquals("YAML API", spec.name)
        val route = spec.routes.single()
        assertEquals("GET", route.method)
        assertEquals("/health", route.path)
        assertTrue(route.body.contains("\"status\": \"up\""))
    }

    @Test
    fun `builds routes from saved collection requests`() {
        val collection = RequestCollection(name = "Local")
        collection.requests += AstraRequest(name = "List users", method = "GET", url = "{{baseUrl}}/users?page=1")
        collection.requests += AstraRequest(name = "Create user", method = "POST", url = "https://api.example.com/users")
            .apply {
                examples += ResponseExample(status = 201, body = "{\"ok\":true}")
            }

        val spec = MockSpecLoader.fromCollection(collection)
        assertEquals("Local", spec.name)
        assertEquals(2, spec.routes.size)
        assertEquals("/users", spec.routes[0].path)
        assertEquals("/users", spec.routes[1].path)
        assertEquals(201, spec.routes[1].status)
        assertEquals("{\"ok\":true}", spec.routes[1].body)
        assertFalse(spec.routes[0].body.isBlank())
    }

    @Test
    fun `extracts paths from absolute and variable urls`() {
        assertEquals("/orders/42", MockSpecLoader.pathOf("https://api.example.com/orders/42?x=1"))
        assertEquals("/orders", MockSpecLoader.pathOf("{{baseUrl}}/orders"))
        assertEquals("/orders", MockSpecLoader.pathOf("localhost:8080/orders"))
    }

    @Test
    fun `rejects documents without operations`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockSpecLoader.fromOpenApi("""{"openapi":"3.0.0","info":{"title":"Empty"},"paths":{}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MockSpecLoader.fromCollection(RequestCollection(name = "Empty"))
        }
    }
}
