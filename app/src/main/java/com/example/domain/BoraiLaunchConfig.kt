package com.example.domain

import com.example.data.model.GeoPointDto
import com.example.data.model.ServiceArea
import kotlin.math.abs

data class ServiceCoverageEvaluation(
    val isAllowed: Boolean,
    val pickupZone: ServiceArea? = null,
    val dropoffZone: ServiceArea? = null,
    val blockingReason: String = "",
    val summaryNote: String = ""
)

object BoraiLaunchConfig {
    const val DEFAULT_LAT = 21.8160
    const val DEFAULT_LNG = 87.3703
    const val DEFAULT_ZOOM = 13.2
    const val DEFAULT_PINCODE = "721457"
    const val DEFAULT_LOCALITY = "Borai"
    const val DEFAULT_BLOCK = "Mohanpur"
    const val DEFAULT_DISTRICT = "Paschim Medinipur"
    const val DEFAULT_STATE = "West Bengal"
    const val DEFAULT_COUNTRY = "India"
    const val DEFAULT_LABEL = "Borai Market & Stand (PIN 721457)"

    val defaultLaunchVillages: List<String> = listOf(
        "Borai",
        "Mohanpur",
        "Gholai",
        "Paikbar",
        "Agarara",
        "Jaysankhapur",
        "Nilda",
        "Shialsai",
        "Kumarda",
        "Tanua"
    )

    val defaultSupportedVehicleCategories: List<String> = listOf("toto", "auto", "bike", "cab")

    val defaultPlace = WestBengalPlace(
        id = "borai_721457",
        nameEn = DEFAULT_LABEL,
        nameBn = "বড়াই বাজার ও স্ট্যান্ড (৭২১৪৫৭)",
        district = DEFAULT_DISTRICT,
        block = DEFAULT_BLOCK,
        category = "village",
        lat = DEFAULT_LAT,
        lng = DEFAULT_LNG
    )

    fun defaultZoneTemplate(): ServiceArea = ServiceArea(
        id = "borai_721457_zone",
        name = "Borai & Mohanpur Launch Zone",
        nameBn = "বড়াই ও মোহনপুর লঞ্চ জোন",
        district = DEFAULT_DISTRICT,
        block = DEFAULT_BLOCK,
        pincode = DEFAULT_PINCODE,
        zoneType = "village",
        lat = DEFAULT_LAT,
        lng = DEFAULT_LNG,
        centerLat = DEFAULT_LAT,
        centerLng = DEFAULT_LNG,
        radiusKm = 12.0,
        polygonPoints = defaultPolygon(),
        villages = defaultLaunchVillages,
        pickupEnabled = true,
        dropoffEnabled = true,
        supportedVehicleCategories = defaultSupportedVehicleCategories,
        availabilityNote = "Default rural & town mobility launch zone around Borai (PIN 721457), Mohanpur.",
        isActive = true,
        parcelApproved = false,
        operationalHours = "05:00 - 22:30"
    )

    fun defaultPolygon(): List<GeoPointDto> = listOf(
        GeoPointDto(21.8720, 87.3100),
        GeoPointDto(21.8820, 87.4450),
        GeoPointDto(21.7550, 87.4500),
        GeoPointDto(21.7480, 87.3050)
    )

    /**
     * Checks whether (lat, lng) falls inside the Owner-configured ServiceArea boundary.
     * Supports polygon vertices (>= 3) or radius around (centerLat, centerLng) / (lat, lng).
     */
    fun isPointInsideZone(lat: Double, lng: Double, zone: ServiceArea): Boolean {
        if (!zone.isActive) return false
        if (zone.polygonPoints.size >= 3) {
            return isPointInPolygon(lat, lng, zone.polygonPoints)
        }
        val cLat = if (abs(zone.centerLat) > 0.001) zone.centerLat else zone.lat
        val cLng = if (abs(zone.centerLng) > 0.001) zone.centerLng else zone.lng
        if (abs(cLat) < 0.001 && abs(cLng) < 0.001) return false
        val radiusKm = zone.radiusKm.takeIf { it > 0.1 } ?: 8.0
        val dist = FareCalculator.haversineKm(lat, lng, cLat, cLng)
        return dist <= radiusKm
    }

