package com.example.rider

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.DriverDocumentItem
import com.example.data.model.VerificationHistoryEntry
import com.example.domain.DocumentExpiryStatus
import com.example.domain.DocumentVerificationEngine
import com.example.ocr.DocumentOcrAnalyzer
import com.example.ocr.OcrExtractionResult
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalSuccess
import com.example.viewmodel.GovernanceViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StructuredDocumentChecklistEditor(
    vehicleCategory: String,
    docsState: SnapshotStateMapLike,
    governanceViewModel: GovernanceViewModel?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requiredSpecs = remember(vehicleCategory) {
        DocumentVerificationEngine.requiredDocsForCategory(vehicleCategory)
    }

    var activeUploadDocType by remember { mutableStateOf<String?>(null) }
    var activeOcrDocType by remember { mutableStateOf<String?>(null) }
    var busyDocType by remember { mutableStateOf<String?>(null) }
    var ocrInfoMessage by remember { mutableStateOf<String?>(null) }

    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        val docType = activeUploadDocType
        if (uri != null && docType != null) {
            val current = docsState.getItem(docType)
            if (governanceViewModel != null) {
                busyDocType = docType
                scope.launch {
                    governanceViewModel.uploadDocumentPhoto("kyc_docs", docType, uri)
                        .onSuccess { url ->
                            docsState.putItem(current.copy(storageUrl = url))
                        }
                        .onFailure {
                            docsState.putItem(current.copy(storageUrl = uri.toString()))
                        }
                    busyDocType = null
                }
            } else {
                docsState.putItem(current.copy(storageUrl = uri.toString()))
            }
        }
    }

    val ocrPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        val docType = activeOcrDocType
        if (uri != null && docType != null) {
            busyDocType = docType
            scope.launch {
                val cur = docsState.getItem(docType)
                DocumentOcrAnalyzer.analyzeUri(
                    context = context,
                    imageUri = uri,
                    docType = docType,
                    userEnteredNumber = cur.documentNumber,
                    userEnteredExpiryIso = cur.expiryDateIso
                ).onSuccess { res ->
                    val updated = cur.copy(
                        documentNumber = cur.documentNumber.ifBlank { res.suggestedNumber },
                        expiryDateIso = cur.expiryDateIso.ifBlank { res.suggestedExpiryIso },
                        ocrExtractedNumber = res.suggestedNumber,
                        ocrExtractedExpiry = res.suggestedExpiryIso,
                        ocrMismatchWarning = res.mismatchWarning
                    )
                    docsState.putItem(updated)
                    ocrInfoMessage = res.mismatchWarning.ifBlank {
                        "OCR scanned ${cur.label}: No=${res.suggestedNumber.ifBlank { "not detected" }}, Exp=${res.suggestedExpiryIso.ifBlank { "not detected" }}"
                    }
                }.onFailure { err ->
                    ocrInfoMessage = err.localizedMessage ?: "Unable to scan text from selected image."
                }
                busyDocType = null
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = OcrExtractionResult.ASSISTIVE_OCR_DISCLAIMER,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp)
        )
    }

    ocrInfoMessage?.let { msg ->
        Text(msg, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }

    requiredSpecs.forEach { spec ->
        val item = docsState.getItem(spec.docType).let {
            if (it.label.isBlank()) it.copy(label = spec.displayLabel) else it
        }
        val expiryStatus = DocumentVerificationEngine.evaluateExpiry(item.expiryDateIso)

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(spec.displayLabel, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    GolocalStatusBadge(
                        label = expiryStatus.name.replace('_', ' '),
                        tone = when (expiryStatus) {
                            DocumentExpiryStatus.VALID -> "success"
                            DocumentExpiryStatus.EXPIRING_SOON -> "warning"
                            else -> "error"
                        }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = item.documentNumber,
                        onValueChange = { docsState.putItem(item.copy(documentNumber = it.take(36))) },
                        label = { Text("Doc Number *") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("kyc_doc_number_${spec.docType}")
                    )
                    OutlinedTextField(
                        value = item.expiryDateIso,
                        onValueChange = { docsState.putItem(item.copy(expiryDateIso = it.take(10))) },
                        label = { Text("Expiry YYYY-MM-DD *") },
                        placeholder = { Text("2028-12-31") },
                        singleLine = true,
                        modifier = Modifier.weight(0.95f).testTag("kyc_doc_expiry_${spec.docType}")
                    )
                }

                if (item.ocrMismatchWarning.isNotBlank()) {
                    Text(item.ocrMismatchWarning, style = MaterialTheme.typography.labelSmall, color = GolocalError)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            activeUploadDocType = spec.docType
                            uploadPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        enabled = busyDocType != spec.docType,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            when {
                                busyDocType == spec.docType -> "Working..."
                                item.storageUrl.isNotBlank() -> "Photo Uploaded ✓"
                                else -> "Upload Photo"
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            activeOcrDocType = spec.docType
                            ocrPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        enabled = busyDocType != spec.docType,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("OCR Prefill")
                    }
                }
            }
        }
    }
}

class SnapshotStateMapLike(
    initialDocs: List<DriverDocumentItem>
) {
    private val map = mutableStateMapOf<String, DriverDocumentItem>().apply {
        initialDocs.forEach { if (it.docType.isNotBlank()) put(it.docType, it) }
    }

    fun getItem(docType: String): DriverDocumentItem =
        map[docType] ?: DriverDocumentItem(docType = docType)

    fun putItem(item: DriverDocumentItem) {
        map[item.docType] = item
    }

    fun buildListForCategory(vehicleCategory: String): List<DriverDocumentItem> {
        return DocumentVerificationEngine.requiredDocsForCategory(vehicleCategory).map { spec ->
            val current = map[spec.docType] ?: DriverDocumentItem(docType = spec.docType, label = spec.displayLabel)
            current.copy(label = spec.displayLabel)
        }
    }
}

@Composable
fun VerificationTimelineCard(history: List<VerificationHistoryEntry>) {
    if (history.isEmpty()) return
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Verification Audit Timeline", style = MaterialTheme.typography.titleSmall)
            history.asReversed().take(6).forEach { entry ->
                val dateLabel = if (entry.timestampEpochMs > 0L) fmt.format(Date(entry.timestampEpochMs)) else ""
                Text(
                    "• ${entry.action.uppercase()} (${entry.status}) $dateLabel — ${entry.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.status == "verified") GolocalSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
