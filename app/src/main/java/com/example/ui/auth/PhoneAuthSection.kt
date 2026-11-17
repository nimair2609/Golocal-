package com.example.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalLightBlue
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalPureWhite
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.JetBrainsMono
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth

@Composable
fun PhoneAuthFormContent(
    controller: PhoneAuthController,
    state: PhoneAuthUiState,
    actionButtonLabel: String = "Send OTP",
    buttonContainerColor: androidx.compose.ui.graphics.Color = GolocalPrimaryBlue
) {
    val context = LocalContext.current
    var countryMenuExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.infoMessage.isNullOrBlank() && state.errorMessage.isNullOrBlank()) {
            Surface(
                color = GolocalLightBlue.copy(alpha = 0.65f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = state.infoMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = GolocalDeepNavy,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (!state.errorMessage.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("phone_auth_error_banner")
            ) {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (state.step == PhoneAuthStep.INPUT_PHONE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .height(56.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !state.isBusy) { countryMenuExpanded = true }
                            .testTag("country_code_selector")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "${state.selectedCountry.flagEmoji} ${state.selectedCountry.dialCode}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = "Select country code",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = countryMenuExpanded,
                        onDismissRequest = { countryMenuExpanded = false }
                    ) {
                        SupportedCountryCodes.all.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text("${option.flagEmoji}  ${option.countryName} (${option.dialCode})")
                                },
                                onClick = {
                                    controller.selectCountry(option)
                                    countryMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = state.phoneInput,
                    onValueChange = controller::updatePhoneInput,
                    label = { Text("Mobile Number") },
                    placeholder = { Text(state.selectedCountry.sampleHint) },
                    singleLine = true,
                    enabled = !state.isBusy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { controller.sendOtp(context, isResend = false) }
                    ),
                    supportingText = {
                        Text(
                            if (state.selectedCountry.dialCode == "+91") {
                                "10-digit Indian mobile number (starts with 6-9)"
                            } else {
                                "${state.selectedCountry.countryName} (${state.selectedCountry.minDigits} digits)"
                            }
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("phone_number_input")
                )
            }

            Button(
                onClick = { controller.sendOtp(context, isResend = false) },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("send_otp_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonContainerColor,
                    contentColor = GolocalPureWhite
                )
            ) {
                if (state.isSendingOtp) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = GolocalPureWhite,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(actionButtonLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Verifying Mobile Number",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = state.formattedE164,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = JetBrainsMono,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    TextButton(
                        onClick = { controller.changeMobileNumber() },
                        enabled = !state.isVerifyingOtp,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("change_phone_button")
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Change")
                    }
                }
            }

            OtpSixDigitIndicator(otp = state.otpInput)

            OutlinedTextField(
                value = state.otpInput,
                onValueChange = controller::updateOtpInput,
                label = { Text("Enter 6-Digit OTP") },
                placeholder = { Text("123456") },
                singleLine = true,
                enabled = !state.isBusy,
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = JetBrainsMono,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { controller.verifyEnteredOtp() }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("otp_code_input")
            )

            if (state.autoRetrievalTimedOut) {
                Text(
                    text = "SMS auto-detection timed out. Enter the 6-digit code manually or tap Resend OTP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = { controller.verifyEnteredOtp() },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("verify_otp_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonContainerColor,
                    contentColor = GolocalPureWhite
                )
            ) {
                if (state.isVerifyingOtp) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = GolocalPureWhite,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Verify OTP & Continue", style = MaterialTheme.typography.labelLarge)
                }
            }

            OutlinedButton(
                onClick = { controller.sendOtp(context, isResend = true) },
                enabled = !state.isBusy && state.resendCooldownSeconds == 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("resend_otp_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (state.resendCooldownSeconds > 0) {
                        "Resend OTP in ${state.resendCooldownSeconds}s"
                    } else {
                        "Resend OTP"
                    }
                )
            }
        }
    }
}

