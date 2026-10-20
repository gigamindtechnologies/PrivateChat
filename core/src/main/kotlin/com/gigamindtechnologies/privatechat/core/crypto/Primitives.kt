package com.gigamindtechnologies.privatechat.core.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA3Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.GeneralSecurityException
import java.security.SecureRandom

class CryptoException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/** A Curve25519 key pair. Keys are raw 32-byte values. */
class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {
    init {
        require(privateKey.size == Dh.KEY_SIZE && publicKey.size == Dh.KEY_SIZE)
    }
}

object SecureRandomHolder {
    val random: SecureRandom by lazy { SecureRandom() }

    fun bytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
}

/** X25519 Diffie-Hellman. */
object Dh {
    const val KEY_SIZE = 32

    fun generate(): KeyPair = fromPrivate(SecureRandomHolder.bytes(KEY_SIZE))

    fun fromPrivate(privateKey: ByteArray): KeyPair {
        require(privateKey.size == KEY_SIZE)
        val publicKey = ByteArray(KEY_SIZE)
        X25519.scalarMultBase(privateKey, 0, publicKey, 0)
        return KeyPair(privateKey.copyOf(), publicKey)
    }

    /** Computes the shared secret, rejecting low-order public keys (all-zero output). */
    fun agree(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        if (privateKey.size != KEY_SIZE || publicKey.size != KEY_SIZE) throw CryptoException("Bad key size")
        val out = ByteArray(KEY_SIZE)
        if (!X25519.calculateAgreement(privateKey, 0, publicKey, 0, out, 0)) {
            throw CryptoException("Invalid public key")
        }
        return out
    }
}

object Hashes {
    fun sha256(vararg parts: ByteArray): ByteArray = digest(SHA256Digest(), parts)

    fun sha512(vararg parts: ByteArray): ByteArray = digest(SHA512Digest(), parts)

    fun sha3_256(vararg parts: ByteArray): ByteArray = digest(SHA3Digest(256), parts)

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        for (p in parts) mac.update(p, 0, p.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    private fun digest(d: org.bouncycastle.crypto.Digest, parts: Array<out ByteArray>): ByteArray {
        for (p in parts) d.update(p, 0, p.size)
        return ByteArray(d.digestSize).also { d.doFinal(it, 0) }
    }
}

/** ChaCha20-Poly1305 with the Noise nonce layout (4 zero bytes + 64-bit little-endian counter). */
object Aead {
    const val TAG_SIZE = 16

    fun encrypt(key: ByteArray, nonce: Long, ad: ByteArray, plaintext: ByteArray): ByteArray =
        run(true, key, nonce, ad, plaintext)

    fun decrypt(key: ByteArray, nonce: Long, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < TAG_SIZE) throw CryptoException("Ciphertext too short")
        return run(false, key, nonce, ad, ciphertext)
    }

    private fun run(encrypt: Boolean, key: ByteArray, nonce: Long, ad: ByteArray, input: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), TAG_SIZE * 8, nonceBytes(nonce), ad))
        val out = ByteArray(cipher.getOutputSize(input.size))
        try {
            var len = cipher.processBytes(input, 0, input.size, out, 0)
            len += cipher.doFinal(out, len)
            return if (len == out.size) out else out.copyOf(len)
        } catch (e: InvalidCipherTextException) {
            throw CryptoException("Decryption failed", e)
        }
    }

    private fun nonceBytes(n: Long): ByteArray {
        val out = ByteArray(12)
        for (i in 0 until 8) out[4 + i] = (n ushr (8 * i)).toByte()
        return out
    }
}
