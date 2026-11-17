package com.example.ui.auth

import android.content.Context
import android.content.MutableContextWrapper
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.UUID

data class GoogleAuthUiState(
    val isSigningIn: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

interface GoogleSignInGateway {
    fun isFirebaseInitialized(context: Context): Boolean
    fun resolveWebClientId(context: Context): String?
    suspend fun getGoogleIdToken(context: Context, webClientId: String, hashedNonce: String): String
    suspend fun signInWithGoogleIdToken(idToken: String): AuthResult
    suspend fun linkGoogleIdToken(idToken: String): AuthResult
    suspend fun clearCredentialState(context: Context)
}

class DefaultGoogleSignInGateway(
    private val authProvider: () -> FirebaseAuth = { Firebase.auth }
) : GoogleSignInGateway {

    override fun isFirebaseInitialized(context: Context): Boolean =
        FirebaseApp.getApps(context).isNotEmpty()

    override fun resolveWebClientId(context: Context): String? {
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (resId == 0) return null
        return context.getString(resId).trim().takeIf { it.isNotBlank() }
    }

    override suspend fun getGoogleIdToken(
        context: Context,
        webClientId: String,
        hashedNonce: String
    ): String {
        val uiContext = context.findActivity() ?: MutableContextWrapper(context)
        val credentialManager = CredentialManager.create(uiContext)
        val signInOption = GetSignInWithGoogleOption.Builder(webClientId)
            .setNonce(hashedNonce)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        val result = credentialManager.getCredential(
            context = uiContext,
            request = request
        )
        val credential = result.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
            return googleCred.idToken
        }
        error("Unsupported credential type returned by Google Credential Manager.")
    }

    override suspend fun signInWithGoogleIdToken(idToken: String): AuthResult {
        val firebaseCred = GoogleAuthProvider.getCredential(idToken, null)
        return authProvider().signInWithCredential(firebaseCred).await()
    }

    override suspend fun linkGoogleIdToken(idToken: String): AuthResult {
        val currentUser = authProvider().currentUser
            ?: throw IllegalStateException("No authenticated user session found for Google account linking.")
        val firebaseCred = GoogleAuthProvider.getCredential(idToken, null)
        return currentUser.linkWithCredential(firebaseCred).await()
    }

    override suspend fun clearCredentialState(context: Context) {
        runCatching {
            val uiContext = context.findActivity() ?: context
            CredentialManager.create(uiContext).clearCredentialState(ClearCredentialStateRequest())
        }
    }
}

internal fun classifyGoogleAuthFailure(throwable: Throwable): Triple<String?, String, String> {
    val firebaseCode = (throwable as? FirebaseAuthException)?.errorCode
        ?: (throwable.cause as? FirebaseAuthException)?.errorCode
    val excType = throwable::class.java.simpleName
    val rawMsg = throwable.message.orEmpty()

    val userMessage = when {
        throwable is GetCredentialCancellationException ->
            "Google sign-in was cancelled."
        throwable is NoCredentialException ->
            "No Google account available on this device/emulator. Sign in with a Google account in device Settings or continue with Mobile OTP below."
        throwable is GoogleIdTokenParsingException ->
            "Unable to parse Google ID token. Please try again."
        throwable is TimeoutCancellationException ->
            "Google sign-in timed out. Check your internet connection and retry."
        throwable is FirebaseNetworkException ->
            "Network unavailable during Google sign-in. Check your connection and try again."
        throwable is FirebaseAuthUserCollisionException ||
            firebaseCode == "ERROR_CREDENTIAL_ALREADY_IN_USE" ||
            firebaseCode == "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" ->
            "This Google account is already linked to a different GoLocal profile. Sign out and log in directly with Google to open that account."
        firebaseCode == "ERROR_OPERATION_NOT_ALLOWED" ->
            "Google Sign-In is disabled in Firebase Authentication. Enable Google provider in Firebase Console."
        firebaseCode == "ERROR_USER_DISABLED" ->
            "This GoLocal account has been disabled. Contact support."
        rawMsg.contains("DEVELOPER_ERROR", ignoreCase = true) ||
            rawMsg.contains("10:", ignoreCase = true) ||
            rawMsg.contains("reauth", ignoreCase = true) ->
            "Google Sign-In configuration mismatch (DEVELOPER_ERROR). Ensure this build's SHA-1 & SHA-256 fingerprints are registered in Firebase Console and google-services.json matches."
        throwable is GetCredentialException ->
            throwable.errorMessage?.toString()?.takeIf { it.isNotBlank() }
                ?: "Google Credential Manager failed. Check Google Play Services & SHA-1 configuration or use Mobile OTP."
        throwable is FirebaseAuthException ->
            "Firebase Google authentication failed (${throwable.errorCode}). Please try again."
        else ->
            throwable.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "Google sign-in failed. Please try again or use Mobile OTP."
    }
    return Triple(firebaseCode, excType, userMessage)
}

