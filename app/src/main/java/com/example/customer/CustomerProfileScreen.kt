package com.example.customer

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.example.data.model.Booking
import com.example.data.model.RatingRecord
import com.example.data.model.UiState
import com.example.notifications.NotificationHelper
import com.example.shared.CircularBackButton
import com.example.shared.LocationCoordinator
import com.example.shared.ProfileHeroCard
import com.example.shared.SettingsNavigationRow
import com.example.shared.SettingsSectionGroup
import com.example.shared.maskPhoneNumber
import com.example.ui.auth.AuthDiagnostics
import com.example.ui.auth.RegistrationValidator
import com.example.ui.auth.SupportedCountryCodes
import com.example.ui.auth.VerifiedMobileBadgeCard
import com.example.ui.auth.signOut
import com.example.ui.components.LanguageSelectorRow
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalSuccess
import com.example.updater.AppUpdateDialogHost
import com.example.updater.CheckForAppUpdatesCard
import com.example.updater.rememberAppUpdateController
import com.example.viewmodel.AppSessionViewModel
import java.util.Locale

private enum class CustomerProfileSubScreen {
    MAIN,
    PERSONAL_INFO,
    SAVED_ADDRESSES,
    SAFETY_PRIVACY,
    PREFERENCES,
    UPDATES_ABOUT
}

