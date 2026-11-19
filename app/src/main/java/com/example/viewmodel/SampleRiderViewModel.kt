package com.example.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AppNotification
import com.example.data.model.SampleRide
import com.example.data.model.SampleRiderProfile
import com.example.data.repository.GolocalRepository
import com.example.data.repository.SampleAcceptOutcome
import com.example.data.repository.SampleRiderRepository
import com.example.shared.DeviceGpsFix
import com.example.shared.LocationAccessState
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class SampleRiderViewModel(
    private val sampleRepository: SampleRiderRepository,
    private val golocalRepository: GolocalRepository,
    private val currentUserId: String
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _otpError = MutableStateFlow<String?>(null)
    val otpError: StateFlow<String?> = _otpError.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _gpsFix = MutableStateFlow<DeviceGpsFix?>(null)
    val gpsFix: StateFlow<DeviceGpsFix?> = _gpsFix.asStateFlow()

    private val _locationAccessState = MutableStateFlow(LocationAccessState.PERMISSION_REQUIRED)
    val locationAccessState: StateFlow<LocationAccessState> = _locationAccessState.asStateFlow()

    private val _lastCompletedRide = MutableStateFlow<SampleRide?>(null)
    val lastCompletedRide: StateFlow<SampleRide?> = _lastCompletedRide.asStateFlow()

    val profile: StateFlow<SampleRiderProfile?> = sampleRepository.observeMySampleRiderProfile(currentUserId)
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Error observing sample rider profile", error)
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val rawIncomingRides = sampleRepository.observeIncomingSampleRides()
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            emit(emptyList())
        }

    private val dismissedRideIds = sampleRepository.observeDismissedRideIds(currentUserId)
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            emit(emptySet())
        }

    val incomingRides: StateFlow<List<SampleRide>> = combine(rawIncomingRides, dismissedRideIds) { list, dismissed ->
        list.filter { it.rideId !in dismissed }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val assignedRides: StateFlow<List<SampleRide>> = sampleRepository.observeAssignedSampleRides(currentUserId)
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Error observing assigned sample rides", error)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val activeRide: StateFlow<SampleRide?> = assignedRides
        .map { list ->
            list.firstOrNull { it.status in setOf("accepted", "arriving", "arrived", "in_progress") }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val completedRides: StateFlow<List<SampleRide>> = assignedRides
        .map { list -> list.filter { it.status == "completed" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val rideHistory: StateFlow<List<SampleRide>> = assignedRides
        .map { list -> list.filter { it.status in setOf("completed", "cancelled", "expired") } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val todayRidesCount: StateFlow<Int> = completedRides
        .map { list ->
            val startOfDayMs = startOfTodayEpochMs()
            list.count { ride ->
                val tsMs = ride.completedAt?.toDate()?.time
                    ?: ride.createdAt?.toDate()?.time
                    ?: 0L
                tsMs >= startOfDayMs
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    val totalSampleEarningsInr: StateFlow<Double> = completedRides
        .map { list -> list.sumOf { it.estimatedFareInr } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0.0)

    val notifications: StateFlow<List<AppNotification>> = golocalRepository.observeMyNotifications(currentUserId)
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun updateLocationAccessState(state: LocationAccessState) {
        _locationAccessState.value = state
    }

    fun updateGpsFix(fix: DeviceGpsFix?) {
        _gpsFix.value = fix
    }

    fun broadcastGpsSample(fix: DeviceGpsFix) {
        _gpsFix.value = fix
        val currentProfile = profile.value ?: return
        if (currentProfile.isSuspendedOrRevoked()) return
        val currentActive = activeRide.value
        viewModelScope.launch {
            sampleRepository.updateSampleRiderLocation(
                uid = currentUserId,
                lat = fix.lat,
                lng = fix.lng,
                activeRideId = currentActive?.rideId
            )
        }
    }

    fun toggleOnline(online: Boolean) {
        val current = profile.value
        if (current == null || current.isSuspendedOrRevoked()) {
            _message.value = "Sample Rider access is suspended or revoked."
            return
        }
        val fix = _gpsFix.value
        if (online && (_locationAccessState.value != LocationAccessState.GRANTED_READY || fix == null)) {
            _message.value = "GPS location unavailable. Enable GPS and grant Location permission to go online."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            sampleRepository.setSampleRiderAvailability(
                uid = currentUserId,
                online = online,
                lat = fix?.lat,
                lng = fix?.lng
            ).onSuccess {
                _message.value = if (online) {
                    "SAMPLE RIDER is now ONLINE and listening for sample requests."
                } else {
                    "SAMPLE RIDER is now OFFLINE."
                }
            }.onFailure { err ->
                _message.value = err.localizedMessage ?: "Failed updating availability status."
            }
            _isBusy.value = false
        }
    }

    fun acceptIncomingRide(ride: SampleRide) {
        val rider = profile.value ?: return
        if (rider.isSuspendedOrRevoked()) {
            _message.value = "Sample Rider access is not active."
            return
        }
        if (activeRide.value != null) {
            _message.value = "Finish your active sample ride before accepting another."
            return
        }
        val fix = _gpsFix.value
        viewModelScope.launch {
            _isBusy.value = true
            when (val outcome = sampleRepository.acceptSampleRideAtomically(ride.rideId, rider, fix?.lat, fix?.lng)) {
                is SampleAcceptOutcome.Accepted -> {
                    _message.value = "Sample ride accepted! Navigate to pickup."
                }
                is SampleAcceptOutcome.AlreadyTaken -> {
                    _message.value = "Request already accepted by another Sample Rider."
                }
                is SampleAcceptOutcome.Expired -> {
                    _message.value = "Request expired before acceptance."
                }
                is SampleAcceptOutcome.Failed -> {
                    _message.value = outcome.message
                }
            }
            _isBusy.value = false
        }
    }

    fun rejectIncomingRide(ride: SampleRide) {
        viewModelScope.launch {
            sampleRepository.dismissSampleRide(currentUserId, ride.rideId)
                .onSuccess { _message.value = "Sample request dismissed." }
                .onFailure { _message.value = it.localizedMessage ?: "Could not dismiss request." }
        }
    }

    fun markArriving() {
        val ride = activeRide.value ?: return
        val fix = _gpsFix.value
        viewModelScope.launch {
            _isBusy.value = true
            sampleRepository.advanceSampleRideStatus(ride.rideId, "arriving", fix?.lat, fix?.lng)
                .onSuccess { _message.value = "Status updated: Heading to Pickup." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed updating status." }
            _isBusy.value = false
        }
    }

    fun markArrivedAtPickup() {
        val ride = activeRide.value ?: return
        val fix = _gpsFix.value
        viewModelScope.launch {
            _isBusy.value = true
            sampleRepository.advanceSampleRideStatus(ride.rideId, "arrived", fix?.lat, fix?.lng)
                .onSuccess { _message.value = "Marked arrived! Ask the customer for the 6-digit OTP." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed marking arrival." }
            _isBusy.value = false
        }
    }

    fun verifyOtpAndStartTrip(enteredOtp: String) {
        val ride = activeRide.value ?: return
        val fix = _gpsFix.value
        _otpError.value = null
        viewModelScope.launch {
            _isBusy.value = true
            sampleRepository.verifySampleRideOtpAndStartTrip(
                rideId = ride.rideId,
                enteredOtp = enteredOtp,
                lat = fix?.lat,
                lng = fix?.lng
            ).onSuccess {
                _otpError.value = null
                _message.value = "OTP verified! Navigate to destination and complete trip."
            }.onFailure { err ->
                _otpError.value = err.localizedMessage ?: "OTP verification failed."
            }
            _isBusy.value = false
        }
    }

    fun completeActiveRide(onCompleteStopLocation: (() -> Unit)? = null) {
        val ride = activeRide.value ?: return
        val fix = _gpsFix.value
        viewModelScope.launch {
            _isBusy.value = true
            sampleRepository.advanceSampleRideStatus(ride.rideId, "completed", fix?.lat, fix?.lng)
                .onSuccess {
                    _lastCompletedRide.value = ride.copy(status = "completed")
                    _message.value = "Sample ride completed! Simulated fare ₹${ride.estimatedFareInr.toInt()} recorded."
                    onCompleteStopLocation?.invoke()
                }
                .onFailure { err ->
                    _message.value = err.localizedMessage ?: "Failed completing ride."
                }
            _isBusy.value = false
        }
    }

    fun cancelActiveRide(reason: String) {
        val ride = activeRide.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            sampleRepository.advanceSampleRideStatus(
                rideId = ride.rideId,
                nextStatus = "cancelled",
                cancellationReason = reason.ifBlank { "Sample rider cancelled" }
            ).onSuccess {
                _message.value = "Sample ride cancelled."
            }.onFailure { err ->
                _message.value = err.localizedMessage ?: "Unable to cancel sample ride."
            }
            _isBusy.value = false
        }
    }

    fun updateProfile(
        displayName: String,
        vehicleCategory: String,
        photoUrl: String,
        emergencyContactName: String,
        emergencyContactPhone: String
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            sampleRepository.updateSampleRiderProfile(
                uid = currentUserId,
                displayName = displayName,
                vehicleCategory = vehicleCategory,
                photoUrl = photoUrl,
                emergencyContactName = emergencyContactName,
                emergencyContactPhone = emergencyContactPhone
            ).onSuccess {
                _message.value = "Sample Rider profile & emergency contact saved."
            }.onFailure { err ->
                _message.value = err.localizedMessage ?: "Failed updating profile."
            }
            _isBusy.value = false
        }
    }

    fun triggerEmergencySos(notes: String) {
        val fix = _gpsFix.value
        val active = activeRide.value
        viewModelScope.launch {
            _isBusy.value = true
            golocalRepository.triggerEmergencySos(
                bookingId = active?.rideId.orEmpty(),
                lat = fix?.lat ?: 0.0,
                lng = fix?.lng ?: 0.0,
                notes = "[SAMPLE RIDER SOS] ${notes.ifBlank { "Emergency support requested from Sample Rider app." }}"
            ).onSuccess { eventId ->
                _message.value = "Emergency SOS #$eventId sent to GoLocal Safety Desk."
            }.onFailure { err ->
                _message.value = err.localizedMessage ?: "Failed sending SOS alert."
            }
            _isBusy.value = false
        }
    }

    fun markNotificationRead(notificationId: String) {
        viewModelScope.launch {
            golocalRepository.markNotificationRead(notificationId)
        }
    }

    fun syncForegroundTrackingService(context: Context) {
        val prof = profile.value
        val active = activeRide.value
        com.example.data.tracking.RiderLocationForegroundService.syncSampleTracking(
            context = context,
            sampleRiderUid = currentUserId,
            isOnline = prof?.isOnline() == true,
            activeSampleRide = active
        )
    }

    fun dismissCompletedSummary() {
        _lastCompletedRide.value = null
    }

    fun clearMessage() {
        _message.value = null
    }

    fun clearOtpError() {
        _otpError.value = null
    }

    private fun startOfTodayEpochMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private companion object {
        const val TAG = "SampleRiderVM"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
