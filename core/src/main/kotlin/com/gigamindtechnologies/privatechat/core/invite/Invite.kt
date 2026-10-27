package com.gigamindtechnologies.privatechat.core.invite

import com.gigamindtechnologies.privatechat.core.crypto.Hashes
import com.gigamindtechnologies.privatechat.core.encoding.Base32
import com.gigamindtechnologies.privatechat.core.tor.OnionAddress

/**
 * The contents of a QR code: a one-time onion address and a one-time Noise key, both created
 * just for this invite and thrown away afterwards. It contains no name, no ID and no permanent
 * key, so a photo of the code is useless once it has been used or has expired.
 *
 * Encoded as base32 (QR "alphanumeric" mode). Other scanners only see random letters.
 */
class InviteCode(val onionPublicKey: ByteArray, val staticKey: ByteArray) {
    init {
        require(onionPublicKey.size == 32 && staticKey.size == 32)
    }

    val onionAddress: String get() = OnionAddress.fromPublicKey(onionPublicKey)

    fun encode(): String {
        val body = byteArrayOf(VERSION) + onionPublicKey + staticKey
        return Base32.encode(body + checksum(body))
    }

    companion object {
        private const val VERSION: Byte = 1
        private const val SIZE = 1 + 32 + 32 + 4
        private val CHECKSUM_LABEL = "PrivateChat invite".toByteArray()

        /** Returns null for anything that is not a PrivateChat invite. */
        fun decode(text: String): InviteCode? {
            val raw = runCatching { Base32.decode(text.trim()) }.getOrNull() ?: return null
            if (raw.size != SIZE || raw[0] != VERSION) return null
            val body = raw.copyOfRange(0, SIZE - 4)
            if (!checksum(body).contentEquals(raw.copyOfRange(SIZE - 4, SIZE))) return null
            return InviteCode(raw.copyOfRange(1, 33), raw.copyOfRange(33, 65))
        }

        private fun checksum(body: ByteArray) = Hashes.sha256(CHECKSUM_LABEL, body).copyOf(4)
    }
}

/**
 * Five symbols derived from the handshake. Both phones show the same symbols only if nobody is
 * in the middle, so comparing them proves you connected to the person in front of you.
 */
object SafetyCode {
    val SYMBOLS = listOf(
        "🐶", "🐱", "🐭", "🐰", "🦊", "🐻", "🐼", "🐨",
        "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🐔", "🐧",
        "🐦", "🦉", "🐴", "🦄", "🐝", "🐛", "🦋", "🐌",
        "🐞", "🐢", "🐍", "🐙", "🦀", "🐠", "🐬", "🐳",
        "🌵", "🌲", "🌴", "🍀", "🍁", "🍄", "🌻", "🌙",
        "🌟", "🎈", "🔥", "🌈", "🌂", "🍪", "🍎", "🍌",
        "🍇", "🍉", "🍒", "🍍", "🥕", "🌽", "🍕", "🍔",
        "🍩", "🎂", "🏀", "🎸", "🚀", "🚲", "🎁", "🔑",
    )
    private val LABEL = "PrivateChat safety code".toByteArray()

    fun fromHandshakeHash(handshakeHash: ByteArray): List<String> {
        val digest = Hashes.sha256(LABEL, handshakeHash)
        var bits = 0L
        for (i in 0 until 4) bits = (bits shl 8) or (digest[i].toLong() and 0xff)
        return (0 until 5).map { i -> SYMBOLS[((bits shr (26 - 6 * i)) and 63).toInt()] }
    }
}
