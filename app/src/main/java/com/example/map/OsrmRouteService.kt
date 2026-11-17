package com.example.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToLong

data class GeoPoint(
    val lat: Double,
    val lng: Double
)

sealed interface RouteUiState {
    data object Idle : RouteUiState
    data object Loading : RouteUiState
    data class Ready(
        val points: List<GeoPoint>,
        val distanceKm: Double,
        val durationMin: Long
    ) : RouteUiState

    data class Fallback(
        val points: List<GeoPoint>,
        val distanceKm: Double,
        val reason: String
    ) : RouteUiState
}

object OsrmRouteService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val routeCache: MutableMap<String, RouteUiState.Ready> = Collections.synchronizedMap(
        object : LinkedHashMap<String, RouteUiState.Ready>(24, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RouteUiState.Ready>?): Boolean =
                size > 24
        }
    )

    fun cacheKey(p1: GeoPoint, p2: GeoPoint): String {
        fun fmt(v: Double) = String.format(java.util.Locale.US, "%.4f", v)
        return "${fmt(p1.lat)},${fmt(p1.lng)}->${fmt(p2.lat)},${fmt(p2.lng)}"
    }

    suspend fun fetchDrivingRoute(
        origin: GeoPoint,
        destination: GeoPoint
    ): RouteUiState {
        if (!GpsBroadcastThrottle.isValidCoordinate(origin.lat, origin.lng) ||
            !GpsBroadcastThrottle.isValidCoordinate(destination.lat, destination.lng)
        ) {
            return RouteUiState.Idle
        }

        val key = cacheKey(origin, destination)
        routeCache[key]?.let { return it }

        val straightKm = GpsBroadcastThrottle.haversineMeters(
            origin.lat,
            origin.lng,
            destination.lat,
            destination.lng
        ) / 1000.0

        return try {
            withContext(Dispatchers.IO) {
                val url = String.format(
                    java.util.Locale.US,
                    "https://router.project-osrm.org/route/v1/driving/%.5f,%.5f;%.5f,%.5f?overview=full&geometries=geojson",
                    origin.lng,
                    origin.lat,
                    destination.lng,
                    destination.lat
                )
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "GoLocal-Android/1.0")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext RouteUiState.Fallback(
                            points = listOf(origin, destination),
                            distanceKm = straightKm,
                            reason = "Routing server HTTP ${response.code}; showing direct line"
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    val parsed = parseOsrmGeoJsonResponse(body)
                    if (parsed != null) {
                        routeCache[key] = parsed
                        parsed
                    } else {
                        RouteUiState.Fallback(
                            points = listOf(origin, destination),
                            distanceKm = straightKm,
                            reason = "Route geometry unavailable; showing direct line"
                        )
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            RouteUiState.Fallback(
                points = listOf(origin, destination),
                distanceKm = straightKm,
                reason = "Offline or slow route lookup; showing direct line"
            )
        }
    }

    internal fun parseOsrmGeoJsonResponse(rawJson: String): RouteUiState.Ready? {
        if (rawJson.isBlank()) return null
        val root = JSONObject(rawJson)
        if (root.optString("code") != "Ok") return null
        val routes = root.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null

        val primary = routes.getJSONObject(0)
        val distanceKm = primary.optDouble("distance", 0.0) / 1000.0
        val durationMin = max(1L, (primary.optDouble("duration", 0.0) / 60.0).roundToLong())
        val geometry = primary.optJSONObject("geometry") ?: return null
        val coords = geometry.optJSONArray("coordinates") ?: return null

        val points = ArrayList<GeoPoint>(coords.length())
        for (i in 0 until coords.length()) {
            val pair = coords.optJSONArray(i) ?: continue
            if (pair.length() >= 2) {
                val lng = pair.optDouble(0)
                val lat = pair.optDouble(1)
                if (GpsBroadcastThrottle.isValidCoordinate(lat, lng)) {
                    points.add(GeoPoint(lat = lat, lng = lng))
                }
            }
        }
        if (points.size < 2) return null
        return RouteUiState.Ready(
            points = points,
            distanceKm = (distanceKm * 10.0).roundToLong() / 10.0,
            durationMin = durationMin
        )
    }
}
