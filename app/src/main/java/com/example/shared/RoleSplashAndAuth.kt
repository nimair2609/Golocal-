package com.example.shared

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.repository.ClientPreferences
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import com.example.ui.auth.GoogleAuthController
import com.example.ui.auth.GoogleAuthUiState
import com.example.ui.auth.PhoneAuthController
import com.example.ui.auth.PhoneAuthFormContent
import com.example.ui.auth.PhoneAuthStep
import com.example.ui.auth.signOut
import com.example.ui.components.GoLocalFullLogo
import com.example.ui.components.GoLocalLogoMark
import com.example.ui.components.LanguageSelectorRow
import com.example.ui.theme.*
import com.example.updater.AppUpdateDialogHost
import com.example.updater.CheckForAppUpdatesCard
import com.example.updater.rememberAppUpdateController
import com.example.viewmodel.AppSessionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

enum class ExperienceMode(
    val appTitle: String,
    val roleLabel: String,
    val headline: String,
    val description: String,
    val buttonLabel: String,
    val footerNotice: String
) {
    CUSTOMER(
        appTitle = "GoLocal",
        roleLabel = "Customer Mobility & Local Services",
        headline = "Sign In with Google or Mobile OTP",
        description = "Book verified Totos, Autos, Bikes, Cabs, Railway Station, Airport & Private Bus rides across West Bengal using your Google account or verified mobile number.",
        buttonLabel = "Send OTP",
        footerNotice = "New users complete a one-time safety & invoice setup right after sign-in."
    ),
    RIDER(
        appTitle = "GoLocal Rider",
        roleLabel = "Verified Driver & Partner App",
        headline = "Partner Login (Google or Mobile OTP)",
        description = "Sign in or register as a GoLocal driver or bus operator partner using Google or Mobile OTP to accept rides, manage buses, and track earnings.",
        buttonLabel = "Send Partner OTP",
        footerNotice = "Admin document verification (Driving Licence, RC, Insurance & Permits) is mandatory before going online."
    ),
    ADMIN(
        appTitle = "GoLocal Admin Portal",
        roleLabel = "Operations & Governance",
        headline = "Admin Sign-In (Google or Mobile OTP)",
        description = "Restricted management portal for rider & bus KYC verification, fare & commission rules, fraud flags, disputes, SOS safety response, and audit logs.",
        buttonLabel = "Send Admin OTP",
        footerNotice = "Public self-registration is disabled. Access is enforced by Firebase Auth & Firestore /admins/{uid} rules."
    )
}

@Composable
fun GolocalRoleSplashScreen(
    mode: ExperienceMode,
    onFinished: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "splash_alpha"
    )

    LaunchedEffect(mode) {
        visible = true
        delay(850)
        onFinished()
    }

    val gradientColors = when (mode) {
        ExperienceMode.ADMIN -> listOf(Color(0xFF07152E), GolocalDeepNavy, GolocalPrimaryBlue)
        ExperienceMode.RIDER -> listOf(GolocalDeepNavy, GolocalPrimaryBlue, GolocalSecondaryBlue)
        ExperienceMode.CUSTOMER -> listOf(GolocalDeepNavy, GolocalPrimaryBlue, GolocalRoyalBlueBright)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = gradientColors))
            .clickable { onFinished() }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("splash_screen_${mode.name.lowercase()}"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .alpha(alpha)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            GoLocalFullLogo(
                modifier = Modifier.size(124.dp)
            )
            Text(
                text = mode.appTitle,
                style = MaterialTheme.typography.headlineLarge,
                color = GolocalPureWhite,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "by NR Next",
                style = MaterialTheme.typography.titleMedium,
                color = GolocalLightBlue
            )
            Surface(
                color = Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = mode.roleLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = GolocalPureWhite,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .width(120.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = GolocalPureWhite,
                trackColor = Color.White.copy(alpha = 0.2f)
            )
            Text(
                text = "Your Local. Our Priority.",
                style = MaterialTheme.typography.bodySmall,
                color = GolocalLightBlue
            )
        }
    }
}

