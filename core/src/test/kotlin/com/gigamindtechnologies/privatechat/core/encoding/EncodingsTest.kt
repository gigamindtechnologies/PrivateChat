package com.gigamindtechnologies.privatechat.core.encoding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class EncodingsTest {
    @Test
    fun base32MatchesRfc4648Vectors() {
        val vectors = mapOf("" to "", "f" to "MY", "fo" to "MZXQ", "foo" to "MZXW6", "foob" to "MZXW6YQ", "fooba" to "MZXW6YTB", "foobar" to "MZXW6YTBOI")
        for ((plain, encoded) in vectors) {
            assertEquals(encoded, Base32.encode(plain.toByteArray()))
            assertArrayEquals(plain.toByteArray(), Base32.decode(encoded))
            assertArrayEquals(plain.toByteArray(), Base32.decode(encoded.lowercase()))
        }
    }

    @Test
    fun base32RoundTripsRandomData() {
        val random = Random(42)
        repeat(200) {
            val data = random.nextBytes(random.nextInt(0, 100))
            assertArrayEquals(data, Base32.decode(Base32.encode(data)))
        }
    }

    @Test
    fun base32RejectsGarbage() {
        assertThrows(IllegalArgumentException::class.java) { Base32.decode("MZ!W6") }
        assertThrows(IllegalArgumentException::class.java) { Base32.decode("MZ") } // non-zero padding bits
    }

    @Test
    fun hexRoundTrips() {
        val data = Random(1).nextBytes(64)
        assertArrayEquals(data, Hex.decode(Hex.encode(data)))
        assertEquals("00ff10", Hex.encode(byteArrayOf(0, -1, 16)))
    }

    @Test
    fun base64RejectsWrongLength() {
        assertEquals(null, B64.decodeOrNull(B64.encode(ByteArray(31)), 32))
        assertEquals(null, B64.decodeOrNull("not base64!", 32))
        assertEquals(32, B64.decodeOrNull(B64.encode(ByteArray(32)), 32)?.size)
    }
}
