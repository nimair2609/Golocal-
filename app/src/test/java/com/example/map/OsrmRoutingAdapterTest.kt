package com.example.map

import com.example.data.map.OsrmRoutingAdapter
import com.example.data.map.RoutePoint
import com.example.data.map.RouteResult
import com.example.domain.BoraiLaunchConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OsrmRoutingAdapterTest {

    @Test
    fun `parses valid OSRM GeoJSON route response accurately`() {
        val sampleJson = """
            {
              "code": "Ok",
              "routes": [
                {
                  "distance": 6420.5,
                  "duration": 930.0,
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [
                      [87.3703, 21.8160],
                      [87.3924, 21.8295],
                      [87.4196, 21.8442]
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val result = OsrmRoutingAdapter.parseOsrmResponseJson(
            rawJson = sampleJson,
            originLat = BoraiLaunchConfig.DEFAULT_LAT,
            originLng = BoraiLaunchConfig.DEFAULT_LNG,
            destLat = 21.8442,
            destLng = 87.4196
        )

        assertEquals("osrm", result.source)
        assertEquals(6.4, result.distanceKm, 0.05)
        assertEquals(16, result.durationMin)
        assertEquals(16L, result.durationMinutes)
        assertEquals(3, result.polylinePoints.size)
        assertEquals(3, result.geometry.size)
        assertEquals(21.8160, result.polylinePoints.first().lat, 0.0001)
        assertEquals(87.3703, result.polylinePoints.first().lng, 0.0001)
    }

    @Test
    fun `returns explicit offline fallback when OSRM status is NoRoute`() {
        val noRouteJson = """{"code":"NoRoute","routes":[]}"""
        val result = OsrmRoutingAdapter.parseOsrmResponseJson(
            rawJson = noRouteJson,
            originLat = 21.8160,
            originLng = 87.3703,
            destLat = 21.8442,
            destLng = 87.4196
        )

        assertEquals("offline_fallback", result.source)
        assertTrue(result.distanceKm >= 1.2)
        assertTrue(result.durationMin >= 4)
        assertEquals(2, result.polylinePoints.size)
        assertTrue(result.errorNote.contains("NoRoute"))
    }

    @Test
    fun `serializes RouteResult into valid GeoJSON FeatureCollection and formats distance`() {
        val route = RouteResult(
            distanceKm = 8.45,
            durationMin = 18,
            polylinePoints = listOf(RoutePoint(21.8160, 87.3703), RoutePoint(21.8442, 87.4196)),
            source = "osrm"
        )
        val geoJson = route.toGeoJsonFeatureCollection()
        assertTrue(geoJson.contains("\"FeatureCollection\""))
        assertTrue(geoJson.contains("\"LineString\""))
        assertTrue(geoJson.contains("87.3703"))
        assertEquals("8.5 km", OsrmRoutingAdapter.formatDistanceKm(8.46))
        assertEquals("0.0 km", OsrmRoutingAdapter.formatDistanceKm(-3.0))
    }
}
