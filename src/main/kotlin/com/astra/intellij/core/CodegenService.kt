package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import java.util.Base64
import java.util.Locale

/** Target languages offered by "Copy as code". */
enum class CodegenLanguage(val label: String, val extension: String) {
    CURL("cURL", "sh"),
    PYTHON("Python (requests)", "py"),
    JAVASCRIPT("JavaScript (fetch)", "js"),
    GO("Go (net/http)", "go"),
    JAVA("Java (HttpClient)", "java"),
    HTTP_CLIENT("IntelliJ HTTP Client", "http"),
}

/**
 * Renders the current request as a runnable snippet for other languages.
 *
 * Variable placeholders are resolved with the same rules the HTTP client uses, so the snippet
 * works when pasted into a terminal or IDE without extra substitution.
 */
class CodegenService(private val curl: CurlService = CurlService()) {
    fun generate(
        request: AstraRequest,
        vars: Map<String, String> = emptyMap(),
        language: CodegenLanguage = CodegenLanguage.CURL,
    ): String {
        if (language == CodegenLanguage.CURL) return curl.export(request, vars)
        val prepared = prepare(request, vars)
        return when (language) {
            CodegenLanguage.PYTHON -> python(prepared)
            CodegenLanguage.JAVASCRIPT -> javascript(prepared)
            CodegenLanguage.GO -> go(prepared)
            CodegenLanguage.JAVA -> java(prepared)
            CodegenLanguage.HTTP_CLIENT -> httpClient(prepared)
            CodegenLanguage.CURL -> curl.export(request, vars)
        }
    }

    private data class Prepared(
        val method: String,
        val url: String,
        val headers: List<Pair<String, String>>,
        val body: String?,
    )

    private fun prepare(request: AstraRequest, vars: Map<String, String>): Prepared {
        val method = VariableResolver.resolve(request.method, vars).uppercase(Locale.ROOT)
        val headers = request.headers
            .filter { it.enabled && it.key.isNotBlank() }
            .map { VariableResolver.resolve(it.key, vars) to VariableResolver.resolve(it.value, vars) }
            .toMutableList()
        val body = bodyFor(request, vars)
        if (body != null && headers.none { it.first.equals("Content-Type", ignoreCase = true) }) {
            headers += "Content-Type" to contentTypeFor(request)
        }
        when (request.auth.type.lowercase(Locale.ROOT)) {
            "bearer" -> headers += "Authorization" to
                "Bearer ${VariableResolver.resolve(request.auth.token, vars)}"
            "basic" -> {
                val username = VariableResolver.resolve(request.auth.username, vars)
                val password = VariableResolver.resolve(request.auth.password, vars)
                val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
                headers += "Authorization" to "Basic $encoded"
            }
            "apikey" -> if (request.auth.apiKeyIn.equals("header", ignoreCase = true)) {
                headers += VariableResolver.resolve(request.auth.apiKeyName, vars) to
                    VariableResolver.resolve(request.auth.apiKeyValue, vars)
            }
            "oauth2" -> {
                val token = VariableResolver.resolve(request.auth.token, vars)
                if (token.isNotBlank()) headers += "Authorization" to "Bearer $token"
            }
        }
        return Prepared(method, buildRequestUrl(request, vars), headers, body)
    }

    private fun bodyFor(request: AstraRequest, vars: Map<String, String>): String? {
        val method = VariableResolver.resolve(request.method, vars).uppercase(Locale.ROOT)
        if (method == "GET" || method == "HEAD" || request.bodyMode == "none") return null
        return when (request.bodyMode) {
            "form", "urlencoded", "form-data" -> request.formFields
                .filter { it.enabled && it.key.isNotBlank() }
                .joinToString("&") {
                    "${encodeQueryComponent(VariableResolver.resolve(it.key, vars))}=" +
                        encodeQueryComponent(VariableResolver.resolve(it.value, vars))
                }
            else -> VariableResolver.resolve(request.body, vars)
        }
    }

    private fun contentTypeFor(request: AstraRequest): String = when (request.bodyMode) {
        "json" -> "application/json"
        "form", "urlencoded" -> "application/x-www-form-urlencoded"
        "form-data" -> "multipart/form-data"
        else -> "text/plain; charset=utf-8"
    }

    private fun python(prepared: Prepared): String {
        val builder = StringBuilder()
        builder.append("import requests\n\n")
        builder.append("url = \"").append(escape(prepared.url, '"')).append("\"\n")
        builder.append("headers = {\n")
        prepared.headers.forEach { (key, value) ->
            builder.append("    \"").append(escape(key, '"')).append("\": \"")
                .append(escape(value, '"')).append("\",\n")
        }
        builder.append("}\n")
        prepared.body?.let {
            builder.append("payload = \"").append(escape(it, '"')).append("\"\n")
        }
        builder.append("\nresponse = requests.request(\"")
            .append(escape(prepared.method, '"')).append("\", url, headers=headers")
        prepared.body?.let { builder.append(", data=payload.encode(\"utf-8\")") }
        builder.append(", timeout=30)\n")
        builder.append("print(response.status_code)\n")
        builder.append("print(response.text)\n")
        return builder.toString()
    }

