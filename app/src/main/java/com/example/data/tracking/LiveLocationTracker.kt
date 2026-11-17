package com.example.data.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.domain.BoraiLaunchConfig
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocationTelemetryState(
    val permissionGranted: Boolean = false,
    val isSharing: Boolean = false,
    val isGpsSignalLost: Boolean = false,
    val lastLat: Double = BoraiLaunchConfig.DEFAULT_LAT,
    val lastLng: Double = BoraiLaunchConfig.DEFAULT_LNG,
    val hasRealFix: Boolean = false,
    val accuracyMeters: Float = 0f,
    val speedMps: Float = 0f,
    val headingDeg: Float = 0f,
    val lastUpdateEpochMs: Long = 0L,
    val statusMessage: String = "Location idle"
)

/**
 * Foreground-only continuous GPS tracker for authorized Partner duty and active trips.
 * Complies with Google Play Location Policy (NO background location permission requested).
 */
class LiveLocationTracker(private val context: Context) {
    private val appContext = context.applicationContext
    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(appContext)
    }

    private val _state = MutableStateFlow(
        LocationTelemetryState(permissionGranted = hasLocationPermission(appContext))
    )
    val state: StateFlow<LocationTelemetryState> = _state.asStateFlow()

    private var activeCallback: LocationCallback? = null
    private var currentHighAccuracy: Boolean = false

    fun refreshPermissionStatus(): Boolean {
        val granted = hasLocationPermission(appContext)
        _state.value = _state.value.copy(permissionGranted = granted)
        return granted
    }

    @SuppressLint("MissingPermission")
    fun startTracking(
        highAccuracyTripMode: Boolean,
        onFix: (lat: Double, lng: Double, accuracy: Float, speed: Float, heading: Float) -> Unit,
        onSignalAvailabilityChanged: (available: Boolean) -> Unit = {}
    ): Boolean {
        val granted = refreshPermissionStatus()
        if (!granted) {
            _state.value = _state.value.copy(
                isSharing = false,
                statusMessage = "Location permission required to share live GPS."
            )
            return false
        }

        if (activeCallback != null && currentHighAccuracy == highAccuracyTripMode) {
            return true
        }
        stopTracking(reason = "Switching GPS mode")

        currentHighAccuracy = highAccuracyTripMode
        val intervalMs = if (highAccuracyTripMode) 4000L else 10000L
        val priority = if (highAccuracyTripMode) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(if (highAccuracyTripMode) 2500L else 6000L)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val now = System.currentTimeMillis()
                _state.value = _state.value.copy(
                    permissionGranted = true,
                    isSharing = true,
                    isGpsSignalLost = false,
                    lastLat = loc.latitude,
                    lastLng = loc.longitude,
                    hasRealFix = true,
                    accuracyMeters = loc.accuracy,
                    speedMps = loc.speed,
                    headingDeg = loc.bearing,
                    lastUpdateEpochMs = now,
                    statusMessage = if (highAccuracyTripMode) {
                        "Live trip GPS active (${loc.accuracy.toInt()}m accuracy)"
                    } else {
                        "Duty GPS sharing active"
                    }
                )
                onFix(loc.latitude, loc.longitude, loc.accuracy, loc.speed, loc.bearing)
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                val available = availability.isLocationAvailable
                if (!available && _state.value.isSharing) {
                    _state.value = _state.value.copy(
                        isGpsSignalLost = true,
                        statusMessage = "GPS signal temporarily lost — waiting for satellite/network lock"
                    )
                }
                onSignalAvailabilityChanged(available)
            }
        }

        activeCallback = callback
        _state.value = _state.value.copy(
            isSharing = true,
            isGpsSignalLost = false,
            statusMessage = "Starting GPS location updates..."
        )

        runCatching {
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && activeCallback != null) {
                    _state.value = _state.value.copy(
                        lastLat = loc.latitude,
                        lastLng = loc.longitude,
                        hasRealFix = true,
                        accuracyMeters = loc.accuracy,
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    onFix(loc.latitude, loc.longitude, loc.accuracy, loc.speed, loc.bearing)
                }
            }
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }.onFailure { err ->
            activeCallback = null
            _state.value = _state.value.copy(
                isSharing = false,
                isGpsSignalLost = true,
                statusMessage = "GPS failed to start: ${err.message ?: "unavailable"}"
            )
            return false
        }
        return true
    }

    fun stopTracking(reason: String = "Location sharing stopped") {
        val existing = activeCallback
        activeCallback = null
        if (existing != null) {
            runCatching { client.removeLocationUpdates(existing) }
        }
        _state.value = _state.value.copy(
            isSharing = false,
            isGpsSignalLost = false,
            statusMessage = reason
        )
    }

    companion object {
        fun hasLocationPermission(context: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            return fine || coarse
        }
    }
}
