package com.astra.intellij.core

import com.astra.intellij.model.AstraSettings
import java.io.File
import java.net.Authenticator
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.CookieStore
import java.net.HttpCookie
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object HttpClients {
    val cookieStore = AstraCookieStore()
    val cookieManager = CookieManager(cookieStore, CookiePolicy.ACCEPT_ALL)

    @Volatile
    private var cached: Pair<String, HttpClient>? = null

    @Synchronized
    fun client(settings: AstraSettings): HttpClient {
        val key = cacheKey(settings)
        cached?.let { if (it.first == key) return it.second }

        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(settings.timeoutSeconds.toLong().coerceAtLeast(1)))
            .cookieHandler(cookieManager)
            .followRedirects(
                if (settings.followRedirects) HttpClient.Redirect.NORMAL else HttpClient.Redirect.NEVER,
            )
        if (settings.proxyHost.isNotBlank() && settings.proxyPort > 0) {
            val type = if (settings.proxyType.equals("socks", ignoreCase = true)) {
                Proxy.Type.SOCKS
            } else {
                Proxy.Type.HTTP
            }
            builder.proxy(
                FixedProxySelector(
                    Proxy(type, InetSocketAddress(settings.proxyHost.trim(), settings.proxyPort)),
                    parseBypass(settings.bypassHosts),
                ),
            )
        }
        if (settings.proxyUsername.isNotBlank()) {
            val username = settings.proxyUsername
            val password = settings.proxyPassword
            builder.authenticator(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? =
                    if (requestorType == RequestorType.PROXY) {
                        PasswordAuthentication(username, password.toCharArray())
                    } else {
                        null
                    }
            })
        }
        val context = sslContext(settings)
        if (context != null) {
            builder.sslContext(context)
            if (!settings.verifySsl) {
                builder.sslParameters(SSLParameters().apply { endpointIdentificationAlgorithm = null })
            }
        }
        return builder.build().also { cached = key to it }
    }

    fun clearCookies() {
        cookieStore.removeAll()
    }

    /** Cache key includes the client certificate's timestamp so replacing the file takes effect. */
    private fun cacheKey(settings: AstraSettings): String {
        val cert = settings.clientCertPath.trim().takeIf { it.isNotEmpty() }?.let { path ->
            val file = File(path)
            "$path:${file.lastModified()}:${file.length()}"
        }.orEmpty()
        return listOf(
            settings.proxyHost,
            settings.proxyPort,
            settings.proxyType,
            settings.proxyUsername,
            settings.proxyPassword,
            settings.bypassHosts,
            settings.verifySsl,
            settings.followRedirects,
            settings.timeoutSeconds,
            cert,
            settings.clientCertPassword,
        ).joinToString("|")
    }

    private fun sslContext(settings: AstraSettings): SSLContext? {
        val certPath = settings.clientCertPath.trim()
        val needTrustAll = !settings.verifySsl
        if (certPath.isEmpty() && !needTrustAll) return null
        val keyManagers = if (certPath.isNotEmpty()) loadKeyManagers(certPath, settings.clientCertPassword) else null
        val trustManagers = if (needTrustAll) arrayOf<TrustManager>(trustAllManager) else null
        return SSLContext.getInstance("TLS").apply {
            init(keyManagers, trustManagers, SecureRandom())
        }
    }

    private fun loadKeyManagers(path: String, password: String): Array<javax.net.ssl.KeyManager> {
        val file = File(path)
        if (!file.isFile) {
            throw IllegalArgumentException("Client certificate not found: $path")
        }
        val keyStore = KeyStore.getInstance("PKCS12")
        file.inputStream().use { keyStore.load(it, password.toCharArray()) }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, password.toCharArray())
        return factory.keyManagers
    }

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    internal fun parseBypass(text: String): List<String> =
        text.split(',', ' ', '\t', '\n', ';')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

    internal fun hostMatchesRule(host: String, rule: String): Boolean {
        val target = host.lowercase()
        val pattern = rule.trim().lowercase()
        if (pattern.isEmpty()) return false
        if (pattern == "*") return true
        if (pattern.startsWith("*.")) {
            val suffix = pattern.removePrefix("*")
            return target.endsWith(suffix) || target == pattern.removePrefix("*.")
        }
        return target == pattern || target.endsWith(".$pattern")
    }

    /** Applies the proxy only to hosts that are not covered by the bypass list. */
    private class FixedProxySelector(
        private val proxy: Proxy,
        private val bypass: List<String>,
    ) : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val host = uri.host
            if (host != null && bypass.any { hostMatchesRule(host, it) }) {
                return listOf(Proxy.NO_PROXY)
            }
            return listOf(proxy)
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
    }
}

/**
 * Simple cookie jar that keeps every cookie addressable so the UI can list, edit and delete
 * individual entries. Matching follows the standard domain/path/secure rules.
 */
class AstraCookieStore : CookieStore {
    private val items = CopyOnWriteArrayList<HttpCookie>()

    override fun add(uri: URI?, cookie: HttpCookie) {
        if (cookie.name.isBlank()) return
        if (cookie.domain.isNullOrBlank()) {
            val host = uri?.host ?: return
            cookie.domain = host
        }
        if (cookie.path.isNullOrBlank()) cookie.path = defaultPath(uri?.path ?: "/")
        items.removeAll { sameKey(it, cookie) }
        if (!cookie.hasExpired()) items.add(cookie)
    }

    override fun get(uri: URI): List<HttpCookie> = items.filter { cookie ->
        !cookie.hasExpired() &&
            domainMatches(uri.host, cookie.domain) &&
            pathMatches(uri.path?.ifBlank { "/" } ?: "/", cookie.path ?: "/") &&
            (!cookie.secure || uri.scheme.equals("https", ignoreCase = true))
    }

    override fun getURIs(): List<URI> = items.mapNotNull { cookie ->
        val host = cookie.domain?.trim()?.removePrefix(".")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        runCatching {
            URI(if (cookie.secure) "https" else "http", host, cookie.path ?: "/", null)
        }.getOrNull()
    }.distinct()

    override fun getCookies(): List<HttpCookie> = items.toList()

    override fun remove(uri: URI?, cookie: HttpCookie): Boolean =
        items.removeAll { sameKey(it, cookie) }

    override fun removeAll(): Boolean = items.isNotEmpty().also { items.clear() }

    private fun sameKey(left: HttpCookie, right: HttpCookie): Boolean =
        left.name == right.name &&
            left.domain.equals(right.domain, ignoreCase = true) &&
            (left.path ?: "/") == (right.path ?: "/")

    private fun domainMatches(host: String?, domain: String?): Boolean {
        if (domain.isNullOrBlank()) return true
        val target = host?.lowercase() ?: return false
        val suffix = domain.lowercase().removePrefix(".")
        return target == suffix || target.endsWith(".$suffix")
    }

    private fun pathMatches(requestPath: String, cookiePath: String): Boolean {
        if (cookiePath == "/" || requestPath == cookiePath) return true
        if (!requestPath.startsWith(cookiePath)) return false
        return cookiePath.endsWith("/") || requestPath.substring(cookiePath.length).startsWith("/")
    }

    private fun defaultPath(path: String): String {
        val value = path.ifBlank { "/" }
        if (!value.startsWith("/")) return "/"
        val index = value.lastIndexOf('/')
        return if (index <= 0) "/" else value.substring(0, index)
    }
}
