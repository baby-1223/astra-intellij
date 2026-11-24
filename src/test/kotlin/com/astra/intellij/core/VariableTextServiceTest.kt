package com.astra.intellij.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VariableTextServiceTest {
    @Test
    fun `parses dot env with export quotes and comments`() {
        val result = VariableTextService.parse(
            """
            # comment
            BASE_URL=https://api.example.com
            export TOKEN="abc 123"
            SINGLE='value=with=equals'
            
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "BASE_URL" to "https://api.example.com",
                "TOKEN" to "abc 123",
                "SINGLE" to "value=with=equals",
            ),
            result,
        )
    }

    @Test
    fun `parses csv with header and quoted fields`() {
        val result = VariableTextService.parse(
            "key,value\nBASE_URL,https://api.example.com\nNOTE,\"hello, world\"\n",
        )
        assertEquals(
            listOf(
                "BASE_URL" to "https://api.example.com",
                "NOTE" to "hello, world",
            ),
            result,
        )
    }

    @Test
    fun `csv without header keeps first row`() {
        val result = VariableTextService.parse("A,1\nB,2")
        assertEquals(listOf("A" to "1", "B" to "2"), result)
    }

    @Test
    fun `renders dot env and quotes values with spaces`() {
        val text = VariableTextService.toDotEnv(linkedMapOf("A" to "1", "B" to "hello world", "C" to ""))
        assertEquals("A=1\nB=\"hello world\"\nC=\n", text)
    }

    @Test
    fun `skips blank lines and empty keys`() {
        assertEquals(emptyList<Pair<String, String>>(), VariableTextService.parse("\n\n# only comments\n"))
        assertEquals(emptyList<Pair<String, String>>(), VariableTextService.parse("=value"))
    }
}
