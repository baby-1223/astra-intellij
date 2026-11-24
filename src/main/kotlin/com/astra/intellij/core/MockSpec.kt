package com.astra.intellij.core

import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.allRequests
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/** One route served by the local mock server. */
data class MockRoute(
    val method: String,
    val path: String,
    val status: Int = 200,
    val contentType: String = "application/json",
    val body: String = "",
    val delayMs: Int = 0,
    val headers: Map<String, String> = emptyMap(),
)

data class MockSpec(
    val name: String,
    val routes: List<MockRoute>,
)

/**
 * Builds mock routes either from an OpenAPI document or from the saved Astra collection.
 *
 * The collection path covers the "no documentation" case: every saved request gets a route with
 * generated sample fields, and saved response examples are used verbatim when present.
 */
object MockSpecLoader {
    private val httpMethods = listOf("get", "post", "put", "patch", "delete", "head", "options")

    fun fromOpenApi(text: String, defaultDelayMs: Int = 0): MockSpec {
        val root = JsonParser.parseString(YamlSupport.normalizeToJson(text)).asJsonObject
        val title = root.getAsJsonObject("info")?.get("title")?.takeIf { it.isJsonPrimitive }?.asString
            ?: "OpenAPI Mock"
        val components = root.getAsJsonObject("components")
        val routes = mutableListOf<MockRoute>()
        val paths = root.getAsJsonObject("paths") ?: return MockSpec(title, emptyList())

        paths.entrySet().forEach { (path, element) ->
            if (!element.isJsonObject) return@forEach
            val pathItem = element.asJsonObject
            httpMethods.forEach { method ->
                val operation = pathItem.getAsJsonObject(method) ?: return@forEach
                val (status, mediaType, body) = responseFor(operation, components)
                routes += MockRoute(
                    method = method.uppercase(Locale.ROOT),
                    path = normalizePath(path),
                    status = status,
                    contentType = mediaType,
                    body = body,
                    delayMs = defaultDelayMs,
                )
            }
        }
        if (routes.isEmpty()) {
            throw IllegalArgumentException("No operations found in this OpenAPI document")
        }
        return MockSpec(title, routes)
    }

    fun fromCollection(collection: RequestCollection, defaultDelayMs: Int = 0): MockSpec {
        val routes = collection.allRequests()
            .filterNot { it.isWebSocket }
            .map { request ->
                val example = request.examples.firstOrNull()
                val body = example?.body?.takeIf { it.isNotBlank() }
                    ?: MockDataGenerator.generatedBody(request.name, request.method)
                MockRoute(
                    method = request.method.uppercase(Locale.ROOT),
                    path = pathOf(request.url, request.name),
                    status = example?.status?.takeIf { it > 0 } ?: 200,
                    contentType = contentTypeOf(body),
                    body = body,
                    delayMs = defaultDelayMs,
                    headers = example?.headers
                        ?.mapNotNull { (key, values) -> values.firstOrNull()?.let { key to it } }
                        ?.toMap()
                        .orEmpty(),
                )
            }
        if (routes.isEmpty()) {
            throw IllegalArgumentException("This collection has no HTTP requests to mock")
        }
        return MockSpec(collection.name, routes)
    }

    /** Extracts the path part of a request URL, including `{{baseUrl}}` style variables. */
    fun pathOf(url: String, name: String = ""): String {
        var value = url.trim().substringBefore('?').substringBefore('#')
        value = value.replace(Regex("^\\{\\{[^}]+}}"), "")
        if (value.contains("://")) {
            val afterScheme = value.substringAfter("://")
            value = afterScheme.substring(afterScheme.indexOf('/').takeIf { it >= 0 } ?: afterScheme.length)
        } else if (!value.startsWith("/")) {
            value = value.substringAfter('/', "")
            if (value.isNotEmpty()) value = "/$value"
        }
        if (value.isBlank()) {
            val slug = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
            return if (slug.isBlank()) "/" else "/$slug"
        }
        return normalizePath(value)
    }

    private fun responseFor(
        operation: JsonObject,
        components: JsonObject?,
    ): Triple<Int, String, String> {
        val responses = operation.getAsJsonObject("responses") ?: return Triple(200, "application/json", "{}")
        val entry = responses.entrySet()
            .firstOrNull { it.key.startsWith("2") }
            ?: responses.entrySet().firstOrNull { it.key == "default" }
            ?: responses.entrySet().firstOrNull()
            ?: return Triple(200, "application/json", "{}")
        val status = entry.key.toIntOrNull() ?: 200
        val response = entry.value.takeIf { it.isJsonObject }?.asJsonObject
            ?: return Triple(status, "application/json", "{}")
        val content = response.getAsJsonObject("content")
            ?: return Triple(status, "application/json", "")
        val mediaEntry = content.entrySet().firstOrNull() ?: return Triple(status, "application/json", "")
        val mediaType = mediaEntry.key
        val media = mediaEntry.value.takeIf { it.isJsonObject }?.asJsonObject
            ?: return Triple(status, mediaType, "")
        val example = media.get("example")
            ?: media.getAsJsonObject("examples")?.entrySet()
                ?.firstOrNull { it.value.isJsonObject }
                ?.value?.asJsonObject?.get("value")
        val body = when {
            example != null && !example.isJsonNull -> render(example)
            media.has("schema") -> MockDataGenerator.pretty(
                MockDataGenerator.fromSchema(media.get("schema"), components),
            )
            else -> ""
        }
        return Triple(status, mediaType, body)
    }

    private fun render(element: com.google.gson.JsonElement): String =
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            element.asString
        } else {
            MockDataGenerator.pretty(element)
        }

    private fun contentTypeOf(body: String): String =
        if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) {
            "application/json"
        } else {
            "text/plain; charset=utf-8"
        }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/"
        val withoutQuery = trimmed.substringBefore('?')
        return if (withoutQuery.startsWith("/")) withoutQuery else "/$withoutQuery"
    }

}
