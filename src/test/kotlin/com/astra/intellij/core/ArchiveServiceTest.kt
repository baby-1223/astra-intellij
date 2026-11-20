package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.KeyValue
import com.astra.intellij.model.RequestCollection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchiveServiceTest {
    private val service = ArchiveService()

    @Test
    fun `export and import round trip`() {
        val collection = RequestCollection(name = "Orders").apply {
            requests.add(AstraRequest(name = "List", method = "GET", url = "https://api.test/orders").apply {
                assertions.add("status 200")
                formFields.add(KeyValue(key = "a", value = "b"))
            })
        }
        val environment = AstraEnvironment(name = "Prod").apply {
            variables["host"] = "api.test"
        }
        val json = service.export(listOf(collection), listOf(environment), environment.id)
        assertTrue(json.contains("astra.workspace"))

        val imported = service.import(json)
        assertEquals(1, imported.collections!!.size)
        assertEquals("Orders", imported.collections!!.first().name)
        assertEquals("status 200", imported.collections!!.first().requests.first().assertions.single())
        assertEquals("a", imported.collections!!.first().requests.first().formFields.single().key)
        assertEquals("Prod", imported.environments!!.single().name)
        assertEquals(environment.id, imported.activeEnvironmentId)
    }

    @Test
    fun `rejects non astra json`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.import("""{"info":{"name":"postman"}}""")
        }
    }
}
