package com.example.customer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.Booking
import com.example.data.model.BookingCallContact
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.RideOffer
import com.example.data.model.RideOtpSecret
import com.example.data.model.TripLocation
import com.example.domain.FareCalculator
import com.example.domain.TestRideOtpUtil
import com.example.map.GeoPoint
import com.example.map.GolocalMapContainer
import com.example.map.GpsBroadcastThrottle
import com.example.map.RouteNavigationMath
import com.example.shared.BookingCommunicationBar
import com.example.shared.CircularBackButton
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalAuthNavy
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalGoldStar
import com.example.ui.theme.GolocalLightBlue
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalRoyalBlueBright
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.GolocalWarningContainer
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.rememberPrefersReducedMotion
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun CustomerActiveRideView(
    booking: Booking,
    offers: List<RideOffer>,
    drivers: List<DriverDirectoryEntry>,
    tripLocation: TripLocation?,
    lowNetwork: Boolean,
    callContact: BookingCallContact?,
    unreadChatCount: Int,
    onBackHome: () -> Unit,
    onOpenCall: () -> Unit,
    onOpenChat: () -> Unit,
    onAcceptOffer: (RideOffer) -> Unit,
    onShareTrip: () -> Unit,
    onOpenSos: () -> Unit,
    onOpenComplaint: () -> Unit,
    onOpenCancel: () -> Unit,
    isVerifyingTestOtp: Boolean = false,
    testOtpError: String? = null,
    rideOtpSecret: RideOtpSecret? = null,
    onVerifyTestOtp: (String) -> Unit = {},
    onClearTestOtpError: () -> Unit = {},
    onSearchTimeout: (String) -> Unit = {}
) {
    val isTestRide = booking.isTestRide || booking.testMode
    var declinedOfferIds by remember(booking.id) { mutableStateOf<Set<String>>(emptySet()) }
    val visibleOffers = remember(offers, declinedOfferIds) {
        offers.filter { it.id !in declinedOfferIds }
    }

    if (booking.status == "searching") {
        // Radar Search + Incoming Offer Cards Modeled on Image 1 Panel 3
        Box(modifier = Modifier.fillMaxSize()) {
            GolocalMapContainer(
                pickupLat = booking.pickupLat,
                pickupLng = booking.pickupLng,
                pickupLabel = booking.pickupLabel,
                destLat = booking.destinationLat,
                destLng = booking.destinationLng,
                destLabel = booking.destinationLabel,
                onlineDrivers = drivers,
                liveTripLocation = null,
                lowNetworkMode = lowNetwork,
                fillContainer = true,
                cornerRadius = 0.dp,
                showBorder = false,
                showTopRouteChip = false,
                recenterBottomPadding = 228.dp
            )

            // Subtle concentric radar pulse animation (no fake driver cars)
            RadarPulseOverlay(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 110.dp)
            )

            // Non-overlapping Top Offers + Bottom Locating Sheet stack
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Floating Navigation + Incoming Fare Proposal Cards
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularBackButton(onClick = onBackHome, testTag = "searching_back_button")
                        Surface(
                            onClick = onOpenCancel,
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 4.dp,
                            modifier = Modifier.testTag("searching_cancel_pill")
                        ) {
                            Text(
                                text = "Cancel Ride",
                                style = MaterialTheme.typography.labelLarge,
                                color = GolocalError,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }

                    if (visibleOffers.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(visibleOffers, key = { it.id }) { offer ->
                                IncomingOfferCard(
                                    offer = offer,
                                    onDecline = { declinedOfferIds = declinedOfferIds + offer.id },
                                    onAccept = { onAcceptOffer(offer) }
                                )
                            }
                        }
                    }
                }

                // Bottom Locating Sheet
                val searchExpiresAtMs = remember(booking.id, booking.requestExpiresAtMs) {
                    booking.requestExpiresAtMs.takeIf { it > 0L }
                        ?: (System.currentTimeMillis() + com.example.data.repository.GolocalRepository.RIDE_REQUEST_TIMEOUT_MS)
                }
                var searchRemainingSeconds by remember(booking.id, searchExpiresAtMs) {
                    mutableIntStateOf(
                        ((searchExpiresAtMs - System.currentTimeMillis()) / 1000L)
                            .toInt()
                            .coerceIn(0, 45)
                    )
                }
                var expiryTriggered by remember(booking.id) { mutableStateOf(false) }
                LaunchedEffect(booking.id, searchExpiresAtMs) {
                    while (true) {
                        val secs = ((searchExpiresAtMs - System.currentTimeMillis()) / 1000L)
                            .toInt()
                            .coerceAtLeast(0)
                        searchRemainingSeconds = secs
                        if (secs <= 0) {
                            if (!expiryTriggered && !isTestRide) {
                                expiryTriggered = true
                                onSearchTimeout(booking.id)
                            }
                            break
                        }
                        delay(1000L)
                    }
                }
                val isWaveTwo = searchRemainingSeconds in 0..25

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("customer_searching_sheet"),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 16.dp
                ) {
                    Column(
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when {
                                    isTestRide -> "Test Ride queued — assigning simulated driver"
                                    visibleOffers.isNotEmpty() -> "${visibleOffers.size} rider offer(s) ready"
                                    searchRemainingSeconds <= 0 -> "No rider found nearby — updating..."
                                    isWaveTwo -> "Expanding search to 6 km..."
                                    else -> "Searching riders within 3 km..."
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!isTestRide) {
                                    GolocalStatusBadge(
                                        label = "${searchRemainingSeconds}s",
                                        tone = if (searchRemainingSeconds <= 10) "error" else "warning"
                                    )
                                } else {
                                    GolocalStatusBadge(label = "TEST RIDE", tone = "warning")
                                }
                                GolocalStatusBadge(label = booking.vehicleCategory.uppercase(), tone = "info")
                            }
                        }

                        if (isTestRide) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = GolocalPrimaryBlue,
                                trackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { (searchRemainingSeconds.toFloat() / 45f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .testTag("customer_searching_countdown_bar"),
                                color = if (searchRemainingSeconds <= 10) GolocalError else GolocalPrimaryBlue,
                                trackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        }

                        Text(
                            text = "${booking.pickupLabel} -> ${booking.destinationLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Est. Fare", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = "₹${booking.estimatedFareInr.toInt()}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = JetBrainsMono,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "Band ₹${booking.minPermittedFareInr.toInt()}–₹${booking.maxPermittedFareInr.toInt()}",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = JetBrainsMono,
                                color = GolocalPrimaryBlue
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onOpenSos,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Text("SOS Safety", color = GolocalError)
                            }
                            Button(
                                onClick = onOpenCancel,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("searching_cancel_button")
                            ) {
                                Text("Cancel Request")
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // Assigned / Arriving / Arrived / In Progress Live Ride View
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sheetHeight = (maxHeight * 0.54f).coerceIn(320.dp, 490.dp)
        GolocalMapContainer(
            pickupLat = booking.pickupLat,
            pickupLng = booking.pickupLng,
            pickupLabel = booking.pickupLabel,
            destLat = booking.destinationLat,
            destLng = booking.destinationLng,
            destLabel = booking.destinationLabel,
            onlineDrivers = drivers,
            liveTripLocation = tripLocation,
            lowNetworkMode = lowNetwork,
            fillContainer = true,
            cornerRadius = 0.dp,
            showBorder = false,
            showTopRouteChip = false,
            rerouteFromLatLng = tripLocation?.let { GeoPoint(it.lat, it.lng) },
            recenterBottomPadding = sheetHeight + 12.dp
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularBackButton(onClick = onBackHome, testTag = "active_ride_back_button")
            val stageHeadline = when (booking.status) {
                "assigned" -> "Rider Confirmed"
                "arriving" -> "Rider On The Way"
                "arrived" -> "Rider At Pickup"
                "in_progress" -> "Trip In Progress"
                else -> booking.status.uppercase()
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 8.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isTestRide) GolocalWarning else GolocalSuccess)
                    )
                    Text(
                        text = if (isTestRide) "TEST RIDE • $stageHeadline" else stageHeadline,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Surface(
                onClick = onOpenSos,
                shape = CircleShape,
                color = GolocalError,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("active_ride_sos_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "SOS",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = sheetHeight),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(42.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                )
                if (isTestRide) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = GolocalWarningContainer,
                        modifier = Modifier.fillMaxWidth().testTag("customer_test_ride_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Science, contentDescription = null, tint = GolocalDeepNavy, modifier = Modifier.size(18.dp))
                            Text(
                                text = "TEST RIDE — Admin-Controlled Live Simulation",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = GolocalDeepNavy
                            )
                        }
                    }

                    if (booking.assignedDriverId.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            modifier = Modifier.fillMaxWidth().testTag("customer_test_driver_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = booking.assignedDriverName.ifBlank { "Simulated Test Driver" },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    GolocalStatusBadge(label = "TEST DRIVER", tone = "warning")
                                }
                                Text(
                                    text = booking.assignedVehicleLabel.ifBlank { "TEST VEHICLE" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Simulated Driver • Controlled via Admin Portal",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                                )
                            }
                        }
                    }

                    if (rideOtpSecret != null &&
                        rideOtpSecret.otpPlaintext.length == 6 &&
                        booking.status in setOf("assigned", "arriving", "arrived")
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, GolocalPrimaryBlue, RoundedCornerShape(16.dp))
                                .testTag("customer_test_otp_secret_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Share 6-Digit Test Pickup OTP",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = "Give this OTP to your Test Rider at pickup (valid until ride starts).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                                Text(
                                    text = rideOtpSecret.otpPlaintext,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontFamily = JetBrainsMono,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("customer_test_otp_secret_value")
                                )
                            }
                        }
                    }

                    CustomerTestPickupOtpCard(
                        booking = booking,
                        isVerifying = isVerifyingTestOtp,
                        errorMessage = testOtpError,
                        onVerifyOtp = onVerifyTestOtp,
                        onClearError = onClearTestOtpError
                    )
                } else {
                    if (booking.status in setOf("assigned", "arriving", "arrived")) {
                        if (rideOtpSecret != null && rideOtpSecret.otpPlaintext.length == 6) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, GolocalPrimaryBlue, RoundedCornerShape(16.dp))
                                    .testTag("customer_commercial_otp_banner")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Share 6-Digit Pickup OTP with Rider",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            text = "Only share this OTP when your Rider arrives at pickup. Protected from driver until verified.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                    Text(
                                        text = rideOtpSecret.otpPlaintext,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontFamily = JetBrainsMono,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.testTag("customer_commercial_otp_value")
                                    )
                                }
                            }
                        } else if (booking.tripPin.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("customer_trip_pin_card")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Share Pickup PIN", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = "Verify rider before sharing PIN at pickup",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = booking.tripPin,
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontFamily = JetBrainsMono,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = GolocalPrimaryBlue
                                    )
                                }
                            }
                        }
                    }

                    if (booking.assignedDriverId.isNotBlank()) {
                        BookingCommunicationBar(
                            booking = booking,
                            viewerRole = "customer",
                            callContact = callContact,
                            unreadCount = unreadChatCount,
                            onCallClick = onOpenCall,
                            onChatClick = onOpenChat
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${booking.vehicleCategory.uppercase()} • ${booking.distanceKm} km",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = GolocalSuccess.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = "₹${(if (booking.agreedFareInr > 0) booking.agreedFareInr else booking.estimatedFareInr).toInt()} • ${booking.paymentMethod.uppercase()}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontFamily = JetBrainsMono,
                                    fontWeight = FontWeight.Bold,
                                    color = GolocalSuccess,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(GolocalPrimaryBlue)
                                )
                                Box(
                                    modifier = Modifier
                                        .width(1.5.dp)
                                        .height(14.dp)
                                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                                )
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(GolocalError)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = booking.pickupLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = booking.destinationLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        tripLocation?.let { loc ->
                            val targetLat = if (booking.status == "in_progress") booking.destinationLat else booking.pickupLat
                            val targetLng = if (booking.status == "in_progress") booking.destinationLng else booking.pickupLng
                            val remainingKm = GpsBroadcastThrottle.haversineMeters(loc.lat, loc.lng, targetLat, targetLng) / 1000.0
                            val liveEtaMin = RouteNavigationMath.estimateEtaMinutes(
                                distanceKm = remainingKm,
                                speedMps = loc.speedKmph / 3.6,
                                vehicleCategory = booking.vehicleCategory
                            )
                            val ageSec = loc.updatedAt?.toDate()?.time?.let { ts ->
                                ((System.currentTimeMillis() - ts) / 1000L).coerceAtLeast(0L)
                            } ?: 0L
                            val isDelayedSignal = loc.trackingStatus == "active" && ageSec > 35L
                            val gpsStatus = when {
                                loc.trackingStatus == "paused_gps_lost" -> "GPS Paused"
                                loc.trackingStatus == "stopped" -> "Tracking Stopped"
                                isDelayedSignal -> "GPS Delayed (${ageSec}s)"
                                else -> "Live GPS"
                            }
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "$gpsStatus: ~%d min (%.1f km) • %d km/h • %.0f°",
                                    liveEtaMin,
                                    remainingKm,
                                    loc.speedKmph.toInt(),
                                    loc.headingDeg
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (loc.trackingStatus == "paused_gps_lost" || isDelayedSignal) {
                                    GolocalWarning
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("customer_live_telemetry_line")
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onShareTrip,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("share_live_location_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Share Live Location",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onOpenSos,
                        colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SOS", fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = onOpenComplaint,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Report Issue")
                    }
                    if (booking.status != "in_progress") {
                        TextButton(
                            onClick = onOpenCancel,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Cancel Ride", color = GolocalError)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IncomingOfferCard(
    offer: RideOffer,
    onDecline: () -> Unit,
    onAccept: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
            .testTag("incoming_offer_card_${offer.id}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = offer.driverName.trim().take(2).uppercase().ifBlank { "DR" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(offer.driverName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = "${offer.vehicleLabel.ifBlank { offer.vehicleCategory.uppercase() }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(14.dp))
                        Text(
                            text = "${String.format(Locale.US, "%.1f", offer.driverRating)} • ${offer.etaMinutes} mins away • ${offer.driverCompletedRides} rides",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = "₹${offer.proposedFareInr.toInt()}",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = JetBrainsMono,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    contentPadding = PaddingValues(vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("decline_offer_button_${offer.id}"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Decline")
                }
                Button(
                    onClick = onAccept,
                    contentPadding = PaddingValues(vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("accept_offer_button_${offer.id}"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Accept")
                }
            }
        }
    }
}

@Composable
private fun RadarPulseOverlay(modifier: Modifier = Modifier) {
    val reducedMotion = rememberPrefersReducedMotion()
    val pulse = if (reducedMotion) {
        0.72f
    } else {
        val transition = rememberInfiniteTransition(label = "radar")
        val animated by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600),
                repeatMode = RepeatMode.Restart
            ),
            label = "radar_pulse"
        )
        animated
    }
    Box(modifier = modifier.size(180.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxR = size.minDimension / 2f
            drawCircle(
                color = Color(0xFF1769E0).copy(alpha = (1f - pulse) * 0.28f),
                radius = maxR * pulse
            )
            drawCircle(
                color = Color(0xFF1769E0).copy(alpha = 0.14f),
                radius = maxR * 0.55f
            )
        }
        Surface(
            shape = CircleShape,
            color = GolocalPrimaryBlue,
            shadowElevation = 6.dp,
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun RideCompletedReceiptDialog(
    booking: Booking,
    onSubmitRating: (stars: Int, behavior: Int, vehicle: Int, punctuality: Int, safety: Int, review: String) -> Unit,
    onDismiss: () -> Unit
) {
    val grossFare = if (booking.agreedFareInr > 0) booking.agreedFareInr else booking.estimatedFareInr
    val settlement = remember(booking.id, grossFare) {
        FareCalculator.calculateSettlement(grossFare, booking.gstPercent, booking.commissionPercent)
    }
    var stars by remember { mutableIntStateOf(5) }
    var behavior by remember { mutableIntStateOf(5) }
    var vehicle by remember { mutableIntStateOf(5) }
    var punctuality by remember { mutableIntStateOf(5) }
    var safety by remember { mutableIntStateOf(5) }
    var review by remember { mutableStateOf("") }
    var showDetailedBreakdown by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 20.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(max = 640.dp)
                .testTag("ride_completed_receipt_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(GolocalAuthNavy, GolocalPrimaryBlue, GolocalRoyalBlueBright)
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(GolocalSuccess)
                                .border(3.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Ride Completed",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        if (booking.isTestRide || booking.testMode) {
                            GolocalStatusBadge(label = "TEST RIDE • SIMULATED RECEIPT", tone = "warning")
                        }

                        Text(
                            text = "Ride Completed!",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Text(
                            text = "₹${settlement.grossFareInr.toInt()}",
                            fontSize = 40.sp,
                            fontFamily = JetBrainsMono,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )

                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color.White.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = "Paid via ${booking.paymentMethod.uppercase()} • Incl. GST ₹${settlement.gstInr} (${booking.gstPercent.toInt()}%)",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }

                        Text(
                            text = "${booking.pickupLabel} → ${booking.destinationLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.86f),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Rate Your Ride",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "How was your trip with ${booking.assignedDriverName.ifBlank { "your rider" }}?",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                (1..5).forEach { s ->
                                    IconButton(
                                        onClick = { stars = s },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("rating_star_$s")
                                    ) {
                                        Icon(
                                            imageVector = if (s <= stars) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                            contentDescription = "$s stars",
                                            tint = if (s <= stars) GolocalGoldStar else MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showDetailedBreakdown = !showDetailedBreakdown }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .testTag("toggle_detailed_rating_button"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Detailed Breakdown & Note (Optional)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            imageVector = if (showDetailedBreakdown) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (showDetailedBreakdown) "Collapse breakdown" else "Expand breakdown",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    AnimatedVisibility(visible = showDetailedBreakdown) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RatingChipSelector("Behavior", behavior) { behavior = it }
                            RatingChipSelector("Vehicle", vehicle) { vehicle = it }
                            RatingChipSelector("Punctuality", punctuality) { punctuality = it }
                            RatingChipSelector("Safety", safety) { safety = it }
                            OutlinedTextField(
                                value = review,
                                onValueChange = { review = it },
                                label = { Text("Feedback note (optional)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Button(
                        onClick = { onSubmitRating(stars, behavior, vehicle, punctuality, safety, review) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GolocalPrimaryBlue),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("submit_completed_rating_button")
                    ) {
                        Text(
                            text = "Submit Rating",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Skip")
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingChipSelector(label: String, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..5).forEach { s ->
                Surface(
                    onClick = { onSelect(s) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected == s) GolocalPrimaryBlue else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "$s★",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected == s) Color.White else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomerTestPickupOtpCard(
    booking: Booking,
    isVerifying: Boolean,
    errorMessage: String?,
    onVerifyOtp: (String) -> Unit,
    onClearError: () -> Unit
) {
    var otpInput by remember(booking.id) { mutableStateOf("") }
    var remainingSec by remember(booking.id, booking.testOtpExpiresAtMs, booking.testOtpStatus) {
        mutableLongStateOf(TestRideOtpUtil.remainingSeconds(booking.testOtpExpiresAtMs))
    }
    LaunchedEffect(booking.id, booking.testOtpExpiresAtMs, booking.testOtpStatus) {
        while (booking.testOtpExpiresAtMs > 0L && booking.testOtpStatus == "pending") {
            remainingSec = TestRideOtpUtil.remainingSeconds(booking.testOtpExpiresAtMs)
            if (remainingSec <= 0L) break
            delay(1000L)
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, GolocalPrimaryBlue, RoundedCornerShape(16.dp))
            .testTag("customer_test_otp_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = GolocalPrimaryBlue, modifier = Modifier.size(18.dp))
                    Text("Pickup OTP Verification", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                if (booking.testOtpStatus.isNotBlank()) {
                    GolocalStatusBadge(
                        label = booking.testOtpStatus.uppercase(),
                        tone = when (booking.testOtpStatus) {
                            "verified", "overridden" -> "success"
                            "expired", "reset" -> "error"
                            else -> "warning"
                        }
                    )
                }
            }

            when {
                booking.testOtpStatus == "verified" -> {
                    Text(
                        text = "Pickup OTP verified. Your simulated test ride is in progress.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GolocalSuccess
                    )
                }
                booking.testOtpStatus == "overridden" -> {
                    Text(
                        text = "Started via authorized Admin OTP Override.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GolocalSuccess
                    )
                }
                booking.status in setOf("assigned", "arriving") -> {
                    Text(
                        text = "OTP entry unlocks automatically once your Test Driver arrives at pickup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                booking.status == "arrived" -> {
                    Text(
                        text = if (remainingSec > 0) {
                            "Enter the 6-digit Test OTP shared from the Admin Portal (valid for ${remainingSec}s)."
                        } else {
                            "Test OTP expired — ask Admin Portal to re-assign or reset this test ride."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (remainingSec > 0) MaterialTheme.colorScheme.onSurfaceVariant else GolocalError
                    )
                    OutlinedTextField(
                        value = otpInput,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }.take(6)
                            otpInput = digits
                            if (!errorMessage.isNullOrBlank()) onClearError()
                        },
                        label = { Text("6-Digit Pickup Test OTP") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("customer_test_otp_input")
                    )
                    if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = GolocalError,
                            modifier = Modifier.testTag("customer_test_otp_error")
                        )
                    }
                    Button(
                        onClick = { onVerifyOtp(otpInput) },
                        enabled = otpInput.length == 6 && !isVerifying && remainingSec > 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("verify_test_otp_button")
                    ) {
                        if (isVerifying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Verify OTP & Start Ride")
                        }
                    }
                }
            }
        }
    }
}