@Composable
fun RoleAuthScreen(
    mode: ExperienceMode,
    onAuthSuccess: () -> Unit,
    phoneControllerFactory: ((CoroutineScope, (String) -> Unit) -> PhoneAuthController)? = null,
    googleControllerFactory: ((CoroutineScope, () -> Unit) -> GoogleAuthController)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(mode) {
        phoneControllerFactory?.invoke(scope) { _ -> onAuthSuccess() }
            ?: PhoneAuthController(
                scope = scope,
                onSuccess = { _ -> onAuthSuccess() }
            )
    }
    val googleController = remember(mode) {
        googleControllerFactory?.invoke(scope) { onAuthSuccess() }
            ?: GoogleAuthController(
                scope = scope,
                onSuccess = onAuthSuccess
            )
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    val phoneState by controller.uiState.collectAsStateWithLifecycle()
    val googleState: GoogleAuthUiState by googleController.uiState.collectAsStateWithLifecycle()
    var showPolicyDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = phoneState.step == PhoneAuthStep.VERIFY_OTP) {
        controller.changeMobileNumber()
    }

    val topGradient = when (mode) {
        ExperienceMode.ADMIN -> listOf(Color(0xFF07152E), GolocalDeepNavy, Color(0xFF102A54))
        else -> listOf(GolocalDeepNavy, GolocalPrimaryBlue, GolocalSoftBlue)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = topGradient))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("role_auth_screen_${mode.name.lowercase()}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 540.dp)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GoLocalLogoMark(
                        modifier = Modifier.size(50.dp),
                        onDarkBackground = false
                    )
                    Column {
                        Text(
                            text = mode.appTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            color = GolocalPureWhite,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Your Local. Our Priority.",
                            style = MaterialTheme.typography.labelLarge,
                            color = GolocalLightBlue
                        )
                    }
                }

                if (mode != ExperienceMode.ADMIN) {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = GolocalPureWhite)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_golocal_hero),
                            contentDescription = "${mode.appTitle} illustration",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentScale = ContentScale.Crop
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(20.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AdminPanelSettings,
                                contentDescription = null,
                                tint = GolocalLightBlue,
                                modifier = Modifier.size(36.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "GoLocal Governance Console",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = GolocalPureWhite
                                )
                                Text(
                                    text = "Role-verified admin access with immutable audit logging.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GolocalLightBlue
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = if (phoneState.step == PhoneAuthStep.INPUT_PHONE) {
                            mode.headline
                        } else {
                            "Verify 6-Digit OTP"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (phoneState.step == PhoneAuthStep.INPUT_PHONE) {
                            mode.description
                        } else {
                            "Enter the 6-digit SMS verification code sent to ${phoneState.formattedE164}."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (mode) {
                            ExperienceMode.CUSTOMER -> {
                                AuthBadge(Icons.Filled.VerifiedUser, "Verified Drivers", Modifier.weight(1f))
                                AuthBadge(Icons.Filled.Shield, "SOS & Trip PIN", Modifier.weight(1f))
                            }
                            ExperienceMode.RIDER -> {
                                AuthBadge(Icons.Filled.CurrencyRupee, "Propose Fares", Modifier.weight(1f))
                                AuthBadge(Icons.Filled.Badge, "KYC Verified", Modifier.weight(1f))
                            }
                            ExperienceMode.ADMIN -> {
                                AuthBadge(Icons.Filled.Lock, "Strict RBAC", Modifier.weight(1f))
                                AuthBadge(Icons.Filled.History, "Audit Trail", Modifier.weight(1f))
                            }
                        }
                    }

                    if (phoneState.step == PhoneAuthStep.INPUT_PHONE) {
                        if (!googleState.errorMessage.isNullOrBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("google_auth_error_banner")
                            ) {
                                Text(
                                    text = googleState.errorMessage ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                googleController.signInWithGoogle(context)
                            },
                            enabled = !googleState.isSigningIn && !phoneState.isBusy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("google_sign_in_button")
                        ) {
                            if (googleState.isSigningIn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Connecting to Google...")
                            } else {
                                Surface(
                                    shape = CircleShape,
                                    color = GolocalPrimaryBlue.copy(alpha = 0.12f),
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "G",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = GolocalPrimaryBlue,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Continue with Google",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            HorizontalDivider(modifier = Modifier.weight(1f))
                            Text(
                                text = "OR USE MOBILE OTP",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider(modifier = Modifier.weight(1f))
                        }
                    }

                    PhoneAuthFormContent(
                        controller = controller,
                        state = phoneState,
                        actionButtonLabel = mode.buttonLabel,
                        buttonContainerColor = if (mode == ExperienceMode.ADMIN) GolocalDeepNavy else GolocalPrimaryBlue
                    )

                    Text(
                        text = mode.footerNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    TextButton(
                        onClick = { showPolicyDialog = true },
                        modifier = Modifier
                            .align(Alignment.Start)
                            .heightIn(min = 48.dp)
                            .testTag("auth_privacy_policy_button")
                    ) {
                        Icon(Icons.Filled.PrivacyTip, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Privacy Policy & Terms of Service")
                    }
                }
            }
        }
    }

    if (showPolicyDialog) {
        AlertDialog(
            onDismissRequest = { showPolicyDialog = false },
            title = { Text("GoLocal Privacy Policy & Terms") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "GoLocal by NR Next processes your verified mobile number / Google identity, pickup/drop locations, and emergency SOS contact strictly to deliver rides, bus tickets, invoices, and safety support.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "We never sell personal data. Driver KYC documents are reviewed by verified Operations Administrators and are not shared publicly. You may delete your account and associated personal data anytime in Profile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showPolicyDialog = false }) { Text("Understand") }
            }
        )
    }
}

@Composable
private fun AuthBadge(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun SharedAppSettingsAndSignOutSection(
    prefs: ClientPreferences,
    sessionViewModel: AppSessionViewModel,
    supportHelpLabel: String = "Help & Support",
    onOpenSupportDialog: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPolicyDialog by remember { mutableStateOf(false) }
    val isDeletingAccount by sessionViewModel.isDeletingAccount.collectAsStateWithLifecycle()

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Language / ভাষা / भाषा", style = MaterialTheme.typography.titleSmall)
            LanguageSelectorRow(
                selected = prefs.language,
                onSelect = { sessionViewModel.setLanguage(it) }
            )
            Text("Theme Appearance", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                    FilterChip(
                        selected = prefs.darkThemeMode == mode,
                        onClick = { sessionViewModel.setThemeMode(mode) },
                        label = { Text(label) }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Low-Network Mode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Lightweight maps & draft recovery for rural areas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = prefs.lowNetworkMode,
                    onCheckedChange = { sessionViewModel.setLowNetworkMode(it) }
                )
            }
        }
    }

    val platformSettings by sessionViewModel.platformSettings.collectAsStateWithLifecycle()
    val updateController = rememberAppUpdateController(platformSettings = platformSettings)

    CheckForAppUpdatesCard(
        platformSettings = platformSettings,
        controller = updateController
    )
    AppUpdateDialogHost(controller = updateController)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Support, Privacy & Account Deletion", style = MaterialTheme.typography.titleSmall)
            if (onOpenSupportDialog != null) {
                OutlinedButton(
                    onClick = onOpenSupportDialog,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.SupportAgent, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(supportHelpLabel)
                }
            }
            OutlinedButton(
                onClick = { showPolicyDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_privacy_policy_button")
            ) {
                Icon(Icons.Filled.PrivacyTip, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Privacy Policy & Data Safety")
            }
            OutlinedButton(
                onClick = { showDeleteDialog = true },
                enabled = !isDeletingAccount,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("delete_account_button")
            ) {
                if (isDeletingAccount) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Delete Account & Data", color = GolocalError)
                }
            }
            Button(
                onClick = { signOut(scope, context = context) },
                colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sign_out_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign out")
            }
        }
    }

    if (showDeleteDialog) {
        var confirmText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!isDeletingAccount) showDeleteDialog = false },
            title = { Text("Delete Account Permanently?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This permanently removes your profile, private SOS contact, FCM tokens, and Firebase Auth account. Financial tax/ride ledger records required by compliance retain anonymized references.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        label = { Text("Type DELETE to confirm") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("confirm_delete_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    enabled = confirmText.trim().equals("DELETE", ignoreCase = true) && !isDeletingAccount,
                    onClick = {
                        sessionViewModel.deleteMyAccountNow(context)
                        showDeleteDialog = false
                    },
                    modifier = Modifier.testTag("confirm_delete_account_button")
                ) { Text("Delete Permanently") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showPolicyDialog) {
        AlertDialog(
            onDismissRequest = { showPolicyDialog = false },
            title = { Text("Privacy Policy & Data Safety") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Data Collected: Phone/Google identity, trip pickup/drop coordinates (only while using ride/dispatch), driver KYC docs (DL, RC, Insurance), private SOS contact.", style = MaterialTheme.typography.bodySmall)
                    Text("Encryption & Deletion: All data is transmitted over TLS and stored in Firebase Firestore/Storage with role-based access rules. Tap 'Delete Account & Data' to permanently remove your personal profile and auth credentials.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { showPolicyDialog = false }) { Text("Close") }
            }
        )
    }
}
