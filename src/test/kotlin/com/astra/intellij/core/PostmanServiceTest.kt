package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AuthConfig
import com.astra.intellij.model.KeyValue
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.RequestFolder
import com.astra.intellij.model.containsRequest
import com.astra.intellij.model.folderPathTo
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PostmanServiceTest {
    private val service = PostmanService()

    @Test
    fun `parses collection folders requests headers body and auth`() {
        val collection = service.parseCollection(COLLECTION)

        assertEquals("Demo", collection.name)
        assertEquals(1, collection.folders.size)
        assertEquals(1, collection.requests.size)
        assertEquals("Ping", collection.requests[0].name)
        assertEquals("GET", collection.requests[0].method)

        val folder = collection.folders[0]
        assertEquals("Auth", folder.name)
        val login = folder.requests[0]
        assertEquals("Login", login.name)
        assertEquals("POST", login.method)
        assertEquals("https://api.example.com/users/{{id}}?page=2", login.url)
        assertEquals(2, login.headers.size)
        assertFalse(login.headers[1].enabled)
        assertEquals("json", login.bodyMode)
        assertEquals("{\"a\":1}", login.body)
        assertEquals("bearer", login.auth.type)
        assertEquals("{{token}}", login.auth.token)
        assertEquals(1, login.params.size)
        assertEquals("page", login.params[0].key)
        assertEquals("2", login.params[0].value)
    }

    @Test
    fun `exports a collection that can be parsed back`() {
        val original = RequestCollection(
            name = "Round Trip",
            folders = mutableListOf(
                RequestFolder(
                    name = "Users",
                    requests = mutableListOf(
                        AstraRequest(
                            name = "Create User",
                            method = "POST",
                            url = "https://api.example.com/users",
                            params = mutableListOf(KeyValue(key = "verbose", value = "true")),
                            headers = mutableListOf(KeyValue(key = "X-Team", value = "astra")),
                            bodyMode = "json",
                            body = "{\"name\":\"Ada\"}",
                            auth = AuthConfig(type = "bearer", token = "{{token}}"),
                        ),
                    ),
                ),
            ),
        )

        val parsed = service.parseCollection(service.exportCollection(listOf(original), "Round Trip"))

        assertEquals("Round Trip", parsed.name)
        val request = parsed.folders[0].requests[0]
        assertEquals("Create User", request.name)
        assertEquals("POST", request.method)
        assertEquals("https://api.example.com/users?verbose=true", request.url)
        assertEquals("json", request.bodyMode)
        assertEquals("{\"name\":\"Ada\"}", request.body)
        assertEquals("bearer", request.auth.type)
        assertTrue(request.headers.any { it.key == "X-Team" && it.value == "astra" })
    }

    @Test
    fun `parses and exports postman environment json`() {
        val environment = service.parseEnvironment(
            """
            {
              "name": "Dev",
              "values": [
                {"key": "baseUrl", "value": "https://api.example.com", "enabled": true},
                {"key": "token", "value": "abc", "enabled": true},
                {"key": "ignored", "value": "x", "enabled": false}
              ]
            }
            """.trimIndent(),
        )

        requireNotNull(environment)
        assertEquals("Dev", environment.name)
        assertEquals("https://api.example.com", environment.variables["baseUrl"])
        assertFalse(environment.variables.containsKey("ignored"))

        val exported = JsonParser.parseString(service.exportEnvironment(environment)).asJsonObject
        assertEquals("environment", exported.get("_postman_variable_scope").asString)
        assertEquals(2, exported.getAsJsonArray("values").size())

        val roundTrip = requireNotNull(service.parseEnvironment(service.exportEnvironment(environment)))
        assertEquals(environment.variables, roundTrip.variables)
    }

    @Test
    fun `imports an environment with no variables as empty environment`() {
        val environment = service.parseEnvironment("""{"name":"Empty","values":[]}""")

        assertEquals(AstraEnvironment(name = "Empty").variables, requireNotNull(environment).variables)
        assertEquals("Empty", environment.name)
    }

    @Test
    fun `round trips a zip data dump`() {
        val collection = RequestCollection(
            name = "Demo",
            requests = mutableListOf(AstraRequest(name = "Ping", url = "https://api.test/ping")),
        )
        val environment = AstraEnvironment(name = "Dev", variables = mutableMapOf("baseUrl" to "https://api.test"))

        val zip = service.exportDataDump(listOf(collection), listOf(environment))
        val parsed = service.parseDataDump(zip)

        assertEquals(1, parsed.collections.size)
        assertEquals("Demo", parsed.collections.single().name)
        assertEquals("Ping", parsed.collections.single().requests.single().name)
        assertEquals(1, parsed.environments.size)
        assertEquals("Dev", parsed.environments.single().name)
        assertEquals("https://api.test", parsed.environments.single().variables["baseUrl"])
    }

    @Test
    fun `imports and exports postman pre-request scripts`() {
        val payload = """
            {
              "info": {"name": "Scripts"},
              "item": [
                {
                  "name": "Login",
                  "request": {"method": "POST", "url": "https://api.test/login"},
                  "event": [
                    {
                      "listen": "prerequest",
                      "script": {
                        "type": "text/javascript",
                        "exec": [
                          "pm.sendRequest('https://api.test/token', function (err, res) {",
                          "  pm.environment.set('token', res.json().token);",
                          "});"
                        ]
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val collection = service.parseCollection(payload)
        assertEquals(3, collection.requests.single().preRequest.size)
        assertTrue(collection.requests.single().preRequest.first().startsWith("pm.sendRequest"))

        val exported = service.parseCollection(service.exportCollection(listOf(collection), "Scripts"))
        assertEquals(collection.requests.single().preRequest, exported.requests.single().preRequest)
    }

    @Test
    fun `imports a json array of collections and environments`() {
        val payload = """
            [
              {"info": {"name": "A"}, "item": [{"name": "Ping", "request": "https://api.test/ping"}]},
              {"name": "E", "values": [{"key": "k", "value": "v"}]}
            ]
        """.trimIndent()

        val parsed = service.parseDataDump(payload.toByteArray())

        assertEquals("A", parsed.collections.single().name)
        assertEquals("E", parsed.environments.single().name)
        assertEquals("v", parsed.environments.single().variables["k"])
    }

    @Test
    fun `imports a legacy data dump with all collections environments and globals`() {
        val parsed = service.parseDataDump(LEGACY_DUMP.toByteArray())

        assertEquals(1, parsed.collections.size)
        val collection = parsed.collections.single()
        assertEquals("Team API", collection.name)
        assertEquals("Ping", collection.requests.single().name)

        val folder = collection.folders.single()
        assertEquals("Auth", folder.name)
        val login = folder.requests.single()
        assertEquals("Login", login.name)
        assertEquals("POST", login.method)
        assertEquals("https://api.test/login", login.url)
        assertEquals(2, login.headers.size)
        assertEquals("astra", login.headers.first { it.key == "X-Team" }.value)
        assertEquals("json", login.bodyMode)
        assertEquals("{\"user\":\"ada\"}", login.body)
        assertEquals("bearer", login.auth.type)
        assertEquals("{{token}}", login.auth.token)
        assertEquals("pm.environment.set('a','b');", login.preRequest.single())

        assertEquals("Dev", parsed.environments.single().name)
        assertEquals("https://api.test", parsed.environments.single().variables["baseUrl"])
        assertEquals(mapOf("g" to "1"), parsed.globals)
    }

    @Test
    fun `imports a legacy data dump stored inside a zip`() {
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("Backup.postman_dump.json"))
            zip.write(LEGACY_DUMP.toByteArray())
            zip.closeEntry()
        }

        val parsed = service.parseDataDump(output.toByteArray())

        assertEquals("Team API", parsed.collections.single().name)
        assertEquals("Dev", parsed.environments.single().name)
    }

    @Test
    fun `legacy dump places requests under the folder referenced by id`() {
        val json = """
            {
              "version": 1,
              "collections": [{
                "id": "c1",
                "name": "Flat",
                "folders": [
                  {"id": "f1", "name": "Auth", "order": ["r1", "r2"], "folders": []},
                  {"id": "f2", "name": "Nested", "order": [], "folders": [
                    {"id": "f3", "name": "Deep", "order": ["r3"], "folders": []}
                  ]}
                ],
                "requests": [
                  {"id": "r1", "name": "Login", "folder": "f1", "method": "POST", "url": "https://x/login", "headers": ""},
                  {"id": "r2", "name": "Token", "folder": "f1", "method": "POST", "url": "https://x/token", "headers": ""},
                  {"id": "r3", "name": "Ping", "folder": "f3", "method": "GET", "url": "https://x/ping", "headers": ""},
                  {"id": "r4", "name": "Health", "method": "GET", "url": "https://x/health", "headers": ""}
                ],
                "order": ["f1", "f2", "r4"]
              }]
            }
        """.trimIndent()

        val collection = service.parseDataDump(json.toByteArray()).collections.single()

        val auth = collection.folders.first { it.name == "Auth" }
        assertEquals(listOf("Login", "Token"), auth.requests.map { it.name })
        val nested = collection.folders.first { it.name == "Nested" }
        assertEquals("Deep", nested.folders.single().name)
        assertEquals(listOf("Ping"), nested.folders.single().requests.map { it.name })
        assertEquals(listOf("Health"), collection.requests.map { it.name })
    }

    @Test
    fun `legacy dump nests flat folders and uses order for membership`() {
        val json = """
            {
              "version": 1,
              "collections": [{
                "name": "Flat",
                "folders": [
                  {"id": "f1", "name": "A", "order": ["f2", "r1"]},
                  {"id": "f2", "name": "B", "folder": "f1", "order": ["r2"]}
                ],
                "requests": [
                  {"id": "r1", "name": "One", "method": "GET", "url": "https://x/1", "headers": ""},
                  {"id": "r2", "name": "Two", "method": "GET", "url": "https://x/2", "headers": ""},
                  {"id": "r3", "name": "Root", "method": "GET", "url": "https://x/3", "headers": ""}
                ],
                "order": ["f1", "r3"]
              }]
            }
        """.trimIndent()

        val collection = service.parseDataDump(json.toByteArray()).collections.single()

        val a = collection.folders.single()
        assertEquals("A", a.name)
        val b = a.folders.single()
        assertEquals("B", b.name)
        assertEquals(listOf("One"), a.requests.map { it.name })
        assertEquals(listOf("Two"), b.requests.map { it.name })
        assertEquals(listOf("Root"), collection.requests.map { it.name })
    }

    @Test
    fun `unrelated json in a dump does not create an empty collection`() {
        val parsed = service.parseDataDump("""{"version":1,"workspaces":[{"name":"Team"}]}""".toByteArray())

        assertTrue(parsed.collections.isEmpty())
        assertTrue(parsed.environments.isEmpty())
    }

    @Test
    fun `collection and folder variables are imported and exported`() {
        val json = """
            {
              "info": {"name": "Scoped", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
              "variable": [
                {"key": "baseUrl", "value": "https://api.test", "enabled": true},
                {"key": "off", "value": "x", "enabled": false}
              ],
              "item": [
                {
                  "name": "Auth",
                  "variable": [{"key": "token", "value": "t1", "enabled": true}],
                  "item": [
                    {"name": "Login", "request": {"method": "GET", "url": "{{baseUrl}}/login"}}
                  ]
                }
              ]
            }
        """.trimIndent()

        val collection = service.parseDataDump(json.toByteArray()).collections.single()
        assertEquals("https://api.test", collection.variables["baseUrl"])
        assertFalse(collection.variables.containsKey("off"))
        val folder = collection.folders.single()
        assertEquals("t1", folder.variables["token"])
        val request = folder.requests.single()
        assertTrue(collection.containsRequest(request))
        assertEquals(listOf(folder), collection.folderPathTo(request))

        val exported = service.exportCollection(listOf(collection), collection.name)
        val reparsed = service.parseDataDump(exported.toByteArray()).collections.single()
        assertEquals(collection.variables, reparsed.variables)
        assertEquals("t1", reparsed.folders.single().variables["token"])
    }

    @Test
    fun `legacy dump keeps collection and folder variables`() {
        val json = """
            {
              "version": 1,
              "collections": [{
                "name": "Scoped",
                "variable": [{"key": "team", "value": "astra"}],
                "folders": [{
                  "id": "f1",
                  "name": "Auth",
                  "variable": [{"key": "token", "value": "t1"}],
                  "requests": []
                }],
                "requests": []
              }]
            }
        """.trimIndent()

        val collection = service.parseDataDump(json.toByteArray()).collections.single()
        assertEquals("astra", collection.variables["team"])
        assertEquals("t1", collection.folders.single().variables["token"])
    }

    private companion object {
        private val LEGACY_DUMP = """
            {
              "version": 1,
              "collections": [
                {
                  "id": "c1",
                  "name": "Team API",
                  "order": ["r1"],
                  "folders": [
                    {
                      "id": "f1",
                      "name": "Auth",
                      "order": ["r2"],
                      "folders": [],
                      "requests": [
                        {
                          "id": "r2",
                          "name": "Login",
                          "method": "POST",
                          "url": "https://api.test/login",
                          "headers": "Content-Type: application/json\nX-Team: astra\n",
                          "dataMode": "raw",
                          "data": "{\"user\":\"ada\"}",
                          "currentHelper": "bearerAuth",
                          "helperAttributes": {"token": "{{token}}"},
                          "preRequestScript": "pm.environment.set('a','b');"
                        }
                      ]
                    }
                  ],
                  "requests": [
                    {
                      "id": "r1",
                      "name": "Ping",
                      "method": "GET",
                      "url": "https://api.test/ping",
                      "headers": ""
                    }
                  ]
                }
              ],
              "environments": [
                {
                  "id": "e1",
                  "name": "Dev",
                  "values": [{"key": "baseUrl", "value": "https://api.test", "enabled": true}]
                }
              ],
              "globals": [
                {"key": "g", "value": "1", "enabled": true},
                {"key": "off", "value": "2", "enabled": false}
              ]
            }
        """.trimIndent()

        private val COLLECTION = """
            {
              "info": {"name": "Demo"},
              "item": [
                {
                  "name": "Auth",
                  "item": [
                    {
                      "name": "Login",
                      "request": {
                        "method": "post",
                        "header": [
                          {"key": "X-Token", "value": "abc"},
                          {"key": "Skip", "value": "1", "disabled": true}
                        ],
                        "url": {
                          "raw": "https://api.example.com/users/:id?page=2",
                          "query": [{"key": "page", "value": "2"}]
                        },
                        "body": {
                          "mode": "raw",
                          "raw": "{\"a\":1}",
                          "options": {"raw": {"language": "json"}}
                        },
                        "auth": {
                          "type": "bearer",
                          "bearer": [{"key": "token", "value": "{{token}}"}]
                        }
                      }
                    }
                  ]
                },
                {"name": "Ping", "request": "https://api.example.com/ping"}
              ]
            }
        """.trimIndent()
    }
}
