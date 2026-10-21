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
import androidx.compose.material3.Badge
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gigamindtechnologies.privatechat.AppGraph
import com.gigamindtechnologies.privatechat.R
import com.gigamindtechnologies.privatechat.TorStatus
import com.gigamindtechnologies.privatechat.core.node.Node
import com.gigamindtechnologies.privatechat.core.store.Contact
import com.gigamindtechnologies.privatechat.core.store.MessageStatus
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
    // Until Tor is up there is no engine yet, but chats can still be listed from the encrypted files.
    val stored by produceState(emptyList<Contact>() to emptyMap<String, Node.ChatSummary>(), graph) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val contacts = graph.store.loadContacts()
                contacts to contacts.associate { contact ->
                    val messages = graph.store.loadMessages(contact.id)
                    contact.id to Node.ChatSummary(
                        messages.count { !it.outgoing && it.status == MessageStatus.RECEIVED },
                        messages.lastOrNull(),
                    )
                }
            }.getOrDefault(emptyList<Contact>() to emptyMap())
        }
    }
    val contacts = node?.contacts?.collectAsStateWithLifecycle()?.value ?: stored.first
    val summaries = node?.summaries?.collectAsStateWithLifecycle()?.value ?: stored.second
    val presence = node?.presence?.collectAsStateWithLifecycle()?.value ?: emptyMap()
    val showReadTicks by graph.readReceipts.collectAsStateWithLifecycle()
    // Most recent conversation first, like WhatsApp; new contacts without messages by when they were added.
    val sorted = remember(contacts, summaries) {
        contacts.sortedByDescending { summaries[it.id]?.last?.timestamp ?: it.addedAt }
    }

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
            BackgroundAccessBanner()
            NotificationsBanner()
            if (contacts.isEmpty()) {
                EmptyContacts()
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(sorted, key = { it.id }) { contact ->
                        ChatRow(
                            contact = contact,
                            summary = summaries[contact.id],
                            online = presence[contact.id]?.online == true,
                            showReadTicks = showReadTicks,
                            onClick = { onOpenChat(contact.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatRow(contact: Contact, summary: Node.ChatSummary?, online: Boolean, showReadTicks: Boolean, onClick: () -> Unit) {
    val last = summary?.last
    val unread = summary?.unread ?: 0
    ListItem(
        headlineContent = { Text(contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (last?.outgoing == true) {
                    Ticks(last.status, showReadTicks)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    last?.body?.replace('\n', ' ') ?: if (online) "Online" else "Say hello 👋",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (unread > 0) FontWeight.SemiBold else null,
                    color = if (unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = { Avatar(contact.name, online) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (last != null) {
                    Text(
                        chatListTime(last.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (unread > 0) ReadGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (unread > 0) {
                    Badge(containerColor = ReadGreen, contentColor = Color.White) { Text(if (unread > 99) "99+" else unread.toString()) }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
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
