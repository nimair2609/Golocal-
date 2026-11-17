package com.example.admin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.example.notifications.NotificationDeepLinks

class AdminActivity : ComponentActivity() {
    private val deepLinkModuleState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLinkModuleState.value = intent?.getStringExtra(NotificationDeepLinks.EXTRA_TARGET_SCREEN)?.takeIf { it.isNotBlank() }
        enableEdgeToEdge()
        setContent {
            AdminAppRoot(initialDeepLinkModule = deepLinkModuleState.value)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val screen = intent.getStringExtra(NotificationDeepLinks.EXTRA_TARGET_SCREEN)?.takeIf { it.isNotBlank() }
        if (screen != null) deepLinkModuleState.value = screen
    }
}
