package com.example.data.map

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

data class LiveDriverLocation(
    val driverId: String = "",
    val displayName: String = "",
    val vehicleCategory: String = "toto",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val bearing: Float = 0f,
    val speedKmph: Float = 0f,
    val accuracyMeters: Float = 0f,
    val isOnline: Boolean = false,
    val gpsStatus: String = "active", // active, gps_lost, offline
    val activeBookingId: String = "",
    val updatedAtEpochMs: Long = 0L
)

data class ActiveTripTracking(
    val bookingId: String = "",
    val customerId: String = "",
    val driverId: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val bearing: Float = 0f,
    val speedKmph: Float = 0f,
    val tripStatus: String = "",
    val trackingStatus: String = "active", // active, stopped
    val gpsStatus: String = "active", // active, gps_lost, network_interrupted
    val updatedAtEpochMs: Long = 0L
)

interface LiveTrackingProvider {
    suspend fun publishDriverLiveLocation(location: LiveDriverLocation): Result<Unit>
    suspend fun stopDriverLiveLocation(driverId: String): Result<Unit>
    suspend fun publishActiveTripLocation(trip: ActiveTripTracking): Result<Unit>
    suspend fun stopActiveTripTracking(bookingId: String): Result<Unit>
    fun observeLiveDrivers(): Flow<List<LiveDriverLocation>>
    fun observeActiveTripTracking(bookingId: String): Flow<ActiveTripTracking?>
    fun observeAllActiveTrips(): Flow<List<ActiveTripTracking>>
}

/**
 * Firebase Realtime Database repository for low-latency partner & active-trip live coordinates.
 * Intentionally avoids storing PII (phone numbers, addresses, emails) in RTDB nodes.
 */
