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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.data.model.SampleRide
import com.example.data.model.SampleRiderProfile
import com.example.data.model.TripLocation
import com.example.data.repository.GolocalRepository
import com.example.data.repository.SampleRiderRepository
import com.example.data.tracking.RiderLocationForegroundService
import com.example.map.GolocalMapContainer
import com.example.notifications.NotificationDeepLinkTarget
import com.example.notifications.NotificationHelper
import com.example.shared.LocationAccessState
import com.example.shared.LocationCoordinator
import com.example.ui.auth.signOut
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.components.GolocalStatusMessageBanner
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalLightBlue
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.GolocalWarningContainer
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.SampleRiderViewModel
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.catch
import java.util.Locale

@Composable
fun SampleRiderDashboardShell(
    currentUserId: String,
    sessionViewModel: AppSessionViewModel,
    deepLinkTarget: NotificationDeepLinkTarget? = null,
    onDeepLinkHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sampleViewModel: SampleRiderViewModel = viewModel(
        key = "sample_rider_vm_$currentUserId",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                SampleRiderViewModel(
                    sampleRepository = SampleRiderRepository(db),
                    golocalRepository = GolocalRepository(db),
                    currentUserId = currentUserId
                )
            }
        }
    )

    val profile by sampleViewModel.profile.collectAsStateWithLifecycle()
    val incomingRides by sampleViewModel.incomingRides.collectAsStateWithLifecycle()
    val activeRide by sampleViewModel.activeRide.collectAsStateWithLifecycle()
    val completedRides by sampleViewModel.completedRides.collectAsStateWithLifecycle()
    val rideHistory by sampleViewModel.rideHistory.collectAsStateWithLifecycle()
    val todayRidesCount by sampleViewModel.todayRidesCount.collectAsStateWithLifecycle()
    val totalSampleEarnings by sampleViewModel.totalSampleEarningsInr.collectAsStateWithLifecycle()
    val notifications by sampleViewModel.notifications.collectAsStateWithLifecycle()
    val gpsFix by sampleViewModel.gpsFix.collectAsStateWithLifecycle()
    val locationAccess by sampleViewModel.locationAccessState.collectAsStateWithLifecycle()
    val message by sampleViewModel.message.collectAsStateWithLifecycle()
    val otpError by sampleViewModel.otpError.collectAsStateWithLifecycle()
    val isBusy by sampleViewModel.isBusy.collectAsStateWithLifecycle()
    val lastCompleted by sampleViewModel.lastCompletedRide.collectAsStateWithLifecycle()
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf("home") }
    var showSosDialog by remember { mutableStateOf(false) }
    var previouslyAlertedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val state = LocationCoordinator.resolveAccessState(context)
        sampleViewModel.updateLocationAccessState(state)
    }

    LaunchedEffect(Unit) {
        val state = LocationCoordinator.resolveAccessState(context)
        sampleViewModel.updateLocationAccessState(state)
        if (state == LocationAccessState.PERMISSION_REQUIRED) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    LaunchedEffect(locationAccess, profile?.availabilityStatus, activeRide?.rideId, activeRide?.status) {
        val currentState = LocationCoordinator.resolveAccessState(context)
        sampleViewModel.updateLocationAccessState(currentState)
        sampleViewModel.syncForegroundTrackingService(context)
        if (currentState == LocationAccessState.GRANTED_READY) {
            val initialFix = LocationCoordinator.fetchBestCurrentLocation(context)
            if (initialFix != null) {
                sampleViewModel.broadcastGpsSample(initialFix)
            }
            val intervalMs = if (activeRide != null) 2_500L else 5_000L
            LocationCoordinator.observeLocationUpdates(context, intervalMs = intervalMs)
                .catch { /* keep resilient if GPS provider stops */ }
                .collect { fix ->
                    sampleViewModel.broadcastGpsSample(fix)
                }
        } else {
            sampleViewModel.updateGpsFix(null)
        }
    }

    LaunchedEffect(
        incomingRides,
        profile?.availabilityStatus,
        profile?.verificationStatus,
        activeRide?.rideId,
        prefs.notificationsEnabled,
        prefs.rideRequestSoundEnabled,
        prefs.rideRequestVibrationEnabled
    ) {
        val currentIds = incomingRides.map { it.rideId }.toSet()
        val clearedIds = previouslyAlertedIds - currentIds
        clearedIds.forEach { NotificationHelper.cancelRideRequestNotification(context, it) }

        val canAlert = profile?.isOnline() == true && activeRide == null && prefs.notificationsEnabled
        if (canAlert) {
            val fresh = incomingRides.filter { it.rideId !in previouslyAlertedIds }
            if (fresh.isNotEmpty()) {
                NotificationHelper.playSampleRequestSoundAndVibrate(
                    context = context,
                    soundEnabled = prefs.rideRequestSoundEnabled,
                    vibrationEnabled = prefs.rideRequestVibrationEnabled
                )
            }
            fresh.forEach { req ->
                NotificationHelper.displaySampleRideRequestAlert(
                    context = context,
                    rideId = req.rideId,
                    title = "SAMPLE RIDE • ₹${req.estimatedFareInr.toInt()} (${req.vehicleCategory.uppercase()})",
                    body = "${req.pickupLabel} -> ${req.destinationLabel} (${String.format(Locale.US, "%.1f", req.distanceKm)} km)",
                    soundEnabled = prefs.rideRequestSoundEnabled,
                    vibrationEnabled = prefs.rideRequestVibrationEnabled,
                    notificationsEnabled = prefs.notificationsEnabled
                )
            }
        }
        previouslyAlertedIds = currentIds
    }

    LaunchedEffect(deepLinkTarget) {
        val target = deepLinkTarget ?: return@LaunchedEffect
        if (target.isSampleRide || target.targetScreen == "requests") {
            selectedTab = "requests"
            onDeepLinkHandled()
        }
    }

    BackHandler(enabled = selectedTab != "home") { selectedTab = "home" }

    val loadedProfile = profile
    if (loadedProfile == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (loadedProfile.isSuspendedOrRevoked()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GolocalStatusBadge(
                label = "SAMPLE RIDER • ${loadedProfile.verificationStatus.uppercase()}",
                tone = "error"
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Sample Rider Access Disabled",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your Sample Rider privileges are currently ${loadedProfile.verificationStatus}. Contact your GoLocal Admin to re-enable your allowlist/sample access.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = { signOut(scope, context) }) {
                Text("Sign Out")
            }
        }
        return
    }

    val unreadCount = remember(notifications) { notifications.count { !it.read } }
    val navItems = remember(incomingRides.size, unreadCount) {
        listOf(
            Triple("home", "Home", Icons.Filled.Home),
            Triple(
                "requests",
                if (incomingRides.isEmpty()) "Requests" else "Requests (${incomingRides.size})",
                Icons.Filled.NotificationsActive
            ),
            Triple("earnings", "Earnings", Icons.Filled.AccountBalanceWallet),
            Triple(
                "notifications",
                if (unreadCount == 0) "Alerts" else "Alerts ($unreadCount)",
                Icons.Filled.Notifications
            ),
            Triple("profile", "Profile", Icons.Filled.Person)
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.navigationBars,
            bottomBar = {
                if (!useRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        navItems.forEach { (route, label, icon) ->
                            NavigationBarItem(
                                selected = selectedTab == route,
                                onClick = { selectedTab = route },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label, maxLines = 1) },
                                modifier = Modifier.testTag("sample_rider_nav_$route")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        Spacer(modifier = Modifier.height(12.dp))
                        navItems.forEach { (route, label, icon) ->
                            NavigationRailItem(
                                selected = selectedTab == route,
                                onClick = { selectedTab = route },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label, maxLines = 1) },
                                modifier = Modifier.testTag("sample_rider_rail_$route")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Box(modifier = Modifier.fillMaxSize().widthIn(max = 960.dp)) {
                        when (selectedTab) {
                            "home" -> SampleRiderHomeTab(
                                profile = loadedProfile,
                                gpsFix = gpsFix,
                                locationAccess = locationAccess,
                                activeRide = activeRide,
                                incomingCount = incomingRides.size,
                                todayRidesCount = todayRidesCount,
                                completedCount = completedRides.size,
                                totalSampleEarnings = totalSampleEarnings,
                                isBusy = isBusy,
                                otpError = otpError,
                                onToggleOnline = { sampleViewModel.toggleOnline(it) },
                                onRequestLocationPermission = { permissionLauncher.launch(requiredPermissions) },
                                onOpenLocationSettings = { LocationCoordinator.openLocationSettings(context) },
                                onOpenRequests = { selectedTab = "requests" },
                                onMarkArriving = { sampleViewModel.markArriving() },
                                onMarkArrived = { sampleViewModel.markArrivedAtPickup() },
                                onVerifyOtpAndStart = { sampleViewModel.verifyOtpAndStartTrip(it) },
                                onCompleteRide = {
                                    sampleViewModel.completeActiveRide {
                                        RiderLocationForegroundService.syncSampleTracking(
                                            context = context,
                                            sampleRiderUid = currentUserId,
                                            isOnline = loadedProfile.isOnline(),
                                            activeSampleRide = null
                                        )
                                    }
                                },
                                onCancelActiveRide = { reason -> sampleViewModel.cancelActiveRide(reason) },
                                onOpenSos = { showSosDialog = true }
                            )
                            "requests" -> SampleRiderRequestsTab(
                                profile = loadedProfile,
                                incomingRides = incomingRides,
                                hasActiveRide = activeRide != null,
                                isBusy = isBusy,
                                onAcceptRide = {
                                    sampleViewModel.acceptIncomingRide(it)
                                    selectedTab = "home"
                                },
                                onRejectRide = { sampleViewModel.rejectIncomingRide(it) }
                            )
                            "earnings" -> SampleRiderEarningsAndHistoryTab(
                                todayRidesCount = todayRidesCount,
                                completedRides = completedRides,
                                rideHistory = rideHistory,
                                totalSampleEarningsInr = totalSampleEarnings
                            )
                            "notifications" -> SampleRiderNotificationsTab(
                                notifications = notifications,
                                onMarkRead = { sampleViewModel.markNotificationRead(it) }
                            )
                            "profile" -> SampleRiderProfileAndSettingsTab(
                                profile = loadedProfile,
                                sessionViewModel = sessionViewModel,
                                sampleViewModel = sampleViewModel,
                                onOpenSos = { showSosDialog = true },
                                onSignOut = {
                                    RiderLocationForegroundService.stop(context)
                                    signOut(scope, context)
                                }
                            )
                        }
                    }

                    GolocalStatusMessageBanner(
                        message = message,
                        onDismiss = { sampleViewModel.clearMessage() },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                    )
                }
            }
        }
    }

    lastCompleted?.let { done ->
        AlertDialog(
            onDismissRequest = { sampleViewModel.dismissCompletedSummary() },
            title = { Text("Sample Ride Completed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GolocalStatusBadge(
                        label = "SAMPLE / SIMULATED SUMMARY",
                        tone = "warning"
                    )
                    Text("${done.pickupLabel} -> ${done.destinationLabel}", fontWeight = FontWeight.SemiBold)
                    Text("Distance: ${String.format(Locale.US, "%.1f", done.distanceKm)} km")
                    Text(
                        "Sample Fare: ₹${done.estimatedFareInr.toInt()}",
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Isolated from commercial revenue, commissions, and payouts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { sampleViewModel.dismissCompletedSummary() }) {
                    Text("Done")
                }
            }
        )
    }

    if (showSosDialog) {
        var sosNotes by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            title = { Text("Emergency Support SOS") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Sends your emergency note and current real GPS coordinates (if available) to GoLocal Operations.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (loadedProfile.emergencyContactPhone.isNotBlank()) {
                        Text(
                            "Primary Emergency Contact: ${loadedProfile.emergencyContactName.ifBlank { "Saved" }} (${loadedProfile.emergencyContactPhone})",
                            style = MaterialTheme.typography.labelMedium,
                            color = GolocalPrimaryBlue
                        )
                    }
                    OutlinedTextField(
                        value = sosNotes,
                        onValueChange = { sosNotes = it },
                        label = { Text("Describe emergency") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    onClick = {
                        sampleViewModel.triggerEmergencySos(sosNotes)
                        showSosDialog = false
                    }
                ) { Text("Send SOS") }
            },
            dismissButton = {
                TextButton(onClick = { showSosDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
internal fun SampleRiderHomeTab(
    profile: SampleRiderProfile,
    gpsFix: com.example.shared.DeviceGpsFix?,
    locationAccess: LocationAccessState,
    activeRide: SampleRide?,
    incomingCount: Int,
    todayRidesCount: Int,
    completedCount: Int,
    totalSampleEarnings: Double,
    isBusy: Boolean,
    otpError: String?,
    onToggleOnline: (Boolean) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenRequests: () -> Unit,
    onMarkArriving: () -> Unit,
    onMarkArrived: () -> Unit,
    onVerifyOtpAndStart: (String) -> Unit,
    onCompleteRide: () -> Unit,
    onCancelActiveRide: (String) -> Unit,
    onOpenSos: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header + SAMPLE RIDER Badge + Online Toggle
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = GolocalDeepNavy,
            modifier = Modifier.fillMaxWidth()
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Science,
                            contentDescription = "Sample Rider",
                            tint = Color(0xFFFCD34D)
                        )
                        GolocalStatusBadge(
                            label = "SAMPLE RIDER",
                            tone = "warning"
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (profile.isOnline()) "ONLINE" else "OFFLINE",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (profile.isOnline()) Color(0xFF86EFAC) else Color(0xFFCBD5E1)
                        )
                        Switch(
                            checked = profile.isOnline(),
                            onCheckedChange = { onToggleOnline(it) },
                            enabled = !isBusy,
                            modifier = Modifier.testTag("sample_rider_online_switch")
                        )
                    }
                }
                Text(
                    text = "${profile.displayName} • ${profile.vehicleCategory.uppercase()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = if (gpsFix != null) {
                        "Real GPS: ${String.format(Locale.US, "%.5f, %.5f", gpsFix.lat, gpsFix.lng)} (±${gpsFix.accuracyMeters.toInt()}m)"
                    } else {
                        "GPS location unavailable — real device fix required"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetBrainsMono,
                    color = if (gpsFix != null) Color(0xFFBFDBFE) else Color(0xFFFCA5A5)
                )
            }
        }

        // Real GPS Unavailable / Permission Banner (NO fake GPS coordinates)
        if (locationAccess != LocationAccessState.GRANTED_READY || gpsFix == null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = GolocalError.copy(alpha = 0.1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GolocalError.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .testTag("sample_gps_unavailable_card")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.GpsOff, contentDescription = "GPS Unavailable", tint = GolocalError)
                        Text(
                            text = "GPS Location Unavailable",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = GolocalError
                        )
                    }
                    Text(
                        text = when (locationAccess) {
                            LocationAccessState.PERMISSION_REQUIRED ->
                                "Grant location permission so GoLocal can display your real position and broadcast pickup/trip GPS."
                            LocationAccessState.GPS_DISABLED ->
                                "Device GPS / Location Services are turned off. Turn on GPS to go online."
                            LocationAccessState.GRANTED_READY ->
                                "Waiting for a real satellite/network GPS fix from this device..."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (locationAccess == LocationAccessState.PERMISSION_REQUIRED) {
                        Button(onClick = onRequestLocationPermission, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Grant Location Permission")
                        }
                    } else if (locationAccess == LocationAccessState.GPS_DISABLED) {
                        Button(onClick = onOpenLocationSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Enable Device GPS")
                        }
                    }
                }
            }
        }

        // KPI Summary Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SampleStatTile(
                title = "Today's Sample",
                value = todayRidesCount.toString(),
                modifier = Modifier.weight(1f)
            )
            SampleStatTile(
                title = "Completed",
                value = completedCount.toString(),
                modifier = Modifier.weight(1f)
            )
            SampleStatTile(
                title = "Sample Fare",
                value = "₹${totalSampleEarnings.toInt()}",
                modifier = Modifier.weight(1f)
            )
        }

        if (activeRide != null) {
            SampleRiderActiveTripCard(
                ride = activeRide,
                gpsFix = gpsFix,
                isBusy = isBusy,
                otpError = otpError,
                onNavigateToPickup = {
                    LocationCoordinator.openExternalNavigation(context, activeRide.pickupLat, activeRide.pickupLng, activeRide.pickupLabel)
                },
                onNavigateToDestination = {
                    LocationCoordinator.openExternalNavigation(context, activeRide.destinationLat, activeRide.destinationLng, activeRide.destinationLabel)
                },
                onMarkArriving = onMarkArriving,
                onMarkArrived = onMarkArrived,
                onVerifyOtpAndStart = onVerifyOtpAndStart,
                onCompleteRide = onCompleteRide,
                onCancelRide = onCancelActiveRide
            )
        } else {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (incomingCount > 0) GolocalLightBlue else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (incomingCount > 0) {
                            "$incomingCount Live Sample Ride Request(s) Waiting"
                        } else if (profile.isOnline()) {
                            "Listening for Incoming Sample Rides..."
                        } else {
                            "You are Offline"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Dispatch a sample ride from Customer Test Mode or the Admin Portal to experience live accept, GPS broadcast, Google Maps navigation, OTP verification, and ride completion.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onOpenRequests,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("open_sample_requests_button")
                    ) {
                        Text(if (incomingCount > 0) "View Incoming Requests ($incomingCount)" else "Open Requests Queue")
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onOpenSos,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalError),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("sample_rider_sos_button")
        ) {
            Icon(Icons.Filled.Warning, contentDescription = "Emergency SOS")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Emergency SOS Support")
        }
    }
}

