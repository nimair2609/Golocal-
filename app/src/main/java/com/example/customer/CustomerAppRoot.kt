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
import com.example.BuildConfig
import com.example.R
import com.example.authStateFlow
import com.example.data.model.AppRole
import com.example.data.model.UiState
import com.example.data.repository.GolocalRepository
import com.example.data.repository.PreferencesRepository
import com.example.shared.ExperienceMode
import com.example.shared.GolocalRoleSplashScreen
import com.example.shared.RoleAuthScreen
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
fun CustomerAppRoot(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    var splashDone by remember { mutableStateOf(false) }
    var guestSignInError by remember { mutableStateOf<String?>(null) }

    // Debug APKs bypass the Google sign-in screen by creating a Firebase anonymous session.
    // A real Firebase UID is required by the existing customer repository and Firestore rules.
    // Release builds set GOLOCAL_SKIP_LOGIN=false and continue to use the original Google flow.
    LaunchedEffect(auth) {
        if (BuildConfig.GOLOCAL_SKIP_LOGIN && auth.currentUser == null) {
            auth.signInAnonymously().addOnFailureListener { error ->
                guestSignInError = error.localizedMessage ?: "Could not start guest session."
            }
        }
    }

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
            if (BuildConfig.GOLOCAL_SKIP_LOGIN && guestSignInError == null) {
                GuestSessionLoadingScreen()
            } else {
                if (guestSignInError != null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "Guest access could not start: $guestSignInError",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            RoleAuthScreen(mode = ExperienceMode.CUSTOMER, onAuthSuccess = {})
                        }
                    }
                } else {
                    RoleAuthScreen(mode = ExperienceMode.CUSTOMER, onAuthSuccess = {})
                }
            }
        }
    } else {
        key(user.uid) {
            CustomerAuthenticatedShell(
                currentUserId = user.uid,
                displayName = user.displayName ?: "GoLocal Passenger",
                email = user.email ?: "",
                photoUrl = user.photoUrl?.toString() ?: ""
            )
        }
    }
}

@Composable
private fun GuestSessionLoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator()
            Text("Starting GOLOCAL guest session鈥�")
            Text(
                text = "Temporary debug access. Google login is unchanged in release builds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CustomerAuthenticatedShell(
    currentUserId: String,
    displayName: String,
    email: String,
    photoUrl: String
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
                    userPhotoUrl = photoUrl
                )
            }
        }
    )

    LaunchedEffect(currentUserId) {
        sessionViewModel.selectRole(AppRole.CUSTOMER)
    }

    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val darkTheme = when (prefs.darkThemeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    GolocalTheme(darkTheme = darkTheme) {
        if (!prefs.customerOnboarded) {
            CustomerOnboardingScreen(
                onComplete = { sessionViewModel.setCustomerOnboarded(true) }
            )
            return@GolocalTheme
        }

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
                        customerName = displayName
                    )
                }
            }
        )

        var selectedTab by remember { mutableStateOf("home") }
        var isBookingFlowOpen by remember { mutableStateOf(false) }

        val accountState by sessionViewModel.userAccountState.collectAsStateWithLifecycle()
        val notifications by sessionViewModel.notificationsState.collectAsStateWithLifecycle()
        val platformSettings by sessionViewModel.platformSettings.collectAsStateWithLifecycle()
        val activeBooking by customerViewModel.activeBooking.collectAsStateWithLifecycle()
        val bookingsState by customerViewModel.myBookingsState.collectAsStateWithLifecycle()
        val verifiedDrivers by customerViewModel.verifiedDrivers.collectAsStateWithLifecycle()
        val statusMsg by sessionViewModel.statusMessage.collectAsStateWithLifecycle()
        val customerMsg by customerViewModel.actionMessage.collectAsStateWithLifecycle()
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
            Triple("explore", "Explore", Icons.Filled.Explore),
            Triple("notifications", "Alerts", Icons.Filled.Notifications),
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
                            message = customerMsg ?: statusMsg,
                            onDismiss = {
                                customerViewModel.clearMessage()
                                sessionViewModel.clearStatusMessage()
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )

                        Box(modifier = Modifier.weight(1f).widthIn(max = 920.dp)) {
                            if (isBookingFlowOpen) {
                                CustomerBookRideScreen(
                                    sessionViewModel = sessionViewModel,
                                    customerViewModel = customerViewModel,
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
                                        }
                                    )
                                    "bookings" -> CustomerBookingsHistoryTab(
                                        customerViewModel = customerViewModel,
                                        onOpenBookRide = { isBookingFlowOpen = true }
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
