package com.example.ui.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

sealed class GoogleSignInOutcome {
    data class Success(val uid: String, val email: String) : GoogleSignInOutcome()
    data class Cancelled(val message: String) : GoogleSignInOutcome()
    data class Error(val message: String) : GoogleSignInOutcome()
}

interface GoogleSignInGateway {
    fun isFirebaseInitialized(context: Context): Boolean
    suspend fun requestGoogleIdToken(context: Context, webClientId: String, autoSelectOnly: Boolean): String
    suspend fun signInWithGoogleCredential(credential: AuthCredential): Pair<String, String>
}

class DefaultGoogleSignInGateway(
    private val authProvider: () -> FirebaseAuth = { Firebase.auth }
) : GoogleSignInGateway {

    override fun isFirebaseInitialized(context: Context): Boolean =
        FirebaseApp.getApps(context).isNotEmpty()

    override suspend fun requestGoogleIdToken(
        context: Context,
        webClientId: String,
        autoSelectOnly: Boolean
    ): String {
        val credentialManager = CredentialManager.create(context)
        val request = if (autoSelectOnly) {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(true)
                .build()
            GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
        } else {
            val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(webClientId)
                .build()
            GetCredentialRequest.Builder()
                .addCredentialOption(signInWithGoogleOption)
                .build()
        }

        val result = credentialManager.getCredential(
            context = context,
            request = request
        )
        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
        return googleIdTokenCredential.idToken
    }

    override suspend fun signInWithGoogleCredential(credential: AuthCredential): Pair<String, String> {
        val authResult = authProvider().signInWithCredential(credential).await()
        val user = authResult.user ?: throw IllegalStateException("Firebase returned null user after Google Sign-In.")
        return user.uid to (user.email ?: "")
    }
}

object GoogleAuthManager {

    internal fun classifyGoogleFailure(throwable: Throwable): Pair<Boolean, String> {
        val code = (throwable as? FirebaseAuthException)?.errorCode
            ?: (throwable.cause as? FirebaseAuthException)?.errorCode
        return when {
            throwable is GetCredentialCancellationException ->
                true to "Google Sign-In cancelled."
            throwable is NoCredentialException ->
                false to "No Google account available on this device. Add a Google account or use Mobile OTP."
            throwable is TimeoutCancellationException ->
                false to "Google Sign-In timed out. Please check your connection and try again."
            throwable is FirebaseNetworkException ->
                false to "Network unavailable during Google Sign-In. Check your internet connection."
            throwable is FirebaseTooManyRequestsException ->
                false to "Too many sign-in attempts. Please wait a moment and try again."
            throwable is FirebaseAuthUserCollisionException || code == "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" ->
                false to "An account already exists with another sign-in method. Sign in with Mobile OTP or link accounts."
            code == "ERROR_OPERATION_NOT_ALLOWED" ->
                false to "Google Sign-In is disabled in Firebase Console. Enable Google provider or use Mobile OTP."
            code == "ERROR_INVALID_CREDENTIAL" || code == "ERROR_APP_NOT_AUTHORIZED" ->
                false to "Google OAuth verification failed. Verify SHA-1/SHA-256 and Web Client ID in google-services.json."
            throwable is GetCredentialException ->
                false to (throwable.errorMessage?.toString()?.takeIf { it.isNotBlank() }
                    ?: "Google Sign-In failed on this device. Try again or use Mobile OTP.")
            else ->
                false to (throwable.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "Unable to complete Google Sign-In. Please retry.")
        }
    }

    suspend fun attemptAutoSignIn(
        context: Context,
        gateway: GoogleSignInGateway = DefaultGoogleSignInGateway()
    ): GoogleSignInOutcome {
        if (!gateway.isFirebaseInitialized(context)) {
            return GoogleSignInOutcome.Error("Firebase is not initialized.")
        }
        val webClientId = runCatching { context.getString(R.string.default_web_client_id) }.getOrDefault("")
        if (webClientId.isBlank()) {
            return GoogleSignInOutcome.Error("Google Web Client ID resource is missing.")
        }
        if (!AuthFlowCoordinator.tryAcquireInteractive()) {
            return GoogleSignInOutcome.Cancelled("Another sign-in flow is already running.")
        }
        return try {
            val idToken = gateway.requestGoogleIdToken(context, webClientId, autoSelectOnly = true)
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val (uid, email) = withTimeout(FIREBASE_SIGN_IN_TIMEOUT_MS) {
                gateway.signInWithGoogleCredential(credential)
            }
            AuthDiagnostics.logStage(AuthStage.FIREBASE_SIGN_IN_SUCCESS, flow = "google_auto_sign_in")
            GoogleSignInOutcome.Success(uid, email)
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                GoogleSignInOutcome.Error("Auto Google Sign-In timed out.")
            } else {
                throw e
            }
        } catch (e: Exception) {
            val (cancelled, msg) = classifyGoogleFailure(e)
            if (cancelled) GoogleSignInOutcome.Cancelled(msg) else GoogleSignInOutcome.Error(msg)
        } finally {
            AuthFlowCoordinator.releaseInteractive()
        }
    }

    suspend fun onGoogleSignInClicked(
        context: Context,
        gateway: GoogleSignInGateway = DefaultGoogleSignInGateway()
    ): GoogleSignInOutcome {
        if (!gateway.isFirebaseInitialized(context)) {
            return GoogleSignInOutcome.Error("Firebase is not initialized. Check google-services.json.")
        }
        val webClientId = runCatching { context.getString(R.string.default_web_client_id) }.getOrDefault("")
        if (webClientId.isBlank()) {
            return GoogleSignInOutcome.Error("Google OAuth Web Client ID is missing in configuration.")
        }
        if (!AuthFlowCoordinator.tryAcquireInteractive()) {
            return GoogleSignInOutcome.Error("Another sign-in request is already in progress. Please wait.")
        }
        AuthDiagnostics.logStage(AuthStage.FIREBASE_SIGN_IN_STARTED, flow = "google_sign_in")
        return try {
            val idToken = gateway.requestGoogleIdToken(context, webClientId, autoSelectOnly = false)
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val (uid, email) = withTimeout(FIREBASE_SIGN_IN_TIMEOUT_MS) {
                gateway.signInWithGoogleCredential(credential)
            }
            AuthDiagnostics.logStage(AuthStage.FIREBASE_SIGN_IN_SUCCESS, flow = "google_sign_in")
            GoogleSignInOutcome.Success(uid, email)
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                AuthDiagnostics.logFailure(AuthStage.AUTH_FAILED, "google_sign_in", "TimeoutCancellationException")
                GoogleSignInOutcome.Error("Google Sign-In timed out. Check your connection and retry.")
            } else {
                throw e
            }
        } catch (e: Exception) {
            val (cancelled, msg) = classifyGoogleFailure(e)
            if (cancelled) {
                AuthDiagnostics.logStage(AuthStage.AUTH_CANCELLED, flow = "google_sign_in")
                GoogleSignInOutcome.Cancelled(msg)
            } else {
                val code = (e as? FirebaseAuthException)?.errorCode
                AuthDiagnostics.logFailure(
                    stage = AuthStage.AUTH_FAILED,
                    flow = "google_sign_in",
                    exceptionType = e::class.java.simpleName,
                    firebaseErrorCode = code
                )
                GoogleSignInOutcome.Error(msg)
            }
        } finally {
            AuthFlowCoordinator.releaseInteractive()
        }
    }
}
