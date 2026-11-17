package com.example.domain

import com.example.data.model.Booking
import com.example.data.model.DriverProfile
import kotlin.math.max

enum class KycDocType(val wire: String, val displayLabel: String) {
    DRIVING_LICENCE("driving_licence", "Driving Licence (DL)"),
    RC_CERTIFICATE("rc_certificate", "Vehicle Registration (RC)"),
    INSURANCE("insurance", "Commercial Insurance"),
    FITNESS("fitness_certificate", "Fitness Certificate"),
    PERMIT("permit", "Transport / Route Permit"),
    PUC("puc", "Pollution Under Control (PUC)")
}

enum class ExpiryStatus {
    VALID,
    EXPIRING_SOON,
    EXPIRED,
    NOT_PROVIDED
}

data class DocumentExpiryAssessment(
    val docType: KycDocType,
    val documentNumber: String,
    val expiryEpochDay: Long,
    val status: ExpiryStatus,
    val daysRemaining: Long?
)

data class DriverAbuseAdvisory(
    val userId: String,
    val role: String,
    val cancelledCount: Int,
    val totalRecentBookings: Int,
    val cancellationRatePct: Int,
    val flagReason: String
)

object RequiredDocumentsCatalog {
    fun requiredFor(vehicleCategory: String): List<KycDocType> = when (vehicleCategory.lowercase()) {
        "bike" -> listOf(
            KycDocType.DRIVING_LICENCE,
            KycDocType.RC_CERTIFICATE,
            KycDocType.INSURANCE,
            KycDocType.PUC
        )
        "toto" -> listOf(
            KycDocType.DRIVING_LICENCE,
            KycDocType.RC_CERTIFICATE,
            KycDocType.INSURANCE
        )
        "auto", "cab", "outstation", "scheduled" -> listOf(
            KycDocType.DRIVING_LICENCE,
            KycDocType.RC_CERTIFICATE,
            KycDocType.INSURANCE,
            KycDocType.FITNESS,
            KycDocType.PERMIT,
            KycDocType.PUC
        )
        "bus" -> listOf(
            KycDocType.DRIVING_LICENCE,
            KycDocType.RC_CERTIFICATE,
            KycDocType.INSURANCE,
            KycDocType.FITNESS,
            KycDocType.PERMIT,
            KycDocType.PUC
        )
        else -> listOf(KycDocType.DRIVING_LICENCE, KycDocType.RC_CERTIFICATE, KycDocType.INSURANCE)
    }

    fun assessExpiry(
        docType: KycDocType,
        documentNumber: String,
        expiryEpochDay: Long,
        todayEpochDay: Long
    ): DocumentExpiryAssessment {
        if (expiryEpochDay <= 0L) {
            return DocumentExpiryAssessment(
                docType = docType,
                documentNumber = documentNumber,
                expiryEpochDay = 0L,
                status = ExpiryStatus.NOT_PROVIDED,
                daysRemaining = null
            )
        }
        val remaining = expiryEpochDay - todayEpochDay
        val status = when {
            remaining < 0L -> ExpiryStatus.EXPIRED
            remaining <= 30L -> ExpiryStatus.EXPIRING_SOON
            else -> ExpiryStatus.VALID
        }
        return DocumentExpiryAssessment(
            docType = docType,
            documentNumber = documentNumber,
            expiryEpochDay = expiryEpochDay,
            status = status,
            daysRemaining = remaining
        )
    }

