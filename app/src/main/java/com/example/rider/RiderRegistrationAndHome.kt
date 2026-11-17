package com.example.rider

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Booking
import com.example.data.model.BookingCallContact
import com.example.data.model.CommissionRecord
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.DriverProfile
import com.example.domain.AbuseDetectionEngine
import com.example.domain.ExpiryStatus
import com.example.domain.KycDocType
import com.example.domain.RequiredDocumentsCatalog
import com.example.map.GolocalMapContainer
import com.example.shared.BookingChatModalSheet
import com.example.shared.BookingCommunicationBar
import com.example.shared.CallPeerConfirmDialog
import com.example.shared.NotificationPermissionBannerCard
import com.example.ui.auth.signOut
import com.example.ui.components.GoLocalLogoMark
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalMetricCard
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.*
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.DriverViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private fun todayEpochDay(): Long =
    TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis())

private fun formatEpochDay(epochDay: Long): String {
    if (epochDay <= 0L) return ""
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return fmt.format(Date(TimeUnit.DAYS.toMillis(epochDay)))
}

private fun parseIsoDateToEpochDay(raw: String): Long {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return 0L
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return runCatching {
        val ms = fmt.parse(trimmed)?.time ?: return 0L
        TimeUnit.MILLISECONDS.toDays(ms)
    }.getOrDefault(0L)
}

