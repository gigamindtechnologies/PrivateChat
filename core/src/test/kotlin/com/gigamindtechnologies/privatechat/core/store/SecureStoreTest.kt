package com.gigamindtechnologies.privatechat.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class SecureStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sealer = AesGcmSealer(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    private fun contact(id: String) = Contact(id, "Bob", "s", "o", "a", "i", 1)

    private fun message(id: String, contactId: String = "0123456789abcdef", status: MessageStatus = MessageStatus.SENDING) =
        ChatMessage(id, contactId, true, "secret text $id", 5, status)

    @Test
    fun identityAndContactsRoundTripEncrypted() {
        val dir = tmp.newFolder()
        val store = SecureStore(dir, sealer)
        assertNull(store.loadIdentity())
        val identity = Identity.create("Alice", 1)
        store.saveIdentity(identity)
        store.saveContacts(listOf(contact("0123456789abcdef")))
        val reopened = SecureStore(dir, sealer)
        assertEquals(identity, reopened.loadIdentity())
        assertEquals("Bob", reopened.loadContacts().single().name)
        // Nothing readable on disk.
        for (file in dir.walk().filter { it.isFile }) {
            val text = file.readBytes().decodeToString()
            assertFalse(text.contains("Alice") || text.contains("Bob"))
        }
    }

    @Test
    fun messageLogAppliesStatusUpdatesAndSurvivesTruncation() {
        val dir = tmp.newFolder()
        val store = SecureStore(dir, sealer)
        val cid = "0123456789abcdef"
        store.appendMessage(message("a"))
        store.appendMessage(message("b"))
        store.updateStatus(cid, "a", true, MessageStatus.DELIVERED)
        assertEquals(listOf(MessageStatus.DELIVERED, MessageStatus.SENDING), store.loadMessages(cid).map { it.status })
        assertFalse(File(dir, "messages/$cid.log").readBytes().decodeToString().contains("secret"))

        // Simulate the phone dying halfway through a write.
        val log = File(dir, "messages/$cid.log")
        log.appendBytes(byteArrayOf(0, 0, 1, 0, 42, 42))
        assertEquals(2, store.loadMessages(cid).size)

        store.deleteConversation(cid)
        assertTrue(store.loadMessages(cid).isEmpty())
    }

    @Test
    fun filesCannotBeSwappedOrReadWithAnotherKey() {
        val dir = tmp.newFolder()
        val store = SecureStore(dir, sealer)
        store.saveIdentity(Identity.create("Alice", 1))
        store.saveContacts(emptyList())
        File(dir, "identity.bin").copyTo(File(dir, "contacts.bin"), overwrite = true)
        assertThrows(Exception::class.java) { store.loadContacts() }

        val otherKey = AesGcmSealer(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        assertThrows(Exception::class.java) { SecureStore(dir, otherKey).loadIdentity() }
    }

    @Test
    fun compactsLogsWithManyStatusUpdates() {
        val dir = tmp.newFolder()
        val store = SecureStore(dir, sealer)
        val cid = "0123456789abcdef"
        store.appendMessage(message("a"))
        repeat(100) { store.updateStatus(cid, "a", true, if (it % 2 == 0) MessageStatus.FAILED else MessageStatus.SENDING) }
        val before = File(dir, "messages/$cid.log").length()
        assertEquals(MessageStatus.SENDING, store.loadMessages(cid).single().status)
        assertTrue(File(dir, "messages/$cid.log").length() < before)
        assertEquals(MessageStatus.SENDING, store.loadMessages(cid).single().status)
    }

    @Test
    fun wipeRemovesEverything() {
        val dir = tmp.newFolder()
        val store = SecureStore(dir, sealer)
        store.saveIdentity(Identity.create("Alice", 1))
        store.appendMessage(message("a"))
        store.wipe()
        assertFalse(dir.exists())
        assertNull(store.loadIdentity())
    }
}
