package com.gigamindtechnologies.privatechat.core.node

import com.gigamindtechnologies.privatechat.core.crypto.Dh
import com.gigamindtechnologies.privatechat.core.tor.OnionKey
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Stands in for the Tor network in tests. Like real Tor, a service published with authorized
 * clients can only be reached by a client holding one of the matching private keys.
 */
class FakeTorNetwork {
    class Service(val localPort: Int, val clients: List<ByteArray>)

    val services = ConcurrentHashMap<String, Service>()

    /** Route connections through relays that [freezeAll] can silence, like a phone that lost its network. */
    @Volatile
    var useRelays = false
    private val relays = CopyOnWriteArrayList<Relay>()

    /** Existing connections stop carrying data but stay open on both ends; new ones work. */
    fun freezeAll() = relays.forEach { it.frozen = true }

    private class Relay(private val inner: Socket, private val upstream: Socket) {
        @Volatile
        var frozen = false

        init {
            pump(inner, upstream)
            pump(upstream, inner)
        }

        private fun pump(from: Socket, to: Socket) = thread(isDaemon = true) {
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    if (frozen) {
                        Thread.sleep(50)
                        continue
                    }
                    val n = from.getInputStream().read(buffer)
                    if (n < 0) break
                    if (frozen) continue // swallowed, like packets into a dead network
                    to.getOutputStream().write(buffer, 0, n)
                }
            } catch (_: Exception) {
            } finally {
                if (!frozen) {
                    runCatching { inner.close() }
                    runCatching { upstream.close() }
                }
            }
        }
    }

    inner class Client : OnionHost, Dialer {
        private val auth = ConcurrentHashMap<String, ByteArray>()

        override fun publish(key: OnionKey, virtualPort: Int, localPort: Int, authorizedClients: List<ByteArray>) {
            if (services.putIfAbsent(key.address, Service(localPort, authorizedClients.map { it.copyOf() })) != null) {
                throw IOException("Onion address collision")
            }
        }

        override fun unpublish(address: String) {
            services.remove(address)
        }

        override fun authorizeClient(address: String, privateKey: ByteArray) {
            auth[address] = privateKey.copyOf()
        }

        override fun deauthorizeClient(address: String) {
            auth.remove(address)
        }

        override fun dial(onionAddress: String, port: Int): Socket {
            val service = services[onionAddress] ?: throw IOException("Onion descriptor not found")
            if (service.clients.isNotEmpty()) {
                val key = auth[onionAddress] ?: throw IOException("Missing client authorization")
                val publicKey = Dh.fromPrivate(key).publicKey
                if (service.clients.none { it.contentEquals(publicKey) }) throw IOException("Wrong client authorization")
            }
            // Like Tor, always forward to 127.0.0.1, so a service listening only on ::1 is unreachable.
            val target = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            if (!useRelays) return Socket(target, service.localPort)
            ServerSocket(0, 1, target).use { server ->
                val client = Socket(target, server.localPort)
                relays += Relay(server.accept(), Socket(target, service.localPort))
                return client
            }
        }
    }
}
