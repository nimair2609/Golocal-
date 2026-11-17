package com.example.domain

import com.example.data.model.BusRoute
import com.example.data.model.BusSchedule
import kotlin.math.round

data class BusFareBreakdown(
    val seatCount: Int,
    val farePerSeatInr: Double,
    val baseFareInr: Double,
    val gstInr: Double,
    val totalCustomerPayableInr: Double,
    val platformCommissionInr: Double,
    val netOperatorPayoutInr: Double
)

object BusFareEngine {

    val ALLOWED_FARE_SOURCES = setOf("government_gazette", "rto_tariff", "operator_submitted")

    fun calculateBreakdown(
        farePerSeatInr: Double,
        seatCount: Int,
        gstPercent: Double = 5.0,
        commissionPercent: Double = 8.0
    ): BusFareBreakdown {
        require(farePerSeatInr > 0.0) { "Bus fare per seat must be positive." }
        require(seatCount in 1..6) { "Select between 1 and 6 seats per booking." }

        val base = round(farePerSeatInr * seatCount * 100.0) / 100.0
        val gst = round(base * (gstPercent / 100.0) * 100.0) / 100.0
        val total = round((base + gst) * 100.0) / 100.0
        val commission = round(base * (commissionPercent / 100.0) * 100.0) / 100.0
        val netOperator = round((base - commission) * 100.0) / 100.0

        return BusFareBreakdown(
            seatCount = seatCount,
            farePerSeatInr = round(farePerSeatInr * 100.0) / 100.0,
            baseFareInr = base,
            gstInr = gst,
            totalCustomerPayableInr = total,
            platformCommissionInr = commission,
            netOperatorPayoutInr = netOperator
        )
    }

    fun validateFareSubmission(
        farePerSeatInr: Double,
        fareSource: String,
        authorityRef: String,
        effectiveDateIso: String
    ): String? {
        if (farePerSeatInr <= 0.0 || farePerSeatInr > 15_000.0) {
            return "Bus fare per seat must be between ₹1 and ₹15,000."
        }
        if (fareSource !in ALLOWED_FARE_SOURCES) {
            return "Invalid fare source. Choose Government Gazette, RTO Tariff, or Operator Tariff."
        }
        if (authorityRef.trim().length < 3) {
            return "Tariff or Gazette reference number/label is required for fare transparency."
        }
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(effectiveDateIso.trim())) {
            return "Effective date must follow YYYY-MM-DD format."
        }
        return null
    }

    fun canBookSchedule(schedule: BusSchedule, route: BusRoute? = null): Boolean {
        val routeApproved = route == null || route.fareApprovalStatus == "approved"
        return schedule.status in setOf("scheduled", "boarding") &&
            schedule.fareApprovalStatus == "approved" &&
            routeApproved &&
            schedule.farePerSeatInr > 0.0 &&
            schedule.totalSeats > 0
    }

    fun evaluateBusCancellationRefund(
        totalPaidInr: Double,
        hoursBeforeDeparture: Int = 6
    ): Pair<Double, Double> {
        // Policy: >=12h before -> 10% fee; <12h before -> 25% fee; 0 negative refund
        val feePct = if (hoursBeforeDeparture >= 12) 0.10 else 0.25
        val fee = round(totalPaidInr * feePct * 100.0) / 100.0
        val refund = round((totalPaidInr - fee).coerceAtLeast(0.0) * 100.0) / 100.0
        return fee to refund
    }

    fun generateSeatIds(totalSeats: Int): List<String> {
        val count = totalSeats.coerceIn(8, 60)
        val rows = (count + 3) / 4
        val ids = mutableListOf<String>()
        val cols = listOf("A", "B", "C", "D")
        for (r in 1..rows) {
            for (c in cols) {
                if (ids.size < count) {
                    ids.add("$r$c")
                }
            }
        }
        return ids
    }
}
