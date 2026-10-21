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

    @Test
    fun presenceAndAppLockRoundTripEncrypted() {
        val dir = tmp.newFolder()
        val store = SecureStore(dir, sealer)
        assertEquals(PresenceState(), store.loadPresence())
        assertNull(store.loadAppLock())
        store.savePresence(PresenceState(mapOf("0123456789abcdef" to 42L), setOf("fedcba9876543210")))
        val lock = PinLock.create("2580", biometric = true)
        store.saveAppLock(lock)
        val reopened = SecureStore(dir, sealer)
        assertEquals(42L, reopened.loadPresence().lastSeen["0123456789abcdef"])
        assertEquals(setOf("fedcba9876543210"), reopened.loadPresence().hiddenBy)
        assertEquals(lock, reopened.loadAppLock())
        reopened.saveAppLock(null)
        assertNull(SecureStore(dir, sealer).loadAppLock())
    }

    @Test
    fun pinLockChecksPinsAndSlowsDownGuessing() {
        val lock = PinLock.create("123456")
        assertTrue(PinLock.matches("123456", lock))
        assertFalse(PinLock.matches("123457", lock))
        assertFalse(PinLock.matches("12a456", lock))
        assertFalse(PinLock.isValid("123"))
        assertFalse(PinLock.isValid("1234567890123"))
        // Same PIN, different salt: the stored hashes differ.
        assertFalse(lock.hash == PinLock.create("123456").hash)

        var state = lock
        repeat(4) { state = PinLock.failed(state, now = 1_000) }
        assertEquals(0L, state.blockedUntil - 1_000)
        state = PinLock.failed(state, now = 1_000)
        assertEquals(30_000L, state.blockedUntil - 1_000)
        state = PinLock.failed(state, now = 1_000)
        assertEquals(60_000L, state.blockedUntil - 1_000)
        assertEquals(15 * 60_000L, PinLock.delayAfter(100))
    }
}
