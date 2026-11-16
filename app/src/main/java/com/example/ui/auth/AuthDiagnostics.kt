package com.example.ui.auth

import android.util.Log
import java.util.Collections

enum class AuthStage {
    AUTH_INIT,
    GOOGLE_CREDENTIAL_REQUEST,
    GOOGLE_CREDENTIAL_RECEIVED,
    PHONE_OTP_REQUESTED,
    PHONE_OTP_SENT,
    PHONE_OTP_VERIFYING,
    PHONE_OTP_VERIFIED,
    PHONE_LINK_STARTED,
    PHONE_LINK_SUCCESS,
    FIREBASE_SIGN_IN_STARTED,
    FIREBASE_SIGN_IN_SUCCESS,
    FIRESTORE_ACCOUNT_CHECK,
    FIRESTORE_ACCOUNT_EXISTS,
    FIRESTORE_ACCOUNT_CREATED,
    FIRESTORE_PROFILE_CREATED,
    AUTH_FLOW_COMPLETED,
    AUTH_CANCELLED,
    AUTH_FAILED,
    AUTH_SIGN_OUT_STARTED,
    AUTH_SIGN_OUT_COMPLETED
}

data class AuthDiagnosticEvent(
    val stage: AuthStage,
    val flow: String,
    val firebaseErrorCode: String? = null,
    val exceptionType: String? = null,
    val detail: String = ""
)

object AuthDiagnostics {
    private const val TAG = "GoLocalAuth"
    private const val MAX_EVENTS = 64

    private val recordedEvents: MutableList<AuthDiagnosticEvent> =
        Collections.synchronizedList(mutableListOf())

    fun maskPhoneNumber(rawPhone: String): String {
        val cleaned = rawPhone.trim()
        if (cleaned.length <= 4) return "****"
        val prefix = if (cleaned.startsWith("+") && cleaned.length > 6) cleaned.take(3) else ""
        val suffix = cleaned.takeLast(4)
        val maskLen = (cleaned.length - prefix.length - suffix.length).coerceAtLeast(4)
        return "$prefix${"*".repeat(maskLen)}$suffix"
    }

    fun logStage(
        stage: AuthStage,
        flow: String,
        detail: String = ""
    ) {
        val safeDetail = sanitizeDetail(detail)
        record(AuthDiagnosticEvent(stage = stage, flow = flow, detail = safeDetail))
        Log.i(TAG, "[${stage.name}] flow=$flow detail=$safeDetail")
    }

    fun logWarning(
        stage: AuthStage,
        flow: String,
        exceptionType: String? = null,
        firebaseErrorCode: String? = null,
        detail: String = ""
    ) {
        val safeDetail = sanitizeDetail(detail)
        record(
            AuthDiagnosticEvent(
                stage = stage,
                flow = flow,
                firebaseErrorCode = firebaseErrorCode,
                exceptionType = exceptionType,
                detail = safeDetail
            )
        )
        Log.w(
            TAG,
            "[${stage.name}] flow=$flow errorCode=${firebaseErrorCode ?: "none"} exception=${exceptionType ?: "none"} detail=$safeDetail"
        )
    }

    fun logFailure(
        stage: AuthStage,
        flow: String,
        exceptionType: String,
        firebaseErrorCode: String? = null,
        detail: String = ""
    ) {
        val safeDetail = sanitizeDetail(detail)
        record(
            AuthDiagnosticEvent(
                stage = stage,
                flow = flow,
                firebaseErrorCode = firebaseErrorCode,
                exceptionType = exceptionType,
                detail = safeDetail
            )
        )
        Log.e(
            TAG,
            "[${stage.name}] flow=$flow errorCode=${firebaseErrorCode ?: "none"} exception=$exceptionType detail=$safeDetail"
        )
    }

    fun recentEvents(): List<AuthDiagnosticEvent> = synchronized(recordedEvents) {
        recordedEvents.toList()
    }

    fun clearEvents() = synchronized(recordedEvents) {
        recordedEvents.clear()
    }

    private fun record(event: AuthDiagnosticEvent) {
        synchronized(recordedEvents) {
            if (recordedEvents.size >= MAX_EVENTS) {
                recordedEvents.removeAt(0)
            }
            recordedEvents.add(event)
        }
    }

    private fun sanitizeDetail(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(Regex("eyJ[A-Za-z0-9_\\-]+=*\\.[A-Za-z0-9_\\-]+=*\\.[A-Za-z0-9_\\-]+=*"), "[REDACTED_TOKEN]")
            .replace(Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"), "[REDACTED_EMAIL]")
            .replace(Regex("(?:\\+\\d{1,3}[\\s\\-]?)?\\d{8,14}"), "[REDACTED_PHONE]")
            .take(180)
    }
}
