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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.Booking
import com.example.data.model.BookingCallContact
import com.example.data.model.CommissionRecord
import com.example.data.model.DriverDirectoryEntry
import com.example.map.GolocalMapContainer
import com.example.shared.BookingCommunicationBar
import com.example.shared.LocationCoordinator
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalPureWhite
import com.example.ui.theme.GolocalSuccess
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
    onAdvanceState: (nextStatus: String, pin: String) -> Unit
) {
    val context = LocalContext.current
    var enteredPin by remember(booking.id) { mutableStateOf("") }

    val navigateToDrop = booking.status == "in_progress"
    val mapPickupLat = if (navigateToDrop) booking.pickupLat else currentLat
    val mapPickupLng = if (navigateToDrop) booking.pickupLng else currentLng
    val mapPickupLabel = if (navigateToDrop) booking.pickupLabel else "Your GPS"
    val mapDestLat = if (navigateToDrop) booking.destinationLat else booking.pickupLat
    val mapDestLng = if (navigateToDrop) booking.destinationLng else booking.pickupLng
    val mapDestLabel = if (navigateToDrop) booking.destinationLabel else booking.pickupLabel

    val phaseTitle = when (booking.status) {
        "assigned" -> "Ride Assigned • Go to Pickup"
        "arriving" -> "En Route to Pickup"
        "arrived" -> "Arrived • Verify Trip PIN"
        "in_progress" -> "Trip in Progress"
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
            userLng = currentLng
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
                        color = GolocalDeepNavy
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
                            contentDescription = "Navigate",
                            tint = GolocalPureWhite,
                            modifier = Modifier.size(18.dp)
                        )
                        Text("Navigate", color = GolocalPureWhite, style = MaterialTheme.typography.labelLarge)
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
                .testTag("rider_active_trip_sheet"),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 18.dp
        ) {
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
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
                            text = "₹${booking.agreedFareInr.toInt()} (${booking.paymentMethod.uppercase()}) • ${booking.distanceKm} km",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMono,
                            color = GolocalPrimaryBlue
                        )
                    }
                    GolocalStatusBadge(label = booking.status.replace('_', ' ').uppercase(), tone = "success")
                }

                BookingCommunicationBar(
                    booking = booking,
                    viewerRole = "driver",
                    callContact = callContact,
                    unreadCount = unreadChatCount,
                    onCallClick = onCallCustomer,
                    onChatClick = onChatCustomer
                )

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
                        OutlinedTextField(
                            value = enteredPin,
                            onValueChange = { ch ->
                                if (ch.length <= 4 && ch.all { it.isDigit() }) enteredPin = ch
                            },
                            label = { Text("Enter 4-Digit Passenger Trip PIN") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("rider_trip_pin_input")
                        )
                        Button(
                            onClick = { onAdvanceState("in_progress", enteredPin) },
                            contentPadding = PaddingValues(vertical = 14.dp),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                        ) {
                            Text("Verify PIN & Start Ride", fontWeight = FontWeight.Bold)
                        }
                    }

                    "in_progress" -> {
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
                            Text("Complete Ride & Settle Fare", fontWeight = FontWeight.Bold)
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

    val gross = commission?.grossFareInr ?: booking.agreedFareInr
    val fee = commission?.commissionAmountInr ?: (gross * (booking.commissionPercent / 100.0))
    val net = commission?.netDriverEarningsInr ?: (gross - fee).coerceAtLeast(0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trip Completed & Settled", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${booking.pickupLabel} → ${booking.destinationLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SettlementRow("Gross Fare (${booking.paymentMethod.uppercase()})", "₹${gross.toInt()}")
                        SettlementRow("Platform Commission", "-₹${fee.toInt()}")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        SettlementRow("Net Partner Earnings", "₹${net.toInt()}", highlight = true)
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
    highlight: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = if (highlight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            text = value,
            style = if (highlight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Bold,
            color = if (highlight) GolocalSuccess else MaterialTheme.colorScheme.onSurface
        )
    }
}
