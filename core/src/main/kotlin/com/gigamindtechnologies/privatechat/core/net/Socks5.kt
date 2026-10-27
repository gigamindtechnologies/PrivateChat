package com.gigamindtechnologies.privatechat.core.net

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

class SocksException(val reply: Int) : IOException("SOCKS connect failed (reply 0x${reply.toString(16)})")

/**
 * Minimal SOCKS5 client (RFC 1928) used to reach onion services through Tor. Host names are
 * always sent to the proxy unresolved, so nothing is ever looked up in DNS.
 */
object Socks5 {
    fun connect(
        proxy: InetSocketAddress,
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        handshakeTimeoutMs: Int,
    ): Socket {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        require(hostBytes.size in 1..255 && port in 1..65535)
        val socket = Socket(Proxy.NO_PROXY)
        try {
            socket.connect(proxy, connectTimeoutMs)
            socket.soTimeout = handshakeTimeoutMs
            socket.tcpNoDelay = true
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())

            out.write(byteArrayOf(5, 1, 0)) // version 5, one method: no authentication
            out.flush()
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0) {
                throw IOException("SOCKS proxy refused our authentication method")
            }

            val request = ByteArray(7 + hostBytes.size)
            request[0] = 5 // version
            request[1] = 1 // CONNECT
            request[2] = 0 // reserved
            request[3] = 3 // address type: domain name
            request[4] = hostBytes.size.toByte()
            hostBytes.copyInto(request, 5)
            request[5 + hostBytes.size] = (port shr 8).toByte()
            request[6 + hostBytes.size] = port.toByte()
            out.write(request)
            out.flush()

            if (input.readUnsignedByte() != 5) throw IOException("Bad SOCKS reply")
            val reply = input.readUnsignedByte()
            if (reply != 0) throw SocksException(reply)
            input.readUnsignedByte() // reserved
            val boundAddressSize = when (input.readUnsignedByte()) {
                1 -> 4
                4 -> 16
                3 -> input.readUnsignedByte()
                else -> throw IOException("Bad SOCKS address type")
            }
            input.readFully(ByteArray(boundAddressSize + 2))
            socket.soTimeout = 0
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }
}
