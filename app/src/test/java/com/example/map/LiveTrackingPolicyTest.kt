package com.example.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.data.tracking.RiderLocationForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveTrackingPolicyTest {

    @Test
    fun `manifest declares foreground location permission without background location`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in perms)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in perms)
        assertFalse(
            "Background location must never be declared for GoLocal rider tracking",
            Manifest.permission.ACCESS_BACKGROUND_LOCATION in perms
        )
    }

    @Test
    fun `starting rider tracking without granted location permission sets permission_denied health state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(RiderLocationForegroundService.hasLocationPermission(context))

        RiderLocationForegroundService.startOrUpdate(
            context = context,
            driverId = "driver_1",
            driverName = "Santu Das",
            vehicleCategory = "toto"
        )

        val health = RiderLocationForegroundService.gpsHealth.value
        assertFalse(health.isServiceRunning)
        assertEquals("permission_denied", health.gpsStatus)
        assertTrue((health.lastError ?: "").contains("location permission", ignoreCase = true))
    }
}