@Composable
fun CustomerProfileScreen(
    sessionViewModel: AppSessionViewModel,
    bookings: List<Booking> = emptyList(),
    ratingsGiven: List<RatingRecord> = emptyList(),
    onOpenBookings: () -> Unit = {},
    onOpenNotifications: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountState by sessionViewModel.userAccountState.collectAsStateWithLifecycle()
    val customerProfile by sessionViewModel.customerProfileState.collectAsStateWithLifecycle()
    val privateEmergency by sessionViewModel.privateEmergencyContactState.collectAsStateWithLifecycle()
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val platformSettings by sessionViewModel.platformSettings.collectAsStateWithLifecycle()
    val updateController = rememberAppUpdateController(platformSettings = platformSettings)
    val account = (accountState as? UiState.Success)?.data

    var subScreen by remember { mutableStateOf(CustomerProfileSubScreen.MAIN) }
    var fullName by remember(account?.fullName) { mutableStateOf(account?.fullName ?: "") }
    var invoiceEmail by remember(account?.email) { mutableStateOf(account?.email ?: "") }
    var homeAddress by remember(customerProfile?.homeAddress) { mutableStateOf(customerProfile?.homeAddress ?: "") }
    var workAddress by remember(customerProfile?.workAddress) { mutableStateOf(customerProfile?.workAddress ?: "") }
    var nativeVillage by remember(customerProfile?.nativeVillage) { mutableStateOf(customerProfile?.nativeVillage ?: "") }
    var nativeDistrict by remember(customerProfile?.nativeDistrict) { mutableStateOf(customerProfile?.nativeDistrict ?: "") }

    var showSupportDialog by remember { mutableStateOf(false) }
    var showEmergencyDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val verifiedPhone = account?.effectiveMobileNumber().orEmpty()
    val hasEmergency = account?.hasEmergencyContact == true || privateEmergency != null
    val completedTripsCount = remember(bookings) {
        bookings.count { it.status == "completed" && !it.isTestRide && !it.testMode }
    }
    val avgRatingText = remember(ratingsGiven) {
        if (ratingsGiven.isEmpty()) "—"
        else String.format(Locale.US, "%.1f ★", ratingsGiven.map { it.stars }.average())
    }

    BackHandler(enabled = subScreen != CustomerProfileSubScreen.MAIN) {
        subScreen = CustomerProfileSubScreen.MAIN
    }

    if (subScreen != CustomerProfileSubScreen.MAIN) {
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
                CircularBackButton(onClick = { subScreen = CustomerProfileSubScreen.MAIN })
                Text(
                    text = when (subScreen) {
                        CustomerProfileSubScreen.PERSONAL_INFO -> "Personal Information"
                        CustomerProfileSubScreen.SAVED_ADDRESSES -> "Saved Addresses"
                        CustomerProfileSubScreen.SAFETY_PRIVACY -> "Privacy & Safety"
                        CustomerProfileSubScreen.PREFERENCES -> "Language & Display"
                        CustomerProfileSubScreen.UPDATES_ABOUT -> "Updates & Legal"
                        CustomerProfileSubScreen.MAIN -> ""
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            when (subScreen) {
                CustomerProfileSubScreen.PERSONAL_INFO -> {
                    var showFullMobile by remember { mutableStateOf(false) }
                    val displayedPhone = when {
                        verifiedPhone.isBlank() -> "Verified via Mobile OTP"
                        showFullMobile -> verifiedPhone
                        else -> maskPhoneNumber(verifiedPhone)
                    }
                    OutlinedTextField(
                        value = displayedPhone,
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        label = { Text("Verified Mobile Number (Read-Only)") },
                        trailingIcon = {
                            if (verifiedPhone.isNotBlank()) {
                                IconButton(onClick = { showFullMobile = !showFullMobile }) {
                                    Icon(
                                        imageVector = if (showFullMobile) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = "Toggle mobile visibility"
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_verified_mobile_readonly"),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = fullName,
                        onValueChange = { fullName = it },
                        label = { Text("Full Name *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = invoiceEmail,
                        onValueChange = { invoiceEmail = it },
                        label = { Text("Email Address (for GST Invoices) *") },
                        supportingText = { Text("Used strictly for ride receipts — never for login.") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            sessionViewModel.updateAccountAndProfile(
                                fullName = fullName,
                                invoiceEmail = invoiceEmail,
                                homeAddress = homeAddress,
                                workAddress = workAddress,
                                nativeVillage = nativeVillage,
                                nativeDistrict = nativeDistrict
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("save_profile_button")
                    ) {
                        Text("Save Personal Info")
                    }
                    VerifiedMobileBadgeCard(fallbackAccountPhone = verifiedPhone)
                }

                CustomerProfileSubScreen.SAVED_ADDRESSES -> {
                    OutlinedTextField(
                        value = homeAddress,
                        onValueChange = { homeAddress = it },
                        label = { Text("Saved Home Address") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = workAddress,
                        onValueChange = { workAddress = it },
                        label = { Text("Saved Work Address") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = nativeVillage,
                        onValueChange = { nativeVillage = it },
                        label = { Text("Native Village / Para") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = nativeDistrict,
                        onValueChange = { nativeDistrict = it },
                        label = { Text("District") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            sessionViewModel.updateAccountAndProfile(
                                fullName = fullName.ifBlank { account?.fullName ?: "GoLocal User" },
                                invoiceEmail = invoiceEmail.ifBlank { account?.email ?: "" },
                                homeAddress = homeAddress,
                                workAddress = workAddress,
                                nativeVillage = nativeVillage,
                                nativeDistrict = nativeDistrict
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text("Save Addresses")
                    }
                }

                CustomerProfileSubScreen.SAFETY_PRIVACY -> {
                    val maskedEmergency = privateEmergency?.emergencyContactNumber
                        ?.takeIf { it.isNotBlank() }
                        ?.let { AuthDiagnostics.maskPhoneNumber(it) }
                    SettingsSectionGroup(title = "Emergency Contact (Private)") {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = maskedEmergency?.let { "Protected SOS Contact: $it" }
                                    ?: if (hasEmergency) "Protected SOS contact configured privately."
                                    else "No private SOS contact set.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Stored in isolated emergency records and never shown to drivers.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = { showEmergencyDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("update_private_emergency_button")
                            ) {
                                Text(if (hasEmergency) "Update Private SOS Contact" else "Set Private SOS Contact")
                            }
                        }
                    }

                    SettingsSectionGroup(title = "Device Permissions") {
                        val hasLoc = LocationCoordinator.hasLocationPermission(context)
                        val hasNotif = NotificationHelper.hasNotificationPermission(context)
                        SettingsNavigationRow(
                            icon = Icons.Filled.GppGood,
                            title = "Location Permission",
                            subtitle = if (hasLoc) "Granted" else "Tap to manage in Android settings",
                            onClick = { LocationCoordinator.openAppPermissionSettings(context) }
                        )
                        SettingsNavigationRow(
                            icon = Icons.Filled.Notifications,
                            title = "Notifications Permission",
                            subtitle = if (hasNotif) "Enabled" else "Open app settings to enable",
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
                        Text("Request Account Deletion", color = GolocalError)
                    }
                }

                CustomerProfileSubScreen.PREFERENCES -> {
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
                                    "Lightweight maps & draft recovery for rural corridors",
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

                CustomerProfileSubScreen.UPDATES_ABOUT -> {
                    CheckForAppUpdatesCard(platformSettings = platformSettings, controller = updateController)
                    SettingsSectionGroup(title = "GoLocal Legal & Policies") {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("GoLocal by NR Next", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                text = "Terms of Service: Fares stay within admin-regulated West Bengal bands; every trip requires your 4-digit Trip PIN. Privacy Policy: Personal phone numbers and private emergency contacts are never exposed to drivers or passengers.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                CustomerProfileSubScreen.MAIN -> Unit
            }
        }
    } else {
        // Minimalist Account Settings Screen modeled on Image 3
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
                modifier = Modifier.testTag("customer_account_settings_title")
            )

            ProfileHeroCard(
                displayName = account?.fullName.orEmpty().ifBlank { "GoLocal Passenger" },
                subtitle = if (verifiedPhone.isNotBlank()) maskPhoneNumber(verifiedPhone) else (account?.email ?: "Verified Member"),
                badgeLabel = if (account?.mobileVerified == true) "Verified Account" else "Customer Account",
                badgeTone = if (account?.mobileVerified == true) "success" else "info",
                statOneLabel = "Completed Rides",
                statOneValue = completedTripsCount.toString(),
                statTwoLabel = "Ratings Given",
                statTwoValue = avgRatingText
            )

            SettingsSectionGroup {
                SettingsNavigationRow(
                    icon = Icons.Filled.Person,
                    title = "Personal information",
                    subtitle = account?.fullName?.ifBlank { "Name & invoice email" },
                    testTag = "settings_row_personal_info",
                    onClick = { subScreen = CustomerProfileSubScreen.PERSONAL_INFO }
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.Bookmark,
                    title = "Saved addresses",
                    subtitle = customerProfile?.homeAddress?.ifBlank { "Home, Work & Native Village" } ?: "Home, Work & Native Village",
                    testTag = "settings_row_saved_addresses",
                    onClick = { subScreen = CustomerProfileSubScreen.SAVED_ADDRESSES }
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.ReceiptLong,
                    title = "My rides & bus passes",
                    subtitle = "History, scheduled trips & digital passes",
                    testTag = "settings_row_my_rides",
                    onClick = onOpenBookings
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.Payments,
                    title = "Payments & invoices",
                    subtitle = "Cash, UPI records & GST receipts",
                    onClick = onOpenBookings
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    subtitle = "Trip alerts, confirmations & announcements",
                    showDivider = false,
                    onClick = onOpenNotifications
                )
            }

            SettingsSectionGroup {
                SettingsNavigationRow(
                    icon = Icons.Filled.Shield,
                    title = "Safety, emergency & privacy",
                    subtitle = if (hasEmergency) "Private SOS contact active" else "Configure private SOS contact",
                    iconTint = if (hasEmergency) GolocalSuccess else GolocalPrimaryBlue,
                    testTag = "private_emergency_contact_card",
                    onClick = { subScreen = CustomerProfileSubScreen.SAFETY_PRIVACY }
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.Language,
                    title = "Language, theme & low-network",
                    subtitle = "${prefs.language.displayLabel} • ${prefs.darkThemeMode.replaceFirstChar { it.uppercase() }}",
                    onClick = { subScreen = CustomerProfileSubScreen.PREFERENCES }
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = "App updates & policies",
                    subtitle = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    onClick = { subScreen = CustomerProfileSubScreen.UPDATES_ABOUT }
                )
                SettingsNavigationRow(
                    icon = Icons.Filled.SupportAgent,
                    title = "Help & support",
                    subtitle = platformSettings?.helplineNumber ?: "24x7 Safety & Booking Desk",
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
                text = "GoLocal by NR Next • Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }

    if (showEmergencyDialog) {
        var newEmergency by remember { mutableStateOf("") }
        var relation by remember { mutableStateOf(privateEmergency?.relationshipNote ?: "Primary Emergency Contact") }
        var localError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showEmergencyDialog = false },
            title = { Text("Update Private SOS Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Stored in isolated emergency records accessible only to you and authorized SOS responders.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (localError != null) {
                        Text(localError ?: "", style = MaterialTheme.typography.bodySmall, color = GolocalError)
                    }
                    OutlinedTextField(
                        value = newEmergency,
                        onValueChange = {
                            newEmergency = it.filter { ch -> ch.isDigit() || ch == '+' }.take(16)
                            localError = null
                        },
                        label = { Text("Emergency Mobile Number (+91...) *") },
                        placeholder = { Text("9876543210") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = relation,
                        onValueChange = { relation = it.take(60) },
                        label = { Text("Relationship Note") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val result = RegistrationValidator.validateEmergencyContact(
                        country = SupportedCountryCodes.INDIA,
                        rawEmergency = newEmergency,
                        ownVerifiedMobileE164 = verifiedPhone
                    )
                    when (result) {
                        is com.example.ui.auth.PhoneValidationResult.Invalid -> localError = result.message
                        is com.example.ui.auth.PhoneValidationResult.Valid -> {
                            sessionViewModel.updatePrivateEmergencyContact(result.e164Phone, relation)
                            showEmergencyDialog = false
                        }
                    }
                }) { Text("Save Privately") }
            },
            dismissButton = { TextButton(onClick = { showEmergencyDialog = false }) { Text("Cancel") } }
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
                    label = { Text("Reason for deletion request") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    onClick = {
                        sessionViewModel.submitSupportOrDeletion(
                            "account_deletion",
                            reason.ifBlank { "User requested account deletion" }
                        )
                        showDeleteDialog = false
                    }
                ) { Text("Submit Request") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }

    if (showSupportDialog) {
        var issue by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            title = { Text("Customer Help & Support") },
            text = {
                OutlinedTextField(
                    value = issue,
                    onValueChange = { issue = it },
                    label = { Text("How can GoLocal Support help?") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    sessionViewModel.submitSupportOrDeletion("customer_support", issue)
                    showSupportDialog = false
                }) { Text("Submit Ticket") }
            },
            dismissButton = { TextButton(onClick = { showSupportDialog = false }) { Text("Cancel") } }
        )
    }

    AppUpdateDialogHost(controller = updateController)
}
