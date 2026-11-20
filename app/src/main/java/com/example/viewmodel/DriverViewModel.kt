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
import com.example.data.model.TestRiderProfile
import com.example.data.model.TestRiderRequest
import com.example.data.model.UiState
import com.example.data.repository.CommunicationRepository
import com.example.data.repository.GolocalRepository
import com.example.data.repository.RealtimeLocationRepository
import com.example.domain.BookingChatValidator
import com.example.domain.FareCalculator
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class DriverViewModel(
    private val repository: GolocalRepository,
    private val currentUserId: String,
    private val communicationRepository: CommunicationRepository? = null,
    private val realtimeLocationRepository: RealtimeLocationRepository? = null
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _dispatchRefreshTrigger = MutableStateFlow(0)

    fun refreshDispatchFeeds() {
        _openRequestsError.value = null
        _assignedBookingsError.value = null
        _openTestBookingsError.value = null
        _testRiderProfileError.value = null
        _dispatchRefreshTrigger.value += 1
    }

    val driverProfileState: StateFlow<UiState<DriverProfile?>> = _dispatchRefreshTrigger
        .flatMapLatest {
            repository.observeMyDriverProfile(currentUserId)
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

    private val _openRequestsError = MutableStateFlow<String?>(null)
    val openRequestsError: StateFlow<String?> = _openRequestsError.asStateFlow()

    private val _assignedBookingsError = MutableStateFlow<String?>(null)
    val assignedBookingsError: StateFlow<String?> = _assignedBookingsError.asStateFlow()

    private val _testRiderProfileError = MutableStateFlow<String?>(null)
    val testRiderProfileError: StateFlow<String?> = _testRiderProfileError.asStateFlow()

    private val _openTestBookingsError = MutableStateFlow<String?>(null)
    val openTestBookingsError: StateFlow<String?> = _openTestBookingsError.asStateFlow()

    private val rejectedBookingIdsFlow = combine(driverProfileState, _dispatchRefreshTrigger) { profileUi, trigger ->
        ((profileUi as? UiState.Success)?.data?.verificationStatus == "verified") to trigger
    }
        .distinctUntilChanged()
        .flatMapLatest { (isVerified, _) ->
            if (!isVerified) {
                flowOf(emptySet())
            } else {
                repository.observeMyRejectedBookingIds(currentUserId)
                    .catch { error ->
                        if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
                        Log.w(TAG, "Error observing rejected booking IDs", error)
                        emit(emptySet())
                    }
            }
        }

    private val rawOpenBookingsFlow = combine(driverProfileState, _dispatchRefreshTrigger) { profileUi, trigger ->
        ((profileUi as? UiState.Success)?.data?.verificationStatus == "verified") to trigger
    }
        .distinctUntilChanged()
        .flatMapLatest { (isVerified, _) ->
            if (!isVerified) {
                _openRequestsError.value = null
                flowOf(emptyList())
            } else {
                repository.observeOpenBookingsForDrivers()
                    .onEach { _openRequestsError.value = null }
                    .catch { error ->
                        val fe = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (fe == null && error is CancellationException) throw error
                        val root = fe ?: error
                        Log.w(TAG, "Error observing open bookings for verified driver=$currentUserId", root)
                        _openRequestsError.value = root.localizedMessage ?: "Unable to sync nearby ride requests."
                        emit(emptyList())
                    }
            }
        }

    val openRequests: StateFlow<List<Booking>> = combine(
        rawOpenBookingsFlow,
        rejectedBookingIdsFlow,
        _dismissedRequestIds,
        driverProfileState
    ) { list, persistedRejected, dismissed, profileUi ->
        val profile = (profileUi as? UiState.Success)?.data
        val nowMs = System.currentTimeMillis()
        list.filter { booking ->
            !booking.isTestRide &&
                !booking.testMode &&
                booking.customerId != currentUserId &&
                booking.id !in persistedRejected &&
                booking.id !in dismissed &&
                (booking.requestExpiresAtMs <= 0L || nowMs <= booking.requestExpiresAtMs) &&
                (profile == null || FareCalculator.isCategoryCompatible(profile.vehicleCategory, booking.vehicleCategory))
        }.sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
    }
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Error combining open bookings", error)
            _openRequestsError.value = error.localizedMessage ?: "Unable to sync nearby ride requests."
            emit(emptyList())
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
            emit(emptyList())
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
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val testRiderRequestState: StateFlow<UiState<TestRiderRequest?>> =
        repository.observeMyTestRiderRequest(currentUserId)
            .map<TestRiderRequest?, UiState<TestRiderRequest?>> { UiState.Success(it) }
            .catch { error ->
                val fe = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fe == null && error is CancellationException) throw error
                val root = fe ?: error
                Log.w(TAG, "Error observing test rider request", root)
                emit(UiState.Error(root.message ?: "Failed to load Test Rider request"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val testRiderProfile: StateFlow<TestRiderProfile?> = _dispatchRefreshTrigger
        .flatMapLatest {
            repository.observeMyTestRiderProfile(currentUserId)
                .onEach { _testRiderProfileError.value = null }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    val root = fe ?: error
                    Log.w(TAG, "Error observing test rider profile", root)
                    _testRiderProfileError.value = root.localizedMessage ?: "Failed to load Test Rider profile."
                    emit(null)
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val assignedBookings: StateFlow<List<Booking>> = combine(
        driverProfileState,
        testRiderProfile,
        _dispatchRefreshTrigger
    ) { profileUi, testProfile, trigger ->
        val isVerifiedDriver = (profileUi as? UiState.Success)?.data?.verificationStatus == "verified"
        val isApprovedTestRider = testProfile?.status == "approved"
        (isVerifiedDriver || isApprovedTestRider) to trigger
    }
        .distinctUntilChanged()
        .flatMapLatest { (canReadBookings, _) ->
            if (!canReadBookings) {
                _assignedBookingsError.value = null
                flowOf(emptyList())
            } else {
                repository.observeDriverAssignedBookings(currentUserId)
                    .onEach { _assignedBookingsError.value = null }
                    .map { list ->
                        list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                    }
                    .catch { error ->
                        val fe = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (fe == null && error is CancellationException) throw error
                        val root = fe ?: error
                        Log.w(TAG, "Error observing assigned bookings", root)
                        _assignedBookingsError.value = root.localizedMessage ?: "Unable to sync active rides."
                        emit(emptyList())
                    }
            }
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

    val openTestBookings: StateFlow<List<Booking>> = combine(
        testRiderProfile,
        _dispatchRefreshTrigger
    ) { profile, trigger ->
        (profile?.status == "approved") to trigger
    }
        .distinctUntilChanged()
        .flatMapLatest { (isApproved, _) ->
            if (!isApproved) {
                _openTestBookingsError.value = null
                flowOf(emptyList())
            } else {
                repository.observeOpenTestBookingsForTestRiders()
                    .onEach { _openTestBookingsError.value = null }
                    .map { list ->
                        list.filter {
                            (it.isTestRide || it.testMode) &&
                                it.status == "searching" &&
                                it.customerId != currentUserId
                        }.sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                    }
                    .catch { error ->
                        val fe = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (fe == null && error is CancellationException) throw error
                        val root = fe ?: error
                        Log.w(TAG, "Error observing open test bookings", root)
                        _openTestBookingsError.value = root.localizedMessage ?: "Unable to sync open TEST RIDE bookings."
                        emit(emptyList())
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val testRiderCompletedCount: StateFlow<Int> = assignedBookings
        .map { list -> list.count { (it.isTestRide || it.testMode) && it.status == "completed" } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = 0
        )

    private val _isVerifyingTestOtp = MutableStateFlow(false)
    val isVerifyingTestOtp: StateFlow<Boolean> = _isVerifyingTestOtp.asStateFlow()

    private val _testOtpError = MutableStateFlow<String?>(null)
    val testOtpError: StateFlow<String?> = _testOtpError.asStateFlow()

    private val _isVerifyingCommercialOtp = MutableStateFlow(false)
    val isVerifyingCommercialOtp: StateFlow<Boolean> = _isVerifyingCommercialOtp.asStateFlow()

    private val _commercialOtpError = MutableStateFlow<String?>(null)
    val commercialOtpError: StateFlow<String?> = _commercialOtpError.asStateFlow()

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

    fun rejectRequest(bookingId: String, isExpired: Boolean = false) {
        _dismissedRequestIds.value = _dismissedRequestIds.value + bookingId
        _message.value = if (isExpired) "Request expired and removed." else "Request declined."
        viewModelScope.launch {
            repository.rejectOpenBooking(
                bookingId = bookingId,
                reason = if (isExpired) "expired" else "declined"
            )
        }
    }

    fun acceptRideDirectly(
        booking: Booking,
        agreedFareInr: Double,
        lat: Double,
        lng: Double
    ) {
        val profile = (driverProfileState.value as? UiState.Success)?.data
        if (profile == null || profile.verificationStatus != "verified") {
            _message.value = "Only verified drivers can accept ride requests."
            return
        }
        viewModelScope.launch {
            repository.acceptBookingAtomically(
                booking = booking,
                driver = profile,
                fareInr = agreedFareInr,
                currentLat = lat,
                currentLng = lng
            ).onSuccess {
                _message.value = "Ride #${booking.id.take(6)} accepted! Navigate to pickup."
                if (com.example.map.GpsBroadcastThrottle.isValidCoordinate(lat, lng)) {
                    realtimeLocationRepository?.publishDriverAndTripLocation(
                        driverId = currentUserId,
                        activeBooking = booking.copy(assignedDriverId = currentUserId, status = "assigned"),
                        lat = lat,
                        lng = lng,
                        accuracyMeters = 15f,
                        speedMps = 0f,
                        headingDeg = 0f
                    )
                }
                communicationRepository?.publishParticipantContactForActiveTrip(
                    booking = booking.copy(assignedDriverId = currentUserId, status = "assigned"),
                    myPhone = profile.phone,
                    myDisplayName = profile.legalName,
                    isDriver = true
                )
            }.onFailure { exc ->
                val raw = exc.localizedMessage ?: "Unable to accept ride request"
                _message.value = if (raw.contains("already", ignoreCase = true) ||
                    raw.contains("PERMISSION_DENIED", ignoreCase = true)
                ) {
                    "This ride is no longer available (already accepted or cancelled)."
                } else {
                    raw
                }
            }
        }
    }

    fun updateDriverSelfProfile(
        legalName: String,
        phone: String,
        address: String,
        settlementUpiOrBank: String
    ) {
        viewModelScope.launch {
            repository.updateDriverSelfProfile(
                legalName = legalName,
                phone = phone,
                address = address,
                settlementUpiOrBank = settlementUpiOrBank
            ).onSuccess {
                _message.value = "Rider contact & payout settings updated."
            }.onFailure { exc ->
                _message.value = exc.localizedMessage ?: "Failed to update rider profile."
            }
        }
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
        pucPhotoUri: String = "",
        emergencyContactName: String = "",
        emergencyContactPhone: String = "",
        policeVerificationNumber: String = "",
        alternateIdType: String = "",
        alternateIdLast4: String = "",
        operatingState: String = "WB",
        welfareInsurancePolicyNo: String = "",
        driverTrainingCompleted: Boolean = false,
        complianceTermsAccepted: Boolean = false
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
                    emergencyContactName = emergencyContactName,
                    emergencyContactPhone = emergencyContactPhone,
                    policeVerificationNumber = policeVerificationNumber,
                    alternateIdType = alternateIdType,
                    alternateIdLast4 = alternateIdLast4,
                    operatingState = operatingState,
                    welfareInsurancePolicyNo = welfareInsurancePolicyNo,
                    driverTrainingCompleted = driverTrainingCompleted,
                    complianceTermsAccepted = complianceTermsAccepted,
                    verificationVersion = (existingVersion + 1L).coerceAtLeast(1L)
                )
            ).onSuccess {
                _message.value = "KYC submitted for verification. Admin review is required before going online."
            }.onFailure {
                _message.value = it.localizedMessage ?: "Registration failed"
            }
        }
    }

    private val gpsThrottle = com.example.map.GpsBroadcastThrottle(
        minIntervalMs = 2_500L,
        minDistanceMeters = 8.0
    )

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

    fun submitTestRiderRequest(
        fullName: String,
        mobileNumber: String,
        email: String,
        vehicleCategory: String,
        testPurpose: String,
        notes: String = "",
        deviceInfoSummary: String = ""
    ) {
        viewModelScope.launch {
            repository.submitTestRiderRequest(
                fullName = fullName,
                mobileNumber = mobileNumber,
                email = email,
                vehicleCategory = vehicleCategory,
                testPurpose = testPurpose,
                notes = notes,
                deviceInfoSummary = deviceInfoSummary
            ).onSuccess {
                _message.value = "Test Rider access request submitted for Owner Admin review."
            }.onFailure {
                _message.value = it.localizedMessage ?: "Failed to submit Test Rider request"
            }
        }
    }

    fun toggleTestRiderOnline(online: Boolean, lat: Double, lng: Double) {
        viewModelScope.launch {
            if (!online) gpsThrottle.reset()
            repository.setTestRiderOnlineStatus(online, lat, lng)
                .onSuccess {
                    _message.value = if (online) {
                        "Test Rider Mode is ONLINE for open TEST RIDE bookings."
                    } else {
                        "Test Rider Mode is OFFLINE."
                    }
                }
                .onFailure {
                    _message.value = it.localizedMessage ?: "Unable to update Test Rider online status"
                }
        }
    }

    fun claimTestBooking(booking: Booking, lat: Double, lng: Double) {
        val tr = testRiderProfile.value
        if (tr == null || tr.status != "approved") {
            _message.value = "Approved Test Rider profile is required to claim Test Rides."
            return
        }
        viewModelScope.launch {
            repository.claimTestBookingByTestRider(
                booking = booking,
                riderProfile = tr,
                currentLat = lat,
                currentLng = lng
            ).onSuccess {
                _message.value = "Claimed TEST RIDE #${booking.id.take(6)}. Head to pickup!"
                if (com.example.map.GpsBroadcastThrottle.isValidCoordinate(lat, lng)) {
                    realtimeLocationRepository?.publishDriverAndTripLocation(
                        driverId = currentUserId,
                        activeBooking = booking.copy(assignedDriverId = currentUserId, status = "assigned"),
                        lat = lat,
                        lng = lng,
                        accuracyMeters = 15f,
                        speedMps = 0f,
                        headingDeg = 0f
                    )
                }
            }.onFailure {
                _message.value = it.localizedMessage ?: "Unable to claim Test Ride"
            }
        }
    }

    fun verifyTestRideOtp(booking: Booking, enteredOtp: String) {
        if (_isVerifyingTestOtp.value) return
        _isVerifyingTestOtp.value = true
        _testOtpError.value = null
        viewModelScope.launch {
            repository.verifyTestRideOtpByRider(booking, enteredOtp)
                .onSuccess {
                    _testOtpError.value = null
                    _message.value = "Pickup OTP verified! Test Ride started."
                }
                .onFailure { exc ->
                    _testOtpError.value = exc.localizedMessage ?: "Invalid 6-digit Pickup OTP."
                }
            _isVerifyingTestOtp.value = false
        }
    }

    fun clearTestOtpError() {
        _testOtpError.value = null
    }

    fun verifyCommercialOtp(booking: Booking, enteredOtp: String) {
        if (_isVerifyingCommercialOtp.value) return
        _isVerifyingCommercialOtp.value = true
        _commercialOtpError.value = null
        viewModelScope.launch {
            repository.verifyCommercialRideOtpAndStartTrip(booking, enteredOtp)
                .onSuccess {
                    _commercialOtpError.value = null
                    _message.value = "6-digit Pickup OTP verified! Navigate to destination."
                }
                .onFailure { exc ->
                    _commercialOtpError.value = exc.localizedMessage ?: "Invalid 6-digit Pickup OTP."
                }
            _isVerifyingCommercialOtp.value = false
        }
    }

    fun clearCommercialOtpError() {
        _commercialOtpError.value = null
    }

    fun verifyPickupOtp(booking: Booking, enteredOtp: String) {
        if (booking.isTestRide || booking.testMode) {
            verifyTestRideOtp(booking, enteredOtp)
        } else {
            verifyCommercialOtp(booking, enteredOtp)
        }
    }

    fun broadcastGpsSample(
        lat: Double,
        lng: Double,
        accuracyMeters: Float = 15f,
        speedMps: Float = 0f,
        headingDeg: Float = 0f,
        force: Boolean = false,
        isTestRiderMode: Boolean = false
    ) {
        val booking = activeAssignedBooking.value
        val shouldEmit: Boolean
        val isOnlineFlag: Boolean
        val isVerifiedFlag: Boolean
        if (isTestRiderMode) {
            val tr = testRiderProfile.value ?: return
            val hasTestBooking = booking != null && (booking.isTestRide || booking.testMode)
            shouldEmit = tr.status == "approved" && (tr.isOnline || hasTestBooking)
            isOnlineFlag = tr.isOnline
            isVerifiedFlag = tr.status == "approved"
        } else {
            val profile = (driverProfileState.value as? UiState.Success)?.data ?: return
            shouldEmit = (profile.isOnline && profile.verificationStatus == "verified") || booking != null
            isOnlineFlag = profile.isOnline
            isVerifiedFlag = profile.verificationStatus == "verified"
        }
        if (!shouldEmit) return

        val sample = com.example.map.LocationSample(
            lat = lat,
            lng = lng,
            accuracyMeters = accuracyMeters,
            speedMps = speedMps,
            headingDeg = headingDeg
        )
        if (!gpsThrottle.acceptIfQualified(sample, force = force)) return

        val speedKmph = (speedMps.coerceAtLeast(0f) * 3.6f).toDouble()
        val normHeading = (((headingDeg % 360f) + 360f) % 360f).toDouble()
        viewModelScope.launch {
            realtimeLocationRepository?.publishDriverAndTripLocation(
                driverId = currentUserId,
                activeBooking = booking,
                lat = lat,
                lng = lng,
                accuracyMeters = accuracyMeters,
                speedMps = speedMps,
                headingDeg = headingDeg
            )
            if (isTestRiderMode) {
                repository.updateTestRiderLiveLocation(
                    activeBooking = booking,
                    isOnline = isOnlineFlag,
                    lat = lat,
                    lng = lng,
                    headingDeg = normHeading,
                    speedKmph = speedKmph
                )
            } else {
                repository.updateDriverLiveLocation(
                    activeBooking = booking,
                    isOnline = isOnlineFlag,
                    isVerified = isVerifiedFlag,
                    lat = lat,
                    lng = lng,
                    headingDeg = normHeading,
                    speedKmph = speedKmph
                )
            }
        }
    }

    fun stopLiveLocationBroadcast(lastLat: Double = 0.0, lastLng: Double = 0.0) {
        gpsThrottle.reset()
        val rtdb = realtimeLocationRepository ?: return
        val booking = activeAssignedBooking.value
        if (com.example.map.GpsBroadcastThrottle.isValidCoordinate(lastLat, lastLng)) {
            viewModelScope.launch {
                rtdb.stopTripTracking(
                    driverId = currentUserId,
                    bookingId = booking?.id,
                    customerId = booking?.customerId,
                    lastLat = lastLat,
                    lastLng = lastLng
                )
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
        val profile = (driverProfileState.value as? UiState.Success)?.data
        val isTest = booking.isTestRide || booking.testMode
        viewModelScope.launch {
            repository.advanceDriverTripState(booking, nextStatus, enteredPin, lat, lng)
                .onSuccess {
                    if (!isTest && profile != null && communicationRepository != null && BookingChatValidator.isChatWriteAllowed(nextStatus)) {
                        communicationRepository.publishParticipantContactForActiveTrip(
                            booking = booking.copy(status = nextStatus),
                            myPhone = profile.phone,
                            myDisplayName = profile.legalName,
                            isDriver = true
                        )
                    }
                    _message.value = when (nextStatus) {
                        "arriving" -> if (isTest) "TEST RIDE: Heading to pickup." else "Marked as heading to pickup."
                        "arrived" -> if (isTest) {
                            "TEST RIDE: Arrived at pickup. Ask passenger for 6-digit Test OTP."
                        } else {
                            "Marked as arrived at pickup. Ask passenger for 6-digit Pickup OTP."
                        }
                        "in_progress" -> "Trip started!"
                        "completed" -> {
                            stopLiveLocationBroadcast(lat, lng)
                            if (isTest) {
                                "TEST RIDE completed with ₹0 commercial impact."
                            } else {
                                "Trip completed & fare settlement recorded."
                            }
                        }
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
                .onSuccess {
                    stopLiveLocationBroadcast(booking.pickupLat, booking.pickupLng)
                    _message.value = "Trip cancelled. Frequent cancellations are reviewed by Operations."
                }
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

    override fun onCleared() {
        gpsThrottle.reset()
        super.onCleared()
    }

    private companion object {
        const val TAG = "DriverVM"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
