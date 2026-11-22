package com.example.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.data.repository.BusRepository
import com.example.data.repository.CommunicationRepository
import com.example.data.repository.GolocalRepository
import com.example.data.repository.PreferencesRepository
import com.example.notifications.NotificationDeepLinkTarget
import com.example.notifications.NotificationHelper
import com.example.shared.ExperienceMode
import com.example.shared.GolocalRoleSplashScreen
import com.example.shared.NotificationPermissionBannerCard
import com.example.shared.RoleAuthScreen
import com.example.ui.auth.signOut
import com.example.ui.components.GoLocalLogoMark
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.components.GolocalStatusMessageBanner
import com.example.ui.theme.*
import com.example.updater.AppUpdateDialogHost
import com.example.updater.rememberAppUpdateController
import com.example.viewmodel.AdminViewModel
import com.example.viewmodel.AppSessionViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

@Composable
fun AdminAppRoot(
    auth: FirebaseAuth = Firebase.auth,
    initialDeepLinkModule: String? = null,
    deepLinkTarget: NotificationDeepLinkTarget? = null,
    onDeepLinkHandled: () -> Unit = {}
) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    var splashDone by remember { mutableStateOf(false) }

    if (!splashDone) {
        GolocalTheme {
            GolocalRoleSplashScreen(
                mode = ExperienceMode.ADMIN,
                onFinished = { splashDone = true }
            )
        }
        return
    }

    val user = currentUser
    if (user == null) {
        GolocalTheme {
            RoleAuthScreen(mode = ExperienceMode.ADMIN, onAuthSuccess = {})
        }
    } else {
        key(user.uid) {
            AdminAuthenticatedShell(
                currentUserId = user.uid,
                displayName = user.displayName ?: "GoLocal Admin",
                email = user.email ?: "",
                phoneNumber = user.phoneNumber ?: "",
                photoUrl = user.photoUrl?.toString() ?: "",
                initialDeepLinkModule = initialDeepLinkModule,
                deepLinkTarget = deepLinkTarget,
                onDeepLinkHandled = onDeepLinkHandled
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminAuthenticatedShell(
    currentUserId: String,
    displayName: String,
    email: String,
    phoneNumber: String = "",
    photoUrl: String,
    initialDeepLinkModule: String? = null,
    deepLinkTarget: NotificationDeepLinkTarget? = null,
    onDeepLinkHandled: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val adminIdentity = phoneNumber.ifBlank { email }.ifBlank { currentUserId }

    val sessionViewModel: AppSessionViewModel = viewModel(
        key = "admin_session_$currentUserId",
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
        sessionViewModel.selectRole(AppRole.ADMIN)
    }

    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val isAdmin by sessionViewModel.isAdmin.collectAsStateWithLifecycle()
    val isOpsStaff by sessionViewModel.isOperationsStaff.collectAsStateWithLifecycle()
    val isAdminChecked by sessionViewModel.isAdminChecked.collectAsStateWithLifecycle()
    val canEnterPortal = isAdmin || isOpsStaff
    val actorRoleLabel = if (isAdmin) "admin" else "ops_staff"

    LaunchedEffect(currentUserId, canEnterPortal) {
        if (canEnterPortal) {
            NotificationHelper.registerCurrentFcmToken(
                context = context,
                userId = currentUserId,
                appVariant = "admin"
            )
        }
    }
    val darkTheme = when (prefs.darkThemeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    GolocalTheme(darkTheme = darkTheme) {
        if (!isAdminChecked) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@GolocalTheme
        }

        if (!canEnterPortal) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp,
                    modifier = Modifier.widthIn(max = 460.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Filled.GppBad, contentDescription = null, tint = GolocalError, modifier = Modifier.size(48.dp))
                        Text("Unauthorized Administrator Account", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Signed in as $adminIdentity, which does not hold GoLocal Admin privileges. All Admin Portal routes are strictly restricted.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { signOut(scope = scope, context = context) },
                            colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                            modifier = Modifier.fillMaxWidth().testTag("admin_unauthorized_signout")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign Out")
                        }
                    }
                }
            }
            return@GolocalTheme
        }

        val adminViewModel: AdminViewModel = viewModel(
            key = "admin_portal_$currentUserId",
            factory = viewModelFactory {
                initializer {
                    val app = checkNotNull(this[APPLICATION_KEY])
                    val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                    AdminViewModel(
                        repository = GolocalRepository(db),
                        communicationRepository = CommunicationRepository(db),
                        busRepository = BusRepository(db),
                        sampleRiderRepository = com.example.data.repository.SampleRiderRepository(db)
                    )
                }
            }
        )

        val adminMsg by adminViewModel.message.collectAsStateWithLifecycle()
        val platformSettings by adminViewModel.platformSettings.collectAsStateWithLifecycle()
        val testBookings by adminViewModel.testBookings.collectAsStateWithLifecycle()
        val adminNotifications by sessionViewModel.notificationsState.collectAsStateWithLifecycle()
        val unreadAdminNotifCount = remember(adminNotifications) { adminNotifications.count { !it.read } }
        val updateController = rememberAppUpdateController(platformSettings = platformSettings)
        val defaultModule = if (isAdmin || !com.example.BuildConfig.ENABLE_TEST_SANDBOX) "dashboard" else "test_rides"
        var selectedModule by remember(isAdmin) { mutableStateOf(defaultModule) }
        var showAdminNotificationsDialog by remember { mutableStateOf(false) }
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

        LaunchedEffect(testBookings.size, platformSettings.testRideModeEnabled, platformSettings.testAutoAssignEnabled) {
            if (com.example.BuildConfig.ENABLE_TEST_SANDBOX &&
                platformSettings.testRideModeEnabled &&
                platformSettings.testAutoAssignEnabled
            ) {
                adminViewModel.tryAutoAssignPendingTestRides(actorRoleLabel)
            }
        }

        val modules = remember(isAdmin) {
            buildList {
                if (isAdmin) {
                    add(Triple("dashboard", "Dashboard", Icons.Filled.Dashboard))
                    if (com.example.BuildConfig.ENABLE_TEST_SANDBOX) {
                        add(Triple("test_rides", "Test Rides", Icons.Filled.Science))
                    }
                    add(Triple("customers", "Customers", Icons.Filled.People))
                    add(Triple("riders", "Riders & KYC", Icons.Filled.LocalTaxi))
                    add(Triple("bookings", "Bookings", Icons.Filled.DirectionsCar))
                    add(Triple("buses", "Private Bus", Icons.Filled.DirectionsBus))
                    add(Triple("services", "Services & Fares", Icons.Filled.Tune))
                    add(Triple("payments", "Payments", Icons.Filled.AccountBalance))
                    add(Triple("reports", "Reports", Icons.Filled.Analytics))
                    add(Triple("settings", "Settings", Icons.Filled.Settings))
                } else {
                    if (com.example.BuildConfig.ENABLE_TEST_SANDBOX) {
                        add(Triple("test_rides", "Test Rides", Icons.Filled.Science))
                    }
                    add(Triple("dashboard", "Dashboard", Icons.Filled.Dashboard))
                    add(Triple("bookings", "Bookings", Icons.Filled.DirectionsCar))
                }
            }
        }

        LaunchedEffect(initialDeepLinkModule, deepLinkTarget, isAdmin) {
            val resolved = resolveAdminDeepLinkModule(initialDeepLinkModule, deepLinkTarget)
            if (resolved != null && modules.any { it.first == resolved }) {
                selectedModule = resolved
            }
            if (!initialDeepLinkModule.isNullOrBlank() || deepLinkTarget != null) {
                onDeepLinkHandled()
            }
        }

        BackHandler(enabled = selectedModule != defaultModule) { selectedModule = defaultModule }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wideScreen = maxWidth >= 700.dp

            val sidebarContent: @Composable () -> Unit = {
                AdminSidebar(
                    identity = adminIdentity,
                    modules = modules,
                    selectedModule = selectedModule,
                    onSelect = {
                        selectedModule = it
                        scope.launch { drawerState.close() }
                    },
                    onSignOut = { signOut(scope = scope, context = context) }
                )
            }

            if (wideScreen) {
                Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
                    Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                        Box(modifier = Modifier.width(236.dp).fillMaxHeight()) {
                            sidebarContent()
                        }
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            GolocalStatusMessageBanner(
                                message = adminMsg,
                                onDismiss = { adminViewModel.clearMessage() },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = modules.firstOrNull { it.first == selectedModule }?.second ?: "Admin Portal",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                OutlinedButton(
                                    onClick = { showAdminNotificationsDialog = true },
                                    modifier = Modifier.testTag("admin_notifications_button")
                                ) {
                                    Icon(Icons.Filled.Notifications, contentDescription = "Admin Alerts", modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (unreadAdminNotifCount > 0) "Alerts ($unreadAdminNotifCount)" else "Alerts")
                                }
                            }
                            NotificationPermissionBannerCard(
                                title = "Enable Operations & SOS Push Alerts",
                                description = "Receive real-time SOS emergencies, Test Rider requests, KYC submissions, and booking exceptions.",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            AdminModuleContent(
                                selectedModule = selectedModule,
                                adminViewModel = adminViewModel,
                                isOwnerAdmin = isAdmin,
                                actorRoleLabel = actorRoleLabel
                            )
                        }
                    }
                }
            } else {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(modifier = Modifier.width(260.dp), drawerContainerColor = GolocalDeepNavy) {
                            sidebarContent()
                        }
                    }
                ) {
                    Scaffold(
                        contentWindowInsets = WindowInsets.safeDrawing,
                        topBar = {
                            TopAppBar(
                                title = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        GoLocalLogoMark(modifier = Modifier.size(28.dp), onDarkBackground = true)
                                        Text(
                                            modules.firstOrNull { it.first == selectedModule }?.second ?: "Admin Portal",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                },
                                navigationIcon = {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(Icons.Filled.Menu, contentDescription = "Open Admin Menu")
                                    }
                                },
                                actions = {
                                    IconButton(
                                        onClick = { showAdminNotificationsDialog = true },
                                        modifier = Modifier.testTag("admin_notifications_button")
                                    ) {
                                        if (unreadAdminNotifCount > 0) {
                                            BadgedBox(badge = { Badge { Text(unreadAdminNotifCount.coerceAtMost(99).toString()) } }) {
                                                Icon(Icons.Filled.Notifications, contentDescription = "Admin Alerts", tint = GolocalPureWhite)
                                            }
                                        } else {
                                            Icon(Icons.Filled.Notifications, contentDescription = "Admin Alerts", tint = GolocalPureWhite)
                                        }
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = GolocalDeepNavy,
                                    titleContentColor = GolocalPureWhite,
                                    navigationIconContentColor = GolocalPureWhite
                                )
                            )
                        }
                    ) { padding ->
                        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                            GolocalStatusMessageBanner(
                                message = adminMsg,
                                onDismiss = { adminViewModel.clearMessage() },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            NotificationPermissionBannerCard(
                                title = "Enable Operations & SOS Push Alerts",
                                description = "Receive real-time SOS emergencies, Test Rider requests, KYC submissions, and booking exceptions.",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            AdminModuleContent(
                                selectedModule = selectedModule,
                                adminViewModel = adminViewModel,
                                isOwnerAdmin = isAdmin,
                                actorRoleLabel = actorRoleLabel
                            )
                        }
                    }
                }
            }
        }

        if (showAdminNotificationsDialog) {
            val unreadIds = adminNotifications.filter { !it.read }.map { it.id }
            AlertDialog(
                onDismissRequest = { showAdminNotificationsDialog = false },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Admin & Ops Alerts", style = MaterialTheme.typography.titleMedium)
                        if (unreadIds.isNotEmpty()) {
                            TextButton(onClick = { sessionViewModel.markAllNotificationsRead(unreadIds) }) {
                                Text("Mark all read")
                            }
                        }
                    }
                },
                text = {
                    if (adminNotifications.isEmpty()) {
                        GolocalEmptyState(
                            title = "No Recent Alerts",
                            message = "SOS alerts, new rider KYC applications, Test Rider requests, complaints, and booking exceptions appear here."
                        )
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(adminNotifications.size, key = { idx -> adminNotifications[idx].id }) { idx ->
                                val notif = adminNotifications[idx]
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (notif.read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (!notif.read) sessionViewModel.markNotificationRead(notif.id)
                                            val target = NotificationDeepLinkTarget.fromAppNotification(notif)
                                            val resolved = resolveAdminDeepLinkModule(null, target)
                                            if (resolved != null && modules.any { it.first == resolved }) {
                                                selectedModule = resolved
                                            }
                                            showAdminNotificationsDialog = false
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(notif.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                            if (!notif.read) GolocalStatusBadge(label = "NEW", tone = "info")
                                        }
                                        Text(notif.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAdminNotificationsDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }

        AppUpdateDialogHost(controller = updateController)
    }
}

@Composable
private fun AdminSidebar(
    identity: String,
    modules: List<Triple<String, String, ImageVector>>,
    selectedModule: String,
    onSelect: (String) -> Unit,
    onSignOut: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GolocalDeepNavy)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp)
            ) {
                GoLocalLogoMark(modifier = Modifier.size(38.dp), onDarkBackground = true)
                Column {
                    Text("GoLocal", style = MaterialTheme.typography.titleMedium, color = GolocalPureWhite)
                    Text("Admin Portal", style = MaterialTheme.typography.labelSmall, color = GolocalLightBlue)
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
            Spacer(modifier = Modifier.height(4.dp))

            modules.forEach { (id, label, icon) ->
                val active = selectedModule == id
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (active) GolocalPrimaryBlue else Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(id) }
                        .testTag("admin_nav_$id")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(icon, contentDescription = label, tint = if (active) GolocalPureWhite else GolocalLightBlue, modifier = Modifier.size(18.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) GolocalPureWhite else GolocalLightBlue)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(identity, style = MaterialTheme.typography.labelSmall, color = GolocalLightBlue, maxLines = 1)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = 0.1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onSignOut)
                    .testTag("admin_logout_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout", tint = GolocalPureWhite, modifier = Modifier.size(18.dp))
                    Text("Logout", style = MaterialTheme.typography.labelLarge, color = GolocalPureWhite)
                }
            }
        }
    }
}

