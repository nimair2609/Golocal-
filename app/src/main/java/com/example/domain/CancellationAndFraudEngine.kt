package com.example.domain

import com.example.data.model.AnomalyFlag
import com.example.data.model.Booking
import com.example.data.model.DisputeRecord
import com.example.data.model.DriverProfile
import com.example.data.model.FareConfig
import com.example.data.model.RatingRecord
import kotlin.math.round

data class CancellationEvaluation(
    val allowed: Boolean,
    val cancellationFeeInr: Double,
    val refundAmountInr: Double,
    val summary: String,
    val error: String? = null
)

object CancellationAndFraudEngine {

    fun evaluateRideCancellation(
        booking: Booking,
        actorRole: String, // "customer" or "driver"
        reasonCode: String,
        fareConfig: FareConfig?
    ): CancellationEvaluation {
        if (booking.status == "completed" || booking.status == "cancelled") {
            return CancellationEvaluation(
                allowed = false,
                cancellationFeeInr = 0.0,
                refundAmountInr = 0.0,
                summary = "",
                error = "Ride is already ${booking.status} and cannot be cancelled."
            )
        }
        val paidAmount = if (booking.paymentStatus == "paid_upi") {
            booking.agreedFareInr.takeIf { it > 0.0 } ?: booking.estimatedFareInr
        } else {
            0.0
        }

        val nominalFee = fareConfig?.cancellationFeeInr ?: 15.0
        val afterArrivalOrTripStart = booking.status in setOf("arrived", "in_progress")
        val safetyOrDriverFault = reasonCode in setOf("driver_asked", "unsafe_pickup")

        val fee = when {
            actorRole == "driver" -> 0.0
            safetyOrDriverFault -> 0.0
            afterArrivalOrTripStart -> nominalFee
            else -> 0.0
        }

        val refund = if (paidAmount > 0.0) {
            (paidAmount - fee).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val summary = when {
            actorRole == "driver" && paidAmount > 0.0 ->
                "Rider cancelled. Customer receives full refund ₹${refund.toInt()}."
            actorRole == "driver" ->
                "Rider cancelled. Ride re-queued or closed without customer penalty."
            fee > 0.0 && refund > 0.0 ->
                "Post-arrival cancellation: nominal fee ₹${fee.toInt()}, net refund ₹${refund.toInt()}."
            fee > 0.0 ->
                "Post-arrival cancellation fee recorded: ₹${fee.toInt()}."
            refund > 0.0 ->
                "Free cancellation: full UPI refund ₹${refund.toInt()} queued."
            else ->
                "Free cancellation confirmed with ₹0 penalty."
        }

        return CancellationEvaluation(
            allowed = true,
            cancellationFeeInr = round(fee * 100.0) / 100.0,
            refundAmountInr = round(refund * 100.0) / 100.0,
            summary = summary
        )
    }

    fun computeWeightedRatingAggregate(
        ratings: List<RatingRecord>,
        driverId: String
    ): Pair<Double, Long> {
        val visible = ratings.filter { it.driverId == driverId && it.moderationStatus != "hidden" }
        if (visible.isEmpty()) return 5.0 to 0L
        val avg = visible.map { rec ->
            val subAvg = (rec.behaviourStars + rec.vehicleStars + rec.punctualityStars + rec.safetyStars) / 4.0
            if (rec.stars in 1L..5L) (rec.stars * 0.4 + subAvg * 0.6) else subAvg
        }.average()
        val rounded = round(avg * 100.0) / 100.0
        return rounded to visible.size.toLong()
    }

    fun analyzeAnomalies(
        bookings: List<Booking>,
        drivers: List<DriverProfile>,
        disputes: List<DisputeRecord>
    ): List<AnomalyFlag> {
        val flags = mutableListOf<AnomalyFlag>()
        val driverNames = drivers.associate { it.driverId to it.legalName }

        // 1. High frequency customer cancellations
        val cancelledByCustomer = bookings.filter { it.status == "cancelled" && it.cancelledByRole == "customer" }
        cancelledByCustomer.groupBy { it.customerId }.forEach { (custId, list) ->
            if (custId.isNotBlank() && list.size >= 3) {
                val postArrival = list.count { it.cancellationFeeInr > 0.0 }
                val custName = list.firstOrNull()?.customerName?.ifBlank { custId } ?: custId
                flags.add(
                    AnomalyFlag(
                        flagId = "cust_cancel_$custId",
                        targetUserId = custId,
                        targetRole = "customer",
                        targetName = custName,
                        severity = if (postArrival >= 2 || list.size >= 5) "high" else "medium",
                        patternType = "repeated_customer_cancellations",
                        reasonSummary = "${list.size} cancelled bookings ($postArrival post-arrival) detected for Admin review.",
                        evidenceCount = list.size
                    )
                )
            }
        }

        // 2. High frequency driver cancellations
        val cancelledByDriver = bookings.filter { it.status == "cancelled" && it.cancelledByRole == "driver" }
        cancelledByDriver.groupBy { it.assignedDriverId }.forEach { (drvId, list) ->
            if (drvId.isNotBlank() && list.size >= 2) {
                val dName = driverNames[drvId] ?: list.firstOrNull()?.assignedDriverName?.ifBlank { drvId } ?: drvId
                flags.add(
                    AnomalyFlag(
                        flagId = "drv_cancel_$drvId",
                        targetUserId = drvId,
                        targetRole = "driver",
                        targetName = dName,
                        severity = if (list.size >= 4) "high" else "medium",
                        patternType = "repeated_driver_cancellations",
                        reasonSummary = "${list.size} assigned rides cancelled by driver. Review operational reliability.",
                        evidenceCount = list.size
                    )
                )
            }
        }

        // 3. Repeated mutual customer-driver pair cancellations / possible collusion
        bookings.filter { it.status == "cancelled" && it.customerId.isNotBlank() && it.assignedDriverId.isNotBlank() }
            .groupBy { "${it.customerId}::${it.assignedDriverId}" }
            .forEach { (pairKey, pairList) ->
                if (pairList.size >= 2) {
                    val cId = pairList.first().customerId
                    val dId = pairList.first().assignedDriverId
                    flags.add(
                        AnomalyFlag(
                            flagId = "pair_cancel_$pairKey",
                            targetUserId = dId,
                            targetRole = "pair",
                            targetName = "${pairList.first().customerName} <-> ${driverNames[dId] ?: dId}",
                            severity = "high",
                            patternType = "suspicious_pair_cancellations",
                            reasonSummary = "${pairList.size} cancelled bookings between identical customer ($cId) & driver ($dId).",
                            evidenceCount = pairList.size
                        )
                    )
                }
            }

        // 4. Open dispute spikes per target user
        disputes.filter { it.status == "open" && it.targetUserId.isNotBlank() }
            .groupBy { it.targetUserId }
            .forEach { (uid, list) ->
                if (list.size >= 2) {
                    flags.add(
                        AnomalyFlag(
                            flagId = "dispute_spike_$uid",
                            targetUserId = uid,
                            targetRole = "account",
                            targetName = driverNames[uid] ?: uid,
                            severity = "high",
                            patternType = "multiple_open_disputes",
                            reasonSummary = "${list.size} open disputes targeting account require immediate resolution.",
                            evidenceCount = list.size
                        )
                    )
                }
            }

        return flags.sortedByDescending { if (it.severity == "high") 2 else 1 }
    }
}
