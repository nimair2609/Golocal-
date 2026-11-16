package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.*
import com.example.domain.FareCalculator
import com.example.ui.auth.AuthDiagnostics
import com.example.ui.auth.AuthStage
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

class GolocalRepository(
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
            ?: throw IllegalStateException("User must be signed in before accessing Firestore.")
    }

    private fun <T> safeQueryFlow(
        path: String,
        query: Query,
        mapper: (DocumentSnapshot) -> T?
    ): Flow<List<T>> = flow {
        val uid = requireUserId()
        emitAll(
            query.snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc -> mapper(doc) }
                }
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

    // --- User & Profile ---

    suspend fun ensureUserAccount(
        fullName: String,
        email: String,
        photoUrl: String,
        phoneNumber: String = ""
    ): Result<UserAccount> = runCatching {
        val uid = requireUserId()
        val safeFullName = fullName.trim().ifBlank { "GoLocal User" }.take(100)
        val safeEmail = email.trim().take(150)
        val safePhotoUrl = photoUrl.trim().take(500)
        val safePhone = phoneNumber.trim().take(25)

        AuthDiagnostics.logStage(
            stage = AuthStage.FIRESTORE_ACCOUNT_CHECK,
            flow = "firestore_account",
            detail = "Checking users/$uid"
        )

        val userRef = db.collection("users").document(uid)
        val custRef = db.collection("customer_profiles").document(uid)
        val existing = try {
            userRef.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = (e as? FirebaseFirestoreException)?.code?.name
            AuthDiagnostics.logFailure(
                stage = AuthStage.AUTH_FAILED,
                flow = "firestore_account",
                exceptionType = e::class.java.simpleName,
                firebaseErrorCode = code,
                detail = "Failed reading user document"
            )
            handleFirestoreError(e, OperationType.GET, userRef.path, auth)
            throw e
        }

        if (existing.exists()) {
            AuthDiagnostics.logStage(
                stage = AuthStage.FIRESTORE_ACCOUNT_EXISTS,
                flow = "firestore_account",
                detail = "Existing user record preserved"
            )
            var existingAccount = existing.toObject(UserAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            if (safePhone.isNotBlank() && existingAccount != null && existingAccount.effectiveMobileNumber().isBlank()) {
                try {
                    userRef.update(
                        mapOf(
                            "phone" to safePhone,
                            "mobileNumber" to safePhone,
                            "mobileVerified" to true,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    existingAccount = existingAccount.copy(phone = safePhone, mobileNumber = safePhone, mobileVerified = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val code = (e as? FirebaseFirestoreException)?.code?.name
                    AuthDiagnostics.logWarning(
                        stage = AuthStage.FIRESTORE_ACCOUNT_EXISTS,
                        flow = "firestore_account",
                        exceptionType = e::class.java.simpleName,
                        firebaseErrorCode = code,
                        detail = "Optional verified phone backfill skipped softly"
                    )
                }
            }
            // Ensure companion customer_profiles/{uid} exists if a prior partial setup missed it
            try {
                if (!custRef.get().await().exists()) {
                    custRef.set(
                        mapOf(
                            "userId" to uid,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    AuthDiagnostics.logStage(
                        stage = AuthStage.FIRESTORE_PROFILE_CREATED,
                        flow = "firestore_account",
                        detail = "Healed missing customer profile"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = (e as? FirebaseFirestoreException)?.code?.name
                AuthDiagnostics.logWarning(
                    stage = AuthStage.FIRESTORE_ACCOUNT_EXISTS,
                    flow = "firestore_account",
                    exceptionType = e::class.java.simpleName,
                    firebaseErrorCode = code,
                    detail = "Optional customer profile check failed softly"
                )
            }
            existingAccount
                ?: UserAccount(
                    id = uid,
                    uid = uid,
                    userId = uid,
                    fullName = safeFullName,
                    email = safeEmail,
                    phone = safePhone,
                    mobileNumber = safePhone,
                    photoUrl = safePhotoUrl,
                    mobileVerified = safePhone.isNotBlank()
                )
        } else {
            val newAccount = UserAccount(
                id = uid,
                uid = uid,
                userId = uid,
                fullName = safeFullName,
                email = safeEmail,
                phone = safePhone,
                mobileNumber = safePhone,
                photoUrl = safePhotoUrl,
                role = "customer",
                preferredLanguage = "en",
                lowNetworkMode = false,
                privacyConsentAccepted = true,
                profileCompleted = safeFullName != "GoLocal User" && safeEmail.isNotBlank(),
                emailVerified = false,
                mobileVerified = safePhone.isNotBlank(),
                hasEmergencyContact = false,
                accountStatus = "active"
            )
            try {
                userRef.set(newAccount.toCreateMap()).await()
                AuthDiagnostics.logStage(
                    stage = AuthStage.FIRESTORE_ACCOUNT_CREATED,
                    flow = "firestore_account",
                    detail = "Created new users/$uid record"
                )
                if (!custRef.get().await().exists()) {
                    custRef.set(
                        mapOf(
                            "userId" to uid,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    AuthDiagnostics.logStage(
                        stage = AuthStage.FIRESTORE_PROFILE_CREATED,
                        flow = "firestore_account",
                        detail = "Created customer_profiles/$uid record"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = (e as? FirebaseFirestoreException)?.code?.name
                AuthDiagnostics.logFailure(
                    stage = AuthStage.AUTH_FAILED,
                    flow = "firestore_account",
                    exceptionType = e::class.java.simpleName,
                    firebaseErrorCode = code,
                    detail = "Failed creating user or profile record"
                )
                handleFirestoreError(e, OperationType.CREATE, userRef.path, auth)
                throw e
            }
            newAccount
        }
    }

    suspend fun checkRegistrationStatus(
        uid: String,
        verifiedPhoneNumber: String
    ): Result<RegistrationStatus> = runCatching {
        val currentUid = requireUserId()
        require(currentUid == uid) { "Session UID mismatch while checking registration." }
        val userRef = db.collection("users").document(uid)
        val emergencyRef = db.collection("emergency_contacts").document(uid)

        AuthDiagnostics.logStage(
            stage = AuthStage.FIRESTORE_ACCOUNT_CHECK,
            flow = "registration_check",
            detail = "Inspecting users/$uid completion status"
        )

        val userSnap = try {
            userRef.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, userRef.path, auth)
            throw e
        }

        val resolvedPhone = verifiedPhoneNumber.trim()
            .ifBlank { auth.currentUser?.phoneNumber.orEmpty().trim() }
            .take(25)

        if (!userSnap.exists()) {
            return@runCatching RegistrationStatus.PendingRegistration(
                verifiedMobileNumber = resolvedPhone,
                existingAccount = null
            )
        }

        val account = userSnap.toObject(UserAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            ?: return@runCatching RegistrationStatus.PendingRegistration(
                verifiedMobileNumber = resolvedPhone,
                existingAccount = null
            )

        val emergencyExists = try {
            emergencyRef.get().await().exists()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

        val effectivePhone = resolvedPhone.ifBlank { account.effectiveMobileNumber() }
        val isComplete = (account.profileCompleted ||
            (account.fullName.isNotBlank() && account.fullName != "GoLocal User" && account.email.isNotBlank() && account.privacyConsentAccepted)) &&
            account.fullName.isNotBlank() &&
            account.email.isNotBlank() &&
            account.privacyConsentAccepted &&
            (account.hasEmergencyContact || emergencyExists)

        if (isComplete) {
            // Backfill mobileVerified / phone if missing on returning account without creating a duplicate
            if (resolvedPhone.isNotBlank() && (account.effectiveMobileNumber().isBlank() || !account.mobileVerified)) {
                try {
                    userRef.update(
                        mapOf(
                            "uid" to uid,
                            "phone" to resolvedPhone,
                            "mobileNumber" to resolvedPhone,
                            "mobileVerified" to true,
                            "profileCompleted" to true,
                            "hasEmergencyContact" to (account.hasEmergencyContact || emergencyExists),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Soft backfill
                }
            }
            AuthDiagnostics.logStage(
                stage = AuthStage.FIRESTORE_ACCOUNT_EXISTS,
                flow = "registration_check",
                detail = "Returning completed user profile verified"
            )
            RegistrationStatus.Completed(
                account.copy(
                    uid = account.uid.ifBlank { uid },
                    mobileNumber = effectivePhone,
                    phone = effectivePhone,
                    mobileVerified = true,
                    profileCompleted = true,
                    hasEmergencyContact = account.hasEmergencyContact || emergencyExists
                )
            )
        } else {
            RegistrationStatus.PendingRegistration(
                verifiedMobileNumber = effectivePhone,
                existingAccount = account
            )
        }
    }

    suspend fun completeMobileRegistration(
        fullName: String,
        verifiedMobileNumber: String,
        invoiceEmail: String,
        emergencyContactNumber: String,
        privacyConsentAccepted: Boolean,
        role: String = "customer",
        preferredLanguage: String = "en"
    ): Result<UserAccount> = runCatching {
        val uid = requireUserId()
        val safeName = fullName.trim().take(100)
        val safePhone = verifiedMobileNumber.trim()
            .ifBlank { auth.currentUser?.phoneNumber.orEmpty().trim() }
            .take(25)
        val safeEmail = invoiceEmail.trim().take(150)
        val safeEmergency = emergencyContactNumber.trim().take(25)

        require(safeName.length >= 2) { "Full name is required." }
        require(safePhone.isNotBlank()) { "Verified mobile number is missing. Please verify OTP again." }
        require(safeEmail.isNotBlank() && safeEmail.contains("@")) { "A valid email address is required for GST invoice delivery." }
        require(safeEmergency.length >= 8) { "A valid emergency contact number is required." }
        require(safeEmergency != safePhone) { "Emergency contact number must be different from your own mobile number." }
        require(privacyConsentAccepted) { "Please accept Terms and Privacy Policy to continue." }

        val userRef = db.collection("users").document(uid)
        val custRef = db.collection("customer_profiles").document(uid)
        val emergencyRef = db.collection("emergency_contacts").document(uid)

        val existingUserSnap = userRef.get().await()
        val existingCustSnap = custRef.get().await()
        val existingEmergencySnap = emergencyRef.get().await()

        val existingAccount = if (existingUserSnap.exists()) {
            existingUserSnap.toObject(UserAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } else {
            null
        }

        val resolvedRole = existingAccount?.role?.takeIf { it.isNotBlank() } ?: role
        val resolvedLang = existingAccount?.preferredLanguage?.takeIf { it.isNotBlank() } ?: preferredLanguage

        val finalAccount = UserAccount(
            id = uid,
            uid = uid,
            userId = uid,
            fullName = safeName,
            email = safeEmail,
            phone = safePhone,
            mobileNumber = safePhone,
            photoUrl = existingAccount?.photoUrl ?: "",
            role = resolvedRole,
            preferredLanguage = resolvedLang,
            lowNetworkMode = existingAccount?.lowNetworkMode ?: false,
            privacyConsentAccepted = true,
            profileCompleted = true,
            emailVerified = false,
            mobileVerified = true,
            hasEmergencyContact = true,
            accountStatus = existingAccount?.accountStatus ?: "active",
            accountCreatedAt = existingAccount?.accountCreatedAt,
            createdAt = existingAccount?.createdAt
        )

        try {
            db.runBatch { batch ->
                if (!existingUserSnap.exists()) {
                    batch.set(userRef, finalAccount.toCreateMap())
                } else {
                    batch.update(
                        userRef,
                        mapOf(
                            "uid" to uid,
                            "fullName" to safeName,
                            "email" to safeEmail,
                            "phone" to safePhone,
                            "mobileNumber" to safePhone,
                            "privacyConsentAccepted" to true,
                            "profileCompleted" to true,
                            "emailVerified" to false,
                            "mobileVerified" to true,
                            "hasEmergencyContact" to true,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }

                if (!existingCustSnap.exists()) {
                    batch.set(
                        custRef,
                        mapOf(
                            "userId" to uid,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }

                if (!existingEmergencySnap.exists()) {
                    batch.set(
                        emergencyRef,
                        mapOf(
                            "userId" to uid,
                            "emergencyContactNumber" to safeEmergency,
                            "relationshipNote" to "Primary Emergency Contact",
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                } else {
                    batch.update(
                        emergencyRef,
                        mapOf(
                            "emergencyContactNumber" to safeEmergency,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.await()

            AuthDiagnostics.logStage(
                stage = AuthStage.FIRESTORE_ACCOUNT_CREATED,
                flow = "mobile_registration",
                detail = if (existingUserSnap.exists()) "Completed existing user profile" else "Registered new mobile OTP account"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userRef.path, auth)
            throw e
        }

        finalAccount
    }

    suspend fun syncVerifiedPhoneOnAccount(verifiedPhone: String): Result<Unit> {
        val safePhone = verifiedPhone.trim().take(25)
        if (safePhone.isBlank()) {
            return Result.failure(IllegalArgumentException("Verified phone number cannot be blank."))
        }
        return runCatching {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid)
            ref.update(
                mapOf(
                    "phone" to safePhone,
                    "mobileNumber" to safePhone,
                    "mobileVerified" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    suspend fun updateInvoiceEmail(invoiceEmail: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val safeEmail = invoiceEmail.trim().take(150)
        require(safeEmail.isNotBlank() && safeEmail.contains("@")) {
            "Enter a valid email address for GST invoice delivery."
        }
        val ref = db.collection("users").document(uid)
        try {
            ref.update(
                mapOf(
                    "email" to safeEmail,
                    "emailVerified" to false,
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

    fun observeMyEmergencyContact(uid: String): Flow<PrivateEmergencyContact?> =
        db.collection("emergency_contacts").document(uid).snapshots()
            .map { it.toObject(PrivateEmergencyContact::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                val fe = error.unwrapFirestoreError()
                if (fe is CancellationException) throw fe
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(fe, OperationType.GET, "emergency_contacts/$uid", auth)
                }
                emit(null)
            }

    suspend fun updatePrivateEmergencyContact(
        emergencyContactNumber: String,
        relationshipNote: String = "Primary Emergency Contact"
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val safeEmergency = emergencyContactNumber.trim().take(25)
        val ownPhone = auth.currentUser?.phoneNumber?.trim().orEmpty()
        require(safeEmergency.length >= 8) { "Enter a valid emergency contact number." }
        if (ownPhone.isNotBlank()) {
            require(safeEmergency != ownPhone) {
                "Emergency contact number must be different from your own mobile number."
            }
        }
        val emergencyRef = db.collection("emergency_contacts").document(uid)
        val userRef = db.collection("users").document(uid)
        val existingSnap = emergencyRef.get().await()
        val safeNote = relationshipNote.trim().ifBlank { "Primary Emergency Contact" }.take(80)
        try {
            db.runBatch { batch ->
                if (!existingSnap.exists()) {
                    batch.set(
                        emergencyRef,
                        mapOf(
                            "userId" to uid,
                            "emergencyContactNumber" to safeEmergency,
                            "relationshipNote" to safeNote,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                } else {
                    batch.update(
                        emergencyRef,
                        mapOf(
                            "emergencyContactNumber" to safeEmergency,
                            "relationshipNote" to safeNote,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.update(
                    userRef,
                    mapOf(
                        "hasEmergencyContact" to true,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, emergencyRef.path, auth)
            throw e
        }
    }

    suspend fun fetchEmergencyContactForAuthorizedResponder(targetUserId: String): Result<PrivateEmergencyContact?> = runCatching {
        requireUserId()
        val ref = db.collection("emergency_contacts").document(targetUserId)
        try {
            val snap = ref.get().await()
            snap.toObject(PrivateEmergencyContact::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, ref.path, auth)
            throw e
        }
    }

    fun observeUserAccount(uid: String): Flow<UserAccount?> =
        db.collection("users").document(uid).snapshots()
            .map { it.toObject(UserAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                val fe = error.unwrapFirestoreError()
                if (fe is CancellationException) throw fe
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(fe, OperationType.GET, "users/$uid", auth)
                }
                throw error
            }

    fun observeCustomerProfile(uid: String): Flow<CustomerProfile?> =
        db.collection("customer_profiles").document(uid).snapshots()
            .map { it.toObject(CustomerProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                val fe = error.unwrapFirestoreError()
                if (fe is CancellationException) throw fe
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(fe, OperationType.GET, "customer_profiles/$uid", auth)
                }
                throw error
            }

    suspend fun updateUserPreferences(
        fullName: String? = null,
        phone: String? = null,
        email: String? = null,
        language: String? = null,
        lowNetworkMode: Boolean? = null,
        privacyConsentAccepted: Boolean? = null
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val ref = db.collection("users").document(uid)
        val updates = buildMap<String, Any> {
            if (!fullName.isNullOrBlank()) put("fullName", fullName.trim().take(100))
            if (phone != null && phone.isNotBlank()) {
                val safePhone = phone.trim().take(25)
                put("phone", safePhone)
                put("mobileNumber", safePhone)
            }
            if (!email.isNullOrBlank()) {
                put("email", email.trim().take(150))
                put("emailVerified", false)
            }
            if (!language.isNullOrBlank()) put("preferredLanguage", language)
            if (lowNetworkMode != null) put("lowNetworkMode", lowNetworkMode)
            if (privacyConsentAccepted != null) put("privacyConsentAccepted", privacyConsentAccepted)
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            ref.update(updates).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path, auth)
            throw e
        }
    }

    suspend fun saveCustomerProfile(profile: CustomerProfile): Result<Unit> = runCatching {
        val uid = requireUserId()
        val ref = db.collection("customer_profiles").document(uid)
        val payload = buildMap<String, Any> {
            put("userId", uid)
            if (profile.homeAddress.isNotBlank()) put("homeAddress", profile.homeAddress.trim())
            if (profile.workAddress.isNotBlank()) put("workAddress", profile.workAddress.trim())
            if (profile.nativeVillage.isNotBlank()) put("nativeVillage", profile.nativeVillage.trim())
            if (profile.nativeDistrict.isNotBlank()) put("nativeDistrict", profile.nativeDistrict.trim())
            if (profile.createdAt == null) put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            if (profile.createdAt == null) {
                ref.set(payload).await()
            } else {
                ref.update(payload.filterKeys { it != "userId" && it != "createdAt" }).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path, auth)
            throw e
        }
    }

    // --- Catalogs & Discovery ---

    fun observeServiceAreas(): Flow<List<ServiceArea>> =
        safeQueryFlow("service_areas", db.collection("service_areas")) {
            it.toObject(ServiceArea::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeFareConfigs(): Flow<List<FareConfig>> =
        safeQueryFlow("fare_configs", db.collection("fare_configs")) {
            it.toObject(FareConfig::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeVerifiedDrivers(): Flow<List<DriverDirectoryEntry>> =
        safeQueryFlow(
            "driver_directory",
            db.collection("driver_directory").whereEqualTo("verificationStatus", "verified")
        ) {
            it.toObject(DriverDirectoryEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    // --- Customer Bookings & Offers ---

    fun observeCustomerBookings(uid: String): Flow<List<Booking>> =
        safeQueryFlow(
            "bookings",
            db.collection("bookings").whereEqualTo("customerId", uid)
        ) {
            it.toObject(Booking::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeOffersForBooking(bookingId: String, customerId: String): Flow<List<RideOffer>> =
        safeQueryFlow(
            "ride_offers",
            db.collection("ride_offers")
                .whereEqualTo("bookingId", bookingId)
                .whereEqualTo("customerId", customerId)
        ) {
            it.toObject(RideOffer::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeTripLocation(bookingId: String): Flow<TripLocation?> =
        db.collection("trip_locations").document(bookingId).snapshots()
            .map { it.toObject(TripLocation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                val fe = error.unwrapFirestoreError()
                if (fe is CancellationException) throw fe
                handleFirestoreError(fe, OperationType.GET, "trip_locations/$bookingId", auth)
                throw error
            }

    suspend fun createBooking(booking: Booking): Result<String> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("bookings").document()
        val payload = buildMap<String, Any> {
            put("customerId", uid)
            put("customerName", booking.customerName.ifBlank { "GOLOCAL Rider" })
            put("bookingChannel", booking.bookingChannel)
            put("bookingType", booking.bookingType)
            put("vehicleCategory", booking.vehicleCategory)
            put("pickupLabel", booking.pickupLabel)
            if (booking.pickupDistrict.isNotBlank()) put("pickupDistrict", booking.pickupDistrict)
            put("pickupLat", booking.pickupLat)
            put("pickupLng", booking.pickupLng)
            put("destinationLabel", booking.destinationLabel)
            if (booking.destinationDistrict.isNotBlank()) put("destinationDistrict", booking.destinationDistrict)
            put("destinationLat", booking.destinationLat)
            put("destinationLng", booking.destinationLng)
            put("distanceKm", booking.distanceKm)
            put("estimatedDurationMin", booking.estimatedDurationMin)
            put("estimatedFareInr", booking.estimatedFareInr)
            put("minPermittedFareInr", booking.minPermittedFareInr)
            put("maxPermittedFareInr", booking.maxPermittedFareInr)
            put("fareConfigVersion", booking.fareConfigVersion)
            put("commissionPercent", booking.commissionPercent)
            put("gstPercent", booking.gstPercent)
            put("paymentMethod", booking.paymentMethod)
            put("paymentStatus", "pending")
            put("status", "searching")
            if (booking.trainNumber.isNotBlank()) put("trainNumber", booking.trainNumber)
            if (booking.scheduledDateTimeLabel.isNotBlank()) put("scheduledDateTimeLabel", booking.scheduledDateTimeLabel)
            if (booking.familyMemberName.isNotBlank()) put("familyMemberName", booking.familyMemberName)
            put("familyConsentConfirmed", booking.familyConsentConfirmed)
            put("isRoundTrip", booking.isRoundTrip)
            if (booking.invoiceEmail.isNotBlank()) put("invoiceEmail", booking.invoiceEmail.trim().take(150))
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
            throw e
        }
        docRef.id
    }

    suspend fun confirmOfferAtomically(bookingId: String, offer: RideOffer): Result<String> = runCatching {
        val uid = requireUserId()
        val bookingRef = db.collection("bookings").document(bookingId)
        val offerRef = db.collection("ride_offers").document(offer.id)
        val assignmentRef = db.collection("ride_assignments").document(bookingId)
        val tripPin = Random.nextInt(1000, 9999).toString()

        try {
            db.runTransaction { tx ->
                val bookingSnap = tx.get(bookingRef)
                val status = bookingSnap.getString("status") ?: ""
                val existingAssignment = tx.get(assignmentRef)
                if (status != "searching" || existingAssignment.exists()) {
                    throw IllegalStateException("Booking has already been assigned to another driver.")
                }
                val minFare = bookingSnap.getDouble("minPermittedFareInr") ?: 0.0
                val maxFare = bookingSnap.getDouble("maxPermittedFareInr") ?: Double.MAX_VALUE
                if (offer.proposedFareInr < minFare || offer.proposedFareInr > maxFare) {
                    throw IllegalArgumentException("Offer fare exceeds permitted regulatory range.")
                }

                tx.update(
                    bookingRef,
                    mapOf(
                        "status" to "assigned",
                        "assignedDriverId" to offer.driverId,
                        "assignedDriverName" to offer.driverName,
                        "assignedVehicleLabel" to offer.vehicleLabel,
                        "acceptedOfferId" to offer.id,
                        "agreedFareInr" to offer.proposedFareInr,
                        "tripPin" to tripPin,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                tx.update(
                    offerRef,
                    mapOf(
                        "status" to "accepted",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                tx.set(
                    assignmentRef,
                    mapOf(
                        "bookingId" to bookingId,
                        "customerId" to uid,
                        "driverId" to offer.driverId,
                        "offerId" to offer.id,
                        "lockedFareInr" to offer.proposedFareInr,
                        "status" to "active",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()

            // Notify driver
            val notifRef = db.collection("notifications").document()
            notifRef.set(
                mapOf(
                    "userId" to uid,
                    "title" to "Ride Confirmed",
                    "body" to "Driver ${offer.driverName} confirmed at ₹${offer.proposedFareInr.toInt()}. Trip PIN: $tripPin",
                    "type" to "booking_confirmed",
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, bookingRef.path, auth)
            throw e
        }
        tripPin
    }

    suspend fun cancelBooking(bookingId: String, reason: String): Result<Unit> = runCatching {
        requireUserId()
        val ref = db.collection("bookings").document(bookingId)
        try {
            ref.update(
                mapOf(
                    "status" to "cancelled",
                    "cancellationReason" to reason.ifBlank { "Cancelled by customer" },
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

    suspend fun submitRating(booking: Booking, stars: Int, review: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val bookingRef = db.collection("bookings").document(booking.id)
        val ratingRef = db.collection("ratings").document(booking.id)
        try {
            db.runBatch { batch ->
                batch.set(
                    ratingRef,
                    buildMap<String, Any> {
                        put("bookingId", booking.id)
                        put("customerId", uid)
                        put("driverId", booking.assignedDriverId)
                        put("stars", stars.coerceIn(1, 5))
                        if (review.isNotBlank()) put("review", review.trim())
                        put("createdAt", FieldValue.serverTimestamp())
                    }
                )
                batch.update(
                    bookingRef,
                    mapOf(
                        "customerRating" to stars.coerceIn(1, 5),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ratingRef.path, auth)
            throw e
        }
    }

    fun observeCustomerPayments(uid: String): Flow<List<PaymentRecord>> =
        safeQueryFlow("payments", db.collection("payments").whereEqualTo("customerId", uid)) {
            it.toObject(PaymentRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeMyNotifications(uid: String): Flow<List<AppNotification>> =
        safeQueryFlow("notifications", db.collection("notifications").whereEqualTo("userId", uid)) {
            it.toObject(AppNotification::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    suspend fun markNotificationRead(notificationId: String): Result<Unit> = runCatching {
        requireUserId()
        val ref = db.collection("notifications").document(notificationId)
        try {
            ref.update("read", true).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path, auth)
            throw e
        }
    }

    suspend fun requestRefund(bookingId: String, amountInr: Double, reason: String): Result<String> = runCatching {
        val uid = requireUserId()
        val ref = db.collection("refunds").document()
        try {
            ref.set(
                mapOf(
                    "bookingId" to bookingId,
                    "customerId" to uid,
                    "amountInr" to amountInr,
                    "reason" to reason.trim(),
                    "status" to "requested",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path, auth)
            throw e
        }
        ref.id
    }

    suspend fun submitComplaint(bookingId: String, category: String, description: String): Result<String> = runCatching {
        val uid = requireUserId()
        val ref = db.collection("complaints").document()
        val payload = buildMap<String, Any> {
            put("reporterId", uid)
            if (bookingId.isNotBlank()) put("bookingId", bookingId)
            put("category", category)
            put("description", description.trim())
            put("status", "open")
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            ref.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path, auth)
            throw e
        }
        ref.id
    }

    suspend fun triggerEmergencySos(bookingId: String, lat: Double, lng: Double, notes: String): Result<String> = runCatching {
        val uid = requireUserId()
        val ref = db.collection("emergency_events").document()
        val payload = buildMap<String, Any> {
            put("userId", uid)
            if (bookingId.isNotBlank()) put("bookingId", bookingId)
            put("lat", lat)
            put("lng", lng)
            put("emergencyContactRef", "emergency_contacts/$uid")
            put("notes", notes.ifBlank { "Emergency SOS triggered from GOLOCAL app" })
            put("status", "active")
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            ref.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path, auth)
            throw e
        }
        ref.id
    }

    suspend fun submitSupportRequest(requestType: String, details: String): Result<String> = runCatching {
        val uid = requireUserId()
        val ref = db.collection("support_requests").document()
        try {
            ref.set(
                mapOf(
                    "userId" to uid,
                    "requestType" to requestType,
                    "details" to details.trim(),
                    "status" to "submitted",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path, auth)
            throw e
        }
        ref.id
    }

    // --- Driver Operations ---

    fun observeMyDriverProfile(driverId: String): Flow<DriverProfile?> =
        db.collection("driver_profiles").document(driverId).snapshots()
            .map { it.toObject(DriverProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                val fe = error.unwrapFirestoreError()
                if (fe is CancellationException) throw fe
                if (auth.currentUser?.uid == driverId) {
                    handleFirestoreError(fe, OperationType.GET, "driver_profiles/$driverId", auth)
                }
                throw error
            }

    suspend fun registerDriverApplication(profile: DriverProfile): Result<Unit> = runCatching {
        val uid = requireUserId()
        val privRef = db.collection("driver_profiles").document(uid)
        val dirRef = db.collection("driver_directory").document(uid)
        val maskedPlate = if (profile.vehicleRegNumber.length > 4) {
            "WB••" + profile.vehicleRegNumber.takeLast(4)
        } else {
            profile.vehicleRegNumber
        }
        val privPayload = buildMap<String, Any> {
            put("driverId", uid)
            put("legalName", profile.legalName.trim())
            put("phone", profile.phone.trim())
            if (profile.address.isNotBlank()) put("address", profile.address.trim())
            put("district", profile.district.trim())
            if (profile.block.isNotBlank()) put("block", profile.block.trim())
            put("vehicleCategory", profile.vehicleCategory)
            if (profile.vehicleModel.isNotBlank()) put("vehicleModel", profile.vehicleModel.trim())
            put("vehicleRegNumber", profile.vehicleRegNumber.trim().uppercase())
            put("drivingLicenceNumber", profile.drivingLicenceNumber.trim().uppercase())
            if (profile.rcNumber.isNotBlank()) put("rcNumber", profile.rcNumber.trim().uppercase())
            if (profile.insuranceNumber.isNotBlank()) put("insuranceNumber", profile.insuranceNumber.trim())
            if (profile.permitInfo.isNotBlank()) put("permitInfo", profile.permitInfo.trim())
            if (profile.settlementUpiOrBank.isNotBlank()) put("settlementUpiOrBank", profile.settlementUpiOrBank.trim())
            if (profile.documentPhotoUri.isNotBlank()) put("documentPhotoUri", profile.documentPhotoUri)
            put("verificationStatus", "pending")
            put("isOnline", false)
            put("ratingAvg", 5.0)
            put("completedRides", 0L)
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }
        val dirPayload = buildMap<String, Any> {
            put("driverId", uid)
            put("displayName", profile.legalName.trim())
            put("vehicleCategory", profile.vehicleCategory)
            if (profile.vehicleModel.isNotBlank()) put("vehicleModel", profile.vehicleModel.trim())
            put("maskedPlate", maskedPlate)
            put("district", profile.district.trim())
            if (profile.block.isNotBlank()) put("block", profile.block.trim())
            put("verificationStatus", "pending")
            put("isOnline", false)
            put("ratingAvg", 5.0)
            put("completedRides", 0L)
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            db.runBatch { batch ->
                batch.set(privRef, privPayload)
                batch.set(dirRef, dirPayload)
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, privRef.path, auth)
            throw e
        }
    }

    suspend fun setDriverOnlineStatus(online: Boolean, lat: Double, lng: Double): Result<Unit> = runCatching {
        val uid = requireUserId()
        val privRef = db.collection("driver_profiles").document(uid)
        val dirRef = db.collection("driver_directory").document(uid)
        try {
            db.runBatch { batch ->
                batch.update(
                    privRef,
                    mapOf("isOnline" to online, "updatedAt" to FieldValue.serverTimestamp())
                )
                batch.update(
                    dirRef,
                    mapOf(
                        "isOnline" to online,
                        "currentLat" to lat,
                        "currentLng" to lng,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, privRef.path, auth)
            throw e
        }
    }

    fun observeOpenBookingsForDrivers(): Flow<List<Booking>> =
        safeQueryFlow("bookings", db.collection("bookings").whereEqualTo("status", "searching")) {
            it.toObject(Booking::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeDriverAssignedBookings(driverId: String): Flow<List<Booking>> =
        safeQueryFlow("bookings", db.collection("bookings").whereEqualTo("assignedDriverId", driverId)) {
            it.toObject(Booking::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    suspend fun submitDriverOffer(
        booking: Booking,
        driver: DriverProfile,
        proposedFareInr: Double,
        etaMinutes: Long
    ): Result<String> = runCatching {
        val uid = requireUserId()
        require(driver.verificationStatus == "verified") { "Only verified drivers can submit fare proposals." }
        require(proposedFareInr >= booking.minPermittedFareInr && proposedFareInr <= booking.maxPermittedFareInr) {
            "Proposed fare must stay within ₹${booking.minPermittedFareInr.toInt()} - ₹${booking.maxPermittedFareInr.toInt()}"
        }
        val offerRef = db.collection("ride_offers").document()
        val vehicleLabel = listOf(driver.vehicleModel, driver.vehicleRegNumber)
            .filter { it.isNotBlank() }.joinToString(" • ")
        val payload = mapOf(
            "bookingId" to booking.id,
            "customerId" to booking.customerId,
            "driverId" to uid,
            "driverName" to driver.legalName,
            "vehicleCategory" to driver.vehicleCategory,
            "vehicleLabel" to vehicleLabel,
            "driverRating" to driver.ratingAvg,
            "driverCompletedRides" to driver.completedRides,
            "isVerifiedDriver" to true,
            "etaMinutes" to etaMinutes.coerceIn(1L, 180L),
            "proposedFareInr" to proposedFareInr,
            "status" to "pending",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            offerRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, offerRef.path, auth)
            throw e
        }
        offerRef.id
    }

    suspend fun advanceDriverTripState(
        booking: Booking,
        nextStatus: String,
        enteredPin: String = "",
        driverLat: Double,
        driverLng: Double
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(FareCalculator.isValidTransition(booking.status, nextStatus)) {
            "Invalid state transition from ${booking.status} to $nextStatus"
        }
        if (nextStatus == "in_progress") {
            require(enteredPin.trim() == booking.tripPin) {
                "Invalid Trip PIN. Please verify the 4-digit PIN with the passenger."
            }
        }
        val bookingRef = db.collection("bookings").document(booking.id)
        val locRef = db.collection("trip_locations").document(booking.id)

        try {
            if (nextStatus != "completed") {
                db.runBatch { batch ->
                    batch.update(
                        bookingRef,
                        mapOf("status" to nextStatus, "updatedAt" to FieldValue.serverTimestamp())
                    )
                    batch.set(
                        locRef,
                        mapOf(
                            "bookingId" to booking.id,
                            "customerId" to booking.customerId,
                            "driverId" to uid,
                            "lat" to driverLat,
                            "lng" to driverLng,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }.await()
            } else {
                // Idempotent completion + payment + commission ledger transaction
                val paymentRef = db.collection("payments").document(booking.id)
                val commissionRef = db.collection("commissions").document(booking.id)
                val assignmentRef = db.collection("ride_assignments").document(booking.id)

                val settlement = FareCalculator.calculateSettlement(
                    grossFareInr = if (booking.agreedFareInr > 0) booking.agreedFareInr else booking.estimatedFareInr,
                    gstPercent = booking.gstPercent,
                    commissionPercent = booking.commissionPercent
                )
                val finalPayStatus = if (booking.paymentMethod == "cash") "settled_cash" else "pending_verification"

                db.runTransaction { tx ->
                    val snap = tx.get(bookingRef)
                    val curStatus = snap.getString("status") ?: ""
                    if (curStatus != "in_progress") {
                        throw IllegalStateException("Trip is not in progress or was already completed.")
                    }
                    val existingPayment = tx.get(paymentRef)
                    val existingCommission = tx.get(commissionRef)
                    if (existingPayment.exists() || existingCommission.exists()) {
                        throw IllegalStateException("Settlement already recorded for this booking.")
                    }

                    val resolvedInvoiceEmail = (snap.getString("invoiceEmail") ?: booking.invoiceEmail).trim().take(150)

                    tx.update(
                        bookingRef,
                        mapOf(
                            "status" to "completed",
                            "paymentStatus" to finalPayStatus,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    tx.update(
                        assignmentRef,
                        mapOf(
                            "status" to "completed",
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    tx.set(
                        paymentRef,
                        buildMap<String, Any> {
                            put("bookingId", booking.id)
                            put("customerId", booking.customerId)
                            put("driverId", uid)
                            put("grossFareInr", settlement.grossFareInr)
                            put("gstInr", settlement.gstInr)
                            put("platformCommissionInr", settlement.platformCommissionInr)
                            put("netDriverEarningsInr", settlement.netDriverEarningsInr)
                            put("paymentMethod", booking.paymentMethod)
                            if (resolvedInvoiceEmail.isNotBlank()) put("invoiceEmail", resolvedInvoiceEmail)
                            put("gatewayReference", if (booking.paymentMethod == "cash") "CASH_COLLECTED" else "AWAITING_WEBHOOK")
                            put("status", finalPayStatus)
                            put("createdAt", FieldValue.serverTimestamp())
                            put("updatedAt", FieldValue.serverTimestamp())
                        }
                    )
                    tx.set(
                        commissionRef,
                        mapOf(
                            "bookingId" to booking.id,
                            "driverId" to uid,
                            "grossFareInr" to settlement.grossFareInr,
                            "commissionRatePct" to booking.commissionPercent,
                            "commissionAmountInr" to settlement.platformCommissionInr,
                            "netDriverEarningsInr" to settlement.netDriverEarningsInr,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, bookingRef.path, auth)
            throw e
        }
    }

    fun observeDriverCommissions(driverId: String): Flow<List<CommissionRecord>> =
        safeQueryFlow("commissions", db.collection("commissions").whereEqualTo("driverId", driverId)) {
            it.toObject(CommissionRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeDriverSettlements(driverId: String): Flow<List<DriverSettlement>> =
        safeQueryFlow("driver_settlements", db.collection("driver_settlements").whereEqualTo("driverId", driverId)) {
            it.toObject(DriverSettlement::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    suspend fun requestDriverSettlement(driverName: String, amountInr: Double, upiOrBank: String): Result<String> = runCatching {
        val uid = requireUserId()
        require(amountInr >= 50.0) { "Minimum withdrawal amount is ₹50." }
        val ref = db.collection("driver_settlements").document()
        try {
            ref.set(
                mapOf(
                    "driverId" to uid,
                    "driverName" to driverName,
                    "amountInr" to amountInr,
                    "upiOrBankRef" to upiOrBank.trim(),
                    "status" to "requested",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path, auth)
            throw e
        }
        ref.id
    }

    // --- Admin Operations ---

    suspend fun checkIsAdmin(uid: String, email: String?): Boolean {
        if (email.equals("renukadas083@gmail.com", ignoreCase = true) && auth.currentUser?.isEmailVerified == true) {
            return true
        }
        return try {
            db.collection("admins").document(uid).get().await().exists()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = (e as? FirebaseFirestoreException)?.code?.name
            AuthDiagnostics.logWarning(
                stage = AuthStage.FIRESTORE_ACCOUNT_CHECK,
                flow = "admin_check",
                exceptionType = e::class.java.simpleName,
                firebaseErrorCode = code,
                detail = "Admin allowlist check failed"
            )
            false
        }
    }

    fun observeAdminUsers(): Flow<List<UserAccount>> =
        safeQueryFlow("users", db.collection("users")) {
            it.toObject(UserAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAdminDrivers(): Flow<List<DriverProfile>> =
        safeQueryFlow("driver_profiles", db.collection("driver_profiles")) {
            it.toObject(DriverProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAdminBookings(): Flow<List<Booking>> =
        safeQueryFlow("bookings", db.collection("bookings")) {
            it.toObject(Booking::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAdminPayments(): Flow<List<PaymentRecord>> =
        safeQueryFlow("payments", db.collection("payments")) {
            it.toObject(PaymentRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAdminComplaints(): Flow<List<ComplaintRecord>> =
        safeQueryFlow("complaints", db.collection("complaints")) {
            it.toObject(ComplaintRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAdminEmergencies(): Flow<List<EmergencyEvent>> =
        safeQueryFlow("emergency_events", db.collection("emergency_events")) {
            it.toObject(EmergencyEvent::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAdminSettlements(): Flow<List<DriverSettlement>> =
        safeQueryFlow("driver_settlements", db.collection("driver_settlements")) {
            it.toObject(DriverSettlement::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAdminRefunds(): Flow<List<RefundRecord>> =
        safeQueryFlow("refunds", db.collection("refunds")) {
            it.toObject(RefundRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAdminAuditLogs(): Flow<List<AuditLog>> =
        safeQueryFlow("audit_logs", db.collection("audit_logs")) {
            it.toObject(AuditLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    suspend fun adminVerifyOrUpdateDriver(
        driverId: String,
        newStatus: String,
        reason: String = ""
    ): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val privRef = db.collection("driver_profiles").document(driverId)
        val dirRef = db.collection("driver_directory").document(driverId)
        val auditRef = db.collection("audit_logs").document()
        val notifRef = db.collection("notifications").document()

        try {
            db.runBatch { batch ->
                val updates = buildMap<String, Any> {
                    put("verificationStatus", newStatus)
                    if (newStatus != "verified") put("isOnline", false)
                    if (reason.isNotBlank()) put("rejectionReason", reason)
                    put("updatedAt", FieldValue.serverTimestamp())
                }
                batch.update(privRef, updates)
                batch.update(
                    dirRef,
                    buildMap<String, Any> {
                        put("verificationStatus", newStatus)
                        if (newStatus != "verified") put("isOnline", false)
                        put("updatedAt", FieldValue.serverTimestamp())
                    }
                )
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "DRIVER_$newStatus".uppercase(),
                        "targetCollection" to "driver_profiles",
                        "targetId" to driverId,
                        "summary" to "Driver status changed to $newStatus ${if (reason.isNotBlank()) "($reason)" else ""}".trim(),
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    notifRef,
                    mapOf(
                        "userId" to driverId,
                        "title" to "Driver Verification Update",
                        "body" to "Your GOLOCAL driver status is now: $newStatus",
                        "type" to "driver_verification",
                        "read" to false,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, privRef.path, auth)
            throw e
        }
    }

    suspend fun adminSaveFareConfig(config: FareConfig): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val nextVersion = config.version + 1
        val ref = db.collection("fare_configs").document("${config.vehicleCategory}_v$nextVersion")
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.set(
                    ref,
                    mapOf(
                        "vehicleCategory" to config.vehicleCategory,
                        "version" to nextVersion,
                        "baseFareInr" to config.baseFareInr,
                        "perKmRateInr" to config.perKmRateInr,
                        "minimumFareInr" to config.minimumFareInr,
                        "waitingChargePerMinInr" to config.waitingChargePerMinInr,
                        "cancellationFeeInr" to config.cancellationFeeInr,
                        "gstPercent" to config.gstPercent,
                        "commissionPercent" to config.commissionPercent,
                        "allowDriverFareProposal" to config.allowDriverFareProposal,
                        "minBidFactor" to config.minBidFactor,
                        "maxBidFactor" to config.maxBidFactor,
                        "isEnabled" to config.isEnabled,
                        "updatedBy" to actorUid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "UPDATE_FARE_CONFIG",
                        "targetCollection" to "fare_configs",
                        "targetId" to ref.id,
                        "summary" to "Published ${config.vehicleCategory} v$nextVersion (base ₹${config.baseFareInr}, ₹${config.perKmRateInr}/km, comm ${config.commissionPercent}%)",
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
    }

    suspend fun adminAddServiceArea(area: ServiceArea): Result<String> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val ref = db.collection("service_areas").document()
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.set(
                    ref,
                    buildMap<String, Any> {
                        put("name", area.name.trim())
                        if (area.nameBn.isNotBlank()) put("nameBn", area.nameBn.trim())
                        put("district", area.district.trim())
                        if (area.block.isNotBlank()) put("block", area.block.trim())
                        put("zoneType", area.zoneType)
                        put("lat", area.lat)
                        put("lng", area.lng)
                        put("isActive", area.isActive)
                        put("parcelApproved", area.parcelApproved)
                        put("operationalHours", area.operationalHours)
                        put("createdAt", FieldValue.serverTimestamp())
                        put("updatedAt", FieldValue.serverTimestamp())
                    }
                )
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "ADD_SERVICE_AREA",
                        "targetCollection" to "service_areas",
                        "targetId" to ref.id,
                        "summary" to "Added service area ${area.name} (${area.district})",
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

    suspend fun adminUpdateComplaintStatus(complaintId: String, status: String, note: String): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val ref = db.collection("complaints").document(complaintId)
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.update(
                    ref,
                    buildMap<String, Any> {
                        put("status", status)
                        if (note.isNotBlank()) put("resolutionNote", note.trim())
                        put("updatedAt", FieldValue.serverTimestamp())
                    }
                )
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "RESOLVE_COMPLAINT",
                        "targetCollection" to "complaints",
                        "targetId" to complaintId,
                        "summary" to "Marked complaint $status",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path, auth)
            throw e
        }
    }

    suspend fun adminResolveEmergency(eventId: String, status: String): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val ref = db.collection("emergency_events").document(eventId)
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.update(ref, mapOf("status" to status, "updatedAt" to FieldValue.serverTimestamp()))
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "EMERGENCY_$status".uppercase(),
                        "targetCollection" to "emergency_events",
                        "targetId" to eventId,
                        "summary" to "Updated SOS event to $status",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path, auth)
            throw e
        }
    }

    suspend fun adminProcessSettlement(settlementId: String, status: String): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val ref = db.collection("driver_settlements").document(settlementId)
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.update(ref, mapOf("status" to status, "updatedAt" to FieldValue.serverTimestamp()))
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "SETTLEMENT_$status".uppercase(),
                        "targetCollection" to "driver_settlements",
                        "targetId" to settlementId,
                        "summary" to "Updated driver settlement to $status",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path, auth)
            throw e
        }
    }

    suspend fun adminProcessRefund(refundId: String, status: String): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val ref = db.collection("refunds").document(refundId)
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.update(ref, mapOf("status" to status, "updatedAt" to FieldValue.serverTimestamp()))
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "REFUND_$status".uppercase(),
                        "targetCollection" to "refunds",
                        "targetId" to refundId,
                        "summary" to "Updated customer refund to $status",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path, auth)
            throw e
        }
    }

    suspend fun adminSeedWestBengalDefaults(): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                FareCalculator.defaultFareConfigs().forEach { cfg ->
                    val ref = db.collection("fare_configs").document("${cfg.vehicleCategory}_v1")
                    batch.set(
                        ref,
                        mapOf(
                            "vehicleCategory" to cfg.vehicleCategory,
                            "version" to 1L,
                            "baseFareInr" to cfg.baseFareInr,
                            "perKmRateInr" to cfg.perKmRateInr,
                            "minimumFareInr" to cfg.minimumFareInr,
                            "waitingChargePerMinInr" to cfg.waitingChargePerMinInr,
                            "cancellationFeeInr" to cfg.cancellationFeeInr,
                            "gstPercent" to cfg.gstPercent,
                            "commissionPercent" to cfg.commissionPercent,
                            "allowDriverFareProposal" to cfg.allowDriverFareProposal,
                            "minBidFactor" to cfg.minBidFactor,
                            "maxBidFactor" to cfg.maxBidFactor,
                            "isEnabled" to cfg.isEnabled,
                            "updatedBy" to actorUid,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "INITIALIZE_PRICING_ENGINE",
                        "targetCollection" to "fare_configs",
                        "targetId" to "v1_defaults",
                        "summary" to "Initialized default West Bengal fare configurations",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "fare_configs", auth)
            throw e
        }
    }

    // --- Ratings, Driver Offers, Platform Settings & Extended Admin Controls ---

    fun observeCustomerRatings(customerId: String): Flow<List<RatingRecord>> =
        safeQueryFlow("ratings", db.collection("ratings").whereEqualTo("customerId", customerId)) {
            it.toObject(RatingRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeDriverRatings(driverId: String): Flow<List<RatingRecord>> =
        safeQueryFlow("ratings", db.collection("ratings").whereEqualTo("driverId", driverId)) {
            it.toObject(RatingRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeDriverOffers(driverId: String): Flow<List<RideOffer>> =
        safeQueryFlow("ride_offers", db.collection("ride_offers").whereEqualTo("driverId", driverId)) {
            it.toObject(RideOffer::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    suspend fun withdrawDriverOffer(offerId: String): Result<Unit> = runCatching {
        requireUserId()
        val ref = db.collection("ride_offers").document(offerId)
        try {
            ref.update(
                mapOf(
                    "status" to "expired",
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

    fun observePlatformSettings(): Flow<PlatformSettings?> =
        db.collection("platform_settings").document("global").snapshots()
            .map { it.toObject(PlatformSettings::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                val fe = error.unwrapFirestoreError()
                if (fe is CancellationException) throw fe
                emit(null)
            }

    fun observeAdminRatings(): Flow<List<RatingRecord>> =
        safeQueryFlow("ratings", db.collection("ratings")) {
            it.toObject(RatingRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAdminSupportRequests(): Flow<List<SupportRequest>> =
        safeQueryFlow("support_requests", db.collection("support_requests")) {
            it.toObject(SupportRequest::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAdminAllowlist(): Flow<List<AdminAccountEntry>> =
        safeQueryFlow("admins", db.collection("admins")) {
            it.toObject(AdminAccountEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    suspend fun adminToggleServiceArea(areaId: String, active: Boolean): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val ref = db.collection("service_areas").document(areaId)
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.update(ref, mapOf("isActive" to active, "updatedAt" to FieldValue.serverTimestamp()))
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to if (active) "ACTIVATE_SERVICE_AREA" else "DEACTIVATE_SERVICE_AREA",
                        "targetCollection" to "service_areas",
                        "targetId" to areaId,
                        "summary" to "Service area $areaId active=$active",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path, auth)
            throw e
        }
    }

    suspend fun adminSetUserAccountStatus(targetUid: String, newStatus: String): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val ref = db.collection("users").document(targetUid)
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.update(ref, mapOf("accountStatus" to newStatus, "updatedAt" to FieldValue.serverTimestamp()))
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "USER_STATUS_$newStatus".uppercase(),
                        "targetCollection" to "users",
                        "targetId" to targetUid,
                        "summary" to "Changed account status of $targetUid to $newStatus",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path, auth)
            throw e
        }
    }

    suspend fun adminSendNotification(targetUid: String, title: String, body: String, type: String = "system"): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val notifRef = db.collection("notifications").document()
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.set(
                    notifRef,
                    mapOf(
                        "userId" to targetUid,
                        "title" to title.trim(),
                        "body" to body.trim(),
                        "type" to type,
                        "read" to false,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "SEND_NOTIFICATION",
                        "targetCollection" to "notifications",
                        "targetId" to targetUid,
                        "summary" to "Sent notification '$title' to $targetUid",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, notifRef.path, auth)
            throw e
        }
    }

    suspend fun adminSavePlatformSettings(settings: PlatformSettings): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val ref = db.collection("platform_settings").document("global")
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.set(
                    ref,
                    mapOf(
                        "helplineNumber" to settings.helplineNumber.trim(),
                        "parcelDeliveryEnabled" to settings.parcelDeliveryEnabled,
                        "maintenanceNotice" to settings.maintenanceNotice.trim(),
                        "cancellationPolicySummary" to settings.cancellationPolicySummary.trim(),
                        "defaultCommissionPct" to settings.defaultCommissionPct,
                        "updatesEnabled" to settings.updatesEnabled,
                        "latestVersionName" to settings.latestVersionName.trim(),
                        "latestVersionCode" to settings.latestVersionCode.coerceAtLeast(1L),
                        "minSupportedVersionCode" to settings.minSupportedVersionCode.coerceAtLeast(1L),
                        "apkDownloadUrl" to settings.apkDownloadUrl.trim(),
                        "sha256Checksum" to settings.sha256Checksum.trim().lowercase(),
                        "releaseNotes" to settings.releaseNotes.trim(),
                        "isMandatoryUpdate" to settings.isMandatoryUpdate,
                        "updateManifestUrl" to settings.updateManifestUrl.trim(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "UPDATE_PLATFORM_SETTINGS",
                        "targetCollection" to "platform_settings",
                        "targetId" to "global",
                        "summary" to "Updated global platform settings",
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
    }

    suspend fun adminGrantAdminAccess(targetUid: String, targetEmail: String, displayName: String): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val ref = db.collection("admins").document(targetUid.trim())
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.set(
                    ref,
                    mapOf(
                        "userId" to targetUid.trim(),
                        "uid" to targetUid.trim(),
                        "email" to targetEmail.trim(),
                        "displayName" to displayName.trim(),
                        "addedBy" to actorEmail,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "GRANT_ADMIN_ACCESS",
                        "targetCollection" to "admins",
                        "targetId" to targetUid.trim(),
                        "summary" to "Granted admin access to $targetEmail",
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
    }

    suspend fun adminRevokeAdminAccess(targetUid: String): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val ref = db.collection("admins").document(targetUid.trim())
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.delete(ref)
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "REVOKE_ADMIN_ACCESS",
                        "targetCollection" to "admins",
                        "targetId" to targetUid.trim(),
                        "summary" to "Revoked admin access for $targetUid",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, ref.path, auth)
            throw e
        }
    }
}
