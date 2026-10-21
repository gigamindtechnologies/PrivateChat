package com.gigamindtechnologies.privatechat.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigamindtechnologies.privatechat.AppGraph
import com.gigamindtechnologies.privatechat.IdentityState
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object ShowCode : Screen
    data object Scan : Screen
    data class Chat(val contactId: String) : Screen
    data object Settings : Screen
    data class SetPin(val flow: PinFlow) : Screen
}

class NavViewModel : ViewModel() {
    val stack = mutableStateListOf<Screen>(Screen.Home)

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun replaceTop(screen: Screen) {
        stack[stack.lastIndex] = screen
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun reset() {
        stack.clear()
        stack.add(Screen.Home)
    }
}

@Composable
fun AppRoot(graph: AppGraph) {
    val identity by graph.identity.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (identity) {
            IdentityState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            IdentityState.Missing -> SetupScreen(onCreate = { name ->
                scope.launch {
                    graph.createIdentity(name)
                    graph.goOnline()
                }
            })
            IdentityState.Unreadable -> UnreadableScreen(onReset = { scope.launch { graph.wipeEverything() } })
            is IdentityState.Ready -> {
                val locked by graph.locked.collectAsStateWithLifecycle()
                // Connect even while locked, so messages keep arriving.
                LaunchedEffect(Unit) { if (graph.wantsOnline.value) graph.goOnline() }
                if (locked) LockScreen(graph) else MainNavigation(graph)
            }
        }
    }
}

@Composable
private fun MainNavigation(graph: AppGraph) {
    val nav: NavViewModel = viewModel()
    val node by graph.node.collectAsStateWithLifecycle()
    val tor by graph.tor.collectAsStateWithLifecycle()

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // A tapped notification opens its chat.
    val openChat by graph.openChat.collectAsStateWithLifecycle()
    LaunchedEffect(openChat) {
        val contactId = openChat ?: return@LaunchedEffect
        graph.openChat.value = null
        nav.reset()
        nav.push(Screen.Chat(contactId))
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    when (val screen = nav.stack.last()) {
        Screen.Home -> HomeScreen(
            graph = graph,
            node = node,
            tor = tor,
            onOpenChat = { nav.push(Screen.Chat(it)) },
            onShowCode = { nav.push(Screen.ShowCode) },
            onScan = { nav.push(Screen.Scan) },
            onSettings = { nav.push(Screen.Settings) },
            onRetryTor = { graph.goOnline() },
        )
        Screen.ShowCode -> ShowCodeScreen(node = node, tor = tor, onPaired = { nav.replaceTop(Screen.Chat(it)) }, onBack = nav::pop)
        Screen.Scan -> ScanScreen(node = node, tor = tor, onPaired = { nav.replaceTop(Screen.Chat(it)) }, onBack = nav::pop)
        is Screen.Chat -> ChatScreen(graph = graph, node = node, contactId = screen.contactId, onBack = nav::pop)
        Screen.Settings -> SettingsScreen(
            graph = graph,
            node = node,
            tor = tor,
            onBack = nav::pop,
            onWiped = nav::reset,
            onSetPin = { nav.push(Screen.SetPin(it)) },
        )
        is Screen.SetPin -> PinSetupScreen(graph, screen.flow, onDone = nav::pop)
    }
}

@Composable
private fun UnreadableScreen(onReset: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Your data can't be opened", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The key that protects your messages is no longer available on this phone. " +
                "You can start over with a new identity.",
            textAlign = TextAlign.Center,
        )
        Button(onClick = onReset) { Text("Start over") }
    }
}
