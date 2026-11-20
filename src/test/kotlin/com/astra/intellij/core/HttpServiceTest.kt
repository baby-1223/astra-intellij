package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AuthConfig
import com.astra.intellij.model.KeyValue
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HttpServiceTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val service = HttpService()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `sends existing and enabled query parameters and resolved headers`() {
        val request = AstraRequest(
            url = "$baseUrl/query?existing=a%20b",
            params = mutableListOf(
                KeyValue(key = "q name", value = "{{value}}"),
                KeyValue(enabled = false, key = "ignored", value = "yes"),
            ),
            headers = mutableListOf(KeyValue(key = "X-Test", value = "{{header}}")),
        )

        val result = service.send(
            request,
            mapOf("value" to "hello world", "header" to "header-value"),
        ).result()

        assertEquals(200, result.status)
        assertEquals("OK", result.statusText)
        assertEquals(
            "existing=a%20b&q+name=hello+world|header-value",
            String(result.body, StandardCharsets.UTF_8),
        )
        assertTrue(result.elapsedMs >= 0)
        assertEquals(
            listOf("astra"),
            result.headers.entries.single { it.key.equals("X-Response", ignoreCase = true) }.value,
        )
    }

    @Test
    fun `sends a resolved json body with content type`() {
        val request = AstraRequest(
            method = "POST",
            url = "$baseUrl/json",
            bodyMode = "json",
            body = "{\"message\":\"{{message}}\"}",
        )

        val result = service.send(request, mapOf("message" to "你好")).result()

        assertEquals(
            "POST|application/json|{\"message\":\"你好\"}",
            String(result.body, StandardCharsets.UTF_8),
        )
    }

    @Test
    fun `sends bearer basic and api key authentication`() {
        val bearer = service.send(
            AstraRequest(
                url = "$baseUrl/auth/bearer",
                auth = AuthConfig(type = "bearer", token = "{{token}}"),
            ),
            mapOf("token" to "abc"),
        ).result()
        val basic = service.send(
            AstraRequest(
                url = "$baseUrl/auth/basic",
                auth = AuthConfig(type = "basic", username = "user", password = "p@ss"),
            ),
        ).result()
        val apiHeader = service.send(
            AstraRequest(
                url = "$baseUrl/auth/header",
                auth = AuthConfig(
                    type = "apikey",
                    apiKeyName = "X-Api-{{kind}}",
                    apiKeyValue = "{{secret}}",
                ),
            ),
            mapOf("kind" to "Key", "secret" to "s e"),
        ).result()
        val apiQuery = service.send(
            AstraRequest(
                url = "$baseUrl/auth/query",
                auth = AuthConfig(
                    type = "apikey",
                    apiKeyName = "api key",
                    apiKeyValue = "{{secret}}",
                    apiKeyIn = "query",
                ),
            ),
            mapOf("secret" to "s e"),
        ).result()

        assertEquals("Bearer abc", String(bearer.body, StandardCharsets.UTF_8))
        val expectedBasic = Base64.getEncoder().encodeToString("user:p@ss".toByteArray())
        assertEquals("Basic $expectedBasic", String(basic.body, StandardCharsets.UTF_8))
        assertEquals("s e", String(apiHeader.body, StandardCharsets.UTF_8))
        assertEquals("api+key=s+e", String(apiQuery.body, StandardCharsets.UTF_8))
    }

    @Test
    fun `collects response headers bodies and cookie attributes`() {
        val result = service.send(AstraRequest(url = "$baseUrl/cookies")).result()

        assertEquals("ok", String(result.body, StandardCharsets.UTF_8))
        assertEquals(2, result.cookies.size)
        val session = result.cookies.single { it.name == "session" }
        assertEquals("abc123", session.value)
        assertEquals("example.com", session.domain)
        assertEquals("/api", session.path)
        val theme = result.cookies.single { it.name == "theme" }
        assertEquals("dark", theme.value)
        assertEquals("/", theme.path)
    }

    @Test
    fun `buildRequestUrl trims and encodes spaces`() {
        val url = buildRequestUrl(
            AstraRequest(url = "  https://example.com/a b?x=1  "),
            emptyMap(),
        )

        assertEquals("https://example.com/a%20b?x=1", url)
    }

    @Test
    fun `sends request with spaces in url`() {
        val request = AstraRequest(url = "  $baseUrl/query?existing=a b  ")

        val result = service.send(request).result()

        assertEquals(200, result.status)
        assertEquals("existing=a%20b|null", String(result.body, StandardCharsets.UTF_8))
    }

    @Test
    fun `sends urlencoded form body`() {
        val request = AstraRequest(
            method = "POST",
            url = "$baseUrl/json",
            bodyMode = "urlencoded",
            formFields = mutableListOf(
                KeyValue(key = "user name", value = "{{name}}"),
                KeyValue(enabled = false, key = "ignored", value = "yes"),
            ),
        )

        val result = service.send(request, mapOf("name" to "a b")).result()

        assertEquals(
            "POST|application/x-www-form-urlencoded|user+name=a+b",
            String(result.body, StandardCharsets.UTF_8),
        )
    }

    @Test
    fun `sends multipart form data body`() {
        val request = AstraRequest(
            method = "POST",
            url = "$baseUrl/json",
            bodyMode = "form-data",
            formFields = mutableListOf(KeyValue(key = "field", value = "{{value}}")),
        )

        val result = service.send(request, mapOf("value" to "hello")).result()

        val parts = String(result.body, StandardCharsets.UTF_8).split("|", limit = 3)
        assertEquals("POST", parts[0])
        assertTrue(parts[1].startsWith("multipart/form-data; boundary=----Astra"))
        assertTrue(parts[2].contains("Content-Disposition: form-data; name=\"field\""))
        assertTrue(parts[2].contains("hello"))
        assertTrue(parts[2].contains("--\r\n"))
    }

    private fun HttpCall.result() = future.get(5, TimeUnit.SECONDS)

    private fun handle(exchange: HttpExchange) {
        val requestBody = exchange.requestBody.use { it.readBytes() }
        val path = exchange.requestURI.path
        val response = when (path) {
            "/query" -> "${exchange.requestURI.rawQuery}|${exchange.requestHeaders.getFirst("X-Test")}"
            "/json" -> "${exchange.requestMethod}|${exchange.requestHeaders.getFirst("Content-Type")}|" +
                String(requestBody, StandardCharsets.UTF_8)
            "/auth/bearer", "/auth/basic" -> exchange.requestHeaders.getFirst("Authorization")
            "/auth/header" -> exchange.requestHeaders.getFirst("X-Api-Key")
            "/auth/query" -> exchange.requestURI.rawQuery
            "/cookies" -> {
                exchange.responseHeaders.add(
                    "Set-Cookie",
                    "session=abc123; Domain=example.com; Path=/api; HttpOnly",
                )
                exchange.responseHeaders.add("Set-Cookie", "theme=dark; Path=/")
                "ok"
            }
            else -> "not found"
        }
        val responseBody = response.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("X-Response", "astra")
        exchange.sendResponseHeaders(if (response == "not found") 404 else 200, responseBody.size.toLong())
        exchange.responseBody.use { it.write(responseBody) }
        exchange.close()
    }
}
