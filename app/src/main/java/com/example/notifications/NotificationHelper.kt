package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.admin.AdminActivity
import com.example.data.model.AppNotification
import com.example.rider.RiderActivity
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import java.util.Collections

object NotificationDeepLinkExtras {
    const val EXTRA_TARGET_SCREEN = "extra_target_screen"
    const val EXTRA_BOOKING_ID = "extra_booking_id"
    const val EXTRA_NOTIFICATION_TYPE = "extra_notification_type"
    const val EXTRA_TARGET_APP = "extra_target_app"
    const val EXTRA_IS_TEST_RIDE = "extra_is_test_ride"
    const val EXTRA_IS_SAMPLE_RIDE = "extra_is_sample_ride"

    const val ACTION_OPEN_CUSTOMER = "com.aistudio.golocal.OPEN_CUSTOMER"
    const val ACTION_OPEN_RIDER = "com.aistudio.golocal.OPEN_RIDER"
    const val ACTION_OPEN_ADMIN = "com.aistudio.golocal.OPEN_ADMIN"
}

data class NotificationDeepLinkTarget(
    val targetScreen: String = "",
    val bookingId: String = "",
    val type: String = "",
    val targetApp: String = "",
    val isTestRide: Boolean = false,
    val isSampleRide: Boolean = false
) {
    companion object {
        fun fromIntent(intent: Intent?): NotificationDeepLinkTarget? {
            if (intent == null) return null
            val extras = intent.extras
            fun str(vararg keys: String): String {
                for (k in keys) {
                    val v = intent.getStringExtra(k)
                        ?: extras?.getString(k)
                    if (!v.isNullOrBlank()) return v.trim()
                }
                return ""
            }

            val screen = str(
                NotificationDeepLinkExtras.EXTRA_TARGET_SCREEN,
                "targetScreen",
                "target_screen"
            )
            val bookingId = str(
                NotificationDeepLinkExtras.EXTRA_BOOKING_ID,
                "bookingId",
                "booking_id",
                "entityId",
                "entity_id"
            )
            val type = str(
                NotificationDeepLinkExtras.EXTRA_NOTIFICATION_TYPE,
                "type"
            )
            val appFromAction = when (intent.action) {
                NotificationDeepLinkExtras.ACTION_OPEN_RIDER -> "rider"
                NotificationDeepLinkExtras.ACTION_OPEN_ADMIN -> "admin"
                NotificationDeepLinkExtras.ACTION_OPEN_CUSTOMER -> "customer"
                else -> ""
            }
            val targetApp = str(
                NotificationDeepLinkExtras.EXTRA_TARGET_APP,
                "targetApp",
                "target_app",
                "targetRole"
            ).ifBlank { appFromAction }

            val isSampleRide = intent.getBooleanExtra(NotificationDeepLinkExtras.EXTRA_IS_SAMPLE_RIDE, false) ||
                str("isSampleRide", "is_sample_ride", "isSample").equals("true", ignoreCase = true) ||
                type.startsWith("sample_", ignoreCase = true)

            val isTestRide = !isSampleRide && (
                intent.getBooleanExtra(NotificationDeepLinkExtras.EXTRA_IS_TEST_RIDE, false) ||
                    str("isTestRide", "is_test_ride").equals("true", ignoreCase = true) ||
                    type.startsWith("test_", ignoreCase = true)
                )

            return if (screen.isNotBlank() || bookingId.isNotBlank() || type.isNotBlank()) {
                NotificationDeepLinkTarget(
                    targetScreen = screen,
                    bookingId = bookingId,
                    type = type,
                    targetApp = targetApp,
                    isTestRide = isTestRide,
                    isSampleRide = isSampleRide
                )
            } else {
                null
            }
        }

        fun fromAppNotification(notif: AppNotification): NotificationDeepLinkTarget {
            val isSample = notif.type.startsWith("sample_", ignoreCase = true)
            return NotificationDeepLinkTarget(
                targetScreen = notif.targetScreen,
                bookingId = notif.bookingId.ifBlank { notif.entityId },
                type = notif.type,
                targetApp = notif.targetApp,
                isTestRide = !isSample && notif.isTestNotification(),
                isSampleRide = isSample
            )
        }
    }
}

