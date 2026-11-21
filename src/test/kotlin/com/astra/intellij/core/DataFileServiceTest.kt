package com.astra.intellij.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class DataFileServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parses csv rows into variable maps`() {
        val file = write("data.csv", "name,value\nalpha,1\nbeta,2")

        val rows = DataFileService.parse(file)

        assertEquals(listOf(mapOf("name" to "alpha", "value" to "1"), mapOf("name" to "beta", "value" to "2")), rows)
    }

    @Test
    fun `parses quoted csv fields`() {
        val file = write("quoted.csv", "greeting,note\n\"hello, world\",\"line1\nline2\"\n\"she said \"\"hi\"\"\",done")

        val rows = DataFileService.parse(file)

        assertEquals("hello, world", rows[0]["greeting"])
        assertEquals("line1\nline2", rows[0]["note"])
        assertEquals("she said \"hi\"", rows[1]["greeting"])
        assertEquals("done", rows[1]["note"])
    }

    @Test
    fun `parses json object array into variable maps`() {
        val file = write("data.json", "[{\"name\":\"alpha\",\"value\":1},{\"name\":\"beta\",\"value\":2}]")

        val rows = DataFileService.parse(file)

        assertEquals(listOf(mapOf("name" to "alpha", "value" to "1"), mapOf("name" to "beta", "value" to "2")), rows)
    }

    @Test
    fun `rejects empty csv`() {
        val file = write("empty.csv", "")

        val error = assertThrows(IllegalArgumentException::class.java) { DataFileService.parse(file) }

        assertEquals("CSV file is empty", error.message)
    }

    @Test
    fun `rejects csv with empty header column`() {
        val file = write("bad.csv", "name,\nalpha,1")

        val error = assertThrows(IllegalArgumentException::class.java) { DataFileService.parse(file) }

        assertEquals("CSV header contains an empty column name", error.message)
    }

    @Test
    fun `rejects json that is not an array`() {
        val file = write("object.json", "{\"name\":\"alpha\"}")

        val error = assertThrows(IllegalArgumentException::class.java) { DataFileService.parse(file) }

        assertEquals("JSON data file must be an array of objects", error.message)
    }

    @Test
    fun `rejects json array with non object row`() {
        val file = write("bad.json", "[\"alpha\"]")

        val error = assertThrows(IllegalArgumentException::class.java) { DataFileService.parse(file) }

        assertEquals("JSON row 1 must be an object", error.message)
    }

    @Test
    fun `rejects unsupported file extension`() {
        val file = write("data.txt", "alpha,1")

        val error = assertThrows(IllegalArgumentException::class.java) { DataFileService.parse(file) }

        assertEquals("Unsupported data file type. Choose a .csv or .json file.", error.message)
    }

    private fun write(name: String, content: String): File {
        val file = tempDir.resolve(name).toFile()
        file.writeText(content)
        return file
    }
}
