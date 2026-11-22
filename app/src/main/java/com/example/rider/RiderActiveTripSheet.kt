package com.example.rider

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Booking
import com.example.data.model.BookingCallContact
import com.example.data.model.CommissionRecord
import com.example.data.model.DriverDirectoryEntry
import com.example.map.GeoPoint
import com.example.map.GolocalMapContainer
import com.example.map.GpsBroadcastThrottle
import com.example.map.RouteNavigationMath
import com.example.map.RouteUiState
import com.example.shared.BookingCommunicationBar
import com.example.shared.LocationCoordinator
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalPureWhite
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.GolocalWarningContainer
import com.example.ui.theme.JetBrainsMono
import java.util.Locale

@Composable
fun RiderActiveTripSheet(
    booking: Booking,
    currentLat: Double,
    currentLng: Double,
    onlineSelf: DriverDirectoryEntry,
    lowNetworkMode: Boolean,
    callContact: BookingCallContact?,
    unreadChatCount: Int,
    onCallCustomer: () -> Unit,
    onChatCustomer: () -> Unit,
    onTriggerSos: () -> Unit,
    onCancelTrip: () -> Unit,
    onAdvanceState: (nextStatus: String, pin: String) -> Unit,
    onVerifyTestOtp: (String) -> Unit = {},
    isVerifyingTestOtp: Boolean = false,
    testOtpError: String? = null,
    onClearTestOtpError: () -> Unit = {},
    onVerifyCommercialOtp: (String) -> Unit = {},
    isVerifyingCommercialOtp: Boolean = false,
    commercialOtpError: String? = null,
    onClearCommercialOtpError: () -> Unit = {}
) {
    val context = LocalContext.current
    val gpsHealth by com.example.data.tracking.RiderLocationForegroundService.gpsHealth.collectAsStateWithLifecycle()
    var enteredPin by remember(booking.id) { mutableStateOf("") }
    var enteredTestOtp by remember(booking.id) { mutableStateOf("") }
    var activeRouteState by remember(booking.id) { mutableStateOf<RouteUiState>(RouteUiState.Idle) }

    val navigateToDrop = booking.status == "in_progress"
    val mapPickupLat = if (navigateToDrop) booking.pickupLat else currentLat
    val mapPickupLng = if (navigateToDrop) booking.pickupLng else currentLng
    val mapPickupLabel = if (navigateToDrop) booking.pickupLabel else "Your GPS"
    val mapDestLat = if (navigateToDrop) booking.destinationLat else booking.pickupLat
    val mapDestLng = if (navigateToDrop) booking.destinationLng else booking.pickupLng
    val mapDestLabel = if (navigateToDrop) booking.destinationLabel else booking.pickupLabel

    val isTestRide = booking.isTestRide || booking.testMode
    val phaseTitle = when (booking.status) {
        "assigned" -> if (isTestRide) "TEST RIDE • Go to Pickup" else "Ride Assigned • Go to Pickup"
        "arriving" -> if (isTestRide) "TEST RIDE • En Route" else "En Route to Pickup"
        "arrived" -> if (isTestRide) "TEST RIDE • Await Customer OTP" else "Arrived • Verify 6-Digit OTP"
        "in_progress" -> if (isTestRide) "TEST RIDE • In Progress" else "Trip in Progress"
        else -> booking.status.replace('_', ' ').uppercase()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GolocalMapContainer(
            pickupLat = mapPickupLat,
            pickupLng = mapPickupLng,
            pickupLabel = mapPickupLabel,
            destLat = mapDestLat,
            destLng = mapDestLng,
            destLabel = mapDestLabel,
            onlineDrivers = listOf(onlineSelf),
            liveTripLocation = null,
            lowNetworkMode = lowNetworkMode,
            fillContainer = true,
            cornerRadius = 0.dp,
            showBorder = false,
            showTopRouteChip = false,
            showRecenterFab = true,
            recenterBottomPadding = 340.dp,
            userLat = currentLat,
            userLng = currentLng,
            rerouteFromLatLng = GeoPoint(currentLat, currentLng),
            onRouteComputed = { activeRouteState = it }
        )

        // Top Floating Status + Navigate + SOS Bar
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(GolocalSuccess, CircleShape)
                    )
                    Text(
                        text = phaseTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = {
                        LocationCoordinator.openExternalNavigation(
                            context = context,
                            lat = mapDestLat,
                            lng = mapDestLng,
                            label = mapDestLabel
                        )
                    },
                    shape = RoundedCornerShape(999.dp),
                    color = GolocalPrimaryBlue,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("rider_external_navigate_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Navigation,
                            contentDescription = "Open external navigation app",
                            tint = GolocalPureWhite,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (navigateToDrop) "Nav Drop" else "Nav Pickup",
                            color = GolocalPureWhite,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                Surface(
                    onClick = onTriggerSos,
                    shape = CircleShape,
                    color = GolocalError,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("rider_active_sos_button")
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

        // Bottom Execution Control Sheet
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .testTag("rider_active_trip_sheet"),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 18.dp
        ) {
            val remainingKm = remember(currentLat, currentLng, mapDestLat, mapDestLng, activeRouteState) {
                when (val st = activeRouteState) {
                    is RouteUiState.Ready -> st.distanceKm
                    else -> GpsBroadcastThrottle.haversineMeters(currentLat, currentLng, mapDestLat, mapDestLng) / 1000.0
                }
            }
            val liveEtaMin = remember(remainingKm, activeRouteState, booking.vehicleCategory) {
                when (val st = activeRouteState) {
                    is RouteUiState.Ready -> st.durationMin.toInt()
                    else -> RouteNavigationMath.estimateEtaMinutes(
                        distanceKm = remainingKm,
                        vehicleCategory = booking.vehicleCategory
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${booking.pickupLabel} → ${booking.destinationLabel}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isTestRide) {
                                String.format(
                                    Locale.US,
                                    "TEST RIDE • ₹0 • %.1f km (~%d min ETA)",
                                    remainingKm,
                                    liveEtaMin
                                )
                            } else {
                                String.format(
                                    Locale.US,
                                    "₹%d (%s) • %.1f km (~%d min ETA)",
                                    booking.agreedFareInr.toInt(),
                                    booking.paymentMethod.uppercase(),
                                    remainingKm,
                                    liveEtaMin
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMono,
                            color = GolocalPrimaryBlue
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isTestRide) {
                            GolocalStatusBadge(label = "TEST RIDE", tone = "warning")
                        }
                        GolocalStatusBadge(label = booking.status.replace('_', ' ').uppercase(), tone = "success")
                    }
                }

                TripProgressStepperRow(currentStatus = booking.status)

                if (gpsHealth.gpsStatus in setOf("gps_lost", "network_interrupted", "permission_denied")) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = GolocalWarningContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rider_active_gps_warning_banner")
                    ) {
                        Text(
                            text = gpsHealth.lastError ?: when (gpsHealth.gpsStatus) {
                                "gps_lost" -> "GPS signal lost — waiting for satellite lock"
                                "network_interrupted" -> "Network interrupted — live location will resume automatically"
                                else -> "Location permission required for live trip tracking"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = GolocalDeepNavy,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                BookingCommunicationBar(
                    booking = booking,
                    viewerRole = "driver",
                    callContact = callContact,
                    unreadCount = unreadChatCount,
                    onCallClick = onCallCustomer,
                    onChatClick = onChatCustomer
                )

                if (!navigateToDrop) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rider_destination_nav_locked")
                    ) {
                        Text(
                            text = "Pickup stage: Maps Nav points to pickup (${booking.pickupLabel}). Destination navigation unlocks after 6-digit Pickup OTP verification.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                when (booking.status) {
                    "assigned" -> {
                        Button(
                            onClick = { onAdvanceState("arriving", "") },
                            contentPadding = PaddingValues(vertical = 14.dp),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                        ) {
                            Text("Head to Pickup (Mark Arriving)", fontWeight = FontWeight.Bold)
                        }
                    }

                    "arriving" -> {
                        Button(
                            onClick = { onAdvanceState("arrived", "") },
                            contentPadding = PaddingValues(vertical = 14.dp),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                        ) {
                            Text("Mark Arrived at Pickup", fontWeight = FontWeight.Bold)
                        }
                    }

                    "arrived" -> {
                        if (isTestRide) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = GolocalWarningContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, GolocalWarning, RoundedCornerShape(14.dp))
                                    .testTag("rider_waiting_test_otp_card")
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Verify 6-Digit Customer Test OTP",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = GolocalDeepNavy
                                    )
                                    Text(
                                        text = "Ask passenger for their 6-digit Pickup OTP to start, or the Customer/Owner Admin can start via their app.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GolocalDeepNavy.copy(alpha = 0.78f)
                                    )
                                    OutlinedTextField(
                                        value = enteredTestOtp,
                                        onValueChange = { ch ->
                                            val digits = ch.filter { it.isDigit() }.take(6)
                                            enteredTestOtp = digits
                                            if (!testOtpError.isNullOrBlank()) onClearTestOtpError()
                                        },
                                        label = { Text("Enter 6-Digit Pickup Test OTP") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = GolocalDeepNavy,
                                            unfocusedTextColor = GolocalDeepNavy,
                                            focusedLabelColor = GolocalDeepNavy,
                                            unfocusedLabelColor = GolocalDeepNavy.copy(alpha = 0.78f),
                                            focusedBorderColor = GolocalPrimaryBlue,
                                            unfocusedBorderColor = GolocalDeepNavy.copy(alpha = 0.45f),
                                            cursorColor = GolocalDeepNavy
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("rider_test_otp_input")
                                    )
                                    if (!testOtpError.isNullOrBlank()) {
                                        Text(
                                            text = testOtpError,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = GolocalError,
                                            modifier = Modifier.testTag("rider_test_otp_error")
                                        )
                                    }
                                    Button(
                                        onClick = { onVerifyTestOtp(enteredTestOtp) },
                                        enabled = enteredTestOtp.length == 6 && !isVerifyingTestOtp,
                                        contentPadding = PaddingValues(vertical = 12.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag("rider_verify_test_otp_button")
                                    ) {
                                        Text(
                                            text = if (isVerifyingTestOtp) "Verifying OTP..." else "Verify Pickup OTP & Start",
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = enteredPin,
                                onValueChange = { ch ->
                                    val digits = ch.filter { it.isDigit() }.take(6)
                                    enteredPin = digits
                                    if (!commercialOtpError.isNullOrBlank()) onClearCommercialOtpError()
                                },
                                label = { Text("Enter 6-Digit Passenger Pickup OTP") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("rider_trip_pin_input")
                            )
                            if (!commercialOtpError.isNullOrBlank()) {
                                Text(
                                    text = commercialOtpError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GolocalError,
                                    modifier = Modifier.testTag("rider_commercial_otp_error")
                                )
                            }
                            Button(
                                onClick = { onVerifyCommercialOtp(enteredPin) },
                                enabled = enteredPin.length == 6 && !isVerifyingCommercialOtp,
                                contentPadding = PaddingValues(vertical = 14.dp),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 50.dp)
                                    .testTag("rider_verify_commercial_otp_button")
                            ) {
                                Text(
                                    text = if (isVerifyingCommercialOtp) "Verifying OTP..." else "Verify OTP & Start Ride",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    "in_progress" -> {
                        Button(
                            onClick = {
                                LocationCoordinator.openExternalNavigation(
                                    context = context,
                                    lat = booking.destinationLat,
                                    lng = booking.destinationLng,
                                    label = booking.destinationLabel
                                )
                            },
                            contentPadding = PaddingValues(vertical = 12.dp),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("rider_navigate_destination_prompt")
                        ) {
                            Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Navigate to Destination (Google Maps)", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = String.format(
                                Locale.US,
                                "Live GPS broadcast active (%.4f, %.4f)",
                                currentLat,
                                currentLng
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = GolocalSuccess
                        )
                        Button(
                            onClick = { onAdvanceState("completed", "") },
                            colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                            contentPadding = PaddingValues(vertical = 14.dp),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("rider_complete_ride_button")
                        ) {
                            Text(
                                text = if (isTestRide) "Complete Test Ride (₹0)" else "Complete Ride & Settle Fare",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (booking.status != "in_progress") {
                    TextButton(
                        onClick = onCancelTrip,
                        modifier = Modifier
                            .align(Alignment.End)
                            .testTag("rider_cancel_trip_button")
                    ) {
                        Text("Cancel Assigned Trip", color = GolocalError)
                    }
                }
            }
        }
    }
}

@Composable
fun RiderCompletedSettlementDialog(
    booking: Booking,
    commission: CommissionRecord?,
    onSubmitFeedback: (stars: Int, note: String) -> Unit,
    onDismiss: () -> Unit
) {
    var stars by remember(booking.id) { mutableIntStateOf(5) }
    var note by remember(booking.id) { mutableStateOf("") }

    val isTestRide = booking.isTestRide || booking.testMode
    val gross = if (isTestRide) 0.0 else (commission?.grossFareInr ?: booking.agreedFareInr)
    val fee = if (isTestRide) 0.0 else (commission?.commissionAmountInr ?: (gross * (booking.commissionPercent / 100.0)))
    val net = if (isTestRide) 0.0 else (commission?.netDriverEarningsInr ?: (gross - fee).coerceAtLeast(0.0))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isTestRide) "TEST RIDE Completed" else "Trip Completed & Settled",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${booking.pickupLabel} → ${booking.destinationLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isTestRide) GolocalWarningContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (isTestRide) GolocalWarning else MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isTestRide) {
                            SettlementRow("Test Mode Impact", "NON-COMMERCIAL", onLightContainer = true)
                            SettlementRow("Commission / Payout", "₹0 (Excluded)", highlight = true, onLightContainer = true)
                        } else {
                            SettlementRow("Gross Fare (${booking.paymentMethod.uppercase()})", "₹${gross.toInt()}")
                            SettlementRow("Platform Commission", "-₹${fee.toInt()}")
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            SettlementRow("Net Partner Earnings", "₹${net.toInt()}", highlight = true)
                        }
                    }
                }

                Text("Rate Passenger (Optional)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { s ->
                        FilterChip(
                            selected = stars == s,
                            onClick = { stars = s },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("$s")
                                    Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(13.dp))
                                }
                            }
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Passenger note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmitFeedback(stars, note) },
                modifier = Modifier.testTag("rider_submit_passenger_feedback_button")
            ) {
                Text("Submit & Done")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Skip")
            }
        }
    )
}

@Composable
private fun SettlementRow(
    label: String,
    value: String,
    highlight: Boolean = false,
    onLightContainer: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = if (highlight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (onLightContainer) GolocalDeepNavy else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = if (highlight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Bold,
            color = when {
                highlight -> GolocalSuccess
                onLightContainer -> GolocalDeepNavy
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun TripProgressStepperRow(currentStatus: String) {
    val steps = listOf(
        "assigned" to "Accepted",
        "arriving" to "En Route",
        "arrived" to "At Pickup",
        "in_progress" to "On Trip"
    )
    val activeIdx = steps.indexOfFirst { it.first == currentStatus }.coerceAtLeast(0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("rider_trip_stepper"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { idx, (_, title) ->
            val reached = idx <= activeIdx
            val isCurrent = idx == activeIdx
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = when {
                    isCurrent -> GolocalPrimaryBlue.copy(alpha = 0.16f)
                    reached -> GolocalSuccess.copy(alpha = 0.14f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                }
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        isCurrent -> GolocalPrimaryBlue
                        reached -> GolocalSuccess
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                )
            }
        }
    }
}