    private fun javascript(prepared: Prepared): String {
        val builder = StringBuilder()
        builder.append("const url = \"").append(escape(prepared.url, '"')).append("\";\n")
        builder.append("const headers = {\n")
        prepared.headers.forEach { (key, value) ->
            builder.append("  \"").append(escape(key, '"')).append("\": \"")
                .append(escape(value, '"')).append("\",\n")
        }
        builder.append("};\n")
        prepared.body?.let {
            builder.append("const body = \"").append(escape(it, '"')).append("\";\n")
        }
        builder.append("\n(async () => {\n")
        builder.append("  const response = await fetch(url, {\n")
        builder.append("    method: \"").append(escape(prepared.method, '"')).append("\",\n")
        builder.append("    headers,\n")
        if (prepared.body != null) builder.append("    body,\n")
        builder.append("  });\n")
        builder.append("  console.log(response.status, await response.text());\n")
        builder.append("})();\n")
        return builder.toString()
    }

    private fun go(prepared: Prepared): String {
        val builder = StringBuilder()
        builder.append("package main\n\nimport (\n\t\"fmt\"\n\t\"io\"\n\t\"net/http\"\n")
        if (prepared.body != null) builder.append("\t\"strings\"\n")
        builder.append(")\n\nfunc main() {\n")
        if (prepared.body != null) {
            builder.append("\tbody := strings.NewReader(\"").append(escape(prepared.body, '"')).append("\")\n")
        }
        builder.append("\treq, err := http.NewRequest(\"").append(escape(prepared.method, '"'))
            .append("\", \"").append(escape(prepared.url, '"')).append("\", ")
            .append(if (prepared.body != null) "body" else "nil").append(")\n")
        builder.append("\tif err != nil {\n\t\tpanic(err)\n\t}\n")
        prepared.headers.forEach { (key, value) ->
            builder.append("\treq.Header.Set(\"").append(escape(key, '"')).append("\", \"")
                .append(escape(value, '"')).append("\")\n")
        }
        builder.append("\tresp, err := (&http.Client{}).Do(req)\n")
        builder.append("\tif err != nil {\n\t\tpanic(err)\n\t}\n")
        builder.append("\tdefer resp.Body.Close()\n")
        builder.append("\tdata, err := io.ReadAll(resp.Body)\n")
        builder.append("\tif err != nil {\n\t\tpanic(err)\n\t}\n")
        builder.append("\tfmt.Println(resp.Status)\n")
        builder.append("\tfmt.Println(string(data))\n")
        builder.append("}\n")
        return builder.toString()
    }

    private fun java(prepared: Prepared): String {
        val builder = StringBuilder()
        builder.append("import java.net.URI;\n")
        builder.append("import java.net.http.HttpClient;\n")
        builder.append("import java.net.http.HttpRequest;\n")
        builder.append("import java.net.http.HttpResponse;\n\n")
        builder.append("public class AstraRequest {\n")
        builder.append("    public static void main(String[] args) throws Exception {\n")
        builder.append("        HttpClient client = HttpClient.newHttpClient();\n")
        builder.append("        HttpRequest request = HttpRequest.newBuilder()\n")
        builder.append("                .uri(URI.create(\"").append(escape(prepared.url, '"')).append("\"))\n")
        prepared.headers.forEach { (key, value) ->
            builder.append("                .header(\"").append(escape(key, '"')).append("\", \"")
                .append(escape(value, '"')).append("\")\n")
        }
        val bodyPublisher = prepared.body?.let {
            "HttpRequest.BodyPublishers.ofString(\"${escape(it, '"')}\")"
        } ?: "HttpRequest.BodyPublishers.noBody()"
        builder.append("                .method(\"").append(escape(prepared.method, '"')).append("\", ")
            .append(bodyPublisher).append(")\n")
        builder.append("                .build();\n")
        builder.append("        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());\n")
        builder.append("        System.out.println(response.statusCode());\n")
        builder.append("        System.out.println(response.body());\n")
        builder.append("    }\n}\n")
        return builder.toString()
    }

    private fun httpClient(prepared: Prepared): String {
        val builder = StringBuilder()
        builder.append(prepared.method).append(' ').append(prepared.url).append('\n')
        prepared.headers.forEach { (key, value) ->
            builder.append(key).append(": ").append(value).append('\n')
        }
        prepared.body?.let { builder.append('\n').append(it).append('\n') }
        return builder.toString()
    }

    /** Escapes a value for a quoted string literal in the target language. */
    private fun escape(value: String, quote: Char): String {
        val builder = StringBuilder(value.length)
        value.forEach { char ->
            when (char) {
                '\\' -> builder.append("\\\\")
                quote -> builder.append('\\').append(char)
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }
}
