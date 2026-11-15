package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.Booking
import com.example.data.model.CommissionRecord
import com.example.data.model.DriverProfile
import com.example.data.model.DriverSettlement
import com.example.data.model.UiState
import com.example.data.repository.GolocalRepository
import com.google.firebase.Timestamp
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

class DriverViewModel(
    private val repository: GolocalRepository,
    private val currentUserId: String
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val driverProfileState: StateFlow<UiState<DriverProfile?>> = repository.observeMyDriverProfile(currentUserId)
        .map<DriverProfile?, UiState<DriverProfile?>> { UiState.Success(it) }
        .catch { error ->
            val fe = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (fe == null && error is CancellationException) throw error
            val root = fe ?: error
            Log.w(TAG, "Error observing driver profile", root)
            emit(UiState.Error(root.message ?: "Failed to load driver profile"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    private val _dismissedRequestIds = MutableStateFlow<Set<String>>(emptySet())

    val openRequests: StateFlow<List<Booking>> = kotlinx.coroutines.flow.combine(
        repository.observeOpenBookingsForDrivers(),
        _dismissedRequestIds
    ) { list, dismissed ->
        list.filter { it.customerId != currentUserId && it.id !in dismissed }
            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
    }
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Error observing open bookings", error)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val myOffers: StateFlow<List<com.example.data.model.RideOffer>> = repository.observeDriverOffers(currentUserId)
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Error observing driver offers", error)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val myRatings: StateFlow<List<com.example.data.model.RatingRecord>> = repository.observeDriverRatings(currentUserId)
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Error observing driver ratings", error)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val assignedBookings: StateFlow<List<Booking>> = repository.observeDriverAssignedBookings(currentUserId)
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Error observing assigned bookings", error)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val commissions: StateFlow<List<CommissionRecord>> = repository.observeDriverCommissions(currentUserId)
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Error observing commissions", error)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val settlements: StateFlow<List<DriverSettlement>> = repository.observeDriverSettlements(currentUserId)
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Error observing settlements", error)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    fun rejectRequest(bookingId: String) {
        _dismissedRequestIds.value = _dismissedRequestIds.value + bookingId
        _message.value = "Request skipped."
    }

    fun withdrawOffer(offerId: String) {
        viewModelScope.launch {
            repository.withdrawDriverOffer(offerId)
                .onSuccess { _message.value = "Fare proposal withdrawn." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to withdraw proposal" }
        }
    }

    fun registerDriver(
        legalName: String,
        phone: String,
        address: String,
        district: String,
        block: String,
        vehicleCategory: String,
        vehicleModel: String,
        vehicleRegNumber: String,
        drivingLicenceNumber: String,
        rcNumber: String,
        insuranceNumber: String,
        permitInfo: String,
        settlementUpi: String,
        documentPhotoUri: String
    ) {
        if (legalName.trim().length < 2 || phone.trim().length < 6 ||
            vehicleRegNumber.trim().length < 4 || drivingLicenceNumber.trim().length < 4
        ) {
            _message.value = "Please fill in legal name, phone, vehicle registration, and driving licence."
            return
        }
        viewModelScope.launch {
            repository.registerDriverApplication(
                DriverProfile(
                    driverId = currentUserId,
                    legalName = legalName,
                    phone = phone,
                    address = address,
                    district = district,
                    block = block,
                    vehicleCategory = vehicleCategory,
                    vehicleModel = vehicleModel,
                    vehicleRegNumber = vehicleRegNumber,
                    drivingLicenceNumber = drivingLicenceNumber,
                    rcNumber = rcNumber,
                    insuranceNumber = insuranceNumber,
                    permitInfo = permitInfo,
                    settlementUpiOrBank = settlementUpi,
                    documentPhotoUri = documentPhotoUri
                )
            ).onSuccess {
                _message.value = "Application submitted. Admin verification is required before going online."
            }.onFailure {
                _message.value = it.localizedMessage ?: "Registration failed"
            }
        }
    }

    fun toggleOnline(online: Boolean, lat: Double, lng: Double) {
        viewModelScope.launch {
            repository.setDriverOnlineStatus(online, lat, lng)
                .onSuccess {
                    _message.value = if (online) "You are now Online and visible to riders." else "You are now Offline."
                }
                .onFailure {
                    _message.value = it.localizedMessage ?: "Unable to update online status"
                }
        }
    }

    fun submitOffer(booking: Booking, proposedFareInr: Double, etaMinutes: Long) {
        val profile = (driverProfileState.value as? UiState.Success)?.data
        if (profile == null || profile.verificationStatus != "verified") {
            _message.value = "Only verified drivers can submit fare offers."
            return
        }
        viewModelScope.launch {
            repository.submitDriverOffer(booking, profile, proposedFareInr, etaMinutes)
                .onSuccess {
                    _message.value = "Fare offer ₹${proposedFareInr.toInt()} sent to ${booking.customerName}."
                }
                .onFailure {
                    _message.value = it.localizedMessage ?: "Offer submission failed"
                }
        }
    }

    fun advanceTripState(booking: Booking, nextStatus: String, enteredPin: String = "", lat: Double, lng: Double) {
        viewModelScope.launch {
            repository.advanceDriverTripState(booking, nextStatus, enteredPin, lat, lng)
                .onSuccess {
                    _message.value = when (nextStatus) {
                        "arriving" -> "Marked as heading to pickup."
                        "arrived" -> "Marked as arrived at pickup. Ask passenger for Trip PIN."
                        "in_progress" -> "Trip started!"
                        "completed" -> "Trip completed & fare settlement recorded."
                        else -> "Status updated."
                    }
                }
                .onFailure {
                    _message.value = it.localizedMessage ?: "Failed to update trip state"
                }
        }
    }

    fun requestWithdrawal(amountInr: Double, upiOrBank: String) {
        val profile = (driverProfileState.value as? UiState.Success)?.data
        if (profile == null) {
            _message.value = "Driver profile not found."
            return
        }
        if (upiOrBank.trim().length < 3) {
            _message.value = "Enter a valid UPI ID or bank reference."
            return
        }
        viewModelScope.launch {
            repository.requestDriverSettlement(profile.legalName, amountInr, upiOrBank)
                .onSuccess { _message.value = "Withdrawal request for ₹${amountInr.toInt()} submitted." }
                .onFailure { _message.value = it.localizedMessage ?: "Withdrawal request failed" }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val TAG = "DriverVM"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
