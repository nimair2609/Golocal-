package com.example.ui.auth

import android.app.Activity
import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthMissingActivityForRecaptchaException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

data class CountryCodeOption(
    val isoCode: String,
    val countryName: String,
    val dialCode: String,
    val flagEmoji: String,
    val minDigits: Int,
    val maxDigits: Int,
    val sampleHint: String
)

object SupportedCountryCodes {
    val INDIA = CountryCodeOption(
        isoCode = "IN",
        countryName = "India",
        dialCode = "+91",
        flagEmoji = "🇮🇳",
        minDigits = 10,
        maxDigits = 10,
        sampleHint = "9876543210"
    )

    val all: List<CountryCodeOption> = listOf(
        INDIA,
        CountryCodeOption("BD", "Bangladesh", "+880", "🇧🇩", 10, 10, "1712345678"),
        CountryCodeOption("NP", "Nepal", "+977", "🇳🇵", 10, 10, "9841234567"),
        CountryCodeOption("AE", "UAE", "+971", "🇦🇪", 9, 9, "501234567"),
        CountryCodeOption("SG", "Singapore", "+65", "🇸🇬", 8, 8, "81234567"),
        CountryCodeOption("GB", "United Kingdom", "+44", "🇬🇧", 10, 10, "7700900123"),
        CountryCodeOption("US", "United States", "+1", "🇺🇸", 10, 10, "6505550101")
    )
}

sealed class PhoneValidationResult {
    data class Valid(
        val e164Phone: String,
        val nationalDigits: String
    ) : PhoneValidationResult()

    data class Invalid(val message: String) : PhoneValidationResult()
}

object PhoneNumberValidator {
    private val INDIAN_MOBILE_REGEX = Regex("^[6-9]\\d{9}$")
    private val SIX_DIGIT_OTP_REGEX = Regex("^\\d{6}$")

    fun validateAndFormat(
        country: CountryCodeOption,
        rawInput: String
    ): PhoneValidationResult {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return PhoneValidationResult.Invalid("Please enter your mobile number.")
        }

        // Strip formatting spaces, dashes, parentheses
        var stripped = trimmed.replace(Regex("[\\s\\-()]"), "")

        // Strip matching country dial code if pasted with prefix (e.g. +919876543210)
        val dialDigits = country.dialCode.removePrefix("+")
        if (stripped.startsWith(country.dialCode)) {
            stripped = stripped.removePrefix(country.dialCode)
        } else if (stripped.startsWith("00$dialDigits")) {
            stripped = stripped.removePrefix("00$dialDigits")
        } else if (stripped.startsWith(dialDigits) && stripped.length > country.maxDigits) {
            stripped = stripped.removePrefix(dialDigits)
        }

        // Strip single leading 0 on 11-digit Indian numbers like 09876543210
        if (country.dialCode == "+91" && stripped.startsWith("0") && stripped.length == 11) {
            stripped = stripped.substring(1)
        }

        if (stripped.any { !it.isDigit() }) {
            return PhoneValidationResult.Invalid("Mobile number must contain digits only.")
        }

        if (country.dialCode == "+91") {
            if (stripped.length != 10) {
                return PhoneValidationResult.Invalid("Enter a valid 10-digit Indian mobile number.")
            }
            if (!INDIAN_MOBILE_REGEX.matches(stripped)) {
                return PhoneValidationResult.Invalid(
                    "Indian mobile numbers must be 10 digits starting with 6, 7, 8, or 9."
                )
            }
        } else {
            if (stripped.length !in country.minDigits..country.maxDigits) {
                val rangeText = if (country.minDigits == country.maxDigits) {
                    "${country.minDigits}"
                } else {
                    "${country.minDigits}-${country.maxDigits}"
                }
                return PhoneValidationResult.Invalid(
                    "Enter a valid $rangeText-digit mobile number for ${country.countryName}."
                )
            }
            if (stripped.startsWith("0")) {
                return PhoneValidationResult.Invalid("Enter mobile digits without leading 0.")
            }
        }

        return PhoneValidationResult.Valid(
            e164Phone = "${country.dialCode}$stripped",
            nationalDigits = stripped
        )
    }

    fun isValidOtp(rawOtp: String): Boolean = SIX_DIGIT_OTP_REGEX.matches(rawOtp.trim())
}

