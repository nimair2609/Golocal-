package com.example.data.map

import com.example.data.model.ServiceArea
import com.example.domain.BoraiLaunchConfig
import com.example.domain.FareCalculator
import com.example.domain.WestBengalCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

data class GeocodedPlace(
    val id: String,
    val displayName: String,
    val secondaryText: String,
    val district: String,
    val lat: Double,
    val lng: Double,
    val source: String // "service_zone", "local_catalog", "osm_nominatim"
)

/**
 * Configurable Geocoding & Place-Search Adapter.
 *
 * Cost & Nominatim Usage Policy Safety:
 * - Instant search ALWAYS checks Owner-configured Firestore [ServiceArea] documents +
 *   the local West Bengal & Borai (721457) village/landmark catalog first.
 * - NEVER sends keystroke-by-keystroke autocomplete queries to public Nominatim.
 * - Remote geocoding is only invoked on explicit user search submission ([searchPlaces]
 *   with `allowRemoteLookup = true`), throttled (>= 1100 ms), tagged with a compliant
 *   User-Agent, bounded to West Bengal, and cached for 15 minutes.
 */
class GeocodingSearchAdapter(
    private val endpoints: MapProviderEndpoints = MapProviderEndpoints(),
    private val fetcher: RoutingHttpFetcher = OkHttpRoutingFetcher(),
    private val cacheTtlMs: Long = 15 * 60 * 1000L,
    private val minIntervalMs: Long = 1100L,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private data class CachedSearch(val results: List<GeocodedPlace>, val storedAtMs: Long)

    private val mutex = Mutex()
    private val searchCache = object : LinkedHashMap<String, CachedSearch>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSearch>?): Boolean = size > 40
    }
    private val reverseCache = object : LinkedHashMap<String, Pair<String, Long>>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Long>>?): Boolean = size > 40
    }
    private var lastRemoteEpochMs: Long = 0L

    fun searchLocalOnly(
        query: String,
        serviceAreas: List<ServiceArea> = emptyList()
    ): List<GeocodedPlace> {
        val q = query.trim().lowercase()
        val zoneResults = serviceAreas
            .filter { it.isActive }
            .filter { zone ->
                q.isBlank() ||
                    zone.name.lowercase().contains(q) ||
                    zone.block.lowercase().contains(q) ||
                    zone.district.lowercase().contains(q) ||
                    zone.pincode.contains(q) ||
                    zone.villages.any { v -> v.lowercase().contains(q) }
            }
            .map { zone ->
                GeocodedPlace(
                    id = "zone_${zone.id}",
                    displayName = zone.name,
                    secondaryText = listOf(zone.block, zone.district, zone.pincode).filter { it.isNotBlank() }.joinToString(", "),
                    district = zone.district,
                    lat = zone.centerLat.takeIf { it != 0.0 } ?: zone.lat,
                    lng = zone.centerLng.takeIf { it != 0.0 } ?: zone.lng,
                    source = "service_zone"
                )
            }

        val catalogResults = WestBengalCatalog.search(query).map { place ->
            GeocodedPlace(
                id = place.id,
                displayName = place.nameEn,
                secondaryText = "${place.block}, ${place.district} (${place.category.replace('_', ' ')})",
                district = place.district,
                lat = place.lat,
                lng = place.lng,
                source = "local_catalog"
            )
        }

        // Prioritize results near Borai (721457) when query is blank or matches Mohanpur/721457
        return (zoneResults + catalogResults)
            .distinctBy { "${it.displayName.lowercase()}_${String.format(Locale.US, "%.3f", it.lat)}" }
    }

    suspend fun searchPlaces(
        query: String,
        serviceAreas: List<ServiceArea> = emptyList(),
        allowRemoteLookup: Boolean = true
    ): List<GeocodedPlace> {
        val trimmed = query.trim()
        val local = searchLocalOnly(trimmed, serviceAreas)
        if (!allowRemoteLookup || trimmed.length < 3) {
            return local
        }

        val key = trimmed.lowercase()
        val now = clockMs()
        mutex.withLock {
            val cached = searchCache[key]
            if (cached != null && (now - cached.storedAtMs) <= cacheTtlMs) {
                return mergeLocalAndRemote(local, cached.results)
            }
            if ((now - lastRemoteEpochMs) < minIntervalMs) {
                return local
            }
            lastRemoteEpochMs = now
        }

        val remoteResults = withContext(Dispatchers.IO) {
            runCatching {
                val encoded = URLEncoder.encode("$trimmed, West Bengal, India", "UTF-8")
                // Viewbox focused around West Bengal (covers Borai 721457 through North Bengal)
                val url = "${endpoints.geocodingBaseUrl.trimEnd('/')}/search?q=$encoded&format=jsonv2&addressdetails=1&limit=6&countrycodes=in"
                val raw = fetcher.get(url, endpoints.userAgent)
                parseNominatimSearchJson(raw)
            }.getOrDefault(emptyList())
        }

        if (remoteResults.isNotEmpty()) {
            mutex.withLock {
                searchCache[key] = CachedSearch(remoteResults, clockMs())
            }
        }
        return mergeLocalAndRemote(local, remoteResults)
    }

    fun parseNominatimSearchJson(rawJson: String): List<GeocodedPlace> {
        val arr = JSONArray(rawJson)
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val lat = item.optString("lat", "").toDoubleOrNull() ?: continue
                val lng = item.optString("lon", "").toDoubleOrNull() ?: continue
                val fullName = item.optString("display_name", "")
                val name = item.optString("name", "").ifBlank {
                    fullName.substringBefore(",").trim().ifBlank { "Selected Location" }
                }
                val address = item.optJSONObject("address")
                val district = address?.optString("state_district")?.ifBlank { null }
                    ?: address?.optString("county")?.ifBlank { null }
                    ?: BoraiLaunchConfig.DEFAULT_DISTRICT
                val villageOrTown = address?.optString("village")?.ifBlank { null }
                    ?: address?.optString("town")?.ifBlank { null }
                    ?: address?.optString("suburb")?.ifBlank { null }
                    ?: ""
                val postcode = address?.optString("postcode", "").orEmpty()
                val secondary = listOf(villageOrTown, district, postcode)
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                    .ifBlank { fullName.take(80) }
                add(
                    GeocodedPlace(
                        id = "osm_${item.optString("place_id", "$lat,$lng")}",
                        displayName = name,
                        secondaryText = secondary,
                        district = district,
                        lat = lat,
                        lng = lng,
                        source = "osm_nominatim"
                    )
                )
            }
        }
    }

    /**
     * Resolves a human-readable label for map-picked coordinates.
     * Prefers nearest configured zone / local West Bengal landmark within 1.5 km
     * before querying remote reverse geocoding (respecting throttle + cache).
     */
    suspend fun reverseGeocode(
        lat: Double,
        lng: Double,
        serviceAreas: List<ServiceArea> = emptyList(),
        allowRemoteLookup: Boolean = false
    ): String {
        val nearestZone = serviceAreas.filter { it.isActive }.minByOrNull {
            val zLat = if (it.centerLat != 0.0) it.centerLat else it.lat
            val zLng = if (it.centerLng != 0.0) it.centerLng else it.lng
            FareCalculator.haversineKm(lat, lng, zLat, zLng)
        }
        if (nearestZone != null) {
            val zLat = if (nearestZone.centerLat != 0.0) nearestZone.centerLat else nearestZone.lat
            val zLng = if (nearestZone.centerLng != 0.0) nearestZone.centerLng else nearestZone.lng
            if (FareCalculator.haversineKm(lat, lng, zLat, zLng) <= 1.2) {
                return "${nearestZone.name} (Map Pin)"
            }
        }

        val nearestCatalog = WestBengalCatalog.places.minByOrNull {
            FareCalculator.haversineKm(lat, lng, it.lat, it.lng)
        }
        if (nearestCatalog != null && FareCalculator.haversineKm(lat, lng, nearestCatalog.lat, nearestCatalog.lng) <= 1.5) {
            return "Near ${nearestCatalog.nameEn}"
        }

        val coordFallback = String.format(Locale.US, "Pinned Location (%.4f, %.4f)", lat, lng)
        if (!allowRemoteLookup) return coordFallback

        val cacheKey = String.format(Locale.US, "%.4f,%.4f", lat, lng)
        val now = clockMs()
        mutex.withLock {
            val cached = reverseCache[cacheKey]
            if (cached != null && (now - cached.second) <= cacheTtlMs) {
                return cached.first
            }
            if ((now - lastRemoteEpochMs) < minIntervalMs) {
                return coordFallback
            }
            lastRemoteEpochMs = now
        }

        val remoteLabel = withContext(Dispatchers.IO) {
            runCatching {
                val url = String.format(
                    Locale.US,
                    "%s/reverse?lat=%.6f&lon=%.6f&format=jsonv2",
                    endpoints.geocodingBaseUrl.trimEnd('/'),
                    lat,
                    lng
                )
                val obj = JSONObject(fetcher.get(url, endpoints.userAgent))
                obj.optString("name", "").ifBlank {
                    obj.optString("display_name", "").split(",").take(2).joinToString(", ").trim()
                }.ifBlank { coordFallback }
            }.getOrDefault(coordFallback)
        }

        mutex.withLock {
            reverseCache[cacheKey] = remoteLabel to clockMs()
        }
        return remoteLabel
    }

    private fun mergeLocalAndRemote(
        local: List<GeocodedPlace>,
        remote: List<GeocodedPlace>
    ): List<GeocodedPlace> {
        return (local + remote)
            .distinctBy { "${it.displayName.lowercase()}_${String.format(Locale.US, "%.3f", it.lat)}" }
    }
}
