package com.example.domain

import com.example.data.model.Booking
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class SimulatedTestDriver(
    val id: String,
    val displayName: String,
    val vehicleCategory: String,
    val vehicleModel: String,
    val vehicleLabel: String,
    val plateNumber: String,
    val phoneLabel: String,
    val rating: Double = 4.9
) {
    val testTagLabel: String
        get() = "TEST DRIVER • $displayName"
}

object TestDriverCatalog {
    val presetDrivers: List<SimulatedTestDriver> = listOf(
        SimulatedTestDriver(
            id = "test_driver_toto_01",
            displayName = "Test Driver – Toto 01",
            vehicleCategory = "toto",
            vehicleModel = "E-Rickshaw Deluxe",
            vehicleLabel = "TEST VEHICLE • WB-TEST-0101",
            plateNumber = "WB-TEST-0101",
            phoneLabel = "Simulated Test Unit (No Real Call)",
            rating = 4.9
        ),
        SimulatedTestDriver(
            id = "test_driver_auto_02",
            displayName = "Test Driver – Auto 02",
            vehicleCategory = "auto",
            vehicleModel = "Bajaj RE Compact",
            vehicleLabel = "TEST VEHICLE • WB-TEST-0202",
            plateNumber = "WB-TEST-0202",
            phoneLabel = "Simulated Test Unit (No Real Call)",
            rating = 4.8
        ),
        SimulatedTestDriver(
            id = "test_driver_bike_03",
            displayName = "Test Driver – Bike 03",
            vehicleCategory = "bike",
            vehicleModel = "Commuter 125",
            vehicleLabel = "TEST VEHICLE • WB-TEST-0303",
            plateNumber = "WB-TEST-0303",
            phoneLabel = "Simulated Test Unit (No Real Call)",
            rating = 4.9
        ),
        SimulatedTestDriver(
            id = "test_driver_cab_04",
            displayName = "Test Driver – Cab 04",
            vehicleCategory = "cab",
            vehicleModel = "AC Hatchback",
            vehicleLabel = "TEST VEHICLE • WB-TEST-0404",
            plateNumber = "WB-TEST-0404",
            phoneLabel = "Simulated Test Unit (No Real Call)",
            rating = 5.0
        )
    )

    fun defaultForCategory(category: String): SimulatedTestDriver =
        presetDrivers.firstOrNull { it.vehicleCategory == category } ?: presetDrivers.first()

    fun findById(driverId: String): SimulatedTestDriver? =
        presetDrivers.firstOrNull { it.id == driverId }
}

object TestRideOtpUtil {
    private val secureRandom = SecureRandom()

    fun generateSixDigitOtp(): String {
        val code = 100000 + secureRandom.nextInt(900000)
        return code.toString()
    }

    fun isValidOtpFormat(otp: String): Boolean {
        val trimmed = otp.trim()
        return trimmed.length == 6 && trimmed.all { it in '0'..'9' }
    }

    fun sha256Hex(bookingId: String, otp: String): String {
        val normalized = "${bookingId.trim()}:${otp.trim()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    fun computeExpiresAtMs(createdAtMs: Long, validitySeconds: Long): Long {
        val safeSeconds = validitySeconds.coerceIn(60L, 1800L)
        return createdAtMs + (safeSeconds * 1000L)
    }

    fun isExpired(expiresAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (expiresAtMs <= 0L) return false
        return nowMs > expiresAtMs
    }

    fun remainingSeconds(expiresAtMs: Long, nowMs: Long = System.currentTimeMillis()): Long {
        if (expiresAtMs <= 0L) return 0L
        return ((expiresAtMs - nowMs) / 1000L).coerceAtLeast(0L)
    }
}

data class SimulationWaypoint(
    val lat: Double,
    val lng: Double
)

object TestRideRouteSimulator {