object RegistrationValidator {
    private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun validateFullName(raw: String): String? {
        val trimmed = raw.trim()
        return when {
            trimmed.isBlank() -> "Full Name is required."
            trimmed.length < 2 -> "Enter your full name (at least 2 characters)."
            trimmed.length > 100 -> "Full Name must be 100 characters or fewer."
            !trimmed.any { it.isLetter() } -> "Full Name must include alphabetic characters."
            else -> null
        }
    }

    fun validateInvoiceEmail(raw: String): String? {
        val trimmed = raw.trim()
        return when {
            trimmed.isBlank() -> "Email address is required for GST invoice delivery."
            trimmed.length > 150 -> "Email address must be 150 characters or fewer."
            !EMAIL_REGEX.matches(trimmed) -> "Enter a valid email address (used for GST invoices only)."
            else -> null
        }
    }

    fun validateEmergencyContact(
        country: CountryCodeOption,
        rawEmergency: String,
        ownVerifiedMobileE164: String
    ): PhoneValidationResult {
        val trimmed = rawEmergency.trim()
        if (trimmed.isBlank()) {
            return PhoneValidationResult.Invalid("Emergency contact number is required.")
        }
        val formatted = PhoneNumberValidator.validateAndFormat(country, trimmed)
        if (formatted is PhoneValidationResult.Valid) {
            val ownClean = ownVerifiedMobileE164.trim()
            if (ownClean.isNotBlank() && formatted.e164Phone == ownClean) {
                return PhoneValidationResult.Invalid(
                    "Emergency contact number must be different from your own verified mobile number."
                )
            }
        }
        return formatted
    }
}

sealed class OtpRateLimitDecision {
    data class Allowed(val nextCooldownSeconds: Int) : OtpRateLimitDecision()
    data class Blocked(
        val remainingSeconds: Int,
        val reason: String
    ) : OtpRateLimitDecision()
}

/**
 * Prevents repeated OTP spam and SMS quota abuse within an app session.
 * Note: Server-side rate limits and reCAPTCHA/Play Integrity are additionally enforced by Firebase Auth.
 */
class OtpRateLimiter(
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val maxRequestsPerWindow: Int = 5,
    private val windowMillis: Long = 15 * 60_000L
) {
    private val requestTimestamps = mutableListOf<Long>()
    private var lastSentAtMillis: Long = 0L
    private var lastCooldownDurationSeconds: Int = 60

    @Synchronized
    fun canRequestOtp(): OtpRateLimitDecision {
        val now = clockMillis()
        requestTimestamps.removeAll { now - it > windowMillis }

        if (lastSentAtMillis > 0L) {
            val elapsedSeconds = ((now - lastSentAtMillis) / 1000L).toInt()
            val remainingCooldown = lastCooldownDurationSeconds - elapsedSeconds
            if (remainingCooldown > 0) {
                return OtpRateLimitDecision.Blocked(
                    remainingSeconds = remainingCooldown,
                    reason = "Please wait ${remainingCooldown}s before requesting another OTP."
                )
            }
        }

        if (requestTimestamps.size >= maxRequestsPerWindow) {
            val oldest = requestTimestamps.minOrNull() ?: now
            val retryAfterSec = (((oldest + windowMillis) - now) / 1000L).toInt().coerceAtLeast(60)
            val mins = ((retryAfterSec + 59) / 60).coerceAtLeast(1)
            return OtpRateLimitDecision.Blocked(
                remainingSeconds = retryAfterSec,
                reason = "Too many OTP requests in a short period. Try again in about $mins minute(s)."
            )
        }

        val nextCooldown = when (requestTimestamps.size) {
            0 -> 60
            1 -> 60
            2 -> 90
            else -> 120
        }
        return OtpRateLimitDecision.Allowed(nextCooldownSeconds = nextCooldown)
    }

    @Synchronized
    fun recordOtpDispatched(cooldownSeconds: Int) {
        val now = clockMillis()
        requestTimestamps.add(now)
        lastSentAtMillis = now
        lastCooldownDurationSeconds = cooldownSeconds
    }

    @Synchronized
    fun resetCooldownForNumberChange() {
        // Number change still preserves the sliding-window abuse cap, while keeping resend guard intact
        lastSentAtMillis = 0L
    }
}

