package com.gigamindtechnologies.privatechat.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gigamindtechnologies.privatechat.graph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots or screen recordings, and the app is blanked in the recent-apps list.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            PrivateChatTheme {
                AppRoot(graph)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        graph.node.value?.refreshNow()
    }
}
