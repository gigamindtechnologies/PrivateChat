package com.gigamindtechnologies.privatechat.core.tor

import com.gigamindtechnologies.privatechat.core.encoding.B64
import com.gigamindtechnologies.privatechat.core.encoding.Base32
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Runs the client against a scripted fake Tor control port. */
class TorControlClientTest {
    private lateinit var server: ServerSocket
    private lateinit var client: TorControlClient
    private val received = LinkedBlockingQueue<String>()
    private val replies = LinkedBlockingQueue<String>()

    @Before
    fun setUp() {
        server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.accept().use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                while (true) {
                    val line = reader.readLine() ?: break
                    received.put(line)
                    s.getOutputStream().write(replies.take().toByteArray())
                }
            }
        }
        val socket = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        client = TorControlClient(socket.getInputStream(), socket.getOutputStream(), socket)
    }

    @After
    fun tearDown() {
        client.close()
        server.close()
    }

    @Test
    fun parsesGetInfoAndSkipsAsyncEvents() {
        replies.put("650 STATUS_CLIENT NOTICE CIRCUIT_ESTABLISHED\r\n250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=85 TAG=ap_conn SUMMARY=\"Connecting\"\r\n250 OK\r\n")
        assertEquals(85, client.bootstrapProgress())
        assertEquals("GETINFO status/bootstrap-phase", received.take())

        replies.put("250-net/listeners/socks=\"127.0.0.1:9050\"\r\n250 OK\r\n")
        assertEquals(9050, client.socksPort())
    }

    @Test
    fun parsesMultiLineData() {
        replies.put("250+config-text=\r\nSocksPort 9050\r\n..dotted\r\n.\r\n250 OK\r\n")
        assertEquals("SocksPort 9050\n.dotted", client.getInfo("config-text"))
    }

    @Test
    fun addOnionSendsClientAuthAndReturnsServiceId() {
        val key = OnionKey.generate()
        val client1 = ByteArray(32) { 1 }
        replies.put("250-ServiceID=${key.address}\r\n250 OK\r\n")
        assertEquals(key.address, client.addOnion(key.torKeyBlob(), 80, "127.0.0.1:4444", listOf(client1)))
        assertEquals(
            "ADD_ONION ${key.torKeyBlob()} Flags=V3Auth Port=80,127.0.0.1:4444 ClientAuthV3=${Base32.encode(client1)}",
            received.take(),
        )
    }

    @Test
    fun clientAuthCommands() {
        val address = OnionKey.generate().address
        val privateKey = ByteArray(32) { 7 }
        replies.put("250 OK\r\n")
        client.addClientAuth(address, privateKey)
        assertEquals("ONION_CLIENT_AUTH_ADD $address x25519:${B64.encode(privateKey)}", received.take())
        replies.put("251 No credentials for \"$address\"\r\n")
        client.removeClientAuth(address)
        assertEquals("ONION_CLIENT_AUTH_REMOVE $address", received.take())
    }

    @Test
    fun errorRepliesThrow() {
        replies.put("552 Unrecognized key \"nope\"\r\n")
        val e = assertThrows(TorControlException::class.java) { client.getInfo("nope") }
        assertEquals(552, e.code)
    }

    @Test
    fun refusesArgumentsThatCouldInjectCommands() {
        assertThrows(IllegalArgumentException::class.java) { client.delOnion("abc\r\nSIGNAL HALT") }
        assertThrows(IllegalArgumentException::class.java) { client.delOnion("aaaa") }
        assertThrows(IllegalArgumentException::class.java) {
            client.addOnion("ED25519-V3:abc Flags=Detach", 80, "127.0.0.1:1", emptyList())
        }
    }
}
