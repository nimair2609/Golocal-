package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.Booking
import com.example.data.model.CustomerProfile
import com.example.data.model.DriverProfile
import com.example.data.model.RegistrationStatus
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class GolocalRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun ensureUserAccountAndCreateBooking_authenticatedCustomer_succeeds() = runBlocking {
        val uid = signInTestUser("alice_${UUID.randomUUID()}@test.com")
        val repo = GolocalRepository(firestore, auth)

        val account = withTimeout(TIMEOUT_MS) {
            repo.ensureUserAccount("Alice Sen", "alice@test.com", "").getOrThrow()
        }
        assertEquals(uid, account.userId)

        val bookingId = withTimeout(TIMEOUT_MS) {
            repo.createBooking(
                Booking(
                    customerName = "Alice Sen",
                    bookingType = "station_pickup",
                    vehicleCategory = "toto",
                    pickupLabel = "Bandel Junction Railway Station",
                    pickupDistrict = "Hooghly",
                    pickupLat = 22.9238,
                    pickupLng = 88.3788,
                    destinationLabel = "Chinsurah Gharimore Market",
                    destinationDistrict = "Hooghly",
                    destinationLat = 22.8902,
                    destinationLng = 88.3937,
                    distanceKm = 4.5,
                    estimatedDurationMin = 15L,
                    estimatedFareInr = 65.0,
                    minPermittedFareInr = 55.0,
                    maxPermittedFareInr = 85.0,
                    paymentMethod = "cash",
                    invoiceEmail = "alice@test.com"
                )
            ).getOrThrow()
        }
        assertTrue(bookingId.isNotBlank())

        val bookings = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeCustomerBookings(uid).first { list -> list.any { it.id == bookingId } }
        }
        assertTrue(bookings.any { it.id == bookingId && it.status == "searching" && it.invoiceEmail == "alice@test.com" })
    }

    @Test
    fun ensureUserAccount_returningUserPreservesProfileAndHealsMissingCustomerProfile() = runBlocking {
        val uid = signInTestUser("returning_${UUID.randomUUID()}@test.com")
        val repo = GolocalRepository(firestore, auth)

        val created = withTimeout(TIMEOUT_MS) {
            repo.ensureUserAccount("Initial Name", "returning@test.com", "https://example.com/a.png").getOrThrow()
        }
        assertEquals(uid, created.userId)
        assertEquals("Initial Name", created.fullName)

        withTimeout(TIMEOUT_MS) {
            repo.updateUserPreferences(fullName = "Custom Preferred Name", language = "bn").getOrThrow()
        }

        // Simulate missing customer_profiles/{uid} and re-run ensureUserAccount as happens on next login
        withTimeout(TIMEOUT_MS) {
            firestore.collection("customer_profiles").document(uid).delete().await()
        }

        val reloaded = withTimeout(TIMEOUT_MS) {
            repo.ensureUserAccount("Fallback Name", "returning@test.com", "").getOrThrow()
        }
        assertEquals("Custom Preferred Name", reloaded.fullName)
        assertEquals("bn", reloaded.preferredLanguage)

        val healedProfile = withTimeout(TIMEOUT_MS) {
            firestore.collection("customer_profiles").document(uid).get().await()
        }
        assertTrue("Missing customer_profiles/{uid} should be healed on returning login", healedProfile.exists())
    }

    @Test
    fun ensureUserAccount_oversizedProfileFieldsAreSafelyTruncatedToPassRules() = runBlocking {
        val uid = signInTestUser("longfields_${UUID.randomUUID()}@test.com")
        val repo = GolocalRepository(firestore, auth)

        val veryLongName = "N".repeat(180)
        val veryLongPhoto = "https://example.com/" + "p".repeat(600)

        val account = withTimeout(TIMEOUT_MS) {
            repo.ensureUserAccount(veryLongName, "long@test.com", veryLongPhoto).getOrThrow()
        }
        assertEquals(uid, account.userId)
        assertTrue(account.fullName.length <= 100)
        assertTrue(account.photoUrl.length <= 500)
    }

    @Test
    fun crossUserIsolation_otherUserCannotReadUserAccountOrCancelBooking() = runBlocking {
        val aliceUid = signInTestUser("alice_iso_${UUID.randomUUID()}@test.com")
        val aliceRepo = GolocalRepository(firestore, auth)
        withTimeout(TIMEOUT_MS) {
            aliceRepo.ensureUserAccount("Alice Isolation", "alice@test.com", "").getOrThrow()
        }
        val bookingId = withTimeout(TIMEOUT_MS) {
            aliceRepo.createBooking(
                Booking(
                    customerName = "Alice Isolation",
                    bookingType = "now",
                    vehicleCategory = "auto",
                    pickupLabel = "Howrah Junction",
                    destinationLabel = "Sealdah",
                    distanceKm = 6.0,
                    estimatedDurationMin = 20L,
                    estimatedFareInr = 90.0,
                    minPermittedFareInr = 76.0,
                    maxPermittedFareInr = 108.0,
                    paymentMethod = "upi"
                )
            ).getOrThrow()
        }

        // Switch to Bob
        signInTestUser("bob_iso_${UUID.randomUUID()}@test.com")
        val bobRepo = GolocalRepository(firestore, auth)

        var userReadDenied = false
        try {
            withTimeout(TIMEOUT_MS) {
                firestore.collection("users").document(aliceUid).get().await()
            }
        } catch (e: Exception) {
            val fe = e.unwrapFirestoreException()
            if (fe?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                userReadDenied = true
            }
        }
        assertTrue("Cross-user read on /users/{aliceUid} must fail with PERMISSION_DENIED", userReadDenied)

        val cancelResult = withTimeout(TIMEOUT_MS) {
            bobRepo.cancelBooking(bookingId, "Unauthorized cancel")
        }
        assertTrue("Bob must not cancel Alice's booking", cancelResult.isFailure)
        val fe = cancelResult.exceptionOrNull()?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fe?.code)
    }

    @Test
    fun driverRegistration_startsPendingAndCannotGoOnlineBeforeVerification() = runBlocking {
        val driverUid = signInTestUser("driver_${UUID.randomUUID()}@test.com")
        val repo = GolocalRepository(firestore, auth)

        withTimeout(TIMEOUT_MS) {
            repo.registerDriverApplication(
                DriverProfile(
                    legalName = "Rahul Das",
                    phone = "+919800001122",
                    address = "Chinsurah, Hooghly",
                    district = "Hooghly",
                    block = "Chinsurah-Mogra",
                    vehicleCategory = "toto",
                    vehicleModel = "Mayuri E-Rickshaw",
                    vehicleRegNumber = "WB15E4321",
                    drivingLicenceNumber = "WB15-20220099",
                    rcNumber = "RC-WB15-9988",
                    insuranceNumber = "INS-2026-11",
                    settlementUpiOrBank = "rahul@upi"
                )
            ).getOrThrow()
        }

        val profile = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeMyDriverProfile(driverUid).first { it != null }
        }
        assertNotNull(profile)
        assertEquals("pending", profile?.verificationStatus)

        val goOnlineResult = withTimeout(TIMEOUT_MS) {
            repo.setDriverOnlineStatus(online = true, lat = 22.90, lng = 88.39)
        }
        assertTrue("Unverified driver must not go online", goOnlineResult.isFailure)
        val fe = goOnlineResult.exceptionOrNull()?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fe?.code)
    }

    @Test
    fun unauthenticatedCaller_observeBookings_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = GolocalRepository(firestore, auth)

        var caught: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeOpenBookingsForDrivers().first()
            }
        } catch (e: Throwable) {
            caught = e
        }
        assertNotNull(caught)
    }

    @Test
    fun mobileOtpRegistration_createsIsolatedPrivateEmergencyContact_andReturnsCompletedOnReLogin() = runBlocking {
        val userUid = signInTestUser("mobile_reg_${UUID.randomUUID()}@test.com")
        val repo = GolocalRepository(firestore, auth)

        // 1. Fresh user must be gated to PendingRegistration
        val initialStatus = withTimeout(TIMEOUT_MS) {
            repo.checkRegistrationStatus(uid = userUid, verifiedPhoneNumber = "+919876543210").getOrThrow()
        }
        assertTrue(initialStatus is RegistrationStatus.PendingRegistration)
        assertEquals("+919876543210", (initialStatus as RegistrationStatus.PendingRegistration).verifiedMobileNumber)

        // 2. Complete registration atomically
        val completed = withTimeout(TIMEOUT_MS) {
            repo.completeMobileRegistration(
                fullName = "Anirban Sen",
                verifiedMobileNumber = "+919876543210",
                invoiceEmail = "anirban.invoices@example.com",
                emergencyContactNumber = "+919830011223",
                privacyConsentAccepted = true,
                role = "customer"
            ).getOrThrow()
        }
        assertEquals(userUid, completed.uid)
        assertEquals("+919876543210", completed.mobileNumber)
        assertEquals("anirban.invoices@example.com", completed.email)
        assertTrue(completed.profileCompleted)
        // Phone number not verified via Firebase Phone Auth on email-signed-in emulator user -> must not spoof mobileVerified
        org.junit.Assert.assertFalse(completed.mobileVerified)
        assertTrue(completed.hasEmergencyContact)

        // 3. Returning user enters directly without recreating account
        val returningStatus = withTimeout(TIMEOUT_MS) {
            repo.checkRegistrationStatus(uid = userUid, verifiedPhoneNumber = "+919876543210").getOrThrow()
        }
        assertTrue(returningStatus is RegistrationStatus.Completed)
        assertEquals("Anirban Sen", (returningStatus as RegistrationStatus.Completed).account.fullName)

        // 4. Customer profile must NOT store raw emergency phone number
        val customerProfile = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeCustomerProfile(userUid).first { it != null }
        }
        assertEquals("", customerProfile?.emergencyContactPhone ?: "")

        // Saving profile details keeps emergencyContactPhone sanitized blank
        withTimeout(TIMEOUT_MS) {
            repo.saveCustomerProfile(
                CustomerProfile(
                    userId = userUid,
                    homeAddress = "Bandel Bazar",
                    workAddress = "Chinsurah",
                    emergencyContactName = "Legacy Should Strip",
                    emergencyContactPhone = "+919999999999"
                )
            ).getOrThrow()
        }
        val reloadedProfile = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeCustomerProfile(userUid).first { it?.homeAddress == "Bandel Bazar" }
        }
        assertEquals("", reloadedProfile?.emergencyContactPhone ?: "")

        // 5. Private emergency contact IS stored in isolated emergency_contacts/{uid} and readable by owner
        val ownEmergency = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeMyEmergencyContact(userUid).first { it != null }
        }
        assertEquals("+919830011223", ownEmergency?.emergencyContactNumber)

        // 6. Another user CANNOT read owner's private emergency contact
        signInTestUser("intruder_${UUID.randomUUID()}@test.com")
        var denied = false
        try {
            withTimeout(TIMEOUT_MS) {
                firestore.collection("emergency_contacts").document(userUid).get().await()
            }
        } catch (e: Exception) {
            if (e.unwrapFirestoreException()?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                denied = true
            }
        }
        assertTrue("Other users must receive PERMISSION_DENIED reading emergency_contacts/{uid}", denied)
    }

    private companion object {
        const val TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
