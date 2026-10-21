package com.gigamindtechnologies.privatechat.core.net

import com.gigamindtechnologies.privatechat.core.crypto.Dh
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NetTest {
    private val prologue = "test".toByteArray()

    private fun connectedPair(): Pair<Socket, Socket> {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            return client to server.accept()
        }
    }

    @Test
    fun handshakeThenMessagesBothWays() {
        val (a, b) = connectedPair()
        val alice = Dh.generate()
        val bob = Dh.generate()
        val bobSide = CompletableFuture.supplyAsync { Handshake.respond(b, prologue, bob) { it.contentEquals(alice.publicKey) }!! }
        val aliceChannel = Handshake.initiate(a, prologue, alice, bob.publicKey)
        val bobChannel = bobSide.get(5, TimeUnit.SECONDS)

        assertArrayEquals(alice.publicKey, bobChannel.remoteStatic)
        assertArrayEquals(aliceChannel.handshakeHash, bobChannel.handshakeHash)
        aliceChannel.send("hello".toByteArray())
        assertEquals("hello", bobChannel.receive().decodeToString())
        bobChannel.send(ByteArray(0))
        assertEquals(0, aliceChannel.receive().size)
        val big = ByteArray(SecureChannel.MAX_MESSAGE) { it.toByte() }
        thread { aliceChannel.send(big) }
        assertArrayEquals(big, bobChannel.receive())
        aliceChannel.close()
        bobChannel.close()
    }

    @Test
    fun unauthorizedCallerGetsNoReply() {
        val (a, b) = connectedPair()
        val bob = Dh.generate()
        val bobSide = CompletableFuture.supplyAsync { Handshake.respond(b, prologue, bob) { false } }
        assertThrows(EOFException::class.java) { Handshake.initiate(a, prologue, Dh.generate(), bob.publicKey) }
        assertNull(bobSide.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun paddingHidesMessageLength() {
        for (size in listOf(0, 1, 100, 254, 255, 1000)) {
            val padded = Padding.pad(ByteArray(size))
            assertEquals(0, padded.size % Padding.BLOCK)
            assertEquals(size, Padding.unpad(padded).size)
        }
        assertEquals(Padding.pad(ByteArray(1)).size, Padding.pad(ByteArray(200)).size)
    }

    @Test
    fun socks5SendsHostNameAndReportsErrors() {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { proxy ->
            val requests = CompletableFuture<ByteArray>()
            thread(isDaemon = true) {
                for (reply in listOf(0, 0xf0)) {
                    proxy.accept().use { s ->
                        val input = DataInputStream(s.getInputStream())
                        val out = s.getOutputStream()
                        input.readFully(ByteArray(3))
                        out.write(byteArrayOf(5, 0))
                        val header = ByteArray(5).also { input.readFully(it) }
                        val rest = ByteArray(header[4] + 2).also { input.readFully(it) }
                        if (reply == 0) requests.complete(header + rest)
                        out.write(byteArrayOf(5, reply.toByte(), 0, 1, 127, 0, 0, 1, 0, 80))
                        out.write("ok".toByteArray())
                        out.flush()
                        Thread.sleep(200)
                    }
                }
            }
            val address = InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.localPort)
            Socks5.connect(address, "example.onion", 80, 2000, 2000).use { s ->
                assertEquals("ok", String(DataInputStream(s.getInputStream()).let { i -> ByteArray(2).also { i.readFully(it) } }))
            }
            val expected = byteArrayOf(5, 1, 0, 3, 13) + "example.onion".toByteArray() + byteArrayOf(0, 80)
            assertArrayEquals(expected, requests.get(5, TimeUnit.SECONDS))
            val e = assertThrows(SocksException::class.java) { Socks5.connect(address, "example.onion", 80, 2000, 2000) }
            assertEquals(0xf0, e.reply)
        }
    }

    @Test
    fun socks5FailsCleanlyWhenProxyIsDown() {
        val port = ServerSocket(0).use { it.localPort }
        assertThrows(IOException::class.java) {
            Socks5.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), "x.onion", 80, 1000, 1000)
        }
    }
}
