package com.gigamindtechnologies.privatechat.core.tor

import com.gigamindtechnologies.privatechat.core.crypto.Hashes
import com.gigamindtechnologies.privatechat.core.crypto.SecureRandomHolder
import com.gigamindtechnologies.privatechat.core.encoding.B64
import com.gigamindtechnologies.privatechat.core.encoding.Base32
import org.bouncycastle.math.ec.rfc8032.Ed25519

/** Tor v3 onion addresses: base32(pubkey || checksum[0:2] || 0x03), 56 lowercase characters. */
object OnionAddress {
    private val CHECKSUM_PREFIX = ".onion checksum".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 3
    private val PATTERN = Regex("^[a-z2-7]{56}$")

    fun fromPublicKey(publicKey: ByteArray): String {
        require(publicKey.size == 32)
        val checksum = checksum(publicKey)
        return Base32.encode(publicKey + checksum + VERSION).lowercase()
    }

    /** Returns the ed25519 public key encoded in [address], or null if it is not a valid v3 address. */
    fun publicKeyOrNull(address: String): ByteArray? {
        val bare = address.removeSuffix(".onion")
        if (!PATTERN.matches(bare)) return null
        val raw = runCatching { Base32.decode(bare) }.getOrNull() ?: return null
        if (raw.size != 35 || raw[34] != VERSION) return null
        val publicKey = raw.copyOfRange(0, 32)
        if (!checksum(publicKey).contentEquals(raw.copyOfRange(32, 34))) return null
        return publicKey
    }

    /** Validates untrusted input and returns the canonical form (56 chars, no ".onion"), or null. */
    fun canonicalOrNull(address: String): String? = publicKeyOrNull(address)?.let(::fromPublicKey)

    private fun checksum(publicKey: ByteArray) =
        Hashes.sha3_256(CHECKSUM_PREFIX, publicKey, byteArrayOf(VERSION)).copyOf(2)
}

/**
 * The secret behind an onion address. We keep the 32-byte ed25519 seed and hand Tor the
 * 64-byte "expanded" secret key it expects in ADD_ONION, so the address is known (and can be
 * put in a QR code) before Tor is even running.
 */
class OnionKey(seed: ByteArray) {
    private val seed = seed.copyOf()

    init {
        require(seed.size == 32)
    }

    val publicKey: ByteArray = ByteArray(Ed25519.PUBLIC_KEY_SIZE).also {
        Ed25519.generatePublicKey(this.seed, 0, it, 0)
    }

    val address: String = OnionAddress.fromPublicKey(publicKey)

    /** `ED25519-V3:<base64 of clamped SHA-512(seed)>`, the key format of Tor's ADD_ONION command. */
    fun torKeyBlob(): String {
        val expanded = Hashes.sha512(seed)
        expanded[0] = (expanded[0].toInt() and 248).toByte()
        expanded[31] = ((expanded[31].toInt() and 63) or 64).toByte()
        return "ED25519-V3:" + B64.encode(expanded).also { expanded.fill(0) }
    }

    fun seedCopy(): ByteArray = seed.copyOf()

    companion object {
        fun generate() = OnionKey(SecureRandomHolder.bytes(32))
    }
}