object NotificationPrivacySanitizer {
    private val secretPinOtpRegex = Regex(
        pattern = """\b(otp|pin|trip\s*pin|passcode|password)\b\s*[:=\-#]?\s*[0-9]{4,8}\b""",
        option = RegexOption.IGNORE_CASE
    )
    private val phoneRegex = Regex("""(?<!\w)(?:\+?91[\s\-]?)?[6-9][0-9]{9}(?!\w)""")

    fun sanitizeText(raw: String): String {
        if (raw.isBlank()) return ""
        val noSecrets = secretPinOtpRegex.replace(raw) { match ->
            val keyword = match.groupValues.getOrNull(1)?.uppercase().orEmpty().ifBlank { "CODE" }
            "$keyword protected (view in app)"
        }
        return phoneRegex.replace(noSecrets, "[protected-contact]").trim()
    }
}

object NotificationHelper {
    private const val TAG = "GoLocalFCM"
    const val CHANNEL_RIDES = "golocal_rides"
    const val CHANNEL_RIDE_REQUESTS_V2 = "golocal_ride_requests_v2"
    const val CHANNEL_TEST_RIDE_REQUESTS_V2 = "golocal_test_ride_requests_v2"
    const val CHANNEL_SAMPLE_RIDE_REQUESTS_V2 = "golocal_sample_ride_requests_v2"
    const val CHANNEL_SAMPLE_RIDES = "golocal_sample_rides"
    const val CHANNEL_LIVE_TRACKING = "golocal_live_tracking"
    const val CHANNEL_CHAT = "golocal_chat"
    const val CHANNEL_SOS = "golocal_sos"
    const val CHANNEL_TEST_RIDES = "golocal_test_rides"
    const val CHANNEL_GENERAL = "golocal_general"
    const val RIDE_REQUEST_TIMEOUT_MS = 45_000L

    val RIDE_REQUEST_VIBRATION_PATTERN = longArrayOf(0L, 280L, 140L, 360L, 140L, 280L)

    private const val PREFS_FCM_META = "golocal_fcm"
    private const val KEY_APP_VARIANT_PREFIX = "app_variant_"
    private const val KEY_DEVICE_ID = "fcm_device_id"
    val ALLOWED_APP_VARIANTS = setOf("customer", "rider", "admin")

