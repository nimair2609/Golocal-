package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal const val FIREBASE_SIGN_IN_TIMEOUT_MS = 25_000L

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal object AuthFlowCoordinator {
    private val interactiveInFlight = AtomicBoolean(false)

    fun tryAcquireInteractive(): Boolean = interactiveInFlight.compareAndSet(false, true)

    fun releaseInteractive() {
        interactiveInFlight.set(false)
    }

    fun isInteractiveInFlight(): Boolean = interactiveInFlight.get()

    fun markExplicitSignOut() {
        interactiveInFlight.set(false)
    }

    fun resetForTesting() {
        interactiveInFlight.set(false)
    }
}

fun signOut(
    scope: CoroutineScope,
    context: Context? = null,
    onSignOutComplete: () -> Unit = {},
    firebaseSignOutAction: () -> Unit = { Firebase.auth.signOut() }
) {
    AuthFlowCoordinator.markExplicitSignOut()
    AuthDiagnostics.logStage(AuthStage.AUTH_SIGN_OUT_STARTED, flow = "sign_out")

    scope.launch {
        withContext(NonCancellable) {
            try {
                if (context != null) {
                    val uid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull().orEmpty()
                    if (uid.isNotBlank()) {
                        com.example.notifications.FcmTokenSyncManager.removeCurrentToken(context, uid)
                    }
                    DefaultGoogleSignInGateway().clearCredentialState(context)
                }
                firebaseSignOutAction()
            } finally {
                AuthDiagnostics.logStage(AuthStage.AUTH_SIGN_OUT_COMPLETED, flow = "sign_out")
                onSignOutComplete()
            }
        }
    }
}

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit
) {
    com.example.shared.RoleAuthScreen(
        mode = com.example.shared.ExperienceMode.CUSTOMER,
        onAuthSuccess = onAuthSuccess
    )
}
