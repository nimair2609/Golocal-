package com.example.admin

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AuditLog
import com.example.data.model.Booking
import com.example.data.model.DriverProfile
import com.example.data.model.PlatformSettings
import com.example.data.model.TestRideOtpChallenge
import com.example.data.model.TestRideOtpSecret
import com.example.data.model.TripLocation
import com.example.domain.SimulatedTestDriver
import com.example.domain.TestDriverCatalog
import com.example.domain.TestRideOtpUtil
import com.example.map.GolocalMapContainer
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun TestRideSettingsPanel(
    settings: PlatformSettings,
    isOwnerAdmin: Boolean,
    onSaveSettings: (Boolean, Boolean, Double, Long, Boolean, Boolean) -> Unit,
    onRequestClearCompleted: () -> Unit
) {
    var autoAssign by remember(settings.testAutoAssignEnabled) { mutableStateOf(settings.testAutoAssignEnabled) }
    var manualAssign by remember(settings.testManualAssignEnabled) { mutableStateOf(settings.testManualAssignEnabled) }
    var speedKmph by remember(settings.testDriverSpeedKmph) { mutableDoubleStateOf(settings.testDriverSpeedKmph.coerceIn(10.0, 80.0)) }
    var otpValiditySec by remember(settings.testOtpValiditySeconds) { mutableLongStateOf(settings.testOtpValiditySeconds.coerceIn(60L, 900L)) }
    var allowOverride by remember(settings.testAllowAdminOtpOverride) { mutableStateOf(settings.testAllowAdminOtpOverride) }
    var autoComplete by remember(settings.testAutoCompleteEnabled) { mutableStateOf(settings.testAutoCompleteEnabled) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Simulation & OTP Controls", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (!isOwnerAdmin) {
                    GolocalStatusBadge(label = "READ ONLY (OPS)", tone = "info")
                }
            }

            SettingToggleRow(
                label = "Auto-Assign Simulated Driver",
                checked = autoAssign,
                enabled = isOwnerAdmin,
                testTag = "test_setting_auto_assign",
                onCheckedChange = { autoAssign = it }
            )
            SettingToggleRow(
                label = "Allow Manual Driver Assignment",
                checked = manualAssign,
                enabled = isOwnerAdmin,
                testTag = "test_setting_manual_assign",
                onCheckedChange = { manualAssign = it }
            )
            SettingToggleRow(
                label = "Allow Admin OTP Override (Audited)",
                checked = allowOverride,
                enabled = isOwnerAdmin,
                testTag = "test_setting_allow_override",
                onCheckedChange = { allowOverride = it }
            )
            SettingToggleRow(
                label = "Auto-Complete on Drop Arrival",
                checked = autoComplete,
                enabled = isOwnerAdmin,
                testTag = "test_setting_auto_complete",
                onCheckedChange = { autoComplete = it }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Simulated Speed", style = MaterialTheme.typography.labelLarge)
                    Text("${speedKmph.toInt()} km/h", style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMono, color = GolocalPrimaryBlue)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { speedKmph = (speedKmph - 5.0).coerceAtLeast(10.0) },
                        enabled = isOwnerAdmin && speedKmph > 10.0,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("-5") }
                    OutlinedButton(
                        onClick = { speedKmph = (speedKmph + 5.0).coerceAtMost(80.0) },
                        enabled = isOwnerAdmin && speedKmph < 80.0,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("+5") }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Test OTP Validity Window", style = MaterialTheme.typography.labelLarge)
                    Text("${otpValiditySec}s (${otpValiditySec / 60}m ${otpValiditySec % 60}s)", style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMono, color = GolocalPrimaryBlue)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { otpValiditySec = (otpValiditySec - 60L).coerceAtLeast(60L) },
                        enabled = isOwnerAdmin && otpValiditySec > 60L,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("-1m") }
                    OutlinedButton(
                        onClick = { otpValiditySec = (otpValiditySec + 60L).coerceAtMost(900L) },
                        enabled = isOwnerAdmin && otpValiditySec < 900L,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("+1m") }
                }
            }

            if (isOwnerAdmin) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveSettings(autoAssign, manualAssign, speedKmph, otpValiditySec, allowOverride, autoComplete)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("test_save_settings_button")
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Settings")
                    }
                    OutlinedButton(
                        onClick = onRequestClearCompleted,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalError),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("test_clear_completed_button")
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear Done", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
internal fun TestRideLiveSimulatorCard(
    booking: Booking?,
    tripLocation: TripLocation?,
    simulatingBookingId: String?,
    simulationPhase: String?,
    onSimulateToPickup: (Booking) -> Unit,
    onSimulateToDrop: (Booking) -> Unit,
    onStopSimulation: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GolocalPrimaryBlue.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Live Driver Movement Simulator", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = booking?.let { "Focused on TEST RIDE #${it.id.take(8)} (${it.status.uppercase()})" }
                            ?: "Select or create a TEST RIDE to preview & simulate movement",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (simulatingBookingId != null) {
                    GolocalStatusBadge(
                        label = if (simulationPhase == "to_drop") "MOVING TO DROP" else "MOVING TO PICKUP",
                        tone = "warning"
                    )
                }
            }

            if (booking != null) {
                GolocalMapContainer(
                    pickupLat = booking.pickupLat,
                    pickupLng = booking.pickupLng,
                    pickupLabel = booking.pickupLabel,
                    destLat = booking.destinationLat,
                    destLng = booking.destinationLng,
                    destLabel = booking.destinationLabel,
                    onlineDrivers = emptyList(),
                    liveTripLocation = tripLocation,
                    lowNetworkMode = false,
                    mapHeight = 200.dp,
                    cornerRadius = 14.dp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onSimulateToPickup(booking) },
                        enabled = booking.status in setOf("assigned", "arriving"),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("sim_to_pickup_button")
                    ) {
                        Text("Sim -> Pickup", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = { onSimulateToDrop(booking) },
                        enabled = booking.status == "in_progress",
                        colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("sim_to_drop_button")
                    ) {
                        Text("Sim -> Drop", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = onStopSimulation,
                        enabled = simulatingBookingId != null,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalError),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("sim_stop_button")
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop simulation", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun AdminTestRideCard(
    booking: Booking,
    otpSecret: TestRideOtpSecret?,
    otpChallenge: TestRideOtpChallenge?,
    settings: PlatformSettings,
    isOwnerAdmin: Boolean,
    isFocused: Boolean,
    isSimulatingThis: Boolean,
    onFocusMap: () -> Unit,
    onAssignDriver: () -> Unit,
    onAdvanceStatus: (String) -> Unit,
    onSimulateToPickup: () -> Unit,
    onSimulateToDrop: () -> Unit,
    onSendOtpToCustomer: () -> Unit,
    onRequestOtpOverride: () -> Unit,
    onOverrideOtp: () -> Unit,
    onResetRide: () -> Unit,
    onCancelRide: () -> Unit
) {
    val fareInr = if (booking.agreedFareInr > 0) booking.agreedFareInr else booking.estimatedFareInr
    val createdLabel = remember(booking.id, booking.createdAt) {
        val ms = booking.createdAt?.toDate()?.time
        if (ms == null) "Just now"
        else SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date(ms))
    }

    var remainingSec by remember(booking.id, booking.testOtpExpiresAtMs, booking.testOtpStatus) {
        mutableLongStateOf(TestRideOtpUtil.remainingSeconds(booking.testOtpExpiresAtMs))
    }
    LaunchedEffect(booking.id, booking.testOtpExpiresAtMs, booking.testOtpStatus) {
        while (booking.testOtpExpiresAtMs > 0L && booking.testOtpStatus == "pending") {
            remainingSec = TestRideOtpUtil.remainingSeconds(booking.testOtpExpiresAtMs)
            if (remainingSec <= 0L) break
            delay(1000L)
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) GolocalPrimaryBlue else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(18.dp)
            )
            .testTag("admin_test_ride_card_${booking.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    GolocalStatusBadge(label = "TEST RIDE", tone = "warning")
                    Text("#${booking.id.take(8)}", fontFamily = JetBrainsMono, style = MaterialTheme.typography.titleSmall)
                }
                GolocalStatusBadge(
                    label = booking.status.uppercase(),
                    tone = when (booking.status) {
                        "completed" -> "success"
                        "cancelled" -> "error"
                        "searching" -> "warning"
                        else -> "info"
                    }
                )
            }

            Text(
                text = "Customer: ${booking.customerName.ifBlank { "Customer" }} (${booking.customerId.take(8)})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${booking.pickupLabel} -> ${booking.destinationLabel}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${booking.vehicleCategory.uppercase()} • ${booking.distanceKm} km • ₹${fareInr.toInt()} • $createdLabel",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = JetBrainsMono,
                color = GolocalPrimaryBlue
            )

            if (booking.assignedDriverId.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = GolocalLightBlue.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "TEST DRIVER: ${booking.assignedDriverName}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = GolocalDeepNavy
                        )
                        Text(
                            text = booking.assignedVehicleLabel.ifBlank { "TEST VEHICLE" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (otpSecret != null && otpSecret.otpPlaintext.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = GolocalWarningContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("ADMIN TEST OTP", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = GolocalDeepNavy)
                            Text(
                                text = otpSecret.otpPlaintext,
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = JetBrainsMono,
                                fontWeight = FontWeight.ExtraBold,
                                color = GolocalPrimaryBlue
                            )
                            val otpStatusLabel = when (booking.testOtpStatus) {
                                "verified" -> "Verified by Customer"
                                "overridden" -> "Overridden by Owner Admin"
                                "override_requested" -> "Override Requested by Ops (Pending Owner)"
                                "reset" -> "Reset"
                                else -> if (remainingSec > 0) "Expires in ${remainingSec}s" else "Expired (regenerate via Assign/Reset)"
                            }
                            Text(otpStatusLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (booking.status in setOf("assigned", "arriving", "arrived") &&
                            booking.testOtpStatus in setOf("pending", "override_requested")
                        ) {
                            OutlinedButton(
                                onClick = onSendOtpToCustomer,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("send_test_otp_${booking.id}")
                            ) {
                                Text("Send OTP")
                            }
                        }
                    }
                }
            }

            if (otpChallenge != null && otpChallenge.overrideRequestedReason.isNotBlank() && booking.testOtpStatus == "override_requested") {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = GolocalLightBlue.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Ops Override Request: ${otpChallenge.overrideRequestedReason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = GolocalDeepNavy,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // Primary action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onFocusMap,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Filled.Map, contentDescription = "Focus map", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isFocused) "Focused" else "Map")
                }

                if (booking.status == "searching" && settings.testManualAssignEnabled) {
                    Button(
                        onClick = onAssignDriver,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("assign_test_driver_${booking.id}")
                    ) {
                        Text("Assign Test Driver")
                    }
                } else if (booking.status == "assigned") {
                    Button(
                        onClick = { onAdvanceStatus("arriving") },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Start Arriving") }
                    OutlinedButton(
                        onClick = onSimulateToPickup,
                        enabled = !isSimulatingThis,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Sim Pickup") }
                } else if (booking.status == "arriving") {
                    Button(
                        onClick = { onAdvanceStatus("arrived") },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Mark Arrived") }
                    OutlinedButton(
                        onClick = onSimulateToPickup,
                        enabled = !isSimulatingThis,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Sim") }
                } else if (booking.status == "arrived" && settings.testAllowAdminOtpOverride) {
                    if (isOwnerAdmin) {
                        Button(
                            onClick = onOverrideOtp,
                            colors = ButtonDefaults.buttonColors(containerColor = GolocalWarning, contentColor = GolocalDeepNavy),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("override_otp_${booking.id}")
                        ) { Text("Authorize & Override OTP") }
                    } else {
                        OutlinedButton(
                            onClick = onRequestOtpOverride,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("request_override_otp_${booking.id}")
                        ) { Text("Request OTP Override") }
                    }
                } else if (booking.status == "in_progress") {
                    OutlinedButton(
                        onClick = onSimulateToDrop,
                        enabled = !isSimulatingThis,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Sim -> Drop") }
                    Button(
                        onClick = { onAdvanceStatus("completed") },
                        colors = ButtonDefaults.buttonColors(containerColor = GolocalSuccess),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("complete_test_ride_${booking.id}")
                    ) { Text("Complete") }
                }
            }

            if (booking.status !in setOf("completed")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (booking.status in setOf("assigned", "arriving", "arrived", "cancelled")) {
                        TextButton(onClick = onResetRide, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Reset Test Ride")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }
                    if (booking.status != "cancelled") {
                        TextButton(
                            onClick = onCancelRide,
                            colors = ButtonDefaults.textButtonColors(contentColor = GolocalError),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Cancel Test Ride") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AssignTestDriverDialog(
    booking: Booking,
    verifiedPartners: List<DriverProfile>,
    onDismiss: () -> Unit,
    onSelectPartner: (DriverProfile) -> Unit,
    onSelectSimulated: (SimulatedTestDriver) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign Authorized Test Driver") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Verified Test Partners (${verifiedPartners.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = GolocalDeepNavy
                )
                if (verifiedPartners.isEmpty()) {
                    Text(
                        text = "No verified drivers marked as Test Partner in Riders module. Authorize a rider there or pick a simulated unit below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    verifiedPartners.forEach { partner ->
                        Surface(
                            onClick = { onSelectPartner(partner) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (partner.vehicleCategory == booking.vehicleCategory) GolocalSuccessContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                                .testTag("select_test_partner_${partner.driverId}")
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(partner.legalName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    GolocalStatusBadge(label = "REAL TEST PARTNER", tone = "success")
                                }
                                Text("${partner.vehicleCategory.uppercase()} • ${partner.vehicleModel} • ${partner.vehicleRegNumber}", style = MaterialTheme.typography.bodySmall, color = GolocalPrimaryBlue)
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "Simulated Test Units — Ride #${booking.id.take(8)} (${booking.vehicleCategory.uppercase()})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = GolocalDeepNavy
                )
                TestDriverCatalog.presetDrivers.forEach { driver ->
                    Surface(
                        onClick = { onSelectSimulated(driver) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (driver.vehicleCategory == booking.vehicleCategory) GolocalLightBlue else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                            .testTag("select_sim_driver_${driver.id}")
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(driver.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("${driver.vehicleModel} • ${driver.plateNumber}", style = MaterialTheme.typography.bodySmall, color = GolocalPrimaryBlue)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
internal fun TestRideAuditLogItem(log: AuditLog) {
    val timeLabel = remember(log.id, log.createdAt) {
        val ms = log.createdAt?.toDate()?.time
        if (ms == null) "Just now"
        else SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date(ms))
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(log.action, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = GolocalPrimaryBlue)
                Text(timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(log.summary, style = MaterialTheme.typography.bodySmall)
            Text(
                text = "Actor: ${log.actorEmail.ifBlank { log.actorId.take(10) }} • Target: ${log.targetId.take(10)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
