package com.example.updater

import com.example.data.model.PlatformSettings
import org.json.JSONObject
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

data class AppUpdateManifest(
    val versionName: String,
    val versionCode: Long,
    val apkDownloadUrl: String,
    val sha256Checksum: String,
    val releaseNotes: String,
    val isMandatory: Boolean,
    val minSupportedVersionCode: Long,
    val updatesEnabled: Boolean = true
)

sealed interface UpdateAvailabilityResult {
    data object UpToDate : UpdateAvailabilityResult
    data class Available(
        val manifest: AppUpdateManifest,
        val isMandatory: Boolean
    ) : UpdateAvailabilityResult
    data class Invalid(val reason: String) : UpdateAvailabilityResult
}

sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState
    data object Checking : AppUpdateUiState
    data object UpToDate : AppUpdateUiState
    data class Available(
        val manifest: AppUpdateManifest,
        val isMandatory: Boolean,
        val installedVersionName: String,
        val installedVersionCode: Long
    ) : AppUpdateUiState
    data class Downloading(
        val manifest: AppUpdateManifest,
        val isMandatory: Boolean,
        val progressFraction: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : AppUpdateUiState
    data class PermissionRequired(
        val manifest: AppUpdateManifest,
        val isMandatory: Boolean,
        val verifiedApkPath: String
    ) : AppUpdateUiState
    data class ReadyToInstall(
        val manifest: AppUpdateManifest,
        val isMandatory: Boolean,
        val verifiedApkPath: String
    ) : AppUpdateUiState
    data class Error(
        val message: String,
        val isMandatory: Boolean = false,
        val manifest: AppUpdateManifest? = null
    ) : AppUpdateUiState
}

object UpdateManifestEvaluator {

    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

    fun isSecureUpdateUrl(url: String, allowLocalhost: Boolean = false): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return false
            val host = uri.host?.lowercase() ?: return false
            when (scheme) {
                "https" -> host.isNotBlank()
                "http" -> allowLocalhost && (host == "127.0.0.1" || host == "localhost")
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isValidSha256Hex(checksum: String): Boolean {
        return SHA256_REGEX.matches(checksum.trim())
    }

    fun parseManifestJson(jsonString: String): Result<AppUpdateManifest> = runCatching {
        val obj = JSONObject(jsonString)
        val versionName = obj.optString("versionName", "").trim()
        val versionCode = obj.optLong("versionCode", 0L)
        val apkUrl = obj.optString("apkDownloadUrl", "").trim()
        val sha256 = obj.optString("sha256Checksum", obj.optString("sha256", "")).trim().lowercase()
        val releaseNotes = obj.optString("releaseNotes", "").trim()
        val isMandatory = obj.optBoolean("isMandatory", false)
        val minSupported = obj.optLong("minSupportedVersionCode", 1L).coerceAtLeast(1L)
        val enabled = if (obj.has("updatesEnabled")) obj.optBoolean("updatesEnabled", true) else true

        require(versionName.isNotBlank()) { "Manifest missing versionName" }
        require(versionCode > 0L) { "Manifest missing valid versionCode" }
        require(apkUrl.isNotBlank()) { "Manifest missing apkDownloadUrl" }
        require(isValidSha256Hex(sha256)) { "Manifest missing valid 64-char SHA-256 checksum" }

        AppUpdateManifest(
            versionName = versionName,
            versionCode = versionCode,
            apkDownloadUrl = apkUrl,
            sha256Checksum = sha256,
            releaseNotes = releaseNotes.ifBlank { "Performance, reliability, and security improvements." },
            isMandatory = isMandatory,
            minSupportedVersionCode = minSupported,
            updatesEnabled = enabled
        )
    }

    fun fromPlatformSettings(settings: PlatformSettings): AppUpdateManifest? {
        if (!settings.updatesEnabled) return null
        if (settings.apkDownloadUrl.isBlank() || settings.latestVersionCode <= 0L) return null
        return AppUpdateManifest(
            versionName = settings.latestVersionName.ifBlank { settings.latestVersionCode.toString() },
            versionCode = settings.latestVersionCode,
            apkDownloadUrl = settings.apkDownloadUrl.trim(),
            sha256Checksum = settings.sha256Checksum.trim().lowercase(),
            releaseNotes = settings.releaseNotes.ifBlank { "New GoLocal update available." },
            isMandatory = settings.isMandatoryUpdate,
            minSupportedVersionCode = settings.minSupportedVersionCode.coerceAtLeast(1L),
            updatesEnabled = settings.updatesEnabled
        )
    }

    fun evaluate(
        manifest: AppUpdateManifest,
        installedVersionCode: Long,
        allowLocalhostUrl: Boolean = false
    ): UpdateAvailabilityResult {
        if (!manifest.updatesEnabled) return UpdateAvailabilityResult.UpToDate
        val belowMinimum = installedVersionCode < manifest.minSupportedVersionCode
        val hasNewer = manifest.versionCode > installedVersionCode
        if (!hasNewer && !belowMinimum) {
            return UpdateAvailabilityResult.UpToDate
        }
        if (manifest.versionCode <= installedVersionCode && belowMinimum) {
            return UpdateAvailabilityResult.Invalid(
                "Current version ($installedVersionCode) is below minimum supported (${manifest.minSupportedVersionCode}), but no newer APK version is published."
            )
        }
        if (!isSecureUpdateUrl(manifest.apkDownloadUrl, allowLocalhost = allowLocalhostUrl)) {
            return UpdateAvailabilityResult.Invalid("Update APK URL must use a valid HTTPS endpoint.")
        }
        if (!isValidSha256Hex(manifest.sha256Checksum)) {
            return UpdateAvailabilityResult.Invalid("Update manifest SHA-256 checksum is invalid or missing.")
        }
        return UpdateAvailabilityResult.Available(
            manifest = manifest,
            isMandatory = manifest.isMandatory || belowMinimum
        )
    }

    fun computeSha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        var read = input.read(buffer)
        while (read != -1) {
            digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
