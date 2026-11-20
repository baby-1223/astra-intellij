package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AstraSettings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class CollectionRunnerTest {
    private lateinit var server: ServerSocket
    private lateinit var baseUrl: String
    private val runner = CollectionRunner()

    @BeforeEach
    fun startServer() {
        server = ServerSocket(0)
        baseUrl = "http://127.0.0.1:${server.localPort}"
        thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val requestBytes = ByteArray(8192)
                        var total = 0
                        while (total < requestBytes.size) {
                            val read = socket.getInputStream().read(requestBytes, total, requestBytes.size - total)
                            if (read < 0) break
                            total += read
                            if (String(requestBytes, 0, total).contains("\r\n\r\n")) break
                        }
                        socket.getOutputStream().bufferedWriter().use { writer ->
                            writer.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{\"ok\":true}")
                            writer.flush()
                        }
                    }
                } catch (_: SocketException) {
                    break
                }
            }
        }
    }

    @AfterEach
    fun closeServer() {
        server.close()
    }

    @Test
    fun `runs requests sequentially and aggregates assertions`() {
        val requests = listOf(
            AstraRequest(name = "one", url = "$baseUrl/1").apply { assertions.add("status 200") },
            AstraRequest(name = "two", url = "$baseUrl/2").apply { assertions.add("status 404") },
            AstraRequest(name = "three", url = "$baseUrl/3"),
        )
        val results = mutableListOf<RunItemResult>()
        val indexes = mutableListOf<Int>()

        runner.run(requests, emptyMap(), null, AstraSettings()) { index, result ->
            indexes.add(index)
            results.add(result)
        }

        assertEquals(listOf(0, 1, 2), indexes)
        assertTrue(results[0].passed, "first request error: ${results[0].error}, tests: ${results[0].tests}")
        assertFalse(results[1].passed)
        assertTrue(results[2].passed)
        assertEquals(200, results[0].status)
        assertEquals("status 404", results[1].tests.single().assertion)
    }

    @Test
    fun `pre request instructions apply during run`() {
        val environment = AstraEnvironment(name = "test")
        val request = AstraRequest(url = "$baseUrl/{{path}}").apply {
            preRequest.add("set path hooked")
        }

        runner.run(listOf(request), emptyMap(), environment, AstraSettings()) { _, _ -> }

        assertEquals("hooked", environment.variables["path"])
    }

    @Test
    fun `invalid request is reported as error item`() {
        val results = mutableListOf<RunItemResult>()

        runner.run(listOf(AstraRequest(url = "not a url")), emptyMap(), null, AstraSettings()) { _, result ->
            results.add(result)
        }

        assertEquals(1, results.size)
        assertNotNull(results.single().error)
        assertFalse(results.single().passed)
    }
}
