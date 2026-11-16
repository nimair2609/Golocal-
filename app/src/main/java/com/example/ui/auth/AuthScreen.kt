package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

internal const val FIREBASE_SIGN_IN_TIMEOUT_MS = 25_000L
internal const val CREDENTIAL_CLEAR_TIMEOUT_MS = 3_000L

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun resolveValidatedWebClientId(context: Context): Result<String> {
    val raw = try {
        context.getString(R.string.default_web_client_id).trim()
    } catch (e: Exception) {
        return Result.failure(
            IllegalStateException("Google Sign-In configuration missing: default_web_client_id not found.", e)
        )
    }
    if (
        raw.isBlank() ||
        raw.contains("YOUR_", ignoreCase = true) ||
        raw.contains("placeholder", ignoreCase = true) ||
        !raw.endsWith(".apps.googleusercontent.com")
    ) {
        return Result.failure(
            IllegalStateException("Google Sign-In Web Client ID in google-services.json is invalid or a placeholder.")
        )
    }
    return Result.success(raw)
}

internal fun classifyAuthFailure(throwable: Throwable): Triple<String?, String, String> {
    val firebaseErrorCode = (throwable as? FirebaseAuthException)?.errorCode
        ?: (throwable.cause as? FirebaseAuthException)?.errorCode
    val exceptionType = throwable::class.java.simpleName

    val userMessage = when (throwable) {
        is TimeoutCancellationException ->
            "Sign-in timed out. Please check your internet connection and try again."
        is FirebaseNetworkException ->
            "Network unavailable during sign-in. Please check your connection and retry."
        is FirebaseAuthInvalidCredentialsException ->
            "Your Google sign-in token was rejected or expired. Please choose your account again."
        is FirebaseAuthInvalidUserException ->
            "This GoLocal account is disabled or unavailable. Please contact support."
        is FirebaseAuthException -> when (throwable.errorCode) {
            "ERROR_OPERATION_NOT_ALLOWED" ->
                "Google Sign-In is not enabled in Firebase Authentication for this project."
            "ERROR_USER_DISABLED" ->
                "This account has been disabled. Contact GoLocal support."
            "ERROR_NETWORK_REQUEST_FAILED" ->
                "Network error while contacting Firebase Authentication. Please retry."
            else ->
                "Firebase sign-in failed (${throwable.errorCode}). Please try again."
        }
        is FirebaseException ->
            "Firebase authentication service failed. Please verify connectivity and retry."
        is NoCredentialException ->
            "No Google account is ready on this device. Add a Google account in device Settings and try again."
        is GetCredentialProviderConfigurationException ->
            "Google Sign-In provider configuration error. Check Google Play Services and app SHA fingerprint setup."
        is GetCredentialUnsupportedException ->
            "Google Sign-In requires updated Google Play Services on this device."
        is GetCredentialInterruptedException ->
            "Google Sign-In was interrupted. Please tap Continue with Google again."
        is GetCredentialException -> {
            val msg = throwable.message.orEmpty()
            if (msg.contains("DEVELOPER_ERROR", ignoreCase = true) ||
                msg.contains("10:", ignoreCase = true) ||
                msg.contains("28444") ||
                msg.contains("sha", ignoreCase = true)
            ) {
                "Google OAuth configuration mismatch for this APK signature. Verify SHA-1/SHA-256 registration in Firebase Console."
            } else {
                throwable.localizedMessage ?: "Google credential retrieval failed. Please try again."
            }
        }
        is IllegalStateException ->
            throwable.message ?: "Authentication configuration error."
        else ->
            throwable.localizedMessage ?: "Sign in failed. Please try again."
    }

    return Triple(firebaseErrorCode, exceptionType, userMessage)
}

internal object AuthFlowCoordinator {
    private val interactiveInFlight = AtomicBoolean(false)
    private val suppressNextAutoSignIn = AtomicBoolean(false)

    @Volatile
    private var autoSignInJob: Job? = null

    fun registerAutoSignInJob(job: Job) {
        autoSignInJob?.cancel()
        autoSignInJob = job
    }

