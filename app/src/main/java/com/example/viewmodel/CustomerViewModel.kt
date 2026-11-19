package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.Booking
import com.example.data.model.BookingCallContact
import com.example.data.model.BookingChatMessage
import com.example.data.model.BusBooking
import com.example.data.model.BusRoute
import com.example.data.model.BusSchedule
import com.example.data.model.CancellationAppeal
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.FareConfig
import com.example.data.model.PaymentRecord
import com.example.data.model.PlatformSettings
import com.example.data.model.RatingRecord
import com.example.data.model.RideOffer
import com.example.data.model.RideOtpSecret
import com.example.data.model.SampleRide
import com.example.data.model.ServiceArea
import com.example.data.model.TripLocation
import com.example.data.model.UiState
import com.example.data.repository.BusRepository
import com.example.data.repository.CommunicationRepository
import com.example.data.repository.GolocalRepository
import com.example.data.repository.PreferencesRepository
import com.example.data.repository.RealtimeLocationRepository
import com.example.data.repository.SampleRiderRepository
import com.example.domain.BookingChatValidator
import com.example.domain.FareCalculator
import com.example.domain.FareQuote
import com.example.domain.LocalSampleOtpEntry
import com.example.domain.SampleOtpLocalStore
import com.example.domain.WestBengalCatalog
import com.example.domain.WestBengalPlace
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

