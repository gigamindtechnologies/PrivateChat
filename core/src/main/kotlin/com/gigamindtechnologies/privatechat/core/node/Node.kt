package com.gigamindtechnologies.privatechat.core.node

import com.gigamindtechnologies.privatechat.core.crypto.Dh
import com.gigamindtechnologies.privatechat.core.crypto.KeyPair
import com.gigamindtechnologies.privatechat.core.crypto.SecureRandomHolder
import com.gigamindtechnologies.privatechat.core.encoding.B64
import com.gigamindtechnologies.privatechat.core.encoding.Hex
import com.gigamindtechnologies.privatechat.core.invite.InviteCode
import com.gigamindtechnologies.privatechat.core.invite.SafetyCode
import com.gigamindtechnologies.privatechat.core.net.Handshake
import com.gigamindtechnologies.privatechat.core.net.SecureChannel
import com.gigamindtechnologies.privatechat.core.protocol.Wire
import com.gigamindtechnologies.privatechat.core.protocol.sanitizeName
import com.gigamindtechnologies.privatechat.core.store.ChatMessage
import com.gigamindtechnologies.privatechat.core.store.Contact
import com.gigamindtechnologies.privatechat.core.store.Identity
import com.gigamindtechnologies.privatechat.core.store.MessageStatus
import com.gigamindtechnologies.privatechat.core.store.PresenceState
import com.gigamindtechnologies.privatechat.core.store.SecureStore
import com.gigamindtechnologies.privatechat.core.tor.OnionAddress
import com.gigamindtechnologies.privatechat.core.tor.OnionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/** Publishes and reaches onion services. Implemented on top of Tor's control port. */
interface OnionHost {
    fun publish(key: OnionKey, virtualPort: Int, localPort: Int, authorizedClients: List<ByteArray>)
    fun unpublish(address: String)

    /** Remembers the key that lets us reach the client-authorized service at [address]. */
    fun authorizeClient(address: String, privateKey: ByteArray)
    fun deauthorizeClient(address: String)
}

/** Opens a TCP connection to an onion service (through Tor's SOCKS port). */
fun interface Dialer {
    fun dial(onionAddress: String, port: Int): Socket
}

/**
 * The PrivateChat engine. It keeps one encrypted connection per contact that is online,
 * runs QR-code pairing, and stores messages. There is no server: each phone runs its own
 * onion service and the phones connect to each other directly through Tor.
 */