class LiveTrackingRepository(
    private val database: FirebaseDatabase? = createSafeRtdbInstance()
) : LiveTrackingProvider {

    override suspend fun publishDriverLiveLocation(location: LiveDriverLocation): Result<Unit> = runCatching {
        val db = database ?: throw IllegalStateException("Firebase Realtime Database is not configured.")
        require(location.driverId.isNotBlank()) { "driverId cannot be blank" }
        val payload = mapOf(
            "driverId" to location.driverId,
            "displayName" to location.displayName.take(80),
            "vehicleCategory" to location.vehicleCategory,
            "lat" to location.lat,
            "lng" to location.lng,
            "bearing" to location.bearing.toDouble(),
            "speedKmph" to location.speedKmph.toDouble(),
            "accuracyMeters" to location.accuracyMeters.toDouble(),
            "isOnline" to location.isOnline,
            "gpsStatus" to location.gpsStatus,
            "activeBookingId" to location.activeBookingId,
            "updatedAtEpochMs" to (if (location.updatedAtEpochMs > 0L) location.updatedAtEpochMs else System.currentTimeMillis())
        )
        try {
            db.getReference(NODE_LIVE_DRIVERS).child(location.driverId).setValue(payload).await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "RTDB write failed for live_drivers/${location.driverId}: ${e.message}")
            throw e
        }
    }

    override suspend fun stopDriverLiveLocation(driverId: String): Result<Unit> = runCatching {
        val db = database ?: return@runCatching
        if (driverId.isBlank()) return@runCatching
        try {
            db.getReference(NODE_LIVE_DRIVERS).child(driverId).removeValue().await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "RTDB remove failed for live_drivers/$driverId: ${e.message}")
            throw e
        }
    }

    override suspend fun publishActiveTripLocation(trip: ActiveTripTracking): Result<Unit> = runCatching {
        val db = database ?: throw IllegalStateException("Firebase Realtime Database is not configured.")
        require(trip.bookingId.isNotBlank() && trip.driverId.isNotBlank() && trip.customerId.isNotBlank()) {
            "bookingId, driverId, and customerId are required for active_trips tracking"
        }
        val payload = mapOf(
            "bookingId" to trip.bookingId,
            "customerId" to trip.customerId,
            "driverId" to trip.driverId,
            "lat" to trip.lat,
            "lng" to trip.lng,
            "bearing" to trip.bearing.toDouble(),
            "speedKmph" to trip.speedKmph.toDouble(),
            "tripStatus" to trip.tripStatus,
            "trackingStatus" to trip.trackingStatus,
            "gpsStatus" to trip.gpsStatus,
            "updatedAtEpochMs" to (if (trip.updatedAtEpochMs > 0L) trip.updatedAtEpochMs else System.currentTimeMillis())
        )
        try {
            db.getReference(NODE_ACTIVE_TRIPS).child(trip.bookingId).setValue(payload).await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "RTDB write failed for active_trips/${trip.bookingId}: ${e.message}")
            throw e
        }
    }

    override suspend fun stopActiveTripTracking(bookingId: String): Result<Unit> = runCatching {
        val db = database ?: return@runCatching
        if (bookingId.isBlank()) return@runCatching
        try {
            db.getReference(NODE_ACTIVE_TRIPS).child(bookingId).removeValue().await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "RTDB remove failed for active_trips/$bookingId: ${e.message}")
            throw e
        }
    }

    override fun observeLiveDrivers(): Flow<List<LiveDriverLocation>> {
        val db = database ?: return flowOf(emptyList())
        return callbackFlow {
            val ref = db.getReference(NODE_LIVE_DRIVERS)
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val out = snapshot.children.mapNotNull { parseDriverSnapshot(it) }
                    trySend(out)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "RTDB live_drivers listener cancelled: ${error.message}")
                    trySend(emptyList())
                }
            }
            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }
    }

    override fun observeActiveTripTracking(bookingId: String): Flow<ActiveTripTracking?> {
        val db = database ?: return flowOf(null)
        if (bookingId.isBlank()) return flowOf(null)
        return callbackFlow {
            val ref = db.getReference(NODE_ACTIVE_TRIPS).child(bookingId)
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    trySend(parseActiveTripSnapshot(snapshot))
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "RTDB active_trips/$bookingId listener cancelled: ${error.message}")
                    trySend(null)
                }
            }
            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }
    }

    override fun observeAllActiveTrips(): Flow<List<ActiveTripTracking>> {
        val db = database ?: return flowOf(emptyList())
        return callbackFlow {
            val ref = db.getReference(NODE_ACTIVE_TRIPS)
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val out = snapshot.children.mapNotNull { parseActiveTripSnapshot(it) }
                    trySend(out)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "RTDB active_trips listener cancelled: ${error.message}")
                    trySend(emptyList())
                }
            }
            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }
    }

    companion object {
        private const val TAG = "LiveTrackingRepo"
        const val NODE_LIVE_DRIVERS = "live_locations/drivers"
        const val NODE_ACTIVE_TRIPS = "live_locations/trips"

        fun createSafeRtdbInstance(): FirebaseDatabase? {
            return runCatching {
                FirebaseDatabase.getInstance(MapProviderConfig.firebaseRtdbUrl)
            }.onFailure {
                Log.w(TAG, "FirebaseDatabase not initialized in current environment: ${it.message}")
            }.getOrNull()
        }

        private fun parseDriverSnapshot(snap: DataSnapshot): LiveDriverLocation? {
            if (!snap.exists()) return null
            val lat = snap.child("lat").getValue(Double::class.java) ?: return null
            val lng = snap.child("lng").getValue(Double::class.java) ?: return null
            return LiveDriverLocation(
                driverId = snap.child("driverId").getValue(String::class.java) ?: snap.key.orEmpty(),
                displayName = snap.child("displayName").getValue(String::class.java) ?: "",
                vehicleCategory = snap.child("vehicleCategory").getValue(String::class.java) ?: "toto",
                lat = lat,
                lng = lng,
                bearing = (snap.child("bearing").getValue(Double::class.java) ?: 0.0).toFloat(),
                speedKmph = (snap.child("speedKmph").getValue(Double::class.java) ?: 0.0).toFloat(),
                accuracyMeters = (snap.child("accuracyMeters").getValue(Double::class.java) ?: 0.0).toFloat(),
                isOnline = snap.child("isOnline").getValue(Boolean::class.java) ?: false,
                gpsStatus = snap.child("gpsStatus").getValue(String::class.java) ?: "active",
                activeBookingId = snap.child("activeBookingId").getValue(String::class.java) ?: "",
                updatedAtEpochMs = snap.child("updatedAtEpochMs").getValue(Long::class.java) ?: 0L
            )
        }

        private fun parseActiveTripSnapshot(snap: DataSnapshot): ActiveTripTracking? {
            if (!snap.exists()) return null
            val lat = snap.child("lat").getValue(Double::class.java) ?: return null
            val lng = snap.child("lng").getValue(Double::class.java) ?: return null
            return ActiveTripTracking(
                bookingId = snap.child("bookingId").getValue(String::class.java) ?: snap.key.orEmpty(),
                customerId = snap.child("customerId").getValue(String::class.java) ?: "",
                driverId = snap.child("driverId").getValue(String::class.java) ?: "",
                lat = lat,
                lng = lng,
                bearing = (snap.child("bearing").getValue(Double::class.java) ?: 0.0).toFloat(),
                speedKmph = (snap.child("speedKmph").getValue(Double::class.java) ?: 0.0).toFloat(),
                tripStatus = snap.child("tripStatus").getValue(String::class.java) ?: "",
                trackingStatus = snap.child("trackingStatus").getValue(String::class.java) ?: "active",
                gpsStatus = snap.child("gpsStatus").getValue(String::class.java) ?: "active",
                updatedAtEpochMs = snap.child("updatedAtEpochMs").getValue(Long::class.java) ?: 0L
            )
        }
    }
}
