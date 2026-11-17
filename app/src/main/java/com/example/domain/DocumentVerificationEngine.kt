package com.example.domain

import com.example.data.model.DriverDocumentItem
import com.example.data.model.VerificationHistoryEntry
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class DocumentExpiryStatus {
    VALID,
    EXPIRING_SOON,
    EXPIRED,
    MISSING,
    INVALID_FORMAT
}

data class RequiredDocSpec(
    val docType: String,
    val displayLabel: String,
    val requiresExpiry: Boolean = true
)

object DocumentVerificationEngine {

    private val ISO_DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    fun requiredDocsForCategory(vehicleCategory: String): List<RequiredDocSpec> {
        val norm = vehicleCategory.trim().lowercase()
        val base = mutableListOf(
            RequiredDocSpec("DRIVING_LICENCE", "Driving Licence (DL)", requiresExpiry = true),
            RequiredDocSpec("RC", "Vehicle Registration Certificate (RC)", requiresExpiry = true),
            RequiredDocSpec("INSURANCE", "Motor Insurance Policy", requiresExpiry = true),
            RequiredDocSpec("PUC", "Pollution Under Control (PUC)", requiresExpiry = true)
        )
        when (norm) {
            "cab", "amb" -> {
                base.add(RequiredDocSpec("FITNESS", "Vehicle Fitness Certificate", requiresExpiry = true))
                base.add(RequiredDocSpec("COMMERCIAL_PERMIT", "Commercial / Tourist Permit", requiresExpiry = true))
            }
            "bus" -> {
                base.add(RequiredDocSpec("FITNESS", "Heavy Vehicle Fitness Certificate", requiresExpiry = true))
                base.add(RequiredDocSpec("COMMERCIAL_PERMIT", "Stage Carriage / Contract Permit", requiresExpiry = true))
            }
        }
        return base
    }

    fun evaluateExpiry(
        expiryIso: String?,
        nowEpochMs: Long = System.currentTimeMillis()
    ): DocumentExpiryStatus {
        val clean = expiryIso?.trim().orEmpty()
        if (clean.isBlank()) return DocumentExpiryStatus.MISSING
        if (!ISO_DATE_REGEX.matches(clean)) return DocumentExpiryStatus.INVALID_FORMAT

        val parsedMs = parseIsoDateUtcEndOfDay(clean) ?: return DocumentExpiryStatus.INVALID_FORMAT
        if (parsedMs < nowEpochMs) return DocumentExpiryStatus.EXPIRED

        val thirtyDaysMs = 30L * 24L * 60L * 60L * 1000L
        return if (parsedMs - nowEpochMs <= thirtyDaysMs) {
            DocumentExpiryStatus.EXPIRING_SOON
        } else {
            DocumentExpiryStatus.VALID
        }
    }

    fun summarizeExpiryAlertLevel(
        docs: List<DriverDocumentItem>,
        vehicleCategory: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Pair<String, String> {
        val required = requiredDocsForCategory(vehicleCategory)
        if (docs.isEmpty()) return "" to "missing"

        var hasExpired = false
        var hasExpiringSoon = false
        var hasMissing = false
        var earliestIso = ""

        required.forEach { spec ->
            val doc = docs.firstOrNull { it.docType == spec.docType }
            if (doc == null || doc.documentNumber.isBlank()) {
                hasMissing = true
            } else if (spec.requiresExpiry) {
                val status = evaluateExpiry(doc.expiryDateIso, nowEpochMs)
                when (status) {
                    DocumentExpiryStatus.EXPIRED -> hasExpired = true
                    DocumentExpiryStatus.EXPIRING_SOON -> hasExpiringSoon = true
                    DocumentExpiryStatus.MISSING, DocumentExpiryStatus.INVALID_FORMAT -> hasMissing = true
                    DocumentExpiryStatus.VALID -> Unit
                }
                if (ISO_DATE_REGEX.matches(doc.expiryDateIso) &&
                    (earliestIso.isBlank() || doc.expiryDateIso < earliestIso)
                ) {
                    earliestIso = doc.expiryDateIso
                }
            }
        }

        val level = when {
            hasExpired -> "expired"
            hasExpiringSoon -> "expiring_soon"
            hasMissing -> "missing"
            else -> "valid"
        }
        return earliestIso to level
    }

    fun validateSubmissionCompleteness(
        vehicleCategory: String,
        docs: List<DriverDocumentItem>,
        nowEpochMs: Long = System.currentTimeMillis()
    ): String? {
        val required = requiredDocsForCategory(vehicleCategory)
        for (spec in required) {
            val item = docs.firstOrNull { it.docType == spec.docType }
                ?: return "${spec.displayLabel} is required for ${vehicleCategory.uppercase()} verification."
            if (item.documentNumber.trim().length < 4) {
                return "Enter a valid document number for ${spec.displayLabel}."
            }
            if (spec.requiresExpiry) {
                when (evaluateExpiry(item.expiryDateIso, nowEpochMs)) {
                    DocumentExpiryStatus.MISSING -> return "Enter expiry date (YYYY-MM-DD) for ${spec.displayLabel}."
                    DocumentExpiryStatus.INVALID_FORMAT -> return "${spec.displayLabel} expiry must be YYYY-MM-DD format."
                    DocumentExpiryStatus.EXPIRED -> return "${spec.displayLabel} appears expired (${item.expiryDateIso}). Upload a valid renewed document."
                    DocumentExpiryStatus.EXPIRING_SOON, DocumentExpiryStatus.VALID -> Unit
                }
            }
        }
        return null
    }

    fun buildAdminDecisionUpdate(
        existingDocs: List<DriverDocumentItem>,
        existingHistory: List<VerificationHistoryEntry>,
        decisionStatus: String,
        reason: String,
        adminUid: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Pair<List<DriverDocumentItem>, List<VerificationHistoryEntry>> {
        val cleanReason = reason.trim()
        require(decisionStatus in setOf("verified", "rejected", "suspended")) {
            "Unsupported verification decision: $decisionStatus"
        }
        if (decisionStatus != "verified") {
            require(cleanReason.length >= 5) {
                "Rejection or suspension reason is mandatory (at least 5 characters)."
            }
        }
        val updatedDocs = existingDocs.map { doc ->
            doc.copy(
                verificationStatus = decisionStatus,
                rejectionReason = if (decisionStatus == "verified") "" else cleanReason,
                reviewerId = adminUid,
                reviewedAtEpochMs = nowEpochMs
            )
        }
        val historyAction = when (decisionStatus) {
            "verified" -> "approved"
            "suspended" -> "suspended"
            else -> "rejected"
        }
        val newEntry = VerificationHistoryEntry(
            action = historyAction,
            status = decisionStatus,
            reason = if (decisionStatus == "verified") cleanReason.ifBlank { "All submitted documents verified" } else cleanReason,
            actorId = adminUid,
            timestampEpochMs = nowEpochMs
        )
        return updatedDocs to (existingHistory + newEntry).takeLast(30)
    }

    private fun parseIsoDateUtcEndOfDay(iso: String): Long? {
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = sdf.parse(iso) ?: return null
            // Treat expiry date as valid through end of that UTC day
            parsed.time + (24L * 60L * 60L * 1000L) - 1L
        }.getOrNull()
    }
}