class Node(
    private val store: SecureStore,
    private val identity: Identity,
    private val host: OnionHost,
    private val dialer: Dialer,
    private val config: Config = Config(),
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    /** Asks the platform to keep the CPU awake for the given milliseconds while work finishes. */
    private val wake: (Long) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Config(
        val virtualPort: Int = 80,
        val inviteLifetimeMs: Long = 5 * 60_000,
        val scanTimeoutMs: Long = 3 * 60_000,
        val scanRetryDelayMs: Long = 3_000,
        val retryMinMs: Long = 20_000,
        val retryMaxMs: Long = 5 * 60_000,
        val tickMs: Long = 1_000,
        val keepAliveMs: Long = 30_000,
        /** A contact who sends nothing (not even a keep-alive reply) for this long is offline. */
        val readTimeoutMs: Int = 90_000,
        val handshakeTimeoutMs: Int = 120_000,
        val maxConcurrentDials: Int = 8,
        val unpairFlushMs: Long = 1_000,
        /**
         * A connection the contact opened replaces our existing one if that is at least this old:
         * they only dial when their side of it is gone, even if ours still looks alive.
         */
        val staleSessionMs: Long = 15_000,
        /** How long to wait for a contact's presence setting before showing them online (older versions send none). */
        val presenceGraceMs: Long = 3_000,
        /** "Came online" alerts: the connection must last this long, so flaky networks don't spam. */
        val onlineAlertDelayMs: Long = 20_000,
        /** No alerts this soon after our own app or network (re)started: everyone "comes online" then. */
        val onlineAlertQuietMs: Long = 120_000,
        /** Only alert if they had been gone at least this long. */
        val onlineAlertMinOfflineMs: Long = 5 * 60_000,
        /** At most one alert per contact this often. */
        val onlineAlertIntervalMs: Long = 30 * 60_000,
    )

    /**
     * What we may show about a contact. [hidden]: they asked us not to show their status, or we
     * hide ours from them (then, like WhatsApp, we don't see theirs either). [lastSeen] is the last
     * time this phone had a connection with theirs; it is only ever stored on this phone.
     */
    data class PeerPresence(val online: Boolean, val lastSeen: Long?, val hidden: Boolean)

    /** For the chat list: unread count and the latest message. */
    data class ChatSummary(val unread: Int, val last: ChatMessage?)

    sealed interface Pairing {
        data object Idle : Pairing

        /** Showing side: creating the one-time onion address. */
        data object Preparing : Pairing
        data class ShowingCode(val code: String, val expiresAt: Long) : Pairing
        data class Incoming(val code: String, val expiresAt: Long, val name: String, val safetyCode: List<String>) : Pairing

        /** Scanning side: reaching the other phone through Tor. */
        data object Connecting : Pairing
        data class AwaitingApproval(val safetyCode: List<String>) : Pairing
        data class Paired(val contactId: String, val name: String) : Pairing
        data class Failed(val reason: FailReason) : Pairing
    }

    enum class FailReason { INVALID_CODE, UNREACHABLE, REJECTED, EXPIRED, ERROR }

    /** [QUEUED]: they are offline; the message waits on this phone and goes out when they connect. */
    enum class SendResult { SENT, QUEUED, INVALID }

    /** Whether we tell contacts when we have read their messages. */
    @Volatile
    var sendReadReceipts: Boolean = true

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> log("Unexpected error", e) },
    )
    private val staticKeys = identity.staticKeyPair()
    private val onionKey = identity.onionKey()

    @Volatile
    private var running = false

    private val contactsLock = Any()
    private val _contacts = MutableStateFlow(store.loadContacts())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _online = MutableStateFlow<Set<String>>(emptySet())

    /** IDs of contacts with a live connection right now. */
    val online: StateFlow<Set<String>> = _online.asStateFlow()

    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Unread incoming messages per contact (only contacts with at least one). */
    val unread: StateFlow<Map<String, Int>> = _unread.asStateFlow()

    private val _summaries = MutableStateFlow<Map<String, ChatSummary>>(emptyMap())
    val summaries: StateFlow<Map<String, ChatSummary>> = _summaries.asStateFlow()

    private val _presence = MutableStateFlow<Map<String, PeerPresence>>(emptyMap())
    val presence: StateFlow<Map<String, PeerPresence>> = _presence.asStateFlow()

    private val _cameOnline = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Contact IDs that just came online and asked to be notified about ([Contact.notifyOnline]). */
    val cameOnline: SharedFlow<String> = _cameOnline.asSharedFlow()

    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val hiddenBy: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lastAlertAt = ConcurrentHashMap<String, Long>()

    /** When we noticed a contact's connection was gone (not when we last heard them: we may have slept). */
    private val offlineSince = ConcurrentHashMap<String, Long>()

    @Volatile
    private var hideFromEveryone = false

    @Volatile
    private var quietUntil = 0L

    private val _incoming = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val incoming: SharedFlow<ChatMessage> = _incoming.asSharedFlow()

    private val _pairing = MutableStateFlow<Pairing>(Pairing.Idle)
    val pairing: StateFlow<Pairing> = _pairing.asStateFlow()

    private val conversations = ConcurrentHashMap<String, MutableStateFlow<List<ChatMessage>>>()

    private val sessionsLock = Any()
    private val sessions = HashMap<String, Session>()
    private val dialing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val retries = ConcurrentHashMap<String, Retry>()
    private val dialPermits = Semaphore(config.maxConcurrentDials)
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    private var mainServer: ServerSocket? = null
    private val publishLock = Any()
    private var published = false
    private var publishRetryAt = 0L

    private val pairingLock = Any()
    private var pairingGeneration = 0
    private var pairingJob: Job? = null
    private val pairingResources = mutableListOf<Closeable>()
    private var pendingDecision: CompletableDeferred<Boolean>? = null

    private class Retry(val nextAt: Long, val failures: Int)

    private inner class Session(val contactId: String, val channel: SecureChannel, val initiatedByMe: Boolean) {
        val unacked = LinkedHashMap<String, ChatMessage>() // guarded by this
        var ended = false // guarded by this
        val createdAt = clock()

        @Volatile
        var lastHeardAt = clock()

        /** Sending of queued messages happens one batch at a time, so they arrive in order. */
        val flushLock = Any()

        /** Set once their presence setting arrived (or the grace time passed). */
        @Volatile
        var presenceKnown = false

        /** For "came online" alerts: false when this connection just replaced another one. */
        var arrival = false
        var awaySince: Long? = null

        /**
         * When both phones dial each other at the same moment, both keep the connection that was
         * started by the phone with the smaller public key, so they always agree on one.
         */
        val preferred: Boolean = run {
            val mine = staticKeys.publicKey
            val theirs = channel.remoteStatic
            if (initiatedByMe) compareKeys(mine, theirs) < 0 else compareKeys(theirs, mine) < 0
        }

        fun send(message: Wire) = channel.send(Wire.encode(message))
    }

    val fingerprint: String get() = identity.fingerprint()

    /** Our permanent onion address. Only ever shared with contacts, inside encrypted channels. */
    val onionAddress: String get() = onionKey.address

    fun start() {
        check(!running) { "Already started" }
        running = true
        quietUntil = clock() + config.onlineAlertQuietMs
        store.loadPresence().let { saved ->
            if (!hideFromEveryone) lastSeen.putAll(saved.lastSeen)
            hiddenBy.addAll(saved.hiddenBy)
        }
        publishPresence()
        val server = ServerSocket(0, 50, LOOPBACK)
        mainServer = server
        scope.launch { acceptLoop(server) }
        scope.launch {
            for (contact in _contacts.value) authorizeOutbound(contact)
            republish()
            dialLoop()
        }
        scope.launch { _contacts.value.forEach { updateSummary(it.id) } }
    }

    fun stop() {
        if (!running) return
        running = false
        cancelPairing()
        runCatching { mainServer?.close() }
        val live = synchronized(sessionsLock) { sessions.values.toList() }
        live.forEach { recordLastSeen(it) }
        savePresence()
        live.forEach { it.channel.close() }
        scope.cancel()
        synchronized(publishLock) {
            if (published) runCatching { host.unpublish(onionKey.address) }
            published = false
        }
    }

    /** Try every offline contact right away (e.g. when the app comes back to the foreground). */
    fun refreshNow() {
        retries.clear()
        wakeUp.trySend(Unit)
    }

    /**
     * Called periodically by the platform even while the phone sleeps (and when the network
     * changes): drops connections that went silent, pings the rest and redials everyone else.
     * Blocking; call from a background thread.
     */
    fun heartbeat() {
        val now = clock()
        for (session in synchronized(sessionsLock) { sessions.values.toList() }) {
            val silent = now - session.lastHeardAt > config.readTimeoutMs
            if (silent || runCatching { session.send(Wire.Ping) }.isFailure) session.channel.close()
        }
        // Keep "last seen" roughly current even if the app is killed without a chance to save.
        synchronized(sessionsLock) { sessions.values.toList() }.forEach { recordLastSeen(it) }
        savePresence()
        refreshNow()
    }

    /** The phone's network changed: everyone will seem to "come online" again, so pause alerts. */
    fun networkChanged() {
        quietUntil = maxOf(quietUntil, clock() + config.onlineAlertQuietMs)
        heartbeat()
    }

    /** Hide (or show) when we are online and last seen from all contacts. Can be set before [start]. */
    fun setHideStatusFromEveryone(hide: Boolean) {
        if (hideFromEveryone == hide) return
        hideFromEveryone = hide
        if (!running) return
        if (hide) {
            lastSeen.clear() // we don't keep theirs either
            savePresence()
        }
        synchronized(sessionsLock) { sessions.values.toList() }.forEach { sendPresence(it) }
        publishPresence()
    }

    suspend fun setHideStatus(contactId: String, hide: Boolean) = withContext(Dispatchers.IO) {
        updateContact(contactId) { it.copy(hideMyStatus = hide) }
        if (hide) {
            lastSeen.remove(contactId)
            savePresence()
        }
        synchronized(sessionsLock) { sessions[contactId] }?.let { sendPresence(it) }
        publishPresence()
    }

    suspend fun setNotifyOnline(contactId: String, notify: Boolean) = withContext(Dispatchers.IO) {
        updateContact(contactId) { it.copy(notifyOnline = notify) }
    }

    fun contact(id: String): Contact? = _contacts.value.find { it.id == id }

    /** Loads a conversation from disk the first time; call from a background thread. */
    fun messages(contactId: String): StateFlow<List<ChatMessage>> = conversation(contactId).asStateFlow()

    suspend fun sendText(contactId: String, text: String): SendResult = withContext(Dispatchers.IO) {
        val body = text.trim()
        if (body.isEmpty() || body.length > Wire.MAX_TEXT_LENGTH || contact(contactId) == null) {
            return@withContext SendResult.INVALID
        }
        val message = ChatMessage(newMessageId(), contactId, true, body, clock(), MessageStatus.SENDING)
        if (!appendMessage(message)) return@withContext SendResult.INVALID
        val session = synchronized(sessionsLock) { sessions[contactId] }
        if (session == null) {
            // Try to reach them now; if they are offline it goes out as soon as they connect.
            retries.remove(contactId)
            wakeUp.trySend(Unit)
            return@withContext SendResult.QUEUED
        }
        flush(session) // older queued messages first
        SendResult.SENT
    }

    /** Marks their messages as read (the chat is on screen) and tells them, if read receipts are on. */
    suspend fun markRead(contactId: String) = withContext(Dispatchers.IO) {
        val conversation = conversation(contactId)
        val unread = synchronized(conversation) {
            conversation.value.filter { !it.outgoing && it.status == MessageStatus.RECEIVED }.map { it.id }
        }
        if (unread.isEmpty()) return@withContext
        val reportable = sendReadReceipts
        unread.forEach { setStatus(contactId, it, false, if (reportable) MessageStatus.READ else MessageStatus.SEEN) }
        if (reportable) {
            val session = synchronized(sessionsLock) { sessions[contactId] }
            runCatching { session?.send(Wire.Read(unread.takeLast(Wire.MAX_READ_IDS))) }
        }
    }

    suspend fun renameContact(contactId: String, name: String) = withContext(Dispatchers.IO) {
        updateContact(contactId) { it.copy(name = sanitizeName(name)) }
    }

    private fun updateContact(contactId: String, change: (Contact) -> Contact) = synchronized(contactsLock) {
        val list = _contacts.value.map { if (it.id == contactId) change(it) else it }
        store.saveContacts(list)
        _contacts.value = list
    }

    /** Removes the contact on this phone and, if they are online, on theirs too. */
    suspend fun removeContact(contactId: String) = withContext(Dispatchers.IO) {
        val session = synchronized(sessionsLock) { sessions[contactId] }
        if (session != null && runCatching { session.send(Wire.Unpair) }.isSuccess) {
            delay(config.unpairFlushMs)
        }
        forgetContact(contactId)
    }

    // ---------------------------------------------------------------- pairing

    /** Creates a one-time QR code. Watch [pairing] for the code and for incoming requests. */
    fun showInvite() = startPairing(Pairing.Preparing) { generation -> runInvite(generation) }

    /** Starts pairing with the person whose QR code was scanned. */
    fun scanInvite(text: String) {
        val invite = InviteCode.decode(text)
        if (invite == null) {
            synchronized(pairingLock) {
                cancelPairingLocked()
                _pairing.value = Pairing.Failed(FailReason.INVALID_CODE)
            }
            return
        }
        startPairing(Pairing.Connecting) { generation -> runScan(generation, invite) }
    }

    /** Accept the person currently shown in [Pairing.Incoming]. */
    fun acceptPairing() = synchronized(pairingLock) { pendingDecision?.complete(true) }

    fun rejectPairing() = synchronized(pairingLock) { pendingDecision?.complete(false) }

    fun cancelPairing() = synchronized(pairingLock) {
        cancelPairingLocked()
        _pairing.value = Pairing.Idle
    }

    private fun startPairing(initial: Pairing, block: suspend (Int) -> Unit) = synchronized(pairingLock) {
        cancelPairingLocked()
        val generation = pairingGeneration
        _pairing.value = initial
        pairingJob = scope.launch {
            try {
                block(generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Pairing failed", e)
                setPairing(generation, Pairing.Failed(FailReason.ERROR))
            } finally {
                closePairingResources(generation)
            }
        }
    }

    private fun cancelPairingLocked() {
        pairingGeneration++
        pairingJob?.cancel()
        pairingJob = null
        pendingDecision?.complete(false)
        pendingDecision = null
        pairingResources.forEach { runCatching { it.close() } }
        pairingResources.clear()
    }

    private fun setPairing(generation: Int, state: Pairing) = synchronized(pairingLock) {
        if (generation == pairingGeneration) _pairing.value = state
    }

    private fun track(generation: Int, resource: Closeable): Boolean = synchronized(pairingLock) {
        if (generation != pairingGeneration) {
            runCatching { resource.close() }
            false
        } else {
            pairingResources += resource
            true
        }
    }

    private fun closePairingResources(generation: Int) = synchronized(pairingLock) {
        if (generation == pairingGeneration) {
            pairingResources.forEach { runCatching { it.close() } }
            pairingResources.clear()
        }
    }

    private suspend fun runInvite(generation: Int) {
        // A fresh onion address and key just for this code. Nothing in the QR code is reused.
        val inviteOnion = OnionKey.generate()
        val inviteStatic = Dh.generate()
        val server = ServerSocket(0, 5, LOOPBACK)
        if (!track(generation, server)) return
        host.publish(inviteOnion, config.virtualPort, server.localPort, emptyList())
        try {
            val code = InviteCode(inviteOnion.publicKey, inviteStatic.publicKey).encode()
            val expiresAt = clock() + config.inviteLifetimeMs
            setPairing(generation, Pairing.ShowingCode(code, expiresAt))
            server.soTimeout = 1_000
            while (true) {
                currentCoroutineContext().ensureActive()
                if (clock() >= expiresAt) {
                    setPairing(generation, Pairing.Failed(FailReason.EXPIRED))
                    return
                }
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (!track(generation, socket)) return
                val paired = try {
                    answerInvite(generation, socket, inviteStatic, code, expiresAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Invite connection failed", e)
                    false
                } finally {
                    runCatching { socket.close() }
                }
                if (paired) return
                setPairing(generation, Pairing.ShowingCode(code, expiresAt))
            }
        } finally {
            runCatching { host.unpublish(inviteOnion.address) }
        }
    }

    /** Handles one person who scanned our code. Returns true once they are a contact. */
    private suspend fun answerInvite(
        generation: Int,
        socket: Socket,
        inviteStatic: KeyPair,
        code: String,
        expiresAt: Long,
    ): Boolean {
        socket.soTimeout = config.handshakeTimeoutMs
        val channel = Handshake.respond(socket, PROLOGUE_INVITE, inviteStatic) { true } ?: return false
        val request = Wire.decode(channel.receive()) as? Wire.PairRequest ?: return false
        val peerKey = channel.remoteStatic
        val peerOnion = OnionAddress.canonicalOrNull(request.onion) ?: return false
        val peerAuth = B64.decodeOrNull(request.authKey, 32) ?: return false
        if (peerKey.contentEquals(staticKeys.publicKey)) return false // our own code
        val name = sanitizeName(request.name)

        val decision = CompletableDeferred<Boolean>()
        synchronized(pairingLock) {
            if (generation != pairingGeneration) return false
            pendingDecision = decision
        }
        setPairing(generation, Pairing.Incoming(code, expiresAt, name, SafetyCode.fromHandshakeHash(channel.handshakeHash)))
        val accepted = withTimeoutOrNull(max(0L, expiresAt - clock())) { decision.await() } ?: false
        synchronized(pairingLock) { if (pendingDecision === decision) pendingDecision = null }
        if (!accepted) {
            runCatching { channel.send(Wire.encode(Wire.PairReject)) }
            return false
        }

        val authForThem = Dh.generate()
        channel.send(
            Wire.encode(
                Wire.PairAccept(
                    name = identity.name,
                    onion = onionKey.address,
                    authKey = B64.encode(authForThem.privateKey),
                    staticKey = B64.encode(staticKeys.publicKey),
                ),
            ),
        )
        val contact = Contact(
            id = Contact.idFor(peerKey),
            name = name,
            staticKey = B64.encode(peerKey),
            onion = peerOnion,
            outboundAuthKey = B64.encode(peerAuth),
            inboundAuthKey = B64.encode(authForThem.publicKey),
            addedAt = clock(),
        )
        addContact(contact)
        setPairing(generation, Pairing.Paired(contact.id, contact.name))
        return true
    }

    private suspend fun runScan(generation: Int, invite: InviteCode) {
        val deadline = clock() + config.scanTimeoutMs
        var channel: SecureChannel? = null
        while (channel == null) {
            currentCoroutineContext().ensureActive()
            if (clock() >= deadline) {
                setPairing(generation, Pairing.Failed(FailReason.UNREACHABLE))
                return
            }
            // The other phone's one-time address can take a little while to appear on the Tor network.
            channel = try {
                val socket = dialer.dial(invite.onionAddress, config.virtualPort)
                if (!track(generation, socket)) return
                socket.soTimeout = config.handshakeTimeoutMs
                Handshake.initiate(socket, PROLOGUE_INVITE, staticKeys, invite.staticKey)
            } catch (e: IOException) {
                null
            } catch (e: java.security.GeneralSecurityException) {
                null
            }
            if (channel == null) delay(config.scanRetryDelayMs)
        }

        channel.use {
            val authForThem = Dh.generate()
            channel.send(Wire.encode(Wire.PairRequest(identity.name, onionKey.address, B64.encode(authForThem.privateKey))))
            setPairing(generation, Pairing.AwaitingApproval(SafetyCode.fromHandshakeHash(channel.handshakeHash)))
            channel.setReadTimeout((config.inviteLifetimeMs + 30_000).toInt())
            when (val reply = Wire.decode(channel.receive())) {
                is Wire.PairAccept -> {
                    val peerKey = B64.decodeOrNull(reply.staticKey, 32)
                    val peerOnion = OnionAddress.canonicalOrNull(reply.onion)
                    val peerAuth = B64.decodeOrNull(reply.authKey, 32)
                    if (peerKey == null || peerOnion == null || peerAuth == null || peerKey.contentEquals(staticKeys.publicKey)) {
                        setPairing(generation, Pairing.Failed(FailReason.ERROR))
                        return
                    }
                    val contact = Contact(
                        id = Contact.idFor(peerKey),
                        name = sanitizeName(reply.name),
                        staticKey = B64.encode(peerKey),
                        onion = peerOnion,
                        outboundAuthKey = B64.encode(peerAuth),
                        inboundAuthKey = B64.encode(authForThem.publicKey),
                        addedAt = clock(),
                    )
                    addContact(contact)
                    setPairing(generation, Pairing.Paired(contact.id, contact.name))
                }
                is Wire.PairReject -> setPairing(generation, Pairing.Failed(FailReason.REJECTED))
                else -> setPairing(generation, Pairing.Failed(FailReason.ERROR))
            }
        }
    }

    // ---------------------------------------------------------------- contacts

    private fun contactByKey(key: ByteArray): Contact? =
        contact(Contact.idFor(key))?.takeIf { it.staticKeyBytes().contentEquals(key) }

    private fun addContact(new: Contact) {
        val contact = contact(new.id)?.let { old -> new.copy(notifyOnline = old.notifyOnline, hideMyStatus = old.hideMyStatus) } ?: new
        synchronized(contactsLock) {
            val list = _contacts.value.filter { it.id != contact.id } + contact
            store.saveContacts(list)
            _contacts.value = list
        }
        authorizeOutbound(contact)
        closeSession(contact.id) // a re-pair replaces any connection made with the old keys
        retries.remove(contact.id)
        republish()
        wakeUp.trySend(Unit)
    }

    private fun forgetContact(contactId: String) {
        val removed = synchronized(contactsLock) {
            val existing = contact(contactId) ?: return
            val list = _contacts.value.filter { it.id != contactId }
            store.saveContacts(list)
            _contacts.value = list
            existing
        }
        closeSession(contactId)
        retries.remove(contactId)
        conversations.remove(contactId)?.let { flow -> synchronized(flow) { flow.value = emptyList() } }
        _unread.update { it - contactId }
        _summaries.update { it - contactId }
        lastSeen.remove(contactId)
        hiddenBy.remove(contactId)
        lastAlertAt.remove(contactId)
        offlineSince.remove(contactId)
        savePresence()
        publishPresence()
        store.deleteConversation(contactId)
        runCatching { host.deauthorizeClient(removed.onion) }
        republish() // they are no longer authorized to even find our onion service
    }

    private fun authorizeOutbound(contact: Contact) {
        try {
            host.authorizeClient(contact.onion, B64.decode(contact.outboundAuthKey))
        } catch (e: Exception) {
            log("Could not register client authorization", e)
        }
    }

    /**
     * (Re)publishes our onion service with one client-authorization key per contact. Only our
     * contacts can fetch its descriptor; to everyone else the address does not exist.
     * With no contacts the service is not published at all.
     */
    private fun republish() {
        synchronized(publishLock) {
            if (!running) return
            if (published) {
                runCatching { host.unpublish(onionKey.address) }
                published = false
            }
            val clients = _contacts.value.map { B64.decode(it.inboundAuthKey) }
            if (clients.isEmpty()) return
            try {
                host.publish(onionKey, config.virtualPort, mainServer!!.localPort, clients)
                published = true
            } catch (e: Exception) {
                log("Could not publish onion service", e)
                publishRetryAt = clock() + config.retryMinMs
            }
        }
    }

    // ---------------------------------------------------------------- connections

    private suspend fun dialLoop() {
        while (running) {
            val now = clock()
            if (!published && _contacts.value.isNotEmpty() && now >= publishRetryAt) republish()
            for (contact in _contacts.value) {
                if (hasSession(contact.id) || contact.id in dialing) continue
                val retry = retries[contact.id]
                if (retry != null && retry.nextAt > now) continue
                if (!dialPermits.tryAcquire()) break
                dialing += contact.id
                scope.launch {
                    try {
                        dial(contact)
                    } finally {
                        dialing -= contact.id
                        dialPermits.release()
                    }
                }
            }
            withTimeoutOrNull(config.tickMs) { wakeUp.receive() }
        }
    }

    private fun dial(contact: Contact) {
        val socket = try {
            dialer.dial(contact.onion, config.virtualPort)
        } catch (e: Exception) {
            scheduleRetry(contact.id) // they are probably offline
            return
        }
        try {
            socket.soTimeout = config.handshakeTimeoutMs
            val channel = Handshake.initiate(socket, PROLOGUE_CONTACT, staticKeys, contact.staticKeyBytes())
            onConnected(contact.id, channel, initiatedByMe = true)
        } catch (e: Exception) {
            runCatching { socket.close() }
            scheduleRetry(contact.id)
        }
    }

    private fun scheduleRetry(contactId: String) {
        val failures = (retries[contactId]?.failures ?: 0) + 1
        val delayMs = min(config.retryMaxMs, config.retryMinMs shl min(failures - 1, 16))
        val jitter = (SecureRandomHolder.random.nextDouble() * delayMs / 4).toLong()
        retries[contactId] = Retry(clock() + delayMs + jitter, failures)
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (!running || server.isClosed) return
                continue
            }
            scope.launch { answerContact(socket) }
        }
    }

    private fun answerContact(socket: Socket) {
        try {
            socket.soTimeout = config.handshakeTimeoutMs
            // Unknown callers are dropped without a reply, before they learn anything.
            val channel = Handshake.respond(socket, PROLOGUE_CONTACT, staticKeys) { contactByKey(it) != null } ?: return
            val contact = contactByKey(channel.remoteStatic)
            if (contact == null) {
                channel.close()
                return
            }
            onConnected(contact.id, channel, initiatedByMe = false)
        } catch (e: Exception) {
            runCatching { socket.close() }
        }
    }

    private fun hasSession(contactId: String) = synchronized(sessionsLock) { contactId in sessions }

    private fun onConnected(contactId: String, channel: SecureChannel, initiatedByMe: Boolean) {
        val session = Session(contactId, channel, initiatedByMe)
        val replaced = synchronized(sessionsLock) {
            val existing = sessions[contactId]
            // Simultaneous dials: both keep the preferred connection. But if they dialled us while we
            // still hold an older connection, theirs died (e.g. they switched networks): take the new one.
            val theirsIsFresh = existing != null && !initiatedByMe && clock() - existing.createdAt >= config.staleSessionMs
            if (!running || contact(contactId) == null ||
                (existing != null && existing.preferred && !session.preferred && !theirsIsFresh)
            ) {
                channel.close()
                return
            }
            sessions[contactId] = session
            existing
        }
        if (replaced == null) {
            session.arrival = true
            session.awaySince = offlineSince[contactId] ?: lastSeen[contactId]
        } else {
            session.presenceKnown = replaced.presenceKnown // a reconnect: no flicker, no alert
        }
        retries.remove(contactId)
        replaced?.channel?.close() // its unacknowledged messages move to the new session
        publishOnline()
        wake(15_000)
        scope.launch { runSession(session) }
        scope.launch { catchUp(session) }
        scope.launch {
            delay(config.presenceGraceMs)
            markPresenceKnown(session)
        }
    }

    /** On every new connection: send what waited while they were offline, and our read receipts. */
    private fun catchUp(session: Session) {
        sendPresence(session)
        flush(session)
        if (sendReadReceipts) {
            val read = conversation(session.contactId).value
                .filter { !it.outgoing && it.status == MessageStatus.READ }
                .takeLast(Wire.MAX_READ_IDS)
            if (read.isNotEmpty()) runCatching { session.send(Wire.Read(read.map { it.id })) }
        }
    }

    /** Sends every message still waiting for this contact, oldest first. */
    private fun flush(session: Session) = synchronized(session.flushLock) {
        conversation(session.contactId).value
            .filter { it.outgoing && it.status == MessageStatus.SENDING }
            .sortedBy { it.timestamp }
            .forEach { transmit(session, it) }
    }

    private suspend fun runSession(session: Session) = coroutineScope {
        val keepAlive = launch {
            while (isActive) {
                delay(config.keepAliveMs)
                try {
                    session.send(Wire.Ping)
                } catch (e: IOException) {
                    session.channel.close()
                }
            }
        }
        try {
            session.channel.setReadTimeout(config.readTimeoutMs)
            while (true) {
                val message = runCatching { Wire.decode(session.channel.receive().also { session.lastHeardAt = clock() }) }
                    .getOrElse { if (it is IOException || it is java.security.GeneralSecurityException) throw it else null }
                    ?: continue
                if (!handle(session, message)) break
            }
        } catch (_: Exception) {
            // Connection closed, timed out or failed authentication; the peer is offline now.
        } finally {
            keepAlive.cancel()
            session.channel.close()
            sessionEnded(session)
        }
    }

    /** Returns false when the session should end. */
    private fun handle(session: Session, message: Wire): Boolean {
        when (message) {
            is Wire.Text -> receiveText(session, message)
            is Wire.Ack -> {
                synchronized(session) { session.unacked.remove(message.id) }
                setStatus(session.contactId, message.id, true, MessageStatus.DELIVERED)
            }
            is Wire.Read -> message.ids.take(Wire.MAX_READ_IDS).filter { MESSAGE_ID.matches(it) }.forEach { id ->
                synchronized(session) { session.unacked.remove(id) }
                setStatus(session.contactId, id, true, MessageStatus.READ)
            }
            is Wire.Presence -> {
                if (message.show) hiddenBy.remove(session.contactId) else {
                    hiddenBy.add(session.contactId)
                    lastSeen.remove(session.contactId) // don't keep what they asked us not to show
                }
                savePresence()
                markPresenceKnown(session)
                publishPresence()
            }
            Wire.Ping -> session.send(Wire.Pong)
            Wire.Pong -> Unit
            Wire.Unpair -> {
                forgetContact(session.contactId)
                return false
            }
            else -> Unit
        }
        return true
    }

    private fun receiveText(session: Session, text: Wire.Text) {
        if (!MESSAGE_ID.matches(text.id)) return
        val body = text.body.trim()
        if (body.isEmpty() || body.length > Wire.MAX_TEXT_LENGTH) return
        val conversation = conversation(session.contactId)
        val message = ChatMessage(text.id, session.contactId, false, body, clock(), MessageStatus.RECEIVED)
        // Duplicates happen when a connection drops before our receipt arrives and they resend.
        val added = synchronized(conversation) {
            conversation.value.none { it.id == text.id && !it.outgoing } && appendMessage(message)
        }
        if (added) {
            wake(10_000) // long enough to save it and show the notification
            _incoming.tryEmit(message)
        }
        session.send(Wire.Ack(text.id))
    }

    private fun transmit(session: Session, message: ChatMessage) {
        val queued = synchronized(session) {
            if (session.ended) false else {
                session.unacked[message.id] = message
                true
            }
        }
        if (!queued) {
            val current = synchronized(sessionsLock) { sessions[message.contactId] }
            if (current != null && current !== session) transmit(current, message)
            else requeue(message) // waits for their next connection
            return
        }
        try {
            session.send(Wire.Text(message.id, message.body, message.timestamp))
            // If the session ended meanwhile, sessionEnded() already put it back in the queue.
            synchronized(session) {
                if (!session.ended) setStatus(message.contactId, message.id, true, MessageStatus.SENT)
            }
        } catch (e: IOException) {
            session.channel.close() // sessionEnded() decides what happens to the message
        }
    }

    private fun sessionEnded(session: Session) {
        val current = synchronized(sessionsLock) {
            if (sessions[session.contactId] === session) sessions.remove(session.contactId)
            sessions[session.contactId]
        }
        if (current == null) {
            offlineSince[session.contactId] = clock()
            recordLastSeen(session)
            savePresence()
        }
        publishOnline()
        val pending = synchronized(session) {
            session.ended = true
            session.unacked.values.toList().also { session.unacked.clear() }
        }
        for (message in pending) {
            if (current != null) transmit(current, message) else requeue(message)
        }
        if (current == null && running) {
            if (clock() - session.createdAt >= config.staleSessionMs) {
                // They may just have switched networks: try again soon instead of waiting for backoff.
                retries[session.contactId] = Retry(clock() + config.retryMinMs / 4, 0)
            } else {
                scheduleRetry(session.contactId) // connections keep dying right away: back off
            }
        }
    }

    private fun closeSession(contactId: String) {
        synchronized(sessionsLock) { sessions[contactId] }?.channel?.close()
    }

    private fun publishOnline() {
        _online.value = synchronized(sessionsLock) { sessions.keys.toSet() }
        publishPresence()
    }

    // ---------------------------------------------------------------- presence

    private fun hidingFrom(contact: Contact) = hideFromEveryone || contact.hideMyStatus

    private fun isHidden(contactId: String): Boolean {
        val contact = contact(contactId) ?: return true
        return hidingFrom(contact) || contactId in hiddenBy
    }

    private fun sendPresence(session: Session) {
        val contact = contact(session.contactId) ?: return
        runCatching { session.send(Wire.Presence(show = !hidingFrom(contact))) }
    }

    private fun recordLastSeen(session: Session) {
        if (!isHidden(session.contactId)) lastSeen[session.contactId] = session.lastHeardAt
    }

    private fun savePresence() {
        runCatching { store.savePresence(PresenceState(lastSeen.toMap(), hiddenBy.toSet())) }
            .onFailure { log("Could not save presence", it) }
    }

    private fun publishPresence() {
        val live = synchronized(sessionsLock) { HashMap(sessions) }
        _presence.value = _contacts.value.associate { contact ->
            val hidden = isHidden(contact.id)
            val session = live[contact.id]
            contact.id to PeerPresence(
                online = !hidden && session != null && session.presenceKnown,
                lastSeen = if (hidden) null else lastSeen[contact.id],
                hidden = hidden,
            )
        }
    }

    private fun markPresenceKnown(session: Session) {
        synchronized(session) {
            if (session.presenceKnown) return
            session.presenceKnown = true
        }
        publishPresence()
        if (session.arrival && contact(session.contactId)?.notifyOnline == true) {
            wake(config.onlineAlertDelayMs + 5_000) // so a sleeping phone still shows the alert
            scope.launch { maybeAlertOnline(session) }
        }
    }

    /** "Alice is online" — only for a real arrival, never a flaky reconnect or our own restart. */
    private suspend fun maybeAlertOnline(session: Session) {
        delay(config.onlineAlertDelayMs)
        val id = session.contactId
        val contact = contact(id) ?: return
        val now = clock()
        val stillHere = hasSession(id) // a quick reconnect replacing this connection still counts
        val wasAway = session.awaySince?.let { session.createdAt - it >= config.onlineAlertMinOfflineMs } ?: true
        val notTooOften = lastAlertAt[id]?.let { now - it >= config.onlineAlertIntervalMs } ?: true
        if (contact.notifyOnline && stillHere && !isHidden(id) && session.createdAt >= quietUntil && wasAway && notTooOften) {
            lastAlertAt[id] = now
            _cameOnline.tryEmit(id)
        }
    }

    // ---------------------------------------------------------------- messages

    private fun conversation(contactId: String): MutableStateFlow<List<ChatMessage>> =
        conversations.computeIfAbsent(contactId) {
            // Anything not confirmed delivered goes out again on the next connection; the
            // receiver ignores duplicates.
            MutableStateFlow(
                store.loadMessages(contactId).map {
                    if (it.outgoing && (it.status == MessageStatus.SENT || it.status == MessageStatus.FAILED)) {
                        it.copy(status = MessageStatus.SENDING)
                    } else {
                        it
                    }
                },
            )
        }

    private fun appendMessage(message: ChatMessage): Boolean {
        val conversation = conversation(message.contactId)
        synchronized(conversation) {
            if (contact(message.contactId) == null) return false
            store.appendMessage(message)
            conversation.update { it + message }
        }
        updateSummary(message.contactId)
        return true
    }

    /** A sent message whose connection dropped before their receipt came back: show the clock again. */
    private fun requeue(message: ChatMessage) =
        setStatus(message.contactId, message.id, true, MessageStatus.SENDING, requeue = true)

    /** Statuses only move forward (sending → sent → delivered → read), except for [requeue]. */
    private fun setStatus(contactId: String, id: String, outgoing: Boolean, status: MessageStatus, requeue: Boolean = false) {
        if (contact(contactId) == null) return
        val conversation = conversation(contactId)
        synchronized(conversation) {
            val current = conversation.value.find { it.id == id && it.outgoing == outgoing } ?: return
            if (current.status == status) return
            val allowed = if (requeue) current.status == MessageStatus.SENT else rank(status) > rank(current.status)
            if (!allowed) return
            store.updateStatus(contactId, id, outgoing, status)
            conversation.update { list ->
                list.map { if (it.id == id && it.outgoing == outgoing) it.copy(status = status) else it }
            }
        }
        updateSummary(contactId)
    }

    private fun updateSummary(contactId: String) {
        if (contact(contactId) == null) {
            _unread.update { it - contactId }
            _summaries.update { it - contactId }
            return
        }
        val messages = conversation(contactId).value
        val count = messages.count { !it.outgoing && it.status == MessageStatus.RECEIVED }
        _unread.update { if (count == 0) it - contactId else it + (contactId to count) }
        _summaries.update { it + (contactId to ChatSummary(count, messages.lastOrNull())) }
    }

    private fun newMessageId() = Hex.encode(SecureRandomHolder.bytes(16))

    companion object {
        private val PROLOGUE_CONTACT = "PrivateChat/1 contact".toByteArray()
        private val PROLOGUE_INVITE = "PrivateChat/1 invite".toByteArray()
        private val MESSAGE_ID = Regex("^[0-9a-f]{32}$")

        /**
         * Tor forwards onion-service connections to 127.0.0.1 (see [TorOnionHost]). This must not be
         * InetAddress.getLoopbackAddress(), which is ::1 on Android, where nothing would arrive.
         */
        val LOOPBACK: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

        private fun rank(status: MessageStatus) = when (status) {
            MessageStatus.SENDING, MessageStatus.FAILED, MessageStatus.RECEIVED -> 0
            MessageStatus.SENT -> 1
            MessageStatus.DELIVERED -> 2
            MessageStatus.READ, MessageStatus.SEEN -> 3
        }

        private fun compareKeys(a: ByteArray, b: ByteArray): Int {
            for (i in a.indices) {
                val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
