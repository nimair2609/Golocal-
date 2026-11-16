package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.Booking
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.FareConfig
import com.example.data.model.PaymentRecord
import com.example.data.model.RideOffer
import com.example.data.model.ServiceArea
import com.example.data.model.TripLocation
import com.example.data.model.UiState
import com.example.data.repository.GolocalRepository
import com.example.data.repository.PreferencesRepository
import com.example.domain.FareCalculator
import com.example.domain.FareQuote
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
    private val customerName: String
) : ViewModel() {

    private val _draft = MutableStateFlow(BookingDraftState())
    val draft: StateFlow<BookingDraftState> = _draft.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    val fareConfigs: StateFlow<List<FareConfig>> = repository.observeFareConfigs()
        .map { remote ->
            if (remote.isEmpty()) FareCalculator.defaultFareConfigs()
            else {
                // Keep latest version per category
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

    fun submitBooking(bookingChannel: String = "app", invoiceEmail: String = "") {
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

        val quote = currentQuote.value
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
                invoiceEmail = invoiceEmail.trim()
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

    fun clearMessage() {
        _actionMessage.value = null
    }

    private companion object {
        const val TAG = "CustomerVM"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
