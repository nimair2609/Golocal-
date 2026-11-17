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
    val timestampMs: Long = System.currentTimeMillis()
)

class GpsBroadcastThrottle(
    private val minIntervalMs: Long = 3_000L,
    private val minDistanceMeters: Double = 15.0,
    private val maxAcceptedAccuracyMeters: Float = 100f
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
        if (elapsed >= minIntervalMs && movedMeters >= minDistanceMeters) {
            lastSample = candidate
            return true
        }
        return false
    }

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
    }
}
