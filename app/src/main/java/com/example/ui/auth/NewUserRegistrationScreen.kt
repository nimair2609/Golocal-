package com.example.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.UserAccount
import com.example.ui.components.GoLocalLogoMark
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalLightBlue
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalPureWhite
import com.example.ui.theme.GolocalSoftBlue
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.JetBrainsMono

@Composable
fun NewUserRegistrationScreen(
    verifiedMobileNumber: String,
    existingAccount: UserAccount? = null,
    prefilledName: String = "",
    prefilledEmail: String = "",
    isPhoneAuthVerified: Boolean = verifiedMobileNumber.isNotBlank(),
    isSubmitting: Boolean,
    serverErrorMessage: String? = null,
    onSubmitRegistration: (
        fullName: String,
        invoiceEmail: String,
        emergencyContactE164: String,
        termsAccepted: Boolean
    ) -> Unit,
    onSubmitRegistrationWithPhone: (
        fullName: String,
        mobileNumberE164: String,
        invoiceEmail: String,
        emergencyContactE164: String,
        termsAccepted: Boolean
    ) -> Unit = { name, _, email, emergency, accepted ->
        onSubmitRegistration(name, email, emergency, accepted)
    },
    onSignOut: () -> Unit
) {
    var fullName by remember(existingAccount?.fullName, prefilledName) {
        mutableStateOf(
            existingAccount?.fullName?.takeIf { it.isNotBlank() && it != "GoLocal User" }
                ?: prefilledName.takeIf { it.isNotBlank() && it != "GoLocal User" && it != "GoLocal Passenger" && it != "GoLocal Rider" }
                ?: ""
        )
    }
    var invoiceEmail by remember(existingAccount?.email, prefilledEmail) {
        mutableStateOf(
            existingAccount?.email?.takeIf { it.isNotBlank() }
                ?: prefilledEmail.takeIf { it.isNotBlank() }
                ?: ""
        )
    }
    var mobileCountry by remember { mutableStateOf(SupportedCountryCodes.INDIA) }
    var mobileCountryExpanded by remember { mutableStateOf(false) }
    var mobileInput by remember(verifiedMobileNumber, existingAccount?.phone) {
        mutableStateOf(
            verifiedMobileNumber.ifBlank { existingAccount?.effectiveMobileNumber().orEmpty() }
        )
    }
    var emergencyCountry by remember { mutableStateOf(SupportedCountryCodes.INDIA) }
    var countryMenuExpanded by remember { mutableStateOf(false) }
    var emergencyPhoneInput by remember { mutableStateOf("") }
    var termsAccepted by remember(existingAccount?.privacyConsentAccepted) {
        mutableStateOf(existingAccount?.privacyConsentAccepted ?: false)
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    val displayError = validationError ?: serverErrorMessage

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(GolocalDeepNavy, GolocalPrimaryBlue, GolocalSoftBlue)
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("new_user_registration_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    GoLocalLogoMark(modifier = Modifier.size(44.dp), onDarkBackground = false)
                    Column {
                        Text(
                            text = "Complete Registration",
                            style = MaterialTheme.typography.titleLarge,
                            color = GolocalPureWhite,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "One-time GoLocal safety & GST invoice setup",
                            style = MaterialTheme.typography.bodySmall,
                            color = GolocalLightBlue
                        )
                    }
                }
                TextButton(
                    onClick = onSignOut,
                    enabled = !isSubmitting,
                    modifier = Modifier.testTag("reg_sign_out_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = "Sign out",
                        tint = GolocalPureWhite,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Switch", color = GolocalPureWhite)
                }
            }

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
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Your GoLocal Profile Details",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (!displayError.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("reg_error_banner")
                        ) {
                            Text(
                                text = displayError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    if (isPhoneAuthVerified && verifiedMobileNumber.isNotBlank()) {
                        // 1. Verified Mobile Number (Read-only for Phone OTP users)
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, GolocalSuccess.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                .testTag("reg_verified_mobile_display")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.VerifiedUser,
                                        contentDescription = "Verified mobile number",
                                        tint = GolocalSuccess
                                    )
                                    Column {
                                        Text(
                                            text = "Mobile Number (OTP Verified)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = verifiedMobileNumber,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontFamily = JetBrainsMono,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                GolocalStatusBadge(label = "VERIFIED", tone = "success")
                            }
                        }
                    } else {
                        // Google Sign-In users enter their contact mobile number (and can link OTP later in Profile)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Contact Mobile Number *",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
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
                                            .clickable(enabled = !isSubmitting) { mobileCountryExpanded = true }
                                            .testTag("reg_mobile_country_selector")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "${mobileCountry.flagEmoji} ${mobileCountry.dialCode}",
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            Icon(
                                                imageVector = Icons.Filled.ArrowDropDown,
                                                contentDescription = "Select mobile country code"
                                            )
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = mobileCountryExpanded,
                                        onDismissRequest = { mobileCountryExpanded = false }
                                    ) {
                                        SupportedCountryCodes.all.forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text("${option.flagEmoji}  ${option.countryName} (${option.dialCode})") },
                                                onClick = {
                                                    mobileCountry = option
                                                    mobileCountryExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = mobileInput,
                                    onValueChange = { raw ->
                                        mobileInput = raw.filter { it.isDigit() || it == '+' || it == ' ' || it == '-' }.take(18)
                                        validationError = null
                                    },
                                    label = { Text("Your Mobile Number *") },
                                    placeholder = { Text(mobileCountry.sampleHint) },
                                    leadingIcon = { Icon(Icons.Filled.PhoneAndroid, contentDescription = null) },
                                    singleLine = true,
                                    enabled = !isSubmitting,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Phone,
                                        imeAction = ImeAction.Next
                                    ),
                                    supportingText = {
                                        Text("Required for driver/customer coordination. You can OTP-link it in Profile.")
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("reg_mobile_input")
                                )
                            }
                        }
                    }

                    // 2. Full Name (Required)
                    OutlinedTextField(
                        value = fullName,
                        onValueChange = {
                            fullName = it
                            validationError = null
                        },
                        label = { Text("Full Name *") },
                        placeholder = { Text("e.g. Anirban Chatterjee") },
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                        singleLine = true,
                        enabled = !isSubmitting,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reg_full_name_input")
                    )

                    // 3. Email Address (Required; strictly for invoice delivery)
                    OutlinedTextField(
                        value = invoiceEmail,
                        onValueChange = {
                            invoiceEmail = it
                            validationError = null
                        },
                        label = { Text("Email Address (for Ride Invoices) *") },
                        placeholder = { Text("you@example.com") },
                        leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
                        singleLine = true,
                        enabled = !isSubmitting,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        supportingText = {
                            Text("Used only for sending digital GST trip invoices. Never used for sign-in.")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reg_invoice_email_input")
                    )

                    // 4. Emergency Contact Number (Required; strictly private)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Security,
                                contentDescription = null,
                                tint = GolocalPrimaryBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Private SOS Emergency Contact *",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

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
                                        .clickable(enabled = !isSubmitting) { countryMenuExpanded = true }
                                        .testTag("reg_emergency_country_selector")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "${emergencyCountry.flagEmoji} ${emergencyCountry.dialCode}",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Icon(
                                            imageVector = Icons.Filled.ArrowDropDown,
                                            contentDescription = "Select emergency contact country code"
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = countryMenuExpanded,
                                    onDismissRequest = { countryMenuExpanded = false }
                                ) {
                                    SupportedCountryCodes.all.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text("${option.flagEmoji}  ${option.countryName} (${option.dialCode})") },
                                            onClick = {
                                                emergencyCountry = option
                                                countryMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = emergencyPhoneInput,
                                onValueChange = { raw ->
                                    emergencyPhoneInput = raw.filter { it.isDigit() || it == '+' || it == ' ' || it == '-' }.take(18)
                                    validationError = null
                                },
                                label = { Text("Emergency Contact No. *") },
                                placeholder = { Text(emergencyCountry.sampleHint) },
                                leadingIcon = { Icon(Icons.Filled.PhoneAndroid, contentDescription = null) },
                                singleLine = true,
                                enabled = !isSubmitting,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Phone,
                                    imeAction = ImeAction.Done
                                ),
                                supportingText = {
                                    Text("Stored privately for emergency SOS responders only — never visible to drivers or other riders.")
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("reg_emergency_phone_input")
                            )
                        }
                    }

                    // 5. Terms and Privacy Policy acceptance (Required)
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .clickable(enabled = !isSubmitting) {
                                termsAccepted = !termsAccepted
                                validationError = null
                            }
                            .padding(10.dp)
                    ) {
                        Checkbox(
                            checked = termsAccepted,
                            onCheckedChange = {
                                if (!isSubmitting) {
                                    termsAccepted = it
                                    validationError = null
                                }
                            },
                            modifier = Modifier.testTag("reg_terms_checkbox")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "I accept GoLocal's Terms of Service & Privacy Policy and consent to private storage of my emergency contact for SOS workflows.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Button(
                        onClick = {
                            val resolvedMobileE164 = if (isPhoneAuthVerified && verifiedMobileNumber.isNotBlank()) {
                                verifiedMobileNumber
                            } else {
                                when (val mobileRes = PhoneNumberValidator.validateAndFormat(mobileCountry, mobileInput)) {
                                    is PhoneValidationResult.Invalid -> {
                                        validationError = mobileRes.message
                                        return@Button
                                    }
                                    is PhoneValidationResult.Valid -> mobileRes.e164Phone
                                }
                            }
                            val nameErr = RegistrationValidator.validateFullName(fullName)
                            if (nameErr != null) {
                                validationError = nameErr
                                return@Button
                            }
                            val emailErr = RegistrationValidator.validateInvoiceEmail(invoiceEmail)
                            if (emailErr != null) {
                                validationError = emailErr
                                return@Button
                            }
                            val emergencyValidation = RegistrationValidator.validateEmergencyContact(
                                country = emergencyCountry,
                                rawEmergency = emergencyPhoneInput,
                                ownVerifiedMobileE164 = resolvedMobileE164
                            )
                            val validEmergency = when (emergencyValidation) {
                                is PhoneValidationResult.Invalid -> {
                                    validationError = emergencyValidation.message
                                    return@Button
                                }
                                is PhoneValidationResult.Valid -> emergencyValidation.e164Phone
                            }
                            if (!termsAccepted) {
                                validationError = "Please accept Terms and Privacy Policy to continue."
                                return@Button
                            }
                            validationError = null
                            onSubmitRegistrationWithPhone(
                                fullName.trim(),
                                resolvedMobileE164,
                                invoiceEmail.trim(),
                                validEmergency,
                                true
                            )
                        },
                        enabled = !isSubmitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("reg_submit_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GolocalPrimaryBlue,
                            contentColor = GolocalPureWhite
                        )
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = GolocalPureWhite,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Create Account & Continue", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}
