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
                        communicationRepository = CommunicationRepository(db)
                    )
                }
            }
        )

        val profileState by driverViewModel.driverProfileState.collectAsStateWithLifecycle()
        val openRequests by driverViewModel.openRequests.collectAsStateWithLifecycle()
        val assignedBookings by driverViewModel.assignedBookings.collectAsStateWithLifecycle()
        val activeAssignedBooking by driverViewModel.activeAssignedBooking.collectAsStateWithLifecycle()
        val commissions by driverViewModel.commissions.collectAsStateWithLifecycle()
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
        LaunchedEffect(
            currentUserId,
            loadedProfile?.isOnline,
            loadedProfile?.verificationStatus,
            activeAssignedBooking?.id
        ) {
            val shouldTrackGps = loadedProfile != null && (
                (loadedProfile.isOnline && loadedProfile.verificationStatus == "verified") ||
                    activeAssignedBooking != null
                )
            if (shouldTrackGps && LocationCoordinator.resolveAccessState(context) == LocationAccessState.GRANTED_READY) {
                LocationCoordinator.observeLocationUpdates(context)
                    .catch { /* keep foreground UI resilient if GPS provider stops */ }
                    .collect { fix ->
                        driverViewModel.broadcastGpsSample(fix.lat, fix.lng, fix.accuracyMeters)
                    }
            } else {
                driverViewModel.stopLiveLocationBroadcast()
            }
        }

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
                    Text("Unable to load Rider profile: ${state.message}")
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
                            driverViewModel = driverViewModel
                        )
                    }
                } else {
                    var selectedTab by remember { mutableStateOf("home") }
                    LaunchedEffect(deepLinkTarget) {
                        val target = deepLinkTarget ?: return@LaunchedEffect
                        selectedTab = when (target.type) {
                            "chat", "booking" -> "home"
                            "request" -> "requests"
                            else -> "notifications"
                        }
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
                                                icon = { Icon(icon, contentDescription = label) },
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
                                    Box(modifier = Modifier.fillMaxSize().widthIn(max = 960.dp)) {
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
                                            "requests" -> RiderRequestsTab(profile = profile, driverViewModel = driverViewModel)
                                            "earnings" -> RiderEarningsHistoryTab(profile = profile, driverViewModel = driverViewModel)
                                            "notifications" -> RiderNotificationsTab(sessionViewModel = sessionViewModel)
                                            "profile" -> RiderProfileTab(
                                                profile = profile,
                                                sessionViewModel = sessionViewModel,
                                                onOpenEarnings = { selectedTab = "earnings" },
                                                onOpenNotifications = { selectedTab = "notifications" }
                                            )
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
