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
import com.example.data.repository.PreferencesRepository
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
    val reducedMotion = rememberPrefersReducedMotion()
    var visible by remember(reducedMotion) { mutableStateOf(reducedMotion) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 420),
        label = "splash_alpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.92f,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 480),
        label = "splash_scale"
    )

    LaunchedEffect(mode, reducedMotion) {
        visible = true
        delay(if (reducedMotion) 320L else 850L)
        onFinished()
    }

    if (mode == ExperienceMode.CUSTOMER) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(GolocalPureWhite, GolocalSkyTop, GolocalLightBlue)
                    )
                )
                .clickable { onFinished() }
                .testTag("splash_screen_customer"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.alpha(alpha),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(30.dp),
                        color = GolocalPureWhite,
                        shadowElevation = 10.dp,
                        modifier = Modifier
                            .size((112 * scale).dp)
                            .border(1.dp, GolocalBorderLight, RoundedCornerShape(30.dp))
                    ) {
                        GoLocalFullLogo(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp)
                        )
                    }
                    Text(
                        text = mode.appTitle,
                        style = MaterialTheme.typography.displaySmall,
                        color = GolocalDeepNavy,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Your Local. Our Priority.",
                        style = MaterialTheme.typography.titleMedium,
                        color = GolocalPrimaryBlue,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        shadowElevation = 6.dp,
                        color = GolocalPureWhite,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 380.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_golocal_hero),
                            contentDescription = "GoLocal mobility across West Bengal",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(148.dp),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            Surface(
                color = GolocalAuthNavy,
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 28.dp, vertical = 26.dp)
                        .alpha(alpha),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "by NR Next",
                        style = MaterialTheme.typography.titleMedium,
                        color = GolocalPureWhite,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .width(148.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = GolocalRoyalBlueBright,
                        trackColor = Color.White.copy(alpha = 0.18f)
                    )
                    Surface(
                        color = Color.White.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = mode.roleLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = GolocalLightBlue,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .width(18.dp)
                                .height(10.dp)
                                .clip(RoundedCornerShape(3.dp))
                        ) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFFFF9933)))
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color.White))
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF138808)))
                        }
                        Text(
                            text = "Proudly Made in India",
                            style = MaterialTheme.typography.labelMedium,
                            color = GolocalLightBlue.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
        return
    }

    val isAdmin = mode == ExperienceMode.ADMIN
    val bgBrush = if (isAdmin) {
        Brush.verticalGradient(colors = listOf(Color(0xFF07152E), GolocalDeepNavy, GolocalPrimaryBlue))
    } else {
        Brush.verticalGradient(colors = listOf(GolocalPureWhite, GolocalSoftBlue, GolocalLightBlue.copy(alpha = 0.45f)))
    }
    val titleColor = if (isAdmin) GolocalPureWhite else GolocalDeepNavy
    val subColor = if (isAdmin) GolocalLightBlue else GolocalPrimaryBlue
    val pillBg = if (isAdmin) Color.White.copy(alpha = 0.14f) else GolocalLightBlue
    val pillFg = if (isAdmin) GolocalPureWhite else GolocalPrimaryBlue

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(30.dp),
                color = GolocalPureWhite,
                shadowElevation = if (isAdmin) 0.dp else 8.dp,
                modifier = Modifier.size((116 * scale).dp)
            ) {
                GoLocalFullLogo(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                )
            }
            Text(
                text = mode.appTitle,
                style = MaterialTheme.typography.headlineLarge,
                color = titleColor,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "by NR Next",
                style = MaterialTheme.typography.titleMedium,
                color = subColor,
                fontWeight = FontWeight.SemiBold
            )
            Surface(
                color = pillBg,
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = mode.roleLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = pillFg,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .width(132.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = if (isAdmin) GolocalPureWhite else GolocalPrimaryBlue,
                trackColor = if (isAdmin) Color.White.copy(alpha = 0.2f) else GolocalLightBlue
            )
            Text(
                text = "Your Local. Our Priority.",
                style = MaterialTheme.typography.bodySmall,
                color = if (isAdmin) GolocalLightBlue else GolocalSecondaryText
            )
        }
    }
}

