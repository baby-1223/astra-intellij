package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.RequestFolder
import com.astra.intellij.model.allFolders
import com.astra.intellij.model.allRequests
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NestedFolderTest {
    @Test
    fun `model helpers traverse nested folders`() {
        val collection = RequestCollection(name = "C")
        val level1 = RequestFolder(name = "L1")
        val level2 = RequestFolder(name = "L2")
        val level3 = RequestFolder(name = "L3")
        level3.requests.add(AstraRequest(name = "deep"))
        level2.folders.add(level3)
        level2.requests.add(AstraRequest(name = "mid"))
        level1.folders.add(level2)
        collection.folders.add(level1)
        collection.requests.add(AstraRequest(name = "root"))

        assertEquals(listOf("L1", "L2", "L3"), collection.allFolders().map { it.name })
        assertEquals(listOf("root", "mid", "deep"), collection.allRequests().map { it.name })
    }

    @Test
    fun `postman import keeps nested folder hierarchy`() {
        val json = """
            {"info":{"name":"Nested","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
             "item":[{"name":"L1","item":[
                {"name":"L2","item":[{"name":"Deep","request":{"method":"GET","url":"https://x.test/deep"}}]},
                {"name":"Mid","request":{"method":"POST","url":"https://x.test/mid"}}
             ]}]}
        """.trimIndent()

        val collection = PostmanService().parseCollection(json)

        assertEquals(1, collection.folders.size)
        val level1 = collection.folders.single()
        assertEquals("L1", level1.name)
        assertEquals(listOf("Mid"), level1.requests.map { it.name })
        val level2 = level1.folders.single()
        assertEquals("L2", level2.name)
        assertEquals(listOf("Deep"), level2.requests.map { it.name })
    }

    @Test
    fun `postman export writes nested items recursively`() {
        val collection = RequestCollection(name = "Nested")
        val level1 = RequestFolder(name = "L1")
        val level2 = RequestFolder(name = "L2")
        level2.requests.add(AstraRequest(name = "Deep", method = "GET", url = "https://x.test/deep"))
        level1.folders.add(level2)
        collection.folders.add(level1)

        val exported = PostmanService().exportCollection(listOf(collection), "Nested")
        val root = JsonParser.parseString(exported).asJsonObject
        val exportedLevel1 = root.getAsJsonArray("item").single().asJsonObject
        val exportedLevel2 = exportedLevel1.getAsJsonArray("item").single().asJsonObject
        assertEquals("L1", exportedLevel1.get("name").asString)
        assertEquals("L2", exportedLevel2.get("name").asString)
        assertEquals("Deep", exportedLevel2.getAsJsonArray("item").single().asJsonObject.get("name").asString)

        val reparsed = PostmanService().parseCollection(exported)
        assertEquals("Deep", reparsed.folders.single().folders.single().requests.single().name)
    }
}
