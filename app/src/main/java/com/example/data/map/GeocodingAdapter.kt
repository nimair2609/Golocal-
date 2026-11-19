package com.example.data.map

import android.util.Log
import com.example.data.model.ServiceArea
import com.example.domain.BoraiLaunchConfig
import com.example.domain.WestBengalCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

data class GeocodedPlaceResult(
    val id: String,
    val label: String,
    val secondaryLabel: String,
    val lat: Double,
    val lng: Double,
    val district: String,
    val pinCode: String = "",
    val source: String, // "local_catalog", "service_area", "nominatim"
    val category: String = "place"
)

data class ReverseGeocodeResult(
    val label: String,
    val district: String,
    val pinCode: String,
    val source: String // "nominatim", "local_catalog", "coordinates_fallback"
)

interface GeocodingProvider {
    suspend fun searchPlaces(
        query: String,
        serviceAreas: List<ServiceArea> = emptyList(),
        biasLat: Double = BoraiLaunchConfig.DEFAULT_LAT,
        biasLng: Double = BoraiLaunchConfig.DEFAULT_LNG
    ): List<GeocodedPlaceResult>

    suspend fun reverseGeocode(lat: Double, lng: Double): ReverseGeocodeResult
}

/**
 * Configurable OpenStreetMap / Nominatim-compatible geocoding adapter merged with
 * local West Bengal hubs and Owner-configured Firestore ServiceAreas.
 * Respects >=1100ms throttling, HTTP 429 cooldowns, and in-memory TTL caching.
 */
