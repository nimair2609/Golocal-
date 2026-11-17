package com.example.domain

import com.example.data.model.Booking
import com.example.data.model.BusRoute
import com.example.data.model.BusSchedule
import com.example.data.model.DisputeRecord
import com.example.data.model.DriverDocumentItem
import com.example.data.model.DriverProfile
import com.example.data.model.FareConfig
import com.example.data.model.RatingRecord
import com.example.ocr.DocumentOcrAnalyzer
import com.example.ocr.OcrExtractionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernanceAndBusUnitTest {

    @Test
    fun `DocumentVerificationEngine enforces category requirements and expiry states`() {
        val nowMs = 1_767_225_600_000L // 2026-01-01T00:00:00Z
        assertEquals(DocumentExpiryStatus.EXPIRED, DocumentVerificationEngine.evaluateExpiry("2025-12-31", nowMs))
        assertEquals(DocumentExpiryStatus.EXPIRING_SOON, DocumentVerificationEngine.evaluateExpiry("2026-01-15", nowMs))
        assertEquals(DocumentExpiryStatus.VALID, DocumentVerificationEngine.evaluateExpiry("2027-06-30", nowMs))
        assertEquals(DocumentExpiryStatus.INVALID_FORMAT, DocumentVerificationEngine.evaluateExpiry("30/06/2027", nowMs))

        val incompleteCabDocs = listOf(
            DriverDocumentItem(docType = "DRIVING_LICENCE", documentNumber = "WB1520200001234", expiryDateIso = "2028-01-01"),
            DriverDocumentItem(docType = "RC", documentNumber = "WB15E1234", expiryDateIso = "2028-01-01"),
            DriverDocumentItem(docType = "INSURANCE", documentNumber = "INS99887766", expiryDateIso = "2028-01-01"),
            DriverDocumentItem(docType = "PUC", documentNumber = "PUC123456", expiryDateIso = "2028-01-01")
        )
        // Cab requires FITNESS and COMMERCIAL_PERMIT in addition to base docs
        val cabErr = DocumentVerificationEngine.validateSubmissionCompleteness("cab", incompleteCabDocs, nowMs)
        assertNotNull(cabErr)
        assertTrue(cabErr!!.contains("Fitness"))

        val validTotoErr = DocumentVerificationEngine.validateSubmissionCompleteness("toto", incompleteCabDocs, nowMs)
        assertNull(validTotoErr)

        // Rejection reason shorter than 5 chars must throw
        var threw = false
        try {
            DocumentVerificationEngine.buildAdminDecisionUpdate(
                existingDocs = incompleteCabDocs,
                existingHistory = emptyList(),
                decisionStatus = "rejected",
                reason = "no",
                adminUid = "admin_1"
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `DocumentOcrAnalyzer extracts Indian DL plate and expiry date and warns on mismatch`() {
        val sampleText = """
            GOVERNMENT OF WEST BENGAL
            DRIVING LICENCE NO: WB1520190012345
            VEHICLE NO: WB15AB1234
            ISSUE DATE: 12-03-2019
            VALID TILL: 11/03/2029
        """.trimIndent()

        val result = DocumentOcrAnalyzer.extractFromRecognizedText(
            rawText = sampleText,
            docType = "DRIVING_LICENCE",
            userEnteredNumber = "WB9999999999999",
            userEnteredExpiryIso = "2028-01-01"
        )
        assertEquals("WB1520190012345", result.suggestedNumber)
        assertEquals("2029-03-11", result.suggestedExpiryIso)
        assertTrue(result.mismatchWarning.contains("differs"))
        assertEquals(OcrExtractionResult.ASSISTIVE_OCR_DISCLAIMER, result.disclaimer)
        assertTrue(result.disclaimer.contains("does NOT verify government/RTO authenticity"))
    }

    @Test
    fun `CancellationAndFraudEngine calculates fees anomalies and moderated weighted ratings`() {
        val cfg = FareConfig(cancellationFeeInr = 20.0)
        val arrivingBooking = Booking(
            id = "b1",
            customerId = "cust1",
            assignedDriverId = "drv1",
            status = "arrived",
            paymentStatus = "paid_upi",
            agreedFareInr = 120.0
        )

        val custCancel = CancellationAndFraudEngine.evaluateRideCancellation(arrivingBooking, "customer", "changed_plans", cfg)
        assertTrue(custCancel.allowed)
        assertEquals(20.0, custCancel.cancellationFeeInr, 0.01)
        assertEquals(100.0, custCancel.refundAmountInr, 0.01)

        // Driver fault excuses customer penalty
        val driverFault = CancellationAndFraudEngine.evaluateRideCancellation(arrivingBooking, "customer", "driver_asked", cfg)
        assertEquals(0.0, driverFault.cancellationFeeInr, 0.01)
        assertEquals(120.0, driverFault.refundAmountInr, 0.01)

        // Driver cancelling sets 0 fee and full refund
        val drvCancel = CancellationAndFraudEngine.evaluateRideCancellation(arrivingBooking, "driver", "vehicle_breakdown", cfg)
        assertEquals(0.0, drvCancel.cancellationFeeInr, 0.01)
        assertEquals(120.0, drvCancel.refundAmountInr, 0.01)

        // Weighted rating excludes hidden moderation entries
        val ratings = listOf(
            RatingRecord(bookingId = "b1", driverId = "drv1", stars = 5, behaviourStars = 5, vehicleStars = 5, punctualityStars = 5, safetyStars = 5, moderationStatus = "active"),
            RatingRecord(bookingId = "b2", driverId = "drv1", stars = 1, behaviourStars = 1, vehicleStars = 1, punctualityStars = 1, safetyStars = 1, moderationStatus = "hidden")
        )
        val (avg, count) = CancellationAndFraudEngine.computeWeightedRatingAggregate(ratings, "drv1")
        assertEquals(5.0, avg, 0.01)
        assertEquals(1L, count)

        // Anomaly engine catches repeated pair cancellations & customer cancellations
        val repeatedCancelledBookings = (1..3).map { idx ->
            Booking(
                id = "c$idx",
                customerId = "cust_repeat",
                customerName = "Repeat Cust",
                assignedDriverId = "drv_pair",
                status = "cancelled",
                cancelledByRole = "customer",
                cancellationFeeInr = 20.0
            )
        }
        val flags = CancellationAndFraudEngine.analyzeAnomalies(
            bookings = repeatedCancelledBookings,
            drivers = listOf(DriverProfile(driverId = "drv_pair", legalName = "Pair Driver")),
            disputes = listOf(DisputeRecord(targetUserId = "drv_pair", status = "open"))
        )
        assertTrue(flags.any { it.patternType == "repeated_customer_cancellations" })
        assertTrue(flags.any { it.patternType == "suspicious_pair_cancellations" })
    }

    @Test
    fun `BusFareEngine blocks unapproved fares calculates GST and generates seat layout`() {
        val pendingSchedule = BusSchedule(
            id = "s1",
            farePerSeatInr = 80.0,
            totalSeats = 36,
            fareApprovalStatus = "pending_fare_approval",
            status = "scheduled"
        )
        assertFalse(BusFareEngine.canBookSchedule(pendingSchedule))

        val approvedSchedule = pendingSchedule.copy(fareApprovalStatus = "approved")
        assertTrue(BusFareEngine.canBookSchedule(approvedSchedule))
        assertFalse(BusFareEngine.canBookSchedule(approvedSchedule, BusRoute(fareApprovalStatus = "rejected")))

        val breakdown = BusFareEngine.calculateBreakdown(farePerSeatInr = 100.0, seatCount = 2, gstPercent = 5.0, commissionPercent = 8.0)
        assertEquals(200.0, breakdown.baseFareInr, 0.01)
        assertEquals(10.0, breakdown.gstInr, 0.01)
        assertEquals(210.0, breakdown.totalCustomerPayableInr, 0.01)
        assertEquals(16.0, breakdown.platformCommissionInr, 0.01)
        assertEquals(184.0, breakdown.netOperatorPayoutInr, 0.01)

        val seats = BusFareEngine.generateSeatIds(36)
        assertEquals(36, seats.size)
        assertEquals("1A", seats.first())
        assertEquals("9D", seats.last())

        val (cancelFee, cancelRefund) = BusFareEngine.evaluateBusCancellationRefund(210.0, hoursBeforeDeparture = 12)
        assertEquals(21.0, cancelFee, 0.01)
        assertEquals(189.0, cancelRefund, 0.01)
    }
}
