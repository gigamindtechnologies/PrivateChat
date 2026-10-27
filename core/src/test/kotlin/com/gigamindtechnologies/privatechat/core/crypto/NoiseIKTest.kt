package com.gigamindtechnologies.privatechat.core.crypto

import com.gigamindtechnologies.privatechat.core.encoding.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NoiseIKTest {

    /** Official vectors from the cacophony project, shared by most Noise implementations. */
    @Test
    fun matchesCacophonyVectors() {
        val text = javaClass.getResourceAsStream("/noise_ik_vectors.json")!!.readBytes().decodeToString()
        val vectors = Json.parseToJsonElement(text).jsonObject["vectors"]!!.jsonArray
        assertEquals(1, vectors.size)
        for (v in vectors) runVector(v.jsonObject)
    }

    private fun runVector(v: JsonObject) {
        fun hex(name: String) = Hex.decode(v[name]!!.jsonPrimitive.content)
        assertEquals(NoiseIK.PROTOCOL_NAME, v["protocol_name"]!!.jsonPrimitive.content)

        val initiator = NoiseIK.initiator(
            prologue = hex("init_prologue"),
            localStatic = Dh.fromPrivate(hex("init_static")),
            remoteStatic = hex("init_remote_static"),
            ephemeral = Dh.fromPrivate(hex("init_ephemeral")),
        )
        val responder = NoiseIK.responder(
            prologue = hex("resp_prologue"),
            localStatic = Dh.fromPrivate(hex("resp_static")),
            ephemeral = Dh.fromPrivate(hex("resp_ephemeral")),
        )
        val messages = v["messages"]!!.jsonArray.map {
            Hex.decode(it.jsonObject["payload"]!!.jsonPrimitive.content) to
                Hex.decode(it.jsonObject["ciphertext"]!!.jsonPrimitive.content)
        }

        val m1 = initiator.writeMessage1(messages[0].first)
        assertArrayEquals(messages[0].second, m1)
        assertArrayEquals(messages[0].first, responder.readMessage1(m1))
        assertArrayEquals(Dh.fromPrivate(hex("init_static")).publicKey, responder.remoteStatic)

        val m2 = responder.writeMessage2(messages[1].first)
        assertArrayEquals(messages[1].second, m2)
        assertArrayEquals(messages[1].first, initiator.readMessage2(m2))

        assertArrayEquals(hex("handshake_hash"), initiator.handshakeHash)
        assertArrayEquals(hex("handshake_hash"), responder.handshakeHash)

        val i = initiator.split()
        val r = responder.split()
        for ((index, msg) in messages.drop(2).withIndex()) {
            val (payload, ciphertext) = msg
            val fromInitiator = index % 2 == 0
            val (sender, receiver) = if (fromInitiator) i.send to r.receive else r.send to i.receive
            val c = sender.encryptWithAd(ByteArray(0), payload)
            assertArrayEquals("transport message ${index + 2}", ciphertext, c)
            assertArrayEquals(payload, receiver.decryptWithAd(ByteArray(0), c))
        }
    }

    @Test
    fun responderRejectsTamperedMessage() {
        val alice = Dh.generate()
        val bob = Dh.generate()
        val init = NoiseIK.initiator("p".toByteArray(), alice, bob.publicKey)
        val m1 = init.writeMessage1("hi".toByteArray())
        m1[40] = (m1[40].toInt() xor 1).toByte()
        assertThrows(CryptoException::class.java) {
            NoiseIK.responder("p".toByteArray(), bob).readMessage1(m1)
        }
    }

    @Test
    fun differentProloguesDoNotInteroperate() {
        val alice = Dh.generate()
        val bob = Dh.generate()
        val m1 = NoiseIK.initiator("invite".toByteArray(), alice, bob.publicKey).writeMessage1()
        assertThrows(CryptoException::class.java) {
            NoiseIK.responder("contact".toByteArray(), bob).readMessage1(m1)
        }
    }

    @Test
    fun wrongResponderKeyFails() {
        val alice = Dh.generate()
        val bob = Dh.generate()
        val mallory = Dh.generate()
        val m1 = NoiseIK.initiator("p".toByteArray(), alice, bob.publicKey).writeMessage1()
        assertThrows(CryptoException::class.java) {
            NoiseIK.responder("p".toByteArray(), mallory).readMessage1(m1)
        }
    }
}
