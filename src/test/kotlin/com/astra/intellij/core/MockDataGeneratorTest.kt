package com.astra.intellij.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MockDataGeneratorTest {
    private val components = JsonParser.parseString(
        """
        {
          "schemas": {
            "Owner": {
              "type": "object",
              "properties": {
                "name": { "type": "string" }
              }
            }
          }
        }
        """.trimIndent(),
    ).asJsonObject

    @Test
    fun `generates values for objects, enums, refs and arrays`() {
        val schema = JsonParser.parseString(
            """
            {
              "type": "object",
              "properties": {
                "id": { "type": "integer" },
                "email": { "type": "string", "format": "email" },
                "status": { "type": "string", "enum": ["active", "disabled"] },
                "owner": { "${'$'}ref": "#/components/schemas/Owner" },
                "tags": { "type": "array", "items": { "type": "string" } },
                "score": { "type": "number" },
                "enabled": { "type": "boolean" }
              }
            }
            """.trimIndent(),
        )

        val generated = MockDataGenerator.fromSchema(schema, components).asJsonObject
        assertEquals(42, generated.get("id").asInt)
        assertEquals("user@example.com", generated.get("email").asString)
        assertEquals("active", generated.get("status").asString)
        assertEquals("string", generated.getAsJsonObject("owner").get("name").asString)
        assertEquals(2, generated.getAsJsonArray("tags").size())
        assertEquals(3.14, generated.get("score").asDouble)
        assertTrue(generated.get("enabled").asBoolean)
    }

    @Test
    fun `prefers example values over generated ones`() {
        val schema = JsonParser.parseString(
            """{ "type": "string", "example": "fixed" }""",
        ).asJsonObject
        assertEquals("fixed", MockDataGenerator.fromSchema(schema, null).asString)
    }

    @Test
    fun `undocumented get collections get an array of sample objects`() {
        val body = MockDataGenerator.generatedBody("List orders", "GET")
        val parsed = JsonParser.parseString(body)
        assertTrue(parsed.isJsonArray)
        assertEquals(2, parsed.asJsonArray.size())
        val first = parsed.asJsonArray[0].asJsonObject
        assertTrue(first.has("id"))
        assertTrue(first.has("name"))
    }

    @Test
    fun `undocumented write endpoints get a single object`() {
        val parsed = JsonParser.parseString(MockDataGenerator.generatedBody("CreateOrder", "POST"))
        assertTrue(parsed.isJsonObject)
        val obj: JsonObject = parsed.asJsonObject
        assertEquals(1, obj.get("id").asInt)
        assertTrue(obj.get("name").asString.contains("CreateOrder"))
    }
}