    fun assessDriverCompliance(
        profile: DriverProfile,
        todayEpochDay: Long
    ): List<DocumentExpiryAssessment> {
        return requiredFor(profile.vehicleCategory).map { doc ->
            when (doc) {
                KycDocType.DRIVING_LICENCE -> assessExpiry(doc, profile.drivingLicenceNumber, profile.dlExpiryEpochDay, todayEpochDay)
                KycDocType.RC_CERTIFICATE -> assessExpiry(doc, profile.rcNumber, profile.rcExpiryEpochDay, todayEpochDay)
                KycDocType.INSURANCE -> assessExpiry(doc, profile.insuranceNumber, profile.insuranceExpiryEpochDay, todayEpochDay)
                KycDocType.FITNESS -> assessExpiry(doc, profile.fitnessCertificateNumber, profile.fitnessExpiryEpochDay, todayEpochDay)
                KycDocType.PERMIT -> assessExpiry(doc, profile.permitInfo, profile.permitExpiryEpochDay, todayEpochDay)
                KycDocType.PUC -> assessExpiry(doc, profile.pucNumber, profile.pucExpiryEpochDay, todayEpochDay)
            }
        }
    }
}

object BookingChatValidator {
    private val ACTIVE_CHAT_STATUSES = setOf("assigned", "arriving", "arrived", "in_progress")
    const val MAX_MESSAGE_LENGTH = 500

    fun isChatWriteAllowed(bookingStatus: String): Boolean = bookingStatus in ACTIVE_CHAT_STATUSES

    fun isChatReadAllowed(bookingStatus: String): Boolean =
        bookingStatus in ACTIVE_CHAT_STATUSES || bookingStatus in setOf("completed", "cancelled")

    fun validateMessageText(raw: String): String? {
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> "Enter a message before sending."
            trimmed.length > MAX_MESSAGE_LENGTH -> "Messages must be $MAX_MESSAGE_LENGTH characters or fewer."
            else -> null
        }
    }
}

object AbuseDetectionEngine {
    val customerCancellationReasons = listOf(
        "Driver taking too long to arrive",
        "Changed travel plans",
        "Booked wrong pickup location",
        "Driver asked to cancel offline",
        "Found alternative local transport",
        "Other reason"
    )

    val driverCancellationReasons = listOf(
        "Passenger unreachable at pickup",
        "Vehicle breakdown or low charge",
        "Road closure or unsafe route",
        "Excess luggage beyond vehicle limit",
        "Emergency personal reason"
    )

    fun evaluateCancellationAdvisories(bookings: List<Booking>): List<DriverAbuseAdvisory> {
        val advisories = mutableListOf<DriverAbuseAdvisory>()

        // Evaluate customers with high repeated cancellations (>= 3 cancelled & >= 50%)
        bookings.filter { it.customerId.isNotBlank() }
            .groupBy { it.customerId }
            .forEach { (uid, list) ->
                val cancelled = list.count { it.status == "cancelled" && (it.cancelledByRole.isBlank() || it.cancelledByRole == "customer") }
                if (cancelled >= 3) {
                    val rate = (cancelled * 100) / max(1, list.size)
                    if (rate >= 45) {
                        advisories.add(
                            DriverAbuseAdvisory(
                                userId = uid,
                                role = "customer",
                                cancelledCount = cancelled,
                                totalRecentBookings = list.size,
                                cancellationRatePct = rate,
                                flagReason = "Repeated customer cancellations ($cancelled of ${list.size} trips) — manual Admin review recommended."
                            )
                        )
                    }
                }
            }

        // Evaluate drivers who cancel after assignment or get flagged for asking offline cancel
        bookings.filter { it.assignedDriverId.isNotBlank() }
            .groupBy { it.assignedDriverId }
            .forEach { (driverId, list) ->
                val cancelledByDriver = list.count {
                    it.status == "cancelled" &&
                        (it.cancelledByRole == "driver" || it.cancellationReason.contains("offline", ignoreCase = true))
                }
                if (cancelledByDriver >= 2) {
                    val rate = (cancelledByDriver * 100) / max(1, list.size)
                    advisories.add(
                        DriverAbuseAdvisory(
                            userId = driverId,
                            role = "driver",
                            cancelledCount = cancelledByDriver,
                            totalRecentBookings = list.size,
                            cancellationRatePct = rate,
                            flagReason = "Post-assignment driver cancellations / offline cancel reports ($cancelledByDriver of ${list.size} trips)."
                        )
                    )
                }
            }

        return advisories.sortedByDescending { it.cancelledCount }
    }
}