internal fun sha256NonceHex(rawNonce: String = UUID.randomUUID().toString()): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

class GoogleAuthController(
    private val scope: CoroutineScope,
    private val onSuccess: () -> Unit,
    private val gateway: GoogleSignInGateway = DefaultGoogleSignInGateway()
) {
    private val _uiState = MutableStateFlow(GoogleAuthUiState())
    val uiState: StateFlow<GoogleAuthUiState> = _uiState.asStateFlow()

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    fun signInWithGoogle(context: Context, linkToExistingUser: Boolean = false) {
        if (_uiState.value.isSigningIn) return
        if (!AuthFlowCoordinator.tryAcquireInteractive()) {
            _uiState.update {
                it.copy(errorMessage = "Another sign-in or verification request is already in progress.")
            }
            return
        }

        val flowName = if (linkToExistingUser) "google_link" else "google_sign_in"
        if (!gateway.isFirebaseInitialized(context)) {
            AuthFlowCoordinator.releaseInteractive()
            _uiState.update {
                it.copy(errorMessage = "Firebase is not initialized in this environment. Verify google-services.json.")
            }
            return
        }

        val webClientId = gateway.resolveWebClientId(context)
        if (webClientId.isNullOrBlank()) {
            AuthFlowCoordinator.releaseInteractive()
            AuthDiagnostics.logFailure(
                stage = AuthStage.AUTH_FAILED,
                flow = flowName,
                exceptionType = "MissingWebClientId",
                detail = "default_web_client_id string resource not found"
            )
            _uiState.update {
                it.copy(errorMessage = "Missing Firebase OAuth Web Client ID in google-services.json.")
            }
            return
        }

        AuthDiagnostics.logStage(
            stage = if (linkToExistingUser) AuthStage.ACCOUNT_LINK_STARTED else AuthStage.GOOGLE_SIGN_IN_STARTED,
            flow = flowName,
            detail = "Launching Google Credential Manager"
        )
        _uiState.update {
            it.copy(
                isSigningIn = true,
                errorMessage = null,
                infoMessage = if (linkToExistingUser) "Linking Google account..." else "Connecting with Google..."
            )
        }

        scope.launch {
            try {
                val hashedNonce = sha256NonceHex()
                val idToken = gateway.getGoogleIdToken(
                    context = context,
                    webClientId = webClientId,
                    hashedNonce = hashedNonce
                )

                withTimeout(FIREBASE_SIGN_IN_TIMEOUT_MS) {
                    if (linkToExistingUser) {
                        gateway.linkGoogleIdToken(idToken)
                    } else {
                        gateway.signInWithGoogleIdToken(idToken)
                    }
                }

                AuthDiagnostics.logStage(
                    stage = if (linkToExistingUser) AuthStage.ACCOUNT_LINK_SUCCESS else AuthStage.GOOGLE_SIGN_IN_SUCCESS,
                    flow = flowName,
                    detail = if (linkToExistingUser) "Linked Google credential to current user" else "Google sign-in exchanged with Firebase"
                )
                _uiState.update {
                    it.copy(
                        isSigningIn = false,
                        errorMessage = null,
                        infoMessage = if (linkToExistingUser) "Google account linked cleanly." else null
                    )
                }
                onSuccess()
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) {
                    val (code, excType, msg) = classifyGoogleAuthFailure(e)
                    AuthDiagnostics.logFailure(
                        stage = AuthStage.AUTH_FAILED,
                        flow = flowName,
                        exceptionType = excType,
                        firebaseErrorCode = code,
                        detail = "Google sign-in timed out"
                    )
                    _uiState.update { it.copy(isSigningIn = false, infoMessage = null, errorMessage = msg) }
                } else {
                    throw e
                }
            } catch (e: GetCredentialCancellationException) {
                AuthDiagnostics.logWarning(
                    stage = AuthStage.AUTH_CANCELLED,
                    flow = flowName,
                    exceptionType = e::class.java.simpleName,
                    detail = "User dismissed Google account picker"
                )
                _uiState.update {
                    it.copy(isSigningIn = false, infoMessage = null, errorMessage = "Google sign-in was cancelled.")
                }
            } catch (e: Exception) {
                val (code, excType, userMessage) = classifyGoogleAuthFailure(e)
                AuthDiagnostics.logFailure(
                    stage = AuthStage.AUTH_FAILED,
                    flow = flowName,
                    exceptionType = excType,
                    firebaseErrorCode = code,
                    detail = "Google Credential Manager / Firebase exchange failed"
                )
                _uiState.update {
                    it.copy(isSigningIn = false, infoMessage = null, errorMessage = userMessage)
                }
            } finally {
                AuthFlowCoordinator.releaseInteractive()
            }
        }
    }
}
