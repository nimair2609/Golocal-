package com.example.customer

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
import com.example.data.repository.BusRepository
import com.example.data.repository.GolocalGovernanceRepository
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
import com.example.viewmodel.BusBookingViewModel
import com.example.viewmodel.CustomerViewModel
import com.example.viewmodel.GovernanceViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

@Composable
fun CustomerAppRoot(
    auth: FirebaseAuth = Firebase.auth,
    initialDeepLinkScreen: String? = null
) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    var splashDone by remember { mutableStateOf(false) }

    if (!splashDone) {
        GolocalTheme {
            GolocalRoleSplashScreen(
                mode = ExperienceMode.CUSTOMER,
                onFinished = { splashDone = true }
            )
        }
        return
    }

    val user = currentUser
    if (user == null) {
        GolocalTheme {
            RoleAuthScreen(mode = ExperienceMode.CUSTOMER, onAuthSuccess = {})
        }
    } else {
        key(user.uid) {
            CustomerAuthenticatedShell(
                currentUserId = user.uid,
                displayName = user.displayName ?: "GoLocal Passenger",
                email = user.email ?: "",
                photoUrl = user.photoUrl?.toString() ?: "",
                phoneNumber = user.phoneNumber ?: "",
                initialDeepLinkScreen = initialDeepLinkScreen
            )
        }
    }
}

