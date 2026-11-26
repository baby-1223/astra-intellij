package com.astra.intellij.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpringControllerParserTest {
    private val javaController = """
        package demo;

        @RestController
        @RequestMapping("/api")
        public class UserController {
            @GetMapping("/users/{id}")
            public ResponseEntity<UserDto> getUser(
                    @PathVariable("id") Long id,
                    @RequestParam("expand") String expand,
                    @RequestHeader("X-Tenant") String tenant) {
                return null;
            }

            @PostMapping(path = "/users", consumes = "application/json")
            public UserDto createUser(@RequestBody UserDto user) {
                return user;
            }

            @RequestMapping(value = "/legacy", method = RequestMethod.DELETE)
            public void legacy() {
            }
        }

        class UserDto {
            private String name;
            private int age;
            private boolean active;
        }
    """.trimIndent()

    @Test
    fun `reads java mappings, parameters and dto bodies`() {
        assertTrue(SpringControllerParser.looksLikeController(javaController))
        val spec = SpringControllerParser.parse(javaController, "UserController")
        assertNotNull(spec)
        assertEquals("UserController", spec!!.className)
        assertEquals("/api", spec.basePath)
        assertEquals(3, spec.endpoints.size)

        val get = spec.endpoints[0]
        assertEquals("getUser", get.name)
        assertEquals("GET", get.method)
        assertEquals("/api/users/0", get.path)
        assertTrue(get.query.contains("expand" to "string"))
        assertTrue(get.headers.contains("X-Tenant" to ""))

        val create = spec.endpoints[1]
        assertEquals("POST", create.method)
        assertEquals("/api/users", create.path)
        assertNotNull(create.body)
        assertTrue(create.body!!.contains("\"name\": \"string\""))
        assertTrue(create.body.contains("\"age\": 0"))
        assertTrue(create.body.contains("\"active\": true"))

        val legacy = spec.endpoints[2]
        assertEquals("DELETE", legacy.method)
        assertEquals("/api/legacy", legacy.path)
    }

    @Test
    fun `reads kotlin mappings and request param defaults`() {
        val source = """
            @RestController
            @RequestMapping(path = ["/v1"])
            class OrderController {
                @GetMapping("/orders")
                fun list(@RequestParam(defaultValue = "10") size: Int): List<String> = emptyList()

                @GetMapping("/orders/{id}")
                fun get(@PathVariable id: String): String = ""
            }
        """.trimIndent()

        val spec = SpringControllerParser.parse(source, "OrderController")
        assertNotNull(spec)
        assertEquals("/v1", spec!!.basePath)
        assertEquals(2, spec.endpoints.size)
        assertEquals("/v1/orders", spec.endpoints[0].path)
        assertEquals(listOf("size" to "10"), spec.endpoints[0].query)
        assertEquals("/v1/orders/string", spec.endpoints[1].path)
    }

    @Test
    fun `renders generic request bodies instead of empty objects`() {
        val source = """
            package demo;

            @RestController
            @RequestMapping("/api")
            public class GenericController {
                @PostMapping("/wrapper")
                public void wrapper(@RequestBody Result<UserDto> body) {
                }

                @PostMapping("/list")
                public void list(@RequestBody List<UserDto> body) {
                }

                @PostMapping("/map")
                public void map(@RequestBody Map<String, UserDto> body) {
                }

                @PostMapping("/text")
                public void text(@RequestBody String body) {
                }
            }

            class Result<T> {
                private T data;
                private String message;
            }

            class UserDto {
                private String name;
                private List<Map<String, Integer>> scores;
            }
        """.trimIndent()

        val spec = SpringControllerParser.parse(source, "GenericController")
        assertNotNull(spec)
        val bodies = spec!!.endpoints.associate { it.name to it.body.orEmpty() }

        val wrapper = bodies.getValue("wrapper")
        assertTrue(wrapper.contains("\"data\""), wrapper)
        assertTrue(wrapper.contains("\"name\": \"string\""), wrapper)
        assertTrue(wrapper.contains("\"scores\""), wrapper)

        val list = bodies.getValue("list")
        assertTrue(list.trimStart().startsWith("["), list)
        assertTrue(list.contains("\"name\": \"string\""), list)

        val map = bodies.getValue("map")
        assertTrue(map.contains("\"key\""), map)
        assertTrue(map.contains("\"name\": \"string\""), map)

        assertEquals("\"string\"", bodies.getValue("text"))
    }

    @Test
    fun `reads kotlin constructor generics and type variables`() {
        val source = """
            @RestController
            @RequestMapping("/kt")
            class KotlinController {
                @PostMapping("/page")
                fun page(@RequestBody body: PageResult<User>): Unit = Unit
            }

            data class PageResult<T>(
                val total: Int,
                val items: List<T>,
                val cursor: String?
            )

            data class User(val id: Long, val nickname: String)
        """.trimIndent()

        val spec = SpringControllerParser.parse(source, "KotlinController")
        assertNotNull(spec)
        val body = spec!!.endpoints.single().body.orEmpty()

        assertTrue(body.contains("\"total\": 0"), body)
        assertTrue(body.contains("\"cursor\": \"string\""), body)
        assertTrue(body.contains("\"nickname\": \"string\""), body)
        assertTrue(body.contains("\"id\": 0"), body)
    }

    @Test
    fun `builds a collection that uses the environment base url`() {
        val spec = SpringControllerParser.parse(javaController, "UserController")!!
        val collection = SpringControllerParser.toCollection(spec)

        assertEquals("UserController", collection.name)
        assertEquals(3, collection.requests.size)
        val create = collection.requests[1]
        assertEquals("{{baseUrl}}/api/users", create.url)
        assertEquals("json", create.bodyMode)
        assertTrue(create.headers.any { it.key == "Content-Type" && it.value == "application/json" })
    }

    @Test
    fun `ignores plain classes without controller annotations`() {
        assertFalse(SpringControllerParser.looksLikeController("class Plain { private String name; }"))
        assertEquals(null, SpringControllerParser.parse("class Plain { private String name; }"))
    }
}