    /**
     * Standard ray-casting algorithm for point-in-polygon checks.
     */
    fun isPointInPolygon(lat: Double, lng: Double, vertices: List<GeoPointDto>): Boolean {
        if (vertices.size < 3) return false
        var inside = false
        var j = vertices.lastIndex
        for (i in vertices.indices) {
            val xi = vertices[i].lat
            val yi = vertices[i].lng
            val xj = vertices[j].lat
            val yj = vertices[j].lng
            val intersects = ((yi > lng) != (yj > lng)) &&
                (lat < (xj - xi) * (lng - yi) / ((yj - yi).let { if (abs(it) < 1e-9) 1e-9 else it }) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Evaluates Owner-configured service boundaries, pickup/drop-off coverage,
     * and supported vehicle categories.
     *
     * Note: Borai is a default starting point, NOT a hardcoded permanent restriction.
     * If no active zone with configured boundaries exists yet, trips remain allowed
     * across West Bengal so existing features are never artificially blocked.
     */
    fun evaluateTripCoverage(
        pickupLat: Double,
        pickupLng: Double,
        dropLat: Double,
        dropLng: Double,
        vehicleCategory: String,
        serviceAreas: List<ServiceArea>
    ): ServiceCoverageEvaluation {
        val activeAreas = serviceAreas.filter { it.isActive }
        val configuredBoundaryZones = activeAreas.filter {
            it.polygonPoints.size >= 3 || it.radiusKm > 0.0
        }
        if (configuredBoundaryZones.isEmpty()) {
            return ServiceCoverageEvaluation(
                isAllowed = true,
                summaryNote = "Open coverage across configured West Bengal stands (Borai launch ready)."
            )
        }

        val pickupMatch = configuredBoundaryZones.firstOrNull { isPointInsideZone(pickupLat, pickupLng, it) }
        val dropMatch = configuredBoundaryZones.firstOrNull { isPointInsideZone(dropLat, dropLng, it) }

        if (pickupMatch != null && !pickupMatch.pickupEnabled) {
            return ServiceCoverageEvaluation(
                isAllowed = false,
                pickupZone = pickupMatch,
                dropoffZone = dropMatch,
                blockingReason = "Pickups are temporarily disabled in ${pickupMatch.name}.",
                summaryNote = pickupMatch.availabilityNote
            )
        }
        if (dropMatch != null && !dropMatch.dropoffEnabled) {
            return ServiceCoverageEvaluation(
                isAllowed = false,
                pickupZone = pickupMatch,
                dropoffZone = dropMatch,
                blockingReason = "Drop-offs are temporarily disabled in ${dropMatch.name}.",
                summaryNote = dropMatch.availabilityNote
            )
        }

        val zoneVehicles = pickupMatch?.supportedVehicleCategories?.takeIf { it.isNotEmpty() }
            ?: dropMatch?.supportedVehicleCategories?.takeIf { it.isNotEmpty() }
        if (zoneVehicles != null && vehicleCategory !in listOf("scheduled", "outstation", "parcel")) {
            val normalized = zoneVehicles.map { it.lowercase() }
            if (vehicleCategory.lowercase() !in normalized) {
                val allowedLabel = normalized.joinToString(", ") { it.uppercase() }
                return ServiceCoverageEvaluation(
                    isAllowed = false,
                    pickupZone = pickupMatch,
                    dropoffZone = dropMatch,
                    blockingReason = "${vehicleCategory.uppercase()} is not enabled in ${pickupMatch?.name ?: dropMatch?.name ?: "this zone"}. Available: $allowedLabel",
                    summaryNote = "Vehicle availability managed by Owner Admin"
                )
            }
        }

        val summary = when {
            pickupMatch != null && dropMatch != null ->
                "Inside configured zones: ${pickupMatch.name} -> ${dropMatch.name}"
            pickupMatch != null ->
                "Pickup inside ${pickupMatch.name} (${pickupMatch.pincode.ifBlank { pickupMatch.district }})"
            dropMatch != null ->
                "Drop-off inside ${dropMatch.name}"
            else ->
                "Outside active local zones (inter-block/outstation trip)."
        }
        return ServiceCoverageEvaluation(
            isAllowed = true,
            pickupZone = pickupMatch,
            dropoffZone = dropMatch,
            summaryNote = summary
        )
    }
}
