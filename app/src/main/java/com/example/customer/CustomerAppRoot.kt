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
import com.example.data.repository.CommunicationRepository
import com.example.data.repository.GolocalRepository
import com.example.data.repository.PreferencesRepository
import com.example.notifications.NotificationDeepLinkTarget
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
import com.example.viewmodel.CustomerViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

@Composable
fun CustomerAppRoot(
    auth: FirebaseAuth = Firebase.auth,
    deepLinkTarget: NotificationDeepLinkTarget? = null,
    onDeepLinkHandled: () -> Unit = {}
) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    var splashDone by remember { mutableStateOf(auth.currentUser != null) }

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
                deepLinkTarget = deepLinkTarget,
                onDeepLinkHandled = onDeepLinkHandled
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
    deepLinkTarget: NotificationDeepLinkTarget? = null,
    onDeepLinkHandled: () -> Unit = {}
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

    LaunchedEffect(currentUserId) {
        sessionViewModel.selectRole(AppRole.CUSTOMER)
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val registrationStatus by sessionViewModel.registrationStatus.collectAsStateWithLifecycle()
    val isSubmittingRegistration by sessionViewModel.isSubmittingRegistration.collectAsStateWithLifecycle()
    val registrationError by sessionViewModel.registrationError.collectAsStateWithLifecycle()
    val accountSyncStatusMsg by sessionViewModel.statusMessage.collectAsStateWithLifecycle()

    LaunchedEffect(currentUserId, registrationStatus) {
        if (registrationStatus is RegistrationStatus.Completed) {
            com.example.notifications.NotificationHelper.registerCurrentFcmToken(
                context = context,
                userId = currentUserId,
                appVariant = "customer"
            )
            if (com.example.shared.LocationCoordinator.resolveAccessState(context) ==
                com.example.shared.LocationAccessState.GRANTED_READY
            ) {
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
                        customerName = completedName,
                        communicationRepository = CommunicationRepository(db),
                        busRepository = BusRepository(db)
                    )
                }
            }
        )

        var selectedTab by remember { mutableStateOf("home") }
        var isBookingFlowOpen by remember { mutableStateOf(false) }
        var isBusFlowOpen by remember { mutableStateOf(false) }

        val accountState by sessionViewModel.userAccountState.collectAsStateWithLifecycle()
        val notifications by sessionViewModel.notificationsState.collectAsStateWithLifecycle()
        val platformSettings by sessionViewModel.platformSettings.collectAsStateWithLifecycle()
        val activeBooking by customerViewModel.activeBooking.collectAsStateWithLifecycle()
        val activeTripLocation by customerViewModel.activeTripLocation.collectAsStateWithLifecycle()
        val activeCallContact by customerViewModel.activeCallContact.collectAsStateWithLifecycle()
        val activeChatMessages by customerViewModel.activeChatMessages.collectAsStateWithLifecycle()
        val unreadChatCount by customerViewModel.unreadChatCount.collectAsStateWithLifecycle()
        val bookingsState by customerViewModel.myBookingsState.collectAsStateWithLifecycle()
        val ratingsGiven by customerViewModel.myRatings.collectAsStateWithLifecycle()
        val verifiedDrivers by customerViewModel.verifiedDrivers.collectAsStateWithLifecycle()
        val statusMsg by sessionViewModel.statusMessage.collectAsStateWithLifecycle()
        val customerMsg by customerViewModel.actionMessage.collectAsStateWithLifecycle()
        val gps by sessionViewModel.gpsLocation.collectAsStateWithLifecycle()
        val updateController = rememberAppUpdateController(platformSettings = platformSettings)
        val verifiedPhone = (accountState as? UiState.Success)?.data?.effectiveMobileNumber().orEmpty()
        val allBookings = (bookingsState as? UiState.Success)?.data.orEmpty()

        var showHelpDialog by remember { mutableStateOf(false) }
        var previousActiveId by remember { mutableStateOf<String?>(null) }
        var completedReceiptTarget by remember { mutableStateOf<com.example.data.model.Booking?>(null) }

        LaunchedEffect(activeBooking?.id, allBookings) {
            val curId = activeBooking?.id
            if (curId != null) {
                previousActiveId = curId
            } else if (previousActiveId != null) {
                val justCompleted = allBookings.firstOrNull {
                    it.id == previousActiveId && it.status == "completed" && it.customerRating == null
                }
                if (justCompleted != null) {
                    completedReceiptTarget = justCompleted
                }
                previousActiveId = null
            }
        }

        LaunchedEffect(activeBooking?.id, verifiedPhone) {
            val active = activeBooking
            if (active != null && !active.isTestRide && !active.testMode && verifiedPhone.isNotBlank()) {
                customerViewModel.syncMyContactForActiveBooking(verifiedPhone)
            }
        }

        LaunchedEffect(deepLinkTarget) {
            val target = deepLinkTarget ?: return@LaunchedEffect
            when (target.type) {
                "chat", "booking" -> {
                    isBusFlowOpen = false
                    isBookingFlowOpen = true
                }
                "bus_booking" -> {
                    isBookingFlowOpen = false
                    isBusFlowOpen = true
                }
                else -> {
                    isBookingFlowOpen = false
                    isBusFlowOpen = false
                    selectedTab = "notifications"
                }
            }
            onDeepLinkHandled()
        }

        BackHandler(enabled = isBookingFlowOpen || isBusFlowOpen || selectedTab != "home") {
            when {
                isBookingFlowOpen -> isBookingFlowOpen = false
                isBusFlowOpen -> isBusFlowOpen = false
                else -> selectedTab = "home"
            }
        }

        val firstName = ((accountState as? UiState.Success)?.data?.fullName ?: displayName)
            .trim()
            .substringBefore(" ")
            .ifBlank { "Friend" }
        val unreadCount = notifications.count { !it.read }

        val navItems = listOf(
            Triple("home", "Home", Icons.Filled.Home),
            Triple("bookings", "Bookings", Icons.Filled.ReceiptLong),
            Triple("explore", "Explore", Icons.Filled.Explore),
            Triple("notifications", "Alerts", Icons.Filled.Notifications),
            Triple("profile", "Profile", Icons.Filled.Person)
        )

        val fullScreenMapActive = isBookingFlowOpen || (!isBusFlowOpen && selectedTab == "home")

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 700.dp
            Scaffold(
                contentWindowInsets = if (fullScreenMapActive) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
                bottomBar = {
                    if (!useRail && !isBookingFlowOpen && !isBusFlowOpen) {
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
                    if (useRail && !isBookingFlowOpen && !isBusFlowOpen) {
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

                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Box(modifier = Modifier.fillMaxSize().widthIn(max = 960.dp)) {
                            if (isBookingFlowOpen) {
                                CustomerBookRideScreen(
                                    sessionViewModel = sessionViewModel,
                                    customerViewModel = customerViewModel,
                                    onBackHome = { isBookingFlowOpen = false }
                                )
                            } else if (isBusFlowOpen) {
                                CustomerBusBookingScreen(
                                    customerViewModel = customerViewModel,
                                    onBackHome = { isBusFlowOpen = false }
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
                                        onOpenExplore = { selectedTab = "explore" },
                                        onOpenBookings = { selectedTab = "bookings" },
                                        onOpenNotifications = { selectedTab = "notifications" },
                                        onOpenHelp = { showHelpDialog = true },
                                        onQuickSos = {
                                            customerViewModel.triggerSos(
                                                bookingId = activeBooking?.id ?: "",
                                                lat = gps.lat ?: 22.90,
                                                lng = gps.lng ?: 88.39,
                                                notes = "Emergency SOS triggered from Customer Home"
                                            )
                                        },
                                        currentUserId = currentUserId,
                                        activeTripLocation = activeTripLocation,
                                        lowNetworkMode = prefs.lowNetworkMode,
                                        activeCallContact = activeCallContact,
                                        activeChatMessages = activeChatMessages,
                                        unreadChatCount = unreadChatCount,
                                        onSendChatMessage = { booking, msg -> customerViewModel.sendChatMessage(booking, msg) },
                                        onMarkChatRead = { customerViewModel.markActiveChatRead() },
                                        onOpenBusBooking = { isBusFlowOpen = true },
                                        userLat = gps.lat,
                                        userLng = gps.lng,
                                        gpsError = gps.error,
                                        onRequestLocationDetect = { sessionViewModel.detectDeviceLocation(context) }
                                    )
                                    "bookings" -> CustomerBookingsHistoryTab(
                                        customerViewModel = customerViewModel,
                                        onOpenBookRide = { isBookingFlowOpen = true },
                                        onOpenBusBooking = { isBusFlowOpen = true }
                                    )
                                    "explore" -> CustomerExploreTab(
                                        customerViewModel = customerViewModel,
                                        onSelectServicePreset = { type, category ->
                                            customerViewModel.setBookingType(type)
                                            customerViewModel.selectCategory(category)
                                            isBookingFlowOpen = true
                                        },
                                        onOpenBusBooking = { isBusFlowOpen = true }
                                    )
                                    "notifications" -> CustomerNotificationsTab(sessionViewModel = sessionViewModel)
                                    "profile" -> CustomerProfileTab(
                                        sessionViewModel = sessionViewModel,
                                        bookings = allBookings,
                                        ratingsGiven = ratingsGiven,
                                        onOpenBookings = { selectedTab = "bookings" },
                                        onOpenNotifications = { selectedTab = "notifications" }
                                    )
                                }
                            }
                        }

                        GolocalStatusMessageBanner(
                            message = customerMsg ?: statusMsg,
                            onDismiss = {
                                customerViewModel.clearMessage()
                                sessionViewModel.clearStatusMessage()
                            },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 68.dp)
                        )
                    }
                }
            }
        }

        completedReceiptTarget?.let { target ->
            RideCompletedReceiptDialog(
                booking = target,
                onSubmitRating = { stars, behavior, vehicle, punctuality, safety, review ->
                    customerViewModel.submitRating(
                        booking = target,
                        stars = stars,
                        review = review,
                        driverBehaviorRating = behavior,
                        vehicleConditionRating = vehicle,
                        punctualityRating = punctuality,
                        safetyRating = safety
                    )
                    completedReceiptTarget = null
                },
                onDismiss = { completedReceiptTarget = null }
            )
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
