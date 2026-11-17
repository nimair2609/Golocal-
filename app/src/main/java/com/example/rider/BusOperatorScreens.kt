package com.example.rider

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.DriverDocumentItem
import com.example.domain.BusFareEngine
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.components.GolocalStatusMessageBanner
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.BusBookingViewModel
import com.example.viewmodel.GovernanceViewModel
import kotlinx.coroutines.launch

@Composable
fun RiderBusOperatorPortalTab(
    defaultPhone: String,
    defaultDistrict: String,
    busViewModel: BusBookingViewModel,
    governanceViewModel: GovernanceViewModel
) {
    val scope = rememberCoroutineScope()
    val operatorProfile by busViewModel.myOperatorProfile.collectAsStateWithLifecycle()
    val vehicles by busViewModel.busVehicles.collectAsStateWithLifecycle()
    val routes by busViewModel.busRoutes.collectAsStateWithLifecycle()
    val schedules by busViewModel.busSchedules.collectAsStateWithLifecycle()
    val bookings by busViewModel.myBusBookings.collectAsStateWithLifecycle()
    val validatedTicket by busViewModel.lastValidatedTicket.collectAsStateWithLifecycle()
    val message by busViewModel.message.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Private Bus Operator Hub", style = MaterialTheme.typography.headlineSmall)

        GolocalStatusMessageBanner(
            message = message,
            onDismiss = { busViewModel.clearMessage() }
        )

        val operator = operatorProfile
        if (operator == null || operator.verificationStatus != "verified") {
            var companyName by remember(operator?.companyName) { mutableStateOf(operator?.companyName ?: "") }
            var contactPhone by remember(operator?.contactPhone, defaultPhone) { mutableStateOf(operator?.contactPhone?.ifBlank { defaultPhone } ?: defaultPhone) }
            var licenseNo by remember(operator?.operatorLicenseNumber) { mutableStateOf(operator?.operatorLicenseNumber ?: "") }
            var permitNo by remember(operator?.permitNumber) { mutableStateOf(operator?.permitNumber ?: "") }
            var district by remember(operator?.district, defaultDistrict) { mutableStateOf(operator?.district?.ifBlank { defaultDistrict } ?: defaultDistrict) }
            var upiRef by remember(operator?.upiOrBankRef) { mutableStateOf(operator?.upiOrBankRef ?: "") }
            var permitUrl by remember(operator?.documents) { mutableStateOf(operator?.documents?.firstOrNull()?.storageUrl ?: "") }
            var uploading by remember { mutableStateOf(false) }

            val permitPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                if (uri != null) {
                    uploading = true
                    scope.launch {
                        governanceViewModel.uploadDocumentPhoto("bus_docs", "OPERATOR_PERMIT", uri)
                            .onSuccess { permitUrl = it }
                        uploading = false
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (operator != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().border(1.5.dp, GolocalWarning, RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            GolocalStatusBadge(label = "OPERATOR KYC: ${operator.verificationStatus.uppercase()}", tone = if (operator.verificationStatus == "pending") "warning" else "error")
                            Text(
                                if (operator.verificationStatus == "pending") "Awaiting GoLocal Admin review of your operator licence & stage carriage permit."
                                else "Rejected: ${operator.rejectionReason}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (operator == null || operator.verificationStatus == "rejected") {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Onboard as Private Bus Operator", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(value = companyName, onValueChange = { companyName = it }, label = { Text("Transport Company / Operator Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = contactPhone, onValueChange = { contactPhone = it }, label = { Text("Operations Contact Phone *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = licenseNo, onValueChange = { licenseNo = it }, label = { Text("Operator Trade / RTO Licence No. *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = permitNo, onValueChange = { permitNo = it }, label = { Text("Stage Carriage / Contract Permit No. *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = district, onValueChange = { district = it }, label = { Text("Primary District *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = upiRef, onValueChange = { upiRef = it }, label = { Text("Settlement Bank / UPI") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                            OutlinedButton(
                                onClick = { permitPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                enabled = !uploading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    when {
                                        uploading -> "Uploading Operator Permit to Firebase Storage..."
                                        permitUrl.isNotBlank() -> "Permit Photo Uploaded ✓"
                                        else -> "Upload Operator Permit Photo (Photo Picker)"
                                    }
                                )
                            }

                            Button(
                                onClick = {
                                    val docs = listOf(
                                        DriverDocumentItem(
                                            docType = "COMMERCIAL_PERMIT",
                                            label = "Bus Operator Permit",
                                            documentNumber = permitNo,
                                            storageUrl = permitUrl,
                                            verificationStatus = "pending"
                                        )
                                    )
                                    busViewModel.submitOperatorProfile(companyName, contactPhone, licenseNo, permitNo, district, upiRef, docs)
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("submit_bus_operator_button")
                            ) {
                                Text("Submit Operator Verification")
                            }
                        }
                    }
                }
            }
            return
        }

        var subTab by remember { mutableIntStateOf(0) }
        ScrollableTabRow(selectedTabIndex = subTab, edgePadding = 0.dp) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Fleet (${vehicles.size})") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Routes & Fare (${routes.size})") })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text("Schedules (${schedules.size})") })
            Tab(selected = subTab == 3, onClick = { subTab = 3 }, text = { Text("QR Check-In") })
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (subTab) {
                0 -> {
                    var busName by remember { mutableStateOf("") }
                    var regNo by remember { mutableStateOf("") }
                    var busType by remember { mutableStateOf("AC Seater") }
                    var seats by remember { mutableStateOf("36") }
                    var docUrl by remember { mutableStateOf("") }
                    var uploading by remember { mutableStateOf(false) }

                    val picker = rememberLauncherForActivityResult(
                        ActivityResultContracts.PickVisualMedia()
                    ) { uri ->
                        if (uri != null) {
                            uploading = true
                            scope.launch {
                                governanceViewModel.uploadDocumentPhoto("bus_docs", "BUS_RC", uri)
                                    .onSuccess { docUrl = it }
                                uploading = false
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Register Verified Bus Vehicle", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(value = busName, onValueChange = { busName = it }, label = { Text("Bus Name / Coach Service *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = regNo, onValueChange = { regNo = it }, label = { Text("Reg No. (WB...) *") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = seats, onValueChange = { seats = it.filter(Char::isDigit).take(2) }, label = { Text("Seats *") }, singleLine = true, modifier = Modifier.weight(0.6f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("AC Seater", "Non-AC Seater", "Mini Bus").forEach { t ->
                                    FilterChip(selected = busType == t, onClick = { busType = t }, label = { Text(t) })
                                }
                            }
                            OutlinedButton(
                                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                enabled = !uploading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (docUrl.isBlank()) "Attach Bus RC / Fitness Photo" else "Bus Document Uploaded ✓")
                            }
                            Button(
                                onClick = {
                                    busViewModel.registerVehicle(
                                        busName = busName,
                                        regNo = regNo,
                                        busType = busType,
                                        totalSeats = seats.toIntOrNull() ?: 36,
                                        docs = listOf(
                                            DriverDocumentItem(
                                                docType = "RC",
                                                label = "Bus RC & Fitness",
                                                documentNumber = regNo,
                                                storageUrl = docUrl
                                            )
                                        )
                                    )
                                    busName = ""
                                    regNo = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Submit Bus for KYC Approval") }
                        }
                    }

                    vehicles.forEach { v ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                        ) {
                            Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${v.busName} (${v.registrationNumber})", style = MaterialTheme.typography.titleSmall)
                                    Text("${v.busType} • ${v.totalSeats} seats", style = MaterialTheme.typography.bodySmall)
                                    if (v.rejectionReason.isNotBlank()) {
                                        Text(v.rejectionReason, style = MaterialTheme.typography.labelSmall, color = GolocalError)
                                    }
                                }
                                GolocalStatusBadge(
                                    label = v.verificationStatus.uppercase(),
                                    tone = when (v.verificationStatus) {
                                        "verified" -> "success"
                                        "pending" -> "warning"
                                        else -> "error"
                                    }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    var origin by remember { mutableStateOf("Arambagh") }
                    var dest by remember { mutableStateOf("Kolkata Esplanade") }
                    var stopsCsv by remember { mutableStateOf("Arambagh, Tarakeswar, Singur, Dankuni, Kolkata Esplanade") }
                    var distKm by remember { mutableStateOf("84") }
                    var farePerSeat by remember { mutableStateOf("145") }
                    var fareSource by remember { mutableStateOf("government_gazette") }
                    var authorityRef by remember { mutableStateOf("") }
                    var effectiveDate by remember { mutableStateOf("2026-01-01") }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Submit Route & Tariff for Admin Approval", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = origin, onValueChange = { origin = it }, label = { Text("Origin Town *") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = dest, onValueChange = { dest = it }, label = { Text("Destination *") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                            OutlinedTextField(value = stopsCsv, onValueChange = { stopsCsv = it }, label = { Text("Stops (comma-separated)") }, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = distKm, onValueChange = { distKm = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Distance (km)") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = farePerSeat, onValueChange = { farePerSeat = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Fare/Seat (₹) *") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                            Text("Tariff Reference Source", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    "government_gazette" to "Govt Gazette",
                                    "rto_tariff" to "RTO Tariff",
                                    "operator_submitted" to "Operator"
                                ).forEach { (id, lbl) ->
                                    FilterChip(selected = fareSource == id, onClick = { fareSource = id }, label = { Text(lbl) })
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = authorityRef, onValueChange = { authorityRef = it }, label = { Text("Gazette / Permit Ref *") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = effectiveDate, onValueChange = { effectiveDate = it.take(10) }, label = { Text("Eff. YYYY-MM-DD *") }, singleLine = true, modifier = Modifier.weight(0.9f))
                            }
                            Button(
                                onClick = {
                                    busViewModel.submitBusRoute(
                                        originTown = origin,
                                        destinationTown = dest,
                                        stopsCsv = stopsCsv,
                                        distanceKm = distKm.toDoubleOrNull() ?: 10.0,
                                        farePerSeatInr = farePerSeat.toDoubleOrNull() ?: 0.0,
                                        fareSource = fareSource,
                                        authorityRef = authorityRef,
                                        effectiveDateIso = effectiveDate
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Submit Route & Tariff") }
                        }
                    }

                    routes.forEach { r ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(r.routeName, style = MaterialTheme.typography.titleSmall)
                                    GolocalStatusBadge(
                                        label = r.fareApprovalStatus.replace('_', ' ').uppercase(),
                                        tone = when (r.fareApprovalStatus) {
                                            "approved" -> "success"
                                            "rejected" -> "error"
                                            else -> "warning"
                                        }
                                    )
                                }
                                Text("₹${r.farePerSeatInr.toInt()}/seat • ${r.distanceKm} km • ${r.fareSource} (${r.authorityRef})", style = MaterialTheme.typography.bodySmall)
                                if (r.fareRejectionReason.isNotBlank()) {
                                    Text(r.fareRejectionReason, style = MaterialTheme.typography.labelSmall, color = GolocalError)
                                }
                            }
                        }
                    }
                }

                2 -> {
                    val verifiedBuses = vehicles.filter { it.verificationStatus == "verified" }
                    val approvedRoutes = routes.filter { it.fareApprovalStatus == "approved" }
                    if (verifiedBuses.isEmpty() || approvedRoutes.isEmpty()) {
                        GolocalEmptyState(
                            title = "Requires Verified Bus & Approved Route Fare",
                            message = "Register at least 1 Admin-verified bus and 1 Admin-approved route tariff before publishing live schedules."
                        )
                    } else {
                        var pickedBusIdx by remember { mutableIntStateOf(0) }
                        var pickedRouteIdx by remember { mutableIntStateOf(0) }
                        var depDate by remember { mutableStateOf("2026-06-01") }
                        var depTime by remember { mutableStateOf("07:30 AM") }
                        var arrTime by remember { mutableStateOf("10:15 AM") }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Publish Bus Departure Schedule", style = MaterialTheme.typography.titleSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    verifiedBuses.forEachIndexed { idx, v ->
                                        FilterChip(selected = pickedBusIdx == idx, onClick = { pickedBusIdx = idx }, label = { Text(v.busName) })
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    approvedRoutes.forEachIndexed { idx, r ->
                                        FilterChip(selected = pickedRouteIdx == idx, onClick = { pickedRouteIdx = idx }, label = { Text(r.routeName) })
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = depDate, onValueChange = { depDate = it.take(10) }, label = { Text("Date YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f))
                                    OutlinedTextField(value = depTime, onValueChange = { depTime = it }, label = { Text("Dep Time") }, singleLine = true, modifier = Modifier.weight(0.7f))
                                    OutlinedTextField(value = arrTime, onValueChange = { arrTime = it }, label = { Text("Arr Time") }, singleLine = true, modifier = Modifier.weight(0.7f))
                                }
                                Button(
                                    onClick = {
                                        val b = verifiedBuses.getOrNull(pickedBusIdx)
                                        val r = approvedRoutes.getOrNull(pickedRouteIdx)
                                        if (b != null && r != null) {
                                            busViewModel.publishBusSchedule(r, b, depDate, depTime, arrTime)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Publish Departure") }
                            }
                        }
                    }

                    schedules.forEach { s ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(s.routeLabel, style = MaterialTheme.typography.titleSmall)
                                    GolocalStatusBadge(
                                        label = s.fareApprovalStatus.replace('_', ' ').uppercase(),
                                        tone = if (s.fareApprovalStatus == "approved") "success" else "warning"
                                    )
                                }
                                Text("${s.busName} • ${s.departureDateIso} ${s.departureTimeLabel} • Booked ${s.bookedSeatIds.size}/${s.totalSeats}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Text("Booked Passengers (${bookings.size})", style = MaterialTheme.typography.titleMedium)
                    bookings.forEach { bk ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("${bk.customerName} • Seats ${bk.seatIds.joinToString(", ")}", style = MaterialTheme.typography.titleSmall)
                                Text("${bk.boardingStop} → ${bk.dropStop} • ${bk.status.uppercase()}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                3 -> {
                    var tokenInput by remember { mutableStateOf("") }
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Verify & Board Passenger QR Ticket", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Enter the QR token from the passenger's e-ticket (e.g. GLBUS-XXXXXXXX) to verify booking authenticity and mark boarded.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = tokenInput,
                                onValueChange = { tokenInput = it.uppercase().take(48) },
                                label = { Text("QR Ticket Token or Booking ID *") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("bus_qr_validate_input")
                            )
                            Button(
                                onClick = { busViewModel.validateQrTicket(tokenInput) },
                                enabled = tokenInput.trim().length >= 5,
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Verify & Board Passenger")
                            }

                            validatedTicket?.let { t ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = GolocalSuccess.copy(alpha = 0.12f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("BOARDING VERIFIED ✓", style = MaterialTheme.typography.labelLarge, color = GolocalSuccess)
                                        Text("${t.customerName} • Seats ${t.seatIds.joinToString(", ")}", style = MaterialTheme.typography.titleSmall)
                                        Text("${t.boardingStop} → ${t.dropStop} • Token ${t.qrToken}", style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMono)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
