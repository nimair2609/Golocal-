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
import com.example.data.map.GeocodedPlace
import com.example.data.map.RouteResult
import com.example.data.model.Booking
import com.example.data.model.GeoPointDto
import com.example.data.model.LiveDriverRtdbLocation
import com.example.data.tracking.LiveLocationTracker
import com.example.domain.BoraiLaunchConfig
import com.example.ui.components.*
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.CustomerViewModel
import com.example.viewmodel.GovernanceViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CustomerBookRideScreen(
    sessionViewModel: AppSessionViewModel,
    customerViewModel: CustomerViewModel,
    governanceViewModel: GovernanceViewModel? = null,
    onBackHome: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(context) {
        customerViewModel.attachContext(context)
    }

    val draft by customerViewModel.draft.collectAsStateWithLifecycle()
    val quote by customerViewModel.currentQuote.collectAsStateWithLifecycle()
    val fareConfigs by customerViewModel.fareConfigs.collectAsStateWithLifecycle()
    val drivers by customerViewModel.verifiedDrivers.collectAsStateWithLifecycle()
    val activeBooking by customerViewModel.activeBooking.collectAsStateWithLifecycle()
    val activeOffers by customerViewModel.activeOffers.collectAsStateWithLifecycle()
    val activeLiveTracking by customerViewModel.activeLiveTracking.collectAsStateWithLifecycle()
    val activeBookingRoute by customerViewModel.activeBookingRoute.collectAsStateWithLifecycle()
    val routeResult by customerViewModel.routeResult.collectAsStateWithLifecycle()
    val serviceAreas by customerViewModel.serviceAreas.collectAsStateWithLifecycle()
    val coverageEvaluation by customerViewModel.coverageEvaluation.collectAsStateWithLifecycle()
    val searchResults by customerViewModel.searchResults.collectAsStateWithLifecycle()
    val isSearchingPlaces by customerViewModel.isSearchingPlaces.collectAsStateWithLifecycle()
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val accountState by sessionViewModel.userAccountState.collectAsStateWithLifecycle()
    val gps by sessionViewModel.gpsLocation.collectAsStateWithLifecycle()
    val isSubmitting by customerViewModel.isSubmitting.collectAsStateWithLifecycle()
    val customerInvoiceEmail = (accountState as? com.example.data.model.UiState.Success)?.data?.email.orEmpty()

    var hasLocationPermission by remember(context) {
        mutableStateOf(LiveLocationTracker.hasLocationPermission(context))
    }
    var pinMode by remember { mutableStateOf("none") } // "none", "pickup", "destination"
    var showPickupPicker by remember { mutableStateOf(false) }
    var showDestPicker by remember { mutableStateOf(false) }
    var showSosDialog by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showComplaintDialog by remember { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.values.any { it }
        hasLocationPermission = granted
        if (granted) {
            sessionViewModel.detectDeviceLocation(context)
        } else {
            pinMode = "pickup"
        }
    }

    LaunchedEffect(gps.lat, gps.lng, gps.label) {
        val lat = gps.lat
        val lng = gps.lng
        if (lat != null && lng != null) {
            customerViewModel.setPickupCoordinates(
                lat = lat,
                lng = lng,
                explicitLabel = gps.label.takeIf { it.isNotBlank() },
                confirmImmediately = true
            )
        }
    }

    val zoneOverlays = remember(serviceAreas) {
        serviceAreas.map { it.toMapZoneOverlay() }
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
                text = if (activeBooking != null) "Live Ride & OSM Tracking" else "Book a Ride",
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
                liveTracking = activeLiveTracking,
                route = activeBookingRoute,
                zones = zoneOverlays,
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
            if (!hasLocationPermission || gps.error != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = gps.error ?: "Location permission not granted. Map defaults to ${BoraiLaunchConfig.DEFAULT_LOCALITY} (PIN ${BoraiLaunchConfig.DEFAULT_PINCODE}) — tap to pin or search any village.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    locationLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            ) {
                                Text("Enable GPS")
                            }
                            TextButton(onClick = { pinMode = "pickup" }) {
                                Text("Pin Pickup Manually")
                            }
                        }
                    }
                }
            }

            GoLocalOsmMapCard(
                centerLat = draft.pickupLat,
                centerLng = draft.pickupLng,
                pickupPoint = GeoPointDto(draft.pickupLat, draft.pickupLng),
                pickupLabel = draft.customPickupLabel.ifBlank { draft.pickupPlace.nameEn },
                destinationPoint = GeoPointDto(draft.destLat, draft.destLng),
                destinationLabel = draft.customDestinationLabel.ifBlank { draft.destinationPlace.nameEn },
                onlineDrivers = drivers,
                routePoints = routeResult?.points.orEmpty(),
                zones = zoneOverlays,
                lowNetworkMode = prefs.lowNetworkMode,
                interactivePinMode = pinMode,
                onMapTap = { lat, lng ->
                    when (pinMode) {
                        "pickup" -> customerViewModel.setPickupCoordinates(lat, lng)
                        "destination" -> customerViewModel.setDestinationCoordinates(lat, lng)
                    }
                },
                onDragPickup = { lat, lng -> customerViewModel.setPickupCoordinates(lat, lng) },
                onDragDestination = { lat, lng -> customerViewModel.setDestinationCoordinates(lat, lng) },
                height = if (prefs.lowNetworkMode) 170.dp else 255.dp
            )

            // Interactive Map Pinning & Marker Drag Mode Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = pinMode == "pickup",
                    onClick = { pinMode = if (pinMode == "pickup") "none" else "pickup" },
                    label = { Text("Drag / Pin Pickup") },
                    leadingIcon = { Icon(Icons.Filled.TripOrigin, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = pinMode == "destination",
                    onClick = { pinMode = if (pinMode == "destination") "none" else "destination" },
                    label = { Text("Drag / Pin Drop") },
                    leadingIcon = { Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                if (pinMode != "none") {
                    AssistChip(
                        onClick = { pinMode = "none" },
                        label = { Text("Done Pinning") },
                        leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            // Pickup & Destination Selector & Confirmation Card
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
                            Text(if (gps.isDetecting) "Detecting..." else "Current GPS")
                        }
                    }

                    OutlinedCard(
                        onClick = {
                            customerViewModel.searchPlaces("", allowRemote = false)
                            showPickupPicker = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Filled.TripOrigin, contentDescription = null, tint = GolocalSuccess)
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Pickup Point", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    draft.customPickupLabel.ifBlank { "${draft.pickupPlace.nameEn} (${draft.pickupDistrict})" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = String.format(Locale.US, "%.4f, %.4f", draft.pickupLat, draft.pickupLng),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMono,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilterChip(
                                selected = draft.pickupConfirmed,
                                onClick = { customerViewModel.confirmPickup(!draft.pickupConfirmed) },
                                label = { Text(if (draft.pickupConfirmed) "Confirmed" else "Confirm") }
                            )
                        }
                    }

                    OutlinedCard(
                        onClick = {
                            customerViewModel.searchPlaces("", allowRemote = false)
                            showDestPicker = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Destination", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    draft.customDestinationLabel.ifBlank { "${draft.destinationPlace.nameEn} (${draft.destinationDistrict})" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = String.format(Locale.US, "%.4f, %.4f", draft.destLat, draft.destLng),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMono,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilterChip(
                                selected = draft.destinationConfirmed,
                                onClick = { customerViewModel.confirmDestination(!draft.destinationConfirmed) },
                                label = { Text(if (draft.destinationConfirmed) "Confirmed" else "Confirm") }
                            )
                        }
                    }
                }
            }

            // Road Route & Service Zone Coverage Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (coverageEvaluation.isAllowed) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    GolocalError.copy(alpha = 0.12f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (coverageEvaluation.isAllowed) MaterialTheme.colorScheme.outline else GolocalError,
                        RoundedCornerShape(16.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (routeResult?.provider == "osrm") "OSRM Road Route" else "Route Estimate",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${quote.distanceKm} km • ${quote.estimatedDurationMin} min",
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = JetBrainsMono
                        )
                    }
                    routeResult?.warningMessage?.let { warn ->
                        Text(warn, style = MaterialTheme.typography.labelSmall, color = GolocalWarning)
                    }
                    Text(
                        text = if (coverageEvaluation.isAllowed) {
                            coverageEvaluation.summaryNote
                        } else {
                            coverageEvaluation.blockingReason
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (coverageEvaluation.isAllowed) MaterialTheme.colorScheme.onSurfaceVariant else GolocalError
                    )
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
                        Text("Road Distance & ETA", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        onClick = { customerViewModel.submitBooking(invoiceEmail = customerInvoiceEmail) },
                        enabled = !isSubmitting && coverageEvaluation.isAllowed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("submit_booking_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        } else {
                            Text("Confirm Route & Request Ride")
                        }
                    }
                }
            }
        }
    }

    if (showPickupPicker) {
        OsmPlaceSearchPickerDialog(
            title = "Select Pickup Village or Stand",
            currentCustom = draft.customPickupLabel,
            results = searchResults,
            isSearching = isSearchingPlaces,
            onQueryChanged = { customerViewModel.searchPlaces(it, allowRemote = false) },
            onSubmitSearch = { customerViewModel.searchPlaces(it, allowRemote = true) },
            onDismiss = { showPickupPicker = false },
            onSelectPlace = {
                customerViewModel.selectGeocodedPlace(it, isPickup = true)
                showPickupPicker = false
            },
            onSaveCustom = { label ->
                customerViewModel.setCustomLabels(label, draft.customDestinationLabel)
                customerViewModel.confirmPickup(true)
                showPickupPicker = false
            }
        )
    }

    if (showDestPicker) {
        OsmPlaceSearchPickerDialog(
            title = "Search Destination Village, Station, or Town",
            currentCustom = draft.customDestinationLabel,
            results = searchResults,
            isSearching = isSearchingPlaces,
            onQueryChanged = { customerViewModel.searchPlaces(it, allowRemote = false) },
            onSubmitSearch = { customerViewModel.searchPlaces(it, allowRemote = true) },
            onDismiss = { showDestPicker = false },
            onSelectPlace = {
                customerViewModel.selectGeocodedPlace(it, isPickup = false)
                showDestPicker = false
            },
            onSaveCustom = { label ->
                customerViewModel.setCustomLabels(draft.customPickupLabel, label)
                customerViewModel.confirmDestination(true)
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
                            activeLiveTracking?.lat ?: gps.lat ?: activeBooking!!.pickupLat,
                            activeLiveTracking?.lng ?: gps.lng ?: activeBooking!!.pickupLng,
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
        val booking = activeBooking!!
        val cfg = fareConfigs.firstOrNull { it.vehicleCategory == booking.vehicleCategory }
        if (governanceViewModel != null) {
            StructuredCancellationDialog(
                booking = booking,
                actorRole = "customer",
                fareConfig = cfg,
                onDismiss = { showCancelDialog = false },
                onConfirmCancel = { code, label ->
                    governanceViewModel.cancelBookingStructured(
                        bookingId = booking.id,
                        actorRole = "customer",
                        reasonCode = code,
                        reasonLabel = label
                    )
                    showCancelDialog = false
                }
            )
        } else {
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
                            customerViewModel.cancelBooking(booking.id, reason)
                            showCancelDialog = false
                        }
                    ) { Text("Confirm Cancel") }
                },
                dismissButton = { TextButton(onClick = { showCancelDialog = false }) { Text("Keep Ride") } }
            )
        }
    }

    if (showComplaintDialog && activeBooking != null) {
        val booking = activeBooking!!
        if (governanceViewModel != null) {
            SubmitDisputeDialog(
                bookingId = booking.id,
                defaultRefundInr = booking.agreedFareInr.takeIf { it > 0.0 } ?: booking.estimatedFareInr,
                onDismiss = { showComplaintDialog = false },
                onSubmit = { cat, desc, refund ->
                    governanceViewModel.submitDispute(
                        bookingId = booking.id,
                        targetUserId = booking.assignedDriverId,
                        category = cat,
                        description = desc,
                        requestedRefundInr = refund
                    )
                    showComplaintDialog = false
                }
            )
        } else {
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
                            customerViewModel.submitComplaint(
                                bookingId = booking.id,
                                category = "trip_issue",
                                description = desc,
                                lat = activeLiveTracking?.lat ?: booking.pickupLat,
                                lng = activeLiveTracking?.lng ?: booking.pickupLng,
                                locationLabel = "${booking.pickupLabel} -> ${booking.destinationLabel}"
                            )
                            showComplaintDialog = false
                        }
                    ) { Text("Submit") }
                },
                dismissButton = { TextButton(onClick = { showComplaintDialog = false }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun ActiveBookingView(
    booking: Booking,
    offers: List<com.example.data.model.RideOffer>,
    drivers: List<com.example.data.model.DriverDirectoryEntry>,
    liveTracking: LiveDriverRtdbLocation?,
    route: RouteResult?,
    zones: List<MapZoneOverlay>,
    lowNetwork: Boolean,
    onAcceptOffer: (com.example.data.model.RideOffer) -> Unit,
    onShareTrip: () -> Unit,
    onOpenSos: () -> Unit,
    onOpenComplaint: () -> Unit,
    onOpenCancel: () -> Unit
) {
    GoLocalOsmMapCard(
        centerLat = liveTracking?.lat ?: booking.pickupLat,
        centerLng = liveTracking?.lng ?: booking.pickupLng,
        pickupPoint = GeoPointDto(booking.pickupLat, booking.pickupLng),
        pickupLabel = booking.pickupLabel,
        destinationPoint = GeoPointDto(booking.destinationLat, booking.destinationLng),
        destinationLabel = booking.destinationLabel,
        driverLivePoint = liveTracking?.let { GeoPointDto(it.lat, it.lng) },
        onlineDrivers = drivers,
        routePoints = route?.points.orEmpty(),
        zones = zones,
        lowNetworkMode = lowNetwork,
        statusBadgeText = if (liveTracking?.trackingStatus == "active") "Live Partner GPS" else "Booking Route (OSM)",
        height = if (lowNetwork) 170.dp else 245.dp
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
                Text("${booking.vehicleCategory.uppercase()} • ${booking.distanceKm} km (~${booking.estimatedDurationMin} min)", style = MaterialTheme.typography.labelMedium)
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

            liveTracking?.let { loc ->
                val timeFmt = remember { SimpleDateFormat("hh:mm:ss a", Locale.US) }
                val updatedAt = if (loc.updatedAtEpochMs > 0L) timeFmt.format(Date(loc.updatedAtEpochMs)) else "Live"
                val (badgeLabel, badgeTone) = when (loc.trackingStatus) {
                    "active" -> "LIVE PARTNER GPS ($updatedAt)" to "success"
                    "paused_gps_lost" -> "PARTNER GPS SIGNAL LOST — LAST KNOWN" to "warning"
                    else -> "LIVE SHARING STOPPED" to "info"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GolocalStatusBadge(label = badgeLabel, tone = badgeTone)
                    Text(
                        text = String.format(Locale.US, "%.4f, %.4f", loc.lat, loc.lng),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
private fun OsmPlaceSearchPickerDialog(
    title: String,
    currentCustom: String,
    results: List<GeocodedPlace>,
    isSearching: Boolean,
    onQueryChanged: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onDismiss: () -> Unit,
    onSelectPlace: (GeocodedPlace) -> Unit,
    onSaveCustom: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var customText by remember { mutableStateOf(currentCustom) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onQueryChanged(it)
                    },
                    label = { Text("Filter Borai, Mohanpur, villages, stations...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Instant local search • Explicit OSM search",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        enabled = query.trim().length >= 3 && !isSearching,
                        onClick = { onSubmitSearch(query) }
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Search OSM")
                        }
                    }
                }
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
                    items(results, key = { it.id }) { place ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectPlace(place) }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        place.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = when (place.source) {
                                            "service_zone" -> "ZONE"
                                            "osm_nominatim" -> "OSM"
                                            else -> "LOCAL"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    "${place.secondaryText} (${String.format(Locale.US, "%.4f, %.4f", place.lat, place.lng)})",
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
