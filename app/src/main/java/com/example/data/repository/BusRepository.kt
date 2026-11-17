package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.BusBooking
import com.example.data.model.BusOperator
import com.example.data.model.BusRoute
import com.example.data.model.BusSchedule
import com.example.domain.BusFareEngine
import com.google.firebase.Firebase
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
import kotlin.random.Random

class BusRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = Firebase.auth
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in before accessing bus bookings.")
    }

    private fun <T> safeQueryFlow(
        path: String,
        query: Query,
        mapper: (DocumentSnapshot) -> T?
    ): Flow<List<T>> = flow {
        val uid = requireUserId()
        emitAll(
            query.snapshots()
                .map { snapshot -> snapshot.documents.mapNotNull(mapper) }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(
                            fe ?: (error as? Exception ?: Exception(error)),
                            OperationType.LIST,
                            path,
                            auth
                        )
                    }
                    throw error
                }
        )
    }

    fun observeActiveBusRoutes(): Flow<List<BusRoute>> =
        safeQueryFlow(
            "bus_routes",
            db.collection("bus_routes").whereEqualTo("status", "active")
        ) { it.toObject(BusRoute::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }

    fun observeAllBusRoutesAdmin(): Flow<List<BusRoute>> =
        safeQueryFlow("bus_routes", db.collection("bus_routes")) {
            it.toObject(BusRoute::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeBusOperatorsAdmin(): Flow<List<BusOperator>> =
        safeQueryFlow("bus_operators", db.collection("bus_operators")) {
            it.toObject(BusOperator::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeVerifiedBusOperators(): Flow<List<BusOperator>> =
        safeQueryFlow(
            "bus_operators",
            db.collection("bus_operators").whereEqualTo("verificationStatus", "verified")
        ) { it.toObject(BusOperator::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }

    fun observeBusSchedules(): Flow<List<BusSchedule>> =
        safeQueryFlow("bus_schedules", db.collection("bus_schedules")) {
            it.toObject(BusSchedule::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeCustomerBusBookings(uid: String): Flow<List<BusBooking>> =
        safeQueryFlow(
            "bus_bookings",
            db.collection("bus_bookings").whereEqualTo("customerId", uid)
        ) { it.toObject(BusBooking::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }

    fun observeAdminBusBookings(): Flow<List<BusBooking>> =
        safeQueryFlow("bus_bookings", db.collection("bus_bookings")) {
            it.toObject(BusBooking::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    suspend fun bookSeatsAtomically(
        schedule: BusSchedule,
        customerName: String,
        selectedSeats: List<String>,
        paymentMethod: String
    ): Result<BusBooking> = runCatching {
        val uid = requireUserId()
        val distinctSeats = selectedSeats.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        require(schedule.status == "scheduled") {
            "This bus departure is no longer open for seat reservations."
        }
        val quote = BusFareEngine.calculateBookingQuote(
            farePerSeatInr = schedule.farePerSeatInr,
            seatCount = distinctSeats.size,
            gstPercent = schedule.gstPercent,
            commissionPercent = schedule.commissionPercent,
            fareSource = schedule.fareSource,
            effectiveDateLabel = schedule.effectiveDateLabel
        )

        val scheduleRef = db.collection("bus_schedules").document(schedule.id)
        val bookingRef = db.collection("bus_bookings").document()
        val bookingRefCode = "GLBUS-${Random.nextInt(100000, 999999)}"
        val qrPayload = BusFareEngine.buildQrTicketPayload(
            bookingId = bookingRef.id,
            bookingRef = bookingRefCode,
            scheduleId = schedule.id,
            seatNumbers = distinctSeats
        )
        val routeLabel = "${schedule.originStop} -> ${schedule.destinationStop}"

        try {
            db.runTransaction { tx ->
                val snap = tx.get(scheduleRef)
                val status = snap.getString("status") ?: ""
                if (status != "scheduled") {
                    throw IllegalStateException("Bus schedule is no longer available.")
                }
                val totalSeats = (snap.getLong("totalSeats") ?: 40L).toInt()
                @Suppress("UNCHECKED_CAST")
                val currentBooked = (snap.get("bookedSeats") as? List<*>)
                    ?.mapNotNull { it?.toString() }
                    .orEmpty()
                val conflict = distinctSeats.firstOrNull { it in currentBooked }
                if (conflict != null) {
                    throw IllegalStateException("Seat $conflict was just booked by another passenger.")
                }
                val updatedSeats = (currentBooked + distinctSeats).distinct()
                if (updatedSeats.size > totalSeats) {
                    throw IllegalStateException("Not enough seats remaining on this bus.")
                }

                tx.update(
                    scheduleRef,
                    mapOf(
                        "bookedSeats" to updatedSeats,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                tx.set(
                    bookingRef,
                    mapOf(
                        "bookingRef" to bookingRefCode,
                        "customerId" to uid,
                        "customerName" to customerName.trim().ifBlank { "GoLocal Passenger" }.take(100),
                        "scheduleId" to schedule.id,
                        "routeId" to schedule.routeId,
                        "operatorId" to schedule.operatorId,
                        "operatorName" to schedule.operatorName,
                        "routeLabel" to routeLabel,
                        "travelDateLabel" to schedule.travelDateLabel,
                        "departureTimeLabel" to schedule.departureTimeLabel,
                        "seatNumbers" to distinctSeats.sorted(),
                        "seatCount" to distinctSeats.size.toLong(),
                        "totalFareInr" to quote.totalFareInr,
                        "gstInr" to quote.gstInr,
                        "commissionInr" to quote.platformCommissionInr,
                        "paymentMethod" to paymentMethod.ifBlank { "cash" },
                        "paymentStatus" to if (paymentMethod == "cash") "pay_on_boarding" else "pending_verification",
                        "status" to "confirmed",
                        "qrPayload" to qrPayload,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, bookingRef.path, auth)
            throw e
        }

        BusBooking(
            id = bookingRef.id,
            bookingRef = bookingRefCode,
            customerId = uid,
            customerName = customerName.trim().ifBlank { "GoLocal Passenger" },
            scheduleId = schedule.id,
            routeId = schedule.routeId,
            operatorId = schedule.operatorId,
            operatorName = schedule.operatorName,
            routeLabel = routeLabel,
            travelDateLabel = schedule.travelDateLabel,
            departureTimeLabel = schedule.departureTimeLabel,
            seatNumbers = distinctSeats.sorted(),
            seatCount = distinctSeats.size.toLong(),
            totalFareInr = quote.totalFareInr,
            gstInr = quote.gstInr,
            commissionInr = quote.platformCommissionInr,
            paymentMethod = paymentMethod.ifBlank { "cash" },
            paymentStatus = if (paymentMethod == "cash") "pay_on_boarding" else "pending_verification",
            status = "confirmed",
            qrPayload = qrPayload
        )
    }

    suspend fun cancelBusBooking(booking: BusBooking, reason: String): Result<Unit> = runCatching {
        requireUserId()
        val ref = db.collection("bus_bookings").document(booking.id)
        try {
            ref.update(
                mapOf(
                    "status" to "cancelled",
                    "cancellationReason" to reason.trim().ifBlank { "Cancelled by passenger" }.take(250),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path, auth)
            throw e
        }
    }

    // --- Admin Bus Operations ---

    suspend fun adminSaveBusOperator(operator: BusOperator): Result<String> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val isNew = operator.id.isBlank()
        val ref = if (isNew) db.collection("bus_operators").document() else db.collection("bus_operators").document(operator.id)
        val auditRef = db.collection("audit_logs").document()
        val payload = buildMap<String, Any> {
            put("operatorName", operator.operatorName.trim())
            if (operator.ownerUid.isNotBlank()) put("ownerUid", operator.ownerUid.trim())
            put("contactPhone", operator.contactPhone.trim())
            put("district", operator.district.trim())
            put("permitNumber", operator.permitNumber.trim().uppercase())
            put("insuranceNumber", operator.insuranceNumber.trim())
            put("fitnessCertificateNumber", operator.fitnessCertificateNumber.trim().uppercase())
            put("pucNumber", operator.pucNumber.trim().uppercase())
            put("verificationStatus", operator.verificationStatus)
            if (operator.rejectionReason.isNotBlank()) put("rejectionReason", operator.rejectionReason.trim())
            if (isNew) put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            db.runBatch { batch ->
                if (isNew) batch.set(ref, payload) else batch.update(ref, payload)
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "SAVE_BUS_OPERATOR",
                        "targetCollection" to "bus_operators",
                        "targetId" to ref.id,
                        "summary" to "Saved bus operator ${operator.operatorName} (${operator.verificationStatus})",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path, auth)
            throw e
        }
        ref.id
    }

    suspend fun adminSaveBusRoute(route: BusRoute, activateRequested: Boolean): Result<String> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val effectiveStatus = BusFareEngine.resolveRouteStatusForAdmin(route, activateRequested)
        require(!activateRequested || effectiveStatus == "active") {
            "Route remains in Pending Pricing until official Fare Source, Effective Date, distance (>0), and base fare (>0) are configured."
        }
        val isNew = route.id.isBlank()
        val ref = if (isNew) db.collection("bus_routes").document() else db.collection("bus_routes").document(route.id)
        val auditRef = db.collection("audit_logs").document()
        val payload = buildMap<String, Any> {
            put("operatorId", route.operatorId)
            put("operatorName", route.operatorName.trim())
            put("routeName", route.routeName.trim())
            put("originStop", route.originStop.trim())
            put("destinationStop", route.destinationStop.trim())
            put("intermediateStops", route.intermediateStops)
            put("distanceKm", route.distanceKm)
            put("fareSource", route.fareSource.trim())
            put("effectiveDateLabel", route.effectiveDateLabel.trim())
            put("baseSeatFareInr", route.baseSeatFareInr)
            put("perKmSeatRateInr", route.perKmSeatRateInr)
            put("gstPercent", route.gstPercent)
            put("commissionPercent", route.commissionPercent)
            put("status", effectiveStatus)
            if (isNew) put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            db.runBatch { batch ->
                if (isNew) batch.set(ref, payload) else batch.update(ref, payload)
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "SAVE_BUS_ROUTE",
                        "targetCollection" to "bus_routes",
                        "targetId" to ref.id,
                        "summary" to "Saved bus route ${route.routeName} ($effectiveStatus, tariff: ${route.fareSource})",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path, auth)
            throw e
        }
        ref.id
    }

    suspend fun adminCreateBusSchedule(schedule: BusSchedule, approvedRoute: BusRoute): Result<String> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        require(approvedRoute.status == "active" && BusFareEngine.isRoutePricingConfigured(approvedRoute)) {
            "Schedules require an active bus route with official tariff source and effective date."
        }
        val computedFare = BusFareEngine.calculateSeatFareInr(approvedRoute)
        val ref = db.collection("bus_schedules").document()
        val auditRef = db.collection("audit_logs").document()
        val safeSeats = schedule.totalSeats.coerceIn(10L, 80L)
        val payload = mapOf(
            "routeId" to approvedRoute.id,
            "operatorId" to approvedRoute.operatorId,
            "operatorName" to approvedRoute.operatorName,
            "busLabel" to schedule.busLabel.trim().ifBlank { "${approvedRoute.operatorName} Express" },
            "busType" to schedule.busType.ifBlank { "non_ac_seater" },
            "originStop" to approvedRoute.originStop,
            "destinationStop" to approvedRoute.destinationStop,
            "departureTimeLabel" to schedule.departureTimeLabel.trim(),
            "arrivalTimeLabel" to schedule.arrivalTimeLabel.trim(),
            "travelDateLabel" to schedule.travelDateLabel.trim(),
            "totalSeats" to safeSeats,
            "bookedSeats" to emptyList<String>(),
            "farePerSeatInr" to computedFare,
            "fareSource" to approvedRoute.fareSource,
            "effectiveDateLabel" to approvedRoute.effectiveDateLabel,
            "gstPercent" to approvedRoute.gstPercent,
            "commissionPercent" to approvedRoute.commissionPercent,
            "status" to "scheduled",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            db.runBatch { batch ->
                batch.set(ref, payload)
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "CREATE_BUS_SCHEDULE",
                        "targetCollection" to "bus_schedules",
                        "targetId" to ref.id,
                        "summary" to "Scheduled ${approvedRoute.routeName} on ${schedule.travelDateLabel} ${schedule.departureTimeLabel} at ₹${computedFare.toInt()}/seat",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path, auth)
            throw e
        }
        ref.id
    }

    suspend fun adminReleaseCancelledBusSeats(booking: BusBooking): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val bookingRef = db.collection("bus_bookings").document(booking.id)
        val scheduleRef = db.collection("bus_schedules").document(booking.scheduleId)
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runTransaction { tx ->
                val schSnap = tx.get(scheduleRef)
                @Suppress("UNCHECKED_CAST")
                val existingBooked = (schSnap.get("bookedSeats") as? List<*>)
                    ?.mapNotNull { it?.toString() }
                    .orEmpty()
                val updatedBooked = existingBooked.filterNot { it in booking.seatNumbers }
                tx.update(scheduleRef, mapOf("bookedSeats" to updatedBooked, "updatedAt" to FieldValue.serverTimestamp()))
                tx.update(bookingRef, mapOf("status" to "refunded", "paymentStatus" to "refunded", "updatedAt" to FieldValue.serverTimestamp()))
                tx.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "REFUND_AND_RELEASE_BUS_SEATS",
                        "targetCollection" to "bus_bookings",
                        "targetId" to booking.id,
                        "summary" to "Refunded ${booking.bookingRef} and released seats ${booking.seatNumbers.joinToString()}",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, bookingRef.path, auth)
            throw e
        }
    }
}
