package com.example.ui.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.OperationType
import com.example.data.repository.handleFirestoreError
import com.example.shared.ExperienceMode
import com.example.shared.RoleAuthScreen
import com.example.ui.theme.GolocalTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthFlowUnitTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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
    fun `findActivity unwraps nested ContextWrapper to Activity`() {
        val wrapped = ContextWrapper(ContextWrapper(activity))
        assertEquals(activity, wrapped.findActivity())
        assertNull(appContext.findActivity())
    }

    @Test
    fun `AuthDiagnostics redacts JWT tokens phone numbers and email PII`() {
        AuthDiagnostics.logStage(
            stage = AuthStage.AUTH_FAILED,
            flow = "test_flow",
            detail = "Failed for alice@example.com phone +919876543210 token eyJhbGciOiJSUzI1NiJ9.abc123.sig456"
        )
        val events = AuthDiagnostics.recentEvents()
        assertEquals(1, events.size)
        val detail = events.first().detail
        assertFalse(detail.contains("alice@example.com"))
        assertFalse(detail.contains("9876543210"))
        assertFalse(detail.contains("eyJhbGciOiJSUzI1NiJ9"))
        assertTrue(detail.contains("[REDACTED_EMAIL]"))
        assertTrue(detail.contains("[REDACTED_PHONE]"))
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
    fun `AuthFlowCoordinator prevents concurrent interactive requests and resets on signOut`() = runBlocking {
        assertTrue(AuthFlowCoordinator.tryAcquireInteractive())
        assertFalse(AuthFlowCoordinator.tryAcquireInteractive())
        assertTrue(AuthFlowCoordinator.isInteractiveInFlight())

        val firebaseSignOutCount = AtomicInteger(0)
        var signOutCompleted = false
        signOut(
            scope = CoroutineScope(Dispatchers.Unconfined),
            onSignOutComplete = { signOutCompleted = true },
            firebaseSignOutAction = { firebaseSignOutCount.incrementAndGet() }
        )

        assertEquals(1, firebaseSignOutCount.get())
        assertTrue(signOutCompleted)
        assertFalse(AuthFlowCoordinator.isInteractiveInFlight())
        assertTrue(AuthDiagnostics.recentEvents().any { it.stage == AuthStage.AUTH_SIGN_OUT_COMPLETED })
    }

    @Test
    fun `all role auth screens support both Google Sign-In and Mobile OTP login`() {
        val activeMode = androidx.compose.runtime.mutableStateOf(ExperienceMode.CUSTOMER)
        composeTestRule.setContent {
            GolocalTheme {
                RoleAuthScreen(mode = activeMode.value, onAuthSuccess = {})
            }
        }
        listOf(ExperienceMode.CUSTOMER, ExperienceMode.RIDER, ExperienceMode.ADMIN).forEach { mode ->
            composeTestRule.runOnUiThread { activeMode.value = mode }
            composeTestRule.waitForIdle()

            // Verify Google Sign-In option is available alongside Mobile OTP
            composeTestRule.onNodeWithTag("google_sign_in_button").performScrollTo().assertIsDisplayed()

            // Verify Mobile OTP form renders directly without extra toggle step
            composeTestRule.onNodeWithTag("country_code_selector").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithTag("phone_number_input").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithTag("send_otp_button").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `NewUserRegistrationScreen validates all mandatory fields and submits privacy-safe payload`() {
        var submittedFullName: String? = null
        var submittedEmail: String? = null
        var submittedEmergency: String? = null
        var submittedTerms = false

        composeTestRule.setContent {
            GolocalTheme {
                NewUserRegistrationScreen(
                    verifiedMobileNumber = "+919876543210",
                    isSubmitting = false,
                    onSubmitRegistration = { name, email, emergency, terms, _ ->
                        submittedFullName = name
                        submittedEmail = email
                        submittedEmergency = emergency
                        submittedTerms = terms
                    },
                    onSignOut = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Verified mobile number is shown read-only
        composeTestRule.onNodeWithTag("reg_verified_mobile_display").assertIsDisplayed()
        composeTestRule.onNodeWithText("+919876543210").assertIsDisplayed()

        // Submitting empty form shows validation error
        composeTestRule.onNodeWithTag("reg_submit_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("reg_error_banner").performScrollTo().assertIsDisplayed()
        assertNull(submittedFullName)

        // Enter valid Full Name & Email, but duplicate own number as Emergency Contact -> rejected
        composeTestRule.onNodeWithTag("reg_full_name_input").performScrollTo().performTextInput("Anirban Chatterjee")
        composeTestRule.onNodeWithTag("reg_invoice_email_input").performScrollTo().performTextInput("anirban@example.com")
        composeTestRule.onNodeWithTag("reg_emergency_phone_input").performScrollTo().performTextInput("9876543210")
        composeTestRule.onNodeWithTag("reg_terms_checkbox").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("reg_submit_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertNull(submittedFullName)
        composeTestRule.onNodeWithText("Emergency contact number must be different from your own verified mobile number.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `NewUserRegistrationScreen submits cleanly when all fields and distinct emergency contact are valid`() {
        var submittedFullName: String? = null
        var submittedEmail: String? = null
        var submittedEmergency: String? = null
        var submittedTerms = false
        var submittedMobile: String? = null

        composeTestRule.setContent {
            GolocalTheme {
                NewUserRegistrationScreen(
                    verifiedMobileNumber = "+919876543210",
                    isSubmitting = false,
                    onSubmitRegistration = { name, email, emergency, terms, mobile ->
                        submittedFullName = name
                        submittedEmail = email
                        submittedEmergency = emergency
                        submittedTerms = terms
                        submittedMobile = mobile
                    },
                    onSignOut = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("reg_full_name_input").performScrollTo().performTextInput("Sohini Mukherjee")
        composeTestRule.onNodeWithTag("reg_invoice_email_input").performScrollTo().performTextInput("sohini.invoices@example.com")
        composeTestRule.onNodeWithTag("reg_emergency_phone_input").performScrollTo().performTextInput("9830011223")

        // Without accepting Terms checkbox, submission is blocked
        composeTestRule.onNodeWithTag("reg_submit_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertNull(submittedFullName)
        composeTestRule.onNodeWithText("Please accept Terms and Privacy Policy to continue.")
            .performScrollTo()
            .assertIsDisplayed()

        // Accept Terms and submit
        composeTestRule.onNodeWithTag("reg_terms_checkbox").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("reg_submit_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals("Sohini Mukherjee", submittedFullName)
        assertEquals("sohini.invoices@example.com", submittedEmail)
        assertEquals("+919830011223", submittedEmergency)
        assertEquals("+919876543210", submittedMobile)
        assertTrue(submittedTerms)
    }
}
