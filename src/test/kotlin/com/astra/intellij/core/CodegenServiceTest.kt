package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AuthConfig
import com.astra.intellij.model.KeyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodegenServiceTest {
    private val service = CodegenService()

    private fun sampleRequest() = AstraRequest(
        method = "POST",
        url = "https://api.example.com/users",
        headers = mutableListOf(KeyValue(key = "X-Trace", value = "abc")),
        bodyMode = "json",
        body = "{\"name\":\"astra\"}",
        auth = AuthConfig(type = "bearer", token = "secret"),
    )

    @Test
    fun `curl delegates to the existing exporter`() {
        val snippet = service.generate(sampleRequest(), emptyMap(), CodegenLanguage.CURL)
        assertTrue(snippet.startsWith("curl"))
    }

    @Test
    fun `python snippet contains url headers and payload`() {
        val snippet = service.generate(sampleRequest(), emptyMap(), CodegenLanguage.PYTHON)
        assertTrue(snippet.contains("import requests"))
        assertTrue(snippet.contains("requests.request(\"POST\", url, headers=headers, data=payload.encode(\"utf-8\"), timeout=30)"))
        assertTrue(snippet.contains("\"Authorization\": \"Bearer secret\""))
        assertTrue(snippet.contains("\"Content-Type\": \"application/json\""))
        assertTrue(snippet.contains("{\\\"name\\\":\\\"astra\\\"}"))
    }

    @Test
    fun `javascript snippet wraps fetch in an async iife`() {
        val snippet = service.generate(sampleRequest(), emptyMap(), CodegenLanguage.JAVASCRIPT)
        assertTrue(snippet.contains("(async () => {"))
        assertTrue(snippet.contains("method: \"POST\""))
        assertTrue(snippet.contains("body,"))
    }

    @Test
    fun `go snippet builds a request with header sets`() {
        val snippet = service.generate(sampleRequest(), emptyMap(), CodegenLanguage.GO)
        assertTrue(snippet.contains("http.NewRequest(\"POST\", \"https://api.example.com/users\", body)"))
        assertTrue(snippet.contains("req.Header.Set(\"X-Trace\", \"abc\")"))
        assertTrue(snippet.contains("\"strings\""))
    }

    @Test
    fun `java snippet uses the http client builder`() {
        val snippet = service.generate(sampleRequest(), emptyMap(), CodegenLanguage.JAVA)
        assertTrue(snippet.contains("HttpRequest.newBuilder()"))
        assertTrue(snippet.contains(".method(\"POST\", HttpRequest.BodyPublishers.ofString("))
        assertTrue(snippet.contains("HttpResponse.BodyHandlers.ofString()"))
    }

    @Test
    fun `http client snippet is plain text`() {
        val snippet = service.generate(sampleRequest(), emptyMap(), CodegenLanguage.HTTP_CLIENT)
        assertTrue(snippet.startsWith("POST https://api.example.com/users"))
        assertTrue(snippet.contains("X-Trace: abc"))
        assertTrue(snippet.endsWith("\n"))
    }

    @Test
    fun `get requests omit bodies`() {
        val request = AstraRequest(method = "GET", url = "https://api.example.com/ping")
        val go = service.generate(request, emptyMap(), CodegenLanguage.GO)
        assertTrue(go.contains("http.NewRequest(\"GET\", \"https://api.example.com/ping\", nil)"))
        val java = service.generate(request, emptyMap(), CodegenLanguage.JAVA)
        assertTrue(java.contains("HttpRequest.BodyPublishers.noBody()"))
    }

    @Test
    fun `variables are resolved before rendering`() {
        val request = AstraRequest(method = "GET", url = "{{baseUrl}}/health")
        val snippet = service.generate(request, mapOf("baseUrl" to "https://staging.example.com"), CodegenLanguage.PYTHON)
        assertEquals(true, snippet.contains("https://staging.example.com/health"))
    }
}
