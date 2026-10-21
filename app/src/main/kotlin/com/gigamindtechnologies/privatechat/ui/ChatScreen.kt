@file:OptIn(ExperimentalMaterial3Api::class)

package com.gigamindtechnologies.privatechat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gigamindtechnologies.privatechat.AppGraph
import com.gigamindtechnologies.privatechat.R
import com.gigamindtechnologies.privatechat.core.node.Node
import com.gigamindtechnologies.privatechat.core.protocol.Wire
import com.gigamindtechnologies.privatechat.core.store.ChatMessage
import com.gigamindtechnologies.privatechat.core.store.Contact
import com.gigamindtechnologies.privatechat.core.store.MessageStatus
import com.gigamindtechnologies.privatechat.service.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatScreen(graph: AppGraph, node: Node?, contactId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    // Only while the app is in front: in the background, new messages should still notify.
    LifecycleResumeEffect(contactId) {
        graph.visibleChatId = contactId
        Notifications.clearMessages(context)
        onPauseOrDispose { if (graph.visibleChatId == contactId) graph.visibleChatId = null }
    }

    val contact: Contact?
    val messages: List<ChatMessage>
    if (node != null) {
        contact = node.contacts.collectAsStateWithLifecycle().value.find { it.id == contactId }
        val flow by produceState<StateFlow<List<ChatMessage>>?>(null, node, contactId) {
            value = withContext(Dispatchers.IO) { node.messages(contactId) }
        }
        messages = flow?.collectAsStateWithLifecycle()?.value ?: emptyList()
        // They removed us (or we removed them): leave the conversation.
        LaunchedEffect(contact) { if (contact == null) onBack() }
    } else {
        // Tor is still starting: show the saved conversation read-only.
        val stored by produceState<Pair<Contact?, List<ChatMessage>>>(null to emptyList(), contactId) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    graph.store.loadContacts().find { it.id == contactId } to graph.store.loadMessages(contactId)
                }.getOrDefault(null to emptyList())
            }
        }
        contact = stored.first
        messages = stored.second
    }
    val online = node?.online?.collectAsStateWithLifecycle()?.value?.contains(contactId) == true
    val name = contact?.name ?: ""
    val showReadTicks by graph.readReceipts.collectAsStateWithLifecycle()

    // Their messages count as read while this chat is on screen (not when the app is in the background).
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val unread = messages.count { !it.outgoing && it.status == MessageStatus.RECEIVED }
    LaunchedEffect(node, resumed, unread) {
        if (node != null && resumed && unread > 0) node.markRead(contactId)
    }

    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
                },
                title = {
                    Column {
                        Text(name)
                        Text(
                            if (online) "Online" else "Offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (online) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (node != null) {
                        IconButton(onClick = { menuOpen = true }) { Icon(painterResource(R.drawable.ic_more), contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; renaming = true })
                            DropdownMenuItem(text = { Text("Remove contact") }, onClick = { menuOpen = false; removing = true })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!online) {
                    Text(
                        if (node == null) {
                            "Connecting to Tor…"
                        } else {
                            "$name is offline. Your messages wait on this phone (🕓) and are sent as soon as $name is online."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(Wire.MAX_TEXT_LENGTH) },
                        placeholder = { Text("Message") },
                        enabled = node != null,
                        maxLines = 5,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = node != null && text.isNotBlank(),
                        onClick = {
                            val toSend = text
                            scope.launch {
                                val result = node?.sendText(contactId, toSend)
                                if (result == Node.SendResult.SENT || result == Node.SendResult.QUEUED) {
                                    if (text == toSend) text = ""
                                }
                            }
                        },
                    ) {
                        Icon(painterResource(R.drawable.ic_send), contentDescription = "Send")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        ) {
            items(messages.asReversed(), key = { (if (it.outgoing) "out-" else "in-") + it.id }) { message ->
                MessageBubble(message, showReadTicks)
            }
            if (messages.isEmpty()) {
                item {
                    Text(
                        "Messages are end-to-end encrypted and travel directly between your phones through Tor. " +
                            "They are never stored on any server.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
        }
    }

    if (renaming && node != null) {
        RenameDialog(name, onDismiss = { renaming = false }, onRename = { newName ->
            renaming = false
            scope.launch { node.renameContact(contactId, newName) }
        })
    }
    if (removing && node != null) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove $name?") },
            text = {
                Text(
                    "This deletes your conversation and your keys for each other. If $name is online right now, " +
                        "it is removed from their phone too. Neither of you will be able to reach the other " +
                        "until you scan codes again.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        removing = false
                        scope.launch { node.removeContact(contactId) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, showReadTicks: Boolean) {
    val outgoing = message.outgoing
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (outgoing) 16.dp else 4.dp,
                bottomEnd = if (outgoing) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(message.body)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                ) {
                    Text(
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (outgoing) {
                        Spacer(Modifier.width(4.dp))
                        Ticks(message.status, showReadTicks)
                    }
                }
            }
        }
    }
}

/**
 * 🕓 waiting on this phone · ✓ sent · ✓✓ delivered to their phone · green ✓✓ read.
 * With read receipts off, we don't show theirs either (like WhatsApp).
 */
@Composable
private fun Ticks(status: MessageStatus, showReadTicks: Boolean) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val (icon, tint, label) = when (status) {
        MessageStatus.SENDING, MessageStatus.FAILED -> Triple(R.drawable.ic_tick_clock, grey, "Waiting to send")
        MessageStatus.SENT -> Triple(R.drawable.ic_tick_single, grey, "Sent")
        MessageStatus.DELIVERED -> Triple(R.drawable.ic_tick_double, grey, "Delivered")
        MessageStatus.READ ->
            if (showReadTicks) Triple(R.drawable.ic_tick_double, ReadGreen, "Read")
            else Triple(R.drawable.ic_tick_double, grey, "Delivered")
        MessageStatus.RECEIVED -> return
    }
    Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename contact") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(Wire.MAX_NAME_LENGTH) },
                singleLine = true,
                supportingText = { Text("Only you see this name") },
            )
        },
        confirmButton = { Button(onClick = { onRename(value) }, enabled = value.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
