package com.example.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.map.MapProviderConfig
import com.example.data.map.OsrmRoutingAdapter
import com.example.data.map.RouteResult
import com.example.data.model.Booking
import com.example.data.model.CommissionRecord
import com.example.data.model.DriverProfile
import com.example.data.model.DriverSettlement
import com.example.data.model.UiState
import com.example.data.repository.GolocalRepository
import com.example.data.tracking.LiveLocationTracker
import com.example.data.tracking.LocationTelemetryState
import com.example.data.tracking.RealtimeTrackingRepository
import com.example.domain.BoraiLaunchConfig
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class DriverViewModel(
    private val repository: GolocalRepository,
    private val currentUserId: String,
    private val realtimeTrackingRepository: RealtimeTrackingRepository? = null,
    private var osrmAdapter: OsrmRoutingAdapter = OsrmRoutingAdapter()
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _telemetryState = MutableStateFlow(LocationTelemetryState())
    val telemetryState: StateFlow<LocationTelemetryState> = _telemetryState.asStateFlow()

    private var liveTracker: LiveLocationTracker? = null
    private var activeTrackingRepo: RealtimeTrackingRepository? = realtimeTrackingRepository
    private var lastTrackedBookingId: String = ""
    private var lastFirestoreSyncMs: Long = 0L

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

    val openRequests: StateFlow<List<Booking>> = combine(
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

    val currentActiveBooking: StateFlow<Booking?> = assignedBookings
        .map { list ->
            list.firstOrNull { it.status in setOf("assigned", "arriving", "arrived", "in_progress") }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val activeTripRoute: StateFlow<RouteResult?> = combine(
        currentActiveBooking,
        _telemetryState
    ) { booking, telemetry ->
        booking to telemetry
    }.flatMapLatest { (booking, telemetry) ->
        if (booking == null) {
            flowOf<RouteResult?>(null)
        } else {
            flow<RouteResult?> {
                val startLat: Double
                val startLng: Double
                val endLat: Double
                val endLng: Double
                if (booking.status == "in_progress") {
                    startLat = booking.pickupLat
                    startLng = booking.pickupLng
                    endLat = booking.destinationLat
                    endLng = booking.destinationLng
                } else {
                    startLat = if (telemetry.hasRealFix) telemetry.lastLat else booking.pickupLat
                    startLng = if (telemetry.hasRealFix) telemetry.lastLng else booking.pickupLng
                    endLat = booking.pickupLat
                    endLng = booking.pickupLng
                }
                emit(
                    osrmAdapter.fetchRoute(
                        startLat = startLat,
                        startLng = startLng,
                        endLat = endLat,
                        endLng = endLng,
                        vehicleCategory = booking.vehicleCategory
                    )
                )
            }
        }
    }.stateIn(
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

    fun attachContext(context: Context) {
        if (activeTrackingRepo == null) {
            activeTrackingRepo = RealtimeTrackingRepository(context.applicationContext)
        }
        if (liveTracker == null) {
            osrmAdapter = OsrmRoutingAdapter(endpoints = MapProviderConfig.loadEndpoints(context))
            val tracker = LiveLocationTracker(context.applicationContext)
            liveTracker = tracker
            viewModelScope.launch {
                tracker.state.collect { st ->
                    _telemetryState.value = st
                }
            }
        } else {
            liveTracker?.refreshPermissionStatus()
        }
    }

    fun refreshLocationPermission(context: Context) {
        attachContext(context)
        liveTracker?.refreshPermissionStatus()
        syncLocationSharingLifecycle(context)
    }

    fun syncLocationSharingLifecycle(context: Context) {
        attachContext(context)
        val tracker = liveTracker ?: return
        val profile = (driverProfileState.value as? UiState.Success)?.data
        val activeBooking = currentActiveBooking.value
        val isAuthorized = profile?.verificationStatus == "verified" &&
            (profile.isOnline || activeBooking != null)

        if (!isAuthorized) {
            val prevBooking = lastTrackedBookingId
            lastTrackedBookingId = ""
            if (tracker.state.value.isSharing || prevBooking.isNotBlank()) {
                val lat = tracker.state.value.lastLat
                val lng = tracker.state.value.lastLng
                tracker.stopTracking("Location sharing stopped (offline / no active trip)")
                viewModelScope.launch {
                    if (prevBooking.isNotBlank()) {
                        activeTrackingRepo?.stopTripSharing(prevBooking, lat, lng)
                    } else {
                        activeTrackingRepo?.stopDriverSharing(lat, lng)
                    }
                }
            }
            return
        }

        if (activeBooking != null) {
            lastTrackedBookingId = activeBooking.id
        } else if (lastTrackedBookingId.isNotBlank()) {
            val completedId = lastTrackedBookingId
            lastTrackedBookingId = ""
            val lat = tracker.state.value.lastLat
            val lng = tracker.state.value.lastLng
            viewModelScope.launch {
                activeTrackingRepo?.stopTripSharing(completedId, lat, lng)
            }
        }

        tracker.startTracking(
            highAccuracyTripMode = activeBooking != null,
            onFix = { lat, lng, accuracy, speed, heading ->
                val bookingNow = currentActiveBooking.value
                viewModelScope.launch {
                    activeTrackingRepo?.publishDriverLocation(
                        lat = lat,
                        lng = lng,
                        activeBookingId = bookingNow?.id.orEmpty(),
                        customerId = bookingNow?.customerId.orEmpty(),
                        accuracyMeters = accuracy,
                        speedMps = speed,
                        headingDeg = heading,
                        trackingStatus = "active"
                    )
                    // Throttled Firestore mirror (every >= 15s during active trip) for audit & cross-portal fallback
                    val now = System.currentTimeMillis()
                    if (bookingNow != null && (now - lastFirestoreSyncMs) >= 15_000L) {
                        lastFirestoreSyncMs = now
                        repository.advanceDriverTripState(
                            booking = bookingNow,
                            nextStatus = bookingNow.status,
                            enteredPin = "",
                            driverLat = lat,
                            driverLng = lng
                        )
                    }
                }
            },
            onSignalAvailabilityChanged = { available ->
                if (!available) {
                    val bookingNow = currentActiveBooking.value
                    val snap = tracker.state.value
                    viewModelScope.launch {
                        activeTrackingRepo?.markGpsSignalLost(
                            lastLat = snap.lastLat,
                            lastLng = snap.lastLng,
                            activeBookingId = bookingNow?.id.orEmpty(),
                            customerId = bookingNow?.customerId.orEmpty()
                        )
                    }
                }
            }
        )
    }

    fun stopAllTrackingForSignOut() {
        val snap = _telemetryState.value
        val prevBooking = lastTrackedBookingId
        lastTrackedBookingId = ""
        liveTracker?.stopTracking("Signed out")
        viewModelScope.launch {
            if (prevBooking.isNotBlank()) {
                activeTrackingRepo?.stopTripSharing(prevBooking, snap.lastLat, snap.lastLng)
            } else {
                activeTrackingRepo?.stopDriverSharing(snap.lastLat, snap.lastLng)
            }
        }
    }

    override fun onCleared() {
        liveTracker?.stopTracking("Session closed")
        super.onCleared()
    }

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

    fun toggleOnline(
        online: Boolean,
        lat: Double = _telemetryState.value.lastLat.takeIf { it != 0.0 } ?: BoraiLaunchConfig.DEFAULT_LAT,
        lng: Double = _telemetryState.value.lastLng.takeIf { it != 0.0 } ?: BoraiLaunchConfig.DEFAULT_LNG
    ) {
        viewModelScope.launch {
            repository.setDriverOnlineStatus(online, lat, lng)
                .onSuccess {
                    if (!online) {
                        liveTracker?.stopTracking("Driver went offline")
                        activeTrackingRepo?.stopDriverSharing(lat, lng)
                    }
                    _message.value = if (online) "You are now Online and visible to riders." else "You are now Offline and GPS sharing is stopped."
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

    fun advanceTripState(
        booking: Booking,
        nextStatus: String,
        enteredPin: String = "",
        lat: Double = _telemetryState.value.lastLat,
        lng: Double = _telemetryState.value.lastLng
    ) {
        viewModelScope.launch {
            repository.advanceDriverTripState(booking, nextStatus, enteredPin, lat, lng)
                .onSuccess {
                    if (nextStatus == "completed" || nextStatus == "cancelled") {
                        activeTrackingRepo?.stopTripSharing(booking.id, lat, lng)
                    }
                    _message.value = when (nextStatus) {
                        "arriving" -> "Marked as heading to pickup. Live GPS shared with passenger."
                        "arrived" -> "Marked as arrived at pickup. Ask passenger for Trip PIN."
                        "in_progress" -> "Trip started! Live route telemetry active."
                        "completed" -> "Trip completed, fare settled, and live trip sharing stopped."
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
