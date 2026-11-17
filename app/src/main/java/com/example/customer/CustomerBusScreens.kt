package com.example.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BusBooking
import com.example.data.model.BusSchedule
import com.example.domain.BusFareEngine
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalQrCodeBox
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.components.GolocalStatusMessageBanner
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.BusBookingViewModel
import com.example.viewmodel.GovernanceViewModel

@Composable
fun CustomerBusBookingTab(
    customerName: String,
    busViewModel: BusBookingViewModel,
    governanceViewModel: GovernanceViewModel
) {
    val schedules by busViewModel.busSchedules.collectAsStateWithLifecycle()
    val seatLocks by busViewModel.selectedScheduleLocks.collectAsStateWithLifecycle()
    val selectedSchedule by busViewModel.selectedSchedule.collectAsStateWithLifecycle()
    val myTickets by busViewModel.myBusBookings.collectAsStateWithLifecycle()
    val busMessage by busViewModel.message.collectAsStateWithLifecycle()
    val isBusy by busViewModel.isBusy.collectAsStateWithLifecycle()

    var subTab by remember { mutableIntStateOf(0) }
    var originFilter by remember { mutableStateOf("") }
    var destFilter by remember { mutableStateOf("") }
    var dateFilter by remember { mutableStateOf("") }
    var inspectedTicket by remember { mutableStateOf<BusBooking?>(null) }
    var cancelTicketTarget by remember { mutableStateOf<BusBooking?>(null) }
    var disputeTicketTarget by remember { mutableStateOf<BusBooking?>(null) }

    val activeSchedule = selectedSchedule
    if (activeSchedule != null) {
        BusSeatSelectionScreen(
            schedule = activeSchedule,
            seatLocks = seatLocks,
            customerName = customerName,
            isBusy = isBusy,
            onBack = { busViewModel.selectScheduleForSeatPicker(null) },
            onConfirmBooking = { boarding, drop, seatIds, paymentMethod ->
                busViewModel.reserveAndBookSeats(
                    schedule = activeSchedule,
                    customerName = customerName,
                    boardingStop = boarding,
                    dropStop = drop,
                    seatIds = seatIds,
                    paymentMethod = paymentMethod
                ) { issuedTicket ->
                    inspectedTicket = issuedTicket
                    subTab = 1
                }
            }
        )
        return
    }

    val filteredSchedules = remember(schedules, originFilter, destFilter, dateFilter) {
        val o = originFilter.trim().lowercase()
        val d = destFilter.trim().lowercase()
        val dt = dateFilter.trim()
        schedules.filter { sched ->
            val okOrigin = o.isBlank() || sched.originTown.lowercase().contains(o) ||
                sched.stops.any { it.lowercase().contains(o) }
            val okDest = d.isBlank() || sched.destinationTown.lowercase().contains(d) ||
                sched.stops.any { it.lowercase().contains(d) }
            val okDate = dt.isBlank() || sched.departureDateIso.contains(dt)
            okOrigin && okDest && okDate && BusFareEngine.canBookSchedule(sched)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Private Bus & Inter-Town Tickets", style = MaterialTheme.typography.headlineSmall)

        GolocalStatusMessageBanner(
            message = busMessage,
            onDismiss = { busViewModel.clearMessage() }
        )

        TabRow(selectedTabIndex = subTab) {
            Tab(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                text = { Text("Search Buses (${filteredSchedules.size})") },
                modifier = Modifier.testTag("customer_bus_search_tab")
            )
            Tab(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                text = { Text("My Tickets (${myTickets.size})") },
                modifier = Modifier.testTag("customer_bus_tickets_tab")
            )
        }

        if (subTab == 0) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = GolocalPrimaryBlue)
                        Text(
                            text = "Only Admin-verified bus operators and tariff-approved West Bengal schedules are listed here.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = originFilter,
                        onValueChange = { originFilter = it },
                        label = { Text("From town / stand") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("bus_origin_filter")
                    )
                    OutlinedTextField(
                        value = destFilter,
                        onValueChange = { destFilter = it },
                        label = { Text("To town / stand") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("bus_dest_filter")
                    )
                }
                OutlinedTextField(
                    value = dateFilter,
                    onValueChange = { dateFilter = it.take(10) },
                    label = { Text("Departure Date (YYYY-MM-DD, optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (filteredSchedules.isEmpty()) {
                    GolocalEmptyState(
                        title = "No Approved Bus Schedules Published",
                        message = "When verified Private Bus operators publish routes and GoLocal Admin approves the tariff schedule, buses will appear here automatically."
                    )
                } else {
                    filteredSchedules.forEach { sched ->
                        val availableCount = (sched.totalSeats - sched.bookedSeatIds.size).coerceAtLeast(0)
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${sched.originTown} → ${sched.destinationTown}", style = MaterialTheme.typography.titleMedium)
                                        Text("${sched.busName} • ${sched.busType}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(
                                        text = "₹${sched.farePerSeatInr.toInt()}/seat",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontFamily = JetBrainsMono,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = "${sched.departureDateIso} • ${sched.departureTimeLabel} → ${sched.arrivalTimeLabel} • $availableCount/${sched.totalSeats} seats open",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Tariff Source: ${sched.fareSource.replace('_', ' ')} (${sched.authorityRef}) • Eff: ${sched.effectiveDateIso}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GolocalSuccess
                                )
                                if (sched.stops.isNotEmpty()) {
                                    Text(
                                        text = "Stops: ${sched.stops.joinToString(" • ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Button(
                                    onClick = { busViewModel.selectScheduleForSeatPicker(sched) },
                                    enabled = availableCount > 0,
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Text(if (availableCount > 0) "Select Seats & Book" else "Sold Out")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (myTickets.isEmpty()) {
                    GolocalEmptyState(
                        title = "No Bus Tickets Booked",
                        message = "Your confirmed inter-town and local bus tickets with QR boarding passes will appear here."
                    )
                } else {
                    myTickets.forEach { ticket ->
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    GolocalStatusBadge(
                                        label = ticket.status.uppercase(),
                                        tone = when (ticket.status) {
                                            "boarded" -> "success"
                                            "cancelled" -> "error"
                                            else -> "info"
                                        }
                                    )
                                    Text("₹${ticket.totalPaidInr.toInt()}", fontFamily = JetBrainsMono, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                }
                                Text(ticket.routeLabel.ifBlank { "${ticket.boardingStop} → ${ticket.dropStop}" }, style = MaterialTheme.typography.titleSmall)
                                Text("${ticket.busName} • ${ticket.departureDateIso} ${ticket.departureTimeLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Seats: ${ticket.seatIds.joinToString(", ")} • Boarding: ${ticket.boardingStop} → ${ticket.dropStop}", style = MaterialTheme.typography.bodySmall)
                                Text("QR Token: ${ticket.qrToken}", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.primary)

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { inspectedTicket = ticket },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("QR Pass")
                                    }
                                    if (ticket.status == "confirmed") {
                                        OutlinedButton(
                                            onClick = { cancelTicketTarget = ticket },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Cancel", color = GolocalError)
                                        }
                                    }
                                    TextButton(onClick = { disputeTicketTarget = ticket }) {
                                        Text("Dispute")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    inspectedTicket?.let { ticket ->
        AlertDialog(
            onDismissRequest = { inspectedTicket = null },
            title = { Text("GoLocal Bus E-Ticket") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GolocalQrCodeBox(payload = ticket.qrToken, size = 190.dp)
                    Text(ticket.qrToken, fontFamily = JetBrainsMono, style = MaterialTheme.typography.titleSmall)
                    Text(ticket.busName, style = MaterialTheme.typography.titleMedium)
                    Text("${ticket.boardingStop} → ${ticket.dropStop}", style = MaterialTheme.typography.bodyMedium)
                    Text("${ticket.departureDateIso} • ${ticket.departureTimeLabel}", style = MaterialTheme.typography.bodySmall)
                    Text("Seats: ${ticket.seatIds.joinToString(", ")} • Status: ${ticket.status.uppercase()}", style = MaterialTheme.typography.labelLarge, color = GolocalSuccess)
                    Text(
                        "Fare: Base ₹${ticket.baseFareInr.toInt()} + GST ₹${ticket.gstInr} = ₹${ticket.totalPaidInr.toInt()}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMono
                    )
                }
            },
            confirmButton = {
                Button(onClick = { inspectedTicket = null }) { Text("Done") }
            }
        )
    }

    cancelTicketTarget?.let { ticket ->
        var reason by remember { mutableStateOf("Travel plan changed") }
        val (fee, refund) = BusFareEngine.evaluateBusCancellationRefund(ticket.totalPaidInr, hoursBeforeDeparture = 12)
        AlertDialog(
            onDismissRequest = { cancelTicketTarget = null },
            title = { Text("Cancel Bus Ticket?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Estimated policy refund: ₹${refund.toInt()} (Fee: ₹${fee.toInt()}). Seats ${ticket.seatIds.joinToString(", ")} will be released immediately.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Reason for cancellation") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    onClick = {
                        busViewModel.cancelBusTicket(ticket.id, reason)
                        cancelTicketTarget = null
                    }
                ) { Text("Confirm Cancel") }
            },
            dismissButton = {
                TextButton(onClick = { cancelTicketTarget = null }) { Text("Keep Ticket") }
            }
        )
    }

    disputeTicketTarget?.let { ticket ->
        SubmitDisputeDialog(
            busBookingId = ticket.id,
            defaultRefundInr = ticket.totalPaidInr,
            onDismiss = { disputeTicketTarget = null },
            onSubmit = { cat, desc, refund ->
                governanceViewModel.submitDispute(
                    busBookingId = ticket.id,
                    targetUserId = ticket.operatorId,
                    category = cat,
                    description = desc,
                    requestedRefundInr = refund
                )
                disputeTicketTarget = null
            }
        )
    }
}

@Composable
private fun BusSeatSelectionScreen(
    schedule: BusSchedule,
    seatLocks: List<com.example.data.model.BusSeatLock>,
    customerName: String,
    isBusy: Boolean,
    onBack: () -> Unit,
    onConfirmBooking: (boarding: String, drop: String, seatIds: List<String>, paymentMethod: String) -> Unit
) {
    val allSeatIds = remember(schedule.id, schedule.totalSeats) {
        BusFareEngine.generateSeatIds(schedule.totalSeats)
    }
    val nowMs = System.currentTimeMillis()
    val unavailableSeats = remember(schedule.bookedSeatIds, seatLocks, nowMs) {
        val activeLockIds = seatLocks.filter {
            it.status == "booked" || (it.status == "locked" && it.lockedUntilEpochMs > nowMs)
        }.map { it.seatId }
        (schedule.bookedSeatIds + activeLockIds).toSet()
    }

    val stopList = remember(schedule.id) {
        val list = mutableListOf(schedule.originTown)
        schedule.stops.forEach { if (it.isNotBlank() && it !in list) list.add(it) }
        if (schedule.destinationTown.isNotBlank() && schedule.destinationTown !in list) list.add(schedule.destinationTown)
        list.filter { it.isNotBlank() }
    }

    var selectedSeats by remember(schedule.id) { mutableStateOf(emptyList<String>()) }
    var boardingStop by remember(schedule.id) { mutableStateOf(stopList.firstOrNull() ?: schedule.originTown) }
    var dropStop by remember(schedule.id) { mutableStateOf(stopList.lastOrNull() ?: schedule.destinationTown) }
    var paymentMethod by remember { mutableStateOf("upi") }

    val breakdown = remember(selectedSeats.size, schedule.farePerSeatInr, schedule.gstPercent, schedule.commissionPercent) {
        if (selectedSeats.isEmpty()) null
        else runCatching {
            BusFareEngine.calculateBreakdown(
                farePerSeatInr = schedule.farePerSeatInr,
                seatCount = selectedSeats.size,
                gstPercent = schedule.gstPercent,
                commissionPercent = schedule.commissionPercent
            )
        }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to buses")
            }
            Column {
                Text("${schedule.originTown} → ${schedule.destinationTown}", style = MaterialTheme.typography.titleLarge)
                Text("${schedule.busName} • ${schedule.departureDateIso} ${schedule.departureTimeLabel}", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Boarding / Drop selectors
        Text("Boarding & Drop Stops", style = MaterialTheme.typography.titleSmall)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            stopList.forEach { stop ->
                FilterChip(
                    selected = boardingStop == stop,
                    onClick = { boardingStop = stop },
                    label = { Text("Board: $stop") }
                )
            }
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            stopList.forEach { stop ->
                FilterChip(
                    selected = dropStop == stop,
                    onClick = { dropStop = stop },
                    label = { Text("Drop: $stop") }
                )
            }
        }

        // Interactive Coach Seat Layout (2 + Aisle + 2)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Select Up to 6 Seats", style = MaterialTheme.typography.titleSmall)
                    Text("Passenger: $customerName", style = MaterialTheme.typography.labelSmall)
                }

                // Legend
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SeatLegendDot("Open", MaterialTheme.colorScheme.surfaceVariant)
                    SeatLegendDot("Selected", GolocalPrimaryBlue)
                    SeatLegendDot("Booked/Locked", GolocalError.copy(alpha = 0.55f))
                }

                val rows = allSeatIds.chunked(4)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { rowSeats ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            rowSeats.forEachIndexed { idx, seatId ->
                                if (idx == 2) {
                                    Spacer(modifier = Modifier.width(18.dp))
                                }
                                val taken = seatId in unavailableSeats
                                val picked = seatId in selectedSeats
                                val bg = when {
                                    taken -> GolocalError.copy(alpha = 0.25f)
                                    picked -> GolocalPrimaryBlue
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                                val fg = when {
                                    taken -> GolocalError
                                    picked -> MaterialTheme.colorScheme.onPrimary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(bg)
                                        .border(
                                            1.dp,
                                            if (picked) GolocalPrimaryBlue else MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable(enabled = !taken) {
                                            selectedSeats = if (picked) {
                                                selectedSeats - seatId
                                            } else if (selectedSeats.size < 6) {
                                                selectedSeats + seatId
                                            } else {
                                                selectedSeats
                                            }
                                        }
                                        .testTag("bus_seat_$seatId"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(seatId, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = fg)
                                }
                            }
                            if (rowSeats.size < 4) {
                                repeat(4 - rowSeats.size) { Spacer(modifier = Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }

        // Breakdown & Confirm
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Selected Seats: ${selectedSeats.ifEmpty { listOf("None") }.joinToString(", ")}",
                    style = MaterialTheme.typography.titleSmall
                )
                if (breakdown != null) {
                    Text(
                        "Base (${breakdown.seatCount} × ₹${breakdown.farePerSeatInr.toInt()}): ₹${breakdown.baseFareInr.toInt()} • GST: ₹${breakdown.gstInr}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMono
                    )
                    Text(
                        "Total Payable: ₹${breakdown.totalCustomerPayableInr.toInt()}",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = JetBrainsMono,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Approved Tariff (${schedule.fareSource.replace('_', ' ')} • ${schedule.authorityRef})",
                        style = MaterialTheme.typography.labelSmall,
                        color = GolocalSuccess
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = paymentMethod == "upi", onClick = { paymentMethod = "upi" }, label = { Text("UPI Ticket") })
                    FilterChip(selected = paymentMethod == "cash", onClick = { paymentMethod = "cash" }, label = { Text("Pay at Boarding") })
                }

                Button(
                    onClick = { onConfirmBooking(boardingStop, dropStop, selectedSeats, paymentMethod) },
                    enabled = selectedSeats.isNotEmpty() && !isBusy,
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("confirm_bus_booking_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    } else {
                        Text("Lock Seats & Issue QR Ticket")
                    }
                }
            }
        }
    }
}

@Composable
private fun SeatLegendDot(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
