package com.astra.intellij.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpringControllerParserAdvancedTest {
    @Test
    fun `supports fully qualified annotations, named paths, arrays and constants`() {
        val source = """
            package demo;

            public class ApiPaths {
                public static final String BASE = "/api";
            }

            @org.springframework.web.bind.annotation.RestController
            @org.springframework.web.bind.annotation.RequestMapping(ApiPaths.BASE)
            public class FqController {
                @org.springframework.web.bind.annotation.PostMapping(consumes = "application/json", path = {"/a", "/b"})
                public void create(@org.springframework.web.bind.annotation.RequestBody User user) {
                }
            }

            class User {
                private String name;
            }
        """.trimIndent()

        val spec = SpringControllerParser.parse(source, "FqController.java")
        assertNotNull(spec)
        assertEquals("/api", spec!!.basePath)
        assertEquals(2, spec.endpoints.size)
        assertEquals(setOf("/api/a", "/api/b"), spec.endpoints.map { it.path }.toSet())
        assertTrue(spec.endpoints.all { it.method == "POST" })
        assertTrue(spec.endpoints.first().body.orEmpty().contains("\"name\": \"string\""))
    }

    @Test
    fun `resolves records, json names, enums, non private fields, records and inheritance across files`() {
        val controller = """
            @RestController
            @RequestMapping("/profiles")
            class ProfileController {
                @PostMapping("/save")
                fun save(@RequestBody profile: Profile): Unit = Unit

                @PostMapping("/wrapped")
                fun wrapped(@RequestBody result: Result<Profile>): Unit = Unit
            }
        """.trimIndent()
        val sources = listOf(
            SpringSource(
                "Profile.kt",
                """
                data class Profile(
                    @JsonProperty("user_name") val userName: String,
                    val role: Role,
                    val tags: List<Tag>
                ) : BaseProfile()
                """.trimIndent(),
            ),
            SpringSource(
                "BaseProfile.kt",
                """
                open class BaseProfile {
                    @JvmField
                    public val id: Long = 0
                }
                """.trimIndent(),
            ),
            SpringSource(
                "Tag.java",
                """
                class Tag {
                    public String label;
                }
                """.trimIndent(),
            ),
            SpringSource("Role.kt", "enum class Role { ADMIN, USER }"),
            SpringSource("Result.java", "class Result<T> { private T data; private String message; }"),
        )

        val spec = SpringControllerParser.parse(controller, "ProfileController.kt", sources)
        assertNotNull(spec)
        val save = spec!!.endpoints.first { it.name == "save" }.body.orEmpty()
        assertTrue(save.contains("\"user_name\": \"string\""), save)
        assertTrue(save.contains("\"role\": \"ADMIN\""), save)
        assertTrue(save.contains("\"label\": \"string\""), save)
        assertTrue(save.contains("\"id\": 0"), save)

        val wrapped = spec.endpoints.first { it.name == "wrapped" }.body.orEmpty()
        assertTrue(wrapped.contains("\"data\""), wrapped)
        assertTrue(wrapped.contains("\"user_name\""), wrapped)
    }

    @Test
    fun `generates multipart, urlencoded, xml and plain text bodies`() {
        val source = """
            @RestController
            @RequestMapping("/files")
            public class FileController {
                @PostMapping(value = "/upload", consumes = "multipart/form-data")
                public void upload(@RequestPart("file") MultipartFile file,
                                   @RequestPart("meta") Meta meta) {
                }

                @PostMapping(value = "/form", consumes = "application/x-www-form-urlencoded")
                public void form(@RequestBody Meta meta) {
                }

                @PostMapping(value = "/xml", consumes = "application/xml")
                public void xml(@RequestBody Meta meta) {
                }

                @PostMapping(value = "/plain")
                public void plain(@RequestBody String text) {
                }
            }

            class Meta {
                private String name;
                private int age;
            }
        """.trimIndent()

        val spec = SpringControllerParser.parse(source, "FileController.java")
        assertNotNull(spec)
        val endpoints = spec!!.endpoints.associateBy { it.path }

        val upload = endpoints.getValue("/files/upload")
        assertEquals("form-data", upload.bodyMode)
        assertTrue(upload.formFields.any { it.name == "file" && it.file })
        assertTrue(upload.formFields.any { it.name == "meta" && !it.file })

        val form = endpoints.getValue("/files/form")
        assertEquals("urlencoded", form.bodyMode)
        assertTrue(form.formFields.any { it.name == "name" && it.value == "string" })
        assertTrue(form.formFields.any { it.name == "age" && it.value == "0" })

        val xml = endpoints.getValue("/files/xml")
        assertEquals("text", xml.bodyMode)
        assertTrue(xml.body.orEmpty().contains("<Meta>"), xml.body.orEmpty())
        assertTrue(xml.body.orEmpty().contains("<name>string</name>"), xml.body.orEmpty())

        val plain = endpoints.getValue("/files/plain")
        assertEquals("text", plain.bodyMode)
        assertEquals("string", plain.body)
        assertEquals("text/plain", plain.contentType)
    }

    @Test
    fun `expands model attributes, merges cookies, skips maps and keeps the first request body`() {
        val source = """
            @RestController
            public class SearchController {
                @GetMapping("/search")
                public void search(@ModelAttribute Filter filter,
                                   @RequestParam Map<String, String> all,
                                   @CookieValue("sid") String sid,
                                   @CookieValue("theme") String theme) {
                }

                @PostMapping("/two")
                public void two(@RequestBody First first, @RequestBody Second second) {
                }
            }

            class Filter {
                private String keyword;
                private int page;
            }

            class First {
                private String first;
            }

            class Second {
                private String second;
            }
        """.trimIndent()

        val spec = SpringControllerParser.parse(source, "SearchController.java")
        assertNotNull(spec)
        val search = spec!!.endpoints.first { it.name == "search" }
        assertTrue(search.query.contains("keyword" to "string"))
        assertTrue(search.query.contains("page" to "0"))
        assertFalse(search.query.any { it.first == "all" })
        assertEquals(1, search.headers.count { it.first == "Cookie" })
        assertEquals("sid=string; theme=string", search.headers.first { it.first == "Cookie" }.second)

        val two = spec.endpoints.first { it.name == "two" }
        assertTrue(two.body.orEmpty().contains("\"first\""), two.body.orEmpty())
        assertFalse(two.body.orEmpty().contains("\"second\""))
    }

    @Test
    fun `ignores commented mappings, nested controllers and braces inside strings`() {
        val source = """
            @RestController
            class CommentController {
                /**
                 * @GetMapping("/ghost")
                 */
                // @PostMapping("/also-ghost")
                @GetMapping("/real")
                fun real(): String = "body { with } braces"

                class Nested {
                    @GetMapping("/nested")
                    fun nested(): String = ""
                }
            }
        """.trimIndent()

        val spec = SpringControllerParser.parse(source, "CommentController.kt")
        assertNotNull(spec)
        assertEquals(listOf("/real"), spec!!.endpoints.map { it.path })
    }

    @Test
    fun `parses every controller in a file and deduplicates overloaded request names`() {
        val source = """
            @RestController
            @RequestMapping("/a")
            class AController {
                @GetMapping("/x") public void x() {}
                @GetMapping("/y") public void x(int page) {}
            }

            @RestController
            @RequestMapping("/b")
            class BController {
                @GetMapping("/z") public void z() {}
            }
        """.trimIndent()

        val specs = SpringControllerParser.parseAll(source, "Controllers.java")
        assertEquals(2, specs.size)
        assertEquals(setOf("AController", "BController"), specs.map { it.className }.toSet())

        val collection = SpringControllerParser.toCollection(specs.first())
        assertEquals(listOf("x", "x (2)"), collection.requests.map { it.name })
    }

    @Test
    fun `inherits the class level path and supports controller interfaces`() {
        val base = """
            @RequestMapping("/base")
            open class BaseController
        """.trimIndent()
        val controller = """
            @RestController
            class ChildController : BaseController() {
                @GetMapping("/child")
                fun child(): String = ""
            }
        """.trimIndent()

        val spec = SpringControllerParser.parse(
            controller,
            "ChildController.kt",
            listOf(SpringSource("BaseController.kt", base)),
        )
        assertNotNull(spec)
        assertEquals("/base/child", spec!!.endpoints.single().path)

        val api = """
            @RestController
            @RequestMapping("/api")
            interface UserApi {
                @GetMapping("/users/{id}")
                fun user(@PathVariable id: String): String
            }
        """.trimIndent()
        val impl = """
            class UserApiImpl : UserApi {
                override fun user(id: String): String = ""
            }
        """.trimIndent()

        val fromImpl = SpringControllerParser.parse(
            impl,
            "UserApiImpl.kt",
            listOf(SpringSource("UserApi.kt", api)),
        )
        assertNotNull(fromImpl)
        assertEquals("/api/users/{{id}}", fromImpl!!.endpoints.single().path)
        assertEquals("UserApiImpl", fromImpl.className)
    }

    @Test
    fun `supports composed mapping annotations and java records`() {
        val source = """
            package demo;

            import java.lang.annotation.*;

            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.RUNTIME)
            @PostMapping
            public @interface CreateUser {
            }

            @RestController
            @RequestMapping("/records")
            public class RecordController {
                @CreateUser("/users")
                public void create(@RequestBody User user) {
                }
            }

            record User(@JsonProperty("user_name") String userName, List<String> tags) {
            }
        """.trimIndent()

        val spec = SpringControllerParser.parse(source, "RecordController.java")
        assertNotNull(spec)
        val endpoint = spec!!.endpoints.single()
        assertEquals("POST", endpoint.method)
        assertEquals("/records/users", endpoint.path)
        val body = endpoint.body.orEmpty()
        assertTrue(body.contains("\"user_name\": \"string\""), body)
        assertTrue(body.contains("\"tags\""), body)
    }

    @Test
    fun `handles raw strings and prevents infinite recursion`() {
        val source = """
            @RestController
            class RawController {
                @GetMapping("/raw")
                fun raw(): String = ""${'"'}
                    { not a body, @GetMapping("/ghost") }
                ""${'"'}

                @PostMapping("/node")
                fun node(@RequestBody node: Node): Unit = Unit
            }

            class Node(
                private val parent: Node?,
                private val name: String,
            )
        """.trimIndent()

        val spec = SpringControllerParser.parse(source, "RawController.kt")
        assertNotNull(spec)
        assertEquals(listOf("/raw", "/node"), spec!!.endpoints.map { it.path })
        val node = spec.endpoints.first { it.name == "node" }.body.orEmpty()
        assertTrue(node.contains("\"name\": \"string\""), node)
        assertTrue(node.contains("\"parent\": {}"), node)
    }

    @Test
    fun `declares path variables without hardcoding sample values`() {
        val source = """
            @RestController
            class PathController {
                @GetMapping("/users/{id}/orders/{orderId}")
                public void orders(@PathVariable Long id, @PathVariable("orderId") String orderId) {
                }
            }
        """.trimIndent()

        val endpoint = SpringControllerParser.parse(source, "PathController.java")!!.endpoints.single()
        assertEquals("/users/{{id}}/orders/{{orderId}}", endpoint.path)
        assertEquals(listOf("id" to "0", "orderId" to "string"), endpoint.pathVariables)
    }
}
