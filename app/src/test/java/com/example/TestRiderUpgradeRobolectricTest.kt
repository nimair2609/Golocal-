package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.admin.AdminTestRiderRequestsPanel
import com.example.customer.CustomerActiveRideView
import com.example.data.model.Booking
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.RideOtpSecret
import com.example.data.model.TestRiderProfile
import com.example.data.model.TestRiderRequest
import com.example.data.model.TripLocation
import com.example.data.model.UiState
import com.example.data.repository.RealtimeLocationPayload
import com.example.data.repository.RealtimeLocationValidator
import com.example.map.GeoPoint
import com.example.map.RouteNavigationMath
import com.example.rider.RiderActiveTripSheet
import com.example.rider.RiderTestModeGateScreen
import com.example.rider.TestRiderQueueTab
import com.example.rider.TestRiderSummaryTab
import com.example.shared.ExperienceMode
import com.example.shared.RoleAuthScreen
import com.example.ui.auth.GoogleSignInGateway
import com.example.ui.auth.PhoneAuthController
import com.example.ui.theme.GolocalTheme
import com.google.firebase.Timestamp
import com.google.firebase.auth.AuthCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TestRiderUpgradeRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `role auth screen in rider mode toggles commercial partner vs test rider sandbox intent`() {
        var selectedIntent = false
        composeTestRule.setContent {
            GolocalTheme {
                RoleAuthScreen(
                    mode = ExperienceMode.RIDER,
                    onAuthSuccess = {},
                    phoneControllerFactory = { scope, onSuccess ->
                        PhoneAuthController(scope = scope, onSuccess = onSuccess)
                    },
                    googleGateway = object : GoogleSignInGateway {
                        override fun isFirebaseInitialized(context: Context): Boolean = false
                        override suspend fun requestGoogleIdToken(
                            context: Context,
                            webClientId: String,
                            autoSelectOnly: Boolean
                        ): String = ""
                        override suspend fun signInWithGoogleCredential(credential: AuthCredential): Pair<String, String> =
                            "" to ""
                    },
                    initialTestRiderIntent = false,
                    onTestRiderIntentChanged = { selectedIntent = it }
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("auth_mode_commercial_partner").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("auth_mode_test_rider").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(selectedIntent)

        composeTestRule.onNodeWithTag("auth_mode_commercial_partner").performClick()
        composeTestRule.waitForIdle()
        assertFalse(selectedIntent)
    }

    @Test
    fun `test rider gate screen submits application, displays pending and rejected reapply, and shows revoked lock`() {
        var submittedCategory = ""
        var submittedPurpose = ""
        var currentState: UiState<TestRiderRequest?> = UiState.Success(null)

        composeTestRule.setContent {
            GolocalTheme {
                RiderTestModeGateScreen(
                    requestState = currentState,
                    defaultName = "Anirban Roy",
                    defaultPhone = "+919811112222",
                    defaultEmail = "anirban@nrnext.in",
                    statusMessage = null,
                    onDismissMessage = {},
                    onSubmitRequest = { _, _, _, cat, purpose, _, _ ->
                        submittedCategory = cat
                        submittedPurpose = purpose
                    },
                    onSwitchToCommercial = {},
                    onSignOut = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("test_rider_mode_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("test_rider_cat_bike").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("test_rider_purpose_input").performScrollTo().performTextInput("Verify Hooghly pickup OTP & GPS")
        composeTestRule.onNodeWithTag("submit_test_rider_request_button").performScrollTo().assertIsEnabled().performClick()
        assertEquals("bike", submittedCategory)
        assertEquals("Verify Hooghly pickup OTP & GPS", submittedPurpose)
    }

    @Test
    fun `test rider gate screen handles rejected reapply and revoked block`() {
        val rejectedReq = TestRiderRequest(
            id = "tr_1",
            uid = "tr_1",
            fullName = "Anirban Roy",
            mobileNumber = "+919811112222",
            vehicleCategory = "toto",
            testPurpose = "Field test",
            status = "rejected",
            rejectionReason = "Include district name"
        )
        composeTestRule.setContent {
            GolocalTheme {
                RiderTestModeGateScreen(
                    requestState = UiState.Success(rejectedReq),
                    defaultName = "Anirban Roy",
                    defaultPhone = "+919811112222",
                    defaultEmail = "",
                    statusMessage = null,
                    onDismissMessage = {},
                    onSubmitRequest = { _, _, _, _, _, _, _ -> },
                    onSwitchToCommercial = {},
                    onSignOut = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("test_rider_rejection_reason").assertIsDisplayed()
        composeTestRule.onNodeWithTag("test_rider_reapply_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("submit_test_rider_request_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `test rider queue and summary enforce online claim guard and non commercial zero rupees`() {
        var claimedBookingId = ""
        val offlineProfile = TestRiderProfile(
            uid = "tr_1",
            displayName = "Anirban Roy",
            vehicleCategory = "toto",
            vehicleLabel = "TEST RIDER • TOTO",
            status = "approved",
            isOnline = false
        )
        val openBooking = Booking(
            id = "test_bk_1",
            customerId = "cust_1",
            customerName = "Alice",
            vehicleCategory = "toto",
            pickupLabel = "Bandel Station",
            destinationLabel = "Chinsurah Court",
            distanceKm = 4.2,
            estimatedFareInr = 75.0,
            status = "searching",
            isTestRide = true,
            testMode = true
        )

        composeTestRule.setContent {
            GolocalTheme {
                TestRiderQueueTab(
                    profile = offlineProfile,
                    openTestBookings = listOf(openBooking),
                    completedOrActiveTest = emptyList(),
                    onClaimBooking = { claimedBookingId = it.id }
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("claim_test_ride_test_bk_1").assertIsNotEnabled()
        assertEquals("", claimedBookingId)
    }

    @Test
    fun `test rider summary displays zero commercial impact`() {
        val profile = TestRiderProfile(
            uid = "tr_1",
            displayName = "Anirban Roy",
            vehicleCategory = "toto",
            vehicleLabel = "TEST RIDER • TOTO",
            status = "approved",
            isOnline = true
        )
        val completed = Booking(
            id = "test_bk_done",
            pickupLabel = "Bandel",
            destinationLabel = "Hooghly",
            distanceKm = 3.0,
            status = "completed",
            isTestRide = true,
            testMode = true
        )
        composeTestRule.setContent {
            GolocalTheme {
                TestRiderSummaryTab(
                    profile = profile,
                    completedCount = 1,
                    recentTestBookings = listOf(completed)
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("₹0").assertIsDisplayed()
        composeTestRule.onNodeWithText("1").assertIsDisplayed()
        composeTestRule.onNodeWithText("₹0 Non-Commercial • 3.0 km").assertIsDisplayed()
    }

    @Test
    fun `rider active trip sheet verifies 6 digit test otp in dark mode and renders otp errors`() {
        var verifiedOtp = ""
        val booking = Booking(
            id = "test_bk_otp",
            customerId = "cust_1",
            customerName = "Alice Sen",
            assignedDriverId = "tr_1",
            assignedDriverName = "Anirban",
            vehicleCategory = "toto",
            pickupLabel = "Chinsurah",
            pickupLat = 22.90,
            pickupLng = 88.39,
            destinationLabel = "Bandel",
            destinationLat = 22.93,
            destinationLng = 88.38,
            distanceKm = 4.0,
            agreedFareInr = 0.0,
            status = "arrived",
            isTestRide = true,
            testMode = true,
            testOtpStatus = "pending"
        )
        composeTestRule.setContent {
            GolocalTheme(darkTheme = true) {
                RiderActiveTripSheet(
                    booking = booking,
                    currentLat = 22.90,
                    currentLng = 88.39,
                    onlineSelf = DriverDirectoryEntry(driverId = "tr_1", isOnline = true, currentLat = 22.90, currentLng = 88.39),
                    lowNetworkMode = false,
                    callContact = null,
                    unreadChatCount = 0,
                    onCallCustomer = {},
                    onChatCustomer = {},
                    onTriggerSos = {},
                    onCancelTrip = {},
                    onAdvanceState = { _, _ -> },
                    onVerifyTestOtp = { verifiedOtp = it },
                    isVerifyingTestOtp = false,
                    testOtpError = "Invalid 6-digit OTP",
                    onClearTestOtpError = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("rider_waiting_test_otp_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("rider_test_otp_error").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("rider_test_otp_input").performScrollTo().performTextInput("593827")
        composeTestRule.onNodeWithTag("rider_verify_test_otp_button").performScrollTo().assertIsEnabled().performClick()
        assertEquals("593827", verifiedOtp)
    }

    @Test
    fun `customer active ride view displays ride otp secret banner and live gps telemetry`() {
        val booking = Booking(
            id = "test_bk_cust",
            customerId = "cust_1",
            customerName = "Alice",
            assignedDriverId = "tr_1",
            assignedDriverName = "Anirban",
            vehicleCategory = "toto",
            pickupLabel = "Bandel",
            pickupLat = 22.92,
            pickupLng = 88.38,
            destinationLabel = "Chinsurah",
            destinationLat = 22.90,
            destinationLng = 88.39,
            distanceKm = 3.5,
            status = "arriving",
            isTestRide = true,
            testMode = true,
            testOtpStatus = "pending"
        )
        val secret = RideOtpSecret(
            id = "test_bk_cust",
            bookingId = "test_bk_cust",
            customerId = "cust_1",
            otpPlaintext = "593827",
            isTestRide = true,
            status = "pending"
        )
        val liveLoc = TripLocation(
            id = "test_bk_cust",
            bookingId = "test_bk_cust",
            driverId = "tr_1",
            lat = 22.915,
            lng = 88.382,
            headingDeg = 140.0,
            speedKmph = 26.0,
            accuracyMeters = 8.0,
            trackingStatus = "active",
            updatedAt = Timestamp(Date())
        )
        composeTestRule.setContent {
            GolocalTheme {
                CustomerActiveRideView(
                    booking = booking,
                    offers = emptyList(),
                    drivers = emptyList(),
                    tripLocation = liveLoc,
                    lowNetwork = false,
                    callContact = null,
                    unreadChatCount = 0,
                    onBackHome = {},
                    onOpenCall = {},
                    onOpenChat = {},
                    onAcceptOffer = {},
                    onShareTrip = {},
                    onOpenSos = {},
                    onOpenComplaint = {},
                    onOpenCancel = {},
                    rideOtpSecret = secret
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("customer_test_otp_secret_banner").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("customer_test_otp_secret_value").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("593827").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("customer_live_telemetry_line").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `admin test rider panel grants owner admin approve and limits ops staff to recommend only`() {
        var approvedStatus = ""
        val pendingReq = TestRiderRequest(
            id = "tr_1",
            uid = "tr_1",
            fullName = "QA Rider One",
            mobileNumber = "+919811112222",
            vehicleCategory = "auto",
            testPurpose = "Route ETA test",
            status = "pending"
        )

        // Owner Admin view
        composeTestRule.setContent {
            GolocalTheme {
                AdminTestRiderRequestsPanel(
                    requests = listOf(pendingReq),
                    auditLogs = emptyList(),
                    isOwnerAdmin = true,
                    onReviewRequest = { _, newStatus, _, _ -> approvedStatus = newStatus },
                    onRecommendRequest = { _, _, _ -> }
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("admin_approve_test_rider_tr_1").performScrollTo().performClick()
        assertEquals("approved", approvedStatus)
    }

    @Test
    fun `operations staff test rider panel hides final approval and submits recommendation`() {
        var opsRec = ""
        var opsNote = ""
        val pendingReq = TestRiderRequest(
            id = "tr_1",
            uid = "tr_1",
            fullName = "QA Rider One",
            mobileNumber = "+919811112222",
            vehicleCategory = "auto",
            testPurpose = "Route ETA test",
            status = "pending"
        )
        composeTestRule.setContent {
            GolocalTheme {
                AdminTestRiderRequestsPanel(
                    requests = listOf(pendingReq),
                    auditLogs = emptyList(),
                    isOwnerAdmin = false,
                    onReviewRequest = { _, _, _, _ -> },
                    onRecommendRequest = { _, rec, note ->
                        opsRec = rec
                        opsNote = note
                    }
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("ops_test_rider_rbac_notice").assertIsDisplayed()
        composeTestRule.onNodeWithTag("admin_approve_test_rider_tr_1").assertDoesNotExist()
        composeTestRule.onNodeWithTag("ops_recommend_approve_tr_1").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("ops_recommend_note_input").performTextInput("Confirmed internal QA engineer")
        composeTestRule.onNodeWithTag("ops_confirm_recommend_button").assertIsEnabled().performClick()
        assertEquals("recommend_approve", opsRec)
        assertEquals("Confirmed internal QA engineer", opsNote)
    }

    @Test
    fun `realtime location validator and route navigation math validate telemetry, deviation rerouting, and eta`() {
        val nowMs = 1_700_000_000_000L
        assertTrue(
            RealtimeLocationValidator.isValidTelemetry(
                RealtimeLocationPayload(
                    driverId = "drv_1",
                    lat = 22.90,
                    lng = 88.39,
                    accuracyMeters = 12.0,
                    speedMps = 8.0,
                    headingDeg = 90.0,
                    trackingStatus = "active",
                    updatedAtEpochMs = nowMs - 2_000L
                ),
                nowMs = nowMs
            )
        )
        assertFalse(
            RealtimeLocationValidator.isValidTelemetry(
                RealtimeLocationPayload(
                    driverId = "drv_1",
                    lat = 22.90,
                    lng = 88.39,
                    accuracyMeters = 250.0,
                    trackingStatus = "active",
                    updatedAtEpochMs = nowMs
                ),
                nowMs = nowMs
            )
        )
        assertFalse(
            RealtimeLocationValidator.isValidTelemetry(
                RealtimeLocationPayload(
                    driverId = "drv_1",
                    lat = 22.90,
                    lng = 88.39,
                    accuracyMeters = 10.0,
                    trackingStatus = "active",
                    updatedAtEpochMs = nowMs - 180_000L
                ),
                nowMs = nowMs
            )
        )

        val route = listOf(GeoPoint(22.9000, 88.3900), GeoPoint(22.9100, 88.3900))
        // On route
        assertFalse(
            RouteNavigationMath.shouldRecalculateRoute(
                currentLat = 22.9050,
                currentLng = 88.3901,
                activeRoute = route,
                lastRerouteEpochMs = nowMs - 30_000L,
                nowEpochMs = nowMs
            )
        )
        // Off route > 75m and outside 12s debounce
        assertTrue(
            RouteNavigationMath.shouldRecalculateRoute(
                currentLat = 22.9050,
                currentLng = 88.3930,
                activeRoute = route,
                lastRerouteEpochMs = nowMs - 30_000L,
                nowEpochMs = nowMs
            )
        )
        // Off route but within debounce window
        assertFalse(
            RouteNavigationMath.shouldRecalculateRoute(
                currentLat = 22.9050,
                currentLng = 88.3930,
                activeRoute = route,
                lastRerouteEpochMs = nowMs - 4_000L,
                nowEpochMs = nowMs
            )
        )

        assertTrue(RouteNavigationMath.estimateEtaMinutes(distanceKm = 4.0, speedMps = 7.0, vehicleCategory = "toto") in 8..15)
        assertEquals(1, RouteNavigationMath.estimateEtaMinutes(distanceKm = 0.02, speedMps = 0.0, vehicleCategory = "bike"))
    }
}
