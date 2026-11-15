package com.example.customer

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Booking
import com.example.domain.WestBengalCatalog
import com.example.ui.components.*
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.CustomerViewModel

@Composable
fun CustomerBookRideScreen(
    sessionViewModel: AppSessionViewModel,
    customerViewModel: CustomerViewModel,
    onBackHome: () -> Unit
) {
    val context = LocalContext.current
    val draft by customerViewModel.draft.collectAsStateWithLifecycle()
    val quote by customerViewModel.currentQuote.collectAsStateWithLifecycle()
    val fareConfigs by customerViewModel.fareConfigs.collectAsStateWithLifecycle()
    val drivers by customerViewModel.verifiedDrivers.collectAsStateWithLifecycle()
    val activeBooking by customerViewModel.activeBooking.collectAsStateWithLifecycle()
    val activeOffers by customerViewModel.activeOffers.collectAsStateWithLifecycle()
    val activeTripLocation by customerViewModel.activeTripLocation.collectAsStateWithLifecycle()
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val gps by sessionViewModel.gpsLocation.collectAsStateWithLifecycle()
    val isSubmitting by customerViewModel.isSubmitting.collectAsStateWithLifecycle()

    var showPickupPicker by remember { mutableStateOf(false) }
    var showDestPicker by remember { mutableStateOf(false) }
    var showSosDialog by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showComplaintDialog by remember { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.any { it }) {
            sessionViewModel.detectDeviceLocation(context)
        }
    }

    LaunchedEffect(gps.label) {
        if (gps.label.isNotBlank()) {
            customerViewModel.setCustomLabels(gps.label, draft.customDestinationLabel)
        }
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconButton(onClick = onBackHome) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Home")
            }
            Text(
                text = if (activeBooking != null) "Live Ride & Fare Proposals" else "Book a Ride",
                style = MaterialTheme.typography.headlineSmall
            )
        }

        LowNetworkBanner(
            isLowNetwork = prefs.lowNetworkMode,
            onToggle = { sessionViewModel.setLowNetworkMode(it) }
        )

        val currentBooking = activeBooking
        if (currentBooking != null) {
            ActiveBookingView(
                booking = currentBooking,
                offers = activeOffers,
                drivers = drivers,
                tripLocation = activeTripLocation,
                lowNetwork = prefs.lowNetworkMode,
                onAcceptOffer = { offer -> customerViewModel.confirmOffer(currentBooking.id, offer) },
                onShareTrip = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "GoLocal Live Trip: ${currentBooking.pickupLabel} -> ${currentBooking.destinationLabel}. Status: ${currentBooking.status}. Driver: ${currentBooking.assignedDriverName} (${currentBooking.assignedVehicleLabel})."
                        )
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share GoLocal Trip"))
                },
                onOpenSos = { showSosDialog = true },
                onOpenComplaint = { showComplaintDialog = true },
                onOpenCancel = { showCancelDialog = true }
            )
        } else {
            InteractiveBengalMapCanvas(
                pickupLat = gps.lat ?: draft.pickupPlace.lat,
                pickupLng = gps.lng ?: draft.pickupPlace.lng,
                pickupLabel = draft.customPickupLabel.ifBlank { draft.pickupPlace.nameEn },
                destLat = draft.destinationPlace.lat,
                destLng = draft.destinationPlace.lng,
                destLabel = draft.customDestinationLabel.ifBlank { draft.destinationPlace.nameEn },
                onlineDrivers = drivers,
                liveTripLocation = null,
                lowNetworkMode = prefs.lowNetworkMode
            )

            // Pickup & Destination Selector Card
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Pickup & Destination", style = MaterialTheme.typography.titleMedium)
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
                            Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (gps.isDetecting) "Detecting..." else "Use GPS")
                        }
                    }

                    gps.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = GolocalError)
                    }

                    OutlinedCard(
                        onClick = { showPickupPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.TripOrigin, contentDescription = null, tint = GolocalSuccess)
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Pickup Point", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    draft.customPickupLabel.ifBlank { "${draft.pickupPlace.nameEn} (${draft.pickupPlace.district})" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(Icons.Filled.EditLocationAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    OutlinedCard(
                        onClick = { showDestPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Destination", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    draft.customDestinationLabel.ifBlank { "${draft.destinationPlace.nameEn} (${draft.destinationPlace.district})" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // Local Service Type Selector
            Text("Service Type", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "now" to "Local Ride",
                    "station_pickup" to "Railway Station",
                    "bus_stand_pickup" to "Bus Stand",
                    "airport_transfer" to "Airport Transfer",
                    "village_to_town" to "Village to Town",
                    "scheduled" to "Schedule Later",
                    "family" to "Family Ride",
                    "return_trip" to "Return Trip"
                ).forEach { (id, label) ->
                    FilterChip(
                        selected = draft.bookingType == id,
                        onClick = { customerViewModel.setBookingType(id) },
                        label = { Text(label) }
                    )
                }
            }

            if (draft.bookingType in setOf("station_pickup", "scheduled", "native_village", "airport_transfer", "bus_stand_pickup")) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (draft.bookingType == "station_pickup") {
                            OutlinedTextField(
                                value = draft.trainNumber,
                                onValueChange = { customerViewModel.updateSpecialFields(trainNumber = it) },
                                label = { Text("Train Number / Name (Optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                        OutlinedTextField(
                            value = draft.scheduledDateTimeLabel,
                            onValueChange = { customerViewModel.updateSpecialFields(scheduledDateTimeLabel = it) },
                            label = { Text("Pickup Date & Time (e.g. Today 06:30 PM)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            if (draft.bookingType == "family") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.familyMemberName,
                            onValueChange = { customerViewModel.updateSpecialFields(familyMemberName = it) },
                            label = { Text("Passenger Name & Phone") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = draft.familyConsentConfirmed,
                                onCheckedChange = { customerViewModel.updateSpecialFields(familyConsentConfirmed = it) }
                            )
                            Text("Passenger consented to receive trip PIN & updates.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Vehicle Categories
            Text("Choose Vehicle", style = MaterialTheme.typography.titleMedium)
            val categoryUi = listOf(
                Triple("toto", "Toto / E-Rickshaw", Icons.Filled.ElectricRickshaw),
                Triple("auto", "Auto Rickshaw", Icons.Filled.LocalTaxi),
                Triple("bike", "Bike Taxi", Icons.Filled.TwoWheeler),
                Triple("cab", "AC Cab", Icons.Filled.DirectionsCar),
                Triple("outstation", "Outstation Cab", Icons.Filled.Commute),
                Triple("scheduled", "Scheduled Cab", Icons.Filled.Schedule),
                Triple("parcel", "Local Parcel", Icons.Filled.Inventory2)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                categoryUi.forEach { (id, title, icon) ->
                    val cfg = fareConfigs.firstOrNull { it.vehicleCategory == id }
                    val enabled = cfg?.isEnabled ?: (id != "parcel")
                    val est = if (cfg != null) {
                        com.example.domain.FareCalculator.calculateFareQuote(
                            quote.distanceKm, cfg, draft.isRoundTrip
                        ).estimatedFareInr
                    } else 0.0
                    VehicleCategoryChip(
                        id = id,
                        title = title,
                        subtitle = if (enabled) "${cfg?.commissionPercent?.toInt() ?: 10}% fee • Verified" else "Admin approval req.",
                        etaOrStatus = if (enabled) "Negotiable" else "Disabled",
                        fareLabel = if (enabled) "₹${est.toInt()}" else "Locked",
                        icon = icon,
                        selected = draft.selectedCategory == id,
                        enabled = enabled,
                        onClick = { customerViewModel.selectCategory(id) }
                    )
                }
            }

            // Fare Quote & Payment Selection
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Estimated Distance", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${quote.distanceKm} km (~${quote.estimatedDurationMin} min)", fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Estimated Fare (incl. GST)", style = MaterialTheme.typography.titleSmall)
                        Text("₹${quote.estimatedFareInr.toInt()}", style = MaterialTheme.typography.titleMedium, fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        text = "Permitted Rider Fare Proposal Band: ₹${quote.minPermittedFareInr.toInt()} – ₹${quote.maxPermittedFareInr.toInt()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider()
                    Text("Payment Method", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = draft.paymentMethod == "cash",
                            onClick = { customerViewModel.setPaymentMethod("cash") },
                            label = { Text("Cash at Drop") },
                            leadingIcon = { Icon(Icons.Filled.Payments, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = draft.paymentMethod == "upi",
                            onClick = { customerViewModel.setPaymentMethod("upi") },
                            label = { Text("UPI (Webhook Pending)") },
                            leadingIcon = { Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                    if (draft.paymentMethod == "upi") {
                        Text(
                            text = "Note: Online UPI gateway webhook requires backend Cloud Function configuration; trip settles as pending_verification.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = { customerViewModel.submitBooking() },
                        enabled = !isSubmitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("submit_booking_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        } else {
                            Text("Request Ride & Receive Fare Proposals")
                        }
                    }
                }
            }
        }
    }

    if (showPickupPicker) {
        WestBengalLocationPickerDialog(
            title = "Select Pickup Location",
            currentCustom = draft.customPickupLabel,
            onDismiss = { showPickupPicker = false },
            onSelectPlace = {
                customerViewModel.setPickupPlace(it)
                showPickupPicker = false
            },
            onSaveCustom = { label ->
                customerViewModel.setCustomLabels(label, draft.customDestinationLabel)
                showPickupPicker = false
            }
        )
    }

    if (showDestPicker) {
        WestBengalLocationPickerDialog(
            title = "Select Destination",
            currentCustom = draft.customDestinationLabel,
            onDismiss = { showDestPicker = false },
            onSelectPlace = {
                customerViewModel.setDestinationPlace(it)
                showDestPicker = false
            },
            onSaveCustom = { label ->
                customerViewModel.setCustomLabels(draft.customPickupLabel, label)
                showDestPicker = false
            }
        )
    }

    if (showSosDialog && activeBooking != null) {
        var notes by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            title = { Text("Trigger Emergency SOS") },
            text = {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Safety note for GoLocal Operations") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    onClick = {
                        customerViewModel.triggerSos(
                            activeBooking!!.id,
                            gps.lat ?: activeBooking!!.pickupLat,
                            gps.lng ?: activeBooking!!.pickupLng,
                            notes
                        )
                        showSosDialog = false
                    }
                ) { Text("Send SOS Alert") }
            },
            dismissButton = { TextButton(onClick = { showSosDialog = false }) { Text("Cancel") } }
        )
    }

    if (showCancelDialog && activeBooking != null) {
        var reason by remember { mutableStateOf("Plan changed") }
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel Ride?") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    onClick = {
                        customerViewModel.cancelBooking(activeBooking!!.id, reason)
                        showCancelDialog = false
                    }
                ) { Text("Confirm Cancel") }
            },
            dismissButton = { TextButton(onClick = { showCancelDialog = false }) { Text("Keep Ride") } }
        )
    }

    if (showComplaintDialog && activeBooking != null) {
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showComplaintDialog = false },
            title = { Text("Report Trip Issue") },
            text = {
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Describe issue") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        customerViewModel.submitComplaint(activeBooking!!.id, "trip_issue", desc)
                        showComplaintDialog = false
                    }
                ) { Text("Submit") }
            },
            dismissButton = { TextButton(onClick = { showComplaintDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ActiveBookingView(
    booking: Booking,
    offers: List<com.example.data.model.RideOffer>,
    drivers: List<com.example.data.model.DriverDirectoryEntry>,
    tripLocation: com.example.data.model.TripLocation?,
    lowNetwork: Boolean,
    onAcceptOffer: (com.example.data.model.RideOffer) -> Unit,
    onShareTrip: () -> Unit,
    onOpenSos: () -> Unit,
    onOpenComplaint: () -> Unit,
    onOpenCancel: () -> Unit
) {
    InteractiveBengalMapCanvas(
        pickupLat = booking.pickupLat,
        pickupLng = booking.pickupLng,
        pickupLabel = booking.pickupLabel,
        destLat = booking.destinationLat,
        destLng = booking.destinationLng,
        destLabel = booking.destinationLabel,
        onlineDrivers = drivers,
        liveTripLocation = tripLocation,
        lowNetworkMode = lowNetwork
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                GolocalStatusBadge(
                    label = booking.status.uppercase(),
                    tone = if (booking.status == "searching") "warning" else "success"
                )
                Text("${booking.vehicleCategory.uppercase()} • ${booking.distanceKm} km", style = MaterialTheme.typography.labelMedium)
            }
            Text("${booking.pickupLabel} → ${booking.destinationLabel}", style = MaterialTheme.typography.titleMedium)

            if (booking.tripPin.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Share 4-Digit Trip PIN at Pickup", style = MaterialTheme.typography.labelMedium)
                            Text("Rider ${booking.assignedDriverName} (${booking.assignedVehicleLabel})", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            text = booking.tripPin,
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = JetBrainsMono,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (booking.agreedFareInr > 0) {
                Text(
                    text = "Locked Fare: ₹${booking.agreedFareInr.toInt()} (${booking.paymentMethod.uppercase()})",
                    style = MaterialTheme.typography.titleSmall,
                    color = GolocalSuccess
                )
            }

            tripLocation?.let { loc ->
                Text(
                    text = "Live Driver GPS: ${String.format("%.4f", loc.lat)}, ${String.format("%.4f", loc.lng)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShareTrip, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share")
                }
                Button(
                    onClick = onOpenSos,
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SOS")
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenComplaint, modifier = Modifier.weight(1f)) {
                    Text("Report Issue")
                }
                if (booking.status != "in_progress") {
                    TextButton(onClick = onOpenCancel, modifier = Modifier.weight(1f)) {
                        Text("Cancel Ride", color = GolocalError)
                    }
                }
            }
        }
    }

    if (booking.status == "searching") {
        Text("Fare Proposals from Verified Riders (${offers.size})", style = MaterialTheme.typography.titleMedium)
        if (offers.isEmpty()) {
            GolocalEmptyState(
                title = "Waiting for Rider Fare Proposals",
                message = "Permitted band: ₹${booking.minPermittedFareInr.toInt()} – ₹${booking.maxPermittedFareInr.toInt()}. Verified drivers nearby will send proposals shortly."
            )
        } else {
            offers.forEach { offer ->
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(offer.driverName, style = MaterialTheme.typography.titleSmall)
                            Text("${offer.vehicleLabel} • ETA ${offer.etaMinutes} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("★ ${String.format("%.1f", offer.driverRating)} • ${offer.driverCompletedRides} rides", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("₹${offer.proposedFareInr.toInt()}", style = MaterialTheme.typography.titleLarge, fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.primary)
                            Button(onClick = { onAcceptOffer(offer) }) {
                                Text("Accept")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WestBengalLocationPickerDialog(
    title: String,
    currentCustom: String,
    onDismiss: () -> Unit,
    onSelectPlace: (com.example.domain.WestBengalPlace) -> Unit,
    onSaveCustom: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var customText by remember { mutableStateOf(currentCustom) }
    val filtered = remember(query) { WestBengalCatalog.search(query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search station, bus stand, airport, village...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it },
                    label = { Text("Or enter custom landmark / para name") },
                    singleLine = true,
                    trailingIcon = {
                        if (customText.isNotBlank()) {
                            TextButton(onClick = { onSaveCustom(customText) }) { Text("Use") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(filtered, key = { it.id }) { place ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectPlace(place) }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(place.nameEn, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${place.nameBn} • ${place.district} • ${place.category.replace('_', ' ')}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
