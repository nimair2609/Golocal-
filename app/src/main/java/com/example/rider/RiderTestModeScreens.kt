package com.example.rider

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.data.model.Booking
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.TestRiderProfile
import com.example.data.model.TestRiderRequest
import com.example.data.model.UiState
import com.example.domain.AbuseDetectionEngine
import com.example.map.GolocalMapContainer
import com.example.notifications.NotificationDeepLinkTarget
import com.example.shared.LocationAccessState
import com.example.shared.LocationCoordinator
import com.example.ui.auth.signOut
import com.example.ui.components.GoLocalLogoMark
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalMetricCard
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.components.GolocalStatusMessageBanner
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalPureWhite
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.GolocalWarningContainer
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.DriverViewModel

private val TEST_VEHICLE_CATEGORIES = listOf("bike", "auto", "toto", "cab")

@Composable
fun RiderTestModeGateScreen(
    requestState: UiState<TestRiderRequest?>,
    defaultName: String,
    defaultPhone: String,
    defaultEmail: String,
    statusMessage: String?,
    onDismissMessage: () -> Unit,
    onSubmitRequest: (
        fullName: String,
        mobile: String,
        email: String,
        category: String,
        purpose: String,
        notes: String,
        deviceSummary: String
    ) -> Unit,
    onSwitchToCommercial: () -> Unit,
    onSignOut: () -> Unit
) {
    val request = (requestState as? UiState.Success)?.data
    var reapplying by remember(request?.status) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        GolocalStatusMessageBanner(message = statusMessage, onDismiss = onDismissMessage)

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = GolocalWarningContainer,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, GolocalWarning, RoundedCornerShape(16.dp))
                .testTag("test_rider_mode_banner")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Filled.Science, contentDescription = null, tint = GolocalDeepNavy)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TEST RIDER MODE • NON-COMMERCIAL",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = GolocalDeepNavy
                    )
                    Text(
                        text = "Isolated QA sandbox. No commercial KYC or payouts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GolocalDeepNavy.copy(alpha = 0.8f)
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GoLocalLogoMark(modifier = Modifier.size(44.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Test Rider Access",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Owner Admin approval required before accepting TEST RIDE trips.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        when (requestState) {
            is UiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            is UiState.Error -> {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = requestState.message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            is UiState.Success -> {
                val showForm = request == null || (request.status == "rejected" && reapplying)
                if (showForm) {
                    TestRiderApplicationForm(
                        existing = request,
                        defaultName = defaultName,
                        defaultPhone = defaultPhone,
                        defaultEmail = defaultEmail,
                        onSubmit = { name, mobile, email, cat, purpose, notes ->
                            val deviceSummary = "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})"
                            onSubmitRequest(name, mobile, email, cat, purpose, notes, deviceSummary)
                            reapplying = false
                        }
                    )
                } else if (request != null) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                            .testTag("test_rider_status_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = request.fullName.ifBlank { "Test Rider Applicant" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                GolocalStatusBadge(
                                    label = request.status.uppercase(),
                                    tone = when (request.status) {
                                        "approved" -> "success"
                                        "rejected", "suspended", "revoked" -> "error"
                                        else -> "warning"
                                    }
                                )
                            }
                            Text(
                                text = "Category: ${request.vehicleCategory.uppercase()} • Mobile: ${request.mobileNumber}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Purpose: ${request.testPurpose}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            when (request.status) {
                                "pending" -> {
                                    Text(
                                        text = "Your Test Rider request is pending Owner Admin approval. Operations Staff may record a recommendation, and Owner Admin grants final sandbox access.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                "rejected" -> {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Rejection Reason: ${request.rejectionReason.ifBlank { "Not specified" }}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier
                                                .padding(12.dp)
                                                .testTag("test_rider_rejection_reason")
                                        )
                                    }
                                    Button(
                                        onClick = { reapplying = true },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag("test_rider_reapply_button")
                                    ) {
                                        Text("Edit & Reapply for Test Rider Access")
                                    }
                                }
                                "suspended", "revoked" -> {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "${request.status.replaceFirstChar { it.uppercase() }} Reason: ${request.rejectionReason.ifBlank { "Contact Owner Admin." }}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier
                                                .padding(12.dp)
                                                .testTag("test_rider_blocked_reason")
                                        )
                                    }
                                }
                                "approved" -> {
                                    Text(
                                        text = "Approved! Activating your isolated Test Rider workspace...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GolocalSuccess
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        com.example.shared.NotificationPermissionBannerCard(
            title = "Enable Test Rider Approval Alerts",
            description = "Get notified immediately when Owner Admin approves or updates your Test Rider access."
        )

        OutlinedButton(
            onClick = onSwitchToCommercial,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("switch_to_commercial_kyc_button")
        ) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Switch to Commercial Partner KYC")
        }

        TextButton(
            onClick = onSignOut,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .heightIn(min = 48.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = GolocalError)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Sign Out", color = GolocalError)
        }
    }
}

@Composable
private fun TestRiderApplicationForm(
    existing: TestRiderRequest?,
    defaultName: String,
    defaultPhone: String,
    defaultEmail: String,
    onSubmit: (String, String, String, String, String, String) -> Unit
) {
    var fullName by remember(existing?.fullName, defaultName) {
        mutableStateOf(existing?.fullName?.ifBlank { defaultName } ?: defaultName)
    }
    var mobile by remember(existing?.mobileNumber, defaultPhone) {
        mutableStateOf(existing?.mobileNumber?.ifBlank { defaultPhone } ?: defaultPhone)
    }
    var email by remember(existing?.email, defaultEmail) {
        mutableStateOf(existing?.email?.ifBlank { defaultEmail } ?: defaultEmail)
    }
    var category by remember(existing?.vehicleCategory) {
        mutableStateOf(existing?.vehicleCategory?.takeIf { it in TEST_VEHICLE_CATEGORIES } ?: "toto")
    }
    var purpose by remember(existing?.testPurpose) {
        mutableStateOf(existing?.testPurpose ?: "")
    }
    var notes by remember(existing?.notes) {
        mutableStateOf(existing?.notes ?: "")
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (existing == null) "Apply for Test Rider Access" else "Update & Reapply",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = fullName,
                onValueChange = { fullName = it },
                label = { Text("Full Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("test_rider_name_input")
            )
            OutlinedTextField(
                value = mobile,
                onValueChange = { mobile = it },
                label = { Text("Mobile Number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("test_rider_mobile_input")
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("test_rider_email_input")
            )
            Text("Test Vehicle Category", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TEST_VEHICLE_CATEGORIES.forEach { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { category = cat },
                        label = { Text(cat.uppercase()) },
                        modifier = Modifier.testTag("test_rider_cat_$cat")
                    )
                }
            }
            OutlinedTextField(
                value = purpose,
                onValueChange = { purpose = it },
                label = { Text("Testing Purpose (e.g., QA pickup OTP & live map)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("test_rider_purpose_input")
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Extra Notes / Device Info (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("test_rider_notes_input")
            )
            Button(
                onClick = { onSubmit(fullName, mobile, email, category, purpose, notes) },
                enabled = fullName.trim().length >= 2 && mobile.trim().length >= 8 && purpose.trim().length >= 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .testTag("submit_test_rider_request_button")
            ) {
                Text("Submit Test Rider Request", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun RiderTestModeDashboardShell(
    profile: TestRiderProfile,
    sessionViewModel: AppSessionViewModel,
    driverViewModel: DriverViewModel,
    lowNetworkMode: Boolean,
    deepLinkTarget: NotificationDeepLinkTarget?,
    onDeepLinkHandled: () -> Unit,
    onSwitchToCommercialMode: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gps by sessionViewModel.gpsLocation.collectAsStateWithLifecycle()
    val openTestBookings by driverViewModel.openTestBookings.collectAsStateWithLifecycle()
    val openTestBookingsError by driverViewModel.openTestBookingsError.collectAsStateWithLifecycle()
    val assignedBookings by driverViewModel.assignedBookings.collectAsStateWithLifecycle()
    val activeAssignedBooking by driverViewModel.activeAssignedBooking.collectAsStateWithLifecycle()
    val completedTestCount by driverViewModel.testRiderCompletedCount.collectAsStateWithLifecycle()
    val isVerifyingTestOtp by driverViewModel.isVerifyingTestOtp.collectAsStateWithLifecycle()
    val testOtpError by driverViewModel.testOtpError.collectAsStateWithLifecycle()
    val driverMsg by driverViewModel.message.collectAsStateWithLifecycle()
    val sessionMsg by sessionViewModel.statusMessage.collectAsStateWithLifecycle()
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf("home") }
    var lastAutoFocusedTestId by remember { mutableStateOf<String?>(null) }
    var alertedTestIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showCancelDialog by remember { mutableStateOf(false) }

    val activeTestTrip = activeAssignedBooking?.takeIf { it.isTestRide || it.testMode }
    val currentLat = gps.lat ?: profile.currentLat.takeIf { it != 0.0 } ?: com.example.domain.BoraiLaunchConfig.DEFAULT_LAT
    val currentLng = gps.lng ?: profile.currentLng.takeIf { it != 0.0 } ?: com.example.domain.BoraiLaunchConfig.DEFAULT_LNG

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            sessionViewModel.detectDeviceLocation(context)
        }
    }

    LaunchedEffect(activeTestTrip?.id) {
        val currentId = activeTestTrip?.id
        if (currentId != null && currentId != lastAutoFocusedTestId) {
            lastAutoFocusedTestId = currentId
            selectedTab = "home"
        } else if (currentId == null) {
            lastAutoFocusedTestId = null
        }
    }

    LaunchedEffect(
        openTestBookings,
        profile.isOnline,
        activeTestTrip?.id,
        prefs.notificationsEnabled,
        prefs.rideRequestSoundEnabled,
        prefs.rideRequestVibrationEnabled
    ) {
        val currentIds = openTestBookings.map { it.id }.toSet()
        (alertedTestIds - currentIds).forEach {
            com.example.notifications.NotificationHelper.cancelRideRequestNotification(context, it)
        }
        val canAlert = profile.isOnline && activeTestTrip == null && prefs.notificationsEnabled
        if (!canAlert) {
            currentIds.forEach {
                com.example.notifications.NotificationHelper.cancelRideRequestNotification(context, it)
            }
            alertedTestIds = currentIds
            return@LaunchedEffect
        }
        openTestBookings.filter { it.id !in alertedTestIds }.forEach { req ->
            com.example.notifications.NotificationHelper.displayRideRequestAlert(
                context = context,
                bookingId = req.id,
                title = "TEST RIDE • ${req.vehicleCategory.uppercase()} (₹0)",
                body = "${req.pickupLabel} -> ${req.destinationLabel} (${req.distanceKm} km)",
                isTestRide = true,
                soundEnabled = prefs.rideRequestSoundEnabled,
                vibrationEnabled = prefs.rideRequestVibrationEnabled,
                notificationsEnabled = prefs.notificationsEnabled
            )
        }
        alertedTestIds = currentIds
    }

    LaunchedEffect(deepLinkTarget) {
        val target = deepLinkTarget ?: return@LaunchedEffect
        selectedTab = resolveRiderDeepLinkTab(target = target, inTestMode = true)
        onDeepLinkHandled()
    }

    BackHandler(enabled = selectedTab != "home") { selectedTab = "home" }

    val navItems = listOf(
        Triple("home", "Home", Icons.Filled.Home),
        Triple("rides", "Test Rides", Icons.Filled.DirectionsCar),
        Triple("summary", "Summary", Icons.Filled.Summarize),
        Triple("alerts", "Alerts", Icons.Filled.Notifications),
        Triple("profile", "Profile", Icons.Filled.Person)
    )

    val fullScreenMap = selectedTab == "home"

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 700.dp
        Scaffold(
            contentWindowInsets = if (fullScreenMap) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        windowInsets = WindowInsets.navigationBars,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        navItems.forEach { (route, label, icon) ->
                            NavigationBarItem(
                                selected = selectedTab == route,
                                onClick = { selectedTab = route },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                                modifier = Modifier.testTag("test_rider_nav_$route")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (useRail) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        Spacer(modifier = Modifier.height(12.dp))
                        navItems.forEach { (route, label, icon) ->
                            NavigationRailItem(
                                selected = selectedTab == route,
                                onClick = { selectedTab = route },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                                modifier = Modifier.testTag("test_rider_rail_$route")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 960.dp)
                            .align(Alignment.Center)
                    ) {
                        Surface(
                            color = GolocalWarningContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .testTag("test_rider_mode_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Science, contentDescription = null, tint = GolocalDeepNavy, modifier = Modifier.size(16.dp))
                                    Text(
                                        text = "TEST RIDER MODE • NON-COMMERCIAL",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = GolocalDeepNavy
                                    )
                                }
                                GolocalStatusBadge(
                                    label = if (profile.isOnline) "ONLINE" else "OFFLINE",
                                    tone = if (profile.isOnline) "success" else "warning"
                                )
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when (selectedTab) {
                                "home" -> {
                                    if (activeTestTrip != null) {
                                        RiderActiveTripSheet(
                                            booking = activeTestTrip,
                                            currentLat = currentLat,
                                            currentLng = currentLng,
                                            onlineSelf = DriverDirectoryEntry(
                                                driverId = profile.uid,
                                                displayName = profile.displayName,
                                                vehicleCategory = profile.vehicleCategory,
                                                isOnline = profile.isOnline,
                                                currentLat = currentLat,
                                                currentLng = currentLng
                                            ),
                                            lowNetworkMode = lowNetworkMode,
                                            callContact = null,
                                            unreadChatCount = 0,
                                            onCallCustomer = {},
                                            onChatCustomer = {},
                                            onTriggerSos = {
                                                driverViewModel.triggerSos(activeTestTrip.id, currentLat, currentLng, "Test Rider SOS")
                                            },
                                            onCancelTrip = { showCancelDialog = true },
                                            onAdvanceState = { next, pin ->
                                                driverViewModel.advanceTripState(activeTestTrip, next, pin, currentLat, currentLng)
                                            },
                                            onVerifyTestOtp = { otp -> driverViewModel.verifyTestRideOtp(activeTestTrip, otp) },
                                            isVerifyingTestOtp = isVerifyingTestOtp,
                                            testOtpError = testOtpError,
                                            onClearTestOtpError = { driverViewModel.clearTestOtpError() }
                                        )
                                    } else {
                                        TestRiderHomeMapTab(
                                            profile = profile,
                                            currentLat = currentLat,
                                            currentLng = currentLng,
                                            lowNetworkMode = lowNetworkMode,
                                            openTestBookings = openTestBookings,
                                            onToggleOnline = { online ->
                                                driverViewModel.toggleTestRiderOnline(online, currentLat, currentLng)
                                            },
                                             onSyncLocation = {
                                                 if (LocationCoordinator.resolveAccessState(context) == LocationAccessState.GRANTED_READY) {
                                                     sessionViewModel.detectDeviceLocation(context)
                                                 } else {
                                                     locationLauncher.launch(
                                                         arrayOf(
                                                             Manifest.permission.ACCESS_FINE_LOCATION,
                                                             Manifest.permission.ACCESS_COARSE_LOCATION
                                                         )
                                                     )
                                                 }
                                             },
                                             onClaimBooking = { b -> driverViewModel.claimTestBooking(b, currentLat, currentLng) },
                                             openTestBookingsError = openTestBookingsError,
                                             onRetryTestFeed = { driverViewModel.refreshDispatchFeeds() }
                                         )
                                     }
                                 }
                                "rides" -> TestRiderQueueTab(
                                    profile = profile,
                                    openTestBookings = openTestBookings,
                                    completedOrActiveTest = assignedBookings.filter { it.isTestRide || it.testMode },
                                    onClaimBooking = { b -> driverViewModel.claimTestBooking(b, currentLat, currentLng) },
                                    openTestBookingsError = openTestBookingsError,
                                    onRetryTestFeed = { driverViewModel.refreshDispatchFeeds() }
                                )
                                "summary" -> TestRiderSummaryTab(
                                    profile = profile,
                                    completedCount = completedTestCount,
                                    recentTestBookings = assignedBookings.filter { it.isTestRide || it.testMode }
                                )
                                "alerts" -> RiderNotificationsTab(
                                    sessionViewModel = sessionViewModel,
                                    testOnly = true,
                                    onNotificationClick = { target ->
                                        selectedTab = resolveRiderDeepLinkTab(target = target, inTestMode = true)
                                    }
                                )
                                "profile" -> TestRiderProfileTab(
                                    profile = profile,
                                    completedCount = completedTestCount,
                                    sessionViewModel = sessionViewModel,
                                    onSwitchToCommercialMode = {
                                        if (profile.isOnline) {
                                            driverViewModel.toggleTestRiderOnline(false, currentLat, currentLng)
                                        }
                                        onSwitchToCommercialMode()
                                    },
                                    onSignOut = { signOut(scope = scope, context = context) }
                                )
                            }
                        }
                    }

                    if (activeTestTrip != null && selectedTab != "home") {
                        Surface(
                            onClick = { selectedTab = "home" },
                            shape = RoundedCornerShape(16.dp),
                            color = GolocalSuccess,
                            shadowElevation = 8.dp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .testTag("test_active_trip_resume_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Active Test Ride (${activeTestTrip.status.uppercase()}) • Return to Trip",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = androidx.compose.ui.graphics.Color.White
                                )
                            }
                        }
                    }

                    GolocalStatusMessageBanner(
                        message = driverMsg ?: sessionMsg,
                        onDismiss = {
                            driverViewModel.clearMessage()
                            sessionViewModel.clearStatusMessage()
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 16.dp, vertical = 54.dp)
                    )
                }
            }
        }
    }

    if (showCancelDialog && activeTestTrip != null) {
        var category by remember { mutableStateOf(AbuseDetectionEngine.driverCancellationReasons.first()) }
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel Test Ride?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AbuseDetectionEngine.driverCancellationReasons.forEach { opt ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            RadioButton(selected = category == opt, onClick = { category = opt })
                            Text(opt, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Note (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    onClick = {
                        driverViewModel.cancelActiveBookingByDriver(activeTestTrip, category, note.ifBlank { category })
                        showCancelDialog = false
                    }
                ) { Text("Confirm Cancel") }
            },
            dismissButton = { TextButton(onClick = { showCancelDialog = false }) { Text("Keep") } }
        )
    }
}

@Composable
private fun TestRiderHomeMapTab(
    profile: TestRiderProfile,
    currentLat: Double,
    currentLng: Double,
    lowNetworkMode: Boolean,
    openTestBookings: List<Booking>,
    onToggleOnline: (Boolean) -> Unit,
    onSyncLocation: () -> Unit,
    onClaimBooking: (Booking) -> Unit,
    openTestBookingsError: String? = null,
    onRetryTestFeed: () -> Unit = {}
) {
    val firstOpen = openTestBookings.firstOrNull()
    Box(modifier = Modifier.fillMaxSize()) {
        GolocalMapContainer(
            pickupLat = firstOpen?.pickupLat ?: currentLat,
            pickupLng = firstOpen?.pickupLng ?: currentLng,
            pickupLabel = firstOpen?.pickupLabel ?: "Test Pickup Zone",
            destLat = firstOpen?.destinationLat ?: (currentLat + 0.012),
            destLng = firstOpen?.destinationLng ?: (currentLng + 0.012),
            destLabel = firstOpen?.destinationLabel ?: "Sandbox Zone",
            onlineDrivers = if (profile.isOnline) {
                listOf(
                    DriverDirectoryEntry(
                        driverId = profile.uid,
                        displayName = profile.displayName,
                        vehicleCategory = profile.vehicleCategory,
                        isOnline = true,
                        currentLat = currentLat,
                        currentLng = currentLng
                    )
                )
            } else {
                emptyList()
            },
            liveTripLocation = null,
            lowNetworkMode = lowNetworkMode,
            fillContainer = true,
            cornerRadius = 0.dp,
            showBorder = false,
            showTopRouteChip = false,
            recenterBottomPadding = 260.dp,
            userLat = currentLat,
            userLng = currentLng
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 360.dp),
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!openTestBookingsError.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().testTag("test_requests_error_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Test feed sync warning: $openTestBookingsError",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onRetryTestFeed) {
                                Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (profile.isOnline) "Test Rider Online" else "Test Rider Offline",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${profile.vehicleLabel} • ${openTestBookings.size} open TEST RIDE(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = profile.isOnline,
                        onCheckedChange = onToggleOnline,
                        colors = SwitchDefaults.colors(checkedTrackColor = GolocalSuccess),
                        modifier = Modifier.testTag("test_rider_online_switch")
                    )
                }
                if (firstOpen != null) {
                    val pickupKm = com.example.domain.FareCalculator.haversineDistanceKm(
                        currentLat,
                        currentLng,
                        firstOpen.pickupLat,
                        firstOpen.pickupLng
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "${firstOpen.pickupLabel} → ${firstOpen.destinationLabel}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = String.format(
                                    java.util.Locale.US,
                                    "Pickup %.1f km away • Trip %.1f km • Non-commercial ₹0",
                                    pickupKm,
                                    firstOpen.distanceKm
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onSyncLocation,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Sync GPS") }
                    if (firstOpen != null) {
                        Button(
                            onClick = { onClaimBooking(firstOpen) },
                            enabled = profile.isOnline,
                            modifier = Modifier
                                .weight(1.4f)
                                .heightIn(min = 48.dp)
                                .testTag("claim_test_ride_${firstOpen.id}")
                        ) {
                            Text("Claim Test Ride #${firstOpen.id.take(5)}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TestRiderQueueTab(
    profile: TestRiderProfile,
    openTestBookings: List<Booking>,
    completedOrActiveTest: List<Booking>,
    onClaimBooking: (Booking) -> Unit,
    openTestBookingsError: String? = null,
    onRetryTestFeed: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Open TEST RIDE Queue (${openTestBookings.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onRetryTestFeed) {
                    Text("Refresh")
                }
            }
        }
        if (!openTestBookingsError.isNullOrBlank()) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().testTag("test_requests_error_card")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sync warning: $openTestBookingsError",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onRetryTestFeed) {
                            Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        if (openTestBookings.isEmpty()) {
            item {
                GolocalEmptyState(
                    title = "No Open TEST RIDE Bookings",
                    message = "Customer bookings flagged TEST RIDE will appear here when Test Ride Mode is active."
                )
            }
        } else {
            items(openTestBookings, key = { it.id }) { booking ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        .testTag("test_ride_queue_card_${booking.id}")
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            GolocalStatusBadge(label = "TEST RIDE • ${booking.vehicleCategory.uppercase()}", tone = "warning")
                            Text("₹0 Commercial", fontFamily = JetBrainsMono, color = GolocalPrimaryBlue)
                        }
                        Text("${booking.pickupLabel} → ${booking.destinationLabel}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${booking.distanceKm} km • Est. ₹${booking.estimatedFareInr.toInt()} (sandbox)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { onClaimBooking(booking) },
                            enabled = profile.isOnline,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("claim_test_ride_${booking.id}")
                        ) {
                            Text(if (profile.isOnline) "Claim & Start Test Ride" else "Go Online on Home Tab to Claim")
                        }
                    }
                }
            }
        }

        if (completedOrActiveTest.isNotEmpty()) {
            item {
                Text(
                    text = "My Test Ride History (${completedOrActiveTest.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            items(completedOrActiveTest, key = { it.id }) { b ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("#${b.id.take(6)}", fontFamily = JetBrainsMono, style = MaterialTheme.typography.labelLarge)
                            GolocalStatusBadge(label = b.status.uppercase(), tone = if (b.status == "completed") "success" else "info")
                        }
                        Text("${b.pickupLabel} → ${b.destinationLabel}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TestRiderSummaryTab(
    profile: TestRiderProfile,
    completedCount: Int,
    recentTestBookings: List<Booking>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Test Sandbox Summary", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GolocalMetricCard("Completed Test Rides", completedCount.toString(), profile.vehicleLabel, Icons.Filled.DirectionsCar, Modifier.weight(1f))
            GolocalMetricCard("Commercial Impact", "₹0", "Isolated sandbox", Icons.Filled.Science, Modifier.weight(1f))
        }
        if (recentTestBookings.isEmpty()) {
            GolocalEmptyState(title = "No Test Trips Yet", message = "Completed TEST RIDE sessions appear here with ₹0 commercial impact.")
        } else {
            recentTestBookings.take(10).forEach { b ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${b.pickupLabel} → ${b.destinationLabel}", style = MaterialTheme.typography.titleSmall)
                            Text("₹0 Non-Commercial • ${b.distanceKm} km", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GolocalStatusBadge(label = b.status.uppercase(), tone = if (b.status == "completed") "success" else "info")
                    }
                }
            }
        }
    }
}

@Composable
private fun TestRiderProfileTab(
    profile: TestRiderProfile,
    completedCount: Int,
    sessionViewModel: AppSessionViewModel,
    onSwitchToCommercialMode: () -> Unit,
    onSignOut: () -> Unit
) {
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val accountState by sessionViewModel.userAccountState.collectAsStateWithLifecycle()
    val privateEmergency by sessionViewModel.privateEmergencyContactState.collectAsStateWithLifecycle()
    val ownVerifiedPhone = (accountState as? UiState.Success)?.data?.effectiveMobileNumber().orEmpty()
    val contacts = remember(privateEmergency) { privateEmergency?.effectiveContacts().orEmpty() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Test Rider Profile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    GolocalStatusBadge(label = "APPROVED TEST RIDER", tone = "success")
                }
                Text("${profile.vehicleCategory.uppercase()} • ${profile.vehicleLabel}", style = MaterialTheme.typography.bodyMedium)
                Text("Completed Test Rides: $completedCount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        RiderPreferencesSubScreen(
            prefs = prefs,
            sessionViewModel = sessionViewModel
        )

        com.example.shared.EmergencyContactsSectionCard(
            contacts = contacts,
            ownVerifiedMobileE164 = ownVerifiedPhone,
            onSaveContacts = { list -> sessionViewModel.saveEmergencyContacts(list) }
        )

        Button(
            onClick = onSwitchToCommercialMode,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .testTag("switch_to_commercial_mode_button")
        ) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Switch to Commercial Partner Mode")
        }
        OutlinedButton(
            onClick = onSignOut,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .testTag("test_rider_sign_out_button")
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = GolocalError)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sign Out", color = GolocalError)
        }
    }
}
