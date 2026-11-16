package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PrepareGetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.OperationType
import com.example.data.repository.handleFirestoreError
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthFlowUnitTest {

    private lateinit var appContext: Context
    private lateinit var activity: ComponentActivity

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            FirebaseApp.initializeApp(
                appContext,
                FirebaseOptions.Builder()
                    .setApplicationId("1:629202329249:android:f2cfdd8b9872a90a461ae0")
                    .setProjectId("gen-lang-client-0818250755")
                    .setApiKey("test-api-key")
                    .build()
            )
        }
        AuthFlowCoordinator.resetForTesting()
        AuthDiagnostics.clearEvents()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        AuthFlowCoordinator.resetForTesting()
        AuthDiagnostics.clearEvents()
    }

    @Test
    fun `resolveValidatedWebClientId returns real Web OAuth client ID from google-services`() {
        val result = resolveValidatedWebClientId(appContext)
        assertTrue("Expected valid default_web_client_id", result.isSuccess)
        val clientId = result.getOrThrow()
        assertTrue(clientId.endsWith(".apps.googleusercontent.com"))
        assertFalse(clientId.contains("YOUR_"))
    }

    @Test
    fun `findActivity unwraps nested ContextWrapper to Activity`() {
        val wrapped = ContextWrapper(ContextWrapper(activity))
        assertEquals(activity, wrapped.findActivity())
        assertNull(appContext.findActivity())
    }

    @Test
    fun `AuthDiagnostics redacts JWT tokens and email PII`() {
        AuthDiagnostics.logStage(
            stage = AuthStage.AUTH_FAILED,
            flow = "test_flow",
            detail = "Failed for user alice@example.com with token eyJhbGciOiJSUzI1NiJ9.abc123.sig456"
        )
        val events = AuthDiagnostics.recentEvents()
        assertEquals(1, events.size)
        val detail = events.first().detail
        assertFalse(detail.contains("alice@example.com"))
        assertFalse(detail.contains("eyJhbGciOiJSUzI1NiJ9"))
        assertTrue(detail.contains("[REDACTED_EMAIL]"))
        assertTrue(detail.contains("[REDACTED_TOKEN]"))
    }

    @Test
    fun `FirestoreErrorHandler omits email PII and includes firestore error code`() {
        val auth = FirebaseAuth.getInstance()
        val exception = FirebaseFirestoreException(
            "Missing or insufficient permissions",
            FirebaseFirestoreException.Code.PERMISSION_DENIED
        )
        val json = handleFirestoreError(exception, OperationType.GET, "users/test_uid", auth)
        assertTrue(json.contains("\"firebaseErrorCode\":\"PERMISSION_DENIED\""))
        assertTrue(json.contains("\"hasEmail\":"))
        assertFalse(json.contains("\"email\":"))
    }

    @Test
    fun `classifyAuthFailure maps Google Credential and Firebase exceptions cleanly`() {
        val (_, _, noCredMsg) = classifyAuthFailure(NoCredentialException("None available"))
        assertTrue(noCredMsg.contains("No Google account"))

        val (_, _, devErrMsg) = classifyAuthFailure(
            GetCredentialUnknownException("Developer console is not set up correctly: DEVELOPER_ERROR (10)")
        )
        assertTrue(devErrMsg.contains("SHA-1/SHA-256"))

        val (_, _, netMsg) = classifyAuthFailure(FirebaseNetworkException("offline"))
        assertTrue(netMsg.contains("Network unavailable"))

        val (opCode, _, opMsg) = classifyAuthFailure(
            FirebaseAuthException("ERROR_OPERATION_NOT_ALLOWED", "Provider disabled")
        )
        assertEquals("ERROR_OPERATION_NOT_ALLOWED", opCode)
        assertTrue(opMsg.contains("not enabled"))

        val (disCode, _, disMsg) = classifyAuthFailure(
            FirebaseAuthException("ERROR_USER_DISABLED", "Disabled")
        )
        assertEquals("ERROR_USER_DISABLED", disCode)
        assertTrue(disMsg.contains("disabled"))

        val (_, _, invCredMsg) = classifyAuthFailure(
            FirebaseAuthInvalidCredentialsException("ERROR_INVALID_CREDENTIAL", "Bad token")
        )
        assertTrue(invCredMsg.contains("rejected or expired"))

        val (_, _, invUserMsg) = classifyAuthFailure(
            FirebaseAuthInvalidUserException("ERROR_USER_NOT_FOUND", "Deleted")
        )
        assertTrue(invUserMsg.contains("disabled or unavailable"))

        val (_, _, providerCfgMsg) = classifyAuthFailure(
            GetCredentialProviderConfigurationException("GMS missing")
        )
        assertTrue(providerCfgMsg.contains("provider configuration error"))

        val (_, _, unsupportedMsg) = classifyAuthFailure(
            GetCredentialUnsupportedException("Unsupported")
        )
        assertTrue(unsupportedMsg.contains("Google Play Services"))

        val (_, _, interruptedMsg) = classifyAuthFailure(
            GetCredentialInterruptedException("Interrupted")
        )
        assertTrue(interruptedMsg.contains("interrupted"))

        val timeoutException = runBlocking {
            try {
                withTimeout(1) { kotlinx.coroutines.delay(50) }
                null
            } catch (e: TimeoutCancellationException) {
                e
            }
        }!!
        val (_, _, timeoutMsg) = classifyAuthFailure(timeoutException)
        assertTrue(timeoutMsg.contains("timed out"))
    }

    @Test
    fun `onGoogleSignInClicked fails safely when Firebase is uninitialized`() = runBlocking {
        val cm = fakeCredentialManager(onGet = { error("Should not be called") })
        var errorMsg: String? = null
        var successCalled = false

        onGoogleSignInClicked(
            context = activity,
            credentialManager = cm,
            onAuthSuccess = { successCalled = true },
            onAuthError = { errorMsg = it },
            scope = CoroutineScope(Dispatchers.Unconfined),
            isFirebaseReady = { false }
        )

        assertFalse(successCalled)
        assertNotNull(errorMsg)
        assertTrue(errorMsg!!.contains("Firebase is not initialized"))
        assertFalse(AuthFlowCoordinator.isInteractiveInFlight())
    }

    @Test
    fun `onGoogleSignInClicked fails safely with non-Activity context`() = runBlocking {
        val cm = fakeCredentialManager(onGet = { error("Should not be called") })
        var errorMsg: String? = null

        onGoogleSignInClicked(
            context = appContext,
            credentialManager = cm,
            onAuthSuccess = {},
            onAuthError = { errorMsg = it },
            scope = CoroutineScope(Dispatchers.Unconfined),
            isFirebaseReady = { true }
        )

        assertNotNull(errorMsg)
        assertTrue(errorMsg!!.contains("active screen context"))
        assertFalse(AuthFlowCoordinator.isInteractiveInFlight())
    }

    @Test
    fun `onGoogleSignInClicked handles user cancellation without error and resets lock`() = runBlocking {
        val cm = fakeCredentialManager(onGet = { throw GetCredentialCancellationException("User dismissed") })
        var cancelledCount = 0
        var errorMsg: String? = null
        var successCalled = false

        onGoogleSignInClicked(
            context = activity,
            credentialManager = cm,
            onAuthSuccess = { successCalled = true },
            onAuthError = { errorMsg = it },
            scope = CoroutineScope(Dispatchers.Unconfined),
            onAuthCancelled = { cancelledCount++ },
            isFirebaseReady = { true }
        )

        assertFalse(successCalled)
        assertNull(errorMsg)
        assertEquals(1, cancelledCount)
        assertFalse(AuthFlowCoordinator.isInteractiveInFlight())
        assertTrue(AuthDiagnostics.recentEvents().any { it.stage == AuthStage.AUTH_CANCELLED })
    }

    @Test
    fun `onGoogleSignInClicked blocks duplicate taps while interactive request is in flight`() = runBlocking {
        val gate = CompletableDeferred<GetCredentialResponse>()
        val requestStarted = CompletableDeferred<Unit>()
        val firstFinished = CompletableDeferred<Unit>()
        val requestCount = AtomicInteger(0)
        val cm = fakeCredentialManager(
            onGet = {
                requestCount.incrementAndGet()
                requestStarted.complete(Unit)
                gate.await()
            }
        )

        val scope = CoroutineScope(Dispatchers.Default + Job())
        onGoogleSignInClicked(
            context = activity,
            credentialManager = cm,
            onAuthSuccess = { firstFinished.complete(Unit) },
            onAuthError = { firstFinished.complete(Unit) },
            onAuthCancelled = { firstFinished.complete(Unit) },
            scope = scope,
            isFirebaseReady = { true }
        )
        withTimeout(3_000) { requestStarted.await() }
        assertTrue(AuthFlowCoordinator.isInteractiveInFlight())

        // Second tap while first request is still pending must be ignored cleanly
        onGoogleSignInClicked(
            context = activity,
            credentialManager = cm,
            onAuthSuccess = {},
            onAuthError = {},
            scope = scope,
            isFirebaseReady = { true }
        )

        assertEquals(1, requestCount.get())
        assertTrue(AuthFlowCoordinator.isInteractiveInFlight())

        gate.completeExceptionally(GetCredentialCancellationException("Done"))
        withTimeout(3_000) { firstFinished.await() }
        assertFalse(AuthFlowCoordinator.isInteractiveInFlight())
    }

    @Test
    fun `onGoogleSignInClicked succeeds with Google ID token and authenticates FirebaseUser`() = runBlocking {
        val cm = fakeCredentialManager(onGet = { validGoogleResponse("eyJhbGciOiJSUzI1NiJ9.payload.sig") })
        val exchangedTokens = mutableListOf<String>()
        var successCalled = false
        var errorMsg: String? = null

        onGoogleSignInClicked(
            context = activity,
            credentialManager = cm,
            onAuthSuccess = { successCalled = true },
            onAuthError = { errorMsg = it },
            scope = CoroutineScope(Dispatchers.Unconfined),
            isFirebaseReady = { true },
            firebaseTokenExchanger = { token ->
                exchangedTokens.add(token)
                true
            }
        )

        assertTrue("Expected interactive Google Sign-In to succeed", successCalled)
        assertNull(errorMsg)
        assertEquals(listOf("eyJhbGciOiJSUzI1NiJ9.payload.sig"), exchangedTokens)
        assertFalse(AuthFlowCoordinator.isInteractiveInFlight())

        val stages = AuthDiagnostics.recentEvents().map { it.stage }
        assertTrue(stages.contains(AuthStage.AUTH_INIT))
        assertTrue(stages.contains(AuthStage.GOOGLE_CREDENTIAL_REQUEST))
        assertTrue(stages.contains(AuthStage.GOOGLE_CREDENTIAL_RECEIVED))
        assertTrue(stages.contains(AuthStage.FIREBASE_SIGN_IN_STARTED))
        assertTrue(stages.contains(AuthStage.FIREBASE_SIGN_IN_SUCCESS))
    }

    @Test
    fun `onGoogleSignInClicked never reports success when Firebase returns false or throws`() = runBlocking {
        val cm = fakeCredentialManager(onGet = { validGoogleResponse("eyJhbGciOiJSUzI1NiJ9.payload.sig") })
        var successCount = 0
        val errors = mutableListOf<String>()

        onGoogleSignInClicked(
            context = activity,
            credentialManager = cm,
            onAuthSuccess = { successCount++ },
            onAuthError = { errors.add(it) },
            scope = CoroutineScope(Dispatchers.Unconfined),
            isFirebaseReady = { true },
            firebaseTokenExchanger = { false }
        )

        onGoogleSignInClicked(
            context = activity,
            credentialManager = cm,
            onAuthSuccess = { successCount++ },
            onAuthError = { errors.add(it) },
            scope = CoroutineScope(Dispatchers.Unconfined),
            isFirebaseReady = { true },
            firebaseTokenExchanger = { throw FirebaseNetworkException("Timeout") }
        )

        assertEquals(0, successCount)
        assertEquals(2, errors.size)
        assertTrue(errors[0].contains("did not complete"))
        assertTrue(errors[1].contains("Network unavailable"))
    }

    @Test
    fun `attemptAutoSignIn restores existing Firebase session without prompting CredentialManager`() = runBlocking {
        val cm = fakeCredentialManager(onGet = { error("Should not query CredentialManager when already signed in") })
        var restored = false

        attemptAutoSignIn(
            context = activity,
            credentialManager = cm,
            onAuthSuccess = { restored = true },
            onUnauthenticated = { error("Should not trigger unauthenticated") },
            scope = CoroutineScope(Dispatchers.Unconfined),
            isFirebaseReady = { true },
            hasSignedInUser = { true }
        )

        assertTrue(restored)
        assertTrue(AuthDiagnostics.recentEvents().any { it.stage == AuthStage.AUTH_FLOW_COMPLETED })
    }

    @Test
    fun `signOut clears credential state, signs out Firebase, and suppresses immediate auto re-login`() = runBlocking {
        val clearedCount = AtomicInteger(0)
        val firebaseSignOutCount = AtomicInteger(0)
        var signOutCompleted = false
        val getCredentialCount = AtomicInteger(0)

        val cm = fakeCredentialManager(
            onGet = {
                getCredentialCount.incrementAndGet()
                validGoogleResponse("eyJhbGciOiJSUzI1NiJ9.payload.sig")
            },
            onClear = { clearedCount.incrementAndGet() }
        )

        signOut(
            credentialManager = cm,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onSignOutComplete = { signOutCompleted = true },
            firebaseSignOutAction = { firebaseSignOutCount.incrementAndGet() }
        )

        assertEquals(1, clearedCount.get())
        assertEquals(1, firebaseSignOutCount.get())
        assertTrue(signOutCompleted)

        // Immediately after explicit sign-out, auto-sign-in must NOT silently re-authenticate
        var unauthenticatedCalled = false
        attemptAutoSignIn(
            context = activity,
            credentialManager = cm,
            onAuthSuccess = { error("Auto sign-in must be suppressed right after signOut") },
            onUnauthenticated = { unauthenticatedCalled = true },
            scope = CoroutineScope(Dispatchers.Unconfined),
            isFirebaseReady = { true },
            hasSignedInUser = { false }
        )
        assertTrue(unauthenticatedCalled)
        assertEquals(0, getCredentialCount.get())

        // Interactive sign-in after sign-out must work cleanly
        var reLoginSuccess = false
        onGoogleSignInClicked(
            context = activity,
            credentialManager = cm,
            onAuthSuccess = { reLoginSuccess = true },
            onAuthError = { error("Unexpected error: $it") },
            scope = CoroutineScope(Dispatchers.Unconfined),
            isFirebaseReady = { true },
            firebaseTokenExchanger = { true }
        )
        assertTrue(reLoginSuccess)
        assertEquals(1, getCredentialCount.get())
    }

    private fun validGoogleResponse(idToken: String): GetCredentialResponse {
        val bundle = Bundle().apply {
            putString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID", "rider@example.com")
            putString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN", idToken)
            putString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_DISPLAY_NAME", "GoLocal Tester")
        }
        val credential: Credential = CustomCredential(
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
            bundle
        )
        return GetCredentialResponse(credential)
    }

    private fun fakeCredentialManager(
        onGet: suspend (GetCredentialRequest) -> GetCredentialResponse,
        onClear: suspend () -> Unit = {}
    ): CredentialManager {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeCredentialManager"
                "getCredential" -> {
                    val lastArg = args?.lastOrNull()
                    if (lastArg is kotlin.coroutines.Continuation<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val cont = lastArg as kotlin.coroutines.Continuation<Any?>
                        val req = args.firstOrNull { it is GetCredentialRequest } as GetCredentialRequest
                        CoroutineScope(cont.context).launch {
                            val result = runCatching { onGet(req) }
                            cont.resumeWith(result)
                        }
                        kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
                    } else {
                        throw UnsupportedOperationException("Unexpected synchronous getCredential")
                    }
                }
                "getCredentialAsync" -> {
                    @Suppress("UNCHECKED_CAST")
                    val callback = args!!.last() as CredentialManagerCallback<GetCredentialResponse, GetCredentialException>
                    val req = args[1] as GetCredentialRequest
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val res = onGet(req)
                            callback.onResult(res)
                        } catch (e: GetCredentialException) {
                            callback.onError(e)
                        } catch (t: Throwable) {
                            callback.onError(GetCredentialUnknownException(t.message))
                        }
                    }
                    Unit
                }
                "clearCredentialState" -> {
                    val lastArg = args?.lastOrNull()
                    if (lastArg is kotlin.coroutines.Continuation<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val cont = lastArg as kotlin.coroutines.Continuation<Any?>
                        CoroutineScope(cont.context).launch {
                            val result = runCatching { onClear(); Unit }
                            cont.resumeWith(result)
                        }
                        kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
                    } else {
                        throw UnsupportedOperationException("Unexpected synchronous clearCredentialState")
                    }
                }
                "clearCredentialStateAsync" -> {
                    @Suppress("UNCHECKED_CAST")
                    val callback = args!!.last() as CredentialManagerCallback<Void?, ClearCredentialException>
                    CoroutineScope(Dispatchers.IO).launch {
                        onClear()
                        callback.onResult(null)
                    }
                    Unit
                }
                else -> Unit
            }
        }
        return Proxy.newProxyInstance(
            CredentialManager::class.java.classLoader,
            arrayOf(CredentialManager::class.java),
            handler
        ) as CredentialManager
    }
}
