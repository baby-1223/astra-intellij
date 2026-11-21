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

    @Test
    fun `runs configured iterations and reports each iteration`() {
        val requests = listOf(
            AstraRequest(name = "one", url = "$baseUrl/1"),
            AstraRequest(name = "two", url = "$baseUrl/2"),
        )
        val events = mutableListOf<Pair<Int, Int>>()

        runner.run(requests, emptyMap(), null, AstraSettings(), RunnerConfig(iterations = 2)) { iteration, index, _ ->
            events += iteration to index
        }

        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1), events)
    }

    @Test
    fun `respects request delay between executions`() {
        val requests = listOf(
            AstraRequest(url = "$baseUrl/1"),
            AstraRequest(url = "$baseUrl/2"),
            AstraRequest(url = "$baseUrl/3"),
        )
        val timestamps = mutableListOf<Long>()

        runner.run(requests, emptyMap(), null, AstraSettings(), RunnerConfig(delayMs = 60)) { _, _, _ ->
            timestamps += System.nanoTime()
        }

        assertEquals(3, timestamps.size)
        assertTrue(timestamps[1] - timestamps[0] >= 50_000_000)
        assertTrue(timestamps[2] - timestamps[1] >= 50_000_000)
    }

    @Test
    fun `stops after first failure when continue on failure is disabled`() {
        val requests = listOf(
            AstraRequest(url = "not a url"),
            AstraRequest(url = "$baseUrl/should-not-run"),
        )
        val indexes = mutableListOf<Int>()

        runner.run(requests, emptyMap(), null, AstraSettings(), RunnerConfig(continueOnFailure = false)) { _, index, _ ->
            indexes += index
        }

        assertEquals(listOf(0), indexes)
    }

    @Test
    fun `cancel stops future executions and keeps completed results`() {
        val requests = List(5) { AstraRequest(url = "$baseUrl/$it") }
        val results = mutableListOf<RunItemResult>()
        val worker = thread(isDaemon = true) {
            runner.run(requests, emptyMap(), null, AstraSettings(), RunnerConfig(delayMs = 100)) { _, _, result ->
                results += result
            }
        }
        Thread.sleep(50)
        runner.cancel()
        worker.join(2000)

        assertTrue(results.isNotEmpty())
        assertTrue(results.size < requests.size)
    }

    @Test
    fun `data row variables resolve in request url without changing environment`() {
        val pathServer = ServerSocket(0)
        val pathBaseUrl = "http://127.0.0.1:${pathServer.localPort}"
        thread(isDaemon = true) {
            while (!pathServer.isClosed) {
                try {
                    pathServer.accept().use { socket ->
                        val requestLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
                        val status = if (requestLine.contains("/alpha")) 200 else 404
                        socket.getOutputStream().bufferedWriter().use { writer ->
                            writer.write("HTTP/1.1 $status OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            writer.flush()
                        }
                    }
                } catch (_: SocketException) {
                    break
                }
            }
        }
        val environment = AstraEnvironment(name = "test").apply { variables["path"] = "old" }
        val request = AstraRequest(url = "$pathBaseUrl/{{path}}").apply {
            assertions.add("status 200")
        }
        val results = mutableListOf<RunItemResult>()

        runner.run(
            listOf(request),
            mapOf("path" to "old"),
            environment,
            AstraSettings(),
            RunnerConfig(dataRows = listOf(mapOf("path" to "alpha"))),
        ) { _, _, result -> results += result }

        pathServer.close()
        assertTrue(results.single().passed, "expected data variable to resolve, got ${results.single().error}")
        assertEquals("old", environment.variables["path"])
    }
}
