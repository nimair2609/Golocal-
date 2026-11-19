package com.example.map

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.example.data.map.RoutePoint
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.TripLocation
import com.example.domain.BoraiLaunchConfig
import com.example.ui.components.GolocalMapLibreView
import com.example.ui.theme.GolocalTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CustomerMapBookingUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `GolocalMapLibreView renders OSM attribution, route endpoints, and live driver GPS safely on JVM`() {
        composeTestRule.setContent {
            GolocalTheme {
                GolocalMapLibreView(
                    pickupLat = BoraiLaunchConfig.DEFAULT_LAT,
                    pickupLng = BoraiLaunchConfig.DEFAULT_LNG,
                    pickupLabel = "Borai, PIN 721457",
                    destLat = 21.8442,
                    destLng = 87.4196,
                    destLabel = "Mohanpur Bus Stand",
                    routePoints = listOf(
                        RoutePoint(BoraiLaunchConfig.DEFAULT_LAT, BoraiLaunchConfig.DEFAULT_LNG),
                        RoutePoint(21.8442, 87.4196)
                    ),
                    routeSource = "osrm",
                    onlineDrivers = listOf(
                        DriverDirectoryEntry(
                            driverId = "drv_1",
                            displayName = "Bikash Jana",
                            isOnline = true,
                            currentLat = 21.8250,
                            currentLng = 87.3810
                        )
                    ),
                    liveTripLocation = TripLocation(
                        bookingId = "bk_1",
                        driverId = "drv_1",
                        lat = 21.8250,
                        lng = 87.3810
                    )
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("golocal_maplibre_container").assertIsDisplayed()
        composeTestRule.onNodeWithTag("maplibre_fallback_preview").assertIsDisplayed()
        composeTestRule.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()
        composeTestRule.onNodeWithText("OSM + OSRM • 1 online").assertIsDisplayed()
        composeTestRule.onNodeWithText("Driver Live GPS: 21.8250, 87.3810").assertIsDisplayed()
    }
}
