package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.customer.CustomerAppRoot
import com.example.notifications.NotificationDeepLinkTarget
import com.example.notifications.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

class MainActivity : ComponentActivity() {
    private var deepLinkState by mutableStateOf<NotificationDeepLinkTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.ensureChannels(this)
        deepLinkState = NotificationDeepLinkTarget.fromIntent(intent)
        setContent {
            GolocalAppRoot(
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

@Composable
fun GolocalAppRoot(
    auth: FirebaseAuth = Firebase.auth,
    deepLinkTarget: NotificationDeepLinkTarget? = null,
    onDeepLinkHandled: () -> Unit = {}
) {
    CustomerAppRoot(
        auth = auth,
        deepLinkTarget = deepLinkTarget,
        onDeepLinkHandled = onDeepLinkHandled
    )
}
