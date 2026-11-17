package com.example.customer

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Booking
import com.example.data.model.CustomerCancelReason
import com.example.data.model.DisputeRecord
import com.example.data.model.FareConfig
import com.example.data.model.RiderCancelReason
import com.example.domain.CancellationAndFraudEngine
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.JetBrainsMono

@Composable
fun StructuredCancellationDialog(
    booking: Booking,
    actorRole: String, // "customer" or "driver"
    fareConfig: FareConfig?,
    onDismiss: () -> Unit,
    onConfirmCancel: (reasonCode: String, reasonLabel: String) -> Unit
) {
    val options: List<Pair<String, String>> = remember(actorRole) {
        if (actorRole == "driver") {
            RiderCancelReason.entries.map { it.wire to it.label }
        } else {
            CustomerCancelReason.entries.map { it.wire to it.label }
        }
    }
    var selectedCode by remember { mutableStateOf(options.first().first) }
    var customNote by remember { mutableStateOf("") }

    val selectedLabel = options.firstOrNull { it.first == selectedCode }?.second ?: "Other"
    val finalLabel = if (selectedCode == "other" && customNote.isNotBlank()) {
        "$selectedLabel: ${customNote.trim()}"
    } else {
        selectedLabel
    }

    val evaluation = remember(booking.id, booking.status, actorRole, selectedCode, fareConfig) {
        CancellationAndFraudEngine.evaluateRideCancellation(
            booking = booking,
            actorRole = actorRole,
            reasonCode = selectedCode,
            fareConfig = fareConfig
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (actorRole == "driver") "Cancel Assigned Ride?" else "Cancel Your Ride?",
                modifier = Modifier.testTag("structured_cancel_dialog_title")
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Select a cancellation reason:", style = MaterialTheme.typography.labelLarge)
                options.forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { selectedCode = code },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedCode == code,
                            onClick = { selectedCode = code }
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (selectedCode == "other") {
                    OutlinedTextField(
                        value = customNote,
                        onValueChange = { customNote = it.take(120) },
                        label = { Text("Specify brief reason") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (evaluation.cancellationFeeInr > 0.0) {
                        GolocalError.copy(alpha = 0.09f)
                    } else {
                        GolocalSuccess.copy(alpha = 0.10f)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Policy Evaluation", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (evaluation.allowed) evaluation.summary else (evaluation.error ?: "Not permitted"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = evaluation.allowed,
                colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                onClick = { onConfirmCancel(selectedCode, finalLabel) },
                modifier = Modifier.testTag("confirm_structured_cancel_button")
            ) {
                Text("Confirm Cancel")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep Ride") }
        }
    )
}

@Composable
fun MultiCriteriaRatingDialog(
    booking: Booking,
    onDismiss: () -> Unit,
    onSubmit: (
        behaviourStars: Int,
        vehicleStars: Int,
        punctualityStars: Int,
        safetyStars: Int,
        review: String
    ) -> Unit
) {
    var behaviour by remember { mutableIntStateOf(5) }
    var vehicle by remember { mutableIntStateOf(5) }
    var punctuality by remember { mutableIntStateOf(5) }
    var safety by remember { mutableIntStateOf(5) }
    var review by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Rate Trip #${booking.id.take(8)}", modifier = Modifier.testTag("multi_rating_dialog_title"))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "One genuine rating per completed ride helps passengers across West Bengal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StarCriterionRow("Driver Behaviour", behaviour) { behaviour = it }
                StarCriterionRow("Vehicle Cleanliness", vehicle) { vehicle = it }
                StarCriterionRow("Punctuality", punctuality) { punctuality = it }
                StarCriterionRow("Safety & Driving", safety) { safety = it }

                OutlinedTextField(
                    value = review,
                    onValueChange = { review = it.take(400) },
                    label = { Text("Trip feedback (optional)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("rating_review_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(behaviour, vehicle, punctuality, safety, review) },
                modifier = Modifier.testTag("submit_multi_rating_button")
            ) {
                Text("Submit Rating")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun StarCriterionRow(
    label: String,
    value: Int,
    onChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Row {
            (1..5).forEach { star ->
                IconButton(
                    onClick = { onChange(star) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (star <= value) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = "$star stars for $label",
                        tint = if (star <= value) GolocalWarning else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SubmitDisputeDialog(
    bookingId: String = "",
    busBookingId: String = "",
    defaultRefundInr: Double = 0.0,
    onDismiss: () -> Unit,
    onSubmit: (category: String, description: String, requestedRefundInr: Double) -> Unit
) {
    val categories = listOf(
        "fare_overcharge" to "Fare Overcharge",
        "no_show_fee" to "Disputed Fee",
        "rude_behavior" to "Conduct Issue",
        "safety" to "Safety Concern",
        "bus_refund" to "Bus Ticket Refund",
        "other" to "Other Issue"
    )
    var category by remember { mutableStateOf(if (busBookingId.isNotBlank()) "bus_refund" else "fare_overcharge") }
    var description by remember { mutableStateOf("") }
    var refundText by remember {
        mutableStateOf(if (defaultRefundInr > 0.0) defaultRefundInr.toInt().toString() else "0")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File Formal Dispute") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Ref: ${(bookingId.ifBlank { busBookingId }).take(12).ifBlank { "General" }}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetBrainsMono
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { (id, label) ->
                        FilterChip(
                            selected = category == id,
                            onClick = { category = id },
                            label = { Text(label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(600) },
                    label = { Text("Describe what happened (min 8 chars) *") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("dispute_description_input")
                )
                OutlinedTextField(
                    value = refundText,
                    onValueChange = { refundText = it.filter { ch -> ch.isDigit() || ch == '.' }.take(8) },
                    label = { Text("Requested Refund Amount (₹, optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = description.trim().length >= 8,
                onClick = {
                    onSubmit(
                        category,
                        description,
                        refundText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                    )
                },
                modifier = Modifier.testTag("submit_dispute_button")
            ) {
                Text("Submit Dispute")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun CustomerDisputesListSection(disputes: List<DisputeRecord>) {
    if (disputes.isEmpty()) {
        GolocalEmptyState(
            title = "No Open Disputes",
            message = "If you encounter a fare issue, refund mismatch, or safety concern on a ride or bus ticket, file a dispute from Bookings."
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            disputes.forEach { d ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(d.category.replace('_', ' ').uppercase(), style = MaterialTheme.typography.titleSmall)
                            GolocalStatusBadge(
                                label = d.status.replace('_', ' ').uppercase(),
                                tone = when {
                                    d.status.startsWith("resolved") -> "success"
                                    d.status == "under_review" -> "info"
                                    else -> "warning"
                                }
                            )
                        }
                        Text(d.description, style = MaterialTheme.typography.bodySmall)
                        if (d.requestedRefundInr > 0.0) {
                            Text("Requested Refund: ₹${d.requestedRefundInr.toInt()}", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMono)
                        }
                        if (d.adminResolutionNote.isNotBlank()) {
                            Text(
                                "Admin Resolution: ${d.adminResolutionNote}",
                                style = MaterialTheme.typography.labelMedium,
                                color = GolocalSuccess
                            )
                        }
                    }
                }
            }
        }
    }
}
