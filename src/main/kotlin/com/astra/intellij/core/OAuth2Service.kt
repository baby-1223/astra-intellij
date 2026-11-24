package com.astra.intellij.core

import com.astra.intellij.model.AuthConfig
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * OAuth 2.0 token acquisition for the IDE plugin.
 *
 * Supports authorization code (+ PKCE), client credentials, resource owner password and refresh
 * tokens. The desktop app already has the same flows; this keeps both clients behaviourally aligned.
 */
object OAuth2Service {
    data class Token(
        val accessToken: String = "",
        val refreshToken: String = "",
        val expiresAt: Long = 0L,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null && accessToken.isNotEmpty()
    }

    /**
     * Returns a usable access token, refreshing it first when it is expired and a refresh token
     * is available. Mutates [auth] so the refreshed values are persisted with the request.
     */
    fun ensureToken(auth: AuthConfig, vars: Map<String, String>): String {
        val token = VariableResolver.resolve(auth.token, vars)
        val expired = auth.oauthExpiresAt > 0 &&
            System.currentTimeMillis() > auth.oauthExpiresAt - 15_000
        if (token.isNotEmpty() && !expired) return token
        val refresh = VariableResolver.resolve(auth.oauthRefreshToken, vars)
        if (refresh.isEmpty()) return token
        val refreshed = refreshToken(
            tokenUrl = VariableResolver.resolve(auth.oauthTokenUrl, vars),
            refreshToken = refresh,
            clientId = VariableResolver.resolve(auth.oauthClientId, vars),
            clientSecret = VariableResolver.resolve(auth.oauthClientSecret, vars),
        )
        if (!refreshed.ok) return token
        auth.token = refreshed.accessToken
        auth.oauthRefreshToken = refreshed.refreshToken.ifEmpty { refresh }
        auth.oauthExpiresAt = refreshed.expiresAt
        return refreshed.accessToken
    }

    fun clientCredentials(
        tokenUrl: String,
        clientId: String,
        clientSecret: String,
        scope: String,
    ): Token = requestToken(
        tokenUrl,
        linkedMapOf(
            "grant_type" to "client_credentials",
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "scope" to scope,
        ),
    )

    fun password(
        tokenUrl: String,
        clientId: String,
        clientSecret: String,
        scope: String,
        username: String,
        password: String,
    ): Token = requestToken(
        tokenUrl,
        linkedMapOf(
            "grant_type" to "password",
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "scope" to scope,
            "username" to username,
            "password" to password,
        ),
    )

    fun refreshToken(
        tokenUrl: String,
        refreshToken: String,
        clientId: String,
        clientSecret: String,
    ): Token {
        if (tokenUrl.isBlank() || refreshToken.isBlank()) {
            return Token(error = "Missing token endpoint or refresh token")
        }
        return requestToken(
            tokenUrl,
            linkedMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
                "client_secret" to clientSecret,
            ),
        )
    }

    /**
     * Runs the browser authorization-code flow with a loopback redirect.
     *
     * Blocks until the callback arrives or [timeoutSeconds] elapses, so call it from a background
     * thread (the editor does).
     */
    fun authorizationCode(
        authUrl: String,
        tokenUrl: String,
        clientId: String,
        clientSecret: String,
        scope: String,
        pkce: Boolean = true,
        timeoutSeconds: Long = 180,
    ): Token {
        if (authUrl.isBlank() || tokenUrl.isBlank()) {
            return Token(error = "Enter the authorization and token endpoints first")
        }
        val verifier = if (pkce) randomString(64) else ""
        val challenge = if (pkce) pkceChallenge(verifier) else ""
        var port = DEFAULT_CALLBACK_PORT
        var server: HttpServer? = null
        while (server == null && port < DEFAULT_CALLBACK_PORT + 20) {
            server = try {
                HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
            } catch (_: Exception) {
                port++
                null
            }
        }
        if (server == null) {
            return Token(error = "No free local callback port (tried $DEFAULT_CALLBACK_PORT-${DEFAULT_CALLBACK_PORT + 19})")
        }
        val redirectUri = "http://127.0.0.1:$port/callback"
        val callback = CompletableFuture<Map<String, String>>()
        server.createContext("/callback") { exchange ->
            val params = parseQuery(exchange.requestURI.rawQuery.orEmpty())
            val body = "<html><head><meta charset=\"utf-8\"><title>Astra Authorization</title></head>" +
                "<body style=\"font-family:sans-serif;padding:40px\"><h2>Authorization complete</h2>" +
                "<p>You can close this tab and return to the IDE.</p></body></html>"
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.toByteArray(StandardCharsets.UTF_8).size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            callback.complete(params)
        }
        server.start()
        try {
            val query = linkedMapOf(
                "response_type" to "code",
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
            )
            if (scope.isNotBlank()) query["scope"] = scope
            if (pkce) {
                query["code_challenge"] = challenge
                query["code_challenge_method"] = "S256"
            }
            val separator = if (authUrl.contains('?')) "&" else "?"
            val url = authUrl + separator + query.entries.joinToString("&") {
                "${encodeQuery(it.key)}=${encodeQuery(it.value)}"
            }
            openBrowser(url)
            val params = callback.get(timeoutSeconds, TimeUnit.SECONDS)
            params["error"]?.let { error ->
                return Token(error = "Authorization failed: $error ${params["error_description"].orEmpty()}".trim())
            }
            val code = params["code"].orEmpty()
            if (code.isEmpty()) return Token(error = "No authorization code in callback")
            return requestToken(
                tokenUrl,
                linkedMapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to redirectUri,
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                    "code_verifier" to verifier,
                ),
            )
        } catch (e: Exception) {
            return Token(error = "Authorization failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            server.stop(0)
        }
    }

    private fun requestToken(tokenUrl: String, fields: Map<String, String>): Token {
        if (tokenUrl.isBlank()) return Token(error = "Enter the token endpoint first")
        val body = fields.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") { "${encodeQuery(it.key)}=${encodeQuery(it.value)}" }
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val text = response.body()
            if (response.statusCode() >= 400) {
                return Token(error = "Token request failed (HTTP ${response.statusCode()}): ${short(text)}")
            }
            val json = JsonParser.parseString(text)
            if (!json.isJsonObject) return Token(error = "Token response is not a JSON object")
            val obj = json.asJsonObject
            val access = obj.get("access_token")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            if (access.isEmpty()) return Token(error = "Response has no access_token: ${short(text)}")
            val expiresIn = obj.get("expires_in")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            Token(
                accessToken = access,
                refreshToken = obj.get("refresh_token")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                expiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
            )
        } catch (e: Exception) {
            Token(error = "Token request failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** RFC 7636 S256 challenge: base64url(SHA-256(verifier)) without padding. */
    internal fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private const val DEFAULT_CALLBACK_PORT = 8765
    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    private fun randomString(length: Int): String {
        val random = java.security.SecureRandom()
        return buildString { repeat(length) { append(CHARS[random.nextInt(CHARS.length)]) } }
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split("&").filter { it.isNotBlank() }.associate { pair ->
            val index = pair.indexOf('=')
            if (index < 0) {
                URLDecoder.decode(pair, StandardCharsets.UTF_8) to ""
            } else {
                URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8) to
                    URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8)
            }
        }

    private fun openBrowser(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url))
            }
        } catch (_: Exception) {
            // The URL is also shown in the status label, so the user can open it manually.
        }
    }

    private fun short(text: String): String =
        if (text.length > 240) text.take(240) + "…" else text
}
