package com.gigamindtechnologies.privatechat.core.store

import com.gigamindtechnologies.privatechat.core.crypto.Dh
import com.gigamindtechnologies.privatechat.core.crypto.Hashes
import com.gigamindtechnologies.privatechat.core.crypto.KeyPair
import com.gigamindtechnologies.privatechat.core.crypto.SecureRandomHolder
import com.gigamindtechnologies.privatechat.core.encoding.B64
import com.gigamindtechnologies.privatechat.core.encoding.Base32
import com.gigamindtechnologies.privatechat.core.encoding.Hex
import com.gigamindtechnologies.privatechat.core.tor.OnionKey
import kotlinx.serialization.Serializable

/**
 * Who you are. Created on the phone, never registered anywhere: no phone number, no email.
 * [name] is only ever sent to people you pair with.
 */
@Serializable
data class Identity(
    val name: String,
    val staticPrivateKey: String,
    val onionSeed: String,
    val createdAt: Long,
) {
    fun staticKeyPair(): KeyPair = Dh.fromPrivate(B64.decode(staticPrivateKey))

    fun onionKey(): OnionKey = OnionKey(B64.decode(onionSeed))

    /** A short, human-readable form of your public key, e.g. "K7QX-M2FA-9RTE-W4PB". */
    fun fingerprint(): String = fingerprintOf(staticKeyPair().publicKey)

    companion object {
        fun create(name: String, now: Long): Identity = Identity(
            name = name,
            staticPrivateKey = B64.encode(Dh.generate().privateKey),
            onionSeed = B64.encode(SecureRandomHolder.bytes(32)),
            createdAt = now,
        )

        fun fingerprintOf(publicKey: ByteArray): String =
            Base32.encode(Hashes.sha256(publicKey).copyOf(10)).chunked(4).joinToString("-")
    }
}

@Serializable
data class Contact(
    /** Derived from [staticKey], so pairing with the same person again keeps the conversation. */
    val id: String,
    /** Your local name for this person. */
    val name: String,
    /** Their long-term X25519 public key (base64). Every connection proves they hold it. */
    val staticKey: String,
    /** Their onion address (56 chars, no ".onion"). */
    val onion: String,
    /** x25519 private key (base64) they gave us so we can reach their client-authorized onion service. */
    val outboundAuthKey: String,
    /** x25519 public key (base64) we authorized on our onion service for them. */
    val inboundAuthKey: String,
    val addedAt: Long,
) {
    fun staticKeyBytes(): ByteArray = B64.decode(staticKey)

    companion object {
        fun idFor(staticKey: ByteArray): String = Hex.encode(Hashes.sha256(staticKey).copyOf(8))
    }
}

/**
 * Outgoing: [SENDING] (waiting on this phone) → [SENT] (handed to their phone) → [DELIVERED]
 * (their phone saved it) → [READ] (they opened the chat).
 * Incoming: [RECEIVED] → [READ] (we opened the chat).
 */
@Serializable
enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ,

    /** Written by older versions; treated as [SENDING] and sent again automatically. */
    FAILED,
    RECEIVED,
}

@Serializable
data class ChatMessage(
    val id: String,
    val contactId: String,
    val outgoing: Boolean,
    val body: String,
    val timestamp: Long,
    val status: MessageStatus,
)
