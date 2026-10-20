package com.gigamindtechnologies.privatechat.core.tor

import com.gigamindtechnologies.privatechat.core.crypto.Dh
import com.gigamindtechnologies.privatechat.core.node.TorDialer
import com.gigamindtechnologies.privatechat.core.node.TorOnionHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

/**
 * Talks to a real `tor` process. No internet is needed: Tor may fail to bootstrap, but it still
 * validates and registers onion services and answers on its SOCKS port.
 * Skipped when no tor binary is installed. This checks the parts the fake network cannot:
 * that Tor accepts our key format and computes the same onion address we do, and that the
 * ADD_ONION / client-authorization commands are well-formed.
 */
class RealTorIntegrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var process: Process? = null
    private lateinit var control: TorControlClient

    @Before
    fun startTor() {
        val binary = listOf("/usr/bin/tor", "/usr/sbin/tor", "/usr/local/bin/tor", "/opt/homebrew/bin/tor")
            .map(::File).firstOrNull { it.canExecute() }
        assumeTrue("tor is not installed", binary != null)
        val dir = tmp.newFolder()
        val portFile = File(dir, "control-port")
        process = ProcessBuilder(
            binary!!.path, "-f", File(dir, "torrc").apply { writeText("") }.path, "--ignore-missing-torrc",
            "--DataDirectory", File(dir, "data").path,
            "--ControlPort", "auto", "--ControlPortWriteToFile", portFile.path,
            "--CookieAuthentication", "0", "--SocksPort", "auto", "--SocksTimeout", "10",
            "--Log", "err stderr",
        ).redirectErrorStream(true).redirectOutput(File(dir, "tor.log")).start()

        val deadline = System.currentTimeMillis() + 30_000
        while (!portFile.exists() || portFile.readText().isBlank()) {
            check(System.currentTimeMillis() < deadline) { "tor did not start: " + File(dir, "tor.log").readText() }
            Thread.sleep(100)
        }
        val port = Regex(":(\\d+)").find(portFile.readText())!!.groupValues[1].toInt()
        val socket = Socket(InetAddress.getLoopbackAddress(), port)
        control = TorControlClient(socket.getInputStream(), socket.getOutputStream(), socket)
        control.authenticate()
    }

    @After
    fun stopTor() {
        if (process != null) control.close()
        process?.destroy()
        process?.waitFor()
    }

    @Test
    fun torAcceptsOurKeysAndAgreesOnTheAddress() {
        assertTrue(control.getInfo("version").startsWith("0.4"))
        assertTrue(control.socksPort() > 0)
        assertTrue(control.bootstrapProgress() in 0..100)

        repeat(5) {
            val key = OnionKey.generate()
            val clients = listOf(Dh.generate().publicKey, Dh.generate().publicKey)
            assertEquals(key.address, control.addOnion(key.torKeyBlob(), 80, "127.0.0.1:9", clients))
            control.delOnion(key.address)
        }
    }

    @Test
    fun onionHostPublishesAndRepublishesWithClientAuth() {
        val host = TorOnionHost(control)
        val key = OnionKey.generate()
        host.publish(key, 80, 9, listOf(Dh.generate().publicKey))
        // Publishing the same key twice is refused, which is why Node unpublishes first.
        assertThrows(TorControlException::class.java) { host.publish(key, 80, 9, emptyList()) }
        host.unpublish(key.address)
        host.publish(key, 80, 9, listOf(Dh.generate().publicKey, Dh.generate().publicKey))
        host.unpublish(key.address)

        val peer = OnionKey.generate().address
        host.authorizeClient(peer, Dh.generate().privateKey)
        host.authorizeClient(peer, Dh.generate().privateKey) // replacing a key is fine
        host.deauthorizeClient(peer)
    }

    @Test
    fun socksClientGetsAnErrorInsteadOfHanging() {
        val dialer = TorDialer(control.socksPort())
        // The address does not exist (and Tor may be offline); we must get a clean IOException.
        val started = System.currentTimeMillis()
        assertThrows(IOException::class.java) { dialer.dial(OnionKey.generate().address, 80).close() }
        assertTrue(System.currentTimeMillis() - started < 125_000)
    }
}
