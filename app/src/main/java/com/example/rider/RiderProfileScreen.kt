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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.example.data.tracking.RiderLocationForegroundService
import com.example.domain.ExpiryStatus
import com.example.domain.RequiredDocumentsCatalog
import com.example.shared.CircularBackButton
import com.example.shared.ProfileHeroCard
import com.example.shared.SettingsNavigationRow
import com.example.shared.SettingsSectionGroup
import com.example.shared.maskPhoneNumber
import com.example.ui.auth.signOut
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalSuccess
import com.example.updater.AppUpdateDialogHost
import com.example.updater.CheckForAppUpdatesCard
import com.example.updater.rememberAppUpdateController
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.DriverViewModel
import java.util.Locale
import java.util.concurrent.TimeUnit

private enum class RiderProfileSubScreen {
    MAIN,
    VEHICLE_INFO,
    KYC_DOCS,
    SAFETY_EMERGENCY,
    PREFERENCES,
    SUPPORT_LEGAL
}

@Composable
fun RiderProfileScreen(
    profile: DriverProfile,
    sessionViewModel: AppSessionViewModel,
    driverViewModel: DriverViewModel? = null,
    onOpenEarnings: () -> Unit = {},
    onOpenNotifications: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val platformSettings by sessionViewModel.platformSettings.collectAsStateWithLifecycle()
    val privateEmergency by sessionViewModel.privateEmergencyContactState.collectAsStateWithLifecycle()
    val gps by sessionViewModel.gpsLocation.collectAsStateWithLifecycle()
    val updateController = rememberAppUpdateController(platformSettings = platformSettings)

    var subScreen by remember { mutableStateOf(RiderProfileSubScreen.MAIN) }
    var showSupportDialog by remember { mutableStateOf(false) }
    var supportDialogTitle by remember { mutableStateOf("Rider Partner Support") }
    var supportDialogPrefix by remember { mutableStateOf("[Rider Support]") }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val emergencyContacts = remember(privateEmergency) {
        privateEmergency?.effectiveContacts().orEmpty()
    }
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
                        RiderProfileSubScreen.VEHICLE_INFO -> "Account & Settlement"
                        RiderProfileSubScreen.KYC_DOCS -> "KYC & Expiry Status"
                        RiderProfileSubScreen.SAFETY_EMERGENCY -> "Safety & Emergency Contacts"
                        RiderProfileSubScreen.PREFERENCES -> "Alerts, Sound & Preferences"
                        RiderProfileSubScreen.SUPPORT_LEGAL -> "Help, Support & Policies"
                        RiderProfileSubScreen.MAIN -> ""
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            when (subScreen) {
                RiderProfileSubScreen.VEHICLE_INFO -> {
                    RiderAccountEditSubScreen(
                        profile = profile,
                        driverViewModel = driverViewModel
                    )
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

                RiderProfileSubScreen.SAFETY_EMERGENCY -> {
                    RiderSafetySubScreen(
                        ownVerifiedMobileE164 = profile.phone,
                        contacts = emergencyContacts,
                        platformSettings = platformSettings,
                        onSaveContacts = { list -> sessionViewModel.saveEmergencyContacts(list) },
                        onTriggerSos = driverViewModel?.let { vm ->
                            {
                                vm.triggerSos(
                                    bookingId = "",
                                    lat = gps.lat ?: 0.0,
                                    lng = gps.lng ?: 0.0,
                                    notes = "SOS triggered from Rider Safety Settings"
                                )
                            }
                        }
                    )
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("rider_delete_account_button")
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = GolocalError)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete Partner Account & Data", color = GolocalError)
                    }
                }

                RiderProfileSubScreen.PREFERENCES -> {
                    RiderPreferencesSubScreen(
                        prefs = prefs,
                        sessionViewModel = sessionViewModel
                    )
                }

                RiderProfileSubScreen.SUPPORT_LEGAL -> {
                    CheckForAppUpdatesCard(platformSettings = platformSettings, controller = updateController)
                    RiderSupportAndLegalSubScreen(
                        platformSettings = platformSettings,
                        onOpenSupportDialog = {
                            supportDialogTitle = "Rider Partner Support"
                            supportDialogPrefix = "[Rider Support]"
                            showSupportDialog = true
                        },
                        onOpenReportBugDialog = {
                            supportDialogTitle = "Report a Problem"
                            supportDialogPrefix = "[Rider Bug Report]"
                            showSupportDialog = true
                        }
                    )
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
                text = "Settings & Partner Profile",
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

            SettingsSectionGroup(title = "Account & Payouts") {
                SettingsNavigationRow(
                    icon = Icons.Filled.DirectionsCar,
                    title = "Account, profile & settlement UPI",
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
                    title = "Notifications & alerts inbox",
                    subtitle = "KYC approvals, payouts & system updates",
                    showDivider = false,
                    onClick = onOpenNotifications
                )
            }

            SettingsSectionGroup(title = "Preferences, Safety & Support") {
                SettingsNavigationRow(
                    icon = Icons.Filled.Tune,
                    title = "Sound, vibration, language & theme",
                    subtitle = "Ride chime: ${if (prefs.rideRequestSoundEnabled) "On" else "Muted"} • Vib: ${if (prefs.rideRequestVibrationEnabled) "On" else "Off"}",
                    iconTint = GolocalPrimaryBlue,
                    testTag = "rider_settings_preferences_row",
                    onClick = { subScreen = RiderProfileSubScreen.PREFERENCES }
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.Shield,
                    title = "Safety & emergency contacts",
                    subtitle = if (emergencyContacts.isEmpty()) "Add at least 2 trusted contacts" else "${emergencyContacts.size}/5 private contacts • SOS",
                    iconTint = if (emergencyContacts.size >= 2) GolocalSuccess else GolocalPrimaryBlue,
                    testTag = "rider_settings_safety_row",
                    onClick = { subScreen = RiderProfileSubScreen.SAFETY_EMERGENCY }
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.SupportAgent,
                    title = "Help, report a problem & policies",
                    subtitle = platformSettings?.helplineNumber?.ifBlank { "FAQ, Support & Legal" } ?: "FAQ, Support & Legal",
                    testTag = "rider_settings_support_row",
                    onClick = { subScreen = RiderProfileSubScreen.SUPPORT_LEGAL }
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = "App version & updates",
                    subtitle = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    showDivider = false,
                    onClick = { subScreen = RiderProfileSubScreen.SUPPORT_LEGAL }
                )
            }

            OutlinedButton(
                onClick = {
                    RiderLocationForegroundService.stop(context)
                    signOut(scope = scope, context = context)
                },
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
            title = { Text(supportDialogTitle) },
            text = {
                OutlinedTextField(
                    value = issue,
                    onValueChange = { issue = it },
                    label = { Text("Describe payout, KYC, sound, or trip issue") },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rider_support_issue_input")
                )
            },
            confirmButton = {
                Button(
                    enabled = issue.trim().length >= 4,
                    onClick = {
                        sessionViewModel.submitSupportOrDeletion(
                            requestType = "general_support",
                            details = "$supportDialogPrefix ${issue.trim()}"
                        )
                        showSupportDialog = false
                    },
                    modifier = Modifier.testTag("rider_support_submit_button")
                ) { Text("Submit") }
            },
            dismissButton = { TextButton(onClick = { showSupportDialog = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteDialog) {
        var reason by remember { mutableStateOf("") }
        var confirmText by remember { mutableStateOf("") }
        val isDeleting by sessionViewModel.isDeletingAccount.collectAsStateWithLifecycle()
        val canDelete = confirmText.trim().equals("DELETE", ignoreCase = false) && !isDeleting
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
            title = { Text("Delete Partner Account & Data") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "This takes your partner profile offline, clears private emergency contacts and FCM tokens, anonymizes personal contact details, and removes your login credential.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Reason (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        label = { Text("Type DELETE to confirm") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rider_delete_account_confirm_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = canDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    modifier = Modifier.testTag("rider_confirm_delete_account_button"),
                    onClick = {
                        RiderLocationForegroundService.stop(context)
                        sessionViewModel.deleteMyAccount(
                            context = context,
                            reason = reason.ifBlank { "Partner self-service account deletion" },
                            onRequiresRecentLogin = {
                                showDeleteDialog = false
                                signOut(scope = scope, context = context)
                            },
                            onAccountDeleted = {
                                showDeleteDialog = false
                                signOut(scope = scope, context = context)
                            }
                        )
                    }
                ) { Text(if (isDeleting) "Deleting..." else "Delete Permanently") }
            },
            dismissButton = {
                TextButton(enabled = !isDeleting, onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    AppUpdateDialogHost(controller = updateController)
}
