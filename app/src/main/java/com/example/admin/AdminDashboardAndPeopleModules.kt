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
import com.example.data.model.DriverProfile
import com.example.domain.ExpiryStatus
import com.example.domain.RequiredDocumentsCatalog
import com.example.ui.components.BookingsOverviewChart
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalMetricCard
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AdminViewModel
import java.util.concurrent.TimeUnit

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
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (u.isTestCustomer) {
                                        GolocalStatusBadge(label = "TEST CUSTOMER", tone = "warning")
                                    }
                                    GolocalStatusBadge(
                                        label = u.accountStatus.uppercase(),
                                        tone = if (u.accountStatus == "active") "success" else "error"
                                    )
                                }
                            }
                            Text("UID: ${u.userId} • Lang: ${u.preferredLanguage.uppercase()}", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (u.accountStatus == "active") {
                                    OutlinedButton(
                                        onClick = { adminViewModel.setUserAccountStatus(u.userId, "restricted") },
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Text("Restrict", color = GolocalError)
                                    }
                                } else {
                                    Button(
                                        onClick = { adminViewModel.setUserAccountStatus(u.userId, "active") },
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Text("Activate")
                                    }
                                }
                                OutlinedButton(
                                    onClick = { adminViewModel.setUserTestCustomer(u.userId, !u.isTestCustomer) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("toggle_test_customer_${u.userId}")
                                ) {
                                    Text(
                                        text = if (u.isTestCustomer) "Revoke Test Customer" else "Authorize Test Customer",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
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
fun AdminRidersModule(adminViewModel: AdminViewModel) {
    val drivers by adminViewModel.drivers.collectAsStateWithLifecycle()
    val allHistory by adminViewModel.verificationHistory.collectAsStateWithLifecycle()
    var subTab by remember { mutableIntStateOf(0) }
    var reasonActionTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // driverId to "rejected"/"suspended"
    var inspectingDriver by remember { mutableStateOf<DriverProfile?>(null) }
    val todayEpochDay = remember { TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis()) }

    val filtered = remember(drivers, subTab) {
        when (subTab) {
            1 -> drivers.filter { it.verificationStatus == "pending" }
            2 -> drivers.filter { it.verificationStatus == "verified" }
            3 -> drivers.filter { it.verificationStatus in setOf("rejected", "suspended") }
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
        Text("Rider & Document Verification (KYC v2)", style = MaterialTheme.typography.headlineSmall)
        ScrollableTabRow(selectedTabIndex = subTab, edgePadding = 0.dp) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("All (${drivers.size})") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Pending KYC (${drivers.count { it.verificationStatus == "pending" }})") })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text("Verified (${drivers.count { it.verificationStatus == "verified" }})") })
            Tab(selected = subTab == 3, onClick = { subTab = 3 }, text = { Text("Blocked/Rejected") })
        }

        if (filtered.isEmpty()) {
            GolocalEmptyState(title = "No Riders in Category", message = "Driver partner KYC submissions appear here for verification.")
        } else {
            filtered.forEach { d ->
                val compliance = remember(d) {
                    RequiredDocumentsCatalog.assessDriverCompliance(d, todayEpochDay)
                }
                val expiredCount = compliance.count { it.status == ExpiryStatus.EXPIRED }
                val expiringSoonCount = compliance.count { it.status == ExpiryStatus.EXPIRING_SOON }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(d.legalName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (d.isTestPartner) {
                                    GolocalStatusBadge(label = "TEST PARTNER", tone = "warning")
                                }
                                GolocalStatusBadge(
                                    label = d.verificationStatus.uppercase(),
                                    tone = when (d.verificationStatus) {
                                        "verified" -> "success"
                                        "pending" -> "warning"
                                        else -> "error"
                                    }
                                )
                            }
                        }
                        Text("${d.vehicleCategory.uppercase()} • ${d.vehicleModel} • Reg: ${d.vehicleRegNumber}", style = MaterialTheme.typography.bodyMedium)
                        Text("Phone: ${d.phone} • District: ${d.district} ${d.block} • KYC v${d.verificationVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("DL: ${d.drivingLicenceNumber} • RC: ${d.rcNumber.ifBlank { "N/A" }} • Ins: ${d.insuranceNumber.ifBlank { "N/A" }}", style = MaterialTheme.typography.bodySmall)
                        if (expiredCount > 0 || expiringSoonCount > 0) {
                            Text(
                                text = "Compliance alert: $expiredCount expired, $expiringSoonCount expiring within 30 days",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (expiredCount > 0) GolocalError else MaterialTheme.colorScheme.primary
                            )
                        }
                        if (d.rejectionReason.isNotBlank()) {
                            Text("Reason: ${d.rejectionReason}", style = MaterialTheme.typography.bodySmall, color = GolocalError)
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { inspectingDriver = d },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) {
                                Text("Inspect KYC")
                            }
                            if (d.verificationStatus != "verified") {
                                Button(
                                    onClick = { adminViewModel.verifyDriver(d.driverId, "verified", "All KYC documents verified") },
                                    colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("approve_driver_${d.driverId}")
                                ) {
                                    Text("Approve")
                                }
                            }
                            if (d.verificationStatus == "pending") {
                                OutlinedButton(
                                    onClick = { reasonActionTarget = d.driverId to "rejected" },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) {
                                    Text("Reject", color = GolocalError)
                                }
                            } else if (d.verificationStatus == "verified") {
                                OutlinedButton(
                                    onClick = { reasonActionTarget = d.driverId to "suspended" },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) {
                                    Text("Suspend", color = GolocalError)
                                }
                            }
                        }
                        if (d.verificationStatus == "verified") {
                            OutlinedButton(
                                onClick = { adminViewModel.setDriverTestPartner(d.driverId, !d.isTestPartner) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("toggle_test_partner_${d.driverId}")
                            ) {
                                Text(
                                    if (d.isTestPartner) "Revoke Test Partner Authorization"
                                    else "Authorize as Test Ride Partner"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    inspectingDriver?.let { d ->
        val compliance = remember(d) { RequiredDocumentsCatalog.assessDriverCompliance(d, todayEpochDay) }
        val driverHistory = remember(allHistory, d.driverId) { allHistory.filter { it.driverId == d.driverId } }
        AlertDialog(
            onDismissRequest = { inspectingDriver = null },
            title = { Text("KYC Review: ${d.legalName}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("${d.vehicleCategory.uppercase()} • ${d.vehicleRegNumber}", style = MaterialTheme.typography.titleSmall)
                    compliance.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.docType.displayLabel, style = MaterialTheme.typography.labelLarge)
                                Text(item.documentNumber.ifBlank { "Missing" }, style = MaterialTheme.typography.bodySmall)
                            }
                            GolocalStatusBadge(
                                label = item.status.name.replace('_', ' '),
                                tone = when (item.status) {
                                    ExpiryStatus.VALID -> "success"
                                    ExpiryStatus.EXPIRING_SOON, ExpiryStatus.NOT_PROVIDED -> "warning"
                                    ExpiryStatus.EXPIRED -> "error"
                                }
                            )
                        }
                    }
                    HorizontalDivider()
                    Text(
                        "Photos: DL=${d.dlPhotoUri.isNotBlank()}, RC=${d.rcPhotoUri.isNotBlank()}, Ins=${d.insurancePhotoUri.isNotBlank()}, Fitness=${d.fitnessPhotoUri.isNotBlank()}, Permit=${d.permitPhotoUri.isNotBlank()}, PUC=${d.pucPhotoUri.isNotBlank()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (driverHistory.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Audit Trail (${driverHistory.size})", style = MaterialTheme.typography.titleSmall)
                        driverHistory.take(5).forEach { entry ->
                            Text(
                                "v${entry.verificationVersion} • ${entry.decision.uppercase()}: ${entry.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { inspectingDriver = null }) { Text("Close") }
            }
        )
    }

    reasonActionTarget?.let { (id, targetStatus) ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { reasonActionTarget = null },
            title = { Text(if (targetStatus == "suspended") "Suspend Rider Account" else "Reject Rider Verification") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Enter a clear audit reason (minimum 4 characters).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Mandatory audit reason *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = reason.trim().length >= 4,
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    onClick = {
                        adminViewModel.verifyDriver(id, targetStatus, reason.trim())
                        reasonActionTarget = null
                    }
                ) { Text(if (targetStatus == "suspended") "Confirm Suspend" else "Confirm Reject") }
            },
            dismissButton = { TextButton(onClick = { reasonActionTarget = null }) { Text("Cancel") } }
        )
    }
}
