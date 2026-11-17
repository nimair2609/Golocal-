package com.example.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.MainActivity
import com.example.R
import com.example.admin.AdminActivity
import com.example.rider.RiderActivity

object NotificationDeepLinks {
    const val EXTRA_TARGET_ROLE = "golocal_target_role"
    const val EXTRA_TARGET_SCREEN = "golocal_target_screen"
    const val EXTRA_ENTITY_ID = "golocal_entity_id"
}

object NotificationChannelHelper {
    const val CHANNEL_BOOKINGS = "golocal_bookings"
    const val CHANNEL_DISPATCH = "golocal_driver_dispatch"
    const val CHANNEL_SAFETY = "golocal_safety_alerts"

    private const val PREF_DEDUPE = "golocal_fcm_dedupe"
    private const val KEY_RECENT_IDS = "recent_event_ids"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val bookings = NotificationChannel(
            CHANNEL_BOOKINGS,
            context.getString(R.string.channel_bookings_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Ride confirmations, driver arrivals, OTP/PIN, refunds, and bus ticket alerts"
            enableVibration(true)
        }

        val dispatch = NotificationChannel(
            CHANNEL_DISPATCH,
            context.getString(R.string.channel_dispatch_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "New ride requests, cancellations, settlements, and document expiry reminders"
            enableVibration(true)
        }

        val safety = NotificationChannel(
            CHANNEL_SAFETY,
            context.getString(R.string.channel_safety_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "KYC reviews, SOS emergencies, disputes, and operations alerts"
            enableVibration(true)
        }

        manager.createNotificationChannels(listOf(bookings, dispatch, safety))
    }

    fun resolveChannelId(type: String?, targetRole: String?): String {
        val norm = type.orEmpty().lowercase()
        return when {
            norm.contains("sos") || norm.contains("fraud") || norm.contains("dispute") ||
                norm.contains("complaint") || targetRole == "admin" -> CHANNEL_SAFETY
            targetRole == "driver" || targetRole == "rider" || norm.contains("request") ||
                norm.contains("settlement") || norm.contains("expiry") -> CHANNEL_DISPATCH
            else -> CHANNEL_BOOKINGS
        }
    }

    fun buildDeepLinkPendingIntent(
        context: Context,
        targetRole: String?,
        targetScreen: String?,
        entityId: String?,
        requestCode: Int
    ): PendingIntent {
        val activityClass = when (targetRole?.lowercase()) {
            "driver", "rider" -> RiderActivity::class.java
            "admin" -> AdminActivity::class.java
            else -> MainActivity::class.java
        }
        val intent = Intent(context, activityClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationDeepLinks.EXTRA_TARGET_ROLE, targetRole.orEmpty())
            putExtra(NotificationDeepLinks.EXTRA_TARGET_SCREEN, targetScreen.orEmpty())
            putExtra(NotificationDeepLinks.EXTRA_ENTITY_ID, entityId.orEmpty())
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }

    @Synchronized
    fun shouldDisplayEvent(context: Context, eventId: String?): Boolean {
        val cleanId = eventId?.trim().orEmpty()
        if (cleanId.isBlank()) return true
        val prefs = context.getSharedPreferences(PREF_DEDUPE, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_RECENT_IDS, "")
            .orEmpty()
            .split('|')
            .filter { it.isNotBlank() }
        if (cleanId in existing) return false
        val updated = (existing + cleanId).takeLast(64).joinToString("|")
        prefs.edit().putString(KEY_RECENT_IDS, updated).apply()
        return true
    }
}
