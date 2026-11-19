package com.example.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.admin.resolveAdminDeepLinkModule
import com.example.customer.resolveCustomerDeepLink
import com.example.data.model.AppNotification
import com.example.rider.resolveRiderDeepLinkTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationPipelineTest {

    @Test
    fun `fromIntent parses raw FCM background extras and clickAction`() {
        val rawFcmIntent = Intent(NotificationDeepLinkExtras.ACTION_OPEN_RIDER).apply {
            putExtra("targetScreen", "requests")
            putExtra("bookingId", "bk_live_77")
            putExtra("type", "new_ride_request")
            putExtra("isTestRide", "false")
        }

        val parsed = NotificationDeepLinkTarget.fromIntent(rawFcmIntent)
        assertNotNull(parsed)
        assertEquals("requests", parsed?.targetScreen)
        assertEquals("bk_live_77", parsed?.bookingId)
        assertEquals("new_ride_request", parsed?.type)
        assertEquals("rider", parsed?.targetApp)
        assertFalse(parsed?.isTestRide ?: true)
    }

    @Test
    fun `resolveCustomerDeepLink routes booking chat bus and history accurately`() {
        val assigned = resolveCustomerDeepLink(
            NotificationDeepLinkTarget(targetScreen = "booking", bookingId = "bk1", type = "booking_assigned"),
            hasActiveBooking = true
        )
        assertTrue(assigned.openBookingFlow)
        assertFalse(assigned.openBusFlow)

        val chat = resolveCustomerDeepLink(
            NotificationDeepLinkTarget(targetScreen = "chat", bookingId = "bk1", type = "chat"),
            hasActiveBooking = true
        )
        assertTrue(chat.openBookingFlow)

        val completed = resolveCustomerDeepLink(
            NotificationDeepLinkTarget(targetScreen = "bookings", bookingId = "bk1", type = "booking_completed"),
            hasActiveBooking = false
        )
        assertFalse(completed.openBookingFlow)
        assertEquals("bookings", completed.selectedTab)

        val bus = resolveCustomerDeepLink(
            NotificationDeepLinkTarget(targetScreen = "bus", bookingId = "bus1", type = "bus_confirmed"),
            hasActiveBooking = false
        )
        assertTrue(bus.openBusFlow)
    }

    @Test
    fun `resolveRiderDeepLinkTab routes commercial and test rider notifications accurately`() {
        assertEquals(
            "requests",
            resolveRiderDeepLinkTab(
                NotificationDeepLinkTarget(targetScreen = "requests", type = "new_ride_request"),
                inTestMode = false
            )
        )
        assertEquals(
            "home",
            resolveRiderDeepLinkTab(
                NotificationDeepLinkTarget(targetScreen = "home", type = "driver_booking_assigned"),
                inTestMode = false
            )
        )
        assertEquals(
            "profile",
            resolveRiderDeepLinkTab(
                NotificationDeepLinkTarget(targetScreen = "profile", type = "kyc_status_update"),
                inTestMode = false
            )
        )
        assertEquals(
            "rides",
            resolveRiderDeepLinkTab(
                NotificationDeepLinkTarget(targetScreen = "rides", type = "test_request", isTestRide = true),
                inTestMode = true
            )
        )
        assertEquals(
            "home",
            resolveRiderDeepLinkTab(
                NotificationDeepLinkTarget(targetScreen = "test_rider_dashboard", type = "test_driver_booking_assigned", isTestRide = true),
                inTestMode = true
            )
        )
    }

    @Test
    fun `resolveAdminDeepLinkModule handles SOS complaints KYC and Test Rider events`() {
        assertEquals(
            "bookings",
            resolveAdminDeepLinkModule(
                null,
                NotificationDeepLinkTarget(targetScreen = "bookings", type = "sos_alert")
            )
        )
        assertEquals(
            "bookings",
            resolveAdminDeepLinkModule(
                null,
                NotificationDeepLinkTarget(targetScreen = "bookings", type = "complaint_opened")
            )
        )
        assertEquals(
            "riders",
            resolveAdminDeepLinkModule(
                null,
                NotificationDeepLinkTarget(targetScreen = "riders", type = "new_rider_application")
            )
        )
        assertEquals(
            "test_rides",
            resolveAdminDeepLinkModule(
                null,
                NotificationDeepLinkTarget(targetScreen = "test_rides", type = "test_rider_request", isTestRide = true)
            )
        )
    }

    @Test
    fun `multi-device token IDs, channel isolation, and privacy sanitizer enforce security requirements`() {
        val docA = NotificationHelper.tokenDocId("user1", "customer", "deviceA")
        val docB = NotificationHelper.tokenDocId("user1", "customer", "deviceB")
        val docRider = NotificationHelper.tokenDocId("user1", "rider", "deviceA")
        assertTrue(docA != docB)
        assertTrue(docA != docRider)

        assertEquals(
            NotificationHelper.CHANNEL_TEST_RIDES,
            NotificationHelper.channelForType("booking_assigned", isTestRide = true)
        )
        assertEquals(
            NotificationHelper.CHANNEL_RIDES,
            NotificationHelper.channelForType("booking_assigned", isTestRide = false)
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        NotificationHelper.ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelIds = nm.notificationChannels.map { it.id }.toSet()
        assertTrue(NotificationHelper.CHANNEL_RIDES in channelIds)
        assertTrue(NotificationHelper.CHANNEL_RIDE_REQUESTS_V2 in channelIds)
        assertTrue(NotificationHelper.CHANNEL_TEST_RIDE_REQUESTS_V2 in channelIds)
        assertTrue(NotificationHelper.CHANNEL_CHAT in channelIds)
        assertTrue(NotificationHelper.CHANNEL_SOS in channelIds)
        assertTrue(NotificationHelper.CHANNEL_TEST_RIDES in channelIds)
        assertTrue(NotificationHelper.CHANNEL_GENERAL in channelIds)

        val soundUri = NotificationHelper.rideRequestSoundUri(context).toString()
        assertTrue(soundUri.startsWith("android.resource://${context.packageName}/"))
        assertTrue(com.example.R.raw.golocal_ride_request != 0)

        val scrubbed = NotificationPrivacySanitizer.sanitizeText("Share OTP 492817 or call +919876543210")
        assertFalse(scrubbed.contains("492817"))
        assertFalse(scrubbed.contains("9876543210"))

        val testNotif = AppNotification(id = "n1", type = "test_otp", isTestRide = true)
        val prodNotif = AppNotification(id = "n2", type = "booking_assigned", isTestRide = false)
        assertTrue(testNotif.isTestNotification())
        assertFalse(prodNotif.isTestNotification())
    }
}
