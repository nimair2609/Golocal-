package com.example.domain

import com.example.data.model.BusRoute
import kotlin.math.max
import kotlin.math.round

data class BusFareQuote(
    val seatCount: Int,
    val farePerSeatInr: Double,
    val subTotalInr: Double,
    val gstInr: Double,
    val totalFareInr: Double,
    val platformCommissionInr: Double,
    val operatorNetPayoutInr: Double,
    val fareSource: String,
    val effectiveDateLabel: String
)

object BusFareEngine {

    fun isRoutePricingConfigured(route: BusRoute): Boolean {
        return route.fareSource.trim().isNotBlank() &&
            route.effectiveDateLabel.trim().isNotBlank() &&
            route.baseSeatFareInr > 0.0 &&
            route.distanceKm > 0.0
    }

    fun resolveRouteStatusForAdmin(route: BusRoute, requestedActive: Boolean): String {
        if (!requestedActive) return "suspended"
        return if (isRoutePricingConfigured(route)) "active" else "pending_pricing"
    }

    fun calculateSeatFareInr(route: BusRoute): Double {
        require(isRoutePricingConfigured(route)) {
            "Route pricing requires official fareSource, effectiveDateLabel, positive base fare, and distance."
        }
        val raw = route.baseSeatFareInr + max(0.0, route.distanceKm) * max(0.0, route.perKmSeatRateInr)
        return round(max(route.baseSeatFareInr, raw))
    }

    fun calculateBookingQuote(
        farePerSeatInr: Double,
        seatCount: Int,
        gstPercent: Double,
        commissionPercent: Double,
        fareSource: String,
        effectiveDateLabel: String
    ): BusFareQuote {
        require(fareSource.isNotBlank()) { "Bus route cannot be booked without an approved tariff source." }
        require(effectiveDateLabel.isNotBlank()) { "Bus route effective date is required." }
        require(seatCount in 1..6) { "You can book between 1 and 6 seats per bus transaction." }
        require(farePerSeatInr >= 10.0) { "Bus per-seat fare must be configured before booking." }

        val subTotal = round(farePerSeatInr * seatCount)
        val gst = round(subTotal * (gstPercent / 100.0) * 100.0) / 100.0
        val total = round(subTotal + gst)
        val commission = round(subTotal * (commissionPercent / 100.0) * 100.0) / 100.0
        val netOperator = round((total - gst - commission) * 100.0) / 100.0

        return BusFareQuote(
            seatCount = seatCount,
            farePerSeatInr = farePerSeatInr,
            subTotalInr = subTotal,
            gstInr = gst,
            totalFareInr = total,
            platformCommissionInr = commission,
            operatorNetPayoutInr = max(0.0, netOperator),
            fareSource = fareSource,
            effectiveDateLabel = effectiveDateLabel
        )
    }

    fun buildQrTicketPayload(
        bookingId: String,
        bookingRef: String,
        scheduleId: String,
        seatNumbers: List<String>
    ): String {
        val seatsJoined = seatNumbers.sorted().joinToString(",")
        return "GOLOCAL-BUS|v1|$bookingId|$bookingRef|$scheduleId|$seatsJoined"
    }

    fun isValidQrTicketPayload(payload: String, bookingId: String, scheduleId: String): Boolean {
        val parts = payload.split("|")
        return parts.size >= 6 &&
            parts[0] == "GOLOCAL-BUS" &&
            parts[2] == bookingId &&
            parts[4] == scheduleId &&
            parts[5].isNotBlank()
    }
}
