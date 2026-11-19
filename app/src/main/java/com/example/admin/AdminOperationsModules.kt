package com.example.admin

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Booking
import com.example.data.model.CancellationAppeal
import com.example.data.model.FareConfig
import com.example.shared.BookingChatModalSheet
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AdminViewModel

@Composable
fun AdminBookingsModule(adminViewModel: AdminViewModel) {
    val bookings by adminViewModel.bookings.collectAsStateWithLifecycle()
    val complaints by adminViewModel.complaints.collectAsStateWithLifecycle()
    val emergencies by adminViewModel.emergencies.collectAsStateWithLifecycle()
    val sosEmergencyContacts by adminViewModel.sosEmergencyContacts.collectAsStateWithLifecycle()
    val ratings by adminViewModel.ratings.collectAsStateWithLifecycle()
    val cancellationAdvisories by adminViewModel.cancellationAdvisories.collectAsStateWithLifecycle()
    val cancellationAppeals by adminViewModel.cancellationAppeals.collectAsStateWithLifecycle()
    val inspectedMessages by adminViewModel.inspectedChatMessages.collectAsStateWithLifecycle()

    var subTab by remember { mutableIntStateOf(0) }
    var statusFilter by remember { mutableStateOf("all") }
    var inspectingChatBooking by remember { mutableStateOf<Booking?>(null) }
    var appealDecisionTarget by remember { mutableStateOf<Pair<CancellationAppeal, String>?>(null) }
    var commercialOtpOverrideTarget by remember { mutableStateOf<Booking?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Bookings, Disputes & Governance", style = MaterialTheme.typography.headlineSmall)
        ScrollableTabRow(selectedTabIndex = subTab, edgePadding = 0.dp) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Bookings (${bookings.size})") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("SOS & Disputes") })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text("Reviews (${ratings.size})") })
            Tab(selected = subTab == 3, onClick = { subTab = 3 }, text = { Text("Appeals & Abuse (${cancellationAppeals.count { it.status == "pending" }})") })
        }

        when (subTab) {
            0 -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("all" to "All", "searching" to "Searching", "in_progress" to "Live", "completed" to "Done", "cancelled" to "Cancel").forEach { (id, label) ->
                        FilterChip(selected = statusFilter == id, onClick = { statusFilter = id }, label = { Text(label) })
                    }
                }
                val filtered = if (statusFilter == "all") bookings else bookings.filter { it.status == statusFilter }
                if (filtered.isEmpty()) {
                    GolocalEmptyState(title = "No Bookings", message = "Bookings matching the selected filter will appear here.")
                } else {
                    filtered.forEach { b ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    GolocalStatusBadge(
                                        label = b.status.uppercase(),
                                        tone = when (b.status) {
                                            "completed" -> "success"
                                            "cancelled" -> "error"
                                            "searching" -> "warning"
                                            else -> "info"
                                        }
                                    )
                                    val fare = if (b.agreedFareInr > 0) b.agreedFareInr else b.estimatedFareInr
                                    Text("₹${fare.toInt()}", fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.primary)
                                }
                                Text("${b.pickupLabel} → ${b.destinationLabel}", style = MaterialTheme.typography.titleSmall)
                                Text("Customer: ${b.customerName} • Driver: ${b.assignedDriverName.ifBlank { "Unassigned" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Category: ${b.vehicleCategory.uppercase()} • Type: ${b.bookingType} • Payment: ${b.paymentMethod.uppercase()} (${b.paymentStatus})", style = MaterialTheme.typography.labelSmall)
                                if (b.assignedDriverId.isNotBlank()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(
                                            onClick = {
                                                adminViewModel.inspectBookingChat(b.id)
                                                inspectingChatBooking = b
                                            }
                                        ) {
                                            Text("Inspect Chat Archive")
                                        }
                                        if (!b.isTestRide && !b.testMode && b.status == "arrived") {
                                            OutlinedButton(
                                                onClick = { commercialOtpOverrideTarget = b },
                                                modifier = Modifier
                                                    .heightIn(min = 48.dp)
                                                    .testTag("admin_override_commercial_otp_${b.id}")
                                            ) {
                                                Text("Override OTP & Start")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                Text("Emergency SOS Alerts (${emergencies.size})", style = MaterialTheme.typography.titleMedium)
                if (emergencies.isEmpty()) {
                    Text("No active or past SOS events.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    emergencies.forEach { sos ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.5.dp, if (sos.status == "active") GolocalError else MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("SOS #${sos.id.take(8)}", style = MaterialTheme.typography.titleSmall, color = GolocalError)
                                    GolocalStatusBadge(label = sos.status.uppercase(), tone = if (sos.status == "active") "error" else "success")
                                }
                                Text("Booking: ${sos.bookingId.ifBlank { "Home SOS" }} • GPS: ${sos.lat}, ${sos.lng}", style = MaterialTheme.typography.bodySmall)
                                if (sos.emergencyContactRef.isNotBlank()) {
                                    Text("Ref: ${sos.emergencyContactRef}", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(sos.notes, style = MaterialTheme.typography.bodySmall)
                                val loadedContact = sosEmergencyContacts[sos.userId]
                                if (loadedContact != null) {
                                    Text(
                                        text = "SOS Contact (${loadedContact.relationshipNote}): ${loadedContact.emergencyContactNumber}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = JetBrainsMono,
                                        color = GolocalError
                                    )
                                } else {
                                    OutlinedButton(
                                        onClick = { adminViewModel.loadEmergencyContactForSos(sos.userId) }
                                    ) {
                                        Text("Load Private SOS Contact")
                                    }
                                }
                                if (sos.status != "resolved") {
                                    Button(
                                        onClick = { adminViewModel.resolveEmergency(sos.id, "resolved") },
                                        colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess)
                                    ) { Text("Mark SOS Resolved") }
                                }
                            }
                        }
                    }
                }

                Text("Customer & Rider Complaints (${complaints.size})", style = MaterialTheme.typography.titleMedium)
                if (complaints.isEmpty()) {
                    GolocalEmptyState(title = "No Complaints", message = "Filed disputes and trip complaints appear here.")
                } else {
                    complaints.forEach { c ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(c.category.uppercase(), style = MaterialTheme.typography.labelLarge)
                                    GolocalStatusBadge(label = c.status.uppercase(), tone = if (c.status == "resolved") "success" else "warning")
                                }
                                Text(c.description, style = MaterialTheme.typography.bodySmall)
                                if (c.status != "resolved") {
                                    OutlinedButton(onClick = { adminViewModel.resolveComplaint(c.id, "resolved", "Resolved by GoLocal Admin") }) {
                                        Text("Mark Resolved")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                if (ratings.isEmpty()) {
                    GolocalEmptyState(title = "No Ratings Yet", message = "Customer ratings and trip reviews appear here.")
                } else {
                    ratings.forEach { r ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("★ ${r.stars} / 5 • Booking #${r.bookingId.take(8)}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    "Behavior ${r.driverBehaviorRating}★ • Vehicle ${r.vehicleConditionRating}★ • Punctuality ${r.punctualityRating}★ • Safety ${r.safetyRating}★",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text("Driver: ${r.driverId} • Customer: ${r.customerId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (r.review.isNotBlank()) Text(r.review, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            3 -> {
                Text("Automated Cancellation Advisories (${cancellationAdvisories.size})", style = MaterialTheme.typography.titleMedium)
                if (cancellationAdvisories.isEmpty()) {
                    GolocalEmptyState(title = "No Abuse Advisories", message = "No customer or driver cancellation anomalies detected right now.")
                } else {
                    cancellationAdvisories.forEach { adv ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, GolocalError, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${adv.role.uppercase()} • ${adv.userId.take(10)}", style = MaterialTheme.typography.titleSmall)
                                    GolocalStatusBadge(label = "${adv.cancellationRatePct}% CANCELLED", tone = "error")
                                }
                                Text(adv.flagReason, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Text("Cancellation Appeals (${cancellationAppeals.size})", style = MaterialTheme.typography.titleMedium)
                if (cancellationAppeals.isEmpty()) {
                    GolocalEmptyState(title = "No Appeals Submitted", message = "Customer and driver cancellation appeals appear here.")
                } else {
                    cancellationAppeals.forEach { appeal ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${appeal.appellantRole.uppercase()} • Booking #${appeal.bookingId.take(8)}", style = MaterialTheme.typography.titleSmall)
                                    GolocalStatusBadge(
                                        label = appeal.status.uppercase(),
                                        tone = when (appeal.status) {
                                            "accepted", "approved" -> "success"
                                            "rejected" -> "error"
                                            else -> "warning"
                                        }
                                    )
                                }
                                Text(appeal.reason, style = MaterialTheme.typography.bodySmall)
                                if (appeal.adminNote.isNotBlank()) {
                                    Text("Decision: ${appeal.adminNote}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (appeal.status == "open" || appeal.status == "pending") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { appealDecisionTarget = appeal to "accepted" },
                                            colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess)
                                        ) { Text("Approve") }
                                        OutlinedButton(
                                            onClick = { appealDecisionTarget = appeal to "rejected" }
                                        ) { Text("Reject", color = GolocalError) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    inspectingChatBooking?.let { booking ->
        BookingChatModalSheet(
            booking = booking.copy(status = "completed"),
            viewerUid = "",
            viewerRole = "customer",
            messages = inspectedMessages,
            onSendMessage = {},
            onMarkRead = {},
            onDismiss = {
                adminViewModel.inspectBookingChat(null)
                inspectingChatBooking = null
            }
        )
    }

    appealDecisionTarget?.let { (appeal, decision) ->
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { appealDecisionTarget = null },
            title = { Text("${decision.replaceFirstChar { it.uppercase() }} Cancellation Appeal") },
            text = {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Resolution note *") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = note.trim().length >= 3,
                    onClick = {
                        adminViewModel.resolveCancellationAppeal(appeal.id, decision, note.trim())
                        appealDecisionTarget = null
                    }
                ) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { appealDecisionTarget = null }) { Text("Cancel") } }
        )
    }

    commercialOtpOverrideTarget?.let { target ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { commercialOtpOverrideTarget = null },
            title = { Text("Override Commercial Pickup OTP") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Audited admin action: marks booking #${target.id.take(8)} (${target.pickupLabel} → ${target.destinationLabel}) as in_progress without passenger OTP.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Mandatory Audit Reason (min 5 chars) *") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_commercial_otp_override_reason")
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = reason.trim().length >= 5,
                    onClick = {
                        adminViewModel.overrideCommercialOtpAndStartRide(target, reason.trim())
                        commercialOtpOverrideTarget = null
                    },
                    modifier = Modifier.testTag("admin_commercial_otp_override_confirm")
                ) { Text("Override & Start") }
            },
            dismissButton = { TextButton(onClick = { commercialOtpOverrideTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun AdminServicesModule(adminViewModel: AdminViewModel) {
    val fareConfigs by adminViewModel.fareConfigs.collectAsStateWithLifecycle()
    val serviceAreas by adminViewModel.serviceAreas.collectAsStateWithLifecycle()
    var subTab by remember { mutableIntStateOf(0) }
    var editingConfig by remember { mutableStateOf<FareConfig?>(null) }
    var showAddArea by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Fare, Commission & Service Areas", style = MaterialTheme.typography.headlineSmall)
        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Fares & Commission") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Service Areas (${serviceAreas.size})") })
        }

        if (subTab == 0) {
            OutlinedButton(
                onClick = { adminViewModel.initializeDefaultPricing() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Publish West Bengal Default Catalog to Firestore")
            }

            fareConfigs.forEach { cfg ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                ) {
                    Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${cfg.vehicleCategory.uppercase()} v${cfg.version}", style = MaterialTheme.typography.titleSmall)
                                GolocalStatusBadge(label = if (cfg.isEnabled) "ENABLED" else "LOCKED", tone = if (cfg.isEnabled) "success" else "warning")
                            }
                            Text("Base ₹${cfg.baseFareInr.toInt()} • ₹${cfg.perKmRateInr}/km • Min ₹${cfg.minimumFareInr.toInt()}", style = MaterialTheme.typography.bodySmall)
                            Text("Commission: ${cfg.commissionPercent}% • GST: ${cfg.gstPercent}% • Bid: ${(cfg.minBidFactor * 100).toInt()}%–${(cfg.maxBidFactor * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { editingConfig = cfg }) { Text("Edit") }
                    }
                }
            }
        } else {
            Button(onClick = { showAddArea = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add West Bengal Service Area")
            }
            if (serviceAreas.isEmpty()) {
                GolocalEmptyState(title = "No Custom Service Areas", message = "Add towns, stations, bus stands, airports, or rural blocks to govern service coverage.")
            } else {
                serviceAreas.forEach { area ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    ) {
                        Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(area.name, style = MaterialTheme.typography.titleSmall)
                                Text("${area.district} • ${area.zoneType.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = area.isActive,
                                onCheckedChange = { adminViewModel.toggleServiceArea(area.id, it) }
                            )
                        }
                    }
                }
            }
        }
    }

    editingConfig?.let { cfg ->
        var base by remember(cfg) { mutableStateOf(cfg.baseFareInr.toString()) }
        var perKm by remember(cfg) { mutableStateOf(cfg.perKmRateInr.toString()) }
        var comm by remember(cfg) { mutableStateOf(cfg.commissionPercent.toString()) }
        var gst by remember(cfg) { mutableStateOf(cfg.gstPercent.toString()) }
        var enabled by remember(cfg) { mutableStateOf(cfg.isEnabled) }

        AlertDialog(
            onDismissRequest = { editingConfig = null },
            title = { Text("Edit ${cfg.vehicleCategory.uppercase()} Pricing") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = base, onValueChange = { base = it }, label = { Text("Base Fare (₹)") }, singleLine = true)
                    OutlinedTextField(value = perKm, onValueChange = { perKm = it }, label = { Text("Per Km Rate (₹)") }, singleLine = true)
                    OutlinedTextField(value = comm, onValueChange = { comm = it }, label = { Text("Platform Commission (%)") }, singleLine = true)
                    OutlinedTextField(value = gst, onValueChange = { gst = it }, label = { Text("GST (%)") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Category Enabled")
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    adminViewModel.saveFareConfig(
                        cfg.copy(
                            baseFareInr = base.toDoubleOrNull() ?: cfg.baseFareInr,
                            perKmRateInr = perKm.toDoubleOrNull() ?: cfg.perKmRateInr,
                            commissionPercent = comm.toDoubleOrNull() ?: cfg.commissionPercent,
                            gstPercent = gst.toDoubleOrNull() ?: cfg.gstPercent,
                            isEnabled = enabled
                        )
                    )
                    editingConfig = null
                }) { Text("Publish New Version") }
            },
            dismissButton = { TextButton(onClick = { editingConfig = null }) { Text("Cancel") } }
        )
    }

    if (showAddArea) {
        var name by remember { mutableStateOf("") }
        var nameBn by remember { mutableStateOf("") }
        var district by remember { mutableStateOf("Hooghly") }
        var block by remember { mutableStateOf("") }
        var zoneType by remember { mutableStateOf("railway_station") }
        var parcelOk by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddArea = false },
            title = { Text("Add Service Area") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Area / Hub Name *") }, singleLine = true)
                    OutlinedTextField(value = nameBn, onValueChange = { nameBn = it }, label = { Text("Bengali Name (Optional)") }, singleLine = true)
                    OutlinedTextField(value = district, onValueChange = { district = it }, label = { Text("District *") }, singleLine = true)
                    OutlinedTextField(value = block, onValueChange = { block = it }, label = { Text("Block / Town") }, singleLine = true)
                    OutlinedTextField(value = zoneType, onValueChange = { zoneType = it }, label = { Text("Type (railway_station, bus_stand, airport, village)") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = parcelOk, onCheckedChange = { parcelOk = it })
                        Text("Parcel Delivery Approved")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    adminViewModel.addServiceArea(name, nameBn, district, block, zoneType, parcelOk)
                    showAddArea = false
                }) { Text("Save Area") }
            },
            dismissButton = { TextButton(onClick = { showAddArea = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun AdminPaymentsModule(adminViewModel: AdminViewModel) {
    val payments by adminViewModel.payments.collectAsStateWithLifecycle()
    val settlements by adminViewModel.settlements.collectAsStateWithLifecycle()
    val refunds by adminViewModel.refunds.collectAsStateWithLifecycle()
    var subTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Payments, Settlements & Refunds", style = MaterialTheme.typography.headlineSmall)
        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Ledger (${payments.size})") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Payouts (${settlements.size})") })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text("Refunds (${refunds.size})") })
        }

        when (subTab) {
            0 -> {
                if (payments.isEmpty()) {
                    GolocalEmptyState(title = "No Payment Records", message = "Completed trip transactions appear here with GST and commission splits.")
                } else {
                    payments.forEach { p ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Booking #${p.bookingId.take(8)}", style = MaterialTheme.typography.titleSmall)
                                    GolocalStatusBadge(label = p.status.uppercase(), tone = if (p.status == "settled_cash") "success" else "warning")
                                }
                                Text("Gross: ₹${p.grossFareInr} • GST: ₹${p.gstInr} • Comm: ₹${p.platformCommissionInr} • Net Rider: ₹${p.netDriverEarningsInr}", fontFamily = JetBrainsMono, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            1 -> {
                if (settlements.isEmpty()) {
                    GolocalEmptyState(title = "No Settlement Requests", message = "Rider withdrawal requests appear here for processing.")
                } else {
                    settlements.forEach { s ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${s.driverName} • ₹${s.amountInr.toInt()}", style = MaterialTheme.typography.titleSmall)
                                    GolocalStatusBadge(label = s.status.uppercase(), tone = if (s.status == "paid") "success" else "warning")
                                }
                                Text("UPI / Bank: ${s.upiOrBankRef}", style = MaterialTheme.typography.bodySmall)
                                if (s.status == "requested") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { adminViewModel.processSettlement(s.id, "paid") }, colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess)) { Text("Mark Paid") }
                                        OutlinedButton(onClick = { adminViewModel.processSettlement(s.id, "rejected") }) { Text("Reject", color = GolocalError) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                if (refunds.isEmpty()) {
                    GolocalEmptyState(title = "No Refund Requests", message = "Customer refund claims appear here for reconciliation.")
                } else {
                    refunds.forEach { r ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Booking #${r.bookingId.take(8)} • ₹${r.amountInr.toInt()}", style = MaterialTheme.typography.titleSmall)
                                    GolocalStatusBadge(label = r.status.uppercase(), tone = if (r.status == "processed") "success" else "warning")
                                }
                                Text("Reason: ${r.reason}", style = MaterialTheme.typography.bodySmall)
                                if (r.status == "requested") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { adminViewModel.processRefund(r.id, "processed") }) { Text("Approve & Process") }
                                        OutlinedButton(onClick = { adminViewModel.processRefund(r.id, "rejected") }) { Text("Reject", color = GolocalError) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
