package com.astra.intellij.core

import com.astra.intellij.model.AstraSettings
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpClientsTest {
    @Test
    fun `bypass list splits on common separators`() {
        assertEqualsList(
            listOf("localhost", "*.internal.corp", "127.0.0.1"),
            HttpClients.parseBypass("localhost, *.internal.corp  127.0.0.1"),
        )
    }

    @Test
    fun `host matching supports wildcards and subdomains`() {
        assertTrue(HttpClients.hostMatchesRule("api.example.com", "example.com"))
        assertTrue(HttpClients.hostMatchesRule("deep.api.example.com", "*.example.com"))
        assertTrue(HttpClients.hostMatchesRule("example.com", "*.example.com"))
        assertTrue(HttpClients.hostMatchesRule("anything.test", "*"))
        assertFalse(HttpClients.hostMatchesRule("notexample.com", "example.com"))
        assertFalse(HttpClients.hostMatchesRule("example.com.evil.test", "*.example.com"))
    }

    @Test
    fun `clients are cached per settings fingerprint`() {
        val first = AstraSettings(proxyHost = "127.0.0.1", proxyPort = 8080, proxyType = "http")
        val second = AstraSettings(proxyHost = "127.0.0.1", proxyPort = 1080, proxyType = "socks")
        val a = HttpClients.client(first)
        val b = HttpClients.client(first)
        val c = HttpClients.client(second)
        assertSame(a, b)
        assertNotSame(a, c)
    }

    private fun assertEqualsList(expected: List<String>, actual: List<String>) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual)
    }
}
