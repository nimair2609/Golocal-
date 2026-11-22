package com.example.rider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.authStateFlow
import com.example.data.model.AppRole
import com.example.data.model.Booking
import com.example.data.model.RegistrationStatus
import com.example.data.model.UiState
import com.example.data.repository.CommunicationRepository
import com.example.data.repository.GolocalRepository
import com.example.data.repository.PreferencesRepository
import com.example.data.tracking.RiderLocationForegroundService
import com.example.notifications.NotificationDeepLinkTarget
import com.example.notifications.NotificationHelper
import com.example.shared.ExperienceMode
import com.example.shared.GolocalRoleSplashScreen
import com.example.shared.LocationAccessState
import com.example.shared.LocationCoordinator
import com.example.shared.RoleAuthScreen
import com.example.ui.auth.NewUserRegistrationScreen
import com.example.ui.auth.signOut
import com.example.ui.components.GolocalStatusMessageBanner
import com.example.ui.theme.GolocalTheme
import com.example.updater.AppUpdateDialogHost
import com.example.updater.rememberAppUpdateController
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.DriverViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.catch

@Composable
fun RiderAppRoot(
    auth: FirebaseAuth = Firebase.auth,
    deepLinkTarget: NotificationDeepLinkTarget? = null,
    onDeepLinkHandled: () -> Unit = {}
) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    var splashDone by remember { mutableStateOf(auth.currentUser != null) }

    if (!splashDone) {
        GolocalTheme {
            GolocalRoleSplashScreen(
                mode = ExperienceMode.RIDER,
                onFinished = { splashDone = true }
            )
        }
        return
    }

    val user = currentUser
    if (user == null) {
        GolocalTheme {
            RoleAuthScreen(mode = ExperienceMode.RIDER, onAuthSuccess = {})
        }
    } else {
        key(user.uid) {
            RiderAuthenticatedShell(
                currentUserId = user.uid,
                displayName = user.displayName ?: "GoLocal Rider",
                email = user.email ?: "",
                photoUrl = user.photoUrl?.toString() ?: "",
                phoneNumber = user.phoneNumber ?: "",
                deepLinkTarget = deepLinkTarget,
                onDeepLinkHandled = onDeepLinkHandled
            )
        }
    }
}

