package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class DriverDocumentItem(
    val docType: String = "", // DRIVING_LICENCE, RC, INSURANCE, FITNESS, COMMERCIAL_PERMIT, PUC
    val label: String = "",
    val documentNumber: String = "",
    val expiryDateIso: String = "", // YYYY-MM-DD
    val storageUrl: String = "",
    val ocrExtractedNumber: String = "",
    val ocrExtractedExpiry: String = "",
    val ocrMismatchWarning: String = "",
    val verificationStatus: String = "pending", // pending, verified, rejected
    val rejectionReason: String = "",
    val reviewerId: String = "",
    val reviewedAtEpochMs: Long = 0L
)

@IgnoreExtraProperties
data class VerificationHistoryEntry(
    val action: String = "", // submitted, resubmitted, approved, rejected, suspended
    val status: String = "pending",
    val reason: String = "",
    val actorId: String = "",
    val timestampEpochMs: Long = 0L
)

@IgnoreExtraProperties
data class StatusHistoryEntry(
    val status: String = "",
    val actorId: String = "",
    val actorRole: String = "",
    val note: String = "",
    val timestampEpochMs: Long = 0L
)

enum class CustomerCancelReason(val wire: String, val label: String) {
    CHANGED_PLANS("changed_plans", "Change of travel plan"),
    LONG_WAIT("long_wait", "Driver taking too long"),
    DRIVER_ASKED("driver_asked", "Driver asked to cancel"),
    WRONG_PICKUP("wrong_pickup", "Entered wrong pickup or drop"),
    PRICE_CONCERN("price_concern", "Fare higher than expected"),
    OTHER("other", "Other reason")
}

enum class RiderCancelReason(val wire: String, val label: String) {
    VEHICLE_BREAKDOWN("vehicle_breakdown", "Vehicle issue / flat tyre"),
    CUSTOMER_NO_SHOW("customer_no_show", "Customer did not arrive at pickup"),
    CUSTOMER_UNREACHABLE("customer_unreachable", "Unable to reach customer"),
    UNSAFE_PICKUP("unsafe_pickup", "Pickup point unsafe or inaccessible"),
    PERSONAL_EMERGENCY("personal_emergency", "Urgent personal emergency"),
    OTHER("other", "Other operational reason")
}

@IgnoreExtraProperties
data class DisputeRecord(
    @DocumentId val id: String = "",
    val bookingId: String = "",
    val busBookingId: String = "",
    val reporterId: String = "",
    val reporterRole: String = "customer", // customer, driver
    val targetUserId: String = "",
    val category: String = "fare_overcharge", // fare_overcharge, no_show_fee, rude_behavior, safety, bus_refund, other
    val description: String = "",
    val requestedRefundInr: Double = 0.0,
    val status: String = "open", // open, under_review, resolved_refund, resolved_closed
    val adminResolutionNote: String = "",
    val resolvedBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class AnomalyFlag(
    val flagId: String,
    val targetUserId: String,
    val targetRole: String,
    val targetName: String,
    val severity: String, // high, medium
    val patternType: String,
    val reasonSummary: String,
    val evidenceCount: Int
)

@IgnoreExtraProperties
data class FcmTokenRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val role: String = "customer",
    val token: String = "",
    val platform: String = "android",
    val appPackage: String = "",
    val active: Boolean = true,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
