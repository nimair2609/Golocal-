package com.example.data.map

import com.example.data.model.GeoPointDto
import com.example.domain.FareCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

data class RouteResult(
    val points: List<GeoPointDto>,
    val distanceKm: Double,
    val durationMin: Long,
    val provider: String,
    val isFallbackEstimate: Boolean = false,
    val warningMessage: String? = null
)

interface RoutingHttpFetcher {
    fun get(url: String, userAgent: String): String
}

class OkHttpRoutingFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
) : RoutingHttpFetcher {
    override fun get(url: String, userAgent: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("OSRM HTTP ${response.code}")
            }
            return response.body?.string()
                ?: throw IllegalStateException("Empty OSRM response body")
        }
    }
}

/**
 * OSRM-compatible road routing adapter with:
 * - Configurable base URL (default public demo URL, swappable to self-hosted OSRM)
 * - In-memory LRU TTL cache (10 minutes) to minimize redundant requests
 * - Request throttling (>= 800 ms between live calls)
 * - Clean Haversine road-factor fallback on network errors, timeouts, or rate limits
 */
class OsrmRoutingAdapter(
    private val endpoints: MapProviderEndpoints = MapProviderEndpoints(),
    private val fetcher: RoutingHttpFetcher = OkHttpRoutingFetcher(),
    private val cacheTtlMs: Long = 10 * 60 * 1000L,
    private val minIntervalMs: Long = 800L,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private data class CachedEntry(val result: RouteResult, val storedAtMs: Long)

    private val cacheLock = Mutex()
    private val cache = object : LinkedHashMap<String, CachedEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedEntry>?): Boolean = size > 50
    }
    private var lastRequestEpochMs: Long = 0L

    suspend fun fetchRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        vehicleCategory: String = "toto"
    ): RouteResult {
        val directKm = FareCalculator.haversineKm(startLat, startLng, endLat, endLng)
        if (directKm < 0.05) {
            return RouteResult(
                points = listOf(GeoPointDto(startLat, startLng), GeoPointDto(endLat, endLng)),
                distanceKm = 0.2,
                durationMin = 2L,
                provider = "local_short_hop",
                isFallbackEstimate = false
            )
        }

        val cacheKey = buildCacheKey(startLat, startLng, endLat, endLng, vehicleCategory)
        val now = clockMs()
        cacheLock.withLock {
            val cached = cache[cacheKey]
            if (cached != null && (now - cached.storedAtMs) <= cacheTtlMs) {
                return cached.result
            }
            if ((now - lastRequestEpochMs) < minIntervalMs && cached != null) {
                return cached.result
            }
        }

        val throttledOut = cacheLock.withLock {
            val tNow = clockMs()
            if (tNow - lastRequestEpochMs < minIntervalMs) {
                true
            } else {
                lastRequestEpochMs = tNow
                false
            }
        }

        if (throttledOut) {
            return buildFallbackRoute(
                startLat = startLat,
                startLng = startLng,
                endLat = endLat,
                endLng = endLng,
                vehicleCategory = vehicleCategory,
                warning = "Route request throttled; showing estimated road distance."
            )
        }

        val profile = when (vehicleCategory.lowercase()) {
            "bike" -> "driving"
            else -> "driving"
        }
        val url = String.format(
            Locale.US,
            "%s/route/v1/%s/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
            endpoints.osrmBaseUrl.trimEnd('/'),
            profile,
            startLng,
            startLat,
            endLng,
            endLat
        )

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val raw = fetcher.get(url, endpoints.userAgent)
                parseOsrmGeoJson(raw, vehicleCategory)
            }.getOrElse { err ->
                buildFallbackRoute(
                    startLat = startLat,
                    startLng = startLng,
                    endLat = endLat,
                    endLng = endLng,
                    vehicleCategory = vehicleCategory,
                    warning = "Offline/OSRM fallback (${err.message ?: "network unavailable"})"
                )
            }
        }

        if (!result.isFallbackEstimate) {
            cacheLock.withLock {
                cache[cacheKey] = CachedEntry(result, clockMs())
            }
        }
        return result
    }

    fun parseOsrmGeoJson(rawJson: String, vehicleCategory: String = "toto"): RouteResult {
        val root = JSONObject(rawJson)
        val code = root.optString("code", "")
        if (code != "Ok") {
            throw IllegalStateException("OSRM status: $code")
        }
        val routes = root.getJSONArray("routes")
        if (routes.length() == 0) {
            throw IllegalStateException("No route returned by OSRM")
        }
        val first = routes.getJSONObject(0)
        val distanceMeters = first.optDouble("distance", 0.0)
        val durationSeconds = first.optDouble("duration", 0.0)
        val coords = first.getJSONObject("geometry").getJSONArray("coordinates")
        val points = buildList(coords.length()) {
            for (i in 0 until coords.length()) {
                val pair = coords.getJSONArray(i)
                val lng = pair.getDouble(0)
                val lat = pair.getDouble(1)
                add(GeoPointDto(lat = lat, lng = lng))
            }
        }
        val distanceKm = ((distanceMeters / 1000.0) * 10.0).roundToLong() / 10.0
        val rawMinutes = (durationSeconds / 60.0).roundToLong().coerceAtLeast(2L)
        // Adjust slightly for slow local rural e-rickshaws / totos if vehicle is toto
        val adjustedMinutes = if (vehicleCategory.equals("toto", ignoreCase = true)) {
            (rawMinutes * 1.25).roundToLong().coerceAtLeast(rawMinutes)
        } else {
            rawMinutes
        }
        return RouteResult(
            points = points,
            distanceKm = distanceKm.coerceAtLeast(0.2),
            durationMin = adjustedMinutes,
            provider = "osrm",
            isFallbackEstimate = false
        )
    }

    fun buildFallbackRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        vehicleCategory: String = "toto",
        warning: String = "Estimated road route (OSRM unavailable)"
    ): RouteResult {
        val straightKm = FareCalculator.haversineKm(startLat, startLng, endLat, endLng)
        val approxRoadKm = ((straightKm * 1.28) * 10.0).roundToLong() / 10.0
        val effectiveKm = approxRoadKm.coerceAtLeast(0.5)
        val etaMin = FareCalculator.estimateDurationMinutes(effectiveKm, vehicleCategory).coerceAtLeast(2L)
        return RouteResult(
            points = listOf(GeoPointDto(startLat, startLng), GeoPointDto(endLat, endLng)),
            distanceKm = effectiveKm,
            durationMin = etaMin,
            provider = "haversine_fallback",
            isFallbackEstimate = true,
            warningMessage = warning
        )
    }

    private fun buildCacheKey(
        sLat: Double,
        sLng: Double,
        eLat: Double,
        eLng: Double,
        vehicle: String
    ): String = String.format(
        Locale.US,
        "%.4f,%.4f->%.4f,%.4f:%s",
        sLat,
        sLng,
        eLat,
        eLng,
        vehicle.lowercase()
    )
}
