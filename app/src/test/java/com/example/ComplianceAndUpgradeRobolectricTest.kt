package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Booking
import com.example.data.model.PlatformSettings
import com.example.data.repository.GolocalRepository
import com.example.domain.BoraiLaunchConfig
import com.example.domain.IndianRegulatoryChecklist
import com.example.domain.OperatingStateCode
import com.example.domain.StateComplianceCatalog
import com.example.map.NominatimGeocoder
import com.example.notifications.GolocalFirebaseMessagingService
import com.example.rider.IncomingRideRequestOverlayCard
import com.example.rider.RiderRegistrationAndVerificationGate
import com.example.shared.ExperienceMode
import com.example.shared.RoleAuthScreen
import com.example.ui.theme.GolocalTheme
import com.example.viewmodel.DriverViewModel
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ComplianceAndUpgradeRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            FirebaseApp.initializeApp(
                appContext,
                FirebaseOptions.Builder()
                    .setApplicationId("1:629202329249:android:f2cfdd8b9872a90a461ae0")
                    .setProjectId("gen-lang-client-0818250755")
                    .setApiKey("test-api-key")
                    .build()
            )
        }
    }

    @Test
    fun `redesigned incoming ride request card shows circular timer net payout emerald accept and skip`() {
        var acceptedDirectFare = -1.0
        var customProposedFare = -1.0
        var declined = false

        val sampleBooking = Booking(
            id = "bk_req_overlay_99",
            customerId = "cust_1",
            customerName = "Sourav Mondal",
            vehicleCategory = "bike",
            pickupLabel = "Borai Market",
            pickupLat = BoraiLaunchConfig.DEFAULT_LAT + 0.004,
            pickupLng = BoraiLaunchConfig.DEFAULT_LNG + 0.004,
            destinationLabel = "Singur Railway Station",
            destinationLat = BoraiLaunchConfig.DEFAULT_LAT + 0.025,
            destinationLng = BoraiLaunchConfig.DEFAULT_LNG + 0.018,
            distanceKm = 3.2,
            estimatedDurationMin = 11,
            estimatedFareInr = 65.0,
            minPermittedFareInr = 55.0,
            maxPermittedFareInr = 95.0,
            paymentMethod = "cash",
            status = "searching"
        )

        composeTestRule.setContent {
            GolocalTheme {
                IncomingRideRequestOverlayCard(
                    booking = sampleBooking,
                    initialSeconds = 20,
                    onDecline = { declined = true },
                    onAccept = { fare, _ -> customProposedFare = fare },
                    onDirectAccept = { fare -> acceptedDirectFare = fare }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rider_incoming_request_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rider_request_timer").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rider_incoming_booking_id").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bike Taxi").assertIsDisplayed()
        composeTestRule.onNodeWithText("₹65").assertIsDisplayed()
        composeTestRule.onNodeWithText("ACCEPT RIDE").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Skip").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("rider_propose_fare_button").performScrollTo().assertIsDisplayed()

        // Expand Fare Band adjuster
        composeTestRule.onNodeWithTag("rider_toggle_fare_adjuster").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Increase fare").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Propose custom fare and verify direct accept & skip actions
        composeTestRule.onNodeWithTag("rider_propose_fare_button").performScrollTo().performClick()
        assertTrue(customProposedFare >= 65.0)

        composeTestRule.onNodeWithTag("rider_accept_request_button").performScrollTo().performClick()
        assertTrue(acceptedDirectFare >= 65.0)

        composeTestRule.onNodeWithTag("rider_decline_request_button").performScrollTo().performClick()
        assertTrue(declined)
    }

    @Test
    fun `rider 4-step KYC compliance wizard advances across steps and reveals final submit`() {
        val firestore = FirebaseFirestore.getInstance(FirebaseApp.getInstance())
        val auth = FirebaseAuth.getInstance(FirebaseApp.getInstance())
        val repo = GolocalRepository(firestore, auth)
        val driverVm = DriverViewModel(repository = repo, currentUserId = "test_drv_kyc_1")

        composeTestRule.setContent {
            GolocalTheme {
                RiderRegistrationAndVerificationGate(
                    existingProfile = null,
                    displayName = "Test Partner",
                    driverViewModel = driverVm,
                    verifiedPhone = "+919876543210"
                )
            }
        }
        composeTestRule.waitForIdle()

        // Starts at Step 1 (Personal & SOS)
        composeTestRule.onNodeWithTag("kyc_step_chip_0").assertIsDisplayed()
        composeTestRule.onNodeWithText("Step 1: Personal Details & Emergency Contact").performScrollTo().assertIsDisplayed()

        // Step 2 (ID & DL)
        composeTestRule.onNodeWithTag("kyc_next_step_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Step 2: Identity, Driving Licence & Police Verification").performScrollTo().assertIsDisplayed()

        // Step 3 (Vehicle & Permits)
        composeTestRule.onNodeWithTag("kyc_next_step_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Step 3: Vehicle Details & Statutory Permits").performScrollTo().assertIsDisplayed()

        // Step 4 (Payout & DPDP Compliance) reveals submit button
        composeTestRule.onNodeWithTag("kyc_next_step_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Step 4: Settlement, Welfare Insurance & Declarations").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("rider_submit_kyc_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `customer auth legal links open Terms and DPDP Privacy dialogs without breaking brand isolation`() {
        composeTestRule.setContent {
            GolocalTheme {
                RoleAuthScreen(mode = ExperienceMode.CUSTOMER, onAuthSuccess = {})
            }
        }
        composeTestRule.waitForIdle()

        // Preserved Customer brand isolation
        assertTrue(composeTestRule.onAllNodesWithText("by NR Next").fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("Admin Portal").fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithTag("auth_privacy_link").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Privacy Notice (DPDP Act 2023)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("auth_legal_dialog_close").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("auth_terms_link").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("GoLocal Terms of Service").assertIsDisplayed()
        composeTestRule.onNodeWithTag("auth_legal_dialog_close").performClick()
    }

    @Test
    fun `state compliance catalog and regulatory checklist highlight KA vs WB and external legal sign-off`() {
        val wbPolicy = StateComplianceCatalog.policyFor("WB")
        val kaPolicy = StateComplianceCatalog.policyFor("KA")
        assertEquals(OperatingStateCode.WEST_BENGAL, wbPolicy.state)
        assertEquals(OperatingStateCode.KARNATAKA, kaPolicy.state)
        assertTrue(kaPolicy.aggregatorLicenceAuthorityName.contains("Karnataka", ignoreCase = true))
        assertTrue(kaPolicy.bikeTaxiRequiresExplicitStatePermit)

        val incomplete = IndianRegulatoryChecklist.evaluate(
            settings = PlatformSettings(operatingStateCode = "KA"),
            verifiedDriversCount = 0
        )
        assertFalse(incomplete.first { it.code == "MVA_AGGREGATOR_2025" }.implementedInCode)
        assertFalse(incomplete.first { it.code == "DPDP_GRIEVANCE" }.implementedInCode)
        assertTrue(incomplete.all { it.requiresExternalSignOff })

        val configured = IndianRegulatoryChecklist.evaluate(
            settings = PlatformSettings(
                operatingStateCode = "WB",
                aggregatorLicenceNumber = "WB-STA-2026-001",
                grievanceOfficerName = "Ananya Sen",
                grievanceOfficerEmail = "grievance@golocal.example"
            ),
            verifiedDriversCount = 4
        )
        assertTrue(configured.first { it.code == "MVA_AGGREGATOR_2025" }.implementedInCode)
        assertTrue(configured.first { it.code == "DPDP_GRIEVANCE" }.implementedInCode)
    }

    @Test
    fun `fcm stale ride request guard suppresses expired pushes and nominatim parser handles real json`() {
        val now = 1_700_000_100_000L
        assertTrue(
            GolocalFirebaseMessagingService.isExpiredRideRequestPayload(
                type = "new_ride_request",
                data = mapOf("requestExpiresAtMs" to (now - 10_000L).toString()),
                nowMs = now
            )
        )
        assertFalse(
            GolocalFirebaseMessagingService.isExpiredRideRequestPayload(
                type = "new_ride_request",
                data = mapOf("requestExpiresAtMs" to (now + 30_000L).toString()),
                nowMs = now
            )
        )
        assertFalse(
            GolocalFirebaseMessagingService.isExpiredRideRequestPayload(
                type = "booking_cancelled",
                data = mapOf("requestExpiresAtMs" to (now - 10_000L).toString()),
                nowMs = now
            )
        )

        val sampleJson = """
            [
              {
                "name": "Bandel Junction",
                "display_name": "Bandel Junction, Hooghly, West Bengal, India",
                "lat": "22.9225",
                "lon": "88.3775",
                "address": {"state_district": "Hooghly", "state": "West Bengal"}
              },
              {
                "name": "Invalid Point",
                "display_name": "Nowhere",
                "lat": "999.0",
                "lon": "0.0"
              }
            ]
        """.trimIndent()
        val parsed = NominatimGeocoder.parseNominatimSearchJson(sampleJson)
        assertEquals(1, parsed.size)
        assertEquals("Bandel Junction", parsed[0].primaryLabel)
        assertEquals("Hooghly", parsed[0].district)
        assertEquals(22.9225, parsed[0].lat, 0.0001)
    }
}
