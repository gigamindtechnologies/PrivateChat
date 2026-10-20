package com.gigamindtechnologies.privatechat.core.node

import com.gigamindtechnologies.privatechat.core.net.Socks5
import com.gigamindtechnologies.privatechat.core.tor.TorControlClient
import com.gigamindtechnologies.privatechat.core.tor.OnionKey
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** [OnionHost] backed by a Tor control connection. */
class TorOnionHost(private val control: TorControlClient) : OnionHost {
    override fun publish(key: OnionKey, virtualPort: Int, localPort: Int, authorizedClients: List<ByteArray>) {
        val serviceId = control.addOnion(key.torKeyBlob(), virtualPort, "127.0.0.1:$localPort", authorizedClients)
        if (serviceId != key.address) {
            runCatching { control.delOnion(serviceId) }
            throw IOException("Tor published an unexpected onion address")
        }
    }

    override fun unpublish(address: String) = control.delOnion(address)

    override fun authorizeClient(address: String, privateKey: ByteArray) = control.addClientAuth(address, privateKey)

    override fun deauthorizeClient(address: String) = control.removeClientAuth(address)
}

/** [Dialer] that connects through Tor's SOCKS port. */
class TorDialer(private val socksPort: Int) : Dialer {
    override fun dial(onionAddress: String, port: Int): Socket = Socks5.connect(
        proxy = InetSocketAddress("127.0.0.1", socksPort),
        host = "$onionAddress.onion",
        port = port,
        connectTimeoutMs = 10_000,
        // Finding an onion service and building the rendezvous circuit can take a while.
        handshakeTimeoutMs = 120_000,
    )
}