enum class PhoneAuthStep {
    INPUT_PHONE,
    VERIFY_OTP
}

data class PhoneAuthUiState(
    val step: PhoneAuthStep = PhoneAuthStep.INPUT_PHONE,
    val selectedCountry: CountryCodeOption = SupportedCountryCodes.INDIA,
    val phoneInput: String = "",
    val formattedE164: String = "",
    val otpInput: String = "",
    val verificationId: String? = null,
    val isSendingOtp: Boolean = false,
    val isVerifyingOtp: Boolean = false,
    val resendCooldownSeconds: Int = 0,
    val autoRetrievalTimedOut: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
) {
    val isBusy: Boolean get() = isSendingOtp || isVerifyingOtp
}

internal fun classifyPhoneAuthFailure(throwable: Throwable): Triple<String?, String, String> {
    val firebaseErrorCode = (throwable as? FirebaseAuthException)?.errorCode
        ?: (throwable.cause as? FirebaseAuthException)?.errorCode
    val exceptionType = throwable::class.java.simpleName
    val rawMessage = buildString {
        append(throwable.message.orEmpty())
        throwable.cause?.message?.let {
            append(' ')
            append(it)
        }
    }

    val userMessage = when {
        throwable is TimeoutCancellationException ->
            "Verification timed out. Check your internet connection and retry."
        throwable is FirebaseNetworkException ->
            "Network unavailable during OTP verification. Check your internet connection and retry."
        throwable is FirebaseTooManyRequestsException ||
            firebaseErrorCode == "ERROR_QUOTA_EXCEEDED" ||
            firebaseErrorCode == "ERROR_TOO_MANY_REQUESTS" ->
            "SMS OTP limit reached for this number or project. Please wait a while before requesting another code."
        throwable is FirebaseAuthMissingActivityForRecaptchaException ->
            "Verification requires an active screen for security checks. Return to the screen and retry."
        throwable is FirebaseAuthUserCollisionException ||
            firebaseErrorCode == "ERROR_CREDENTIAL_ALREADY_IN_USE" ||
            firebaseErrorCode == "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" ->
            "An account conflict occurred for this mobile number. Please sign in directly with this mobile number."
        firebaseErrorCode == "ERROR_INVALID_VERIFICATION_CODE" ->
            "Incorrect 6-digit OTP. Please double-check the SMS code and try again."
        firebaseErrorCode == "ERROR_SESSION_EXPIRED" ||
            firebaseErrorCode == "ERROR_CODE_EXPIRED" ->
            "This OTP has expired or was already used. Tap Resend OTP to receive a fresh 6-digit code."
        firebaseErrorCode == "ERROR_INVALID_PHONE_NUMBER" ||
            firebaseErrorCode == "ERROR_MISSING_PHONE_NUMBER" ->
            "Invalid mobile number format. Check your country code and 10-digit number."
        firebaseErrorCode == "ERROR_MISSING_VERIFICATION_CODE" ->
            "Please enter the 6-digit OTP."
        firebaseErrorCode == "ERROR_OPERATION_NOT_ALLOWED" &&
            (rawMessage.contains("17006") || rawMessage.contains("region", ignoreCase = true)) ->
            "SMS OTP for this country/region is not enabled yet in Firebase Authentication (Code 17006). Enable this region under Authentication -> Settings -> SMS region policy in Firebase Console, or sign in with Google."
        firebaseErrorCode == "ERROR_OPERATION_NOT_ALLOWED" ->
            "Mobile Number (Phone) sign-in is not enabled in Firebase Authentication. Enable Phone provider in Firebase Console, or sign in with Google."
        firebaseErrorCode == "ERROR_USER_DISABLED" ->
            "This GoLocal account has been disabled. Please contact support."
        firebaseErrorCode == "ERROR_REQUIRES_RECENT_LOGIN" ->
            "Please sign out and verify your mobile number again."
        firebaseErrorCode == "ERROR_APP_NOT_AUTHORIZED" ||
            firebaseErrorCode == "ERROR_INVALID_APP_CREDENTIAL" ||
            firebaseErrorCode == "ERROR_CAPTCHA_CHECK_FAILED" ||
            firebaseErrorCode == "ERROR_MISSING_CLIENT_IDENTIFIER" ||
            rawMessage.contains("certificate hash", ignoreCase = true) ->
            "App verification for Phone OTP failed. Verify APK SHA-1/SHA-256 fingerprints and Play Integrity / reCAPTCHA in Firebase Console."
        throwable is FirebaseAuthInvalidCredentialsException ->
            "Invalid OTP or mobile number verification failed. Please check the code or request a new OTP."
        throwable is FirebaseAuthInvalidUserException ->
            "This account is unavailable or disabled. Contact GoLocal support."
        throwable is FirebaseAuthException ->
            "Phone authentication failed (${throwable.errorCode}). Please try again."
        throwable is FirebaseException ->
            throwable.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "Firebase Phone Authentication failed. Please try again."
        else ->
            throwable.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "Mobile verification failed. Please try again."
    }

    return Triple(firebaseErrorCode, exceptionType, userMessage)
}

