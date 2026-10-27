package com.gigamindtechnologies.privatechat.core.encoding

import java.io.ByteArrayOutputStream
import java.util.Base64

/** RFC 4648 base32 without padding. Encodes uppercase, decodes case-insensitively. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in text) {
            val value = ALPHABET.indexOf(c.uppercaseChar())
            require(value >= 0) { "Invalid base32 character" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        // Leftover bits are padding and must be zero, otherwise the text is not canonical.
        require(bits < 5 && buffer == 0) { "Invalid base32 padding" }
        return out.toByteArray()
    }
}

object B64 {
    fun encode(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

    fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    /** Decodes [text] and checks the length, returning null for anything malformed. */
    fun decodeOrNull(text: String, expectedSize: Int): ByteArray? =
        runCatching { decode(text) }.getOrNull()?.takeIf { it.size == expectedSize }
}

object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(data: ByteArray): String {
        val out = StringBuilder(data.size * 2)
        for (b in data) {
            out.append(DIGITS[(b.toInt() shr 4) and 0xf])
            out.append(DIGITS[b.toInt() and 0xf])
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        require(text.length % 2 == 0) { "Odd hex length" }
        return ByteArray(text.length / 2) { i ->
            val hi = Character.digit(text[2 * i], 16)
            val lo = Character.digit(text[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex character" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
