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

        val pastedPrefix = PhoneNumberValidator.validateAndFormat(SupportedCountryCodes.INDIA, "+91 98765-43210")
        assertEquals("+919876543210", (pastedPrefix as PhoneValidationResult.Valid).e164Phone)

        val leadingZero = PhoneNumberValidator.validateAndFormat(SupportedCountryCodes.INDIA, "09123456789")
        assertEquals("+919123456789", (leadingZero as PhoneValidationResult.Valid).e164Phone)

        assertTrue(PhoneNumberValidator.validateAndFormat(SupportedCountryCodes.INDIA, "") is PhoneValidationResult.Invalid)
        assertTrue(PhoneNumberValidator.validateAndFormat(SupportedCountryCodes.INDIA, "98765") is PhoneValidationResult.Invalid)
        assertTrue(PhoneNumberValidator.validateAndFormat(SupportedCountryCodes.INDIA, "987654321012") is PhoneValidationResult.Invalid)
        assertTrue(PhoneNumberValidator.validateAndFormat(SupportedCountryCodes.INDIA, "5123456789") is PhoneValidationResult.Invalid)
        assertTrue(PhoneNumberValidator.validateAndFormat(SupportedCountryCodes.INDIA, "98765abc10") is PhoneValidationResult.Invalid)

        val usOption = SupportedCountryCodes.all.first { it.dialCode == "+1" }
        val usValid = PhoneNumberValidator.validateAndFormat(usOption, "6505550101")
        assertEquals("+16505550101", (usValid as PhoneValidationResult.Valid).e164Phone)
        assertTrue(PhoneNumberValidator.validateAndFormat(usOption, "12345") is PhoneValidationResult.Invalid)
    }

    @Test
    fun `RegistrationValidator enforces full name invoice email and distinct emergency contact`() {
        assertNotNull(RegistrationValidator.validateFullName(""))
        assertNotNull(RegistrationValidator.validateFullName("A"))
        assertNotNull(RegistrationValidator.validateFullName("1234"))
        assertNull(RegistrationValidator.validateFullName("Rohan Chakraborty"))

        assertNotNull(RegistrationValidator.validateInvoiceEmail(""))
        assertNotNull(RegistrationValidator.validateInvoiceEmail("not-an-email"))
        assertNotNull(RegistrationValidator.validateInvoiceEmail("rohan@invalid"))
        assertNull(RegistrationValidator.validateInvoiceEmail("rohan.invoices@example.com"))

        // Emergency contact must not equal user's own verified mobile number
        val sameNumberResult = RegistrationValidator.validateEmergencyContact(
            country = SupportedCountryCodes.INDIA,
            rawEmergency = "9876543210",
            ownVerifiedMobileE164 = "+919876543210"
        )
        assertTrue(sameNumberResult is PhoneValidationResult.Invalid)
        assertTrue((sameNumberResult as PhoneValidationResult.Invalid).message.contains("different"))

        // Distinct valid number succeeds
        val distinctResult = RegistrationValidator.validateEmergencyContact(
            country = SupportedCountryCodes.INDIA,
            rawEmergency = "9830011223",
            ownVerifiedMobileE164 = "+919876543210"
        )
        assertEquals("+919830011223", (distinctResult as PhoneValidationResult.Valid).e164Phone)
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

        val d1 = limiter.canRequestOtp() as OtpRateLimitDecision.Allowed
        assertEquals(60, d1.nextCooldownSeconds)
        limiter.recordOtpDispatched(d1.nextCooldownSeconds)

        val blocked = limiter.canRequestOtp()
        assertTrue(blocked is OtpRateLimitDecision.Blocked)
        assertTrue((blocked as OtpRateLimitDecision.Blocked).remainingSeconds in 59..60)

        now += 61_000L
        val d2 = limiter.canRequestOtp() as OtpRateLimitDecision.Allowed
        limiter.recordOtpDispatched(d2.nextCooldownSeconds)

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
        assertTrue(collision.contains("sign in directly with this mobile number"))

        val (_, _, notEnabled) = classifyPhoneAuthFailure(
            FirebaseAuthException("ERROR_OPERATION_NOT_ALLOWED", "Disabled")
        )
        assertTrue(notEnabled.contains("Enable Phone provider in Firebase Console"))

        val (_, _, regionBlocked) = classifyPhoneAuthFailure(
            FirebaseAuthException(
                "ERROR_OPERATION_NOT_ALLOWED",
                "SMS unable to be sent until this region enabled by the app developer. [ 17006 ]"
            )
        )
        assertTrue(regionBlocked.contains("17006"))
        assertTrue(regionBlocked.contains("SMS region policy"))

        val (_, _, appCheck) = classifyPhoneAuthFailure(
            FirebaseAuthException("ERROR_CAPTCHA_CHECK_FAILED", "Captcha failed")
        )
        assertTrue(appCheck.contains("SHA-1/SHA-256"))

        val (_, _, certMismatch) = classifyPhoneAuthFailure(
            FirebaseAuthException("ERROR_MISSING_CLIENT_IDENTIFIER", "missing SHA certificate hash")
        )
        assertTrue(certMismatch.contains("SHA-1/SHA-256"))

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
        }

        val controller = PhoneAuthController(
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
    fun `customer auth screen uses mobile OTP end to end without Google login`() {
        var fakeCallbacks: PhoneAuthProvider.OnVerificationStateChangedCallbacks? = null
        var authSucceeded = false
        val fakeGateway = object : FirebasePhoneAuthGateway {
            override fun isFirebaseInitialized(context: Context): Boolean = true
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
        }

        composeTestRule.setContent {
            GolocalTheme {
                RoleAuthScreen(
                    mode = ExperienceMode.CUSTOMER,
                    onAuthSuccess = { authSucceeded = true },
                    phoneControllerFactory = { scope, onDone ->
                        PhoneAuthController(
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

        // Mobile OTP form renders immediately without Google Sign-In
        assertTrue(composeTestRule.onAllNodesWithTag("google_sign_in_button").fetchSemanticsNodes().isEmpty())
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
    fun `rider and admin auth screens render Mobile OTP login only`() {
        val activeMode = androidx.compose.runtime.mutableStateOf(ExperienceMode.RIDER)
        composeTestRule.setContent {
            GolocalTheme {
                RoleAuthScreen(mode = activeMode.value, onAuthSuccess = {})
            }
        }
        composeTestRule.waitForIdle()
        assertTrue(composeTestRule.onAllNodesWithTag("google_sign_in_button").fetchSemanticsNodes().isEmpty())
        composeTestRule.onNodeWithTag("send_otp_button").performScrollTo().assertIsDisplayed()

        composeTestRule.runOnUiThread { activeMode.value = ExperienceMode.ADMIN }
        composeTestRule.waitForIdle()
        assertTrue(composeTestRule.onAllNodesWithTag("google_sign_in_button").fetchSemanticsNodes().isEmpty())
        composeTestRule.onNodeWithTag("send_otp_button").performScrollTo().assertIsDisplayed()
    }
}
