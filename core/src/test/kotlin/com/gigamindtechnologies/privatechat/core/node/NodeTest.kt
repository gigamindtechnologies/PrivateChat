package com.gigamindtechnologies.privatechat.core.node

import com.gigamindtechnologies.privatechat.core.crypto.Dh
import com.gigamindtechnologies.privatechat.core.invite.InviteCode
import com.gigamindtechnologies.privatechat.core.net.Handshake
import com.gigamindtechnologies.privatechat.core.store.AesGcmSealer
import com.gigamindtechnologies.privatechat.core.store.Identity
import com.gigamindtechnologies.privatechat.core.store.MessageStatus
import com.gigamindtechnologies.privatechat.core.store.SecureStore
import com.gigamindtechnologies.privatechat.core.tor.OnionKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.crypto.KeyGenerator

class NodeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val network = FakeTorNetwork()
    private val nodes = mutableListOf<Node>()
    private val sealer = AesGcmSealer(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    private val fastConfig = Node.Config(
        inviteLifetimeMs = 10_000,
        scanTimeoutMs = 5_000,
        scanRetryDelayMs = 50,
        retryMinMs = 100,
        retryMaxMs = 400,
        tickMs = 20,
        keepAliveMs = 500,
        readTimeoutMs = 5_000,
        handshakeTimeoutMs = 5_000,
        unpairFlushMs = 100,
    )

    private class Phone(val node: Node, val store: SecureStore, val identity: Identity, val client: FakeTorNetwork.Client)

    private fun phone(name: String, config: Node.Config = fastConfig, store: SecureStore? = null): Phone {
        val s = store ?: SecureStore(tmp.newFolder(), sealer)
        val identity = s.loadIdentity() ?: Identity.create(name, System.currentTimeMillis()).also { s.saveIdentity(it) }
        val client = network.Client()
        val node = Node(s, identity, client, client, config)
        nodes += node
        node.start()
        return Phone(node, s, identity, client)
    }

    @After
    fun tearDown() = nodes.forEach { it.stop() }

    private fun <T : Any> await(what: String, timeoutMs: Long = 10_000, check: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            check()?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun pair(alice: Phone, bob: Phone) {
        alice.node.showInvite()
        val code = await("code") { (alice.node.pairing.value as? Node.Pairing.ShowingCode)?.code }
        bob.node.scanInvite(code)
        val incoming = await("request") { alice.node.pairing.value as? Node.Pairing.Incoming }
        val waiting = await("waiting") { bob.node.pairing.value as? Node.Pairing.AwaitingApproval }
        assertEquals(bob.identity.name, incoming.name)
        assertEquals(incoming.safetyCode, waiting.safetyCode)
        alice.node.acceptPairing()
        await("paired") { (alice.node.pairing.value is Node.Pairing.Paired && bob.node.pairing.value is Node.Pairing.Paired).takeIf { it } }
    }

    @Test
    fun pairThenChat() {
        val alice = phone("Alice")
        val bob = phone("Bob")
        // Nothing is published before you have contacts.
        assertNull(network.services[alice.node.onionAddress])

        pair(alice, bob)
        val bobContact = alice.node.contacts.value.single()
        val aliceContact = bob.node.contacts.value.single()
        assertEquals("Bob", bobContact.name)
        assertEquals("Alice", aliceContact.name)
        assertEquals((bob.node.pairing.value as Node.Pairing.Paired).contactId, aliceContact.id)
        // The one-time invite address is gone; the permanent ones are published for each other only.
        assertEquals(setOf(alice.node.onionAddress, bob.node.onionAddress), network.services.keys)

        await("online") { (alice.node.online.value == setOf(bobContact.id) && bob.node.online.value == setOf(aliceContact.id)).takeIf { it } }

        assertEquals(Node.SendResult.SENT, runBlocking { bob.node.sendText(aliceContact.id, "  hello alice  ") })
        await("delivered") { bob.node.messages(aliceContact.id).value.singleOrNull()?.takeIf { it.status == MessageStatus.DELIVERED } }
        val received = alice.node.messages(bobContact.id).value.single()
        assertEquals("hello alice", received.body)
        assertFalse(received.outgoing)

        runBlocking { alice.node.sendText(bobContact.id, "hi bob") }
        await("reply") { bob.node.messages(aliceContact.id).value.find { !it.outgoing }?.takeIf { it.body == "hi bob" } }
        assertEquals(Node.SendResult.INVALID, runBlocking { alice.node.sendText(bobContact.id, "   ") })
    }

    @Test
    fun messagesWaitOnThePhoneUntilTheContactComesOnline() {
        val alice = phone("Alice")
        val bob = phone("Bob")
        pair(alice, bob)
        val aliceId = bob.node.contacts.value.single().id
        await("online") { bob.node.online.value.takeIf { aliceId in it } }
        runBlocking { bob.node.sendText(aliceId, "before") }
        await("delivered") { bob.node.messages(aliceId).value.single().takeIf { it.status == MessageStatus.DELIVERED } }

        alice.node.stop()
        await("offline") { bob.node.online.value.takeIf { it.isEmpty() } }
        // No server: the message waits on Bob's phone (the clock icon).
        assertEquals(Node.SendResult.QUEUED, runBlocking { bob.node.sendText(aliceId, "while you were away") })
        val queued = bob.node.messages(aliceId).value.single { it.body == "while you were away" }
        assertEquals(MessageStatus.SENDING, queued.status)

        // Alice opens the app again: history comes back from the encrypted files, and the
        // waiting message is delivered automatically.
        val alice2 = phone("Alice", store = alice.store)
        val bobId = alice2.node.contacts.value.single().id
        await("queued message arrives") { alice2.node.messages(bobId).value.find { it.body == "while you were away" } }
        await("bob sees delivered") {
            bob.node.messages(aliceId).value.single { it.id == queued.id }.takeIf { it.status == MessageStatus.DELIVERED }
        }
        assertEquals(listOf("before", "while you were away"), alice2.node.messages(bobId).value.map { it.body })
    }

    @Test
    fun ticksGoFromDeliveredToReadWhenTheChatIsOpened() {
        val alice = phone("Alice")
        val bob = phone("Bob")
        pair(alice, bob)
        val aliceId = bob.node.contacts.value.single().id
        val bobId = alice.node.contacts.value.single().id
        await("online") { bob.node.online.value.takeIf { aliceId in it } }

        assertEquals(Node.SendResult.SENT, runBlocking { bob.node.sendText(aliceId, "hi") })
        val sent = await("delivered") { bob.node.messages(aliceId).value.single().takeIf { it.status == MessageStatus.DELIVERED } }
        assertEquals(MessageStatus.RECEIVED, alice.node.messages(bobId).value.single().status)

        runBlocking { alice.node.markRead(bobId) }
        assertEquals(MessageStatus.READ, alice.node.messages(bobId).value.single().status)
        await("green ticks") { bob.node.messages(aliceId).value.single { it.id == sent.id }.takeIf { it.status == MessageStatus.READ } }
    }

    @Test
    fun readReceiptsCanBeTurnedOff() {
        val alice = phone("Alice")
        val bob = phone("Bob")
        pair(alice, bob)
        val aliceId = bob.node.contacts.value.single().id
        val bobId = alice.node.contacts.value.single().id
        await("online") { bob.node.online.value.takeIf { aliceId in it } }
        alice.node.sendReadReceipts = false

        runBlocking { bob.node.sendText(aliceId, "did you read this?") }
        await("delivered") { bob.node.messages(aliceId).value.single().takeIf { it.status == MessageStatus.DELIVERED } }
        runBlocking { alice.node.markRead(bobId) }
        assertEquals(MessageStatus.READ, alice.node.messages(bobId).value.single().status)
        // Give a receipt time to arrive if one had been (wrongly) sent.
        runBlocking { bob.node.sendText(aliceId, "ping") }
        await("second delivered") { bob.node.messages(aliceId).value.last().takeIf { it.status == MessageStatus.DELIVERED } }
        Thread.sleep(300)
        assertEquals(MessageStatus.DELIVERED, bob.node.messages(aliceId).value.first().status)
    }

    @Test
    fun readReceiptsReachTheSenderAfterTheyReconnect() {
        val alice = phone("Alice")
        val bob = phone("Bob")
        pair(alice, bob)
        val aliceId = bob.node.contacts.value.single().id
        val bobId = alice.node.contacts.value.single().id
        await("online") { bob.node.online.value.takeIf { aliceId in it } }
        runBlocking { bob.node.sendText(aliceId, "read me later") }
        await("delivered") { bob.node.messages(aliceId).value.single().takeIf { it.status == MessageStatus.DELIVERED } }

        bob.node.stop()
        await("bob offline") { alice.node.online.value.takeIf { it.isEmpty() } }
        runBlocking { alice.node.markRead(bobId) } // Bob is offline: the receipt has to wait

        val bob2 = phone("Bob", store = bob.store)
        await("green ticks after reconnect") {
            bob2.node.messages(aliceId).value.single().takeIf { it.status == MessageStatus.READ }
        }
    }

    @Test
    fun rejectedRequestLeavesNoTrace() {
        val alice = phone("Alice")
        val mallory = phone("Mallory")
        alice.node.showInvite()
        val code = await("code") { (alice.node.pairing.value as? Node.Pairing.ShowingCode)?.code }
        mallory.node.scanInvite(code)
        await("request") { alice.node.pairing.value as? Node.Pairing.Incoming }
        alice.node.rejectPairing()
        await("rejected") { mallory.node.pairing.value.takeIf { it == Node.Pairing.Failed(Node.FailReason.REJECTED) } }
        // Alice keeps showing her code for the right person.
        await("showing again") { alice.node.pairing.value as? Node.Pairing.ShowingCode }
        assertTrue(alice.node.contacts.value.isEmpty())
        assertTrue(mallory.node.contacts.value.isEmpty())
        alice.node.cancelPairing()
        await("invite unpublished") { network.services.isEmpty().takeIf { it } }
    }

    @Test
    fun invalidExpiredAndUnreachableCodes() {
        val alice = phone("Alice", fastConfig.copy(inviteLifetimeMs = 300))
        val bob = phone("Bob", fastConfig.copy(scanTimeoutMs = 300))

        bob.node.scanInvite("HELLO WORLD")
        assertEquals(Node.Pairing.Failed(Node.FailReason.INVALID_CODE), bob.node.pairing.value)

        alice.node.showInvite()
        await("expired") { alice.node.pairing.value.takeIf { it == Node.Pairing.Failed(Node.FailReason.EXPIRED) } }
        await("unpublished") { network.services.isEmpty().takeIf { it } }

        val nobody = InviteCode(OnionKey.generate().publicKey, Dh.generate().publicKey).encode()
        bob.node.scanInvite(nobody)
        await("unreachable") { bob.node.pairing.value.takeIf { it == Node.Pairing.Failed(Node.FailReason.UNREACHABLE) } }
    }

    @Test
    fun strangersCannotReachYou() {
        val alice = phone("Alice")
        val bob = phone("Bob")
        val mallory = phone("Mallory")
        pair(alice, bob)

        // Knowing Alice's address is not enough: without a client key Tor will not even find it.
        assertThrows(IOException::class.java) { mallory.client.dial(alice.node.onionAddress, 80) }

        // Even with a direct line to Alice's service, an unknown key gets no answer.
        val port = network.services[alice.node.onionAddress]!!.localPort
        val socket = Socket(InetAddress.getLoopbackAddress(), port)
        assertThrows(IOException::class.java) {
            Handshake.initiate(
                socket,
                "PrivateChat/1 contact".toByteArray(),
                mallory.identity.staticKeyPair(),
                alice.identity.staticKeyPair().publicKey,
            )
        }
    }

    @Test
    fun removingAContactRemovesItOnBothPhones() {
        val alice = phone("Alice")
        val bob = phone("Bob")
        pair(alice, bob)
        val aliceId = bob.node.contacts.value.single().id
        val bobId = alice.node.contacts.value.single().id
        await("online") { alice.node.online.value.takeIf { bobId in it } }
        runBlocking { alice.node.sendText(bobId, "hello") }
        await("received") { bob.node.messages(aliceId).value.singleOrNull() }

        runBlocking { bob.node.removeContact(aliceId) }
        assertTrue(bob.node.contacts.value.isEmpty())
        assertTrue(bob.node.messages(aliceId).value.isEmpty())
        await("alice forgot bob") { alice.node.contacts.value.isEmpty().takeIf { it } }
        // With no contacts left, neither phone publishes an onion service at all.
        await("unpublished") { network.services.isEmpty().takeIf { it } }
    }

    @Test
    fun pairingAgainKeepsTheConversation() {
        val alice = phone("Alice")
        val bob = phone("Bob")
        pair(alice, bob)
        val bobId = alice.node.contacts.value.single().id
        await("online") { alice.node.online.value.takeIf { bobId in it } }
        runBlocking { alice.node.sendText(bobId, "first") }
        await("delivered") { alice.node.messages(bobId).value.single().takeIf { it.status == MessageStatus.DELIVERED } }

        pair(alice, bob)
        assertEquals(1, alice.node.contacts.value.size)
        assertEquals("first", alice.node.messages(bobId).value.single().body)
        await("online again") { alice.node.online.value.takeIf { bobId in it } }
        runBlocking { alice.node.sendText(bobId, "second") }
        val aliceId = bob.node.contacts.value.single().id
        await("second") { bob.node.messages(aliceId).value.find { it.body == "second" } }
    }
}
