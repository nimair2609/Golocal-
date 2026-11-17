package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class AppRole(val wire: String) {
    CUSTOMER("customer"),
    DRIVER("driver"),
    ADMIN("admin")
}

enum class PortalRole {
    OWNER_ADMIN,
    OPERATIONS_STAFF,
    NONE
}

@IgnoreExtraProperties
data class GeoPointDto(
    val lat: Double = 0.0,
    val lng: Double = 0.0
)

enum class AppLanguage(val code: String, val displayLabel: String) {
    EN("en", "English"),
    BN("bn", "বাংলা"),
    HI("hi", "हिन्दी");

    companion object {
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: EN
    }
}

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

sealed interface RegistrationStatus {
    data object Checking : RegistrationStatus
    data class PendingRegistration(
        val verifiedMobileNumber: String,
        val existingAccount: UserAccount? = null,
        val prefilledName: String = "",
        val prefilledEmail: String = "",
        val isPhoneAuthVerified: Boolean = verifiedMobileNumber.isNotBlank()
    ) : RegistrationStatus
    data class Completed(val account: UserAccount) : RegistrationStatus
}

@IgnoreExtraProperties
data class UserAccount(
    @DocumentId val id: String = "",
    val uid: String = "",
    val userId: String = "",
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val mobileNumber: String = "",
    val photoUrl: String = "",
    val role: String = "customer",
    val preferredLanguage: String = "en",
    val lowNetworkMode: Boolean = false,
    val privacyConsentAccepted: Boolean = false,
    val profileCompleted: Boolean = false,
    val emailVerified: Boolean = false,
    val mobileVerified: Boolean = false,
    val authProviders: List<String> = emptyList(),
    val hasEmergencyContact: Boolean = false,
    val accountStatus: String = "active",
    @ServerTimestamp val accountCreatedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun effectiveMobileNumber(): String = mobileNumber.ifBlank { phone }

    fun toCreateMap(): Map<String, Any> = buildMap {
        val resolvedUid = uid.ifBlank { userId }
        val resolvedPhone = effectiveMobileNumber()
        put("uid", resolvedUid)
        put("userId", userId)
        put("fullName", fullName.ifBlank { "GoLocal User" })
        if (email.isNotBlank()) put("email", email)
        if (resolvedPhone.isNotBlank()) {
            put("phone", resolvedPhone)
            put("mobileNumber", resolvedPhone)
        }
        if (photoUrl.isNotBlank()) put("photoUrl", photoUrl)
        if (authProviders.isNotEmpty()) put("authProviders", authProviders)
        put("role", role)
        put("preferredLanguage", preferredLanguage)
        put("lowNetworkMode", lowNetworkMode)
        put("privacyConsentAccepted", privacyConsentAccepted)
        put("profileCompleted", profileCompleted)
        put("emailVerified", emailVerified)
        put("mobileVerified", mobileVerified)
        put("hasEmergencyContact", hasEmergencyContact)
        put("accountStatus", accountStatus)
        put("accountCreatedAt", FieldValue.serverTimestamp())
        put("createdAt", FieldValue.serverTimestamp())
        put("updatedAt", FieldValue.serverTimestamp())
    }
}

