package com.gigamindtechnologies.privatechat.core.net

import com.gigamindtechnologies.privatechat.core.crypto.Aead
import com.gigamindtechnologies.privatechat.core.crypto.CryptoException
import com.gigamindtechnologies.privatechat.core.crypto.KeyPair
import com.gigamindtechnologies.privatechat.core.crypto.NoiseIK
import com.gigamindtechnologies.privatechat.core.crypto.TransportState
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

/** Length-prefixed frames (2-byte big-endian length), the Noise wire format. */
internal class FrameIO(socket: Socket) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    fun write(frame: ByteArray) {
        require(frame.size <= MAX_FRAME)
        output.writeShort(frame.size)
        output.write(frame)
        output.flush()
    }

    fun read(): ByteArray {
        val size = input.readUnsignedShort()
        return ByteArray(size).also { input.readFully(it) }
    }

    companion object {
        const val MAX_FRAME = 65535
    }
}

/**
 * Every message is padded to a multiple of [BLOCK] bytes before encryption, so someone watching
 * the traffic cannot guess what was said from the message length.
 */
internal object Padding {
    const val BLOCK = 256
    const val MAX_PLAINTEXT = FrameIO.MAX_FRAME - Aead.TAG_SIZE
    const val MAX_MESSAGE = MAX_PLAINTEXT - 2

    fun pad(message: ByteArray): ByteArray {
        require(message.size <= MAX_MESSAGE) { "Message too large" }
        val needed = message.size + 2
        val size = ((needed + BLOCK - 1) / BLOCK * BLOCK).coerceAtMost(MAX_PLAINTEXT)
        val out = ByteArray(size)
        out[0] = (message.size shr 8).toByte()
        out[1] = message.size.toByte()
        message.copyInto(out, 2)
        return out
    }

    fun unpad(padded: ByteArray): ByteArray {
        if (padded.size < 2) throw CryptoException("Bad padding")
        val size = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        if (size > padded.size - 2) throw CryptoException("Bad padding")
        return padded.copyOfRange(2, 2 + size)
    }
}

/** An authenticated, encrypted, forward-secret connection to a peer. Thread-safe. */
class SecureChannel internal constructor(
    private val socket: Socket,
    private val frames: FrameIO,
    private val transport: TransportState,
    /** The peer's long-term X25519 public key, proven during the handshake. */
    val remoteStatic: ByteArray,
    /** Identical on both ends and unique per connection. */
    val handshakeHash: ByteArray,
) : Closeable {
    private val sendLock = Any()
    private val receiveLock = Any()

    @Volatile
    var isClosed = false
        private set

    fun send(message: ByteArray) = synchronized(sendLock) {
        if (isClosed) throw IOException("Channel closed")
        frames.write(transport.send.encryptWithAd(EMPTY, Padding.pad(message)))
    }

    fun receive(): ByteArray = synchronized(receiveLock) {
        Padding.unpad(transport.receive.decryptWithAd(EMPTY, frames.read()))
    }

    /** Maximum time [receive] blocks before throwing [java.net.SocketTimeoutException]; 0 = forever. */
    fun setReadTimeout(millis: Int) {
        socket.soTimeout = millis
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { socket.close() }
    }

    companion object {
        private val EMPTY = ByteArray(0)
        const val MAX_MESSAGE = Padding.MAX_MESSAGE
    }
}

object Handshake {
    /** Runs the initiator side of Noise IK on [socket]. Closes the socket on failure. */
    fun initiate(socket: Socket, prologue: ByteArray, local: KeyPair, remoteStatic: ByteArray): SecureChannel =
        closingOnFailure(socket) {
            val frames = FrameIO(socket)
            val noise = NoiseIK.initiator(prologue, local, remoteStatic)
            frames.write(noise.writeMessage1())
            noise.readMessage2(frames.read())
            SecureChannel(socket, frames, noise.split(), remoteStatic.copyOf(), noise.handshakeHash)
        }

    /**
     * Runs the responder side of Noise IK on [socket]. After the first message reveals who is
     * calling, [authorize] decides whether to continue; unknown callers get no reply at all.
     * Returns null (and closes the socket) when the caller was refused.
     */
    fun respond(socket: Socket, prologue: ByteArray, local: KeyPair, authorize: (ByteArray) -> Boolean): SecureChannel? =
        closingOnFailure(socket) {
            val frames = FrameIO(socket)
            val noise = NoiseIK.responder(prologue, local)
            noise.readMessage1(frames.read())
            val remote = noise.remoteStatic!!
            if (!authorize(remote)) {
                socket.close()
                return@closingOnFailure null
            }
            frames.write(noise.writeMessage2())
            SecureChannel(socket, frames, noise.split(), remote, noise.handshakeHash)
        }

    private inline fun <T> closingOnFailure(socket: Socket, block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
}
