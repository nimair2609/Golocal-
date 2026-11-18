package com.example.notifications

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GolocalFirebaseMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "Refreshed FCM registration token received")
        if (FirebaseApp.getApps(applicationContext).isEmpty()) {
            Log.w(TAG, "Skipping refreshed FCM token: FirebaseApp is not initialized")
            return
        }
        val currentUid = Firebase.auth.currentUser?.uid
        val target = NotificationHelper.resolveRefreshedTokenTarget(
            context = applicationContext,
            currentUid = currentUid,
            token = token
        ) ?: return

        serviceScope.launch {
            NotificationHelper.saveTokenToFirestore(
                context = applicationContext,
                userId = target.first,
                token = token,
                appVariant = target.second
            )
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val title = message.notification?.title ?: data["title"].orEmpty()
        val body = message.notification?.body ?: data["body"].orEmpty()
        val type = data["type"] ?: "general"
        val targetApp = data["targetApp"] ?: data["target_app"] ?: data["targetRole"] ?: "customer"
        val targetScreen = data["targetScreen"] ?: data["target_screen"] ?: ""
        val bookingId = data["bookingId"] ?: data["booking_id"] ?: data["entityId"] ?: data["entity_id"] ?: ""
        val isTestRide = data["isTestRide"].equals("true", ignoreCase = true) ||
            data["is_test_ride"].equals("true", ignoreCase = true) ||
            type.startsWith("test_", ignoreCase = true)
        val dedupeKey = data["dedupeKey"]
            ?: data["eventId"]
            ?: data["messageId"]
            ?: message.messageId
            ?: "$targetApp|$type|$bookingId|$title|$body"

        NotificationHelper.displayNotification(
            context = applicationContext,
            title = title,
            body = body,
            type = type,
            targetApp = targetApp,
            targetScreen = targetScreen,
            bookingId = bookingId,
            dedupeKey = dedupeKey,
            isTestRide = isTestRide
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "GoLocalFcmService"
    }
}
