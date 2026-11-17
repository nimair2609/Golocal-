package com.example.updater

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.PlatformSettings
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.JetBrainsMono
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun rememberAppUpdateController(
    platformSettings: PlatformSettings?
): AppUpdateController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(context.applicationContext) {
        AppUpdateManager(context.applicationContext)
    }
    val controller = remember(manager) {
        AppUpdateController(manager, scope)
    }

    LaunchedEffect(
        platformSettings?.updatesEnabled,
        platformSettings?.latestVersionCode,
        platformSettings?.minSupportedVersionCode,
        platformSettings?.apkDownloadUrl,
        platformSettings?.sha256Checksum,
        platformSettings?.isMandatoryUpdate,
        platformSettings?.updateManifestUrl
    ) {
        controller.evaluateFromSettings(platformSettings, showUpToDateFeedback = false)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                controller.onAppResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return controller
}

class AppUpdateController(
    private val manager: AppUpdateManager,
    private val scope: kotlinx.coroutines.CoroutineScope
) {
    var uiState by mutableStateOf<AppUpdateUiState>(AppUpdateUiState.Idle)
        private set

    var dismissedOptionalVersionCode by mutableStateOf<Long?>(null)
        private set

    val installedVersion: Pair<String, Long>
        get() = manager.getInstalledVersionInfo()

    fun evaluateFromSettings(
        platformSettings: PlatformSettings?,
        showUpToDateFeedback: Boolean
    ) {
        if (uiState is AppUpdateUiState.Downloading || uiState is AppUpdateUiState.ReadyToInstall) {
            return
        }
        scope.launch {
            if (showUpToDateFeedback) {
                uiState = AppUpdateUiState.Checking
            }
            val (installedName, installedCode) = manager.getInstalledVersionInfo()
            when (val res = manager.checkForUpdates(platformSettings)) {
                is UpdateAvailabilityResult.UpToDate -> {
                    uiState = if (showUpToDateFeedback) AppUpdateUiState.UpToDate else AppUpdateUiState.Idle
                }
                is UpdateAvailabilityResult.Available -> {
                    if (!res.isMandatory && !showUpToDateFeedback && dismissedOptionalVersionCode == res.manifest.versionCode) {
                        return@launch
                    }
                    uiState = AppUpdateUiState.Available(
                        manifest = res.manifest,
                        isMandatory = res.isMandatory,
                        installedVersionName = installedName,
                        installedVersionCode = installedCode
                    )
                }
                is UpdateAvailabilityResult.Invalid -> {
                    if (showUpToDateFeedback) {
                        uiState = AppUpdateUiState.Error(res.reason)
                    }
                }
            }
        }
    }

    val supportsInAppApkInstall: Boolean
        get() = manager.hasRequestInstallPackagesPermission()

    fun startDownload(manifest: AppUpdateManifest, isMandatory: Boolean) {
        if (!manager.hasRequestInstallPackagesPermission()) {
            // Play-Store build: omit REQUEST_INSTALL_PACKAGES and route update via HTTPS browser / store link
            openUpdateInBrowser(manifest.apkDownloadUrl, isMandatory, manifest)
            return
        }
        scope.launch {
            uiState = AppUpdateUiState.Downloading(
                manifest = manifest,
                isMandatory = isMandatory,
                progressFraction = 0f,
                bytesDownloaded = 0L,
                totalBytes = -1L
            )
            manager.downloadAndVerifyApk(manifest) { fraction, downloaded, total ->
                uiState = AppUpdateUiState.Downloading(
                    manifest = manifest,
                    isMandatory = isMandatory,
                    progressFraction = fraction,
                    bytesDownloaded = downloaded,
                    totalBytes = total
                )
            }.onSuccess { apkFile ->
                if (manager.canRequestPackageInstalls()) {
                    uiState = AppUpdateUiState.ReadyToInstall(manifest, isMandatory, apkFile.absolutePath)
                    manager.launchPackageInstaller(apkFile).onFailure { err ->
                        uiState = AppUpdateUiState.Error(
                            message = err.localizedMessage ?: "Unable to open Android package installer.",
                            isMandatory = isMandatory,
                            manifest = manifest
                        )
                    }
                } else {
                    uiState = AppUpdateUiState.PermissionRequired(manifest, isMandatory, apkFile.absolutePath)
                }
            }.onFailure { err ->
                uiState = AppUpdateUiState.Error(
                    message = err.localizedMessage ?: "APK download or verification failed.",
                    isMandatory = isMandatory,
                    manifest = manifest
                )
            }
        }
    }

    fun openUpdateInBrowser(url: String, isMandatory: Boolean, manifest: AppUpdateManifest? = null) {
        manager.openDownloadUrlInBrowser(url)
            .onSuccess {
                if (!isMandatory) uiState = AppUpdateUiState.Idle
            }
            .onFailure { err ->
                uiState = AppUpdateUiState.Error(
                    message = err.localizedMessage ?: "Unable to open HTTPS update page.",
                    isMandatory = isMandatory,
                    manifest = manifest
                )
            }
    }

    fun openInstallPermissionSettings() {
        manager.launchUnknownSourcesSettingsIntent()
    }

    fun triggerVerifiedInstall(manifest: AppUpdateManifest, isMandatory: Boolean, apkPath: String) {
        if (!manager.hasRequestInstallPackagesPermission()) {
            openUpdateInBrowser(manifest.apkDownloadUrl, isMandatory, manifest)
            return
        }
        if (!manager.canRequestPackageInstalls()) {
            uiState = AppUpdateUiState.PermissionRequired(manifest, isMandatory, apkPath)
            manager.launchUnknownSourcesSettingsIntent()
            return
        }
        manager.launchPackageInstaller(File(apkPath)).onFailure { err ->
            uiState = AppUpdateUiState.Error(
                message = err.localizedMessage ?: "Unable to launch package installer.",
                isMandatory = isMandatory,
                manifest = manifest
            )
        }
    }

    fun onAppResumed() {
        val current = uiState
        if (current is AppUpdateUiState.PermissionRequired && manager.canRequestPackageInstalls()) {
            uiState = AppUpdateUiState.ReadyToInstall(
                manifest = current.manifest,
                isMandatory = current.isMandatory,
                verifiedApkPath = current.verifiedApkPath
            )
        }
    }

    fun dismissOptional(versionCode: Long?) {
        if (versionCode != null) dismissedOptionalVersionCode = versionCode
        uiState = AppUpdateUiState.Idle
    }

    fun clearFeedback() {
        uiState = AppUpdateUiState.Idle
    }
}