data class BookingDraftState(
    val pickupPlace: WestBengalPlace = WestBengalCatalog.places.first(),
    val destinationPlace: WestBengalPlace = WestBengalCatalog.places[1],
    val customPickupLabel: String = "",
    val customDestinationLabel: String = "",
    val selectedCategory: String = "toto",
    val bookingType: String = "now",
    val paymentMethod: String = "cash",
    val trainNumber: String = "",
    val scheduledDateTimeLabel: String = "",
    val familyMemberName: String = "",
    val familyConsentConfirmed: Boolean = false,
    val isRoundTrip: Boolean = false,
    val bookAsTestRide: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class CustomerViewModel(
    private val repository: GolocalRepository,
    private val prefsRepository: PreferencesRepository,
    private val currentUserId: String,
    private val customerName: String,
    private val communicationRepository: CommunicationRepository? = null,
    private val busRepository: BusRepository? = null,
    private val realtimeLocationRepository: RealtimeLocationRepository? = null,
    private val sampleRiderRepository: SampleRiderRepository? = null
) : ViewModel() {

    private val _draft = MutableStateFlow(BookingDraftState())
    val draft: StateFlow<BookingDraftState> = _draft.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _isVerifyingTestOtp = MutableStateFlow(false)
    val isVerifyingTestOtp: StateFlow<Boolean> = _isVerifyingTestOtp.asStateFlow()

    private val _testOtpError = MutableStateFlow<String?>(null)
    val testOtpError: StateFlow<String?> = _testOtpError.asStateFlow()

    private val _archiveBookingId = MutableStateFlow<String?>(null)

    val platformSettings: StateFlow<PlatformSettings?> = repository.observePlatformSettings()
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            emit(null)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val fareConfigs: StateFlow<List<FareConfig>> = repository.observeFareConfigs()
        .map { remote ->
            if (remote.isEmpty()) FareCalculator.defaultFareConfigs()
            else {
                remote.groupBy { it.vehicleCategory }
                    .mapValues { (_, list) -> list.maxByOrNull { it.version }!! }
                    .values
                    .toList()
            }
        }
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Error observing fare configs", error)
            emit(FareCalculator.defaultFareConfigs())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = FareCalculator.defaultFareConfigs()
        )

    val serviceAreas: StateFlow<List<ServiceArea>> = repository.observeServiceAreas()
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Error observing service areas", error)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val verifiedDrivers: StateFlow<List<DriverDirectoryEntry>> = repository.observeVerifiedDrivers()
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Error observing verified drivers", error)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val myBookingsState: StateFlow<UiState<List<Booking>>> = repository.observeCustomerBookings(currentUserId)
        .map<List<Booking>, UiState<List<Booking>>> { list ->
            UiState.Success(list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) })
        }
        .catch { error ->
            val fe = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (fe == null && error is CancellationException) throw error
            val root = fe ?: error
            Log.w(TAG, "Error observing customer bookings", root)
            emit(UiState.Error(root.message ?: "Unable to load rides"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    val activeBooking: StateFlow<Booking?> = myBookingsState
        .map { state ->
            (state as? UiState.Success)?.data?.firstOrNull {
                it.status in setOf("searching", "assigned", "arriving", "arrived", "in_progress")
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val mySampleRides: StateFlow<List<SampleRide>> =
        (sampleRiderRepository?.observeCustomerSampleRides(currentUserId) ?: flowOf(emptyList()))
            .catch { error ->
                if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
                emit(emptyList())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList()
            )

    val activeSampleRide: StateFlow<SampleRide?> = mySampleRides
        .map { list ->
            list.firstOrNull { it.status in setOf("incoming", "accepted", "arriving", "arrived", "in_progress") }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val localSampleOtps: StateFlow<Map<String, LocalSampleOtpEntry>> = SampleOtpLocalStore.entriesFlow

    val activeOffers: StateFlow<List<RideOffer>> = activeBooking
        .flatMapLatest { booking ->
            if (booking == null) flowOf(emptyList())
            else repository.observeOffersForBooking(booking.id, currentUserId)
                .map { offers -> offers.filter { it.status == "pending" }.sortedBy { it.proposedFareInr } }
                .catch { error ->
                    if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
                    Log.w(TAG, "Error observing offers", error)
                    emit(emptyList())
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val activeTripLocation: StateFlow<TripLocation?> = activeBooking
        .flatMapLatest { booking ->
            if (booking == null || booking.assignedDriverId.isBlank()) {
                flowOf(null)
            } else {
                val fsFlow = repository.observeTripLocation(booking.id)
                    .catch { error ->
                        if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
                        emit(null)
                    }
                val rtdbRepo = realtimeLocationRepository
                if (rtdbRepo == null) {
                    fsFlow
                } else {
                    val rtdbFlow = rtdbRepo.observeTripLocation(booking.id)
                        .catch { error ->
                            if (error is CancellationException) throw error
                            emit(null)
                        }
                    combine(rtdbFlow, fsFlow) { rtdbLoc, fsLoc ->
                        when {
                            rtdbLoc == null -> fsLoc
                            fsLoc == null -> rtdbLoc
                            else -> {
                                val rtdbMs = rtdbLoc.updatedAt?.toDate()?.time ?: 0L
                                val fsMs = fsLoc.updatedAt?.toDate()?.time ?: 0L
                                if (rtdbMs >= fsMs) rtdbLoc else fsLoc
                            }
                        }
                    }
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val activeRideOtpSecret: StateFlow<RideOtpSecret?> = activeBooking
        .flatMapLatest { booking ->
            if (booking == null || booking.assignedDriverId.isBlank()) {
                flowOf(null)
            } else {
                repository.observeCustomerRideOtpSecret(booking.id)
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

    val activeChatMessages: StateFlow<List<BookingChatMessage>> = activeBooking
        .flatMapLatest { booking ->
            val comm = communicationRepository
            if (comm == null ||
                booking == null ||
                booking.isTestRide ||
                booking.testMode ||
                booking.assignedDriverId.isBlank() ||
                !BookingChatValidator.isChatReadAllowed(booking.status)
            ) {
                flowOf(emptyList())
            } else {
                comm.observeMessages(booking.id)
                    .catch { error ->
                        if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
                        Log.w(TAG, "Error observing active booking chat", error)
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
        .map { msgs -> msgs.count { it.senderId != currentUserId && !it.readByCustomer } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = 0
        )

    val activeCallContact: StateFlow<BookingCallContact?> = activeBooking
        .flatMapLatest { booking ->
            val comm = communicationRepository
            if (comm == null ||
                booking == null ||
                booking.isTestRide ||
                booking.testMode ||
                booking.assignedDriverId.isBlank() ||
                !BookingChatValidator.isChatWriteAllowed(booking.status)
            ) {
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

    val archivedChatMessages: StateFlow<List<BookingChatMessage>> = _archiveBookingId
        .flatMapLatest { bid ->
            val comm = communicationRepository
            if (comm == null || bid.isNullOrBlank()) flowOf(emptyList())
            else comm.observeMessages(bid).catch { emit(emptyList()) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val myPayments: StateFlow<List<PaymentRecord>> = repository.observeCustomerPayments(currentUserId)
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Error observing payments", error)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val myRatings: StateFlow<List<RatingRecord>> = repository.observeCustomerRatings(currentUserId)
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            Log.w(TAG, "Error observing customer ratings", error)
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

    val activeBusRoutes: StateFlow<List<BusRoute>> = (busRepository?.observeActiveBusRoutes() ?: flowOf(emptyList()))
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val busSchedules: StateFlow<List<BusSchedule>> = (busRepository?.observeBusSchedules() ?: flowOf(emptyList()))
        .catch { error ->
            if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val myBusBookings: StateFlow<List<BusBooking>> = (busRepository?.observeCustomerBusBookings(currentUserId) ?: flowOf(emptyList()))
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

    val currentQuote: StateFlow<FareQuote> = combine(_draft, fareConfigs) { draft, configs ->
        val config = configs.firstOrNull { it.vehicleCategory == draft.selectedCategory }
            ?: FareCalculator.defaultFareConfigs().first { it.vehicleCategory == draft.selectedCategory }
        val distanceKm = FareCalculator.haversineDistanceKm(
            draft.pickupPlace.lat,
            draft.pickupPlace.lng,
            draft.destinationPlace.lat,
            draft.destinationPlace.lng
        )
        FareCalculator.calculateFareQuote(distanceKm, config, draft.isRoundTrip)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = FareCalculator.calculateFareQuote(
            4.5,
            FareCalculator.defaultFareConfigs().first { it.vehicleCategory == "toto" }
        )
    )

    fun setPickupPlace(place: WestBengalPlace) {
        _draft.value = _draft.value.copy(pickupPlace = place, customPickupLabel = "")
        persistDraft()
    }

    fun setDestinationPlace(place: WestBengalPlace) {
        _draft.value = _draft.value.copy(destinationPlace = place, customDestinationLabel = "")
        persistDraft()
    }

    fun setPickupCoordinates(label: String, lat: Double, lng: Double, district: String = "West Bengal") {
        val cleanLabel = label.trim().ifBlank { "Selected Pickup" }
        val customPlace = WestBengalPlace(
            id = "custom_pickup_${(lat * 1000).toInt()}_${(lng * 1000).toInt()}",
            nameEn = cleanLabel,
            nameBn = cleanLabel,
            district = district.ifBlank { "West Bengal" },
            block = "",
            category = "custom",
            lat = lat,
            lng = lng
        )
        _draft.value = _draft.value.copy(pickupPlace = customPlace, customPickupLabel = cleanLabel)
    }

    fun setDestinationCoordinates(label: String, lat: Double, lng: Double, district: String = "West Bengal") {
        val cleanLabel = label.trim().ifBlank { "Selected Destination" }
        val customPlace = WestBengalPlace(
            id = "custom_dest_${(lat * 1000).toInt()}_${(lng * 1000).toInt()}",
            nameEn = cleanLabel,
            nameBn = cleanLabel,
            district = district.ifBlank { "West Bengal" },
            block = "",
            category = "custom",
            lat = lat,
            lng = lng
        )
        _draft.value = _draft.value.copy(destinationPlace = customPlace, customDestinationLabel = cleanLabel)
    }

    fun swapLocations() {
        val current = _draft.value
        _draft.value = current.copy(
            pickupPlace = current.destinationPlace,
            destinationPlace = current.pickupPlace,
            customPickupLabel = current.customDestinationLabel,
            customDestinationLabel = current.customPickupLabel
        )
    }

    fun setCustomLabels(pickup: String, destination: String) {
        _draft.value = _draft.value.copy(
            customPickupLabel = pickup,
            customDestinationLabel = destination
        )
    }

    fun selectCategory(category: String) {
        _draft.value = _draft.value.copy(selectedCategory = category)
        persistDraft()
    }

    fun setBookingType(type: String) {
        _draft.value = _draft.value.copy(
            bookingType = type,
            isRoundTrip = type == "return_trip" || _draft.value.isRoundTrip
        )
    }

    fun setPaymentMethod(method: String) {
        _draft.value = _draft.value.copy(paymentMethod = method)
    }

    fun updateSpecialFields(
        trainNumber: String = _draft.value.trainNumber,
        scheduledDateTimeLabel: String = _draft.value.scheduledDateTimeLabel,
        familyMemberName: String = _draft.value.familyMemberName,
        familyConsentConfirmed: Boolean = _draft.value.familyConsentConfirmed,
        isRoundTrip: Boolean = _draft.value.isRoundTrip
    ) {
        _draft.value = _draft.value.copy(
            trainNumber = trainNumber,
            scheduledDateTimeLabel = scheduledDateTimeLabel,
            familyMemberName = familyMemberName,
            familyConsentConfirmed = familyConsentConfirmed,
            isRoundTrip = isRoundTrip
        )
    }

    fun setBookAsTestRide(enabled: Boolean) {
        _draft.value = _draft.value.copy(bookAsTestRide = enabled)
    }

    fun restoreDraftFromPreferences(pickupId: String, destId: String, category: String) {
        val pickup = WestBengalCatalog.places.firstOrNull { it.id == pickupId }
        val dest = WestBengalCatalog.places.firstOrNull { it.id == destId }
        _draft.value = _draft.value.copy(
            pickupPlace = pickup ?: _draft.value.pickupPlace,
            destinationPlace = dest ?: _draft.value.destinationPlace,
            selectedCategory = category.ifBlank { _draft.value.selectedCategory }
        )
    }

    private fun persistDraft() {
        viewModelScope.launch {
            prefsRepository.saveDraftBooking(
                _draft.value.pickupPlace.id,
                _draft.value.destinationPlace.id,
                _draft.value.selectedCategory
            )
        }
    }

    fun submitBooking(
        bookingChannel: String = "app",
        invoiceEmail: String = "",
        isAuthorizedTestCustomer: Boolean = false
    ) {
        val d = _draft.value
        val cfg = fareConfigs.value.firstOrNull { it.vehicleCategory == d.selectedCategory }
        if (d.selectedCategory == "parcel" && cfg?.isEnabled != true) {
            _actionMessage.value = "Parcel Delivery requires separate West Bengal regulatory & admin approval."
            return
        }
        val pickupLabel = d.customPickupLabel.ifBlank { d.pickupPlace.nameEn }
        val destLabel = d.customDestinationLabel.ifBlank { d.destinationPlace.nameEn }
        if (pickupLabel == destLabel) {
            _actionMessage.value = "Pickup and destination cannot be the same location."
            return
        }
        if (d.bookingType == "family" && (!d.familyConsentConfirmed || d.familyMemberName.isBlank())) {
            _actionMessage.value = "Please enter the family member name and confirm passenger consent."
            return
        }
        if ((d.bookingType == "scheduled" || d.bookingType == "station_pickup" || d.bookingType == "native_village") &&
            d.scheduledDateTimeLabel.isBlank()
        ) {
            _actionMessage.value = "Please provide your arrival date and pickup time."
            return
        }

        val globalTestModeEnabled = platformSettings.value?.testRideModeEnabled == true
        if (d.bookAsTestRide && !isAuthorizedTestCustomer) {
            _actionMessage.value = "Only Admin-authorized test customer accounts can create Test Ride bookings."
            return
        }
        if (d.bookAsTestRide && !globalTestModeEnabled) {
            _actionMessage.value = "Test Ride Mode is currently disabled by Owner Admin."
            return
        }

        val quote = currentQuote.value
        val isTestRideActive = globalTestModeEnabled && isAuthorizedTestCustomer && d.bookAsTestRide
        _isSubmitting.value = true
        viewModelScope.launch {
            val booking = Booking(
                customerId = currentUserId,
                customerName = customerName,
                bookingChannel = bookingChannel,
                bookingType = d.bookingType,
                vehicleCategory = d.selectedCategory,
                pickupLabel = pickupLabel,
                pickupDistrict = d.pickupPlace.district,
                pickupLat = d.pickupPlace.lat,
                pickupLng = d.pickupPlace.lng,
                destinationLabel = destLabel,
                destinationDistrict = d.destinationPlace.district,
                destinationLat = d.destinationPlace.lat,
                destinationLng = d.destinationPlace.lng,
                distanceKm = quote.distanceKm,
                estimatedDurationMin = quote.estimatedDurationMin,
                estimatedFareInr = quote.estimatedFareInr,
                minPermittedFareInr = quote.minPermittedFareInr,
                maxPermittedFareInr = quote.maxPermittedFareInr,
                fareConfigVersion = quote.fareConfigVersion,
                commissionPercent = quote.commissionPercent,
                gstPercent = quote.gstPercent,
                paymentMethod = d.paymentMethod,
                trainNumber = d.trainNumber,
                scheduledDateTimeLabel = d.scheduledDateTimeLabel,
                familyMemberName = d.familyMemberName,
                familyConsentConfirmed = d.familyConsentConfirmed,
                isRoundTrip = d.isRoundTrip,
                invoiceEmail = invoiceEmail.trim(),
                isTestRide = isTestRideActive,
                testMode = isTestRideActive,
                testOtpStatus = if (isTestRideActive) "awaiting_assignment" else ""
            )
            repository.createBooking(booking)
                .onSuccess { id ->
                    prefsRepository.clearDraftBooking()
                    _draft.value = _draft.value.copy(bookAsTestRide = false)
                    _actionMessage.value = if (isTestRideActive) {
                        "TEST RIDE #$id created. Waiting for Admin/Ops Test Driver assignment."
                    } else {
                        "Booking #$id created. Verified local drivers can now submit fare proposals."
                    }
                }
                .onFailure { exc ->
                    _actionMessage.value = exc.localizedMessage ?: "Failed to create booking"
                }
            _isSubmitting.value = false
        }
    }

    fun verifyTestRideOtp(booking: Booking, enteredOtp: String) {
        if (_isVerifyingTestOtp.value) return
        _isVerifyingTestOtp.value = true
        _testOtpError.value = null
        viewModelScope.launch {
            repository.verifyTestRideOtpByCustomer(booking, enteredOtp)
                .onSuccess {
                    _testOtpError.value = null
                    _actionMessage.value = "TEST OTP verified! Simulated test trip started."
                }
                .onFailure { exc ->
                    _testOtpError.value = exc.localizedMessage ?: "Invalid or expired Test OTP."
                }
            _isVerifyingTestOtp.value = false
        }
    }

    fun clearTestOtpError() {
        _testOtpError.value = null
    }

    fun confirmOffer(bookingId: String, offer: RideOffer) {
        viewModelScope.launch {
            repository.confirmOfferAtomically(bookingId, offer)
                .onSuccess {
                    _actionMessage.value = "Ride confirmed with ${offer.driverName} at ₹${offer.proposedFareInr.toInt()}. Open Active Ride to view your 6-digit Pickup OTP."
                }
                .onFailure { exc ->
                    _actionMessage.value = exc.localizedMessage ?: "Unable to confirm offer"
                }
        }
    }

    fun cancelBooking(bookingId: String, reason: String, category: String = "") {
        viewModelScope.launch {
            repository.cancelBooking(bookingId, reason, cancelledByRole = "customer", cancellationCategory = category)
                .onSuccess { _actionMessage.value = "Booking cancelled." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Cancellation failed" }
        }
    }

    fun submitRating(
        booking: Booking,
        stars: Int,
        review: String,
        driverBehaviorRating: Int = stars,
        vehicleConditionRating: Int = stars,
        punctualityRating: Int = stars,
        safetyRating: Int = stars
    ) {
        viewModelScope.launch {
            repository.submitRating(
                booking = booking,
                stars = stars,
                review = review,
                driverBehaviorRating = driverBehaviorRating,
                vehicleConditionRating = vehicleConditionRating,
                punctualityRating = punctualityRating,
                safetyRating = safetyRating
            )
                .onSuccess { _actionMessage.value = "Thank you for submitting your trip rating!" }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to save rating" }
        }
    }

    fun submitAppeal(bookingId: String, reason: String) {
        viewModelScope.launch {
            repository.submitCancellationAppeal(bookingId, "customer", reason)
                .onSuccess { _actionMessage.value = "Cancellation appeal submitted to GoLocal Support." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to submit appeal" }
        }
    }

    fun sendChatMessage(booking: Booking, text: String) {
        val comm = communicationRepository ?: return
        viewModelScope.launch {
            comm.sendMessage(booking, "customer", customerName, text)
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Message failed to send" }
        }
    }

    fun markActiveChatRead() {
        val comm = communicationRepository ?: return
        val booking = activeBooking.value ?: return
        val msgs = activeChatMessages.value
        if (msgs.isEmpty()) return
        viewModelScope.launch {
            comm.markMessagesRead(booking.id, "customer", msgs)
        }
    }

    fun openChatArchive(bookingId: String?) {
        _archiveBookingId.value = bookingId
    }

    fun syncMyContactForActiveBooking(phone: String) {
        val comm = communicationRepository ?: return
        val booking = activeBooking.value ?: return
        if (phone.isBlank()) return
        viewModelScope.launch {
            comm.publishParticipantContactForActiveTrip(booking, phone, customerName, isDriver = false)
        }
    }

    fun bookBusSeats(schedule: BusSchedule, selectedSeats: List<String>, paymentMethod: String, onBooked: (BusBooking) -> Unit = {}) {
        val busRepo = busRepository ?: return
        viewModelScope.launch {
            _isSubmitting.value = true
            busRepo.bookSeatsAtomically(schedule, customerName, selectedSeats, paymentMethod)
                .onSuccess { ticket ->
                    _actionMessage.value = "Ticket ${ticket.bookingRef} confirmed for seats ${ticket.seatNumbers.joinToString()}."
                    onBooked(ticket)
                }
                .onFailure { exc ->
                    _actionMessage.value = exc.localizedMessage ?: "Seat booking failed"
                }
            _isSubmitting.value = false
        }
    }

    fun cancelBusBooking(booking: BusBooking, reason: String) {
        val busRepo = busRepository ?: return
        viewModelScope.launch {
            busRepo.cancelBusBooking(booking, reason)
                .onSuccess { _actionMessage.value = "Bus ticket ${booking.bookingRef} cancelled." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Bus cancellation failed" }
        }
    }

    fun triggerSos(bookingId: String, lat: Double, lng: Double, notes: String) {
        viewModelScope.launch {
            repository.triggerEmergencySos(bookingId, lat, lng, notes)
                .onSuccess { eventId ->
                    _actionMessage.value = "SOS Alert #$eventId recorded & escalated to GOLOCAL Safety Desk."
                }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to send SOS" }
        }
    }

    fun submitComplaint(bookingId: String, category: String, description: String) {
        if (description.trim().length < 5) {
            _actionMessage.value = "Please describe the issue (at least 5 characters)."
            return
        }
        viewModelScope.launch {
            repository.submitComplaint(bookingId, category, description)
                .onSuccess { _actionMessage.value = "Complaint submitted for review." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to submit complaint" }
        }
    }

    fun requestRefund(bookingId: String, amountInr: Double, reason: String) {
        if (reason.trim().length < 3) {
            _actionMessage.value = "Please enter a refund reason."
            return
        }
        viewModelScope.launch {
            repository.requestRefund(bookingId, amountInr, reason)
                .onSuccess { _actionMessage.value = "Refund request submitted for reconciliation." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Refund request failed" }
        }
    }

    fun submitSampleRideBooking(
        context: android.content.Context,
        quote: FareQuote,
        pickupLabel: String,
        pickupLat: Double,
        pickupLng: Double,
        destinationLabel: String,
        destinationLat: Double,
        destinationLng: Double,
        vehicleCategory: String,
        onCreated: () -> Unit = {}
    ) {
        val sampleRepo = sampleRiderRepository ?: return
        viewModelScope.launch {
            _isSubmitting.value = true
            sampleRepo.createSampleRideWithHashedOtp(
                context = context,
                customerId = currentUserId,
                customerName = customerName,
                createdByRole = "customer",
                vehicleCategory = vehicleCategory,
                pickupLabel = pickupLabel,
                pickupLat = pickupLat,
                pickupLng = pickupLng,
                destinationLabel = destinationLabel,
                destinationLat = destinationLat,
                destinationLng = destinationLng,
                distanceKm = quote.distanceKm,
                estimatedDurationMin = (quote.distanceKm * 3.0).toInt().coerceAtLeast(3),
                estimatedFareInr = quote.estimatedFareInr,
                requestTtlSeconds = 180L
            ).onSuccess { res ->
                _actionMessage.value = "Sample Ride dispatched! Local 6-digit OTP: ${res.localOtp}"
                onCreated()
            }.onFailure { exc ->
                _actionMessage.value = exc.localizedMessage ?: "Failed creating Sample Ride."
            }
            _isSubmitting.value = false
        }
    }

    fun cancelSampleRide(rideId: String, reason: String = "Cancelled by customer") {
        val sampleRepo = sampleRiderRepository ?: return
        viewModelScope.launch {
            sampleRepo.advanceSampleRideStatus(rideId, "cancelled", cancellationReason = reason)
                .onSuccess { _actionMessage.value = "Sample Ride cancelled." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Unable to cancel Sample Ride." }
        }
    }

    fun clearMessage() {
        _actionMessage.value = null
    }

    private companion object {
        const val TAG = "CustomerVM"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
