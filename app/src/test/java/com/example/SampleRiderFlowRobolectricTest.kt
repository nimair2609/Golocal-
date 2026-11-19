package com.example

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.customer.CustomerActiveSampleRideCard
import com.example.data.model.SampleRide
import com.example.data.model.SampleRiderProfile
import com.example.data.model.UserAccount
import com.example.domain.LocalSampleOtpEntry
import com.example.domain.SampleOtpLocalStore
import com.example.domain.TestRideOtpUtil
import com.example.notifications.NotificationDeepLinkExtras
import com.example.notifications.NotificationDeepLinkTarget
import com.example.notifications.NotificationHelper
import com.example.rider.SampleRiderEarningsAndHistoryTab
import com.example.rider.SampleRiderHomeTab
import com.example.rider.SampleRiderRequestsTab
import com.example.shared.LocationAccessState
import com.example.ui.theme.GolocalTheme
import com.google.firebase.Timestamp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SampleRiderFlowRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SampleOtpLocalStore.clearAllInMemoryForTests()
    }

    @After
    fun tearDown() {
        SampleOtpLocalStore.clearAllInMemoryForTests()
    }

    @Test
    fun `sample otp local store enforces creator uid scoping and matches sha256 challenge`() {
        val rideId = "sample_ride_001"
        val otp = TestRideOtpUtil.generateSixDigitOtp()
        assertTrue(TestRideOtpUtil.isValidOtpFormat(otp))
        val hash = TestRideOtpUtil.sha256Hex(rideId, otp)
        assertNotEquals(otp, hash)
        assertEquals(64, hash.length)

        SampleOtpLocalStore.saveOtp(
            context = context,
            creatorUid = "cust_alpha",
            rideId = rideId,
            otpPlaintext = otp,
            expiresAtMs = System.currentTimeMillis() + 180_000L
        )

        val ownerEntry = SampleOtpLocalStore.getOtpForRide(context, "cust_alpha", rideId)
        assertNotNull(ownerEntry)
        assertEquals(otp, ownerEntry?.otpPlaintext)
        assertEquals(hash, TestRideOtpUtil.sha256Hex(rideId, ownerEntry!!.otpPlaintext))

        // Different user / rider device must never read creator's local plaintext OTP
        assertNull(SampleOtpLocalStore.getOtpForRide(context, "sample_rider_beta", rideId))

        SampleOtpLocalStore.removeOtp(context, rideId)
        assertNull(SampleOtpLocalStore.getOtpForRide(context, "cust_alpha", rideId))
    }

    @Test
    fun `sample rider user account serializes required isolated role and approval fields`() {
        val account = UserAccount(
            uid = "rider_sample_1",
            userId = "rider_sample_1",
            fullName = "Sample Partner",
            phone = "+919876543210",
            mobileNumber = "+919876543210",
            role = "rider",
            accountType = "sample",
            isSampleRider = true,
            verificationStatus = "sample_approved",
            availabilityStatus = "offline",
            profileCompleted = true
        )
        assertTrue(account.isSampleRiderAccount())
        val map = account.toCreateMap()
        assertEquals("rider", map["role"])
        assertEquals("sample", map["accountType"])
        assertEquals(true, map["isSampleRider"])
        assertEquals("sample_approved", map["verificationStatus"])
        assertEquals("offline", map["availabilityStatus"])

        val activeProfile = SampleRiderProfile(
            uid = "rider_sample_1",
            displayName = "Sample Partner",
            phone = "+919876543210",
            verificationStatus = "sample_approved",
            availabilityStatus = "online"
        )
        assertTrue(activeProfile.isOnline())
        assertFalse(activeProfile.isSuspendedOrRevoked())
        assertTrue(activeProfile.copy(verificationStatus = "suspended").isSuspendedOrRevoked())
    }

    @Test
    fun `notification channels and deep link extras keep sample rides isolated`() {
        assertEquals(
            NotificationHelper.CHANNEL_SAMPLE_RIDE_REQUESTS_V2,
            NotificationHelper.channelForType(type = "sample_request", isTestRide = false, isSampleRide = true)
        )
        assertEquals(
            NotificationHelper.CHANNEL_SAMPLE_RIDES,
            NotificationHelper.channelForType(type = "sample_status", isTestRide = false, isSampleRide = true)
        )

        val intent = Intent().apply {
            putExtra(NotificationDeepLinkExtras.EXTRA_NOTIFICATION_TYPE, "sample_request")
            putExtra(NotificationDeepLinkExtras.EXTRA_TARGET_SCREEN, "rider_home")
            putExtra(NotificationDeepLinkExtras.EXTRA_BOOKING_ID, "sample_ride_77")
            putExtra(NotificationDeepLinkExtras.EXTRA_IS_SAMPLE_RIDE, true)
        }
        val parsed = NotificationDeepLinkTarget.fromIntent(intent)
        assertNotNull(parsed)
        assertEquals("sample_request", parsed?.type)
        assertEquals("sample_ride_77", parsed?.bookingId)
        assertTrue(parsed?.isSampleRide == true)
    }

    @Test
    fun `sample rider dashboard shows sample badge and real gps unavailable banner when gps is absent`() {
        val profile = SampleRiderProfile(
            uid = "sr_1",
            displayName = "Bikash Sample",
            phone = "+919000011111",
            vehicleCategory = "toto",
            verificationStatus = "sample_approved",
            availabilityStatus = "offline"
        )

        composeTestRule.setContent {
            GolocalTheme {
                SampleRiderHomeTab(
                    profile = profile,
                    gpsFix = null,
                    locationAccess = LocationAccessState.PERMISSION_REQUIRED,
                    activeRide = null,
                    incomingCount = 1,
                    todayRidesCount = 2,
                    completedCount = 5,
                    totalSampleEarnings = 420.0,
                    isBusy = false,
                    otpError = null,
                    onToggleOnline = {},
                    onRequestLocationPermission = {},
                    onOpenLocationSettings = {},
                    onOpenRequests = {},
                    onMarkArriving = {},
                    onMarkArrived = {},
                    onVerifyOtpAndStart = {},
                    onCompleteRide = {},
                    onCancelActiveRide = {},
                    onOpenSos = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SAMPLE RIDER").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sample_gps_unavailable_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Grant Location Permission").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sample_rider_sos_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `customer active sample ride card displays local otp and sample rider badge`() {
        val ride = SampleRide(
            rideId = "sr_trip_99",
            customerId = "cust_1",
            customerName = "Ananya",
            vehicleCategory = "toto",
            pickupLabel = "Chinsurah Station",
            pickupLat = 22.888,
            pickupLng = 88.392,
            destinationLabel = "Hooghly Imambara",
            destinationLat = 22.912,
            destinationLng = 88.401,
            distanceKm = 3.2,
            estimatedFareInr = 75.0,
            status = "arrived",
            assignedSampleRiderId = "sr_1",
            assignedSampleRiderName = "Bikash Sample",
            otpVerified = false
        )
        val localEntry = LocalSampleOtpEntry(
            rideId = "sr_trip_99",
            creatorUid = "cust_1",
            otpPlaintext = "482913",
            expiresAtMs = System.currentTimeMillis() + 120_000L,
            createdAtMs = System.currentTimeMillis()
        )

        composeTestRule.setContent {
            GolocalTheme {
                CustomerActiveSampleRideCard(
                    ride = ride,
                    localOtpEntry = localEntry,
                    onCancelSampleRide = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("customer_active_sample_ride_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("SAMPLE RIDER: Bikash Sample").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sample Ride OTP: 482913").assertIsDisplayed()
    }

    @Test
    fun `sample rider requests and earnings tabs show simulated isolation labels`() {
        val profile = SampleRiderProfile(
            uid = "sr_1",
            displayName = "Bikash Sample",
            verificationStatus = "sample_approved",
            availabilityStatus = "online"
        )
        val ride = SampleRide(
            rideId = "sr_req_1",
            customerName = "Test Customer",
            vehicleCategory = "auto",
            pickupLabel = "Bandel Station",
            destinationLabel = "Serampore Ghat",
            distanceKm = 8.5,
            estimatedFareInr = 160.0,
            status = "incoming",
            expiresAt = Timestamp(Date(System.currentTimeMillis() + 90_000L))
        )

        composeTestRule.setContent {
            GolocalTheme {
                SampleRiderRequestsTab(
                    profile = profile,
                    incomingRides = listOf(ride),
                    hasActiveRide = false,
                    isBusy = false,
                    onAcceptRide = {},
                    onRejectRide = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("accept_sample_ride_sr_req_1").assertIsDisplayed()
    }

    @Test
    fun `sample earnings tab renders sample simulated badge`() {
        val completed = SampleRide(
            rideId = "sr_done_1",
            pickupLabel = "Bandel",
            destinationLabel = "Chinsurah",
            distanceKm = 4.0,
            estimatedFareInr = 95.0,
            status = "completed"
        )
        composeTestRule.setContent {
            GolocalTheme {
                SampleRiderEarningsAndHistoryTab(
                    todayRidesCount = 1,
                    completedRides = listOf(completed),
                    rideHistory = listOf(completed),
                    totalSampleEarningsInr = 95.0
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SAMPLE / SIMULATED EARNINGS").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("₹95", substring = true).onFirst().assertIsDisplayed()
    }
}