    fun interpolateWaypoints(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        steps: Int = 16
    ): List<SimulationWaypoint> {
        val safeSteps = steps.coerceIn(2, 60)
        return (0..safeSteps).map { idx ->
            val t = idx.toDouble() / safeSteps.toDouble()
            SimulationWaypoint(
                lat = startLat + (endLat - startLat) * t,
                lng = startLng + (endLng - startLng) * t
            )
        }
    }

    fun resamplePolyline(
        points: List<Pair<Double, Double>>,
        targetSteps: Int = 18
    ): List<SimulationWaypoint> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return listOf(SimulationWaypoint(points[0].first, points[0].second))
        val safeSteps = targetSteps.coerceIn(2, 60)
        if (points.size <= safeSteps + 1) {
            return points.map { SimulationWaypoint(it.first, it.second) }
        }
        val sampled = mutableListOf<SimulationWaypoint>()
        for (i in 0 until safeSteps) {
            val idx = ((i.toDouble() / safeSteps.toDouble()) * (points.size - 1)).roundToInt()
                .coerceIn(0, points.lastIndex)
            val pt = points[idx]
            sampled += SimulationWaypoint(pt.first, pt.second)
        }
        val last = points.last()
        val tail = SimulationWaypoint(last.first, last.second)
        if (sampled.lastOrNull() != tail) sampled += tail
        return sampled
    }

    fun stepDelayMs(
        totalDistanceKm: Double,
        speedKmph: Double,
        steps: Int
    ): Long {
        val safeSpeed = speedKmph.coerceIn(5.0, 120.0)
        val safeSteps = max(1, steps)
        val realSeconds = (totalDistanceKm.coerceAtLeast(0.4) / safeSpeed) * 3600.0
        // Accelerated proportionally so higher configured speedKmph drives noticeably faster simulation updates
        val scaledStepMs = ((realSeconds * 1000.0) / (safeSteps * 18.0)).roundToInt().toLong()
        return scaledStepMs.coerceIn(300L, 1600L)
    }

    fun initialApproachPoint(pickupLat: Double, pickupLng: Double): SimulationWaypoint {
        // Place simulated driver ~450m away from pickup so movement toward customer is clearly visible on map
        return SimulationWaypoint(
            lat = pickupLat + 0.0032,
            lng = pickupLng + 0.0028
        )
    }

    fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}

object TestRideStateMachine {
    private val allowedTransitions = mapOf(
        "searching" to setOf("assigned", "cancelled"),
        "assigned" to setOf("arriving", "arrived", "cancelled", "searching"),
        "arriving" to setOf("arrived", "cancelled", "searching"),
        "arrived" to setOf("in_progress", "cancelled", "searching"),
        "in_progress" to setOf("completed", "cancelled"),
        "completed" to emptySet(),
        "cancelled" to setOf("searching")
    )

    fun isValidTransition(fromStatus: String, toStatus: String): Boolean {
        return allowedTransitions[fromStatus]?.contains(toStatus) == true
    }

    fun isActiveStatus(status: String): Boolean =
        status in setOf("searching", "assigned", "arriving", "arrived", "in_progress")
}

object TestRideSeparation {
    fun isTestBooking(booking: Booking): Boolean = booking.isTestRide || booking.testMode

    fun filterProductionBookings(bookings: List<Booking>): List<Booking> =
        bookings.filterNot { isTestBooking(it) }

    fun filterTestBookings(bookings: List<Booking>): List<Booking> =
        bookings.filter { isTestBooking(it) }

    fun matchesAdminTestFilter(booking: Booking, filter: String): Boolean {
        if (!isTestBooking(booking)) return false
        return when (filter) {
            "Pending" -> booking.status == "searching"
            "Assigned" -> booking.status == "assigned"
            "Ongoing" -> booking.status in setOf("arriving", "arrived", "in_progress")
            "Completed" -> booking.status == "completed"
            "Cancelled" -> booking.status == "cancelled"
            else -> true // "All Test"
        }
    }
}
