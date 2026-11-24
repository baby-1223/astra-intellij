package com.astra.intellij.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.util.Locale

/**
 * Produces realistic example payloads for mock responses.
 *
 * When an OpenAPI schema is available it is followed (properties, arrays, enums, `$ref`).
 * Endpoints without documentation get a plausible object derived from the request name instead.
 */
object MockDataGenerator {
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .serializeNulls()
        .create()

    fun pretty(element: JsonElement): String = gson.toJson(element)

    fun fromSchema(schema: JsonElement?, components: JsonObject?, depth: Int = 0): JsonElement {
        if (schema == null || schema.isJsonNull || !schema.isJsonObject) return JsonObject()
        if (depth > 6) return JsonObject()
        val obj = schema.asJsonObject

        obj.get("\$ref")?.takeIf { it.isJsonPrimitive }?.asString?.let { ref ->
            resolveRef(ref, components)?.let { return fromSchema(it, components, depth + 1) }
        }
        obj.get("example")?.takeIf { !it.isJsonNull }?.let { return it }
        obj.get("default")?.takeIf { !it.isJsonNull }?.let { return it }
        obj.get("const")?.takeIf { !it.isJsonNull }?.let { return it }
        obj.get("enum")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.let { return it }

        obj.get("allOf")?.takeIf { it.isJsonArray }?.let { allOf ->
            val merged = JsonObject()
            val properties = JsonObject()
            allOf.asJsonArray.forEach { part ->
                val resolved = fromSchema(part, components, depth + 1)
                if (resolved.isJsonObject) {
                    resolved.asJsonObject.entrySet().forEach { (key, value) ->
                        if (key == "properties" && value.isJsonObject) {
                            value.asJsonObject.entrySet().forEach { (name, schemaValue) ->
                                properties.add(name, schemaValue)
                            }
                        } else {
                            merged.add(key, value)
                        }
                    }
                }
            }
            if (properties.size() > 0) {
                merged.add("properties", properties)
                merged.addProperty("type", "object")
            }
            return fromSchema(merged, components, depth + 1)
        }
        obj.get("oneOf")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.let {
            return fromSchema(it, components, depth + 1)
        }
        obj.get("anyOf")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.let {
            return fromSchema(it, components, depth + 1)
        }

        return when (typeOf(obj)) {
            "object" -> objectValue(obj, components, depth)
            "array" -> {
                val array = JsonArray()
                val items = obj.get("items")
                array.add(fromSchema(items, components, depth + 1))
                array.add(fromSchema(items, components, depth + 1))
                array
            }
            "integer" -> JsonPrimitive(42)
            "number" -> JsonPrimitive(3.14)
            "boolean" -> JsonPrimitive(true)
            "null" -> JsonNull.INSTANCE
            "string" -> JsonPrimitive(stringFor(obj))
            else -> if (obj.has("properties")) objectValue(obj, components, depth) else JsonObject()
        }
    }

    /** Deterministic payload for an endpoint that has no schema of its own. */
    fun generatedBody(name: String, method: String): String {
        val label = name.ifBlank { "Resource" }.trim()
        val upper = method.uppercase(Locale.ROOT)
        val plural = label.endsWith("s", ignoreCase = true) || label.contains("list", ignoreCase = true)
        return if (upper == "GET" && plural) {
            pretty(
                JsonArray().apply {
                    add(sampleObject(label, 1))
                    add(sampleObject(label, 2))
                },
            )
        } else {
            pretty(sampleObject(label, 1))
        }
    }

    private fun sampleObject(label: String, index: Int): JsonObject = JsonObject().apply {
        addProperty("id", index)
        addProperty("name", if (index == 1) "Sample $label" else "Sample $label $index")
        addProperty("status", "active")
        addProperty("email", "user$index@example.com")
        addProperty("createdAt", "2024-01-0${index}T00:00:00Z")
    }

    private fun objectValue(obj: JsonObject, components: JsonObject?, depth: Int): JsonObject {
        val result = JsonObject()
        obj.get("properties")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (name, schema) ->
            result.add(name, fromSchema(schema, components, depth + 1))
        }
        if (result.size() == 0) {
            obj.get("additionalProperties")?.takeIf { it.isJsonObject }?.let { extra ->
                result.add("key", fromSchema(extra, components, depth + 1))
            }
        }
        return result
    }

    private fun typeOf(obj: JsonObject): String {
        val type = obj.get("type") ?: return ""
        if (type.isJsonArray) {
            return type.asJsonArray
                .firstOrNull { it.isJsonPrimitive && it.asString != "null" }
                ?.asString
                ?: ""
        }
        return if (type.isJsonPrimitive) type.asString else ""
    }

    private fun stringFor(obj: JsonObject): String {
        val format = obj.get("format")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        return when (format) {
            "date" -> "2024-01-01"
            "date-time" -> "2024-01-01T00:00:00Z"
            "email" -> "user@example.com"
            "uuid" -> "00000000-0000-4000-8000-000000000000"
            "uri", "url" -> "https://example.com"
            "hostname" -> "example.com"
            "ipv4" -> "127.0.0.1"
            "ipv6" -> "::1"
            "password" -> "password"
            "byte" -> "c3RyaW5n"
            "binary" -> "binary"
            else -> "string"
        }
    }

    private fun resolveRef(ref: String, components: JsonObject?): JsonObject? {
        if (!ref.startsWith("#/") || components == null) return null
        var tokens = ref.removePrefix("#/").split('/')
        // The caller passes the `components` object, so strip its own prefix from the pointer.
        if (tokens.firstOrNull() == "components") tokens = tokens.drop(1)
        var current: JsonElement = components
        tokens.forEach { token ->
            val name = token.replace("~1", "/").replace("~0", "~")
            if (!current.isJsonObject) return null
            current = current.asJsonObject.get(name) ?: return null
        }
        return current.takeIf { it.isJsonObject }?.asJsonObject
    }
}
