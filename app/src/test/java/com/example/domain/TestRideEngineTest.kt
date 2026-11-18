package com.example.domain

import com.example.data.model.AuditLog
import com.example.data.model.Booking
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.DriverProfile
import com.example.data.model.LiveLocation
import com.example.data.model.PlatformSettings
import com.example.data.model.RideAssignment
import com.example.data.model.TestRideOtpChallenge
import com.example.data.model.TripLocation
import com.example.data.model.UserAccount
import com.google.firebase.firestore.PropertyName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestRideEngineTest {

    @Test
    fun generateSixDigitOtp_producesValidSixDigitNumericStrings() {
        val generated = (1..25).map { TestRideOtpUtil.generateSixDigitOtp() }
        generated.forEach { otp ->
            assertEquals(6, otp.length)
            assertTrue(otp.all { it in '0'..'9' })
            assertTrue(TestRideOtpUtil.isValidOtpFormat(otp))
        }
        assertTrue(generated.toSet().size > 5)
    }

    @Test
    fun isValidOtpFormat_rejectsInvalidLengthsAndNonDigits() {
        assertFalse(TestRideOtpUtil.isValidOtpFormat(""))
        assertFalse(TestRideOtpUtil.isValidOtpFormat("12345"))
        assertFalse(TestRideOtpUtil.isValidOtpFormat("1234567"))
        assertFalse(TestRideOtpUtil.isValidOtpFormat("12345a"))
        assertTrue(TestRideOtpUtil.isValidOtpFormat(" 048392 "))
    }

    @Test
    fun sha256Hex_isDeterministicAndBoundToBookingId() {
        val hashA = TestRideOtpUtil.sha256Hex("booking_001", "482910")
        val hashB = TestRideOtpUtil.sha256Hex("booking_001", "482910")
        val hashOtherBooking = TestRideOtpUtil.sha256Hex("booking_002", "482910")
        val hashOtherOtp = TestRideOtpUtil.sha256Hex("booking_001", "482911")

        assertEquals(64, hashA.length)
        assertEquals(hashA, hashB)
        assertNotEquals(hashA, hashOtherBooking)
        assertNotEquals(hashA, hashOtherOtp)
    }

    @Test
    fun otpExpiryAndRemainingSeconds_computeWindowAccurately() {
        val expiresAt = TestRideOtpUtil.computeExpiresAtMs(10_000L, 300L)
        assertEquals(310_000L, expiresAt)
        assertFalse(TestRideOtpUtil.isExpired(expiresAt, nowMs = 310_000L))
        assertTrue(TestRideOtpUtil.isExpired(expiresAt, nowMs = 310_001L))
        assertEquals(60L, TestRideOtpUtil.remainingSeconds(expiresAt, nowMs = 250_000L))
        assertEquals(0L, TestRideOtpUtil.remainingSeconds(expiresAt, nowMs = 350_000L))
    }

    @Test
    fun testDriverCatalog_returnsCategorySpecificAndDefaultDrivers() {
        assertEquals("test_driver_toto_01", TestDriverCatalog.defaultForCategory("toto").id)
        assertEquals("test_driver_auto_02", TestDriverCatalog.defaultForCategory("auto").id)
        assertEquals("test_driver_bike_03", TestDriverCatalog.defaultForCategory("bike").id)
        assertEquals("test_driver_cab_04", TestDriverCatalog.defaultForCategory("cab").id)
        assertEquals("test_driver_toto_01", TestDriverCatalog.defaultForCategory("unknown").id)
        assertEquals("Test Driver – Auto 02", TestDriverCatalog.findById("test_driver_auto_02")?.displayName)
    }

    @Test
    fun stateMachine_enforcesAllowedTransitionsAndActiveStatuses() {
        assertTrue(TestRideStateMachine.isValidTransition("searching", "assigned"))
        assertTrue(TestRideStateMachine.isValidTransition("assigned", "arriving"))
        assertTrue(TestRideStateMachine.isValidTransition("arriving", "arrived"))
        assertTrue(TestRideStateMachine.isValidTransition("arrived", "in_progress"))
        assertTrue(TestRideStateMachine.isValidTransition("in_progress", "completed"))
        assertFalse(TestRideStateMachine.isValidTransition("searching", "completed"))
        assertFalse(TestRideStateMachine.isValidTransition("completed", "searching"))

        assertTrue(TestRideStateMachine.isActiveStatus("searching"))
        assertTrue(TestRideStateMachine.isActiveStatus("in_progress"))
        assertFalse(TestRideStateMachine.isActiveStatus("completed"))
        assertFalse(TestRideStateMachine.isActiveStatus("cancelled"))
    }

    @Test
    fun testRideSeparation_isolatesProductionFromTestBookings() {
        val prodBooking = Booking(id = "prod_1", status = "searching", isTestRide = false, testMode = false)
        val testPending = Booking(id = "test_1", status = "searching", isTestRide = true, testMode = true)
        val testAssigned = Booking(id = "test_2", status = "assigned", isTestRide = true, testMode = false)
        val testOngoing = Booking(id = "test_3", status = "in_progress", isTestRide = false, testMode = true)
        val testDone = Booking(id = "test_4", status = "completed", isTestRide = true, testMode = true)
        val testCancelled = Booking(id = "test_5", status = "cancelled", isTestRide = true, testMode = true)

        val mixed = listOf(prodBooking, testPending, testAssigned, testOngoing, testDone, testCancelled)

        val productionOnly = TestRideSeparation.filterProductionBookings(mixed)
        assertEquals(listOf("prod_1"), productionOnly.map { it.id })

        val testOnly = TestRideSeparation.filterTestBookings(mixed)
        assertEquals(5, testOnly.size)
        assertFalse(testOnly.any { it.id == "prod_1" })

        assertTrue(TestRideSeparation.matchesAdminTestFilter(testPending, "Pending"))
        assertTrue(TestRideSeparation.matchesAdminTestFilter(testAssigned, "Assigned"))
        assertTrue(TestRideSeparation.matchesAdminTestFilter(testOngoing, "Ongoing"))
        assertTrue(TestRideSeparation.matchesAdminTestFilter(testDone, "Completed"))
        assertTrue(TestRideSeparation.matchesAdminTestFilter(testCancelled, "Cancelled"))
        assertFalse(TestRideSeparation.matchesAdminTestFilter(prodBooking, "All Test"))
    }

    @Test
    fun routeSimulator_interpolatesWaypointsAndResamplesPolyline() {
        val waypoints = TestRideRouteSimulator.interpolateWaypoints(22.90, 88.39, 22.94, 88.43, steps = 10)
        assertEquals(11, waypoints.size)
        assertEquals(22.90, waypoints.first().lat, 0.0001)
        assertEquals(88.43, waypoints.last().lng, 0.0001)

        val dense = (0..50).map { idx -> (22.90 + idx * 0.001) to (88.39 + idx * 0.001) }
        val resampled = TestRideRouteSimulator.resamplePolyline(dense, targetSteps = 12)
        assertTrue(resampled.size in 12..13)
        assertEquals(dense.first().first, resampled.first().lat, 0.0001)
        assertEquals(dense.last().second, resampled.last().lng, 0.0001)
    }

    @Test
    fun routeSimulator_stepDelayScalesDownWithHigherSpeed() {
        val slowDelay = TestRideRouteSimulator.stepDelayMs(totalDistanceKm = 12.0, speedKmph = 15.0, steps = 15)
        val fastDelay = TestRideRouteSimulator.stepDelayMs(totalDistanceKm = 12.0, speedKmph = 60.0, steps = 15)
        assertTrue(slowDelay >= fastDelay)
        assertTrue(slowDelay in 300L..1600L)
        assertTrue(fastDelay in 300L..1600L)
    }

    @Test
    fun models_defaultValuesAndPropertyNameAnnotationsAreSafe() {
        val booking = Booking()
        assertFalse(booking.isTestRide)
        assertFalse(booking.testMode)
        assertEquals("", booking.testOtpStatus)
        assertEquals(0L, booking.testOtpExpiresAtMs)

        val isTestProp = Booking::class.java.getDeclaredMethod("isTestRide")
            .getAnnotation(PropertyName::class.java)
        val testModeProp = Booking::class.java.getDeclaredMethod("getTestMode")
            .getAnnotation(PropertyName::class.java)
        assertEquals("is_test_ride", isTestProp?.value)
        assertEquals("test_mode", testModeProp?.value)

        val settings = PlatformSettings()
        assertFalse(settings.testRideModeEnabled)
        assertTrue(settings.testManualAssignEnabled)
        assertEquals(300L, settings.testOtpValiditySeconds)

        val user = UserAccount()
        assertFalse(user.isTestCustomer)
        assertEquals("isTestCustomer", UserAccount::class.java.getDeclaredMethod("isTestCustomer").getAnnotation(PropertyName::class.java)?.value)

        val driver = DriverProfile()
        assertFalse(driver.isTestPartner)
        assertEquals("isTestPartner", DriverProfile::class.java.getDeclaredMethod("isTestPartner").getAnnotation(PropertyName::class.java)?.value)

        val dirEntry = DriverDirectoryEntry()
        assertFalse(dirEntry.isTestPartner)
        assertEquals("isTestPartner", DriverDirectoryEntry::class.java.getDeclaredMethod("isTestPartner").getAnnotation(PropertyName::class.java)?.value)

        val assignment = RideAssignment()
        assertFalse(assignment.isTestRide)
        assertEquals("is_test_ride", RideAssignment::class.java.getDeclaredMethod("isTestRide").getAnnotation(PropertyName::class.java)?.value)

        val tripLoc = TripLocation()
        assertFalse(tripLoc.isTestRide)
        assertFalse(tripLoc.isSimulated)
        assertEquals("isTestRide", TripLocation::class.java.getDeclaredMethod("isTestRide").getAnnotation(PropertyName::class.java)?.value)
        assertEquals("isSimulated", TripLocation::class.java.getDeclaredMethod("isSimulated").getAnnotation(PropertyName::class.java)?.value)

        val liveLoc = LiveLocation()
        assertFalse(liveLoc.isTestRide)
        assertFalse(liveLoc.isSimulated)
        assertEquals("isTestRide", LiveLocation::class.java.getDeclaredMethod("isTestRide").getAnnotation(PropertyName::class.java)?.value)
        assertEquals("isSimulated", LiveLocation::class.java.getDeclaredMethod("isSimulated").getAnnotation(PropertyName::class.java)?.value)

        val auditLog = AuditLog()
        assertFalse(auditLog.isTestRide)
        assertEquals("isTestRide", AuditLog::class.java.getDeclaredMethod("isTestRide").getAnnotation(PropertyName::class.java)?.value)

        val challenge = TestRideOtpChallenge()
        assertEquals("", challenge.overrideRequestedByUid)
        assertEquals("", challenge.overrideRequestedReason)
        assertEquals("", challenge.overriddenByUid)
    }
}
