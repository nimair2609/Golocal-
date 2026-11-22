package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.R
import com.example.data.model.*
import com.example.domain.FareCalculator
import com.example.domain.SimulatedTestDriver
import com.example.domain.TestRideOtpUtil
import com.example.domain.TestRideRouteSimulator
import com.example.domain.TestRideStateMachine
import com.example.ui.auth.AuthDiagnostics
import com.example.ui.auth.AuthStage
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import com.google.firebase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
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

        val accountOrNull = if (userSnap.exists()) {
            userSnap.toObject(UserAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } else {
            null
        }
        val effectivePhoneForSample = resolvedPhone.ifBlank { accountOrNull?.effectiveMobileNumber().orEmpty() }
        val provisionedSampleAccount = runCatching {
            SampleRiderRepository(db, auth).autoProvisionSampleRiderIfAllowlisted(
                uid = uid,
                verifiedPhoneE164 = effectivePhoneForSample
            )
        }.getOrNull()
        if (provisionedSampleAccount != null) {
            AuthDiagnostics.logStage(
                stage = AuthStage.FIRESTORE_ACCOUNT_EXISTS,
                flow = "registration_check",
                detail = "Allowlisted Sample Rider auto-provisioned without commercial KYC"
            )
            return@runCatching RegistrationStatus.Completed(provisionedSampleAccount)
        }

        if (!userSnap.exists() || accountOrNull == null) {
            return@runCatching RegistrationStatus.PendingRegistration(
                verifiedMobileNumber = resolvedPhone,
                existingAccount = null
            )
        }
        val account = accountOrNull

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
        require(safePhone.isNotBlank()) { "Mobile number is required to complete registration." }
        require(safeEmail.isNotBlank() && safeEmail.contains("@")) { "A valid email address is required for GST invoice delivery." }
        require(safeEmergency.length >= 8) { "A valid emergency contact number is required." }
        require(safeEmergency != safePhone) { "Emergency contact number must be different from your own mobile number." }
        require(privacyConsentAccepted) { "Please accept Terms and Privacy Policy to continue." }

        val authUser = auth.currentUser
        val phoneVerifiedByAuth = !authUser?.phoneNumber.isNullOrBlank()
        val emailVerifiedByAuth = authUser?.isEmailVerified == true &&
            safeEmail.equals(authUser.email.orEmpty(), ignoreCase = true)

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
            photoUrl = existingAccount?.photoUrl?.ifBlank { authUser?.photoUrl?.toString().orEmpty() }
                ?: authUser?.photoUrl?.toString().orEmpty(),
            role = resolvedRole,
            preferredLanguage = resolvedLang,
            lowNetworkMode = existingAccount?.lowNetworkMode ?: false,
            privacyConsentAccepted = true,
            profileCompleted = true,
            emailVerified = emailVerifiedByAuth,
            mobileVerified = phoneVerifiedByAuth,
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
                            "emailVerified" to emailVerifiedByAuth,
                            "mobileVerified" to phoneVerifiedByAuth,
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

    suspend fun savePrivateEmergencyContacts(
        contacts: List<EmergencyContactEntry>
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val ownPhone = auth.currentUser?.phoneNumber?.trim().orEmpty()
        val sanitized = contacts
            .take(5)
            .mapIndexed { idx, entry ->
                val cleanPhone = entry.phone.trim().take(25)
                val cleanName = entry.name.trim().ifBlank { "Contact ${idx + 1}" }.take(80)
                val cleanRelation = entry.relationship.trim().ifBlank { "Emergency Contact" }.take(60)
                require(cleanPhone.length >= 8) { "Each emergency contact needs a valid phone number." }
                if (ownPhone.isNotBlank()) {
                    require(cleanPhone != ownPhone) { "Emergency contact number must be different from your own mobile number." }
                }
                EmergencyContactEntry(
                    id = entry.id.ifBlank { "ec_${idx + 1}" }.take(40),
                    name = cleanName,
                    phone = cleanPhone,
                    relationship = cleanRelation
                )
            }
        require(sanitized.isNotEmpty()) { "At least one emergency contact is required." }
        val primary = sanitized.first()
        val emergencyRef = db.collection("emergency_contacts").document(uid)
        val userRef = db.collection("users").document(uid)
        val existingSnap = emergencyRef.get().await()
        val contactMaps = sanitized.map { c ->
            mapOf(
                "id" to c.id,
                "name" to c.name,
                "phone" to c.phone,
                "relationship" to c.relationship
            )
        }
        try {
            db.runBatch { batch ->
                if (!existingSnap.exists()) {
                    batch.set(
                        emergencyRef,
                        mapOf(
                            "userId" to uid,
                            "emergencyContactNumber" to primary.phone,
                            "relationshipNote" to "${primary.name} (${primary.relationship})".take(80),
                            "contacts" to contactMaps,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                } else {
                    batch.update(
                        emergencyRef,
                        mapOf(
                            "emergencyContactNumber" to primary.phone,
                            "relationshipNote" to "${primary.name} (${primary.relationship})".take(80),
                            "contacts" to contactMaps,
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
        val existingSnap = try {
            ref.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, ref.path, auth)
            throw e
        }
        val payload = buildMap<String, Any> {
            put("userId", uid)
            if (profile.homeAddress.isNotBlank()) put("homeAddress", profile.homeAddress.trim())
            if (profile.workAddress.isNotBlank()) put("workAddress", profile.workAddress.trim())
            if (profile.nativeVillage.isNotBlank()) put("nativeVillage", profile.nativeVillage.trim())
            if (profile.nativeDistrict.isNotBlank()) put("nativeDistrict", profile.nativeDistrict.trim())
            if (!existingSnap.exists()) put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            if (!existingSnap.exists()) {
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
            put("is_test_ride", booking.isTestRide)
            put("test_mode", booking.testMode)
            if (booking.testOtpStatus.isNotBlank()) put("test_otp_status", booking.testOtpStatus)
            if (booking.testOtpExpiresAtMs > 0L) put("test_otp_expires_at_ms", booking.testOtpExpiresAtMs)
            val reqExpiry = if (booking.requestExpiresAtMs > 0L) booking.requestExpiresAtMs else System.currentTimeMillis() + RIDE_REQUEST_TIMEOUT_MS
            put("requestExpiresAtMs", reqExpiry)
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

    suspend fun confirmOfferAtomically(
        bookingId: String,
        offer: RideOffer,
        otpValiditySeconds: Long = 600L
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val bookingRef = db.collection("bookings").document(bookingId)
        val offerRef = db.collection("ride_offers").document(offer.id)
        val assignmentRef = db.collection("ride_assignments").document(bookingId)
        val otpRef = db.collection("ride_otps").document(bookingId)
        val secretRef = db.collection("ride_otp_secrets").document(bookingId)

        val otpPlaintext = TestRideOtpUtil.generateSixDigitOtp()
        val otpHash = TestRideOtpUtil.sha256Hex(bookingId, otpPlaintext)
        val expiresAtMs = TestRideOtpUtil.computeExpiresAtMs(System.currentTimeMillis(), otpValiditySeconds)
        val expiresAtTs = Timestamp(Date(expiresAtMs))

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
                        "otp_status" to "pending",
                        "otp_expires_at_ms" to expiresAtMs,
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
                        "is_test_ride" to false,
                        "assignedByUid" to uid,
                        "assignedByRole" to "customer",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                tx.set(
                    otpRef,
                    mapOf(
                        "bookingId" to bookingId,
                        "customerId" to uid,
                        "otpHash" to otpHash,
                        "submittedHash" to "",
                        "used" to false,
                        "overridden" to false,
                        "overrideReason" to "",
                        "overriddenByUid" to "",
                        "verifiedByUid" to "",
                        "attempts" to 0L,
                        "maxAttempts" to 5L,
                        "lastAttemptAtMs" to 0L,
                        "expiresAt" to expiresAtTs,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                tx.set(
                    secretRef,
                    mapOf(
                        "bookingId" to bookingId,
                        "customerId" to uid,
                        "otpPlaintext" to otpPlaintext,
                        "isTestRide" to false,
                        "status" to "pending",
                        "expiresAt" to expiresAtTs,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()

            // Populate participant contact bridge now that booking status is committed as 'assigned'
            runCatching {
                val userSnap = db.collection("users").document(uid).get().await()
                val custPhone = (userSnap.getString("mobileNumber") ?: userSnap.getString("phone")).orEmpty()
                val custName = (userSnap.getString("fullName") ?: "Customer").substringBefore(" ").take(80)
                val driverDirSnap = db.collection("driver_profiles").document(offer.driverId).get().await()
                val driverPhone = driverDirSnap.getString("phone").orEmpty()
                db.collection("booking_contacts").document(bookingId)
                    .set(
                        buildMap<String, Any> {
                            put("bookingId", bookingId)
                            put("customerId", uid)
                            put("driverId", offer.driverId)
                            if (custPhone.isNotBlank()) put("customerPhone", custPhone.take(25))
                            if (custName.isNotBlank()) put("customerFirstName", custName)
                            if (driverPhone.isNotBlank()) put("driverPhone", driverPhone.take(25))
                            put("driverName", offer.driverName.take(100))
                            put("updatedAt", FieldValue.serverTimestamp())
                        },
                        SetOptions.merge()
                    ).await()
            }

            // Notify customer (NEVER put raw OTP in notification text!)
            val notifRef = db.collection("notifications").document()
            notifRef.set(
                mapOf(
                    "userId" to uid,
                    "title" to "Ride Confirmed",
                    "body" to "Driver ${offer.driverName} confirmed at ₹${offer.proposedFareInr.toInt()}. Open Active Ride to view your 6-digit Pickup OTP.",
                    "type" to "booking_assigned",
                    "bookingId" to bookingId,
                    "entityId" to bookingId,
                    "targetApp" to "customer",
                    "targetScreen" to "booking",
                    "isTestRide" to false,
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
        "otp_pending"
    }

    suspend fun cancelBooking(
        bookingId: String,
        reason: String,
        cancelledByRole: String = "customer",
        cancellationCategory: String = ""
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val ref = db.collection("bookings").document(bookingId)
        try {
            ref.update(
                buildMap<String, Any> {
                    put("status", "cancelled")
                    put("cancellationReason", reason.ifBlank { "Cancelled by $cancelledByRole" }.take(250))
                    put("cancelledBy", uid)
                    put("cancelledByRole", cancelledByRole)
                    if (cancellationCategory.isNotBlank()) put("cancellationCategory", cancellationCategory.take(100))
                    put("updatedAt", FieldValue.serverTimestamp())
                }
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path, auth)
            throw e
        }
    }

    suspend fun cancelBookingByDriver(
        booking: Booking,
        reason: String,
        category: String = ""
    ): Result<Unit> = cancelBooking(
        bookingId = booking.id,
        reason = reason.ifBlank { "Cancelled by driver" },
        cancelledByRole = "driver",
        cancellationCategory = category
    )

    suspend fun submitCancellationAppeal(
        bookingId: String,
        appellantRole: String,
        reason: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val trimmed = reason.trim()
        require(trimmed.length >= 5) { "Please provide a clear explanation (at least 5 characters)." }
        val ref = db.collection("cancellation_appeals").document()
        try {
            ref.set(
                mapOf(
                    "bookingId" to bookingId,
                    "appellantId" to uid,
                    "appellantRole" to appellantRole,
                    "reason" to trimmed.take(400),
                    "status" to "open",
                    "adminNote" to "",
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

    fun observeMyCancellationAppeals(uid: String): Flow<List<CancellationAppeal>> =
        safeQueryFlow("cancellation_appeals", db.collection("cancellation_appeals").whereEqualTo("appellantId", uid)) {
            it.toObject(CancellationAppeal::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAdminCancellationAppeals(): Flow<List<CancellationAppeal>> =
        safeQueryFlow("cancellation_appeals", db.collection("cancellation_appeals")) {
            it.toObject(CancellationAppeal::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    suspend fun adminResolveCancellationAppeal(
        appealId: String,
        decision: String,
        adminNote: String
    ): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        require(decision in listOf("accepted", "rejected")) { "Appeal decision must be accepted or rejected." }
        val ref = db.collection("cancellation_appeals").document(appealId)
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.update(
                    ref,
                    mapOf(
                        "status" to decision,
                        "adminNote" to adminNote.trim().take(300),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "APPEAL_${decision.uppercase()}",
                        "targetCollection" to "cancellation_appeals",
                        "targetId" to appealId,
                        "summary" to "Resolved cancellation appeal as $decision",
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

    suspend fun submitRating(
        booking: Booking,
        stars: Int,
        review: String,
        driverBehaviorRating: Int = stars,
        vehicleConditionRating: Int = stars,
        punctualityRating: Int = stars,
        safetyRating: Int = stars
    ): Result<Unit> = runCatching {
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
                        put("driverBehaviorRating", driverBehaviorRating.coerceIn(1, 5))
                        put("vehicleConditionRating", vehicleConditionRating.coerceIn(1, 5))
                        put("punctualityRating", punctualityRating.coerceIn(1, 5))
                        put("safetyRating", safetyRating.coerceIn(1, 5))
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

    suspend fun submitCustomerFeedbackByDriver(
        bookingId: String,
        stars: Int,
        note: String
    ): Result<Unit> = runCatching {
        requireUserId()
        val bookingRef = db.collection("bookings").document(bookingId)
        try {
            bookingRef.update(
                buildMap<String, Any> {
                    put("customerFeedbackRating", stars.coerceIn(1, 5))
                    if (note.isNotBlank()) put("customerFeedbackNote", note.trim().take(250))
                    put("updatedAt", FieldValue.serverTimestamp())
                }
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, bookingRef.path, auth)
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

    suspend fun markAllNotificationsRead(notificationIds: List<String>): Result<Unit> = runCatching {
        requireUserId()
        val cleanIds = notificationIds.filter { it.isNotBlank() }.distinct().take(40)
        if (cleanIds.isEmpty()) return@runCatching
        try {
            db.runBatch { batch ->
                cleanIds.forEach { id ->
                    batch.update(db.collection("notifications").document(id), "read", true)
                }
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "notifications", auth)
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
        val nowMs = System.currentTimeMillis()
        val remainingCooldownSec = SosCooldownGuard.remainingCooldownSeconds(uid, nowMs)
        require(remainingCooldownSec <= 0L) {
            "SOS alert recently triggered. Please wait ${remainingCooldownSec}s before sending another SOS."
        }
        val ref = db.collection("emergency_events").document()
        val payload = buildMap<String, Any> {
            put("userId", uid)
            if (bookingId.isNotBlank()) put("bookingId", bookingId)
            put("lat", lat)
            put("lng", lng)
            put("emergencyContactRef", "emergency_contacts/$uid")
            put("notes", notes.ifBlank { "Emergency SOS triggered from GOLOCAL app" }.take(300))
            put("status", "active")
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            ref.set(payload).await()
            SosCooldownGuard.markTriggered(uid, nowMs)
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

    suspend fun uploadKycDocumentPhoto(docType: String, localUriString: String): Result<String> = runCatching {
        val uid = requireUserId()
        val trimmed = localUriString.trim()
        require(trimmed.isNotBlank()) { "Document URI is empty." }
        if (trimmed.startsWith("https://", ignoreCase = true)) {
            return@runCatching trimmed
        }
        val safeDocType = docType.lowercase().replace(Regex("[^a-z0-9_-]"), "_").ifBlank { "doc" }
        val storageRef = Firebase.storage.reference
            .child("kyc_docs/$uid/${safeDocType}_${System.currentTimeMillis()}.jpg")
        storageRef.putFile(Uri.parse(trimmed)).await()
        storageRef.downloadUrl.await().toString()
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
            if (profile.pucNumber.isNotBlank()) put("pucNumber", profile.pucNumber.trim().uppercase())
            if (profile.fitnessCertificateNumber.isNotBlank()) {
                put("fitnessCertificateNumber", profile.fitnessCertificateNumber.trim().uppercase())
            }
            if (profile.dlExpiryEpochDay > 0L) put("dlExpiryEpochDay", profile.dlExpiryEpochDay)
            if (profile.rcExpiryEpochDay > 0L) put("rcExpiryEpochDay", profile.rcExpiryEpochDay)
            if (profile.insuranceExpiryEpochDay > 0L) put("insuranceExpiryEpochDay", profile.insuranceExpiryEpochDay)
            if (profile.permitExpiryEpochDay > 0L) put("permitExpiryEpochDay", profile.permitExpiryEpochDay)
            if (profile.fitnessExpiryEpochDay > 0L) put("fitnessExpiryEpochDay", profile.fitnessExpiryEpochDay)
            if (profile.pucExpiryEpochDay > 0L) put("pucExpiryEpochDay", profile.pucExpiryEpochDay)
            if (profile.dlPhotoUri.isNotBlank()) put("dlPhotoUri", profile.dlPhotoUri)
            if (profile.rcPhotoUri.isNotBlank()) put("rcPhotoUri", profile.rcPhotoUri)
            if (profile.insurancePhotoUri.isNotBlank()) put("insurancePhotoUri", profile.insurancePhotoUri)
            if (profile.permitPhotoUri.isNotBlank()) put("permitPhotoUri", profile.permitPhotoUri)
            if (profile.fitnessPhotoUri.isNotBlank()) put("fitnessPhotoUri", profile.fitnessPhotoUri)
            if (profile.pucPhotoUri.isNotBlank()) put("pucPhotoUri", profile.pucPhotoUri)
            if (profile.emergencyContactName.isNotBlank()) put("emergencyContactName", profile.emergencyContactName.trim())
            if (profile.emergencyContactPhone.isNotBlank()) put("emergencyContactPhone", profile.emergencyContactPhone.trim())
            if (profile.policeVerificationNumber.isNotBlank()) put("policeVerificationNumber", profile.policeVerificationNumber.trim())
            if (profile.alternateIdType.isNotBlank()) put("alternateIdType", profile.alternateIdType.trim())
            if (profile.alternateIdLast4.isNotBlank()) put("alternateIdLast4", profile.alternateIdLast4.trim().takeLast(4))
            if (profile.operatingState.isNotBlank()) put("operatingState", profile.operatingState.trim().uppercase())
            if (profile.welfareInsurancePolicyNo.isNotBlank()) put("welfareInsurancePolicyNo", profile.welfareInsurancePolicyNo.trim())
            put("driverTrainingCompleted", profile.driverTrainingCompleted)
            put("complianceTermsAccepted", profile.complianceTermsAccepted)
            put("verificationVersion", profile.verificationVersion.coerceAtLeast(1L))
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

    suspend fun updateDriverLiveLocation(
        activeBooking: Booking?,
        isOnline: Boolean,
        isVerified: Boolean,
        lat: Double,
        lng: Double,
        headingDeg: Double = 0.0,
        speedKmph: Double = 0.0
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        if (!com.example.map.GpsBroadcastThrottle.isValidCoordinate(lat, lng)) return@runCatching
        val dirRef = db.collection("driver_directory").document(uid)
        val hasAssignedTrip = activeBooking != null &&
            activeBooking.assignedDriverId == uid &&
            activeBooking.status in setOf("assigned", "arriving", "arrived", "in_progress")

        try {
            db.runBatch { batch ->
                if (isVerified && isOnline) {
                    batch.update(
                        dirRef,
                        mapOf(
                            "currentLat" to lat,
                            "currentLng" to lng,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                if (hasAssignedTrip && activeBooking != null) {
                    val isTest = activeBooking.isTestRide || activeBooking.testMode
                    val locRef = db.collection("trip_locations").document(activeBooking.id)
                    batch.set(
                        locRef,
                        mapOf(
                            "bookingId" to activeBooking.id,
                            "customerId" to activeBooking.customerId,
                            "driverId" to uid,
                            "lat" to lat,
                            "lng" to lng,
                            "isTestRide" to isTest,
                            "isSimulated" to false,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    val liveRef = db.collection("live_locations").document(uid)
                    batch.set(
                        liveRef,
                        mapOf(
                            "driverId" to uid,
                            "bookingId" to activeBooking.id,
                            "customerId" to activeBooking.customerId,
                            "lat" to lat,
                            "lng" to lng,
                            "headingDeg" to headingDeg.coerceIn(0.0, 360.0),
                            "speedKmph" to speedKmph.coerceAtLeast(0.0),
                            "isTestRide" to isTest,
                            "isSimulated" to false,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, dirRef.path, auth)
            throw e
        }
    }

    fun observeOpenBookingsForDrivers(): Flow<List<Booking>> =
        safeQueryFlow(
            "bookings",
            db.collection("bookings")
                .whereEqualTo("status", "searching")
                .whereEqualTo("is_test_ride", false)
        ) {
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

    suspend fun updateDriverSelfProfile(
        legalName: String,
        phone: String,
        address: String,
        settlementUpiOrBank: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val cleanName = legalName.trim().take(100)
        val cleanPhone = phone.trim().take(25)
        val cleanAddress = address.trim().take(240)
        val cleanUpi = settlementUpiOrBank.trim().take(100)
        require(cleanName.length >= 2) { "Full name must be at least 2 characters." }
        require(cleanPhone.length >= 8) { "Enter a valid mobile number." }

        val driverRef = db.collection("driver_profiles").document(uid)
        val userRef = db.collection("users").document(uid)
        try {
            db.runBatch { batch ->
                batch.update(
                    driverRef,
                    mapOf(
                        "phone" to cleanPhone,
                        "address" to cleanAddress,
                        "settlementUpiOrBank" to cleanUpi,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.update(
                    userRef,
                    mapOf(
                        "fullName" to cleanName,
                        "phone" to cleanPhone,
                        "mobileNumber" to cleanPhone,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, driverRef.path, auth)
            throw e
        }
    }

    suspend fun rejectOpenBooking(
        bookingId: String,
        reason: String = "declined"
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(bookingId.isNotBlank()) { "Booking ID is required." }
        val docId = "${bookingId}_${uid}"
        val ref = db.collection("ride_rejections").document(docId)
        try {
            ref.set(
                mapOf(
                    "bookingId" to bookingId,
                    "driverId" to uid,
                    "reason" to reason.take(60),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path, auth)
            throw e
        }
    }

    fun observeMyRejectedBookingIds(driverId: String): Flow<Set<String>> {
        val currentUid = auth.currentUser?.uid
        if (driverId.isBlank() || currentUid.isNullOrBlank() || currentUid != driverId) {
            return flowOf(emptySet())
        }
        return safeQueryFlow(
            "ride_rejections",
            db.collection("ride_rejections").whereEqualTo("driverId", driverId)
        ) { doc ->
            doc.getString("bookingId")?.takeIf { it.isNotBlank() }
        }.map { it.toSet() }
    }

    suspend fun acceptBookingAtomically(
        booking: Booking,
        driver: DriverProfile,
        fareInr: Double,
        currentLat: Double = 22.9041,
        currentLng: Double = 88.3976,
        otpValiditySeconds: Long = 600L
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(driver.driverId == uid && driver.verificationStatus == "verified") {
            "Only verified drivers can accept ride requests."
        }
        require(!booking.isTestRide && !booking.testMode) {
            "Commercial drivers cannot accept isolated TEST RIDE bookings."
        }
        require(FareCalculator.isCategoryCompatible(driver.vehicleCategory, booking.vehicleCategory)) {
            "Vehicle category (${driver.vehicleCategory.uppercase()}) does not match ${booking.vehicleCategory.uppercase()}."
        }
        if (booking.requestExpiresAtMs > 0L && System.currentTimeMillis() > booking.requestExpiresAtMs) {
            throw IllegalStateException("This ride request has expired.")
        }
        val effectiveFare = fareInr.coerceIn(booking.minPermittedFareInr, booking.maxPermittedFareInr)
        val vehicleLabel = listOf(driver.vehicleModel, driver.vehicleRegNumber)
            .filter { it.isNotBlank() }.joinToString(" • ")
        val otpPlaintext = TestRideOtpUtil.generateSixDigitOtp()
        val otpHash = TestRideOtpUtil.sha256Hex(booking.id, otpPlaintext)
        val expiresAtMs = TestRideOtpUtil.computeExpiresAtMs(System.currentTimeMillis(), otpValiditySeconds)
        val expiresAtTs = Timestamp(Date(expiresAtMs))

        val bookingRef = db.collection("bookings").document(booking.id)
        val assignmentRef = db.collection("ride_assignments").document(booking.id)
        val otpRef = db.collection("ride_otps").document(booking.id)
        val secretRef = db.collection("ride_otp_secrets").document(booking.id)

        try {
            db.runTransaction { tx ->
                val snap = tx.get(bookingRef)
                val status = snap.getString("status") ?: ""
                val existingAssignment = tx.get(assignmentRef)
                if (status != "searching" || existingAssignment.exists()) {
                    throw IllegalStateException("This ride was already accepted by another rider.")
                }
                tx.update(
                    bookingRef,
                    mapOf(
                        "status" to "assigned",
                        "assignedDriverId" to uid,
                        "assignedDriverName" to driver.legalName.ifBlank { "GoLocal Partner" },
                        "assignedVehicleLabel" to vehicleLabel,
                        "agreedFareInr" to effectiveFare,
                        "otp_status" to "pending",
                        "otp_expires_at_ms" to expiresAtMs,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                tx.set(
                    assignmentRef,
                    mapOf(
                        "bookingId" to booking.id,
                        "customerId" to booking.customerId,
                        "driverId" to uid,
                        "offerId" to "direct_accept_${booking.id}",
                        "lockedFareInr" to effectiveFare,
                        "status" to "active",
                        "is_test_ride" to false,
                        "assignedByUid" to uid,
                        "assignedByRole" to "driver",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                tx.set(
                    otpRef,
                    mapOf(
                        "bookingId" to booking.id,
                        "customerId" to booking.customerId,
                        "otpHash" to otpHash,
                        "submittedHash" to "",
                        "used" to false,
                        "overridden" to false,
                        "overrideReason" to "",
                        "overriddenByUid" to "",
                        "verifiedByUid" to "",
                        "attempts" to 0L,
                        "maxAttempts" to 5L,
                        "lastAttemptAtMs" to 0L,
                        "expiresAt" to expiresAtTs,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                tx.set(
                    secretRef,
                    mapOf(
                        "bookingId" to booking.id,
                        "customerId" to booking.customerId,
                        "otpPlaintext" to otpPlaintext,
                        "isTestRide" to false,
                        "status" to "pending",
                        "expiresAt" to expiresAtTs,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()

            // Seed initial trip location & contact bridge best-effort after lock commit
            val startLat = currentLat.takeIf { com.example.map.GpsBroadcastThrottle.isValidCoordinate(it, currentLng) }
                ?: booking.pickupLat
            val startLng = currentLng.takeIf { com.example.map.GpsBroadcastThrottle.isValidCoordinate(currentLat, it) }
                ?: booking.pickupLng
            runCatching {
                db.runBatch { batch ->
                    batch.set(
                        db.collection("trip_locations").document(booking.id),
                        mapOf(
                            "bookingId" to booking.id,
                            "customerId" to booking.customerId,
                            "driverId" to uid,
                            "lat" to startLat,
                            "lng" to startLng,
                            "isTestRide" to false,
                            "isSimulated" to false,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    batch.set(
                        db.collection("live_locations").document(uid),
                        mapOf(
                            "driverId" to uid,
                            "bookingId" to booking.id,
                            "customerId" to booking.customerId,
                            "lat" to startLat,
                            "lng" to startLng,
                            "isTestRide" to false,
                            "isSimulated" to false,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    batch.set(
                        db.collection("booking_contacts").document(booking.id),
                        buildMap<String, Any> {
                            put("bookingId", booking.id)
                            put("customerId", booking.customerId)
                            put("driverId", uid)
                            put("customerFirstName", booking.customerName.trim().substringBefore(" ").take(80))
                            if (driver.phone.isNotBlank()) put("driverPhone", driver.phone.trim().take(25))
                            put("driverName", driver.legalName.trim().take(100))
                            put("updatedAt", FieldValue.serverTimestamp())
                        },
                        SetOptions.merge()
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

    suspend fun verifyCommercialRideOtpAndStartTrip(
        booking: Booking,
        enteredOtp: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val cleanOtp = enteredOtp.trim()
        require(!booking.isTestRide && !booking.testMode) { "Use Test Ride OTP verification for test rides." }
        require(booking.assignedDriverId == uid) { "Only the assigned rider can verify Pickup OTP." }
        require(booking.status == "arrived") { "Mark arrived at pickup before verifying Pickup OTP." }
        require(TestRideOtpUtil.isValidOtpFormat(cleanOtp)) { "Enter the exact 6-digit numeric Pickup OTP." }
        if (booking.otpExpiresAtMs > 0L && TestRideOtpUtil.isExpired(booking.otpExpiresAtMs)) {
            throw IllegalStateException("Pickup OTP has expired. Ask customer or Owner Admin for assistance.")
        }

        val submittedHash = TestRideOtpUtil.sha256Hex(booking.id, cleanOtp)
        val otpRef = db.collection("ride_otps").document(booking.id)
        val bookingRef = db.collection("bookings").document(booking.id)

        try {
            db.runBatch { batch ->
                batch.update(
                    otpRef,
                    mapOf(
                        "used" to true,
                        "submittedHash" to submittedHash,
                        "verifiedByUid" to uid,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.update(
                    bookingRef,
                    mapOf(
                        "status" to "in_progress",
                        "otp_status" to "verified",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
            runCatching {
                db.collection("ride_otp_secrets").document(booking.id)
                    .update(
                        mapOf(
                            "status" to "verified",
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fe = e.unwrapFirestoreError() as? FirebaseFirestoreException
            if (fe?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                runCatching {
                    otpRef.update(
                        mapOf(
                            "attempts" to FieldValue.increment(1L),
                            "lastAttemptAtMs" to System.currentTimeMillis(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
                val message = if (booking.otpExpiresAtMs > 0L && TestRideOtpUtil.isExpired(booking.otpExpiresAtMs)) {
                    "Pickup OTP expired. Ask Owner Admin for OTP override if needed."
                } else {
                    "Incorrect 6-digit Pickup OTP, cooldown active, or max attempts reached."
                }
                throw IllegalArgumentException(message)
            }
            handleFirestoreError(e, OperationType.WRITE, otpRef.path, auth)
            throw e
        }
    }

    suspend fun adminOverrideCommercialOtpAndStartRide(
        booking: Booking,
        reason: String,
        actorRole: String = "admin"
    ): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val cleanReason = reason.trim()
        require(!booking.isTestRide && !booking.testMode) { "Use Test OTP override for TEST RIDE bookings." }
        require(booking.status == "arrived") { "Ride must be in 'arrived' state to override Pickup OTP." }
        require(cleanReason.length >= 4) { "Provide an audit reason (at least 4 characters)." }

        val otpRef = db.collection("ride_otps").document(booking.id)
        val secretRef = db.collection("ride_otp_secrets").document(booking.id)
        val bookingRef = db.collection("bookings").document(booking.id)
        val auditRef = db.collection("audit_logs").document()

        try {
            db.runBatch { batch ->
                batch.update(
                    otpRef,
                    mapOf(
                        "used" to true,
                        "overridden" to true,
                        "overrideReason" to cleanReason.take(250),
                        "overriddenByUid" to actorUid,
                        "verifiedByUid" to actorUid,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.update(
                    secretRef,
                    mapOf(
                        "status" to "overridden",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.update(
                    bookingRef,
                    mapOf(
                        "status" to "in_progress",
                        "otp_status" to "overridden",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "COMMERCIAL_OTP_ADMIN_OVERRIDE",
                        "targetCollection" to "ride_otps",
                        "targetId" to booking.id,
                        "summary" to "[$actorRole] Overrode Commercial Pickup OTP for ${booking.id}: $cleanReason",
                        "isTestRide" to false,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, otpRef.path, auth)
            throw e
        }
    }

    suspend fun advanceDriverTripState(
        booking: Booking,
        nextStatus: String,
        enteredPin: String = "",
        driverLat: Double,
        driverLng: Double
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val isTest = booking.isTestRide || booking.testMode
        require(FareCalculator.isValidTransition(booking.status, nextStatus)) {
            "Invalid state transition from ${booking.status} to $nextStatus"
        }
        if (isTest && nextStatus == "in_progress") {
            throw IllegalStateException("Test rides must be started by 6-digit Customer Test OTP or Owner Admin override.")
        }
        if (!isTest && nextStatus == "in_progress") {
            throw IllegalStateException("Commercial rides must be started by verifying the Customer's 6-digit Pickup OTP or Owner Admin override.")
        }
        val bookingRef = db.collection("bookings").document(booking.id)
        val locRef = db.collection("trip_locations").document(booking.id)
        val liveRef = db.collection("live_locations").document(uid)

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
                            "isTestRide" to isTest,
                            "isSimulated" to false,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    batch.set(
                        liveRef,
                        mapOf(
                            "driverId" to uid,
                            "bookingId" to booking.id,
                            "customerId" to booking.customerId,
                            "lat" to driverLat,
                            "lng" to driverLng,
                            "isTestRide" to isTest,
                            "isSimulated" to false,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }.await()
            } else if (isTest) {
                // Non-commercial completion for assigned Test Rides: ZERO payments or commissions created
                val assignmentRef = db.collection("ride_assignments").document(booking.id)
                val actorEmail = auth.currentUser?.email ?: ""
                val auditRef = db.collection("audit_logs").document()
                db.runTransaction { tx ->
                    val snap = tx.get(bookingRef)
                    val curStatus = snap.getString("status") ?: ""
                    if (curStatus != "in_progress") {
                        throw IllegalStateException("Test ride is not in progress or was already completed.")
                    }
                    val hasAssignment = tx.get(assignmentRef).exists()
                    tx.update(
                        bookingRef,
                        mapOf(
                            "status" to "completed",
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    if (hasAssignment) {
                        tx.update(
                            assignmentRef,
                            mapOf(
                                "status" to "completed",
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                    tx.set(
                        auditRef,
                        mapOf(
                            "actorId" to uid,
                            "actorEmail" to actorEmail,
                            "action" to "TEST_RIDE_COMPLETED_BY_PARTNER",
                            "targetCollection" to "bookings",
                            "targetId" to booking.id,
                            "summary" to "Test partner completed TEST RIDE ${booking.id} (zero financial impact)",
                            "isTestRide" to true,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }.await()
            } else {
                // Idempotent completion + payment + commission ledger transaction (production rides only)
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
        val currentUser = auth.currentUser
        val hasAdminClaim = try {
            val claims = currentUser?.getIdToken(false)?.await()?.claims
            (claims?.get("admin") as? Boolean == true) ||
                (claims?.get("ownerAdmin") as? Boolean == true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (hasAdminClaim) {
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

    fun observeDriverVerificationHistory(driverId: String): Flow<List<DriverVerificationHistoryEntry>> =
        safeQueryFlow(
            "driver_verification_history",
            db.collection("driver_verification_history").whereEqualTo("driverId", driverId)
        ) {
            it.toObject(DriverVerificationHistoryEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAllVerificationHistory(): Flow<List<DriverVerificationHistoryEntry>> =
        safeQueryFlow("driver_verification_history", db.collection("driver_verification_history")) {
            it.toObject(DriverVerificationHistoryEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    suspend fun adminVerifyOrUpdateDriver(
        driverId: String,
        newStatus: String,
        reason: String = ""
    ): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val trimmedReason = reason.trim()
        if (newStatus in listOf("rejected", "suspended")) {
            require(trimmedReason.length >= 4) {
                "A specific reason is mandatory when rejecting or suspending a driver."
            }
        }
        val privRef = db.collection("driver_profiles").document(driverId)
        val dirRef = db.collection("driver_directory").document(driverId)
        val auditRef = db.collection("audit_logs").document()
        val historyRef = db.collection("driver_verification_history").document()
        val notifRef = db.collection("notifications").document()

        val existingSnap = privRef.get().await()
        val nextVersion = (existingSnap.getLong("verificationVersion") ?: 1L) + 1L

        try {
            db.runBatch { batch ->
                val updates = buildMap<String, Any> {
                    put("verificationStatus", newStatus)
                    put("verificationVersion", nextVersion)
                    put("reviewedBy", actorEmail.ifBlank { actorUid })
                    if (newStatus != "verified") put("isOnline", false)
                    if (trimmedReason.isNotBlank()) {
                        put("rejectionReason", trimmedReason)
                        put("reviewNote", trimmedReason)
                    } else if (newStatus == "verified") {
                        put("rejectionReason", "")
                        put("reviewNote", "Verified by GoLocal Admin")
                    }
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
                    historyRef,
                    mapOf(
                        "driverId" to driverId,
                        "reviewerId" to actorUid,
                        "reviewerEmail" to actorEmail,
                        "decision" to newStatus,
                        "reason" to trimmedReason.take(300),
                        "verificationVersion" to nextVersion,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "DRIVER_$newStatus".uppercase(),
                        "targetCollection" to "driver_profiles",
                        "targetId" to driverId,
                        "summary" to "Driver status changed to $newStatus (v$nextVersion) ${if (trimmedReason.isNotBlank()) "($trimmedReason)" else ""}".trim(),
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    notifRef,
                    mapOf(
                        "userId" to driverId,
                        "title" to "Driver Verification Update",
                        "body" to if (trimmedReason.isNotBlank()) {
                            "Your GOLOCAL driver status is now $newStatus: $trimmedReason"
                        } else {
                            "Your GOLOCAL driver status is now: $newStatus"
                        },
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

    suspend fun adminSetUserTestCustomer(targetUid: String, enabled: Boolean): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val ref = db.collection("users").document(targetUid)
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.update(ref, mapOf("isTestCustomer" to enabled, "updatedAt" to FieldValue.serverTimestamp()))
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to if (enabled) "ENABLE_TEST_CUSTOMER" else "DISABLE_TEST_CUSTOMER",
                        "targetCollection" to "users",
                        "targetId" to targetUid,
                        "summary" to "Set isTestCustomer=$enabled for user $targetUid",
                        "isTestRide" to true,
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

    suspend fun adminSetDriverTestPartner(driverId: String, enabled: Boolean): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val privRef = db.collection("driver_profiles").document(driverId)
        val dirRef = db.collection("driver_directory").document(driverId)
        val auditRef = db.collection("audit_logs").document()
        try {
            val dirExists = dirRef.get().await().exists()
            db.runBatch { batch ->
                batch.update(privRef, mapOf("isTestPartner" to enabled, "updatedAt" to FieldValue.serverTimestamp()))
                if (dirExists) {
                    batch.update(dirRef, mapOf("isTestPartner" to enabled, "updatedAt" to FieldValue.serverTimestamp()))
                }
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to if (enabled) "ENABLE_TEST_PARTNER" else "DISABLE_TEST_PARTNER",
                        "targetCollection" to "driver_profiles",
                        "targetId" to driverId,
                        "summary" to "Set isTestPartner=$enabled for partner $driverId",
                        "isTestRide" to true,
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
                        "testRideModeEnabled" to settings.testRideModeEnabled,
                        "testAutoAssignEnabled" to settings.testAutoAssignEnabled,
                        "testManualAssignEnabled" to settings.testManualAssignEnabled,
                        "testDriverSpeedKmph" to settings.testDriverSpeedKmph.coerceIn(5.0, 120.0),
                        "testOtpValiditySeconds" to settings.testOtpValiditySeconds.coerceIn(60L, 1800L),
                        "testAllowAdminOtpOverride" to settings.testAllowAdminOtpOverride,
                        "testAutoCompleteEnabled" to settings.testAutoCompleteEnabled,
                        "operatingStateCode" to settings.operatingStateCode.trim().uppercase().ifBlank { "WB" },
                        "aggregatorLicenceNumber" to settings.aggregatorLicenceNumber.trim(),
                        "grievanceOfficerName" to settings.grievanceOfficerName.trim(),
                        "grievanceOfficerEmail" to settings.grievanceOfficerEmail.trim(),
                        "bikeTaxiCommercialApproved" to settings.bikeTaxiCommercialApproved,
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
                        "summary" to "Updated platform settings (testRideMode=${settings.testRideModeEnabled})",
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

    fun observeOperationsStaff(): Flow<List<OperationsStaffEntry>> =
        safeQueryFlow("operations_staff", db.collection("operations_staff")) {
            it.toObject(OperationsStaffEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    suspend fun adminGrantOperationsAccess(
        targetUid: String,
        displayName: String,
        contactIdentifier: String,
        assignedDistrict: String
    ): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val cleanUid = targetUid.trim()
        val cleanName = displayName.trim()
        val cleanContact = contactIdentifier.trim()
        val cleanDistrict = assignedDistrict.trim()
        require(cleanUid.matches(Regex("^[a-zA-Z0-9_\\-]{2,128}$"))) {
            "Enter a valid Firebase UID (letters, numbers, _ or -)."
        }
        require(cleanName.length in 2..100) {
            "Staff name must be between 2 and 100 characters."
        }
        require(cleanDistrict.length in 2..80) {
            "Assigned district must be between 2 and 80 characters."
        }
        require(cleanContact.length <= 100) {
            "Contact identifier must be 100 characters or fewer."
        }
        val ref = db.collection("operations_staff").document(cleanUid)
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runTransaction { tx ->
                val existing = tx.get(ref)
                val payload = buildMap<String, Any> {
                    put("userId", cleanUid)
                    put("displayName", cleanName)
                    if (cleanContact.isNotBlank()) put("contactIdentifier", cleanContact)
                    put("assignedDistrict", cleanDistrict)
                    put("isActive", true)
                    put("grantedBy", actorUid)
                    if (actorEmail.isNotBlank()) put("grantedByEmail", actorEmail)
                    val existingCreatedAt = existing.getTimestamp("createdAt")
                    if (existing.exists() && existingCreatedAt != null) {
                        put("createdAt", existingCreatedAt)
                    } else {
                        put("createdAt", FieldValue.serverTimestamp())
                    }
                    put("updatedAt", FieldValue.serverTimestamp())
                }
                tx.set(ref, payload)
                tx.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "GRANT_OPERATIONS_STAFF",
                        "targetCollection" to "operations_staff",
                        "targetId" to cleanUid,
                        "summary" to "Granted scoped operations access to $cleanName ($cleanDistrict)",
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

    suspend fun adminRevokeOperationsAccess(targetUid: String): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val cleanUid = targetUid.trim()
        require(cleanUid.matches(Regex("^[a-zA-Z0-9_\\-]{2,128}$"))) {
            "Invalid staff UID."
        }
        val ref = db.collection("operations_staff").document(cleanUid)
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.update(
                    ref,
                    mapOf(
                        "isActive" to false,
                        "revokedBy" to actorUid,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "REVOKE_OPERATIONS_STAFF",
                        "targetCollection" to "operations_staff",
                        "targetId" to cleanUid,
                        "summary" to "Revoked scoped operations access for $cleanUid",
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

    // --- Admin-Controlled Live Test Ride Mode ---

    suspend fun checkIsOperationsStaff(uid: String): Boolean {
        return try {
            val snap = db.collection("operations_staff").document(uid).get().await()
            snap.exists() && snap.getBoolean("isActive") == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun observeTestRideTripLocations(): Flow<List<TripLocation>> =
        safeQueryFlow("trip_locations", db.collection("trip_locations")) {
            it.toObject(TripLocation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeTestRideOtpSecrets(): Flow<List<TestRideOtpSecret>> =
        safeQueryFlow("test_ride_otp_secrets", db.collection("test_ride_otp_secrets")) {
            it.toObject(TestRideOtpSecret::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeTestRideOtpChallenges(): Flow<List<TestRideOtpChallenge>> =
        safeQueryFlow("test_ride_otps", db.collection("test_ride_otps")) {
            it.toObject(TestRideOtpChallenge::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeTestAuditLogs(): Flow<List<AuditLog>> =
        safeQueryFlow("audit_logs", db.collection("audit_logs").whereEqualTo("isTestRide", true)) {
            it.toObject(AuditLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    suspend fun assignTestDriverToBooking(
        booking: Booking,
        testDriver: SimulatedTestDriver,
        otpValiditySeconds: Long,
        notifyCustomer: Boolean = true,
        actorRole: String = "admin"
    ): Result<String> = assignTestPartnerOrUnitToBooking(
        booking = booking,
        driverId = testDriver.id,
        driverName = testDriver.displayName,
        vehicleLabel = testDriver.vehicleLabel,
        isSimulated = testDriver.id.startsWith("test_driver_"),
        otpValiditySeconds = otpValiditySeconds,
        notifyCustomer = notifyCustomer,
        actorRole = actorRole
    )

    suspend fun assignTestPartnerToBooking(
        booking: Booking,
        partner: DriverProfile,
        otpValiditySeconds: Long,
        notifyCustomer: Boolean = true,
        actorRole: String = "admin"
    ): Result<String> {
        require(partner.verificationStatus == "verified" && partner.isTestPartner) {
            "Selected driver must be KYC verified and authorized as a Test Partner."
        }
        val vehicleLabel = listOf(partner.vehicleModel, partner.vehicleRegNumber)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
            .ifBlank { partner.vehicleCategory.uppercase() }
        return assignTestPartnerOrUnitToBooking(
            booking = booking,
            driverId = partner.driverId,
            driverName = partner.legalName,
            vehicleLabel = vehicleLabel,
            isSimulated = false,
            otpValiditySeconds = otpValiditySeconds,
            notifyCustomer = notifyCustomer,
            actorRole = actorRole
        )
    }

    private suspend fun assignTestPartnerOrUnitToBooking(
        booking: Booking,
        driverId: String,
        driverName: String,
        vehicleLabel: String,
        isSimulated: Boolean,
        otpValiditySeconds: Long,
        notifyCustomer: Boolean,
        actorRole: String
    ): Result<String> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        require(booking.isTestRide || booking.testMode) {
            "Only TEST RIDE bookings can be assigned a test partner or simulated unit."
        }
        val otpPlaintext = TestRideOtpUtil.generateSixDigitOtp()
        val otpHash = TestRideOtpUtil.sha256Hex(booking.id, otpPlaintext)
        val expiresAtMs = TestRideOtpUtil.computeExpiresAtMs(System.currentTimeMillis(), otpValiditySeconds)
        val expiresAtTs = Timestamp(Date(expiresAtMs))
        val initialPt = TestRideRouteSimulator.initialApproachPoint(booking.pickupLat, booking.pickupLng)
        val lockedFare = booking.estimatedFareInr.coerceAtLeast(1.0)
        val cleanRole = if (actorRole == "ops_staff") "ops_staff" else "admin"

        val bookingRef = db.collection("bookings").document(booking.id)
        val assignmentRef = db.collection("ride_assignments").document(booking.id)
        val otpChallengeRef = db.collection("test_ride_otps").document(booking.id)
        val otpSecretRef = db.collection("test_ride_otp_secrets").document(booking.id)
        val customerOtpSecretRef = db.collection("ride_otp_secrets").document(booking.id)
        val locationRef = db.collection("trip_locations").document(booking.id)
        val liveLocationRef = db.collection("live_locations").document(driverId)
        val auditRef = db.collection("audit_logs").document()

        try {
            db.runBatch { batch ->
                batch.update(
                    bookingRef,
                    mapOf(
                        "status" to "assigned",
                        "assignedDriverId" to driverId,
                        "assignedDriverName" to driverName,
                        "assignedVehicleLabel" to vehicleLabel,
                        "agreedFareInr" to lockedFare,
                        "test_otp_status" to "pending",
                        "test_otp_expires_at_ms" to expiresAtMs,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    assignmentRef,
                    mapOf(
                        "bookingId" to booking.id,
                        "customerId" to booking.customerId,
                        "driverId" to driverId,
                        "offerId" to "test_dispatch_${booking.id}",
                        "lockedFareInr" to lockedFare,
                        "status" to "active",
                        "is_test_ride" to true,
                        "assignedByUid" to actorUid,
                        "assignedByRole" to cleanRole,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    otpChallengeRef,
                    mapOf(
                        "bookingId" to booking.id,
                        "customerId" to booking.customerId,
                        "otpHash" to otpHash,
                        "submittedHash" to "",
                        "used" to false,
                        "overridden" to false,
                        "overrideReason" to "",
                        "overrideRequestedByUid" to "",
                        "overrideRequestedReason" to "",
                        "overriddenByUid" to "",
                        "verifiedByUid" to "",
                        "attempts" to 0L,
                        "maxAttempts" to 5L,
                        "expiresAt" to expiresAtTs,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    otpSecretRef,
                    mapOf(
                        "bookingId" to booking.id,
                        "customerId" to booking.customerId,
                        "otpPlaintext" to otpPlaintext,
                        "status" to "pending",
                        "issuedByUid" to actorUid,
                        "issuedByRole" to cleanRole,
                        "expiresAt" to expiresAtTs,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                if (!isSimulated) {
                    batch.set(
                        customerOtpSecretRef,
                        mapOf(
                            "bookingId" to booking.id,
                            "customerId" to booking.customerId,
                            "otpPlaintext" to otpPlaintext,
                            "isTestRide" to true,
                            "status" to "pending",
                            "expiresAt" to expiresAtTs,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.set(
                    locationRef,
                    mapOf(
                        "bookingId" to booking.id,
                        "customerId" to booking.customerId,
                        "driverId" to driverId,
                        "lat" to initialPt.lat,
                        "lng" to initialPt.lng,
                        "isTestRide" to true,
                        "isSimulated" to isSimulated,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    liveLocationRef,
                    mapOf(
                        "driverId" to driverId,
                        "bookingId" to booking.id,
                        "customerId" to booking.customerId,
                        "lat" to initialPt.lat,
                        "lng" to initialPt.lng,
                        "isTestRide" to true,
                        "isSimulated" to isSimulated,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                if (notifyCustomer) {
                    val notifRef = db.collection("notifications").document()
                    batch.set(
                        notifRef,
                        mapOf(
                            "userId" to booking.customerId,
                            "title" to "TEST RIDE Assigned • Test OTP Ready",
                            "body" to "TEST DRIVER $driverName assigned. Open Active Ride in GoLocal to view your 6-digit Test OTP.",
                            "type" to "test_otp",
                            "bookingId" to booking.id,
                            "entityId" to booking.id,
                            "targetApp" to "customer",
                            "targetScreen" to "booking",
                            "isTestRide" to true,
                            "read" to false,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "TEST_RIDE_ASSIGN_DRIVER",
                        "targetCollection" to "bookings",
                        "targetId" to booking.id,
                        "summary" to "[$cleanRole] Assigned $driverName (${if (isSimulated) "Simulated" else "Verified Test Partner"}) to TEST RIDE ${booking.id}",
                        "isTestRide" to true,
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
        otpPlaintext
    }

    suspend fun updateTestRideStatus(
        booking: Booking,
        nextStatus: String,
        reason: String = "",
        actorRole: String = "admin"
    ): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val cleanRole = if (actorRole == "ops_staff") "ops_staff" else "admin"
        require(booking.isTestRide || booking.testMode) { "Only TEST RIDE bookings can use test controls." }
        require(TestRideStateMachine.isValidTransition(booking.status, nextStatus)) {
            "Invalid test ride transition from ${booking.status} to $nextStatus."
        }
        val bookingRef = db.collection("bookings").document(booking.id)
        val assignmentRef = db.collection("ride_assignments").document(booking.id)
        val auditRef = db.collection("audit_logs").document()
        try {
            val assignmentExists = if (nextStatus in setOf("completed", "cancelled")) {
                assignmentRef.get().await().exists()
            } else {
                false
            }
            db.runBatch { batch ->
                val updates = buildMap<String, Any> {
                    put("status", nextStatus)
                    if (nextStatus == "cancelled") {
                        put("cancellationReason", reason.ifBlank { "Cancelled from Admin Test Ride Control" }.take(250))
                        put("cancelledBy", actorUid)
                        put("cancelledByRole", cleanRole)
                    }
                    put("updatedAt", FieldValue.serverTimestamp())
                }
                batch.update(bookingRef, updates)
                if (assignmentExists && nextStatus in setOf("completed", "cancelled")) {
                    batch.update(
                        assignmentRef,
                        mapOf(
                            "status" to nextStatus,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                if (nextStatus in setOf("arriving", "arrived", "completed", "cancelled")) {
                    val notifRef = db.collection("notifications").document()
                    val (title, body) = when (nextStatus) {
                        "arriving" -> "TEST DRIVER En Route" to "Test driver is heading to ${booking.pickupLabel}."
                        "arrived" -> "TEST DRIVER Arrived" to "Test driver arrived at pickup. Verify Test OTP to start ride."
                        "completed" -> "TEST RIDE Completed" to "Test ride ended (${booking.pickupLabel} -> ${booking.destinationLabel}). No production charge recorded."
                        else -> "TEST RIDE Cancelled" to reason.ifBlank { "Test ride cancelled by Admin/Ops." }.take(250)
                    }
                    batch.set(
                        notifRef,
                        mapOf(
                            "userId" to booking.customerId,
                            "title" to title,
                            "body" to body,
                            "type" to "test_ride",
                            "bookingId" to booking.id,
                            "entityId" to booking.id,
                            "targetApp" to "customer",
                            "targetScreen" to if (nextStatus in setOf("completed", "cancelled")) "bookings" else "booking",
                            "isTestRide" to true,
                            "read" to false,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "TEST_RIDE_STATUS_${nextStatus.uppercase()}",
                        "targetCollection" to "bookings",
                        "targetId" to booking.id,
                        "summary" to "[$cleanRole] Changed TEST RIDE ${booking.id} from ${booking.status} to $nextStatus",
                        "isTestRide" to true,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, bookingRef.path, auth)
            throw e
        }
    }

    suspend fun updateTestRideTripLocation(
        booking: Booking,
        lat: Double,
        lng: Double
    ): Result<Unit> = runCatching {
        requireUserId()
        require(booking.isTestRide || booking.testMode) { "Not a test ride." }
        val resolvedDriverId = booking.assignedDriverId.ifBlank { "test_driver_toto_01" }
        val isSimulated = resolvedDriverId.startsWith("test_driver_")
        val locRef = db.collection("trip_locations").document(booking.id)
        val liveRef = db.collection("live_locations").document(resolvedDriverId)
        try {
            db.runBatch { batch ->
                batch.set(
                    locRef,
                    mapOf(
                        "bookingId" to booking.id,
                        "customerId" to booking.customerId,
                        "driverId" to resolvedDriverId,
                        "lat" to lat,
                        "lng" to lng,
                        "isTestRide" to true,
                        "isSimulated" to isSimulated,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    liveRef,
                    mapOf(
                        "driverId" to resolvedDriverId,
                        "bookingId" to booking.id,
                        "customerId" to booking.customerId,
                        "lat" to lat,
                        "lng" to lng,
                        "isTestRide" to true,
                        "isSimulated" to isSimulated,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, locRef.path, auth)
            throw e
        }
    }

    suspend fun sendTestOtpNotificationToCustomer(
        booking: Booking,
        otpPlaintext: String,
        actorRole: String = "admin"
    ): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val cleanRole = if (actorRole == "ops_staff") "ops_staff" else "admin"
        require(TestRideOtpUtil.isValidOtpFormat(otpPlaintext)) { "Valid 6-digit test OTP required." }
        val notifRef = db.collection("notifications").document()
        val auditRef = db.collection("audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.set(
                    notifRef,
                    mapOf(
                        "userId" to booking.customerId,
                        "title" to "TEST OTP • Pickup Verification Ready",
                        "body" to "Pickup verification Test OTP is ready for ${booking.pickupLabel}. Open Active Ride in GoLocal to view it.",
                        "type" to "test_otp",
                        "bookingId" to booking.id,
                        "entityId" to booking.id,
                        "targetApp" to "customer",
                        "targetScreen" to "booking",
                        "isTestRide" to true,
                        "read" to false,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "TEST_OTP_SENT",
                        "targetCollection" to "notifications",
                        "targetId" to booking.id,
                        "summary" to "[$cleanRole] Dispatched TEST OTP notification for TEST RIDE ${booking.id}",
                        "isTestRide" to true,
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

    suspend fun verifyTestRideOtpByCustomer(
        booking: Booking,
        enteredOtp: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val cleanOtp = enteredOtp.trim()
        require(booking.isTestRide || booking.testMode) { "Not a Test Ride booking." }
        require(booking.status == "arrived") { "Pickup OTP can only be verified once Test Driver has arrived." }
        require(TestRideOtpUtil.isValidOtpFormat(cleanOtp)) { "Enter the exact 6-digit numeric Test OTP." }
        if (booking.testOtpExpiresAtMs > 0L && TestRideOtpUtil.isExpired(booking.testOtpExpiresAtMs)) {
            throw IllegalStateException("Test OTP has expired. Ask Admin or Operations Staff to regenerate OTP.")
        }

        val submittedHash = TestRideOtpUtil.sha256Hex(booking.id, cleanOtp)
        val otpRef = db.collection("test_ride_otps").document(booking.id)
        val bookingRef = db.collection("bookings").document(booking.id)

        try {
            db.runBatch { batch ->
                batch.update(
                    otpRef,
                    mapOf(
                        "used" to true,
                        "submittedHash" to submittedHash,
                        "verifiedByUid" to uid,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.update(
                    bookingRef,
                    mapOf(
                        "status" to "in_progress",
                        "test_otp_status" to "verified",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fe = e.unwrapFirestoreError() as? FirebaseFirestoreException
            if (fe?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                runCatching {
                    otpRef.update(
                        mapOf(
                            "attempts" to FieldValue.increment(1L),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
                val message = if (booking.testOtpExpiresAtMs > 0L &&
                    TestRideOtpUtil.isExpired(booking.testOtpExpiresAtMs)
                ) {
                    "Test OTP expired. Ask Admin/Ops to regenerate or request override."
                } else {
                    "Incorrect Test OTP or attempt limit reached."
                }
                throw IllegalArgumentException(message)
            }
            handleFirestoreError(e, OperationType.WRITE, otpRef.path, auth)
            throw e
        }
    }

    suspend fun requestTestOtpOverride(
        booking: Booking,
        reason: String,
        actorRole: String = "ops_staff"
    ): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val cleanReason = reason.trim()
        require(booking.isTestRide || booking.testMode) { "Only TEST RIDE bookings support OTP override requests." }
        require(booking.status == "arrived") { "Ride must be in 'arrived' state to request OTP override." }
        require(cleanReason.length >= 4) { "Provide an audit reason (at least 4 characters)." }

        val otpRef = db.collection("test_ride_otps").document(booking.id)
        val bookingRef = db.collection("bookings").document(booking.id)
        val auditRef = db.collection("audit_logs").document()

        try {
            db.runBatch { batch ->
                batch.update(
                    otpRef,
                    mapOf(
                        "overrideRequestedByUid" to actorUid,
                        "overrideRequestedReason" to cleanReason.take(250),
                        "overrideRequestedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.update(
                    bookingRef,
                    mapOf(
                        "test_otp_status" to "override_requested",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "TEST_OTP_OVERRIDE_REQUESTED",
                        "targetCollection" to "test_ride_otps",
                        "targetId" to booking.id,
                        "summary" to "[$actorRole] Requested Owner OTP override for ${booking.id}: $cleanReason",
                        "isTestRide" to true,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, otpRef.path, auth)
            throw e
        }
    }

    suspend fun adminOverrideTestOtpAndStartRide(
        booking: Booking,
        reason: String,
        actorRole: String = "admin"
    ): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val cleanReason = reason.trim()
        require(booking.isTestRide || booking.testMode) { "Only TEST RIDE bookings support OTP override." }
        require(booking.status == "arrived") { "Ride must be in 'arrived' state to override pickup OTP." }
        require(cleanReason.length >= 4) { "Provide an audit reason (at least 4 characters)." }

        val otpRef = db.collection("test_ride_otps").document(booking.id)
        val secretRef = db.collection("test_ride_otp_secrets").document(booking.id)
        val bookingRef = db.collection("bookings").document(booking.id)
        val auditRef = db.collection("audit_logs").document()

        try {
            db.runBatch { batch ->
                batch.update(
                    otpRef,
                    mapOf(
                        "used" to true,
                        "overridden" to true,
                        "overrideReason" to cleanReason.take(250),
                        "overriddenByUid" to actorUid,
                        "verifiedByUid" to actorUid,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.update(
                    secretRef,
                    mapOf(
                        "status" to "overridden",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.update(
                    bookingRef,
                    mapOf(
                        "status" to "in_progress",
                        "test_otp_status" to "overridden",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "TEST_OTP_ADMIN_OVERRIDE",
                        "targetCollection" to "test_ride_otps",
                        "targetId" to booking.id,
                        "summary" to "[$actorRole] Overrode Test OTP for ${booking.id}: $cleanReason",
                        "isTestRide" to true,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, otpRef.path, auth)
            throw e
        }
    }

    suspend fun resetTestRideBooking(
        booking: Booking,
        actorRole: String = "admin"
    ): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val cleanRole = if (actorRole == "ops_staff") "ops_staff" else "admin"
        require(booking.isTestRide || booking.testMode) { "Only TEST RIDE bookings can be reset." }
        val bookingRef = db.collection("bookings").document(booking.id)
        val assignmentRef = db.collection("ride_assignments").document(booking.id)
        val otpRef = db.collection("test_ride_otps").document(booking.id)
        val secretRef = db.collection("test_ride_otp_secrets").document(booking.id)
        val customerSecretRef = db.collection("ride_otp_secrets").document(booking.id)
        val locRef = db.collection("trip_locations").document(booking.id)
        val liveRef = if (booking.assignedDriverId.isNotBlank()) {
            db.collection("live_locations").document(booking.assignedDriverId)
        } else null
        val auditRef = db.collection("audit_logs").document()

        try {
            db.runBatch { batch ->
                batch.update(
                    bookingRef,
                    mapOf(
                        "status" to "searching",
                        "assignedDriverId" to "",
                        "assignedDriverName" to "",
                        "assignedVehicleLabel" to "",
                        "test_otp_status" to "reset",
                        "test_otp_expires_at_ms" to 0L,
                        "cancellationReason" to "",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.delete(assignmentRef)
                batch.delete(otpRef)
                batch.delete(secretRef)
                batch.delete(customerSecretRef)
                batch.delete(locRef)
                if (liveRef != null) {
                    batch.delete(liveRef)
                }
                batch.set(
                    auditRef,
                    mapOf(
                        "actorId" to actorUid,
                        "actorEmail" to actorEmail,
                        "action" to "TEST_RIDE_RESET",
                        "targetCollection" to "bookings",
                        "targetId" to booking.id,
                        "summary" to "[$cleanRole] Reset TEST RIDE ${booking.id} back to Pending",
                        "isTestRide" to true,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, bookingRef.path, auth)
            throw e
        }
    }

    suspend fun clearCompletedTestRideRecords(actorRole: String = "admin"): Result<Int> = runCatching {
        val actorUid = requireUserId()
        val actorEmail = auth.currentUser?.email ?: ""
        val cleanRole = if (actorRole == "ops_staff") "ops_staff" else "admin"
        val snap = db.collection("bookings")
            .whereEqualTo("is_test_ride", true)
            .get()
            .await()

        val targets = snap.documents.filter { doc ->
            doc.getString("status") in setOf("completed", "cancelled")
        }
        if (targets.isEmpty()) return@runCatching 0

        targets.chunked(25).forEach { chunk ->
            db.runBatch { batch ->
                chunk.forEach { doc ->
                    val id = doc.id
                    val driverId = doc.getString("assignedDriverId").orEmpty()
                    batch.delete(db.collection("bookings").document(id))
                    batch.delete(db.collection("ride_assignments").document(id))
                    batch.delete(db.collection("test_ride_otps").document(id))
                    batch.delete(db.collection("test_ride_otp_secrets").document(id))
                    batch.delete(db.collection("ride_otp_secrets").document(id))
                    batch.delete(db.collection("trip_locations").document(id))
                    if (driverId.isNotBlank()) {
                        batch.delete(db.collection("live_locations").document(driverId))
                    }
                }
            }.await()
        }

        val auditRef = db.collection("audit_logs").document()
        auditRef.set(
            mapOf(
                "actorId" to actorUid,
                "actorEmail" to actorEmail,
                "action" to "CLEAR_TEST_RIDE_RECORDS",
                "targetCollection" to "bookings",
                "targetId" to "test_cleanup",
                "summary" to "[$cleanRole] Cleared ${targets.size} completed/cancelled TEST RIDE records",
                "isTestRide" to true,
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).await()

        targets.size
    }

    // --- Isolated Test Rider Sandbox & Admin/Ops Governance ---

    fun observeCustomerRideOtpSecret(bookingId: String): Flow<RideOtpSecret?> =
        db.collection("ride_otp_secrets").document(bookingId).snapshots()
            .map { it.toObject(RideOtpSecret::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                val fe = error.unwrapFirestoreError()
                if (fe is CancellationException) throw fe
                emit(null)
            }

    fun observeMyTestRiderRequest(uid: String): Flow<TestRiderRequest?> =
        db.collection("test_rider_requests").document(uid).snapshots()
            .map { it.toObject(TestRiderRequest::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                val fe = error.unwrapFirestoreError()
                if (fe is CancellationException) throw fe
                emit(null)
            }

    fun observeMyTestRiderProfile(uid: String): Flow<TestRiderProfile?> =
        db.collection("test_riders").document(uid).snapshots()
            .map { it.toObject(TestRiderProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                val fe = error.unwrapFirestoreError()
                if (fe is CancellationException) throw fe
                emit(null)
            }

    fun observeOpenTestBookingsForTestRiders(): Flow<List<Booking>> =
        safeQueryFlow(
            "bookings",
            db.collection("bookings")
                .whereEqualTo("status", "searching")
                .whereEqualTo("is_test_ride", true)
        ) {
            it.toObject(Booking::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAllTestRiderRequests(): Flow<List<TestRiderRequest>> =
        safeQueryFlow("test_rider_requests", db.collection("test_rider_requests")) {
            it.toObject(TestRiderRequest::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAllTestRiders(): Flow<List<TestRiderProfile>> =
        safeQueryFlow("test_riders", db.collection("test_riders")) {
            it.toObject(TestRiderProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeTestRiderAuditLogs(): Flow<List<TestRiderAuditLog>> =
        safeQueryFlow("test_rider_audit_logs", db.collection("test_rider_audit_logs")) {
            it.toObject(TestRiderAuditLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    suspend fun submitTestRiderRequest(
        fullName: String,
        mobileNumber: String,
        email: String,
        vehicleCategory: String,
        testPurpose: String,
        notes: String = "",
        deviceInfoSummary: String = ""
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val cleanName = fullName.trim()
        val cleanPhone = mobileNumber.trim()
        val cleanPurpose = testPurpose.trim()
        val cleanCat = vehicleCategory.lowercase().trim()
        require(cleanName.length in 2..100) { "Full name must be between 2 and 100 characters." }
        require(cleanPhone.length in 8..25) { "Valid mobile number is required." }
        require(cleanCat in setOf("bike", "auto", "toto", "cab")) { "Choose bike, auto, toto, or cab." }
        require(cleanPurpose.length in 4..300) { "Describe your testing purpose (at least 4 characters)." }

        val ref = db.collection("test_rider_requests").document(uid)
        try {
            val snap = ref.get().await()
            if (!snap.exists()) {
                val payload = buildMap<String, Any> {
                    put("uid", uid)
                    put("fullName", cleanName)
                    put("mobileNumber", cleanPhone)
                    if (email.isNotBlank()) put("email", email.trim().take(150))
                    put("vehicleCategory", cleanCat)
                    put("testPurpose", cleanPurpose)
                    if (notes.isNotBlank()) put("notes", notes.trim().take(400))
                    put("status", "pending")
                    put("rejectionReason", "")
                    put("reviewedByUid", "")
                    put("opsRecommendation", "")
                    if (deviceInfoSummary.isNotBlank()) put("deviceInfoSummary", deviceInfoSummary.trim().take(200))
                    put("isOnline", false)
                    put("createdAt", FieldValue.serverTimestamp())
                    put("updatedAt", FieldValue.serverTimestamp())
                }
                ref.set(payload).await()
            } else {
                val existingStatus = snap.getString("status") ?: "pending"
                require(existingStatus == "rejected") {
                    when (existingStatus) {
                        "approved" -> "Your Test Rider access is already approved."
                        "suspended" -> "Test Rider access is suspended. Contact Owner Admin."
                        "revoked" -> "Test Rider access was revoked. Contact Owner Admin."
                        else -> "Your Test Rider request is already pending review."
                    }
                }
                // Matches firestore.rules rejected->pending applicant reapply affectedKeys whitelist
                ref.update(
                    mapOf(
                        "fullName" to cleanName,
                        "mobileNumber" to cleanPhone,
                        "email" to email.trim().take(150),
                        "vehicleCategory" to cleanCat,
                        "testPurpose" to cleanPurpose,
                        "notes" to notes.trim().take(400),
                        "status" to "pending",
                        "rejectionReason" to "",
                        "reviewedByUid" to "",
                        "opsRecommendation" to "",
                        "deviceInfoSummary" to deviceInfoSummary.trim().take(200),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path, auth)
            throw e
        }
    }

    suspend fun setTestRiderOnlineStatus(online: Boolean, lat: Double, lng: Double): Result<Unit> = runCatching {
        val uid = requireUserId()
        val reqRef = db.collection("test_rider_requests").document(uid)
        val riderRef = db.collection("test_riders").document(uid)
        try {
            db.runBatch { batch ->
                batch.update(
                    riderRef,
                    mapOf(
                        "isOnline" to online,
                        "currentLat" to lat,
                        "currentLng" to lng,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.update(
                    reqRef,
                    mapOf(
                        "isOnline" to online,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, riderRef.path, auth)
            throw e
        }
    }

    suspend fun updateTestRiderLiveLocation(
        activeBooking: Booking?,
        isOnline: Boolean,
        lat: Double,
        lng: Double,
        headingDeg: Double = 0.0,
        speedKmph: Double = 0.0
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        if (!com.example.map.GpsBroadcastThrottle.isValidCoordinate(lat, lng)) return@runCatching
        val riderRef = db.collection("test_riders").document(uid)
        val hasAssignedTestTrip = activeBooking != null &&
            (activeBooking.isTestRide || activeBooking.testMode) &&
            activeBooking.assignedDriverId == uid &&
            activeBooking.status in setOf("assigned", "arriving", "arrived", "in_progress")

        try {
            db.runBatch { batch ->
                batch.update(
                    riderRef,
                    mapOf(
                        "isOnline" to isOnline,
                        "currentLat" to lat,
                        "currentLng" to lng,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                if (hasAssignedTestTrip && activeBooking != null) {
                    val locRef = db.collection("trip_locations").document(activeBooking.id)
                    batch.set(
                        locRef,
                        mapOf(
                            "bookingId" to activeBooking.id,
                            "customerId" to activeBooking.customerId,
                            "driverId" to uid,
                            "lat" to lat,
                            "lng" to lng,
                            "isTestRide" to true,
                            "isSimulated" to false,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    val liveRef = db.collection("live_locations").document(uid)
                    batch.set(
                        liveRef,
                        mapOf(
                            "driverId" to uid,
                            "bookingId" to activeBooking.id,
                            "customerId" to activeBooking.customerId,
                            "lat" to lat,
                            "lng" to lng,
                            "headingDeg" to headingDeg.coerceIn(0.0, 360.0),
                            "speedKmph" to speedKmph.coerceAtLeast(0.0),
                            "isTestRide" to true,
                            "isSimulated" to false,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, riderRef.path, auth)
            throw e
        }
    }

    suspend fun claimTestBookingByTestRider(
        booking: Booking,
        riderProfile: TestRiderProfile,
        otpValiditySeconds: Long = 600L,
        currentLat: Double = 22.9041,
        currentLng: Double = 88.3976
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(riderProfile.uid == uid && riderProfile.status == "approved") {
            "Only approved Test Riders can claim Test Rides."
        }
        require(booking.isTestRide || booking.testMode) {
            "Test Riders can only accept TEST RIDE bookings."
        }
        require(booking.status == "searching" && booking.assignedDriverId.isBlank()) {
            "This Test Ride is no longer waiting for pickup."
        }

        val otpPlaintext = TestRideOtpUtil.generateSixDigitOtp()
        val otpHash = TestRideOtpUtil.sha256Hex(booking.id, otpPlaintext)
        val expiresAtMs = TestRideOtpUtil.computeExpiresAtMs(System.currentTimeMillis(), otpValiditySeconds)
        val expiresAtTs = Timestamp(Date(expiresAtMs))
        val lockedFare = booking.estimatedFareInr.coerceAtLeast(1.0)
        val resolvedVehicle = riderProfile.vehicleLabel.ifBlank {
            "TEST ${riderProfile.vehicleCategory.uppercase()}"
        }
        val startLat = currentLat.takeIf { com.example.map.GpsBroadcastThrottle.isValidCoordinate(it, currentLng) }
            ?: booking.pickupLat
        val startLng = currentLng.takeIf { com.example.map.GpsBroadcastThrottle.isValidCoordinate(currentLat, it) }
            ?: booking.pickupLng

        val bookingRef = db.collection("bookings").document(booking.id)
        val assignmentRef = db.collection("ride_assignments").document(booking.id)
        val otpChallengeRef = db.collection("test_ride_otps").document(booking.id)
        val customerOtpSecretRef = db.collection("ride_otp_secrets").document(booking.id)
        val locationRef = db.collection("trip_locations").document(booking.id)
        val liveRef = db.collection("live_locations").document(uid)

        try {
            db.runBatch { batch ->
                batch.update(
                    bookingRef,
                    mapOf(
                        "status" to "assigned",
                        "assignedDriverId" to uid,
                        "assignedDriverName" to riderProfile.displayName,
                        "assignedVehicleLabel" to resolvedVehicle,
                        "agreedFareInr" to lockedFare,
                        "test_otp_status" to "pending",
                        "test_otp_expires_at_ms" to expiresAtMs,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    assignmentRef,
                    mapOf(
                        "bookingId" to booking.id,
                        "customerId" to booking.customerId,
                        "driverId" to uid,
                        "offerId" to "test_rider_claim_${booking.id}",
                        "lockedFareInr" to lockedFare,
                        "status" to "active",
                        "is_test_ride" to true,
                        "assignedByUid" to uid,
                        "assignedByRole" to "",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    otpChallengeRef,
                    mapOf(
                        "bookingId" to booking.id,
                        "customerId" to booking.customerId,
                        "otpHash" to otpHash,
                        "submittedHash" to "",
                        "used" to false,
                        "overridden" to false,
                        "overrideReason" to "",
                        "overrideRequestedByUid" to "",
                        "overrideRequestedReason" to "",
                        "overriddenByUid" to "",
                        "verifiedByUid" to "",
                        "attempts" to 0L,
                        "maxAttempts" to 5L,
                        "expiresAt" to expiresAtTs,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    customerOtpSecretRef,
                    mapOf(
                        "bookingId" to booking.id,
                        "customerId" to booking.customerId,
                        "otpPlaintext" to otpPlaintext,
                        "isTestRide" to true,
                        "status" to "pending",
                        "expiresAt" to expiresAtTs,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    locationRef,
                    mapOf(
                        "bookingId" to booking.id,
                        "customerId" to booking.customerId,
                        "driverId" to uid,
                        "lat" to startLat,
                        "lng" to startLng,
                        "isTestRide" to true,
                        "isSimulated" to false,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    liveRef,
                    mapOf(
                        "driverId" to uid,
                        "bookingId" to booking.id,
                        "customerId" to booking.customerId,
                        "lat" to startLat,
                        "lng" to startLng,
                        "isTestRide" to true,
                        "isSimulated" to false,
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
    }

    suspend fun verifyTestRideOtpByRider(
        booking: Booking,
        enteredOtp: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val cleanOtp = enteredOtp.trim()
        require(booking.isTestRide || booking.testMode) { "Not a Test Ride booking." }
        require(booking.assignedDriverId == uid) { "Only the assigned rider can verify pickup OTP." }
        require(booking.status == "arrived") { "Mark arrived at pickup before verifying Pickup OTP." }
        require(TestRideOtpUtil.isValidOtpFormat(cleanOtp)) { "Enter the exact 6-digit numeric Pickup OTP." }
        if (booking.testOtpExpiresAtMs > 0L && TestRideOtpUtil.isExpired(booking.testOtpExpiresAtMs)) {
            throw IllegalStateException("Test OTP has expired. Ask Admin or Operations Staff to reset this ride.")
        }

        val submittedHash = TestRideOtpUtil.sha256Hex(booking.id, cleanOtp)
        val otpRef = db.collection("test_ride_otps").document(booking.id)
        val bookingRef = db.collection("bookings").document(booking.id)

        try {
            db.runBatch { batch ->
                batch.update(
                    otpRef,
                    mapOf(
                        "used" to true,
                        "submittedHash" to submittedHash,
                        "verifiedByUid" to uid,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.update(
                    bookingRef,
                    mapOf(
                        "status" to "in_progress",
                        "test_otp_status" to "verified",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
            runCatching {
                db.collection("ride_otp_secrets").document(booking.id)
                    .update(
                        mapOf(
                            "status" to "verified",
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fe = e.unwrapFirestoreError() as? FirebaseFirestoreException
            if (fe?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                runCatching {
                    otpRef.update(
                        mapOf(
                            "attempts" to FieldValue.increment(1L),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
                val message = if (booking.testOtpExpiresAtMs > 0L &&
                    TestRideOtpUtil.isExpired(booking.testOtpExpiresAtMs)
                ) {
                    "Test OTP expired. Ask Admin/Ops to reset or override."
                } else {
                    "Incorrect 6-digit Pickup OTP or max attempts reached."
                }
                throw IllegalArgumentException(message)
            }
            handleFirestoreError(e, OperationType.WRITE, otpRef.path, auth)
            throw e
        }
    }

    suspend fun opsRecommendTestRiderRequest(
        request: TestRiderRequest,
        recommendation: String,
        note: String
    ): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        require(recommendation in setOf("", "recommend_approve", "recommend_reject")) {
            "Invalid operations recommendation value."
        }
        val reqRef = db.collection("test_rider_requests").document(request.uid)
        val auditRef = db.collection("test_rider_audit_logs").document()
        try {
            db.runBatch { batch ->
                batch.update(
                    reqRef,
                    mapOf(
                        "opsRecommendation" to recommendation,
                        "opsRecommendedByUid" to actorUid,
                        "opsRecommendedNote" to note.trim().take(300),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    auditRef,
                    mapOf(
                        "targetUid" to request.uid,
                        "actorUid" to actorUid,
                        "actorRole" to "ops_staff",
                        "action" to "OPS_RECOMMEND_${recommendation.ifBlank { "CLEAR" }.uppercase()}",
                        "previousStatus" to request.status,
                        "newStatus" to request.status,
                        "reason" to "",
                        "note" to note.trim().take(300),
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, reqRef.path, auth)
            throw e
        }
    }

    suspend fun adminReviewTestRiderRequest(
        request: TestRiderRequest,
        newStatus: String,
        reason: String = "",
        note: String = ""
    ): Result<Unit> = runCatching {
        val actorUid = requireUserId()
        require(newStatus in setOf("pending", "approved", "rejected", "suspended", "revoked")) {
            "Invalid Test Rider decision status: $newStatus"
        }
        val cleanReason = reason.trim().take(300)
        if (newStatus in setOf("rejected", "suspended", "revoked")) {
            require(cleanReason.length >= 4) {
                "Enter a reason (at least 4 characters) when marking $newStatus."
            }
        }
        val reqRef = db.collection("test_rider_requests").document(request.uid)
        val riderRef = db.collection("test_riders").document(request.uid)
        val auditRef = db.collection("test_rider_audit_logs").document()
        val notifRef = db.collection("notifications").document()

        try {
            val riderExists = riderRef.get().await().exists()
            db.runBatch { batch ->
                batch.update(
                    reqRef,
                    mapOf(
                        "status" to newStatus,
                        "rejectionReason" to if (newStatus == "approved") "" else cleanReason,
                        "reviewedByUid" to actorUid,
                        "reviewedByRole" to "admin",
                        "reviewNote" to note.trim().take(300),
                        "isOnline" to if (newStatus == "approved") request.isOnline else false,
                        "reviewedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                when (newStatus) {
                    "approved" -> {
                        batch.set(
                            riderRef,
                            mapOf(
                                "uid" to request.uid,
                                "displayName" to request.fullName.ifBlank { "Test Rider" },
                                "mobileNumber" to request.mobileNumber,
                                "vehicleCategory" to request.vehicleCategory,
                                "vehicleLabel" to "TEST ${request.vehicleCategory.uppercase()}",
                                "status" to "approved",
                                "isOnline" to false,
                                "currentLat" to 22.9041,
                                "currentLng" to 88.3976,
                                "completedTestRides" to 0L,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                    "suspended", "revoked" -> {
                        if (riderExists) {
                            batch.update(
                                riderRef,
                                mapOf(
                                    "status" to newStatus,
                                    "isOnline" to false,
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            )
                        }
                    }
                    "rejected" -> {
                        if (riderExists) {
                            batch.delete(riderRef)
                        }
                    }
                }
                batch.set(
                    auditRef,
                    mapOf(
                        "targetUid" to request.uid,
                        "actorUid" to actorUid,
                        "actorRole" to "admin",
                        "action" to "ADMIN_${newStatus.uppercase()}_TEST_RIDER",
                        "previousStatus" to request.status,
                        "newStatus" to newStatus,
                        "reason" to cleanReason,
                        "note" to note.trim().take(300),
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                val (nTitle, nBody) = when (newStatus) {
                    "approved" -> "Test Rider Access Approved" to "Your GoLocal Test Rider sandbox access is approved. Switch to Test Rider Mode to run non-commercial QA rides."
                    "rejected" -> "Test Rider Request Rejected" to "Reason: $cleanReason"
                    "suspended" -> "Test Rider Access Suspended" to "Reason: $cleanReason"
                    "revoked" -> "Test Rider Access Revoked" to "Reason: $cleanReason"
                    else -> "Test Rider Status Updated" to "Your Test Rider request status is now $newStatus."
                }
                batch.set(
                    notifRef,
                    mapOf(
                        "userId" to request.uid,
                        "title" to nTitle,
                        "body" to nBody,
                        "type" to "test_rider_status",
                        "read" to false,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, reqRef.path, auth)
            throw e
        }
    }

    suspend fun getBookingById(bookingId: String): Booking? {
        val cleanId = bookingId.trim()
        if (cleanId.isBlank()) return null
        val snap = runCatching { db.collection("bookings").document(cleanId).get().await() }.getOrNull()
            ?: return null
        return if (snap.exists()) {
            snap.toObject(Booking::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } else {
            null
        }
    }

    suspend fun getDriverProfileOnce(driverId: String): DriverProfile? {
        val cleanId = driverId.trim()
        if (cleanId.isBlank()) return null
        val snap = runCatching { db.collection("driver_profiles").document(cleanId).get().await() }.getOrNull()
            ?: return null
        return if (snap.exists()) {
            snap.toObject(DriverProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } else {
            null
        }
    }

    suspend fun getTestRiderProfileOnce(uid: String): TestRiderProfile? {
        val cleanUid = uid.trim()
        if (cleanUid.isBlank()) return null
        val snap = runCatching { db.collection("test_riders").document(cleanUid).get().await() }.getOrNull()
            ?: return null
        return if (snap.exists()) {
            snap.toObject(TestRiderProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } else {
            null
        }
    }

    suspend fun expireTimedOutSearchingBooking(bookingId: String): Result<Boolean> = runCatching {
        val cleanId = bookingId.trim()
        require(cleanId.isNotBlank()) { "Booking ID is required." }
        val ref = db.collection("bookings").document(cleanId)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            if (!snap.exists()) return@runTransaction false
            val currentStatus = snap.getString("status").orEmpty()
            if (currentStatus != "searching") return@runTransaction false
            tx.update(
                ref,
                mapOf(
                    "status" to "expired",
                    "cancellationReason" to "No rider accepted within 45 seconds",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            true
        }.await()
    }

    companion object {
        const val RIDE_REQUEST_TIMEOUT_MS = 45_000L
    }
}

internal object SosCooldownGuard {
    private const val COOLDOWN_MS = 15_000L
    private val lastTriggerMsByUid = ConcurrentHashMap<String, Long>()

    fun remainingCooldownSeconds(uid: String, nowMs: Long = System.currentTimeMillis()): Long {
        val last = lastTriggerMsByUid[uid] ?: return 0L
        val elapsed = nowMs - last
        if (elapsed >= COOLDOWN_MS) return 0L
        return ((COOLDOWN_MS - elapsed + 999L) / 1000L).coerceAtLeast(1L)
    }

    fun markTriggered(uid: String, nowMs: Long = System.currentTimeMillis()) {
        lastTriggerMsByUid[uid] = nowMs
    }

    fun reset() {
        lastTriggerMsByUid.clear()
    }
}
