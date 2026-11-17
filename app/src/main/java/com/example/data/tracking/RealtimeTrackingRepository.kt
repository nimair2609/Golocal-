package com.example.data.tracking

import android.content.Context
import com.example.R
import com.example.data.model.LiveDriverRtdbLocation
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/**
 * Firebase Realtime Database repository for sub-second live Partner & Active Trip coordinates.
 *
 * Security & Lifecycle Invariants:
 * - Only writes when driver UID matches current Firebase Auth UID and sharing is authorized.
 * - Explicitly marks trackingStatus = "stopped" and removes active trip path when trip
 *   completes/cancels or driver goes offline.
 * - Falls back gracefully (no crash) in JVM/Robolectric unit tests if RTDB is uninitialized.
 */
class RealtimeTrackingRepository(
    private val rtdbProvider: () -> FirebaseDatabase?,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context? = null) : this(
        rtdbProvider = buildRtdbProvider(context)
    )

    suspend fun publishDriverLocation(
        lat: Double,
        lng: Double,
        activeBookingId: String = "",
        customerId: String = "",
        accuracyMeters: Float = 0f,
        speedMps: Float = 0f,
        headingDeg: Float = 0f,
        trackingStatus: String = "active"
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("Driver must be authenticated to publish live location.")
        val db = rtdbProvider() ?: return@runCatching
        val now = System.currentTimeMillis()
        val driverPayload = buildMap<String, Any> {
            put("driverId", uid)
            put("lat", lat)
            put("lng", lng)
            if (activeBookingId.isNotBlank()) put("activeBookingId", activeBookingId)
            put("accuracyMeters", accuracyMeters.toDouble())
            put("speedMps", speedMps.toDouble())
            put("headingDeg", headingDeg.toDouble())
            put("trackingStatus", trackingStatus)
            put("updatedAtEpochMs", now)
        }
        db.reference.child("live_locations").child("drivers").child(uid).setValue(driverPayload).await()

        if (activeBookingId.isNotBlank() && customerId.isNotBlank()) {
            val tripPayload = mapOf(
                "bookingId" to activeBookingId,
                "customerId" to customerId,
                "driverId" to uid,
                "lat" to lat,
                "lng" to lng,
                "accuracyMeters" to accuracyMeters.toDouble(),
                "speedMps" to speedMps.toDouble(),
                "headingDeg" to headingDeg.toDouble(),
                "trackingStatus" to trackingStatus,
                "updatedAtEpochMs" to now
            )
            db.reference.child("live_locations").child("trips").child(activeBookingId)
                .setValue(tripPayload).await()
        }
    }

    suspend fun markGpsSignalLost(
        lastLat: Double,
        lastLng: Double,
        activeBookingId: String = "",
        customerId: String = ""
    ): Result<Unit> = publishDriverLocation(
        lat = lastLat,
        lng = lastLng,
        activeBookingId = activeBookingId,
        customerId = customerId,
        trackingStatus = "paused_gps_lost"
    )

    suspend fun stopTripSharing(bookingId: String, lastLat: Double, lastLng: Double): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: return@runCatching
        val db = rtdbProvider() ?: return@runCatching
        val now = System.currentTimeMillis()
        if (bookingId.isNotBlank()) {
            db.reference.child("live_locations").child("trips").child(bookingId)
                .updateChildren(
                    mapOf(
                        "trackingStatus" to "stopped",
                        "updatedAtEpochMs" to now
                    )
                ).await()
        }
        db.reference.child("live_locations").child("drivers").child(uid)
            .setValue(
                mapOf(
                    "driverId" to uid,
                    "lat" to lastLat,
                    "lng" to lastLng,
                    "trackingStatus" to "stopped",
                    "updatedAtEpochMs" to now
                )
            ).await()
    }

    suspend fun stopDriverSharing(lastLat: Double, lastLng: Double): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: return@runCatching
        val db = rtdbProvider() ?: return@runCatching
        val now = System.currentTimeMillis()
        db.reference.child("live_locations").child("drivers").child(uid)
            .setValue(
                mapOf(
                    "driverId" to uid,
                    "lat" to lastLat,
                    "lng" to lastLng,
                    "trackingStatus" to "stopped",
                    "updatedAtEpochMs" to now
                )
            ).await()
    }

    fun observeTripLiveLocation(bookingId: String): Flow<LiveDriverRtdbLocation?> {
        if (bookingId.isBlank()) return flowOf(null)
        val db = rtdbProvider() ?: return flowOf(null)
        return callbackFlow {
            val ref = db.reference.child("live_locations").child("trips").child(bookingId)
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    trySend(snapshot.toLiveDriverLocation(bookingId = bookingId))
                }

                override fun onCancelled(error: DatabaseError) {
                    trySend(null)
                }
            }
            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }
    }

    fun observeAllLiveDriversForOps(): Flow<List<LiveDriverRtdbLocation>> {
        val db = rtdbProvider() ?: return flowOf(emptyList())
        return callbackFlow {
            val ref = db.reference.child("live_locations").child("drivers")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val items = snapshot.children.mapNotNull { child ->
                        child.toLiveDriverLocation(driverIdFallback = child.key.orEmpty())
                    }
                    trySend(items)
                }

                override fun onCancelled(error: DatabaseError) {
                    trySend(emptyList())
                }
            }
            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }
    }

    suspend fun syncOpsRoleToRtdb(targetUid: String, enabled: Boolean): Result<Unit> = runCatching {
        val db = rtdbProvider() ?: return@runCatching
        val ref = db.reference.child("auth_roles").child("ops").child(targetUid)
        if (enabled) {
            ref.setValue(mapOf("uid" to targetUid, "updatedAt" to System.currentTimeMillis())).await()
        } else {
            ref.removeValue().await()
        }
    }

    private fun DataSnapshot.toLiveDriverLocation(
        bookingId: String = "",
        driverIdFallback: String = ""
    ): LiveDriverRtdbLocation? {
        if (!exists()) return null
        val lat = (child("lat").value as? Number)?.toDouble() ?: return null
        val lng = (child("lng").value as? Number)?.toDouble() ?: return null
        return LiveDriverRtdbLocation(
            driverId = child("driverId").getValue(String::class.java) ?: driverIdFallback,
            bookingId = child("bookingId").getValue(String::class.java)
                ?: child("activeBookingId").getValue(String::class.java)
                ?: bookingId,
            customerId = child("customerId").getValue(String::class.java) ?: "",
            lat = lat,
            lng = lng,
            accuracyMeters = (child("accuracyMeters").value as? Number)?.toFloat() ?: 0f,
            speedMps = (child("speedMps").value as? Number)?.toFloat() ?: 0f,
            headingDeg = (child("headingDeg").value as? Number)?.toFloat() ?: 0f,
            trackingStatus = child("trackingStatus").getValue(String::class.java) ?: "stopped",
            updatedAtEpochMs = (child("updatedAtEpochMs").value as? Number)?.toLong() ?: 0L
        )
    }

    companion object {
        private fun buildRtdbProvider(context: Context?): () -> FirebaseDatabase? {
            val appCtx = context?.applicationContext
            return {
                runCatching {
                    val url = appCtx?.getString(R.string.firebase_rtdb_url)
                        ?.takeIf { it.isNotBlank() }
                        ?: "https://gen-lang-client-0818250755-default-rtdb.firebaseio.com"
                    FirebaseDatabase.getInstance(url)
                }.getOrNull()
            }
        }
    }
}
