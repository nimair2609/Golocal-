package com.example.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.example.BuildConfig
import com.example.data.model.PlatformSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

interface ApkVerifier {
    fun verifyPackageAndSigningCompatibility(
        context: Context,
        apkFile: File,
        expectedVersionCode: Long
    ): Result<Unit>
}

object AndroidApkVerifier : ApkVerifier {
    override fun verifyPackageAndSigningCompatibility(
        context: Context,
        apkFile: File,
        expectedVersionCode: Long
    ): Result<Unit> = runCatching {
        require(apkFile.exists() && apkFile.length() > 0L) {
            "Downloaded APK file is empty or missing."
        }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val installedInfo = pm.getPackageInfo(context.packageName, flags)
        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: error("Downloaded file is not a valid Android APK package.")

        require(archiveInfo.packageName == context.packageName) {
            "Security check failed: APK package ID (${archiveInfo.packageName}) does not match ${context.packageName}."
        }
        val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
        val installedVersionCode = PackageInfoCompat.getLongVersionCode(installedInfo)
        require(archiveVersionCode >= expectedVersionCode && archiveVersionCode > installedVersionCode) {
            "Security check failed: APK version ($archiveVersionCode) is not newer than installed ($installedVersionCode)."
        }

        val installedCerts = extractSigningCertSha256s(installedInfo)
        val archiveCerts = extractSigningCertSha256s(archiveInfo)
        require(installedCerts.isNotEmpty() && archiveCerts.isNotEmpty()) {
            "Unable to verify APK signing certificates."
        }
        require(archiveCerts.any { it in installedCerts }) {
            "Security check failed: APK signing certificate does not match the installed GoLocal release key."
        }
    }

