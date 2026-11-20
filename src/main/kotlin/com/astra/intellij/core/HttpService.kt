package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.CookieData
import com.astra.intellij.model.HttpResult
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture

class HttpCall internal constructor(
    val future: CompletableFuture<HttpResult>,
    private val exchange: CompletableFuture<*>,
) {
    fun cancel() {
        exchange.cancel(true)
        future.cancel(true)
    }
}

class HttpService(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(90))
        .build(),
) {
    fun send(request: AstraRequest, vars: Map<String, String> = emptyMap()): HttpCall {
        val startedAt = System.nanoTime()
        val httpRequest = createRequest(request, vars)
        val exchange = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
        val future = exchange.thenApply { response ->
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            HttpResult(
                status = response.statusCode(),
                statusText = reasonPhrase(response.statusCode()),
                headers = response.headers().map().mapValues { (_, values) -> values.toList() },
                body = response.body(),
                elapsedMs = elapsedMs,
                cookies = parseCookies(response.headers().allValues("set-cookie")),
            )
        }
        return HttpCall(future, exchange)
    }

    private fun createRequest(request: AstraRequest, vars: Map<String, String>): HttpRequest {
        val method = VariableResolver.resolve(request.method, vars).uppercase()
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(buildRequestUrl(request, vars)))
            .timeout(Duration.ofSeconds(90))

        request.headers.filter { it.enabled && it.key.isNotBlank() }.forEach { header ->
            builder.header(
                VariableResolver.resolve(header.key, vars),
                VariableResolver.resolve(header.value, vars),
            )
        }

        val hasBody = method != "GET" && method != "HEAD" && request.bodyMode != "none"
        if (hasBody && request.headers.none {
                it.enabled && it.key.equals("Content-Type", ignoreCase = true)
            }
        ) {
            builder.header(
                "Content-Type",
                if (request.bodyMode == "json") "application/json" else "text/plain; charset=utf-8",
            )
        }

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
        }

        val body = if (hasBody) {
            HttpRequest.BodyPublishers.ofString(
                VariableResolver.resolve(request.body, vars),
                StandardCharsets.UTF_8,
            )
        } else {
            HttpRequest.BodyPublishers.noBody()
        }
        return builder.method(method, body).build()
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

    val resolvedUrl = VariableResolver.resolve(request.url, vars)
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

private fun encodeQueryComponent(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
