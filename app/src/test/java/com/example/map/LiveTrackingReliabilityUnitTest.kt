package com.example.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.data.map.MapProviderConfig
import com.example.data.repository.RealtimeLocationPayload
import com.example.data.repository.RealtimeLocationValidator
import com.example.data.tracking.RiderLocationForegroundService
import org.json.JSONObject
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
@Config(sdk = [33])
class LiveTrackingReliabilityUnitTest {

    @Test
    fun `GpsBroadcastThrottle suppresses jitter under distance threshold but emits stationary 20s heartbeat`() {
        val throttle = GpsBroadcastThrottle(
            minIntervalMs = 4_000L,
            minDistanceMeters = 10.0,
            maxAcceptedAccuracyMeters = 80f,
            maxHeartbeatIntervalMs = 20_000L
        )

        assertTrue(throttle.acceptIfQualified(LocationSample(21.8160, 87.3703, accuracyMeters = 12f, timestampMs = 1_000L)))
        // 5s later, stationary (~1m jitter) -> suppressed
        assertFalse(throttle.acceptIfQualified(LocationSample(21.81601, 87.3703, accuracyMeters = 12f, timestampMs = 6_000L)))
        // 20s later, still stationary -> heartbeat accepted so customer live GPS timestamp stays fresh
        assertTrue(throttle.acceptIfQualified(LocationSample(21.81601, 87.3703, accuracyMeters = 12f, timestampMs = 21_500L)))
        // Poor accuracy (>80m) rejected even after heartbeat
        assertFalse(throttle.acceptIfQualified(LocationSample(21.8170, 87.3710, accuracyMeters = 120f, timestampMs = 45_000L)))
    }

    @Test
    fun `RealtimeLocationValidator validates coordinates, staleness, and tracking statuses`() {
        val now = 1_700_000_000_000L
        val valid = RealtimeLocationPayload(
            driverId = "drv_1",
            bookingId = "bk_1",
            customerId = "cust_1",
            lat = 21.816,
            lng = 87.3703,
            accuracyMeters = 12.0,
            speedMps = 8.0,
            headingDeg = 90.0,
            trackingStatus = "active",
            updatedAtEpochMs = now - 5_000L
        )
        assertTrue(RealtimeLocationValidator.isValidTelemetry(valid, now))
        assertNotNull(RealtimeLocationValidator.toTripLocation(valid))

        val stale = valid.copy(updatedAtEpochMs = now - 130_000L)
        assertFalse(RealtimeLocationValidator.isValidTelemetry(stale, now))

        val invalidCoords = valid.copy(lat = 0.0, lng = 0.0)
        assertFalse(RealtimeLocationValidator.isValidTelemetry(invalidCoords, now))
        assertNull(RealtimeLocationValidator.toTripLocation(invalidCoords))
    }

    @Test
    fun `RouteNavigationMath triggers reroute on route deviation beyond 75m after debounce`() {
        val route = listOf(GeoPoint(21.8160, 87.3703), GeoPoint(21.8300, 87.3703))
        // On route -> no reroute
        assertFalse(
            RouteNavigationMath.shouldRecalculateRoute(
                currentLat = 21.8200,
                currentLng = 87.3703,
                activeRoute = route,
                lastRerouteEpochMs = 0L,
                nowEpochMs = 20_000L
            )
        )
        // ~200m east of route, inside debounce window -> no reroute yet
        assertFalse(
            RouteNavigationMath.shouldRecalculateRoute(
                currentLat = 21.8200,
                currentLng = 87.3725,
                activeRoute = route,
                lastRerouteEpochMs = 15_000L,
                nowEpochMs = 20_000L
            )
        )
        // ~200m east of route, outside 12s debounce -> reroute triggered
        assertTrue(
            RouteNavigationMath.shouldRecalculateRoute(
                currentLat = 21.8200,
                currentLng = 87.3725,
                activeRoute = route,
                lastRerouteEpochMs = 5_000L,
                nowEpochMs = 20_000L
            )
        )
    }

    @Test
    fun `MapProviderConfig builds valid MapLibre OSM style JSON with custom tile template`() {
        val json = MapProviderConfig.buildOsmStyleJson(
            tileUrlTemplate = "https://tiles.golocal.example/{z}/{x}/{y}.png",
            attribution = "© GoLocal OSM"
        )
        val obj = JSONObject(json)
        assertEquals(8, obj.getInt("version"))
        val tiles = obj.getJSONObject("sources")
            .getJSONObject("osm-raster")
            .getJSONArray("tiles")
        assertEquals("https://tiles.golocal.example/{z}/{x}/{y}.png", tiles.getString(0))
        assertTrue(MapProviderConfig.osrmBaseUrl.startsWith("https://"))
    }

    @Test
    fun `manifest declares foreground location service without background location and handles missing permission safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in perms)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_LOCATION in perms)
        assertFalse(Manifest.permission.ACCESS_BACKGROUND_LOCATION in perms)

        RiderLocationForegroundService.startOrUpdate(
            context = context,
            driverId = "driver_test",
            vehicleCategory = "toto"
        )
        assertFalse(RiderLocationForegroundService.gpsHealth.value.isServiceRunning)
        assertEquals("permission_denied", RiderLocationForegroundService.gpsHealth.value.gpsStatus)
    }
}