@IgnoreExtraProperties
data class PrivateEmergencyContact(
    @DocumentId val id: String = "",
    val userId: String = "",
    val emergencyContactNumber: String = "",
    val relationshipNote: String = "Primary Emergency Contact",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CustomerProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val homeAddress: String = "",
    val workAddress: String = "",
    val nativeVillage: String = "",
    val nativeDistrict: String = "",
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DriverProfile(
    @DocumentId val id: String = "",
    val driverId: String = "",
    val legalName: String = "",
    val phone: String = "",
    val address: String = "",
    val district: String = "",
    val block: String = "",
    val vehicleCategory: String = "toto",
    val vehicleModel: String = "",
    val vehicleRegNumber: String = "",
    val drivingLicenceNumber: String = "",
    val rcNumber: String = "",
    val insuranceNumber: String = "",
    val permitInfo: String = "",
    val settlementUpiOrBank: String = "",
    val documentPhotoUri: String = "",
    val verificationStatus: String = "pending",
    val rejectionReason: String = "",
    val documents: List<DriverDocumentItem> = emptyList(),
    val verificationHistory: List<VerificationHistoryEntry> = emptyList(),
    val earliestExpiryIso: String = "",
    val expiryAlertLevel: String = "valid", // valid, expiring_soon, expired, missing
    val isOnline: Boolean = false,
    val ratingAvg: Double = 5.0,
    val totalRatingCount: Long = 0L,
    val completedRides: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DriverDirectoryEntry(
    @DocumentId val id: String = "",
    val driverId: String = "",
    val displayName: String = "",
    val vehicleCategory: String = "toto",
    val vehicleModel: String = "",
    val maskedPlate: String = "",
    val district: String = "",
    val block: String = "",
    val verificationStatus: String = "pending",
    val isOnline: Boolean = false,
    val currentLat: Double = 21.8160,
    val currentLng: Double = 87.3703,
    val ratingAvg: Double = 5.0,
    val completedRides: Long = 0L,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ServiceArea(
    @DocumentId val id: String = "",
    val name: String = "",
    val nameBn: String = "",
    val district: String = "",
    val block: String = "",
    val pincode: String = "",
    val zoneType: String = "town", // railway_station, bus_stand, airport, village, hospital, market, town
    val lat: Double = 21.8160,
    val lng: Double = 87.3703,
    val centerLat: Double = 21.8160,
    val centerLng: Double = 87.3703,
    val radiusKm: Double = 8.0,
    val polygonPoints: List<GeoPointDto> = emptyList(),
    val villages: List<String> = emptyList(),
    val pickupEnabled: Boolean = true,
    val dropoffEnabled: Boolean = true,
    val supportedVehicleCategories: List<String> = listOf("toto", "auto", "bike", "cab"),
    val availabilityNote: String = "",
    val isActive: Boolean = true,
    val parcelApproved: Boolean = false,
    val operationalHours: String = "05:00 - 23:00",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class FareConfig(
    @DocumentId val id: String = "",
    val vehicleCategory: String = "toto",
    val version: Long = 1L,
    val baseFareInr: Double = 25.0,
    val perKmRateInr: Double = 11.0,
    val minimumFareInr: Double = 25.0,
    val waitingChargePerMinInr: Double = 1.5,
    val cancellationFeeInr: Double = 15.0,
    val gstPercent: Double = 5.0,
    val commissionPercent: Double = 10.0,
    val allowDriverFareProposal: Boolean = true,
    val minBidFactor: Double = 0.85,
    val maxBidFactor: Double = 1.25,
    val isEnabled: Boolean = true,
    val updatedBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class Booking(
    @DocumentId val id: String = "",
    val customerId: String = "",
    val customerName: String = "",
    val bookingChannel: String = "app",
    val bookingType: String = "now", // now, scheduled, station_pickup, bus_stand_pickup, airport_transfer, village_to_town, native_village, family, return_trip
    val vehicleCategory: String = "toto",
    val pickupLabel: String = "",
    val pickupDistrict: String = "",
    val pickupLat: Double = 0.0,
    val pickupLng: Double = 0.0,
    val destinationLabel: String = "",
    val destinationDistrict: String = "",
    val destinationLat: Double = 0.0,
    val destinationLng: Double = 0.0,
    val distanceKm: Double = 0.0,
    val estimatedDurationMin: Long = 0L,
    val estimatedFareInr: Double = 0.0,
    val minPermittedFareInr: Double = 0.0,
    val maxPermittedFareInr: Double = 0.0,
    val agreedFareInr: Double = 0.0,
    val fareConfigVersion: Long = 1L,
    val commissionPercent: Double = 10.0,
    val gstPercent: Double = 5.0,
    val paymentMethod: String = "cash",
    val paymentStatus: String = "pending",
    val status: String = "searching",
    val assignedDriverId: String = "",
    val assignedDriverName: String = "",
    val assignedVehicleLabel: String = "",
    val acceptedOfferId: String = "",
    val tripPin: String = "",
    val trainNumber: String = "",
    val scheduledDateTimeLabel: String = "",
    val familyMemberName: String = "",
    val familyConsentConfirmed: Boolean = false,
    val isRoundTrip: Boolean = false,
    val cancellationReason: String = "",
    val cancelledBy: String = "",
    val cancelledByRole: String = "",
    val cancellationFeeInr: Double = 0.0,
    val refundAmountInr: Double = 0.0,
    val statusHistory: List<StatusHistoryEntry> = emptyList(),
    val invoiceEmail: String = "",
    val customerRating: Long? = null,
    val routeProvider: String = "",
    val pickupZoneId: String = "",
    val dropoffZoneId: String = "",
    val assignedByOpsUid: String = "",
    val opsAssignmentNote: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class RideOffer(
    @DocumentId val id: String = "",
    val bookingId: String = "",
    val customerId: String = "",
    val driverId: String = "",
    val driverName: String = "",
    val vehicleCategory: String = "toto",
    val vehicleLabel: String = "",
    val driverRating: Double = 5.0,
    val driverCompletedRides: Long = 0L,
    val isVerifiedDriver: Boolean = false,
    val etaMinutes: Long = 5L,
    val proposedFareInr: Double = 0.0,
    val status: String = "pending",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class TripLocation(
    @DocumentId val id: String = "",
    val bookingId: String = "",
    val customerId: String = "",
    val driverId: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PaymentRecord(
    @DocumentId val id: String = "",
    val bookingId: String = "",
    val customerId: String = "",
    val driverId: String = "",
    val grossFareInr: Double = 0.0,
    val gstInr: Double = 0.0,
    val platformCommissionInr: Double = 0.0,
    val netDriverEarningsInr: Double = 0.0,
    val paymentMethod: String = "cash",
    val invoiceEmail: String = "",
    val gatewayReference: String = "",
    val status: String = "settled_cash",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CommissionRecord(
    @DocumentId val id: String = "",
    val bookingId: String = "",
    val driverId: String = "",
    val grossFareInr: Double = 0.0,
    val commissionRatePct: Double = 0.0,
    val commissionAmountInr: Double = 0.0,
    val netDriverEarningsInr: Double = 0.0,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DriverSettlement(
    @DocumentId val id: String = "",
    val driverId: String = "",
    val driverName: String = "",
    val amountInr: Double = 0.0,
    val upiOrBankRef: String = "",
    val status: String = "requested",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class RefundRecord(
    @DocumentId val id: String = "",
    val bookingId: String = "",
    val customerId: String = "",
    val amountInr: Double = 0.0,
    val reason: String = "",
    val status: String = "requested",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class RatingRecord(
    @DocumentId val id: String = "",
    val bookingId: String = "",
    val customerId: String = "",
    val driverId: String = "",
    val stars: Long = 5L,
    val behaviourStars: Long = 5L,
    val vehicleStars: Long = 5L,
    val punctualityStars: Long = 5L,
    val safetyStars: Long = 5L,
    val review: String = "",
    val riderToCustomerStars: Long? = null,
    val riderToCustomerNote: String = "",
    val moderationStatus: String = "active", // active, flagged, hidden
    val flaggedReason: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ComplaintRecord(
    @DocumentId val id: String = "",
    val reporterId: String = "",
    val bookingId: String = "",
    val category: String = "",
    val description: String = "",
    val status: String = "open",
    val resolutionNote: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val locationLabel: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class EmergencyEvent(
    @DocumentId val id: String = "",
    val userId: String = "",
    val bookingId: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val emergencyContactRef: String = "",
    val notes: String = "",
    val status: String = "active",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AppNotification(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val body: String = "",
    val type: String = "booking",
    val read: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SupportRequest(
    @DocumentId val id: String = "",
    val userId: String = "",
    val requestType: String = "general_support",
    val details: String = "",
    val status: String = "submitted",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AuditLog(
    @DocumentId val id: String = "",
    val actorId: String = "",
    val actorEmail: String = "",
    val action: String = "",
    val targetCollection: String = "",
    val targetId: String = "",
    val summary: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AdminAccountEntry(
    @DocumentId val id: String = "",
    val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val addedBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class OperationsStaffEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val phoneOrEmail: String = "",
    val isActive: Boolean = true,
    val assignedDistrict: String = "Paschim Medinipur",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class LiveDriverRtdbLocation(
    val driverId: String = "",
    val bookingId: String = "",
    val customerId: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val accuracyMeters: Float = 0f,
    val speedMps: Float = 0f,
    val headingDeg: Float = 0f,
    val trackingStatus: String = "stopped", // active, paused_gps_lost, stopped
    val updatedAtEpochMs: Long = 0L
)

@IgnoreExtraProperties
data class PlatformSettings(
    @DocumentId val id: String = "",
    val helplineNumber: String = "+91-1800-103-0000",
    val parcelDeliveryEnabled: Boolean = false,
    val maintenanceNotice: String = "",
    val cancellationPolicySummary: String = "Free cancellation before driver arrival. Nominal cancellation fee may apply after pickup arrival.",
    val defaultCommissionPct: Double = 10.0,
    val updatesEnabled: Boolean = false,
    val latestVersionName: String = "1.0",
    val latestVersionCode: Long = 1L,
    val minSupportedVersionCode: Long = 1L,
    val apkDownloadUrl: String = "",
    val sha256Checksum: String = "",
    val releaseNotes: String = "",
    val isMandatoryUpdate: Boolean = false,
    val updateManifestUrl: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null
)
