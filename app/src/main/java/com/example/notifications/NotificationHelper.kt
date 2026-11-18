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

    const val ACTION_OPEN_CUSTOMER = "com.aistudio.golocal.OPEN_CUSTOMER"
    const val ACTION_OPEN_RIDER = "com.aistudio.golocal.OPEN_RIDER"
    const val ACTION_OPEN_ADMIN = "com.aistudio.golocal.OPEN_ADMIN"
}

data class NotificationDeepLinkTarget(
    val targetScreen: String = "",
    val bookingId: String = "",
    val type: String = "",
    val targetApp: String = "",
    val isTestRide: Boolean = false
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

            val isTestRide = intent.getBooleanExtra(NotificationDeepLinkExtras.EXTRA_IS_TEST_RIDE, false) ||
                str("isTestRide", "is_test_ride").equals("true", ignoreCase = true) ||
                type.startsWith("test_", ignoreCase = true)

            return if (screen.isNotBlank() || bookingId.isNotBlank() || type.isNotBlank()) {
                NotificationDeepLinkTarget(
                    targetScreen = screen,
                    bookingId = bookingId,
                    type = type,
                    targetApp = targetApp,
                    isTestRide = isTestRide
                )
            } else {
                null
            }
        }

        fun fromAppNotification(notif: AppNotification): NotificationDeepLinkTarget {
            return NotificationDeepLinkTarget(
                targetScreen = notif.targetScreen,
                bookingId = notif.bookingId.ifBlank { notif.entityId },
                type = notif.type,
                targetApp = notif.targetApp,
                isTestRide = notif.isTestNotification()
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
    const val CHANNEL_CHAT = "golocal_chat"
    const val CHANNEL_SOS = "golocal_sos"
    const val CHANNEL_TEST_RIDES = "golocal_test_rides"
    const val CHANNEL_GENERAL = "golocal_general"

    private const val PREFS_FCM_META = "golocal_fcm"
    private const val KEY_APP_VARIANT_PREFIX = "app_variant_"
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

    fun tokenDocId(userId: String, appVariant: String): String? {
        val cleanUid = userId.trim().replace("/", "_")
        val validVariant = validateAppVariant(appVariant) ?: return null
        if (cleanUid.isBlank()) return null
        return "${cleanUid}_${validVariant}".take(128)
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

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: defaultSound
        val notifAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
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
                description = "Ride requests, driver assignment, arrival alerts, bus bookings, and trip updates"
                setSound(defaultSound, notifAudio)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 220, 120, 220)
                enableLights(true)
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

    fun channelForType(type: String): String = channelForType(type = type, isTestRide = false)

    fun channelForType(type: String, isTestRide: Boolean): String = when {
        isTestRide || type.startsWith("test_", ignoreCase = true) -> CHANNEL_TEST_RIDES
        type.equals("chat", true) || type.equals("booking_chat", true) -> CHANNEL_CHAT
        type.equals("sos", true) ||
            type.equals("emergency", true) ||
            type.equals("sos_alert", true) ||
            type.equals("dispute_opened", true) -> CHANNEL_SOS
        type.startsWith("booking", true) ||
            type.startsWith("driver_booking", true) ||
            type.equals("new_ride_request", true) ||
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

    fun displayNotification(
        context: Context,
        title: String,
        body: String,
        type: String,
        targetApp: String = "customer",
        targetScreen: String = "",
        bookingId: String = "",
        dedupeKey: String = "",
        isTestRide: Boolean = false
    ) {
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
        }
        val requestCode = "$cleanVariant|$bookingId|$type|$targetScreen|$isTestRide".hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = channelForType(type = type, isTestRide = isTestRide)
        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(safeTitle.ifBlank { context.getString(R.string.app_name) })
            .setContentText(safeBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
            .setSound(defaultSound)
            .setDefaults(NotificationCompat.DEFAULT_LIGHTS or NotificationCompat.DEFAULT_VIBRATE)
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
            .build()

        try {
            NotificationManagerCompat.from(context).notify(requestCode, notification)
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
            val docId = tokenDocId(cleanUid, validVariant) ?: return
            val dbId = context.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(dbId)
            db.collection("user_tokens").document(docId)
                .set(
                    mapOf(
                        "userId" to cleanUid,
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
            val savedVariant = getSavedUserPortalVariant(context, uid)
            val variantsToDeactivate = buildSet {
                if (savedVariant != null) add(savedVariant)
                addAll(ALLOWED_APP_VARIANTS)
            }
            variantsToDeactivate.forEach { variant ->
                val docId = tokenDocId(uid, variant) ?: return@forEach
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
            clearSavedUserPortalVariant(context, uid)
        } catch (e: Exception) {
            Log.w(TAG, "Soft failure cleaning up FCM token on sign out", e)
        }
    }
}