@Composable
private fun OtpSixDigitIndicator(otp: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        repeat(6) { index ->
            val char = otp.getOrNull(index)?.toString() ?: ""
            val filled = char.isNotEmpty()
            val isNext = index == otp.length && otp.length < 6
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (filled) GolocalLightBlue.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.surface
                    )
                    .border(
                        width = if (isNext || filled) 1.5.dp else 1.dp,
                        color = if (isNext || filled) GolocalPrimaryBlue else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (filled) char else "•",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = JetBrainsMono,
                    color = if (filled) GolocalDeepNavy else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun VerifiedMobileBadgeCard(
    fallbackAccountPhone: String
) {
    val context = LocalContext.current
    val currentUser = remember(fallbackAccountPhone) {
        if (FirebaseApp.getApps(context).isNotEmpty()) Firebase.auth.currentUser else null
    }
    val verifiedPhone = currentUser?.phoneNumber?.takeIf { it.isNotBlank() } ?: fallbackAccountPhone

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.VerifiedUser,
                        contentDescription = null,
                        tint = GolocalSuccess
                    )
                    Text("Mobile OTP Authentication", style = MaterialTheme.typography.titleSmall)
                }
                GolocalStatusBadge(
                    label = if (verifiedPhone.isNotBlank()) "VERIFIED" else "ACTIVE",
                    tone = "success"
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = GolocalSuccess,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (verifiedPhone.isNotBlank()) {
                        "Verified Login Number: $verifiedPhone"
                    } else {
                        "Authenticated via Firebase Mobile OTP"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMono,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "Your mobile number is verified and read-only. Re-logging in with this number always restores this same GoLocal account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AccountSecurityLinkingCard(
    fallbackAccountPhone: String,
    fallbackAccountEmail: String,
    onPhoneLinked: (String) -> Unit,
    onGoogleLinked: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentUser = remember(fallbackAccountPhone, fallbackAccountEmail) {
        if (FirebaseApp.getApps(context).isNotEmpty()) Firebase.auth.currentUser else null
    }
    val providerIds = currentUser?.providerData?.map { it.providerId }?.toSet().orEmpty()
    var hasGoogleLinked by remember(providerIds) { mutableStateOf("google.com" in providerIds) }
    var linkedPhone by remember(currentUser?.phoneNumber, providerIds) {
        mutableStateOf(currentUser?.phoneNumber?.takeIf { it.isNotBlank() } ?: "")
    }
    var showPhoneLinkDialog by remember { mutableStateOf(false) }

    val googleLinker = remember {
        GoogleAuthController(
            scope = scope,
            onSuccess = {
                hasGoogleLinked = true
                onGoogleLinked()
            }
        )
    }
    val googleState by googleLinker.uiState.collectAsState()

    val phoneLinker = remember {
        PhoneAuthController(
            scope = scope,
            linkToExistingUser = true,
            onSuccess = { verifiedE164 ->
                linkedPhone = verifiedE164
                showPhoneLinkDialog = false
                onPhoneLinked(verifiedE164)
            }
        )
    }
    DisposableEffect(phoneLinker) { onDispose { phoneLinker.dispose() } }
    val phoneState by phoneLinker.uiState.collectAsState()

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .testTag("account_security_linking_card")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sign-In Methods & Linking", style = MaterialTheme.typography.titleSmall)
                GolocalStatusBadge(
                    label = if (hasGoogleLinked && linkedPhone.isNotBlank()) "DUAL LINKED" else "ACTIVE",
                    tone = if (hasGoogleLinked && linkedPhone.isNotBlank()) "success" else "info"
                )
            }

            if (!googleState.errorMessage.isNullOrBlank()) {
                Text(
                    text = googleState.errorMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Mobile OTP Sign-In", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = if (linkedPhone.isNotBlank()) "Verified: $linkedPhone"
                        else if (fallbackAccountPhone.isNotBlank()) "Contact: $fallbackAccountPhone (Not OTP linked)"
                        else "Not linked yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (linkedPhone.isNotBlank()) {
                    GolocalStatusBadge(label = "LINKED", tone = "success")
                } else {
                    OutlinedButton(
                        onClick = { showPhoneLinkDialog = true },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("link_phone_button")
                    ) {
                        Text("Verify & Link")
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Google Sign-In", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = if (hasGoogleLinked) {
                            currentUser?.email?.takeIf { it.isNotBlank() } ?: fallbackAccountEmail.ifBlank { "Google account linked" }
                        } else {
                            "Link your Google account for one-tap login"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (hasGoogleLinked) {
                    GolocalStatusBadge(label = "LINKED", tone = "success")
                } else {
                    OutlinedButton(
                        onClick = { googleLinker.signInWithGoogle(context, linkToExistingUser = true) },
                        enabled = !googleState.isSigningIn,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("link_google_button")
                    ) {
                        if (googleState.isSigningIn) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Link Google")
                        }
                    }
                }
            }
        }
    }

    if (showPhoneLinkDialog) {
        AlertDialog(
            onDismissRequest = { if (!phoneState.isBusy) showPhoneLinkDialog = false },
            title = { Text("Verify & Link Mobile Number") },
            text = {
                PhoneAuthFormContent(
                    controller = phoneLinker,
                    state = phoneState,
                    actionButtonLabel = "Send Verification OTP"
                )
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showPhoneLinkDialog = false },
                    enabled = !phoneState.isBusy
                ) { Text("Close") }
            }
        )
    }
}
