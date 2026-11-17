package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.customer.CustomerHomeTab
import com.example.customer.CustomerOnboardingScreen
import com.example.data.model.PlatformSettings
import com.example.data.model.UiState
import com.example.shared.ExperienceMode
import com.example.shared.GolocalRoleSplashScreen
import com.example.shared.RoleAuthScreen
import com.example.ui.theme.GolocalTheme
import com.example.updater.AppUpdateController
import com.example.updater.AppUpdateDialogHost
import com.example.updater.AppUpdateManager
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `app labels reflect separated GoLocal experiences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("GoLocal", context.getString(R.string.app_name))
        assertEquals("GoLocal Rider", context.getString(R.string.app_name_rider))
        assertEquals("GoLocal Admin", context.getString(R.string.app_name_admin))
        assertEquals("Your Local. Our Priority.", context.getString(R.string.brand_tagline))
    }

    @Test
    fun `customer auth screen renders Mobile OTP login without Google Sign-In or Admin entry points`() {
        composeTestRule.setContent {
            GolocalTheme {
                RoleAuthScreen(mode = ExperienceMode.CUSTOMER, onAuthSuccess = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("GoLocal").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your Local. Our Priority.").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithText("by NR Next").fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithTag("google_sign_in_button").fetchSemanticsNodes().isEmpty())
        composeTestRule.onNodeWithTag("phone_number_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("send_otp_button").performScrollTo().assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithText("Admin Portal").fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("Rider Mode").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `rider auth screen renders Partner Mobile OTP login without Google Sign-In or Admin options`() {
        composeTestRule.setContent {
            GolocalTheme {
                RoleAuthScreen(mode = ExperienceMode.RIDER, onAuthSuccess = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("GoLocal Rider").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithTag("google_sign_in_button").fetchSemanticsNodes().isEmpty())
        composeTestRule.onNodeWithTag("send_otp_button").performScrollTo().assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithText("Admin Portal").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `admin auth screen renders strict Admin Mobile OTP gate without Google Sign-In or public signup`() {
        composeTestRule.setContent {
            GolocalTheme {
                RoleAuthScreen(mode = ExperienceMode.ADMIN, onAuthSuccess = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("GoLocal Admin Portal").assertIsDisplayed()
        composeTestRule.onNodeWithText("Portal Sign-In (Mobile OTP)").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithTag("google_sign_in_button").fetchSemanticsNodes().isEmpty())
        composeTestRule.onNodeWithTag("send_otp_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `customer onboarding completes across slides`() {
        var completed = false
        composeTestRule.setContent {
            GolocalTheme {
                CustomerOnboardingScreen(onComplete = { completed = true })
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Book Verified Local Rides").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customer_onboarding_done").performClick()
        composeTestRule.onNodeWithText("Fair & Transparent Fare Offers").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customer_onboarding_done").performClick()
        composeTestRule.onNodeWithText("Trip PIN & 24x7 Safety Desk").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customer_onboarding_done").performClick()
        assertTrue(completed)
    }

    @Test
    fun `customer home dashboard renders mockup layout and empty recent bookings state`() {
        composeTestRule.setContent {
            GolocalTheme {
                CustomerHomeTab(
                    firstName = "Nimai",
                    unreadNotifications = 0,
                    activeBooking = null,
                    bookingsState = UiState.Success(emptyList()),
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
        composeTestRule.onNodeWithText("Hello, Nimai").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_search_destination").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_hero_book_ride").assertIsDisplayed()
        composeTestRule.onNodeWithText("Book Ride").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bus Tickets").assertIsDisplayed()
        composeTestRule.onNodeWithText("Explore").assertIsDisplayed()
        composeTestRule.onNodeWithText("No recent bookings").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `opening splash screen displays GoLocal and by NR Next`() {
        composeTestRule.setContent {
            GolocalTheme {
                GolocalRoleSplashScreen(mode = ExperienceMode.CUSTOMER, onFinished = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("GoLocal").assertIsDisplayed()
        composeTestRule.onNodeWithText("by NR Next").assertIsDisplayed()
    }

    @Test
    fun `optional and mandatory APK update dialogs render expected actions and release notes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = AppUpdateController(AppUpdateManager(context), MainScope())
        val optionalSettings = PlatformSettings(
            updatesEnabled = true,
            latestVersionName = "1.5.0",
            latestVersionCode = 5L,
            minSupportedVersionCode = 1L,
            apkDownloadUrl = "https://github.com/nrnext/golocal/releases/download/v1.5.0/GoLocal.apk",
            sha256Checksum = "a".repeat(64),
            releaseNotes = "New logo and speed improvements.",
            isMandatoryUpdate = false
        )

        composeTestRule.setContent {
            GolocalTheme {
                AppUpdateDialogHost(controller = controller)
            }
        }

        controller.evaluateFromSettings(optionalSettings, showUpToDateFeedback = false)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("GoLocal Update Available").assertIsDisplayed()
        composeTestRule.onNodeWithText("New logo and speed improvements.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("download_update_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dismiss_update_button").assertIsDisplayed()

        controller.evaluateFromSettings(optionalSettings.copy(isMandatoryUpdate = true, latestVersionCode = 6L), showUpToDateFeedback = false)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Mandatory GoLocal Update Required").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithText("Later").fetchSemanticsNodes().isEmpty())
    }
}
