package com.example.rider

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.DriverProfile
import com.example.data.model.EmergencyContactEntry
import com.example.data.model.PlatformSettings
import com.example.data.repository.ClientPreferences
import com.example.notifications.NotificationHelper
import com.example.shared.EmergencyContactsSectionCard
import com.example.shared.LocationCoordinator
import com.example.shared.SettingsNavigationRow
import com.example.shared.SettingsSectionGroup
import com.example.ui.auth.VerifiedMobileBadgeCard
import com.example.ui.components.LanguageSelectorRow
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarning
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.DriverViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

@Composable
internal fun RiderAccountEditSubScreen(
    profile: DriverProfile,
    driverViewModel: DriverViewModel?
) {
    var legalName by remember(profile.legalName) { mutableStateOf(profile.legalName) }
    var phone by remember(profile.phone) { mutableStateOf(profile.phone) }
    var address by remember(profile.address) { mutableStateOf(profile.address) }
    var settlementUpi by remember(profile.settlementUpiOrBank) { mutableStateOf(profile.settlementUpiOrBank) }
    val authEmail = remember { runCatching { Firebase.auth.currentUser?.email.orEmpty() }.getOrDefault("") }

    SettingsSectionGroup(title = "Editable Account & Payout Details") {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = legalName,
                onValueChange = { legalName = it },
                label = { Text("Partner Display / Legal Name *") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rider_edit_name_input")
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Partner Contact Phone *") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rider_edit_phone_input")
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Home / Stand Address") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rider_edit_address_input")
            )
            OutlinedTextField(
                value = settlementUpi,
                onValueChange = { settlementUpi = it },
                label = { Text("Settlement UPI ID or Bank Ref") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rider_edit_upi_input")
            )
            if (driverViewModel != null) {
                Button(
                    onClick = {
                        driverViewModel.updateDriverSelfProfile(
                            legalName = legalName,
                            phone = phone,
                            address = address,
                            settlementUpiOrBank = settlementUpi
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("rider_save_account_button")
                ) {
                    Text("Save Account Changes")
                }
            }
        }
    }

    SettingsSectionGroup(title = "Verified Vehicle & Auth Status (Locked)") {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "${profile.vehicleCategory.uppercase()} • ${profile.vehicleModel.ifBlank { "Partner Vehicle" }}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text("Plate Number: ${profile.vehicleRegNumber}", style = MaterialTheme.typography.bodySmall)
            Text("Driving Licence: ${profile.drivingLicenceNumber}", style = MaterialTheme.typography.bodySmall)
            Text(
                text = "Operating Zone: ${profile.district} ${profile.block}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (authEmail.isNotBlank()) {
                Text(
                    text = "Linked Sign-In Email: $authEmail",
                    style = MaterialTheme.typography.bodySmall,
                    color = GolocalPrimaryBlue
                )
            }
            Text(
                text = "Vehicle plate, category, and KYC document fields require Admin re-verification to change.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    VerifiedMobileBadgeCard(fallbackAccountPhone = profile.phone)
}

@Composable
internal fun RiderPreferencesSubScreen(
    prefs: ClientPreferences,
    sessionViewModel: AppSessionViewModel
) {
    val context = LocalContext.current
    val hasLoc = LocationCoordinator.hasLocationPermission(context)
    val hasNotif = NotificationHelper.hasNotificationPermission(context)
    val isDndActive = NotificationHelper.isDeviceDoNotDisturbActive(context)

    SettingsSectionGroup(title = "Ride Request Alerts & Sound") {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RiderToggleRow(
                icon = Icons.Filled.Notifications,
                title = "Notifications",
                subtitle = "Master switch for ride requests, trip alerts, and updates",
                checked = prefs.notificationsEnabled,
                testTag = "rider_pref_notifications_switch",
                onCheckedChange = { sessionViewModel.setNotificationsEnabled(it) }
            )
            RiderToggleRow(
                icon = Icons.Filled.VolumeUp,
                title = "Ride Request Sound",
                subtitle = "Play GoLocal custom chime when new ride requests arrive",
                checked = prefs.rideRequestSoundEnabled,
                enabled = prefs.notificationsEnabled,
                testTag = "rider_pref_sound_switch",
                onCheckedChange = { sessionViewModel.setRideRequestSoundEnabled(it) }
            )
            RiderToggleRow(
                icon = Icons.Filled.Vibration,
                title = "Ride Request Vibration",
                subtitle = "Vibrate distinctly for incoming ride requests",
                checked = prefs.rideRequestVibrationEnabled,
                enabled = prefs.notificationsEnabled,
                testTag = "rider_pref_vibration_switch",
                onCheckedChange = { sessionViewModel.setRideRequestVibrationEnabled(it) }
            )
            if (isDndActive) {
                Text(
                    text = "Device Do Not Disturb is active — Android mutes alert sounds/vibration while DND is enabled.",
                    style = MaterialTheme.typography.labelMedium,
                    color = GolocalWarning
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.CHANNEL_RIDE_REQUESTS_V2)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                            .onFailure { LocationCoordinator.openAppPermissionSettings(context) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("rider_channel_sound_settings_button")
                ) {
                    Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Android Ride Alert Channel Settings")
                }
            }
        }
    }

    SettingsSectionGroup(title = "Device Permissions") {
        val supportsFullScreenIntentToggle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        SettingsNavigationRow(
            icon = Icons.Filled.GppGood,
            title = "Location Permission",
            subtitle = if (hasLoc) "Granted for live GPS dispatch" else "Tap to allow in Android settings",
            onClick = { LocationCoordinator.openAppPermissionSettings(context) }
        )
        SettingsNavigationRow(
            icon = Icons.Filled.Notifications,
            title = "OS Notification Permission",
            subtitle = if (hasNotif) "Allowed on device" else "Tap to allow notifications in Android settings",
            showDivider = supportsFullScreenIntentToggle,
            onClick = { LocationCoordinator.openAppPermissionSettings(context) }
        )
        if (supportsFullScreenIntentToggle) {
            val canFullScreen = NotificationHelper.canUseFullScreenIntent(context)
            SettingsNavigationRow(
                icon = Icons.Filled.NotificationsActive,
                title = "Full-Screen Lock-Screen Alerts",
                subtitle = if (canFullScreen) {
                    "Enabled for urgent incoming ride requests"
                } else {
                    "Tap to allow full-screen alerts when screen is locked"
                },
                showDivider = false,
                testTag = "rider_fullscreen_intent_settings_row",
                onClick = { NotificationHelper.openFullScreenIntentSettings(context) }
            )
        }
    }

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
                    text = "Lightweight map tiles & draft recovery for rural West Bengal routes",
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

@Composable
internal fun RiderSafetySubScreen(
    ownVerifiedMobileE164: String,
    contacts: List<EmergencyContactEntry>,
    platformSettings: PlatformSettings?,
    onSaveContacts: (List<EmergencyContactEntry>) -> Unit,
    onTriggerSos: (() -> Unit)?
) {
    EmergencyContactsSectionCard(
        contacts = contacts,
        ownVerifiedMobileE164 = ownVerifiedMobileE164,
        onSaveContacts = onSaveContacts
    )

    if (onTriggerSos != null) {
        SettingsSectionGroup(title = "Emergency SOS Dispatch") {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Immediate safety escalation alerts GoLocal Safety Desk and shares your latest GPS coordinates (15s duplicate-tap protection active).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onTriggerSos,
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .testTag("rider_settings_sos_button")
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Trigger Emergency SOS", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    SettingsSectionGroup(title = "Rider Safety Guidelines & Helpline") {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Phone, contentDescription = null, tint = GolocalSuccess, modifier = Modifier.size(16.dp))
                Text(
                    text = "24x7 Safety Desk: ${platformSettings?.helplineNumber?.ifBlank { "+91 1800-GOLOCAL" } ?: "+91 1800-GOLOCAL"}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "• Always verify the passenger's 6-digit Pickup OTP before starting any trip.\n" +
                    "• Never drive off-route without informing the passenger and Operations.\n" +
                    "• Keep Location and Ride Alert permissions enabled while Online.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun RiderSupportAndLegalSubScreen(
    platformSettings: PlatformSettings?,
    onOpenSupportDialog: () -> Unit,
    onOpenReportBugDialog: () -> Unit
) {
    SettingsSectionGroup(title = "Partner Help & FAQ") {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("• Incoming Requests: Stay Online with Location granted. Tap Accept to lock a ride exclusively, or Propose Fare within the regulated band.", style = MaterialTheme.typography.bodySmall)
            Text("• 6-Digit Pickup OTP: Ask the passenger at pickup and enter all 6 digits to unlock destination Google Maps navigation.", style = MaterialTheme.typography.bodySmall)
            Text("• Custom Sound Channel: Android creates immutable sound channels once installed; GoLocal uses channel 'Ride Requests (v2)' for your custom tone.", style = MaterialTheme.typography.bodySmall)
            Text("• Helpline: ${platformSettings?.helplineNumber?.ifBlank { "24x7 GoLocal Desk" } ?: "24x7 GoLocal Desk"}", style = MaterialTheme.typography.labelMedium, color = GolocalPrimaryBlue)
        }
    }

    SettingsSectionGroup(title = "Contact Support & Issue Reporting") {
        SettingsNavigationRow(
            icon = Icons.Filled.SupportAgent,
            title = "Contact Partner Support",
            subtitle = "Payouts, KYC review, or booking dispute help",
            testTag = "rider_contact_support_row",
            onClick = onOpenSupportDialog
        )
        SettingsNavigationRow(
            icon = Icons.Filled.BugReport,
            title = "Report a Problem",
            subtitle = "Report dispatch, sound, map GPS, or app bugs",
            showDivider = false,
            testTag = "rider_report_problem_row",
            onClick = onOpenReportBugDialog
        )
    }

    SettingsSectionGroup(title = "Terms & Privacy Policy") {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("GoLocal by NR Next", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                text = "Terms of Service: All rides use admin-regulated West Bengal fare bands and 6-digit hashed Pickup OTP verification before starting.\n" +
                    "Privacy Policy: Passenger & partner phone numbers and private emergency contacts are isolated under strict security rules and never exposed publicly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RiderToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) GolocalPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
