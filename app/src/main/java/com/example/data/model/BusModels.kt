package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class BusOperator(
    @DocumentId val id: String = "",
    val operatorId: String = "",
    val ownerUid: String = "",
    val companyName: String = "",
    val contactPhone: String = "",
    val operatorLicenseNumber: String = "",
    val permitNumber: String = "",
    val district: String = "",
    val upiOrBankRef: String = "",
    val documents: List<DriverDocumentItem> = emptyList(),
    val verificationStatus: String = "pending", // pending, verified, rejected, suspended
    val rejectionReason: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BusVehicle(
    @DocumentId val id: String = "",
    val operatorId: String = "",
    val busName: String = "",
    val registrationNumber: String = "",
    val busType: String = "AC Seater", // Non-AC Seater, AC Seater, Mini Bus, AC Sleeper
    val totalSeats: Int = 36,
    val layoutCols: Int = 4,
    val amenityTags: List<String> = emptyList(),
    val documents: List<DriverDocumentItem> = emptyList(),
    val verificationStatus: String = "pending", // pending, verified, rejected
    val rejectionReason: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BusRoute(
    @DocumentId val id: String = "",
    val operatorId: String = "",
    val routeName: String = "",
    val originTown: String = "",
    val destinationTown: String = "",
    val stops: List<String> = emptyList(),
    val distanceKm: Double = 0.0,
    val farePerSeatInr: Double = 0.0,
    val fareSource: String = "operator_submitted", // government_gazette, rto_tariff, operator_submitted
    val authorityRef: String = "",
    val effectiveDateIso: String = "",
    val fareApprovalStatus: String = "pending_fare_approval", // pending_fare_approval, approved, rejected
    val fareRejectionReason: String = "",
    val gstPercent: Double = 5.0,
    val commissionPercent: Double = 8.0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BusSchedule(
    @DocumentId val id: String = "",
    val routeId: String = "",
    val operatorId: String = "",
    val busId: String = "",
    val busName: String = "",
    val busType: String = "AC Seater",
    val routeLabel: String = "",
    val originTown: String = "",
    val destinationTown: String = "",
    val stops: List<String> = emptyList(),
    val departureDateIso: String = "", // YYYY-MM-DD
    val departureTimeLabel: String = "", // e.g. 07:30 AM
    val arrivalTimeLabel: String = "",
    val totalSeats: Int = 36,
    val bookedSeatIds: List<String> = emptyList(),
    val farePerSeatInr: Double = 0.0,
    val fareSource: String = "operator_submitted",
    val authorityRef: String = "",
    val effectiveDateIso: String = "",
    val fareApprovalStatus: String = "pending_fare_approval",
    val gstPercent: Double = 5.0,
    val commissionPercent: Double = 8.0,
    val status: String = "scheduled", // scheduled, boarding, departed, cancelled
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BusSeatLock(
    @DocumentId val id: String = "", // "${scheduleId}_${seatId}"
    val scheduleId: String = "",
    val seatId: String = "",
    val lockedByUid: String = "",
    val lockedUntilEpochMs: Long = 0L,
    val status: String = "locked" // locked, booked
)

@IgnoreExtraProperties
data class BusBooking(
    @DocumentId val id: String = "",
    val customerId: String = "",
    val customerName: String = "",
    val scheduleId: String = "",
    val routeId: String = "",
    val operatorId: String = "",
    val busName: String = "",
    val routeLabel: String = "",
    val departureDateIso: String = "",
    val departureTimeLabel: String = "",
    val boardingStop: String = "",
    val dropStop: String = "",
    val seatIds: List<String> = emptyList(),
    val seatCount: Int = 0,
    val farePerSeatInr: Double = 0.0,
    val baseFareInr: Double = 0.0,
    val gstInr: Double = 0.0,
    val platformCommissionInr: Double = 0.0,
    val netOperatorPayoutInr: Double = 0.0,
    val totalPaidInr: Double = 0.0,
    val fareSource: String = "",
    val authorityRef: String = "",
    val qrToken: String = "",
    val qrValidated: Boolean = false,
    val paymentMethod: String = "upi",
    val status: String = "confirmed", // confirmed, boarded, cancelled
    val cancellationFeeInr: Double = 0.0,
    val refundAmountInr: Double = 0.0,
    val cancellationReason: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
