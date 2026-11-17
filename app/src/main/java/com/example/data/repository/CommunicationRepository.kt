package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.Booking
import com.example.data.model.BookingCallContact
import com.example.data.model.BookingChatMessage
import com.example.domain.BookingChatValidator
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class CommunicationRepository(
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
            ?: throw IllegalStateException("User must be signed in before accessing trip communication.")
    }

    fun observeMessages(bookingId: String): Flow<List<BookingChatMessage>> = flow {
        if (bookingId.isBlank()) {
            emit(emptyList())
            return@flow
        }
        val uid = requireUserId()
        val query = db.collection("booking_chats")
            .document(bookingId)
            .collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
        emitAll(
            query.snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            BookingChatMessage::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(
                            fe ?: (error as? Exception ?: Exception(error)),
                            OperationType.LIST,
                            "booking_chats/$bookingId/messages",
                            auth
                        )
                    }
                    throw error
                }
        )
    }

    suspend fun sendMessage(
        booking: Booking,
        senderRole: String,
        senderName: String,
        rawText: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        require(BookingChatValidator.isChatWriteAllowed(booking.status)) {
            "Trip chat is locked because this booking is ${booking.status}."
        }
        val validationError = BookingChatValidator.validateMessageText(rawText)
        require(validationError == null) { validationError ?: "Invalid message." }
        require(booking.assignedDriverId.isNotBlank()) {
            "Chat becomes available once a driver is assigned."
        }

        val cleanText = rawText.trim().take(BookingChatValidator.MAX_MESSAGE_LENGTH)
        val normalizedRole = if (senderRole == "driver") "driver" else "customer"
        val msgRef = db.collection("booking_chats")
            .document(booking.id)
            .collection("messages")
            .document()

        val payload = mapOf(
            "bookingId" to booking.id,
            "customerId" to booking.customerId,
            "driverId" to booking.assignedDriverId,
            "senderId" to uid,
            "senderRole" to normalizedRole,
            "senderName" to senderName.trim().ifBlank {
                if (normalizedRole == "driver") "Driver" else "Customer"
            }.take(100),
            "text" to cleanText,
            "deliveryState" to "sent",
            "readByCustomer" to (normalizedRole == "customer"),
            "readByDriver" to (normalizedRole == "driver"),
            "createdAt" to FieldValue.serverTimestamp()
        )
        try {
            msgRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, msgRef.path, auth)
            throw e
        }
        msgRef.id
    }

    suspend fun markIncomingMessagesDelivered(
        bookingId: String,
        messages: List<BookingChatMessage>
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        if (bookingId.isBlank()) return@runCatching
        val pending = messages.filter { msg ->
            msg.senderId != uid && msg.deliveryState == "sent" && msg.id.isNotBlank()
        }
        if (pending.isEmpty()) return@runCatching

        db.runBatch { batch ->
            pending.take(25).forEach { msg ->
                val ref = db.collection("booking_chats")
                    .document(bookingId)
                    .collection("messages")
                    .document(msg.id)
                batch.update(ref, mapOf("deliveryState" to "delivered"))
            }
        }.await()
    }

    suspend fun markMessagesRead(
        bookingId: String,
        readerRole: String,
        messages: List<BookingChatMessage>
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        if (bookingId.isBlank()) return@runCatching
        val unread = messages.filter { msg ->
            msg.senderId != uid && msg.id.isNotBlank() && (
                (readerRole == "customer" && !msg.readByCustomer) ||
                    (readerRole == "driver" && !msg.readByDriver)
                )
        }
        if (unread.isEmpty()) return@runCatching

        db.runBatch { batch ->
            unread.take(40).forEach { msg ->
                val ref = db.collection("booking_chats")
                    .document(bookingId)
                    .collection("messages")
                    .document(msg.id)
                val updates = if (readerRole == "customer") {
                    mapOf("deliveryState" to "read", "readByCustomer" to true)
                } else {
                    mapOf("deliveryState" to "read", "readByDriver" to true)
                }
                batch.update(ref, updates)
            }
        }.await()
    }

    fun observeParticipantCallContact(booking: Booking): Flow<BookingCallContact?> = flow {
        if (booking.id.isBlank() || !BookingChatValidator.isChatWriteAllowed(booking.status)) {
            emit(null)
            return@flow
        }
        val uid = requireUserId()
        val ref = db.collection("booking_contacts").document(booking.id)
        emitAll(
            ref.snapshots()
                .map { snap ->
                    snap.toObject(
                        BookingCallContact::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(
                            fe ?: (error as? Exception ?: Exception(error)),
                            OperationType.GET,
                            ref.path,
                            auth
                        )
                    }
                    emit(null)
                }
        )
    }

    suspend fun publishParticipantContactForActiveTrip(
        booking: Booking,
        myPhone: String,
        myDisplayName: String,
        isDriver: Boolean
    ): Result<Unit> = runCatching {
        requireUserId()
        if (booking.id.isBlank() || booking.assignedDriverId.isBlank()) return@runCatching
        if (!BookingChatValidator.isChatWriteAllowed(booking.status)) return@runCatching

        val safePhone = myPhone.trim().take(25)
        if (safePhone.isBlank()) return@runCatching
        val ref = db.collection("booking_contacts").document(booking.id)
        val firstName = myDisplayName.trim().substringBefore(" ").take(80)
        val payload = buildMap<String, Any> {
            put("bookingId", booking.id)
            put("customerId", booking.customerId)
            put("driverId", booking.assignedDriverId)
            if (isDriver) {
                put("driverPhone", safePhone)
                if (myDisplayName.isNotBlank()) put("driverName", myDisplayName.trim().take(100))
            } else {
                put("customerPhone", safePhone)
                if (firstName.isNotBlank()) put("customerFirstName", firstName)
            }
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            ref.set(payload, SetOptions.merge()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path, auth)
            throw e
        }
    }
}
