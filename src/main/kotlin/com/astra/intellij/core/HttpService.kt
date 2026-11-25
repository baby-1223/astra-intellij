package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AstraSettings
import com.astra.intellij.model.CookieData
import com.astra.intellij.model.FormField
import com.astra.intellij.model.HttpResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** A server-sent event delivered while a stream is open. */
data class SseEvent(
    val event: String = "message",
    val data: String = "",
    val id: String? = null,
    val timeMs: Long = System.currentTimeMillis(),
)

/** Handle to a running event stream; [close] stops reading and releases the connection. */
class SseCall internal constructor(private val closer: () -> Unit) {
    fun close() = closer()
}

class HttpCall internal constructor(val future: CompletableFuture<HttpResult>) {
    @Volatile
    private var exchange: CompletableFuture<*>? = null

    @Volatile
    private var cancelled = false

    /** The retry loop swaps the in-flight attempt; cancel the newest one too. */
    internal fun attach(next: CompletableFuture<*>) {
        exchange = next
        if (cancelled) next.cancel(true)
    }

    fun cancel() {
        cancelled = true
        exchange?.cancel(true)
        future.cancel(true)
    }
}

class HttpService(
    private val clientProvider: (AstraSettings) -> HttpClient = { HttpClients.client(it) },
) {
    fun send(
        request: AstraRequest,
        vars: Map<String, String> = emptyMap(),
        settings: AstraSettings = AstraSettings(),
    ): HttpCall {
        val startedAt = System.nanoTime()
        val effective = effectiveSettings(request, settings)
        val httpRequest = createRequest(request, vars, effective)
        val result = CompletableFuture<HttpResult>()
        val call = HttpCall(result)
        val attempts = request.retries.coerceIn(0, 10) + 1

        fun attempt(number: Int) {
            val exchange = clientProvider(effective)
                .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
            call.attach(exchange)
            exchange.whenComplete { response, error ->
                if (result.isCancelled) return@whenComplete
                if (error == null) {
                    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                    result.complete(
                        HttpResult(
                            status = response.statusCode(),
                            statusText = reasonPhrase(response.statusCode()),
                            headers = response.headers().map().mapValues { (_, values) -> values.toList() },
                            body = response.body(),
                            elapsedMs = elapsedMs,
                            cookies = parseCookies(response.headers().allValues("set-cookie")),
                        ),
                    )
                } else if (number < attempts) {
                    // Retry transport failures only: retrying a completed 5xx could duplicate writes.
                    CompletableFuture.delayedExecutor(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .execute { if (!result.isCancelled) attempt(number + 1) }
                } else {
                    result.completeExceptionally(error)
                }
            }
        }
        attempt(1)
        return call
    }

    /** Sends a request and waits for the response; call from a background thread. */
    fun sendBlocking(
        request: AstraRequest,
        vars: Map<String, String> = emptyMap(),
        settings: AstraSettings = AstraSettings(),
    ): HttpResult = try {
        val attempts = request.retries.coerceIn(0, 10) + 1
        send(request, vars, settings).future.get(
            (effectiveSettings(request, settings).timeoutSeconds.toLong() + 10) * attempts,
            java.util.concurrent.TimeUnit.SECONDS,
        )
    } catch (error: java.util.concurrent.ExecutionException) {
        throw error.cause ?: error
    }

    /** Applies per-request overrides on top of the global settings. */
    private fun effectiveSettings(request: AstraRequest, settings: AstraSettings): AstraSettings = settings.copy(
        timeoutSeconds = request.timeoutSeconds.takeIf { it > 0 } ?: settings.timeoutSeconds,
        followRedirects = request.followRedirects ?: settings.followRedirects,
    )

    private fun createRequest(request: AstraRequest, vars: Map<String, String>, settings: AstraSettings): HttpRequest {
        val method = VariableResolver.resolve(request.method, vars).uppercase()
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(buildRequestUrl(request, vars)))
            .timeout(Duration.ofSeconds(settings.timeoutSeconds.toLong().coerceAtLeast(1)))

        request.headers.filter { it.enabled && it.key.isNotBlank() }.forEach { header ->
            builder.header(
                VariableResolver.resolve(header.key, vars),
                VariableResolver.resolve(header.value, vars),
            )
        }

        val hasBody = method != "GET" && method != "HEAD" && request.bodyMode != "none"
        val boundary = "----Astra${java.util.UUID.randomUUID().toString().replace("-", "")}"
        if (hasBody && request.headers.none {
                it.enabled && it.key.equals("Content-Type", ignoreCase = true)
            }
        ) {
            builder.header(
                "Content-Type",
                when (request.bodyMode) {
                    "json" -> "application/json"
                    "graphql" -> "application/json"
                    "grpc" -> "application/grpc-web+${if (request.grpcBinary) "proto" else "json"}"
                    "urlencoded" -> "application/x-www-form-urlencoded"
                    "form-data" -> "multipart/form-data; boundary=$boundary"
                    else -> "text/plain; charset=utf-8"
                },
            )
        }
        if (request.bodyMode == "grpc") {
            if (request.headers.none { it.enabled && it.key.equals("x-grpc-web", ignoreCase = true) }) {
                builder.header("x-grpc-web", "1")
            }
            if (request.headers.none { it.enabled && it.key.equals("Accept", ignoreCase = true) }) {
                builder.header("Accept", "application/grpc-web+proto")
            }
        }

        applyAuth(builder, request, vars)

        val body = if (!hasBody) {
            HttpRequest.BodyPublishers.noBody()
        } else when (request.bodyMode) {
            "urlencoded" -> HttpRequest.BodyPublishers.ofString(
                request.formFields
                    .filter { it.enabled && !it.isFile && it.key.isNotBlank() }
                    .joinToString("&") {
                        "${encodeQueryComponent(VariableResolver.resolve(it.key, vars))}=" +
                            encodeQueryComponent(VariableResolver.resolve(it.value, vars))
                    },
                StandardCharsets.UTF_8,
            )
            "form-data" -> HttpRequest.BodyPublishers.ofByteArray(
                multipartBody(request.formFields, boundary, vars),
            )
            "graphql" -> HttpRequest.BodyPublishers.ofByteArray(graphqlBody(request, vars))
            "grpc" -> HttpRequest.BodyPublishers.ofByteArray(
                GrpcCodec.encode(GrpcCodec.payload(VariableResolver.resolve(request.grpcPayload, vars), request.grpcBinary)),
            )
            else -> HttpRequest.BodyPublishers.ofString(
                VariableResolver.resolve(request.body, vars),
                StandardCharsets.UTF_8,
            )
        }
        return builder.method(method, body).build()
    }

    /** Applies the configured authorization headers; shared with the SSE stream request. */
    private fun applyAuth(builder: HttpRequest.Builder, request: AstraRequest, vars: Map<String, String>) {
        when (request.auth.type.lowercase()) {
            "bearer" -> builder.header(
                "Authorization",
                "Bearer ${VariableResolver.resolve(request.auth.token, vars)}",
            )
            "basic" -> {
                val username = VariableResolver.resolve(request.auth.username, vars)
                val password = VariableResolver.resolve(request.auth.password, vars)
                val credentials = Base64.getEncoder().encodeToString(
                    "$username:$password".toByteArray(StandardCharsets.UTF_8),
                )
                builder.header("Authorization", "Basic $credentials")
            }
            "apikey" -> if (request.auth.apiKeyIn.equals("header", ignoreCase = true)) {
                builder.header(
                    VariableResolver.resolve(request.auth.apiKeyName, vars),
                    VariableResolver.resolve(request.auth.apiKeyValue, vars),
                )
            }
            "oauth2" -> {
                val token = OAuth2Service.ensureToken(request.auth, vars)
                if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
            }
        }
    }

    /** GraphQL over HTTP: a JSON object with the query and an optional variables object. */
    internal fun graphqlBody(request: AstraRequest, vars: Map<String, String>): ByteArray {
        val query = VariableResolver.resolve(request.graphqlQuery, vars)
        val rawVariables = VariableResolver.resolve(request.graphqlVars, vars).trim()
        val payload = JsonObject().apply { addProperty("query", query) }
        if (rawVariables.isNotEmpty()) {
            val parsed = try {
                JsonParser.parseString(rawVariables)
            } catch (_: Exception) {
                throw IllegalArgumentException("GraphQL variables are not valid JSON")
            }
            payload.add("variables", parsed)
        }
        return payload.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Opens a `text/event-stream` connection and reports each event through [onEvent].
     *
     * Reading happens on a daemon thread so the IDE stays responsive; [SseCall.close] aborts it.
     */
    fun openEventStream(
        request: AstraRequest,
        vars: Map<String, String> = emptyMap(),
        settings: AstraSettings = AstraSettings(),
        onOpen: (HttpResult) -> Unit = {},
        onEvent: (SseEvent) -> Unit,
        onClosed: (String) -> Unit = {},
        onError: (String) -> Unit = {},
    ): SseCall {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(buildRequestUrl(request, vars)))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .GET()
        request.headers.filter { it.enabled && it.key.isNotBlank() }.forEach { header ->
            builder.header(
                VariableResolver.resolve(header.key, vars),
                VariableResolver.resolve(header.value, vars),
            )
        }
        applyAuth(builder, request, vars)
        val closed = AtomicBoolean(false)
        val stream = java.util.concurrent.atomic.AtomicReference<java.io.InputStream?>()
        val future = clientProvider(settings).sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        future.whenComplete { response, error ->
            if (closed.get()) return@whenComplete
            if (error != null) {
                onError(error.cause?.message ?: error.message ?: "Event stream failed")
                return@whenComplete
            }
            stream.set(response.body())
            val headers = response.headers().map().mapValues { (_, values) -> values.toList() }
            if (response.statusCode() !in 200..299) {
                val errorBody = runCatching {
                    response.body().use { it.readNBytes(256 * 1024).toString(StandardCharsets.UTF_8) }
                }.getOrElse { "" }
                onOpen(
                    HttpResult(
                        status = response.statusCode(),
                        statusText = reasonPhrase(response.statusCode()),
                        headers = headers,
                        body = errorBody.toByteArray(StandardCharsets.UTF_8),
                    ),
                )
                onClosed("HTTP ${response.statusCode()}")
                return@whenComplete
            }
            onOpen(
                HttpResult(
                    status = response.statusCode(),
                    statusText = reasonPhrase(response.statusCode()),
                    headers = headers,
                ),
            )
            Thread({
                try {
                    BufferedReader(InputStreamReader(response.body(), StandardCharsets.UTF_8)).use { reader ->
                        var event = "message"
                        var data = StringBuilder()
                        var id: String? = null
                        while (!closed.get()) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) {
                                if (data.isNotEmpty() || event != "message") {
                                    onEvent(SseEvent(event, data.toString(), id))
                                }
                                event = "message"
                                data = StringBuilder()
                                continue
                            }
                            if (line.startsWith(":")) continue
                            val separator = line.indexOf(':')
                            val field = if (separator < 0) line else line.substring(0, separator)
                            var value = if (separator < 0) "" else line.substring(separator + 1)
                            if (value.startsWith(" ")) value = value.substring(1)
                            when (field) {
                                "event" -> event = value
                                "data" -> {
                                    if (data.isNotEmpty()) data.append('\n')
                                    data.append(value)
                                }
                                "id" -> id = value
                            }
                        }
                    }
                    if (!closed.get()) onClosed("Connection closed")
                } catch (readError: Exception) {
                    if (!closed.get()) onError(readError.message ?: "Event stream failed")
                }
            }, "Astra SSE reader").apply { isDaemon = true }.start()
        }
        return SseCall {
            closed.set(true)
            future.cancel(true)
            runCatching { stream.get()?.close() }
        }
    }

    internal fun multipartBody(fields: List<FormField>, boundary: String, vars: Map<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fields.filter { it.enabled && it.key.isNotBlank() }.forEach { field ->
            val name = VariableResolver.resolve(field.key, vars).replace("\"", "\\\"")
            out.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
            if (field.isFile) {
                val path = VariableResolver.resolve(field.filePath, vars).trim()
                if (path.isEmpty()) {
                    throw IllegalArgumentException("Choose a file for form field \"$name\"")
                }
                val file = java.io.File(path)
                if (!file.isFile) throw IllegalArgumentException("File not found: $path")
                val fileName = field.fileName.ifBlank { file.name }.replace("\"", "\\\"")
                val contentType = java.nio.file.Files.probeContentType(file.toPath())
                    ?: contentTypeForFile(file.name)
                out.write(
                    ("Content-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"\r\n" +
                        "Content-Type: $contentType\r\n\r\n").toByteArray(StandardCharsets.UTF_8),
                )
                out.write(file.readBytes())
            } else {
                val value = VariableResolver.resolve(field.value, vars)
                out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                out.write(value.toByteArray(StandardCharsets.UTF_8))
            }
            out.write("\r\n".toByteArray(StandardCharsets.UTF_8))
        }
        out.write("--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
        return out.toByteArray()
    }

    private fun contentTypeForFile(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "xml" -> "application/xml"
        "txt", "log", "csv" -> "text/plain"
        "html", "htm" -> "text/html"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun parseCookies(headers: List<String>): MutableList<CookieData> =
        headers.mapNotNull { header ->
            val parts = header.split(';')
            val first = parts.firstOrNull() ?: return@mapNotNull null
            val separator = first.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val cookie = CookieData(
                name = first.substring(0, separator).trim(),
                value = first.substring(separator + 1).trim(),
            )
            parts.drop(1).forEach { attribute ->
                val attributeParts = attribute.split('=', limit = 2)
                if (attributeParts.size == 2) {
                    when (attributeParts[0].trim().lowercase()) {
                        "domain" -> cookie.domain = attributeParts[1].trim()
                        "path" -> cookie.path = attributeParts[1].trim()
                    }
                }
            }
            cookie
        }.toMutableList()

    private fun reasonPhrase(status: Int): String = when (status) {
        100 -> "Continue"
        101 -> "Switching Protocols"
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        418 -> "I'm a teapot"
        422 -> "Unprocessable Entity"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> ""
    }
}

internal fun buildRequestUrl(request: AstraRequest, vars: Map<String, String>): String {
    val query = request.params
        .filter { it.enabled && it.key.isNotBlank() }
        .map { param ->
            encodeQueryComponent(VariableResolver.resolve(param.key, vars)) to
                encodeQueryComponent(VariableResolver.resolve(param.value, vars))
        }
        .toMutableList()

    if (request.auth.type.equals("apikey", ignoreCase = true) &&
        request.auth.apiKeyIn.equals("query", ignoreCase = true)
    ) {
        query += encodeQueryComponent(VariableResolver.resolve(request.auth.apiKeyName, vars)) to
            encodeQueryComponent(VariableResolver.resolve(request.auth.apiKeyValue, vars))
    }

    val resolvedUrl = VariableResolver.resolve(request.url, vars).trim().replace(" ", "%20")
    if (query.isEmpty()) return resolvedUrl

    val fragmentIndex = resolvedUrl.indexOf('#')
    val base = if (fragmentIndex >= 0) resolvedUrl.substring(0, fragmentIndex) else resolvedUrl
    val fragment = if (fragmentIndex >= 0) resolvedUrl.substring(fragmentIndex) else ""
    val separator = when {
        !base.contains('?') -> "?"
        base.endsWith('?') || base.endsWith('&') -> ""
        else -> "&"
    }
    return base + separator + query.joinToString("&") { (key, value) -> "$key=$value" } + fragment
}

internal fun encodeQueryComponent(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
