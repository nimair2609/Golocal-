package com.example.rider

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.notifications.NotificationDeepLinkTarget
import com.example.notifications.NotificationHelper
import com.example.security.AppCheckHelper

class RiderActivity : ComponentActivity() {
    private var deepLinkState by mutableStateOf<NotificationDeepLinkTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCheckHelper.configureAppCheck(this, intent)
        enableEdgeToEdge()
        NotificationHelper.ensureChannels(this)
        deepLinkState = NotificationDeepLinkTarget.fromIntent(intent)
        setContent {
            RiderAppRoot(
                deepLinkTarget = deepLinkState,
                onDeepLinkHandled = { deepLinkState = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkState = NotificationDeepLinkTarget.fromIntent(intent)
    }
}
