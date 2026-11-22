package com.example.notifications

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ElectricRickshaw
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.R
import com.example.data.repository.GolocalRepository
import com.example.data.repository.SampleRiderRepository
import com.example.domain.FareCalculator
import com.example.rider.RiderActivity
import com.example.security.AppCheckHelper
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalLightBlue
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalPureWhite
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalTheme
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.JetBrainsMono
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

class RideRequestActivity : ComponentActivity() {
    private var payloadState by mutableStateOf<RideRequestPayload?>(null)
    private var bookingListener: ListenerRegistration? = null

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val targetId = intent?.getStringExtra(NotificationDeepLinkExtras.EXTRA_BOOKING_ID).orEmpty().trim()
            val activeId = payloadState?.bookingId.orEmpty().trim()
            if (targetId.isBlank() || activeId.isBlank() || targetId == activeId) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCheckHelper.configureAppCheck(this, intent)
        configureLockScreenBehavior()
        enableEdgeToEdge()

        val initial = RideRequestPayload.fromIntent(intent)
        if (initial == null || ((initial.isTestRide || initial.isSampleRide) && !BuildConfig.ENABLE_TEST_SANDBOX)) {
            finish()
            return
        }
        payloadState = initial
        ContextCompat.registerReceiver(
            this,
            dismissReceiver,
            IntentFilter(RideRequestAlertController.ACTION_DISMISS_RIDE_REQUEST_UI),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        attachBookingGuard(initial)

        setContent {
            val activePayload = payloadState
            if (activePayload != null) {
                GolocalTheme(darkTheme = true) {
                    FullScreenIncomingRideContent(
                        payload = activePayload,
                        onAccept = { onResultMessage ->
                            acceptIncomingRide(activePayload, onResultMessage)
                        },
                        onDecline = {
                            declineIncomingRide(activePayload)
                        },
                        onExpire = {
                            RideRequestAlertController.stopAlert(this, activePayload.bookingId)
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val updated = RideRequestPayload.fromIntent(intent) ?: return
        payloadState = updated
        attachBookingGuard(updated)
    }

    override fun onDestroy() {
        bookingListener?.remove()
        bookingListener = null
        runCatching { unregisterReceiver(dismissReceiver) }
        super.onDestroy()
    }

    private fun configureLockScreenBehavior() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun attachBookingGuard(payload: RideRequestPayload) {
        bookingListener?.remove()
        bookingListener = null
        if (FirebaseApp.getApps(this).isEmpty()) return
        val db = runCatching {
            FirebaseFirestore.getInstance(getString(R.string.firestore_database_id))
        }.getOrNull() ?: return

        val docRef = if (payload.isSampleRide) {
            db.collection("sample_rides").document(payload.bookingId)
        } else {
            db.collection("bookings").document(payload.bookingId)
        }
        val expectedStatus = if (payload.isSampleRide) "incoming" else "searching"
        bookingListener = docRef.addSnapshotListener { snap, _ ->
            if (snap != null && snap.exists()) {
                val status = snap.getString("status").orEmpty()
                if (status.isNotBlank() && status != expectedStatus) {
                    RideRequestAlertController.stopAlert(this, payload.bookingId)
                    finish()
                }
            }
        }
    }

    private suspend fun acceptIncomingRide(
        payload: RideRequestPayload,
        onError: (String) -> Unit
    ) {
        RideRequestAlertController.stopAlert(this, payload.bookingId)
        if (FirebaseApp.getApps(this).isEmpty()) {
            onError("Firebase is unavailable right now.")
            return
        }
        val uid = Firebase.auth.currentUser?.uid.orEmpty()
        if (uid.isBlank()) {
            onError("Sign in as a verified rider to accept rides.")
            return
        }
        val db = FirebaseFirestore.getInstance(getString(R.string.firestore_database_id))
        val result: Result<Unit> = withContext(Dispatchers.IO) {
            val repo = GolocalRepository(db)
            when {
                payload.isSampleRide -> {
                    val sampleRepo = SampleRiderRepository(db)
                    val sampleProfile = sampleRepo.getSampleRiderOnce(uid)
                    val sampleRide = sampleRepo.getSampleRideOnce(payload.bookingId)
                    if (sampleProfile == null || sampleRide == null) {
                        Result.failure(IllegalStateException("Sample ride is no longer available."))
                    } else {
                        sampleRepo.acceptSampleRide(sampleRide, sampleProfile).map { }
                    }
                }
                payload.isTestRide -> {
                    val booking = repo.getBookingById(payload.bookingId)
                    val tr = repo.getTestRiderProfileOnce(uid)
                    if (booking == null || booking.status != "searching") {
                        Result.failure(IllegalStateException("Test ride is no longer available."))
                    } else if (tr == null || tr.status != "approved") {
                        Result.failure(IllegalStateException("Approved Test Rider profile required."))
                    } else {
                        repo.claimTestBookingByTestRider(booking = booking, riderProfile = tr)
                    }
                }
                else -> {
                    val booking = repo.getBookingById(payload.bookingId)
                    val profile = repo.getDriverProfileOnce(uid)
                    if (booking == null || booking.status != "searching") {
                        Result.failure(IllegalStateException("Ride was already accepted or expired."))
                    } else if (profile == null || profile.verificationStatus != "verified") {
                        Result.failure(IllegalStateException("Verified rider profile required."))
                    } else {
                        repo.acceptBookingAtomically(
                            booking = booking,
                            driver = profile,
                            fareInr = booking.estimatedFareInr
                        )
                    }
                }
            }
        }

        result.onSuccess {
            dismissKeyguardIfNeeded()
            val openIntent = Intent(this, RiderActivity::class.java).apply {
                action = NotificationDeepLinkExtras.ACTION_OPEN_RIDER
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(NotificationDeepLinkExtras.EXTRA_TARGET_SCREEN, "home")
                putExtra(NotificationDeepLinkExtras.EXTRA_BOOKING_ID, payload.bookingId)
                putExtra(NotificationDeepLinkExtras.EXTRA_TARGET_APP, "rider")
                putExtra(NotificationDeepLinkExtras.EXTRA_IS_TEST_RIDE, payload.isTestRide)
                putExtra(NotificationDeepLinkExtras.EXTRA_IS_SAMPLE_RIDE, payload.isSampleRide)
            }
            startActivity(openIntent)
            finish()
        }.onFailure { err ->
            onError(err.localizedMessage ?: "This ride is no longer available.")
        }
    }

    private fun declineIncomingRide(payload: RideRequestPayload) {
        RideRequestAlertController.stopAlert(this, payload.bookingId)
        val appContext = applicationContext
        if (FirebaseApp.getApps(appContext).isNotEmpty()) {
            val uid = Firebase.auth.currentUser?.uid.orEmpty()
            if (uid.isNotBlank()) {
                val db = FirebaseFirestore.getInstance(appContext.getString(R.string.firestore_database_id))
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    if (payload.isSampleRide) {
                        SampleRiderRepository(db).rejectSampleRide(payload.bookingId)
                    } else {
                        GolocalRepository(db).rejectOpenBooking(payload.bookingId, reason = "declined")
                    }
                }
            }
        }
        finish()
    }

    private fun dismissKeyguardIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        }
    }
}

@Composable
private fun FullScreenIncomingRideContent(
    payload: RideRequestPayload,
    onAccept: suspend ((String) -> Unit) -> Unit,
    onDecline: () -> Unit,
    onExpire: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val totalSec = remember(payload.bookingId, payload.requestExpiresAtMs) {
        ((payload.requestExpiresAtMs - System.currentTimeMillis()) / 1000L)
            .toInt()
            .coerceIn(1, 45)
    }
    var remainingSeconds by remember(payload.bookingId) { mutableIntStateOf(totalSec) }
    var isSubmitting by remember(payload.bookingId) { mutableStateOf(false) }
    var errorMessage by remember(payload.bookingId) { mutableStateOf<String?>(null) }

    BackHandler(enabled = !isSubmitting) {
        onDecline()
    }

    LaunchedEffect(payload.bookingId, payload.requestExpiresAtMs) {
        while (remainingSeconds > 0) {
            delay(1000L)
            val secsLeft = ((payload.requestExpiresAtMs - System.currentTimeMillis()) / 1000L).toInt()
            remainingSeconds = secsLeft.coerceAtLeast(0)
        }
        onExpire()
    }

    val ringProgress by animateFloatAsState(
        targetValue = (remainingSeconds.toFloat() / 45f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 350),
        label = "fullscreen_countdown_ring"
    )
    val estimatedNetInr = remember(payload.estimatedFareInr) {
        FareCalculator.estimateDriverNetEarningsInr(
            grossFareInr = payload.estimatedFareInr,
            gstPercent = 5.0,
            commissionPercent = 10.0
        ).roundToInt()
    }
    val vehicleIcon = when (payload.vehicleCategory.lowercase()) {
        "bike" -> Icons.Filled.TwoWheeler
        "toto", "auto" -> Icons.Filled.ElectricRickshaw
        "cab" -> Icons.Filled.LocalTaxi
        else -> Icons.Filled.DirectionsCar
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(GolocalDeepNavy, Color(0xFF0A1931), Color(0xFF102A43))
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .testTag("ride_request_fullscreen_root")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Status Header + Circular 45s Ring
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GolocalStatusBadge(
                        label = when {
                            payload.isSampleRide -> "SAMPLE RIDE REQUEST"
                            payload.isTestRide -> "TEST RIDE REQUEST"
                            payload.dispatchWave >= 2 -> "EXPANDED SEARCH • WAVE 2"
                            else -> "NEW RIDE REQUEST"
                        },
                        tone = if (payload.isTestRide || payload.isSampleRide) "warning" else "info"
                    )
                    Text(
                        text = "#${payload.bookingId.take(8).uppercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = JetBrainsMono,
                        color = GolocalPureWhite.copy(alpha = 0.78f)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    shape = CircleShape,
                    color = GolocalPureWhite,
                    shadowElevation = 14.dp,
                    modifier = Modifier
                        .size(104.dp)
                        .testTag("ride_request_fullscreen_timer")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(7.dp)
                        ) {
                            val stroke = 7.dp.toPx()
                            drawArc(
                                color = Color(0xFFE5ECF6),
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = if (remainingSeconds <= 10) GolocalError else GolocalSuccess,
                                startAngle = -90f,
                                sweepAngle = 360f * ringProgress,
                                useCenter = false,
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${remainingSeconds}s",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = JetBrainsMono,
                                color = if (remainingSeconds <= 10) GolocalError else GolocalDeepNavy
                            )
                            Text(
                                text = "LEFT",
                                style = MaterialTheme.typography.labelSmall,
                                color = GolocalDeepNavy.copy(alpha = 0.65f)
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = vehicleIcon,
                        contentDescription = payload.vehicleCategory,
                        tint = GolocalWarning,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "${payload.vehicleCategory.uppercase()} • ${payload.passengerFirstName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = GolocalPureWhite
                    )
                }

                Text(
                    text = "₹${payload.estimatedFareInr.roundToInt()}",
                    style = MaterialTheme.typography.displayMedium,
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.ExtraBold,
                    color = GolocalPureWhite
                )

                Text(
                    text = "${payload.paymentMethod.uppercase()} • Est. Net ₹$estimatedNetInr",
                    style = MaterialTheme.typography.labelLarge,
                    color = GolocalLightBlue
                )
            }

            // Route Details Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(GolocalSuccess)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "PICKUP",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = GolocalSuccess
                            )
                            Text(
                                text = payload.pickupLabel.ifBlank { payload.title.ifBlank { "Nearby Pickup" } },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(GolocalError)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            val tripMeta = if (payload.distanceKm > 0.0) {
                                String.format(
                                    Locale.US,
                                    "DROP • %.1f km (%d min)",
                                    payload.distanceKm,
                                    payload.estimatedDurationMin.coerceAtLeast(5L)
                                )
                            } else {
                                "DROP"
                            }
                            Text(
                                text = tripMeta,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = GolocalError
                            )
                            Text(
                                text = payload.destinationLabel.ifBlank { payload.body.ifBlank { "Drop Destination" } },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (!errorMessage.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errorMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }

            // Bottom Tactile Accept / Decline Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (isSubmitting) return@Button
                        isSubmitting = true
                        errorMessage = null
                        scope.launch {
                            onAccept { msg ->
                                errorMessage = msg
                                isSubmitting = false
                            }
                        }
                    },
                    enabled = !isSubmitting && remainingSeconds > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GolocalSuccess,
                        contentColor = GolocalPureWhite
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("ride_request_fullscreen_accept")
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = GolocalPureWhite
                        )
                    } else {
                        Icon(Icons.Filled.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (remainingSeconds > 0) "ACCEPT RIDE" else "REQUEST EXPIRED",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                OutlinedButton(
                    onClick = onDecline,
                    enabled = !isSubmitting,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalPureWhite),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("ride_request_fullscreen_decline")
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = GolocalError)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "DECLINE",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = GolocalPureWhite
                    )
                }
            }
        }
    }
}
