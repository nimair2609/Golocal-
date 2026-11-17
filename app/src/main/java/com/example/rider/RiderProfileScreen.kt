package com.example.rider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.data.model.DriverProfile
import com.example.domain.ExpiryStatus
import com.example.domain.RequiredDocumentsCatalog
import com.example.notifications.NotificationHelper
import com.example.shared.CircularBackButton
import com.example.shared.LocationCoordinator
import com.example.shared.ProfileHeroCard
import com.example.shared.SettingsNavigationRow
import com.example.shared.SettingsSectionGroup
import com.example.shared.maskPhoneNumber
import com.example.ui.auth.VerifiedMobileBadgeCard
import com.example.ui.auth.signOut
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.components.LanguageSelectorRow
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalSuccess
import com.example.updater.AppUpdateDialogHost
import com.example.updater.CheckForAppUpdatesCard
import com.example.updater.rememberAppUpdateController
import com.example.viewmodel.AppSessionViewModel
import java.util.Locale
import java.util.concurrent.TimeUnit

private enum class RiderProfileSubScreen {
    MAIN,
    VEHICLE_INFO,
    KYC_DOCS,
    PRIVACY_PERMISSIONS,
    PREFERENCES,
    UPDATES_POLICIES
}

@Composable
fun RiderProfileScreen(
    profile: DriverProfile,
    sessionViewModel: AppSessionViewModel,
    onOpenEarnings: () -> Unit = {},
    onOpenNotifications: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val platformSettings by sessionViewModel.platformSettings.collectAsStateWithLifecycle()
    val updateController = rememberAppUpdateController(platformSettings = platformSettings)

    var subScreen by remember { mutableStateOf(RiderProfileSubScreen.MAIN) }
    var showSupportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val compliance = remember(profile) {
        RequiredDocumentsCatalog.assessDriverCompliance(
            profile = profile,
            todayEpochDay = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis())
        )
    }

    BackHandler(enabled = subScreen != RiderProfileSubScreen.MAIN) {
        subScreen = RiderProfileSubScreen.MAIN
    }

    if (subScreen != RiderProfileSubScreen.MAIN) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularBackButton(onClick = { subScreen = RiderProfileSubScreen.MAIN })
                Text(
                    text = when (subScreen) {
                        RiderProfileSubScreen.VEHICLE_INFO -> "Vehicle & Settlement"
                        RiderProfileSubScreen.KYC_DOCS -> "KYC & Expiry Status"
                        RiderProfileSubScreen.PRIVACY_PERMISSIONS -> "Privacy & Permissions"
                        RiderProfileSubScreen.PREFERENCES -> "Language & Display"
                        RiderProfileSubScreen.UPDATES_POLICIES -> "Updates & Partner Rules"
                        RiderProfileSubScreen.MAIN -> ""
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            when (subScreen) {
                RiderProfileSubScreen.VEHICLE_INFO -> {
                    SettingsSectionGroup(title = "Registered Vehicle") {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${profile.vehicleCategory.uppercase()} • ${profile.vehicleModel.ifBlank { "Standard Partner Vehicle" }}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Plate Number: ${profile.vehicleRegNumber}", style = MaterialTheme.typography.bodyMedium)
                            Text("Operating Area: ${profile.district} ${profile.block}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Driving Licence: ${profile.drivingLicenceNumber}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = "Settlement UPI / Bank: ${profile.settlementUpiOrBank.ifBlank { "Not configured" }}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    VerifiedMobileBadgeCard(fallbackAccountPhone = profile.phone)
                }

                RiderProfileSubScreen.KYC_DOCS -> {
                    SettingsSectionGroup(title = "Document Compliance Matrix") {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            compliance.forEach { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.docType.displayLabel, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = item.documentNumber.ifBlank { "Not entered" },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
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
                        }
                    }
                }

                RiderProfileSubScreen.PRIVACY_PERMISSIONS -> {
                    SettingsSectionGroup(title = "Device Permissions") {
                        val hasLoc = LocationCoordinator.hasLocationPermission(context)
                        val hasNotif = NotificationHelper.hasNotificationPermission(context)
                        SettingsNavigationRow(
                            icon = Icons.Filled.GppGood,
                            title = "Location Permission",
                            subtitle = if (hasLoc) "Granted for live GPS dispatch" else "Tap to allow in Android settings",
                            onClick = { LocationCoordinator.openAppPermissionSettings(context) }
                        )
                        SettingsNavigationRow(
                            icon = Icons.Filled.Notifications,
                            title = "Notifications Permission",
                            subtitle = if (hasNotif) "Enabled for ride requests & alerts" else "Tap to enable in Android settings",
                            showDivider = false,
                            onClick = { LocationCoordinator.openAppPermissionSettings(context) }
                        )
                    }
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = GolocalError)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Request Partner Account Deletion", color = GolocalError)
                    }
                }

                RiderProfileSubScreen.PREFERENCES -> {
                    SettingsSectionGroup(title = "Language / ভাষা / भाषा") {
                        Column(modifier = Modifier.padding(16.dp)) {
                            LanguageSelectorRow(
                                selected = prefs.language,
                                onSelect = { sessionViewModel.setLanguage(it) }
                            )
                        }
                    }
                    SettingsSectionGroup(title = "Theme Appearance") {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                                FilterChip(
                                    selected = prefs.darkThemeMode == mode,
                                    onClick = { sessionViewModel.setThemeMode(mode) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                    SettingsSectionGroup(title = "Connectivity") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Low-Network Mode", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Lightweight map mode for highway & rural routes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = prefs.lowNetworkMode,
                                onCheckedChange = { sessionViewModel.setLowNetworkMode(it) }
                            )
                        }
                    }
                }

                RiderProfileSubScreen.UPDATES_POLICIES -> {
                    CheckForAppUpdatesCard(platformSettings = platformSettings, controller = updateController)
                    SettingsSectionGroup(title = "Partner Operating Rules") {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("GoLocal by NR Next", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                text = "Partners keep all fare offers inside the regulated admin band, collect the 4-digit Trip PIN before starting any ride, and communicate strictly through privacy-safe channels.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                RiderProfileSubScreen.MAIN -> Unit
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Account settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("rider_account_settings_title")
            )

            ProfileHeroCard(
                displayName = profile.legalName.ifBlank { "GoLocal Partner" },
                subtitle = "${profile.vehicleCategory.uppercase()} • ${profile.vehicleRegNumber} • ${maskPhoneNumber(profile.phone)}",
                badgeLabel = "VERIFIED PARTNER",
                badgeTone = "success",
                statOneLabel = "Completed Rides",
                statOneValue = profile.completedRides.toString(),
                statTwoLabel = "Partner Rating",
                statTwoValue = String.format(Locale.US, "%.1f ★", profile.ratingAvg)
            )

            SettingsSectionGroup {
                SettingsNavigationRow(
                    icon = Icons.Filled.DirectionsCar,
                    title = "Vehicle & settlement",
                    subtitle = "${profile.vehicleModel.ifBlank { profile.vehicleCategory.uppercase() }} • ${profile.settlementUpiOrBank.ifBlank { "Add UPI" }}",
                    testTag = "rider_settings_vehicle_row",
                    onClick = { subScreen = RiderProfileSubScreen.VEHICLE_INFO }
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.Badge,
                    title = "KYC documents & expiry",
                    subtitle = "${compliance.count { it.status == ExpiryStatus.VALID }}/${compliance.size} active documents",
                    testTag = "rider_settings_kyc_row",
                    onClick = { subScreen = RiderProfileSubScreen.KYC_DOCS }
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.AccountBalanceWallet,
                    title = "Earnings & withdrawals",
                    subtitle = "Gross fares, net ledger & payouts",
                    onClick = onOpenEarnings
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications & alerts",
                    subtitle = "KYC approvals, payouts & system updates",
                    showDivider = false,
                    onClick = onOpenNotifications
                )
            }

            SettingsSectionGroup {
                SettingsNavigationRow(
                    icon = Icons.Filled.Shield,
                    title = "Privacy & device permissions",
                    subtitle = "Location, notifications & account controls",
                    iconTint = GolocalSuccess,
                    onClick = { subScreen = RiderProfileSubScreen.PRIVACY_PERMISSIONS }
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.Language,
                    title = "Language, theme & low-network",
                    subtitle = "${prefs.language.displayLabel} • ${prefs.darkThemeMode.replaceFirstChar { it.uppercase() }}",
                    onClick = { subScreen = RiderProfileSubScreen.PREFERENCES }
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = "App updates & policies",
                    subtitle = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    onClick = { subScreen = RiderProfileSubScreen.UPDATES_POLICIES }
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.SupportAgent,
                    title = "Rider partner support",
                    subtitle = platformSettings?.helplineNumber ?: "KYC, trip & payout helpline",
                    showDivider = false,
                    onClick = { showSupportDialog = true }
                )
            }

            OutlinedButton(
                onClick = { signOut(scope = scope, context = context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("sign_out_button"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = GolocalError)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign out", color = GolocalError, fontWeight = FontWeight.SemiBold)
            }

            Text(
                text = "GoLocal Rider by NR Next • Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }

    if (showSupportDialog) {
        var issue by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            title = { Text("Rider Partner Support") },
            text = {
                OutlinedTextField(
                    value = issue,
                    onValueChange = { issue = it },
                    label = { Text("Describe payout, KYC, or trip issue") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    sessionViewModel.submitSupportOrDeletion("rider_support", issue)
                    showSupportDialog = false
                }) { Text("Submit") }
            },
            dismissButton = { TextButton(onClick = { showSupportDialog = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteDialog) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Account Deletion Request") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason for partner account deletion") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    onClick = {
                        sessionViewModel.submitSupportOrDeletion(
                            "account_deletion",
                            reason.ifBlank { "Partner requested account deletion" }
                        )
                        showDeleteDialog = false
                    }
                ) { Text("Submit Request") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }

    AppUpdateDialogHost(controller = updateController)
}
