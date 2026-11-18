package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.model.Booking
import com.example.data.model.TripLocation
import com.example.map.GpsBroadcastThrottle
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Date

data class RealtimeLocationPayload(
    val driverId: String = "",
    val bookingId: String = "",
    val customerId: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val accuracyMeters: Double = 15.0,
    val speedMps: Double = 0.0,
    val headingDeg: Double = 0.0,
    val tripStatus: String = "",
    val trackingStatus: String = "active", // active, paused_gps_lost, stopped
    val isTestRide: Boolean = false,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

object RealtimeLocationValidator {
    private const val STALE_THRESHOLD_MS = 120_000L

    fun isValidTelemetry(payload: RealtimeLocationPayload, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (payload.driverId.isBlank()) return false
        if (!GpsBroadcastThrottle.isValidCoordinate(payload.lat, payload.lng)) return false
        if (payload.accuracyMeters.isNaN() || payload.accuracyMeters < 0.0 || payload.accuracyMeters > 150.0) return false
        if (payload.trackingStatus !in setOf("active", "paused_gps_lost", "stopped")) return false
        if (payload.updatedAtEpochMs <= 0L) return false
        if (nowMs - payload.updatedAtEpochMs > STALE_THRESHOLD_MS) return false
        return true
    }

    fun toTripLocation(payload: RealtimeLocationPayload): TripLocation? {
        if (!GpsBroadcastThrottle.isValidCoordinate(payload.lat, payload.lng)) return null
        return TripLocation(
            id = payload.bookingId,
            bookingId = payload.bookingId,
            customerId = payload.customerId,
            driverId = payload.driverId,
            lat = payload.lat,
            lng = payload.lng,
            headingDeg = payload.headingDeg,
            speedKmph = (payload.speedMps * 3.6).coerceAtLeast(0.0),
            accuracyMeters = payload.accuracyMeters,
            trackingStatus = payload.trackingStatus,
            isTestRide = payload.isTestRide,
            isSimulated = false,
            updatedAt = Timestamp(Date(payload.updatedAtEpochMs))
        )
    }
}

class RealtimeLocationRepository(context: Context) {
    private val appContext = context.applicationContext

    private val database: FirebaseDatabase? by lazy {
        runCatching {
            if (FirebaseApp.getApps(appContext).isEmpty()) return@runCatching null
            val url = appContext.getString(R.string.firebase_rtdb_url)
            if (url.isBlank()) return@runCatching null
            FirebaseDatabase.getInstance(url)
        }.getOrNull()
    }

    private val offlineRetryBuffer = ArrayDeque<RealtimeLocationPayload>(16)

    suspend fun publishDriverAndTripLocation(
        driverId: String,
        activeBooking: Booking?,
        lat: Double,
        lng: Double,
        accuracyMeters: Float,
        speedMps: Float,
        headingDeg: Float,
        trackingStatus: String = "active"
    ) {
        val nowMs = System.currentTimeMillis()
        val payload = RealtimeLocationPayload(
            driverId = driverId,
            bookingId = activeBooking?.id.orEmpty(),
            customerId = activeBooking?.customerId.orEmpty(),
            lat = lat,
            lng = lng,
            accuracyMeters = accuracyMeters.toDouble().coerceAtLeast(0.0),
            speedMps = speedMps.toDouble().coerceAtLeast(0.0),
            headingDeg = ((headingDeg % 360f) + 360f) % 360f.toDouble(),
            tripStatus = activeBooking?.status.orEmpty(),
            trackingStatus = trackingStatus,
            isTestRide = activeBooking?.let { it.isTestRide || it.testMode } ?: false,
            updatedAtEpochMs = nowMs
        )
        if (!RealtimeLocationValidator.isValidTelemetry(payload, nowMs)) return

        val rtdb = database ?: return
        flushBufferedSamples(rtdb)
        writeSample(rtdb, payload)
    }

    private suspend fun writeSample(rtdb: FirebaseDatabase, payload: RealtimeLocationPayload) {
        val driverMap = mutableMapOf<String, Any>(
            "driverId" to payload.driverId,
            "lat" to payload.lat,
            "lng" to payload.lng,
            "accuracyMeters" to payload.accuracyMeters,
            "speedMps" to payload.speedMps,
            "headingDeg" to payload.headingDeg,
            "trackingStatus" to payload.trackingStatus,
            "updatedAtEpochMs" to payload.updatedAtEpochMs
        )
        if (payload.bookingId.isNotBlank()) {
            driverMap["activeBookingId"] = payload.bookingId
        }

        try {
            rtdb.getReference("live_locations/drivers/${payload.driverId}")
                .setValue(driverMap)
                .await()

            if (payload.bookingId.isNotBlank() && payload.customerId.isNotBlank()) {
                val tripMap = mapOf(
                    "bookingId" to payload.bookingId,
                    "customerId" to payload.customerId,
                    "driverId" to payload.driverId,
                    "lat" to payload.lat,
                    "lng" to payload.lng,
                    "accuracyMeters" to payload.accuracyMeters,
                    "speedMps" to payload.speedMps,
                    "headingDeg" to payload.headingDeg,
                    "tripStatus" to payload.tripStatus,
                    "trackingStatus" to payload.trackingStatus,
                    "isTestRide" to payload.isTestRide,
                    "updatedAtEpochMs" to payload.updatedAtEpochMs
                )
                rtdb.getReference("live_locations/trips/${payload.bookingId}")
                    .setValue(tripMap)
                    .await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "RTDB live location publish buffered for retry", e)
            if (offlineRetryBuffer.size >= 12) offlineRetryBuffer.removeFirst()
            offlineRetryBuffer.addLast(payload)
        }
    }

    private suspend fun flushBufferedSamples(rtdb: FirebaseDatabase) {
        while (offlineRetryBuffer.isNotEmpty()) {
            val next = offlineRetryBuffer.removeFirst()
            runCatching {
                val latestCheck = RealtimeLocationValidator.isValidTelemetry(next)
                if (latestCheck) {
                    rtdb.getReference("live_locations/drivers/${next.driverId}")
                        .child("lat").get().await()
                    writeSample(rtdb, next)
                }
            }.onFailure {
                offlineRetryBuffer.addFirst(next)
                return
            }
        }
    }

    suspend fun stopTripTracking(driverId: String, bookingId: String?, customerId: String?, lastLat: Double, lastLng: Double) {
        val rtdb = database ?: return
        val nowMs = System.currentTimeMillis()
        runCatching {
            if (GpsBroadcastThrottle.isValidCoordinate(lastLat, lastLng)) {
                rtdb.getReference("live_locations/drivers/$driverId")
                    .setValue(
                        mapOf(
                            "driverId" to driverId,
                            "lat" to lastLat,
                            "lng" to lastLng,
                            "trackingStatus" to "stopped",
                            "updatedAtEpochMs" to nowMs
                        )
                    ).await()
                if (!bookingId.isNullOrBlank() && !customerId.isNullOrBlank()) {
                    rtdb.getReference("live_locations/trips/$bookingId")
                        .setValue(
                            mapOf(
                                "bookingId" to bookingId,
                                "customerId" to customerId,
                                "driverId" to driverId,
                                "lat" to lastLat,
                                "lng" to lastLng,
                                "trackingStatus" to "stopped",
                                "updatedAtEpochMs" to nowMs
                            )
                        ).await()
                }
            }
        }
    }

    fun observeTripLocation(bookingId: String): Flow<TripLocation?> = callbackFlow {
        val rtdb = database
        if (bookingId.isBlank() || rtdb == null) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val ref = rtdb.getReference("live_locations/trips/$bookingId")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(null)
                    return
                }
                val lat = snapshot.child("lat").getValue(Double::class.java) ?: 0.0
                val lng = snapshot.child("lng").getValue(Double::class.java) ?: 0.0
                val payload = RealtimeLocationPayload(
                    driverId = snapshot.child("driverId").getValue(String::class.java).orEmpty(),
                    bookingId = snapshot.child("bookingId").getValue(String::class.java).orEmpty().ifBlank { bookingId },
                    customerId = snapshot.child("customerId").getValue(String::class.java).orEmpty(),
                    lat = lat,
                    lng = lng,
                    accuracyMeters = snapshot.child("accuracyMeters").getValue(Double::class.java) ?: 15.0,
                    speedMps = snapshot.child("speedMps").getValue(Double::class.java) ?: 0.0,
                    headingDeg = snapshot.child("headingDeg").getValue(Double::class.java) ?: 0.0,
                    tripStatus = snapshot.child("tripStatus").getValue(String::class.java).orEmpty(),
                    trackingStatus = snapshot.child("trackingStatus").getValue(String::class.java) ?: "active",
                    isTestRide = snapshot.child("isTestRide").getValue(Boolean::class.java) ?: false,
                    updatedAtEpochMs = snapshot.child("updatedAtEpochMs").getValue(Long::class.java) ?: 0L
                )
                trySend(RealtimeLocationValidator.toTripLocation(payload))
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "RTDB live trip location cancelled: ${error.message}")
                trySend(null)
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    private companion object {
        const val TAG = "GoLocalRTDBLocation"
    }
}
