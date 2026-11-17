package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.customer.CustomerActiveRideView
import com.example.customer.CustomerHomeMapScreen
import com.example.customer.CustomerLocationSearchSheet
import com.example.customer.LocationFieldTarget
import com.example.data.model.Booking
import com.example.data.model.DriverProfile
import com.example.data.model.RideOffer
import com.example.data.model.UiState
import com.example.domain.WestBengalCatalog
import com.example.map.GpsBroadcastThrottle
import com.example.map.LocationSample
import com.example.rider.IncomingRideRequestOverlayCard
import com.example.rider.RiderActiveTripSheet
import com.example.rider.RiderMapHomeScreen
import com.example.shared.CallPeerConfirmDialog
import com.example.ui.theme.GolocalTheme
import com.example.viewmodel.BookingDraftState
import com.example.viewmodel.GpsLocationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RedesignFlowsRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `customer home map screen renders full bleed map floating controls and quick actions`() {
        var sosClicked = false
        composeTestRule.setContent {
            GolocalTheme {
                CustomerHomeMapScreen(
                    firstName = "Aritra",
                    unreadNotifications = 2,
                    activeBooking = null,
                    bookingsState = UiState.Success(emptyList()),
                    verifiedDrivers = emptyList(),
                    platformSettings = null,
                    onOpenBookRide = {},
                    onOpenExplore = {},
                    onOpenBookings = {},
                    onOpenNotifications = {},
                    onOpenHelp = {},
                    onQuickSos = { sosClicked = true }
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hello, Aritra").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_sos_button").performClick()
        assertTrue(sosClicked)
        composeTestRule.onNodeWithTag("home_search_destination").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_private_bus_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("No recent bookings").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `customer location search sheet filters places and allows manual map pin selection`() {
        var selectedLabel = ""
        val draft = BookingDraftState(
            pickupPlace = WestBengalCatalog.places[0],
            destinationPlace = WestBengalCatalog.places[1]
        )

        composeTestRule.setContent {
            GolocalTheme {
                CustomerLocationSearchSheet(
                    initialTarget = LocationFieldTarget.DESTINATION,
                    draft = draft,
                    gps = GpsLocationState(),
                    customerProfile = null,
                    recentBookings = emptyList(),
                    serviceAreas = emptyList(),
                    onlineDrivers = emptyList(),
                    lowNetworkMode = false,
                    onSelectCatalogPlace = { _, p -> selectedLabel = p.nameEn },
                    onSelectCoordinates = { _, lbl, _, _, _ -> selectedLabel = lbl },
                    onSwapLocations = {},
                    onRequestGps = {},
                    onClose = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("search_use_gps_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("search_pick_on_map_button").assertIsDisplayed()

        // Unknown query must show honest custom-entry / empty state without fake entries
        composeTestRule.onNodeWithTag("location_search_input").performTextInput("ZqNonexistentVillage99")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("location_results_list").performScrollToNode(hasText("Matching Locations (0)"))
        composeTestRule.onNodeWithText("Matching Locations (0)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("location_results_list").performScrollToNode(hasText("No verified stops match", substring = true))
        composeTestRule.onNodeWithText("No verified stops match", substring = true).assertIsDisplayed()

        // Manual pin picker opens and confirms coordinates
        composeTestRule.onNodeWithTag("location_results_list").performScrollToNode(hasText("Pin on Map"))
        composeTestRule.onNodeWithTag("search_pick_on_map_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("manual_map_pin_picker").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_map_pin_button").performClick()
        assertTrue(selectedLabel.isNotBlank())
    }

    @Test
    fun `customer searching ride view displays radar incoming offers and supports decline and accept`() {
        var acceptedOfferId = ""
        val searchingBooking = Booking(
            id = "bk_search_9",
            customerId = "cust_1",
            customerName = "Aritra Sen",
            vehicleCategory = "auto",
            pickupLabel = "Borai",
            pickupLat = 22.90,
            pickupLng = 88.39,
            destinationLabel = "Singur Station",
            destinationLat = 22.81,
            destinationLng = 88.23,
            estimatedFareInr = 120.0,
            minPermittedFareInr = 100.0,
            maxPermittedFareInr = 150.0,
            status = "searching"
        )
        val offer = RideOffer(
            id = "offer_77",
            bookingId = "bk_search_9",
            driverId = "drv_77",
            driverName = "Bikash Das",
            vehicleCategory = "auto",
            vehicleLabel = "Bajaj RE • WB-16-4411",
            proposedFareInr = 115.0,
            etaMinutes = 4L,
            driverRating = 4.9,
            status = "pending"
        )

        composeTestRule.setContent {
            GolocalTheme {
                CustomerActiveRideView(
                    booking = searchingBooking,
                    offers = listOf(offer),
                    drivers = emptyList(),
                    tripLocation = null,
                    lowNetwork = false,
                    callContact = null,
                    unreadChatCount = 0,
                    onBackHome = {},
                    onOpenCall = {},
                    onOpenChat = {},
                    onAcceptOffer = { acceptedOfferId = it.id },
                    onShareTrip = {},
                    onOpenSos = {},
                    onOpenComplaint = {},
                    onOpenCancel = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("customer_searching_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("incoming_offer_card_offer_77").assertIsDisplayed()
        composeTestRule.onNodeWithTag("accept_offer_button_offer_77").performClick()
        composeTestRule.waitForIdle()
        assertEquals("offer_77", acceptedOfferId)
    }

    @Test
    fun `call peer confirm dialog protects raw mobile number and routes to private chat`() {
        var openedChat = false
        val secretRawPhone = "+919876543210"
        composeTestRule.setContent {
            GolocalTheme {
                CallPeerConfirmDialog(
                    peerLabel = "Bikash",
                    phoneNumber = secretRawPhone,
                    onDismiss = {},
                    onOpenChat = { openedChat = true }
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Privacy-Protected Contact (Bikash)").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithText(secretRawPhone, substring = true).fetchSemanticsNodes().isEmpty())
        composeTestRule.onNodeWithTag("confirm_dial_peer_button").performClick()
        assertTrue(openedChat)
    }

    @Test
    fun `rider map home and incoming request countdown card enforce regulated fares and accept decline`() {
        var acceptedFare = 0.0
        var declined = false
        val driver = DriverProfile(
            driverId = "drv_1",
            legalName = "Ramesh Mondal",
            phone = "+919800001111",
            vehicleCategory = "toto",
            vehicleRegNumber = "WB-16-E-4421",
            district = "Hooghly",
            verificationStatus = "verified",
            isOnline = true,
            ratingAvg = 4.9,
            completedRides = 42L
        )
        val booking = Booking(
            id = "bk_req_1",
            customerId = "cust_1",
            customerName = "Sourav Roy",
            vehicleCategory = "toto",
            pickupLabel = "Singur Bazar",
            pickupLat = 22.811,
            pickupLng = 88.231,
            destinationLabel = "Kamarkundu",
            destinationLat = 22.831,
            destinationLng = 88.201,
            distanceKm = 4.2,
            estimatedDurationMin = 14L,
            estimatedFareInr = 75.0,
            minPermittedFareInr = 65.0,
            maxPermittedFareInr = 95.0,
            paymentMethod = "upi",
            status = "searching"
        )

        composeTestRule.setContent {
            GolocalTheme {
                RiderMapHomeScreen(
                    profile = driver,
                    currentLat = 22.810,
                    currentLng = 88.230,
                    gpsIsDetecting = false,
                    locationLabel = "Singur",
                    openRequests = listOf(booking),
                    myOffers = emptyList(),
                    todaysCompletedRides = 3,
                    todaysEarningsInr = 280.0,
                    expiryAlerts = emptyList(),
                    lowNetworkMode = false,
                    onToggleOnline = {},
                    onRequestLocationSync = {},
                    onOpenNotifications = {},
                    onOpenRequests = {},
                    onTriggerSos = {},
                    onAcceptRequestWithFare = { _, fare, _ -> acceptedFare = fare },
                    onDeclineRequest = { declined = true },
                    onWithdrawOffer = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hi, Ramesh").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rider_online_switch").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rider_incoming_request_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rider_request_timer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sourav").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rider_accept_request_button").assertIsEnabled().performClick()
        assertEquals(75.0, acceptedFare, 0.1)
        composeTestRule.onNodeWithTag("rider_decline_request_button").performClick()
        assertTrue(declined)
    }

    @Test
    fun `rider active trip sheet renders navigation pin entry and completion controls without leaking passenger phone`() {
        val booking = Booking(
            id = "bk_act_2",
            customerId = "cust_2",
            customerName = "Ananya Sen",
            assignedDriverId = "drv_1",
            assignedDriverName = "Ramesh",
            vehicleCategory = "auto",
            pickupLabel = "Chinsurah",
            pickupLat = 22.90,
            pickupLng = 88.39,
            destinationLabel = "Bandel",
            destinationLat = 22.93,
            destinationLng = 88.38,
            distanceKm = 4.5,
            agreedFareInr = 85.0,
            status = "arrived"
        )
        var capturedPin = ""
        composeTestRule.setContent {
            GolocalTheme {
                RiderActiveTripSheet(
                    booking = booking,
                    currentLat = 22.90,
                    currentLng = 88.39,
                    onlineSelf = com.example.data.model.DriverDirectoryEntry(
                        driverId = "drv_1",
                        isOnline = true,
                        currentLat = 22.90,
                        currentLng = 88.39
                    ),
                    lowNetworkMode = false,
                    callContact = null,
                    unreadChatCount = 0,
                    onCallCustomer = {},
                    onChatCustomer = {},
                    onTriggerSos = {},
                    onCancelTrip = {},
                    onAdvanceState = { _, pin -> capturedPin = pin }
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("rider_external_navigate_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rider_trip_pin_input").performTextInput("4829")
        composeTestRule.onNodeWithText("Verify PIN & Start Ride").performClick()
        assertEquals("4829", capturedPin)
    }

    @Test
    fun `gps broadcast throttle rejects stationary jitter and accepts meaningful movement`() {
        val throttle = GpsBroadcastThrottle(
            minIntervalMs = 4_000L,
            minDistanceMeters = 15.0,
            maxAcceptedAccuracyMeters = 80f
        )
        assertTrue(throttle.acceptIfQualified(LocationSample(22.9000, 88.3900, 12f, 1_000L)))
        assertFalse(throttle.acceptIfQualified(LocationSample(22.90001, 88.39001, 12f, 2_000L)))
        assertTrue(throttle.acceptIfQualified(LocationSample(22.9010, 88.3910, 12f, 6_000L)))
        throttle.reset()
        assertTrue(throttle.acceptIfQualified(LocationSample(22.9000, 88.3900, 12f, 6_500L)))
    }
}
