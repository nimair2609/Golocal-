package com.example.shared

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.data.model.DriverDirectoryEntry
import com.example.domain.FareCalculator
import com.example.domain.WestBengalCatalog
import com.example.domain.WestBengalPlace
import com.example.map.GpsBroadcastThrottle
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

enum class LocationAccessState {
    GRANTED_READY,
    PERMISSION_REQUIRED,
    GPS_DISABLED
}

data class DeviceGpsFix(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float,
    val timestampMs: Long,
    val speedMps: Float = 0f,
    val bearingDeg: Float = 0f
)

object LocationCoordinator {

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

    fun isGpsEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }

    fun resolveAccessState(context: Context): LocationAccessState {
        if (!hasLocationPermission(context)) return LocationAccessState.PERMISSION_REQUIRED
        if (!isGpsEnabled(context)) return LocationAccessState.GPS_DISABLED
        return LocationAccessState.GRANTED_READY
    }

    fun openLocationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openAppPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openExternalNavigation(context: Context, lat: Double, lng: Double, label: String) {
        if (!GpsBroadcastThrottle.isValidCoordinate(lat, lng)) return
        val turnByTurnUri = Uri.parse(
            String.format(Locale.US, "google.navigation:q=%.6f,%.6f&mode=d", lat, lng)
        )
        val navIntent = Intent(Intent.ACTION_VIEW, turnByTurnUri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (navIntent.resolveActivity(context.packageManager) != null) {
            val started = runCatching { context.startActivity(navIntent) }.isSuccess
            if (started) return
        }
        val encodedLabel = Uri.encode(label.ifBlank { "GoLocal Stop" })
        val webMapsUri = Uri.parse(
            String.format(
                Locale.US,
                "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f&travelmode=driving&destination_place_id=%s",
                lat,
                lng,
                encodedLabel
            )
        )
        val fallbackIntent = Intent(Intent.ACTION_VIEW, webMapsUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (fallbackIntent.resolveActivity(context.packageManager) != null) {
            runCatching { context.startActivity(fallbackIntent) }
        } else {
            val geoIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", lat, lng, lat, lng, encodedLabel))
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(geoIntent) }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun fetchBestCurrentLocation(context: Context): DeviceGpsFix? {
        if (!hasLocationPermission(context) || !isGpsEnabled(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        return suspendCancellableCoroutine { cont ->
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null && GpsBroadcastThrottle.isValidCoordinate(loc.latitude, loc.longitude)) {
                        cont.resume(
                            DeviceGpsFix(
                                lat = loc.latitude,
                                lng = loc.longitude,
                                accuracyMeters = loc.accuracy.takeIf { it > 0f } ?: 20f,
                                timestampMs = loc.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                                speedMps = if (loc.hasSpeed()) loc.speed.coerceAtLeast(0f) else 0f,
                                bearingDeg = if (loc.hasBearing()) loc.bearing else 0f
                            )
                        )
                    } else {
                        client.lastLocation
                            .addOnSuccessListener { last ->
                                if (last != null && GpsBroadcastThrottle.isValidCoordinate(last.latitude, last.longitude)) {
                                    cont.resume(
                                        DeviceGpsFix(
                                            lat = last.latitude,
                                            lng = last.longitude,
                                            accuracyMeters = last.accuracy.takeIf { it > 0f } ?: 35f,
                                            timestampMs = last.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                                            speedMps = if (last.hasSpeed()) last.speed.coerceAtLeast(0f) else 0f,
                                            bearingDeg = if (last.hasBearing()) last.bearing else 0f
                                        )
                                    )
                                } else {
                                    cont.resume(null)
                                }
                            }
                            .addOnFailureListener { cont.resume(null) }
                    }
                }
                .addOnFailureListener { cont.resume(null) }
        }
    }

    @SuppressLint("MissingPermission")
    fun observeLocationUpdates(
        context: Context,
        intervalMs: Long = 4_000L
    ): Flow<DeviceGpsFix> = callbackFlow {
        if (!hasLocationPermission(context)) {
            close()
            return@callbackFlow
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val minDistMeters = if (intervalMs <= 3_000L) 5f else 10f
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs.coerceAtMost(2_500L))
            .setMinUpdateDistanceMeters(minDistMeters)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                if (GpsBroadcastThrottle.isValidCoordinate(loc.latitude, loc.longitude)) {
                    trySend(
                        DeviceGpsFix(
                            lat = loc.latitude,
                            lng = loc.longitude,
                            accuracyMeters = loc.accuracy.takeIf { it > 0f } ?: 20f,
                            timestampMs = loc.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                            speedMps = if (loc.hasSpeed()) loc.speed.coerceAtLeast(0f) else 0f,
                            bearingDeg = if (loc.hasBearing()) loc.bearing else 0f
                        )
                    )
                }
            }
        }
        runCatching {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }.onFailure {
            close()
        }
        awaitClose {
            runCatching { client.removeLocationUpdates(callback) }
        }
    }

    fun nearestCatalogPlace(lat: Double, lng: Double): WestBengalPlace? {
        if (!GpsBroadcastThrottle.isValidCoordinate(lat, lng)) return null
        val nearest = WestBengalCatalog.places.minByOrNull { place ->
            FareCalculator.haversineDistanceKm(lat, lng, place.lat, place.lng)
        } ?: return null
        val distanceKm = FareCalculator.haversineDistanceKm(lat, lng, nearest.lat, nearest.lng)
        return if (distanceKm <= 25.0) nearest else null
    }

    fun formatCoordinateLabel(lat: Double, lng: Double): String {
        val nearby = nearestCatalogPlace(lat, lng)
        return if (nearby != null) {
            val km = FareCalculator.haversineDistanceKm(lat, lng, nearby.lat, nearby.lng)
            if (km < 0.35) nearby.nameEn
            else "Near ${nearby.nameEn}"
        } else {
            String.format(Locale.US, "GPS Pin (%.4f, %.4f)", lat, lng)
        }
    }

    fun estimateNearestDriverEtaMinutes(
        onlineDrivers: List<DriverDirectoryEntry>,
        pickupLat: Double,
        pickupLng: Double,
        category: String? = null
    ): Int? {
        val candidates = onlineDrivers.filter { drv ->
            drv.isOnline &&
                GpsBroadcastThrottle.isValidCoordinate(drv.currentLat, drv.currentLng) &&
                (category == null || drv.vehicleCategory == category)
        }
        if (candidates.isEmpty()) return null
        val minKm = candidates.minOf { drv ->
            FareCalculator.haversineDistanceKm(pickupLat, pickupLng, drv.currentLat, drv.currentLng)
        }
        val speedKmH = when (category) {
            "bike" -> 28.0
            "cab", "outstation", "scheduled" -> 32.0
            else -> 20.0
        }
        return ((minKm / speedKmH) * 60.0).roundToInt().coerceIn(2, 60)
    }
}
