package com.example.data.repository

import android.net.Uri
import com.example.data.model.AuditLog
import com.example.data.model.Booking
import com.example.data.model.DisputeRecord
import com.example.data.model.DriverDocumentItem
import com.example.data.model.DriverProfile
import com.example.data.model.FareConfig
import com.example.data.model.RatingRecord
import com.example.data.model.StatusHistoryEntry
import com.example.data.model.VerificationHistoryEntry
import com.example.domain.CancellationAndFraudEngine
import com.example.domain.DocumentVerificationEngine
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlin.math.round

class GolocalGovernanceRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val storageProvider: () -> FirebaseStorage = { Firebase.storage }
) {
    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in.")
    }

    private fun <T> safeQueryFlow(
        path: String,
        query: Query,
        mapper: (DocumentSnapshot) -> T?
    ): Flow<List<T>> = flow {
        val uid = requireUserId()
        emitAll(
            query.snapshots()
                .map { snap -> snap.documents.mapNotNull(mapper) }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    val logEx = fe ?: (error as? Exception)
                    if (logEx != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(logEx, OperationType.LIST, path, auth)
                    }
                    throw error
                }
        )
    }

    suspend fun uploadDocumentImage(
        folder: String,
        ownerId: String,
        docType: String,
        localUri: Uri
    ): Result<String> = runCatching {
        val uid = requireUserId()
        require(uid == ownerId) { "Unauthorized document upload target." }
        val ref = storageProvider().reference
            .child(folder)
            .child(uid)
            .child("${docType}_${System.currentTimeMillis()}.jpg")
        ref.putFile(localUri).await()
        ref.downloadUrl.await().toString()
    }

    suspend fun submitDriverDocumentsKyc(
        legalName: String,
        phone: String,
        address: String,
        district: String,
        block: String,
        vehicleCategory: String,
        vehicleModel: String,
        vehicleRegNumber: String,
        settlementUpiOrBank: String,
        documents: List<DriverDocumentItem>
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val validationError = DocumentVerificationEngine.validateSubmissionCompleteness(
            vehicleCategory = vehicleCategory,
            docs = documents
        )
        require(validationError == null) { validationError ?: "Incomplete documents" }

        val dl = documents.firstOrNull { it.docType == "DRIVING_LICENCE" }?.documentNumber.orEmpty()
        val rc = documents.firstOrNull { it.docType == "RC" }?.documentNumber.orEmpty()
        val ins = documents.firstOrNull { it.docType == "INSURANCE" }?.documentNumber.orEmpty()
        val permit = documents.firstOrNull { it.docType == "COMMERCIAL_PERMIT" }?.documentNumber.orEmpty()
        val primaryPhoto = documents.firstOrNull { it.storageUrl.isNotBlank() }?.storageUrl.orEmpty()
        val (earliestExpiry, alertLevel) = DocumentVerificationEngine.summarizeExpiryAlertLevel(
            docs = documents,
            vehicleCategory = vehicleCategory
        )

        val profileRef = db.collection("driver_profiles").document(uid)
        val directoryRef = db.collection("driver_directory").document(uid)
        val existingSnap = profileRef.get().await()
        val existingProfile = existingSnap.toObject(DriverProfile::class.java)
        val isResubmission = existingSnap.exists()
        val nowMs = System.currentTimeMillis()
        val newHistoryEntry = VerificationHistoryEntry(
            action = if (isResubmission) "resubmitted" else "submitted",
            status = "pending",
            reason = "${documents.size} documents submitted for ${vehicleCategory.uppercase()}",
            actorId = uid,
            timestampEpochMs = nowMs
        )
        val history = ((existingProfile?.verificationHistory ?: emptyList()) + newHistoryEntry).takeLast(30)
        val normalizedDocs = documents.map { it.copy(verificationStatus = "pending", rejectionReason = "") }

        val maskedPlate = if (vehicleRegNumber.length >= 4) {
            "***${vehicleRegNumber.takeLast(4)}"
        } else {
            vehicleRegNumber
        }

        db.runBatch { batch ->
            batch.set(
                profileRef,
                mapOf(
                    "driverId" to uid,
                    "legalName" to legalName.trim(),
                    "phone" to phone.trim(),
                    "address" to address.trim(),
                    "district" to district.trim(),
                    "block" to block.trim(),
                    "vehicleCategory" to vehicleCategory.trim().lowercase(),
                    "vehicleModel" to vehicleModel.trim(),
                    "vehicleRegNumber" to vehicleRegNumber.trim().uppercase(),
                    "drivingLicenceNumber" to dl.trim(),
                    "rcNumber" to rc.trim(),
                    "insuranceNumber" to ins.trim(),
                    "permitInfo" to permit.trim(),
                    "settlementUpiOrBank" to settlementUpiOrBank.trim(),
                    "documentPhotoUri" to primaryPhoto,
                    "documents" to normalizedDocs,
                    "verificationHistory" to history,
                    "earliestExpiryIso" to earliestExpiry,
                    "expiryAlertLevel" to alertLevel,
                    "verificationStatus" to "pending",
                    "rejectionReason" to "",
                    "isOnline" to false,
                    "ratingAvg" to (existingProfile?.ratingAvg ?: 5.0),
                    "totalRatingCount" to (existingProfile?.totalRatingCount ?: 0L),
                    "completedRides" to (existingProfile?.completedRides ?: 0L),
                    "createdAt" to (existingProfile?.createdAt ?: FieldValue.serverTimestamp()),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                directoryRef,
                mapOf(
                    "driverId" to uid,
                    "displayName" to legalName.trim(),
                    "vehicleCategory" to vehicleCategory.trim().lowercase(),
                    "vehicleModel" to vehicleModel.trim(),
                    "maskedPlate" to maskedPlate,
                    "district" to district.trim(),
                    "block" to block.trim(),
                    "verificationStatus" to "pending",
                    "isOnline" to false,
                    "currentLat" to 22.90,
                    "currentLng" to 88.39,
                    "ratingAvg" to (existingProfile?.ratingAvg ?: 5.0),
                    "completedRides" to (existingProfile?.completedRides ?: 0L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }.await()
    }

    suspend fun adminReviewDriverDocuments(
        driverId: String,
        decisionStatus: String,
        reason: String
    ): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: auth.currentUser?.phoneNumber ?: actorUid
        val profileRef = db.collection("driver_profiles").document(driverId)
        val dirRef = db.collection("driver_directory").document(driverId)
        val notifRef = db.collection("notifications").document()
        val auditRef = db.collection("audit_logs").document()

        val snap = profileRef.get().await()
        val profile = snap.toObject(DriverProfile::class.java) ?: error("Driver profile not found")
        val (updatedDocs, updatedHistory) = DocumentVerificationEngine.buildAdminDecisionUpdate(
            existingDocs = profile.documents,
            existingHistory = profile.verificationHistory,
            decisionStatus = decisionStatus,
            reason = reason,
            adminUid = actorUid
        )
        val (earliestExpiry, alertLevel) = DocumentVerificationEngine.summarizeExpiryAlertLevel(
            docs = updatedDocs,
            vehicleCategory = profile.vehicleCategory
        )

        db.runBatch { batch ->
            batch.update(
                profileRef,
                mapOf(
                    "verificationStatus" to decisionStatus,
                    "rejectionReason" to if (decisionStatus == "verified") "" else reason.trim(),
                    "documents" to updatedDocs,
                    "verificationHistory" to updatedHistory,
                    "earliestExpiryIso" to earliestExpiry,
                    "expiryAlertLevel" to alertLevel,
                    "isOnline" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                dirRef,
                mapOf(
                    "verificationStatus" to decisionStatus,
                    "isOnline" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                notifRef,
                mapOf(
                    "userId" to driverId,
                    "title" to "KYC Decision: ${decisionStatus.uppercase()}",
                    "body" to if (decisionStatus == "verified") {
                        "Your vehicle & licence documents were approved. You can now go online."
                    } else {
                        "Verification $decisionStatus: ${reason.trim()}"
                    },
                    "type" to "verification",
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                auditRef,
                mapOf(
                    "actorId" to actorUid,
                    "actorEmail" to actorEmail,
                    "action" to "DRIVER_KYC_${decisionStatus.uppercase()}",
                    "targetCollection" to "driver_profiles",
                    "targetId" to driverId,
                    "summary" to "Driver $driverId set to $decisionStatus (${reason.trim().ifBlank { "approved" }})",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
        }.await()
    }

    suspend fun cancelBookingWithPolicy(
        bookingId: String,
        actorRole: String,
        reasonCode: String,
        reasonLabel: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val bookingRef = db.collection("bookings").document(bookingId)
        val bookingSnap = bookingRef.get().await()
        val booking = bookingSnap.toObject(Booking::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            ?: error("Booking not found")

        val authorized = when (actorRole) {
            "customer" -> booking.customerId == uid
            "driver" -> booking.assignedDriverId == uid
            else -> false
        }
        require(authorized) { "Unauthorized cancellation request." }

        val fareSnap = runCatching {
            db.collection("fare_configs").document(booking.vehicleCategory).get().await()
        }.getOrNull()
        val fareConfig = fareSnap?.toObject(FareConfig::class.java)

        val eval = CancellationAndFraudEngine.evaluateRideCancellation(
            booking = booking,
            actorRole = actorRole,
            reasonCode = reasonCode,
            fareConfig = fareConfig
        )
        require(eval.allowed) { eval.error ?: "Cancellation not permitted" }

        val nowMs = System.currentTimeMillis()
        val newEntry = StatusHistoryEntry(
            status = "cancelled",
            actorId = uid,
            actorRole = actorRole,
            note = reasonLabel,
            timestampEpochMs = nowMs
        )
        val updatedHistory = (booking.statusHistory + newEntry).takeLast(25)

        db.runBatch { batch ->
            batch.update(
                bookingRef,
                mapOf(
                    "status" to "cancelled",
                    "cancellationReason" to reasonLabel,
                    "cancelledBy" to uid,
                    "cancelledByRole" to actorRole,
                    "cancellationFeeInr" to eval.cancellationFeeInr,
                    "refundAmountInr" to eval.refundAmountInr,
                    "statusHistory" to updatedHistory,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            if (eval.refundAmountInr > 0.0) {
                val refundRef = db.collection("refunds").document()
                batch.set(
                    refundRef,
                    mapOf(
                        "bookingId" to bookingId,
                        "customerId" to booking.customerId,
                        "amountInr" to eval.refundAmountInr,
                        "reason" to "Auto-evaluated $actorRole cancel ($reasonLabel)",
                        "status" to "requested",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            val targetNotifyUid = if (actorRole == "customer") booking.assignedDriverId else booking.customerId
            if (targetNotifyUid.isNotBlank()) {
                val notifRef = db.collection("notifications").document()
                batch.set(
                    notifRef,
                    mapOf(
                        "userId" to targetNotifyUid,
                        "title" to if (actorRole == "driver") "Driver Cancelled Trip" else "Customer Cancelled Ride",
                        "body" to "Reason: $reasonLabel. ${eval.summary}",
                        "type" to "cancellation",
                        "read" to false,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }.await()

        eval.summary
    }

    suspend fun submitMultiCriteriaRating(
        booking: Booking,
        behaviourStars: Int,
        vehicleStars: Int,
        punctualityStars: Int,
        safetyStars: Int,
        review: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(booking.customerId == uid) { "Only the booking customer can submit this rating." }
        require(booking.status == "completed") { "Ratings are enabled only after ride completion." }
        require(booking.assignedDriverId.isNotBlank()) { "No driver assigned to this ride." }

        val b = behaviourStars.coerceIn(1, 5).toLong()
        val v = vehicleStars.coerceIn(1, 5).toLong()
        val p = punctualityStars.coerceIn(1, 5).toLong()
        val s = safetyStars.coerceIn(1, 5).toLong()
        val overall = round(((b + v + p + s) / 4.0)).toLong().coerceIn(1L, 5L)

        // Deterministic document ID == bookingId enforces 1 genuine rating per completed trip
        val ratingRef = db.collection("ratings").document(booking.id)
        val bookingRef = db.collection("bookings").document(booking.id)

        db.runTransaction { tx ->
            val existingRating = tx.get(ratingRef)
            check(!existingRating.exists()) { "You have already rated this completed ride." }
            tx.set(
                ratingRef,
                mapOf(
                    "bookingId" to booking.id,
                    "customerId" to uid,
                    "driverId" to booking.assignedDriverId,
                    "stars" to overall,
                    "behaviourStars" to b,
                    "vehicleStars" to v,
                    "punctualityStars" to p,
                    "safetyStars" to s,
                    "review" to review.trim().take(500),
                    "moderationStatus" to "active",
                    "flaggedReason" to "",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            tx.update(
                bookingRef,
                mapOf(
                    "customerRating" to overall,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }.await()

        recalculateGenuineDriverRating(booking.assignedDriverId)
    }

    suspend fun submitRiderToCustomerFeedback(
        booking: Booking,
        stars: Int,
        note: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(booking.assignedDriverId == uid) { "Only the assigned driver can rate this trip's passenger." }
        require(booking.status == "completed") { "Feedback allowed only on completed trips." }
        val ref = db.collection("ratings").document(booking.id)
        val snap = ref.get().await()
        if (snap.exists()) {
            ref.update(
                mapOf(
                    "riderToCustomerStars" to stars.coerceIn(1, 5).toLong(),
                    "riderToCustomerNote" to note.trim().take(300)
                )
            ).await()
        } else {
            ref.set(
                mapOf(
                    "bookingId" to booking.id,
                    "customerId" to booking.customerId,
                    "driverId" to uid,
                    "stars" to 5L,
                    "behaviourStars" to 5L,
                    "vehicleStars" to 5L,
                    "punctualityStars" to 5L,
                    "safetyStars" to 5L,
                    "review" to "",
                    "riderToCustomerStars" to stars.coerceIn(1, 5).toLong(),
                    "riderToCustomerNote" to note.trim().take(300),
                    "moderationStatus" to "active",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    suspend fun recalculateGenuineDriverRating(driverId: String) {
        val snap = runCatching {
            db.collection("ratings").whereEqualTo("driverId", driverId).get().await()
        }.getOrNull() ?: return
        val ratings = snap.documents.mapNotNull { it.toObject(RatingRecord::class.java) }
        val (avg, count) = CancellationAndFraudEngine.computeWeightedRatingAggregate(ratings, driverId)
        runCatching {
            db.collection("driver_profiles").document(driverId)
                .update(mapOf("ratingAvg" to avg, "totalRatingCount" to count))
                .await()
        }
        runCatching {
            db.collection("driver_directory").document(driverId)
                .update("ratingAvg", avg)
                .await()
        }
    }

    fun observeAllRatings(): Flow<List<RatingRecord>> =
        safeQueryFlow("ratings", db.collection("ratings")) {
            it.toObject(RatingRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    suspend fun adminModerateRating(ratingId: String, driverId: String, status: String, reason: String): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        db.collection("ratings").document(ratingId)
            .update(
                mapOf(
                    "moderationStatus" to status,
                    "flaggedReason" to reason.trim()
                )
            ).await()
        if (driverId.isNotBlank()) recalculateGenuineDriverRating(driverId)
        db.collection("audit_logs").document().set(
            mapOf(
                "actorId" to actorUid,
                "actorEmail" to (auth.currentUser?.email ?: actorUid),
                "action" to "MODERATE_RATING_${status.uppercase()}",
                "targetCollection" to "ratings",
                "targetId" to ratingId,
                "summary" to "Rating $ratingId set to $status: ${reason.trim()}",
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    fun observeMyDisputes(uid: String): Flow<List<DisputeRecord>> =
        safeQueryFlow(
            "disputes",
            db.collection("disputes").whereEqualTo("reporterId", uid)
        ) {
            it.toObject(DisputeRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAllDisputes(): Flow<List<DisputeRecord>> =
        safeQueryFlow("disputes", db.collection("disputes")) {
            it.toObject(DisputeRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    suspend fun submitDispute(
        bookingId: String,
        busBookingId: String = "",
        reporterRole: String,
        targetUserId: String,
        category: String,
        description: String,
        requestedRefundInr: Double
    ): Result<String> = runCatching {
        val uid = requireUserId()
        require(description.trim().length >= 8) { "Please describe the dispute issue (min 8 chars)." }
        val ref = db.collection("disputes").document()
        ref.set(
            mapOf(
                "bookingId" to bookingId.trim(),
                "busBookingId" to busBookingId.trim(),
                "reporterId" to uid,
                "reporterRole" to reporterRole,
                "targetUserId" to targetUserId.trim(),
                "category" to category.trim(),
                "description" to description.trim().take(800),
                "requestedRefundInr" to requestedRefundInr.coerceAtLeast(0.0),
                "status" to "open",
                "adminResolutionNote" to "",
                "resolvedBy" to "",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        ref.id
    }

    suspend fun adminResolveDispute(
        dispute: DisputeRecord,
        newStatus: String,
        resolutionNote: String,
        approvedRefundInr: Double = 0.0
    ): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val ref = db.collection("disputes").document(dispute.id)
        db.runBatch { batch ->
            batch.update(
                ref,
                mapOf(
                    "status" to newStatus,
                    "adminResolutionNote" to resolutionNote.trim(),
                    "resolvedBy" to actorUid,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            if (newStatus == "resolved_refund" && approvedRefundInr > 0.0) {
                batch.set(
                    db.collection("refunds").document(),
                    mapOf(
                        "bookingId" to (dispute.bookingId.ifBlank { dispute.busBookingId }),
                        "customerId" to dispute.reporterId,
                        "amountInr" to approvedRefundInr,
                        "reason" to "Dispute refund: ${resolutionNote.trim()}",
                        "status" to "approved",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.set(
                db.collection("notifications").document(),
                mapOf(
                    "userId" to dispute.reporterId,
                    "title" to "Dispute Update: ${newStatus.replace('_', ' ').uppercase()}",
                    "body" to resolutionNote.trim().ifBlank { "Your dispute case has been updated by GoLocal Support." },
                    "type" to "dispute",
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
        }.await()
    }
}
