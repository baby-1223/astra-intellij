package com.astra.intellij.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class ResponseCharsetTest {
    @Test
    fun `uses the charset advertised by content type`() {
        val headers = mapOf("Content-Type" to listOf("text/plain; charset=GBK"))
        val body = "中文响应".toByteArray(charset("GBK"))

        assertEquals("中文响应", ResponseCharset.decode(body, headers))
    }

    @Test
    fun `detects utf-8 without a charset header`() {
        val body = "中文响应".toByteArray(StandardCharsets.UTF_8)

        assertEquals("中文响应", ResponseCharset.decode(body, emptyMap()))
    }

    @Test
    fun `falls back to gb18030 for invalid utf-8`() {
        val body = "中文响应".toByteArray(charset("GBK"))

        assertEquals("中文响应", ResponseCharset.decode(body, emptyMap()))
    }

    @Test
    fun `strips the utf-8 bom`() {
        val body = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "ok".toByteArray(StandardCharsets.UTF_8)

        assertEquals("ok", ResponseCharset.decode(body, emptyMap()))
        assertTrue(ResponseCharset.decode(ByteArray(0), emptyMap()).isEmpty())
    }
}
