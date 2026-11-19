package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.customer.CustomerActiveRideView
import com.example.customer.CustomerHomeMapScreen
import com.example.data.model.Booking
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.DriverProfile
import com.example.data.model.RideOtpSecret
import com.example.data.model.TripLocation
import com.example.data.model.UiState
import com.example.data.tracking.DriverGpsHealth
import com.example.domain.BoraiLaunchConfig
import com.example.notifications.NotificationDeepLinkTarget
import com.example.rider.RiderActiveTripSheet
import com.example.rider.RiderMapHomeScreen
import com.example.rider.resolveRiderDeepLinkTab
import com.example.ui.theme.GolocalTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class DispatchAndUpgradeRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `resolveRiderDeepLinkTab routes assigned ride, requests, earnings, profile, and test mode alerts accurately`() {
        assertEquals(
            "home",
            resolveRiderDeepLinkTab(
                NotificationDeepLinkTarget(type = "booking_assigned", targetScreen = "active_trip", bookingId = "b1"),
                inTestMode = false
            )
        )
        assertEquals(
            "home",
            resolveRiderDeepLinkTab(
                NotificationDeepLinkTarget(type = "ride_accepted", targetScreen = "", bookingId = "b2"),
                inTestMode = false
            )
        )
        assertEquals(
            "home",
            resolveRiderDeepLinkTab(
                NotificationDeepLinkTarget(type = "trip_status", targetScreen = "rider_home", bookingId = "b3"),
                inTestMode = false
            )
        )
        assertEquals(
            "requests",
            resolveRiderDeepLinkTab(
                NotificationDeepLinkTarget(type = "new_ride_request", targetScreen = "", bookingId = "b4"),
                inTestMode = false
            )
        )
        assertEquals(
            "earnings",
            resolveRiderDeepLinkTab(
                NotificationDeepLinkTarget(type = "settlement", targetScreen = ""),
                inTestMode = false
            )
        )
        assertEquals(
            "profile",
            resolveRiderDeepLinkTab(
                NotificationDeepLinkTarget(type = "kyc_status_update", targetScreen = ""),
                inTestMode = false
            )
        )
        assertEquals(
            "rides",
            resolveRiderDeepLinkTab(
                NotificationDeepLinkTarget(type = "test_request", targetScreen = ""),
                inTestMode = true
            )
        )
        assertEquals(
            "home",
            resolveRiderDeepLinkTab(
                NotificationDeepLinkTarget(type = "test_otp", targetScreen = "test_rider_dashboard", bookingId = "bt1"),
                inTestMode = true
            )
        )
    }

    @Test
    fun `rider map home in dark mode renders retry sync error banner, GPS badge, and pickup metrics`() {
        var retriedSync = false
        val driver = DriverProfile(
            driverId = "drv_borai_1",
            legalName = "Subhashis Jana",
            phone = "+919800001234",
            vehicleCategory = "auto",
            vehicleRegNumber = "WB-34-A-7712",
            district = BoraiLaunchConfig.DEFAULT_DISTRICT,
            verificationStatus = "verified",
            isOnline = true,
            ratingAvg = 4.8,
            completedRides = 19L
        )
        val openBooking = Booking(
            id = "bk_borai_req_1",
            customerId = "cust_1",
            customerName = "Anirban Das",
            vehicleCategory = "auto",
            pickupLabel = "Borai Bazaar",
            pickupLat = BoraiLaunchConfig.DEFAULT_LAT + 0.008,
            pickupLng = BoraiLaunchConfig.DEFAULT_LNG + 0.006,
            destinationLabel = "Mohanpur Bus Stand",
            destinationLat = BoraiLaunchConfig.DEFAULT_LAT + 0.032,
            destinationLng = BoraiLaunchConfig.DEFAULT_LNG + 0.025,
            distanceKm = 4.6,
            estimatedDurationMin = 12L,
            estimatedFareInr = 90.0,
            minPermittedFareInr = 75.0,
            maxPermittedFareInr = 115.0,
            paymentMethod = "cash",
            status = "searching"
        )

        composeTestRule.setContent {
            GolocalTheme(darkTheme = true) {
                RiderMapHomeScreen(
                    profile = driver,
                    currentLat = BoraiLaunchConfig.DEFAULT_LAT,
                    currentLng = BoraiLaunchConfig.DEFAULT_LNG,
                    gpsIsDetecting = false,
                    locationLabel = BoraiLaunchConfig.DEFAULT_AREA_NAME,
                    openRequests = listOf(openBooking),
                    myOffers = emptyList(),
                    todaysCompletedRides = 4,
                    todaysEarningsInr = 420.0,
                    expiryAlerts = emptyList(),
                    lowNetworkMode = false,
                    onToggleOnline = {},
                    onRequestLocationSync = {},
                    onOpenNotifications = {},
                    onOpenRequests = {},
                    onTriggerSos = {},
                    onAcceptRequestWithFare = { _, _, _ -> },
                    onDeclineRequest = {},
                    onWithdrawOffer = {},
                    openRequestsError = "Dispatch listener interrupted: Missing index",
                    gpsHealth = DriverGpsHealth(
                        isServiceRunning = true,
                        gpsStatus = "active",
                        lastLat = BoraiLaunchConfig.DEFAULT_LAT,
                        lastLng = BoraiLaunchConfig.DEFAULT_LNG
                    ),
                    onRetryRequestsSync = { retriedSync = true }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("GPS Live", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("rider_requests_error_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rider_requests_retry_button").performClick()
        assertTrue(retriedSync)
        composeTestRule.onNodeWithTag("rider_incoming_request_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("km away (", substring = true).assertIsDisplayed()
    }

    @Test
    fun `rider active trip sheet displays 4 step trip stepper in dark mode`() {
        val booking = Booking(
            id = "bk_active_stepper_1",
            customerId = "cust_1",
            customerName = "Rina Maity",
            assignedDriverId = "drv_borai_1",
            assignedDriverName = "Subhashis",
            vehicleCategory = "auto",
            pickupLabel = "Borai More",
            pickupLat = BoraiLaunchConfig.DEFAULT_LAT,
            pickupLng = BoraiLaunchConfig.DEFAULT_LNG,
            destinationLabel = "Dantan Station",
            destinationLat = BoraiLaunchConfig.DEFAULT_LAT + 0.04,
            destinationLng = BoraiLaunchConfig.DEFAULT_LNG - 0.02,
            distanceKm = 6.2,
            estimatedDurationMin = 15L,
            agreedFareInr = 120.0,
            status = "arriving"
        )

        composeTestRule.setContent {
            GolocalTheme(darkTheme = true) {
                RiderActiveTripSheet(
                    booking = booking,
                    currentLat = BoraiLaunchConfig.DEFAULT_LAT,
                    currentLng = BoraiLaunchConfig.DEFAULT_LNG,
                    onlineSelf = DriverDirectoryEntry(
                        driverId = "drv_borai_1",
                        isOnline = true,
                        currentLat = BoraiLaunchConfig.DEFAULT_LAT,
                        currentLng = BoraiLaunchConfig.DEFAULT_LNG
                    ),
                    lowNetworkMode = false,
                    callContact = null,
                    unreadChatCount = 0,
                    onCallCustomer = {},
                    onChatCustomer = {},
                    onTriggerSos = {},
                    onCancelTrip = {},
                    onAdvanceState = { _, _ -> }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("rider_trip_stepper").assertIsDisplayed()
        composeTestRule.onNodeWithText("En Route").assertIsDisplayed()
        composeTestRule.onNodeWithText("On Trip").assertIsDisplayed()
    }

    @Test
    fun `customer home and active ride screens render cleanly in dark mode with OTP and telemetry`() {
        val activeBooking = Booking(
            id = "bk_cust_dark_1",
            customerId = "cust_1",
            customerName = "Arjun Bera",
            assignedDriverId = "drv_borai_1",
            assignedDriverName = "Subhashis Jana",
            assignedVehicleLabel = "Bajaj RE • WB-34-A-7712",
            vehicleCategory = "auto",
            pickupLabel = "Borai",
            pickupLat = BoraiLaunchConfig.DEFAULT_LAT,
            pickupLng = BoraiLaunchConfig.DEFAULT_LNG,
            destinationLabel = "Belda",
            destinationLat = BoraiLaunchConfig.DEFAULT_LAT + 0.05,
            destinationLng = BoraiLaunchConfig.DEFAULT_LNG + 0.03,
            distanceKm = 8.4,
            estimatedDurationMin = 19L,
            agreedFareInr = 160.0,
            tripPin = "482910",
            status = "arriving"
        )

        composeTestRule.setContent {
            GolocalTheme(darkTheme = true) {
                CustomerHomeMapScreen(
                    firstName = "Arjun",
                    unreadNotifications = 1,
                    activeBooking = activeBooking,
                    bookingsState = UiState.Success(listOf(activeBooking)),
                    verifiedDrivers = emptyList(),
                    platformSettings = null,
                    onOpenBookRide = {},
                    onOpenExplore = {},
                    onOpenBookings = {},
                    onOpenNotifications = {},
                    onOpenHelp = {},
                    onQuickSos = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hello, Arjun").assertIsDisplayed()
        composeTestRule.onNodeWithText("PIN 482910").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `customer active ride view displays commercial OTP and live telemetry line in dark mode`() {
        val activeBooking = Booking(
            id = "bk_cust_dark_2",
            customerId = "cust_1",
            customerName = "Arjun Bera",
            assignedDriverId = "drv_borai_1",
            assignedDriverName = "Subhashis Jana",
            assignedVehicleLabel = "WB-34-A-7712",
            vehicleCategory = "auto",
            pickupLabel = "Borai",
            pickupLat = BoraiLaunchConfig.DEFAULT_LAT,
            pickupLng = BoraiLaunchConfig.DEFAULT_LNG,
            destinationLabel = "Belda",
            destinationLat = BoraiLaunchConfig.DEFAULT_LAT + 0.05,
            destinationLng = BoraiLaunchConfig.DEFAULT_LNG + 0.03,
            distanceKm = 8.4,
            estimatedDurationMin = 19L,
            agreedFareInr = 160.0,
            tripPin = "482910",
            status = "arrived"
        )
        val liveLoc = TripLocation(
            bookingId = "bk_cust_dark_2",
            driverId = "drv_borai_1",
            lat = BoraiLaunchConfig.DEFAULT_LAT,
            lng = BoraiLaunchConfig.DEFAULT_LNG,
            speedKmph = 18.0
        )

        composeTestRule.setContent {
            GolocalTheme(darkTheme = true) {
                CustomerActiveRideView(
                    booking = activeBooking,
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
                    rideOtpSecret = RideOtpSecret(
                        id = "bk_cust_dark_2",
                        bookingId = "bk_cust_dark_2",
                        customerId = "cust_1",
                        otpPlaintext = "482910",
                        status = "pending"
                    )
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("customer_commercial_otp_banner").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("customer_commercial_otp_value").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("customer_live_telemetry_line").performScrollTo().assertIsDisplayed()
    }
}
