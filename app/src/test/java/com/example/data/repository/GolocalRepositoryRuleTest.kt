package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.Booking
import com.example.data.model.DriverProfile
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
                    paymentMethod = "cash"
                )
            ).getOrThrow()
        }
        assertTrue(bookingId.isNotBlank())

        val bookings = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeCustomerBookings(uid).first { list -> list.any { it.id == bookingId } }
        }
        assertTrue(bookings.any { it.id == bookingId && it.status == "searching" })
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

    private companion object {
        const val TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