interface FirebasePhoneAuthGateway {
    fun isFirebaseInitialized(context: Context): Boolean
    fun verifyPhoneNumber(
        activity: Activity,
        e164Phone: String,
        resendToken: PhoneAuthProvider.ForceResendingToken?,
        callbacks: PhoneAuthProvider.OnVerificationStateChangedCallbacks
    )
    fun createCredential(verificationId: String, smsCode: String): PhoneAuthCredential
    suspend fun signInWithCredential(credential: PhoneAuthCredential): String?
    suspend fun linkPhoneCredential(credential: PhoneAuthCredential): String? =
        signInWithCredential(credential)
}

class DefaultFirebasePhoneAuthGateway(
    private val authProvider: () -> FirebaseAuth = { Firebase.auth }
) : FirebasePhoneAuthGateway {

    override fun isFirebaseInitialized(context: Context): Boolean =
        FirebaseApp.getApps(context).isNotEmpty()

    override fun verifyPhoneNumber(
        activity: Activity,
        e164Phone: String,
        resendToken: PhoneAuthProvider.ForceResendingToken?,
        callbacks: PhoneAuthProvider.OnVerificationStateChangedCallbacks
    ) {
        val builder = PhoneAuthOptions.newBuilder(authProvider())
            .setPhoneNumber(e164Phone)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
        if (resendToken != null) {
            builder.setForceResendingToken(resendToken)
        }
        PhoneAuthProvider.verifyPhoneNumber(builder.build())
    }

    override fun createCredential(verificationId: String, smsCode: String): PhoneAuthCredential =
        PhoneAuthProvider.getCredential(verificationId, smsCode)

    override suspend fun signInWithCredential(credential: PhoneAuthCredential): String? {
        val result = authProvider().signInWithCredential(credential).await()
        return result.user?.phoneNumber ?: result.user?.uid
    }

    override suspend fun linkPhoneCredential(credential: PhoneAuthCredential): String? {
        val user = authProvider().currentUser
            ?: throw IllegalStateException("No authenticated account found to link this mobile number.")
        val result = user.linkWithCredential(credential).await()
        return result.user?.phoneNumber ?: user.phoneNumber
    }
}

