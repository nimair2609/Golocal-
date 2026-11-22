package com.example.notifications

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.data.model.Booking

data class RideRequestPayload(
    val bookingId: String,
    val title: String = "",
    val body: String = "",
    val pickupLabel: String = "",
    val destinationLabel: String = "",
    val pickupLat: Double = 0.0,
    val pickupLng: Double = 0.0,
    val distanceKm: Double = 0.0,
    val estimatedDurationMin: Long = 0L,
    val estimatedFareInr: Double = 0.0,
    val minPermittedFareInr: Double = 0.0,
    val maxPermittedFareInr: Double = 0.0,
    val vehicleCategory: String = "toto",
    val paymentMethod: String = "cash",
    val passengerFirstName: String = "Passenger",
    val requestExpiresAtMs: Long = 0L,
    val dispatchWave: Int = 1,
    val isTestRide: Boolean = false,
    val isSampleRide: Boolean = false
) {
    fun writeToIntent(intent: Intent): Intent = intent.apply {
        putExtra(NotificationDeepLinkExtras.EXTRA_BOOKING_ID, bookingId)
        putExtra(EXTRA_TITLE, title)
        putExtra(EXTRA_BODY, body)
        putExtra(EXTRA_PICKUP_LABEL, pickupLabel)
        putExtra(EXTRA_DESTINATION_LABEL, destinationLabel)
        putExtra(EXTRA_PICKUP_LAT, pickupLat)
        putExtra(EXTRA_PICKUP_LNG, pickupLng)
        putExtra(EXTRA_DISTANCE_KM, distanceKm)
        putExtra(EXTRA_DURATION_MIN, estimatedDurationMin)
        putExtra(EXTRA_ESTIMATED_FARE, estimatedFareInr)
        putExtra(EXTRA_MIN_FARE, minPermittedFareInr)
        putExtra(EXTRA_MAX_FARE, maxPermittedFareInr)
        putExtra(EXTRA_VEHICLE_CATEGORY, vehicleCategory)
        putExtra(EXTRA_PAYMENT_METHOD, paymentMethod)
        putExtra(EXTRA_PASSENGER_FIRST, passengerFirstName)
        putExtra(EXTRA_REQUEST_EXPIRES_AT_MS, requestExpiresAtMs)
        putExtra(EXTRA_DISPATCH_WAVE, dispatchWave)
        putExtra(NotificationDeepLinkExtras.EXTRA_IS_TEST_RIDE, isTestRide)
        putExtra(NotificationDeepLinkExtras.EXTRA_IS_SAMPLE_RIDE, isSampleRide)
    }

    companion object {
        const val EXTRA_TITLE = "extra_req_title"
        const val EXTRA_BODY = "extra_req_body"
        const val EXTRA_PICKUP_LABEL = "extra_pickup_label"
        const val EXTRA_DESTINATION_LABEL = "extra_destination_label"
        const val EXTRA_PICKUP_LAT = "extra_pickup_lat"
        const val EXTRA_PICKUP_LNG = "extra_pickup_lng"
        const val EXTRA_DISTANCE_KM = "extra_distance_km"
        const val EXTRA_DURATION_MIN = "extra_duration_min"
        const val EXTRA_ESTIMATED_FARE = "extra_estimated_fare"
        const val EXTRA_MIN_FARE = "extra_min_fare"
        const val EXTRA_MAX_FARE = "extra_max_fare"
        const val EXTRA_VEHICLE_CATEGORY = "extra_vehicle_category"
        const val EXTRA_PAYMENT_METHOD = "extra_payment_method"
        const val EXTRA_PASSENGER_FIRST = "extra_passenger_first"
        const val EXTRA_REQUEST_EXPIRES_AT_MS = "extra_request_expires_at_ms"
        const val EXTRA_DISPATCH_WAVE = "extra_dispatch_wave"

        fun fromIntent(intent: Intent?): RideRequestPayload? {
            if (intent == null) return null
            val bookingId = intent.getStringExtra(NotificationDeepLinkExtras.EXTRA_BOOKING_ID)
                ?: intent.getStringExtra("bookingId")
                ?: ""
            if (bookingId.isBlank()) return null
            val defaultExpiry = System.currentTimeMillis() + NotificationHelper.RIDE_REQUEST_TIMEOUT_MS
            val expiresAt = intent.getLongExtra(EXTRA_REQUEST_EXPIRES_AT_MS, 0L)
                .takeIf { it > 0L } ?: defaultExpiry
            val estFare = intent.getDoubleExtra(EXTRA_ESTIMATED_FARE, 0.0)
            return RideRequestPayload(
                bookingId = bookingId.trim(),
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                body = intent.getStringExtra(EXTRA_BODY).orEmpty(),
                pickupLabel = intent.getStringExtra(EXTRA_PICKUP_LABEL).orEmpty(),
                destinationLabel = intent.getStringExtra(EXTRA_DESTINATION_LABEL).orEmpty(),
                pickupLat = intent.getDoubleExtra(EXTRA_PICKUP_LAT, 0.0),
                pickupLng = intent.getDoubleExtra(EXTRA_PICKUP_LNG, 0.0),
                distanceKm = intent.getDoubleExtra(EXTRA_DISTANCE_KM, 0.0),
                estimatedDurationMin = intent.getLongExtra(EXTRA_DURATION_MIN, 0L),
                estimatedFareInr = estFare,
                minPermittedFareInr = intent.getDoubleExtra(EXTRA_MIN_FARE, estFare),
                maxPermittedFareInr = intent.getDoubleExtra(EXTRA_MAX_FARE, estFare),
                vehicleCategory = intent.getStringExtra(EXTRA_VEHICLE_CATEGORY).orEmpty().ifBlank { "toto" },
                paymentMethod = intent.getStringExtra(EXTRA_PAYMENT_METHOD).orEmpty().ifBlank { "cash" },
                passengerFirstName = intent.getStringExtra(EXTRA_PASSENGER_FIRST).orEmpty().ifBlank { "Passenger" },
                requestExpiresAtMs = expiresAt,
                dispatchWave = intent.getIntExtra(EXTRA_DISPATCH_WAVE, 1).coerceAtLeast(1),
                isTestRide = intent.getBooleanExtra(NotificationDeepLinkExtras.EXTRA_IS_TEST_RIDE, false),
                isSampleRide = intent.getBooleanExtra(NotificationDeepLinkExtras.EXTRA_IS_SAMPLE_RIDE, false)
            )
        }

        fun fromFcmData(
            data: Map<String, String>,
            bookingId: String,
            title: String,
            body: String,
            isTestRide: Boolean,
            isSampleRide: Boolean
        ): RideRequestPayload {
            val estFare = data["estimatedFareInr"]?.toDoubleOrNull() ?: 0.0
            val expiresAt = (data["requestExpiresAtMs"] ?: data["request_expires_at_ms"])
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: (System.currentTimeMillis() + NotificationHelper.RIDE_REQUEST_TIMEOUT_MS)
            return RideRequestPayload(
                bookingId = bookingId,
                title = title,
                body = body,
                pickupLabel = data["pickupLabel"].orEmpty(),
                destinationLabel = data["destinationLabel"].orEmpty(),
                pickupLat = data["pickupLat"]?.toDoubleOrNull() ?: 0.0,
                pickupLng = data["pickupLng"]?.toDoubleOrNull() ?: 0.0,
                distanceKm = data["distanceKm"]?.toDoubleOrNull() ?: 0.0,
                estimatedDurationMin = data["estimatedDurationMin"]?.toLongOrNull() ?: 0L,
                estimatedFareInr = estFare,
                minPermittedFareInr = data["minPermittedFareInr"]?.toDoubleOrNull() ?: estFare,
                maxPermittedFareInr = data["maxPermittedFareInr"]?.toDoubleOrNull() ?: estFare,
                vehicleCategory = data["vehicleCategory"].orEmpty().ifBlank { "toto" },
                paymentMethod = data["paymentMethod"].orEmpty().ifBlank { "cash" },
                passengerFirstName = data["passengerFirstName"].orEmpty().ifBlank { "Passenger" },
                requestExpiresAtMs = expiresAt,
                dispatchWave = data["dispatchWave"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                isTestRide = isTestRide,
                isSampleRide = isSampleRide
            )
        }

        fun fromBooking(
            booking: Booking,
            title: String,
            body: String
        ): RideRequestPayload {
            val firstName = booking.customerName.trim().substringBefore(" ").ifBlank { "Passenger" }
            val expiresAt = booking.requestExpiresAtMs.takeIf { it > 0L }
                ?: (System.currentTimeMillis() + NotificationHelper.RIDE_REQUEST_TIMEOUT_MS)
            return RideRequestPayload(
                bookingId = booking.id,
                title = title,
                body = body,
                pickupLabel = booking.pickupLabel,
                destinationLabel = booking.destinationLabel,
                pickupLat = booking.pickupLat,
                pickupLng = booking.pickupLng,
                distanceKm = booking.distanceKm,
                estimatedDurationMin = booking.estimatedDurationMin,
                estimatedFareInr = booking.estimatedFareInr,
                minPermittedFareInr = booking.minPermittedFareInr,
                maxPermittedFareInr = booking.maxPermittedFareInr,
                vehicleCategory = booking.vehicleCategory.ifBlank { "toto" },
                paymentMethod = booking.paymentMethod.ifBlank { "cash" },
                passengerFirstName = firstName,
                requestExpiresAtMs = expiresAt,
                dispatchWave = 1,
                isTestRide = booking.isTestRide || booking.testMode,
                isSampleRide = false
            )
        }

        fun fromSampleRide(
            ride: com.example.data.model.SampleRide,
            title: String,
            body: String
        ): RideRequestPayload {
            val firstName = ride.customerName.trim().substringBefore(" ").ifBlank { "Passenger" }
            val expiresAt = (ride.expiresAt?.toDate()?.time ?: 0L).takeIf { it > 0L }
                ?: (System.currentTimeMillis() + NotificationHelper.RIDE_REQUEST_TIMEOUT_MS)
            return RideRequestPayload(
                bookingId = ride.rideId.ifBlank { ride.id },
                title = title,
                body = body,
                pickupLabel = ride.pickupLabel,
                destinationLabel = ride.destinationLabel,
                pickupLat = ride.pickupLat,
                pickupLng = ride.pickupLng,
                distanceKm = ride.distanceKm,
                estimatedDurationMin = ride.estimatedDurationMin.toLong(),
                estimatedFareInr = ride.estimatedFareInr,
                minPermittedFareInr = ride.estimatedFareInr,
                maxPermittedFareInr = ride.estimatedFareInr,
                vehicleCategory = ride.vehicleCategory.ifBlank { "toto" },
                paymentMethod = "sample",
                passengerFirstName = firstName,
                requestExpiresAtMs = expiresAt,
                dispatchWave = 1,
                isTestRide = false,
                isSampleRide = true
            )
        }
    }
}

object RideRequestAlertController {
    private const val TAG = "GoLocalAlertCtrl"
    const val ACTION_DISMISS_RIDE_REQUEST_UI = "com.aistudio.golocal.DISMISS_RIDE_REQUEST_UI"

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var activeVibrator: Vibrator? = null
    private var activeBookingId: String? = null
    private var timeoutRunnable: Runnable? = null

    fun startLoopingAlert(
        context: Context,
        bookingId: String,
        expiresAtMs: Long,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean
    ): Boolean {
        val cleanId = bookingId.trim()
        if (cleanId.isBlank()) return false
        if (NotificationHelper.isDeviceDoNotDisturbActive(context)) return false
        if (!soundEnabled && !vibrationEnabled) return false

        val appCtx = context.applicationContext
        val nowMs = System.currentTimeMillis()
        val durationMs = if (expiresAtMs > 0L) {
            (expiresAtMs - nowMs).coerceIn(1_000L, NotificationHelper.RIDE_REQUEST_TIMEOUT_MS)
        } else {
            NotificationHelper.RIDE_REQUEST_TIMEOUT_MS
        }

        synchronized(lock) {
            stopMediaLocked()
            activeBookingId = cleanId

            var soundStarted = false
            if (soundEnabled) {
                soundStarted = runCatching {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    val player = MediaPlayer().apply {
                        setAudioAttributes(attrs)
                        setDataSource(appCtx, NotificationHelper.rideRequestSoundUri(appCtx))
                        isLooping = true
                        prepare()
                        start()
                    }
                    mediaPlayer = player
                    true
                }.onFailure { err ->
                    Log.w(TAG, "Unable to start looping MediaPlayer alert; falling back to notification sound", err)
                }.getOrDefault(false)
            }

            if (vibrationEnabled) {
                runCatching {
                    val vib = resolveVibrator(appCtx)
                    if (vib != null) {
                        activeVibrator = vib
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vib.vibrate(
                                VibrationEffect.createWaveform(
                                    NotificationHelper.RIDE_REQUEST_VIBRATION_PATTERN,
                                    0
                                )
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            vib.vibrate(NotificationHelper.RIDE_REQUEST_VIBRATION_PATTERN, 0)
                        }
                    }
                }.onFailure { err ->
                    Log.w(TAG, "Unable to start looping vibration", err)
                }
            }

            val autoStop = Runnable {
                stopAlert(appCtx, cleanId)
            }
            timeoutRunnable = autoStop
            mainHandler.postDelayed(autoStop, durationMs)
            return soundStarted
        }
    }

    fun stopMediaOnly(context: Context, bookingId: String? = null) {
        val cleanId = bookingId?.trim()
        synchronized(lock) {
            if (cleanId.isNullOrBlank() || activeBookingId == null || activeBookingId == cleanId) {
                stopMediaLocked()
            }
        }
        if (!cleanId.isNullOrBlank()) {
            sendDismissBroadcast(context, cleanId)
        }
    }

    fun stopAlert(context: Context, bookingId: String? = null) {
        val cleanId = bookingId?.trim()
        val targetToDismiss: String?
        synchronized(lock) {
            targetToDismiss = cleanId?.takeIf { it.isNotBlank() } ?: activeBookingId
            if (cleanId.isNullOrBlank() || activeBookingId == null || activeBookingId == cleanId) {
                stopMediaLocked()
            }
        }
        if (!targetToDismiss.isNullOrBlank()) {
            NotificationHelper.cancelNotificationOnly(context, targetToDismiss)
            sendDismissBroadcast(context, targetToDismiss)
        }
    }

    private fun stopMediaLocked() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        runCatching {
            mediaPlayer?.let { player ->
                if (player.isPlaying) player.stop()
                player.reset()
                player.release()
            }
        }
        mediaPlayer = null
        runCatching { activeVibrator?.cancel() }
        activeVibrator = null
        activeBookingId = null
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun sendDismissBroadcast(context: Context, bookingId: String) {
        runCatching {
            val intent = Intent(ACTION_DISMISS_RIDE_REQUEST_UI).apply {
                setPackage(context.packageName)
                putExtra(NotificationDeepLinkExtras.EXTRA_BOOKING_ID, bookingId)
            }
            context.applicationContext.sendBroadcast(intent)
        }
    }
}