@Composable
private fun AdminModuleContent(
    selectedModule: String,
    adminViewModel: AdminViewModel,
    isOwnerAdmin: Boolean,
    actorRoleLabel: String
) {
    when (selectedModule) {
        "dashboard" -> AdminDashboardModule(adminViewModel)
        "test_rides" -> if (com.example.BuildConfig.ENABLE_TEST_SANDBOX) {
            AdminTestRideModule(
                adminViewModel = adminViewModel,
                isOwnerAdmin = isOwnerAdmin,
                actorRoleLabel = actorRoleLabel
            )
        } else {
            AdminDashboardModule(adminViewModel)
        }
        "customers" -> AdminCustomersModule(adminViewModel)
        "riders" -> AdminRidersModule(adminViewModel)
        "bookings" -> AdminBookingsModule(adminViewModel)
        "buses" -> AdminBusManagementModule(adminViewModel)
        "services" -> AdminServicesModule(adminViewModel)
        "payments" -> AdminPaymentsModule(adminViewModel)
        "reports" -> AdminReportsModule(adminViewModel)
        "settings" -> AdminSettingsModule(adminViewModel)
    }
}

internal fun resolveAdminDeepLinkModule(
    initialDeepLinkModule: String?,
    deepLinkTarget: NotificationDeepLinkTarget?
): String? {
    val screenHint = deepLinkTarget?.targetScreen?.trim()?.takeIf { it.isNotBlank() }
        ?: initialDeepLinkModule?.trim()?.takeIf { it.isNotBlank() }
    val normalizedScreen = screenHint?.lowercase()
    val fromScreen = when (normalizedScreen) {
        "dashboard", "home" -> "dashboard"
        "test_rides", "test_ride", "test_otp", "test_riders", "test_rider", "test_rider_request", "test_rider_status", "test_rider_dashboard" -> "test_rides"
        "customers", "users", "support" -> "customers"
        "riders", "drivers", "kyc" -> "riders"
        "bookings", "trips", "rides", "sos", "emergency", "safety", "complaints", "disputes", "appeals" -> "bookings"
        "buses", "bus" -> "buses"
        "services", "fares", "service_areas" -> "services"
        "payments", "settlements", "refunds" -> "payments"
        "reports", "notifications", "analytics" -> "reports"
        "settings", "admins", "staff", "operations_staff", "audit", "updates" -> "settings"
        else -> null
    }
    if (fromScreen != null) return fromScreen

    return when (deepLinkTarget?.type?.trim()?.lowercase()) {
        "sos", "sos_alert", "emergency", "booking", "booking_exception", "booking_cancelled", "dispute", "dispute_opened", "complaint", "complaint_opened", "cancellation_appeal", "appeal" -> "bookings"
        "test_ride", "test_otp", "test_rider", "test_rider_request", "test_rider_status" -> "test_rides"
        "kyc", "driver", "pending_verification", "new_rider_application" -> "riders"
        "payment", "settlement", "refund" -> "payments"
        "bus", "bus_booking" -> "buses"
        "update" -> "settings"
        else -> null
    }
}
