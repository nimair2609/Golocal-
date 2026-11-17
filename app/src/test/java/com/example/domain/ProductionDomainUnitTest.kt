package com.example.domain

import com.example.data.model.Booking
import com.example.data.model.BusRoute
import com.example.map.GeoPoint
import com.example.map.GpsBroadcastThrottle
import com.example.map.LocationSample
import com.example.map.OsrmRouteService
import com.example.map.RouteUiState
import com.example.notifications.NotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProductionDomainUnitTest {

    @Test
    fun `BusFareEngine gates activation on approved fareSource and effectiveDateLabel`() {
        val unconfigured = BusRoute(
            id = "route_1",
            originStop = "Kolkata",
            destinationStop = "Durgapur",
            distanceKm = 168.0,
            baseSeatFareInr = 250.0,
            fareSource = "",
            effectiveDateLabel = ""
        )
        assertFalse(BusFareEngine.isRoutePricingConfigured(unconfigured))
        assertEquals("pending_pricing", BusFareEngine.resolveRouteStatusForAdmin(unconfigured, requestedActive = true))
        assertEquals("suspended", BusFareEngine.resolveRouteStatusForAdmin(unconfigured, requestedActive = false))

        val configured = unconfigured.copy(
            fareSource = "WB Transport Tariff Gaz. #2025-11",
            effectiveDateLabel = "2025-11-01"
        )
        assertTrue(BusFareEngine.isRoutePricingConfigured(configured))
        assertEquals("active", BusFareEngine.resolveRouteStatusForAdmin(configured, requestedActive = true))
    }

    @Test
    fun `BusFareEngine calculates GST and commission accurately and validates seat bounds`() {
        val quote = BusFareEngine.calculateBookingQuote(
            farePerSeatInr = 300.0,
            seatCount = 2,
            gstPercent = 5.0,
            commissionPercent = 10.0,
            fareSource = "WB Transport Tariff",
            effectiveDateLabel = "2025-11-01"
        )
        assertEquals(600.0, quote.subTotalInr, 0.01)
        assertEquals(30.0, quote.gstInr, 0.01)
        assertEquals(630.0, quote.totalFareInr, 0.01)
        assertEquals(60.0, quote.platformCommissionInr, 0.01)
        assertEquals(540.0, quote.operatorNetPayoutInr, 0.01)

        var threwSeatBounds = false
        try {
            BusFareEngine.calculateBookingQuote(
                farePerSeatInr = 300.0,
                seatCount = 7,
                gstPercent = 5.0,
                commissionPercent = 10.0,
                fareSource = "WB Transport Tariff",
                effectiveDateLabel = "2025-11-01"
            )
        } catch (_: IllegalArgumentException) {
            threwSeatBounds = true
        }
        assertTrue(threwSeatBounds)

        val payload = BusFareEngine.buildQrTicketPayload("b1", "GLB-1001", "sched_1", listOf("A2", "A1"))
        assertTrue(BusFareEngine.isValidQrTicketPayload(payload, "b1", "sched_1"))
        assertFalse(BusFareEngine.isValidQrTicketPayload(payload, "other", "sched_1"))
    }

    @Test
    fun `RequiredDocumentsCatalog maps per-category compliance and assesses document expiries`() {
        val totoDocs = RequiredDocumentsCatalog.requiredFor("toto")
        assertTrue(KycDocType.DRIVING_LICENCE in totoDocs)
        assertFalse(KycDocType.FITNESS in totoDocs)

        val cabDocs = RequiredDocumentsCatalog.requiredFor("cab")
        assertEquals(6, cabDocs.size)
        assertTrue(KycDocType.PERMIT in cabDocs)
        assertTrue(KycDocType.PUC in cabDocs)

        val today = 20_000L
        assertEquals(
            ExpiryStatus.NOT_PROVIDED,
            RequiredDocumentsCatalog.assessExpiry(KycDocType.DRIVING_LICENCE, "", 0L, today).status
        )
        assertEquals(
            ExpiryStatus.EXPIRED,
            RequiredDocumentsCatalog.assessExpiry(KycDocType.DRIVING_LICENCE, "WB01", today - 1L, today).status
        )
        assertEquals(
            ExpiryStatus.EXPIRING_SOON,
            RequiredDocumentsCatalog.assessExpiry(KycDocType.INSURANCE, "INS01", today + 15L, today).status
        )
        assertEquals(
            ExpiryStatus.VALID,
            RequiredDocumentsCatalog.assessExpiry(KycDocType.PUC, "PUC01", today + 180L, today).status
        )
    }

    @Test
    fun `BookingChatValidator enforces lifecycle write lock and 500 char ceiling`() {
        assertTrue(BookingChatValidator.isChatWriteAllowed("assigned"))
        assertTrue(BookingChatValidator.isChatWriteAllowed("arriving"))
        assertTrue(BookingChatValidator.isChatWriteAllowed("in_progress"))
        assertFalse(BookingChatValidator.isChatWriteAllowed("completed"))
        assertFalse(BookingChatValidator.isChatWriteAllowed("cancelled"))

        assertTrue(BookingChatValidator.isChatReadAllowed("completed"))
        assertTrue(BookingChatValidator.isChatReadAllowed("cancelled"))

        assertNotNull(BookingChatValidator.validateMessageText("   "))
        assertNotNull(BookingChatValidator.validateMessageText("a".repeat(501)))
        assertNull(BookingChatValidator.validateMessageText("Wait near Gate 2 please"))
    }

    @Test
    fun `AbuseDetectionEngine flags repeated customer and post-assignment driver cancellations`() {
        val bookings = listOf(
            Booking(id = "1", customerId = "cust_1", status = "cancelled", cancelledByRole = "customer"),
            Booking(id = "2", customerId = "cust_1", status = "cancelled", cancelledByRole = "customer"),
            Booking(id = "3", customerId = "cust_1", status = "cancelled", cancelledByRole = "customer"),
            Booking(id = "4", customerId = "cust_1", status = "completed"),
            Booking(id = "5", customerId = "cust_2", assignedDriverId = "drv_1", status = "cancelled", cancelledByRole = "driver"),
            Booking(id = "6", customerId = "cust_3", assignedDriverId = "drv_1", status = "cancelled", cancellationReason = "Driver asked to cancel offline")
        )
        val advisories = AbuseDetectionEngine.evaluateCancellationAdvisories(bookings)
        assertTrue(advisories.any { it.userId == "cust_1" && it.role == "customer" && it.cancelledCount == 3 })
        assertTrue(advisories.any { it.userId == "drv_1" && it.role == "driver" && it.cancelledCount == 2 })
    }

    @Test
    fun `OsrmRouteService parses valid GeoJSON LineString into lat-lng GeoPoints and rejects malformed payloads`() {
        val sampleJson = """
            {
              "code": "Ok",
              "routes": [
                {
                  "distance": 12450.0,
                  "duration": 1320.0,
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [
                      [88.3639, 22.5726],
                      [88.3810, 22.5850],
                      [88.4100, 22.6010]
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        val parsed = OsrmRouteService.parseOsrmGeoJsonResponse(sampleJson)
        assertTrue(parsed is RouteUiState.Ready)
        assertEquals(3, parsed!!.points.size)
        assertEquals(GeoPoint(22.5726, 88.3639), parsed.points.first())
        assertEquals(12.5, parsed.distanceKm, 0.2)
        assertEquals(22L, parsed.durationMin)

        assertNull(OsrmRouteService.parseOsrmGeoJsonResponse(""))
        assertNull(OsrmRouteService.parseOsrmGeoJsonResponse("""{"code":"NoRoute","routes":[]}"""))
    }

    @Test
    fun `GpsBroadcastThrottle rejects invalid accuracy and throttles rapid or small jitter updates`() {
        val throttle = GpsBroadcastThrottle(minIntervalMs = 3_000L, minDistanceMeters = 15.0, maxAcceptedAccuracyMeters = 100f)

        // Poor GPS fix (>100m accuracy) rejected
        assertFalse(throttle.acceptIfQualified(LocationSample(22.5726, 88.3639, accuracyMeters = 140f, timestampMs = 1_000L)))
        // Null island (0,0) rejected
        assertFalse(throttle.acceptIfQualified(LocationSample(0.0, 0.0, accuracyMeters = 10f, timestampMs = 1_000L)))

        // First good fix accepted
        assertTrue(throttle.acceptIfQualified(LocationSample(22.5726, 88.3639, accuracyMeters = 12f, timestampMs = 1_000L)))
        // Too soon (< 3000ms) even if moved -> rejected
        assertFalse(throttle.acceptIfQualified(LocationSample(22.5740, 88.3639, accuracyMeters = 12f, timestampMs = 2_500L)))
        // Time elapsed (>= 3000ms) but negligible GPS jitter (~1m) -> rejected
        assertFalse(throttle.acceptIfQualified(LocationSample(22.57261, 88.3639, accuracyMeters = 12f, timestampMs = 5_000L)))
        // Both interval & distance >= 15m -> accepted
        assertTrue(throttle.acceptIfQualified(LocationSample(22.5745, 88.3639, accuracyMeters = 12f, timestampMs = 6_000L)))
    }

    @Test
    fun `NotificationHelper routes types to channels and suppresses duplicate notification keys`() {
        assertEquals(NotificationHelper.CHANNEL_CHAT, NotificationHelper.channelForType("chat"))
        assertEquals(NotificationHelper.CHANNEL_RIDES, NotificationHelper.channelForType("bus_booking"))
        assertEquals(NotificationHelper.CHANNEL_RIDES, NotificationHelper.channelForType("booking"))
        assertEquals(NotificationHelper.CHANNEL_SOS, NotificationHelper.channelForType("sos"))
        assertEquals(NotificationHelper.CHANNEL_GENERAL, NotificationHelper.channelForType("kyc_review"))

        val uniqueKey = "unit_test_dedupe_${System.nanoTime()}"
        assertTrue(NotificationHelper.shouldDisplayNotification(uniqueKey))
        assertFalse(NotificationHelper.shouldDisplayNotification(uniqueKey))
        assertTrue(NotificationHelper.shouldDisplayNotification(""))
    }

}
