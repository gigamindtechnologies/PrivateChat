package com.gigamindtechnologies.privatechat

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.gigamindtechnologies.privatechat.core.node.Node
import com.gigamindtechnologies.privatechat.core.protocol.sanitizeName
import com.gigamindtechnologies.privatechat.core.store.Identity
import com.gigamindtechnologies.privatechat.core.store.SecureStore
import com.gigamindtechnologies.privatechat.security.VaultKey
import com.gigamindtechnologies.privatechat.service.ChatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

sealed interface IdentityState {
    data object Loading : IdentityState
    data object Missing : IdentityState
    data class Ready(val identity: Identity) : IdentityState

    /** The encrypted files exist but cannot be opened (e.g. the Keystore key was lost). */
    data object Unreadable : IdentityState
}

sealed interface TorStatus {
    data object Stopped : TorStatus
    data class Starting(val progress: Int) : TorStatus
    data object Ready : TorStatus
    data object Failed : TorStatus
}

/** App-wide state shared by the UI and [ChatService]. */
class AppGraph(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vaultDir = File(app.noBackupFilesDir, "vault")

    @Volatile
    private var _store: SecureStore? = null

    /** Opening the store touches the Keystore; call from a background thread. */
    val store: SecureStore
        get() = _store ?: synchronized(this) {
            _store ?: SecureStore(vaultDir, VaultKey.sealer(app)).also { _store = it }
        }

    private val _identity = MutableStateFlow<IdentityState>(IdentityState.Loading)
    val identity: StateFlow<IdentityState> = _identity.asStateFlow()

    /** Small, non-secret preferences. Excluded from backups like everything else. */
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** False after the user chose "Go offline"; the app then doesn't reconnect by itself (not even after a reboot). */
    val wantsOnline = MutableStateFlow(prefs.getBoolean(PREF_ONLINE, true))

    /** Whether contacts see green ticks when we read their messages. Off also hides theirs, like WhatsApp. */
    val readReceipts = MutableStateFlow(prefs.getBoolean(PREF_READ_RECEIPTS, true))

    /** The running engine, or null while Tor is starting or the app is offline. */
    val node = MutableStateFlow<Node?>(null)
    val tor = MutableStateFlow<TorStatus>(TorStatus.Stopped)

    /** The conversation on screen right now, so we don't notify about messages already being read. */
    @Volatile
    var visibleChatId: String? = null

    private val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    init {
        scope.launch {
            _identity.value = try {
                store.loadIdentity()?.let { IdentityState.Ready(it) } ?: IdentityState.Missing
            } catch (e: Exception) {
                log("Could not open the vault", e)
                IdentityState.Unreadable
            }
        }
    }

    suspend fun createIdentity(name: String) = withContext(Dispatchers.IO) {
        val identity = Identity.create(sanitizeName(name), System.currentTimeMillis())
        store.saveIdentity(identity)
        _identity.value = IdentityState.Ready(identity)
    }

    /** True once an identity has been created (checked without touching the Keystore). */
    fun hasIdentity(): Boolean = File(vaultDir, "identity.bin").exists()

    fun goOnline() {
        setWantsOnline(true)
        ContextCompat.startForegroundService(app, Intent(app, ChatService::class.java))
    }

    fun goOffline() {
        setWantsOnline(false)
        app.stopService(Intent(app, ChatService::class.java))
    }

    fun setReadReceipts(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_READ_RECEIPTS, enabled) }
        readReceipts.value = enabled
        node.value?.sendReadReceipts = enabled
    }

    private fun setWantsOnline(online: Boolean) {
        prefs.edit { putBoolean(PREF_ONLINE, online) }
        wantsOnline.value = online
    }

    /** Destroys the identity, contacts, messages, Tor state and the encryption key. */
    suspend fun wipeEverything() {
        withContext(Dispatchers.Main) { goOffline() }
        withTimeoutOrNull(10_000) { tor.first { it == TorStatus.Stopped || it == TorStatus.Failed } }
        withContext(Dispatchers.IO) {
            synchronized(this@AppGraph) {
                runCatching { _store?.wipe() }
                vaultDir.deleteRecursively()
                VaultKey.destroy(app)
                _store = null
            }
            File(app.dataDir, "app_TorService").deleteRecursively()
            NotificationManagerCompat.from(app).cancelAll()
        }
        prefs.edit { clear() }
        readReceipts.value = true
        _identity.value = IdentityState.Missing
        wantsOnline.value = true
    }

    private companion object {
        const val PREF_ONLINE = "online"
        const val PREF_READ_RECEIPTS = "read_receipts"
    }

    /** Logs only in debug builds, and never message contents, names or addresses. */
    fun log(message: String, error: Throwable? = null) {
        if (debuggable) Log.w("PrivateChat", message, error)
    }
}
