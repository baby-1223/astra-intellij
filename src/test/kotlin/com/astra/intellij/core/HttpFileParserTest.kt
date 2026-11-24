package com.astra.intellij.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpFileParserTest {
    @Test
    fun `parses requests, variables, headers and json bodies`() {
        val collection = HttpFileParser.parse(
            """
            @baseUrl = https://api.example.com
            @token=abc123

            ### List users
            # @name listUsers
            GET {{baseUrl}}/users?page=1
            Authorization: Bearer {{token}}
            Accept: application/json

            ### Create user
            POST {{baseUrl}}/users
            Content-Type: application/json

            {
              "name": "Ada"
            }

            > {%
              client.test("created", function() {});
            %}
            """.trimIndent(),
            "demo.http",
        )

        assertEquals("demo", collection.name)
        assertEquals("https://api.example.com", collection.variables["baseUrl"])
        assertEquals("abc123", collection.variables["token"])
        assertEquals(2, collection.requests.size)

        val list = collection.requests[0]
        assertEquals("listUsers", list.name)
        assertEquals("GET", list.method)
        assertEquals("{{baseUrl}}/users?page=1", list.url)
        assertEquals("Bearer {{token}}", list.headers.first { it.key == "Authorization" }.value)
        assertEquals("none", list.bodyMode)

        val create = collection.requests[1]
        assertEquals("Create user", create.name)
        assertEquals("POST", create.method)
        assertEquals("json", create.bodyMode)
        assertTrue(create.body.contains("\"name\": \"Ada\""))
        // Response handler scripts are not part of the request body.
        assertFalse(create.body.contains("client.test"))
    }

    @Test
    fun `bare url becomes a get and urlencoded bodies become form fields`() {
        val collection = HttpFileParser.parse(
            """
            ### Search
            https://api.example.com/search?q=astra

            ### Login
            POST https://api.example.com/login
            Content-Type: application/x-www-form-urlencoded

            user=ada&password=a%20b
            """.trimIndent(),
            "misc.rest",
        )

        assertEquals(2, collection.requests.size)
        assertEquals("GET", collection.requests[0].method)
        assertEquals("https://api.example.com/search?q=astra", collection.requests[0].url)

        val login = collection.requests[1]
        assertEquals("urlencoded", login.bodyMode)
        assertEquals(listOf("user" to "ada", "password" to "a b"), login.formFields.map { it.key to it.value })
    }

    @Test
    fun `falls back to a readable name when no separator title exists`() {
        val collection = HttpFileParser.parse(
            """
            DELETE https://api.example.com/orders/42
            """.trimIndent(),
            "orders.http",
        )
        assertEquals("DELETE /orders/42", collection.requests.single().name)
    }

    @Test
    fun `recognizes http and rest extensions`() {
        assertTrue(HttpFileParser.supports("api.http"))
        assertTrue(HttpFileParser.supports("api.REST"))
        assertFalse(HttpFileParser.supports("api.json"))
    }
}
