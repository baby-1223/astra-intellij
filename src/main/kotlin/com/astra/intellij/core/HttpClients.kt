package com.astra.intellij.core

import com.astra.intellij.model.AstraSettings
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.http.HttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object HttpClients {
    val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    @Volatile
    private var cached: Pair<String, HttpClient>? = null

    @Synchronized
    fun client(settings: AstraSettings): HttpClient {
        val key = listOf(
            settings.proxyHost,
            settings.proxyPort,
            settings.verifySsl,
            settings.followRedirects,
            settings.timeoutSeconds,
        ).joinToString("|")
        cached?.let { if (it.first == key) return it.second }

        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(settings.timeoutSeconds.toLong().coerceAtLeast(1)))
            .cookieHandler(cookieManager)
            .followRedirects(
                if (settings.followRedirects) HttpClient.Redirect.NORMAL else HttpClient.Redirect.NEVER,
            )
        if (settings.proxyHost.isNotBlank() && settings.proxyPort > 0) {
            builder.proxy(ProxySelector.of(InetSocketAddress(settings.proxyHost.trim(), settings.proxyPort)))
        }
        if (!settings.verifySsl) {
            builder.sslContext(trustAllContext())
            builder.sslParameters(SSLParameters().apply { endpointIdentificationAlgorithm = null })
        }
        return builder.build().also { cached = key to it }
    }

    fun clearCookies() {
        cookieManager.cookieStore.removeAll()
    }

    private fun trustAllContext(): SSLContext {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        return SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
    }
}
