package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.Booking
import com.example.data.model.BookingCallContact
import com.example.data.model.BookingChatMessage
import com.example.data.model.CancellationAppeal
import com.example.data.model.CommissionRecord
import com.example.data.model.DriverProfile
import com.example.data.model.DriverSettlement
import com.example.data.model.DriverVerificationHistoryEntry
import com.example.data.model.RatingRecord
import com.example.data.model.RideOffer
import com.example.data.model.UiState
import com.example.data.repository.CommunicationRepository
import com.example.data.repository.GolocalRepository
import com.example.domain.BookingChatValidator
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class DriverViewModel(
    private val repository: GolocalRepository,
    private val currentUserId: String,
    private val communicationRepository: CommunicationRepository? = null
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

    val verificationHistory: StateFlow<List<DriverVerificationHistoryEntry>> =
        repository.observeDriverVerificationHistory(currentUserId)
            .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
            .catch { error ->
                if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
                emit(emptyList())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList()
            )

    private val _dismissedRequestIds = MutableStateFlow<Set<String>>(emptySet())

    val openRequests: StateFlow<List<Booking>> = combine(
        repository.observeOpenBookingsForDrivers(),
        _dismissedRequestIds
    ) { list, dismissed ->
        list.filter { !it.isTestRide && !it.testMode && it.customerId != currentUserId && it.id !in dismissed }
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

    val myOffers: StateFlow<List<RideOffer>> = repository.observeDriverOffers(currentUserId)
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

    val myRatings: StateFlow<List<RatingRecord>> = repository.observeDriverRatings(currentUserId)
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
        .map { list ->
            list.filter { !it.isTestRide && !it.testMode }
                .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Error observing assigned bookings", error)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val activeAssignedBooking: StateFlow<Booking?> = assignedBookings
        .map { list ->
            list.firstOrNull { it.status in setOf("assigned", "arriving", "arrived", "in_progress") }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val activeChatMessages: StateFlow<List<BookingChatMessage>> = activeAssignedBooking
        .flatMapLatest { booking ->
            val comm = communicationRepository
            if (comm == null || booking == null || !BookingChatValidator.isChatReadAllowed(booking.status)) {
                flowOf(emptyList())
            } else {
                comm.observeMessages(booking.id)
                    .catch { error ->
                        if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
                        emit(emptyList())
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val unreadChatCount: StateFlow<Int> = activeChatMessages
        .map { msgs -> msgs.count { it.senderId != currentUserId && !it.readByDriver } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = 0
        )

    val activeCallContact: StateFlow<BookingCallContact?> = activeAssignedBooking
        .flatMapLatest { booking ->
            val comm = communicationRepository
            if (comm == null || booking == null || !BookingChatValidator.isChatWriteAllowed(booking.status)) {
                flowOf(null)
            } else {
                comm.observeParticipantCallContact(booking)
                    .catch { error ->
                        if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
                        emit(null)
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
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

    val myAppeals: StateFlow<List<CancellationAppeal>> = repository.observeMyCancellationAppeals(currentUserId)
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
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
        documentPhotoUri: String,
        pucNumber: String = "",
        fitnessCertificateNumber: String = "",
        dlExpiryEpochDay: Long = 0L,
        rcExpiryEpochDay: Long = 0L,
        insuranceExpiryEpochDay: Long = 0L,
        permitExpiryEpochDay: Long = 0L,
        fitnessExpiryEpochDay: Long = 0L,
        pucExpiryEpochDay: Long = 0L,
        dlPhotoUri: String = "",
        rcPhotoUri: String = "",
        insurancePhotoUri: String = "",
        permitPhotoUri: String = "",
        fitnessPhotoUri: String = "",
        pucPhotoUri: String = ""
    ) {
        if (legalName.trim().length < 2 || phone.trim().length < 6 ||
            vehicleRegNumber.trim().length < 4 || drivingLicenceNumber.trim().length < 4
        ) {
            _message.value = "Please fill in legal name, phone, vehicle registration, and driving licence."
            return
        }
        val existingVersion = (driverProfileState.value as? UiState.Success)?.data?.verificationVersion ?: 0L
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
                    documentPhotoUri = documentPhotoUri,
                    pucNumber = pucNumber,
                    fitnessCertificateNumber = fitnessCertificateNumber,
                    dlExpiryEpochDay = dlExpiryEpochDay,
                    rcExpiryEpochDay = rcExpiryEpochDay,
                    insuranceExpiryEpochDay = insuranceExpiryEpochDay,
                    permitExpiryEpochDay = permitExpiryEpochDay,
                    fitnessExpiryEpochDay = fitnessExpiryEpochDay,
                    pucExpiryEpochDay = pucExpiryEpochDay,
                    dlPhotoUri = dlPhotoUri,
                    rcPhotoUri = rcPhotoUri,
                    insurancePhotoUri = insurancePhotoUri,
                    permitPhotoUri = permitPhotoUri,
                    fitnessPhotoUri = fitnessPhotoUri,
                    pucPhotoUri = pucPhotoUri,
                    verificationVersion = (existingVersion + 1L).coerceAtLeast(1L)
                )
            ).onSuccess {
                _message.value = "KYC submitted for verification. Admin review is required before going online."
            }.onFailure {
                _message.value = it.localizedMessage ?: "Registration failed"
            }
        }
    }

    private val gpsThrottle = com.example.map.GpsBroadcastThrottle()

    fun toggleOnline(online: Boolean, lat: Double, lng: Double) {
        viewModelScope.launch {
            if (!online) gpsThrottle.reset()
            repository.setDriverOnlineStatus(online, lat, lng)
                .onSuccess {
                    _message.value = if (online) "You are now Online and visible to riders." else "You are now Offline."
                }
                .onFailure {
                    _message.value = it.localizedMessage ?: "Unable to update online status"
                }
        }
    }

    fun broadcastGpsSample(lat: Double, lng: Double, accuracyMeters: Float = 15f, force: Boolean = false) {
        val profile = (driverProfileState.value as? UiState.Success)?.data ?: return
        val booking = activeAssignedBooking.value
        val shouldEmit = (profile.isOnline && profile.verificationStatus == "verified") || booking != null
        if (!shouldEmit) return

        val sample = com.example.map.LocationSample(lat = lat, lng = lng, accuracyMeters = accuracyMeters)
        if (!gpsThrottle.acceptIfQualified(sample, force = force)) return

        viewModelScope.launch {
            repository.updateDriverLiveLocation(
                activeBooking = booking,
                isOnline = profile.isOnline,
                isVerified = profile.verificationStatus == "verified",
                lat = lat,
                lng = lng
            )
        }
    }

    fun stopLiveLocationBroadcast() {
        gpsThrottle.reset()
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
        val profile = (driverProfileState.value as? UiState.Success)?.data
        viewModelScope.launch {
            repository.advanceDriverTripState(booking, nextStatus, enteredPin, lat, lng)
                .onSuccess {
                    if (profile != null && communicationRepository != null && BookingChatValidator.isChatWriteAllowed(nextStatus)) {
                        communicationRepository.publishParticipantContactForActiveTrip(
                            booking = booking.copy(status = nextStatus),
                            myPhone = profile.phone,
                            myDisplayName = profile.legalName,
                            isDriver = true
                        )
                    }
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

    fun cancelActiveBookingByDriver(booking: Booking, category: String, reason: String) {
        viewModelScope.launch {
            repository.cancelBookingByDriver(booking, reason, category)
                .onSuccess { _message.value = "Trip cancelled. Frequent cancellations are reviewed by Operations." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to cancel trip" }
        }
    }

    fun submitCustomerFeedback(bookingId: String, stars: Int, note: String) {
        viewModelScope.launch {
            repository.submitCustomerFeedbackByDriver(bookingId, stars, note)
                .onSuccess { _message.value = "Passenger feedback recorded." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to save feedback" }
        }
    }

    fun sendChatMessage(booking: Booking, text: String) {
        val comm = communicationRepository ?: return
        val driverName = (driverProfileState.value as? UiState.Success)?.data?.legalName ?: "GoLocal Rider"
        viewModelScope.launch {
            comm.sendMessage(booking, "driver", driverName, text)
                .onFailure { _message.value = it.localizedMessage ?: "Failed to send message" }
        }
    }

    fun markActiveChatRead() {
        val comm = communicationRepository ?: return
        val booking = activeAssignedBooking.value ?: return
        val msgs = activeChatMessages.value
        if (msgs.isEmpty()) return
        viewModelScope.launch {
            comm.markMessagesRead(booking.id, "driver", msgs)
        }
    }

    fun submitAppeal(bookingId: String, reason: String) {
        viewModelScope.launch {
            repository.submitCancellationAppeal(bookingId, "driver", reason)
                .onSuccess { _message.value = "Appeal submitted for Admin review." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to submit appeal" }
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

    fun triggerSos(bookingId: String, lat: Double, lng: Double, notes: String) {
        viewModelScope.launch {
            repository.triggerEmergencySos(bookingId, lat, lng, notes)
                .onSuccess { eventId ->
                    _message.value = "SOS Alert #$eventId escalated to GoLocal Safety Desk."
                }
                .onFailure {
                    _message.value = it.localizedMessage ?: "Failed to send SOS alert"
                }
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
