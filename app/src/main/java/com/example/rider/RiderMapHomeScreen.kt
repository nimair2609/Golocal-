package com.example.rider

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.Booking
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.DriverProfile
import com.example.data.model.RideOffer
import com.example.domain.DocumentExpiryAssessment
import com.example.map.GolocalMapContainer
import com.example.shared.LocationAccessState
import com.example.shared.LocationCoordinator
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalLightBlue
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalPureWhite
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.JetBrainsMono
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RiderMapHomeScreen(
    profile: DriverProfile,
    currentLat: Double,
    currentLng: Double,
    gpsIsDetecting: Boolean,
    locationLabel: String,
    openRequests: List<Booking>,
    myOffers: List<RideOffer>,
    todaysCompletedRides: Int,
    todaysEarningsInr: Double,
    expiryAlerts: List<DocumentExpiryAssessment>,
    lowNetworkMode: Boolean,
    onToggleOnline: (Boolean) -> Unit,
    onRequestLocationSync: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenRequests: () -> Unit,
    onTriggerSos: () -> Unit,
    onAcceptRequestWithFare: (booking: Booking, proposedFareInr: Double, etaMinutes: Long) -> Unit,
    onDeclineRequest: (bookingId: String) -> Unit,
    onWithdrawOffer: (offerId: String) -> Unit
) {
    val context = LocalContext.current
    val locationAccess = remember(currentLat, currentLng, gpsIsDetecting) {
        LocationCoordinator.resolveAccessState(context)
    }

    // Filter out requests where this driver already has an active pending proposal so queue rotates cleanly
    val pendingBookingIds = remember(myOffers) {
        myOffers.filter { it.status == "pending" }.map { it.bookingId }.toSet()
    }
    val candidateRequests = remember(openRequests, pendingBookingIds) {
        openRequests.filter { it.id !in pendingBookingIds }
    }
    var selectedIdx by remember(candidateRequests.size) { mutableIntStateOf(0) }
    val safeIdx = if (candidateRequests.isEmpty()) 0 else selectedIdx.coerceIn(0, candidateRequests.lastIndex)
    val incomingRequest = if (profile.isOnline) candidateRequests.getOrNull(safeIdx) else null

    val firstName = remember(profile.legalName) {
        profile.legalName.trim().substringBefore(" ").ifBlank { "Partner" }
    }

    val onlineSelfEntry = remember(profile, currentLat, currentLng) {
        if (!profile.isOnline) emptyList()
        else listOf(
            DriverDirectoryEntry(
                driverId = profile.driverId,
                displayName = profile.legalName,
                vehicleCategory = profile.vehicleCategory,
                isOnline = true,
                currentLat = currentLat,
                currentLng = currentLng
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-Screen Map Canvas
        GolocalMapContainer(
            pickupLat = incomingRequest?.pickupLat ?: currentLat,
            pickupLng = incomingRequest?.pickupLng ?: currentLng,
            pickupLabel = incomingRequest?.pickupLabel ?: locationLabel.ifBlank { "${profile.district} Zone" },
            destLat = incomingRequest?.destinationLat ?: (currentLat + 0.012),
            destLng = incomingRequest?.destinationLng ?: (currentLng + 0.012),
            destLabel = incomingRequest?.destinationLabel ?: "Nearby Service Area",
            onlineDrivers = onlineSelfEntry,
            liveTripLocation = null,
            lowNetworkMode = lowNetworkMode,
            fillContainer = true,
            cornerRadius = 0.dp,
            showBorder = false,
            showTopRouteChip = false,
            showRecenterFab = true,
            recenterBottomPadding = if (incomingRequest != null) 340.dp else 255.dp,
            userLat = currentLat,
            userLng = currentLng
        )

        // Compact Floating Top Header modeled on Reference Image 2
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 5.dp,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (profile.isOnline) GolocalSuccess else MaterialTheme.colorScheme.outline)
                        )
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = "Hi, $firstName",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${profile.vehicleCategory.uppercase()} • ${if (profile.isOnline) "Online" else "Offline"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (profile.isOnline) GolocalSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Switch(
                            checked = profile.isOnline,
                            onCheckedChange = onToggleOnline,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = GolocalSuccess,
                                checkedThumbColor = GolocalPureWhite
                            ),
                            modifier = Modifier.testTag("rider_online_switch")
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = onOpenNotifications,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 5.dp,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("rider_alerts_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Notifications,
                                contentDescription = "Partner alerts",
                                tint = GolocalDeepNavy
                            )
                        }
                    }
                    Surface(
                        onClick = onTriggerSos,
                        shape = CircleShape,
                        color = GolocalError,
                        shadowElevation = 5.dp,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("rider_home_sos_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "Emergency SOS",
                                tint = GolocalPureWhite
                            )
                        }
                    }
                }
            }

            if (locationAccess != LocationAccessState.GRANTED_READY) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Filled.GpsFixed, contentDescription = null, tint = GolocalWarning)
                        Text(
                            text = if (locationAccess == LocationAccessState.GPS_DISABLED) {
                                "Turn on GPS to broadcast live location"
                            } else {
                                "Allow Location permission for live pickup dispatch"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                if (locationAccess == LocationAccessState.GPS_DISABLED) {
                                    LocationCoordinator.openLocationSettings(context)
                                } else {
                                    onRequestLocationSync()
                                }
                            }
                        ) {
                            Text(if (locationAccess == LocationAccessState.GPS_DISABLED) "GPS Settings" else "Allow")
                        }
                    }
                }
            }

            if (expiryAlerts.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shadowElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Document notice: ${expiryAlerts.joinToString { "${it.docType.displayLabel} (${it.status.name.lowercase()})" }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = GolocalError,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Bottom Area: High-Contrast Incoming Ride Request Overlay OR Clean White Online/Offline Dock
        if (incomingRequest != null) {
            IncomingRideRequestOverlayCard(
                booking = incomingRequest,
                queueIndex = safeIdx,
                queueTotal = candidateRequests.size,
                riderLat = currentLat,
                riderLng = currentLng,
                onPreviousRequest = {
                    if (safeIdx > 0) selectedIdx = safeIdx - 1
                },
                onNextRequest = {
                    if (safeIdx < candidateRequests.lastIndex) selectedIdx = safeIdx + 1
                },
                onDecline = { onDeclineRequest(incomingRequest.id) },
                onAccept = { proposedFare, etaMins ->
                    onAcceptRequestWithFare(incomingRequest, proposedFare, etaMins)
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        } else {
            RiderIdleDashboardDock(
                profile = profile,
                todaysCompletedRides = todaysCompletedRides,
                todaysEarningsInr = todaysEarningsInr,
                openRequestsCount = openRequests.size,
                myOffers = myOffers,
                gpsIsDetecting = gpsIsDetecting,
                onToggleOnline = onToggleOnline,
                onOpenRequests = onOpenRequests,
                onSyncGps = onRequestLocationSync,
                onWithdrawOffer = onWithdrawOffer,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
fun IncomingRideRequestOverlayCard(
    booking: Booking,
    queueIndex: Int = 0,
    queueTotal: Int = 1,
    riderLat: Double = 22.9041,
    riderLng: Double = 88.3976,
    initialSeconds: Int = 25,
    onPreviousRequest: () -> Unit = {},
    onNextRequest: () -> Unit = {},
    onDecline: () -> Unit,
    onAccept: (proposedFareInr: Double, etaMinutes: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val minFare = booking.minPermittedFareInr.toFloat().coerceAtLeast(10f)
    val maxFare = booking.maxPermittedFareInr.toFloat().coerceAtLeast(minFare)
    var proposedFare by remember(booking.id) {
        mutableFloatStateOf(booking.estimatedFareInr.toFloat().coerceIn(minFare, maxFare))
    }
    var remainingSeconds by remember(booking.id) { mutableIntStateOf(initialSeconds.coerceAtLeast(1)) }
    val etaMins = remember(booking.id, riderLat, riderLng) {
        val approxKm = com.example.domain.FareCalculator.haversineDistanceKm(
            riderLat,
            riderLng,
            booking.pickupLat,
            booking.pickupLng
        )
        ((approxKm / 22.0) * 60.0).roundToInt().coerceIn(3, 35).toLong()
    }

    LaunchedEffect(booking.id) {
        remainingSeconds = initialSeconds.coerceAtLeast(1)
        while (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds -= 1
        }
    }

    val ringProgress by animateFloatAsState(
        targetValue = (remainingSeconds.toFloat() / initialSeconds.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 350),
        label = "request_ring"
    )
    val passengerFirstName = booking.customerName.trim().substringBefore(" ").ifBlank { "Passenger" }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("rider_incoming_request_card"),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = GolocalDeepNavy,
        contentColor = GolocalPureWhite,
        shadowElevation = 18.dp
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Queue header + Passenger Name + Payment Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = passengerFirstName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = GolocalPureWhite
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = GolocalPureWhite.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = GolocalWarning,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = booking.paymentMethod.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = GolocalPureWhite
                            )
                        }
                    }
                }

                if (queueTotal > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onPreviousRequest, enabled = queueIndex > 0) {
                            Text("Prev", color = GolocalPureWhite)
                        }
                        Text(
                            text = "${queueIndex + 1}/$queueTotal",
                            style = MaterialTheme.typography.labelMedium,
                            color = GolocalPureWhite.copy(alpha = 0.85f)
                        )
                        TextButton(onClick = onNextRequest, enabled = queueIndex < queueTotal - 1) {
                            Text("Next", color = GolocalPureWhite)
                        }
                    }
                } else {
                    GolocalStatusBadge(label = booking.vehicleCategory.uppercase(), tone = "info")
                }
            }

            // Circular Countdown Timer Ring modeled on Mockup 2
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .testTag("rider_request_timer"),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 6.dp.toPx()
                        drawArc(
                            color = GolocalPureWhite.copy(alpha = 0.18f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = if (remainingSeconds <= 5) GolocalError else GolocalPrimaryBlue,
                            startAngle = -90f,
                            sweepAngle = 360f * ringProgress,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        text = if (remainingSeconds > 0) "$remainingSeconds" else "0",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = GolocalPureWhite,
                        fontFamily = JetBrainsMono
                    )
                }
            }

            // Duration + Distance & Route Summary
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "${booking.estimatedDurationMin} mins  •  ${String.format(Locale.US, "%.2f", booking.distanceKm)} kms",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = GolocalPureWhite
                )
                Text(
                    text = "${booking.pickupLabel} → ${booking.destinationLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = GolocalPureWhite.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Fare & Regulated Proposal Adjuster
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = GolocalPureWhite.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { proposedFare = (proposedFare - 10f).coerceIn(minFare, maxFare) },
                            enabled = proposedFare > minFare,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Filled.Remove, contentDescription = "Decrease fare", tint = GolocalPureWhite)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "₹${proposedFare.roundToInt()}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = JetBrainsMono,
                                fontWeight = FontWeight.Bold,
                                color = GolocalPureWhite
                            )
                            Text(
                                text = "Estimated fare (Band ₹${minFare.toInt()}–₹${maxFare.toInt()})",
                                style = MaterialTheme.typography.labelSmall,
                                color = GolocalPureWhite.copy(alpha = 0.72f)
                            )
                        }
                        IconButton(
                            onClick = { proposedFare = (proposedFare + 10f).coerceIn(minFare, maxFare) },
                            enabled = proposedFare < maxFare,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Increase fare", tint = GolocalPureWhite)
                        }
                    }

                    if (maxFare > minFare + 1f) {
                        Slider(
                            value = proposedFare,
                            onValueChange = { proposedFare = it.coerceIn(minFare, maxFare) },
                            valueRange = minFare..maxFare,
                            colors = SliderDefaults.colors(
                                thumbColor = GolocalPureWhite,
                                activeTrackColor = GolocalPrimaryBlue,
                                inactiveTrackColor = GolocalPureWhite.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }

            // Decline / Accept Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalPureWhite),
                    border = androidx.compose.foundation.BorderStroke(1.2.dp, GolocalPureWhite.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp)
                        .testTag("rider_decline_request_button")
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Decline", fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = { onAccept(proposedFare.roundToInt().toDouble(), etaMins) },
                    enabled = remainingSeconds > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GolocalPureWhite,
                        contentColor = GolocalDeepNavy
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp)
                        .testTag("rider_accept_request_button")
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (remainingSeconds > 0) "Accept" else "Timed Out",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun RiderIdleDashboardDock(
    profile: DriverProfile,
    todaysCompletedRides: Int,
    todaysEarningsInr: Double,
    openRequestsCount: Int,
    myOffers: List<RideOffer>,
    gpsIsDetecting: Boolean,
    onToggleOnline: (Boolean) -> Unit,
    onOpenRequests: () -> Unit,
    onSyncGps: () -> Unit,
    onWithdrawOffer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activePendingOffers = remember(myOffers) { myOffers.filter { it.status == "pending" } }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Drag Indicator
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (profile.isOnline) "You're Online" else "You're Offline",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (profile.isOnline) {
                            if (openRequestsCount > 0) "$openRequestsCount local request(s) ready"
                            else "Listening for nearby rides in ${profile.district}"
                        } else {
                            "Go online to receive passenger requests"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onSyncGps) {
                    Text(if (gpsIsDetecting) "Syncing..." else "Sync GPS")
                }
            }

            // 3 Real Metrics Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RiderStatChip(
                    label = "Today Rides",
                    value = todaysCompletedRides.toString(),
                    modifier = Modifier.weight(1f)
                )
                RiderStatChip(
                    label = "Net Today",
                    value = "₹${todaysEarningsInr.toInt()}",
                    modifier = Modifier.weight(1f)
                )
                RiderStatChip(
                    label = "Rating",
                    value = String.format(Locale.US, "%.1f ★", profile.ratingAvg),
                    modifier = Modifier.weight(1f)
                )
            }

            if (activePendingOffers.isNotEmpty()) {
                val topOffer = activePendingOffers.first()
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = GolocalLightBlue,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Offer ₹${topOffer.proposedFareInr.toInt()} awaiting customer (${activePendingOffers.size} sent)",
                            style = MaterialTheme.typography.labelMedium,
                            color = GolocalDeepNavy,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onWithdrawOffer(topOffer.id) }) {
                            Text("Withdraw", color = GolocalError)
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (profile.isOnline && openRequestsCount > 0) {
                        onOpenRequests()
                    } else {
                        onToggleOnline(!profile.isOnline)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (profile.isOnline) GolocalSuccess else GolocalPrimaryBlue
                ),
                contentPadding = PaddingValues(vertical = 14.dp),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("rider_primary_online_cta")
            ) {
                Text(
                    text = when {
                        profile.isOnline && openRequestsCount > 0 -> "View $openRequestsCount Request(s)"
                        profile.isOnline -> "Go Offline"
                        else -> "Go Online"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun RiderStatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMono,
                color = GolocalPrimaryBlue
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
