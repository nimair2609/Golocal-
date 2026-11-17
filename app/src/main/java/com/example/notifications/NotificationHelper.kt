package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.admin.AdminActivity
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
}

data class NotificationDeepLinkTarget(
    val targetScreen: String = "",
    val bookingId: String = "",
    val type: String = ""
) {
    companion object {
        fun fromIntent(intent: Intent?): NotificationDeepLinkTarget? {
            if (intent == null) return null
            val screen = intent.getStringExtra(NotificationDeepLinkExtras.EXTRA_TARGET_SCREEN).orEmpty()
            val bookingId = intent.getStringExtra(NotificationDeepLinkExtras.EXTRA_BOOKING_ID).orEmpty()
            val type = intent.getStringExtra(NotificationDeepLinkExtras.EXTRA_NOTIFICATION_TYPE).orEmpty()
            return if (screen.isNotBlank() || bookingId.isNotBlank() || type.isNotBlank()) {
                NotificationDeepLinkTarget(screen, bookingId, type)
            } else {
                null
            }
        }
    }
}

object NotificationHelper {
    private const val TAG = "GoLocalFCM"
    const val CHANNEL_RIDES = "golocal_rides"
    const val CHANNEL_CHAT = "golocal_chat"
    const val CHANNEL_SOS = "golocal_sos"
    const val CHANNEL_GENERAL = "golocal_general"

    private val recentDedupeKeys: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 64
            }
        )
    )

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
        val channels = listOf(
            NotificationChannel(
                CHANNEL_RIDES,
                context.getString(R.string.notif_channel_rides),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Ride proposals, driver arrival, trip PIN, bus ticket confirmation, and status updates"
            },
            NotificationChannel(
                CHANNEL_CHAT,
                context.getString(R.string.notif_channel_chat),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Two-way trip messages between customer and driver"
            },
            NotificationChannel(
                CHANNEL_SOS,
                context.getString(R.string.notif_channel_sos),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Emergency SOS safety alerts and incident escalation"
            },
            NotificationChannel(
                CHANNEL_GENERAL,
                context.getString(R.string.notif_channel_general),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "KYC document review, support updates, and governance notices"
            }
        )
        channels.forEach { manager.createNotificationChannel(it) }
    }

    fun channelForType(type: String): String = when (type.lowercase()) {
        "chat", "booking_chat" -> CHANNEL_CHAT
        "sos", "emergency" -> CHANNEL_SOS
        "booking", "ride_offer", "driver_assigned", "trip_status", "bus_booking" -> CHANNEL_RIDES
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
        dedupeKey: String = ""
    ) {
        if (title.isBlank() && body.isBlank()) return
        if (!shouldDisplayNotification(dedupeKey)) return
        ensureChannels(context)
        if (!hasNotificationPermission(context)) return

        val targetClass = when (targetApp.lowercase()) {
            "rider", "driver" -> RiderActivity::class.java
            "admin" -> AdminActivity::class.java
            else -> MainActivity::class.java
        }
        val intent = Intent(context, targetClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NotificationDeepLinkExtras.EXTRA_TARGET_SCREEN, targetScreen)
            putExtra(NotificationDeepLinkExtras.EXTRA_BOOKING_ID, bookingId)
            putExtra(NotificationDeepLinkExtras.EXTRA_NOTIFICATION_TYPE, type)
        }
        val requestCode = (bookingId + "|" + type + "|" + targetScreen).hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = channelForType(type)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(
                if (channelId == CHANNEL_GENERAL) NotificationCompat.PRIORITY_DEFAULT
                else NotificationCompat.PRIORITY_HIGH
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
        if (userId.isBlank() || FirebaseApp.getApps(context).isEmpty()) return
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            if (token.isNotBlank()) {
                saveTokenToFirestore(context, userId, token, appVariant)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to fetch or sync FCM token right now", e)
        }
    }

    suspend fun saveTokenToFirestore(
        context: Context,
        userId: String,
        token: String,
        appVariant: String = "customer"
    ) {
        if (userId.isBlank() || token.isBlank() || FirebaseApp.getApps(context).isEmpty()) return
        try {
            val dbId = context.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(dbId)
            val docId = "${userId}_${appVariant.lowercase()}"
            db.collection("user_tokens").document(docId)
                .set(
                    mapOf(
                        "userId" to userId,
                        "token" to token,
                        "platform" to "android",
                        "appVariant" to appVariant.lowercase(),
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
        val uid = Firebase.auth.currentUser?.uid ?: return
        try {
            val dbId = context.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(dbId)
            listOf("customer", "rider", "admin").forEach { variant ->
                runCatching {
                    db.collection("user_tokens").document("${uid}_$variant")
                        .update(
                            mapOf(
                                "active" to false,
                                "token" to "",
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Soft failure cleaning up FCM token on sign out", e)
        }
    }
}
