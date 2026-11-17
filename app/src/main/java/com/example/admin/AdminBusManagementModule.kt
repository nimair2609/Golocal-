package com.example.admin

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BusOperator
import com.example.data.model.BusRoute
import com.example.data.model.BusSchedule
import com.example.domain.BusFareEngine
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AdminViewModel

@Composable
fun AdminBusManagementModule(adminViewModel: AdminViewModel) {
    val operators by adminViewModel.busOperators.collectAsStateWithLifecycle()
    val routes by adminViewModel.busRoutes.collectAsStateWithLifecycle()
    val schedules by adminViewModel.busSchedules.collectAsStateWithLifecycle()
    val busBookings by adminViewModel.busBookings.collectAsStateWithLifecycle()

    var subTab by remember { mutableIntStateOf(0) }
    var showAddOperator by remember { mutableStateOf(false) }
    var showAddRoute by remember { mutableStateOf(false) }
    var showAddSchedule by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Private Bus Operations", style = MaterialTheme.typography.headlineSmall)
        ScrollableTabRow(selectedTabIndex = subTab, edgePadding = 0.dp) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Operators (${operators.size})") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Routes (${routes.size})") })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text("Schedules (${schedules.size})") })
            Tab(selected = subTab == 3, onClick = { subTab = 3 }, text = { Text("Bookings (${busBookings.size})") })
        }

        when (subTab) {
            0 -> {
                Button(
                    onClick = { showAddOperator = true },
                    modifier = Modifier.fillMaxWidth().testTag("admin_add_bus_operator")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Register Private Bus Operator")
                }
                if (operators.isEmpty()) {
                    GolocalEmptyState(
                        title = "No Bus Operators Yet",
                        message = "Register and KYC-verify licensed West Bengal private bus operators here."
                    )
                } else {
                    operators.forEach { op ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(op.operatorName, style = MaterialTheme.typography.titleSmall)
                                    GolocalStatusBadge(
                                        label = op.verificationStatus.uppercase(),
                                        tone = when (op.verificationStatus) {
                                            "verified" -> "success"
                                            "pending" -> "warning"
                                            else -> "error"
                                        }
                                    )
                                }
                                Text("District: ${op.district} • Phone: ${op.contactPhone} • Permit: ${op.permitNumber}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Ins: ${op.insuranceNumber} • Fitness: ${op.fitnessCertificateNumber} • PUC: ${op.pucNumber}", style = MaterialTheme.typography.labelSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (op.verificationStatus != "verified") {
                                        Button(
                                            onClick = { adminViewModel.saveBusOperator(op.copy(verificationStatus = "verified")) },
                                            colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess)
                                        ) { Text("Verify Operator") }
                                    } else {
                                        OutlinedButton(
                                            onClick = { adminViewModel.saveBusOperator(op.copy(verificationStatus = "suspended", rejectionReason = "Suspended by Admin")) }
                                        ) { Text("Suspend", color = GolocalError) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                Button(
                    onClick = { showAddRoute = true },
                    enabled = operators.any { it.verificationStatus == "verified" },
                    modifier = Modifier.fillMaxWidth().testTag("admin_add_bus_route")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Configure Tariff-Gated Bus Route")
                }
                if (operators.none { it.verificationStatus == "verified" }) {
                    Text(
                        "Verify at least one bus operator before configuring routes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (routes.isEmpty()) {
                    GolocalEmptyState(
                        title = "No Bus Routes Configured",
                        message = "Routes require an official fareSource and effectiveDateLabel before activation."
                    )
                } else {
                    routes.forEach { route ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(route.routeName, style = MaterialTheme.typography.titleSmall)
                                    GolocalStatusBadge(
                                        label = route.status.uppercase(),
                                        tone = when (route.status) {
                                            "active" -> "success"
                                            "pending_pricing" -> "warning"
                                            else -> "error"
                                        }
                                    )
                                }
                                Text("${route.originStop} -> ${route.destinationStop} (${route.distanceKm} km)", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Operator: ${route.operatorName} • Base ₹${route.baseSeatFareInr.toInt()} + ₹${route.perKmSeatRateInr}/km",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = JetBrainsMono
                                )
                                Text(
                                    "Tariff: ${route.fareSource.ifBlank { "Pending" }} • Effective: ${route.effectiveDateLabel.ifBlank { "N/A" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            2 -> {
                val activeRoutes = remember(routes) { routes.filter { it.status == "active" } }
                Button(
                    onClick = { showAddSchedule = true },
                    enabled = activeRoutes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag("admin_add_bus_schedule")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Publish Bus Departure Schedule")
                }
                if (activeRoutes.isEmpty()) {
                    Text(
                        "Schedules can only be created from active, officially priced bus routes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (schedules.isEmpty()) {
                    GolocalEmptyState(title = "No Bus Schedules", message = "Publish departures on active routes for customer seat booking.")
                } else {
                    schedules.forEach { s ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${s.originStop} -> ${s.destinationStop}", style = MaterialTheme.typography.titleSmall)
                                    Text("₹${s.farePerSeatInr.toInt()}/seat", fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.primary)
                                }
                                Text("${s.operatorName} • ${s.busLabel} (${s.travelDateLabel} ${s.departureTimeLabel})", style = MaterialTheme.typography.bodySmall)
                                Text("Booked: ${s.bookedSeats.size}/${s.totalSeats} seats • Status: ${s.status.uppercase()}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            3 -> {
                if (busBookings.isEmpty()) {
                    GolocalEmptyState(title = "No Bus Bookings Yet", message = "Customer reserved bus seats & QR tickets appear here.")
                } else {
                    busBookings.forEach { b ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(b.bookingRef, style = MaterialTheme.typography.titleSmall, fontFamily = JetBrainsMono)
                                    GolocalStatusBadge(
                                        label = b.status.uppercase(),
                                        tone = when (b.status) {
                                            "confirmed", "boarded" -> "success"
                                            "refunded" -> "info"
                                            else -> "error"
                                        }
                                    )
                                }
                                Text("${b.routeLabel} • ${b.customerName} • Seats: ${b.seatNumbers.joinToString()}", style = MaterialTheme.typography.bodySmall)
                                Text("Total: ₹${b.totalFareInr.toInt()} (${b.paymentStatus})", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMono)
                                if (b.status == "cancelled") {
                                    OutlinedButton(onClick = { adminViewModel.releaseCancelledBusSeats(b) }) {
                                        Text("Release Seats & Mark Refunded")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddOperator) {
        var name by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var district by remember { mutableStateOf("Hooghly") }
        var permit by remember { mutableStateOf("") }
        var insurance by remember { mutableStateOf("") }
        var fitness by remember { mutableStateOf("") }
        var puc by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddOperator = false },
            title = { Text("Register Bus Operator") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Operator Name *") }, singleLine = true)
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Contact Phone *") }, singleLine = true)
                    OutlinedTextField(value = district, onValueChange = { district = it }, label = { Text("Base District *") }, singleLine = true)
                    OutlinedTextField(value = permit, onValueChange = { permit = it }, label = { Text("Stage Carriage Permit No. *") }, singleLine = true)
                    OutlinedTextField(value = insurance, onValueChange = { insurance = it }, label = { Text("Commercial Insurance No.") }, singleLine = true)
                    OutlinedTextField(value = fitness, onValueChange = { fitness = it }, label = { Text("Fitness Certificate No.") }, singleLine = true)
                    OutlinedTextField(value = puc, onValueChange = { puc = it }, label = { Text("PUC No.") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank() && permit.isNotBlank(),
                    onClick = {
                        adminViewModel.saveBusOperator(
                            BusOperator(
                                operatorName = name,
                                contactPhone = phone,
                                district = district,
                                permitNumber = permit,
                                insuranceNumber = insurance,
                                fitnessCertificateNumber = fitness,
                                pucNumber = puc,
                                verificationStatus = "verified"
                            )
                        )
                        showAddOperator = false
                    }
                ) { Text("Save Operator") }
            },
            dismissButton = { TextButton(onClick = { showAddOperator = false }) { Text("Cancel") } }
        )
    }

    if (showAddRoute) {
        val verifiedOps = remember(operators) { operators.filter { it.verificationStatus == "verified" } }
        var selectedOp by remember { mutableStateOf(verifiedOps.firstOrNull()) }
        var routeName by remember { mutableStateOf("") }
        var origin by remember { mutableStateOf("") }
        var dest by remember { mutableStateOf("") }
        var distance by remember { mutableStateOf("45") }
        var fareSource by remember { mutableStateOf("WB Transport Approved Tariff") }
        var effectiveDate by remember { mutableStateOf("2026-01-01") }
        var baseFare by remember { mutableStateOf("40") }
        var perKm by remember { mutableStateOf("1.5") }
        var activateNow by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showAddRoute = false },
            title = { Text("Configure Bus Route & Tariff") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Operator:", style = MaterialTheme.typography.labelMedium)
                    verifiedOps.forEach { op ->
                        FilterChip(
                            selected = selectedOp?.id == op.id,
                            onClick = { selectedOp = op },
                            label = { Text(op.operatorName) }
                        )
                    }
                    OutlinedTextField(value = routeName, onValueChange = { routeName = it }, label = { Text("Route Name *") }, singleLine = true)
                    OutlinedTextField(value = origin, onValueChange = { origin = it }, label = { Text("Origin Stop *") }, singleLine = true)
                    OutlinedTextField(value = dest, onValueChange = { dest = it }, label = { Text("Destination Stop *") }, singleLine = true)
                    OutlinedTextField(value = distance, onValueChange = { distance = it }, label = { Text("Distance (km) *") }, singleLine = true)
                    OutlinedTextField(value = fareSource, onValueChange = { fareSource = it }, label = { Text("Official Fare Source *") }, singleLine = true)
                    OutlinedTextField(value = effectiveDate, onValueChange = { effectiveDate = it }, label = { Text("Effective Date *") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = baseFare, onValueChange = { baseFare = it }, label = { Text("Base ₹") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = perKm, onValueChange = { perKm = it }, label = { Text("₹ / km") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Activate Immediately")
                        Switch(checked = activateNow, onCheckedChange = { activateNow = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedOp != null && routeName.isNotBlank() && origin.isNotBlank() && dest.isNotBlank(),
                    onClick = {
                        val op = selectedOp!!
                        val draftRoute = BusRoute(
                            operatorId = op.id,
                            operatorName = op.operatorName,
                            routeName = routeName,
                            originStop = origin,
                            destinationStop = dest,
                            distanceKm = distance.toDoubleOrNull() ?: 0.0,
                            fareSource = fareSource,
                            effectiveDateLabel = effectiveDate,
                            baseSeatFareInr = baseFare.toDoubleOrNull() ?: 0.0,
                            perKmSeatRateInr = perKm.toDoubleOrNull() ?: 0.0
                        )
                        adminViewModel.saveBusRoute(draftRoute, activateNow)
                        showAddRoute = false
                    }
                ) { Text("Save Route") }
            },
            dismissButton = { TextButton(onClick = { showAddRoute = false }) { Text("Cancel") } }
        )
    }

    if (showAddSchedule) {
        val activeRoutes = remember(routes) { routes.filter { it.status == "active" && BusFareEngine.isRoutePricingConfigured(it) } }
        var selectedRoute by remember { mutableStateOf(activeRoutes.firstOrNull()) }
        var busLabel by remember { mutableStateOf("") }
        var travelDate by remember { mutableStateOf("Today") }
        var depTime by remember { mutableStateOf("08:00 AM") }
        var arrTime by remember { mutableStateOf("10:30 AM") }
        var totalSeats by remember { mutableStateOf("40") }

        AlertDialog(
            onDismissRequest = { showAddSchedule = false },
            title = { Text("Publish Bus Schedule") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeRoutes.forEach { rt ->
                        FilterChip(
                            selected = selectedRoute?.id == rt.id,
                            onClick = { selectedRoute = rt },
                            label = { Text(rt.routeName) }
                        )
                    }
                    OutlinedTextField(value = busLabel, onValueChange = { busLabel = it }, label = { Text("Bus Name / Plate Label") }, singleLine = true)
                    OutlinedTextField(value = travelDate, onValueChange = { travelDate = it }, label = { Text("Travel Date") }, singleLine = true)
                    OutlinedTextField(value = depTime, onValueChange = { depTime = it }, label = { Text("Departure Time") }, singleLine = true)
                    OutlinedTextField(value = arrTime, onValueChange = { arrTime = it }, label = { Text("Arrival Time") }, singleLine = true)
                    OutlinedTextField(value = totalSeats, onValueChange = { totalSeats = it.filter { c -> c.isDigit() } }, label = { Text("Total Seats (10-80)") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedRoute != null,
                    onClick = {
                        val rt = selectedRoute!!
                        adminViewModel.createBusSchedule(
                            schedule = BusSchedule(
                                busLabel = busLabel,
                                travelDateLabel = travelDate,
                                departureTimeLabel = depTime,
                                arrivalTimeLabel = arrTime,
                                totalSeats = totalSeats.toLongOrNull() ?: 40L
                            ),
                            approvedRoute = rt
                        )
                        showAddSchedule = false
                    }
                ) { Text("Publish Schedule") }
            },
            dismissButton = { TextButton(onClick = { showAddSchedule = false }) { Text("Cancel") } }
        )
    }
}
