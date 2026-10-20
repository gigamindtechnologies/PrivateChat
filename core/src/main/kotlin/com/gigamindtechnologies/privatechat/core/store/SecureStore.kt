package com.gigamindtechnologies.privatechat.core.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts data at rest. [label] is bound to the ciphertext so files cannot be swapped around. */
interface Sealer {
    fun seal(plaintext: ByteArray, label: String): ByteArray
    fun open(sealed: ByteArray, label: String): ByteArray
}

/**
 * AES-256-GCM. On Android the key lives in the hardware-backed Android Keystore and never leaves
 * it; the provider picks a fresh random IV for every encryption.
 */
class AesGcmSealer(private val key: SecretKey) : Sealer {
    override fun seal(plaintext: ByteArray, label: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(label.toByteArray())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    override fun open(sealed: ByteArray, label: String): ByteArray {
        val ivSize = sealed[0].toInt() and 0xff
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 1, ivSize))
        cipher.updateAAD(label.toByteArray())
        return cipher.doFinal(sealed, 1 + ivSize, sealed.size - 1 - ivSize)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * Everything PrivateChat keeps, all of it encrypted, all of it on this phone. There is no
 * database and no server: just a few encrypted files in the app's private folder.
 *
 * - `identity.bin` and `contacts.bin` are rewritten atomically.
 * - `messages/<contactId>.log` is an append-only log of encrypted records.
 */
class SecureStore(private val dir: File, private val sealer: Sealer) {
    private val json = Json { ignoreUnknownKeys = true }
    private val identityFile get() = File(dir, "identity.bin")
    private val contactsFile get() = File(dir, "contacts.bin")
    private val messagesDir get() = File(dir, "messages")

    @Serializable
    private sealed class LogRecord {
        @Serializable
        @SerialName("m")
        data class Message(val message: ChatMessage) : LogRecord()

        @Serializable
        @SerialName("s")
        data class Status(val id: String, val outgoing: Boolean, val status: MessageStatus) : LogRecord()
    }

    @Synchronized
    fun loadIdentity(): Identity? = readSealed(identityFile, "identity")?.let {
        json.decodeFromString(Identity.serializer(), it.decodeToString())
    }

    @Synchronized
    fun saveIdentity(identity: Identity) =
        writeSealed(identityFile, "identity", json.encodeToString(Identity.serializer(), identity).encodeToByteArray())

    @Synchronized
    fun loadContacts(): List<Contact> = readSealed(contactsFile, "contacts")?.let {
        json.decodeFromString(ListSerializer(Contact.serializer()), it.decodeToString())
    } ?: emptyList()

    @Synchronized
    fun saveContacts(contacts: List<Contact>) = writeSealed(
        contactsFile,
        "contacts",
        json.encodeToString(ListSerializer(Contact.serializer()), contacts).encodeToByteArray(),
    )

    @Synchronized
    fun loadMessages(contactId: String): List<ChatMessage> {
        val file = messageFile(contactId)
        if (!file.exists()) return emptyList()
        val messages = LinkedHashMap<Pair<String, Boolean>, ChatMessage>()
        var records = 0
        DataInputStream(file.inputStream().buffered()).use { input ->
            while (true) {
                val sealed = try {
                    val size = input.readInt()
                    if (size <= 0 || size > MAX_RECORD) break
                    ByteArray(size).also { input.readFully(it) }
                } catch (_: EOFException) {
                    break // a write was interrupted; everything before it is intact
                }
                val record = runCatching {
                    json.decodeFromString(LogRecord.serializer(), sealer.open(sealed, labelFor(contactId)).decodeToString())
                }.getOrNull() ?: continue
                records++
                when (record) {
                    is LogRecord.Message -> messages[record.message.id to record.message.outgoing] = record.message
                    is LogRecord.Status -> messages[record.id to record.outgoing]?.let {
                        messages[record.id to record.outgoing] = it.copy(status = record.status)
                    }
                }
            }
        }
        val result = messages.values.toList()
        if (records > result.size * 2 + 50) rewriteMessages(contactId, result)
        return result
    }

    @Synchronized
    fun appendMessage(message: ChatMessage) = append(message.contactId, LogRecord.Message(message))

    @Synchronized
    fun updateStatus(contactId: String, id: String, outgoing: Boolean, status: MessageStatus) =
        append(contactId, LogRecord.Status(id, outgoing, status))

    @Synchronized
    fun deleteConversation(contactId: String) {
        messageFile(contactId).delete()
    }

    /** Deletes everything. The caller should also destroy the encryption key. */
    @Synchronized
    fun wipe() {
        dir.deleteRecursively()
    }

    private fun rewriteMessages(contactId: String, messages: List<ChatMessage>) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            for (m in messages) writeRecord(out, contactId, LogRecord.Message(m))
        }
        atomicWrite(messageFile(contactId), bytes.toByteArray())
    }

    private fun append(contactId: String, record: LogRecord) {
        val file = messageFile(contactId)
        file.parentFile.mkdirs()
        DataOutputStream(FileOutputStream(file, true).buffered()).use { out ->
            writeRecord(out, contactId, record)
        }
    }

    private fun writeRecord(out: DataOutputStream, contactId: String, record: LogRecord) {
        val sealed = sealer.seal(json.encodeToString(LogRecord.serializer(), record).encodeToByteArray(), labelFor(contactId))
        out.writeInt(sealed.size)
        out.write(sealed)
    }

    private fun messageFile(contactId: String): File {
        require(contactId.matches(Regex("^[0-9a-f]{16}$"))) { "Bad contact id" }
        return File(messagesDir, "$contactId.log")
    }

    private fun labelFor(contactId: String) = "messages:$contactId"

    private fun readSealed(file: File, label: String): ByteArray? {
        if (!file.exists()) return null
        return sealer.open(file.readBytes(), label)
    }

    private fun writeSealed(file: File, label: String, plaintext: ByteArray) =
        atomicWrite(file, sealer.seal(plaintext, label))

    private fun atomicWrite(file: File, bytes: ByteArray) {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("Could not save ${file.name}")
    }

    private companion object {
        const val MAX_RECORD = 1 shl 20
    }
}
