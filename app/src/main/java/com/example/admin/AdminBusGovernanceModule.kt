package com.example.admin

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.BusBookingViewModel

@Composable
fun AdminBusGovernanceModule(busViewModel: BusBookingViewModel) {
    val operators by busViewModel.allOperators.collectAsStateWithLifecycle()
    val vehicles by busViewModel.busVehicles.collectAsStateWithLifecycle()
    val routes by busViewModel.busRoutes.collectAsStateWithLifecycle()
    val schedules by busViewModel.busSchedules.collectAsStateWithLifecycle()

    var subTab by remember { mutableIntStateOf(0) }
    var rejectTarget by remember { mutableStateOf<Triple<String, String, String>?>(null) } // kind("operator"|"vehicle"|"route"), id, title
    var previewPhotoUrl by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Private Bus & Fare Governance", style = MaterialTheme.typography.headlineSmall)
        ScrollableTabRow(selectedTabIndex = subTab, edgePadding = 0.dp) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Operators (${operators.size})") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Buses (${vehicles.size})") })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text("Route Fares (${routes.size})") })
            Tab(selected = subTab == 3, onClick = { subTab = 3 }, text = { Text("Schedules (${schedules.size})") })
        }

        when (subTab) {
            0 -> {
                if (operators.isEmpty()) {
                    GolocalEmptyState("No Bus Operators Yet", "Private bus operator KYC applications appear here.")
                } else {
                    operators.forEach { op ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(op.companyName, style = MaterialTheme.typography.titleMedium)
                                    GolocalStatusBadge(
                                        label = op.verificationStatus.uppercase(),
                                        tone = when (op.verificationStatus) {
                                            "verified" -> "success"
                                            "pending" -> "warning"
                                            else -> "error"
                                        }
                                    )
                                }
                                Text("Permit: ${op.permitNumber} • Licence: ${op.operatorLicenseNumber}", style = MaterialTheme.typography.bodySmall)
                                Text("Phone: ${op.contactPhone} • District: ${op.district}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val url = op.documents.firstOrNull { it.storageUrl.isNotBlank() }?.storageUrl
                                if (url != null) {
                                    TextButton(onClick = { previewPhotoUrl = url }) { Text("View Permit Document Photo") }
                                }
                                if (op.verificationStatus != "verified") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { busViewModel.adminVerifyOperator(op.operatorId, "verified", "Approved by Admin") },
                                            colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Verify Operator") }
                                        OutlinedButton(
                                            onClick = { rejectTarget = Triple("operator", op.operatorId, op.companyName) },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Reject", color = GolocalError) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                if (vehicles.isEmpty()) {
                    GolocalEmptyState("No Bus Vehicles Yet", "Bus fleet RC and fitness submissions appear here.")
                } else {
                    vehicles.forEach { v ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("${v.busName} (${v.registrationNumber})", style = MaterialTheme.typography.titleSmall)
                                    GolocalStatusBadge(
                                        label = v.verificationStatus.uppercase(),
                                        tone = when (v.verificationStatus) {
                                            "verified" -> "success"
                                            "pending" -> "warning"
                                            else -> "error"
                                        }
                                    )
                                }
                                Text("${v.busType} • ${v.totalSeats} seats", style = MaterialTheme.typography.bodySmall)
                                val url = v.documents.firstOrNull { it.storageUrl.isNotBlank() }?.storageUrl
                                if (url != null) {
                                    TextButton(onClick = { previewPhotoUrl = url }) { Text("View Bus RC Photo") }
                                }
                                if (v.verificationStatus != "verified") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { busViewModel.adminVerifyVehicle(v.id, "verified", "Bus KYC verified") },
                                            colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Verify Bus") }
                                        OutlinedButton(
                                            onClick = { rejectTarget = Triple("vehicle", v.id, v.busName) },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Reject", color = GolocalError) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                if (routes.isEmpty()) {
                    GolocalEmptyState("No Bus Route Tariffs Submitted", "Operator-submitted bus fares stay pending_fare_approval until Admin verifies source and tariff authority.")
                } else {
                    routes.forEach { r ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.routeName, style = MaterialTheme.typography.titleSmall)
                                    GolocalStatusBadge(
                                        label = r.fareApprovalStatus.replace('_', ' ').uppercase(),
                                        tone = when (r.fareApprovalStatus) {
                                            "approved" -> "success"
                                            "rejected" -> "error"
                                            else -> "warning"
                                        }
                                    )
                                }
                                Text("Fare: ₹${r.farePerSeatInr.toInt()}/seat • ${r.distanceKm} km • GST ${r.gstPercent}% • Comm ${r.commissionPercent}%", fontFamily = JetBrainsMono, style = MaterialTheme.typography.bodySmall)
                                Text("Source: ${r.fareSource} • Ref: ${r.authorityRef} • Effective: ${r.effectiveDateIso}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                if (r.fareRejectionReason.isNotBlank()) {
                                    Text("Reason: ${r.fareRejectionReason}", style = MaterialTheme.typography.bodySmall, color = GolocalError)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (r.fareApprovalStatus != "approved") {
                                        Button(
                                            onClick = { busViewModel.adminReviewRouteFare(r.id, approve = true, reason = "Tariff verified against ${r.fareSource}") },
                                            colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                                            modifier = Modifier.weight(1f).testTag("approve_bus_fare_${r.id}")
                                        ) { Text("Approve Tariff") }
                                    }
                                    OutlinedButton(
                                        onClick = { rejectTarget = Triple("route", r.id, r.routeName) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Reject", color = GolocalError) }
                                }
                            }
                        }
                    }
                }
            }
            3 -> {
                if (schedules.isEmpty()) {
                    GolocalEmptyState("No Schedules Created", "Bus departures published by operators appear here.")
                } else {
                    schedules.forEach { s ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(s.routeLabel, style = MaterialTheme.typography.titleSmall)
                                    GolocalStatusBadge(
                                        label = s.fareApprovalStatus.replace('_', ' ').uppercase(),
                                        tone = if (s.fareApprovalStatus == "approved") "success" else "warning"
                                    )
                                }
                                Text("${s.busName} • ${s.departureDateIso} ${s.departureTimeLabel} • Booked ${s.bookedSeatIds.size}/${s.totalSeats}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    rejectTarget?.let { (kind, id, title) ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejectTarget = null },
            title = { Text("Reject $title") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Mandatory rejection reason (min 5 chars) *") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = reason.trim().length >= 5,
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    onClick = {
                        when (kind) {
                            "operator" -> busViewModel.adminVerifyOperator(id, "rejected", reason)
                            "vehicle" -> busViewModel.adminVerifyVehicle(id, "rejected", reason)
                            "route" -> busViewModel.adminReviewRouteFare(id, approve = false, reason = reason)
                        }
                        rejectTarget = null
                    }
                ) { Text("Reject") }
            },
            dismissButton = { TextButton(onClick = { rejectTarget = null }) { Text("Cancel") } }
        )
    }

    previewPhotoUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { previewPhotoUrl = null },
            title = { Text("Document Image Preview") },
            text = {
                AsyncImage(
                    model = url,
                    contentDescription = "Uploaded bus document",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp)
                )
            },
            confirmButton = {
                Button(onClick = { previewPhotoUrl = null }) { Text("Close") }
            }
        )
    }
}
