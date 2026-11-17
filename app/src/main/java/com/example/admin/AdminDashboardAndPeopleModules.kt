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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.domain.DocumentExpiryStatus
import com.example.domain.DocumentVerificationEngine
import com.example.ui.components.BookingsOverviewChart
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalMetricCard
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AdminViewModel
import com.example.viewmodel.GovernanceViewModel

@Composable
fun AdminDashboardModule(adminViewModel: AdminViewModel) {
    val metrics by adminViewModel.metrics.collectAsStateWithLifecycle()
    val bookings by adminViewModel.bookings.collectAsStateWithLifecycle()
    val auditLogs by adminViewModel.auditLogs.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Operations Dashboard", style = MaterialTheme.typography.headlineSmall)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GolocalMetricCard(
                title = "Total Customers",
                value = metrics.totalCustomers.toString(),
                subtitle = "Registered users",
                icon = Icons.Filled.People,
                modifier = Modifier.weight(1f)
            )
            GolocalMetricCard(
                title = "Registered Riders",
                value = metrics.totalDrivers.toString(),
                subtitle = "${metrics.verifiedDrivers} verified • ${metrics.pendingDrivers} pending",
                icon = Icons.Filled.LocalTaxi,
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GolocalMetricCard(
                title = "Bookings",
                value = "${metrics.activeRides + metrics.completedRides + metrics.cancelledRides}",
                subtitle = "${metrics.activeRides} active • ${metrics.completedRides} done • ${metrics.cancelledRides} cancel",
                icon = Icons.Filled.DirectionsCar,
                modifier = Modifier.weight(1f)
            )
            GolocalMetricCard(
                title = "Platform Revenue",
                value = "₹${metrics.totalPlatformCommissionInr.toInt()}",
                subtitle = "Gross ₹${metrics.grossBookingValueInr.toInt()}",
                icon = Icons.Filled.CurrencyRupee,
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GolocalMetricCard(
                title = "Online Riders",
                value = metrics.onlineDrivers.toString(),
                subtitle = "Live across WB",
                icon = Icons.Filled.Sensors,
                modifier = Modifier.weight(1f)
            )
            GolocalMetricCard(
                title = "Complaints & SOS",
                value = "${metrics.openComplaintsCount} / ${metrics.activeEmergenciesCount}",
                subtitle = "${metrics.pendingSettlementsCount} payouts queued",
                icon = Icons.Filled.ReportProblem,
                modifier = Modifier.weight(1f)
            )
        }

        BookingsOverviewChart(bookings = bookings)

        Text("Recent Platform Activities", style = MaterialTheme.typography.titleMedium)
        if (auditLogs.isEmpty() && bookings.isEmpty()) {
            GolocalEmptyState(
                title = "No Platform Activity Yet",
                message = "Real bookings, KYC approvals, fare updates, and audit events appear here automatically."
            )
        } else {
            auditLogs.take(5).forEach { log ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(log.action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(log.targetCollection, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(log.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun AdminCustomersModule(adminViewModel: AdminViewModel) {
    val users by adminViewModel.users.collectAsStateWithLifecycle()
    val supportRequests by adminViewModel.supportRequests.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var subTab by remember { mutableIntStateOf(0) }

    val filteredUsers = remember(users, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) users
        else users.filter {
            it.fullName.lowercase().contains(q) ||
                it.email.lowercase().contains(q) ||
                it.effectiveMobileNumber().contains(q)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Customer Management", style = MaterialTheme.typography.headlineSmall)
        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Users (${users.size})") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Support (${supportRequests.size})") })
        }

        if (subTab == 0) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search name, email, or phone") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (filteredUsers.isEmpty()) {
                GolocalEmptyState(title = "No Customers Found", message = "Registered GoLocal users appear here.")
            } else {
                filteredUsers.forEach { u ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(u.fullName.ifBlank { "GoLocal User" }, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${u.effectiveMobileNumber().ifBlank { "No phone" }} • Invoice: ${u.email.ifBlank { "No email" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                GolocalStatusBadge(
                                    label = u.accountStatus.uppercase(),
                                    tone = if (u.accountStatus == "active") "success" else "error"
                                )
                            }
                            Text("UID: ${u.userId} • Lang: ${u.preferredLanguage.uppercase()}", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (u.accountStatus == "active") {
                                    OutlinedButton(onClick = { adminViewModel.setUserAccountStatus(u.userId, "restricted") }) {
                                        Text("Restrict Account", color = GolocalError)
                                    }
                                } else {
                                    Button(onClick = { adminViewModel.setUserAccountStatus(u.userId, "active") }) {
                                        Text("Activate Account")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (supportRequests.isEmpty()) {
                GolocalEmptyState(title = "No Support Tickets", message = "Customer and rider help or deletion requests appear here.")
            } else {
                supportRequests.forEach { req ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(req.requestType.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                GolocalStatusBadge(label = req.status.uppercase(), tone = "warning")
                            }
                            Text("User UID: ${req.userId}", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMono)
                            Text(req.details, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminRidersModule(
    adminViewModel: AdminViewModel,
    governanceViewModel: GovernanceViewModel? = null
) {
    val drivers by adminViewModel.drivers.collectAsStateWithLifecycle()
    var subTab by remember { mutableIntStateOf(0) }
    var rejectTargetId by remember { mutableStateOf<String?>(null) }
    var suspendTargetId by remember { mutableStateOf<String?>(null) }
    var previewPhotoUrl by remember { mutableStateOf<String?>(null) }

    val filtered = remember(drivers, subTab) {
        when (subTab) {
            1 -> drivers.filter { it.verificationStatus == "pending" }
            2 -> drivers.filter { it.verificationStatus == "verified" }
            3 -> drivers.filter { it.verificationStatus in setOf("rejected", "suspended") }
            4 -> drivers.filter { it.expiryAlertLevel in setOf("expiring_soon", "expired") }
            else -> drivers
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Rider & Document Verification", style = MaterialTheme.typography.headlineSmall)
        ScrollableTabRow(selectedTabIndex = subTab, edgePadding = 0.dp) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("All (${drivers.size})") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Pending KYC (${drivers.count { it.verificationStatus == "pending" }})") })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text("Verified (${drivers.count { it.verificationStatus == "verified" }})") })
            Tab(selected = subTab == 3, onClick = { subTab = 3 }, text = { Text("Blocked/Rejected") })
            Tab(selected = subTab == 4, onClick = { subTab = 4 }, text = { Text("Expiring (${drivers.count { it.expiryAlertLevel in setOf("expiring_soon", "expired") }})") })
        }

        if (filtered.isEmpty()) {
            GolocalEmptyState(title = "No Riders in Category", message = "Driver partner KYC submissions appear here for verification.")
        } else {
            filtered.forEach { d ->
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(d.legalName, style = MaterialTheme.typography.titleMedium)
                            GolocalStatusBadge(
                                label = d.verificationStatus.uppercase(),
                                tone = when (d.verificationStatus) {
                                    "verified" -> "success"
                                    "pending" -> "warning"
                                    else -> "error"
                                }
                            )
                        }
                        Text("${d.vehicleCategory.uppercase()} • ${d.vehicleModel} • Reg: ${d.vehicleRegNumber}", style = MaterialTheme.typography.bodyMedium)
                        Text("Phone: ${d.phone} • District: ${d.district} ${d.block}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (d.earliestExpiryIso.isNotBlank()) {
                            Text(
                                "Earliest Expiry: ${d.earliestExpiryIso} (${d.expiryAlertLevel.replace('_', ' ').uppercase()})",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (d.expiryAlertLevel == "valid") GolocalSuccess else GolocalError
                            )
                        }

                        if (d.documents.isNotEmpty()) {
                            d.documents.forEach { doc ->
                                val expStatus = DocumentVerificationEngine.evaluateExpiry(doc.expiryDateIso)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${doc.label.ifBlank { doc.docType }}: ${doc.documentNumber} (Exp ${doc.expiryDateIso.ifBlank { "N/A" }})", style = MaterialTheme.typography.bodySmall)
                                        if (doc.ocrMismatchWarning.isNotBlank()) {
                                            Text("OCR Notice: ${doc.ocrMismatchWarning}", style = MaterialTheme.typography.labelSmall, color = GolocalError)
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        GolocalStatusBadge(
                                            label = expStatus.name.replace('_', ' '),
                                            tone = when (expStatus) {
                                                DocumentExpiryStatus.VALID -> "success"
                                                DocumentExpiryStatus.EXPIRING_SOON -> "warning"
                                                else -> "error"
                                            }
                                        )
                                        if (doc.storageUrl.isNotBlank()) {
                                            TextButton(onClick = { previewPhotoUrl = doc.storageUrl }) { Text("View") }
                                        }
                                    }
                                }
                            }
                        } else {
                            Text("DL: ${d.drivingLicenceNumber} • RC: ${d.rcNumber.ifBlank { "N/A" }} • Ins: ${d.insuranceNumber.ifBlank { "N/A" }}", style = MaterialTheme.typography.bodySmall)
                            if (d.documentPhotoUri.isNotBlank()) {
                                TextButton(onClick = { previewPhotoUrl = d.documentPhotoUri }) { Text("View Attached Document") }
                            }
                        }

                        if (d.rejectionReason.isNotBlank()) {
                            Text("Decision Note: ${d.rejectionReason}", style = MaterialTheme.typography.bodySmall, color = GolocalError)
                        }
                        if (d.verificationHistory.isNotEmpty()) {
                            Text(
                                "Audit Log (${d.verificationHistory.size}): Last ${d.verificationHistory.last().action.uppercase()} — ${d.verificationHistory.last().reason}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (d.verificationStatus != "verified") {
                                Button(
                                    onClick = {
                                        if (governanceViewModel != null) {
                                            governanceViewModel.adminReviewDriverKyc(d.driverId, "verified", "All required documents verified")
                                        } else {
                                            adminViewModel.verifyDriver(d.driverId, "verified")
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                                    modifier = Modifier.weight(1f).testTag("approve_driver_${d.driverId}")
                                ) {
                                    Text("Approve KYC")
                                }
                            }
                            if (d.verificationStatus == "pending") {
                                OutlinedButton(
                                    onClick = { rejectTargetId = d.driverId },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Reject", color = GolocalError)
                                }
                            } else if (d.verificationStatus == "verified") {
                                OutlinedButton(
                                    onClick = { suspendTargetId = d.driverId },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Suspend", color = GolocalError)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    rejectTargetId?.let { id ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejectTargetId = null },
            title = { Text("Reject Rider Verification") },
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
                        if (governanceViewModel != null) {
                            governanceViewModel.adminReviewDriverKyc(id, "rejected", reason)
                        } else {
                            adminViewModel.verifyDriver(id, "rejected", reason)
                        }
                        rejectTargetId = null
                    }
                ) { Text("Reject") }
            },
            dismissButton = { TextButton(onClick = { rejectTargetId = null }) { Text("Cancel") } }
        )
    }

    suspendTargetId?.let { id ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { suspendTargetId = null },
            title = { Text("Suspend Rider Account") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Mandatory suspension reason (min 5 chars) *") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = reason.trim().length >= 5,
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    onClick = {
                        if (governanceViewModel != null) {
                            governanceViewModel.adminReviewDriverKyc(id, "suspended", reason)
                        } else {
                            adminViewModel.verifyDriver(id, "suspended", reason)
                        }
                        suspendTargetId = null
                    }
                ) { Text("Suspend Rider") }
            },
            dismissButton = { TextButton(onClick = { suspendTargetId = null }) { Text("Cancel") } }
        )
    }

    previewPhotoUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { previewPhotoUrl = null },
            title = { Text("KYC Document Preview") },
            text = {
                AsyncImage(
                    model = url,
                    contentDescription = "Driver KYC Document",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp)
                )
            },
            confirmButton = {
                Button(onClick = { previewPhotoUrl = null }) { Text("Close") }
            }
        )
    }
}
