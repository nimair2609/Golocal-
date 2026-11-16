package com.example.ui.auth

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.shared.ExperienceMode
import com.example.shared.RoleAuthScreen
import com.example.ui.theme.GolocalTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseOptions
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthMissingActivityForRecaptchaException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class PhoneAuthUnitTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var appContext: Context
    private lateinit var activity: ComponentActivity
    private lateinit var testScope: CoroutineScope

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
        testScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        testScope.cancel()
        AuthFlowCoordinator.resetForTesting()
        AuthDiagnostics.clearEvents()
    }

    @Test
    fun `india default country code and 10 digit validation works accurately`() {
        assertEquals("+91", SupportedCountryCodes.INDIA.dialCode)

        val valid = PhoneNumberValidator.validateAndFormat(SupportedCountryCodes.INDIA, "9876543210")
        assertTrue(valid is PhoneValidationResult.Valid)
        assertEquals("+919876543210", (valid as PhoneValidationResult.Valid).e164Phone)

        // Handles pasted +91 prefix or leading 0 cleanly
        val pastedPrefix = PhoneNumberValidator.validateAndFormat(SupportedCountryCodes.INDIA, "+91 98765-43210")
        assertEquals("+919876543210", (pastedPrefix as PhoneValidationResult.Valid).e164Phone)

        val leadingZero = PhoneNumberValidator.validateAndFormat(SupportedCountryCodes.INDIA, "09123456789")
        assertEquals("+919123456789", (leadingZero as PhoneValidationResult.Valid).e164Phone)

        // Rejects invalid lengths & invalid leading digits (must start with 6-9)
        assertTrue(PhoneNumberValidator.validateAndFormat(SupportedCountryCodes.INDIA, "") is PhoneValidationResult.Invalid)
        assertTrue(PhoneNumberValidator.validateAndFormat(SupportedCountryCodes.INDIA, "98765") is PhoneValidationResult.Invalid)
        assertTrue(PhoneNumberValidator.validateAndFormat(SupportedCountryCodes.INDIA, "987654321012") is PhoneValidationResult.Invalid)
        assertTrue(PhoneNumberValidator.validateAndFormat(SupportedCountryCodes.INDIA, "5123456789") is PhoneValidationResult.Invalid)
        assertTrue(PhoneNumberValidator.validateAndFormat(SupportedCountryCodes.INDIA, "98765abc10") is PhoneValidationResult.Invalid)

        // International code validation
        val usOption = SupportedCountryCodes.all.first { it.dialCode == "+1" }
        val usValid = PhoneNumberValidator.validateAndFormat(usOption, "6505550101")
        assertEquals("+16505550101", (usValid as PhoneValidationResult.Valid).e164Phone)
        assertTrue(PhoneNumberValidator.validateAndFormat(usOption, "12345") is PhoneValidationResult.Invalid)
    }

    @Test
    fun `otp validation accepts only exact 6 digits`() {
        assertTrue(PhoneNumberValidator.isValidOtp("123456"))
        assertFalse(PhoneNumberValidator.isValidOtp("12345"))
        assertFalse(PhoneNumberValidator.isValidOtp("1234567"))
        assertFalse(PhoneNumberValidator.isValidOtp("12ab56"))
        assertFalse(PhoneNumberValidator.isValidOtp(""))
    }

    @Test
    fun `otp rate limiter enforces resend cooldown and sliding window abuse cap`() {
        var now = 1_000_000L
        val limiter = OtpRateLimiter(clockMillis = { now }, maxRequestsPerWindow = 5, windowMillis = 15 * 60_000L)

        // Request 1 allowed
        val d1 = limiter.canRequestOtp() as OtpRateLimitDecision.Allowed
        assertEquals(60, d1.nextCooldownSeconds)
        limiter.recordOtpDispatched(d1.nextCooldownSeconds)

        // Immediate resend blocked
        val blocked = limiter.canRequestOtp()
        assertTrue(blocked is OtpRateLimitDecision.Blocked)
        assertTrue((blocked as OtpRateLimitDecision.Blocked).remainingSeconds in 59..60)

        // After 61s, request 2 allowed
        now += 61_000L
        val d2 = limiter.canRequestOtp() as OtpRateLimitDecision.Allowed
        limiter.recordOtpDispatched(d2.nextCooldownSeconds)

        // Dispatch 3 more spaced past cooldowns
        now += 61_000L
        val d3 = limiter.canRequestOtp() as OtpRateLimitDecision.Allowed
        assertEquals(90, d3.nextCooldownSeconds)
        limiter.recordOtpDispatched(d3.nextCooldownSeconds)

        now += 91_000L
        val d4 = limiter.canRequestOtp() as OtpRateLimitDecision.Allowed
        assertEquals(120, d4.nextCooldownSeconds)
        limiter.recordOtpDispatched(d4.nextCooldownSeconds)

        now += 121_000L
        val d5 = limiter.canRequestOtp() as OtpRateLimitDecision.Allowed
        limiter.recordOtpDispatched(d5.nextCooldownSeconds)

        // 6th request within 15 min window must still be blocked even after cooldown or changing number
        now += 125_000L
        limiter.resetCooldownForNumberChange()
        val windowBlocked = limiter.canRequestOtp()
        assertTrue(windowBlocked is OtpRateLimitDecision.Blocked)
        assertTrue((windowBlocked as OtpRateLimitDecision.Blocked).reason.contains("Too many OTP requests"))
    }

    @Test
    fun `classifyPhoneAuthFailure maps all Firebase Phone Auth error conditions cleanly`() {
        val (_, _, wrongOtp) = classifyPhoneAuthFailure(
            FirebaseAuthInvalidCredentialsException("ERROR_INVALID_VERIFICATION_CODE", "Wrong OTP")
        )
        assertTrue(wrongOtp.contains("Incorrect 6-digit OTP"))

        val (_, _, expiredOtp) = classifyPhoneAuthFailure(
            FirebaseAuthException("ERROR_SESSION_EXPIRED", "Expired")
        )
        assertTrue(expiredOtp.contains("expired or was already used"))

        val (_, _, tooMany) = classifyPhoneAuthFailure(
            FirebaseTooManyRequestsException("Quota blocked")
        )
        assertTrue(tooMany.contains("limit reached"))

        val (_, _, collision) = classifyPhoneAuthFailure(
            FirebaseAuthUserCollisionException("ERROR_CREDENTIAL_ALREADY_IN_USE", "In use")
        )
        assertTrue(collision.contains("already linked to another GoLocal account"))

        val (_, _, alreadyLinked) = classifyPhoneAuthFailure(
            FirebaseAuthException("ERROR_PROVIDER_ALREADY_LINKED", "Already linked")
        )
        assertTrue(alreadyLinked.contains("already linked to this GoLocal account"))

        val (_, _, notEnabled) = classifyPhoneAuthFailure(
            FirebaseAuthException("ERROR_OPERATION_NOT_ALLOWED", "Disabled")
        )
        assertTrue(notEnabled.contains("Enable Phone provider in Firebase Console"))

        val (_, _, appCheck) = classifyPhoneAuthFailure(
            FirebaseAuthException("ERROR_CAPTCHA_CHECK_FAILED", "Captcha failed")
        )
        assertTrue(appCheck.contains("SHA-1/SHA-256"))

        val (_, _, network) = classifyPhoneAuthFailure(FirebaseNetworkException("Offline"))
        assertTrue(network.contains("Network unavailable"))

        val (_, _, recaptchaActivity) = classifyPhoneAuthFailure(
            FirebaseAuthMissingActivityForRecaptchaException()
        )
        assertTrue(recaptchaActivity.contains("active screen"))
    }

    @Test
    fun `AuthDiagnostics redacts raw phone numbers and provides safe masking`() {
        assertEquals("+91******3210", AuthDiagnostics.maskPhoneNumber("+919876543210"))
        AuthDiagnostics.logStage(
            stage = AuthStage.PHONE_OTP_REQUESTED,
            flow = "phone_test",
            detail = "Sending OTP to +919876543210"
        )
        val detail = AuthDiagnostics.recentEvents().single().detail
        assertFalse(detail.contains("9876543210"))
        assertTrue(detail.contains("[REDACTED_PHONE]"))
    }

    @Test
    fun `phone sign in flow validates sends OTP verifies 6 digits and handles change number & reuse`() = runBlocking {
        var capturedCallbacks: PhoneAuthProvider.OnVerificationStateChangedCallbacks? = null
        val verifyRequests = AtomicInteger(0)
        var signedInWith: String? = null
        var signInErrorToThrow: Exception? = null

        val fakeGateway = object : FirebasePhoneAuthGateway {
            override fun isFirebaseInitialized(context: Context): Boolean = true
            override fun hasCurrentUser(): Boolean = false
            override fun verifyPhoneNumber(
                activity: Activity,
                e164Phone: String,
                resendToken: PhoneAuthProvider.ForceResendingToken?,
                callbacks: PhoneAuthProvider.OnVerificationStateChangedCallbacks
            ) {
                verifyRequests.incrementAndGet()
                capturedCallbacks = callbacks
            }

            override fun createCredential(verificationId: String, smsCode: String): PhoneAuthCredential =
                PhoneAuthProvider.getCredential(verificationId, smsCode)

            override suspend fun signInWithCredential(credential: PhoneAuthCredential): String? {
                signInErrorToThrow?.let { throw it }
                return "+919876543210"
            }

            override suspend fun linkWithCredential(credential: PhoneAuthCredential): String? = null
        }

        val controller = PhoneAuthController(
            mode = PhoneAuthMode.SIGN_IN,
            scope = testScope,
            gateway = fakeGateway,
            rateLimiter = OtpRateLimiter(clockMillis = { 10_000L }),
            onSuccess = { signedInWith = it }
        )

        // Invalid phone stops locally without calling Firebase
        controller.updatePhoneInput("12345")
        controller.sendOtp(activity)
        assertEquals(0, verifyRequests.get())
        assertNotNull(controller.uiState.value.errorMessage)

        // Valid 10-digit Indian phone triggers Firebase verifyPhoneNumber
        controller.updatePhoneInput("9876543210")
        controller.sendOtp(activity)
        assertEquals(1, verifyRequests.get())
        assertTrue(controller.uiState.value.isSendingOtp)

        // Simulate Firebase onCodeSent
        val resendToken = PhoneAuthProvider.ForceResendingToken.zza()
        capturedCallbacks!!.onCodeSent("verif-123", resendToken)
        assertEquals(PhoneAuthStep.VERIFY_OTP, controller.uiState.value.step)
        assertEquals("+919876543210", controller.uiState.value.formattedE164)
        assertEquals(60, controller.uiState.value.resendCooldownSeconds)
        assertFalse(controller.uiState.value.isSendingOtp)

        // Simulate wrong OTP failure first (token remains usable to retry)
        signInErrorToThrow = FirebaseAuthInvalidCredentialsException("ERROR_INVALID_VERIFICATION_CODE", "Wrong code")
        controller.updateOtpInput("000000")
        controller.verifyEnteredOtp()
        assertNull(signedInWith)
        assertTrue(controller.uiState.value.errorMessage!!.contains("Incorrect 6-digit OTP"))

        // Simulate valid 6-digit OTP verification
        signInErrorToThrow = null
        controller.updateOtpInput("654321")
        controller.verifyEnteredOtp()
        assertEquals("+919876543210", signedInWith)
        assertNull(controller.uiState.value.errorMessage)

        // Submitting same already-used verification again must be blocked
        signedInWith = null
        controller.verifyEnteredOtp()
        assertNull(signedInWith)
        assertTrue(controller.uiState.value.errorMessage!!.contains("already been submitted or expired"))

        // Change mobile number cleanly resets to input step
        controller.changeMobileNumber()
        assertEquals(PhoneAuthStep.INPUT_PHONE, controller.uiState.value.step)
        assertNull(controller.uiState.value.verificationId)
    }

    @Test
    fun `phone linking requires explicit consent and handles duplicate phone collision safely`() = runBlocking {
        var capturedCallbacks: PhoneAuthProvider.OnVerificationStateChangedCallbacks? = null
        val linkCalls = AtomicInteger(0)
        var linkedSuccess: String? = null
        var shouldCollide = true

        val fakeGateway = object : FirebasePhoneAuthGateway {
            override fun isFirebaseInitialized(context: Context): Boolean = true
            override fun hasCurrentUser(): Boolean = true
            override fun verifyPhoneNumber(
                activity: Activity,
                e164Phone: String,
                resendToken: PhoneAuthProvider.ForceResendingToken?,
                callbacks: PhoneAuthProvider.OnVerificationStateChangedCallbacks
            ) {
                capturedCallbacks = callbacks
            }

            override fun createCredential(verificationId: String, smsCode: String): PhoneAuthCredential =
                PhoneAuthProvider.getCredential(verificationId, smsCode)

            override suspend fun signInWithCredential(credential: PhoneAuthCredential): String? = null

            override suspend fun linkWithCredential(credential: PhoneAuthCredential): String? {
                linkCalls.incrementAndGet()
                if (shouldCollide) {
                    throw FirebaseAuthUserCollisionException(
                        "ERROR_CREDENTIAL_ALREADY_IN_USE",
                        "Phone number belongs to another account"
                    )
                }
                return "+919876543210"
            }
        }

        val controller = PhoneAuthController(
            mode = PhoneAuthMode.LINK_EXISTING_ACCOUNT,
            scope = testScope,
            gateway = fakeGateway,
            rateLimiter = OtpRateLimiter(clockMillis = { 50_000L }),
            onSuccess = { linkedSuccess = it }
        )

        controller.updatePhoneInput("9876543210")

        // Without explicit consent checkbox, sendOtp must refuse
        controller.sendOtp(activity)
        assertNull(capturedCallbacks)
        assertTrue(controller.uiState.value.errorMessage!!.contains("consent"))

        // With consent accepted, OTP request proceeds
        controller.setLinkConsentAccepted(true)
        controller.sendOtp(activity)
        assertNotNull(capturedCallbacks)

        capturedCallbacks!!.onCodeSent("verif-link-1", PhoneAuthProvider.ForceResendingToken.zza())
        controller.updateOtpInput("112233")
        controller.verifyEnteredOtp()

        // Collision prevented duplicate/overwritten account and reported clear guidance
        assertEquals(1, linkCalls.get())
        assertNull(linkedSuccess)
        assertTrue(controller.uiState.value.errorMessage!!.contains("already linked to another GoLocal account"))

        // When non-colliding phone is verified, link succeeds and preserves single account
        shouldCollide = false
        controller.verifyEnteredOtp()
        assertEquals("+919876543210", linkedSuccess)
    }

    @Test
    fun `customer and rider auth screens preserve Google Login alongside Mobile OTP flow`() {
        var fakeCallbacks: PhoneAuthProvider.OnVerificationStateChangedCallbacks? = null
        var authSucceeded = false
        val fakeGateway = object : FirebasePhoneAuthGateway {
            override fun isFirebaseInitialized(context: Context): Boolean = true
            override fun hasCurrentUser(): Boolean = false
            override fun verifyPhoneNumber(
                activity: Activity,
                e164Phone: String,
                resendToken: PhoneAuthProvider.ForceResendingToken?,
                callbacks: PhoneAuthProvider.OnVerificationStateChangedCallbacks
            ) {
                fakeCallbacks = callbacks
            }

            override fun createCredential(verificationId: String, smsCode: String): PhoneAuthCredential =
                PhoneAuthProvider.getCredential(verificationId, smsCode)

            override suspend fun signInWithCredential(credential: PhoneAuthCredential): String = "+919876543210"
            override suspend fun linkWithCredential(credential: PhoneAuthCredential): String = "+919876543210"
        }

        composeTestRule.setContent {
            GolocalTheme {
                RoleAuthScreen(
                    mode = ExperienceMode.CUSTOMER,
                    onAuthSuccess = { authSucceeded = true },
                    phoneControllerFactory = { scope, onDone ->
                        PhoneAuthController(
                            mode = PhoneAuthMode.SIGN_IN,
                            scope = scope,
                            gateway = fakeGateway,
                            rateLimiter = OtpRateLimiter(clockMillis = { 100_000L }),
                            onSuccess = onDone
                        )
                    }
                )
            }
        }
        composeTestRule.waitForIdle()

        // Both Google and Mobile options must be present
        composeTestRule.onNodeWithTag("google_sign_in_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("mobile_sign_in_button").performScrollTo().assertIsDisplayed()

        // Enter Mobile Number flow
        composeTestRule.onNodeWithTag("mobile_sign_in_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("country_code_selector").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("🇮🇳 +91").assertIsDisplayed()

        // Enter valid Indian 10-digit number and send OTP
        composeTestRule.onNodeWithTag("phone_number_input").performScrollTo().performTextInput("9876543210")
        composeTestRule.onNodeWithTag("send_otp_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertNotNull(fakeCallbacks)
        composeTestRule.runOnUiThread {
            fakeCallbacks!!.onCodeSent("ui-verif-1", PhoneAuthProvider.ForceResendingToken.zza())
        }
        composeTestRule.waitForIdle()

        // 6-digit OTP UI, Change button, and Resend countdown appear
        composeTestRule.onNodeWithTag("change_phone_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("otp_code_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("resend_otp_button").performScrollTo().assertIsNotEnabled()

        // Enter 6-digit OTP and verify
        composeTestRule.onNodeWithTag("otp_code_input").performTextInput("123456")
        composeTestRule.onNodeWithTag("verify_otp_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertTrue("Expected mobile OTP UI flow to trigger onAuthSuccess", authSucceeded)
    }

    @Test
    fun `rider auth screen renders both Google Login and Mobile Number option`() {
        composeTestRule.setContent {
            GolocalTheme {
                RoleAuthScreen(mode = ExperienceMode.RIDER, onAuthSuccess = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("google_sign_in_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("mobile_sign_in_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `admin auth screen keeps strict Google gate without mobile login button`() {
        composeTestRule.setContent {
            GolocalTheme {
                RoleAuthScreen(mode = ExperienceMode.ADMIN, onAuthSuccess = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("google_sign_in_button").performScrollTo().assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithTag("mobile_sign_in_button").fetchSemanticsNodes().isEmpty())
    }
}
