@file:OptIn(ExperimentalMaterial3Api::class)

package com.gigamindtechnologies.privatechat.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gigamindtechnologies.privatechat.R
import com.gigamindtechnologies.privatechat.TorStatus

val OnlineGreen = Color(0xFF2E7D32)

@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
        },
        actions = { actions() },
    )
}

/** Shown on pairing screens until Tor is ready. */
@Composable
fun WaitingForTor(title: String, tor: TorStatus?, onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar(title, onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text("Connecting to the Tor network…", style = MaterialTheme.typography.titleMedium)
            val progress = (tor as? TorStatus.Starting)?.progress
            if (progress != null) Text("$progress%")
        }
    }
}

@Composable
fun TorBanner(tor: TorStatus, onRetry: () -> Unit) {
    when (tor) {
        is TorStatus.Starting -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Connecting privately through Tor… ${tor.progress}%", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { tor.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        TorStatus.Ready -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_lock), null, tint = OnlineGreen, modifier = Modifier.size(16.dp))
            Text(
                "  Online privately — contacts can reach you",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TorStatus.Failed -> Banner("Couldn't connect to Tor. Check your internet connection.", "Retry", onRetry)
        TorStatus.Stopped -> Banner("You're offline. Nobody can reach you.", "Go online", onRetry)
    }
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun SafetyCodeView(symbols: List<String>) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
        Text(
            symbols.joinToString("  "),
            fontSize = 34.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }
}

@Composable
fun Avatar(name: String, online: Boolean) {
    Box {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        if (online) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface).padding(2.dp).clip(CircleShape).background(OnlineGreen),
            )
        }
    }
}

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

/** Turns the screen to full brightness while shown, so the QR code scans easily. */
@Composable
fun FullBrightness() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        val previous = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window?.attributes = window.attributes.apply { screenBrightness = 1f }
        onDispose { window?.attributes = window.attributes.apply { screenBrightness = previous } }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
