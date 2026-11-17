package com.example.data.repository

import com.example.data.model.BusBooking
import com.example.data.model.BusOperator
import com.example.data.model.BusRoute
import com.example.data.model.BusSchedule
import com.example.data.model.BusSeatLock
import com.example.data.model.BusVehicle
import com.example.data.model.DriverDocumentItem
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
import java.security.MessageDigest
import java.util.UUID

class BusRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
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

    // --- Real-time Observers ---

    fun observeMyBusOperator(uid: String): Flow<BusOperator?> =
        db.collection("bus_operators").document(uid).snapshots()
            .map { it.toObject(BusOperator::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .catch { err ->
                if (err is CancellationException && err.unwrapFirestoreError() == null) throw err
                emit(null)
            }

    fun observeAllBusOperators(): Flow<List<BusOperator>> =
        safeQueryFlow("bus_operators", db.collection("bus_operators")) {
            it.toObject(BusOperator::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeBusVehicles(operatorId: String? = null): Flow<List<BusVehicle>> {
        val q = if (!operatorId.isNullOrBlank()) {
            db.collection("bus_vehicles").whereEqualTo("operatorId", operatorId)
        } else {
            db.collection("bus_vehicles")
        }
        return safeQueryFlow("bus_vehicles", q) {
            it.toObject(BusVehicle::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }
    }

    fun observeBusRoutes(operatorId: String? = null): Flow<List<BusRoute>> {
        val q = if (!operatorId.isNullOrBlank()) {
            db.collection("bus_routes").whereEqualTo("operatorId", operatorId)
        } else {
            db.collection("bus_routes")
        }
        return safeQueryFlow("bus_routes", q) {
            it.toObject(BusRoute::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }
    }

    fun observeApprovedBusSchedules(): Flow<List<BusSchedule>> =
        safeQueryFlow(
            "bus_schedules",
            db.collection("bus_schedules").whereEqualTo("fareApprovalStatus", "approved")
        ) {
            it.toObject(BusSchedule::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeOperatorBusSchedules(operatorId: String): Flow<List<BusSchedule>> =
        safeQueryFlow(
            "bus_schedules",
            db.collection("bus_schedules").whereEqualTo("operatorId", operatorId)
        ) {
            it.toObject(BusSchedule::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAllBusSchedules(): Flow<List<BusSchedule>> =
        safeQueryFlow("bus_schedules", db.collection("bus_schedules")) {
            it.toObject(BusSchedule::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeSeatLocksForSchedule(scheduleId: String): Flow<List<BusSeatLock>> =
        safeQueryFlow(
            "bus_seat_locks",
            db.collection("bus_seat_locks").whereEqualTo("scheduleId", scheduleId)
        ) {
            it.toObject(BusSeatLock::class.java)
        }

    fun observeMyBusBookings(uid: String): Flow<List<BusBooking>> =
        safeQueryFlow(
            "bus_bookings",
            db.collection("bus_bookings").whereEqualTo("customerId", uid)
        ) {
            it.toObject(BusBooking::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeOperatorBusBookings(operatorId: String): Flow<List<BusBooking>> =
        safeQueryFlow(
            "bus_bookings",
            db.collection("bus_bookings").whereEqualTo("operatorId", operatorId)
        ) {
            it.toObject(BusBooking::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    // --- Operator & Admin Onboarding / Fare Governance ---

    suspend fun submitBusOperatorOnboarding(
        companyName: String,
        contactPhone: String,
        operatorLicenseNumber: String,
        permitNumber: String,
        district: String,
        upiOrBankRef: String,
        documents: List<DriverDocumentItem>
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(companyName.trim().length >= 2) { "Company / Bus Operator name is required." }
        require(permitNumber.trim().length >= 4) { "Stage Carriage / Operator Permit number is required." }
        db.collection("bus_operators").document(uid).set(
            mapOf(
                "operatorId" to uid,
                "ownerUid" to uid,
                "companyName" to companyName.trim(),
                "contactPhone" to contactPhone.trim(),
                "operatorLicenseNumber" to operatorLicenseNumber.trim(),
                "permitNumber" to permitNumber.trim(),
                "district" to district.trim(),
                "upiOrBankRef" to upiOrBankRef.trim(),
                "documents" to documents,
                "verificationStatus" to "pending",
                "rejectionReason" to "",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun adminVerifyBusOperator(operatorId: String, status: String, reason: String): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        if (status != "verified") require(reason.trim().length >= 5) { "Rejection reason required." }
        db.runBatch { batch ->
            batch.update(
                db.collection("bus_operators").document(operatorId),
                mapOf(
                    "verificationStatus" to status,
                    "rejectionReason" to if (status == "verified") "" else reason.trim(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                db.collection("audit_logs").document(),
                mapOf(
                    "actorId" to actorUid,
                    "actorEmail" to (auth.currentUser?.email ?: actorUid),
                    "action" to "BUS_OPERATOR_${status.uppercase()}",
                    "targetCollection" to "bus_operators",
                    "targetId" to operatorId,
                    "summary" to "Bus operator $operatorId -> $status ($reason)",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
        }.await()
    }

    suspend fun registerBusVehicle(
        busName: String,
        registrationNumber: String,
        busType: String,
        totalSeats: Int,
        documents: List<DriverDocumentItem>
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(busName.trim().length >= 2) { "Bus display name is required." }
        require(registrationNumber.trim().length >= 5) { "Valid bus registration plate is required." }
        val ref = db.collection("bus_vehicles").document()
        ref.set(
            mapOf(
                "operatorId" to uid,
                "busName" to busName.trim(),
                "registrationNumber" to registrationNumber.trim().uppercase(),
                "busType" to busType.trim(),
                "totalSeats" to totalSeats.coerceIn(12, 60),
                "layoutCols" to 4,
                "amenityTags" to listOf("Verified Permit", "QR Ticket"),
                "documents" to documents,
                "verificationStatus" to "pending",
                "rejectionReason" to "",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun adminVerifyBusVehicle(busId: String, status: String, reason: String): Result<Unit> = runCatching {
        requireUserId()
        if (status != "verified") require(reason.trim().length >= 5) { "Rejection reason required." }
        db.collection("bus_vehicles").document(busId).update(
            mapOf(
                "verificationStatus" to status,
                "rejectionReason" to if (status == "verified") "" else reason.trim(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun createBusRoute(
        originTown: String,
        destinationTown: String,
        stops: List<String>,
        distanceKm: Double,
        farePerSeatInr: Double,
        fareSource: String,
        authorityRef: String,
        effectiveDateIso: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val fareErr = BusFareEngine.validateFareSubmission(farePerSeatInr, fareSource, authorityRef, effectiveDateIso)
        require(fareErr == null) { fareErr ?: "Invalid bus tariff" }
        require(originTown.isNotBlank() && destinationTown.isNotBlank()) { "Origin and destination are required." }

        val ref = db.collection("bus_routes").document()
        ref.set(
            mapOf(
                "operatorId" to uid,
                "routeName" to "${originTown.trim()} -> ${destinationTown.trim()}",
                "originTown" to originTown.trim(),
                "destinationTown" to destinationTown.trim(),
                "stops" to stops.map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf(originTown.trim(), destinationTown.trim()) },
                "distanceKm" to distanceKm.coerceAtLeast(1.0),
                "farePerSeatInr" to farePerSeatInr,
                "fareSource" to fareSource,
                "authorityRef" to authorityRef.trim(),
                "effectiveDateIso" to effectiveDateIso.trim(),
                "fareApprovalStatus" to "pending_fare_approval",
                "fareRejectionReason" to "",
                "gstPercent" to 5.0,
                "commissionPercent" to 8.0,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        ref.id
    }

    suspend fun createBusSchedule(
        route: BusRoute,
        vehicle: BusVehicle,
        departureDateIso: String,
        departureTimeLabel: String,
        arrivalTimeLabel: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        require(route.operatorId == uid && vehicle.operatorId == uid) { "Operator owns route and vehicle check failed." }
        require(vehicle.verificationStatus == "verified") { "Bus vehicle must be verified by Admin before scheduling trips." }
        require(Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(departureDateIso.trim())) { "Departure date must be YYYY-MM-DD." }

        val ref = db.collection("bus_schedules").document()
        ref.set(
            mapOf(
                "routeId" to route.id,
                "operatorId" to uid,
                "busId" to vehicle.id,
                "busName" to "${vehicle.busName} (${vehicle.registrationNumber})",
                "busType" to vehicle.busType,
                "routeLabel" to route.routeName,
                "originTown" to route.originTown,
                "destinationTown" to route.destinationTown,
                "stops" to route.stops,
                "departureDateIso" to departureDateIso.trim(),
                "departureTimeLabel" to departureTimeLabel.trim(),
                "arrivalTimeLabel" to arrivalTimeLabel.trim(),
                "totalSeats" to vehicle.totalSeats,
                "bookedSeatIds" to emptyList<String>(),
                "farePerSeatInr" to route.farePerSeatInr,
                "fareSource" to route.fareSource,
                "authorityRef" to route.authorityRef,
                "effectiveDateIso" to route.effectiveDateIso,
                "fareApprovalStatus" to route.fareApprovalStatus,
                "gstPercent" to route.gstPercent,
                "commissionPercent" to route.commissionPercent,
                "status" to "scheduled",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        ref.id
    }

    suspend fun adminApproveOrRejectBusRouteFare(
        routeId: String,
        approve: Boolean,
        reason: String
    ): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        if (!approve) require(reason.trim().length >= 5) { "Rejection reason required." }
        val newStatus = if (approve) "approved" else "rejected"
        val matchingSchedules = db.collection("bus_schedules").whereEqualTo("routeId", routeId).get().await()

        db.runBatch { batch ->
            batch.update(
                db.collection("bus_routes").document(routeId),
                mapOf(
                    "fareApprovalStatus" to newStatus,
                    "fareRejectionReason" to if (approve) "" else reason.trim(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            matchingSchedules.documents.forEach { schedDoc ->
                batch.update(
                    schedDoc.reference,
                    mapOf(
                        "fareApprovalStatus" to newStatus,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.set(
                db.collection("audit_logs").document(),
                mapOf(
                    "actorId" to actorUid,
                    "actorEmail" to (auth.currentUser?.email ?: actorUid),
                    "action" to "BUS_FARE_${newStatus.uppercase()}",
                    "targetCollection" to "bus_routes",
                    "targetId" to routeId,
                    "summary" to "Bus tariff $routeId -> $newStatus (${reason.trim()})",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
        }.await()
    }

    // --- Atomic Seat Reservation, Booking, QR Validation & Cancellation ---

    suspend fun reserveBusSeatsAndBook(
        scheduleId: String,
        customerName: String,
        boardingStop: String,
        dropStop: String,
        selectedSeatIds: List<String>,
        paymentMethod: String
    ): Result<BusBooking> = runCatching {
        val uid = requireUserId()
        val cleanSeats = selectedSeatIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        require(cleanSeats.isNotEmpty() && cleanSeats.size <= 6) { "Select between 1 and 6 seats." }

        val schedRef = db.collection("bus_schedules").document(scheduleId)
        val ticketRef = db.collection("bus_bookings").document()
        val nowMs = System.currentTimeMillis()
        val qrToken = buildQrToken(ticketRef.id, scheduleId, uid)

        val bookingResult = db.runTransaction { tx ->
            val schedSnap = tx.get(schedRef)
            val schedule = schedSnap.toObject(BusSchedule::class.java)
                ?: error("Bus schedule not found")
            check(BusFareEngine.canBookSchedule(schedule)) {
                "Bus fare is pending Admin verification or trip is no longer accepting bookings."
            }
            val alreadyBooked = schedule.bookedSeatIds.toSet()
            cleanSeats.forEach { seatId ->
                check(seatId !in alreadyBooked) { "Seat $seatId was just booked by another passenger." }
                val lockRef = db.collection("bus_seat_locks").document("${scheduleId}_${seatId}")
                val lockSnap = tx.get(lockRef)
                if (lockSnap.exists()) {
                    val lock = lockSnap.toObject(BusSeatLock::class.java)
                    val activeLock = lock != null && (
                        lock.status == "booked" ||
                            (lock.lockedUntilEpochMs > nowMs && lock.lockedByUid != uid)
                        )
                    check(!activeLock) { "Seat $seatId is currently held by another passenger." }
                }
            }

            val breakdown = BusFareEngine.calculateBreakdown(
                farePerSeatInr = schedule.farePerSeatInr,
                seatCount = cleanSeats.size,
                gstPercent = schedule.gstPercent,
                commissionPercent = schedule.commissionPercent
            )

            cleanSeats.forEach { seatId ->
                val lockRef = db.collection("bus_seat_locks").document("${scheduleId}_${seatId}")
                tx.set(
                    lockRef,
                    mapOf(
                        "scheduleId" to scheduleId,
                        "seatId" to seatId,
                        "lockedByUid" to uid,
                        "lockedUntilEpochMs" to Long.MAX_VALUE / 2,
                        "status" to "booked"
                    )
                )
            }

            tx.update(
                schedRef,
                mapOf(
                    "bookedSeatIds" to FieldValue.arrayUnion(*cleanSeats.toTypedArray()),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            val ticketMap = mapOf(
                "customerId" to uid,
                "customerName" to customerName.ifBlank { "GoLocal Passenger" },
                "scheduleId" to scheduleId,
                "routeId" to schedule.routeId,
                "operatorId" to schedule.operatorId,
                "busName" to schedule.busName,
                "routeLabel" to schedule.routeLabel,
                "departureDateIso" to schedule.departureDateIso,
                "departureTimeLabel" to schedule.departureTimeLabel,
                "boardingStop" to boardingStop.ifBlank { schedule.originTown },
                "dropStop" to dropStop.ifBlank { schedule.destinationTown },
                "seatIds" to cleanSeats,
                "seatCount" to cleanSeats.size,
                "farePerSeatInr" to breakdown.farePerSeatInr,
                "baseFareInr" to breakdown.baseFareInr,
                "gstInr" to breakdown.gstInr,
                "platformCommissionInr" to breakdown.platformCommissionInr,
                "netOperatorPayoutInr" to breakdown.netOperatorPayoutInr,
                "totalPaidInr" to breakdown.totalCustomerPayableInr,
                "fareSource" to schedule.fareSource,
                "authorityRef" to schedule.authorityRef,
                "qrToken" to qrToken,
                "qrValidated" to false,
                "paymentMethod" to paymentMethod,
                "status" to "confirmed",
                "cancellationFeeInr" to 0.0,
                "refundAmountInr" to 0.0,
                "cancellationReason" to "",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            tx.set(ticketRef, ticketMap)

            BusBooking(
                id = ticketRef.id,
                customerId = uid,
                customerName = customerName,
                scheduleId = scheduleId,
                routeId = schedule.routeId,
                operatorId = schedule.operatorId,
                busName = schedule.busName,
                routeLabel = schedule.routeLabel,
                departureDateIso = schedule.departureDateIso,
                departureTimeLabel = schedule.departureTimeLabel,
                boardingStop = boardingStop.ifBlank { schedule.originTown },
                dropStop = dropStop.ifBlank { schedule.destinationTown },
                seatIds = cleanSeats,
                seatCount = cleanSeats.size,
                farePerSeatInr = breakdown.farePerSeatInr,
                baseFareInr = breakdown.baseFareInr,
                gstInr = breakdown.gstInr,
                platformCommissionInr = breakdown.platformCommissionInr,
                netOperatorPayoutInr = breakdown.netOperatorPayoutInr,
                totalPaidInr = breakdown.totalCustomerPayableInr,
                fareSource = schedule.fareSource,
                authorityRef = schedule.authorityRef,
                qrToken = qrToken,
                qrValidated = false,
                paymentMethod = paymentMethod,
                status = "confirmed"
            )
        }.await()

        bookingResult
    }

    suspend fun cancelBusBooking(ticketId: String, reason: String): Result<Double> = runCatching {
        val uid = requireUserId()
        val ticketRef = db.collection("bus_bookings").document(ticketId)
        val refund = db.runTransaction { tx ->
            val snap = tx.get(ticketRef)
            val ticket = snap.toObject(BusBooking::class.java) ?: error("Ticket not found")
            check(ticket.customerId == uid) { "Only ticket owner can cancel." }
            check(ticket.status == "confirmed") { "Only confirmed tickets can be cancelled." }

            val (fee, refundAmt) = BusFareEngine.evaluateBusCancellationRefund(ticket.totalPaidInr, hoursBeforeDeparture = 12)
            val schedRef = db.collection("bus_schedules").document(ticket.scheduleId)
            tx.update(
                schedRef,
                mapOf(
                    "bookedSeatIds" to FieldValue.arrayRemove(*ticket.seatIds.toTypedArray()),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            ticket.seatIds.forEach { seatId ->
                tx.delete(db.collection("bus_seat_locks").document("${ticket.scheduleId}_${seatId}"))
            }
            tx.update(
                ticketRef,
                mapOf(
                    "status" to "cancelled",
                    "cancellationFeeInr" to fee,
                    "refundAmountInr" to refundAmt,
                    "cancellationReason" to reason.trim().ifBlank { "Passenger cancelled" },
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            if (refundAmt > 0.0) {
                tx.set(
                    db.collection("refunds").document(),
                    mapOf(
                        "bookingId" to ticketId,
                        "customerId" to uid,
                        "amountInr" to refundAmt,
                        "reason" to "Bus ticket cancellation refund ($reason)",
                        "status" to "requested",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            refundAmt
        }.await()
        refund
    }

    suspend fun validateBusTicketQr(qrTokenOrId: String): Result<BusBooking> = runCatching {
        val uid = requireUserId()
        val queryStr = qrTokenOrId.trim()
        require(queryStr.isNotBlank()) { "Enter ticket ID or scan QR payload." }

        val snap = db.collection("bus_bookings")
            .whereEqualTo("operatorId", uid)
            .whereEqualTo("qrToken", queryStr)
            .get()
            .await()

        val doc = snap.documents.firstOrNull() ?: run {
            val byId = db.collection("bus_bookings").document(queryStr).get().await()
            if (byId.exists()) byId else null
        } ?: error("Bus ticket not found for this operator.")

        val booking = doc.toObject(BusBooking::class.java) ?: error("Invalid ticket format.")
        require(booking.operatorId == uid) { "Ticket belongs to another bus operator." }
        require(booking.status == "confirmed") {
            if (booking.qrValidated || booking.status == "boarded") "Ticket already validated & boarded!"
            else "Ticket is ${booking.status} and invalid for boarding."
        }

        doc.reference.update(
            mapOf(
                "qrValidated" to true,
                "status" to "boarded",
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()

        booking.copy(qrValidated = true, status = "boarded")
    }

    private fun buildQrToken(ticketId: String, scheduleId: String, uid: String): String {
        val seed = "$ticketId:$scheduleId:$uid:${UUID.randomUUID()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        val sig = digest.take(6).joinToString("") { "%02x".format(it) }.uppercase()
        return "GLBUS-${ticketId.take(6).uppercase()}-$sig"
    }
}
