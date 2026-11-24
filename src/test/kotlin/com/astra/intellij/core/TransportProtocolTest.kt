package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AuthConfig
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.RequestFolder
import com.astra.intellij.model.effectiveAuth
import com.astra.intellij.model.inheritedScripts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Covers the protocol extensions: GraphQL bodies, gRPC-Web framing and Postman scoped auth. */
class TransportProtocolTest {
    private val http = HttpService()
    private val postman = PostmanService()

    @Test
    fun `graphql body combines query and variables`() {
        val request = AstraRequest(
            bodyMode = "graphql",
            graphqlQuery = "query { hero { name } }",
            graphqlVars = "{\"id\": 7}",
        )

        val body = String(http.graphqlBody(request, emptyMap()), StandardCharsets.UTF_8)

        assertTrue(body.contains("\"query\""))
        assertTrue(body.contains("hero"))
        assertTrue(body.contains("\"variables\":{\"id\":7}"))
    }

    @Test
    fun `graphql body rejects invalid variables json`() {
        val request = AstraRequest(bodyMode = "graphql", graphqlQuery = "query { x }", graphqlVars = "{oops")

        assertThrows(IllegalArgumentException::class.java) {
            http.graphqlBody(request, emptyMap())
        }
    }

    @Test
    fun `grpc frames round-trip with trailers`() {
        val payload = "{\"ping\":true}".toByteArray(StandardCharsets.UTF_8)
        val frame = GrpcCodec.encode(payload)
        val trailer = GrpcCodec.encode("grpc-status: 0".toByteArray(), trailer = true)

        val decoded = GrpcCodec.decodeFrames(frame + trailer)

        assertTrue(decoded.contains("Frame 1"))
        assertTrue(decoded.contains("{\"ping\":true}"))
        assertTrue(decoded.contains("trailer"))
        assertTrue(decoded.contains("grpc-status: 0"))
    }

    @Test
    fun `grpc payload decodes base64 and rejects invalid input`() {
        val raw = byteArrayOf(1, 2, 3)
        val encoded = Base64.getEncoder().encodeToString(raw)

        assertTrue(GrpcCodec.payload(encoded, binary = true).contentEquals(raw))
        assertThrows(IllegalArgumentException::class.java) {
            GrpcCodec.payload("not base64!", binary = true)
        }
        assertEquals("hello", String(GrpcCodec.payload("hello", binary = false), StandardCharsets.UTF_8))
    }

    @Test
    fun `event stream delivers parsed sse events`() {
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/events") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                out.write("event: tick\ndata: 1\n\n".toByteArray())
                out.write("id: 9\ndata: hello\n\n".toByteArray())
                out.flush()
            }
        }
        server.start()
        val events = CopyOnWriteArrayList<SseEvent>()
        val closed = CountDownLatch(1)
        try {
            val call = http.openEventStream(
                AstraRequest(url = "http://127.0.0.1:${server.address.port}/events"),
                onEvent = { events.add(it) },
                onClosed = { closed.countDown() },
                onError = { closed.countDown() },
            )
            closed.await(5, TimeUnit.SECONDS)
            call.close()
        } finally {
            server.stop(0)
        }

        assertEquals(2, events.size)
        assertEquals("tick", events[0].event)
        assertEquals("1", events[0].data)
        assertEquals("message", events[1].event)
        assertEquals("hello", events[1].data)
        assertEquals("9", events[1].id)
    }

    @Test
    fun `postman export keeps folder auth scripts and graphql body`() {
        val collection = RequestCollection(name = "Scoped")
        collection.auth = AuthConfig(type = "basic", username = "root", password = "pw")
        collection.preRequest = mutableListOf("pm.variables.set('c','1');")
        val folder = RequestFolder(name = "Folder")
        folder.auth = AuthConfig(type = "bearer", token = "ft")
        folder.postResponse = mutableListOf("pm.test('ok', function () {});")
        val request = AstraRequest(
            name = "GraphQL",
            method = "POST",
            url = "https://x/graphql",
            bodyMode = "graphql",
            graphqlQuery = "query { hero { name } }",
            graphqlVars = "{\"id\":1}",
        )
        folder.requests.add(request)
        collection.folders.add(folder)

        val imported = postman.parseCollection(postman.exportCollection(listOf(collection), "Scoped"))
        val reimportedFolder = imported.folders.single()

        assertEquals("basic", imported.auth.type)
        assertEquals("root", imported.auth.username)
        assertEquals(listOf("pm.variables.set('c','1');"), imported.preRequest)
        assertEquals("bearer", reimportedFolder.auth.type)
        assertEquals("ft", reimportedFolder.auth.token)
        assertEquals(listOf("pm.test('ok', function () {});"), reimportedFolder.postResponse)
        assertEquals("graphql", reimportedFolder.requests.single().bodyMode)
        assertEquals("query { hero { name } }", reimportedFolder.requests.single().graphqlQuery)
        assertEquals("{\"id\":1}", reimportedFolder.requests.single().graphqlVars)
        assertEquals("bearer", imported.effectiveAuth(reimportedFolder.requests.single())!!.type)
        assertTrue(imported.inheritedScripts(reimportedFolder.requests.single(), preRequest = true).isNotEmpty())
    }
}
