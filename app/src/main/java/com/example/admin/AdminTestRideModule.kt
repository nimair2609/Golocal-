package com.example.admin

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Booking
import com.example.domain.TestRideSeparation
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.*
import com.example.viewmodel.AdminViewModel

@Composable
fun AdminTestRideModule(
    adminViewModel: AdminViewModel,
    isOwnerAdmin: Boolean,
    actorRoleLabel: String
) {
    val platformSettings by adminViewModel.platformSettings.collectAsStateWithLifecycle()
    val testBookings by adminViewModel.testBookings.collectAsStateWithLifecycle()
    val verifiedTestPartners by adminViewModel.verifiedTestPartners.collectAsStateWithLifecycle()
    val testTripLocations by adminViewModel.testTripLocations.collectAsStateWithLifecycle()
    val testOtpSecrets by adminViewModel.testOtpSecrets.collectAsStateWithLifecycle()
    val testOtpChallenges by adminViewModel.testOtpChallenges.collectAsStateWithLifecycle()
    val testAuditLogs by adminViewModel.testAuditLogs.collectAsStateWithLifecycle()
    val simulatingBookingId by adminViewModel.simulatingBookingId.collectAsStateWithLifecycle()
    val simulationPhase by adminViewModel.simulationPhase.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    var showAuditHistory by remember { mutableStateOf(true) }
    var selectedFilter by remember { mutableStateOf("All Test") }
    var focusedBookingId by remember { mutableStateOf<String?>(null) }
    var assignTarget by remember { mutableStateOf<Booking?>(null) }
    var overrideTarget by remember { mutableStateOf<Booking?>(null) }
    var requestOverrideTarget by remember { mutableStateOf<Booking?>(null) }
    var cancelTarget by remember { mutableStateOf<Booking?>(null) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(
        platformSettings.testRideModeEnabled,
        platformSettings.testAutoAssignEnabled,
        testBookings.size,
        verifiedTestPartners.size
    ) {
        if (platformSettings.testRideModeEnabled && platformSettings.testAutoAssignEnabled) {
            adminViewModel.tryAutoAssignPendingTestRides(actorRoleLabel)
        }
    }

    val filters = listOf("All Test", "Pending", "Assigned", "Ongoing", "Completed", "Cancelled")
    val filteredBookings = remember(testBookings, selectedFilter) {
        testBookings.filter { TestRideSeparation.matchesAdminTestFilter(it, selectedFilter) }
    }
    val focusedBooking = remember(focusedBookingId, testBookings) {
        testBookings.firstOrNull { it.id == focusedBookingId }
            ?: testBookings.firstOrNull { it.status in setOf("searching", "assigned", "arriving", "arrived", "in_progress") }
            ?: testBookings.firstOrNull()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Master Test Ride Mode Banner + Toggle
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (platformSettings.testRideModeEnabled) GolocalWarningContainer else MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.5.dp,
                        color = if (platformSettings.testRideModeEnabled) GolocalWarning else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(20.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Science,
                                contentDescription = "Test Ride Mode",
                                tint = if (platformSettings.testRideModeEnabled) GolocalDeepNavy else GolocalPrimaryBlue
                            )
                            Column {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Test Ride Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    GolocalStatusBadge(
                                        label = if (platformSettings.testRideModeEnabled) "ACTIVE" else "OFF",
                                        tone = if (platformSettings.testRideModeEnabled) "warning" else "info"
                                    )
                                }
                                Text(
                                    text = if (platformSettings.testRideModeEnabled) {
                                        "New customer bookings are tagged TEST RIDE & isolated from real drivers and payments."
                                    } else {
                                        "Production mode active. Toggle ON to test end-to-end customer rides without a driver login."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = platformSettings.testRideModeEnabled,
                            onCheckedChange = { adminViewModel.toggleTestRideMode(it) },
                            enabled = isOwnerAdmin,
                            modifier = Modifier.testTag("test_ride_mode_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Total Test Rides: ${testBookings.size}",
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = JetBrainsMono,
                            color = GolocalDeepNavy
                        )
                        TextButton(
                            onClick = { showSettings = !showSettings },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("toggle_test_settings_button")
                        ) {
                            Text(if (showSettings) "Hide Settings" else "Simulation Settings")
                            Icon(
                                imageVector = if (showSettings) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null
                            )
                        }
                    }
                }
            }
        }

        if (showSettings) {
            item {
                TestRideSettingsPanel(
                    settings = platformSettings,
                    isOwnerAdmin = isOwnerAdmin,
                    onSaveSettings = { auto, manual, speed, otpSec, override, autoComp ->
                        adminViewModel.updateTestRideSettings(auto, manual, speed, otpSec, override, autoComp)
                    },
                    onRequestClearCompleted = { showClearConfirmDialog = true }
                )
            }
        }

        // Embedded Live Simulator Preview Card
        item {
            TestRideLiveSimulatorCard(
                booking = focusedBooking,
                tripLocation = focusedBooking?.let { testTripLocations[it.id] },
                simulatingBookingId = simulatingBookingId,
                simulationPhase = simulationPhase,
                onSimulateToPickup = { adminViewModel.startMovementSimulation(it, "to_pickup", actorRoleLabel) },
                onSimulateToDrop = { adminViewModel.startMovementSimulation(it, "to_drop", actorRoleLabel) },
                onStopSimulation = { adminViewModel.stopMovementSimulation() }
            )
        }

        // Filter Chips
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filters.forEach { filter ->
                    val count = testBookings.count { TestRideSeparation.matchesAdminTestFilter(it, filter) }
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text("$filter ($count)") },
                        modifier = Modifier.testTag("test_filter_${filter.lowercase().replace(" ", "_")}")
                    )
                }
            }
        }

        if (filteredBookings.isEmpty()) {
            item {
                GolocalEmptyState(
                    title = "No $selectedFilter Rides",
                    message = if (platformSettings.testRideModeEnabled) {
                        "Place a booking from the Customer app while Test Ride Mode is ACTIVE to simulate the full journey here."
                    } else {
                        "Turn ON Test Ride Mode above, then create a customer booking to test pickup OTP and live movement."
                    }
                )
            }
        } else {
            items(filteredBookings, key = { it.id }) { booking ->
                AdminTestRideCard(
                    booking = booking,
                    otpSecret = testOtpSecrets[booking.id],
                    otpChallenge = testOtpChallenges[booking.id],
                    settings = platformSettings,
                    isOwnerAdmin = isOwnerAdmin,
                    isFocused = focusedBooking?.id == booking.id,
                    isSimulatingThis = simulatingBookingId == booking.id,
                    onFocusMap = { focusedBookingId = booking.id },
                    onAssignDriver = { assignTarget = booking },
                    onAdvanceStatus = { next -> adminViewModel.advanceTestRideStatus(booking, next, actorRole = actorRoleLabel) },
                    onSimulateToPickup = {
                        focusedBookingId = booking.id
                        adminViewModel.startMovementSimulation(booking, "to_pickup", actorRoleLabel)
                    },
                    onSimulateToDrop = {
                        focusedBookingId = booking.id
                        adminViewModel.startMovementSimulation(booking, "to_drop", actorRoleLabel)
                    },
                    onSendOtpToCustomer = { adminViewModel.sendTestOtpToCustomer(booking, actorRoleLabel) },
                    onRequestOtpOverride = { requestOverrideTarget = booking },
                    onOverrideOtp = { overrideTarget = booking },
                    onResetRide = { adminViewModel.resetTestRide(booking, actorRoleLabel) },
                    onCancelRide = { cancelTarget = booking }
                )
            }
        }

        // Test Ride Audit History Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Test Ride Audit History (${testAuditLogs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = { showAuditHistory = !showAuditHistory },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("toggle_test_audit_history")
                ) {
                    Text(if (showAuditHistory) "Hide" else "Show")
                    Icon(
                        imageVector = if (showAuditHistory) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }
            }
        }

        if (showAuditHistory) {
            if (testAuditLogs.isEmpty()) {
                item {
                    GolocalEmptyState(
                        title = "No Test Audit Events Yet",
                        message = "Assignments, OTP verifications, override requests, and completions are recorded here."
                    )
                }
            } else {
                items(testAuditLogs.take(25), key = { it.id }) { log ->
                    TestRideAuditLogItem(log = log)
                }
            }
        }
    }

    assignTarget?.let { target ->
        AssignTestDriverDialog(
            booking = target,
            verifiedPartners = verifiedTestPartners,
            onDismiss = { assignTarget = null },
            onSelectPartner = { partner ->
                focusedBookingId = target.id
                adminViewModel.assignTestPartner(target, partner, notifyCustomer = true, actorRole = actorRoleLabel)
                assignTarget = null
            },
            onSelectSimulated = { driver ->
                focusedBookingId = target.id
                adminViewModel.assignTestDriver(target, driver, notifyCustomer = true, actorRole = actorRoleLabel)
                assignTarget = null
            }
        )
    }

    overrideTarget?.let { target ->
        val pendingReqReason = testOtpChallenges[target.id]?.overrideRequestedReason.orEmpty()
        var reason by remember(target.id, pendingReqReason) { mutableStateOf(pendingReqReason) }
        AlertDialog(
            onDismissRequest = { overrideTarget = null },
            title = { Text("Authorize & Execute OTP Override") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Owner Admin authorization: bypass pickup OTP and start TEST RIDE #${target.id.take(8)}. Logged in audit_logs.")
                    if (pendingReqReason.isNotBlank()) {
                        Text(
                            text = "Ops Staff Request: $pendingReqReason",
                            style = MaterialTheme.typography.labelMedium,
                            color = GolocalPrimaryBlue
                        )
                    }
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Override reason (min 4 chars)") },
                        modifier = Modifier.fillMaxWidth().testTag("override_reason_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        adminViewModel.overrideTestOtpAndStartRide(target, reason, actorRoleLabel)
                        overrideTarget = null
                    },
                    enabled = reason.trim().length >= 4,
                    modifier = Modifier.testTag("confirm_override_otp_button")
                ) { Text("Confirm Override") }
            },
            dismissButton = { TextButton(onClick = { overrideTarget = null }) { Text("Cancel") } }
        )
    }

    requestOverrideTarget?.let { target ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { requestOverrideTarget = null },
            title = { Text("Request Owner OTP Override") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Operations Staff cannot bypass Test OTP directly. Submit an audited override request for TEST RIDE #${target.id.take(8)} to Owner Admin.")
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Request reason (min 4 chars)") },
                        modifier = Modifier.fillMaxWidth().testTag("request_override_reason_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        adminViewModel.requestTestOtpOverride(target, reason, actorRoleLabel)
                        requestOverrideTarget = null
                    },
                    enabled = reason.trim().length >= 4,
                    modifier = Modifier.testTag("submit_override_request_button")
                ) { Text("Submit Request") }
            },
            dismissButton = { TextButton(onClick = { requestOverrideTarget = null }) { Text("Cancel") } }
        )
    }

    cancelTarget?.let { target ->
        var reason by remember { mutableStateOf("Cancelled via Admin Test Control") }
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text("Cancel Test Ride?") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Cancellation reason") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    onClick = {
                        adminViewModel.advanceTestRideStatus(target, "cancelled", reason, actorRoleLabel)
                        cancelTarget = null
                    }
                ) { Text("Cancel Ride") }
            },
            dismissButton = { TextButton(onClick = { cancelTarget = null }) { Text("Keep") } }
        )
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear Completed Test Records?") },
            text = { Text("Deletes all completed and cancelled TEST RIDE bookings, their OTP docs, and simulated locations. Production rides and payments are untouched.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    onClick = {
                        adminViewModel.clearCompletedTestRecords(actorRoleLabel)
                        showClearConfirmDialog = false
                    }
                ) { Text("Delete Test Records") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirmDialog = false }) { Text("Cancel") } }
        )
    }
}
