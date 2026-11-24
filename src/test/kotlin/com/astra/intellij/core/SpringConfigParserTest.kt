package com.astra.intellij.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpringConfigParserTest {
    @Test
    fun `flattens nested yaml and derives the base url`() {
        val yaml = """
            server:
              port: 9090
              servlet:
                context-path: /shop
            spring:
              datasource:
                url: jdbc:postgresql://localhost/db
                username: ${'$'}{DB_USER:astra}
            api:
              token: abc
        """.trimIndent()

        val properties = SpringConfigParser.parse(yaml, "application.yml")
        assertEquals("9090", properties["server.port"])
        assertEquals("/shop", properties["server.servlet.context-path"])
        assertEquals("jdbc:postgresql://localhost/db", properties["spring.datasource.url"])
        assertEquals("abc", properties["api.token"])
        assertEquals("http://localhost:9090/shop", SpringConfigParser.baseUrl(properties))

        val variables = SpringConfigParser.variables(properties)
        assertEquals("astra", variables["spring.datasource.username"])
        assertEquals("http://localhost:9090/shop", variables["baseUrl"])
    }

    @Test
    fun `parses properties files and keeps comments out`() {
        val properties = SpringConfigParser.parse(
            """
            # server settings
            server.port = 8081
            server.servlet.context-path=/api
            ! legacy comment
            app.name=Astra
            """.trimIndent(),
            "application.properties",
        )

        assertEquals("8081", properties["server.port"])
        assertEquals("/api", properties["server.servlet.context-path"])
        assertEquals("Astra", properties["app.name"])
        assertEquals(3, properties.size)
        assertEquals("http://localhost:8081/api", SpringConfigParser.baseUrl(properties))
    }

    @Test
    fun `defaults to spring boot port when no port is configured`() {
        assertEquals("http://localhost:8080", SpringConfigParser.baseUrl(emptyMap()))
        assertNull(SpringConfigParser.baseUrl(emptyMap())?.takeIf { it.contains("null") })
    }

    @Test
    fun `creates an environment with all variables`() {
        val environment = SpringConfigParser.environment(
            mapOf("server.port" to "7000", "spring.application.name" to "demo"),
            "Spring application.yml",
        )
        assertEquals("Spring application.yml", environment.name)
        assertEquals("7000", environment.variables["server.port"])
        assertEquals("demo", environment.variables["spring.application.name"])
        assertEquals("http://localhost:7000", environment.variables["baseUrl"])
    }
}
