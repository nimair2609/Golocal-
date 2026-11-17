package com.example.data.map

import android.content.Context
import com.example.R

data class MapProviderEndpoints(
    val osmTileUrlTemplate: String = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    val osrmBaseUrl: String = "https://router.project-osrm.org",
    val geocodingBaseUrl: String = "https://nominatim.openstreetmap.org",
    val tileAttribution: String = "© OpenStreetMap contributors",
    val userAgent: String = "GoLocal-WestBengal-Mobility/1.0"
)

/**
 * Configurable OpenStreetMap, OSRM, and Geocoding provider configuration.
 *
 * Provider & Cost Safety:
 * - Reads endpoint templates from string resources so self-hosted or commercial
 *   tile/OSRM/geocoding servers can be swapped without modifying Kotlin code.
 * - Never sends unlimited autocomplete traffic to public Nominatim or assumes
 *   public OSRM demo / OSM tile servers provide production SLAs.
 */
object MapProviderConfig {
    fun loadEndpoints(context: Context?): MapProviderEndpoints {
        if (context == null) return MapProviderEndpoints()
        val res = context.applicationContext.resources
        val tiles = runCatching { res.getString(R.string.osm_tile_url_template) }
            .getOrDefault("https://tile.openstreetmap.org/{z}/{x}/{y}.png")
            .ifBlank { "https://tile.openstreetmap.org/{z}/{x}/{y}.png" }
        val osrm = runCatching { res.getString(R.string.osrm_base_url) }
            .getOrDefault("https://router.project-osrm.org")
            .trimEnd('/')
            .ifBlank { "https://router.project-osrm.org" }
        val geo = runCatching { res.getString(R.string.geocoding_base_url) }
            .getOrDefault("https://nominatim.openstreetmap.org")
            .trimEnd('/')
            .ifBlank { "https://nominatim.openstreetmap.org" }
        return MapProviderEndpoints(
            osmTileUrlTemplate = tiles,
            osrmBaseUrl = osrm,
            geocodingBaseUrl = geo
        )
    }

    /**
     * Builds a MapLibre Style JSON using configurable raster OpenStreetMap tiles.
     * When [lowNetworkLite] is true, tile sources are omitted to conserve rural mobile data
     * while keeping GeoJSON boundaries, routes, and markers crisp.
     */
    fun buildMapLibreStyleJson(
        tileUrlTemplate: String = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        lowNetworkLite: Boolean = false
    ): String {
        val safeTemplate = tileUrlTemplate.replace("\"", "\\\"")
        if (lowNetworkLite) {
            return """
                {
                  "version": 8,
                  "name": "GoLocal Low-Network Vector-Lite",
                  "sources": {},
                  "layers": [
                    {
                      "id": "background",
                      "type": "background",
                      "paint": { "background-color": "#EDF4F2" }
                    }
                  ]
                }
            """.trimIndent()
        }
        return """
            {
              "version": 8,
              "name": "GoLocal OpenStreetMap Raster",
              "sources": {
                "osm-tiles": {
                  "type": "raster",
                  "tiles": ["$safeTemplate"],
                  "tileSize": 256,
                  "attribution": "© OpenStreetMap contributors",
                  "maxzoom": 19
                }
              },
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": { "background-color": "#EBF2F0" }
                },
                {
                  "id": "osm-raster-layer",
                  "type": "raster",
                  "source": "osm-tiles",
                  "minzoom": 0,
                  "maxzoom": 19
                }
              ]
            }
        """.trimIndent()
    }
}
