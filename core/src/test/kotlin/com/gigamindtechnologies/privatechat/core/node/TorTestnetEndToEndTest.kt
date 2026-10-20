package com.gigamindtechnologies.privatechat.core.node

import com.gigamindtechnologies.privatechat.core.invite.InviteCode
import com.gigamindtechnologies.privatechat.core.net.SocksException
import com.gigamindtechnologies.privatechat.core.store.AesGcmSealer
import com.gigamindtechnologies.privatechat.core.store.Identity
import com.gigamindtechnologies.privatechat.core.store.MessageStatus
import com.gigamindtechnologies.privatechat.core.store.SecureStore
import com.gigamindtechnologies.privatechat.core.tor.TorControlClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.Socket
import javax.crypto.KeyGenerator

/**
 * The whole flow over real Tor: two Tor clients on a private test network (built with Tor's
 * chutney tool), one PrivateChat engine each, QR pairing, client-authorized onion services,
 * and messages both ways.
 *
 * Skipped unless PRIVATECHAT_TESTNET_TORRC points to a torrc fragment for the test network
 * (its "TestingTorNetwork 1" and "DirAuthority ..." lines). See README.md.
 */
class TorTestnetEndToEndTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val processes = mutableListOf<Process>()
    private val nodes = mutableListOf<Node>()
    private val sealer = AesGcmSealer(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    private class TorClient(val control: TorControlClient, val socksPort: Int)

    private fun startTor(name: String, baseConfig: String): TorClient {
        val dir = tmp.newFolder(name)
        val portFile = File(dir, "control-port")
        File(dir, "torrc").writeText(
            baseConfig + "\n" + """
                DataDirectory ${File(dir, "data").path}
                SocksPort auto ExtendedErrors
                ControlPort auto
                ControlPortWriteToFile ${portFile.path}
                CookieAuthentication 0
                Log notice file ${File(dir, "notice.log").path}
            """.trimIndent(),
        )
        processes += ProcessBuilder("tor", "-f", File(dir, "torrc").path)
            .redirectErrorStream(true).redirectOutput(File(dir, "stdout.log")).start()
        val deadline = System.currentTimeMillis() + 30_000
        while (!portFile.exists() || portFile.readText().isBlank()) {
            check(System.currentTimeMillis() < deadline) { "$name: tor did not start" }
            Thread.sleep(100)
        }
        val port = Regex(":(\\d+)").find(portFile.readText())!!.groupValues[1].toInt()
        val socket = Socket(InetAddress.getLoopbackAddress(), port)
        val control = TorControlClient(socket.getInputStream(), socket.getOutputStream(), socket)
        control.authenticate()
        await("$name bootstrap", 180_000) { (control.bootstrapProgress() == 100).takeIf { it } }
        return TorClient(control, control.socksPort())
    }

    private fun startNode(name: String, tor: TorClient): Node {
        val store = SecureStore(tmp.newFolder("$name-store"), sealer)
        val identity = Identity.create(name, System.currentTimeMillis()).also { store.saveIdentity(it) }
        val config = Node.Config(retryMinMs = 2_000, retryMaxMs = 10_000, tickMs = 200, scanRetryDelayMs = 1_000)
        val log = { msg: String, e: Throwable? -> println("[$name] $msg ${e ?: ""}") }
        return Node(store, identity, TorOnionHost(tor.control), TorDialer(tor.socksPort), config, log).also {
            nodes += it
            it.start()
        }
    }

    @After
    fun tearDown() {
        nodes.forEach { it.stop() }
        processes.forEach { it.destroy() }
    }

    private fun <T : Any> await(what: String, timeoutMs: Long = 180_000, check: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            runCatching(check).getOrNull()?.let { return it }
            Thread.sleep(250)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    @Test
    fun pairAndChatOverRealTor() {
        val torrc = System.getenv("PRIVATECHAT_TESTNET_TORRC")
        assumeTrue("PRIVATECHAT_TESTNET_TORRC not set", torrc != null)
        val base = File(torrc!!).readText()

        val aliceTor = startTor("alice", base)
        val bobTor = startTor("bob", base)
        val malloryTor = startTor("mallory", base)
        val alice = startNode("Alice", aliceTor)
        val bob = startNode("Bob", bobTor)

        var started = System.currentTimeMillis()
        alice.showInvite()
        val code = await("invite code") { (alice.pairing.value as? Node.Pairing.ShowingCode)?.code }
        bob.scanInvite(code)
        val incoming = await("pair request over Tor") { alice.pairing.value as? Node.Pairing.Incoming }
        val waiting = await("awaiting approval") { bob.pairing.value as? Node.Pairing.AwaitingApproval }
        assertEquals("Bob", incoming.name)
        assertEquals(incoming.safetyCode, waiting.safetyCode)
        alice.acceptPairing()
        await("paired") { (bob.pairing.value as? Node.Pairing.Paired)?.takeIf { alice.pairing.value is Node.Pairing.Paired } }
        println("Paired over Tor in ${System.currentTimeMillis() - started} ms")

        val aliceId = bob.contacts.value.single().id
        val bobId = alice.contacts.value.single().id
        started = System.currentTimeMillis()
        await("connected over client-authorized onion services") {
            (aliceId in bob.online.value && bobId in alice.online.value).takeIf { it }
        }
        println("Connected in ${System.currentTimeMillis() - started} ms")

        assertEquals(Node.SendResult.SENT, runBlocking { bob.sendText(aliceId, "Hello Alice, over Tor") })
        await("delivered") { bob.messages(aliceId).value.single().takeIf { it.status == MessageStatus.DELIVERED } }
        assertEquals("Hello Alice, over Tor", alice.messages(bobId).value.single().body)
        assertEquals(Node.SendResult.SENT, runBlocking { alice.sendText(bobId, "Hi Bob") })
        await("reply") { bob.messages(aliceId).value.find { it.body == "Hi Bob" } }

        // A stranger who somehow learned Alice's onion address still cannot reach it: without a
        // client-authorization key Tor cannot even decrypt the descriptor (SOCKS error F4).
        val mallory = TorDialer(malloryTor.socksPort)
        val refused = assertThrows(SocksException::class.java) { mallory.dial(alice.onionAddress, 80) }
        assertEquals(0xF4, refused.reply)
        // Control: the stranger's Tor works fine for an onion service without client authorization.
        alice.showInvite()
        val invite = InviteCode.decode(await("second code") { (alice.pairing.value as? Node.Pairing.ShowingCode)?.code })!!
        await("stranger reaches an open onion service") { mallory.dial(invite.onionAddress, 80).also { it.close() } }
        alice.cancelPairing()
    }
}
