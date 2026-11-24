package com.astra.intellij.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection

class MockServerTest {
    private var server: MockServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun `serves matching routes and reports unknown paths`() {
        val spec = MockSpec(
            name = "Test",
            routes = listOf(
                MockRoute(
                    method = "GET",
                    path = "/pets/{id}",
                    status = 200,
                    body = """{"id":7,"name":"Rex"}""",
                ),
                MockRoute(
                    method = "POST",
                    path = "/pets",
                    status = 201,
                    body = """{"created":true}""",
                ),
            ),
        )
        val instance = MockServer(spec)
        server = instance
        val port = instance.start(0)

        val get = request("GET", "http://127.0.0.1:$port/pets/7")
        assertEquals(200, get.first)
        assertTrue(get.second.contains("\"name\":\"Rex\""))
        assertEquals("true", get.third["x-astra-mock"])

        val post = request("POST", "http://127.0.0.1:$port/pets")
        assertEquals(201, post.first)
        assertTrue(post.second.contains("created"))

        val missing = request("GET", "http://127.0.0.1:$port/unknown")
        assertEquals(404, missing.first)
        assertTrue(missing.second.contains("/pets/{id}"))

        val options = request("OPTIONS", "http://127.0.0.1:$port/pets")
        assertEquals(204, options.first)
        assertEquals("*", options.third["access-control-allow-origin"])

        val log = instance.log()
        assertTrue(log.any { it.method == "GET" && it.path == "/pets/7" && it.matched })
        assertTrue(log.any { it.path == "/unknown" && !it.matched })
    }

    private fun request(method: String, url: String): Triple<Int, String, Map<String, String>> {
        val connection = java.net.URI.create(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val headers = connection.headerFields
            .filterKeys { it != null }
            .mapKeys { it.key.lowercase() }
            .mapValues { it.value.firstOrNull().orEmpty() }
        connection.disconnect()
        return Triple(status, body, headers)
    }
}
