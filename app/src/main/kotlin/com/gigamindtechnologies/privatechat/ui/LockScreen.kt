package com.gigamindtechnologies.privatechat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gigamindtechnologies.privatechat.AppGraph
import com.gigamindtechnologies.privatechat.R
import com.gigamindtechnologies.privatechat.UnlockResult
import com.gigamindtechnologies.privatechat.core.store.PinLock
import com.gigamindtechnologies.privatechat.security.Biometric
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Covers the whole app until the PIN (or fingerprint) is given. */
@Composable
fun LockScreen(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lock by graph.appLock.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var waitUntil by remember { mutableLongStateOf(lock?.blockedUntil ?: 0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(waitUntil) {
        while (System.currentTimeMillis() < waitUntil) {
            now = System.currentTimeMillis()
            delay(250)
        }
        now = System.currentTimeMillis()
    }
    val waitSeconds = ((waitUntil - now + 999) / 1000).coerceAtLeast(0)

    val activity = context.findActivity()
    val fingerprint = lock?.biometric == true && activity != null && Biometric.isAvailable(context)
    fun askFingerprint() = activity?.let { Biometric.prompt(it) { scope.launch { graph.unlockWithBiometric() } } }
    if (fingerprint) {
        DisposableEffect(Unit) {
            val prompt = askFingerprint()
            onDispose { prompt?.cancel() }
        }
    }

    PinPad(
        title = "PrivateChat is locked",
        subtitle = when {
            checking -> "Checking…"
            waitSeconds > 0 -> "Too many wrong PINs. Try again in $waitSeconds s."
            else -> "Enter your PIN"
        },
        error = error,
        enabled = !checking && waitSeconds == 0L,
        onSubmit = { pin, clear ->
            checking = true
            scope.launch {
                when (val result = graph.unlock(pin)) {
                    UnlockResult.Unlocked -> Unit
                    is UnlockResult.Wrong -> {
                        error = "Wrong PIN"
                        waitUntil = result.waitUntil
                        clear()
                    }
                }
                checking = false
            }
        },
        footer = {
            if (fingerprint) {
                TextButton(onClick = { askFingerprint() }) { Text("Use fingerprint") }
            }
            Text(
                "Forgot your PIN? The only way back in is to uninstall PrivateChat, which deletes your messages and contacts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        },
    )
}

/**
 * A number pad drawn by the app itself, so no keyboard app ever sees the PIN. [onSubmit] gets
 * the PIN and a function that clears the dots.
 */
@Composable
fun PinPad(
    title: String,
    subtitle: String,
    error: String?,
    enabled: Boolean = true,
    onSubmit: (pin: String, clear: () -> Unit) -> Unit,
    footer: @Composable () -> Unit = {},
) {
    var pin by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        Row(
            Modifier.height(20.dp).semantics { contentDescription = "${pin.length} digits entered" },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(maxOf(pin.length, PinLock.MIN_LENGTH)) { i ->
                val filled = i < pin.length
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        Text(error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { digit ->
                        Key(digit, enabled && pin.length < PinLock.MAX_LENGTH) { pin += digit }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.size(66.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { pin = pin.dropLast(1) }, enabled = enabled && pin.isNotEmpty(), modifier = Modifier.size(66.dp)) {
                        Icon(painterResource(R.drawable.ic_backspace), contentDescription = "Delete")
                    }
                }
                Key("0", enabled && pin.length < PinLock.MAX_LENGTH) { pin += "0" }
                Box(Modifier.size(66.dp), contentAlignment = Alignment.Center) {
                    FilledIconButton(
                        onClick = { onSubmit(pin) { pin = "" } },
                        enabled = enabled && PinLock.isValid(pin),
                        modifier = Modifier.size(58.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = "OK")
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        footer()
    }
}

@Composable
private fun Key(digit: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = Modifier.size(66.dp)) {
        Text(digit, style = MaterialTheme.typography.headlineSmall)
    }
}

enum class PinFlow { CREATE, CHANGE, REMOVE }

/** Turning the lock on, changing the PIN, or turning the lock off (which needs the current PIN). */
@Composable
fun PinSetupScreen(graph: AppGraph, flow: PinFlow, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var verified by remember { mutableStateOf(flow == PinFlow.CREATE) }
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // A new key resets the pad for each step.
    val step = when {
        !verified -> 0
        first == null -> 1
        else -> 2
    }
    androidx.compose.runtime.key(step) {
        when (step) {
            0 -> PinPad(
                title = if (flow == PinFlow.REMOVE) "Turn off app lock" else "Change PIN",
                subtitle = if (busy) "Checking…" else "Enter your current PIN",
                error = error,
                enabled = !busy,
                onSubmit = { pin, clear ->
                    busy = true
                    scope.launch {
                        if (graph.checkPin(pin)) {
                            error = null
                            if (flow == PinFlow.REMOVE) {
                                graph.removeAppLock()
                                onDone()
                            } else {
                                verified = true
                            }
                        } else {
                            error = "Wrong PIN"
                            clear()
                        }
                        busy = false
                    }
                },
            )
            1 -> PinPad(
                title = "Choose a PIN",
                subtitle = "${PinLock.MIN_LENGTH} to ${PinLock.MAX_LENGTH} digits. You'll need it every time you open PrivateChat.",
                error = error,
                onSubmit = { pin, _ ->
                    error = null
                    first = pin
                },
            )
            else -> PinPad(
                title = "Enter it again",
                subtitle = if (busy) "Saving…" else "To make sure you remember it",
                error = error,
                enabled = !busy,
                onSubmit = { pin, _ ->
                    if (pin != first) {
                        error = "The PINs didn't match. Choose a PIN again."
                        first = null
                    } else {
                        busy = true
                        scope.launch {
                            val keepBiometric = graph.appLock.value?.biometric ?: Biometric.isAvailable(context)
                            graph.setPin(pin, keepBiometric)
                            onDone()
                        }
                    }
                },
            )
        }
    }
}
