package com.example.data.map

import com.example.BuildConfig

object MapProviderConfig {
    const val DEFAULT_OSM_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    const val DEFAULT_OSRM_BASE_URL = "https://router.project-osrm.org"
    const val DEFAULT_GEOCODING_BASE_URL = "https://nominatim.openstreetmap.org"
    const val DEFAULT_RTDB_URL = "https://gen-lang-client-0818250755-default-rtdb.firebaseio.com"
    const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"

    val osmTileUrlTemplate: String
        get() = BuildConfig.OSM_TILE_URL_TEMPLATE.ifBlank { DEFAULT_OSM_TILE_URL }

    val osrmBaseUrl: String
        get() = BuildConfig.OSRM_BASE_URL.ifBlank { DEFAULT_OSRM_BASE_URL }

    val geocodingBaseUrl: String
        get() = BuildConfig.GEOCODING_BASE_URL.ifBlank { DEFAULT_GEOCODING_BASE_URL }

    val firebaseRtdbUrl: String
        get() = BuildConfig.FIREBASE_RTDB_URL.ifBlank { DEFAULT_RTDB_URL }

    /**
     * Builds a MapLibre GL Style v8 JSON document referencing OpenStreetMap raster tiles.
     * Supports overriding the tile server template via BuildConfig.OSM_TILE_URL_TEMPLATE.
     */
    fun buildOsmStyleJson(
        tileUrlTemplate: String = osmTileUrlTemplate,
        attribution: String = OSM_ATTRIBUTION
    ): String {
        val safeUrl = tileUrlTemplate
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val safeAttr = attribution
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        return """
            {
              "version": 8,
              "name": "GoLocal OpenStreetMap",
              "sources": {
                "osm-raster": {
                  "type": "raster",
                  "tiles": ["$safeUrl"],
                  "tileSize": 256,
                  "maxzoom": 19,
                  "attribution": "$safeAttr"
                }
              },
              "layers": [
                {
                  "id": "osm-background",
                  "type": "background",
                  "paint": {
                    "background-color": "#EEF4FA"
                  }
                },
                {
                  "id": "osm-raster-layer",
                  "type": "raster",
                  "source": "osm-raster",
                  "minzoom": 0,
                  "maxzoom": 20
                }
              ]
            }
        """.trimIndent()
    }
}
