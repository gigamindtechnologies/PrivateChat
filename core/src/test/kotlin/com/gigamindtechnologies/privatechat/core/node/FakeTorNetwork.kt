package com.gigamindtechnologies.privatechat.core.node

import com.gigamindtechnologies.privatechat.core.crypto.Dh
import com.gigamindtechnologies.privatechat.core.tor.OnionKey
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Stands in for the Tor network in tests. Like real Tor, a service published with authorized
 * clients can only be reached by a client holding one of the matching private keys.
 */
class FakeTorNetwork {
    class Service(val localPort: Int, val clients: List<ByteArray>)

    val services = ConcurrentHashMap<String, Service>()

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
            return Socket(InetAddress.getLoopbackAddress(), service.localPort)
        }
    }
}
