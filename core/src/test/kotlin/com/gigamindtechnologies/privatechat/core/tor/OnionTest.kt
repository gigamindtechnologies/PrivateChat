package com.gigamindtechnologies.privatechat.core.tor

import com.gigamindtechnologies.privatechat.core.encoding.B64
import com.gigamindtechnologies.privatechat.core.encoding.Hex
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnionTest {
    // Well-known public v3 onion services; their checksums must validate.
    private val known = listOf(
        "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad",
        "2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid",
    )

    @Test
    fun knownAddressesValidateAndRoundTrip() {
        for (address in known) {
            val key = OnionAddress.publicKeyOrNull(address)!!
            assertEquals(address, OnionAddress.fromPublicKey(key))
            assertEquals(address, OnionAddress.canonicalOrNull("$address.onion"))
        }
    }

    @Test
    fun rejectsTamperedOrMalformedAddresses() {
        val address = known[0]
        val tampered = (if (address[10] == 'a') 'b' else 'a').let { address.substring(0, 10) + it + address.substring(11) }
        assertNull(OnionAddress.canonicalOrNull(tampered))
        assertNull(OnionAddress.canonicalOrNull(address.uppercase()))
        assertNull(OnionAddress.canonicalOrNull(address.dropLast(1)))
        assertNull(OnionAddress.canonicalOrNull("$address extra"))
        assertNull(OnionAddress.canonicalOrNull("ADD_ONION NEW:BEST"))
    }

    @Test
    fun ed25519MatchesRfc8032TestVector1() {
        val seed = Hex.decode("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", Hex.encode(OnionKey(seed).publicKey))
    }

    @Test
    fun torKeyBlobIsAClampedExpandedKey() {
        val key = OnionKey.generate()
        val blob = key.torKeyBlob()
        assertTrue(blob.startsWith("ED25519-V3:"))
        val expanded = B64.decode(blob.removePrefix("ED25519-V3:"))
        assertEquals(64, expanded.size)
        assertEquals(0, expanded[0].toInt() and 7)
        assertEquals(64, expanded[31].toInt() and 0xc0)
        val pub = ByteArray(32).also { Ed25519.generatePublicKey(key.seedCopy(), 0, it, 0) }
        assertArrayEquals(pub, key.publicKey)
        assertEquals(key.address, OnionAddress.canonicalOrNull(key.address))
    }
}
