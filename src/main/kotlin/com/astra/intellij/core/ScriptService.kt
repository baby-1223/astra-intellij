package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.HttpResult
import com.astra.intellij.model.KeyValue
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Runs Postman-compatible pre-request JavaScript in an embedded Rhino engine.
 *
 * Supported API (a practical subset of Postman's sandbox):
 *   pm.sendRequest(url | config, callback?)  request is sent immediately and the callback runs
 *                                            before the script continues (no real asynchrony)
 *   pm.environment.get/set/unset/clear/has/replaceIn/toObject
 *   pm.variables.get/set/unset/has/replaceIn/toObject
 *   pm.collectionVariables / pm.globals (treated like pm.variables)
 *   pm.request.method/url/headers.add|upsert|remove, pm.request.body.raw
 *   pm.info.eventName / pm.info.requestName, pm.execution.setNextRequest (no-op)
 *   console.log/info/warn/error/debug, pm.test (runs the callback)
 */
object ScriptService {
    private val JS_MARKERS = listOf("pm.", "console.", "=>", "function ", "var ", "let ", "const ")

    /** True when the script uses JavaScript syntax instead of the simple declarative lines. */
    fun looksLikeJavaScript(lines: List<String>): Boolean {
        val script = lines.joinToString("\n") { it.trimStart() }
        if (script.isBlank()) return false
        return JS_MARKERS.any { script.contains(it) }
    }

    fun runPreRequest(
        script: String,
        baseVars: Map<String, String>,
        environment: AstraEnvironment?,
        request: AstraRequest? = null,
        sender: (AstraRequest, Map<String, String>) -> HttpResult = { _, _ ->
            throw IllegalStateException("Pre-request HTTP calls are not available in this context")
        },
        logs: MutableList<String>? = null,
    ): MutableMap<String, String> {
        val vars = baseVars.toMutableMap()
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initSafeStandardObjects()
            val requestObject = requestObject(cx, scope, request)
            val pm = cx.newObject(scope) as ScriptableObject
            putProperty(pm, "environment", environmentObject(cx, scope, vars, environment))
            putProperty(pm, "variables", variablesObject(cx, scope, vars))
            putProperty(pm, "collectionVariables", variablesObject(cx, scope, vars))
            putProperty(pm, "globals", variablesObject(cx, scope, vars))
            putProperty(pm, "request", requestObject)
            putProperty(pm, "info", infoObject(cx, scope, request))
            putProperty(pm, "sendRequest", sendRequestFunction(cx, scope, vars, sender))
            putProperty(pm, "test", testFunction(cx, scope, logs))
            putProperty(pm, "execution", executionObject(cx, scope))
            putProperty(scope, "pm", pm)
            putProperty(scope, "console", consoleObject(cx, scope, logs))

            cx.evaluateString(scope, script, "pre-request", 1, null)
            request?.let { applyRequestMutations(it, requestObject) }
        } catch (error: RhinoException) {
            throw IllegalStateException(
                "Pre-request script failed at line ${error.lineNumber()}: ${error.details()}",
                error,
            )
        } finally {
            Context.exit()
        }
        return vars
    }

    private fun environmentObject(
        cx: Context,
        scope: Scriptable,
        vars: MutableMap<String, String>,
        environment: AstraEnvironment?,
    ): ScriptableObject {
        val obj = cx.newObject(scope) as ScriptableObject
        putProperty(obj, "get", NativeFn("get") { args ->
            val name = jsString(args.getOrNull(0))
            environment?.variables?.get(name) ?: vars[name] ?: Undefined.instance
        })
        putProperty(obj, "set", NativeFn("set") { args ->
            val name = jsString(args.getOrNull(0))
            if (name.isNotBlank()) {
                val value = jsString(args.getOrNull(1))
                vars[name] = value
                environment?.variables?.set(name, value)
            }
            Undefined.instance
        })
        putProperty(obj, "unset", NativeFn("unset") { args ->
            val name = jsString(args.getOrNull(0))
            vars.remove(name)
            environment?.variables?.remove(name)
            Undefined.instance
        })
        putProperty(obj, "clear", NativeFn("clear") {
            environment?.variables?.keys?.toList()?.forEach { vars.remove(it) }
            environment?.variables?.clear()
            Undefined.instance
        })
        putProperty(obj, "has", NativeFn("has") { args ->
            val name = jsString(args.getOrNull(0))
            environment?.variables?.containsKey(name) == true || vars.containsKey(name)
        })
        putProperty(obj, "replaceIn", NativeFn("replaceIn") { args ->
            VariableResolver.resolve(jsString(args.getOrNull(0)), vars)
        })
        putProperty(obj, "toObject", NativeFn("toObject") {
            val snapshot = cx.newObject(scope) as ScriptableObject
            vars.forEach { (key, value) -> putProperty(snapshot, key, value) }
            snapshot
        })
        return obj
    }

    private fun variablesObject(
        cx: Context,
        scope: Scriptable,
        vars: MutableMap<String, String>,
    ): ScriptableObject {
        val obj = cx.newObject(scope) as ScriptableObject
        putProperty(obj, "get", NativeFn("get") { args ->
            vars[jsString(args.getOrNull(0))] ?: Undefined.instance
        })
        putProperty(obj, "set", NativeFn("set") { args ->
            val name = jsString(args.getOrNull(0))
            if (name.isNotBlank()) vars[name] = jsString(args.getOrNull(1))
            Undefined.instance
        })
        putProperty(obj, "unset", NativeFn("unset") { args ->
            vars.remove(jsString(args.getOrNull(0)))
            Undefined.instance
        })
        putProperty(obj, "has", NativeFn("has") { args ->
            vars.containsKey(jsString(args.getOrNull(0)))
        })
        putProperty(obj, "replaceIn", NativeFn("replaceIn") { args ->
            VariableResolver.resolve(jsString(args.getOrNull(0)), vars)
        })
        putProperty(obj, "toObject", NativeFn("toObject") {
            val snapshot = cx.newObject(scope) as ScriptableObject
            vars.forEach { (key, value) -> putProperty(snapshot, key, value) }
            snapshot
        })
        return obj
    }

    private fun requestObject(cx: Context, scope: Scriptable, request: AstraRequest?): ScriptableObject {
        val obj = cx.newObject(scope) as ScriptableObject
        putProperty(obj, "name", request?.name.orEmpty())
        putProperty(obj, "method", request?.method ?: "GET")
        putProperty(obj, "url", request?.url.orEmpty())
        val body = cx.newObject(scope) as ScriptableObject
        val bodyMode = request?.bodyMode ?: "none"
        putProperty(body, "mode", if (bodyMode == "form-data") "formdata" else bodyMode)
        putProperty(body, "raw", request?.body.orEmpty())
        putProperty(obj, "body", body)
        putProperty(
            obj,
            "headers",
            headerListObject(cx, scope, request?.headers ?: mutableListOf()),
        )
        return obj
    }

    private fun headerListObject(
        cx: Context,
        scope: Scriptable,
        headers: MutableList<KeyValue>,
    ): ScriptableObject {
        val obj = cx.newObject(scope) as ScriptableObject
        putProperty(obj, "add", NativeFn("add") { args ->
            headerFromJs(args.firstOrNull())?.let { headers.add(it) }
            Undefined.instance
        })
        putProperty(obj, "upsert", NativeFn("upsert") { args ->
            val header = headerFromJs(args.firstOrNull())
            if (header != null) {
                headers.removeAll { it.key.equals(header.key, ignoreCase = true) }
                headers.add(header)
            }
            Undefined.instance
        })
        putProperty(obj, "remove", NativeFn("remove") { args ->
            val name = jsString(args.firstOrNull())
            headers.removeAll { it.key.equals(name, ignoreCase = true) }
            Undefined.instance
        })
        putProperty(obj, "get", NativeFn("get") { args ->
            val name = jsString(args.firstOrNull())
            headers.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: Undefined.instance
        })
        putProperty(obj, "has", NativeFn("has") { args ->
            val name = jsString(args.firstOrNull())
            headers.any { it.key.equals(name, ignoreCase = true) }
        })
        putProperty(obj, "toObject", NativeFn("toObject") {
            val snapshot = cx.newObject(scope) as ScriptableObject
            headers.forEach { putProperty(snapshot, it.key, it.value) }
            snapshot
        })
        return obj
    }

    private fun infoObject(cx: Context, scope: Scriptable, request: AstraRequest?): ScriptableObject {
        val obj = cx.newObject(scope) as ScriptableObject
        putProperty(obj, "eventName", "prerequest")
        putProperty(obj, "requestName", request?.name.orEmpty())
        putProperty(obj, "requestId", request?.id.orEmpty())
        return obj
    }

    private fun executionObject(cx: Context, scope: Scriptable): ScriptableObject {
        val obj = cx.newObject(scope) as ScriptableObject
        putProperty(obj, "setNextRequest", NativeFn("setNextRequest") { Undefined.instance })
        return obj
    }

    private fun testFunction(
        cx: Context,
        scope: Scriptable,
        logs: MutableList<String>?,
    ): BaseFunction = NativeFn("test") { args ->
        val name = jsString(args.getOrNull(0)).ifBlank { "test" }
        val callback = args.getOrNull(1)
        if (callback is Function) {
            runCatching { callback.call(cx, scope, scope, emptyArray()) }
                .onFailure { logs?.add("$name: ${it.message}") }
        }
        Undefined.instance
    }

    private fun consoleObject(cx: Context, scope: Scriptable, logs: MutableList<String>?): ScriptableObject {
        val obj = cx.newObject(scope) as ScriptableObject
        listOf("log", "info", "warn", "error", "debug").forEach { level ->
            putProperty(obj, level, NativeFn(level) { args ->
                logs?.add(args.joinToString(" ") { jsString(it) })
                Undefined.instance
            })
        }
        return obj
    }

    private fun sendRequestFunction(
        cx: Context,
        scope: Scriptable,
        vars: MutableMap<String, String>,
        sender: (AstraRequest, Map<String, String>) -> HttpResult,
    ): BaseFunction = NativeFn("sendRequest") { args ->
        val callback = args.getOrNull(1)
        var errorValue: Any = Undefined.instance
        var responseValue: Any = Undefined.instance
        try {
            val subRequest = buildRequest(args.firstOrNull())
            responseValue = responseObject(cx, scope, sender(subRequest, vars.toMap()))
        } catch (error: Exception) {
            errorValue = errorObject(cx, scope, error.message ?: error.javaClass.simpleName)
            if (callback !is Function) throw error
        }
        if (callback is Function) {
            callback.call(cx, scope, scope, arrayOf(errorValue, responseValue))
        }
        // Returning the response lets scripts use the value directly too, which is not possible
        // in Postman but is convenient here because the callback runs synchronously.
        responseValue
    }

    private fun responseObject(cx: Context, scope: Scriptable, result: HttpResult): ScriptableObject {
        val text = String(result.body, StandardCharsets.UTF_8)
        val obj = cx.newObject(scope) as ScriptableObject
        putProperty(obj, "code", result.status)
        putProperty(obj, "status", result.statusText)
        putProperty(obj, "responseTime", result.elapsedMs)
        putProperty(obj, "body", text)
        val headers = cx.newObject(scope) as ScriptableObject
        result.headers.forEach { (name, values) -> putProperty(headers, name, values.joinToString(", ")) }
        putProperty(headers, "get", NativeFn("get") { args ->
            headerValue(result, jsString(args.firstOrNull())) ?: Undefined.instance
        })
        putProperty(headers, "has", NativeFn("has") { args ->
            headerValue(result, jsString(args.firstOrNull())) != null
        })
        putProperty(headers, "toObject", NativeFn("toObject") {
            val snapshot = cx.newObject(scope) as ScriptableObject
            result.headers.forEach { (name, values) ->
                putProperty(snapshot, name, values.joinToString(", "))
            }
            snapshot
        })
        putProperty(obj, "headers", headers)
        putProperty(obj, "text", NativeFn("text") { text })
        putProperty(obj, "json", NativeFn("json") {
            val standardJson = getProperty(scope, "JSON") as? Scriptable
            val parse = standardJson?.let { getProperty(it, "parse") } as? Function
            try {
                parse?.call(cx, scope, standardJson, arrayOf(text))
                    ?: NativeJSON.parse(cx, scope, text, { _, _, _, args -> args.firstOrNull() })
            } catch (error: Exception) {
                throw IllegalStateException("Response body is not valid JSON", error)
            }
        })
        return obj
    }

    private fun errorObject(cx: Context, scope: Scriptable, message: String): ScriptableObject {
        val obj = cx.newObject(scope) as ScriptableObject
        putProperty(obj, "name", "Error")
        putProperty(obj, "message", message)
        return obj
    }

    private fun buildRequest(value: Any?): AstraRequest {
        val request = AstraRequest(name = "Pre-request", method = "GET")
        when (value) {
            null, Undefined.instance -> throw IllegalArgumentException("pm.sendRequest needs a URL or an object")
            is CharSequence -> {
                request.url = value.toString()
                return request
            }
        }
        val obj = value as? Scriptable
            ?: throw IllegalArgumentException("pm.sendRequest needs a URL or an object")
        request.url = urlString(getProperty(obj, "url"))
        val method = jsString(getProperty(obj, "method")).trim()
        if (method.isNotEmpty()) request.method = method.uppercase(Locale.ROOT)
        val headerValue = getProperty(obj, "header") ?: getProperty(obj, "headers")
        if (headerValue != null && headerValue != Undefined.instance) {
            readHeaders(headerValue, request.headers)
        }
        val bodyValue = getProperty(obj, "body")
        if (bodyValue != null && bodyValue != Undefined.instance) {
            readBody(bodyValue, request)
        }
        return request
    }

    private fun readHeaders(value: Any?, target: MutableList<KeyValue>) {
        val scriptable = value as? Scriptable ?: return
        if (value is NativeArray) {
            arrayValues(scriptable).forEach { item -> headerFromJs(item)?.let { target.add(it) } }
            return
        }
        scriptable.ids.forEach { id ->
            val key = id.toString()
            val item = scriptable.get(key, scriptable)
            if (item != Scriptable.NOT_FOUND && item != null && item != Undefined.instance) {
                target.add(KeyValue(key = key, value = jsString(item)))
            }
        }
    }

    private fun readBody(value: Any?, request: AstraRequest) {
        if (value is CharSequence) {
            request.body = value.toString()
            request.bodyMode = when {
                request.body.isBlank() -> "none"
                looksLikeJson(request.body) -> "json"
                else -> "text"
            }
            return
        }
        val body = value as? Scriptable ?: return
        when (jsString(getProperty(body, "mode")).lowercase(Locale.ROOT)) {
            "raw" -> {
                request.body = jsString(getProperty(body, "raw"))
                val language = jsString(nested(body, "options", "raw", "language"))
                request.bodyMode = if (language == "json" || looksLikeJson(request.body)) "json" else "text"
            }
            "urlencoded" -> {
                request.bodyMode = "urlencoded"
                request.formFields = formFields(body, "urlencoded")
            }
            "formdata" -> {
                request.bodyMode = "form-data"
                request.formFields = formFields(body, "formdata")
            }
            else -> {
                val raw = getProperty(body, "raw")
                if (raw != null && raw != Undefined.instance) {
                    request.body = jsString(raw)
                    request.bodyMode = if (looksLikeJson(request.body)) "json" else "text"
                }
            }
        }
    }

    private fun formFields(body: Scriptable, name: String): MutableList<KeyValue> {
        val list = getProperty(body, name) as? Scriptable ?: return mutableListOf()
        return arrayValues(list).mapNotNull { item ->
            val field = item as? Scriptable ?: return@mapNotNull null
            val key = jsString(getProperty(field, "key"))
            if (key.isBlank()) return@mapNotNull null
            KeyValue(
                enabled = !jsBoolean(getProperty(field, "disabled")),
                key = key,
                value = jsString(getProperty(field, "value")),
            )
        }.toMutableList()
    }

    private fun applyRequestMutations(target: AstraRequest, requestObject: ScriptableObject) {
        val method = jsString(getProperty(requestObject, "method")).trim()
        if (method.isNotEmpty()) target.method = method.uppercase(Locale.ROOT)
        val url = urlString(getProperty(requestObject, "url"))
        if (url.isNotBlank()) target.url = url
        val bodyValue = getProperty(requestObject, "body")
        if (bodyValue is CharSequence) {
            target.body = bodyValue.toString()
            target.bodyMode = when {
                target.body.isBlank() -> "none"
                looksLikeJson(target.body) -> "json"
                else -> "text"
            }
        } else if (bodyValue is Scriptable) {
            val raw = jsString(getProperty(bodyValue, "raw"))
            if (raw != target.body) target.body = raw
            val mode = jsString(getProperty(bodyValue, "mode")).trim().lowercase(Locale.ROOT)
            if (mode.isNotEmpty()) {
                target.bodyMode = when (mode) {
                    "formdata" -> "form-data"
                    "raw" -> if (raw.isBlank()) "none" else if (looksLikeJson(raw)) "json" else "text"
                    else -> mode
                }
            }
        }
    }

    private fun headerFromJs(value: Any?): KeyValue? = when (value) {
        is CharSequence -> parseHeader(value.toString())
        is Scriptable -> {
            val key = jsString(getProperty(value, "key")).trim()
            if (key.isBlank()) {
                null
            } else {
                KeyValue(
                    enabled = !jsBoolean(getProperty(value, "disabled")),
                    key = key,
                    value = jsString(getProperty(value, "value")),
                )
            }
        }
        else -> null
    }

    private fun parseHeader(text: String): KeyValue? {
        val separator = text.indexOf(':')
        if (separator <= 0) return null
        return KeyValue(
            key = text.substring(0, separator).trim(),
            value = text.substring(separator + 1).trim(),
        )
    }

    private fun headerValue(result: HttpResult, name: String): String? =
        result.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.joinToString(", ")

    private fun arrayValues(value: Scriptable): List<Any?> {
        val declared = getProperty(value, "length")
        val count = if (declared is Number) declared.toInt() else value.ids.size
        return (0 until count.coerceAtLeast(0)).mapNotNull { index ->
            val item = value.get(index, value)
            if (item == Scriptable.NOT_FOUND) null else item
        }
    }

    private fun nested(value: Any?, vararg names: String): Any? {
        var current = value
        names.forEach { name ->
            current = getProperty(current ?: return null, name)
        }
        return current
    }

    private fun urlString(value: Any?): String = when (value) {
        null, Undefined.instance -> ""
        is CharSequence -> value.toString()
        is Scriptable -> {
            val raw = getProperty(value, "raw")
            if (raw != null && raw != Undefined.instance && raw !is Scriptable) {
                jsString(raw)
            } else {
                jsString(value)
            }
        }
        else -> jsString(value)
    }

    private fun jsString(value: Any?): String = when (value) {
        null, Undefined.instance -> ""
        is String -> value
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        else -> Context.toString(value)
    }

    private fun jsBoolean(value: Any?): Boolean = when (value) {
        null, Undefined.instance -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.isNotEmpty() && !value.equals("false", ignoreCase = true)
        else -> Context.toBoolean(value)
    }

    private fun getProperty(value: Any?, name: String): Any? {
        val scriptable = value as? Scriptable ?: return null
        val result = ScriptableObject.getProperty(scriptable, name)
        return if (result == Scriptable.NOT_FOUND) null else result
    }

    private fun putProperty(target: Scriptable, name: String, value: Any?) {
        ScriptableObject.putProperty(target, name, value)
    }

    private fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    private class NativeFn(
        private val name: String,
        private val body: (Array<Any?>) -> Any?,
    ) : BaseFunction() {
        override fun getFunctionName(): String = name

        override fun call(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<Any?>,
        ): Any? = body(args)
    }
}
