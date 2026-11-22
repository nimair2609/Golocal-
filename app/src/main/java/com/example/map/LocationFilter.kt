package com.example.map

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LocationSample(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float = 15f,
    val timestampMs: Long = System.currentTimeMillis(),
    val speedMps: Float = 0f,
    val headingDeg: Float = 0f
)

class GpsBroadcastThrottle(
    private val minIntervalMs: Long = 3_000L,
    private val minDistanceMeters: Double = 15.0,
    private val maxAcceptedAccuracyMeters: Float = 100f,
    private val maxHeartbeatIntervalMs: Long = 20_000L
) {
    private var lastSample: LocationSample? = null

    fun acceptIfQualified(candidate: LocationSample, force: Boolean = false): Boolean {
        if (!isValidCoordinate(candidate.lat, candidate.lng)) return false
        if (candidate.accuracyMeters.isNaN() || candidate.accuracyMeters <= 0f || candidate.accuracyMeters > maxAcceptedAccuracyMeters) {
            return false
        }
        val prev = lastSample ?: run {
            lastSample = candidate
            return true
        }
        if (candidate.timestampMs <= prev.timestampMs && !force) {
            return false
        }
        if (force) {
            lastSample = candidate
            return true
        }
        val elapsed = candidate.timestampMs - prev.timestampMs
        val movedMeters = haversineMeters(prev.lat, prev.lng, candidate.lat, candidate.lng)
        if ((elapsed >= minIntervalMs && movedMeters >= minDistanceMeters) || elapsed >= maxHeartbeatIntervalMs) {
            lastSample = candidate
            return true
        }
        return false
    }

    fun lastAccepted(): LocationSample? = lastSample

    fun reset() {
        lastSample = null
    }

    companion object {
        fun isValidCoordinate(lat: Double, lng: Double): Boolean {
            if (lat.isNaN() || lng.isNaN()) return false
            if (lat == 0.0 && lng == 0.0) return false
            return lat in -85.0..85.0 && lng in -180.0..180.0
        }

        fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val earthRadiusMeters = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return earthRadiusMeters * c
        }

        fun bearingDegrees(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Float {
            if (!isValidCoordinate(fromLat, fromLng) || !isValidCoordinate(toLat, toLng)) return 0f
            if (fromLat == toLat && fromLng == toLng) return 0f
            val phi1 = Math.toRadians(fromLat)
            val phi2 = Math.toRadians(toLat)
            val dLambda = Math.toRadians(toLng - fromLng)
            val y = sin(dLambda) * cos(phi2)
            val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
            val raw = Math.toDegrees(atan2(y, x))
            return (((raw % 360.0) + 360.0) % 360.0).toFloat()
        }

        fun distanceFromRouteMeters(lat: Double, lng: Double, route: List<GeoPoint>): Double {
            if (!isValidCoordinate(lat, lng) || route.isEmpty()) return Double.MAX_VALUE
            if (route.size == 1) return haversineMeters(lat, lng, route.first().lat, route.first().lng)
            var minMeters = Double.MAX_VALUE
            for (i in 0 until route.lastIndex) {
                val dist = distancePointToSegmentMeters(lat, lng, route[i], route[i + 1])
                if (dist < minMeters) minMeters = dist
            }
            return minMeters
        }

        fun distancePointToSegmentMeters(
            lat: Double,
            lng: Double,
            a: GeoPoint,
            b: GeoPoint
        ): Double {
            val refLat = Math.toRadians(lat)
            val metersPerDegLat = 111_132.0
            val metersPerDegLng = 111_320.0 * cos(refLat).coerceAtLeast(0.1)
            val ax = (a.lng - lng) * metersPerDegLng
            val ay = (a.lat - lat) * metersPerDegLat
            val bx = (b.lng - lng) * metersPerDegLng
            val by = (b.lat - lat) * metersPerDegLat
            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            if (lenSq <= 1e-6) return sqrt(ax * ax + ay * ay)
            val t = (-(ax * dx + ay * dy) / lenSq).coerceIn(0.0, 1.0)
            val projX = ax + t * dx
            val projY = ay + t * dy
            return sqrt(projX * projX + projY * projY)
        }
    }
}
