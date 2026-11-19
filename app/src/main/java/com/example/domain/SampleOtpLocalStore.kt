package com.example.domain

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class LocalSampleOtpEntry(
    val rideId: String,
    val creatorUid: String,
    val otpPlaintext: String,
    val expiresAtMs: Long,
    val createdAtMs: Long
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtMs > 0L && nowMs > expiresAtMs
}

/**
 * Stores generated 6-digit Sample Ride OTPs strictly on the creator's device
 * (Customer or Admin who created the sample ride) so NO plaintext OTP is ever written
 * to Firestore (`sample_rides` or `sample_ride_otps`).
 */
object SampleOtpLocalStore {
    private const val PREFS_NAME = "golocal_sample_ride_local_otps"
    private const val KEY_ENTRIES = "entries_json"

    private val lock = Any()
    private val _entriesFlow = MutableStateFlow<Map<String, LocalSampleOtpEntry>>(emptyMap())
    val entriesFlow: StateFlow<Map<String, LocalSampleOtpEntry>> = _entriesFlow.asStateFlow()

    @Volatile
    private var loadedFromPrefs = false

    fun ensureLoaded(context: Context) {
        if (loadedFromPrefs) return
        synchronized(lock) {
            if (loadedFromPrefs) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_ENTRIES, null).orEmpty()
            val parsed = parseEntries(raw)
            _entriesFlow.value = parsed
            loadedFromPrefs = true
        }
    }

    fun saveOtp(
        context: Context,
        creatorUid: String,
        rideId: String,
        otpPlaintext: String,
        expiresAtMs: Long
    ) {
        if (creatorUid.isBlank() || rideId.isBlank() || otpPlaintext.length != 6) return
        ensureLoaded(context)
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val next = _entriesFlow.value
                .filterValues { !it.isExpired(now) || it.rideId == rideId }
                .toMutableMap()
            next[rideId] = LocalSampleOtpEntry(
                rideId = rideId,
                creatorUid = creatorUid,
                otpPlaintext = otpPlaintext,
                expiresAtMs = expiresAtMs,
                createdAtMs = now
            )
            persistLocked(context, next)
        }
    }

    fun getOtpForRide(
        context: Context,
        creatorUid: String,
        rideId: String
    ): LocalSampleOtpEntry? {
        if (creatorUid.isBlank() || rideId.isBlank()) return null
        ensureLoaded(context)
        val entry = _entriesFlow.value[rideId] ?: return null
        return if (entry.creatorUid == creatorUid) entry else null
    }

    fun removeOtp(context: Context, rideId: String) {
        if (rideId.isBlank()) return
        ensureLoaded(context)
        synchronized(lock) {
            if (!_entriesFlow.value.containsKey(rideId)) return
            val next = _entriesFlow.value.toMutableMap()
            next.remove(rideId)
            persistLocked(context, next)
        }
    }

    fun clearAllInMemoryForTests() {
        synchronized(lock) {
            _entriesFlow.value = emptyMap()
            loadedFromPrefs = false
        }
    }

    private fun persistLocked(context: Context, entries: Map<String, LocalSampleOtpEntry>) {
        _entriesFlow.value = entries
        val json = JSONObject()
        entries.values.forEach { entry ->
            json.put(
                entry.rideId,
                JSONObject().apply {
                    put("rideId", entry.rideId)
                    put("creatorUid", entry.creatorUid)
                    put("otpPlaintext", entry.otpPlaintext)
                    put("expiresAtMs", entry.expiresAtMs)
                    put("createdAtMs", entry.createdAtMs)
                }
            )
        }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, json.toString())
            .apply()
    }

    private fun parseEntries(raw: String): Map<String, LocalSampleOtpEntry> {
        if (raw.isBlank()) return emptyMap()
        val now = System.currentTimeMillis()
        val result = linkedMapOf<String, LocalSampleOtpEntry>()
        runCatching {
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val obj = root.optJSONObject(k) ?: continue
                val entry = LocalSampleOtpEntry(
                    rideId = obj.optString("rideId", k),
                    creatorUid = obj.optString("creatorUid", ""),
                    otpPlaintext = obj.optString("otpPlaintext", ""),
                    expiresAtMs = obj.optLong("expiresAtMs", 0L),
                    createdAtMs = obj.optLong("createdAtMs", 0L)
                )
                if (entry.rideId.isNotBlank() && entry.otpPlaintext.length == 6 && !entry.isExpired(now)) {
                    result[entry.rideId] = entry
                }
            }
        }
        return result
    }
}
