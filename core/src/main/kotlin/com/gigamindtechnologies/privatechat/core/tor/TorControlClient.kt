package com.gigamindtechnologies.privatechat.core.tor

import com.gigamindtechnologies.privatechat.core.encoding.B64
import com.gigamindtechnologies.privatechat.core.encoding.Base32
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

class TorControlException(val code: Int, message: String) : IOException("Tor control error $code: $message")

/**
 * A small client for Tor's control protocol (control-spec.txt), covering only the commands
 * PrivateChat needs. Arguments are validated so nothing a peer sends us can inject commands.
 */
class TorControlClient(
    input: InputStream,
    private val output: OutputStream,
    private val connection: Closeable,
) : Closeable {
    private val reader = BufferedReader(InputStreamReader(input, Charsets.US_ASCII))

    data class ReplyLine(val code: Int, val text: String, val data: List<String> = emptyList())

    /** Sends one command and returns its reply lines. Throws [TorControlException] on 4xx/5xx replies. */
    @Synchronized
    fun command(line: String): List<ReplyLine> {
        require('\r' !in line && '\n' !in line) { "Command must be a single line" }
        output.write("$line\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
        while (true) {
            val reply = readReply()
            if (reply.first().code == 650) continue // asynchronous event, not ours
            val last = reply.last()
            if (last.code !in 200..299) throw TorControlException(last.code, last.text)
            return reply
        }
    }

    private fun readReply(): List<ReplyLine> {
        val lines = mutableListOf<ReplyLine>()
        while (true) {
            val raw = reader.readLine() ?: throw IOException("Tor control connection closed")
            if (raw.length < 4) throw IOException("Malformed control reply")
            val code = raw.substring(0, 3).toIntOrNull() ?: throw IOException("Malformed control reply")
            val separator = raw[3]
            val text = raw.substring(4)
            when (separator) {
                ' ' -> {
                    lines += ReplyLine(code, text)
                    return lines
                }
                '-' -> lines += ReplyLine(code, text)
                '+' -> {
                    val data = mutableListOf<String>()
                    while (true) {
                        val dataLine = reader.readLine() ?: throw IOException("Tor control connection closed")
                        if (dataLine == ".") break
                        data += if (dataLine.startsWith("..")) dataLine.substring(1) else dataLine
                    }
                    lines += ReplyLine(code, text, data)
                }
                else -> throw IOException("Malformed control reply")
            }
        }
    }

    /** Tor is started with CookieAuthentication 0 and no password, so a bare AUTHENTICATE suffices. */
    fun authenticate() {
        command("AUTHENTICATE")
    }

    fun getInfo(key: String): String {
        requireToken(key)
        val reply = command("GETINFO $key")
        val line = reply.firstOrNull { it.text.startsWith("$key=") } ?: throw IOException("Missing GETINFO value")
        return if (line.data.isNotEmpty()) line.data.joinToString("\n") else line.text.substring(key.length + 1)
    }

    /** Bootstrap progress, 0..100. */
    fun bootstrapProgress(): Int =
        Regex("PROGRESS=(\\d+)").find(getInfo("status/bootstrap-phase"))?.groupValues?.get(1)?.toInt() ?: 0

    fun socksPort(): Int {
        val listeners = getInfo("net/listeners/socks")
        return Regex(":(\\d+)\"?").find(listeners)?.groupValues?.get(1)?.toInt()
            ?: throw IOException("Tor has no SOCKS listener")
    }

    /**
     * Publishes an onion service for [keyBlob] that forwards [virtualPort] to [target].
     * When [authorizedClients] (x25519 public keys) is non-empty, only those clients can even
     * fetch the service descriptor, so strangers cannot tell whether the service is online.
     * Returns the service ID (onion address without ".onion").
     */
    fun addOnion(keyBlob: String, virtualPort: Int, target: String, authorizedClients: List<ByteArray>): String {
        requireToken(keyBlob)
        requireToken(target)
        val cmd = StringBuilder("ADD_ONION ").append(keyBlob)
        if (authorizedClients.isNotEmpty()) cmd.append(" Flags=V3Auth") // required alongside ClientAuthV3
        cmd.append(" Port=").append(virtualPort).append(',').append(target)
        for (client in authorizedClients) {
            require(client.size == 32)
            cmd.append(" ClientAuthV3=").append(Base32.encode(client))
        }
        val reply = command(cmd.toString())
        return reply.firstOrNull { it.text.startsWith("ServiceID=") }?.text?.removePrefix("ServiceID=")
            ?: throw IOException("ADD_ONION returned no ServiceID")
    }

    fun delOnion(serviceId: String) {
        requireServiceId(serviceId)
        command("DEL_ONION $serviceId")
    }

    /** Registers the x25519 private key that lets us reach the client-authorized service [serviceId]. */
    fun addClientAuth(serviceId: String, privateKey: ByteArray) {
        requireServiceId(serviceId)
        require(privateKey.size == 32)
        command("ONION_CLIENT_AUTH_ADD $serviceId x25519:${B64.encode(privateKey)}")
    }

    fun removeClientAuth(serviceId: String) {
        requireServiceId(serviceId)
        command("ONION_CLIENT_AUTH_REMOVE $serviceId")
    }

    override fun close() {
        runCatching { connection.close() }
    }

    private fun requireToken(value: String) =
        require(TOKEN.matches(value)) { "Unsafe control argument" }

    private fun requireServiceId(serviceId: String) =
        require(OnionAddress.canonicalOrNull(serviceId) == serviceId) { "Invalid onion service ID" }

    private companion object {
        val TOKEN = Regex("^[A-Za-z0-9+/=:.,_-]{1,200}$")
    }
}
