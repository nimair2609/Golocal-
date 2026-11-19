package com.example.map

import com.example.data.map.HybridGeocodingRepository
import com.example.data.model.ServiceArea
import com.example.domain.BoraiLaunchConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeocodingAdapterTest {

    @Test
    fun `parses Nominatim forward search JSON with West Bengal district and postcode`() {
        val json = """
            [
              {
                "place_id": "991234",
                "lat": "21.8160",
                "lon": "87.3703",
                "name": "Borai",
                "display_name": "Borai, Mohanpur, Paschim Medinipur, West Bengal, 721457, India",
                "type": "village",
                "address": {
                  "village": "Borai",
                  "state_district": "Paschim Medinipur",
                  "postcode": "721457"
                }
              }
            ]
        """.trimIndent()

        val parsed = HybridGeocodingRepository.parseNominatimSearchJson(json)
        assertEquals(1, parsed.size)
        val first = parsed.first()
        assertEquals("osm_991234", first.id)
        assertEquals("Borai", first.label)
        assertEquals("Paschim Medinipur", first.district)
        assertEquals("721457", first.pinCode)
        assertEquals("nominatim", first.source)
        assertEquals(21.8160, first.lat, 0.0001)
        assertEquals(87.3703, first.lng, 0.0001)
    }

    @Test
    fun `parses Nominatim reverse geocode JSON cleanly`() {
        val json = """
            {
              "name": "Begunia More",
              "display_name": "Begunia, Mohanpur, Paschim Medinipur, West Bengal, India",
              "address": {
                "village": "Begunia",
                "road": "Contai-Egra Road",
                "county": "Paschim Medinipur",
                "postcode": "721436"
              }
            }
        """.trimIndent()

        val result = HybridGeocodingRepository.parseNominatimReverseJson(json, 21.8295, 87.3924)
        assertTrue(result.label.contains("Begunia"))
        assertEquals("Paschim Medinipur", result.district)
        assertEquals("721436", result.pinCode)
        assertEquals("nominatim", result.source)
    }

    @Test
    fun `local catalog and owner service area search finds Borai 721457 and custom villages`() {
        val repo = HybridGeocodingRepository()
        val customAreas = listOf(
            ServiceArea(
                id = "area_borai",
                name = "Borai & Sautia Gram Hub",
                district = "Paschim Medinipur",
                block = "Mohanpur",
                pinCode = "721457",
                lat = BoraiLaunchConfig.DEFAULT_LAT,
                lng = BoraiLaunchConfig.DEFAULT_LNG,
                villagesCsv = "Borai, Sautia, Kharika",
                isActive = true,
                serviceAvailable = true
            )
        )

        val byVillage = repo.searchLocalAndZones("Kharika", customAreas)
        assertTrue(byVillage.any { it.source == "service_area" && it.label == "Borai & Sautia Gram Hub" })

        val byPin = repo.searchLocalAndZones("721457", customAreas)
        assertTrue(byPin.any { it.pinCode == "721457" })
        assertTrue(byPin.any { it.source == "local_catalog" })
    }
}