@Composable
fun CustomerAuthenticatedShell(
    currentUserId: String,
    displayName: String,
    email: String,
    photoUrl: String,
    phoneNumber: String = "",
    initialDeepLinkScreen: String? = null
) {
    val sessionViewModel: AppSessionViewModel = viewModel(
        key = "cust_session_$currentUserId",
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

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(currentUserId) {
        sessionViewModel.selectRole(AppRole.CUSTOMER)
        com.example.notifications.FcmTokenSyncManager.syncCurrentToken(context, currentUserId, "customer")
    }

    val scope = rememberCoroutineScope()
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val registrationStatus by sessionViewModel.registrationStatus.collectAsStateWithLifecycle()
    val isSubmittingRegistration by sessionViewModel.isSubmittingRegistration.collectAsStateWithLifecycle()
    val registrationError by sessionViewModel.registrationError.collectAsStateWithLifecycle()
    val accountSyncStatusMsg by sessionViewModel.statusMessage.collectAsStateWithLifecycle()

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
                    if (accountSyncStatusMsg != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = accountSyncStatusMsg ?: "",
                                style = MaterialTheme.typography.bodyMedium
                            )
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
                    prefilledName = reg.prefilledName.ifBlank { displayName },
                    prefilledEmail = reg.prefilledEmail.ifBlank { email },
                    isPhoneAuthVerified = reg.isPhoneAuthVerified || phoneNumber.isNotBlank(),
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
                    onSubmitRegistrationWithPhone = { fullName, mobileE164, invoiceEmail, emergencyContact, termsAccepted ->
                        sessionViewModel.completeRegistration(
                            fullName = fullName,
                            invoiceEmail = invoiceEmail,
                            emergencyContactE164 = emergencyContact,
                            termsAccepted = termsAccepted,
                            enteredMobileE164 = mobileE164
                        )
                    },
                    onSignOut = { signOut(scope, context = context) }
                )
                return@GolocalTheme
            }

            is RegistrationStatus.Completed -> Unit
        }

        if (!prefs.customerOnboarded) {
            CustomerOnboardingScreen(
                onComplete = { sessionViewModel.setCustomerOnboarded(true) }
            )
            return@GolocalTheme
        }

        val completedName = (registrationStatus as? RegistrationStatus.Completed)?.account?.fullName
            ?.takeIf { it.isNotBlank() } ?: displayName

        val customerViewModel: CustomerViewModel = viewModel(
            key = "cust_vm_$currentUserId",
            factory = viewModelFactory {
                initializer {
                    val app = checkNotNull(this[APPLICATION_KEY])
                    val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                    CustomerViewModel(
                        repository = GolocalRepository(db),
                        prefsRepository = PreferencesRepository(app),
                        currentUserId = currentUserId,
                        customerName = completedName
                    )
                }
            }
        )
        LaunchedEffect(currentUserId, context) {
            customerViewModel.attachContext(context)
        }
        val governanceViewModel: GovernanceViewModel = viewModel(
            key = "cust_gov_$currentUserId",
            factory = viewModelFactory {
                initializer {
                    val app = checkNotNull(this[APPLICATION_KEY])
                    val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                    GovernanceViewModel(
                        repository = GolocalGovernanceRepository(db),
                        currentUserId = currentUserId,
                        role = "customer"
                    )
                }
            }
        )
        val busViewModel: BusBookingViewModel = viewModel(
            key = "cust_bus_$currentUserId",
            factory = viewModelFactory {
                initializer {
                    val app = checkNotNull(this[APPLICATION_KEY])
                    val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                    BusBookingViewModel(
                        repository = BusRepository(db),
                        currentUserId = currentUserId,
                        mode = "customer"
                    )
                }
            }
        )

        var selectedTab by remember { mutableStateOf("home") }
        var isBookingFlowOpen by remember { mutableStateOf(false) }
        LaunchedEffect(initialDeepLinkScreen) {
            val validTabs = setOf("home", "bookings", "bus", "explore", "notifications", "profile")
            if (initialDeepLinkScreen != null && initialDeepLinkScreen in validTabs) {
                isBookingFlowOpen = false
                selectedTab = initialDeepLinkScreen
            }
        }

        val accountState by sessionViewModel.userAccountState.collectAsStateWithLifecycle()
        val notifications by sessionViewModel.notificationsState.collectAsStateWithLifecycle()
        val platformSettings by sessionViewModel.platformSettings.collectAsStateWithLifecycle()
        val activeBooking by customerViewModel.activeBooking.collectAsStateWithLifecycle()
        val bookingsState by customerViewModel.myBookingsState.collectAsStateWithLifecycle()
        val verifiedDrivers by customerViewModel.verifiedDrivers.collectAsStateWithLifecycle()
        val statusMsg by sessionViewModel.statusMessage.collectAsStateWithLifecycle()
        val customerMsg by customerViewModel.actionMessage.collectAsStateWithLifecycle()
        val govMsg by governanceViewModel.message.collectAsStateWithLifecycle()
        val gps by sessionViewModel.gpsLocation.collectAsStateWithLifecycle()
        val updateController = rememberAppUpdateController(platformSettings = platformSettings)

        var showHelpDialog by remember { mutableStateOf(false) }

        BackHandler(enabled = isBookingFlowOpen || selectedTab != "home") {
            if (isBookingFlowOpen) isBookingFlowOpen = false
            else selectedTab = "home"
        }

        val firstName = ((accountState as? UiState.Success)?.data?.fullName ?: displayName)
            .trim()
            .substringBefore(" ")
            .ifBlank { "Friend" }
        val unreadCount = notifications.count { !it.read }

        val navItems = listOf(
            Triple("home", "Home", Icons.Filled.Home),
            Triple("bookings", "Bookings", Icons.Filled.ReceiptLong),
            Triple("bus", "Bus", Icons.Filled.DirectionsBus),
            Triple("explore", "Explore", Icons.Filled.Explore),
            Triple("profile", "Profile", Icons.Filled.Person)
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 700.dp
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (!useRail && !isBookingFlowOpen) {
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
                                    modifier = Modifier.testTag("customer_nav_$route")
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
                    if (useRail && !isBookingFlowOpen) {
                        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                            Spacer(modifier = Modifier.height(12.dp))
                            navItems.forEach { (route, label, icon) ->
                                NavigationRailItem(
                                    selected = selectedTab == route,
                                    onClick = { selectedTab = route },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("customer_rail_$route")
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        GolocalStatusMessageBanner(
                            message = govMsg ?: customerMsg ?: statusMsg,
                            onDismiss = {
                                governanceViewModel.clearMessage()
                                customerViewModel.clearMessage()
                                sessionViewModel.clearStatusMessage()
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                        com.example.notifications.NotificationPermissionBannerCard(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )

                        Box(modifier = Modifier.weight(1f).widthIn(max = 920.dp)) {
                            if (isBookingFlowOpen) {
                                CustomerBookRideScreen(
                                    sessionViewModel = sessionViewModel,
                                    customerViewModel = customerViewModel,
                                    governanceViewModel = governanceViewModel,
                                    onBackHome = { isBookingFlowOpen = false }
                                )
                            } else {
                                when (selectedTab) {
                                    "home" -> CustomerHomeTab(
                                        firstName = firstName,
                                        unreadNotifications = unreadCount,
                                        activeBooking = activeBooking,
                                        bookingsState = bookingsState,
                                        verifiedDrivers = verifiedDrivers,
                                        platformSettings = platformSettings,
                                        onOpenBookRide = { preset ->
                                            if (!preset.isNullOrBlank()) customerViewModel.setBookingType(preset)
                                            isBookingFlowOpen = true
                                        },
                                        onOpenBus = { selectedTab = "bus" },
                                        onOpenExplore = { selectedTab = "explore" },
                                        onOpenBookings = { selectedTab = "bookings" },
                                        onOpenNotifications = { selectedTab = "notifications" },
                                        onOpenHelp = { showHelpDialog = true },
                                        onQuickSos = {
                                            customerViewModel.triggerSos(
                                                bookingId = activeBooking?.id ?: "",
                                                lat = gps.lat ?: com.example.domain.BoraiLaunchConfig.DEFAULT_LAT,
                                                lng = gps.lng ?: com.example.domain.BoraiLaunchConfig.DEFAULT_LNG,
                                                notes = "Emergency SOS triggered from Customer Home"
                                            )
                                        }
                                    )
                                    "bookings" -> CustomerBookingsHistoryTab(
                                        customerViewModel = customerViewModel,
                                        governanceViewModel = governanceViewModel,
                                        onOpenBookRide = { isBookingFlowOpen = true }
                                    )
                                    "bus" -> CustomerBusBookingTab(
                                        customerName = completedName,
                                        busViewModel = busViewModel,
                                        governanceViewModel = governanceViewModel
                                    )
                                    "explore" -> CustomerExploreTab(
                                        customerViewModel = customerViewModel,
                                        onSelectServicePreset = { type, category ->
                                            customerViewModel.setBookingType(type)
                                            customerViewModel.selectCategory(category)
                                            isBookingFlowOpen = true
                                        }
                                    )
                                    "notifications" -> CustomerNotificationsTab(sessionViewModel = sessionViewModel)
                                    "profile" -> CustomerProfileTab(sessionViewModel = sessionViewModel)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showHelpDialog) {
            var details by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showHelpDialog = false },
                title = { Text("GoLocal Help & Support Desk") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "24x7 Helpline: ${platformSettings?.helplineNumber ?: "+91-1800-103-0000"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = details,
                            onValueChange = { details = it },
                            label = { Text("Describe your question or ride issue") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        sessionViewModel.submitSupportOrDeletion("customer_help", details)
                        showHelpDialog = false
                    }) { Text("Submit") }
                },
                dismissButton = { TextButton(onClick = { showHelpDialog = false }) { Text("Close") } }
            )
        }

        AppUpdateDialogHost(controller = updateController)
    }
}
