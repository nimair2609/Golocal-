package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.example.customer.CustomerAppRoot
import com.example.notifications.NotificationDeepLinks
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
    private val deepLinkScreenState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        enableEdgeToEdge()
        setContent {
            GolocalAppRoot(initialScreen = deepLinkScreenState.value)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val screen = intent?.getStringExtra(NotificationDeepLinks.EXTRA_TARGET_SCREEN)?.takeIf { it.isNotBlank() }
        if (screen != null) deepLinkScreenState.value = screen
    }
}

@Composable
fun GolocalAppRoot(
    auth: FirebaseAuth = Firebase.auth,
    initialScreen: String? = null
) {
    CustomerAppRoot(auth = auth, initialDeepLinkScreen = initialScreen)
}