@Composable
fun RoleAuthScreen(
    mode: ExperienceMode,
    onAuthSuccess: () -> Unit,
    phoneControllerFactory: ((CoroutineScope, (String) -> Unit) -> PhoneAuthController)? = null,
    googleGateway: com.example.ui.auth.GoogleSignInGateway = com.example.ui.auth.DefaultGoogleSignInGateway(),
    initialTestRiderIntent: Boolean? = null,
    onTestRiderIntentChanged: ((Boolean) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsRepository = remember(context) { PreferencesRepository(context) }
    val storedPrefs by prefsRepository.preferencesFlow.collectAsStateWithLifecycle(initialValue = ClientPreferences())
    var localTestRiderIntent by remember(initialTestRiderIntent) {
        mutableStateOf(initialTestRiderIntent ?: storedPrefs.testRiderIntent)
    }
    LaunchedEffect(storedPrefs.testRiderIntent, initialTestRiderIntent) {
        if (initialTestRiderIntent == null) {
            localTestRiderIntent = storedPrefs.testRiderIntent
        }
    }
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
    var activeLegalDialog by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = phoneState.step == PhoneAuthStep.VERIFY_OTP) {
        controller.changeMobileNumber()
    }

    val isCustomer = mode == ExperienceMode.CUSTOMER
    val isAdmin = mode == ExperienceMode.ADMIN
    val topGradient = if (isAdmin) {
        listOf(Color(0xFF07152E), GolocalDeepNavy, Color(0xFF102A54))
    } else {
        listOf(GolocalPureWhite, GolocalSkyTop, GolocalLightBlue.copy(alpha = 0.65f))
    }
    val headerTitleColor = if (isAdmin) GolocalPureWhite else GolocalDeepNavy
    val headerSubColor = if (isAdmin) GolocalLightBlue else GolocalPrimaryBlue

    val cardBg = if (isCustomer) GolocalAuthNavy else MaterialTheme.colorScheme.surface
    val cardBorder = if (isCustomer) GolocalAuthNavyBorder else MaterialTheme.colorScheme.outline
    val cardTitleColor = if (isCustomer) GolocalPureWhite else MaterialTheme.colorScheme.onSurface
    val cardSubColor = if (isCustomer) GolocalLightBlue.copy(alpha = 0.86f) else MaterialTheme.colorScheme.onSurfaceVariant

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
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = GolocalPureWhite,
                        shadowElevation = 4.dp
                    ) {
                        GoLocalLogoMark(
                            modifier = Modifier.size(48.dp),
                            onDarkBackground = false
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.appTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            color = headerTitleColor,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Your Local. Our Priority.",
                            style = MaterialTheme.typography.labelLarge,
                            color = headerSubColor
                        )
                    }
                }

                if (mode != ExperienceMode.ADMIN) {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = GolocalPureWhite)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_golocal_hero),
                            contentDescription = "${mode.appTitle} illustration",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(136.dp),
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

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(26.dp),
                color = cardBg,
                tonalElevation = if (isCustomer) 0.dp else 4.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, cardBorder, RoundedCornerShape(26.dp))
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = if (phoneState.step == PhoneAuthStep.INPUT_PHONE) {
                            mode.headline
                        } else {
                            "Verify 6-Digit OTP"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = cardTitleColor
                    )
                    Text(
                        text = if (phoneState.step == PhoneAuthStep.INPUT_PHONE) {
                            mode.description
                        } else {
                            "Enter the 6-digit SMS verification code sent to ${phoneState.formattedE164}."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = cardSubColor
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (mode) {
                            ExperienceMode.CUSTOMER -> {
                                AuthBadge(Icons.Filled.VerifiedUser, "Verified Drivers", Modifier.weight(1f), darkSurface = true)
                                AuthBadge(Icons.Filled.Shield, "SOS & Trip PIN", Modifier.weight(1f), darkSurface = true)
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
                        if (mode == ExperienceMode.RIDER) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Select Sign-In Mode",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    RiderModeOptionCard(
                                        title = "Commercial Partner",
                                        subtitle = "Verified DL, RC & KYC",
                                        selected = !localTestRiderIntent,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("auth_mode_commercial_partner"),
                                        onClick = {
                                            localTestRiderIntent = false
                                            onTestRiderIntentChanged?.invoke(false)
                                            scope.launch { prefsRepository.setTestRiderIntent(false) }
                                        }
                                    )
                                    RiderModeOptionCard(
                                        title = "Test Rider (Sandbox)",
                                        subtitle = "QA & Admin-approved test rides",
                                        selected = localTestRiderIntent,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("auth_mode_test_rider"),
                                        onClick = {
                                            localTestRiderIntent = true
                                            onTestRiderIntentChanged?.invoke(true)
                                            scope.launch { prefsRepository.setTestRiderIntent(true) }
                                        }
                                    )
                                }
                            }
                        }

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

                        val googleClick: () -> Unit = {
                            if (!isGoogleSigningIn && !phoneState.isBusy) {
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
                            }
                        }

                        if (isCustomer) {
                            Button(
                                onClick = googleClick,
                                enabled = !isGoogleSigningIn && !phoneState.isBusy,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GolocalAuthNavySurface,
                                    contentColor = GolocalPureWhite
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, GolocalAuthNavyBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("google_sign_in_button"),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                if (isGoogleSigningIn) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = GolocalPureWhite,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Signing in with Google...")
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.AccountCircle,
                                        contentDescription = null,
                                        tint = GolocalPureWhite,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Continue with Google",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = googleClick,
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
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = if (isCustomer) GolocalAuthNavyBorder else MaterialTheme.colorScheme.outlineVariant
                            )
                            Text(
                                text = "OR MOBILE OTP",
                                style = MaterialTheme.typography.labelSmall,
                                color = cardSubColor
                            )
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = if (isCustomer) GolocalAuthNavyBorder else MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }

                    PhoneAuthFormContent(
                        controller = controller,
                        state = phoneState,
                        actionButtonLabel = mode.buttonLabel,
                        buttonContainerColor = when (mode) {
                            ExperienceMode.ADMIN -> GolocalDeepNavy
                            ExperienceMode.CUSTOMER -> GolocalRoyalBlueBright
                            ExperienceMode.RIDER -> GolocalPrimaryBlue
                        },
                        darkSurface = isCustomer
                    )

                    Text(
                        text = mode.footerNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = cardSubColor
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { activeLegalDialog = "terms" },
                            modifier = Modifier.testTag("auth_terms_link")
                        ) {
                            Text(
                                text = "Terms",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isCustomer) GolocalLightBlue else GolocalPrimaryBlue
                            )
                        }
                        Text("•", color = cardSubColor)
                        TextButton(
                            onClick = { activeLegalDialog = "privacy" },
                            modifier = Modifier.testTag("auth_privacy_link")
                        ) {
                            Text(
                                text = "Privacy (DPDP)",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isCustomer) GolocalLightBlue else GolocalPrimaryBlue
                            )
                        }
                        Text("•", color = cardSubColor)
                        TextButton(
                            onClick = { activeLegalDialog = "help" },
                            modifier = Modifier.testTag("auth_help_link")
                        ) {
                            Text(
                                text = "Help",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isCustomer) GolocalLightBlue else GolocalPrimaryBlue
                            )
                        }
                    }
                }
            }
        }
    }

    if (activeLegalDialog != null) {
        val (title, body) = when (activeLegalDialog) {
            "terms" -> "GoLocal Terms of Service" to
                "Fares stay within admin-configured state fare bands. All commercial trips verify a 6-digit Pickup OTP/PIN before starting. Riders must maintain valid DL, RC, Commercial Insurance, Permit, and PUC under the Motor Vehicles Act 1988 & MVA Guidelines."
            "privacy" -> "Privacy Notice (DPDP Act 2023)" to
                "GoLocal processes your phone number, pickup/drop coordinates, and emergency contact solely for booking fulfilment, safety SOS alerts, and statutory invoicing. Phone numbers are never exposed directly between unrelated parties; you may request correction or account deletion anytime from Account Settings."
            else -> "GoLocal Help & Safety Desk" to
                "Need sign-in or trip help? Emergency SOS is available 24x7 inside active trips. Once signed in, open Profile -> Help & Support to raise a ticket with GoLocal Operations."
        }
        AlertDialog(
            onDismissRequest = { activeLegalDialog = null },
            title = { Text(title) },
            text = { Text(body, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(
                    onClick = { activeLegalDialog = null },
                    modifier = Modifier.testTag("auth_legal_dialog_close")
                ) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun RiderModeOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        modifier = modifier
            .heightIn(min = 68.dp)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(14.dp)
            )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AuthBadge(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    darkSurface: Boolean = false
) {
    Surface(
        modifier = modifier.let {
            if (darkSurface) it.border(1.dp, GolocalAuthNavyBorder, RoundedCornerShape(12.dp)) else it
        },
        shape = RoundedCornerShape(12.dp),
        color = if (darkSurface) GolocalAuthNavySurface else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (darkSurface) GolocalRoyalBlueBright.copy(alpha = 0.25f) else MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (darkSurface) GolocalLightBlue else Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (darkSurface) GolocalPureWhite else MaterialTheme.colorScheme.onPrimaryContainer
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
                Text("Delete Account & Data", color = GolocalError)
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
        var confirmText by remember { mutableStateOf("") }
        val isDeleting by sessionViewModel.isDeletingAccount.collectAsStateWithLifecycle()
        val canDelete = confirmText.trim().equals("DELETE", ignoreCase = false) && !isDeleting
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
            title = { Text("Delete Account & Data") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "This wipes your saved personal info, private emergency contacts, and FCM tokens, anonymizes your user record, and deletes your Firebase Auth login.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Reason (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        label = { Text("Type DELETE to confirm") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = canDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    onClick = {
                        sessionViewModel.deleteMyAccount(
                            context = context,
                            reason = reason.ifBlank { "Self-service account deletion" },
                            onRequiresRecentLogin = {
                                showDeleteDialog = false
                                signOut(scope = scope, context = context)
                            },
                            onAccountDeleted = {
                                showDeleteDialog = false
                                signOut(scope = scope, context = context)
                            }
                        )
                    }
                ) { Text(if (isDeleting) "Deleting..." else "Delete Permanently") }
            },
            dismissButton = {
                TextButton(enabled = !isDeleting, onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}
