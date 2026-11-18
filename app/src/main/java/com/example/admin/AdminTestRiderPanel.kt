package com.example.admin

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.TestRiderAuditLog
import com.example.data.model.TestRiderProfile
import com.example.data.model.TestRiderRequest
import com.example.shared.maskPhoneNumber
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.GolocalWarningContainer
import com.example.ui.theme.JetBrainsMono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class TestRiderReviewDialogState(
    val request: TestRiderRequest,
    val targetStatus: String
)

private data class OpsRecommendDialogState(
    val request: TestRiderRequest,
    val recommendation: String
)

@Composable
internal fun AdminTestRiderRequestsPanel(
    requests: List<TestRiderRequest>,
    auditLogs: List<TestRiderAuditLog>,
    isOwnerAdmin: Boolean,
    onReviewRequest: (request: TestRiderRequest, newStatus: String, reason: String, note: String) -> Unit,
    onRecommendRequest: (request: TestRiderRequest, recommendation: String, note: String) -> Unit
) {
    val statuses = listOf("All", "Pending", "Approved", "Rejected", "Suspended", "Revoked")
    var selectedStatus by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }
    var reviewDialog by remember { mutableStateOf<TestRiderReviewDialogState?>(null) }
    var opsDialog by remember { mutableStateOf<OpsRecommendDialogState?>(null) }

    val filtered = remember(requests, selectedStatus, searchQuery) {
        val q = searchQuery.trim().lowercase()
        requests.filter { req ->
            val statusMatches = selectedStatus == "All" || req.status.equals(selectedStatus, ignoreCase = true)
            val queryMatches = q.isBlank() ||
                req.fullName.lowercase().contains(q) ||
                req.mobileNumber.lowercase().contains(q) ||
                req.vehicleCategory.lowercase().contains(q) ||
                req.testPurpose.lowercase().contains(q) ||
                req.uid.lowercase().contains(q)
            statusMatches && queryMatches
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isOwnerAdmin) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = GolocalWarningContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ops_test_rider_rbac_notice")
                ) {
                    Text(
                        text = "Operations Staff RBAC: You may recommend approval or rejection with a note. Only Owner Admin can finalize Approve, Reject, Suspend, or Revoke decisions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GolocalDeepNavy,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                label = { Text("Search applicant name, vehicle, purpose, or UID") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("admin_test_rider_search")
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                statuses.forEach { status ->
                    val count = if (status == "All") requests.size else requests.count { it.status.equals(status, ignoreCase = true) }
                    FilterChip(
                        selected = selectedStatus == status,
                        onClick = { selectedStatus = status },
                        label = { Text("$status ($count)") },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("test_rider_filter_${status.lowercase()}")
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                GolocalEmptyState(
                    title = "No Test Rider Requests",
                    message = "Applications submitted via Test Rider Sandbox mode appear here for review."
                )
            }
        } else {
            items(filtered, key = { it.uid.ifBlank { it.id } }) { req ->
                AdminTestRiderRequestCard(
                    request = req,
                    isOwnerAdmin = isOwnerAdmin,
                    onApprove = { onReviewRequest(req, "approved", "", "Approved by Owner Admin") },
                    onOpenReasonReview = { target -> reviewDialog = TestRiderReviewDialogState(req, target) },
                    onOpenOpsRecommend = { rec -> opsDialog = OpsRecommendDialogState(req, rec) }
                )
            }
        }

        if (auditLogs.isNotEmpty()) {
            item {
                Text(
                    text = "Test Rider Governance Audit (${auditLogs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            items(auditLogs.take(20), key = { it.id.ifBlank { "${it.targetUid}_${it.createdAt?.seconds ?: 0L}_${it.action}" } }) { log ->
                TestRiderAuditLogRow(log = log)
            }
        }
    }

    reviewDialog?.let { dlg ->
        var reason by remember(dlg) { mutableStateOf("") }
        val verb = when (dlg.targetStatus) {
            "rejected" -> "Reject"
            "suspended" -> "Suspend"
            else -> "Revoke"
        }
        AlertDialog(
            onDismissRequest = { reviewDialog = null },
            title = { Text("$verb Test Rider Access") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Applicant: ${dlg.request.fullName.ifBlank { dlg.request.uid.take(8) }} (${dlg.request.vehicleCategory.uppercase()})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Reason (min 4 chars)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_review_reason_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onReviewRequest(dlg.request, dlg.targetStatus, reason.trim(), "")
                        reviewDialog = null
                    },
                    enabled = reason.trim().length >= 4,
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("admin_confirm_review_button")
                ) { Text("Confirm $verb") }
            },
            dismissButton = {
                TextButton(
                    onClick = { reviewDialog = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Cancel") }
            }
        )
    }

    opsDialog?.let { dlg ->
        var note by remember(dlg) { mutableStateOf(dlg.request.opsRecommendedNote) }
        val label = if (dlg.recommendation == "recommend_approve") "Recommend Approve" else "Recommend Reject"
        AlertDialog(
            onDismissRequest = { opsDialog = null },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Advisory note for Owner Admin regarding ${dlg.request.fullName.ifBlank { dlg.request.uid.take(8) }}:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Recommendation note (min 4 chars)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ops_recommend_note_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRecommendRequest(dlg.request, dlg.recommendation, note.trim())
                        opsDialog = null
                    },
                    enabled = note.trim().length >= 4,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("ops_confirm_recommend_button")
                ) { Text("Save Recommendation") }
            },
            dismissButton = {
                TextButton(
                    onClick = { opsDialog = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AdminTestRiderRequestCard(
    request: TestRiderRequest,
    isOwnerAdmin: Boolean,
    onApprove: () -> Unit,
    onOpenReasonReview: (String) -> Unit,
    onOpenOpsRecommend: (String) -> Unit
) {
    val uid = request.uid.ifBlank { request.id }
    val statusTone = when (request.status) {
        "approved" -> "success"
        "pending" -> "warning"
        "rejected", "suspended", "revoked" -> "error"
        else -> "info"
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .testTag("admin_test_rider_card_$uid")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = request.fullName.ifBlank { "Test Rider Applicant" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${maskPhoneNumber(request.mobileNumber)} • ${request.vehicleCategory.uppercase()} • UID ${uid.take(8)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                GolocalStatusBadge(label = request.status.uppercase(), tone = statusTone)
            }

            if (request.testPurpose.isNotBlank()) {
                Text(
                    text = "Purpose: ${request.testPurpose}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (request.notes.isNotBlank()) {
                Text(
                    text = "Notes: ${request.notes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (request.rejectionReason.isNotBlank()) {
                Text(
                    text = "Reason: ${request.rejectionReason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = GolocalError
                )
            }

            if (request.opsRecommendation.isNotBlank()) {
                val recLabel = if (request.opsRecommendation == "recommend_approve") {
                    "OPS RECOMMENDS APPROVE"
                } else {
                    "OPS RECOMMENDS REJECT"
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        GolocalStatusBadge(
                            label = recLabel,
                            tone = if (request.opsRecommendation == "recommend_approve") "success" else "warning"
                        )
                        if (request.opsRecommendedNote.isNotBlank()) {
                            Text(
                                text = request.opsRecommendedNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            if (isOwnerAdmin) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (request.status != "approved") {
                        Button(
                            onClick = onApprove,
                            colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("admin_approve_test_rider_$uid")
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Approve", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (request.status == "pending") {
                        OutlinedButton(
                            onClick = { onOpenReasonReview("rejected") },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalError),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("admin_reject_test_rider_$uid")
                        ) {
                            Text("Reject", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (request.status == "approved") {
                        OutlinedButton(
                            onClick = { onOpenReasonReview("suspended") },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalWarning),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("admin_suspend_test_rider_$uid")
                        ) {
                            Icon(Icons.Filled.PauseCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Suspend", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = { onOpenReasonReview("revoked") },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalError),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("admin_revoke_test_rider_$uid")
                        ) {
                            Icon(Icons.Filled.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Revoke", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onOpenOpsRecommend("recommend_approve") },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("ops_recommend_approve_$uid")
                    ) {
                        Icon(Icons.Filled.ThumbUp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Rec. Approve", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { onOpenOpsRecommend("recommend_reject") },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalError),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("ops_recommend_reject_$uid")
                    ) {
                        Icon(Icons.Filled.ThumbDown, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Rec. Reject", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AdminApprovedTestRidersPanel(
    riders: List<TestRiderProfile>,
    requests: List<TestRiderRequest>,
    isOwnerAdmin: Boolean,
    onReviewRequest: (request: TestRiderRequest, newStatus: String, reason: String, note: String) -> Unit
) {
    var reasonDialog by remember { mutableStateOf<TestRiderReviewDialogState?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (riders.isEmpty()) {
            item {
                GolocalEmptyState(
                    title = "No Test Riders Directory Entries",
                    message = "Approve a Test Rider request to populate the sandbox rider roster."
                )
            }
        } else {
            items(riders, key = { it.uid.ifBlank { it.id } }) { rider ->
                val uid = rider.uid.ifBlank { rider.id }
                val linkedRequest = requests.firstOrNull { it.uid == uid || it.id == uid }
                    ?: TestRiderRequest(
                        id = uid,
                        uid = uid,
                        fullName = rider.displayName,
                        mobileNumber = rider.mobileNumber,
                        vehicleCategory = rider.vehicleCategory,
                        status = rider.status
                    )
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                        .testTag("admin_approved_rider_card_$uid")
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = rider.displayName.ifBlank { "Test Rider" },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${rider.vehicleCategory.uppercase()} • ${rider.vehicleLabel.ifBlank { "SANDBOX" }} • ${maskPhoneNumber(rider.mobileNumber)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (rider.isOnline && rider.status == "approved") {
                                    GolocalStatusBadge(label = "ONLINE", tone = "success")
                                }
                                GolocalStatusBadge(
                                    label = rider.status.uppercase(),
                                    tone = when (rider.status) {
                                        "approved" -> "info"
                                        else -> "error"
                                    }
                                )
                            }
                        }

                        if (isOwnerAdmin) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (rider.status == "approved") {
                                    OutlinedButton(
                                        onClick = { reasonDialog = TestRiderReviewDialogState(linkedRequest, "suspended") },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalWarning),
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    ) { Text("Suspend") }
                                    OutlinedButton(
                                        onClick = { reasonDialog = TestRiderReviewDialogState(linkedRequest, "revoked") },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalError),
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    ) { Text("Revoke") }
                                } else {
                                    Button(
                                        onClick = { onReviewRequest(linkedRequest, "approved", "", "Re-approved by Owner Admin") },
                                        colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    ) { Text("Re-Approve") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    reasonDialog?.let { dlg ->
        var reason by remember(dlg) { mutableStateOf("") }
        val verb = if (dlg.targetStatus == "suspended") "Suspend" else "Revoke"
        AlertDialog(
            onDismissRequest = { reasonDialog = null },
            title = { Text("$verb Test Rider?") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason (min 4 chars)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onReviewRequest(dlg.request, dlg.targetStatus, reason.trim(), "")
                        reasonDialog = null
                    },
                    enabled = reason.trim().length >= 4,
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Confirm $verb") }
            },
            dismissButton = {
                TextButton(
                    onClick = { reasonDialog = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TestRiderAuditLogRow(log: TestRiderAuditLog) {
    val timeLabel = remember(log.id, log.createdAt) {
        val ms = log.createdAt?.toDate()?.time
        if (ms == null) "Just now"
        else SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date(ms))
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(log.action, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = GolocalPrimaryBlue)
                Text(timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = "Status: ${log.previousStatus.ifBlank { "none" }} -> ${log.newStatus}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (log.reason.isNotBlank() || log.note.isNotBlank()) {
                Text(
                    text = log.reason.ifBlank { log.note },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "Actor: ${log.actorRole} (${log.actorUid.take(8)}) • Target: ${log.targetUid.take(8)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
