package com.example.rider

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Booking
import com.example.data.model.CommissionRecord
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.DriverProfile
import com.example.domain.AbuseDetectionEngine
import com.example.domain.ExpiryStatus
import com.example.domain.KycDocType
import com.example.domain.RequiredDocumentsCatalog
import com.example.shared.BookingChatModalSheet
import com.example.shared.CallPeerConfirmDialog
import com.example.ui.auth.signOut
import com.example.ui.components.GoLocalLogoMark
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalWarning
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
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
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
                GoLocalLogoMark(modifier = Modifier.size(38.dp))
                Column {
                    Text(
                        text = "Partner Verification",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "GoLocal Rider • KYV Check",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    .border(
                        1.5.dp,
                        if (status == "rejected" || status == "suspended") GolocalError else GolocalWarning,
                        RoundedCornerShape(20.dp)
                    )
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
                    Text(
                        text = "Vehicle: ${existingProfile.vehicleCategory.uppercase()} • ${existingProfile.vehicleRegNumber}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        color = GolocalPrimaryBlue
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
                        Text("Submit Rider Verification", fontWeight = FontWeight.Bold)
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
    onOpenRequests: () -> Unit,
    onOpenNotifications: () -> Unit = {}
) {
    val context = LocalContext.current
    val gps by sessionViewModel.gpsLocation.collectAsStateWithLifecycle()
    val openRequests by driverViewModel.openRequests.collectAsStateWithLifecycle()
    val myOffers by driverViewModel.myOffers.collectAsStateWithLifecycle()
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

    if (activeTrip != null) {
        RiderActiveTripSheet(
            booking = activeTrip,
            currentLat = currentLat,
            currentLng = currentLng,
            onlineSelf = DriverDirectoryEntry(
                driverId = profile.driverId,
                displayName = profile.legalName,
                vehicleCategory = profile.vehicleCategory,
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
            onTriggerSos = {
                driverViewModel.triggerSos(
                    bookingId = activeTrip.id,
                    lat = currentLat,
                    lng = currentLng,
                    notes = "Emergency SOS triggered during active Rider trip"
                )
            },
            onCancelTrip = { showDriverCancelDialog = true },
            onAdvanceState = { next, pin ->
                driverViewModel.advanceTripState(activeTrip, next, pin, currentLat, currentLng)
            }
        )
    } else {
        RiderMapHomeScreen(
            profile = profile,
            currentLat = currentLat,
            currentLng = currentLng,
            gpsIsDetecting = gps.isDetecting,
            locationLabel = gps.label,
            openRequests = openRequests,
            myOffers = myOffers,
            todaysCompletedRides = todaysCompletedRides,
            todaysEarningsInr = todaysEarningsInr,
            expiryAlerts = expiryAlerts,
            lowNetworkMode = lowNetworkMode,
            onToggleOnline = { online ->
                driverViewModel.toggleOnline(online, currentLat, currentLng)
            },
            onRequestLocationSync = {
                locationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            },
            onOpenNotifications = onOpenNotifications,
            onOpenRequests = onOpenRequests,
            onTriggerSos = {
                driverViewModel.triggerSos(
                    bookingId = "",
                    lat = currentLat,
                    lng = currentLng,
                    notes = "Emergency SOS triggered from Rider Home"
                )
            },
            onAcceptRequestWithFare = { booking, proposedFare, etaMins ->
                driverViewModel.submitOffer(booking, proposedFare, etaMins)
            },
            onDeclineRequest = { bookingId ->
                driverViewModel.rejectRequest(bookingId)
            },
            onWithdrawOffer = { offerId ->
                driverViewModel.withdrawOffer(offerId)
            }
        )
    }

    if (showCallDialog && activeTrip != null && activeCallContact != null && activeCallContact!!.customerPhone.isNotBlank()) {
        CallPeerConfirmDialog(
            peerLabel = activeTrip.customerName.trim().substringBefore(" ").ifBlank { "Passenger" },
            phoneNumber = activeCallContact!!.customerPhone,
            onDismiss = { showCallDialog = false },
            onOpenChat = {
                driverViewModel.markActiveChatRead()
                showChatSheet = true
            }
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
