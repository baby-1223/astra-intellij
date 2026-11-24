package com.astra.intellij.core

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * gRPC-Web framing used by the "grpc" body mode.
 *
 * Frames are a 1-byte flag (0x80 marks a trailer), a 4-byte big-endian length and the payload,
 * matching the desktop client so requests and responses look the same in both apps.
 */
object GrpcCodec {
    fun encode(payload: ByteArray, trailer: Boolean = false): ByteArray {
        val frame = ByteArray(5 + payload.size)
        frame[0] = if (trailer) 0x80.toByte() else 0
        val length = payload.size
        frame[1] = ((length ushr 24) and 0xFF).toByte()
        frame[2] = ((length ushr 16) and 0xFF).toByte()
        frame[3] = ((length ushr 8) and 0xFF).toByte()
        frame[4] = (length and 0xFF).toByte()
        payload.copyInto(frame, 5)
        return frame
    }

    /** Turns the editor payload into frame bytes; [binary] selects base64 protobuf input. */
    fun payload(text: String, binary: Boolean): ByteArray {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ByteArray(0)
        if (!binary) return trimmed.toByteArray(StandardCharsets.UTF_8)
        return try {
            Base64.getDecoder().decode(trimmed.replace(Regex("\\s+"), ""))
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("gRPC payload is not valid base64")
        }
    }

    /** Renders response frames as readable text, including trailers such as grpc-status. */
    fun decodeFrames(bytes: ByteArray): String {
        val out = StringBuilder()
        var offset = 0
        var index = 0
        while (offset + 5 <= bytes.size) {
            index += 1
            val trailer = (bytes[offset].toInt() and 0x80) != 0
            val length = ((bytes[offset + 1].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 8) or
                (bytes[offset + 4].toInt() and 0xFF)
            val start = offset + 5
            val end = start + length
            if (length < 0 || end > bytes.size) break
            val payload = bytes.copyOfRange(start, end)
            out.append("— Frame ").append(index)
                .append(" · ").append(if (trailer) "trailer" else "message")
                .append(" · ").append(length).append(" bytes —\n")
            if (trailer) {
                out.append(String(payload, StandardCharsets.ISO_8859_1).trim()).append('\n')
            } else {
                val text = String(payload, StandardCharsets.UTF_8)
                val printable = text.all { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }
                out.append(if (printable && text.isNotBlank()) text else "base64: ${Base64.getEncoder().encodeToString(payload)}")
                out.append('\n')
            }
            out.append('\n')
            offset = end
        }
        if (offset < bytes.size) {
            out.append("[${bytes.size - offset} remaining bytes could not be parsed as gRPC frames]\n")
            out.append("base64: ${Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, bytes.size))}\n")
        }
        return out.toString()
    }

    fun isGrpcContentType(contentType: String?): Boolean =
        contentType?.contains("application/grpc", ignoreCase = true) == true
}
