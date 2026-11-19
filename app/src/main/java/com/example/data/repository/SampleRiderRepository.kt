package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.SampleRide
import com.example.data.model.SampleRiderAllowlistEntry
import com.example.data.model.SampleRiderProfile
import com.example.data.model.UserAccount
import com.example.domain.SampleOtpLocalStore
import com.example.domain.TestRideOtpUtil
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.UUID

sealed interface SampleAcceptOutcome {
    data class Accepted(val rideId: String) : SampleAcceptOutcome
    data object AlreadyTaken : SampleAcceptOutcome
    data object Expired : SampleAcceptOutcome
    data class Failed(val message: String) : SampleAcceptOutcome
}

data class CreatedSampleRideResult(
    val rideId: String,
    val localOtp: String,
    val expiresAtMs: Long
)

class SampleRiderRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = Firebase.auth
    )

    private fun requireUid(): String =
        auth.currentUser?.uid?.trim().orEmpty().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Firebase authentication is required.")

    private fun <T> safeQueryFlow(
        path: String,
        query: Query,
        mapper: (DocumentSnapshot) -> T?
    ): Flow<List<T>> = flow {
        val uid = requireUid()
        emitAll(
            query.snapshots()
                .map { snapshot -> snapshot.documents.mapNotNull { mapper(it) } }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path, auth)
                    }
                    throw error
                }
        )
    }

    private fun <T> safeDocFlow(
        path: String,
        docId: String,
        mapper: (DocumentSnapshot) -> T?
    ): Flow<T?> = flow {
        val uid = requireUid()
        val ref = db.collection(path).document(docId)
        emitAll(
            ref.snapshots()
                .map { snapshot -> if (snapshot.exists()) mapper(snapshot) else null }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, ref.path, auth)
                    }
                    throw error
                }
        )
    }

    suspend fun getAllowlistEntryIfEligible(phoneE164: String): SampleRiderAllowlistEntry? {
        val cleanPhone = phoneE164.trim()
        if (cleanPhone.isBlank() || !cleanPhone.startsWith("+")) return null
        val ref = db.collection("sample_rider_allowlist").document(cleanPhone)
        val snap = try {
            ref.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (!snap.exists()) return null
        val entry = snap.toObject(SampleRiderAllowlistEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        return entry?.takeIf { it.enabled }
    }

    /**
     * Checks allowlist & auto-provisions `/users/{uid}` and `/sample_riders/{uid}`
     * immediately after Firebase Phone OTP verification without requiring DL, RC,
     * insurance, or commercial driver KYC review.
     */
    suspend fun autoProvisionSampleRiderIfAllowlisted(
        uid: String,
        verifiedPhoneE164: String
    ): UserAccount? {
        val cleanPhone = verifiedPhoneE164.trim()
            .ifBlank { auth.currentUser?.phoneNumber.orEmpty().trim() }
            .take(25)
        if (cleanPhone.isBlank()) return null

        val allowlistEntry = getAllowlistEntryIfEligible(cleanPhone)
        val sampleRef = db.collection("sample_riders").document(uid)
        val existingSampleSnap = try {
            sampleRef.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val existingSample = existingSampleSnap?.takeIf { it.exists() }
            ?.toObject(SampleRiderProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)

        if (allowlistEntry == null && existingSample == null) {
            return null
        }

        val userRef = db.collection("users").document(uid)
        val existingUserSnap = runCatching { userRef.get().await() }.getOrNull()
        val existingUser = existingUserSnap?.takeIf { it.exists() }
            ?.toObject(UserAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)

        val resolvedStatus = existingSample?.verificationStatus?.takeIf { it != "sample_approved" }
            ?: "sample_approved"
        val resolvedName = existingSample?.displayName?.ifBlank { null }
            ?: existingUser?.fullName?.takeIf { it.isNotBlank() && it != "GoLocal User" }
            ?: allowlistEntry?.label?.ifBlank { null }
            ?: "Sample Rider"
        val resolvedVehicle = existingSample?.vehicleCategory?.ifBlank { null }
            ?: allowlistEntry?.vehicleCategory?.ifBlank { null }
            ?: "toto"
        val resolvedAvailability = if (resolvedStatus == "sample_approved") {
            existingSample?.availabilityStatus?.ifBlank { "offline" } ?: "offline"
        } else {
            "offline"
        }

        // Provision /users/{uid} ONLY if allowlisted and missing/needs sample metadata
        if (allowlistEntry != null) {
            val batch = db.batch()
            if (existingUser == null) {
                val newUser = mapOf(
                    "uid" to uid,
                    "userId" to uid,
                    "fullName" to resolvedName.take(100),
                    "phone" to cleanPhone,
                    "mobileNumber" to cleanPhone,
                    "role" to "rider",
                    "accountType" to "sample",
                    "isSampleRider" to true,
                    "verificationStatus" to "sample_approved",
                    "availabilityStatus" to resolvedAvailability,
                    "preferredLanguage" to "en",
                    "accountStatus" to "active",
                    "lowNetworkMode" to false,
                    "privacyConsentAccepted" to true,
                    "profileCompleted" to true,
                    "emailVerified" to false,
                    "mobileVerified" to true,
                    "hasEmergencyContact" to (existingSample?.emergencyContactPhone?.isNotBlank() == true),
                    "accountCreatedAt" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                batch.set(userRef, newUser)
            } else if (!existingUser.isSampleRiderAccount() ||
                existingUser.effectiveMobileNumber() != cleanPhone ||
                !existingUser.mobileVerified
            ) {
                batch.update(
                    userRef,
                    mapOf(
                        "uid" to uid,
                        "fullName" to resolvedName.take(100),
                        "phone" to cleanPhone,
                        "mobileNumber" to cleanPhone,
                        "role" to "rider",
                        "accountType" to "sample",
                        "isSampleRider" to true,
                        "verificationStatus" to "sample_approved",
                        "availabilityStatus" to resolvedAvailability,
                        "privacyConsentAccepted" to true,
                        "profileCompleted" to true,
                        "mobileVerified" to true,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            if (existingSample == null) {
                batch.set(
                    sampleRef,
                    mapOf(
                        "uid" to uid,
                        "role" to "rider",
                        "accountType" to "sample",
                        "isSampleRider" to true,
                        "verificationStatus" to "sample_approved",
                        "availabilityStatus" to "offline",
                        "displayName" to resolvedName.take(100),
                        "phone" to cleanPhone,
                        "photoUrl" to "",
                        "vehicleCategory" to resolvedVehicle,
                        "emergencyContactName" to "",
                        "emergencyContactPhone" to "",
                        "completedSampleRides" to 0L,
                        "totalSampleEarningsInr" to 0.0,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            try {
                batch.commit().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, sampleRef.path, auth)
                throw e
            }
        }

        return UserAccount(
            id = uid,
            uid = uid,
            userId = uid,
            fullName = resolvedName,
            phone = cleanPhone,
            mobileNumber = cleanPhone,
            role = "rider",
            accountType = "sample",
            isSampleRider = true,
            verificationStatus = resolvedStatus,
            availabilityStatus = resolvedAvailability,
            preferredLanguage = existingUser?.preferredLanguage?.ifBlank { "en" } ?: "en",
            accountStatus = existingUser?.accountStatus?.ifBlank { "active" } ?: "active",
            privacyConsentAccepted = true,
            profileCompleted = true,
            mobileVerified = true,
            hasEmergencyContact = existingSample?.emergencyContactPhone?.isNotBlank() == true
        )
    }

    fun observeMySampleRiderProfile(uid: String): Flow<SampleRiderProfile?> =
        safeDocFlow("sample_riders", uid) { doc ->
            doc.toObject(SampleRiderProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeIncomingSampleRides(): Flow<List<SampleRide>> =
        safeQueryFlow(
            path = "sample_rides",
            query = db.collection("sample_rides").whereEqualTo("status", "incoming")
        ) { doc ->
            doc.toObject(SampleRide::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }.map { list ->
            val nowMs = System.currentTimeMillis()
            list.filter { ride ->
                val expMs = ride.expiresAt?.toDate()?.time ?: Long.MAX_VALUE
                expMs > nowMs
            }.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
        }

    fun observeDismissedRideIds(uid: String): Flow<Set<String>> =
        safeQueryFlow(
            path = "sample_riders/$uid/dismissed_rides",
            query = db.collection("sample_riders").document(uid).collection("dismissed_rides")
        ) { doc -> doc.id }.map { it.toSet() }

    fun observeAssignedSampleRides(uid: String): Flow<List<SampleRide>> {
        val currentUid = auth.currentUser?.uid?.trim().orEmpty()
        if (uid.isBlank() || currentUid.isBlank() || uid != currentUid) {
            return kotlinx.coroutines.flow.flowOf(emptyList())
        }
        return safeQueryFlow(
            path = "sample_rides",
            query = db.collection("sample_rides").whereEqualTo("assignedSampleRiderId", uid)
        ) { doc ->
            doc.toObject(SampleRide::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }.map { list ->
            list.sortedByDescending { it.updatedAt?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: 0L }
        }
    }

    fun observeCustomerSampleRides(customerId: String): Flow<List<SampleRide>> {
        val currentUid = auth.currentUser?.uid?.trim().orEmpty()
        if (customerId.isBlank() || currentUid.isBlank() || customerId != currentUid) {
            return kotlinx.coroutines.flow.flowOf(emptyList())
        }
        return safeQueryFlow(
            path = "sample_rides",
            query = db.collection("sample_rides").whereEqualTo("customerId", customerId)
        ) { doc ->
            doc.toObject(SampleRide::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }.map { list ->
            list.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
        }
    }

    fun observeAllSampleAllowlist(): Flow<List<SampleRiderAllowlistEntry>> =
        safeQueryFlow(
            path = "sample_rider_allowlist",
            query = db.collection("sample_rider_allowlist")
        ) { doc ->
            doc.toObject(SampleRiderAllowlistEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }.map { list -> list.sortedByDescending { it.updatedAt?.toDate()?.time ?: 0L } }

    fun observeAllSampleRiders(): Flow<List<SampleRiderProfile>> =
        safeQueryFlow(
            path = "sample_riders",
            query = db.collection("sample_riders")
        ) { doc ->
            doc.toObject(SampleRiderProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }.map { list -> list.sortedByDescending { it.updatedAt?.toDate()?.time ?: 0L } }

    fun observeAllSampleRides(): Flow<List<SampleRide>> =
        safeQueryFlow(
            path = "sample_rides",
            query = db.collection("sample_rides")
        ) { doc ->
            doc.toObject(SampleRide::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }.map { list -> list.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L } }

    suspend fun setSampleRiderAvailability(
        uid: String,
        online: Boolean,
        lat: Double? = null,
        lng: Double? = null
    ): Result<Unit> = runCatching {
        val currentUid = requireUid()
        require(currentUid == uid) { "Session UID mismatch." }
        if (online) {
            require(lat != null && lng != null) {
                "Real GPS location fix is required before going online."
            }
        }
        val status = if (online) "online" else "offline"
        val sampleRef = db.collection("sample_riders").document(uid)
        val userRef = db.collection("users").document(uid)
        val batch = db.batch()
        val sampleUpdate = mutableMapOf<String, Any>(
            "availabilityStatus" to status,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (lat != null && lng != null) {
            sampleUpdate["currentLat"] = lat
            sampleUpdate["currentLng"] = lng
            sampleUpdate["locationUpdatedAt"] = FieldValue.serverTimestamp()
        }
        batch.update(sampleRef, sampleUpdate)
        batch.update(
            userRef,
            mapOf(
                "availabilityStatus" to status,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        Unit
    }

    suspend fun updateSampleRiderProfile(
        uid: String,
        displayName: String,
        vehicleCategory: String,
        photoUrl: String,
        emergencyContactName: String,
        emergencyContactPhone: String
    ): Result<Unit> = runCatching {
        val currentUid = requireUid()
        require(currentUid == uid) { "Session UID mismatch." }
        val cleanName = displayName.trim().take(100)
        require(cleanName.length >= 2) { "Display name must be at least 2 characters." }
        val cleanVehicle = vehicleCategory.trim().lowercase().takeIf {
            it in setOf("bike", "auto", "toto", "cab")
        } ?: "toto"
        val cleanPhoto = photoUrl.trim().take(500)
        val cleanEmergencyName = emergencyContactName.trim().take(100)
        val cleanEmergencyPhone = emergencyContactPhone.trim().take(25)

        val batch = db.batch()
        batch.update(
            db.collection("sample_riders").document(uid),
            mapOf(
                "displayName" to cleanName,
                "vehicleCategory" to cleanVehicle,
                "photoUrl" to cleanPhoto,
                "emergencyContactName" to cleanEmergencyName,
                "emergencyContactPhone" to cleanEmergencyPhone,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.update(
            db.collection("users").document(uid),
            mapOf(
                "fullName" to cleanName,
                "photoUrl" to cleanPhoto,
                "hasEmergencyContact" to (cleanEmergencyPhone.length >= 8),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        Unit
    }

    suspend fun updateSampleRiderLocation(
        uid: String,
        lat: Double,
        lng: Double,
        activeRideId: String? = null
    ): Result<Unit> = runCatching {
        val currentUid = requireUid()
        require(currentUid == uid) { "Session UID mismatch." }
        val batch = db.batch()
        batch.update(
            db.collection("sample_riders").document(uid),
            mapOf(
                "currentLat" to lat,
                "currentLng" to lng,
                "locationUpdatedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        if (!activeRideId.isNullOrBlank()) {
            batch.update(
                db.collection("sample_rides").document(activeRideId),
                mapOf(
                    "riderLat" to lat,
                    "riderLng" to lng,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
        batch.commit().await()
        Unit
    }

    suspend fun dismissSampleRide(uid: String, rideId: String): Result<Unit> = runCatching {
        val currentUid = requireUid()
        require(currentUid == uid) { "Session UID mismatch." }
        db.collection("sample_riders")
            .document(uid)
            .collection("dismissed_rides")
            .document(rideId)
            .set(
                mapOf(
                    "rideId" to rideId,
                    "dismissedAt" to FieldValue.serverTimestamp()
                )
            )
            .await()
        Unit
    }

    suspend fun acceptSampleRideAtomically(
        rideId: String,
        rider: SampleRiderProfile,
        currentLat: Double?,
        currentLng: Double?
    ): SampleAcceptOutcome {
        val uid = runCatching { requireUid() }.getOrElse {
            return SampleAcceptOutcome.Failed("Please sign in again.")
        }
        if (rider.uid != uid || rider.isSuspendedOrRevoked()) {
            return SampleAcceptOutcome.Failed("Sample Rider account is not active.")
        }
        val rideRef = db.collection("sample_rides").document(rideId)
        return try {
            db.runTransaction { tx ->
                val snap = tx.get(rideRef)
                if (!snap.exists()) {
                    throw IllegalStateException("EXPIRED")
                }
                val current = snap.toObject(SampleRide::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    ?: throw IllegalStateException("TAKEN")
                val expMs = current.expiresAt?.toDate()?.time ?: Long.MAX_VALUE
                if (expMs <= System.currentTimeMillis() || current.status == "expired") {
                    throw IllegalStateException("EXPIRED")
                }
                if (current.status != "incoming" || current.assignedSampleRiderId.isNotBlank()) {
                    throw IllegalStateException("TAKEN")
                }
                val updates = mutableMapOf<String, Any>(
                    "status" to "accepted",
                    "assignedSampleRiderId" to uid,
                    "assignedSampleRiderName" to rider.displayName.ifBlank { "Sample Rider" },
                    "assignedSampleRiderPhone" to rider.phone,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (currentLat != null && currentLng != null) {
                    updates["riderLat"] = currentLat
                    updates["riderLng"] = currentLng
                }
                tx.update(rideRef, updates)
                null
            }.await()
            SampleAcceptOutcome.Accepted(rideId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            when {
                msg.contains("TAKEN") -> SampleAcceptOutcome.AlreadyTaken
                msg.contains("EXPIRED") -> SampleAcceptOutcome.Expired
                e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    SampleAcceptOutcome.AlreadyTaken
                else -> SampleAcceptOutcome.Failed(msg.ifBlank { "Unable to accept sample request." })
            }
        }
    }

    suspend fun advanceSampleRideStatus(
        rideId: String,
        nextStatus: String,
        lat: Double? = null,
        lng: Double? = null,
        cancellationReason: String = ""
    ): Result<Unit> = runCatching {
        requireUid()
        require(nextStatus != "in_progress") {
            "Sample ride cannot start without OTP verification."
        }
        val updates = mutableMapOf<String, Any>(
            "status" to nextStatus,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (lat != null && lng != null) {
            updates["riderLat"] = lat
            updates["riderLng"] = lng
        }
        if (nextStatus == "completed") {
            updates["completedAt"] = FieldValue.serverTimestamp()
        }
        if (nextStatus == "cancelled" && cancellationReason.isNotBlank()) {
            updates["cancellationReason"] = cancellationReason.trim().take(250)
        }
        db.collection("sample_rides").document(rideId).update(updates).await()
        Unit
    }

    suspend fun verifySampleRideOtpAndStartTrip(
        rideId: String,
        enteredOtp: String,
        lat: Double? = null,
        lng: Double? = null
    ): Result<Unit> = runCatching {
        val riderUid = requireUid()
        val cleanOtp = enteredOtp.trim()
        require(cleanOtp.length == 6 && cleanOtp.all { it.isDigit() }) {
            "Enter the 6-digit numeric OTP shown to the customer."
        }
        val submittedHash = TestRideOtpUtil.sha256Hex(rideId, cleanOtp)
        val batch = db.batch()
        batch.update(
            db.collection("sample_ride_otps").document(rideId),
            mapOf(
                "used" to true,
                "submittedHash" to submittedHash,
                "verifiedByUid" to riderUid,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        val rideUpdates = mutableMapOf<String, Any>(
            "status" to "in_progress",
            "otpVerified" to true,
            "otpVerifiedAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (lat != null && lng != null) {
            rideUpdates["riderLat"] = lat
            rideUpdates["riderLng"] = lng
        }
        batch.update(db.collection("sample_rides").document(rideId), rideUpdates)
        try {
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                "Incorrect OTP or challenge expired. Please verify the 6-digit code shown on the customer/dispatch screen."
            } else {
                e.localizedMessage ?: "Failed verifying OTP."
            }
            throw IllegalArgumentException(message, e)
        }
        Unit
    }

    /**
     * Creates an isolated Sample Ride + hashed OTP challenge in Firestore.
     * The plaintext 6-digit OTP is NEVER sent to Firestore — it is stored strictly locally
     * in [SampleOtpLocalStore] on the creator's device.
     */
    suspend fun createSampleRideWithHashedOtp(
        context: Context,
        customerId: String,
        customerName: String,
        createdByRole: String,
        vehicleCategory: String,
        pickupLabel: String,
        pickupLat: Double,
        pickupLng: Double,
        destinationLabel: String,
        destinationLat: Double,
        destinationLng: Double,
        distanceKm: Double,
        estimatedDurationMin: Int,
        estimatedFareInr: Double,
        requestTtlSeconds: Long = 180L
    ): Result<CreatedSampleRideResult> = runCatching {
        val actorUid = requireUid()
        val rideId = "sr_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val otpPlaintext = TestRideOtpUtil.generateSixDigitOtp()
        val otpHash = TestRideOtpUtil.sha256Hex(rideId, otpPlaintext)
        val expiresAtMs = System.currentTimeMillis() + (requestTtlSeconds.coerceIn(60L, 900L) * 1000L)
        val expiresAtTs = Timestamp(Date(expiresAtMs))

        val rideDoc = mapOf(
            "rideId" to rideId,
            "isSample" to true,
            "customerId" to customerId.ifBlank { actorUid },
            "customerName" to customerName.trim().ifBlank { "Sample Customer" }.take(100),
            "createdByUid" to actorUid,
            "createdByRole" to createdByRole,
            "vehicleCategory" to vehicleCategory.trim().lowercase().takeIf { it in setOf("bike", "auto", "toto", "cab") }.orEmpty().ifBlank { "toto" },
            "pickupLabel" to pickupLabel.trim().take(200),
            "pickupLat" to pickupLat,
            "pickupLng" to pickupLng,
            "destinationLabel" to destinationLabel.trim().take(200),
            "destinationLat" to destinationLat,
            "destinationLng" to destinationLng,
            "distanceKm" to distanceKm.coerceIn(0.1, 2000.0),
            "estimatedDurationMin" to estimatedDurationMin.coerceAtLeast(1),
            "estimatedFareInr" to estimatedFareInr.coerceAtLeast(0.0),
            "status" to "incoming",
            "assignedSampleRiderId" to "",
            "assignedSampleRiderName" to "",
            "assignedSampleRiderPhone" to "",
            "otpVerified" to false,
            "expiresAt" to expiresAtTs,
            "cancellationReason" to "",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        val otpChallengeDoc = mapOf(
            "rideId" to rideId,
            "customerId" to customerId.ifBlank { actorUid },
            "otpHash" to otpHash,
            "submittedHash" to "",
            "used" to false,
            "attempts" to 0,
            "maxAttempts" to 5,
            "lastAttemptAtMs" to 0L,
            "verifiedByUid" to "",
            "expiresAt" to expiresAtTs,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        val batch = db.batch()
        batch.set(db.collection("sample_rides").document(rideId), rideDoc)
        batch.set(db.collection("sample_ride_otps").document(rideId), otpChallengeDoc)
        batch.commit().await()

        SampleOtpLocalStore.saveOtp(
            context = context,
            creatorUid = actorUid,
            rideId = rideId,
            otpPlaintext = otpPlaintext,
            expiresAtMs = expiresAtMs
        )

        CreatedSampleRideResult(
            rideId = rideId,
            localOtp = otpPlaintext,
            expiresAtMs = expiresAtMs
        )
    }

    // --- Admin Sample Rider Governance ---

    suspend fun upsertSampleRiderAllowlist(
        phoneE164: String,
        label: String,
        vehicleCategory: String,
        enabled: Boolean = true,
        note: String = ""
    ): Result<Unit> = runCatching {
        val adminUid = requireUid()
        val cleanPhone = phoneE164.trim()
        require(Regex("^\\+[1-9][0-9]{7,15}$").matches(cleanPhone)) {
            "Enter a valid E.164 phone number starting with + (e.g., +919876543210)."
        }
        val cleanVehicle = vehicleCategory.trim().lowercase().takeIf {
            it in setOf("bike", "auto", "toto", "cab")
        } ?: "toto"
        val ref = db.collection("sample_rider_allowlist").document(cleanPhone)
        val exists = runCatching { ref.get().await().exists() }.getOrDefault(false)
        if (exists) {
            ref.update(
                mapOf(
                    "enabled" to enabled,
                    "label" to label.trim().take(100),
                    "vehicleCategory" to cleanVehicle,
                    "addedByUid" to adminUid,
                    "note" to note.trim().take(300),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } else {
            ref.set(
                mapOf(
                    "phoneE164" to cleanPhone,
                    "enabled" to enabled,
                    "label" to label.trim().take(100),
                    "vehicleCategory" to cleanVehicle,
                    "addedByUid" to adminUid,
                    "note" to note.trim().take(300),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
        Unit
    }

    suspend fun setSampleRiderVerificationStatus(
        riderUid: String,
        newStatus: String
    ): Result<Unit> = runCatching {
        requireUid()
        require(newStatus in setOf("sample_approved", "suspended", "revoked"))
        val batch = db.batch()
        batch.update(
            db.collection("sample_riders").document(riderUid),
            mapOf(
                "verificationStatus" to newStatus,
                "availabilityStatus" to if (newStatus == "sample_approved") "offline" else "offline",
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.update(
            db.collection("users").document(riderUid),
            mapOf(
                "verificationStatus" to newStatus,
                "availabilityStatus" to "offline",
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        Unit
    }
}
