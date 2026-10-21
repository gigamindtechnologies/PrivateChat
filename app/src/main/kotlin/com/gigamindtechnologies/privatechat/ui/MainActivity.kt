package com.gigamindtechnologies.privatechat.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gigamindtechnologies.privatechat.graph
import com.gigamindtechnologies.privatechat.service.Notifications

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots or screen recordings, and the app is blanked in the recent-apps list.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        if (savedInstanceState == null) openChatFrom(intent)
        setContent {
            PrivateChatTheme {
                AppRoot(graph)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openChatFrom(intent)
    }

    override fun onStart() {
        super.onStart()
        graph.onAppShown()
    }

    override fun onStop() {
        super.onStop()
        // Rotating the screen is not leaving the app.
        if (!isChangingConfigurations) graph.onAppHidden()
    }

    override fun onResume() {
        super.onResume()
        graph.node.value?.refreshNow()
    }

    /** A tapped notification names the chat to open. */
    private fun openChatFrom(intent: Intent?) {
        val contactId = intent?.getStringExtra(Notifications.EXTRA_CONTACT) ?: return
        if (CONTACT_ID.matches(contactId)) graph.openChat.value = contactId
    }

    private companion object {
        val CONTACT_ID = Regex("[0-9a-f]{16}")
    }
}