class PhoneAuthController(
    private val scope: CoroutineScope,
    private val gateway: FirebasePhoneAuthGateway = DefaultFirebasePhoneAuthGateway(),
    private val rateLimiter: OtpRateLimiter = OtpRateLimiter(),
    private val linkToExistingUser: Boolean = false,
    private val onSuccess: (verifiedPhoneE164: String) -> Unit
) {
    private val _uiState = MutableStateFlow(PhoneAuthUiState())
    val uiState: StateFlow<PhoneAuthUiState> = _uiState.asStateFlow()

    private var forceResendingToken: PhoneAuthProvider.ForceResendingToken? = null
    private var countdownJob: Job? = null
    private var activeVerificationUsed: Boolean = false
    private var pendingCooldownSeconds: Int = 60

    fun selectCountry(country: CountryCodeOption) {
        _uiState.update {
            it.copy(
                selectedCountry = country,
                errorMessage = null
            )
        }
    }

    fun updatePhoneInput(raw: String) {
        val filtered = raw.filter { it.isDigit() || it == '+' || it == ' ' || it == '-' }.take(18)
        _uiState.update {
            it.copy(
                phoneInput = filtered,
                errorMessage = null
            )
        }
    }

    fun updateOtpInput(raw: String) {
        val digitsOnly = raw.filter { it.isDigit() }.take(6)
        _uiState.update {
            it.copy(
                otpInput = digitsOnly,
                errorMessage = null
            )
        }
    }

    fun changeMobileNumber() {
        countdownJob?.cancel()
        countdownJob = null
        forceResendingToken = null
        activeVerificationUsed = false
        rateLimiter.resetCooldownForNumberChange()
        AuthFlowCoordinator.releaseInteractive()
        _uiState.update {
            it.copy(
                step = PhoneAuthStep.INPUT_PHONE,
                otpInput = "",
                verificationId = null,
                isSendingOtp = false,
                isVerifyingOtp = false,
                resendCooldownSeconds = 0,
                autoRetrievalTimedOut = false,
                errorMessage = null,
                infoMessage = null
            )
        }
    }

    fun sendOtp(context: Context, isResend: Boolean = false) {
        val current = _uiState.value
        if (current.isBusy) return

        val validationTarget = if (isResend && current.formattedE164.isNotBlank()) {
            PhoneValidationResult.Valid(current.formattedE164, current.phoneInput)
        } else {
            PhoneNumberValidator.validateAndFormat(current.selectedCountry, current.phoneInput)
        }

        val valid = when (validationTarget) {
            is PhoneValidationResult.Invalid -> {
                _uiState.update { it.copy(errorMessage = validationTarget.message) }
                return
            }
            is PhoneValidationResult.Valid -> validationTarget
        }

        when (val limit = rateLimiter.canRequestOtp()) {
            is OtpRateLimitDecision.Blocked -> {
                _uiState.update {
                    it.copy(
                        errorMessage = limit.reason,
                        resendCooldownSeconds = maxOf(it.resendCooldownSeconds, limit.remainingSeconds)
                    )
                }
                return
            }
            is OtpRateLimitDecision.Allowed -> {
                pendingCooldownSeconds = limit.nextCooldownSeconds
            }
        }

        if (!gateway.isFirebaseInitialized(context)) {
            _uiState.update {
                it.copy(errorMessage = "Firebase is not initialized. Check google-services.json and restart the app.")
            }
            return
        }

        val activity = context.findActivity()
        if (activity == null) {
            _uiState.update {
                it.copy(errorMessage = "Mobile verification requires an active screen context.")
            }
            return
        }

        if (!AuthFlowCoordinator.tryAcquireInteractive()) {
            _uiState.update {
                it.copy(errorMessage = "Another sign-in request is already in progress. Please wait.")
            }
            return
        }

        val flowName = "phone_sign_in"
        AuthDiagnostics.logStage(
            stage = AuthStage.PHONE_OTP_REQUESTED,
            flow = flowName,
            detail = "target=${AuthDiagnostics.maskPhoneNumber(valid.e164Phone)} resend=$isResend"
        )

        _uiState.update {
            it.copy(
                formattedE164 = valid.e164Phone,
                isSendingOtp = true,
                errorMessage = null,
                infoMessage = if (isResend) "Requesting a new 6-digit OTP..." else "Sending 6-digit OTP...",
                autoRetrievalTimedOut = false
            )
        }

        val callbacks = createVerificationCallbacks(e164Phone = valid.e164Phone, flowName = flowName)
        try {
            gateway.verifyPhoneNumber(
                activity = activity,
                e164Phone = valid.e164Phone,
                resendToken = if (isResend) forceResendingToken else null,
                callbacks = callbacks
            )
        } catch (e: Exception) {
            AuthFlowCoordinator.releaseInteractive()
            val (code, excType, userMessage) = classifyPhoneAuthFailure(e)
            AuthDiagnostics.logFailure(
                stage = AuthStage.AUTH_FAILED,
                flow = flowName,
                exceptionType = excType,
                firebaseErrorCode = code,
                detail = "verifyPhoneNumber threw synchronously"
            )
            _uiState.update {
                it.copy(
                    isSendingOtp = false,
                    infoMessage = null,
                    errorMessage = userMessage
                )
            }
        }
    }

    fun verifyEnteredOtp() {
        val current = _uiState.value
        if (current.isBusy) return

        val verificationId = current.verificationId
        if (verificationId.isNullOrBlank()) {
            _uiState.update {
                it.copy(errorMessage = "OTP session missing or expired. Tap Resend OTP to request a new code.")
            }
            return
        }

        if (activeVerificationUsed) {
            _uiState.update {
                it.copy(errorMessage = "This OTP has already been submitted or expired. Tap Resend OTP for a new code.")
            }
            return
        }

        val code = current.otpInput.trim()
        if (!PhoneNumberValidator.isValidOtp(code)) {
            _uiState.update {
                it.copy(errorMessage = "Enter the complete 6-digit OTP sent to ${AuthDiagnostics.maskPhoneNumber(current.formattedE164)}.")
            }
            return
        }

        if (!AuthFlowCoordinator.tryAcquireInteractive()) {
            _uiState.update {
                it.copy(errorMessage = "Verification is already in progress. Please wait.")
            }
            return
        }

        val flowName = "phone_sign_in"
        val credential = try {
            gateway.createCredential(verificationId, code)
        } catch (e: Exception) {
            AuthFlowCoordinator.releaseInteractive()
            val (_, _, msg) = classifyPhoneAuthFailure(e)
            _uiState.update { it.copy(errorMessage = msg) }
            return
        }

        submitCredential(
            credential = credential,
            e164Phone = current.formattedE164,
            flowName = flowName,
            markVerificationUsed = true
        )
    }

    fun dispose() {
        countdownJob?.cancel()
        countdownJob = null
    }

    internal fun createVerificationCallbacks(
        e164Phone: String,
        flowName: String
    ): PhoneAuthProvider.OnVerificationStateChangedCallbacks {
        return object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                if (activeVerificationUsed || _uiState.value.isVerifyingOtp) {
                    return
                }
                AuthFlowCoordinator.releaseInteractive()
                AuthDiagnostics.logStage(
                    stage = AuthStage.PHONE_OTP_VERIFIED,
                    flow = flowName,
                    detail = "Auto-verification credential received"
                )
                if (!AuthFlowCoordinator.tryAcquireInteractive()) {
                    return
                }
                submitCredential(
                    credential = credential,
                    e164Phone = e164Phone,
                    flowName = flowName,
                    markVerificationUsed = true
                )
            }

            override fun onVerificationFailed(e: FirebaseException) {
                AuthFlowCoordinator.releaseInteractive()
                val (code, excType, userMessage) = classifyPhoneAuthFailure(e)
                AuthDiagnostics.logFailure(
                    stage = AuthStage.AUTH_FAILED,
                    flow = flowName,
                    exceptionType = excType,
                    firebaseErrorCode = code,
                    detail = "Phone OTP verification failed"
                )
                _uiState.update {
                    it.copy(
                        isSendingOtp = false,
                        isVerifyingOtp = false,
                        infoMessage = null,
                        errorMessage = userMessage
                    )
                }
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                AuthFlowCoordinator.releaseInteractive()
                forceResendingToken = token
                activeVerificationUsed = false
                rateLimiter.recordOtpDispatched(pendingCooldownSeconds)
                AuthDiagnostics.logStage(
                    stage = AuthStage.PHONE_OTP_SENT,
                    flow = flowName,
                    detail = "OTP dispatched to ${AuthDiagnostics.maskPhoneNumber(e164Phone)}"
                )
                _uiState.update {
                    it.copy(
                        step = PhoneAuthStep.VERIFY_OTP,
                        formattedE164 = e164Phone,
                        verificationId = verificationId,
                        otpInput = "",
                        isSendingOtp = false,
                        isVerifyingOtp = false,
                        autoRetrievalTimedOut = false,
                        errorMessage = null,
                        infoMessage = "6-digit OTP sent to $e164Phone"
                    )
                }
                startResendCountdown(pendingCooldownSeconds)
            }

            override fun onCodeAutoRetrievalTimeOut(verificationId: String) {
                AuthDiagnostics.logWarning(
                    stage = AuthStage.PHONE_OTP_SENT,
                    flow = flowName,
                    exceptionType = "AutoRetrievalTimeout",
                    detail = "SMS auto-retrieval timed out; manual 6-digit OTP remains active"
                )
                _uiState.update {
                    it.copy(
                        verificationId = it.verificationId ?: verificationId,
                        autoRetrievalTimedOut = true
                    )
                }
            }
        }
    }

    private fun submitCredential(
        credential: PhoneAuthCredential,
        e164Phone: String,
        flowName: String,
        markVerificationUsed: Boolean
    ) {
        AuthDiagnostics.logStage(
            stage = AuthStage.PHONE_OTP_VERIFYING,
            flow = flowName,
            detail = "Exchanging credential for ${AuthDiagnostics.maskPhoneNumber(e164Phone)}"
        )

        _uiState.update {
            it.copy(
                isSendingOtp = false,
                isVerifyingOtp = true,
                errorMessage = null,
                infoMessage = "Verifying OTP..."
            )
        }

        scope.launch {
            try {
                val resolvedPhone = withTimeout(FIREBASE_SIGN_IN_TIMEOUT_MS) {
                    if (linkToExistingUser) {
                        gateway.linkPhoneCredential(credential)
                    } else {
                        gateway.signInWithCredential(credential)
                    }
                }
                if (resolvedPhone != null) {
                    if (markVerificationUsed) {
                        activeVerificationUsed = true
                    }
                    countdownJob?.cancel()
                    AuthDiagnostics.logStage(
                        stage = if (linkToExistingUser) AuthStage.ACCOUNT_LINK_SUCCESS else AuthStage.FIREBASE_SIGN_IN_SUCCESS,
                        flow = flowName,
                        detail = if (linkToExistingUser) "Phone linked to account" else "Phone authentication completed"
                    )
                    _uiState.update {
                        it.copy(
                            isVerifyingOtp = false,
                            errorMessage = null,
                            infoMessage = null
                        )
                    }
                    val verifiedE164 = if (resolvedPhone.startsWith("+")) resolvedPhone else e164Phone
                    onSuccess(verifiedE164)
                } else {
                    AuthDiagnostics.logFailure(
                        stage = AuthStage.AUTH_FAILED,
                        flow = flowName,
                        exceptionType = "NullFirebaseUser",
                        detail = "Firebase returned null user after phone OTP"
                    )
                    _uiState.update {
                        it.copy(
                            isVerifyingOtp = false,
                            infoMessage = null,
                            errorMessage = "Authentication did not complete. Please retry."
                        )
                    }
                }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) {
                    val (code, excType, msg) = classifyPhoneAuthFailure(e)
                    AuthDiagnostics.logFailure(
                        stage = AuthStage.AUTH_FAILED,
                        flow = flowName,
                        exceptionType = excType,
                        firebaseErrorCode = code,
                        detail = "Timed out verifying phone credential"
                    )
                    _uiState.update {
                        it.copy(
                            isVerifyingOtp = false,
                            infoMessage = null,
                            errorMessage = msg
                        )
                    }
                } else {
                    throw e
                }
            } catch (e: Exception) {
                val (code, excType, userMessage) = classifyPhoneAuthFailure(e)
                // If Firebase rejected an already-used/expired session, invalidate local token so user triggers Resend OTP
                if (code == "ERROR_SESSION_EXPIRED" || code == "ERROR_CODE_EXPIRED") {
                    activeVerificationUsed = true
                }
                AuthDiagnostics.logFailure(
                    stage = AuthStage.AUTH_FAILED,
                    flow = flowName,
                    exceptionType = excType,
                    firebaseErrorCode = code,
                    detail = "Credential exchange failed"
                )
                _uiState.update {
                    it.copy(
                        isVerifyingOtp = false,
                        infoMessage = null,
                        errorMessage = userMessage
                    )
                }
            } finally {
                AuthFlowCoordinator.releaseInteractive()
            }
        }
    }

    private fun startResendCountdown(seconds: Int) {
        countdownJob?.cancel()
        _uiState.update { it.copy(resendCooldownSeconds = seconds.coerceAtLeast(0)) }
        if (seconds <= 0) return

        countdownJob = scope.launch {
            var remaining = seconds
            while (remaining > 0) {
                delay(1000L)
                remaining -= 1
                _uiState.update { it.copy(resendCooldownSeconds = remaining.coerceAtLeast(0)) }
            }
        }
    }
}
