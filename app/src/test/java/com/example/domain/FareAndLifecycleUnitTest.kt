package com.example.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FareAndLifecycleUnitTest {

    @Test
    fun `calculateFareQuote computes valid fare range and GST`() {
        val totoConfig = FareCalculator.defaultFareConfigs().first { it.vehicleCategory == "toto" }
        val quote = FareCalculator.calculateFareQuote(distanceKm = 5.5, config = totoConfig)
        assertTrue(quote.estimatedFareInr >= totoConfig.minimumFareInr)
        assertTrue(quote.minPermittedFareInr <= quote.estimatedFareInr)
        assertTrue(quote.maxPermittedFareInr >= quote.estimatedFareInr)
    }

    @Test
    fun `calculateFareQuote applies round trip distance factor`() {
        val cabConfig = FareCalculator.defaultFareConfigs().first { it.vehicleCategory == "cab" }
        val oneWay = FareCalculator.calculateFareQuote(distanceKm = 10.0, config = cabConfig, isRoundTrip = false)
        val roundTrip = FareCalculator.calculateFareQuote(distanceKm = 10.0, config = cabConfig, isRoundTrip = true)
        assertTrue(roundTrip.estimatedFareInr > oneWay.estimatedFareInr)
        assertTrue(roundTrip.distanceKm > oneWay.distanceKm)
    }

    @Test
    fun `calculateSettlement separates GST, commission, and driver net earnings`() {
        val breakdown = FareCalculator.calculateSettlement(
            grossFareInr = 210.0,
            gstPercent = 5.0,
            commissionPercent = 10.0
        )
        assertEquals(10.0, breakdown.gstInr, 0.01)
        assertEquals(20.0, breakdown.platformCommissionInr, 0.01)
        assertEquals(180.0, breakdown.netDriverEarningsInr, 0.01)
    }

    @Test
    fun `trip lifecycle transitions enforce strict ordering and terminal lock`() {
        assertTrue(FareCalculator.isValidTransition("searching", "assigned"))
        assertTrue(FareCalculator.isValidTransition("assigned", "arriving"))
        assertTrue(FareCalculator.isValidTransition("arriving", "arrived"))
        assertTrue(FareCalculator.isValidTransition("arrived", "in_progress"))
        assertTrue(FareCalculator.isValidTransition("in_progress", "completed"))

        // Invalid shortcuts or post-terminal transitions
        assertFalse(FareCalculator.isValidTransition("searching", "in_progress"))
        assertFalse(FareCalculator.isValidTransition("completed", "cancelled"))
        assertFalse(FareCalculator.isValidTransition("cancelled", "assigned"))
    }

    @Test
    fun `west bengal catalog searches Bengali and English place names`() {
        val bandelMatches = WestBengalCatalog.search("Bandel")
        assertTrue(bandelMatches.any { it.id == "bandel_jn" })

        val bengaliMatches = WestBengalCatalog.search("বোলপুর")
        assertTrue(bengaliMatches.any { it.id == "bolpur_stn" })

        val busStandMatches = WestBengalCatalog.search("Esplanade", categoryFilter = "bus_stand")
        assertTrue(busStandMatches.any { it.id == "esplanade_bus" })

        val airportMatches = WestBengalCatalog.search("Bagdogra", categoryFilter = "airport")
        assertTrue(airportMatches.any { it.id == "bagdogra_airport" })
    }

    @Test
    fun `localized strings expose updated GoLocal tagline`() {
        assertEquals("Your Local. Our Priority.", StringsL10n.get(com.example.data.model.AppLanguage.EN).brandTagline)
    }
}
