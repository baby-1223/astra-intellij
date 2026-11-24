package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.normalize
import java.net.HttpCookie
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AstraCookieStoreTest {
    @Test
    fun `matches cookies by domain path and secure flag`() {
        val store = AstraCookieStore()
        store.add(null, HttpCookie("sid", "abc").apply {
            domain = "example.com"
            path = "/"
        })
        store.add(null, HttpCookie("api", "xyz").apply {
            domain = ".example.com"
            path = "/api"
            secure = true
        })

        val httpsRoot = store.get(URI("https://example.com/dashboard"))
        assertEquals(listOf("sid"), httpsRoot.map { it.name })

        val httpsApi = store.get(URI("https://api.example.com/api/users"))
        assertEquals(setOf("sid", "api"), httpsApi.map { it.name }.toSet())

        val plainApi = store.get(URI("http://api.example.com/api/users"))
        assertEquals(listOf("sid"), plainApi.map { it.name })

        assertTrue(store.get(URI("https://other.com/")).isEmpty())
    }

    @Test
    fun `replaces cookies with the same identity and removes them`() {
        val store = AstraCookieStore()
        repeat(2) { index ->
            store.add(null, HttpCookie("sid", "v$index").apply {
                domain = "example.com"
                path = "/"
            })
        }
        assertEquals(1, store.cookies.size)
        assertEquals("v1", store.cookies.single().value)

        val removed = HttpCookie("sid", "").apply {
            domain = "example.com"
            path = "/"
        }
        assertTrue(store.remove(null, removed))
        assertTrue(store.cookies.isEmpty())
        assertFalse(store.remove(null, removed))
    }

    @Test
    fun `fills defaults for new request fields`() {
        // Gson leaves absent fields null, which is what old persisted workspaces look like.
        val request = com.google.gson.Gson().fromJson(
            """{"retries":99,"timeoutSeconds":-5}""",
            AstraRequest::class.java,
        )

        request.normalize()

        assertEquals(0, request.timeoutSeconds)
        assertEquals(10, request.retries)
        assertNull(request.followRedirects)
        assertTrue(request.examples.isEmpty())
    }
}