@Composable
fun AppUpdateDialogHost(
    controller: AppUpdateController
) {
    when (val state = controller.uiState) {
        is AppUpdateUiState.Available -> {
            val isMandatory = state.isMandatory
            AlertDialog(
                onDismissRequest = {
                    if (!isMandatory) controller.dismissOptional(state.manifest.versionCode)
                },
                properties = DialogProperties(
                    dismissOnBackPress = !isMandatory,
                    dismissOnClickOutside = !isMandatory
                ),
                icon = {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = if (isMandatory) GolocalError else GolocalPrimaryBlue
                    )
                },
                title = {
                    Text(
                        text = if (isMandatory) "Mandatory GoLocal Update Required" else "GoLocal Update Available",
                        modifier = Modifier.testTag("update_dialog_title")
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "New Version: v${state.manifest.versionName} (Build ${state.manifest.versionCode})\nInstalled: v${state.installedVersionName} (Build ${state.installedVersionCode})",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = JetBrainsMono
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Release Notes", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                Text(state.manifest.releaseNotes, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = GolocalSuccess, modifier = Modifier.size(16.dp))
                            Text(
                                text = "SHA-256 & release signing verified before install.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { controller.startDownload(state.manifest, isMandatory) },
                        modifier = Modifier.testTag("download_update_button")
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (controller.supportsInAppApkInstall) "Download Update" else "Open Update Page")
                    }
                },
                dismissButton = {
                    if (!isMandatory) {
                        TextButton(
                            onClick = { controller.dismissOptional(state.manifest.versionCode) },
                            modifier = Modifier.testTag("dismiss_update_button")
                        ) { Text("Later") }
                    }
                }
            )
        }

        is AppUpdateUiState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text("Downloading GoLocal v${state.manifest.versionName}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.progressFraction >= 0f) {
                            LinearProgressIndicator(
                                progress = { state.progressFraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "${(state.progressFraction * 100).toInt()}% • ${state.bytesDownloaded / 1024} KB",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = JetBrainsMono
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = "${state.bytesDownloaded / 1024} KB downloaded…",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = JetBrainsMono
                            )
                        }
                        Text(
                            "Verifying SHA-256 checksum & package signature automatically on completion.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {}
            )
        }

        is AppUpdateUiState.PermissionRequired -> {
            AlertDialog(
                onDismissRequest = { if (!state.isMandatory) controller.clearFeedback() },
                properties = DialogProperties(
                    dismissOnBackPress = !state.isMandatory,
                    dismissOnClickOutside = !state.isMandatory
                ),
                icon = { Icon(Icons.Filled.Security, contentDescription = null, tint = GolocalPrimaryBlue) },
                title = {
                    Text(
                        if (controller.supportsInAppApkInstall) "Allow App Installation"
                        else "Update via Browser / Play Store"
                    )
                },
                text = {
                    Text(
                        if (controller.supportsInAppApkInstall) {
                            "The GoLocal APK passed SHA-256 and signing verification. Enable \"Allow from this source\" in Android Settings to review and install the update."
                        } else {
                            "This Play Store build omits self-install permissions. Tap Open Update in Browser to download or update securely over HTTPS."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                confirmButton = {
                    if (controller.supportsInAppApkInstall) {
                        Button(onClick = { controller.openInstallPermissionSettings() }) {
                            Text("Open Settings")
                        }
                    } else {
                        Button(onClick = {
                            controller.openUpdateInBrowser(state.manifest.apkDownloadUrl, state.isMandatory, state.manifest)
                        }) {
                            Text("Open Update in Browser")
                        }
                    }
                },
                dismissButton = {
                    if (!state.isMandatory) {
                        TextButton(onClick = { controller.clearFeedback() }) { Text("Cancel") }
                    }
                }
            )
        }

        is AppUpdateUiState.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = { if (!state.isMandatory) controller.clearFeedback() },
                properties = DialogProperties(
                    dismissOnBackPress = !state.isMandatory,
                    dismissOnClickOutside = !state.isMandatory
                ),
                icon = { Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = GolocalSuccess) },
                title = { Text("Update Verified & Ready") },
                text = {
                    Text(
                        "GoLocal v${state.manifest.versionName} passed SHA-256 and release certificate verification. Tap Install Update to confirm in the Android installer.",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            controller.triggerVerifiedInstall(
                                manifest = state.manifest,
                                isMandatory = state.isMandatory,
                                apkPath = state.verifiedApkPath
                            )
                        }
                    ) { Text("Install Update") }
                },
                dismissButton = {
                    if (!state.isMandatory) {
                        TextButton(onClick = { controller.clearFeedback() }) { Text("Later") }
                    }
                }
            )
        }

        is AppUpdateUiState.UpToDate -> {
            AlertDialog(
                onDismissRequest = { controller.clearFeedback() },
                title = { Text("GoLocal Is Up To Date") },
                text = {
                    val (name, code) = controller.installedVersion
                    Text("You are running the latest version: v$name (Build $code).")
                },
                confirmButton = {
                    Button(onClick = { controller.clearFeedback() }) { Text("OK") }
                }
            )
        }

        is AppUpdateUiState.Error -> {
            AlertDialog(
                onDismissRequest = { if (!state.isMandatory) controller.clearFeedback() },
                properties = DialogProperties(
                    dismissOnBackPress = !state.isMandatory,
                    dismissOnClickOutside = !state.isMandatory
                ),
                title = { Text("Update Verification Error") },
                text = { Text(state.message, style = MaterialTheme.typography.bodySmall) },
                confirmButton = {
                    if (state.manifest != null) {
                        Button(onClick = { controller.startDownload(state.manifest, state.isMandatory) }) {
                            Text("Retry")
                        }
                    } else {
                        Button(onClick = { controller.clearFeedback() }) { Text("OK") }
                    }
                },
                dismissButton = {
                    if (!state.isMandatory && state.manifest != null) {
                        TextButton(onClick = { controller.clearFeedback() }) { Text("Close") }
                    }
                }
            )
        }

        else -> Unit
    }
}

@Composable
fun CheckForAppUpdatesCard(
    platformSettings: PlatformSettings?,
    controller: AppUpdateController,
    modifier: Modifier = Modifier
) {
    val (versionName, versionCode) = controller.installedVersion
    val isChecking = controller.uiState is AppUpdateUiState.Checking
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("App Updates", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Installed: v$versionName (Build $versionCode)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = { controller.evaluateFromSettings(platformSettings, showUpToDateFeedback = true) },
                enabled = !isChecking,
                modifier = Modifier.testTag("check_updates_button")
            ) {
                if (isChecking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Check Now")
                }
            }
        }
    }
}
