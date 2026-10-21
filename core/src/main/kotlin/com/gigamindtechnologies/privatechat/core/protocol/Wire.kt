package com.gigamindtechnologies.privatechat.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything two phones say to each other, always inside an encrypted [SecureChannel]. */
@Serializable
sealed class Wire {
    @Serializable
    @SerialName("txt")
    data class Text(val id: String, val body: String, val sentAt: Long) : Wire()

    @Serializable
    @SerialName("ack")
    data class Ack(val id: String) : Wire()

    /**
     * First message of every connection (and again when the setting changes): whether the sender
     * allows the receiver to show when they are online and last seen. Older versions ignore it.
     */
    @Serializable
    @SerialName("pres")
    data class Presence(val show: Boolean) : Wire()

    /** "I have read these messages of yours." Only sent if read receipts are on. */
    @Serializable
    @SerialName("read")
    data class Read(val ids: List<String>) : Wire()

    @Serializable
    @SerialName("ping")
    data object Ping : Wire()

    @Serializable
    @SerialName("pong")
    data object Pong : Wire()

    /** "I removed you": the receiver forgets the sender too. */
    @Serializable
    @SerialName("bye")
    data object Unpair : Wire()

    /**
     * Sent by the person who scanned the QR code. [authKey] is a fresh x25519 private key that
     * lets the receiver reach the sender's (client-authorized) onion address.
     */
    @Serializable
    @SerialName("preq")
    data class PairRequest(val name: String, val onion: String, val authKey: String) : Wire()

    /** Sent by the person who showed the QR code after they tap Accept. */
    @Serializable
    @SerialName("pok")
    data class PairAccept(val name: String, val onion: String, val authKey: String, val staticKey: String) : Wire()

    @Serializable
    @SerialName("pno")
    data object PairReject : Wire()

    companion object {
        const val MAX_TEXT_LENGTH = 4000
        const val MAX_NAME_LENGTH = 40
        const val MAX_READ_IDS = 100

        private val json = Json {
            classDiscriminator = "t"
            ignoreUnknownKeys = true
        }

        fun encode(message: Wire): ByteArray = json.encodeToString(serializer(), message).encodeToByteArray()

        fun decode(bytes: ByteArray): Wire = json.decodeFromString(serializer(), bytes.decodeToString())
    }
}

/** Cleans up a display name received from (or typed for) a peer. */
fun sanitizeName(raw: String): String =
    raw.filter { !it.isISOControl() }.trim().take(Wire.MAX_NAME_LENGTH).ifEmpty { "Unknown" }
