package com.example.map

import android.os.Build
import com.example.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
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
        val reason: String,
        val estimatedDurationMin: Long = max(1L, ((distanceKm / 24.0) * 60.0).roundToLong()),
        val providerName: String = "Direct Fallback"
    ) : RouteUiState
}

/**
 * Pluggable navigation route provider abstraction so GoLocal can use OSRM by default
 * or swap in a licensed commercial routing provider at runtime without hardcoding keys.
 */
interface RoutingProvider {
    val providerName: String
    suspend fun computeDrivingRoute(origin: GeoPoint, destination: GeoPoint): RouteUiState
}

class OsrmRoutingProvider(
    private val client: OkHttpClient = defaultHttpClient
) : RoutingProvider {
    override val providerName: String = "OSRM Driving"

    override suspend fun computeDrivingRoute(
        origin: GeoPoint,
        destination: GeoPoint
    ): RouteUiState {
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
                            distanceKm = (straightKm * 10.0).roundToLong() / 10.0,
                            reason = "Routing server HTTP ${response.code}; showing direct line"
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    val parsed = OsrmRouteService.parseOsrmGeoJsonResponse(body)
                    if (parsed != null) {
                        parsed
                    } else {
                        RouteUiState.Fallback(
                            points = listOf(origin, destination),
                            distanceKm = (straightKm * 10.0).roundToLong() / 10.0,
                            reason = "Route geometry unavailable; showing direct line"
                        )
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            RouteUiState.Fallback(
                points = listOf(origin, destination),
                distanceKm = (straightKm * 10.0).roundToLong() / 10.0,
                reason = "Offline or slow route lookup; showing direct line"
            )
        }
    }

    private companion object {
        val defaultHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}

object RouteNavigationMath {
    const val DEFAULT_DEVIATION_THRESHOLD_METERS = 75.0
    const val REROUTE_DEBOUNCE_MS = 12_000L

    fun shouldRecalculateRoute(
        currentLat: Double,
        currentLng: Double,
        activeRoute: List<GeoPoint>,
        lastRerouteEpochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        deviationThresholdMeters: Double = DEFAULT_DEVIATION_THRESHOLD_METERS
    ): Boolean {
        if (!GpsBroadcastThrottle.isValidCoordinate(currentLat, currentLng)) return false
        if (activeRoute.size < 2) return false
        if (nowEpochMs - lastRerouteEpochMs < REROUTE_DEBOUNCE_MS) return false
        val deviationMeters = GpsBroadcastThrottle.distanceFromRouteMeters(currentLat, currentLng, activeRoute)
        return deviationMeters > deviationThresholdMeters
    }

    fun estimateEtaMinutes(
        distanceKm: Double,
        speedMps: Double = 0.0,
        vehicleCategory: String = "toto"
    ): Int {
        if (distanceKm <= 0.05) return 1
        val observedKmph = if (speedMps > 1.2) speedMps * 3.6 else 0.0
        val categoryFallbackKmph = when (vehicleCategory.lowercase()) {
            "bike" -> 28.0
            "cab" -> 32.0
            "auto" -> 24.0
            else -> 20.0
        }
        val effectiveKmph = if (observedKmph > 5.0) {
            ((observedKmph * 0.6) + (categoryFallbackKmph * 0.4)).coerceIn(8.0, 75.0)
        } else {
            categoryFallbackKmph
        }
        return max(1, ((distanceKm / effectiveKmph) * 60.0).roundToLong().toInt())
    }
}

object OsrmRouteService {
    @Volatile
    private var activeProvider: RoutingProvider = OsrmRoutingProvider()

    private val routeCache: MutableMap<String, RouteUiState.Ready> = Collections.synchronizedMap(
        object : LinkedHashMap<String, RouteUiState.Ready>(24, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RouteUiState.Ready>?): Boolean =
                size > 24
        }
    )

    fun configureProvider(provider: RoutingProvider) {
        activeProvider = provider
        routeCache.clear()
    }

    fun activeProviderName(): String = activeProvider.providerName

    fun cacheKey(p1: GeoPoint, p2: GeoPoint): String {
        fun fmt(v: Double) = String.format(java.util.Locale.US, "%.4f", v)
        return "${fmt(p1.lat)},${fmt(p1.lng)}->${fmt(p2.lat)},${fmt(p2.lng)}"
    }

    suspend fun fetchDrivingRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        bypassCache: Boolean = false
    ): RouteUiState {
        if (!GpsBroadcastThrottle.isValidCoordinate(origin.lat, origin.lng) ||
            !GpsBroadcastThrottle.isValidCoordinate(destination.lat, destination.lng)
        ) {
            return RouteUiState.Idle
        }

        val key = cacheKey(origin, destination)
        if (!bypassCache) {
            routeCache[key]?.let { return it }
        }

        val result = activeProvider.computeDrivingRoute(origin, destination)
        if (result is RouteUiState.Ready) {
            routeCache[key] = result
        }
        return result
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

data class NominatimPlaceResult(
    val displayName: String,
    val primaryLabel: String,
    val district: String,
    val lat: Double,
    val lng: Double
)

object NominatimGeocoder {
    private const val DEFAULT_GEOCODING_URL = "https://nominatim.openstreetmap.org"
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val queryCache: MutableMap<String, List<NominatimPlaceResult>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<NominatimPlaceResult>>(24, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<NominatimPlaceResult>>?): Boolean =
                size > 24
        }
    )

    suspend fun searchPlaces(
        query: String,
        biasLat: Double? = null,
        biasLng: Double? = null,
        limit: Int = 5
    ): List<NominatimPlaceResult> {
        val trimmed = query.trim()
        if (trimmed.length < 3) return emptyList()
        val fp = Build.FINGERPRINT.orEmpty()
        val hw = Build.HARDWARE.orEmpty()
        if (fp.contains("robolectric", ignoreCase = true) || hw.contains("robolectric", ignoreCase = true)) {
            return emptyList()
        }

        val cacheKey = trimmed.lowercase()
        queryCache[cacheKey]?.let { return it }

        return try {
            withContext(Dispatchers.IO) {
                val baseUrl = BuildConfig.GEOCODING_BASE_URL.ifBlank { DEFAULT_GEOCODING_URL }.trimEnd('/')
                val encoded = URLEncoder.encode(trimmed, "UTF-8")
                val viewboxParam = if (
                    biasLat != null && biasLng != null &&
                    GpsBroadcastThrottle.isValidCoordinate(biasLat, biasLng)
                ) {
                    val left = biasLng - 0.6
                    val right = biasLng + 0.6
                    val top = biasLat + 0.6
                    val bottom = biasLat - 0.6
                    String.format(java.util.Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f", left, top, right, bottom)
                } else ""
                val url = "$baseUrl/search?q=$encoded&format=jsonv2&addressdetails=1&countrycodes=in&limit=${limit.coerceIn(1, 8)}$viewboxParam"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "GoLocal-Android/1.0 (India Mobility)")
                    .header("Accept", "application/json")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string().orEmpty()
                    val parsed = parseNominatimSearchJson(body)
                    if (parsed.isNotEmpty()) {
                        queryCache[cacheKey] = parsed
                    }
                    parsed
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun parseNominatimSearchJson(rawJson: String): List<NominatimPlaceResult> {
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(rawJson)
            val results = mutableListOf<NominatimPlaceResult>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val lat = item.optString("lat").toDoubleOrNull() ?: continue
                val lng = item.optString("lon").toDoubleOrNull() ?: continue
                if (!GpsBroadcastThrottle.isValidCoordinate(lat, lng)) continue
                val displayName = item.optString("display_name").trim()
                if (displayName.isBlank()) continue
                val name = item.optString("name").trim()
                val address = item.optJSONObject("address")
                val district = address?.optString("state_district")?.ifBlank { null }
                    ?: address?.optString("county")?.ifBlank { null }
                    ?: address?.optString("city")?.ifBlank { null }
                    ?: address?.optString("state")?.ifBlank { null }
                    ?: "India"
                val primaryLabel = name.ifBlank {
                    displayName.substringBefore(",").trim().ifBlank { displayName }
                }
                results.add(
                    NominatimPlaceResult(
                        displayName = displayName,
                        primaryLabel = primaryLabel,
                        district = district,
                        lat = lat,
                        lng = lng
                    )
                )
            }
            results.distinctBy { "${it.primaryLabel.lowercase()}_${it.district.lowercase()}" }
        }.getOrDefault(emptyList())
    }
}

