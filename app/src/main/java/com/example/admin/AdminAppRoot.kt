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
import com.example.data.model.PortalRole
import com.example.data.repository.BusRepository
import com.example.data.repository.GolocalGovernanceRepository
import com.example.data.repository.GolocalRepository
import com.example.data.repository.PreferencesRepository
import com.example.shared.ExperienceMode
import com.example.shared.GolocalRoleSplashScreen
import com.example.shared.RoleAuthScreen
import com.example.ui.auth.signOut
import com.example.ui.components.GoLocalLogoMark
import com.example.ui.components.GolocalStatusMessageBanner
import com.example.ui.theme.*
import com.example.updater.AppUpdateDialogHost
import com.example.updater.rememberAppUpdateController
import com.example.viewmodel.AdminViewModel
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.BusBookingViewModel
import com.example.viewmodel.GovernanceViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

@Composable
fun AdminAppRoot(
    auth: FirebaseAuth = Firebase.auth,
    initialDeepLinkModule: String? = null
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
                initialDeepLinkModule = initialDeepLinkModule
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
    initialDeepLinkModule: String? = null
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
        com.example.notifications.FcmTokenSyncManager.syncCurrentToken(context, currentUserId, "admin")
    }

    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val portalRole by sessionViewModel.portalRole.collectAsStateWithLifecycle()
    val isAdminChecked by sessionViewModel.isAdminChecked.collectAsStateWithLifecycle()
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

        if (portalRole == PortalRole.NONE) {
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
                        Text("Unauthorized Portal Account", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Signed in as $adminIdentity, which does not hold GoLocal Owner Admin or Operations Staff privileges. All Portal routes are strictly restricted.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { signOut(scope) },
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
                        realtimeTrackingRepository = com.example.data.tracking.RealtimeTrackingRepository(app)
                    )
                }
            }
        )
        LaunchedEffect(currentUserId, context) {
            adminViewModel.attachContext(context)
        }
        val governanceViewModel: GovernanceViewModel = viewModel(
            key = "admin_gov_$currentUserId",
            factory = viewModelFactory {
                initializer {
                    val app = checkNotNull(this[APPLICATION_KEY])
                    val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                    GovernanceViewModel(
                        repository = GolocalGovernanceRepository(db),
                        currentUserId = currentUserId,
                        role = if (portalRole == PortalRole.OWNER_ADMIN) "admin" else "operations"
                    )
                }
            }
        )
        val busViewModel: BusBookingViewModel = viewModel(
            key = "admin_bus_$currentUserId",
            factory = viewModelFactory {
                initializer {
                    val app = checkNotNull(this[APPLICATION_KEY])
                    val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                    BusBookingViewModel(
                        repository = BusRepository(db),
                        currentUserId = currentUserId,
                        mode = "admin"
                    )
                }
            }
        )

        val adminMsg by adminViewModel.message.collectAsStateWithLifecycle()
        val govMsg by governanceViewModel.message.collectAsStateWithLifecycle()
        val busMsg by busViewModel.message.collectAsStateWithLifecycle()
        val platformSettings by adminViewModel.platformSettings.collectAsStateWithLifecycle()
        val updateController = rememberAppUpdateController(platformSettings = platformSettings)
        val defaultModule = if (portalRole == PortalRole.OWNER_ADMIN) "dashboard" else "ops_map"
        var selectedModule by remember(portalRole) { mutableStateOf(defaultModule) }
        val modules = remember(portalRole) {
            if (portalRole == PortalRole.OWNER_ADMIN) {
                listOf(
                    Triple("dashboard", "Dashboard", Icons.Filled.Dashboard),
                    Triple("ops_map", "Live Ops Map", Icons.Filled.Map),
                    Triple("customers", "Customers", Icons.Filled.People),
                    Triple("riders", "Riders & KYC", Icons.Filled.LocalTaxi),
                    Triple("bookings", "Bookings & Fraud", Icons.Filled.DirectionsCar),
                    Triple("bus_admin", "Bus & Tariffs", Icons.Filled.DirectionsBus),
                    Triple("services", "Zones & Fares", Icons.Filled.Tune),
                    Triple("payments", "Payments", Icons.Filled.AccountBalance),
                    Triple("reports", "Reports", Icons.Filled.Analytics),
                    Triple("settings", "Settings & RBAC", Icons.Filled.Settings)
                )
            } else {
                listOf(
                    Triple("ops_map", "Live Ops Map", Icons.Filled.Map),
                    Triple("bookings", "Dispatch & Escalations", Icons.Filled.DirectionsCar)
                )
            }
        }
        LaunchedEffect(initialDeepLinkModule, modules) {
            val validModules = modules.map { it.first }.toSet()
            if (initialDeepLinkModule != null && initialDeepLinkModule in validModules) {
                selectedModule = initialDeepLinkModule
            }
        }
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

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
                    onSignOut = { signOut(scope, context = context) }
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
                                message = busMsg ?: govMsg ?: adminMsg,
                                onDismiss = {
                                    busViewModel.clearMessage()
                                    governanceViewModel.clearMessage()
                                    adminViewModel.clearMessage()
                                },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            com.example.notifications.NotificationPermissionBannerCard(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            AdminModuleContent(selectedModule, portalRole, adminViewModel, governanceViewModel, busViewModel)
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
                                message = busMsg ?: govMsg ?: adminMsg,
                                onDismiss = {
                                    busViewModel.clearMessage()
                                    governanceViewModel.clearMessage()
                                    adminViewModel.clearMessage()
                                },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            com.example.notifications.NotificationPermissionBannerCard(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            AdminModuleContent(selectedModule, portalRole, adminViewModel, governanceViewModel, busViewModel)
                        }
                    }
                }
            }
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
    portalRole: PortalRole,
    adminViewModel: AdminViewModel,
    governanceViewModel: GovernanceViewModel,
    busViewModel: BusBookingViewModel
) {
    when (selectedModule) {
        "ops_map" -> AdminLiveOperationsMapModule(adminViewModel, portalRole)
        "bookings" -> AdminBookingsModule(adminViewModel, governanceViewModel, portalRole)
        "dashboard" -> if (portalRole == PortalRole.OWNER_ADMIN) AdminDashboardModule(adminViewModel) else AdminLiveOperationsMapModule(adminViewModel, portalRole)
        "customers" -> if (portalRole == PortalRole.OWNER_ADMIN) AdminCustomersModule(adminViewModel) else AdminLiveOperationsMapModule(adminViewModel, portalRole)
        "riders" -> if (portalRole == PortalRole.OWNER_ADMIN) AdminRidersModule(adminViewModel, governanceViewModel) else AdminLiveOperationsMapModule(adminViewModel, portalRole)
        "bus_admin" -> if (portalRole == PortalRole.OWNER_ADMIN) AdminBusGovernanceModule(busViewModel) else AdminLiveOperationsMapModule(adminViewModel, portalRole)
        "services" -> if (portalRole == PortalRole.OWNER_ADMIN) AdminServicesModule(adminViewModel) else AdminLiveOperationsMapModule(adminViewModel, portalRole)
        "payments" -> if (portalRole == PortalRole.OWNER_ADMIN) AdminPaymentsModule(adminViewModel) else AdminLiveOperationsMapModule(adminViewModel, portalRole)
        "reports" -> if (portalRole == PortalRole.OWNER_ADMIN) AdminReportsModule(adminViewModel) else AdminLiveOperationsMapModule(adminViewModel, portalRole)
        "settings" -> if (portalRole == PortalRole.OWNER_ADMIN) AdminSettingsModule(adminViewModel) else AdminLiveOperationsMapModule(adminViewModel, portalRole)
    }
}
