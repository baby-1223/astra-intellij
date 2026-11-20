package com.astra.intellij.core

import com.astra.intellij.model.AstraSettings
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HttpClientsTest {
    @Test
    fun `client is cached per settings`() {
        val settings = AstraSettings()
        assertSame(HttpClients.client(settings), HttpClients.client(settings.copy()))
        assertNotSame(
            HttpClients.client(settings),
            HttpClients.client(settings.copy(timeoutSeconds = settings.timeoutSeconds + 1)),
        )
        assertNotSame(
            HttpClients.client(settings.copy(proxyHost = "127.0.0.1", proxyPort = 8080)),
            HttpClients.client(settings),
        )
    }

    @Test
    fun `clear cookies does not fail`() {
        HttpClients.clearCookies()
        assertSame(HttpClients.cookieManager, HttpClients.cookieManager)
    }
}
