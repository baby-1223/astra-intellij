package com.astra.intellij.core

import com.astra.intellij.model.HttpResult
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Decodes response bytes using the charset advertised by `Content-Type`, with a BOM check and a
 * UTF-8 probe before falling back to GB18030 (the common encoding for Chinese services).
 */
object ResponseCharset {
    private val CHARSET_PATTERN = Regex("""charset\s*=\s*["']?([\w.:-]+)""", RegexOption.IGNORE_CASE)

    fun decode(result: HttpResult): String = decode(result.body, result.headers)

    fun decode(body: ByteArray, headers: Map<String, List<String>>): String {
        if (body.isEmpty()) return ""
        return String(body, charsetFor(headers, body)).removePrefix("\uFEFF")
    }

    fun charsetFor(headers: Map<String, List<String>>, body: ByteArray): Charset {
        val contentType = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull()
            .orEmpty()
        CHARSET_PATTERN.find(contentType)?.groupValues?.getOrNull(1)?.let { name ->
            runCatching { Charset.forName(name) }.getOrNull()?.let { return it }
        }
        if (body.size >= 3 &&
            body[0] == 0xEF.toByte() && body[1] == 0xBB.toByte() && body[2] == 0xBF.toByte()
        ) {
            return StandardCharsets.UTF_8
        }
        if (body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte()) {
            return StandardCharsets.UTF_16LE
        }
        if (body.size >= 2 && body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte()) {
            return StandardCharsets.UTF_16BE
        }
        if (isValid(body, StandardCharsets.UTF_8)) return StandardCharsets.UTF_8
        return runCatching { Charset.forName("GB18030") }.getOrDefault(StandardCharsets.ISO_8859_1)
    }

    private fun isValid(body: ByteArray, charset: Charset): Boolean = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body))
    }.isSuccess
}
