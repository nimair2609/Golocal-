package com.example.map

import com.example.data.model.ServiceArea
import com.example.domain.BoraiLaunchConfig
import com.example.domain.ServiceAreaCoverageEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServiceAreaCoverageEngineTest {

    @Test
    fun `empty service areas uses Borai as non-restrictive starting center`() {
        val eval = ServiceAreaCoverageEngine.evaluateRouteCoverage(
            pickupLat = 22.5726,
            pickupLng = 88.3639,
            destLat = 22.5839,
            destLng = 88.3426,
            vehicleCategory = "toto",
            serviceAreas = emptyList()
        )
        assertFalse(eval.hasConfiguredZones)
        assertFalse(eval.isRestrictedByZone)
        assertTrue(eval.isCovered)
        assertTrue(eval.serviceAvailable)
        assertTrue(eval.advisoryMessage.contains("Borai"))
    }

    @Test
    fun `all paused zones restricts bookings with clear admin advisory`() {
        val pausedZone = ServiceArea(
            id = "borai_hub",
            name = "Borai Hub",
            lat = BoraiLaunchConfig.DEFAULT_LAT,
            lng = BoraiLaunchConfig.DEFAULT_LNG,
            radiusKm = 8.0,
            isActive = true,
            serviceAvailable = false
        )
        val eval = ServiceAreaCoverageEngine.evaluateRouteCoverage(
            pickupLat = BoraiLaunchConfig.DEFAULT_LAT,
            pickupLng = BoraiLaunchConfig.DEFAULT_LNG,
            destLat = 21.8442,
            destLng = 87.4196,
            vehicleCategory = "toto",
            serviceAreas = listOf(pausedZone)
        )
        assertTrue(eval.isRestrictedByZone)
        assertFalse(eval.serviceAvailable)
        assertTrue(eval.advisoryMessage.contains("paused"))
    }

    @Test
    fun `evaluates polygon boundary, pickup-dropoff flags, and supported vehicle categories`() {
        // Simple bounding square around Borai [lng, lat] GeoJSON ring
        val polygonJson = "[[87.34,21.79],[87.43,21.79],[87.43,21.86],[87.34,21.86]]"
        val mohanpurZone = ServiceArea(
            id = "mohanpur_poly",
            name = "Borai-Mohanpur Corridor",
            lat = BoraiLaunchConfig.DEFAULT_LAT,
            lng = BoraiLaunchConfig.DEFAULT_LNG,
            boundaryPolygonJson = polygonJson,
            pickupEnabled = true,
            dropoffEnabled = true,
            supportedVehicleCategories = listOf("toto", "auto"),
            isActive = true,
            serviceAvailable = true
        )

        val allowedEval = ServiceAreaCoverageEngine.evaluateRouteCoverage(
            pickupLat = 21.8160,
            pickupLng = 87.3703,
            destLat = 21.8442,
            destLng = 87.4196,
            vehicleCategory = "toto",
            serviceAreas = listOf(mohanpurZone)
        )
        assertTrue(allowedEval.isCovered)
        assertFalse(allowedEval.isRestrictedByZone)
        assertEquals(listOf("Borai-Mohanpur Corridor"), allowedEval.matchedZones)

        val unsupportedVehicleEval = ServiceAreaCoverageEngine.evaluateRouteCoverage(
            pickupLat = 21.8160,
            pickupLng = 87.3703,
            destLat = 21.8442,
            destLng = 87.4196,
            vehicleCategory = "cab",
            serviceAreas = listOf(mohanpurZone)
        )
        assertTrue(unsupportedVehicleEval.isRestrictedByZone)
        assertTrue(unsupportedVehicleEval.advisoryMessage.contains("CAB is not enabled"))

        val dropoffDisabledZone = mohanpurZone.copy(dropoffEnabled = false)
        val dropDisabledEval = ServiceAreaCoverageEngine.evaluateRouteCoverage(
            pickupLat = 21.8160,
            pickupLng = 87.3703,
            destLat = 21.8442,
            destLng = 87.4196,
            vehicleCategory = "toto",
            serviceAreas = listOf(dropoffDisabledZone)
        )
        assertTrue(dropDisabledEval.isRestrictedByZone)
        assertTrue(dropDisabledEval.advisoryMessage.contains("Drop-off"))
    }
}
