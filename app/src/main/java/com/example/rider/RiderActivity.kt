package com.example.rider

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.example.notifications.NotificationDeepLinks

class RiderActivity : ComponentActivity() {
    private val deepLinkScreenState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLinkScreenState.value = intent?.getStringExtra(NotificationDeepLinks.EXTRA_TARGET_SCREEN)?.takeIf { it.isNotBlank() }
        enableEdgeToEdge()
        setContent {
            RiderAppRoot(initialDeepLinkScreen = deepLinkScreenState.value)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val screen = intent.getStringExtra(NotificationDeepLinks.EXTRA_TARGET_SCREEN)?.takeIf { it.isNotBlank() }
        if (screen != null) deepLinkScreenState.value = screen
    }
}
