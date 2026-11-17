package com.example.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale

data class OcrExtractionResult(
    val rawTextPreview: String = "",
    val suggestedNumber: String = "",
    val suggestedExpiryIso: String = "",
    val mismatchWarning: String = "",
    val disclaimer: String = ASSISTIVE_OCR_DISCLAIMER
) {
    companion object {
        const val ASSISTIVE_OCR_DISCLAIMER =
            "Assistive on-device ML Kit OCR only — extracts readable fields from the photo. It does NOT verify government/RTO authenticity; manual Admin approval is mandatory."
    }
}

object DocumentOcrAnalyzer {

    private val DL_REGEX = Regex("\\b[A-Z]{2}[-\\s]?\\d{2}[-\\s]?\\d{4}[-\\s]?\\d{6,8}\\b")
    private val PLATE_REGEX = Regex("\\b[A-Z]{2}[-\\s]?\\d{1,2}[-\\s]?[A-Z]{1,3}[-\\s]?\\d{3,4}\\b")
    private val POLICY_REGEX = Regex("\\b[A-Z0-9]{2,6}[-/]?[A-Z0-9]{4,14}\\b")
    private val ISO_DATE_REGEX = Regex("\\b(20\\d{2})[-/](\\d{2})[-/](\\d{2})\\b")
    private val DMY_DATE_REGEX = Regex("\\b(\\d{2})[-/](\\d{2})[-/](20\\d{2})\\b")

    suspend fun analyzeUri(
        context: Context,
        imageUri: Uri,
        docType: String,
        userEnteredNumber: String = "",
        userEnteredExpiryIso: String = ""
    ): Result<OcrExtractionResult> = runCatching {
        val image = InputImage.fromFilePath(context, imageUri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val visionText = recognizer.process(image).await()
            extractFromRecognizedText(
                rawText = visionText.text,
                docType = docType,
                userEnteredNumber = userEnteredNumber,
                userEnteredExpiryIso = userEnteredExpiryIso
            )
        } finally {
            runCatching { recognizer.close() }
        }
    }

    fun extractFromRecognizedText(
        rawText: String,
        docType: String,
        userEnteredNumber: String = "",
        userEnteredExpiryIso: String = ""
    ): OcrExtractionResult {
        val upper = rawText.uppercase(Locale.US)
        val suggestedNumber = when (docType) {
            "DRIVING_LICENCE" -> DL_REGEX.find(upper)?.value
                ?: PLATE_REGEX.find(upper)?.value
                ?: ""
            "RC", "FITNESS", "PUC" -> PLATE_REGEX.find(upper)?.value
                ?: POLICY_REGEX.findAll(upper).firstOrNull { it.value.any(Char::isDigit) && it.value.length >= 8 }?.value
                ?: ""
            else -> POLICY_REGEX.findAll(upper)
                .firstOrNull { it.value.any(Char::isDigit) && it.value.length >= 8 }
                ?.value
                .orEmpty()
        }.replace(" ", "")

        val candidateDates = buildList {
            ISO_DATE_REGEX.findAll(upper).forEach { m ->
                add("${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}")
            }
            DMY_DATE_REGEX.findAll(upper).forEach { m ->
                val day = m.groupValues[1].toIntOrNull() ?: 0
                val month = m.groupValues[2].toIntOrNull() ?: 0
                val year = m.groupValues[3]
                if (day in 1..31 && month in 1..12) {
                    add(String.format(Locale.US, "%s-%02d-%02d", year, month, day))
                }
            }
        }.distinct().sortedDescending()

        // Prefer the latest valid future/expiry date if multiple dates exist (e.g. Issue Date + Valid Till)
        val suggestedExpiry = candidateDates.firstOrNull().orEmpty()

        val warnings = mutableListOf<String>()
        val cleanEnteredNum = userEnteredNumber.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")
        val cleanOcrNum = suggestedNumber.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")
        if (cleanEnteredNum.isNotBlank() && cleanOcrNum.isNotBlank() &&
            !cleanOcrNum.contains(cleanEnteredNum) && !cleanEnteredNum.contains(cleanOcrNum)
        ) {
            warnings.add("Entered number ($cleanEnteredNum) differs from OCR-read value ($cleanOcrNum).")
        }
        val cleanEnteredExp = userEnteredExpiryIso.trim()
        if (cleanEnteredExp.isNotBlank() && suggestedExpiry.isNotBlank() && cleanEnteredExp != suggestedExpiry) {
            warnings.add("Entered expiry ($cleanEnteredExp) differs from OCR-read date ($suggestedExpiry).")
        }

        return OcrExtractionResult(
            rawTextPreview = rawText.trim().take(280),
            suggestedNumber = suggestedNumber,
            suggestedExpiryIso = suggestedExpiry,
            mismatchWarning = warnings.joinToString(" "),
            disclaimer = OcrExtractionResult.ASSISTIVE_OCR_DISCLAIMER
        )
    }
}
