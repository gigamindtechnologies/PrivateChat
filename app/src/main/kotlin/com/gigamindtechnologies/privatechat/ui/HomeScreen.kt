@file:OptIn(ExperimentalMaterial3Api::class)

package com.gigamindtechnologies.privatechat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gigamindtechnologies.privatechat.AppGraph
import com.gigamindtechnologies.privatechat.R
import com.gigamindtechnologies.privatechat.TorStatus
import com.gigamindtechnologies.privatechat.core.node.Node
import com.gigamindtechnologies.privatechat.core.store.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    graph: AppGraph,
    node: Node?,
    tor: TorStatus,
    onOpenChat: (String) -> Unit,
    onShowCode: () -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onRetryTor: () -> Unit,
) {
    // Until Tor is up there is no engine yet, but contacts can still be listed from the encrypted files.
    val stored by produceState(emptyList<Contact>(), graph) {
        value = withContext(Dispatchers.IO) { runCatching { graph.store.loadContacts() }.getOrDefault(emptyList()) }
    }
    val contacts = node?.contacts?.collectAsStateWithLifecycle()?.value ?: stored
    val online = node?.online?.collectAsStateWithLifecycle()?.value ?: emptySet()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PrivateChat") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_more), contentDescription = "Settings") }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(onClick = onShowCode, enabled = node != null, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.ic_qr), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Show my code")
                }
                Button(onClick = onScan, enabled = node != null, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.ic_scan), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan a code")
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TorBanner(tor, onRetryTor)
            if (contacts.isEmpty()) {
                EmptyContacts()
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(contacts.sortedBy { it.name.lowercase() }, key = { it.id }) { contact ->
                        val isOnline = contact.id in online
                        ListItem(
                            headlineContent = { Text(contact.name) },
                            supportingContent = {
                                Text(
                                    if (isOnline) "Online" else "Offline",
                                    color = if (isOnline) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            leadingContent = { Avatar(contact.name, isOnline) },
                            modifier = Modifier.clickable { onOpenChat(contact.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyContacts() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No contacts yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Meet the person you want to talk to. One of you taps \"Show my code\", " +
                "the other taps \"Scan a code\". That's the only way to add someone.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
