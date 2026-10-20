package com.gigamindtechnologies.privatechat.core.invite

import com.gigamindtechnologies.privatechat.core.crypto.Dh
import com.gigamindtechnologies.privatechat.core.tor.OnionKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteTest {
    @Test
    fun roundTripsAndLooksLikeRandomLetters() {
        val onion = OnionKey.generate()
        val static = Dh.generate()
        val code = InviteCode(onion.publicKey, static.publicKey).encode()
        assertTrue(code.matches(Regex("^[A-Z2-7]+$"))) // QR alphanumeric mode, nothing readable
        assertEquals(111, code.length)
        val decoded = InviteCode.decode(code)!!
        assertEquals(onion.address, decoded.onionAddress)
        assertArrayEquals(static.publicKey, decoded.staticKey)
    }

    @Test
    fun rejectsAnythingElse() {
        val code = InviteCode(OnionKey.generate().publicKey, Dh.generate().publicKey).encode()
        val flipped = code.substring(0, 20) + (if (code[20] == 'A') 'B' else 'A') + code.substring(21)
        assertNull(InviteCode.decode(flipped))
        assertNull(InviteCode.decode(code.dropLast(2)))
        assertNull(InviteCode.decode("https://example.com"))
        assertNull(InviteCode.decode(""))
    }

    @Test
    fun safetyCodeIsDeterministicAndUsesDistinctSymbols() {
        assertEquals(64, SafetyCode.SYMBOLS.toSet().size)
        val h = ByteArray(32) { it.toByte() }
        assertEquals(SafetyCode.fromHandshakeHash(h), SafetyCode.fromHandshakeHash(h.copyOf()))
        assertEquals(5, SafetyCode.fromHandshakeHash(h).size)
        assertNotEquals(SafetyCode.fromHandshakeHash(h), SafetyCode.fromHandshakeHash(ByteArray(32)))
    }
}
