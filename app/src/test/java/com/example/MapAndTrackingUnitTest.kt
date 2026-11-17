package com.example

import com.example.data.map.GeocodingSearchAdapter
import com.example.data.map.MapProviderConfig
import com.example.data.map.MapProviderEndpoints
import com.example.data.map.OsrmRoutingAdapter
import com.example.data.map.RoutingHttpFetcher
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.GeoPointDto
import com.example.data.model.ServiceArea
import com.example.domain.BoraiLaunchConfig
import com.example.ui.components.MapMarkerPin
import com.example.ui.components.MapZoneOverlay
import com.example.ui.components.buildMarkersGeoJson
import com.example.ui.components.buildRouteGeoJson
import com.example.ui.components.buildZonesGeoJson
import com.example.viewmodel.BookingDraftState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MapAndTrackingUnitTest {

    @Test
    fun `borai launch configuration uses PIN 721457 Mohanpur without restricting open West Bengal coverage`() {
        assertEquals(21.8160, BoraiLaunchConfig.DEFAULT_LAT, 0.0001)
        assertEquals(87.3703, BoraiLaunchConfig.DEFAULT_LNG, 0.0001)
        assertEquals("721457", BoraiLaunchConfig.DEFAULT_PINCODE)
        assertEquals("Mohanpur", BoraiLaunchConfig.DEFAULT_BLOCK)
        assertEquals("Paschim Medinipur", BoraiLaunchConfig.DEFAULT_DISTRICT)

        // With empty serviceAreas, West Bengal trips remain allowed (Borai is starting point, not restriction)
        val openEval = BoraiLaunchConfig.evaluateTripCoverage(
            pickupLat = 22.5839,
            pickupLng = 88.3426,
            dropLat = 22.6520,
            dropLng = 88.4463,
            vehicleCategory = "cab",
            serviceAreas = emptyList()
        )
        assertTrue(openEval.isAllowed)

        // Default booking draft starts centered on Borai launch point
        val draft = BookingDraftState()
        assertEquals(BoraiLaunchConfig.DEFAULT_LAT, draft.pickupLat, 0.0001)
        assertEquals(BoraiLaunchConfig.DEFAULT_LNG, draft.pickupLng, 0.0001)
    }

    @Test
    fun `owner configured service boundaries enforce pickup dropoff toggles and vehicle categories`() {
        val boraiZone = BoraiLaunchConfig.defaultZoneTemplate()
        assertTrue(BoraiLaunchConfig.isPointInsideZone(21.8160, 87.3703, boraiZone))

        // Pickup disabled blocks pickup inside zone
        val pickupBlockedZone = boraiZone.copy(pickupEnabled = false)
        val blockedPickupEval = BoraiLaunchConfig.evaluateTripCoverage(
            pickupLat = 21.8160,
            pickupLng = 87.3703,
            dropLat = 21.8500,
            dropLng = 87.3800,
            vehicleCategory = "toto",
            serviceAreas = listOf(pickupBlockedZone)
        )
        assertFalse(blockedPickupEval.isAllowed)
        assertTrue(blockedPickupEval.blockingReason.contains("Pickups are temporarily disabled"))

        // Dropoff disabled blocks destination inside zone
        val dropBlockedZone = boraiZone.copy(dropoffEnabled = false)
        val blockedDropEval = BoraiLaunchConfig.evaluateTripCoverage(
            pickupLat = 22.0000,
            pickupLng = 87.5000,
            dropLat = 21.8160,
            dropLng = 87.3703,
            vehicleCategory = "toto",
            serviceAreas = listOf(dropBlockedZone)
        )
        assertFalse(blockedDropEval.isAllowed)
        assertTrue(blockedDropEval.blockingReason.contains("Drop-offs are temporarily disabled"))

        // Vehicle category restriction blocks cab when only toto/auto enabled
        val totoOnlyZone = boraiZone.copy(supportedVehicleCategories = listOf("toto", "auto"))
        val blockedVehicleEval = BoraiLaunchConfig.evaluateTripCoverage(
            pickupLat = 21.8160,
            pickupLng = 87.3703,
            dropLat = 21.8300,
            dropLng = 87.3900,
            vehicleCategory = "cab",
            serviceAreas = listOf(totoOnlyZone)
        )
        assertFalse(blockedVehicleEval.isAllowed)
        assertTrue(blockedVehicleEval.blockingReason.contains("CAB is not enabled"))

        // Allowed vehicle succeeds inside zone
        val allowedEval = BoraiLaunchConfig.evaluateTripCoverage(
            pickupLat = 21.8160,
            pickupLng = 87.3703,
            dropLat = 21.8300,
            dropLng = 87.3900,
            vehicleCategory = "toto",
            serviceAreas = listOf(totoOnlyZone)
        )
        assertTrue(allowedEval.isAllowed)
    }

    @Test
    fun `osrm routing adapter parses geojson caches results and falls back cleanly on error`() = runBlocking {
        var nowMs = 10_000L
        val calls = AtomicInteger(0)
        val osrmPayload = """
            {
              "code": "Ok",
              "routes": [
                {
                  "distance": 8450.0,
                  "duration": 1020.0,
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [[87.3703, 21.8160], [87.4000, 21.8400], [87.4200, 21.8600]]
                  }
                }
              ]
            }
        """.trimIndent()

        val adapter = OsrmRoutingAdapter(
            endpoints = MapProviderEndpoints(),
            fetcher = object : RoutingHttpFetcher {
                override fun get(url: String, userAgent: String): String {
                    calls.incrementAndGet()
                    return osrmPayload
                }
            },
            cacheTtlMs = 600_000L,
            minIntervalMs = 800L,
            clockMs = { nowMs }
        )

        val r1 = adapter.fetchRoute(21.8160, 87.3703, 21.8600, 87.4200, "toto")
        assertEquals("osrm", r1.provider)
        assertFalse(r1.isFallbackEstimate)
        assertEquals(8.5, r1.distanceKm, 0.2)
        // Raw 17 min * 1.25 rural toto adjustment = 21 min
        assertEquals(21L, r1.durationMin)
        assertEquals(3, r1.points.size)
        assertEquals(1, calls.get())

        // Cache hit does not make a second HTTP request
        nowMs += 100L
        val r2 = adapter.fetchRoute(21.8160, 87.3703, 21.8600, 87.4200, "toto")
        assertEquals(1, calls.get())
        assertEquals(r1.distanceKm, r2.distanceKm, 0.01)

        // Rapid uncached call within 800ms of first HTTP request falls back without hitting HTTP
        nowMs += 100L
        val rThrottled = adapter.fetchRoute(21.8200, 87.3750, 21.9000, 87.4500, "auto")
        assertEquals(1, calls.get())
        assertTrue(rThrottled.isFallbackEstimate)
        assertEquals("haversine_fallback", rThrottled.provider)

        // Network failure falls back gracefully to Haversine road-factor estimate
        val failingAdapter = OsrmRoutingAdapter(
            fetcher = object : RoutingHttpFetcher {
                override fun get(url: String, userAgent: String): String {
                    throw IllegalStateException("Offline")
                }
            },
            clockMs = { 50_000L }
        )
        val fallback = failingAdapter.fetchRoute(21.8160, 87.3703, 21.8600, 87.4200, "cab")
        assertTrue(fallback.isFallbackEstimate)
        assertEquals("haversine_fallback", fallback.provider)
        assertTrue(fallback.distanceKm > 0.0)
        assertTrue(fallback.durationMin >= 1L)
    }

    @Test
    fun `geocoding adapter searches Borai catalog and service zones locally and respects explicit-submit throttling`() = runBlocking {
        var nowMs = 20_000L
        val remoteCalls = AtomicInteger(0)
        val fakeNominatimJson = """
            [
              {
                "place_id": 9912,
                "name": "Belda Station",
                "display_name": "Belda Station, Paschim Medinipur, West Bengal, 721424, India",
                "lat": "22.0785",
                "lon": "87.3420",
                "address": { "state_district": "Paschim Medinipur" }
              }
            ]
        """.trimIndent()

        val adapter = GeocodingSearchAdapter(
            fetcher = object : RoutingHttpFetcher {
                override fun get(url: String, userAgent: String): String {
                    remoteCalls.incrementAndGet()
                    assertTrue(userAgent.contains("GoLocal"))
                    return fakeNominatimJson
                }
            },
            clockMs = { nowMs }
        )

        val boraiZone = BoraiLaunchConfig.defaultZoneTemplate()

        // Local search finds Borai & Mohanpur without remote HTTP call
        val localOnly = adapter.searchPlaces(
            query = "Borai",
            serviceAreas = listOf(boraiZone),
            allowRemoteLookup = false
        )
        assertEquals(0, remoteCalls.get())
        assertTrue(localOnly.any { it.displayName.contains("Borai", ignoreCase = true) })

        // Explicit search triggers remote Nominatim and caches results
        val remote1 = adapter.searchPlaces(
            query = "Belda Station",
            serviceAreas = listOf(boraiZone),
            allowRemoteLookup = true
        )
        assertEquals(1, remoteCalls.get())
        assertTrue(remote1.any { it.source == "osm_nominatim" && it.displayName == "Belda Station" })

        // Cached hit skips HTTP
        nowMs += 200L
        adapter.searchPlaces(query = "Belda Station", serviceAreas = listOf(boraiZone), allowRemoteLookup = true)
        assertEquals(1, remoteCalls.get())

        // Rapid different query within 1100ms of first HTTP request is throttled safely
        nowMs += 200L
        adapter.searchPlaces(query = "Dantan Bazaar", serviceAreas = listOf(boraiZone), allowRemoteLookup = true)
        assertEquals(1, remoteCalls.get())

        // Reverse geocode near Borai matches local place without remote call
        val nearBoraiLabel = adapter.reverseGeocode(21.8162, 87.3705, serviceAreas = listOf(boraiZone), allowRemoteLookup = false)
        assertTrue(nearBoraiLabel.contains("Borai"))
    }

    @Test
    fun `maplibre style and geojson builders produce valid GeoJSON and attribution`() {
        val styleJson = MapProviderConfig.buildMapLibreStyleJson(lowNetworkLite = false)
        assertTrue(styleJson.contains("OpenStreetMap"))
        assertTrue(styleJson.contains("osm-tiles"))

        val liteStyleJson = MapProviderConfig.buildMapLibreStyleJson(lowNetworkLite = true)
        assertFalse(liteStyleJson.contains("osm-raster-layer"))

        val zonesGeo = JSONObject(
            buildZonesGeoJson(
                listOf(
                    MapZoneOverlay(
                        id = "borai",
                        name = "Borai Zone",
                        centerLat = 21.8160,
                        centerLng = 87.3703,
                        radiusKm = 8.0,
                        polygonPoints = BoraiLaunchConfig.defaultPolygon(),
                        isActive = true
                    )
                )
            )
        )
        assertEquals("FeatureCollection", zonesGeo.getString("type"))
        assertEquals(1, zonesGeo.getJSONArray("features").length())

        val routeGeo = JSONObject(
            buildRouteGeoJson(
                routePoints = listOf(GeoPointDto(21.8160, 87.3703), GeoPointDto(21.8500, 87.4100)),
                pickupPoint = null,
                destinationPoint = null
            )
        )
        assertEquals(1, routeGeo.getJSONArray("features").length())

        val markersGeo = JSONObject(
            buildMarkersGeoJson(
                pickupPoint = GeoPointDto(21.8160, 87.3703),
                destinationPoint = GeoPointDto(21.8500, 87.4100),
                driverLivePoint = GeoPointDto(21.8250, 87.3820),
                onlineDrivers = listOf(
                    DriverDirectoryEntry(
                        driverId = "drv_1",
                        displayName = "Raju Mondal",
                        isOnline = true,
                        currentLat = 21.8200,
                        currentLng = 87.3750,
                        verificationStatus = "verified"
                    )
                ),
                extraMarkers = listOf(
                    MapMarkerPin(id = "sos_1", lat = 21.8300, lng = 87.3900, title = "SOS")
                )
            )
        )
        // 1 online driver + 1 extra + pickup + destination + live driver = 5 point features
        assertEquals(5, markersGeo.getJSONArray("features").length())
    }
}
