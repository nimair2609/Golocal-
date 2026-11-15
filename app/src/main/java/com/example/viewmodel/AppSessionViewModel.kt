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
import com.example.data.model.UiState
import com.example.data.model.UserAccount
import com.example.data.repository.ClientPreferences
import com.example.data.repository.GolocalRepository
import com.example.data.repository.PreferencesRepository
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
    private val userPhotoUrl: String
) : ViewModel() {

    private val _selectedRole = MutableStateFlow(AppRole.CUSTOMER)
    val selectedRole: StateFlow<AppRole> = _selectedRole.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _isAdminChecked = MutableStateFlow(false)
    val isAdminChecked: StateFlow<Boolean> = _isAdminChecked.asStateFlow()

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
        viewModelScope.launch {
            repository.ensureUserAccount(userDisplayName, userEmail, userPhotoUrl)
                .onFailure { _statusMessage.value = it.localizedMessage }
            _isAdmin.value = repository.checkIsAdmin(currentUserId, userEmail)
            _isAdminChecked.value = true
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
            repository.updateUserPreferences(language = language.code)
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
            repository.updateUserPreferences(lowNetworkMode = enabled)
        }
    }

    fun updateAccountAndProfile(
        fullName: String,
        phone: String,
        homeAddress: String,
        workAddress: String,
        nativeVillage: String,
        nativeDistrict: String,
        emergencyName: String,
        emergencyPhone: String
    ) {
        viewModelScope.launch {
            val existingProfile = customerProfileState.value
            val r1 = repository.updateUserPreferences(fullName = fullName, phone = phone)
            val r2 = repository.saveCustomerProfile(
                CustomerProfile(
                    userId = currentUserId,
                    homeAddress = homeAddress,
                    workAddress = workAddress,
                    nativeVillage = nativeVillage,
                    nativeDistrict = nativeDistrict,
                    emergencyContactName = emergencyName,
                    emergencyContactPhone = emergencyPhone,
                    createdAt = existingProfile?.createdAt
                )
            )
            _statusMessage.value = if (r1.isSuccess && r2.isSuccess) {
                "Profile & emergency contacts saved."
            } else {
                r1.exceptionOrNull()?.localizedMessage ?: r2.exceptionOrNull()?.localizedMessage ?: "Failed to save profile"
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
    }
}
