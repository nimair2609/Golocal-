package com.example.rider

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.model.AppNotification
import com.example.data.model.SampleRide
import com.example.data.model.SampleRiderProfile
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalLightBlue
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.GolocalWarningContainer
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.SampleRiderViewModel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Locale

@Composable
fun SampleRiderRequestsTab(
    profile: SampleRiderProfile,
    incomingRides: List<SampleRide>,
    hasActiveRide: Boolean,
    isBusy: Boolean,
    onAcceptRide: (SampleRide) -> Unit,
    onRejectRide: (SampleRide) -> Unit
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(incomingRides.size) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Incoming Sample Requests", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                GolocalStatusBadge(
                    label = if (profile.isOnline()) "ONLINE" else "OFFLINE",
                    tone = if (profile.isOnline()) "success" else "info"
                )
            }
        }

        if (incomingRides.isEmpty()) {
            item {
                GolocalEmptyState(
                    title = "No incoming sample requests",
                    message = "Authorized Customers or Admin can dispatch a sample request to appear here in real time."
                )
            }
        } else {
            items(incomingRides, key = { it.rideId }) { ride ->
                val expMs = ride.expiresAt?.toDate()?.time ?: (nowMs + 60_000L)
                val remainingSec = ((expMs - nowMs) / 1000L).coerceAtLeast(0L)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, GolocalPrimaryBlue.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GolocalStatusBadge(
                                label = "SAMPLE • ${ride.vehicleCategory.uppercase()}",
                                tone = "warning"
                            )
                            Text(
                                text = "${remainingSec}s left",
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = JetBrainsMono,
                                fontWeight = FontWeight.Bold,
                                color = if (remainingSec <= 20) GolocalError else GolocalPrimaryBlue
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(ride.customerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "₹${ride.estimatedFareInr.toInt()} • ${String.format(Locale.US, "%.1f", ride.distanceKm)} km",
                                fontFamily = JetBrainsMono,
                                fontWeight = FontWeight.Bold,
                                color = GolocalDeepNavy
                            )
                        }
                        Text("Pickup: ${ride.pickupLabel}", style = MaterialTheme.typography.bodyMedium)
                        Text("Drop: ${ride.destinationLabel}", style = MaterialTheme.typography.bodyMedium)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onRejectRide(ride) },
                                enabled = !isBusy,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) { Text("Reject") }
                            Button(
                                onClick = { onAcceptRide(ride) },
                                enabled = !isBusy && !hasActiveRide && remainingSec > 0,
                                colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("accept_sample_ride_${ride.rideId}")
                            ) { Text("Accept") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SampleRiderEarningsAndHistoryTab(
    todayRidesCount: Int,
    completedRides: List<SampleRide>,
    rideHistory: List<SampleRide>,
    totalSampleEarningsInr: Double
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Sample Earnings & Ride History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = GolocalDeepNavy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GolocalStatusBadge(
                        label = "SAMPLE / SIMULATED EARNINGS",
                        tone = "warning"
                    )
                    Text(
                        text = "₹${totalSampleEarningsInr.toInt()}",
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Today: $todayRidesCount rides • Total Completed: ${completedRides.size} rides",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFBFDBFE)
                    )
                    Text(
                        text = "Completely isolated from commercial settlements, GST, and payouts.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }

        if (rideHistory.isEmpty()) {
            item {
                GolocalEmptyState(
                    title = "No completed sample rides yet",
                    message = "Accepted, completed, and cancelled sample rides will appear here."
                )
            }
        } else {
            items(rideHistory, key = { it.rideId }) { ride ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GolocalStatusBadge(
                                label = "SAMPLE • ${ride.status.uppercase()}",
                                tone = if (ride.status == "completed") "success" else "error"
                            )
                            Text(
                                text = "₹${ride.estimatedFareInr.toInt()}",
                                fontFamily = JetBrainsMono,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text("${ride.pickupLabel} -> ${ride.destinationLabel}", fontWeight = FontWeight.SemiBold)
                        val timeLabel = ride.createdAt?.toDate()?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(it) }.orEmpty()
                        Text(
                            text = "${String.format(Locale.US, "%.1f", ride.distanceKm)} km • $timeLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SampleRiderNotificationsTab(
    notifications: List<AppNotification>,
    onMarkRead: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Sample Rider Notifications", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        if (notifications.isEmpty()) {
            item {
                GolocalEmptyState(
                    title = "No alerts yet",
                    message = "Incoming sample ride alerts and system updates appear here."
                )
            }
        } else {
            items(notifications, key = { it.id }) { notif ->
                Surface(
                    onClick = { if (!notif.read) onMarkRead(notif.id) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (notif.read) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else GolocalLightBlue,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(notif.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(notif.body, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun SampleRiderProfileAndSettingsTab(
    profile: SampleRiderProfile,
    sessionViewModel: AppSessionViewModel,
    sampleViewModel: SampleRiderViewModel,
    onOpenSos: () -> Unit,
    onSignOut: () -> Unit
) {
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    var displayName by remember(profile.uid, profile.displayName) { mutableStateOf(profile.displayName) }
    var vehicleCategory by remember(profile.uid, profile.vehicleCategory) { mutableStateOf(profile.vehicleCategory) }
    var photoUrl by remember(profile.uid, profile.photoUrl) { mutableStateOf(profile.photoUrl) }
    var emergencyName by remember(profile.uid, profile.emergencyContactName) { mutableStateOf(profile.emergencyContactName) }
    var emergencyPhone by remember(profile.uid, profile.emergencyContactPhone) { mutableStateOf(profile.emergencyContactPhone) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            photoUrl = uri.toString()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(GolocalLightBlue),
                contentAlignment = Alignment.Center
            ) {
                if (photoUrl.isNotBlank()) {
                    AsyncImage(
                        model = photoUrl,
                        contentDescription = "Sample Rider Avatar",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Filled.Person, contentDescription = "Sample Rider", tint = GolocalPrimaryBlue, modifier = Modifier.size(34.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GolocalStatusBadge(
                    label = "SAMPLE RIDER • NO KYC REQUIRED",
                    tone = "warning"
                )
                Text(profile.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(profile.phone, style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMono)
            }
        }

        OutlinedButton(
            onClick = {
                photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Icon(Icons.Filled.PhotoCamera, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Choose Profile Photo")
        }

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Rider Display Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Vehicle Category", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("toto", "auto", "bike", "cab").forEach { cat ->
                FilterChip(
                    selected = vehicleCategory == cat,
                    onClick = { vehicleCategory = cat },
                    label = { Text(cat.uppercase()) }
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Shield, contentDescription = null, tint = GolocalPrimaryBlue)
                    Text("Emergency Contact", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                OutlinedTextField(
                    value = emergencyName,
                    onValueChange = { emergencyName = it },
                    label = { Text("Emergency Contact Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = emergencyPhone,
                    onValueChange = { emergencyPhone = it },
                    label = { Text("Emergency Contact Phone (+91...)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Button(
            onClick = {
                sampleViewModel.updateProfile(
                    displayName = displayName,
                    vehicleCategory = vehicleCategory,
                    photoUrl = photoUrl,
                    emergencyContactName = emergencyName,
                    emergencyContactPhone = emergencyPhone
                )
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save_sample_profile_button")
        ) { Text("Save Profile & Emergency Contact") }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sound & Vibration Alerts", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Incoming Ride Chime", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = prefs.rideRequestSoundEnabled,
                        onCheckedChange = { sessionViewModel.setRideRequestSoundEnabled(it) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Incoming Ride Vibration", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = prefs.rideRequestVibrationEnabled,
                        onCheckedChange = { sessionViewModel.setRideRequestVibrationEnabled(it) }
                    )
                }
            }
        }

        OutlinedButton(
            onClick = onOpenSos,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalError),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Trigger Emergency SOS")
        }

        Button(
            onClick = onSignOut,
            colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sign Out")
        }
    }
}