    private val recentDedupeKeys: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 64
            }
        )
    )

    fun validateAppVariant(appVariant: String?): String? {
        val clean = appVariant?.trim()?.lowercase().orEmpty()
        return clean.takeIf { it in ALLOWED_APP_VARIANTS }
    }

    fun fcmVariantPrefKey(userId: String): String = "$KEY_APP_VARIANT_PREFIX${userId.trim()}"

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_FCM_META, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null).orEmpty()
        if (existing.isNotBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun saveUserPortalVariant(context: Context, userId: String, appVariant: String): String? {
        val cleanUid = userId.trim()
        if (cleanUid.isBlank()) return null
        val validVariant = validateAppVariant(appVariant) ?: return null
        context.applicationContext
            .getSharedPreferences(PREFS_FCM_META, Context.MODE_PRIVATE)
            .edit()
            .putString(fcmVariantPrefKey(cleanUid), validVariant)
            .apply()
        return validVariant
    }

    fun getSavedUserPortalVariant(context: Context, userId: String): String? {
        val cleanUid = userId.trim()
        if (cleanUid.isBlank()) return null
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_FCM_META, Context.MODE_PRIVATE)
            .getString(fcmVariantPrefKey(cleanUid), null)
        return validateAppVariant(raw)
    }

    fun clearSavedUserPortalVariant(context: Context, userId: String) {
        val cleanUid = userId.trim()
        if (cleanUid.isBlank()) return
        context.applicationContext
            .getSharedPreferences(PREFS_FCM_META, Context.MODE_PRIVATE)
            .edit()
            .remove(fcmVariantPrefKey(cleanUid))
            .apply()
    }

    fun resolveRefreshedTokenTarget(
        context: Context,
        currentUid: String?,
        token: String
    ): Pair<String, String>? {
        if (token.isBlank()) {
            Log.w(TAG, "Skipping refreshed FCM token write: token is blank")
            return null
        }
        val uid = currentUid?.trim().orEmpty()
        if (uid.isBlank()) {
            Log.i(TAG, "Skipping refreshed FCM token write: no authenticated Firebase user")
            return null
        }
        val validVariant = getSavedUserPortalVariant(context, uid)
        if (validVariant == null) {
            Log.w(TAG, "Skipping refreshed FCM token write for uid=$uid: no valid saved portal variant in $PREFS_FCM_META")
            return null
        }
        return uid to validVariant
    }

    fun tokenDocId(userId: String, appVariant: String, deviceId: String = ""): String? {
        val cleanUid = userId.trim().replace("/", "_")
        val validVariant = validateAppVariant(appVariant) ?: return null
        if (cleanUid.isBlank()) return null
        val cleanDevice = deviceId.trim().replace("/", "_").replace(" ", "")
        return if (cleanDevice.isNotBlank()) {
            "${cleanUid}_${validVariant}_${cleanDevice}".take(128)
        } else {
            "${cleanUid}_${validVariant}".take(128)
        }
    }

    fun normalizeVariant(appVariant: String): String = when (appVariant.trim().lowercase()) {
        "rider", "driver" -> "rider"
        "admin", "ops", "ops_staff" -> "admin"
        else -> "customer"
    }

    fun clickActionForApp(targetApp: String): String = when (normalizeVariant(targetApp)) {
        "rider" -> NotificationDeepLinkExtras.ACTION_OPEN_RIDER
        "admin" -> NotificationDeepLinkExtras.ACTION_OPEN_ADMIN
        else -> NotificationDeepLinkExtras.ACTION_OPEN_CUSTOMER
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.canUseFullScreenIntent() ?: true
        } else {
            true
        }
    }

    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }.onFailure {
                val fallback = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(fallback) }
            }
        }
    }

    fun isDeviceDoNotDisturbActive(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    fun rideRequestSoundUri(context: Context): android.net.Uri =
        android.net.Uri.parse("android.resource://${context.packageName}/${R.raw.golocal_ride_request}")

    fun rideRequestNotificationId(bookingId: String): Int =
        "ride_req|${bookingId.trim()}".hashCode()

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: defaultSound
        val customRideRequestSound = rideRequestSoundUri(context)
        val notifAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val rideRequestAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sosAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channels = listOf(
            NotificationChannel(
                CHANNEL_RIDES,
                context.getString(R.string.notif_channel_rides),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Ride status, driver assignment, arrival alerts, bus bookings, and trip updates"
                setSound(defaultSound, notifAudio)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 220, 120, 220)
                enableLights(true)
            },
            NotificationChannel(
                CHANNEL_RIDE_REQUESTS_V2,
                context.getString(R.string.notif_channel_ride_requests_v2),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time incoming ride requests with custom GoLocal chime and vibration"
                setSound(customRideRequestSound, rideRequestAudio)
                enableVibration(true)
                vibrationPattern = RIDE_REQUEST_VIBRATION_PATTERN
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
            NotificationChannel(
                CHANNEL_TEST_RIDE_REQUESTS_V2,
                context.getString(R.string.notif_channel_test_ride_requests_v2),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming Test Ride requests for approved QA testers (non-commercial)"
                setSound(customRideRequestSound, rideRequestAudio)
                enableVibration(true)
                vibrationPattern = RIDE_REQUEST_VIBRATION_PATTERN
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
            NotificationChannel(
                CHANNEL_LIVE_TRACKING,
                context.getString(R.string.notif_channel_live_tracking),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing foreground GPS tracking notification while active on GoLocal Rider"
                setSound(null, null)
                enableVibration(false)
            },
            NotificationChannel(
                CHANNEL_CHAT,
                context.getString(R.string.notif_channel_chat),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Two-way trip messages between customer and rider"
                setSound(defaultSound, notifAudio)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 80, 150)
            },
            NotificationChannel(
                CHANNEL_SOS,
                context.getString(R.string.notif_channel_sos),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Emergency SOS safety alerts, disputes, and urgent escalations"
                setSound(alarmSound, sosAudio)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 500)
                enableLights(true)
            },
            NotificationChannel(
                CHANNEL_TEST_RIDES,
                context.getString(R.string.notif_channel_test_rides),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Isolated Test Rider Mode notifications separated from commercial trips"
                setSound(defaultSound, notifAudio)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 160, 100, 160)
            },
            NotificationChannel(
                CHANNEL_SAMPLE_RIDE_REQUESTS_V2,
                context.getString(R.string.notif_channel_sample_ride_requests_v2),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming isolated Sample Ride requests with GoLocal chime and vibration"
                setSound(customRideRequestSound, rideRequestAudio)
                enableVibration(true)
                vibrationPattern = RIDE_REQUEST_VIBRATION_PATTERN
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
            NotificationChannel(
                CHANNEL_SAMPLE_RIDES,
                context.getString(R.string.notif_channel_sample_rides),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Isolated Sample Rider trip lifecycle notifications"
                setSound(defaultSound, notifAudio)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 100, 180)
            },
            NotificationChannel(
                CHANNEL_GENERAL,
                context.getString(R.string.notif_channel_general),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "KYC document review, complaints, appeals, and governance notices"
                setSound(defaultSound, notifAudio)
                enableVibration(true)
            }
        )
        channels.forEach { manager.createNotificationChannel(it) }
    }

    fun channelForType(type: String): String = channelForType(type = type, isTestRide = false, isSampleRide = false)

    fun channelForType(type: String, isTestRide: Boolean): String =
        channelForType(type = type, isTestRide = isTestRide, isSampleRide = false)

    fun channelForType(type: String, isTestRide: Boolean, isSampleRide: Boolean): String = when {
        type.equals("sample_request", true) -> CHANNEL_SAMPLE_RIDE_REQUESTS_V2
        isSampleRide || type.startsWith("sample_", ignoreCase = true) -> CHANNEL_SAMPLE_RIDES
        type.equals("test_request", true) -> CHANNEL_TEST_RIDE_REQUESTS_V2
        type.equals("new_ride_request", true) ->
            if (isTestRide) CHANNEL_TEST_RIDE_REQUESTS_V2 else CHANNEL_RIDE_REQUESTS_V2
        isTestRide || type.startsWith("test_", ignoreCase = true) -> CHANNEL_TEST_RIDES
        type.equals("chat", true) || type.equals("booking_chat", true) -> CHANNEL_CHAT
        type.equals("sos", true) ||
            type.equals("emergency", true) ||
            type.equals("sos_alert", true) ||
            type.equals("dispute_opened", true) -> CHANNEL_SOS
        type.startsWith("booking", true) ||
            type.startsWith("driver_booking", true) ||
            type.equals("ride_offer", true) ||
            type.equals("driver_assigned", true) ||
            type.equals("trip_status", true) ||
            type.equals("kyc_status_update", true) ||
            type.equals("document_expiry_reminder", true) ||
            type.startsWith("bus_", true) -> CHANNEL_RIDES
        else -> CHANNEL_GENERAL
    }

    fun shouldDisplayNotification(dedupeKey: String): Boolean {
        if (dedupeKey.isBlank()) return true
        return recentDedupeKeys.add(dedupeKey)
    }

    fun buildRideRequestActionPendingIntent(
        context: Context,
        action: String,
        bookingId: String,
        isTestRide: Boolean,
        isSampleRide: Boolean,
        requestCode: Int
    ): PendingIntent {
        val actionIntent = Intent(context, RideRequestActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationDeepLinkExtras.EXTRA_BOOKING_ID, bookingId)
            putExtra(NotificationDeepLinkExtras.EXTRA_IS_TEST_RIDE, isTestRide)
            putExtra(NotificationDeepLinkExtras.EXTRA_IS_SAMPLE_RIDE, isSampleRide)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun displayRideRequestAlert(
        context: Context,
        bookingId: String,
        title: String,
        body: String,
        isTestRide: Boolean = false,
        soundEnabled: Boolean = true,
        vibrationEnabled: Boolean = true,
        notificationsEnabled: Boolean = true,
        payload: RideRequestPayload? = null
    ) {
        if (bookingId.isBlank() || !notificationsEnabled) return
        val nowMs = System.currentTimeMillis()
        if (payload != null && payload.requestExpiresAtMs > 0L && nowMs > payload.requestExpiresAtMs) {
            return
        }
        val safeTitle = NotificationPrivacySanitizer.sanitizeText(title)
        val safeBody = NotificationPrivacySanitizer.sanitizeText(body)
        if (safeTitle.isBlank() && safeBody.isBlank()) return

        val dedupeKey = "ride_alert|${bookingId.trim()}|$isTestRide"
        if (!shouldDisplayNotification(dedupeKey)) return

        ensureChannels(context)
        if (!hasNotificationPermission(context)) return

        val effectivePayload = payload ?: RideRequestPayload(
            bookingId = bookingId.trim(),
            title = safeTitle,
            body = safeBody,
            requestExpiresAtMs = nowMs + RIDE_REQUEST_TIMEOUT_MS,
            isTestRide = isTestRide,
            isSampleRide = false
        )
        val remainingTimeoutMs = if (effectivePayload.requestExpiresAtMs > 0L) {
            (effectivePayload.requestExpiresAtMs - nowMs).coerceIn(1_000L, RIDE_REQUEST_TIMEOUT_MS)
        } else {
            RIDE_REQUEST_TIMEOUT_MS
        }

        val notifId = rideRequestNotificationId(bookingId)
        val contentIntent = Intent(context, RiderActivity::class.java).apply {
            action = NotificationDeepLinkExtras.ACTION_OPEN_RIDER
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NotificationDeepLinkExtras.EXTRA_TARGET_SCREEN, if (isTestRide) "rides" else "requests")
            putExtra(NotificationDeepLinkExtras.EXTRA_BOOKING_ID, bookingId)
            putExtra(
                NotificationDeepLinkExtras.EXTRA_NOTIFICATION_TYPE,
                if (isTestRide) "test_request" else "new_ride_request"
            )
            putExtra(NotificationDeepLinkExtras.EXTRA_TARGET_APP, "rider")
            putExtra(NotificationDeepLinkExtras.EXTRA_IS_TEST_RIDE, isTestRide)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenIntent = Intent(context, RideRequestActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            effectivePayload.writeToIntent(this)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            notifId xor 0x3311,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val acceptPendingIntent = buildRideRequestActionPendingIntent(
            context = context,
            action = RideRequestActionReceiver.ACTION_ACCEPT_RIDE,
            bookingId = bookingId,
            isTestRide = isTestRide,
            isSampleRide = false,
            requestCode = notifId xor 0x11
        )
        val declinePendingIntent = buildRideRequestActionPendingIntent(
            context = context,
            action = RideRequestActionReceiver.ACTION_DECLINE_RIDE,
            bookingId = bookingId,
            isTestRide = isTestRide,
            isSampleRide = false,
            requestCode = notifId xor 0x22
        )

        val loopingSoundStarted = RideRequestAlertController.startLoopingAlert(
            context = context,
            bookingId = bookingId,
            expiresAtMs = effectivePayload.requestExpiresAtMs,
            soundEnabled = soundEnabled,
            vibrationEnabled = vibrationEnabled
        )

        val dndActive = isDeviceDoNotDisturbActive(context)
        val channelId = if (isTestRide) CHANNEL_TEST_RIDE_REQUESTS_V2 else CHANNEL_RIDE_REQUESTS_V2
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(safeTitle.ifBlank { context.getString(R.string.app_name_rider) })
            .setContentText(safeBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(remainingTimeoutMs)
            .setContentIntent(contentPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(R.drawable.ic_notification, "Accept Ride", acceptPendingIntent)
            .addAction(R.drawable.ic_notification, "Decline", declinePendingIntent)

        if (dndActive || loopingSoundStarted || (!soundEnabled && !vibrationEnabled)) {
            builder.setSilent(true)
        } else {
            if (soundEnabled) {
                builder.setSound(rideRequestSoundUri(context))
            } else {
                builder.setSound(null)
            }
            if (vibrationEnabled) {
                builder.setVibrate(RIDE_REQUEST_VIBRATION_PATTERN)
            } else {
                builder.setVibrate(longArrayOf(0L))
            }
        }

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (se: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS permission revoked while posting ride request alert", se)
        }
    }

    fun playSampleRequestSoundAndVibrate(
        context: Context,
        soundEnabled: Boolean = true,
        vibrationEnabled: Boolean = true
    ) {
        if (isDeviceDoNotDisturbActive(context)) return
        if (soundEnabled) {
            runCatching {
                val ringtone = RingtoneManager.getRingtone(context.applicationContext, rideRequestSoundUri(context))
                ringtone?.play()
            }
        }
        if (vibrationEnabled) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                    manager?.defaultVibrator?.vibrate(
                        android.os.VibrationEffect.createWaveform(RIDE_REQUEST_VIBRATION_PATTERN, -1)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(android.os.VibrationEffect.createWaveform(RIDE_REQUEST_VIBRATION_PATTERN, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(RIDE_REQUEST_VIBRATION_PATTERN, -1)
                    }
                }
            }
        }
    }

    fun displaySampleRideRequestAlert(
        context: Context,
        rideId: String,
        title: String,
        body: String,
        soundEnabled: Boolean = true,
        vibrationEnabled: Boolean = true,
        notificationsEnabled: Boolean = true,
        payload: RideRequestPayload? = null
    ) {
        if (rideId.isBlank() || !notificationsEnabled) return
        val nowMs = System.currentTimeMillis()
        if (payload != null && payload.requestExpiresAtMs > 0L && nowMs > payload.requestExpiresAtMs) {
            return
        }
        val safeTitle = NotificationPrivacySanitizer.sanitizeText(title)
        val safeBody = NotificationPrivacySanitizer.sanitizeText(body)
        if (safeTitle.isBlank() && safeBody.isBlank()) return

        val dedupeKey = "sample_ride_alert|${rideId.trim()}"
        if (!shouldDisplayNotification(dedupeKey)) return

        ensureChannels(context)
        if (!hasNotificationPermission(context)) return

        val effectivePayload = payload ?: RideRequestPayload(
            bookingId = rideId.trim(),
            title = safeTitle,
            body = safeBody,
            requestExpiresAtMs = nowMs + RIDE_REQUEST_TIMEOUT_MS,
            isTestRide = false,
            isSampleRide = true
        )
        val remainingTimeoutMs = if (effectivePayload.requestExpiresAtMs > 0L) {
            (effectivePayload.requestExpiresAtMs - nowMs).coerceIn(1_000L, RIDE_REQUEST_TIMEOUT_MS)
        } else {
            RIDE_REQUEST_TIMEOUT_MS
        }

        val notifId = rideRequestNotificationId("sample|$rideId")
        val contentIntent = Intent(context, RiderActivity::class.java).apply {
            action = NotificationDeepLinkExtras.ACTION_OPEN_RIDER
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NotificationDeepLinkExtras.EXTRA_TARGET_SCREEN, "requests")
            putExtra(NotificationDeepLinkExtras.EXTRA_BOOKING_ID, rideId)
            putExtra(NotificationDeepLinkExtras.EXTRA_NOTIFICATION_TYPE, "sample_request")
            putExtra(NotificationDeepLinkExtras.EXTRA_TARGET_APP, "rider")
            putExtra(NotificationDeepLinkExtras.EXTRA_IS_SAMPLE_RIDE, true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fullScreenIntent = Intent(context, RideRequestActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            effectivePayload.writeToIntent(this)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            notifId xor 0x3311,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val acceptPendingIntent = buildRideRequestActionPendingIntent(
            context = context,
            action = RideRequestActionReceiver.ACTION_ACCEPT_RIDE,
            bookingId = rideId,
            isTestRide = false,
            isSampleRide = true,
            requestCode = notifId xor 0x11
        )
        val declinePendingIntent = buildRideRequestActionPendingIntent(
            context = context,
            action = RideRequestActionReceiver.ACTION_DECLINE_RIDE,
            bookingId = rideId,
            isTestRide = false,
            isSampleRide = true,
            requestCode = notifId xor 0x22
        )

        val loopingSoundStarted = RideRequestAlertController.startLoopingAlert(
            context = context,
            bookingId = rideId,
            expiresAtMs = effectivePayload.requestExpiresAtMs,
            soundEnabled = soundEnabled,
            vibrationEnabled = vibrationEnabled
        )

        val dndActive = isDeviceDoNotDisturbActive(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_SAMPLE_RIDE_REQUESTS_V2)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(safeTitle.ifBlank { context.getString(R.string.app_name_rider) })
            .setContentText(safeBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(remainingTimeoutMs)
            .setContentIntent(contentPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(R.drawable.ic_notification, "Accept Ride", acceptPendingIntent)
            .addAction(R.drawable.ic_notification, "Decline", declinePendingIntent)

        if (dndActive || loopingSoundStarted || (!soundEnabled && !vibrationEnabled)) {
            builder.setSilent(true)
        } else {
            if (soundEnabled) builder.setSound(rideRequestSoundUri(context)) else builder.setSound(null)
            if (vibrationEnabled) builder.setVibrate(RIDE_REQUEST_VIBRATION_PATTERN) else builder.setVibrate(longArrayOf(0L))
        }
        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (se: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS permission revoked while posting sample ride request", se)
        }
    }

    fun cancelNotificationOnly(context: Context, bookingId: String) {
        val cleanId = bookingId.trim()
        if (cleanId.isBlank()) return
        runCatching {
            val nm = NotificationManagerCompat.from(context)
            nm.cancel(rideRequestNotificationId(cleanId))
            nm.cancel(rideRequestNotificationId("sample|$cleanId"))
        }
    }

    fun cancelRideRequestNotification(context: Context, bookingId: String) {
        val cleanId = bookingId.trim()
        if (cleanId.isBlank()) return
        RideRequestAlertController.stopMediaOnly(context, cleanId)
        cancelNotificationOnly(context, cleanId)
    }

    fun displayNotification(
        context: Context,
        title: String,
        body: String,
        type: String,
        targetApp: String = "customer",
        targetScreen: String = "",
        bookingId: String = "",
        dedupeKey: String = "",
        isTestRide: Boolean = false,
        isSampleRide: Boolean = false,
        soundEnabled: Boolean = true,
        vibrationEnabled: Boolean = true,
        notificationsEnabled: Boolean = true,
        rideRequestPayload: RideRequestPayload? = null
    ) {
        if (!notificationsEnabled) return
        val resolvedSample = isSampleRide || type.equals("sample_request", true) || type.startsWith("sample_", true)
        if (type.equals("sample_request", true)) {
            displaySampleRideRequestAlert(
                context = context,
                rideId = bookingId,
                title = title,
                body = body,
                soundEnabled = soundEnabled,
                vibrationEnabled = vibrationEnabled,
                notificationsEnabled = notificationsEnabled,
                payload = rideRequestPayload
            )
            return
        }
        if (type.equals("new_ride_request", true) || type.equals("test_request", true)) {
            displayRideRequestAlert(
                context = context,
                bookingId = bookingId,
                title = title,
                body = body,
                isTestRide = isTestRide || type.equals("test_request", true),
                soundEnabled = soundEnabled,
                vibrationEnabled = vibrationEnabled,
                notificationsEnabled = notificationsEnabled,
                payload = rideRequestPayload
            )
            return
        }

        val safeTitle = NotificationPrivacySanitizer.sanitizeText(title)
        val safeBody = NotificationPrivacySanitizer.sanitizeText(body)
        if (safeTitle.isBlank() && safeBody.isBlank()) return
        if (!shouldDisplayNotification(dedupeKey)) return
        ensureChannels(context)
        if (!hasNotificationPermission(context)) return

        val cleanVariant = normalizeVariant(targetApp)
        val targetClass = when (cleanVariant) {
            "rider" -> RiderActivity::class.java
            "admin" -> AdminActivity::class.java
            else -> MainActivity::class.java
        }
        val intent = Intent(context, targetClass).apply {
            action = clickActionForApp(cleanVariant)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NotificationDeepLinkExtras.EXTRA_TARGET_SCREEN, targetScreen)
            putExtra(NotificationDeepLinkExtras.EXTRA_BOOKING_ID, bookingId)
            putExtra(NotificationDeepLinkExtras.EXTRA_NOTIFICATION_TYPE, type)
            putExtra(NotificationDeepLinkExtras.EXTRA_TARGET_APP, cleanVariant)
            putExtra(NotificationDeepLinkExtras.EXTRA_IS_TEST_RIDE, isTestRide)
            putExtra(NotificationDeepLinkExtras.EXTRA_IS_SAMPLE_RIDE, resolvedSample)
        }
        val requestCode = "$cleanVariant|$bookingId|$type|$targetScreen|$isTestRide|$resolvedSample".hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = channelForType(type = type, isTestRide = isTestRide, isSampleRide = resolvedSample)
        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val dndActive = isDeviceDoNotDisturbActive(context)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(safeTitle.ifBlank { context.getString(R.string.app_name) })
            .setContentText(safeBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
            .setPriority(
                if (channelId == CHANNEL_GENERAL) NotificationCompat.PRIORITY_DEFAULT
                else NotificationCompat.PRIORITY_HIGH
            )
            .setCategory(
                when (channelId) {
                    CHANNEL_SOS -> NotificationCompat.CATEGORY_ALARM
                    CHANNEL_CHAT -> NotificationCompat.CATEGORY_MESSAGE
                    else -> NotificationCompat.CATEGORY_STATUS
                }
            )
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (dndActive || (!soundEnabled && !vibrationEnabled)) {
            builder.setSilent(true)
        } else {
            if (soundEnabled) builder.setSound(defaultSound) else builder.setSound(null)
            if (vibrationEnabled) builder.setDefaults(NotificationCompat.DEFAULT_LIGHTS or NotificationCompat.DEFAULT_VIBRATE)
            else builder.setDefaults(NotificationCompat.DEFAULT_LIGHTS)
        }

        try {
            NotificationManagerCompat.from(context).notify(requestCode, builder.build())
        } catch (se: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS permission revoked while posting notification", se)
        }
    }

    suspend fun registerCurrentFcmToken(
        context: Context,
        userId: String,
        appVariant: String
    ) {
        val cleanUid = userId.trim()
        if (cleanUid.isBlank()) {
            Log.w(TAG, "Skipping FCM token registration: userId is blank")
            return
        }
        val validVariant = validateAppVariant(appVariant)
        if (validVariant == null) {
            Log.w(TAG, "Skipping FCM token registration: invalid appVariant='$appVariant'")
            return
        }
        saveUserPortalVariant(context, cleanUid, validVariant)
        if (FirebaseApp.getApps(context).isEmpty()) return
        ensureChannels(context)
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            if (token.isNotBlank()) {
                saveTokenToFirestore(context, cleanUid, token, validVariant)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to fetch or sync FCM token right now", e)
        }
    }

    suspend fun saveTokenToFirestore(
        context: Context,
        userId: String,
        token: String,
        appVariant: String
    ) {
        val cleanUid = userId.trim()
        val validVariant = validateAppVariant(appVariant)
        if (cleanUid.isBlank() || token.isBlank() || validVariant == null) {
            Log.w(
                TAG,
                "Skipping FCM token Firestore write: uidBlank=${cleanUid.isBlank()}, tokenBlank=${token.isBlank()}, appVariant='$appVariant'"
            )
            return
        }
        saveUserPortalVariant(context, cleanUid, validVariant)
        if (FirebaseApp.getApps(context).isEmpty()) return
        try {
            val deviceId = getOrCreateDeviceId(context)
            val docId = tokenDocId(cleanUid, validVariant, deviceId) ?: return
            val dbId = context.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(dbId)
            db.collection("user_tokens").document(docId)
                .set(
                    mapOf(
                        "userId" to cleanUid,
                        "deviceId" to deviceId,
                        "token" to token,
                        "platform" to "android",
                        "appVariant" to validVariant,
                        "active" to true,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving FCM token to Firestore", e)
        }
    }

    suspend fun removeCurrentUserTokensOnSignOut(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) return
        val uid = Firebase.auth.currentUser?.uid?.trim().orEmpty()
        if (uid.isBlank()) {
            Log.i(TAG, "Skipping FCM token deactivation on sign out: no authenticated Firebase user")
            return
        }
        try {
            val dbId = context.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(dbId)
            val deviceId = getOrCreateDeviceId(context)
            val savedVariant = getSavedUserPortalVariant(context, uid)
            val variantsToDeactivate = buildSet {
                if (savedVariant != null) add(savedVariant)
                addAll(ALLOWED_APP_VARIANTS)
            }
            variantsToDeactivate.forEach { variant ->
                listOf(deviceId, "").forEach { dev ->
                    val docId = tokenDocId(uid, variant, dev) ?: return@forEach
                    runCatching {
                        db.collection("user_tokens").document(docId)
                            .update(
                                mapOf(
                                    "active" to false,
                                    "token" to "",
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            )
                            .await()
                    }
                }
            }
            clearSavedUserPortalVariant(context, uid)
        } catch (e: Exception) {
            Log.w(TAG, "Soft failure cleaning up FCM token on sign out", e)
        }
    }
}