@Composable
fun RiderAuthenticatedShell(
    currentUserId: String,
    displayName: String,
    email: String,
    photoUrl: String,
    phoneNumber: String = "",
    deepLinkTarget: NotificationDeepLinkTarget? = null,
    onDeepLinkHandled: () -> Unit = {}
) {
    val sessionViewModel: AppSessionViewModel = viewModel(
        key = "rider_session_$currentUserId",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                AppSessionViewModel(
                    repository = GolocalRepository(db),
                    prefsRepository = PreferencesRepository(app),
                    currentUserId = currentUserId,
                    userDisplayName = displayName,
                    userEmail = email,
                    userPhotoUrl = photoUrl,
                    userPhoneNumber = phoneNumber
                )
            }
        }
    )

    LaunchedEffect(currentUserId) {
        sessionViewModel.selectRole(AppRole.DRIVER)
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val registrationStatus by sessionViewModel.registrationStatus.collectAsStateWithLifecycle()
    val isSubmittingRegistration by sessionViewModel.isSubmittingRegistration.collectAsStateWithLifecycle()
    val registrationError by sessionViewModel.registrationError.collectAsStateWithLifecycle()
    val sessionMsg by sessionViewModel.statusMessage.collectAsStateWithLifecycle()

    LaunchedEffect(currentUserId, registrationStatus) {
        if (registrationStatus is RegistrationStatus.Completed) {
            NotificationHelper.registerCurrentFcmToken(
                context = context,
                userId = currentUserId,
                appVariant = "rider"
            )
            if (LocationCoordinator.resolveAccessState(context) == LocationAccessState.GRANTED_READY) {
                sessionViewModel.detectDeviceLocation(context)
            }
        }
    }

    val darkTheme = when (prefs.darkThemeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    GolocalTheme(darkTheme = darkTheme) {
        when (val reg = registrationStatus) {
            is RegistrationStatus.Checking -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (sessionMsg != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(sessionMsg ?: "", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { sessionViewModel.retryAccountSync() }) {
                                Text("Retry")
                            }
                            TextButton(onClick = { signOut(scope = scope, context = context) }) {
                                Text("Sign Out")
                            }
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
                return@GolocalTheme
            }

            is RegistrationStatus.PendingRegistration -> {
                NewUserRegistrationScreen(
                    verifiedMobileNumber = reg.verifiedMobileNumber.ifBlank { phoneNumber },
                    existingAccount = reg.existingAccount,
                    defaultFullName = displayName,
                    defaultEmail = email,
                    isSubmitting = isSubmittingRegistration,
                    serverErrorMessage = registrationError,
                    onSubmitRegistration = { fullName, invoiceEmail, emergencyContact, termsAccepted, mobileE164 ->
                        sessionViewModel.completeRegistration(
                            fullName = fullName,
                            invoiceEmail = invoiceEmail,
                            emergencyContactE164 = emergencyContact,
                            termsAccepted = termsAccepted,
                            mobileNumberOverride = mobileE164
                        )
                    },
                    onSignOut = { signOut(scope = scope, context = context) }
                )
                return@GolocalTheme
            }

            is RegistrationStatus.Completed -> Unit
        }

        val completedAccount = (registrationStatus as? RegistrationStatus.Completed)?.account
        if (completedAccount?.isSampleRiderAccount() == true) {
            if (com.example.BuildConfig.ENABLE_TEST_SANDBOX) {
                SampleRiderDashboardShell(
                    currentUserId = currentUserId,
                    sessionViewModel = sessionViewModel,
                    deepLinkTarget = deepLinkTarget,
                    onDeepLinkHandled = onDeepLinkHandled
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Sample Rider Sandbox Disabled",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Sample rider testing is disabled in commercial release builds.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = { signOut(scope = scope, context = context) }) {
                            Text("Sign out")
                        }
                    }
                }
            }
            return@GolocalTheme
        }

        val completedName = completedAccount?.fullName?.takeIf { it.isNotBlank() } ?: displayName
        val completedPhone = completedAccount?.effectiveMobileNumber()?.takeIf { it.isNotBlank() } ?: phoneNumber

        val driverViewModel: DriverViewModel = viewModel(
            key = "rider_vm_$currentUserId",
            factory = viewModelFactory {
                initializer {
                    val app = checkNotNull(this[APPLICATION_KEY])
                    val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                    DriverViewModel(
                        repository = GolocalRepository(db),
                        currentUserId = currentUserId,
                        communicationRepository = CommunicationRepository(db),
                        realtimeLocationRepository = com.example.data.repository.RealtimeLocationRepository(app)
                    )
                }
            }
        )

        val profileState by driverViewModel.driverProfileState.collectAsStateWithLifecycle()
        val testRiderRequestState by driverViewModel.testRiderRequestState.collectAsStateWithLifecycle()
        val testRiderProfile by driverViewModel.testRiderProfile.collectAsStateWithLifecycle()
        val openRequests by driverViewModel.openRequests.collectAsStateWithLifecycle()
        val assignedBookings by driverViewModel.assignedBookings.collectAsStateWithLifecycle()
        val activeAssignedBooking by driverViewModel.activeAssignedBooking.collectAsStateWithLifecycle()
        val commissions by driverViewModel.commissions.collectAsStateWithLifecycle()
        val gps by sessionViewModel.gpsLocation.collectAsStateWithLifecycle()
        val driverMsg by driverViewModel.message.collectAsStateWithLifecycle()
        val platformSettings by sessionViewModel.platformSettings.collectAsStateWithLifecycle()
        val updateController = rememberAppUpdateController(platformSettings = platformSettings)

        var previousActiveAssignedId by remember { mutableStateOf<String?>(null) }
        var completedTripSummary by remember { mutableStateOf<Booking?>(null) }

        LaunchedEffect(activeAssignedBooking?.id, assignedBookings) {
            val currentId = activeAssignedBooking?.id
            if (currentId != null) {
                previousActiveAssignedId = currentId
            } else if (previousActiveAssignedId != null) {
                val justDone = assignedBookings.firstOrNull {
                    it.id == previousActiveAssignedId && it.status == "completed"
                }
                if (justDone != null) {
                    completedTripSummary = justDone
                }
                previousActiveAssignedId = null
            }
        }

        val loadedProfile = (profileState as? UiState.Success)?.data
        val isCommercialVerified = loadedProfile?.verificationStatus == "verified"
        val inTestRiderMode = prefs.testRiderIntent || (testRiderProfile != null && !isCommercialVerified)

        LaunchedEffect(deepLinkTarget, testRiderProfile?.status) {
            val target = deepLinkTarget ?: return@LaunchedEffect
            val wantsTestMode = target.isTestRide ||
                target.type.equals("test_rider_status", ignoreCase = true) ||
                target.targetScreen.contains("test_rider", ignoreCase = true)
            if (wantsTestMode && testRiderProfile?.status == "approved" && !prefs.testRiderIntent) {
                sessionViewModel.setTestRiderIntent(true)
            }
        }

        var previouslyAlertedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

        LaunchedEffect(
            currentUserId,
            inTestRiderMode,
            loadedProfile?.isOnline,
            loadedProfile?.verificationStatus,
            testRiderProfile?.isOnline,
            testRiderProfile?.status,
            activeAssignedBooking?.id,
            activeAssignedBooking?.status
        ) {
            val isOnlineForMode = if (inTestRiderMode) {
                testRiderProfile?.isOnline == true && testRiderProfile?.status == "approved"
            } else {
                loadedProfile?.isOnline == true && loadedProfile.verificationStatus == "verified"
            }
            val shouldTrackGps = if (inTestRiderMode) {
                val tr = testRiderProfile
                val hasActiveTest = activeAssignedBooking?.let { it.isTestRide || it.testMode } == true
                tr != null && tr.status == "approved" && (tr.isOnline || hasActiveTest)
            } else {
                loadedProfile != null && (
                    (loadedProfile.isOnline && loadedProfile.verificationStatus == "verified") ||
                        activeAssignedBooking != null
                    )
            }
            RiderLocationForegroundService.syncTracking(
                context = context,
                driverId = currentUserId,
                isOnline = isOnlineForMode,
                activeBooking = activeAssignedBooking,
                isTestMode = inTestRiderMode
            )
            val gpsHealthNow = RiderLocationForegroundService.gpsHealth.value
            if (shouldTrackGps && LocationCoordinator.resolveAccessState(context) == LocationAccessState.GRANTED_READY) {
                if (!gpsHealthNow.isServiceRunning) {
                    val intervalMs = if (activeAssignedBooking != null) 2_500L else 4_000L
                    LocationCoordinator.observeLocationUpdates(context, intervalMs = intervalMs)
                        .catch { /* keep foreground UI resilient if GPS provider stops */ }
                        .collect { fix ->
                            if (!RiderLocationForegroundService.gpsHealth.value.isServiceRunning) {
                                driverViewModel.broadcastGpsSample(
                                    lat = fix.lat,
                                    lng = fix.lng,
                                    accuracyMeters = fix.accuracyMeters,
                                    speedMps = fix.speedMps,
                                    headingDeg = fix.bearingDeg,
                                    isTestRiderMode = inTestRiderMode
                                )
                            }
                        }
                }
            } else {
                val fallbackLat = gpsHealthNow.lastLat ?: gps.lat ?: 0.0
                val fallbackLng = gpsHealthNow.lastLng ?: gps.lng ?: 0.0
                driverViewModel.stopLiveLocationBroadcast(
                    lastLat = fallbackLat,
                    lastLng = fallbackLng
                )
            }
        }

        LaunchedEffect(
            openRequests,
            inTestRiderMode,
            loadedProfile?.isOnline,
            loadedProfile?.verificationStatus,
            activeAssignedBooking?.id,
            prefs.notificationsEnabled,
            prefs.rideRequestSoundEnabled,
            prefs.rideRequestVibrationEnabled
        ) {
            val currentIds = openRequests.map { it.id }.toSet()
            val clearedIds = previouslyAlertedIds - currentIds
            clearedIds.forEach { NotificationHelper.cancelRideRequestNotification(context, it) }

            val canAlert = !inTestRiderMode &&
                loadedProfile?.isOnline == true &&
                loadedProfile.verificationStatus == "verified" &&
                activeAssignedBooking == null

            if (!canAlert || !prefs.notificationsEnabled) {
                currentIds.forEach { NotificationHelper.cancelRideRequestNotification(context, it) }
                previouslyAlertedIds = currentIds
                return@LaunchedEffect
            }

            openRequests.filter { it.id !in previouslyAlertedIds }.forEach { req ->
                val netEst = com.example.domain.FareCalculator.estimateDriverNetEarningsInr(
                    grossFareInr = req.estimatedFareInr,
                    gstPercent = req.gstPercent,
                    commissionPercent = req.commissionPercent
                ).toInt()
                val reqTitle = "New ${req.vehicleCategory.uppercase()} Ride • Est. Net ₹$netEst"
                val reqBody = "${req.pickupLabel} -> ${req.destinationLabel} (${req.distanceKm} km)"
                NotificationHelper.displayRideRequestAlert(
                    context = context,
                    bookingId = req.id,
                    title = reqTitle,
                    body = reqBody,
                    isTestRide = false,
                    soundEnabled = prefs.rideRequestSoundEnabled,
                    vibrationEnabled = prefs.rideRequestVibrationEnabled,
                    notificationsEnabled = prefs.notificationsEnabled,
                    payload = com.example.notifications.RideRequestPayload.fromBooking(
                        booking = req,
                        title = reqTitle,
                        body = reqBody
                    )
                )
            }
            previouslyAlertedIds = currentIds
        }

        if (inTestRiderMode) {
            val approvedTestProfile = testRiderProfile?.takeIf { it.status == "approved" }
            if (approvedTestProfile != null) {
                RiderTestModeDashboardShell(
                    profile = approvedTestProfile,
                    sessionViewModel = sessionViewModel,
                    driverViewModel = driverViewModel,
                    lowNetworkMode = prefs.lowNetworkMode,
                    deepLinkTarget = deepLinkTarget,
                    onDeepLinkHandled = onDeepLinkHandled,
                    onSwitchToCommercialMode = { sessionViewModel.setTestRiderIntent(false) }
                )
            } else {
                RiderTestModeGateScreen(
                    requestState = testRiderRequestState,
                    defaultName = completedName,
                    defaultPhone = completedPhone,
                    defaultEmail = email,
                    statusMessage = driverMsg ?: sessionMsg,
                    onDismissMessage = {
                        driverViewModel.clearMessage()
                        sessionViewModel.clearStatusMessage()
                    },
                    onSubmitRequest = { name, mobile, reqEmail, cat, purpose, notes, deviceSummary ->
                        driverViewModel.submitTestRiderRequest(
                            fullName = name,
                            mobileNumber = mobile,
                            email = reqEmail,
                            vehicleCategory = cat,
                            testPurpose = purpose,
                            notes = notes,
                            deviceInfoSummary = deviceSummary
                        )
                    },
                    onSwitchToCommercial = { sessionViewModel.setTestRiderIntent(false) },
                    onSignOut = { signOut(scope = scope, context = context) }
                )
            }
        } else {
            when (val state = profileState) {
                is UiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is UiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Unable to load Rider profile: ${state.message}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { driverViewModel.refreshDispatchFeeds() },
                            modifier = Modifier.testTag("rider_profile_retry_button")
                        ) {
                            Text("Retry Sync")
                        }
                        TextButton(onClick = { signOut(scope = scope, context = context) }) {
                            Text("Sign Out")
                        }
                    }
                }

                is UiState.Success -> {
                    val profile = state.data
                    if (profile == null || profile.verificationStatus != "verified") {
                        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            GolocalStatusMessageBanner(
                                message = driverMsg ?: sessionMsg,
                                onDismiss = {
                                    driverViewModel.clearMessage()
                                    sessionViewModel.clearStatusMessage()
                                },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            RiderRegistrationAndVerificationGate(
                                existingProfile = profile,
                                displayName = completedName,
                                verifiedPhone = completedPhone,
                                driverViewModel = driverViewModel,
                                onSwitchToTestRiderMode = { sessionViewModel.setTestRiderIntent(true) }
                            )
                        }
                    } else {
                        var selectedTab by remember { mutableStateOf("home") }
                        var lastAutoFocusedBookingId by remember { mutableStateOf<String?>(null) }
                        LaunchedEffect(activeAssignedBooking?.id) {
                            val activeId = activeAssignedBooking?.id
                            if (activeId != null && activeId != lastAutoFocusedBookingId) {
                                lastAutoFocusedBookingId = activeId
                                selectedTab = "home"
                            } else if (activeId == null) {
                                lastAutoFocusedBookingId = null
                            }
                        }
                        LaunchedEffect(deepLinkTarget) {
                            val target = deepLinkTarget ?: return@LaunchedEffect
                            selectedTab = resolveRiderDeepLinkTab(target = target, inTestMode = false)
                            onDeepLinkHandled()
                        }
                        BackHandler(enabled = selectedTab != "home") { selectedTab = "home" }

                        val navItems = listOf(
                            Triple("home", "Home", Icons.Filled.Home),
                            Triple("requests", "Requests", Icons.Filled.NotificationsActive),
                            Triple("earnings", "Earnings", Icons.Filled.AccountBalanceWallet),
                            Triple("notifications", "Alerts", Icons.Filled.Notifications),
                            Triple("profile", "Profile", Icons.Filled.Person)
                        )

                        val fullScreenMapActive = selectedTab == "home"

                        val commercialNotifications by sessionViewModel.notificationsState.collectAsStateWithLifecycle()
                        val unreadRiderAlertCount = remember(commercialNotifications) {
                            commercialNotifications.count { !it.read && !it.isTestNotification() }
                        }

                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val useRail = maxWidth >= 700.dp
                            Scaffold(
                                contentWindowInsets = if (fullScreenMapActive) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
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
                                                    icon = {
                                                        if (route == "notifications" && unreadRiderAlertCount > 0) {
                                                            androidx.compose.material3.BadgedBox(
                                                                badge = {
                                                                    androidx.compose.material3.Badge {
                                                                        Text(unreadRiderAlertCount.coerceAtMost(99).toString())
                                                                    }
                                                                }
                                                            ) {
                                                                Icon(icon, contentDescription = label)
                                                            }
                                                        } else {
                                                            Icon(icon, contentDescription = label)
                                                        }
                                                    },
                                                    label = { Text(label) },
                                                    modifier = Modifier.testTag("rider_nav_$route")
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
                                                    modifier = Modifier.testTag("rider_rail_$route")
                                                )
                                            }
                                        }
                                    }

                                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .widthIn(max = 960.dp)
                                                .align(Alignment.Center)
                                        ) {
                                            when (selectedTab) {
                                                "home" -> RiderHomeDashboardTab(
                                                    profile = profile,
                                                    assignedBookings = assignedBookings,
                                                    openRequestsCount = openRequests.size,
                                                    commissions = commissions,
                                                    sessionViewModel = sessionViewModel,
                                                    driverViewModel = driverViewModel,
                                                    lowNetworkMode = prefs.lowNetworkMode,
                                                    onOpenRequests = { selectedTab = "requests" },
                                                    onOpenNotifications = { selectedTab = "notifications" }
                                                )
                                                "requests" -> {
                                                    val serviceHealth by RiderLocationForegroundService.gpsHealth.collectAsStateWithLifecycle()
                                                    val reqLat = serviceHealth.lastLat?.takeIf {
                                                        com.example.map.GpsBroadcastThrottle.isValidCoordinate(it, serviceHealth.lastLng ?: 0.0)
                                                    } ?: gps.lat ?: com.example.domain.BoraiLaunchConfig.DEFAULT_LAT
                                                    val reqLng = serviceHealth.lastLng?.takeIf {
                                                        com.example.map.GpsBroadcastThrottle.isValidCoordinate(serviceHealth.lastLat ?: 0.0, it)
                                                    } ?: gps.lng ?: com.example.domain.BoraiLaunchConfig.DEFAULT_LNG
                                                    RiderRequestsTab(
                                                        profile = profile,
                                                        driverViewModel = driverViewModel,
                                                        currentLat = reqLat,
                                                        currentLng = reqLng
                                                    )
                                                }
                                                "earnings" -> RiderEarningsHistoryTab(profile = profile, driverViewModel = driverViewModel)
                                                "notifications" -> RiderNotificationsTab(
                                                    sessionViewModel = sessionViewModel,
                                                    excludeTestNotifications = true,
                                                    onNotificationClick = { target ->
                                                        selectedTab = resolveRiderDeepLinkTab(target = target, inTestMode = false)
                                                    }
                                                )
                                                "profile" -> RiderProfileTab(
                                                    profile = profile,
                                                    sessionViewModel = sessionViewModel,
                                                    driverViewModel = driverViewModel,
                                                    onOpenEarnings = { selectedTab = "earnings" },
                                                    onOpenNotifications = { selectedTab = "notifications" }
                                                )
                                            }
                                        }

                                        val activeBannerBooking = activeAssignedBooking
                                        if (activeBannerBooking != null && selectedTab != "home") {
                                            androidx.compose.material3.Surface(
                                                onClick = { selectedTab = "home" },
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                                color = com.example.ui.theme.GolocalSuccess,
                                                shadowElevation = 8.dp,
                                                modifier = Modifier
                                                    .align(Alignment.BottomCenter)
                                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                                    .testTag("rider_active_trip_resume_banner")
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Text(
                                                        text = "Active Ride (${activeBannerBooking.status.replace('_', ' ').uppercase()}) • Return to Trip",
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = com.example.ui.theme.GolocalPureWhite
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
                                                .statusBarsPadding()
                                                .padding(horizontal = 16.dp, vertical = 70.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        completedTripSummary?.let { completed ->
            val matchingCommission = commissions.firstOrNull { it.bookingId == completed.id }
            RiderCompletedSettlementDialog(
                booking = completed,
                commission = matchingCommission,
                onSubmitFeedback = { stars, note ->
                    driverViewModel.submitCustomerFeedback(completed.id, stars, note)
                    completedTripSummary = null
                },
                onDismiss = { completedTripSummary = null }
            )
        }

        AppUpdateDialogHost(controller = updateController)
    }
}

internal fun resolveRiderDeepLinkTab(
    target: NotificationDeepLinkTarget,
    inTestMode: Boolean
): String {
    val normType = target.type.trim().lowercase()
    val normScreen = target.targetScreen.trim().lowercase()

    if (inTestMode) {
        return when {
            normScreen in setOf("rides", "requests", "queue") ||
                normType in setOf("request", "test_request", "new_ride_request") -> "rides"
            normScreen in setOf("summary", "earnings") -> "summary"
            normScreen == "profile" || normType == "test_rider_status" -> "profile"
            normScreen in setOf("home", "test_rider_dashboard", "active_trip", "booking", "chat") ||
                normType in setOf(
                    "chat",
                    "booking_chat",
                    "booking",
                    "booking_assigned",
                    "assigned",
                    "ride_accepted",
                    "test_ride",
                    "test_otp",
                    "trip_status"
                ) ||
                normType.startsWith("test_driver_booking") -> "home"
            else -> "alerts"
        }
    }

    return when {
        normScreen in setOf("requests", "rides") ||
            normType in setOf("request", "new_ride_request") -> "requests"
        normScreen == "earnings" ||
            normType in setOf("settlement", "payout") -> "earnings"
        normScreen == "profile" ||
            normType in setOf("kyc_status_update", "document_expiry_reminder", "kyc") -> "profile"
        normScreen in setOf("home", "active_trip", "booking", "chat", "rider_home") ||
            normType in setOf(
                "chat",
                "booking_chat",
                "booking",
                "booking_assigned",
                "assigned",
                "ride_accepted",
                "ride_offer",
                "driver_assigned",
                "trip_status",
                "booking_exception"
            ) ||
            normType.startsWith("driver_booking") -> "home"
        else -> "notifications"
    }
}