    fun cancelAutoSignIn() {
        autoSignInJob?.cancel()
        autoSignInJob = null
    }

    fun tryAcquireInteractive(): Boolean = interactiveInFlight.compareAndSet(false, true)

    fun releaseInteractive() {
        interactiveInFlight.set(false)
    }

    fun isInteractiveInFlight(): Boolean = interactiveInFlight.get()

    fun markExplicitSignOut() {
        suppressNextAutoSignIn.set(true)
        cancelAutoSignIn()
        interactiveInFlight.set(false)
    }

    fun consumeSuppressAutoSignIn(): Boolean = suppressNextAutoSignIn.getAndSet(false)

    fun clearSuppressAutoSignIn() {
        suppressNextAutoSignIn.set(false)
    }

    fun resetForTesting() {
        cancelAutoSignIn()
        interactiveInFlight.set(false)
        suppressNextAutoSignIn.set(false)
    }
}

fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope,
    isFirebaseReady: (Context) -> Boolean = { FirebaseApp.getApps(it).isNotEmpty() },
    hasSignedInUser: () -> Boolean = { Firebase.auth.currentUser != null },
    firebaseTokenExchanger: suspend (String) -> Boolean = { googleIdToken ->
        val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
        Firebase.auth.signInWithCredential(authCredential).await().user != null
    }
) {
    AuthDiagnostics.logStage(AuthStage.AUTH_INIT, flow = "auto_sign_in")

    if (!isFirebaseReady(context)) {
        AuthDiagnostics.logWarning(
            stage = AuthStage.AUTH_FAILED,
            flow = "auto_sign_in",
            exceptionType = "FirebaseNotInitialized",
            detail = "FirebaseApp not initialized"
        )
        onUnauthenticated()
        return
    }

    if (hasSignedInUser()) {
        AuthDiagnostics.logStage(
            stage = AuthStage.AUTH_FLOW_COMPLETED,
            flow = "auto_sign_in",
            detail = "Existing Firebase session restored"
        )
        onAuthSuccess()
        return
    }

    if (AuthFlowCoordinator.consumeSuppressAutoSignIn()) {
        AuthDiagnostics.logStage(
            stage = AuthStage.AUTH_INIT,
            flow = "auto_sign_in",
            detail = "Skipped after explicit sign-out"
        )
        onUnauthenticated()
        return
    }

    if (AuthFlowCoordinator.isInteractiveInFlight()) {
        return
    }

    val clientId = resolveValidatedWebClientId(context).getOrElse { error ->
        AuthDiagnostics.logWarning(
            stage = AuthStage.AUTH_FAILED,
            flow = "auto_sign_in",
            exceptionType = error::class.java.simpleName,
            detail = "Web Client ID invalid or missing"
        )
        onUnauthenticated()
        return
    }

    val credentialContext = context.findActivity() ?: context
    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()

    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

    val job = scope.launch {
        try {
            AuthDiagnostics.logStage(AuthStage.GOOGLE_CREDENTIAL_REQUEST, flow = "auto_sign_in")
            val result = credentialManager.getCredential(credentialContext, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                if (googleIdToken.isBlank()) {
                    AuthDiagnostics.logWarning(
                        stage = AuthStage.AUTH_FAILED,
                        flow = "auto_sign_in",
                        exceptionType = "BlankIdToken",
                        detail = "Google credential had blank ID token"
                    )
                    onUnauthenticated()
                    return@launch
                }
                AuthDiagnostics.logStage(AuthStage.GOOGLE_CREDENTIAL_RECEIVED, flow = "auto_sign_in")
                AuthDiagnostics.logStage(AuthStage.FIREBASE_SIGN_IN_STARTED, flow = "auto_sign_in")
                val authenticated = withTimeout(FIREBASE_SIGN_IN_TIMEOUT_MS) {
                    firebaseTokenExchanger(googleIdToken)
                }
                if (authenticated) {
                    AuthDiagnostics.logStage(AuthStage.FIREBASE_SIGN_IN_SUCCESS, flow = "auto_sign_in")
                    onAuthSuccess()
                } else {
                    AuthDiagnostics.logWarning(
                        stage = AuthStage.AUTH_FAILED,
                        flow = "auto_sign_in",
                        exceptionType = "NullFirebaseUser",
                        detail = "Firebase returned null user after credential exchange"
                    )
                    onUnauthenticated()
                }
            } else {
                AuthDiagnostics.logWarning(
                    stage = AuthStage.AUTH_FAILED,
                    flow = "auto_sign_in",
                    exceptionType = "UnexpectedCredential",
                    detail = "type=${credential.type}"
                )
                onUnauthenticated()
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                val (_, excType, _) = classifyAuthFailure(e)
                AuthDiagnostics.logWarning(
                    stage = AuthStage.AUTH_FAILED,
                    flow = "auto_sign_in",
                    exceptionType = excType,
                    detail = "Auto sign-in timed out"
                )
                onUnauthenticated()
            } else {
                throw e
            }
        } catch (e: GetCredentialCancellationException) {
            AuthDiagnostics.logWarning(
                stage = AuthStage.AUTH_CANCELLED,
                flow = "auto_sign_in",
                exceptionType = e::class.java.simpleName,
                detail = "Silent credential check cancelled"
            )
            onUnauthenticated()
        } catch (e: Exception) {
            val (code, excType, _) = classifyAuthFailure(e)
            AuthDiagnostics.logWarning(
                stage = AuthStage.AUTH_FAILED,
                flow = "auto_sign_in",
                exceptionType = excType,
                firebaseErrorCode = code,
                detail = "Silent credential check ended unauthenticated"
            )
            onUnauthenticated()
        }
    }
    AuthFlowCoordinator.registerAutoSignInJob(job)
}

fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {},
    isFirebaseReady: (Context) -> Boolean = { FirebaseApp.getApps(it).isNotEmpty() },
    firebaseTokenExchanger: suspend (String) -> Boolean = { googleIdToken ->
        val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
        Firebase.auth.signInWithCredential(authCredential).await().user != null
    }
) {
    if (!AuthFlowCoordinator.tryAcquireInteractive()) {
        AuthDiagnostics.logWarning(
            stage = AuthStage.AUTH_INIT,
            flow = "interactive_google",
            exceptionType = "DuplicateSignInIgnored",
            detail = "Ignored duplicate sign-in tap while request is in progress"
        )
        return
    }

    AuthFlowCoordinator.clearSuppressAutoSignIn()
    AuthFlowCoordinator.cancelAutoSignIn()
    AuthDiagnostics.logStage(AuthStage.AUTH_INIT, flow = "interactive_google")

    if (!isFirebaseReady(context)) {
        AuthFlowCoordinator.releaseInteractive()
        AuthDiagnostics.logFailure(
            stage = AuthStage.AUTH_FAILED,
            flow = "interactive_google",
            exceptionType = "FirebaseNotInitialized",
            detail = "FirebaseApp is not initialized"
        )
        onAuthError("Firebase is not initialized for this build. Verify google-services.json and restart the app.")
        return
    }

    val clientId = resolveValidatedWebClientId(context).getOrElse { error ->
        AuthFlowCoordinator.releaseInteractive()
        val (_, excType, message) = classifyAuthFailure(error)
        AuthDiagnostics.logFailure(
            stage = AuthStage.AUTH_FAILED,
            flow = "interactive_google",
            exceptionType = excType,
            detail = "Web Client ID validation failed"
        )
        onAuthError(message)
        return
    }

    val activity = context.findActivity()
    if (activity == null) {
        AuthFlowCoordinator.releaseInteractive()
        AuthDiagnostics.logFailure(
            stage = AuthStage.AUTH_FAILED,
            flow = "interactive_google",
            exceptionType = "MissingActivityContext",
            detail = "Context did not resolve to an Activity"
        )
        onAuthError("Sign-in requires an active screen context.")
        return
    }

    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

    scope.launch {
        try {
            AuthDiagnostics.logStage(AuthStage.GOOGLE_CREDENTIAL_REQUEST, flow = "interactive_google")
            val result = credentialManager.getCredential(activity, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                if (googleIdToken.isBlank()) {
                    AuthDiagnostics.logFailure(
                        stage = AuthStage.AUTH_FAILED,
                        flow = "interactive_google",
                        exceptionType = "BlankIdToken",
                        detail = "Google returned blank ID token"
                    )
                    onAuthError("Google returned an empty sign-in token. Please try again.")
                    return@launch
                }
                AuthDiagnostics.logStage(AuthStage.GOOGLE_CREDENTIAL_RECEIVED, flow = "interactive_google")
                AuthDiagnostics.logStage(AuthStage.FIREBASE_SIGN_IN_STARTED, flow = "interactive_google")

                val authenticated = withTimeout(FIREBASE_SIGN_IN_TIMEOUT_MS) {
                    firebaseTokenExchanger(googleIdToken)
                }
                if (authenticated) {
                    AuthDiagnostics.logStage(
                        stage = AuthStage.FIREBASE_SIGN_IN_SUCCESS,
                        flow = "interactive_google",
                        detail = "Firebase authenticated user confirmed"
                    )
                    onAuthSuccess()
                } else {
                    AuthDiagnostics.logFailure(
                        stage = AuthStage.AUTH_FAILED,
                        flow = "interactive_google",
                        exceptionType = "NullFirebaseUser",
                        detail = "Firebase signInWithCredential returned null user"
                    )
                    onAuthError("Authentication did not complete. Please sign in again.")
                }
            } else {
                AuthDiagnostics.logFailure(
                    stage = AuthStage.AUTH_FAILED,
                    flow = "interactive_google",
                    exceptionType = "UnexpectedCredentialType",
                    detail = "type=${credential.type}"
                )
                onAuthError("Unexpected credential type returned.")
            }
        } catch (e: GetCredentialCancellationException) {
            AuthDiagnostics.logWarning(
                stage = AuthStage.AUTH_CANCELLED,
                flow = "interactive_google",
                exceptionType = e::class.java.simpleName,
                detail = "User cancelled Google account picker"
            )
            onAuthCancelled()
        } catch (e: TimeoutCancellationException) {
            val (code, excType, userMessage) = classifyAuthFailure(e)
            AuthDiagnostics.logFailure(
                stage = AuthStage.AUTH_FAILED,
                flow = "interactive_google",
                exceptionType = excType,
                firebaseErrorCode = code,
                detail = "Timed out awaiting Firebase signInWithCredential"
            )
            onAuthError(userMessage)
        } catch (e: CancellationException) {
            AuthDiagnostics.logWarning(
                stage = AuthStage.AUTH_CANCELLED,
                flow = "interactive_google",
                exceptionType = e::class.java.simpleName,
                detail = "Sign-in coroutine cancelled"
            )
            onAuthCancelled()
            throw e
        } catch (e: Exception) {
            val (code, excType, userMessage) = classifyAuthFailure(e)
            AuthDiagnostics.logFailure(
                stage = AuthStage.AUTH_FAILED,
                flow = "interactive_google",
                exceptionType = excType,
                firebaseErrorCode = code,
                detail = "Interactive Google sign-in failed"
            )
            onAuthError(userMessage)
        } finally {
            AuthFlowCoordinator.releaseInteractive()
        }
    }
}

fun signOut(
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onSignOutComplete: () -> Unit = {},
    firebaseSignOutAction: () -> Unit = { Firebase.auth.signOut() }
) {
    AuthFlowCoordinator.markExplicitSignOut()
    AuthDiagnostics.logStage(AuthStage.AUTH_SIGN_OUT_STARTED, flow = "sign_out")

    scope.launch {
        withContext(NonCancellable) {
            try {
                withTimeoutOrNull(CREDENTIAL_CLEAR_TIMEOUT_MS) {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                }
            } catch (e: Exception) {
                AuthDiagnostics.logWarning(
                    stage = AuthStage.AUTH_SIGN_OUT_STARTED,
                    flow = "sign_out",
                    exceptionType = e::class.java.simpleName,
                    detail = "Credential state clear failed softly"
                )
            } finally {
                firebaseSignOutAction()
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
