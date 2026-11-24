package com.astra.intellij.core

import com.astra.intellij.model.AstraSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One live WebSocket connection; [close] is idempotent. */
class WsConnection internal constructor(
    private val socket: WebSocket,
    private val onCloseCallback: () -> Unit,
) {
    private val closed = AtomicBoolean(false)

    fun send(text: String) {
        socket.sendText(text, true).get(20, TimeUnit.SECONDS)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "Client closed").get(5, TimeUnit.SECONDS) }
        onCloseCallback()
    }
}

/**
 * WebSocket support built on the JDK client.
 *
 * The desktop app connects with its own socket library; using [HttpClients] here keeps proxy,
 * client-certificate and TLS-verification settings identical to regular HTTP requests.
 */
object WsClient {
    fun connect(
        url: String,
        headers: List<Pair<String, String>>,
        subprotocol: String,
        settings: AstraSettings,
        verifyTls: Boolean,
        onOpen: () -> Unit,
        onMessage: (String) -> Unit,
        onClosed: (String) -> Unit,
        onError: (String) -> Unit,
    ): WsConnection {
        val effective = settings.copy(verifySsl = settings.verifySsl && verifyTls)
        val client = HttpClients.client(effective)
        val builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(20))
        headers.forEach { (key, value) ->
            if (key.isBlank()) return@forEach
            runCatching { builder.header(key, value) }
        }
        val protocols = subprotocol.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (protocols.isNotEmpty()) {
            builder.subprotocols(protocols.first(), *protocols.drop(1).toTypedArray())
        }

        val listener = object : WebSocket.Listener {
            private val buffer = StringBuilder()

            override fun onOpen(webSocket: WebSocket) {
                onOpen()
                webSocket.request(1)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buffer.append(data)
                if (last) {
                    val message = buffer.toString()
                    buffer.setLength(0)
                    onMessage(message)
                }
                webSocket.request(1)
                return null
            }

            override fun onBinary(webSocket: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletionStage<*>? {
                if (last) {
                    val bytes = ByteArray(data.remaining())
                    data.get(bytes)
                    onMessage("[binary ${bytes.size} bytes] base64: ${Base64.getEncoder().encodeToString(bytes)}")
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                onClosed(if (reason.isBlank()) "Closed (${statusCode})" else "Closed (${statusCode}): $reason")
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                onError(error.message ?: error.javaClass.simpleName)
            }
        }

        val socket = try {
            val future = builder.buildAsync(URI.create(url), listener)
            future.get(25, TimeUnit.SECONDS)
        } catch (error: Exception) {
            val cause = (error as? java.util.concurrent.ExecutionException)?.cause ?: error
            throw IllegalArgumentException("Connection failed: ${cause.message ?: cause.javaClass.simpleName}")
        }
        return WsConnection(socket) {
            onClosed("Connection closed")
        }
    }
}
