package com.example.domain

import com.example.data.model.FareConfig
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

data class FareQuote(
    val distanceKm: Double,
    val estimatedDurationMin: Long,
    val baseFareInr: Double,
    val distanceFareInr: Double,
    val gstInr: Double,
    val estimatedFareInr: Double,
    val minPermittedFareInr: Double,
    val maxPermittedFareInr: Double,
    val commissionPercent: Double,
    val gstPercent: Double,
    val allowDriverFareProposal: Boolean,
    val fareConfigVersion: Long
)

data class SettlementBreakdown(
    val grossFareInr: Double,
    val gstInr: Double,
    val platformCommissionInr: Double,
    val netDriverEarningsInr: Double
)

object FareCalculator {

    private const val EARTH_RADIUS_KM = 6371.0

    fun haversineKm(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2).pow(2)
        val c = 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        return EARTH_RADIUS_KM * c
    }

    fun haversineDistanceKm(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Double {
        // Road factor ~1.22x straight-line distance in Bengal semi-urban corridors
        val rawKm = haversineKm(lat1, lng1, lat2, lng2) * 1.22
        return round(max(1.2, rawKm) * 10.0) / 10.0
    }

    fun estimateDurationMin(distanceKm: Double, vehicleCategory: String): Long {
        val avgSpeedKmh = when (vehicleCategory) {
            "bike" -> 34.0
            "toto" -> 20.0
            "auto" -> 26.0
            "cab" -> 38.0
            "outstation" -> 45.0
            "scheduled" -> 35.0
            else -> 25.0
        }
        val minutes = (distanceKm / avgSpeedKmh) * 60.0 + 4.0
        return max(5L, minutes.toLong())
    }

    fun estimateDurationMinutes(distanceKm: Double, vehicleCategory: String): Long =
        estimateDurationMin(distanceKm, vehicleCategory)

    fun calculateFareQuote(
        distanceKm: Double,
        config: FareConfig,
        isRoundTrip: Boolean = false
    ): FareQuote {
        val effectiveKm = if (isRoundTrip) distanceKm * 1.85 else distanceKm
        val base = config.baseFareInr
        val distanceFare = max(0.0, effectiveKm - 1.5) * config.perKmRateInr
        val subTotal = max(config.minimumFareInr, base + distanceFare)
        val gst = round(subTotal * (config.gstPercent / 100.0))
        val estimatedTotal = round(subTotal + gst)

        val minFactor = if (config.allowDriverFareProposal) config.minBidFactor else 1.0
        val maxFactor = if (config.allowDriverFareProposal) config.maxBidFactor else 1.0

        val minPermitted = max(config.minimumFareInr, round(estimatedTotal * minFactor))
        val maxPermitted = max(minPermitted, round(estimatedTotal * maxFactor))

        return FareQuote(
            distanceKm = round(effectiveKm * 10.0) / 10.0,
            estimatedDurationMin = estimateDurationMin(effectiveKm, config.vehicleCategory),
            baseFareInr = base,
            distanceFareInr = round(distanceFare),
            gstInr = gst,
            estimatedFareInr = estimatedTotal,
            minPermittedFareInr = minPermitted,
            maxPermittedFareInr = maxPermitted,
            commissionPercent = config.commissionPercent,
            gstPercent = config.gstPercent,
            allowDriverFareProposal = config.allowDriverFareProposal,
            fareConfigVersion = config.version
        )
    }

    fun calculateSettlement(
        grossFareInr: Double,
        gstPercent: Double,
        commissionPercent: Double
    ): SettlementBreakdown {
        val gstInr = round((grossFareInr * gstPercent / (100.0 + gstPercent)) * 100.0) / 100.0
        val preTax = grossFareInr - gstInr
        val commission = round((preTax * commissionPercent / 100.0) * 100.0) / 100.0
        val netDriver = round((grossFareInr - gstInr - commission) * 100.0) / 100.0
        return SettlementBreakdown(
            grossFareInr = grossFareInr,
            gstInr = gstInr,
            platformCommissionInr = commission,
            netDriverEarningsInr = max(0.0, netDriver)
        )
    }

    fun isValidTransition(fromStatus: String, toStatus: String): Boolean {
        return when (fromStatus) {
            "searching" -> toStatus in setOf("assigned", "cancelled")
            "assigned" -> toStatus in setOf("arriving", "cancelled")
            "arriving" -> toStatus in setOf("arrived", "cancelled")
            "arrived" -> toStatus in setOf("in_progress", "cancelled")
            "in_progress" -> toStatus == "completed"
            else -> false
        }
    }

    fun defaultFareConfigs(): List<FareConfig> = listOf(
        FareConfig(
            id = "bike",
            vehicleCategory = "bike",
            version = 1,
            baseFareInr = 20.0,
            perKmRateInr = 8.0,
            minimumFareInr = 25.0,
            waitingChargePerMinInr = 1.0,
            cancellationFeeInr = 10.0,
            gstPercent = 5.0,
            commissionPercent = 10.0,
            allowDriverFareProposal = true,
            minBidFactor = 0.85,
            maxBidFactor = 1.25,
            isEnabled = true
        ),
        FareConfig(
            id = "toto",
            vehicleCategory = "toto",
            version = 1,
            baseFareInr = 20.0,
            perKmRateInr = 10.0,
            minimumFareInr = 25.0,
            waitingChargePerMinInr = 1.0,
            cancellationFeeInr = 10.0,
            gstPercent = 0.0,
            commissionPercent = 8.0,
            allowDriverFareProposal = true,
            minBidFactor = 0.85,
            maxBidFactor = 1.25,
            isEnabled = true
        ),
        FareConfig(
            id = "auto",
            vehicleCategory = "auto",
            version = 1,
            baseFareInr = 30.0,
            perKmRateInr = 13.0,
            minimumFareInr = 35.0,
            waitingChargePerMinInr = 1.5,
            cancellationFeeInr = 15.0,
            gstPercent = 5.0,
            commissionPercent = 10.0,
            allowDriverFareProposal = true,
            minBidFactor = 0.85,
            maxBidFactor = 1.20,
            isEnabled = true
        ),
        FareConfig(
            id = "cab",
            vehicleCategory = "cab",
            version = 1,
            baseFareInr = 65.0,
            perKmRateInr = 18.0,
            minimumFareInr = 85.0,
            waitingChargePerMinInr = 2.0,
            cancellationFeeInr = 35.0,
            gstPercent = 5.0,
            commissionPercent = 12.0,
            allowDriverFareProposal = true,
            minBidFactor = 0.88,
            maxBidFactor = 1.25,
            isEnabled = true
        ),
        FareConfig(
            id = "outstation",
            vehicleCategory = "outstation",
            version = 1,
            baseFareInr = 220.0,
            perKmRateInr = 16.0,
            minimumFareInr = 350.0,
            waitingChargePerMinInr = 2.0,
            cancellationFeeInr = 50.0,
            gstPercent = 5.0,
            commissionPercent = 12.0,
            allowDriverFareProposal = true,
            minBidFactor = 0.90,
            maxBidFactor = 1.20,
            isEnabled = true
        ),
        FareConfig(
            id = "scheduled",
            vehicleCategory = "scheduled",
            version = 1,
            baseFareInr = 55.0,
            perKmRateInr = 15.0,
            minimumFareInr = 70.0,
            waitingChargePerMinInr = 2.0,
            cancellationFeeInr = 30.0,
            gstPercent = 5.0,
            commissionPercent = 10.0,
            allowDriverFareProposal = true,
            minBidFactor = 0.90,
            maxBidFactor = 1.20,
            isEnabled = true
        ),
        FareConfig(
            id = "parcel",
            vehicleCategory = "parcel",
            version = 1,
            baseFareInr = 35.0,
            perKmRateInr = 9.0,
            minimumFareInr = 40.0,
            waitingChargePerMinInr = 1.0,
            cancellationFeeInr = 15.0,
            gstPercent = 18.0,
            commissionPercent = 12.0,
            allowDriverFareProposal = false,
            minBidFactor = 1.0,
            maxBidFactor = 1.0,
            isEnabled = false // Requires separate legal & operational approval by Admin
        )
    )
}
