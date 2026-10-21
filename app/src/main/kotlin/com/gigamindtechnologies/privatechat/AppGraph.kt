package com.gigamindtechnologies.privatechat

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.gigamindtechnologies.privatechat.core.node.Node
import com.gigamindtechnologies.privatechat.core.protocol.sanitizeName
import com.gigamindtechnologies.privatechat.core.store.AppLockConfig
import com.gigamindtechnologies.privatechat.core.store.Identity
import com.gigamindtechnologies.privatechat.core.store.PinLock
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

sealed interface UnlockResult {
    data object Unlocked : UnlockResult

    /** Wrong PIN. [waitUntil] (wall clock) is when the next try is allowed; 0 for right away. */
    data class Wrong(val waitUntil: Long) : UnlockResult
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

    /** Hide "online" and "last seen" from every contact. Like WhatsApp, we then don't see theirs either. */
    val hideStatus = MutableStateFlow(prefs.getBoolean(PREF_HIDE_STATUS, false))

    /** The app lock, or null when it is off. Kept in the encrypted vault. */
    val appLock = MutableStateFlow<AppLockConfig?>(null)

    /** True while the lock screen must cover the app. */
    val locked = MutableStateFlow(false)

    /** How long the app may be out of sight before it locks again. */
    val lockDelayMs = MutableStateFlow(prefs.getLong(PREF_LOCK_DELAY, 0L))
    private var hiddenAt = 0L

    /** A chat to open, e.g. after tapping a notification. */
    val openChat = MutableStateFlow<String?>(null)

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
                // Load the lock first, so the chats never flash on screen before it.
                appLock.value = runCatching { store.loadAppLock() }.getOrNull()
                locked.value = appLock.value != null
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

    fun setHideStatus(hide: Boolean) {
        prefs.edit { putBoolean(PREF_HIDE_STATUS, hide) }
        hideStatus.value = hide
        node.value?.setHideStatusFromEveryone(hide)
    }

    // ---------------------------------------------------------------- app lock

    /** The app went out of sight (another app, home screen, screen off). */
    fun onAppHidden() {
        hiddenAt = SystemClock.elapsedRealtime()
    }

    fun onAppShown() {
        if (appLock.value != null && hiddenAt != 0L && SystemClock.elapsedRealtime() - hiddenAt >= lockDelayMs.value) {
            locked.value = true
        }
        hiddenAt = 0L
    }

    suspend fun unlock(pin: String): UnlockResult = withContext(Dispatchers.IO) {
        val lock = appLock.value ?: return@withContext UnlockResult.Unlocked.also { locked.value = false }
        val now = System.currentTimeMillis()
        if (now < lock.blockedUntil) return@withContext UnlockResult.Wrong(lock.blockedUntil)
        if (PinLock.matches(pin, lock)) {
            unlocked(lock)
            UnlockResult.Unlocked
        } else {
            val failed = PinLock.failed(lock, now)
            saveLock(failed)
            UnlockResult.Wrong(failed.blockedUntil)
        }
    }

    /** Fingerprint (or other strong biometric) accepted by the system. */
    suspend fun unlockWithBiometric() = withContext(Dispatchers.IO) {
        appLock.value?.let { unlocked(it) } ?: run { locked.value = false }
    }

    private fun unlocked(lock: AppLockConfig) {
        if (lock.failures != 0 || lock.blockedUntil != 0L) saveLock(lock.copy(failures = 0, blockedUntil = 0))
        locked.value = false
    }

    /** Turns the lock on with a new PIN (or changes the PIN). */
    suspend fun setPin(pin: String, biometric: Boolean) = withContext(Dispatchers.IO) {
        saveLock(PinLock.create(pin, biometric))
    }

    suspend fun setBiometricUnlock(enabled: Boolean) = withContext(Dispatchers.IO) {
        appLock.value?.let { saveLock(it.copy(biometric = enabled)) }
    }

    /** Checks the PIN without unlocking anything (to change or remove the lock). */
    suspend fun checkPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        appLock.value?.let { PinLock.matches(pin, it) } ?: false
    }

    suspend fun removeAppLock() = withContext(Dispatchers.IO) {
        store.saveAppLock(null)
        appLock.value = null
        locked.value = false
    }

    fun setLockDelay(ms: Long) {
        prefs.edit { putLong(PREF_LOCK_DELAY, ms) }
        lockDelayMs.value = ms
    }

    private fun saveLock(lock: AppLockConfig) {
        store.saveAppLock(lock)
        appLock.value = lock
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
        hideStatus.value = false
        appLock.value = null
        locked.value = false
        lockDelayMs.value = 0L
        openChat.value = null
        _identity.value = IdentityState.Missing
        wantsOnline.value = true
    }

    private companion object {
        const val PREF_ONLINE = "online"
        const val PREF_READ_RECEIPTS = "read_receipts"
        const val PREF_HIDE_STATUS = "hide_status"
        const val PREF_LOCK_DELAY = "lock_delay_ms"
    }

    /** Logs only in debug builds, and never message contents, names or addresses. */
    fun log(message: String, error: Throwable? = null) {
        if (debuggable) Log.w("PrivateChat", message, error)
    }
}
