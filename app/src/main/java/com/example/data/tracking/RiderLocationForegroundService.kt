package com.example.data.tracking

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.model.Booking
import com.example.data.repository.GolocalRepository
import com.example.data.repository.RealtimeLocationRepository
import com.example.map.GpsBroadcastThrottle
import com.example.map.LocationSample
import com.example.notifications.NotificationHelper
import com.example.rider.RiderActivity
import com.example.shared.LocationCoordinator
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Location Service (location-only, no background permission requested) that keeps
 * rider live coordinates broadcasting to RTDB & Firestore while navigating in Google Maps or paused.
 */
class RiderLocationForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tracker: LiveLocationTracker? = null
    private var rtdbRepo: RealtimeLocationRepository? = null
    private var firestoreRepo: GolocalRepository? = null

    private val throttle = GpsBroadcastThrottle(
        minIntervalMs = 4_000L,
        minDistanceMeters = 10.0,
        maxAcceptedAccuracyMeters = 80f
    )

    private var activeDriverId: String = ""
    private var activeBookingId: String = ""
    private var activeCustomerId: String = ""
    private var activeTripStatus: String = ""
    private var isTestMode: Boolean = false
    private var isOnline: Boolean = true
    private var lastLat: Double = 0.0
    private var lastLng: Double = 0.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(applicationContext)
        tracker = LiveLocationTracker(applicationContext)
        rtdbRepo = RealtimeLocationRepository(applicationContext)
        if (FirebaseApp.getApps(applicationContext).isNotEmpty()) {
            val dbId = getString(R.string.firestore_database_id)
            firestoreRepo = GolocalRepository(
                db = FirebaseFirestore.getInstance(dbId),
                auth = Firebase.auth
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTrackingAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START_OR_UPDATE, null -> {
                if (!LocationCoordinator.hasLocationPermission(applicationContext)) {
                    stopTrackingAndSelf()
                    return START_NOT_STICKY
                }
                activeDriverId = intent?.getStringExtra(EXTRA_DRIVER_ID).orEmpty()
                    .ifBlank { runCatching { Firebase.auth.currentUser?.uid.orEmpty() }.getOrDefault("") }
                activeBookingId = intent?.getStringExtra(EXTRA_BOOKING_ID).orEmpty()
                activeCustomerId = intent?.getStringExtra(EXTRA_CUSTOMER_ID).orEmpty()
                activeTripStatus = intent?.getStringExtra(EXTRA_TRIP_STATUS).orEmpty()
                isTestMode = intent?.getBooleanExtra(EXTRA_IS_TEST_MODE, false) ?: false
                isOnline = intent?.getBooleanExtra(EXTRA_IS_ONLINE, true) ?: true

                if (activeDriverId.isBlank() || (!isOnline && activeBookingId.isBlank())) {
                    stopTrackingAndSelf()
                    return START_NOT_STICKY
                }

                startForegroundCompat()
                startLocationStream()
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun startForegroundCompat() {
        val openIntent = Intent(this, RiderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentTitle = when {
            activeBookingId.isNotBlank() && isTestMode -> "GoLocal Test Ride GPS Active"
            activeBookingId.isNotBlank() -> "GoLocal Live Ride Tracking Active"
            else -> "GoLocal Rider Online — Location Sharing"
        }
        val contentText = when {
            activeBookingId.isNotBlank() ->
                "Sharing live route updates with your passenger (Status: ${activeTripStatus.replace('_', ' ')})"
            else -> "Online and ready for nearby West Bengal ride requests"
        }
        val notif = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_LIVE_TRACKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(pi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun startLocationStream() {
        val highAccuracy = activeBookingId.isNotBlank()
        val liveTracker = tracker ?: return
        val started = liveTracker.startTracking(
            highAccuracyTripMode = highAccuracy,
            onFix = { lat, lng, accuracy, speed, heading ->
                lastLat = lat
                lastLng = lng
                val accepted = throttle.acceptIfQualified(
                    LocationSample(lat = lat, lng = lng, accuracyMeters = accuracy, timestampMs = System.currentTimeMillis())
                )
                if (!accepted) return@startTracking

                val booking = if (activeBookingId.isNotBlank() && activeCustomerId.isNotBlank()) {
                    Booking(
                        id = activeBookingId,
                        customerId = activeCustomerId,
                        assignedDriverId = activeDriverId,
                        status = activeTripStatus.ifBlank { "arriving" },
                        isTestRide = isTestMode,
                        testMode = isTestMode
                    )
                } else null

                serviceScope.launch {
                    runCatching {
                        rtdbRepo?.publishDriverAndTripLocation(
                            driverId = activeDriverId,
                            activeBooking = booking,
                            lat = lat,
                            lng = lng,
                            accuracyMeters = accuracy,
                            speedMps = speed,
                            headingDeg = heading,
                            trackingStatus = "active"
                        )
                        val repo = firestoreRepo ?: return@runCatching
                        if (isTestMode) {
                            repo.updateTestRiderLiveLocation(
                                activeBooking = booking,
                                isOnline = isOnline,
                                lat = lat,
                                lng = lng,
                                headingDeg = heading.toDouble(),
                                speedKmph = (speed * 3.6).coerceAtLeast(0.0)
                            )
                        } else {
                            repo.updateDriverLiveLocation(
                                activeBooking = booking,
                                isOnline = isOnline,
                                isVerified = true,
                                lat = lat,
                                lng = lng,
                                headingDeg = heading.toDouble(),
                                speedKmph = (speed * 3.6).coerceAtLeast(0.0)
                            )
                        }
                    }.onFailure { err ->
                        Log.w(TAG, "Foreground telemetry publish failed", err)
                    }
                }
            },
            onSignalAvailabilityChanged = { available ->
                if (!available && activeBookingId.isNotBlank() && GpsBroadcastThrottle.isValidCoordinate(lastLat, lastLng)) {
                    val booking = Booking(
                        id = activeBookingId,
                        customerId = activeCustomerId,
                        assignedDriverId = activeDriverId,
                        status = activeTripStatus.ifBlank { "arriving" },
                        isTestRide = isTestMode,
                        testMode = isTestMode
                    )
                    serviceScope.launch {
                        rtdbRepo?.publishDriverAndTripLocation(
                            driverId = activeDriverId,
                            activeBooking = booking,
                            lat = lastLat,
                            lng = lastLng,
                            accuracyMeters = 35f,
                            speedMps = 0f,
                            headingDeg = 0f,
                            trackingStatus = "paused_gps_lost"
                        )
                    }
                }
            }
        )
        if (!started) {
            stopTrackingAndSelf()
        }
    }

    private fun stopTrackingAndSelf() {
        val dId = activeDriverId
        val bId = activeBookingId
        val cId = activeCustomerId
        val lat = lastLat
        val lng = lastLng
        tracker?.stopTracking("Rider stopped tracking")
        if (dId.isNotBlank() && GpsBroadcastThrottle.isValidCoordinate(lat, lng)) {
            serviceScope.launch {
                rtdbRepo?.stopTripTracking(dId, bId.takeIf { it.isNotBlank() }, cId.takeIf { it.isNotBlank() }, lat, lng)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        tracker?.stopTracking("Service destroyed")
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RiderLocFgService"
        private const val NOTIFICATION_ID = 44091
        const val ACTION_START_OR_UPDATE = "com.example.action.START_OR_UPDATE_TRACKING"
        const val ACTION_STOP = "com.example.action.STOP_TRACKING"
        const val EXTRA_DRIVER_ID = "extra_driver_id"
        const val EXTRA_BOOKING_ID = "extra_booking_id"
        const val EXTRA_CUSTOMER_ID = "extra_customer_id"
        const val EXTRA_TRIP_STATUS = "extra_trip_status"
        const val EXTRA_IS_TEST_MODE = "extra_is_test_mode"
        const val EXTRA_IS_ONLINE = "extra_is_online"

        fun syncTracking(
            context: Context,
            driverId: String,
            isOnline: Boolean,
            activeBooking: Booking?,
            isTestMode: Boolean = false
        ) {
            val activeTrip = activeBooking?.takeIf {
                it.assignedDriverId == driverId &&
                    it.status in setOf("assigned", "arriving", "arrived", "in_progress")
            }
            val shouldTrack = driverId.isNotBlank() &&
                LocationCoordinator.hasLocationPermission(context) &&
                (isOnline || activeTrip != null)
            if (!shouldTrack) {
                stop(context)
                return
            }
            val intent = Intent(context, RiderLocationForegroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_DRIVER_ID, driverId)
                putExtra(EXTRA_BOOKING_ID, activeTrip?.id.orEmpty())
                putExtra(EXTRA_CUSTOMER_ID, activeTrip?.customerId.orEmpty())
                putExtra(EXTRA_TRIP_STATUS, activeTrip?.status.orEmpty())
                putExtra(EXTRA_IS_TEST_MODE, isTestMode || activeTrip?.isTestRide == true || activeTrip?.testMode == true)
                putExtra(EXTRA_IS_ONLINE, isOnline)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { err ->
                Log.w(TAG, "Unable to start foreground location service", err)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RiderLocationForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.stopService(intent) }
        }
    }
}
