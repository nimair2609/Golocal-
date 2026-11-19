package com.example.shared

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.EmergencyContactEntry
import com.example.ui.auth.AuthDiagnostics
import com.example.ui.auth.PhoneValidationResult
import com.example.ui.auth.RegistrationValidator
import com.example.ui.auth.SupportedCountryCodes
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.JetBrainsMono
import java.util.UUID

@Composable
fun EmergencyContactsSectionCard(
    contacts: List<EmergencyContactEntry>,
    ownVerifiedMobileE164: String,
    onSaveContacts: (List<EmergencyContactEntry>) -> Unit,
    modifier: Modifier = Modifier
) {
    var editingTarget by remember { mutableStateOf<EmergencyContactEntry?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    SettingsSectionGroup(
        title = "Emergency Contacts (${contacts.size}/5)",
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Stored privately for SOS alerts. Authorized responders receive your active SOS contact when you trigger SOS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (contacts.size < 2) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = GolocalWarning.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("emergency_contacts_recommendation_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = GolocalWarning,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (contacts.isEmpty()) {
                                "Recommended: Add at least 2 trusted emergency contacts before riding."
                            } else {
                                "Recommended: Add 1 more emergency contact (at least 2 total)."
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            contacts.forEachIndexed { index, contact ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .testTag("emergency_contact_row_$index")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = contact.name.ifBlank { "Trusted Contact ${index + 1}" },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                GolocalStatusBadge(
                                    label = contact.relationship.ifBlank { "Emergency" },
                                    tone = if (index == 0) "success" else "info"
                                )
                            }
                            Text(
                                text = AuthDiagnostics.maskPhoneNumber(contact.phone),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetBrainsMono,
                                color = GolocalPrimaryBlue
                            )
                        }
                        IconButton(
                            onClick = { editingTarget = contact },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("edit_emergency_contact_$index")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Edit contact ${contact.name}"
                            )
                        }
                        IconButton(
                            onClick = {
                                val updated = contacts.filterNot { it.id == contact.id && it.phone == contact.phone }
                                onSaveContacts(updated)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("delete_emergency_contact_$index")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Remove contact ${contact.name}",
                                tint = GolocalError
                            )
                        }
                    }
                }
            }

            if (contacts.size < 5) {
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("add_emergency_contact_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (contacts.isEmpty()) "Add Emergency Contact" else "Add Another Contact (${contacts.size}/5)")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Shield, contentDescription = null, tint = GolocalSuccess, modifier = Modifier.size(16.dp))
                    Text(
                        text = "Maximum of 5 emergency contacts configured.",
                        style = MaterialTheme.typography.labelSmall,
                        color = GolocalSuccess
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        EmergencyContactEditorDialog(
            initial = null,
            ownVerifiedMobileE164 = ownVerifiedMobileE164,
            existingPhones = contacts.map { it.phone }.toSet(),
            onDismiss = { showAddDialog = false },
            onConfirm = { entry ->
                onSaveContacts((contacts + entry).take(5))
                showAddDialog = false
            }
        )
    }

    editingTarget?.let { target ->
        EmergencyContactEditorDialog(
            initial = target,
            ownVerifiedMobileE164 = ownVerifiedMobileE164,
            existingPhones = contacts.filterNot { it.id == target.id && it.phone == target.phone }.map { it.phone }.toSet(),
            onDismiss = { editingTarget = null },
            onConfirm = { updated ->
                val next = contacts.map { existing ->
                    if (existing.id == target.id && existing.phone == target.phone) updated else existing
                }
                onSaveContacts(next)
                editingTarget = null
            }
        )
    }
}

@Composable
private fun EmergencyContactEditorDialog(
    initial: EmergencyContactEntry?,
    ownVerifiedMobileE164: String,
    existingPhones: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (EmergencyContactEntry) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var phone by remember(initial) { mutableStateOf(initial?.phone.orEmpty()) }
    var relationship by remember(initial) { mutableStateOf(initial?.relationship?.ifBlank { "Family" } ?: "Family") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Emergency Contact" else "Edit Emergency Contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Only enter numbers of trusted contacts who can assist during a safety alert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!errorMsg.isNullOrBlank()) {
                    Text(
                        text = errorMsg.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = GolocalError,
                        modifier = Modifier.testTag("emergency_contact_dialog_error")
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(50)
                        errorMsg = null
                    },
                    label = { Text("Contact Name *") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("emergency_contact_name_input")
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = {
                        phone = it.filter { ch -> ch.isDigit() || ch == '+' }.take(16)
                        errorMsg = null
                    },
                    label = { Text("Mobile Number (+91...) *") },
                    placeholder = { Text("9876543210") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("emergency_contact_phone_input")
                )
                OutlinedTextField(
                    value = relationship,
                    onValueChange = { relationship = it.take(40) },
                    label = { Text("Relationship (e.g. Spouse, Parent)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("emergency_contact_relation_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = name.trim()
                    if (cleanName.length < 2) {
                        errorMsg = "Enter a valid contact name (min 2 characters)."
                        return@Button
                    }
                    val result = RegistrationValidator.validateEmergencyContact(
                        country = SupportedCountryCodes.INDIA,
                        rawEmergency = phone,
                        ownVerifiedMobileE164 = ownVerifiedMobileE164
                    )
                    when (result) {
                        is PhoneValidationResult.Invalid -> errorMsg = result.message
                        is PhoneValidationResult.Valid -> {
                            if (result.e164Phone in existingPhones) {
                                errorMsg = "This number is already saved in your emergency list."
                            } else {
                                onConfirm(
                                    EmergencyContactEntry(
                                        id = initial?.id?.takeIf { it.isNotBlank() }
                                            ?: "ec_${UUID.randomUUID().toString().take(8)}",
                                        name = cleanName,
                                        phone = result.e164Phone,
                                        relationship = relationship.trim().ifBlank { "Emergency Contact" }
                                    )
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.testTag("emergency_contact_save_button")
            ) {
                Text("Save Privately")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
