package com.example.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.Booking
import com.example.data.model.DisputeRecord
import com.example.data.model.DriverDocumentItem
import com.example.data.model.RatingRecord
import com.example.data.repository.GolocalGovernanceRepository
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GovernanceViewModel(
    private val repository: GolocalGovernanceRepository,
    private val currentUserId: String,
    private val role: String // customer, driver, admin
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    val myDisputes: StateFlow<List<DisputeRecord>> = if (role != "admin") {
        repository.observeMyDisputes(currentUserId)
            .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
            .catch { }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    } else {
        MutableStateFlow<List<DisputeRecord>>(emptyList()).asStateFlow()
    }

    val allDisputes: StateFlow<List<DisputeRecord>> = if (role == "admin") {
        repository.observeAllDisputes()
            .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
            .catch { }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    } else {
        MutableStateFlow<List<DisputeRecord>>(emptyList()).asStateFlow()
    }

    val allRatings: StateFlow<List<RatingRecord>> = repository.observeAllRatings()
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    fun clearMessage() {
        _message.value = null
    }

    suspend fun uploadDocumentPhoto(folder: String, docType: String, uri: Uri): Result<String> {
        return repository.uploadDocumentImage(folder, currentUserId, docType, uri)
    }

    fun submitStructuredDriverKyc(
        legalName: String,
        phone: String,
        address: String,
        district: String,
        block: String,
        vehicleCategory: String,
        vehicleModel: String,
        vehicleRegNumber: String,
        settlementUpiOrBank: String,
        documents: List<DriverDocumentItem>
    ) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            repository.submitDriverDocumentsKyc(
                legalName, phone, address, district, block,
                vehicleCategory, vehicleModel, vehicleRegNumber, settlementUpiOrBank, documents
            ).onSuccess {
                _isBusy.value = false
                _message.value = "Structured KYC documents submitted cleanly for Admin verification."
            }.onFailure { err ->
                _isBusy.value = false
                _message.value = err.localizedMessage ?: "KYC submission failed."
            }
        }
    }

    fun adminReviewDriverKyc(driverId: String, decisionStatus: String, reason: String) {
        viewModelScope.launch {
            repository.adminReviewDriverDocuments(driverId, decisionStatus, reason)
                .onSuccess {
                    _message.value = "Driver KYC set to ${decisionStatus.uppercase()} with audit history."
                }
                .onFailure { err ->
                    _message.value = err.localizedMessage ?: "KYC review update failed."
                }
        }
    }

    fun cancelBookingStructured(
        bookingId: String,
        actorRole: String,
        reasonCode: String,
        reasonLabel: String
    ) {
        viewModelScope.launch {
            repository.cancelBookingWithPolicy(bookingId, actorRole, reasonCode, reasonLabel)
                .onSuccess { summary -> _message.value = summary }
                .onFailure { err -> _message.value = err.localizedMessage ?: "Cancellation failed." }
        }
    }

    fun submitMultiCriteriaRating(
        booking: Booking,
        behaviourStars: Int,
        vehicleStars: Int,
        punctualityStars: Int,
        safetyStars: Int,
        review: String
    ) {
        viewModelScope.launch {
            repository.submitMultiCriteriaRating(
                booking, behaviourStars, vehicleStars, punctualityStars, safetyStars, review
            ).onSuccess {
                _message.value = "Thank you! Multi-criteria rating recorded."
            }.onFailure { err ->
                _message.value = err.localizedMessage ?: "Unable to submit rating."
            }
        }
    }

    fun submitRiderToCustomerFeedback(booking: Booking, stars: Int, note: String) {
        viewModelScope.launch {
            repository.submitRiderToCustomerFeedback(booking, stars, note)
                .onSuccess { _message.value = "Passenger feedback saved." }
                .onFailure { err -> _message.value = err.localizedMessage ?: "Failed to save feedback." }
        }
    }

    fun submitDispute(
        bookingId: String = "",
        busBookingId: String = "",
        targetUserId: String = "",
        category: String,
        description: String,
        requestedRefundInr: Double = 0.0
    ) {
        viewModelScope.launch {
            repository.submitDispute(
                bookingId = bookingId,
                busBookingId = busBookingId,
                reporterRole = role,
                targetUserId = targetUserId,
                category = category,
                description = description,
                requestedRefundInr = requestedRefundInr
            ).onSuccess { id ->
                _message.value = "Dispute #$id submitted for Operations review."
            }.onFailure { err ->
                _message.value = err.localizedMessage ?: "Dispute submission failed."
            }
        }
    }

    fun adminResolveDispute(dispute: DisputeRecord, status: String, note: String, refundInr: Double = 0.0) {
        viewModelScope.launch {
            repository.adminResolveDispute(dispute, status, note, refundInr)
                .onSuccess { _message.value = "Dispute marked ${status.replace('_', ' ')}." }
                .onFailure { err -> _message.value = err.localizedMessage ?: "Dispute resolution failed." }
        }
    }

    fun adminModerateRating(rating: RatingRecord, status: String, reason: String) {
        viewModelScope.launch {
            repository.adminModerateRating(rating.id, rating.driverId, status, reason)
                .onSuccess { _message.value = "Review moderation saved & rating recalculated." }
                .onFailure { err -> _message.value = err.localizedMessage ?: "Moderation failed." }
        }
    }
}
