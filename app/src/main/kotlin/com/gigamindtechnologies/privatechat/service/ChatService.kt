package com.gigamindtechnologies.privatechat.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.gigamindtechnologies.privatechat.IdentityState
import com.gigamindtechnologies.privatechat.TorStatus
import com.gigamindtechnologies.privatechat.core.node.Node
import com.gigamindtechnologies.privatechat.core.node.TorDialer
import com.gigamindtechnologies.privatechat.core.node.TorOnionHost
import com.gigamindtechnologies.privatechat.core.tor.TorControlClient
import com.gigamindtechnologies.privatechat.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * Keeps Tor and the PrivateChat engine running while you are "online", so contacts can reach
 * you even when the app is in the background. Stopping it makes you unreachable.
 */
class ChatService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var tor: TorRuntime
    private var control: TorControlClient? = null
    @Volatile
    private var node: Node? = null

    /**
     * Held only for a few seconds at a time (while a message is saved and shown), never
     * permanently: a permanent wake lock would drain the battery.
     */
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrivateChat:message")
            .apply { setReferenceCounted(false) }
    }

    /**
     * When the phone gets a network again (Wi-Fi, mobile data), check the existing connections
     * (they usually die on a network switch) and reconnect at once instead of waiting.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val node = node ?: return
            thread { node.networkChanged() }
        }
    }
    private var networkCallbackRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        tor = TorRuntime(this)
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.SERVICE_ID,
                Notifications.service(this, "Connecting privately…"),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0,
            )
        } catch (e: Exception) {
            // Android may refuse when it restarts us in the background; the app reconnects when opened.
            graph.log("Could not start in the foreground", e)
            stopSelf()
            return
        }
        networkCallbackRegistered = runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        }.isSuccess
        Notifications.clearConnectionProblem(this)
        Heartbeat.schedule(this)
        scope.launch { run() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            graph.goOffline() // remembered, so a reboot or reopening the app doesn't bring us back online
            return START_NOT_STICKY
        }
        graph.node.value?.refreshNow()
        return START_STICKY
    }

    private suspend fun run() {
        val graph = graph
        // After Android restarts the service, the identity may still be loading from storage.
        val identity = (graph.identity.first { it != IdentityState.Loading } as? IdentityState.Ready)?.identity ?: run {
            stopSelf()
            return
        }
        try {
            graph.tor.value = TorStatus.Starting(0)
            val control = tor.start { progress -> graph.tor.value = TorStatus.Starting(progress) }
            this.control = control
            val node = withContext(Dispatchers.IO) {
                Node(
                    store = graph.store,
                    identity = identity,
                    host = TorOnionHost(control),
                    dialer = TorDialer(control.socksPort()),
                    log = { message, error -> graph.log(message, error) },
                    wake = { millis -> runCatching { wakeLock.acquire(millis) } },
                ).also {
                    it.sendReadReceipts = graph.readReceipts.value
                    it.setHideStatusFromEveryone(graph.hideStatus.value)
                }
            }
            this.node = node
            watchMessages(node) // listen before starting, so no early message is missed
            withContext(Dispatchers.IO) { node.start() }
            graph.node.value = node
            graph.tor.value = TorStatus.Ready
            Notifications.updateService(this, "Online privately — contacts can reach you")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.log("Could not start Tor", e)
            graph.tor.value = TorStatus.Failed
            // Don't pretend to be online: stop, and tell the user how to get back.
            Notifications.connectionProblem(this)
            stopSelf()
        }
    }

    /**
     * Rings for each new message (unless its chat is open) and keeps the "N new messages" count
     * right. Also shows "X is online" for contacts the user asked about.
     */
    private fun watchMessages(node: Node) {
        val graph = graph
        fun show(alert: Boolean) {
            val elsewhere = node.unread.value.filterKeys { it != graph.visibleChatId }
            Notifications.showUnread(this, elsewhere.values.sum(), alert, elsewhere.keys.singleOrNull())
        }
        scope.launch {
            node.incoming.collect { message -> if (graph.visibleChatId != message.contactId) show(alert = true) }
        }
        scope.launch {
            // Opening a chat reads its messages; the count shrinks silently and disappears at zero.
            node.unread.collect { show(alert = false) }
        }
        scope.launch {
            node.cameOnline.collect { id ->
                val name = node.contact(id)?.name ?: return@collect
                if (graph.visibleChatId != id) Notifications.contactOnline(this@ChatService, id, name)
            }
        }
        scope.launch {
            var shown = emptySet<String>()
            node.presence.collect { presence ->
                val online = presence.filterValues { it.online }.keys
                (shown - online).forEach { Notifications.cancelContactOnline(this@ChatService, it) }
                shown = online
            }
        }
    }

    override fun onDestroy() {
        if (networkCallbackRegistered) {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        }
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
        Heartbeat.cancel(this)
        val graph = graph
        graph.node.value = null
        if (graph.tor.value != TorStatus.Failed) graph.tor.value = TorStatus.Stopped
        scope.cancel()
        val node = node
        val control = control
        this.node = null
        this.control = null
        // Unpublish onion services before Tor shuts down; the control calls must not block the main thread.
        Thread {
            node?.stop()
            control?.close()
        }.apply { start() }.join(2_000)
        tor.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.gigamindtechnologies.privatechat.STOP"
    }
}
