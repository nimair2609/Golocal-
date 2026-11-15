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
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Booking
import com.example.data.model.CommissionRecord
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.DriverProfile
import com.example.ui.auth.signOut
import com.example.ui.components.GoLocalLogoMark
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalMetricCard
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.components.InteractiveBengalMapCanvas
import com.example.ui.theme.*
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.DriverViewModel
import java.util.Calendar

@Composable
fun RiderRegistrationAndVerificationGate(
    existingProfile: DriverProfile?,
    displayName: String,
    driverViewModel: DriverViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    var showForm by remember(existingProfile?.verificationStatus) {
        mutableStateOf(existingProfile == null || existingProfile.verificationStatus == "rejected")
    }

    var legalName by remember(existingProfile?.legalName) { mutableStateOf(existingProfile?.legalName?.ifBlank { displayName } ?: displayName) }
    var phone by remember(existingProfile?.phone) { mutableStateOf(existingProfile?.phone ?: "") }
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
    var upi by remember(existingProfile?.settlementUpiOrBank) { mutableStateOf(existingProfile?.settlementUpiOrBank ?: "") }
    var docUri by remember(existingProfile?.documentPhotoUri) { mutableStateOf(existingProfile?.documentPhotoUri ?: "") }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) docUri = uri.toString()
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
            OutlinedButton(onClick = { signOut(credentialManager, scope) }) {
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
                            "pending" -> "Your Driving Licence, Vehicle RC, and Permit are awaiting GoLocal Admin verification. You cannot go online or send fare proposals until approved."
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
                    OutlinedTextField(value = licence, onValueChange = { licence = it }, label = { Text("Driving Licence Number *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = rcNumber, onValueChange = { rcNumber = it }, label = { Text("RC Number") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = insurance, onValueChange = { insurance = it }, label = { Text("Insurance No.") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    OutlinedTextField(value = permit, onValueChange = { permit = it }, label = { Text("Local Route / Commercial Permit") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = upi, onValueChange = { upi = it }, label = { Text("Settlement UPI ID or Bank Ref") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (docUri.isBlank()) "Attach KYC / RC Document Photo" else "Document Attached ✓")
                    }

                    Button(
                        onClick = {
                            driverViewModel.registerDriver(
                                legalName, phone, address, district, block,
                                vehicleCategory, vehicleModel, vehicleReg,
                                licence, rcNumber, insurance, permit, upi, docUri
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
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.any { it }) {
            sessionViewModel.detectDeviceLocation(context)
        }
    }

    // Compute strict real today's rides & today's earnings from Firestore records
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
                onAdvanceState = { next, pin ->
                    driverViewModel.advanceTripState(activeTrip, next, pin, currentLat, currentLng)
                }
            )
        } else {
            InteractiveBengalMapCanvas(
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
                lowNetworkMode = lowNetworkMode
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
    }
}

@Composable
private fun ActiveRiderTripExecutionCard(
    booking: Booking,
    currentLat: Double,
    currentLng: Double,
    onlineSelf: DriverDirectoryEntry,
    lowNetworkMode: Boolean,
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

            InteractiveBengalMapCanvas(
                pickupLat = booking.pickupLat,
                pickupLng = booking.pickupLng,
                pickupLabel = booking.pickupLabel,
                destLat = booking.destinationLat,
                destLng = booking.destinationLng,
                destLabel = booking.destinationLabel,
                onlineDrivers = listOf(onlineSelf),
                liveTripLocation = null,
                lowNetworkMode = lowNetworkMode
            )

            Text("Passenger: ${booking.customerName}", style = MaterialTheme.typography.titleSmall)
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
        }
    }
}
