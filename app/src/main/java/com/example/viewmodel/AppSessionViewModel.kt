package com.example.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AppLanguage
import com.example.data.model.AppNotification
import com.example.data.model.AppRole
import com.example.data.model.CustomerProfile
import com.example.data.model.PrivateEmergencyContact
import com.example.data.model.RegistrationStatus
import com.example.data.model.UiState
import com.example.data.model.UserAccount
import com.example.data.repository.ClientPreferences
import com.example.data.repository.GolocalRepository
import com.example.data.repository.PreferencesRepository
import com.example.ui.auth.AuthDiagnostics
import com.example.ui.auth.AuthStage
import com.example.ui.auth.RegistrationValidator
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class GpsLocationState(
    val lat: Double? = null,
    val lng: Double? = null,
    val label: String = "",
    val isDetecting: Boolean = false,
    val error: String? = null
)

class AppSessionViewModel(
    private val repository: GolocalRepository,
    private val prefsRepository: PreferencesRepository,
    private val currentUserId: String,
    private val userDisplayName: String,
    private val userEmail: String,
    private val userPhotoUrl: String,
    private val userPhoneNumber: String = ""
) : ViewModel() {

    private val _selectedRole = MutableStateFlow(AppRole.CUSTOMER)
    val selectedRole: StateFlow<AppRole> = _selectedRole.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _isAdminChecked = MutableStateFlow(false)
    val isAdminChecked: StateFlow<Boolean> = _isAdminChecked.asStateFlow()

    private val _isAccountReady = MutableStateFlow(false)
    val isAccountReady: StateFlow<Boolean> = _isAccountReady.asStateFlow()

    private val _registrationStatus = MutableStateFlow<RegistrationStatus>(RegistrationStatus.Checking)
    val registrationStatus: StateFlow<RegistrationStatus> = _registrationStatus.asStateFlow()

    private val _isSubmittingRegistration = MutableStateFlow(false)
    val isSubmittingRegistration: StateFlow<Boolean> = _isSubmittingRegistration.asStateFlow()

    private val _isDeletingAccount = MutableStateFlow(false)
    val isDeletingAccount: StateFlow<Boolean> = _isDeletingAccount.asStateFlow()

    private val _registrationError = MutableStateFlow<String?>(null)
    val registrationError: StateFlow<String?> = _registrationError.asStateFlow()

    private val _gpsLocation = MutableStateFlow(GpsLocationState())
    val gpsLocation: StateFlow<GpsLocationState> = _gpsLocation.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    val clientPreferences: StateFlow<ClientPreferences> = prefsRepository.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = ClientPreferences()
        )

    val platformSettings: StateFlow<com.example.data.model.PlatformSettings?> = repository.observePlatformSettings()
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Failed to observe platform settings", error)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val userAccountState: StateFlow<UiState<UserAccount?>> = repository.observeUserAccount(currentUserId)
        .map<UserAccount?, UiState<UserAccount?>> { UiState.Success(it) }
        .catch { error ->
            val fe = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (fe == null && error is CancellationException) throw error
            val root = fe ?: error
            Log.w(TAG, "Failed to observe user account", root)
            emit(UiState.Error(root.message ?: "Failed to load account"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    val customerProfileState: StateFlow<CustomerProfile?> = repository.observeCustomerProfile(currentUserId)
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Failed to observe customer profile", error)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val privateEmergencyContactState: StateFlow<PrivateEmergencyContact?> =
        repository.observeMyEmergencyContact(currentUserId)
            .catch { error ->
                if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
                Log.w(TAG, "Failed to observe private emergency contact", error)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = null
            )

    val notificationsState: StateFlow<List<AppNotification>> = repository.observeMyNotifications(currentUserId)
        .map { list -> list.sortedByDescending { it.createdAt?.seconds ?: 0L } }
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Failed to observe notifications", error)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    init {
        syncAccountAndRole()
    }

    fun retryAccountSync() {
        _registrationStatus.value = RegistrationStatus.Checking
        _statusMessage.value = null
        syncAccountAndRole()
    }

    private fun syncAccountAndRole() {
        viewModelScope.launch {
            try {
                val synced = withTimeoutOrNull(ACCOUNT_SYNC_TIMEOUT_MS) {
                    repository.checkRegistrationStatus(currentUserId, userPhoneNumber)
                        .onSuccess { status ->
                            _registrationStatus.value = status
                            when (status) {
                                is RegistrationStatus.Completed -> {
                                    _isAccountReady.value = true
                                    AuthDiagnostics.logStage(
                                        stage = AuthStage.AUTH_FLOW_COMPLETED,
                                        flow = "session_init",
                                        detail = "Returning user profile & session ready"
                                    )
                                }
                                is RegistrationStatus.PendingRegistration -> {
                                    _isAccountReady.value = false
                                    _registrationStatus.value = status.copy(
                                        prefilledName = status.prefilledName.ifBlank { userDisplayName },
                                        prefilledEmail = status.prefilledEmail.ifBlank { userEmail }
                                    )
                                    AuthDiagnostics.logStage(
                                        stage = AuthStage.REGISTRATION_REQUIRED,
                                        flow = "session_init",
                                        detail = "New or incomplete user requires registration"
                                    )
                                }
                                RegistrationStatus.Checking -> Unit
                            }
                        }
                        .onFailure { err ->
                            val fe = err as? FirebaseFirestoreException
                            _statusMessage.value = when (fe?.code) {
                                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                                    "Account permission check failed. Please sign out and sign in again."
                                FirebaseFirestoreException.Code.UNAVAILABLE,
                                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                                    "Network issue checking your GoLocal profile. Tap Retry when online."
                                else ->
                                    err.localizedMessage ?: "Unable to verify your GoLocal account right now."
                            }
                        }
                    _isAdmin.value = repository.checkIsAdmin(currentUserId, userEmail)
                    true
                }
                if (synced == null) {
                    AuthDiagnostics.logWarning(
                        stage = AuthStage.AUTH_FAILED,
                        flow = "session_init",
                        exceptionType = "AccountSyncTimeout",
                        detail = "Timed out awaiting initial Firestore account & admin check"
                    )
                    _statusMessage.value = "Account sync timed out. Please check your connection and retry."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error in syncAccountAndRole", e)
                _statusMessage.value = e.localizedMessage ?: "Account initialization failed."
            } finally {
                _isAdminChecked.value = true
            }
        }
    }

    fun completeRegistration(
        fullName: String,
        invoiceEmail: String,
        emergencyContactE164: String,
        termsAccepted: Boolean,
        enteredMobileE164: String = ""
    ) {
        if (_isSubmittingRegistration.value) return
        val currentStatus = _registrationStatus.value as? RegistrationStatus.PendingRegistration
        val verifiedPhone = currentStatus?.verifiedMobileNumber?.ifBlank { userPhoneNumber }
            ?.ifBlank { enteredMobileE164 }
            ?: enteredMobileE164.ifBlank { userPhoneNumber }

        _isSubmittingRegistration.value = true
        _registrationError.value = null
        viewModelScope.launch {
            repository.completeMobileRegistration(
                fullName = fullName,
                verifiedMobileNumber = verifiedPhone,
                invoiceEmail = invoiceEmail,
                emergencyContactNumber = emergencyContactE164,
                privacyConsentAccepted = termsAccepted,
                role = _selectedRole.value.wire,
                preferredLanguage = clientPreferences.value.language.code,
                isMobileOtpVerified = currentStatus?.isPhoneAuthVerified ?: userPhoneNumber.isNotBlank()
            )
                .onSuccess { account ->
                    _registrationStatus.value = RegistrationStatus.Completed(account)
                    _isAccountReady.value = true
                    _isSubmittingRegistration.value = false
                    AuthDiagnostics.logStage(
                        stage = AuthStage.REGISTRATION_COMPLETED,
                        flow = "mobile_registration",
                        detail = "Completed profile registration for ${account.userId}"
                    )
                    _statusMessage.value = "Welcome to GoLocal, ${account.fullName}!"
                }
                .onFailure { err ->
                    _isSubmittingRegistration.value = false
                    _registrationError.value = err.localizedMessage ?: "Could not save registration details. Please retry."
                }
        }
    }

    fun onAccountCredentialLinked(linkedPhoneE164: String? = null) {
        viewModelScope.launch {
            if (!linkedPhoneE164.isNullOrBlank()) {
                repository.syncVerifiedPhoneOnAccount(linkedPhoneE164)
            } else {
                repository.syncLinkedAuthProviders()
            }
                .onSuccess {
                    _statusMessage.value = "Account security credentials linked cleanly."
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Account link saved in Auth; Firestore sync will retry."
                }
        }
    }

    fun deleteMyAccountNow(context: Context) {
        if (_isDeletingAccount.value) return
        _isDeletingAccount.value = true
        viewModelScope.launch {
            repository.deleteMyAccountAndData()
                .onSuccess {
                    _isDeletingAccount.value = false
                    com.example.ui.auth.signOut(viewModelScope, context = context)
                }
                .onFailure { err ->
                    _isDeletingAccount.value = false
                    val isRecentLogin = err is com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException ||
                        err.message?.contains("REQUIRES_RECENT_LOGIN", ignoreCase = true) == true
                    _statusMessage.value = if (isRecentLogin) {
                        "Security check: Sign out and sign back in right before deleting your account."
                    } else {
                        err.localizedMessage ?: "Could not delete account right now. Please try again."
                    }
                }
        }
    }

    fun selectRole(role: AppRole) {
        _selectedRole.value = role
    }

    fun setCustomerOnboarded(done: Boolean) {
        viewModelScope.launch { prefsRepository.setCustomerOnboarded(done) }
    }

    fun setRiderOnboarded(done: Boolean) {
        viewModelScope.launch { prefsRepository.setRiderOnboarded(done) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            prefsRepository.setLanguage(language)
            if (_isAccountReady.value) {
                repository.updateUserPreferences(language = language.code)
            }
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            prefsRepository.setThemeMode(mode)
        }
    }

    fun setLowNetworkMode(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepository.setLowNetworkMode(enabled)
            if (_isAccountReady.value) {
                repository.updateUserPreferences(lowNetworkMode = enabled)
            }
        }
    }

    fun updateAccountAndProfile(
        fullName: String,
        invoiceEmail: String,
        homeAddress: String,
        workAddress: String,
        nativeVillage: String,
        nativeDistrict: String
    ) {
        val nameErr = RegistrationValidator.validateFullName(fullName)
        if (nameErr != null) {
            _statusMessage.value = nameErr
            return
        }
        val emailErr = RegistrationValidator.validateInvoiceEmail(invoiceEmail)
        if (emailErr != null) {
            _statusMessage.value = emailErr
            return
        }

        viewModelScope.launch {
            val existingProfile = customerProfileState.value
            val r1 = repository.updateUserPreferences(
                fullName = fullName.trim(),
                email = invoiceEmail.trim()
            )
            val r2 = repository.saveCustomerProfile(
                CustomerProfile(
                    userId = currentUserId,
                    homeAddress = homeAddress,
                    workAddress = workAddress,
                    nativeVillage = nativeVillage,
                    nativeDistrict = nativeDistrict,
                    createdAt = existingProfile?.createdAt
                )
            )
            _statusMessage.value = if (r1.isSuccess && r2.isSuccess) {
                "Profile & GST invoice email updated."
            } else {
                r1.exceptionOrNull()?.localizedMessage
                    ?: r2.exceptionOrNull()?.localizedMessage
                    ?: "Failed to save profile"
            }
        }
    }

    fun updatePrivateEmergencyContact(
        emergencyContactE164: String,
        relationshipNote: String = "Primary Emergency Contact"
    ) {
        viewModelScope.launch {
            repository.updatePrivateEmergencyContact(emergencyContactE164, relationshipNote)
                .onSuccess {
                    _statusMessage.value = "Private emergency contact updated securely."
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Failed to update private emergency contact."
                }
        }
    }

    fun submitSupportOrDeletion(requestType: String, details: String) {
        if (details.isBlank()) {
            _statusMessage.value = "Please enter details before submitting."
            return
        }
        viewModelScope.launch {
            repository.submitSupportRequest(requestType, details)
                .onSuccess { _statusMessage.value = "Request #$it submitted to GOLOCAL Support." }
                .onFailure { _statusMessage.value = it.localizedMessage ?: "Submission failed" }
        }
    }

    fun markNotificationRead(notificationId: String) {
        viewModelScope.launch {
            repository.markNotificationRead(notificationId)
        }
    }

    @SuppressLint("MissingPermission")
    fun detectDeviceLocation(context: Context) {
        _gpsLocation.value = _gpsLocation.value.copy(isDetecting = true, error = null)
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        _gpsLocation.value = GpsLocationState(
                            lat = location.latitude,
                            lng = location.longitude,
                            label = "GPS (${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)})",
                            isDetecting = false
                        )
                    } else {
                        _gpsLocation.value = _gpsLocation.value.copy(
                            isDetecting = false,
                            error = "GPS fix unavailable — please pick your station or locality manually."
                        )
                    }
                }
                .addOnFailureListener { exc ->
                    _gpsLocation.value = _gpsLocation.value.copy(
                        isDetecting = false,
                        error = exc.localizedMessage ?: "Location unavailable"
                    )
                }
        } catch (e: Exception) {
            _gpsLocation.value = _gpsLocation.value.copy(
                isDetecting = false,
                error = e.localizedMessage ?: "Location permission required"
            )
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    private companion object {
        const val TAG = "AppSessionVM"
        const val STOP_TIMEOUT_MS = 5000L
        const val ACCOUNT_SYNC_TIMEOUT_MS = 15_000L
    }
}
