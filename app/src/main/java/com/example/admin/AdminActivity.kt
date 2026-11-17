package com.example.admin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.notifications.NotificationDeepLinkExtras
import com.example.notifications.NotificationDeepLinkTarget

class AdminActivity : ComponentActivity() {
    private var deepLinkState by mutableStateOf<NotificationDeepLinkTarget?>(null)
    private var legacyScreenState by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyIntentDeepLink(intent)
        enableEdgeToEdge()
        setContent {
            AdminAppRoot(
                initialDeepLinkModule = legacyScreenState,
                deepLinkTarget = deepLinkState,
                onDeepLinkHandled = {
                    deepLinkState = null
                    legacyScreenState = null
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntentDeepLink(intent)
    }

    private fun applyIntentDeepLink(intent: Intent?) {
        val target = NotificationDeepLinkTarget.fromIntent(intent)
        if (target != null) {
            deepLinkState = target
        }
        val screen = intent?.getStringExtra(NotificationDeepLinkExtras.EXTRA_TARGET_SCREEN)
            ?.takeIf { it.isNotBlank() }
        if (screen != null) {
            legacyScreenState = screen
        }
    }
}
