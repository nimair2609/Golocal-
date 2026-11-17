package com.example.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.map.GeocodedPlace
import com.example.data.map.GeocodingSearchAdapter
import com.example.data.map.MapProviderConfig
import com.example.data.map.OsrmRoutingAdapter
import com.example.data.map.RouteResult
import com.example.data.model.Booking
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.FareConfig
import com.example.data.model.LiveDriverRtdbLocation
import com.example.data.model.PaymentRecord
import com.example.data.model.RideOffer
import com.example.data.model.ServiceArea
import com.example.data.model.TripLocation
import com.example.data.model.UiState
import com.example.data.repository.GolocalRepository
import com.example.data.repository.PreferencesRepository
import com.example.data.tracking.RealtimeTrackingRepository
import com.example.domain.BoraiLaunchConfig
import com.example.domain.FareCalculator
import com.example.domain.FareQuote
import com.example.domain.ServiceCoverageEvaluation
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookingDraftState(
    val pickupPlace: WestBengalPlace = BoraiLaunchConfig.defaultPlace,
    val destinationPlace: WestBengalPlace = WestBengalCatalog.places.getOrElse(1) { BoraiLaunchConfig.defaultPlace },
    val customPickupLabel: String = "",
    val customDestinationLabel: String = "",
    val pickupLat: Double = BoraiLaunchConfig.DEFAULT_LAT,
    val pickupLng: Double = BoraiLaunchConfig.DEFAULT_LNG,
    val destLat: Double = WestBengalCatalog.places.getOrElse(1) { BoraiLaunchConfig.defaultPlace }.lat,
    val destLng: Double = WestBengalCatalog.places.getOrElse(1) { BoraiLaunchConfig.defaultPlace }.lng,
    val pickupDistrict: String = BoraiLaunchConfig.DEFAULT_DISTRICT,
    val destinationDistrict: String = WestBengalCatalog.places.getOrElse(1) { BoraiLaunchConfig.defaultPlace }.district,
    val pickupConfirmed: Boolean = false,
    val destinationConfirmed: Boolean = false,
    val isPickupManualPin: Boolean = false,
    val isDestinationManualPin: Boolean = false,
    val selectedCategory: String = "toto",
    val bookingType: String = "now", // now, scheduled, station_pickup, native_village, family, return_trip
    val paymentMethod: String = "cash", // cash, upi
    val trainNumber: String = "",
    val scheduledDateTimeLabel: String = "",
    val familyMemberName: String = "",
    val familyConsentConfirmed: Boolean = false,
    val isRoundTrip: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class CustomerViewModel(
    private val repository: GolocalRepository,
    private val prefsRepository: PreferencesRepository,
    private val currentUserId: String,
    private val customerName: String,
    private var osrmAdapter: OsrmRoutingAdapter = OsrmRoutingAdapter(),
    private var geocodingAdapter: GeocodingSearchAdapter = GeocodingSearchAdapter(),
    private var realtimeTrackingRepository: RealtimeTrackingRepository? = null
) : ViewModel() {

    private val _draft = MutableStateFlow(BookingDraftState())
    val draft: StateFlow<BookingDraftState> = _draft.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _searchResults = MutableStateFlow<List<GeocodedPlace>>(
        geocodingAdapter.searchLocalOnly("")
    )
    val searchResults: StateFlow<List<GeocodedPlace>> = _searchResults.asStateFlow()

    private val _isSearchingPlaces = MutableStateFlow(false)
    val isSearchingPlaces: StateFlow<Boolean> = _isSearchingPlaces.asStateFlow()

    fun attachContext(context: Context) {
        val endpoints = MapProviderConfig.loadEndpoints(context)
        osrmAdapter = OsrmRoutingAdapter(endpoints = endpoints)
        geocodingAdapter = GeocodingSearchAdapter(endpoints = endpoints)
        if (realtimeTrackingRepository == null) {
            realtimeTrackingRepository = RealtimeTrackingRepository(context.applicationContext)
        }
    }

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
            if (booking == null || booking.assignedDriverId.isBlank()) flowOf(null)
            else repository.observeTripLocation(booking.id)
                .catch { error ->
                    if (error is CancellationException && error.cause !is FirebaseFirestoreException) throw error
                    emit(null)
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val activeLiveTracking: StateFlow<LiveDriverRtdbLocation?> = activeBooking
        .flatMapLatest { booking ->
            if (booking == null || booking.assignedDriverId.isBlank()) {
                flowOf(null)
            } else {
                val rtdbFlow = realtimeTrackingRepository?.observeTripLiveLocation(booking.id)
                    ?: flowOf(null)
                combine(rtdbFlow, activeTripLocation) { rtdb, fsLoc ->
                    when {
                        rtdb != null -> rtdb
                        fsLoc != null -> LiveDriverRtdbLocation(
                            driverId = fsLoc.driverId,
                            bookingId = fsLoc.bookingId,
                            customerId = fsLoc.customerId,
                            lat = fsLoc.lat,
                            lng = fsLoc.lng,
                            trackingStatus = "active",
                            updatedAtEpochMs = (fsLoc.updatedAt?.seconds ?: 0L) * 1000L
                        )
                        else -> null
                    }
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val activeBookingRoute: StateFlow<RouteResult?> = activeBooking
        .distinctUntilChanged { old, new ->
            old?.id == new?.id && old?.pickupLat == new?.pickupLat && old?.destinationLat == new?.destinationLat
        }
        .flatMapLatest { booking ->
            if (booking == null) flowOf<RouteResult?>(null)
            else flow<RouteResult?> {
                emit(
                    osrmAdapter.fetchRoute(
                        startLat = booking.pickupLat,
                        startLng = booking.pickupLng,
                        endLat = booking.destinationLat,
                        endLng = booking.destinationLng,
                        vehicleCategory = booking.vehicleCategory
                    )
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val routeResult: StateFlow<RouteResult?> = _draft
        .map { d -> RouteKey(d.pickupLat, d.pickupLng, d.destLat, d.destLng, d.selectedCategory) }
        .distinctUntilChanged()
        .flatMapLatest { key ->
            flow {
                emit(
                    osrmAdapter.fetchRoute(
                        startLat = key.pLat,
                        startLng = key.pLng,
                        endLat = key.dLat,
                        endLng = key.dLng,
                        vehicleCategory = key.category
                    )
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val coverageEvaluation: StateFlow<ServiceCoverageEvaluation> = combine(
        _draft,
        serviceAreas
    ) { d, zones ->
        BoraiLaunchConfig.evaluateTripCoverage(
            pickupLat = d.pickupLat,
            pickupLng = d.pickupLng,
            dropLat = d.destLat,
            dropLng = d.destLng,
            vehicleCategory = d.selectedCategory,
            serviceAreas = zones
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = ServiceCoverageEvaluation(isAllowed = true)
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

    val myRatings: StateFlow<List<com.example.data.model.RatingRecord>> = repository.observeCustomerRatings(currentUserId)
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

    val currentQuote: StateFlow<FareQuote> = combine(_draft, fareConfigs, routeResult) { draft, configs, route ->
        val config = configs.firstOrNull { it.vehicleCategory == draft.selectedCategory }
            ?: FareCalculator.defaultFareConfigs().first { it.vehicleCategory == draft.selectedCategory }
        val distanceKm = route?.distanceKm ?: FareCalculator.haversineDistanceKm(
            draft.pickupLat,
            draft.pickupLng,
            draft.destLat,
            draft.destLng
        )
        val baseQuote = FareCalculator.calculateFareQuote(distanceKm, config, draft.isRoundTrip)
        if (route != null) {
            val dur = if (draft.isRoundTrip) (route.durationMin * 1.85).toLong() else route.durationMin
            baseQuote.copy(estimatedDurationMin = dur.coerceAtLeast(2L))
        } else {
            baseQuote
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = FareCalculator.calculateFareQuote(
            3.5,
            FareCalculator.defaultFareConfigs().first { it.vehicleCategory == "toto" }
        )
    )

    fun searchPlaces(query: String, allowRemote: Boolean = false) {
        val zones = serviceAreas.value
        val localNow = geocodingAdapter.searchLocalOnly(query, zones)
        _searchResults.value = localNow
        if (!allowRemote || query.trim().length < 3) return
        viewModelScope.launch {
            _isSearchingPlaces.value = true
            _searchResults.value = geocodingAdapter.searchPlaces(query, zones, allowRemoteLookup = true)
            _isSearchingPlaces.value = false
        }
    }

    fun selectGeocodedPlace(place: GeocodedPlace, isPickup: Boolean) {
        if (isPickup) {
            _draft.value = _draft.value.copy(
                pickupLat = place.lat,
                pickupLng = place.lng,
                customPickupLabel = place.displayName,
                pickupDistrict = place.district.ifBlank { BoraiLaunchConfig.DEFAULT_DISTRICT },
                isPickupManualPin = true,
                pickupConfirmed = true
            )
        } else {
            _draft.value = _draft.value.copy(
                destLat = place.lat,
                destLng = place.lng,
                customDestinationLabel = place.displayName,
                destinationDistrict = place.district.ifBlank { BoraiLaunchConfig.DEFAULT_DISTRICT },
                isDestinationManualPin = true,
                destinationConfirmed = true
            )
        }
    }

    fun setPickupCoordinates(
        lat: Double,
        lng: Double,
        explicitLabel: String? = null,
        confirmImmediately: Boolean = false
    ) {
        val initialLabel = explicitLabel?.takeIf { it.isNotBlank() }
            ?: String.format("Pinned Pickup (%.4f, %.4f)", lat, lng)
        _draft.value = _draft.value.copy(
            pickupLat = lat,
            pickupLng = lng,
            customPickupLabel = initialLabel,
            isPickupManualPin = true,
            pickupConfirmed = confirmImmediately
        )
        if (explicitLabel.isNullOrBlank()) {
            viewModelScope.launch {
                val resolved = geocodingAdapter.reverseGeocode(
                    lat = lat,
                    lng = lng,
                    serviceAreas = serviceAreas.value,
                    allowRemoteLookup = true
                )
                if (_draft.value.pickupLat == lat && _draft.value.pickupLng == lng) {
                    _draft.value = _draft.value.copy(customPickupLabel = resolved)
                }
            }
        }
    }

    fun setDestinationCoordinates(
        lat: Double,
        lng: Double,
        explicitLabel: String? = null,
        confirmImmediately: Boolean = false
    ) {
        val initialLabel = explicitLabel?.takeIf { it.isNotBlank() }
            ?: String.format("Pinned Destination (%.4f, %.4f)", lat, lng)
        _draft.value = _draft.value.copy(
            destLat = lat,
            destLng = lng,
            customDestinationLabel = initialLabel,
            isDestinationManualPin = true,
            destinationConfirmed = confirmImmediately
        )
        if (explicitLabel.isNullOrBlank()) {
            viewModelScope.launch {
                val resolved = geocodingAdapter.reverseGeocode(
                    lat = lat,
                    lng = lng,
                    serviceAreas = serviceAreas.value,
                    allowRemoteLookup = true
                )
                if (_draft.value.destLat == lat && _draft.value.destLng == lng) {
                    _draft.value = _draft.value.copy(customDestinationLabel = resolved)
                }
            }
        }
    }

    fun confirmPickup(confirmed: Boolean = true) {
        _draft.value = _draft.value.copy(pickupConfirmed = confirmed)
    }

    fun confirmDestination(confirmed: Boolean = true) {
        _draft.value = _draft.value.copy(destinationConfirmed = confirmed)
    }

    fun setPickupPlace(place: WestBengalPlace) {
        _draft.value = _draft.value.copy(
            pickupPlace = place,
            pickupLat = place.lat,
            pickupLng = place.lng,
            pickupDistrict = place.district,
            customPickupLabel = "",
            isPickupManualPin = false,
            pickupConfirmed = true
        )
        persistDraft()
    }

    fun setDestinationPlace(place: WestBengalPlace) {
        _draft.value = _draft.value.copy(
            destinationPlace = place,
            destLat = place.lat,
            destLng = place.lng,
            destinationDistrict = place.district,
            customDestinationLabel = "",
            isDestinationManualPin = false,
            destinationConfirmed = true
        )
        persistDraft()
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

    fun restoreDraftFromPreferences(pickupId: String, destId: String, category: String) {
        val pickup = WestBengalCatalog.places.firstOrNull { it.id == pickupId }
        val dest = WestBengalCatalog.places.firstOrNull { it.id == destId }
        _draft.value = _draft.value.copy(
            pickupPlace = pickup ?: _draft.value.pickupPlace,
            pickupLat = pickup?.lat ?: _draft.value.pickupLat,
            pickupLng = pickup?.lng ?: _draft.value.pickupLng,
            pickupDistrict = pickup?.district ?: _draft.value.pickupDistrict,
            destinationPlace = dest ?: _draft.value.destinationPlace,
            destLat = dest?.lat ?: _draft.value.destLat,
            destLng = dest?.lng ?: _draft.value.destLng,
            destinationDistrict = dest?.district ?: _draft.value.destinationDistrict,
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

    fun submitBooking(bookingChannel: String = "app", invoiceEmail: String = "") {
        val d = _draft.value
        val cfg = fareConfigs.value.firstOrNull { it.vehicleCategory == d.selectedCategory }
        if (d.selectedCategory == "parcel" && cfg?.isEnabled != true) {
            _actionMessage.value = "Parcel Delivery requires separate West Bengal regulatory & admin approval."
            return
        }
        val coverage = coverageEvaluation.value
        if (!coverage.isAllowed) {
            _actionMessage.value = coverage.blockingReason.ifBlank {
                "Trip is outside active Owner-configured service coverage."
            }
            return
        }
        val pickupLabel = d.customPickupLabel.ifBlank { d.pickupPlace.nameEn }
        val destLabel = d.customDestinationLabel.ifBlank { d.destinationPlace.nameEn }
        if (pickupLabel == destLabel &&
            FareCalculator.haversineKm(d.pickupLat, d.pickupLng, d.destLat, d.destLng) < 0.08
        ) {
            _actionMessage.value = "Pickup and destination cannot be the exact same location."
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

        val quote = currentQuote.value
        val route = routeResult.value
        _isSubmitting.value = true
        viewModelScope.launch {
            val booking = Booking(
                customerId = currentUserId,
                customerName = customerName,
                bookingChannel = bookingChannel,
                bookingType = d.bookingType,
                vehicleCategory = d.selectedCategory,
                pickupLabel = pickupLabel,
                pickupDistrict = d.pickupDistrict,
                pickupLat = d.pickupLat,
                pickupLng = d.pickupLng,
                destinationLabel = destLabel,
                destinationDistrict = d.destinationDistrict,
                destinationLat = d.destLat,
                destinationLng = d.destLng,
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
                routeProvider = route?.provider ?: "haversine_fallback",
                pickupZoneId = coverage.pickupZone?.id.orEmpty(),
                dropoffZoneId = coverage.dropoffZone?.id.orEmpty()
            )
            repository.createBooking(booking)
                .onSuccess { id ->
                    prefsRepository.clearDraftBooking()
                    _actionMessage.value = "Booking #$id created. Verified local drivers can now submit fare proposals."
                }
                .onFailure { exc ->
                    _actionMessage.value = exc.localizedMessage ?: "Failed to create booking"
                }
            _isSubmitting.value = false
        }
    }

    fun confirmOffer(bookingId: String, offer: RideOffer) {
        viewModelScope.launch {
            repository.confirmOfferAtomically(bookingId, offer)
                .onSuccess { pin ->
                    _actionMessage.value = "Ride confirmed with ${offer.driverName} at ₹${offer.proposedFareInr.toInt()}. Share Trip PIN $pin at pickup."
                }
                .onFailure { exc ->
                    _actionMessage.value = exc.localizedMessage ?: "Unable to confirm offer"
                }
        }
    }

    fun cancelBooking(bookingId: String, reason: String) {
        viewModelScope.launch {
            repository.cancelBooking(bookingId, reason)
                .onSuccess { _actionMessage.value = "Booking cancelled." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Cancellation failed" }
        }
    }

    fun submitRating(booking: Booking, stars: Int, review: String) {
        viewModelScope.launch {
            repository.submitRating(booking, stars, review)
                .onSuccess { _actionMessage.value = "Thank you for rating your trip!" }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to save rating" }
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

    fun submitComplaint(
        bookingId: String,
        category: String,
        description: String,
        lat: Double? = null,
        lng: Double? = null,
        locationLabel: String = ""
    ) {
        if (description.trim().length < 5) {
            _actionMessage.value = "Please describe the issue (at least 5 characters)."
            return
        }
        viewModelScope.launch {
            repository.submitComplaint(bookingId, category, description, lat, lng, locationLabel)
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

    fun clearMessage() {
        _actionMessage.value = null
    }

    private data class RouteKey(
        val pLat: Double,
        val pLng: Double,
        val dLat: Double,
        val dLng: Double,
        val category: String
    )

    private companion object {
        const val TAG = "CustomerVM"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
