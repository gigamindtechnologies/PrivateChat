package com.gigamindtechnologies.privatechat.core.crypto

import java.io.ByteArrayOutputStream

/**
 * A minimal implementation of the Noise Protocol Framework (revision 34), limited to
 * `Noise_IK_25519_ChaChaPoly_SHA256`, which is the only pattern PrivateChat uses.
 *
 * IK gives mutual authentication and forward secrecy in two messages:
 * ```
 *   <- s
 *   ...
 *   -> e, es, s, ss
 *   <- e, ee, se
 * ```
 * Verified against the official cacophony test vectors (see NoiseIKTest).
 */
class CipherState {
    private var key: ByteArray? = null
    private var nonce = 0L

    val hasKey: Boolean get() = key != null

    fun initializeKey(newKey: ByteArray?) {
        key?.fill(0)
        key = newKey?.copyOf()
        nonce = 0
    }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext
        checkNonce()
        return Aead.encrypt(k, nonce, ad, plaintext).also { nonce++ }
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext
        checkNonce()
        // The nonce only advances when authentication succeeds.
        return Aead.decrypt(k, nonce, ad, ciphertext).also { nonce++ }
    }

    fun destroy() = initializeKey(null)

    private fun checkNonce() {
        if (nonce == Long.MAX_VALUE) throw CryptoException("Nonce exhausted")
    }
}

internal class SymmetricState(protocolName: String) {
    var h: ByteArray
        private set
    private var ck: ByteArray
    val cipher = CipherState()

    init {
        val name = protocolName.toByteArray(Charsets.US_ASCII)
        h = if (name.size <= HASH_LEN) name.copyOf(HASH_LEN) else Hashes.sha256(name)
        ck = h.copyOf()
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val (newCk, tempK) = hkdf2(ck, inputKeyMaterial)
        ck = newCk
        cipher.initializeKey(tempK)
    }

    fun mixHash(data: ByteArray) {
        h = Hashes.sha256(h, data)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray =
        cipher.encryptWithAd(h, plaintext).also { mixHash(it) }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray =
        cipher.decryptWithAd(h, ciphertext).also { mixHash(ciphertext) }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = hkdf2(ck, ByteArray(0))
        ck.fill(0)
        cipher.destroy()
        return CipherState().apply { initializeKey(k1) } to CipherState().apply { initializeKey(k2) }
    }

    companion object {
        const val HASH_LEN = 32

        fun hkdf2(chainingKey: ByteArray, inputKeyMaterial: ByteArray): Pair<ByteArray, ByteArray> {
            val tempKey = Hashes.hmacSha256(chainingKey, inputKeyMaterial)
            val out1 = Hashes.hmacSha256(tempKey, byteArrayOf(1))
            val out2 = Hashes.hmacSha256(tempKey, out1, byteArrayOf(2))
            tempKey.fill(0)
            return out1 to out2
        }
    }
}

/** The two directional cipher states produced by a completed handshake. */
class TransportState(val send: CipherState, val receive: CipherState) {
    fun destroy() {
        send.destroy()
        receive.destroy()
    }
}

