package com.example.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BusBooking
import com.example.data.model.BusSchedule
import com.example.domain.BusFareEngine
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.components.QrCodeImage
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.CustomerViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomerBusBookingScreen(
    customerViewModel: CustomerViewModel,
    onBackHome: () -> Unit
) {
    val activeRoutes by customerViewModel.activeBusRoutes.collectAsStateWithLifecycle()
    val schedules by customerViewModel.busSchedules.collectAsStateWithLifecycle()
    val myBusBookings by customerViewModel.myBusBookings.collectAsStateWithLifecycle()
    val isSubmitting by customerViewModel.isSubmitting.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var selectedSchedule by remember { mutableStateOf<BusSchedule?>(null) }
    var selectedSeats by remember { mutableStateOf<Set<String>>(emptySet()) }
    var paymentMethod by remember { mutableStateOf("cash") }
    var activeQrTicket by remember { mutableStateOf<BusBooking?>(null) }

    val openSchedules = remember(schedules, activeRoutes, searchQuery) {
        val activeRouteIds = activeRoutes.map { it.id }.toSet()
        val q = searchQuery.trim().lowercase()
        schedules.filter { sch ->
            sch.status == "scheduled" &&
                (activeRouteIds.isEmpty() || sch.routeId in activeRouteIds) &&
                (q.isBlank() ||
                    sch.originStop.lowercase().contains(q) ||
                    sch.destinationStop.lowercase().contains(q) ||
                    sch.operatorName.lowercase().contains(q))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconButton(onClick = onBackHome, modifier = Modifier.testTag("bus_back_button")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Intercity & Local Private Bus",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Approved private operators with official route tariff",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search town, bus stand, or operator") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bus_search_input")
        )

        if (selectedSchedule == null) {
            if (openSchedules.isEmpty()) {
                GolocalEmptyState(
                    title = "No Verified Bus Schedules Open",
                    message = "Private bus departures appear only after operator KYC verification and official West Bengal tariff configuration in Admin."
                )
            } else {
                openSchedules.forEach { sch ->
                    val availableCount = (sch.totalSeats.toInt() - sch.bookedSeats.size).coerceAtLeast(0)
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.DirectionsBus, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Column {
                                        Text("${sch.originStop} -> ${sch.destinationStop}", style = MaterialTheme.typography.titleMedium)
                                        Text("${sch.operatorName} • ${sch.busLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text(
                                    text = "₹${sch.farePerSeatInr.toInt()}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontFamily = JetBrainsMono,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "${sch.travelDateLabel} • ${sch.departureTimeLabel} - ${sch.arrivalTimeLabel} • $availableCount/${sch.totalSeats} seats open",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Tariff Source: ${sch.fareSource} (Effective ${sch.effectiveDateLabel})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    selectedSchedule = sch
                                    selectedSeats = emptySet()
                                },
                                enabled = availableCount > 0,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("select_bus_schedule_${sch.id}")
                            ) {
                                Text(if (availableCount > 0) "Choose Seats" else "Sold Out")
                            }
                        }
                    }
                }
            }
        } else {
            val sch = selectedSchedule!!
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${sch.originStop} -> ${sch.destinationStop}", style = MaterialTheme.typography.titleMedium)
                            Text("${sch.operatorName} • ${sch.departureTimeLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { selectedSchedule = null; selectedSeats = emptySet() }) {
                            Text("Change")
                        }
                    }

                    Text("Tap up to 6 available seats", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BusSeatLegendChip("Available", MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.outline)
                        BusSeatLegendChip("Selected", GolocalPrimaryBlue, GolocalPrimaryBlue)
                        BusSeatLegendChip("Booked", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    }
                    val total = sch.totalSeats.toInt().coerceIn(10, 80)
                    val bookedSet = remember(sch.bookedSeats) { sch.bookedSeats.toSet() }
                    val seatLabels = remember(total) {
                        (1..total).map { idx -> "S$idx" }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        seatLabels.forEach { seat ->
                            val isBooked = seat in bookedSet
                            val isChosen = seat in selectedSeats
                            val bg = when {
                                isBooked -> MaterialTheme.colorScheme.surfaceVariant
                                isChosen -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surface
                            }
                            val fg = when {
                                isBooked -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                isChosen -> Color.White
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            Box(
                                modifier = Modifier
                                    .size(width = 56.dp, height = 48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(bg)
                                    .border(
                                        width = 1.dp,
                                        color = if (isChosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable(enabled = !isBooked) {
                                        selectedSeats = if (isChosen) {
                                            selectedSeats - seat
                                        } else if (selectedSeats.size < 6) {
                                            selectedSeats + seat
                                        } else {
                                            selectedSeats
                                        }
                                    }
                                    .testTag("bus_seat_$seat"),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Icon(Icons.Filled.EventSeat, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
                                    Text(seat, style = MaterialTheme.typography.labelSmall, color = fg)
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    if (selectedSeats.isNotEmpty()) {
                        val quote = remember(sch, selectedSeats) {
                            BusFareEngine.calculateBookingQuote(
                                farePerSeatInr = sch.farePerSeatInr,
                                seatCount = selectedSeats.size,
                                gstPercent = sch.gstPercent,
                                commissionPercent = sch.commissionPercent,
                                fareSource = sch.fareSource,
                                effectiveDateLabel = sch.effectiveDateLabel
                            )
                        }
                        Text("Selected: ${selectedSeats.sorted().joinToString()}", style = MaterialTheme.typography.titleSmall)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Subtotal (${quote.seatCount} x ₹${quote.farePerSeatInr.toInt()})", style = MaterialTheme.typography.bodySmall)
                            Text("₹${quote.subTotalInr.toInt()}", fontFamily = JetBrainsMono)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("GST (${sch.gstPercent}%)", style = MaterialTheme.typography.bodySmall)
                            Text("₹${quote.gstInr}", fontFamily = JetBrainsMono)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total Payable", style = MaterialTheme.typography.titleSmall)
                            Text("₹${quote.totalFareInr.toInt()}", style = MaterialTheme.typography.titleMedium, fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = paymentMethod == "cash",
                            onClick = { paymentMethod = "cash" },
                            label = { Text("Pay on Boarding") }
                        )
                        FilterChip(
                            selected = paymentMethod == "upi",
                            onClick = { paymentMethod = "upi" },
                            label = { Text("UPI (Pending Webhook)") }
                        )
                    }

                    Button(
                        onClick = {
                            customerViewModel.bookBusSeats(
                                schedule = sch,
                                selectedSeats = selectedSeats.toList(),
                                paymentMethod = paymentMethod
                            ) { ticket ->
                                selectedSchedule = null
                                selectedSeats = emptySet()
                                activeQrTicket = ticket
                            }
                        },
                        enabled = selectedSeats.isNotEmpty() && !isSubmitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("confirm_bus_booking_button")
                    ) {
                        Text(if (isSubmitting) "Reserving Seats..." else "Confirm Seat Ticket")
                    }
                }
            }
        }

        if (myBusBookings.isNotEmpty()) {
            Text("Your Bus Tickets (${myBusBookings.size})", style = MaterialTheme.typography.titleMedium)
            myBusBookings.forEach { ticket ->
                BusTicketSummaryCard(
                    ticket = ticket,
                    onShowQr = { activeQrTicket = ticket },
                    onCancel = { customerViewModel.cancelBusBooking(ticket, "Passenger plan changed") }
                )
            }
        }
    }

    activeQrTicket?.let { ticket ->
        BusQrTicketDialog(ticket = ticket, onDismiss = { activeQrTicket = null })
    }
}

@Composable
fun BusTicketSummaryCard(
    ticket: BusBooking,
    onShowQr: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(ticket.bookingRef, style = MaterialTheme.typography.titleSmall, fontFamily = JetBrainsMono)
                GolocalStatusBadge(
                    label = ticket.status.uppercase(),
                    tone = when (ticket.status) {
                        "confirmed", "boarded" -> "success"
                        "cancelled" -> "error"
                        else -> "info"
                    }
                )
            }
            Text(ticket.routeLabel, style = MaterialTheme.typography.titleSmall)
            Text(
                "${ticket.operatorName} • ${ticket.travelDateLabel} ${ticket.departureTimeLabel} • Seats: ${ticket.seatNumbers.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Total: ₹${ticket.totalFareInr.toInt()} (${ticket.paymentStatus})",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = JetBrainsMono,
                color = MaterialTheme.colorScheme.primary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onShowQr,
                    modifier = Modifier.weight(1f).testTag("show_bus_qr_${ticket.id}")
                ) {
                    Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("QR Ticket")
                }
                if (ticket.status == "confirmed") {
                    TextButton(onClick = onCancel) {
                        Text("Cancel Ticket", color = GolocalError)
                    }
                }
            }
        }
    }
}

@Composable
fun BusQrTicketDialog(
    ticket: BusBooking,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Digital Boarding Pass") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag("bus_ticket_qr_dialog"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QrCodeImage(
                    payload = ticket.qrPayload,
                    contentDescription = "Bus ticket QR code for ${ticket.bookingRef}",
                    sizeDp = 188.dp
                )
                Text(ticket.bookingRef, style = MaterialTheme.typography.titleMedium, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold)
                Text(ticket.routeLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Seats: ${ticket.seatNumbers.joinToString()} • ${ticket.travelDateLabel} ${ticket.departureTimeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GolocalStatusBadge(
                    label = if (ticket.status == "confirmed") "VALID FOR BOARDING" else ticket.status.uppercase(),
                    tone = if (ticket.status == "confirmed") "success" else "error"
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun BusSeatLegendChip(
    label: String,
    fillColor: Color,
    borderColor: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(fillColor)
                .border(1.dp, borderColor, RoundedCornerShape(4.dp))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
