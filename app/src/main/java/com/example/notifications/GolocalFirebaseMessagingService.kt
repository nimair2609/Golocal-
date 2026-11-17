package com.example.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

class GolocalFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationChannelHelper.ensureChannels(this)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token refreshed")
        if (FirebaseApp.getApps(applicationContext).isEmpty()) return
        val uid = Firebase.auth.currentUser?.uid ?: return
        serviceScope.launch {
            FcmTokenSyncManager.persistToken(
                context = applicationContext,
                uid = uid,
                role = FcmTokenSyncManager.readLastActiveRole(applicationContext),
                token = token
            )
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        NotificationChannelHelper.ensureChannels(this)

        val data = message.data
        val eventId = data["eventId"] ?: message.messageId
        if (!NotificationChannelHelper.shouldDisplayEvent(this, eventId)) {
            Log.d(TAG, "Suppressed duplicate FCM notification: $eventId")
            return
        }

        val title = message.notification?.title ?: data["title"] ?: "GoLocal Update"
        val body = message.notification?.body ?: data["body"] ?: return
        val targetRole = data["targetRole"] ?: "customer"
        val targetScreen = data["targetScreen"] ?: "notifications"
        val entityId = data["entityId"].orEmpty()
        val type = data["type"] ?: "booking"

        val channelId = NotificationChannelHelper.resolveChannelId(type, targetRole)
        val notifId = (eventId ?: "$type:$entityId:${System.currentTimeMillis()}").hashCode()
        val pendingIntent = NotificationChannelHelper.buildDeepLinkPendingIntent(
            context = this,
            targetRole = targetRole,
            targetScreen = targetScreen,
            entityId = entityId,
            requestCode = notifId
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(notifId, notification)
    }

    private companion object {
        const val TAG = "GolocalFcmService"
    }
}

object FcmTokenSyncManager {
    private const val PREF_FCM = "golocal_fcm_meta"
    private const val KEY_ROLE = "last_role"
    private const val KEY_TOKEN = "last_token"

    fun readLastActiveRole(context: Context): String =
        context.getSharedPreferences(PREF_FCM, Context.MODE_PRIVATE)
            .getString(KEY_ROLE, "customer") ?: "customer"

    suspend fun syncCurrentToken(context: Context, uid: String, role: String) {
        if (uid.isBlank() || FirebaseApp.getApps(context).isEmpty()) return
        NotificationChannelHelper.ensureChannels(context)
        context.getSharedPreferences(PREF_FCM, Context.MODE_PRIVATE)
            .edit().putString(KEY_ROLE, role).apply()

        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            if (token.isNotBlank()) {
                persistToken(context, uid, role, token)
            }
        }.onFailure { err ->
            Log.w("GolocalFcmSync", "Unable to fetch/sync FCM token", err)
        }
    }

    suspend fun persistToken(context: Context, uid: String, role: String, token: String) {
        if (uid.isBlank() || token.isBlank()) return
        val app = context.applicationContext
        app.getSharedPreferences(PREF_FCM, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).putString(KEY_ROLE, role).apply()

        runCatching {
            val dbId = app.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(dbId)
            val docId = "${uid}_${tokenDocSuffix(token)}"
            val payload = mapOf(
                "userId" to uid,
                "role" to role,
                "token" to token,
                "platform" to "android",
                "appPackage" to app.packageName,
                "active" to true,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("fcm_tokens").document(docId)
                .set(payload, SetOptions.merge())
                .await()
        }.onFailure { err ->
            Log.w("GolocalFcmSync", "Failed to store FCM token in Firestore", err)
        }
    }

    suspend fun removeCurrentToken(context: Context, uid: String) {
        val token = context.getSharedPreferences(PREF_FCM, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null).orEmpty()
        if (uid.isBlank() || token.isBlank() || FirebaseApp.getApps(context).isEmpty()) return
        runCatching {
            val dbId = context.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(dbId)
            val docId = "${uid}_${tokenDocSuffix(token)}"
            db.collection("fcm_tokens").document(docId).delete().await()
        }
    }

    internal fun tokenDocSuffix(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}

@Composable
fun NotificationPermissionBannerCard(
    modifier: Modifier = Modifier
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var dismissed by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) dismissed = true
    }

    if (hasPermission || dismissed) return

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .testTag("notification_permission_banner")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.NotificationsActive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Enable Ride & Safety Alerts", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Allow push notifications for ride status, driver arrival, KYC updates & SOS alerts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("enable_notifications_button")
            ) {
                Text("Allow")
            }
        }
    }
}
