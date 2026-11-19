package com.example.domain

import com.example.data.model.ServiceArea
import org.json.JSONArray
import kotlin.math.abs

data class LatLngPoint(
    val lat: Double,
    val lng: Double
)

data class CoverageEvaluation(
    val hasConfiguredZones: Boolean = false,
    val isPickupCovered: Boolean = true,
    val isDropoffCovered: Boolean = true,
    val matchingZoneNames: List<String> = emptyList(),
    val supportedVehicleCategories: Set<String> = setOf("toto", "auto", "bike", "cab", "outstation", "scheduled"),
    val serviceAvailable: Boolean = true,
    val advisoryMessage: String = "",
    val isCovered: Boolean = isPickupCovered && isDropoffCovered,
    val isRestrictedByZone: Boolean = false,
    val matchedZones: List<String> = matchingZoneNames
)

/**
 * Evaluates Owner-configured ServiceAreas (radius bounds, optional polygon vertices,
 * pickup/drop coverage flags, vehicle category support, and operational availability).
 */
object ServiceAreaCoverageEngine {

    fun parsePolygonJson(polygonJson: String): List<LatLngPoint> {
        if (polygonJson.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(polygonJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONArray(i) ?: continue
                    if (item.length() >= 2) {
                        // Supports GeoJSON [lng, lat] convention cleanly
                        val lng = item.optDouble(0, Double.NaN)
                        val lat = item.optDouble(1, Double.NaN)
                        if (!lat.isNaN() && !lng.isNaN()) {
                            add(LatLngPoint(lat = lat, lng = lng))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isPointInsidePolygon(point: LatLngPoint, polygon: List<LatLngPoint>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val xi = polygon[i].lng
            val yi = polygon[i].lat
            val xj = polygon[j].lng
            val yj = polygon[j].lat
            val intersect = ((yi > point.lat) != (yj > point.lat)) &&
                (point.lng < (xj - xi) * (point.lat - yi) / ((yj - yi).takeIf { abs(it) > 1e-9 } ?: 1e-9) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    fun containsPoint(area: ServiceArea, lat: Double, lng: Double): Boolean {
        val polygon = parsePolygonJson(area.boundaryPolygonJson)
        if (polygon.size >= 3) {
            return isPointInsidePolygon(LatLngPoint(lat, lng), polygon)
        }
        val effectiveRadius = area.radiusKm.takeIf { it > 0.0 } ?: 8.0
        return FareCalculator.haversineDistanceKm(area.lat, area.lng, lat, lng) <= effectiveRadius
    }

    fun evaluateRouteCoverage(
        pickupLat: Double,
        pickupLng: Double,
        destLat: Double,
        destLng: Double,
        vehicleCategory: String,
        serviceAreas: List<ServiceArea>
    ): CoverageEvaluation = evaluateTrip(
        pickupLat = pickupLat,
        pickupLng = pickupLng,
        destLat = destLat,
        destLng = destLng,
        vehicleCategory = vehicleCategory,
        serviceAreas = serviceAreas
    )

    fun evaluateTrip(
        pickupLat: Double,
        pickupLng: Double,
        destLat: Double,
        destLng: Double,
        vehicleCategory: String,
        serviceAreas: List<ServiceArea>
    ): CoverageEvaluation {
        if (serviceAreas.isEmpty()) {
            // No restrictive zones configured yet -> default open starting from Borai
            return CoverageEvaluation(
                hasConfiguredZones = false,
                isPickupCovered = true,
                isDropoffCovered = true,
                matchingZoneNames = emptyList(),
                supportedVehicleCategories = setOf("toto", "auto", "bike", "cab", "outstation", "scheduled"),
                serviceAvailable = true,
                advisoryMessage = "Default launch center: ${BoraiLaunchConfig.DEFAULT_DISPLAY_LABEL}",
                isCovered = true,
                isRestrictedByZone = false,
                matchedZones = emptyList()
            )
        }

        val activeZones = serviceAreas.filter { it.isActive && it.serviceAvailable }
        if (activeZones.isEmpty()) {
            val pausedMsg = "All configured service zones are currently paused by GoLocal Admin."
            return CoverageEvaluation(
                hasConfiguredZones = true,
                isPickupCovered = false,
                isDropoffCovered = false,
                matchingZoneNames = emptyList(),
                supportedVehicleCategories = emptySet(),
                serviceAvailable = false,
                advisoryMessage = pausedMsg,
                isCovered = false,
                isRestrictedByZone = true,
                matchedZones = emptyList()
            )
        }

        val pickupZonesContaining = activeZones.filter { containsPoint(it, pickupLat, pickupLng) }
        val dropZonesContaining = activeZones.filter { containsPoint(it, destLat, destLng) }
        val pickupMatches = pickupZonesContaining.filter { it.pickupEnabled }
        val dropMatches = dropZonesContaining.filter { it.dropoffEnabled }
        val allMatches = (pickupMatches + dropMatches).distinctBy { it.id.ifBlank { it.name } }

        val supportedCategories = if (allMatches.isEmpty()) {
            activeZones.flatMap { it.supportedVehicleCategories }.toSet()
        } else {
            allMatches.flatMap { it.supportedVehicleCategories }.toSet()
        }

        val categorySupported = supportedCategories.isEmpty() || vehicleCategory in supportedCategories
        val zoneDisabledPickup = pickupZonesContaining.isNotEmpty() && pickupMatches.isEmpty()
        val zoneDisabledDrop = dropZonesContaining.isNotEmpty() && dropMatches.isEmpty()
        val categoryRestricted = allMatches.isNotEmpty() && !categorySupported
        val isRestricted = zoneDisabledPickup || zoneDisabledDrop || categoryRestricted

        val advisory = when {
            zoneDisabledPickup ->
                "Pickup in ${pickupZonesContaining.first().name} is disabled by GoLocal Admin."
            zoneDisabledDrop ->
                "Drop-off in ${dropZonesContaining.first().name} is disabled by GoLocal Admin."
            categoryRestricted ->
                "${vehicleCategory.uppercase()} is not enabled for ${allMatches.first().name}. Supported: ${supportedCategories.joinToString { it.uppercase() }}."
            pickupMatches.isEmpty() && dropMatches.isEmpty() ->
                "Outside defined hub zones — dispatch availability may depend on nearby verified partners."
            pickupMatches.isEmpty() ->
                "Pickup point is outside active pickup coverage zones (${dropMatches.joinToString { it.name }} drop supported)."
            dropMatches.isEmpty() ->
                "Destination is outside active drop-off zones; check outstation availability."
            else ->
                "Service covered by ${allMatches.joinToString { it.name }}."
        }

        val zoneNames = allMatches.map { it.name }
        return CoverageEvaluation(
            hasConfiguredZones = true,
            isPickupCovered = pickupMatches.isNotEmpty(),
            isDropoffCovered = dropMatches.isNotEmpty(),
            matchingZoneNames = zoneNames,
            supportedVehicleCategories = supportedCategories,
            serviceAvailable = true,
            advisoryMessage = advisory,
            isCovered = pickupMatches.isNotEmpty() && dropMatches.isNotEmpty() && categorySupported,
            isRestrictedByZone = isRestricted,
            matchedZones = zoneNames
        )
    }
}
