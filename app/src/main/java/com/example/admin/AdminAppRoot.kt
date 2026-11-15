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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.authStateFlow
import com.example.data.model.AppRole
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
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

@Composable
fun AdminAppRoot(auth: FirebaseAuth = Firebase.auth) {
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
                photoUrl = user.photoUrl?.toString() ?: ""
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
    photoUrl: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

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
                    userPhotoUrl = photoUrl
                )
            }
        }
    )

    LaunchedEffect(currentUserId) {
        sessionViewModel.selectRole(AppRole.ADMIN)
    }

    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val isAdmin by sessionViewModel.isAdmin.collectAsStateWithLifecycle()
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

        if (!isAdmin) {
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
                            text = "Signed in as $email, which does not hold GoLocal Admin privileges. All Admin Portal routes are strictly restricted.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { signOut(credentialManager, scope) },
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
                    AdminViewModel(GolocalRepository(db))
                }
            }
        )

        val adminMsg by adminViewModel.message.collectAsStateWithLifecycle()
        val platformSettings by adminViewModel.platformSettings.collectAsStateWithLifecycle()
        val updateController = rememberAppUpdateController(platformSettings = platformSettings)
        var selectedModule by remember { mutableStateOf("dashboard") }
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

        BackHandler(enabled = selectedModule != "dashboard") { selectedModule = "dashboard" }

        val modules = listOf(
            Triple("dashboard", "Dashboard", Icons.Filled.Dashboard),
            Triple("customers", "Customers", Icons.Filled.People),
            Triple("riders", "Riders & KYC", Icons.Filled.LocalTaxi),
            Triple("bookings", "Bookings", Icons.Filled.DirectionsCar),
            Triple("services", "Services & Fares", Icons.Filled.Tune),
            Triple("payments", "Payments", Icons.Filled.AccountBalance),
            Triple("reports", "Reports", Icons.Filled.Analytics),
            Triple("settings", "Settings", Icons.Filled.Settings)
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wideScreen = maxWidth >= 700.dp

            val sidebarContent: @Composable () -> Unit = {
                AdminSidebar(
                    email = email,
                    modules = modules,
                    selectedModule = selectedModule,
                    onSelect = {
                        selectedModule = it
                        scope.launch { drawerState.close() }
                    },
                    onSignOut = { signOut(credentialManager, scope) }
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
                            AdminModuleContent(selectedModule, adminViewModel)
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
                                message = adminMsg,
                                onDismiss = { adminViewModel.clearMessage() },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            AdminModuleContent(selectedModule, adminViewModel)
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
    email: String,
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
            Text(email, style = MaterialTheme.typography.labelSmall, color = GolocalLightBlue, maxLines = 1)
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
    adminViewModel: AdminViewModel
) {
    when (selectedModule) {
        "dashboard" -> AdminDashboardModule(adminViewModel)
        "customers" -> AdminCustomersModule(adminViewModel)
        "riders" -> AdminRidersModule(adminViewModel)
        "bookings" -> AdminBookingsModule(adminViewModel)
        "services" -> AdminServicesModule(adminViewModel)
        "payments" -> AdminPaymentsModule(adminViewModel)
        "reports" -> AdminReportsModule(adminViewModel)
        "settings" -> AdminSettingsModule(adminViewModel)
    }
}