class HybridGeocodingRepository(
    private val baseUrl: String = MapProviderConfig.geocodingBaseUrl,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val timeProviderMs: () -> Long = { System.currentTimeMillis() },
    private val minIntervalMs: Long = 1100L
) : GeocodingProvider {

    private data class CachedSearch(val items: List<GeocodedPlaceResult>, val expiresAtMs: Long)
    private data class CachedReverse(val result: ReverseGeocodeResult, val expiresAtMs: Long)

    private val mutex = Mutex()
    private val searchCache = LinkedHashMap<String, CachedSearch>(64, 0.75f, true)
    private val reverseCache = LinkedHashMap<String, CachedReverse>(64, 0.75f, true)
    private var lastHttpAtMs = 0L
    private var rateLimitCooldownUntilMs = 0L

    override suspend fun searchPlaces(
        query: String,
        serviceAreas: List<ServiceArea>,
        biasLat: Double,
        biasLng: Double
    ): List<GeocodedPlaceResult> {
        val localMatches = searchLocalAndZones(query, serviceAreas)
        val trimmed = query.trim()
        if (trimmed.length < 3) {
            return localMatches
        }

        val cacheKey = "${trimmed.lowercase(Locale.ROOT)}@${String.format(Locale.US, "%.2f,%.2f", biasLat, biasLng)}"
        val now = timeProviderMs()
        val cachedRemote = mutex.withLock {
            searchCache[cacheKey]?.takeIf { it.expiresAtMs > now }?.items
        }
        if (cachedRemote != null) {
            return mergeDistinct(localMatches, cachedRemote)
        }

        val remoteResults = try {
            val url = buildSearchUrl(trimmed, biasLat, biasLng) ?: return localMatches
            val raw = executeThrottledGet(url) ?: return localMatches
            val parsed = parseNominatimSearchJson(raw)
            mutex.withLock {
                if (searchCache.size >= MAX_CACHE) {
                    searchCache.entries.firstOrNull()?.key?.let { searchCache.remove(it) }
                }
                searchCache[cacheKey] = CachedSearch(parsed, timeProviderMs() + SEARCH_TTL_MS)
            }
            parsed
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "Geocoding search fell back to local catalog: ${e.message}")
            emptyList()
        }

        return mergeDistinct(localMatches, remoteResults)
    }

    override suspend fun reverseGeocode(lat: Double, lng: Double): ReverseGeocodeResult {
        if (!OsrmRoutingAdapter.isValidCoordinate(lat, lng)) {
            return ReverseGeocodeResult(
                label = BoraiLaunchConfig.DEFAULT_DISPLAY_LABEL,
                district = BoraiLaunchConfig.DEFAULT_DISTRICT,
                pinCode = BoraiLaunchConfig.DEFAULT_PIN_CODE,
                source = "coordinates_fallback"
            )
        }

        val key = String.format(Locale.US, "%.4f,%.4f", lat, lng)
        val now = timeProviderMs()
        mutex.withLock {
            reverseCache[key]?.takeIf { it.expiresAtMs > now }?.let { return it.result }
        }

        // Check very close catalog hit (< 0.45 km) so offline Borai/Mohanpur picks still get nice names
        val nearCatalog = WestBengalCatalog.nearestPlace(lat, lng, maxKm = 0.45)

        val remote = try {
            val cleanBase = baseUrl.trimEnd('/')
            val url = "$cleanBase/reverse".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("format", "jsonv2")
                ?.addQueryParameter("lat", String.format(Locale.US, "%.6f", lat))
                ?.addQueryParameter("lon", String.format(Locale.US, "%.6f", lng))
                ?.addQueryParameter("addressdetails", "1")
                ?.addQueryParameter("zoom", "16")
                ?.build()
                ?.toString()
            if (url != null) {
                executeThrottledGet(url)?.let { parseNominatimReverseJson(it, lat, lng) }
            } else null
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocoding failed: ${e.message}")
            null
        }

        val resolved = remote ?: if (nearCatalog != null) {
            ReverseGeocodeResult(
                label = "${nearCatalog.nameEn}, ${nearCatalog.district}",
                district = nearCatalog.district,
                pinCode = nearCatalog.pinCode,
                source = "local_catalog"
            )
        } else {
            ReverseGeocodeResult(
                label = "Pinned Location (${String.format(Locale.US, "%.4f", lat)}, ${String.format(Locale.US, "%.4f", lng)})",
                district = "",
                pinCode = "",
                source = "coordinates_fallback"
            )
        }

        mutex.withLock {
            if (reverseCache.size >= MAX_CACHE) {
                reverseCache.entries.firstOrNull()?.key?.let { reverseCache.remove(it) }
            }
            reverseCache[key] = CachedReverse(resolved, timeProviderMs() + REVERSE_TTL_MS)
        }
        return resolved
    }

    private fun buildSearchUrl(query: String, biasLat: Double, biasLng: Double): String? {
        val cleanBase = baseUrl.trimEnd('/')
        val builder = "$cleanBase/search".toHttpUrlOrNull()?.newBuilder() ?: return null
        val viewbox = String.format(
            Locale.US,
            "%.4f,%.4f,%.4f,%.4f",
            biasLng - 0.65,
            biasLat + 0.65,
            biasLng + 0.65,
            biasLat - 0.65
        )
        return builder
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("q", query)
            .addQueryParameter("addressdetails", "1")
            .addQueryParameter("limit", "8")
            .addQueryParameter("countrycodes", "in")
            .addQueryParameter("viewbox", viewbox)
            .build()
            .toString()
    }

    private suspend fun executeThrottledGet(url: String): String? {
        mutex.withLock {
            val now = timeProviderMs()
            if (now < rateLimitCooldownUntilMs) {
                return null
            }
            val wait = minIntervalMs - (now - lastHttpAtMs)
            if (wait > 0) {
                kotlinx.coroutines.delay(wait)
            }
            lastHttpAtMs = timeProviderMs()
        }

        return withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "GoLocalAndroid/1.0 (https://golocal.nrnext.in)")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 429 || resp.code == 503) {
                    mutex.withLock {
                        rateLimitCooldownUntilMs = timeProviderMs() + COOLDOWN_AFTER_429_MS
                    }
                    return@use null
                }
                if (!resp.isSuccessful) {
                    return@use null
                }
                resp.body?.string()?.takeIf { it.isNotBlank() }
            }
        }
    }

    fun searchLocalAndZones(
        query: String,
        serviceAreas: List<ServiceArea>
    ): List<GeocodedPlaceResult> {
        val q = query.trim().lowercase(Locale.ROOT)
        val zoneResults = serviceAreas
            .filter { it.isActive && it.serviceAvailable }
            .filter { area ->
                q.isBlank() ||
                    area.name.lowercase(Locale.ROOT).contains(q) ||
                    area.nameBn.lowercase(Locale.ROOT).contains(q) ||
                    area.district.lowercase(Locale.ROOT).contains(q) ||
                    area.block.lowercase(Locale.ROOT).contains(q) ||
                    area.pinCode.contains(q) ||
                    area.villagesCsv.lowercase(Locale.ROOT).contains(q)
            }
            .map { area ->
                GeocodedPlaceResult(
                    id = "zone_${area.id}",
                    label = area.name,
                    secondaryLabel = listOfNotNull(
                        area.block.takeIf { it.isNotBlank() },
                        area.district,
                        area.pinCode.takeIf { it.isNotBlank() }?.let { "PIN $it" }
                    ).joinToString(" • "),
                    lat = area.lat,
                    lng = area.lng,
                    district = area.district,
                    pinCode = area.pinCode,
                    source = "service_area",
                    category = area.zoneType
                )
            }

        val catalogResults = WestBengalCatalog.search(query).map { p ->
            GeocodedPlaceResult(
                id = "wb_${p.id}",
                label = p.nameEn,
                secondaryLabel = listOfNotNull(
                    p.nameBn.takeIf { it.isNotBlank() },
                    p.district,
                    p.pinCode.takeIf { it.isNotBlank() }?.let { "PIN $it" },
                    p.category.replace('_', ' ')
                ).joinToString(" • "),
                lat = p.lat,
                lng = p.lng,
                district = p.district,
                pinCode = p.pinCode,
                source = "local_catalog",
                category = p.category
            )
        }

        return mergeDistinct(zoneResults, catalogResults)
    }

    companion object {
        private const val TAG = "HybridGeocoding"
        private const val SEARCH_TTL_MS = 10 * 60 * 1000L
        private const val REVERSE_TTL_MS = 10 * 60 * 1000L
        private const val COOLDOWN_AFTER_429_MS = 30_000L
        private const val MAX_CACHE = 64

        fun parseNominatimSearchJson(rawJson: String): List<GeocodedPlaceResult> {
            val arr = JSONArray(rawJson)
            val list = ArrayList<GeocodedPlaceResult>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val lat = obj.optString("lat", "").toDoubleOrNull() ?: continue
                val lng = obj.optString("lon", "").toDoubleOrNull() ?: continue
                val addr = obj.optJSONObject("address")
                val district = addr?.let {
                    it.optString("state_district").ifBlank { it.optString("county") }
                }.orEmpty()
                val pin = addr?.optString("postcode").orEmpty()
                val name = obj.optString("name").ifBlank {
                    obj.optString("display_name").substringBefore(',').trim()
                }
                val display = obj.optString("display_name").ifBlank { name }
                list.add(
                    GeocodedPlaceResult(
                        id = "osm_${obj.optString("place_id", i.toString())}",
                        label = name.ifBlank { display },
                        secondaryLabel = display,
                        lat = lat,
                        lng = lng,
                        district = district,
                        pinCode = pin,
                        source = "nominatim",
                        category = obj.optString("type", "place")
                    )
                )
            }
            return list
        }

        fun parseNominatimReverseJson(rawJson: String, lat: Double, lng: Double): ReverseGeocodeResult {
            val obj = JSONObject(rawJson)
            val addr = obj.optJSONObject("address")
            val primary = listOfNotNull(
                addr?.optString("village")?.takeIf { it.isNotBlank() },
                addr?.optString("hamlet")?.takeIf { it.isNotBlank() },
                addr?.optString("town")?.takeIf { it.isNotBlank() },
                addr?.optString("suburb")?.takeIf { it.isNotBlank() },
                addr?.optString("road")?.takeIf { it.isNotBlank() },
                obj.optString("name").takeIf { it.isNotBlank() }
            ).distinct().take(2).joinToString(", ")

            val district = addr?.let {
                it.optString("state_district").ifBlank { it.optString("county") }
            }.orEmpty()
            val pin = addr?.optString("postcode").orEmpty()

            val fallbackDisplay = obj.optString("display_name")
                .split(',')
                .take(3)
                .joinToString(", ")
                .trim()

            val finalLabel = primary.ifBlank {
                fallbackDisplay.ifBlank {
                    String.format(Locale.US, "%.4f, %.4f", lat, lng)
                }
            }
            return ReverseGeocodeResult(
                label = finalLabel,
                district = district,
                pinCode = pin,
                source = "nominatim"
            )
        }

        private fun mergeDistinct(
            first: List<GeocodedPlaceResult>,
            second: List<GeocodedPlaceResult>
        ): List<GeocodedPlaceResult> {
            val out = LinkedHashMap<String, GeocodedPlaceResult>()
            (first + second).forEach { item ->
                val key = "${item.label.lowercase(Locale.ROOT)}@${String.format(Locale.US, "%.3f,%.3f", item.lat, item.lng)}"
                if (!out.containsKey(key)) {
                    out[key] = item
                }
            }
            return out.values.toList()
        }
    }
}
