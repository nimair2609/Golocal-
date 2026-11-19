package com.example.data.map

import android.util.Log
import com.example.domain.FareCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class RoutePoint(
    val lat: Double,
    val lng: Double
)

data class RouteResult(
    val distanceKm: Double,
    val durationMin: Int,
    val polylinePoints: List<RoutePoint>,
    val source: String, // "osrm" or "offline_fallback"
    val errorNote: String = ""
) {
    val geometry: List<RoutePoint> get() = polylinePoints
    val durationMinutes: Long get() = durationMin.toLong()

    fun toGeoJsonFeatureCollection(): String {
        val coords = JSONArray()
        polylinePoints.forEach { pt ->
            coords.put(JSONArray().put(pt.lng).put(pt.lat))
        }
        val geometry = JSONObject()
            .put("type", "LineString")
            .put("coordinates", coords)
        val feature = JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject().put("source", source))
            .put("geometry", geometry)
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", JSONArray().put(feature))
            .toString()
    }
}

interface RoutingProvider {
    suspend fun computeRoute(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double
    ): RouteResult
}

/**
 * OSRM-compatible road routing adapter with TTL response caching, request throttling,
 * and an explicit offline straight-line fallback when the routing server is unreachable.
 */
class OsrmRoutingAdapter(
    private val baseUrl: String = MapProviderConfig.osrmBaseUrl,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val timeProviderMs: () -> Long = { System.currentTimeMillis() }
) : RoutingProvider {

    private data class CachedRoute(val result: RouteResult, val expiresAtMs: Long)

    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, CachedRoute>(64, 0.75f, true)
    private var lastRequestAtMs = 0L

    override suspend fun computeRoute(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double
    ): RouteResult {
        if (!isValidCoordinate(originLat, originLng) || !isValidCoordinate(destLat, destLng)) {
            return buildFallbackRoute(originLat, originLng, destLat, destLng, "Invalid coordinates")
        }

        val cacheKey = String.format(
            Locale.US,
            "%.4f,%.4f->%.4f,%.4f",
            originLat, originLng, destLat, destLng
        )
        val now = timeProviderMs()
        mutex.withLock {
            val hit = cache[cacheKey]
            if (hit != null && hit.expiresAtMs > now) {
                return hit.result
            }
        }

        return try {
            val responseBody = fetchOsrmJson(originLat, originLng, destLat, destLng)
            val parsed = parseOsrmResponseJson(responseBody, originLat, originLng, destLat, destLng)
            mutex.withLock {
                if (cache.size >= MAX_CACHE_ENTRIES) {
                    val eldest = cache.entries.firstOrNull()?.key
                    if (eldest != null) cache.remove(eldest)
                }
                cache[cacheKey] = CachedRoute(parsed, timeProviderMs() + CACHE_TTL_MS)
            }
            parsed
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "OSRM route request failed, returning offline fallback: ${e.message}")
            buildFallbackRoute(
                originLat,
                originLng,
                destLat,
                destLng,
                e.localizedMessage ?: "Routing endpoint unreachable"
            )
        }
    }

    private suspend fun fetchOsrmJson(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double
    ): String {
        mutex.withLock {
            val waitMs = MIN_INTERVAL_MS - (timeProviderMs() - lastRequestAtMs)
            if (waitMs > 0) {
                kotlinx.coroutines.delay(waitMs)
            }
            lastRequestAtMs = timeProviderMs()
        }

        val cleanBase = baseUrl.trimEnd('/')
        val url = String.format(
            Locale.US,
            "%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
            cleanBase,
            originLng,
            originLat,
            destLng,
            destLat
        )

        return withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "GoLocalAndroid/1.0 (OpenStreetMap OSRM Client)")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("OSRM HTTP ${resp.code}")
                }
                resp.body?.string()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Empty OSRM body")
            }
        }
    }

    companion object {
        private const val TAG = "OsrmRoutingAdapter"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val MIN_INTERVAL_MS = 350L
        private const val MAX_CACHE_ENTRIES = 64

        fun parseOsrmResponseJson(
            rawJson: String,
            originLat: Double,
            originLng: Double,
            destLat: Double,
            destLng: Double
        ): RouteResult {
            val root = JSONObject(rawJson)
            val code = root.optString("code", "")
            if (code != "Ok") {
                return buildFallbackRoute(
                    originLat,
                    originLng,
                    destLat,
                    destLng,
                    "OSRM status: ${code.ifBlank { "Error" }}"
                )
            }
            val routes = root.optJSONArray("routes")
            if (routes == null || routes.length() == 0) {
                return buildFallbackRoute(originLat, originLng, destLat, destLng, "No drivable route returned")
            }
            val best = routes.getJSONObject(0)
            val distanceMeters = best.optDouble("distance", 0.0).coerceAtLeast(0.0)
            val durationSeconds = best.optDouble("duration", 0.0).coerceAtLeast(0.0)

            val points = mutableListOf<RoutePoint>()
            val coords = best.optJSONObject("geometry")?.optJSONArray("coordinates")
            if (coords != null) {
                for (i in 0 until coords.length()) {
                    val pair = coords.optJSONArray(i) ?: continue
                    if (pair.length() >= 2) {
                        val lng = pair.optDouble(0, Double.NaN)
                        val lat = pair.optDouble(1, Double.NaN)
                        if (isValidCoordinate(lat, lng)) {
                            points.add(RoutePoint(lat = lat, lng = lng))
                        }
                    }
                }
            }
            if (points.size < 2) {
                points.clear()
                points.add(RoutePoint(originLat, originLng))
                points.add(RoutePoint(destLat, destLng))
            }

            val distanceKm = ((distanceMeters / 1000.0) * 10.0).roundToInt() / 10.0
            val durationMin = (durationSeconds / 60.0).roundToInt().coerceAtLeast(1)
            return RouteResult(
                distanceKm = distanceKm.coerceAtLeast(0.1),
                durationMin = durationMin,
                polylinePoints = points,
                source = "osrm"
            )
        }

        fun buildFallbackRoute(
            originLat: Double,
            originLng: Double,
            destLat: Double,
            destLng: Double,
            reason: String = ""
        ): RouteResult {
            val estKm = FareCalculator.haversineDistanceKm(originLat, originLng, destLat, destLng)
            val estMin = ((estKm / 22.0) * 60.0).roundToInt().coerceAtLeast(4)
            return RouteResult(
                distanceKm = estKm,
                durationMin = estMin,
                polylinePoints = listOf(
                    RoutePoint(originLat, originLng),
                    RoutePoint(destLat, destLng)
                ),
                source = "offline_fallback",
                errorNote = reason
            )
        }

        fun isValidCoordinate(lat: Double, lng: Double): Boolean {
            return !lat.isNaN() && !lng.isNaN() && lat in -90.0..90.0 && lng in -180.0..180.0
        }

        fun formatDistanceKm(km: Double): String {
            return String.format(Locale.US, "%.1f km", km.coerceAtLeast(0.0))
        }
    }
}
