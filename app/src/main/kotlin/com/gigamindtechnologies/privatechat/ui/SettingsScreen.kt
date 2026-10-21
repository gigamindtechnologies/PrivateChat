package com.gigamindtechnologies.privatechat.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gigamindtechnologies.privatechat.AppGraph
import com.gigamindtechnologies.privatechat.IdentityState
import com.gigamindtechnologies.privatechat.TorStatus
import com.gigamindtechnologies.privatechat.core.node.Node
import com.gigamindtechnologies.privatechat.service.BackgroundAccess
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(graph: AppGraph, node: Node?, tor: TorStatus, onBack: () -> Unit, onWiped: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identity = (graph.identity.collectAsStateWithLifecycle().value as? IdentityState.Ready)?.identity
    val fingerprint = remember(identity) { identity?.fingerprint() ?: "" }
    var confirmWipe by remember { mutableStateOf(false) }
    var wiping by remember { mutableStateOf(false) }

    Scaffold(topBar = { BackTopBar("Settings", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("You") {
                Text(identity?.name ?: "", style = MaterialTheme.typography.titleLarge)
                Text("Your name is only shown to people you add.", style = MaterialTheme.typography.bodySmall)
                Text("Key fingerprint", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Text(fingerprint, fontFamily = FontFamily.Monospace)
            }

            Section("Connection") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (tor) {
                            TorStatus.Ready -> "Online"
                            is TorStatus.Starting -> "Connecting… ${tor.progress}%"
                            TorStatus.Failed -> "Connection failed"
                            TorStatus.Stopped -> "Offline"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = tor != TorStatus.Stopped,
                        onCheckedChange = { if (it) graph.goOnline() else graph.goOffline() },
                    )
                }
                Text(
                    "While online, PrivateChat keeps a private Tor connection open so your contacts can reach you. " +
                        "Go offline and nobody can reach you or see that you exist.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Background") {
                var unrestricted by remember { mutableStateOf(BackgroundAccess.isUnrestricted(context)) }
                LifecycleResumeEffect(Unit) {
                    unrestricted = BackgroundAccess.isUnrestricted(context)
                    onPauseOrDispose {}
                }
                if (unrestricted) {
                    Text("✓ Allowed to run in the background", style = MaterialTheme.typography.titleMedium, color = OnlineGreen)
                } else {
                    Text(
                        "Not allowed to run in the background. Messages stop arriving when the app is closed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { BackgroundAccess.request(context) }) { Text("Allow background") }
                }
                Text(
                    "There is no server to wake the app up, so it keeps its own connection open. " +
                        "On Xiaomi, Redmi, Poco, Oppo, Realme, Vivo, OnePlus and some Samsung phones, also turn on " +
                        "\"Autostart\" / \"Allow background activity\" for PrivateChat in the phone's own settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
                        )
                    } catch (_: ActivityNotFoundException) {
                    }
                }) { Text("Open app settings") }
            }

            Section("Privacy") {
                val readReceipts by graph.readReceipts.collectAsStateWithLifecycle()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Read receipts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = readReceipts, onCheckedChange = graph::setReadReceipts)
                }
                Text(
                    "When on, contacts see green ticks when you read their messages. " +
                        "When off, they don't, and you won't see theirs either.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("How you're protected") {
                listOf(
                    "No phone number, email or account. Your ID is a key created on this phone.",
                    "No servers. Phones connect directly through Tor, which hides both of your IP addresses.",
                    "Only your contacts can even find your address on the Tor network.",
                    "Every connection is end-to-end encrypted with fresh keys (forward secrecy).",
                    "Messages are encrypted on this phone with a key kept in the Android Keystore.",
                    "Screenshots are blocked, notifications never show names or text, and nothing is backed up to the cloud.",
                ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
                if (node != null) {
                    Text(
                        "${node.contacts.collectAsStateWithLifecycle().value.size} contacts",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Section("Delete everything") {
                Text(
                    "Permanently deletes your identity, contacts and messages from this phone and destroys the encryption key.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { confirmWipe = true },
                    enabled = !wiping,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (wiping) "Deleting…" else "Delete everything") }
            }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete everything?") },
            text = { Text("This cannot be undone. Your contacts will have to scan your code again to reach you.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmWipe = false
                        wiping = true
                        scope.launch {
                            graph.wipeEverything()
                            onWiped()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
