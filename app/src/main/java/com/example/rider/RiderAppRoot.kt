package com.example.rider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.data.model.RegistrationStatus
import com.example.data.model.UiState
import com.example.data.repository.GolocalRepository
import com.example.data.repository.PreferencesRepository
import com.example.shared.ExperienceMode
import com.example.shared.GolocalRoleSplashScreen
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

@Composable
fun RiderAppRoot(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    var splashDone by remember { mutableStateOf(false) }

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
                phoneNumber = user.phoneNumber ?: ""
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
    phoneNumber: String = ""
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

    val scope = rememberCoroutineScope()
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val registrationStatus by sessionViewModel.registrationStatus.collectAsStateWithLifecycle()
    val isSubmittingRegistration by sessionViewModel.isSubmittingRegistration.collectAsStateWithLifecycle()
    val registrationError by sessionViewModel.registrationError.collectAsStateWithLifecycle()
    val sessionMsg by sessionViewModel.statusMessage.collectAsStateWithLifecycle()

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
                            TextButton(onClick = { signOut(scope) }) {
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
                    isSubmitting = isSubmittingRegistration,
                    serverErrorMessage = registrationError,
                    onSubmitRegistration = { fullName, invoiceEmail, emergencyContact, termsAccepted ->
                        sessionViewModel.completeRegistration(
                            fullName = fullName,
                            invoiceEmail = invoiceEmail,
                            emergencyContactE164 = emergencyContact,
                            termsAccepted = termsAccepted
                        )
                    },
                    onSignOut = { signOut(scope) }
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
                        currentUserId = currentUserId
                    )
                }
            }
        )

        val profileState by driverViewModel.driverProfileState.collectAsStateWithLifecycle()
        val openRequests by driverViewModel.openRequests.collectAsStateWithLifecycle()
        val assignedBookings by driverViewModel.assignedBookings.collectAsStateWithLifecycle()
        val commissions by driverViewModel.commissions.collectAsStateWithLifecycle()
        val driverMsg by driverViewModel.message.collectAsStateWithLifecycle()
        val platformSettings by sessionViewModel.platformSettings.collectAsStateWithLifecycle()
        val updateController = rememberAppUpdateController(platformSettings = platformSettings)

        Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { basePadding ->
            Box(modifier = Modifier.fillMaxSize().padding(basePadding)) {
                when (val state = profileState) {
                    is UiState.Loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is UiState.Error -> {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Unable to load Rider profile: ${state.message}")
                        }
                    }
                    is UiState.Success -> {
                        val profile = state.data
                        if (profile == null || profile.verificationStatus != "verified") {
                            Column(modifier = Modifier.fillMaxSize()) {
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
                            BackHandler(enabled = selectedTab != "home") { selectedTab = "home" }

                            val navItems = listOf(
                                Triple("home", "Home", Icons.Filled.Home),
                                Triple("requests", "Requests", Icons.Filled.NotificationsActive),
                                Triple("earnings", "Earnings", Icons.Filled.AccountBalanceWallet),
                                Triple("notifications", "Alerts", Icons.Filled.Notifications),
                                Triple("profile", "Profile", Icons.Filled.Person)
                            )

                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val useRail = maxWidth >= 700.dp
                                Scaffold(
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

                                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                            GolocalStatusMessageBanner(
                                                message = driverMsg ?: sessionMsg,
                                                onDismiss = {
                                                    driverViewModel.clearMessage()
                                                    sessionViewModel.clearStatusMessage()
                                                },
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                            )
                                            Box(modifier = Modifier.weight(1f).widthIn(max = 920.dp)) {
                                                when (selectedTab) {
                                                    "home" -> RiderHomeDashboardTab(
                                                        profile = profile,
                                                        assignedBookings = assignedBookings,
                                                        openRequestsCount = openRequests.size,
                                                        commissions = commissions,
                                                        sessionViewModel = sessionViewModel,
                                                        driverViewModel = driverViewModel,
                                                        lowNetworkMode = prefs.lowNetworkMode,
                                                        onOpenRequests = { selectedTab = "requests" }
                                                    )
                                                    "requests" -> RiderRequestsTab(profile = profile, driverViewModel = driverViewModel)
                                                    "earnings" -> RiderEarningsHistoryTab(profile = profile, driverViewModel = driverViewModel)
                                                    "notifications" -> RiderNotificationsTab(sessionViewModel = sessionViewModel)
                                                    "profile" -> RiderProfileTab(profile = profile, sessionViewModel = sessionViewModel)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        AppUpdateDialogHost(controller = updateController)
    }
}