    fun extractSigningCertSha256s(packageInfo: PackageInfo): Set<String> {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
            if (signingInfo == null) null
            else if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
            else signingInfo.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }
        if (sigs.isNullOrEmpty()) return emptySet()
        return sigs.map { signature ->
            val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}

class AppUpdateManager(
    private val context: Context,
    private val apkVerifier: ApkVerifier = AndroidApkVerifier,
    private val allowLocalhostForTests: Boolean = false
) {
    fun getInstalledVersionInfo(): Pair<String, Long> {
        return try {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = pkg.versionName ?: BuildConfig.VERSION_NAME
            val code = PackageInfoCompat.getLongVersionCode(pkg)
            name to code
        } catch (_: Exception) {
            BuildConfig.VERSION_NAME to BuildConfig.VERSION_CODE.toLong()
        }
    }

    suspend fun checkForUpdates(platformSettings: PlatformSettings?): UpdateAvailabilityResult {
        val (_, installedCode) = getInstalledVersionInfo()

        // 1. Check remote HTTPS JSON manifest if configured in PlatformSettings or BuildConfig
        val remoteUrl = platformSettings?.updateManifestUrl?.takeIf { it.isNotBlank() }
            ?: BuildConfig.UPDATE_MANIFEST_URL.takeIf { it.isNotBlank() }

        val shouldFetchRemote = !remoteUrl.isNullOrBlank() &&
            (platformSettings == null || platformSettings.updatesEnabled)

        if (shouldFetchRemote && remoteUrl != null) {
            val remoteManifest = fetchRemoteManifest(remoteUrl)
            if (remoteManifest.isSuccess) {
                return UpdateManifestEvaluator.evaluate(
                    manifest = remoteManifest.getOrThrow(),
                    installedVersionCode = installedCode,
                    allowLocalhostUrl = allowLocalhostForTests
                )
            } else if (platformSettings == null || platformSettings.apkDownloadUrl.isBlank()) {
                return UpdateAvailabilityResult.Invalid(
                    remoteManifest.exceptionOrNull()?.message ?: "Failed to check remote update manifest."
                )
            }
        }

        // 2. Fallback or direct Firestore PlatformSettings manifest
        val firestoreManifest = platformSettings?.let { UpdateManifestEvaluator.fromPlatformSettings(it) }
            ?: return UpdateAvailabilityResult.UpToDate

        return UpdateManifestEvaluator.evaluate(
            manifest = firestoreManifest,
            installedVersionCode = installedCode,
            allowLocalhostUrl = allowLocalhostForTests
        )
    }

    suspend fun fetchRemoteManifest(manifestUrl: String): Result<AppUpdateManifest> = withContext(Dispatchers.IO) {
        runCatching {
            require(UpdateManifestEvaluator.isSecureUpdateUrl(manifestUrl, allowLocalhostForTests)) {
                "Update manifest URL must use HTTPS."
            }
            val conn = openSecureConnection(manifestUrl)
            try {
                val code = conn.responseCode
                require(code in 200..299) { "Update manifest server returned HTTP $code." }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                UpdateManifestEvaluator.parseManifestJson(body).getOrThrow()
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun downloadAndVerifyApk(
        manifest: AppUpdateManifest,
        onProgress: (fraction: Float, downloaded: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
        // Clean stale APKs before downloading
        updatesDir.listFiles()?.forEach { it.delete() }
        val tempFile = File(updatesDir, "golocal-v${manifest.versionCode}.apk.part")
        val finalFile = File(updatesDir, "golocal-v${manifest.versionCode}.apk")

        runCatching {
            require(UpdateManifestEvaluator.isSecureUpdateUrl(manifest.apkDownloadUrl, allowLocalhostForTests)) {
                "APK download URL must use HTTPS."
            }
            require(UpdateManifestEvaluator.isValidSha256Hex(manifest.sha256Checksum)) {
                "Invalid expected SHA-256 checksum."
            }

            val conn = openSecureConnection(manifest.apkDownloadUrl)
            try {
                val code = conn.responseCode
                require(code in 200..299) { "APK download failed with HTTP $code." }
                val totalBytes = conn.contentLengthLong
                var downloaded = 0L
                val buffer = ByteArray(16 * 1024)
                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        var read = input.read(buffer)
                        while (read != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val fraction = if (totalBytes > 0L) {
                                (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            } else {
                                -1f
                            }
                            onProgress(fraction, downloaded, totalBytes)
                            read = input.read(buffer)
                        }
                        output.flush()
                    }
                }
            } finally {
                conn.disconnect()
            }

            val actualSha256 = FileInputStream(tempFile).use { UpdateManifestEvaluator.computeSha256Hex(it) }
            if (!actualSha256.equals(manifest.sha256Checksum, ignoreCase = true)) {
                tempFile.delete()
                error("Security verification failed: SHA-256 checksum mismatch (expected ${manifest.sha256Checksum.take(12)}…, got ${actualSha256.take(12)}…).")
            }

            if (finalFile.exists()) finalFile.delete()
            check(tempFile.renameTo(finalFile)) { "Unable to stage verified APK file." }

            apkVerifier.verifyPackageAndSigningCompatibility(
                context = context,
                apkFile = finalFile,
                expectedVersionCode = manifest.versionCode
            ).getOrThrow()

            finalFile
        }.onFailure {
            tempFile.delete()
            finalFile.delete()
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun launchUnknownSourcesSettingsIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun launchPackageInstaller(apkFile: File): Result<Unit> = runCatching {
        require(apkFile.exists()) { "Verified APK file missing." }
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }

    private fun openSecureConnection(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 5) {
            require(UpdateManifestEvaluator.isSecureUpdateUrl(currentUrl, allowLocalhostForTests)) {
                "Blocked non-HTTPS update URL redirect."
            }
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json, application/vnd.android.package-archive, */*")
            }
            val status = conn.responseCode
            if (status in listOf(
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308
                )
            ) {
                val nextLocation = conn.getHeaderField("Location")
                conn.disconnect()
                require(!nextLocation.isNullOrBlank()) { "Redirect missing Location header." }
                currentUrl = URL(URL(currentUrl), nextLocation).toString()
                redirects++
            } else {
                return conn
            }
        }
        error("Too many redirects while fetching update resource.")
    }
}
