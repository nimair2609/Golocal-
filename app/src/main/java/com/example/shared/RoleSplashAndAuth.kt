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
import kotlinx.coroutines.launch

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
        headline = "Welcome to GoLocal",
        description = "Continue with Google or verify your mobile number via OTP to book local rides and private buses across West Bengal.",
        buttonLabel = "Send OTP",
        footerNotice = "New users complete a quick profile & safety setup right after sign-in."
    ),
    RIDER(
        appTitle = "GoLocal Rider",
        roleLabel = "Verified Driver & Partner App",
        headline = "Partner Sign-In",
        description = "Sign in with Google or Mobile OTP to manage driver verification documents, trip requests, and daily earnings.",
        buttonLabel = "Send Partner OTP",
        footerNotice = "Admin KYC document verification (DL, RC, Insurance, Permit & PUC) is mandatory before going online."
    ),
    ADMIN(
        appTitle = "GoLocal Admin Portal",
        roleLabel = "Operations & Governance",
        headline = "Authorized Admin Sign-In",
        description = "Sign in with your verified Google Workspace/Gmail admin account or allowlisted mobile OTP.",
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
    googleGateway: com.example.ui.auth.GoogleSignInGateway = com.example.ui.auth.DefaultGoogleSignInGateway()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(mode) {
        phoneControllerFactory?.invoke(scope) { _ -> onAuthSuccess() }
            ?: PhoneAuthController(
                scope = scope,
                onSuccess = { _ -> onAuthSuccess() }
            )
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    val phoneState by controller.uiState.collectAsStateWithLifecycle()
    var isGoogleSigningIn by remember { mutableStateOf(false) }
    var googleError by remember { mutableStateOf<String?>(null) }

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
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GoLocalLogoMark(
                        modifier = Modifier.size(48.dp),
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
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = GolocalPureWhite)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_golocal_hero),
                            contentDescription = "${mode.appTitle} illustration",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp),
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
                        if (!googleError.isNullOrBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("google_auth_error_banner")
                            ) {
                                Text(
                                    text = googleError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                if (isGoogleSigningIn || phoneState.isBusy) return@OutlinedButton
                                googleError = null
                                isGoogleSigningIn = true
                                scope.launch {
                                    when (
                                        val outcome = com.example.ui.auth.GoogleAuthManager.onGoogleSignInClicked(
                                            context = context,
                                            gateway = googleGateway
                                        )
                                    ) {
                                        is com.example.ui.auth.GoogleSignInOutcome.Success -> {
                                            isGoogleSigningIn = false
                                            onAuthSuccess()
                                        }
                                        is com.example.ui.auth.GoogleSignInOutcome.Cancelled -> {
                                            isGoogleSigningIn = false
                                            googleError = outcome.message
                                        }
                                        is com.example.ui.auth.GoogleSignInOutcome.Error -> {
                                            isGoogleSigningIn = false
                                            googleError = outcome.message
                                        }
                                    }
                                }
                            },
                            enabled = !isGoogleSigningIn && !phoneState.isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("google_sign_in_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            if (isGoogleSigningIn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Signing in with Google...")
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.AccountCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
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
                                text = "OR MOBILE OTP",
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
                }
            }
        }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }

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
            Text("Support & Account Governance", style = MaterialTheme.typography.titleSmall)
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
                onClick = { showDeleteDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Request Account Deletion", color = GolocalError)
            }
            Button(
                onClick = { signOut(scope = scope, context = context) },
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
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Account Deletion Request") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason for deletion request") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    onClick = {
                        sessionViewModel.submitSupportOrDeletion(
                            "account_deletion",
                            reason.ifBlank { "User requested account deletion" }
                        )
                        showDeleteDialog = false
                    }
                ) { Text("Submit Request") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}
