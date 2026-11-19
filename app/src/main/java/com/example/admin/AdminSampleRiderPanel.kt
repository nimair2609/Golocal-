package com.example.admin

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.SampleRide
import com.example.data.model.SampleRiderAllowlistEntry
import com.example.data.model.SampleRiderProfile
import com.example.domain.FareCalculator
import com.example.domain.WestBengalCatalog
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalLightBlue
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarningContainer
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AdminViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminSampleRiderPanel(
    adminViewModel: AdminViewModel
) {
    val context = LocalContext.current
    val allowlist by adminViewModel.sampleAllowlist.collectAsStateWithLifecycle()
    val sampleRiders by adminViewModel.sampleRiders.collectAsStateWithLifecycle()
    val sampleRides by adminViewModel.sampleRides.collectAsStateWithLifecycle()
    val localSampleOtps by adminViewModel.localSampleOtps.collectAsStateWithLifecycle()
    val fareConfigs by adminViewModel.fareConfigs.collectAsStateWithLifecycle()

    var phoneInput by remember { mutableStateOf("+91") }
    var labelInput by remember { mutableStateOf("") }
    var vehicleCategory by remember { mutableStateOf("toto") }
    var allowlistNote by remember { mutableStateOf("") }

    var pickupPlace by remember { mutableStateOf(WestBengalCatalog.places.first()) }
    var dropPlace by remember { mutableStateOf(WestBengalCatalog.places[1]) }
    var dispatchVehicle by remember { mutableStateOf("toto") }
    var dispatchCustomerName by remember { mutableStateOf("Admin Sample Dispatch") }
    var pickupExpanded by remember { mutableStateOf(false) }
    var dropExpanded by remember { mutableStateOf(false) }

    val quote = remember(pickupPlace, dropPlace, dispatchVehicle, fareConfigs) {
        val cfg = fareConfigs.firstOrNull { it.vehicleCategory == dispatchVehicle }
            ?: FareCalculator.defaultFareConfigs().firstOrNull { it.vehicleCategory == dispatchVehicle }
            ?: FareCalculator.defaultFareConfigs().first()
        val distKm = FareCalculator.haversineDistanceKm(
            pickupPlace.lat,
            pickupPlace.lng,
            dropPlace.lat,
            dropPlace.lng
        ).coerceAtLeast(1.2)
        FareCalculator.calculateFareQuote(distKm, cfg)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Admin Sample Allowlist Card
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GolocalPrimaryBlue.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Science, contentDescription = null, tint = GolocalPrimaryBlue)
                        Text(
                            text = "Allowlist Sample Rider (Zero KYC)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Allowlisted phone numbers auto-provision as SAMPLE RIDER upon Mobile OTP login without DL, RC, or insurance documents.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = phoneInput,
                        onValueChange = { phoneInput = it },
                        label = { Text("Phone Number (E.164, e.g. +919876543210)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("admin_sample_phone_input")
                    )
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text("Sample Rider Display Label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("toto", "auto", "bike", "cab").forEach { cat ->
                            FilterChip(
                                selected = vehicleCategory == cat,
                                onClick = { vehicleCategory = cat },
                                label = { Text(cat.uppercase()) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = allowlistNote,
                        onValueChange = { allowlistNote = it },
                        label = { Text("Admin note (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            adminViewModel.upsertSampleAllowlistPhone(
                                phoneE164 = phoneInput,
                                label = labelInput.ifBlank { "Sample Rider" },
                                vehicleCategory = vehicleCategory,
                                enabled = true,
                                note = allowlistNote
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("admin_save_sample_allowlist_button")
                    ) {
                        Text("Allowlist Phone for Sample Rider Login")
                    }
                }
            }
        }

        // 2. Allowlisted Phone Entries
        if (allowlist.isNotEmpty()) {
            item {
                Text(
                    text = "Sample Allowlist (${allowlist.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            items(allowlist, key = { it.phoneE164 }) { entry ->
                AllowlistEntryRow(
                    entry = entry,
                    onToggle = { nextEnabled ->
                        adminViewModel.upsertSampleAllowlistPhone(
                            phoneE164 = entry.phoneE164,
                            label = entry.label,
                            vehicleCategory = entry.vehicleCategory,
                            enabled = nextEnabled,
                            note = entry.note
                        )
                    }
                )
            }
        }

        // 3. Dispatch Incoming Sample Ride Card
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GolocalSuccess.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Dispatch Incoming Sample Ride",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = dispatchCustomerName,
                        onValueChange = { dispatchCustomerName = it },
                        label = { Text("Sample Customer Label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ExposedDropdownMenuBox(
                        expanded = pickupExpanded,
                        onExpandedChange = { pickupExpanded = !pickupExpanded }
                    ) {
                        OutlinedTextField(
                            value = pickupPlace.nameEn,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Pickup Location") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pickupExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = pickupExpanded,
                            onDismissRequest = { pickupExpanded = false }
                        ) {
                            WestBengalCatalog.places.forEach { place ->
                                DropdownMenuItem(
                                    text = { Text("${place.nameEn} (${place.district})") },
                                    onClick = {
                                        pickupPlace = place
                                        pickupExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = dropExpanded,
                        onExpandedChange = { dropExpanded = !dropExpanded }
                    ) {
                        OutlinedTextField(
                            value = dropPlace.nameEn,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Destination Location") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = dropExpanded,
                            onDismissRequest = { dropExpanded = false }
                        ) {
                            WestBengalCatalog.places.forEach { place ->
                                DropdownMenuItem(
                                    text = { Text("${place.nameEn} (${place.district})") },
                                    onClick = {
                                        dropPlace = place
                                        dropExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("toto", "auto", "bike", "cab").forEach { cat ->
                            FilterChip(
                                selected = dispatchVehicle == cat,
                                onClick = { dispatchVehicle = cat },
                                label = { Text(cat.uppercase()) }
                            )
                        }
                    }

                    Text(
                        text = "Est. Sample Fare: ₹${quote.estimatedFareInr.toInt()} • ${String.format(Locale.US, "%.1f", quote.distanceKm)} km",
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold,
                        color = GolocalDeepNavy
                    )

                    Button(
                        onClick = {
                            adminViewModel.dispatchSampleRide(
                                context = context,
                                customerId = "admin_sample_dispatch",
                                customerName = dispatchCustomerName,
                                vehicleCategory = dispatchVehicle,
                                pickupLabel = pickupPlace.nameEn,
                                pickupLat = pickupPlace.lat,
                                pickupLng = pickupPlace.lng,
                                destinationLabel = dropPlace.nameEn,
                                destinationLat = dropPlace.lat,
                                destinationLng = dropPlace.lng,
                                distanceKm = quote.distanceKm,
                                estimatedFareInr = quote.estimatedFareInr
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("admin_dispatch_sample_ride_button")
                    ) {
                        Text("Dispatch Sample Ride (Hashed OTP)")
                    }
                }
            }
        }

        // 4. Registered Sample Riders Roster
        item {
            Text(
                text = "Sample Riders Roster (${sampleRiders.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
        if (sampleRiders.isEmpty()) {
            item {
                GolocalEmptyState(
                    title = "No Sample Riders signed in yet",
                    message = "Allowlist a phone number above, then sign in with that phone in the Rider app via Mobile OTP."
                )
            }
        } else {
            items(sampleRiders, key = { it.uid }) { rider ->
                SampleRiderRosterCard(
                    rider = rider,
                    rides = sampleRides.filter { it.assignedSampleRiderId == rider.uid },
                    onSetStatus = { newStatus -> adminViewModel.setSampleRiderStatus(rider.uid, newStatus) }
                )
            }
        }

        // 5. Live Sample Rides Monitor (with Local Dispatch OTP when Admin created it)
        item {
            Text(
                text = "Isolated Sample Rides (${sampleRides.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
        items(sampleRides, key = { it.rideId }) { ride ->
            val localOtp = localSampleOtps[ride.rideId]?.otpPlaintext
            AdminSampleRideCard(
                ride = ride,
                localDispatchOtp = localOtp,
                onCancel = { adminViewModel.cancelAdminSampleRide(ride.rideId) }
            )
        }
    }
}

@Composable
private fun AllowlistEntryRow(
    entry: SampleRiderAllowlistEntry,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.phoneE164, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold)
                Text(
                    "${entry.label.ifBlank { "Sample Rider" }} • ${entry.vehicleCategory.uppercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = { onToggle(!entry.enabled) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(if (entry.enabled) "Disable" else "Enable")
            }
        }
    }
}

@Composable
private fun SampleRiderRosterCard(
    rider: SampleRiderProfile,
    rides: List<SampleRide>,
    onSetStatus: (String) -> Unit
) {
    val completed = rides.count { it.status == "completed" }
    val earnings = rides.filter { it.status == "completed" }.sumOf { it.estimatedFareInr }.toInt()

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GolocalStatusBadge(
                    label = "SAMPLE RIDER • ${rider.verificationStatus.uppercase()}",
                    tone = "warning"
                )
                GolocalStatusBadge(
                    label = if (rider.isOnline()) "ONLINE" else "OFFLINE",
                    tone = if (rider.isOnline()) "success" else "info"
                )
            }
            Text("${rider.displayName} (${rider.vehicleCategory.uppercase()})", fontWeight = FontWeight.Bold)
            Text("Phone: ${rider.phone}", fontFamily = JetBrainsMono, style = MaterialTheme.typography.bodySmall)
            Text(
                text = if (rider.currentLat != null && rider.currentLng != null) {
                    "Live GPS: ${String.format(Locale.US, "%.4f, %.4f", rider.currentLat, rider.currentLng)} • Completed: $completed (₹$earnings simulated)"
                } else {
                    "GPS unavailable • Completed: $completed (₹$earnings simulated)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rider.verificationStatus == "sample_approved") {
                    OutlinedButton(
                        onClick = { onSetStatus("suspended") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Suspend") }
                    OutlinedButton(
                        onClick = { onSetStatus("revoked") },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalError),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Revoke") }
                } else {
                    Button(
                        onClick = { onSetStatus("sample_approved") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Re-approve") }
                }
            }
        }
    }
}

@Composable
private fun AdminSampleRideCard(
    ride: SampleRide,
    localDispatchOtp: String?,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GolocalStatusBadge(
                    label = "SAMPLE • ${ride.status.uppercase()}",
                    tone = "info"
                )
                Text("₹${ride.estimatedFareInr.toInt()}", fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold)
            }
            Text("${ride.pickupLabel} -> ${ride.destinationLabel}", fontWeight = FontWeight.SemiBold)
            if (ride.assignedSampleRiderName.isNotBlank()) {
                Text("Assigned SAMPLE RIDER: ${ride.assignedSampleRiderName}", style = MaterialTheme.typography.bodySmall)
            }
            if (!localDispatchOtp.isNullOrBlank() && !ride.otpVerified && ride.status in setOf("incoming", "accepted", "arriving", "arrived")) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = GolocalWarningContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Local Dispatch OTP (device-only, hashed in Firestore): $localDispatchOtp",
                        modifier = Modifier.padding(10.dp),
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold,
                        color = GolocalDeepNavy
                    )
                }
            }
            if (ride.status in setOf("incoming", "accepted", "arriving", "arrived")) {
                Spacer(modifier = Modifier.height(2.dp))
                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalError),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Cancel Sample Ride") }
            }
        }
    }
}
