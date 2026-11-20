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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.domain.IndianRegulatoryChecklist
import com.example.domain.OperatingStateCode
import com.example.domain.StateComplianceCatalog
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalError
import com.example.ui.theme.JetBrainsMono
import com.example.updater.AppUpdateDialogHost
import com.example.updater.CheckForAppUpdatesCard
import com.example.updater.rememberAppUpdateController
import com.example.viewmodel.AdminViewModel

@Composable
fun AdminReportsModule(adminViewModel: AdminViewModel) {
    val bookings by adminViewModel.bookings.collectAsStateWithLifecycle()
    val payments by adminViewModel.payments.collectAsStateWithLifecycle()

    var targetUid by remember { mutableStateOf("") }
    var notifTitle by remember { mutableStateOf("") }
    var notifBody by remember { mutableStateOf("") }

    val categoryStats = remember(bookings) {
        bookings.groupBy { it.vehicleCategory }.mapValues { (_, list) ->
            val completed = list.count { it.status == "completed" }
            val revenue = list.filter { it.status == "completed" }.sumOf { if (it.agreedFareInr > 0) it.agreedFareInr else it.estimatedFareInr }
            Triple(list.size, completed, revenue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Analytics & Notifications", style = MaterialTheme.typography.headlineSmall)

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Category Performance (Real Firestore Data)", style = MaterialTheme.typography.titleMedium)
                if (categoryStats.isEmpty()) {
                    Text("No rides recorded yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    categoryStats.forEach { (cat, stats) ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${cat.uppercase()} (${stats.first} total, ${stats.second} completed)", style = MaterialTheme.typography.bodyMedium)
                            Text("₹${stats.third.toInt()}", fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    text = "Total GST Collected: ₹${payments.sumOf { it.gstInr }.toInt()} • Total Commission: ₹${payments.sumOf { it.platformCommissionInr }.toInt()}",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = JetBrainsMono
                )
            }
        }

        // Notifications Management
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Dispatch Platform Notification", style = MaterialTheme.typography.titleMedium)
                Text("Send a targeted notification to a Customer or Rider UID.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = targetUid, onValueChange = { targetUid = it }, label = { Text("Recipient Firebase UID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = notifTitle, onValueChange = { notifTitle = it }, label = { Text("Notification Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = notifBody, onValueChange = { notifBody = it }, label = { Text("Message Body") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        adminViewModel.sendNotification(targetUid, notifTitle, notifBody)
                        notifTitle = ""
                        notifBody = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Send Notification")
                }
            }
        }
    }
}

@Composable
fun AdminSettingsModule(adminViewModel: AdminViewModel) {
    val platformSettings by adminViewModel.platformSettings.collectAsStateWithLifecycle()
    val drivers by adminViewModel.drivers.collectAsStateWithLifecycle()
    val adminAllowlist by adminViewModel.adminAllowlist.collectAsStateWithLifecycle()
    val operationsStaff by adminViewModel.operationsStaff.collectAsStateWithLifecycle()
    val auditLogs by adminViewModel.auditLogs.collectAsStateWithLifecycle()

    var helpline by remember(platformSettings.helplineNumber) { mutableStateOf(platformSettings.helplineNumber) }
    var commissionPct by remember(platformSettings.defaultCommissionPct) { mutableStateOf(platformSettings.defaultCommissionPct.toString()) }
    var cancelPolicy by remember(platformSettings.cancellationPolicySummary) { mutableStateOf(platformSettings.cancellationPolicySummary) }
    var notice by remember(platformSettings.maintenanceNotice) { mutableStateOf(platformSettings.maintenanceNotice) }
    var parcelEnabled by remember(platformSettings.parcelDeliveryEnabled) { mutableStateOf(platformSettings.parcelDeliveryEnabled) }
    var operatingStateCode by remember(platformSettings.operatingStateCode) { mutableStateOf(platformSettings.operatingStateCode.ifBlank { "WB" }) }
    var aggregatorLicenceNumber by remember(platformSettings.aggregatorLicenceNumber) { mutableStateOf(platformSettings.aggregatorLicenceNumber) }
    var grievanceOfficerName by remember(platformSettings.grievanceOfficerName) { mutableStateOf(platformSettings.grievanceOfficerName) }
    var grievanceOfficerEmail by remember(platformSettings.grievanceOfficerEmail) { mutableStateOf(platformSettings.grievanceOfficerEmail) }
    var bikeTaxiCommercialApproved by remember(platformSettings.bikeTaxiCommercialApproved) { mutableStateOf(platformSettings.bikeTaxiCommercialApproved) }

    var updatesEnabled by remember(platformSettings.updatesEnabled) { mutableStateOf(platformSettings.updatesEnabled) }
    var updateManifestUrl by remember(platformSettings.updateManifestUrl) {
        mutableStateOf(platformSettings.updateManifestUrl.ifBlank { BuildConfig.UPDATE_MANIFEST_URL })
    }
    var latestVersionName by remember(platformSettings.latestVersionName) { mutableStateOf(platformSettings.latestVersionName) }
    var latestVersionCode by remember(platformSettings.latestVersionCode) { mutableStateOf(platformSettings.latestVersionCode.toString()) }
    var minSupportedCode by remember(platformSettings.minSupportedVersionCode) { mutableStateOf(platformSettings.minSupportedVersionCode.toString()) }
    var apkDownloadUrl by remember(platformSettings.apkDownloadUrl) { mutableStateOf(platformSettings.apkDownloadUrl) }
    var sha256Checksum by remember(platformSettings.sha256Checksum) { mutableStateOf(platformSettings.sha256Checksum) }
    var releaseNotes by remember(platformSettings.releaseNotes) { mutableStateOf(platformSettings.releaseNotes) }
    var isMandatoryUpdate by remember(platformSettings.isMandatoryUpdate) { mutableStateOf(platformSettings.isMandatoryUpdate) }
    val updateController = rememberAppUpdateController(platformSettings = platformSettings)

    var newAdminUid by remember { mutableStateOf("") }
    var newAdminEmail by remember { mutableStateOf("") }
    var newAdminName by remember { mutableStateOf("") }
    var newOpsUid by remember { mutableStateOf("") }
    var newOpsName by remember { mutableStateOf("") }
    var newOpsContact by remember { mutableStateOf("") }
    var newOpsDistrict by remember { mutableStateOf("Paschim Medinipur") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings, Admin Access & Audit Logs", style = MaterialTheme.typography.headlineSmall)

        // Platform Settings
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Global Platform Settings", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = helpline, onValueChange = { helpline = it }, label = { Text("Support Helpline Number") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = commissionPct, onValueChange = { commissionPct = it }, label = { Text("Default Commission (%)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = cancelPolicy, onValueChange = { cancelPolicy = it }, label = { Text("Cancellation Policy Summary") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = notice, onValueChange = { notice = it }, label = { Text("Operational Notice (Optional)") }, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Local Parcel Delivery Regulatory Approval")
                    Switch(checked = parcelEnabled, onCheckedChange = { parcelEnabled = it })
                }
                Button(
                    onClick = {
                        adminViewModel.savePlatformSettings(
                            platformSettings.copy(
                                helplineNumber = helpline,
                                defaultCommissionPct = commissionPct.toDoubleOrNull() ?: 10.0,
                                cancellationPolicySummary = cancelPolicy,
                                maintenanceNotice = notice,
                                parcelDeliveryEnabled = parcelEnabled
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save Platform Settings") }
            }
        }

        // Indian Regulatory & Aggregator Compliance Registry
        val statePolicy = remember(operatingStateCode) { StateComplianceCatalog.policyFor(operatingStateCode) }
        val verifiedDriversCount = remember(drivers) { drivers.count { it.verificationStatus == "verified" } }
        val checklistItems = remember(
            operatingStateCode,
            aggregatorLicenceNumber,
            grievanceOfficerName,
            grievanceOfficerEmail,
            bikeTaxiCommercialApproved,
            platformSettings.defaultCommissionPct,
            verifiedDriversCount
        ) {
            IndianRegulatoryChecklist.evaluate(
                settings = platformSettings.copy(
                    operatingStateCode = operatingStateCode,
                    aggregatorLicenceNumber = aggregatorLicenceNumber,
                    grievanceOfficerName = grievanceOfficerName,
                    grievanceOfficerEmail = grievanceOfficerEmail,
                    bikeTaxiCommercialApproved = bikeTaxiCommercialApproved
                ),
                verifiedDriversCount = verifiedDriversCount
            )
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                .testTag("admin_regulatory_compliance_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Indian Regulatory Compliance & Licensing", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Track Motor Vehicles Act / MVA Guidelines 2025, State Aggregator Licence (${statePolicy.state.displayName}), Bike-Taxi permissibility, and DPDP Grievance Officer fields.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OperatingStateCode.entries.forEach { stateOption ->
                        FilterChip(
                            selected = operatingStateCode.equals(stateOption.code, ignoreCase = true),
                            onClick = { operatingStateCode = stateOption.code },
                            label = { Text("${stateOption.displayName} (${stateOption.code})") },
                            modifier = Modifier.testTag("admin_state_chip_${stateOption.code}")
                        )
                    }
                }
                OutlinedTextField(
                    value = aggregatorLicenceNumber,
                    onValueChange = { aggregatorLicenceNumber = it },
                    label = { Text("State Aggregator Licence Ref (${statePolicy.state.code})") },
                    placeholder = { Text("Enter Transport Department licence ref") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = grievanceOfficerName,
                    onValueChange = { grievanceOfficerName = it },
                    label = { Text("DPDP / Consumer Grievance Officer Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = grievanceOfficerEmail,
                    onValueChange = { grievanceOfficerEmail = it },
                    label = { Text("Grievance Officer Contact Email") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bike-Taxi Commercial Permit Confirmed", style = MaterialTheme.typography.titleSmall)
                        Text(
                            statePolicy.bikeTaxiStatusNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = bikeTaxiCommercialApproved,
                        onCheckedChange = { bikeTaxiCommercialApproved = it }
                    )
                }
                Button(
                    onClick = {
                        adminViewModel.savePlatformSettings(
                            platformSettings.copy(
                                operatingStateCode = operatingStateCode.uppercase(),
                                aggregatorLicenceNumber = aggregatorLicenceNumber.trim(),
                                grievanceOfficerName = grievanceOfficerName.trim(),
                                grievanceOfficerEmail = grievanceOfficerEmail.trim(),
                                bikeTaxiCommercialApproved = bikeTaxiCommercialApproved
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("admin_save_regulatory_button")
                ) { Text("Save Regulatory Compliance") }

                HorizontalDivider()
                Text("Compliance Readiness Summary", style = MaterialTheme.typography.labelLarge)
                checklistItems.forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            GolocalStatusBadge(
                                label = if (item.implementedInCode) "READY" else "ACTION REQ",
                                tone = if (item.implementedInCode) "success" else "warning"
                            )
                        }
                        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.requiresExternalSignOff) {
                            Text(
                                "Requires Transport Authority / Legal Counsel Confirmation",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // In-App APK Update Management
        CheckForAppUpdatesCard(
            platformSettings = platformSettings,
            controller = updateController
        )
        AppUpdateDialogHost(controller = updateController)

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("APK Update Management", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Publish verified GitHub Release or HTTPS APK updates for Customer, Rider, and Admin users.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable In-App APK Updates")
                    Switch(checked = updatesEnabled, onCheckedChange = { updatesEnabled = it })
                }
                OutlinedTextField(
                    value = updateManifestUrl,
                    onValueChange = { updateManifestUrl = it },
                    label = { Text("HTTPS Update Manifest URL (Optional JSON)") },
                    placeholder = { Text("https://github.com/<owner>/<repo>/releases/latest/download/update-manifest.json") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latestVersionName,
                        onValueChange = { latestVersionName = it },
                        label = { Text("Version Name") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = latestVersionCode,
                        onValueChange = { latestVersionCode = it },
                        label = { Text("Version Code") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = minSupportedCode,
                    onValueChange = { minSupportedCode = it },
                    label = { Text("Minimum Supported Version Code") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = apkDownloadUrl,
                    onValueChange = { apkDownloadUrl = it },
                    label = { Text("HTTPS APK Download URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = sha256Checksum,
                    onValueChange = { sha256Checksum = it },
                    label = { Text("APK SHA-256 Checksum (64 hex chars)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = releaseNotes,
                    onValueChange = { releaseNotes = it },
                    label = { Text("Release Notes") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Mandatory Update (Blocks Older Builds)")
                    Switch(checked = isMandatoryUpdate, onCheckedChange = { isMandatoryUpdate = it })
                }
                Button(
                    onClick = {
                        adminViewModel.savePlatformSettings(
                            platformSettings.copy(
                                updatesEnabled = updatesEnabled,
                                updateManifestUrl = updateManifestUrl,
                                latestVersionName = latestVersionName,
                                latestVersionCode = latestVersionCode.toLongOrNull()?.coerceAtLeast(1L) ?: 1L,
                                minSupportedVersionCode = minSupportedCode.toLongOrNull()?.coerceAtLeast(1L) ?: 1L,
                                apkDownloadUrl = apkDownloadUrl,
                                sha256Checksum = sha256Checksum,
                                releaseNotes = releaseNotes,
                                isMandatoryUpdate = isMandatoryUpdate
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save APK Update Manifest") }
            }
        }

        // Admin Account Management
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Admin Account Allowlist", style = MaterialTheme.typography.titleMedium)
                Text("Primary bootstrapped admin (renukadas083@gmail.com) can grant or revoke delegated admin UIDs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = newAdminUid, onValueChange = { newAdminUid = it }, label = { Text("Admin Firebase UID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = newAdminEmail, onValueChange = { newAdminEmail = it }, label = { Text("Verified Admin Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = newAdminName, onValueChange = { newAdminName = it }, label = { Text("Display Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(
                    onClick = {
                        adminViewModel.grantAdminAccess(newAdminUid, newAdminEmail, newAdminName)
                        newAdminUid = ""
                        newAdminEmail = ""
                        newAdminName = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Grant Admin Access") }

                adminAllowlist.forEach { entry ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.email, style = MaterialTheme.typography.titleSmall)
                            Text("UID: ${entry.uid}", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMono)
                        }
                        TextButton(onClick = { adminViewModel.revokeAdminAccess(entry.uid) }) {
                            Text("Revoke", color = GolocalError)
                        }
                    }
                }
            }
        }

        // Operations Staff Role Management (strictly scoped, NO Owner Admin permissions)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Operations Staff Access (/operations_staff)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Operations staff can view the Live Ops Map, assign available verified partners, and handle trip escalations/SOS location context. They do NOT receive Owner Admin pricing, payouts, or KYC permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(value = newOpsUid, onValueChange = { newOpsUid = it }, label = { Text("Staff Firebase UID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = newOpsName, onValueChange = { newOpsName = it }, label = { Text("Staff Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = newOpsContact, onValueChange = { newOpsContact = it }, label = { Text("Staff Phone / Identifier") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = newOpsDistrict, onValueChange = { newOpsDistrict = it }, label = { Text("Assigned District") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(
                    onClick = {
                        adminViewModel.grantOperationsAccess(newOpsUid, newOpsName, newOpsContact, newOpsDistrict)
                        newOpsUid = ""
                        newOpsName = ""
                        newOpsContact = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Grant Scoped Operations Access") }

                operationsStaff.forEach { staff ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${staff.displayName.ifBlank { "Ops Staff" }} (${staff.assignedDistrict})", style = MaterialTheme.typography.titleSmall)
                            Text("UID: ${staff.userId} • Active: ${staff.isActive}", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMono)
                        }
                        if (staff.isActive) {
                            TextButton(onClick = { adminViewModel.revokeOperationsAccess(staff.userId) }) {
                                Text("Revoke", color = GolocalError)
                            }
                        }
                    }
                }
            }
        }

        // Security & Audit Logs
        Text("Security & Audit Logs (${auditLogs.size})", style = MaterialTheme.typography.titleMedium)
        if (auditLogs.isEmpty()) {
            GolocalEmptyState(title = "No Audit Entries Yet", message = "All administrative mutations record an immutable audit trail here.")
        } else {
            auditLogs.take(30).forEach { log ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            GolocalStatusBadge(label = log.action, tone = "info")
                            Text(log.actorEmail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(log.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
