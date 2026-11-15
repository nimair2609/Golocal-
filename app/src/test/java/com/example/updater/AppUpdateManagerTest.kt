package com.example.updater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.PlatformSettings
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdateManagerTest {

    private val validSha = "a".repeat(64)

    @Test
    fun `manifest JSON parser parses valid JSON and rejects malformed or invalid SHA-256`() {
        val validJson = """
            {
              "versionName": "1.2.0",
              "versionCode": 3,
              "apkDownloadUrl": "https://github.com/nrnext/golocal/releases/download/v1.2.0/GoLocal-v1.2.0-3.apk",
              "sha256Checksum": "$validSha",
              "releaseNotes": "Added new GoLocal branding and faster trip sync.",
              "isMandatory": true,
              "minSupportedVersionCode": 2,
              "updatesEnabled": true
            }
        """.trimIndent()

        val parsed = UpdateManifestEvaluator.parseManifestJson(validJson).getOrThrow()
        assertEquals("1.2.0", parsed.versionName)
        assertEquals(3L, parsed.versionCode)
        assertTrue(parsed.isMandatory)
        assertEquals(2L, parsed.minSupportedVersionCode)

        val badShaJson = validJson.replace(validSha, "not-a-sha")
        assertTrue(UpdateManifestEvaluator.parseManifestJson(badShaJson).isFailure)
    }

    @Test
    fun `evaluate enforces optional, mandatory, minSupportedVersionCode, and HTTPS URL rules`() {
        val baseManifest = AppUpdateManifest(
            versionName = "1.1.0",
            versionCode = 2L,
            apkDownloadUrl = "https://github.com/nrnext/golocal/releases/download/v1.1.0/GoLocal.apk",
            sha256Checksum = validSha,
            releaseNotes = "Bug fixes",
            isMandatory = false,
            minSupportedVersionCode = 1L,
            updatesEnabled = true
        )

        val optional = UpdateManifestEvaluator.evaluate(baseManifest, installedVersionCode = 1L)
        assertTrue(optional is UpdateAvailabilityResult.Available && !optional.isMandatory)

        val upToDate = UpdateManifestEvaluator.evaluate(baseManifest, installedVersionCode = 2L)
        assertTrue(upToDate is UpdateAvailabilityResult.UpToDate)

        val mandatoryByMin = UpdateManifestEvaluator.evaluate(
            baseManifest.copy(versionCode = 4L, minSupportedVersionCode = 3L, isMandatory = false),
            installedVersionCode = 2L
        )
        assertTrue(mandatoryByMin is UpdateAvailabilityResult.Available && mandatoryByMin.isMandatory)

        val insecureUrl = UpdateManifestEvaluator.evaluate(
            baseManifest.copy(apkDownloadUrl = "http://example.com/app.apk"),
            installedVersionCode = 1L
        )
        assertTrue(insecureUrl is UpdateAvailabilityResult.Invalid)
    }

    @Test
    fun `downloadAndVerifyApk verifies SHA-256 and package signature and deletes tampered files`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val apkBytes = "MOCK_SIGNED_GOLOCAL_APK_PAYLOAD_BYTES".toByteArray()
        val expectedSha = UpdateManifestEvaluator.computeSha256Hex(ByteArrayInputStream(apkBytes))

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/GoLocal.apk") { exchange ->
            exchange.sendResponseHeaders(200, apkBytes.size.toLong())
            exchange.responseBody.use { it.write(apkBytes) }
        }
        server.start()
        val port = server.address.port

        try {
            val manifest = AppUpdateManifest(
                versionName = "1.2.0",
                versionCode = 3L,
                apkDownloadUrl = "http://127.0.0.1:$port/GoLocal.apk",
                sha256Checksum = expectedSha,
                releaseNotes = "Verified build",
                isMandatory = false,
                minSupportedVersionCode = 1L,
                updatesEnabled = true
            )

            // 1. Valid SHA-256 + passing package verifier succeeds
            val passingManager = AppUpdateManager(
                context = context,
                apkVerifier = object : ApkVerifier {
                    override fun verifyPackageAndSigningCompatibility(
                        context: Context,
                        apkFile: File,
                        expectedVersionCode: Long
                    ): Result<Unit> = Result.success(Unit)
                },
                allowLocalhostForTests = true
            )
            val okResult = passingManager.downloadAndVerifyApk(manifest) { _, _, _ -> }
            assertTrue(okResult.isSuccess)
            assertTrue(okResult.getOrThrow().exists())

            // 2. Tampered SHA-256 fails and cleans up file
            val badShaResult = passingManager.downloadAndVerifyApk(
                manifest.copy(sha256Checksum = "f".repeat(64))
            ) { _, _, _ -> }
            assertTrue(badShaResult.isFailure)
            assertFalse(File(context.filesDir, "updates/golocal-v3.apk").exists())

            // 3. Incompatible signing cert fails and cleans up file
            val failingCertManager = AppUpdateManager(
                context = context,
                apkVerifier = object : ApkVerifier {
                    override fun verifyPackageAndSigningCompatibility(
                        context: Context,
                        apkFile: File,
                        expectedVersionCode: Long
                    ): Result<Unit> = Result.failure(IllegalArgumentException("Signing certificate mismatch"))
                },
                allowLocalhostForTests = true
            )
            val certFailResult = failingCertManager.downloadAndVerifyApk(manifest) { _, _, _ -> }
            assertTrue(certFailResult.isFailure)
            assertFalse(File(context.filesDir, "updates/golocal-v3.apk").exists())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `PlatformSettings maps cleanly to AppUpdateManifest when enabled`() {
        val settings = PlatformSettings(
            updatesEnabled = true,
            latestVersionName = "2.0.0",
            latestVersionCode = 5L,
            minSupportedVersionCode = 2L,
            apkDownloadUrl = "https://github.com/nrnext/golocal/releases/download/v2.0.0/app.apk",
            sha256Checksum = validSha,
            releaseNotes = "Major release",
            isMandatoryUpdate = true
        )
        val manifest = UpdateManifestEvaluator.fromPlatformSettings(settings)!!
        assertEquals("2.0.0", manifest.versionName)
        assertEquals(5L, manifest.versionCode)
        assertTrue(manifest.isMandatory)
    }
}
