package com.gigamindtechnologies.privatechat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gigamindtechnologies.privatechat.TorStatus
import com.gigamindtechnologies.privatechat.core.node.Node
import kotlinx.coroutines.delay

@Composable
fun ShowCodeScreen(node: Node?, tor: TorStatus, onPaired: (String) -> Unit, onBack: () -> Unit) {
    if (node == null) {
        WaitingForTor("Your code", tor, onBack)
        return
    }
    val state by node.pairing.collectAsStateWithLifecycle()
    DisposableEffect(node) {
        node.showInvite()
        onDispose { if (node.pairing.value !is Node.Pairing.Paired) node.cancelPairing() }
    }
    LaunchedEffect(state) {
        (state as? Node.Pairing.Paired)?.let {
            node.cancelPairing()
            onPaired(it.contactId)
        }
    }

    Scaffold(topBar = { BackTopBar("Your code", onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                is Node.Pairing.ShowingCode -> CodeView(s.code, s.expiresAt)
                is Node.Pairing.Incoming -> {
                    CodeView(s.code, s.expiresAt)
                    IncomingRequestDialog(s, onAccept = node::acceptPairing, onReject = node::rejectPairing)
                }
                is Node.Pairing.Failed -> {
                    Text(
                        if (s.reason == Node.FailReason.EXPIRED) "This code expired." else "Something went wrong creating your code.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    PrimaryAction("Make a new code") { node.showInvite() }
                }
                else -> {
                    CircularProgressIndicator()
                    Text("Creating a one-time code…")
                }
            }
        }
    }
}

@Composable
private fun CodeView(code: String, expiresAt: Long) {
    FullBrightness()
    val image = remember(code) { qrImage(code) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val secondsLeft = ((expiresAt - now) / 1000).coerceAtLeast(0)

    Text("Let the other person scan this", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(
        "They open PrivateChat, tap \"Scan a code\" and point their camera here.",
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(color = Color.White, shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth()) {
        Image(
            bitmap = image,
            contentDescription = "Your one-time PrivateChat code",
            filterQuality = FilterQuality.None,
            modifier = Modifier.padding(12.dp).fillMaxWidth().aspectRatio(1f),
        )
    }
    Text(
        "Works once · expires in %d:%02d".format(secondsLeft / 60, secondsLeft % 60),
        fontWeight = FontWeight.Medium,
    )
    Text(
        "The code holds no name, number or ID — only a temporary address that is deleted afterwards. " +
            "Only show it to the person in front of you.",
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun IncomingRequestDialog(request: Node.Pairing.Incoming, onAccept: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("${request.name} wants to connect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Check that their screen shows exactly these symbols:")
                SafetyCodeView(request.safetyCode)
                Text(
                    "If they don't match, someone else scanned your code. Tap \"Not them\".",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text("They match") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Not them") } },
    )
}