@Composable
fun RiderRegistrationAndVerificationGate(
    existingProfile: DriverProfile?,
    displayName: String,
    driverViewModel: DriverViewModel,
    verifiedPhone: String = ""
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history by driverViewModel.verificationHistory.collectAsStateWithLifecycle()

    var showForm by remember(existingProfile?.verificationStatus) {
        mutableStateOf(existingProfile == null || existingProfile.verificationStatus == "rejected")
    }

    var legalName by remember(existingProfile?.legalName) { mutableStateOf(existingProfile?.legalName?.ifBlank { displayName } ?: displayName) }
    var phone by remember(existingProfile?.phone, verifiedPhone) {
        mutableStateOf(existingProfile?.phone?.ifBlank { verifiedPhone } ?: verifiedPhone)
    }
    var address by remember(existingProfile?.address) { mutableStateOf(existingProfile?.address ?: "") }
    var district by remember(existingProfile?.district) { mutableStateOf(existingProfile?.district?.ifBlank { "Hooghly" } ?: "Hooghly") }
    var block by remember(existingProfile?.block) { mutableStateOf(existingProfile?.block ?: "") }
    var vehicleCategory by remember(existingProfile?.vehicleCategory) { mutableStateOf(existingProfile?.vehicleCategory ?: "toto") }
    var vehicleModel by remember(existingProfile?.vehicleModel) { mutableStateOf(existingProfile?.vehicleModel ?: "") }
    var vehicleReg by remember(existingProfile?.vehicleRegNumber) { mutableStateOf(existingProfile?.vehicleRegNumber ?: "") }
    var licence by remember(existingProfile?.drivingLicenceNumber) { mutableStateOf(existingProfile?.drivingLicenceNumber ?: "") }
    var rcNumber by remember(existingProfile?.rcNumber) { mutableStateOf(existingProfile?.rcNumber ?: "") }
    var insurance by remember(existingProfile?.insuranceNumber) { mutableStateOf(existingProfile?.insuranceNumber ?: "") }
    var permit by remember(existingProfile?.permitInfo) { mutableStateOf(existingProfile?.permitInfo ?: "") }
    var fitness by remember(existingProfile?.fitnessCertificateNumber) { mutableStateOf(existingProfile?.fitnessCertificateNumber ?: "") }
    var puc by remember(existingProfile?.pucNumber) { mutableStateOf(existingProfile?.pucNumber ?: "") }
    var upi by remember(existingProfile?.settlementUpiOrBank) { mutableStateOf(existingProfile?.settlementUpiOrBank ?: "") }

    var dlExpiryInput by remember(existingProfile?.dlExpiryEpochDay) { mutableStateOf(formatEpochDay(existingProfile?.dlExpiryEpochDay ?: 0L)) }
    var rcExpiryInput by remember(existingProfile?.rcExpiryEpochDay) { mutableStateOf(formatEpochDay(existingProfile?.rcExpiryEpochDay ?: 0L)) }
    var insuranceExpiryInput by remember(existingProfile?.insuranceExpiryEpochDay) { mutableStateOf(formatEpochDay(existingProfile?.insuranceExpiryEpochDay ?: 0L)) }
    var permitExpiryInput by remember(existingProfile?.permitExpiryEpochDay) { mutableStateOf(formatEpochDay(existingProfile?.permitExpiryEpochDay ?: 0L)) }
    var fitnessExpiryInput by remember(existingProfile?.fitnessExpiryEpochDay) { mutableStateOf(formatEpochDay(existingProfile?.fitnessExpiryEpochDay ?: 0L)) }
    var pucExpiryInput by remember(existingProfile?.pucExpiryEpochDay) { mutableStateOf(formatEpochDay(existingProfile?.pucExpiryEpochDay ?: 0L)) }

    var docUri by remember(existingProfile?.documentPhotoUri) { mutableStateOf(existingProfile?.documentPhotoUri ?: "") }
    var dlPhotoUri by remember(existingProfile?.dlPhotoUri) { mutableStateOf(existingProfile?.dlPhotoUri ?: "") }
    var rcPhotoUri by remember(existingProfile?.rcPhotoUri) { mutableStateOf(existingProfile?.rcPhotoUri ?: "") }
    var insurancePhotoUri by remember(existingProfile?.insurancePhotoUri) { mutableStateOf(existingProfile?.insurancePhotoUri ?: "") }
    var permitPhotoUri by remember(existingProfile?.permitPhotoUri) { mutableStateOf(existingProfile?.permitPhotoUri ?: "") }
    var fitnessPhotoUri by remember(existingProfile?.fitnessPhotoUri) { mutableStateOf(existingProfile?.fitnessPhotoUri ?: "") }
    var pucPhotoUri by remember(existingProfile?.pucPhotoUri) { mutableStateOf(existingProfile?.pucPhotoUri ?: "") }

    var pendingPhotoTarget by remember { mutableStateOf("general") }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val s = uri.toString()
            when (pendingPhotoTarget) {
                "dl" -> dlPhotoUri = s
                "rc" -> rcPhotoUri = s
                "insurance" -> insurancePhotoUri = s
                "permit" -> permitPhotoUri = s
                "fitness" -> fitnessPhotoUri = s
                "puc" -> pucPhotoUri = s
                else -> docUri = s
            }
        }
    }

    val requiredDocs = remember(vehicleCategory) {
        RequiredDocumentsCatalog.requiredFor(vehicleCategory)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                GoLocalLogoMark(modifier = Modifier.size(36.dp))
                Column {
                    Text("Rider Partner Verification", style = MaterialTheme.typography.headlineSmall)
                    Text("GoLocal Rider", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(onClick = { signOut(scope = scope, context = context) }) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Sign Out")
            }
        }

        if (existingProfile != null) {
            val status = existingProfile.verificationStatus
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, if (status == "rejected" || status == "suspended") GolocalError else GolocalWarning, RoundedCornerShape(20.dp))
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GolocalStatusBadge(
                        label = "KYC STATUS: ${status.uppercase()}",
                        tone = if (status == "pending") "warning" else "error"
                    )
                    Text(
                        text = when (status) {
                            "pending" -> "Your Driving Licence, Vehicle RC, Insurance, and Permit are awaiting GoLocal Admin verification. You cannot go online until approved."
                            "rejected" -> "Verification Rejected: ${existingProfile.rejectionReason.ifBlank { "Please verify document details and resubmit." }}"
                            else -> "Account Suspended: ${existingProfile.rejectionReason.ifBlank { "Contact GoLocal Partner Support." }}"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("Vehicle: ${existingProfile.vehicleCategory.uppercase()} • ${existingProfile.vehicleRegNumber}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (status == "rejected") {
                        Button(onClick = { showForm = !showForm }) {
                            Text(if (showForm) "Hide Form" else "Update & Resubmit KYC")
                        }
                    }
                }
            }
        }

        if (history.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Verification Audit Timeline", style = MaterialTheme.typography.titleSmall)
                    history.take(4).forEach { entry ->
                        Text(
                            text = "${entry.decision.uppercase()} • ${entry.reason.ifBlank { "Reviewed by Operations" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (showForm) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Register Vehicle & KYC Documents", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = legalName, onValueChange = { legalName = it }, label = { Text("Full Legal Name *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Mobile Number *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Home / Stand Address") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = district, onValueChange = { district = it }, label = { Text("District *") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = block, onValueChange = { block = it }, label = { Text("Block / Town") }, modifier = Modifier.weight(1f), singleLine = true)
                    }

                    Text("Vehicle Category", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("toto", "auto", "bike", "cab").forEach { cat ->
                            FilterChip(
                                selected = vehicleCategory == cat,
                                onClick = { vehicleCategory = cat },
                                label = { Text(cat.uppercase()) }
                            )
                        }
                    }

                    OutlinedTextField(value = vehicleModel, onValueChange = { vehicleModel = it }, label = { Text("Vehicle Model (e.g. Bajaj RE / Mayuri Toto)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = vehicleReg, onValueChange = { vehicleReg = it }, label = { Text("Vehicle Registration Number *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                    Text(
                        text = "Required for ${vehicleCategory.uppercase()}: ${requiredDocs.joinToString { it.displayLabel }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    KycDocumentInputRow(
                        label = "Driving Licence No. *",
                        docNum = licence,
                        onDocNumChange = { licence = it },
                        expiryIso = dlExpiryInput,
                        onExpiryChange = { dlExpiryInput = it },
                        hasPhoto = dlPhotoUri.isNotBlank() || docUri.isNotBlank(),
                        onPickPhoto = {
                            pendingPhotoTarget = "dl"
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                    KycDocumentInputRow(
                        label = "Vehicle RC Number",
                        docNum = rcNumber,
                        onDocNumChange = { rcNumber = it },
                        expiryIso = rcExpiryInput,
                        onExpiryChange = { rcExpiryInput = it },
                        hasPhoto = rcPhotoUri.isNotBlank(),
                        onPickPhoto = {
                            pendingPhotoTarget = "rc"
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                    KycDocumentInputRow(
                        label = "Insurance Policy No.",
                        docNum = insurance,
                        onDocNumChange = { insurance = it },
                        expiryIso = insuranceExpiryInput,
                        onExpiryChange = { insuranceExpiryInput = it },
                        hasPhoto = insurancePhotoUri.isNotBlank(),
                        onPickPhoto = {
                            pendingPhotoTarget = "insurance"
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                    if (KycDocType.FITNESS in requiredDocs) {
                        KycDocumentInputRow(
                            label = "Fitness Certificate No.",
                            docNum = fitness,
                            onDocNumChange = { fitness = it },
                            expiryIso = fitnessExpiryInput,
                            onExpiryChange = { fitnessExpiryInput = it },
                            hasPhoto = fitnessPhotoUri.isNotBlank(),
                            onPickPhoto = {
                                pendingPhotoTarget = "fitness"
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        )
                    }
                    if (KycDocType.PERMIT in requiredDocs) {
                        KycDocumentInputRow(
                            label = "Commercial / Route Permit",
                            docNum = permit,
                            onDocNumChange = { permit = it },
                            expiryIso = permitExpiryInput,
                            onExpiryChange = { permitExpiryInput = it },
                            hasPhoto = permitPhotoUri.isNotBlank(),
                            onPickPhoto = {
                                pendingPhotoTarget = "permit"
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        )
                    }
                    if (KycDocType.PUC in requiredDocs) {
                        KycDocumentInputRow(
                            label = "PUC Certificate No.",
                            docNum = puc,
                            onDocNumChange = { puc = it },
                            expiryIso = pucExpiryInput,
                            onExpiryChange = { pucExpiryInput = it },
                            hasPhoto = pucPhotoUri.isNotBlank(),
                            onPickPhoto = {
                                pendingPhotoTarget = "puc"
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        )
                    }

                    OutlinedTextField(value = upi, onValueChange = { upi = it }, label = { Text("Settlement UPI ID or Bank Ref") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                    Button(
                        onClick = {
                            driverViewModel.registerDriver(
                                legalName = legalName,
                                phone = phone,
                                address = address,
                                district = district,
                                block = block,
                                vehicleCategory = vehicleCategory,
                                vehicleModel = vehicleModel,
                                vehicleRegNumber = vehicleReg,
                                drivingLicenceNumber = licence,
                                rcNumber = rcNumber,
                                insuranceNumber = insurance,
                                permitInfo = permit,
                                settlementUpi = upi,
                                documentPhotoUri = docUri.ifBlank { dlPhotoUri.ifBlank { rcPhotoUri } },
                                pucNumber = puc,
                                fitnessCertificateNumber = fitness,
                                dlExpiryEpochDay = parseIsoDateToEpochDay(dlExpiryInput),
                                rcExpiryEpochDay = parseIsoDateToEpochDay(rcExpiryInput),
                                insuranceExpiryEpochDay = parseIsoDateToEpochDay(insuranceExpiryInput),
                                permitExpiryEpochDay = parseIsoDateToEpochDay(permitExpiryInput),
                                fitnessExpiryEpochDay = parseIsoDateToEpochDay(fitnessExpiryInput),
                                pucExpiryEpochDay = parseIsoDateToEpochDay(pucExpiryInput),
                                dlPhotoUri = dlPhotoUri,
                                rcPhotoUri = rcPhotoUri,
                                insurancePhotoUri = insurancePhotoUri,
                                permitPhotoUri = permitPhotoUri,
                                fitnessPhotoUri = fitnessPhotoUri,
                                pucPhotoUri = pucPhotoUri
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("rider_submit_kyc_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Submit Rider Verification")
                    }
                }
            }
        }
    }
}

@Composable
private fun KycDocumentInputRow(
    label: String,
    docNum: String,
    onDocNumChange: (String) -> Unit,
    expiryIso: String,
    onExpiryChange: (String) -> Unit,
    hasPhoto: Boolean,
    onPickPhoto: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = docNum,
                onValueChange = onDocNumChange,
                label = { Text(label) },
                modifier = Modifier.weight(1.2f),
                singleLine = true
            )
            OutlinedTextField(
                value = expiryIso,
                onValueChange = { if (it.length <= 10) onExpiryChange(it) },
                label = { Text("Expiry (YYYY-MM-DD)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        OutlinedButton(onClick = onPickPhoto, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (hasPhoto) "Document Photo Attached ✓" else "Attach Photo ($label)")
        }
    }
}

@Composable
fun RiderHomeDashboardTab(
    profile: DriverProfile,
    assignedBookings: List<Booking>,
    openRequestsCount: Int,
    commissions: List<CommissionRecord>,
    sessionViewModel: AppSessionViewModel,
    driverViewModel: DriverViewModel,
    lowNetworkMode: Boolean,
    onOpenRequests: () -> Unit
) {
    val context = LocalContext.current
    val gps by sessionViewModel.gpsLocation.collectAsStateWithLifecycle()
    val activeChatMessages by driverViewModel.activeChatMessages.collectAsStateWithLifecycle()
    val unreadChatCount by driverViewModel.unreadChatCount.collectAsStateWithLifecycle()
    val activeCallContact by driverViewModel.activeCallContact.collectAsStateWithLifecycle()
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.any { it }) {
            sessionViewModel.detectDeviceLocation(context)
        }
    }

    var showCallDialog by remember { mutableStateOf(false) }
    var showChatSheet by remember { mutableStateOf(false) }
    var showDriverCancelDialog by remember { mutableStateOf(false) }

    val startOfDaySec = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000L
    }
    val todaysCompletedRides = assignedBookings.count {
        it.status == "completed" && (it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L) >= startOfDaySec
    }
    val todaysEarningsInr = commissions.filter {
        (it.createdAt?.seconds ?: 0L) >= startOfDaySec
    }.sumOf { it.netDriverEarningsInr }
    val activeTrip = assignedBookings.firstOrNull {
        it.status in setOf("assigned", "arriving", "arrived", "in_progress")
    }

    val expiryAlerts = remember(profile) {
        RequiredDocumentsCatalog.assessDriverCompliance(profile, todayEpochDay())
            .filter { it.status == ExpiryStatus.EXPIRED || it.status == ExpiryStatus.EXPIRING_SOON }
    }

    val currentLat = gps.lat ?: 22.9041
    val currentLng = gps.lng ?: 88.3976

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header + Online/Offline Switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GoLocalLogoMark(modifier = Modifier.size(38.dp))
                Column {
                    Text("Rider Mode", style = MaterialTheme.typography.headlineSmall)
                    Text("${profile.legalName} • ${profile.vehicleCategory.uppercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(
                checked = profile.isOnline,
                onCheckedChange = { online ->
                    driverViewModel.toggleOnline(online, currentLat, currentLng)
                },
                modifier = Modifier.testTag("rider_online_switch")
            )
        }

        // Online Status Banner
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (profile.isOnline) GolocalSuccessContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (profile.isOnline) GolocalSuccess else MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (profile.isOnline) GolocalSuccess else MaterialTheme.colorScheme.outline),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (profile.isOnline) Icons.Filled.Check else Icons.Filled.Pause,
                            contentDescription = null,
                            tint = GolocalPureWhite
                        )
                    }
                    Column {
                        Text(
                            text = if (profile.isOnline) "You're Online!" else "You're Offline",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (profile.isOnline) "Ready to receive local ride requests" else "Toggle switch to start receiving rides",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(
                    onClick = {
                        locationLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                ) {
                    Text(if (gps.isDetecting) "GPS..." else "Sync GPS")
                }
            }
        }

        // KPI Row (Real Firestore numbers only)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GolocalMetricCard(
                title = "Today's Rides",
                value = todaysCompletedRides.toString(),
                subtitle = "${profile.completedRides} lifetime",
                icon = Icons.Filled.DirectionsCar,
                modifier = Modifier.weight(1f)
            )
            GolocalMetricCard(
                title = "Today's Earnings",
                value = "₹${todaysEarningsInr.toInt()}",
                subtitle = "Net after fee",
                icon = Icons.Filled.CurrencyRupee,
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GolocalMetricCard(
                title = "Open Requests",
                value = openRequestsCount.toString(),
                subtitle = "Tap Requests tab",
                icon = Icons.Filled.NotificationsActive,
                modifier = Modifier.weight(1f)
            )
            GolocalMetricCard(
                title = "Rating",
                value = String.format("%.1f ★", profile.ratingAvg),
                subtitle = "Verified Partner",
                icon = Icons.Filled.Star,
                modifier = Modifier.weight(1f)
            )
        }

        NotificationPermissionBannerCard()

        if (expiryAlerts.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = GolocalErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GolocalError, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Document Expiry Notice", style = MaterialTheme.typography.titleSmall, color = GolocalError)
                    expiryAlerts.forEach { item ->
                        Text(
                            text = "${item.docType.displayLabel}: ${item.status.name.replace('_', ' ')}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Active Trip Execution Card (if driver has an assigned trip)
        if (activeTrip != null) {
            ActiveRiderTripExecutionCard(
                booking = activeTrip,
                currentLat = currentLat,
                currentLng = currentLng,
                onlineSelf = DriverDirectoryEntry(
                    driverId = profile.driverId,
                    displayName = profile.legalName,
                    isOnline = profile.isOnline,
                    currentLat = currentLat,
                    currentLng = currentLng
                ),
                lowNetworkMode = lowNetworkMode,
                callContact = activeCallContact,
                unreadChatCount = unreadChatCount,
                onCallCustomer = { showCallDialog = true },
                onChatCustomer = {
                    driverViewModel.markActiveChatRead()
                    showChatSheet = true
                },
                onCancelTrip = { showDriverCancelDialog = true },
                onAdvanceState = { next, pin ->
                    driverViewModel.advanceTripState(activeTrip, next, pin, currentLat, currentLng)
                }
            )
        } else {
            GolocalMapContainer(
                pickupLat = currentLat,
                pickupLng = currentLng,
                pickupLabel = gps.label.ifBlank { "${profile.district} Stand" },
                destLat = currentLat + 0.015,
                destLng = currentLng + 0.015,
                destLabel = "Nearby Zone",
                onlineDrivers = if (profile.isOnline) listOf(
                    DriverDirectoryEntry(
                        driverId = profile.driverId,
                        displayName = profile.legalName,
                        isOnline = true,
                        currentLat = currentLat,
                        currentLng = currentLng
                    )
                ) else emptyList(),
                liveTripLocation = null,
                lowNetworkMode = lowNetworkMode,
                mapHeight = 225.dp
            )

            Button(
                onClick = {
                    if (openRequestsCount > 0) onOpenRequests()
                    else driverViewModel.toggleOnline(!profile.isOnline, currentLat, currentLng)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    if (openRequestsCount > 0) "View $openRequestsCount Incoming Request(s)"
                    else if (profile.isOnline) "Go Offline"
                    else "Go Online"
                )
            }
        }

        // Recent Activity
        Text("Recent Activity", style = MaterialTheme.typography.titleMedium)
        if (assignedBookings.isEmpty()) {
            GolocalEmptyState(
                title = "No Assigned Trips Yet",
                message = "Send fare proposals in the Requests tab. Accepted trips and customer pickup details appear here."
            )
        } else {
            assignedBookings.take(4).forEach { b ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                ) {
                    Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${b.pickupLabel} → ${b.destinationLabel}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Passenger: ${b.customerName} • ${b.distanceKm} km", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("₹${b.agreedFareInr.toInt()}", fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.primary)
                            GolocalStatusBadge(label = b.status.uppercase(), tone = if (b.status == "completed") "success" else "info")
                        }
                    }
                }
            }
        }
        if (showCallDialog && activeTrip != null && activeCallContact != null && activeCallContact!!.customerPhone.isNotBlank()) {
            CallPeerConfirmDialog(
                peerLabel = activeTrip.customerName.trim().substringBefore(" ").ifBlank { "Passenger" },
                phoneNumber = activeCallContact!!.customerPhone,
                onDismiss = { showCallDialog = false }
            )
        }

        if (showChatSheet && activeTrip != null) {
            BookingChatModalSheet(
                booking = activeTrip,
                viewerUid = profile.driverId,
                viewerRole = "driver",
                messages = activeChatMessages,
                onSendMessage = { txt -> driverViewModel.sendChatMessage(activeTrip, txt) },
                onMarkRead = { driverViewModel.markActiveChatRead() },
                onDismiss = { showChatSheet = false }
            )
        }

        if (showDriverCancelDialog && activeTrip != null) {
            var category by remember { mutableStateOf(AbuseDetectionEngine.driverCancellationReasons.first()) }
            var note by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showDriverCancelDialog = false },
                title = { Text("Cancel Assigned Trip?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Frequent post-assignment cancellations affect reliability standing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AbuseDetectionEngine.driverCancellationReasons.forEach { reasonOpt ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                RadioButton(selected = category == reasonOpt, onClick = { category = reasonOpt })
                                Text(reasonOpt, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            label = { Text("Note (optional)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                        onClick = {
                            val combined = if (note.isBlank()) category else "$category: ${note.trim()}"
                            driverViewModel.cancelActiveBookingByDriver(activeTrip, category, combined)
                            showDriverCancelDialog = false
                        },
                        modifier = Modifier.testTag("confirm_driver_cancel_button")
                    ) { Text("Confirm Cancel") }
                },
                dismissButton = {
                    TextButton(onClick = { showDriverCancelDialog = false }) { Text("Keep Trip") }
                }
            )
        }
    }
}

@Composable
private fun ActiveRiderTripExecutionCard(
    booking: Booking,
    currentLat: Double,
    currentLng: Double,
    onlineSelf: DriverDirectoryEntry,
    lowNetworkMode: Boolean,
    callContact: BookingCallContact?,
    unreadChatCount: Int,
    onCallCustomer: () -> Unit,
    onChatCustomer: () -> Unit,
    onCancelTrip: () -> Unit,
    onAdvanceState: (nextStatus: String, pin: String) -> Unit
) {
    var enteredPin by remember(booking.id) { mutableStateOf("") }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Active Customer Pickup & Trip", style = MaterialTheme.typography.titleMedium)
                GolocalStatusBadge(label = booking.status.uppercase(), tone = "success")
            }

            GolocalMapContainer(
                pickupLat = booking.pickupLat,
                pickupLng = booking.pickupLng,
                pickupLabel = booking.pickupLabel,
                destLat = booking.destinationLat,
                destLng = booking.destinationLng,
                destLabel = booking.destinationLabel,
                onlineDrivers = listOf(onlineSelf),
                liveTripLocation = null,
                lowNetworkMode = lowNetworkMode,
                mapHeight = 225.dp
            )

            BookingCommunicationBar(
                booking = booking,
                viewerRole = "driver",
                callContact = callContact,
                unreadCount = unreadChatCount,
                onCallClick = onCallCustomer,
                onChatClick = onChatCustomer
            )

            Text("Passenger: ${booking.customerName.trim().substringBefore(" ")}", style = MaterialTheme.typography.titleSmall)
            Text("Route: ${booking.pickupLabel} → ${booking.destinationLabel}", style = MaterialTheme.typography.bodyMedium)
            Text("Agreed Fare: ₹${booking.agreedFareInr.toInt()} (${booking.paymentMethod.uppercase()}) • ${booking.distanceKm} km", fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.primary)

            when (booking.status) {
                "assigned" -> {
                    Button(
                        onClick = { onAdvanceState("arriving", "") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Navigate to Pickup (Mark Arriving)")
                    }
                }
                "arriving" -> {
                    Button(
                        onClick = { onAdvanceState("arrived", "") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Mark Arrived at Pickup")
                    }
                }
                "arrived" -> {
                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = { if (it.length <= 4) enteredPin = it },
                        label = { Text("Enter 4-Digit Passenger Trip PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rider_trip_pin_input")
                    )
                    Button(
                        onClick = { onAdvanceState("in_progress", enteredPin) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Verify PIN & Start Ride")
                    }
                }
                "in_progress" -> {
                    Text("Live GPS coordinate broadcast active (${String.format("%.4f", currentLat)}, ${String.format("%.4f", currentLng)})", style = MaterialTheme.typography.bodySmall, color = GolocalSuccess)
                    Button(
                        onClick = { onAdvanceState("completed", "") },
                        colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                        modifier = Modifier.fillMaxWidth().testTag("rider_complete_ride_button")
                    ) {
                        Text("Complete Ride & Settle Fare")
                    }
                }
            }

            if (booking.status != "in_progress") {
                TextButton(
                    onClick = onCancelTrip,
                    modifier = Modifier.align(Alignment.End).testTag("rider_cancel_trip_button")
                ) {
                    Text("Cancel Assigned Trip", color = GolocalError)
                }
            }
        }
    }
}