class NoiseIK private constructor(
    private val initiator: Boolean,
    prologue: ByteArray,
    private val localStatic: KeyPair,
    remoteStatic: ByteArray?,
    private val fixedEphemeral: KeyPair?,
) {
    private val state = SymmetricState(PROTOCOL_NAME)
    private var ephemeral: KeyPair? = null
    private var remoteEphemeral: ByteArray? = null
    private var step = 0

    /** The peer's static public key. Known up front for the initiator, learned from message 1 by the responder. */
    var remoteStatic: ByteArray? = remoteStatic?.copyOf()
        private set

    init {
        state.mixHash(prologue)
        // Pre-message pattern "<- s": both sides hash the responder's static key.
        state.mixHash(if (initiator) this.remoteStatic!! else localStatic.publicKey)
    }

    /** Initiator: `-> e, es, s, ss` followed by the encrypted payload. */
    fun writeMessage1(payload: ByteArray = EMPTY): ByteArray {
        check(initiator && step == 0)
        val rs = remoteStatic!!
        val e = fixedEphemeral ?: Dh.generate()
        ephemeral = e
        val out = ByteArrayOutputStream()
        out.write(e.publicKey)
        state.mixHash(e.publicKey)
        state.mixKey(Dh.agree(e.privateKey, rs))
        out.write(state.encryptAndHash(localStatic.publicKey))
        state.mixKey(Dh.agree(localStatic.privateKey, rs))
        out.write(state.encryptAndHash(payload))
        step = 1
        return out.toByteArray()
    }

    /** Responder: reads message 1 and returns its payload. [remoteStatic] is available afterwards. */
    fun readMessage1(message: ByteArray): ByteArray {
        check(!initiator && step == 0)
        if (message.size < MIN_MESSAGE1) throw CryptoException("Handshake message too short")
        val re = message.copyOfRange(0, Dh.KEY_SIZE)
        remoteEphemeral = re
        state.mixHash(re)
        state.mixKey(Dh.agree(localStatic.privateKey, re))
        val rs = state.decryptAndHash(message.copyOfRange(Dh.KEY_SIZE, Dh.KEY_SIZE + Dh.KEY_SIZE + Aead.TAG_SIZE))
        remoteStatic = rs
        state.mixKey(Dh.agree(localStatic.privateKey, rs))
        val payload = state.decryptAndHash(message.copyOfRange(MIN_MESSAGE1 - Aead.TAG_SIZE, message.size))
        step = 1
        return payload
    }

    /** Responder: `<- e, ee, se` followed by the encrypted payload. Completes the handshake. */
    fun writeMessage2(payload: ByteArray = EMPTY): ByteArray {
        check(!initiator && step == 1)
        val e = fixedEphemeral ?: Dh.generate()
        ephemeral = e
        val out = ByteArrayOutputStream()
        out.write(e.publicKey)
        state.mixHash(e.publicKey)
        state.mixKey(Dh.agree(e.privateKey, remoteEphemeral!!))
        state.mixKey(Dh.agree(e.privateKey, remoteStatic!!))
        out.write(state.encryptAndHash(payload))
        step = 2
        return out.toByteArray()
    }

    /** Initiator: reads message 2 and returns its payload. Completes the handshake. */
    fun readMessage2(message: ByteArray): ByteArray {
        check(initiator && step == 1)
        if (message.size < MIN_MESSAGE2) throw CryptoException("Handshake message too short")
        val re = message.copyOfRange(0, Dh.KEY_SIZE)
        remoteEphemeral = re
        state.mixHash(re)
        state.mixKey(Dh.agree(ephemeral!!.privateKey, re))
        state.mixKey(Dh.agree(localStatic.privateKey, re))
        val payload = state.decryptAndHash(message.copyOfRange(Dh.KEY_SIZE, message.size))
        step = 2
        return payload
    }

    val isComplete: Boolean get() = step == 2

    /** Unique for every session and identical on both sides; used to derive safety codes. */
    val handshakeHash: ByteArray
        get() {
            check(isComplete)
            return state.h.copyOf()
        }

    fun split(): TransportState {
        check(isComplete)
        ephemeral?.privateKey?.fill(0)
        val (c1, c2) = state.split()
        return if (initiator) TransportState(send = c1, receive = c2) else TransportState(send = c2, receive = c1)
    }

    companion object {
        const val PROTOCOL_NAME = "Noise_IK_25519_ChaChaPoly_SHA256"
        private val EMPTY = ByteArray(0)
        private const val MIN_MESSAGE1 = Dh.KEY_SIZE + Dh.KEY_SIZE + Aead.TAG_SIZE + Aead.TAG_SIZE
        private const val MIN_MESSAGE2 = Dh.KEY_SIZE + Aead.TAG_SIZE

        fun initiator(prologue: ByteArray, localStatic: KeyPair, remoteStatic: ByteArray, ephemeral: KeyPair? = null) =
            NoiseIK(true, prologue, localStatic, remoteStatic, ephemeral)

        fun responder(prologue: ByteArray, localStatic: KeyPair, ephemeral: KeyPair? = null) =
            NoiseIK(false, prologue, localStatic, null, ephemeral)
    }
}
