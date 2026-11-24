package com.astra.intellij.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

data class MockRequestLog(
    val method: String,
    val path: String,
    val status: Int,
    val matched: Boolean,
    val elapsedMs: Long,
    val at: Long = System.currentTimeMillis(),
)

/**
 * Local HTTP server that answers with the bodies described by a [MockSpec].
 *
 * Uses the JDK's built-in HTTP server, so it works the same on macOS and Windows without extra
 * dependencies. Requests never leave the machine; the server binds to the loopback interface.
 */
class MockServer(private val spec: MockSpec) {
    private var server: HttpServer? = null
    private var executor: java.util.concurrent.ExecutorService? = null
    private val entries = CopyOnWriteArrayList<MockRequestLog>()

    var port: Int = 0
        private set

    val isRunning: Boolean
        get() = server != null

    val baseUrl: String
        get() = "http://127.0.0.1:$port"

    val routes: List<MockRoute>
        get() = spec.routes

    /** Starts listening on the loopback interface and returns the bound port. */
    fun start(port: Int = 8080, defaultDelayMs: Int = 0): Int {
        if (server != null) return this.port
        val instance = try {
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        } catch (error: java.io.IOException) {
            throw IllegalArgumentException("Cannot start the mock server on port $port: ${error.message}", error)
        }
        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "Astra-Mock-Server").apply { isDaemon = true }
        }
        instance.executor = pool
        instance.createContext("/") { exchange -> handle(exchange, defaultDelayMs) }
        instance.start()
        server = instance
        executor = pool
        this.port = instance.address.port
        return this.port
    }

    fun stop() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    fun log(): List<MockRequestLog> = entries.toList()

    fun clearLog() = entries.clear()

    private fun handle(exchange: HttpExchange, defaultDelayMs: Int) {
        val startedAt = System.nanoTime()
        val method = exchange.requestMethod.uppercase(Locale.ROOT)
        val path = normalize(exchange.requestURI.path.orEmpty())
        try {
            if (method == "OPTIONS") {
                write(exchange, 204, emptyMap(), "", "application/json")
                remember(method, path, 204, true, startedAt)
                return
            }
            val route = spec.routes.firstOrNull {
                it.method.equals(method, ignoreCase = true) && matches(it.path, path)
            }
            if (route == null) {
                val payload = notFound(method, path)
                write(exchange, 404, emptyMap(), payload, "application/json")
                remember(method, path, 404, false, startedAt)
                return
            }
            val delay = (route.delayMs + defaultDelayMs).coerceAtLeast(0)
            if (delay > 0) Thread.sleep(delay.toLong())
            val headers = LinkedHashMap(route.headers)
            write(exchange, route.status, headers, route.body, route.contentType)
            remember(method, path, route.status, true, startedAt)
        } catch (error: Exception) {
            runCatching {
                write(exchange, 500, emptyMap(), "{\"error\":\"Mock server failure\"}", "application/json")
            }
            remember(method, path, 500, false, startedAt)
        } finally {
            exchange.close()
        }
    }

    private fun write(
        exchange: HttpExchange,
        status: Int,
        headers: Map<String, String>,
        body: String,
        contentType: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val responseHeaders = exchange.responseHeaders
        responseHeaders.set("Content-Type", contentType)
        responseHeaders.set("Access-Control-Allow-Origin", "*")
        responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS")
        responseHeaders.set("Access-Control-Allow-Headers", "*")
        responseHeaders.set("X-Astra-Mock", "true")
        headers.forEach { (key, value) ->
            if (key.isNotBlank() && !key.equals("Content-Length", ignoreCase = true)) {
                responseHeaders.set(key, value)
            }
        }
        if (status == 204 || status == 304) {
            exchange.sendResponseHeaders(status, -1)
            return
        }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        if (body.isNotEmpty()) {
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun notFound(method: String, path: String): String {
        val json = JsonObject()
        json.addProperty("error", "No mock route for $method $path")
        json.addProperty("mock", spec.name)
        val available = JsonArray()
        spec.routes.forEach { route ->
            available.add(
                JsonObject().apply {
                    addProperty("method", route.method)
                    addProperty("path", route.path)
                },
            )
        }
        json.add("routes", available)
        return MockDataGenerator.pretty(json)
    }

    private fun remember(method: String, path: String, status: Int, matched: Boolean, startedAt: Long) {
        entries.add(
            0,
            MockRequestLog(
                method = method,
                path = path,
                status = status,
                matched = matched,
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
            ),
        )
        while (entries.size > 200) entries.removeAt(entries.lastIndex)
    }

    private fun matches(routePath: String, requestPath: String): Boolean {
        val route = normalize(routePath)
        val target = normalize(requestPath)
        if (route == target) return true
        if (!route.contains('{')) return false
        val pattern = route.split('/').joinToString("/") { segment ->
            if (segment.startsWith("{") && segment.endsWith("}")) {
                "[^/]+"
            } else {
                Regex.escape(segment)
            }
        }
        return Regex("^$pattern$").matches(target)
    }

    private fun normalize(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "/") return "/"
        val withSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return withSlash.trimEnd('/').ifEmpty { "/" }
    }
}