@Composable
private fun SampleRiderActiveTripCard(
    ride: SampleRide,
    gpsFix: com.example.shared.DeviceGpsFix?,
    isBusy: Boolean,
    otpError: String?,
    onNavigateToPickup: () -> Unit,
    onNavigateToDestination: () -> Unit,
    onMarkArriving: () -> Unit,
    onMarkArrived: () -> Unit,
    onVerifyOtpAndStart: (String) -> Unit,
    onCompleteRide: () -> Unit,
    onCancelRide: (String) -> Unit
) {
    var otpInput by remember(ride.rideId) { mutableStateOf("") }
    val liveTripLoc = remember(gpsFix?.lat, gpsFix?.lng, ride.rideId) {
        gpsFix?.let {
            TripLocation(
                bookingId = ride.rideId,
                customerId = ride.customerId,
                driverId = ride.assignedSampleRiderId,
                lat = it.lat,
                lng = it.lng,
                accuracyMeters = it.accuracyMeters.toDouble(),
                isTestRide = true,
                isSimulated = true
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GolocalPrimaryBlue.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GolocalStatusBadge(
                    label = "ACTIVE SAMPLE • ${ride.status.uppercase()}",
                    tone = "info"
                )
                Text(
                    text = "₹${ride.estimatedFareInr.toInt()}",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Bold
                )
            }

            GolocalMapContainer(
                pickupLat = ride.pickupLat,
                pickupLng = ride.pickupLng,
                pickupLabel = ride.pickupLabel,
                destLat = ride.destinationLat,
                destLng = ride.destinationLng,
                destLabel = ride.destinationLabel,
                onlineDrivers = emptyList(),
                liveTripLocation = liveTripLoc,
                lowNetworkMode = false,
                mapHeight = 210.dp,
                userLat = gpsFix?.lat,
                userLng = gpsFix?.lng
            )

            Text("Customer: ${ride.customerName}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text("Pickup: ${ride.pickupLabel}", style = MaterialTheme.typography.bodyMedium)
            Text("Destination: ${ride.destinationLabel}", style = MaterialTheme.typography.bodyMedium)

            if (!ride.otpVerified) {
                Button(
                    onClick = onNavigateToPickup,
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalPrimaryBlue),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Filled.Navigation, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Navigate to Pickup (Google Maps)")
                }

                when (ride.status) {
                    "accepted" -> {
                        Button(
                            onClick = onMarkArriving,
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text("Start Heading to Pickup") }
                    }
                    "arriving" -> {
                        Button(
                            onClick = onMarkArrived,
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text("Mark Arrived at Pickup") }
                    }
                    "arrived" -> {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = GolocalWarningContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Enter 6-Digit Customer Sample OTP",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                OutlinedTextField(
                                    value = otpInput,
                                    onValueChange = { next ->
                                        if (next.length <= 6 && next.all { it.isDigit() }) otpInput = next
                                    },
                                    label = { Text("6-digit OTP") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth().testTag("sample_rider_otp_input")
                                )
                                if (!otpError.isNullOrBlank()) {
                                    Text(otpError, color = GolocalError, style = MaterialTheme.typography.bodySmall)
                                }
                                Button(
                                    onClick = { onVerifyOtpAndStart(otpInput) },
                                    enabled = !isBusy && otpInput.length == 6,
                                    colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sample_verify_otp_button")
                                ) { Text("Verify OTP & Start Sample Ride") }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { onCancelRide("Sample rider cancelled before trip start") },
                    enabled = !isBusy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalError),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Cancel Sample Ride") }
            } else {
                Button(
                    onClick = onNavigateToDestination,
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalPrimaryBlue),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Filled.Navigation, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Navigate to Destination (Google Maps)")
                }
                Button(
                    onClick = onCompleteRide,
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sample_complete_ride_button")
                ) { Text("Complete Sample Ride") }
            }
        }
    }
}

@Composable
private fun SampleStatTile(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
