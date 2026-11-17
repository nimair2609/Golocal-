package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

enum class AppRole(val wire: String) {
    CUSTOMER("customer"),
    DRIVER("driver"),
    ADMIN("admin")
}

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
        val existingAccount: UserAccount? = null
    ) : RegistrationStatus
    data class Completed(val account: UserAccount) : RegistrationStatus
}

@IgnoreExtraProperties
data class UserAccount(
    @DocumentId val id: String = "",
    val uid: String = "",
    val userId: String = "",
    val fullName: String = "",
    val email: String = "", // Invoice delivery email only (never an authentication credential)
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
    val pucNumber: String = "",
    val fitnessCertificateNumber: String = "",
    val dlExpiryEpochDay: Long = 0L,
    val rcExpiryEpochDay: Long = 0L,
    val insuranceExpiryEpochDay: Long = 0L,
    val permitExpiryEpochDay: Long = 0L,
    val fitnessExpiryEpochDay: Long = 0L,
    val pucExpiryEpochDay: Long = 0L,
    val dlPhotoUri: String = "",
    val rcPhotoUri: String = "",
    val insurancePhotoUri: String = "",
    val permitPhotoUri: String = "",
    val fitnessPhotoUri: String = "",
    val pucPhotoUri: String = "",
    val reviewedBy: String = "",
    val reviewNote: String = "",
    val verificationVersion: Long = 1L,
    val verificationStatus: String = "pending",
    val rejectionReason: String = "",
    val isOnline: Boolean = false,
    val ratingAvg: Double = 5.0,
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
    val currentLat: Double = 22.90,
    val currentLng: Double = 88.39,
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
    val zoneType: String = "town", // railway_station, bus_stand, airport, village, hospital, market, town
    val lat: Double = 22.90,
    val lng: Double = 88.39,
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
    val cancellationCategory: String = "",
    val invoiceEmail: String = "",
    val customerRating: Long? = null,
    val customerFeedbackRating: Long? = null,
    val customerFeedbackNote: String = "",
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
    val driverBehaviorRating: Long = 5L,
    val vehicleConditionRating: Long = 5L,
    val punctualityRating: Long = 5L,
    val safetyRating: Long = 5L,
    val review: String = "",
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

@Keep
@IgnoreExtraProperties
data class OperationsStaffEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val contactIdentifier: String = "",
    val assignedDistrict: String = "",
    @get:PropertyName("isActive")
    @PropertyName("isActive")
    val isActive: Boolean = true,
    val grantedBy: String = "",
    val grantedByEmail: String = "",
    val revokedBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
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

@IgnoreExtraProperties
data class BookingChatMessage(
    @DocumentId val id: String = "",
    val bookingId: String = "",
    val customerId: String = "",
    val driverId: String = "",
    val senderId: String = "",
    val senderRole: String = "customer",
    val senderName: String = "",
    val text: String = "",
    val deliveryState: String = "sent", // sent, delivered, read
    val readByCustomer: Boolean = false,
    val readByDriver: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BookingCallContact(
    @DocumentId val id: String = "",
    val bookingId: String = "",
    val customerId: String = "",
    val driverId: String = "",
    val customerPhone: String = "",
    val driverPhone: String = "",
    val customerFirstName: String = "",
    val driverName: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DriverVerificationHistoryEntry(
    @DocumentId val id: String = "",
    val driverId: String = "",
    val reviewerId: String = "",
    val reviewerEmail: String = "",
    val decision: String = "pending", // pending, verified, rejected, suspended
    val reason: String = "",
    val verificationVersion: Long = 1L,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CancellationAppeal(
    @DocumentId val id: String = "",
    val bookingId: String = "",
    val appellantId: String = "",
    val appellantRole: String = "customer",
    val reason: String = "",
    val status: String = "open", // open, accepted, rejected
    val adminNote: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class UserDeviceToken(
    @DocumentId val id: String = "",
    val userId: String = "",
    val token: String = "",
    val platform: String = "android",
    val appVariant: String = "customer",
    val active: Boolean = true,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BusOperator(
    @DocumentId val id: String = "",
    val operatorName: String = "",
    val ownerUid: String = "",
    val contactPhone: String = "",
    val district: String = "",
    val permitNumber: String = "",
    val insuranceNumber: String = "",
    val fitnessCertificateNumber: String = "",
    val pucNumber: String = "",
    val verificationStatus: String = "pending", // pending, verified, rejected, suspended
    val rejectionReason: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BusRoute(
    @DocumentId val id: String = "",
    val operatorId: String = "",
    val operatorName: String = "",
    val routeName: String = "",
    val originStop: String = "",
    val destinationStop: String = "",
    val intermediateStops: List<String> = emptyList(),
    val distanceKm: Double = 0.0,
    val fareSource: String = "",
    val effectiveDateLabel: String = "",
    val baseSeatFareInr: Double = 0.0,
    val perKmSeatRateInr: Double = 0.0,
    val gstPercent: Double = 5.0,
    val commissionPercent: Double = 8.0,
    val status: String = "pending_pricing", // pending_pricing, active, suspended
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BusSchedule(
    @DocumentId val id: String = "",
    val routeId: String = "",
    val operatorId: String = "",
    val operatorName: String = "",
    val busLabel: String = "",
    val busType: String = "non_ac_seater", // non_ac_seater, ac_seater, sleeper
    val originStop: String = "",
    val destinationStop: String = "",
    val departureTimeLabel: String = "",
    val arrivalTimeLabel: String = "",
    val travelDateLabel: String = "",
    val totalSeats: Long = 40L,
    val bookedSeats: List<String> = emptyList(),
    val farePerSeatInr: Double = 0.0,
    val fareSource: String = "",
    val effectiveDateLabel: String = "",
    val gstPercent: Double = 5.0,
    val commissionPercent: Double = 8.0,
    val status: String = "scheduled", // scheduled, boarding, departed, cancelled
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BusBooking(
    @DocumentId val id: String = "",
    val bookingRef: String = "",
    val customerId: String = "",
    val customerName: String = "",
    val scheduleId: String = "",
    val routeId: String = "",
    val operatorId: String = "",
    val operatorName: String = "",
    val routeLabel: String = "",
    val travelDateLabel: String = "",
    val departureTimeLabel: String = "",
    val seatNumbers: List<String> = emptyList(),
    val seatCount: Long = 0L,
    val totalFareInr: Double = 0.0,
    val gstInr: Double = 0.0,
    val commissionInr: Double = 0.0,
    val paymentMethod: String = "cash",
    val paymentStatus: String = "pending",
    val status: String = "confirmed", // confirmed, boarded, cancelled, refunded
    val qrPayload: String = "",
    val cancellationReason: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
