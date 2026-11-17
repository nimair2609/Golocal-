package com.example.admin

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Booking
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.GeoPointDto
import com.example.data.model.PortalRole
import com.example.data.model.ServiceArea
import com.example.domain.BoraiLaunchConfig
import com.example.domain.FareCalculator
import com.example.ui.components.GoLocalOsmMapCard
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalMetricCard
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.components.MapMarkerPin
import com.example.ui.components.toMapZoneOverlay
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AdminViewModel
import java.util.Locale

@Composable
fun AdminLiveOperationsMapModule(
    adminViewModel: AdminViewModel,
    portalRole: PortalRole
) {
    val bookings by adminViewModel.bookings.collectAsStateWithLifecycle()
    val driverDirectory by adminViewModel.driverDirectoryEntries.collectAsStateWithLifecycle()
    val liveRtdb by adminViewModel.liveDriversRtdb.collectAsStateWithLifecycle()
    val tripLocations by adminViewModel.tripLocations.collectAsStateWithLifecycle()
    val serviceAreas by adminViewModel.serviceAreas.collectAsStateWithLifecycle()
    val complaints by adminViewModel.complaints.collectAsStateWithLifecycle()
    val emergencies by adminViewModel.emergencies.collectAsStateWithLifecycle()

    var statusFilter by remember { mutableStateOf("active") } // all, active, unassigned, delayed, alerts
    var categoryFilter by remember { mutableStateOf("all") }
    var districtFilter by remember { mutableStateOf("all") }
    var assignTargetBooking by remember { mutableStateOf<Booking?>(null) }

    val nowEpochSec = System.currentTimeMillis() / 1000L

    // Merge RTDB sub-second coordinates into verified driver directory entries (real data only)
    val mergedDrivers = remember(driverDirectory, liveRtdb) {
        val rtdbById = liveRtdb.associateBy { it.driverId }
        val base = driverDirectory.map { entry ->
            val live = rtdbById[entry.driverId]
            if (live != null && live.trackingStatus != "stopped") {
                entry.copy(
                    isOnline = true,
                    currentLat = live.lat,
                    currentLng = live.lng
                )
            } else {
                entry
            }
        }
        val baseIds = base.map { it.driverId }.toSet()
        val extraRtdb = liveRtdb.filter { it.driverId !in baseIds && it.trackingStatus == "active" }.map { live ->
            DriverDirectoryEntry(
                driverId = live.driverId,
                displayName = "Partner ${live.driverId.take(6)}",
                isOnline = true,
                currentLat = live.lat,
                currentLng = live.lng,
                verificationStatus = "verified"
            )
        }
        base + extraRtdb
    }

    val districts = remember(bookings, serviceAreas) {
        (bookings.map { it.pickupDistrict } + serviceAreas.map { it.district })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    val activeStates = setOf("searching", "assigned", "arriving", "arrived", "in_progress")
    val delayedBookings = remember(bookings, nowEpochSec) {
        bookings.filter { b ->
            b.status in setOf("searching", "assigned") &&
                (b.createdAt?.seconds ?: nowEpochSec).let { created ->
                    (nowEpochSec - created) >= 600L // >= 10 minutes waiting
                }
        }
    }
    val delayedIds = remember(delayedBookings) { delayedBookings.map { it.id }.toSet() }

    val filteredBookings = remember(bookings, statusFilter, categoryFilter, districtFilter, delayedIds) {
        bookings.filter { b ->
            val matchStatus = when (statusFilter) {
                "active" -> b.status in activeStates
                "unassigned" -> b.status == "searching"
                "delayed" -> b.id in delayedIds
                "alerts" -> false
                else -> true
            }
            val matchCategory = categoryFilter == "all" || b.vehicleCategory == categoryFilter
            val matchDistrict = districtFilter == "all" ||
                b.pickupDistrict.equals(districtFilter, ignoreCase = true) ||
                b.destinationDistrict.equals(districtFilter, ignoreCase = true)
            matchStatus && matchCategory && matchDistrict
        }
    }

    val openEmergencies = remember(emergencies) { emergencies.filter { it.status != "resolved" } }
    val openComplaints = remember(complaints) { complaints.filter { it.status != "resolved" } }

    val mapMarkers = remember(filteredBookings, tripLocations, openEmergencies, openComplaints) {
        buildList {
            filteredBookings.take(40).forEach { b ->
                val pinColor = when {
                    b.status == "searching" -> "#F59E0B" // unassigned orange
                    b.status in activeStates -> "#1565C0" // active blue
                    else -> "#64748B"
                }
                add(
                    MapMarkerPin(
                        id = "bk_p_${b.id}",
                        lat = b.pickupLat,
                        lng = b.pickupLng,
                        title = "Pickup: ${b.pickupLabel}",
                        subtitle = "${b.status.uppercase()} • ${b.vehicleCategory.uppercase()}",
                        colorHex = pinColor,
                        radiusPx = 8.5f
                    )
                )
            }
            tripLocations.forEach { tl ->
                add(
                    MapMarkerPin(
                        id = "trip_${tl.bookingId}",
                        lat = tl.lat,
                        lng = tl.lng,
                        title = "Active Trip #${tl.bookingId.take(6)}",
                        colorHex = "#0D9488",
                        radiusPx = 9.0f
                    )
                )
            }
            openEmergencies.forEach { sos ->
                add(
                    MapMarkerPin(
                        id = "sos_${sos.id}",
                        lat = sos.lat,
                        lng = sos.lng,
                        title = "SOS Alert #${sos.id.take(6)}",
                        colorHex = "#DC2626",
                        radiusPx = 11.0f
                    )
                )
            }
            openComplaints.filter { it.lat != null && it.lng != null }.forEach { c ->
                add(
                    MapMarkerPin(
                        id = "cmp_${c.id}",
                        lat = c.lat!!,
                        lng = c.lng!!,
                        title = "Escalation: ${c.category}",
                        colorHex = "#DC2626",
                        radiusPx = 9.5f
                    )
                )
            }
        }
    }

    val centerCoord = remember(filteredBookings, mergedDrivers, serviceAreas) {
        val activeZone = serviceAreas.firstOrNull { it.isActive }
        val firstBooking = filteredBookings.firstOrNull()
        val firstDriver = mergedDrivers.firstOrNull { it.isOnline }
        when {
            firstBooking != null && firstBooking.pickupLat != 0.0 -> firstBooking.pickupLat to firstBooking.pickupLng
            firstDriver != null -> firstDriver.currentLat to firstDriver.currentLng
            activeZone != null && activeZone.centerLat != 0.0 -> activeZone.centerLat to activeZone.centerLng
            else -> BoraiLaunchConfig.DEFAULT_LAT to BoraiLaunchConfig.DEFAULT_LNG
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (portalRole == PortalRole.OWNER_ADMIN) {
                        "Owner Live Demand & Dispatch Map"
                    } else {
                        "Operations Live Dispatch & Monitoring"
                    },
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Real OpenStreetMap view • Default hub: ${BoraiLaunchConfig.DEFAULT_LOCALITY} (${BoraiLaunchConfig.DEFAULT_PINCODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            GolocalStatusBadge(
                label = if (portalRole == PortalRole.OWNER_ADMIN) "OWNER ADMIN" else "OPERATIONS STAFF",
                tone = "info"
            )
        }

        // Real-time Ops Metrics Row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GolocalMetricCard(
                title = "Unassigned",
                value = bookings.count { it.status == "searching" }.toString(),
                subtitle = "${delayedBookings.size} delayed >=10m",
                icon = Icons.Filled.HourglassTop,
                modifier = Modifier.weight(1f)
            )
            GolocalMetricCard(
                title = "Live Partners",
                value = mergedDrivers.count { it.isOnline }.toString(),
                subtitle = "${liveRtdb.count { it.trackingStatus == "active" }} RTDB active",
                icon = Icons.Filled.Sensors,
                modifier = Modifier.weight(1f)
            )
            GolocalMetricCard(
                title = "SOS & Issues",
                value = "${openEmergencies.size}/${openComplaints.size}",
                subtitle = "Escalations",
                icon = Icons.Filled.Warning,
                modifier = Modifier.weight(1f)
            )
        }

        // Interactive MapLibre OpenStreetMap Surface
        GoLocalOsmMapCard(
            centerLat = centerCoord.first,
            centerLng = centerCoord.second,
            onlineDrivers = mergedDrivers,
            extraMarkers = mapMarkers,
            zones = serviceAreas.map { it.toMapZoneOverlay() },
            statusBadgeText = "${filteredBookings.size} bookings • ${mergedDrivers.count { it.isOnline }} live riders",
            height = 290.dp
        )

        // Legend & Filters
        Text("Map & Queue Filters", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "active" to "Active (${bookings.count { it.status in activeStates }})",
                "unassigned" to "Unassigned (${bookings.count { it.status == "searching" }})",
                "delayed" to "Delayed (${delayedBookings.size})",
                "alerts" to "SOS/Issues (${openEmergencies.size + openComplaints.size})",
                "all" to "All (${bookings.size})"
            ).forEach { (id, label) ->
                FilterChip(
                    selected = statusFilter == id,
                    onClick = { statusFilter = id },
                    label = { Text(label) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("all", "toto", "auto", "bike", "cab").forEach { cat ->
                FilterChip(
                    selected = categoryFilter == cat,
                    onClick = { categoryFilter = cat },
                    label = { Text(if (cat == "all") "All Vehicles" else cat.uppercase()) }
                )
            }
            if (districts.isNotEmpty()) {
                FilterChip(
                    selected = districtFilter == "all",
                    onClick = { districtFilter = "all" },
                    label = { Text("All Districts") }
                )
                districts.forEach { dist ->
                    FilterChip(
                        selected = districtFilter.equals(dist, ignoreCase = true),
                        onClick = { districtFilter = dist },
                        label = { Text(dist) }
                    )
                }
            }
        }

        // Pickup Delay Alerts
        if (delayedBookings.isNotEmpty()) {
            Text("Pickup Delay Alerts (${delayedBookings.size})", style = MaterialTheme.typography.titleMedium, color = GolocalError)
            delayedBookings.take(5).forEach { delayed ->
                val minsWaiting = ((nowEpochSec - (delayed.createdAt?.seconds ?: nowEpochSec)) / 60L).coerceAtLeast(10L)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = GolocalWarning.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, GolocalWarning, RoundedCornerShape(14.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${delayed.pickupLabel} -> ${delayed.destinationLabel}", style = MaterialTheme.typography.titleSmall)
                            Text("Waiting ${minsWaiting}m • ${delayed.vehicleCategory.uppercase()} • ${delayed.status.uppercase()}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (delayed.status == "searching") {
                            FilledTonalButton(onClick = { assignTargetBooking = delayed }) {
                                Text("Assign")
                            }
                        }
                    }
                }
            }
        }

        // Unassigned & Active Bookings List with Ops Assignment Assistance
        Text("Operational Bookings Queue (${filteredBookings.size})", style = MaterialTheme.typography.titleMedium)
        if (filteredBookings.isEmpty()) {
            GolocalEmptyState(
                title = "No Bookings Match Filters",
                message = "Real customer rides, pickup delays, and driver assignments will appear here automatically."
            )
        } else {
            filteredBookings.take(20).forEach { b ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            GolocalStatusBadge(
                                label = b.status.uppercase(),
                                tone = when {
                                    b.id in delayedIds -> "error"
                                    b.status == "searching" -> "warning"
                                    b.status == "completed" -> "success"
                                    else -> "info"
                                }
                            )
                            Text(
                                "${b.vehicleCategory.uppercase()} • ${b.distanceKm} km",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = JetBrainsMono
                            )
                        }
                        Text("${b.pickupLabel} -> ${b.destinationLabel}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "District: ${b.pickupDistrict.ifBlank { "West Bengal" }} • Driver: ${b.assignedDriverName.ifBlank { "Unassigned" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (b.status == "searching") {
                            OutlinedButton(
                                onClick = { assignTargetBooking = b },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Ops Assistance: Assign Available Partner")
                            }
                        }
                    }
                }
            }
        }

        // Escalation & Complaint Location Context
        if (openEmergencies.isNotEmpty() || openComplaints.isNotEmpty()) {
            Text("SOS & Complaint Location Context", style = MaterialTheme.typography.titleMedium, color = GolocalError)
            openEmergencies.forEach { sos ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = GolocalError.copy(alpha = 0.08f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, GolocalError, RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("ACTIVE SOS #${sos.id.take(8)} • Booking ${sos.bookingId.ifBlank { "Home" }}", style = MaterialTheme.typography.titleSmall, color = GolocalError)
                        Text("Coords: ${String.format(Locale.US, "%.4f, %.4f", sos.lat, sos.lng)} • ${sos.notes}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            openComplaints.forEach { cmp ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Complaint (${cmp.category}) • ${cmp.locationLabel.ifBlank { "Trip #${cmp.bookingId.take(6)}" }}", style = MaterialTheme.typography.titleSmall)
                        if (cmp.lat != null && cmp.lng != null) {
                            Text(
                                "Location: ${String.format(Locale.US, "%.4f, %.4f", cmp.lat, cmp.lng)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMono
                            )
                        }
                        Text(cmp.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Service Area Monitoring summary
        Text("Service-Area Monitoring (${serviceAreas.size})", style = MaterialTheme.typography.titleMedium)
        if (serviceAreas.isEmpty()) {
            Text(
                text = "No restricted service boundaries published yet — open coverage active around Borai (PIN 721457) & West Bengal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            serviceAreas.forEach { zone ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(zone.name, style = MaterialTheme.typography.titleSmall)
                            GolocalStatusBadge(
                                label = if (zone.isActive) "ACTIVE" else "PAUSED",
                                tone = if (zone.isActive) "success" else "warning"
                            )
                        }
                        Text(
                            "${zone.block}, ${zone.district} (${zone.pincode.ifBlank { "WB" }}) • Pickup: ${if (zone.pickupEnabled) "ON" else "OFF"} • Drop: ${if (zone.dropoffEnabled) "ON" else "OFF"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Operations Partner Assignment Dialog
    assignTargetBooking?.let { booking ->
        var note by remember { mutableStateOf("Dispatched by Operations Desk") }
        val candidateDrivers = remember(mergedDrivers, booking) {
            mergedDrivers
                .filter { it.verificationStatus == "verified" && it.isOnline }
                .sortedBy {
                    FareCalculator.haversineKm(booking.pickupLat, booking.pickupLng, it.currentLat, it.currentLng)
                }
        }
        AlertDialog(
            onDismissRequest = { assignTargetBooking = null },
            title = { Text("Assign Partner to #${booking.id.take(6)}") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("${booking.pickupLabel} -> ${booking.destinationLabel} (${booking.vehicleCategory.uppercase()})", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Ops assignment note") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (candidateDrivers.isEmpty()) {
                        Text(
                            "No verified online partners are currently live. Partners appear here as soon as they go online.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GolocalError
                        )
                    } else {
                        candidateDrivers.forEach { d ->
                            val distKm = FareCalculator.haversineKm(booking.pickupLat, booking.pickupLng, d.currentLat, d.currentLng)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        adminViewModel.opsAssignDriver(booking, d, note)
                                        assignTargetBooking = null
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(d.displayName, style = MaterialTheme.typography.titleSmall)
                                        Text("${d.vehicleCategory.uppercase()} • ${d.maskedPlate} • ${String.format(Locale.US, "%.1f km away", distKm)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("Assign", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { assignTargetBooking = null }) { Text("Close") } }
        )
    }
}

@Composable
fun OwnerServiceZonesConfigurator(adminViewModel: AdminViewModel) {
    val serviceAreas by adminViewModel.serviceAreas.collectAsStateWithLifecycle()
    var editingZone by remember { mutableStateOf<ServiceArea?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    editingZone = BoraiLaunchConfig.defaultZoneTemplate().copy(id = "")
                    showEditor = true
                },
                modifier = Modifier.weight(1f).testTag("owner_add_zone_button")
            ) {
                Icon(Icons.Filled.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("New Zone")
            }
            OutlinedButton(
                onClick = { adminViewModel.seedBoraiDefaultZone() },
                modifier = Modifier.weight(1f).testTag("owner_seed_borai_button")
            ) {
                Text("Seed Borai 721457")
            }
        }

        // Live boundary map preview
        GoLocalOsmMapCard(
            centerLat = BoraiLaunchConfig.DEFAULT_LAT,
            centerLng = BoraiLaunchConfig.DEFAULT_LNG,
            zones = serviceAreas.map { it.toMapZoneOverlay() },
            statusBadgeText = "Owner Service Boundaries (${serviceAreas.count { it.isActive }} active)",
            height = 220.dp
        )

        if (serviceAreas.isEmpty()) {
            GolocalEmptyState(
                title = "No Service Zones Configured",
                message = "Seed Borai (PIN 721457, Mohanpur) or add custom village/town coverage boundaries, vehicle categories, and pickup/drop toggles."
            )
        } else {
            serviceAreas.forEach { area ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(area.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${area.block}, ${area.district} ${if (area.pincode.isNotBlank()) "(${area.pincode})" else ""} • ${area.radiusKm} km / ${area.polygonPoints.size} vertices",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = area.isActive,
                                onCheckedChange = { adminViewModel.toggleServiceArea(area.id, it) }
                            )
                        }
                        Text(
                            text = "Pickup: ${if (area.pickupEnabled) "ON" else "OFF"} • Drop: ${if (area.dropoffEnabled) "ON" else "OFF"} • Vehicles: ${area.supportedVehicleCategories.joinToString(", ") { it.uppercase() }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (area.villages.isNotEmpty()) {
                            Text(
                                text = "Villages: ${area.villages.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                editingZone = area
                                showEditor = true
                            }) {
                                Text("Edit Boundary & Vehicles")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        val base = editingZone ?: BoraiLaunchConfig.defaultZoneTemplate().copy(id = "")
        var name by remember(base) { mutableStateOf(base.name) }
        var nameBn by remember(base) { mutableStateOf(base.nameBn) }
        var district by remember(base) { mutableStateOf(base.district) }
        var block by remember(base) { mutableStateOf(base.block) }
        var pincode by remember(base) { mutableStateOf(base.pincode) }
        var zoneType by remember(base) { mutableStateOf(base.zoneType) }
        var centerLat by remember(base) { mutableStateOf(base.centerLat.takeIf { it != 0.0 } ?: BoraiLaunchConfig.DEFAULT_LAT) }
        var centerLng by remember(base) { mutableStateOf(base.centerLng.takeIf { it != 0.0 } ?: BoraiLaunchConfig.DEFAULT_LNG) }
        var radiusKm by remember(base) { mutableStateOf(base.radiusKm.toString()) }
        var polygonPts by remember(base) { mutableStateOf(base.polygonPoints) }
        var villagesText by remember(base) { mutableStateOf(base.villages.joinToString(", ")) }
        var pickupEnabled by remember(base) { mutableStateOf(base.pickupEnabled) }
        var dropoffEnabled by remember(base) { mutableStateOf(base.dropoffEnabled) }
        var isActive by remember(base) { mutableStateOf(base.isActive) }
        var parcelOk by remember(base) { mutableStateOf(base.parcelApproved) }
        var note by remember(base) { mutableStateOf(base.availabilityNote) }
        var selectedVehicles by remember(base) {
            mutableStateOf(base.supportedVehicleCategories.map { it.lowercase() }.toSet().ifEmpty { setOf("toto", "auto", "bike", "cab") })
        }
        var mapEditMode by remember { mutableStateOf("zone_center") }

        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(if (base.id.isBlank()) "Create Service Zone" else "Edit ${base.name}") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GoLocalOsmMapCard(
                        centerLat = centerLat,
                        centerLng = centerLng,
                        pickupPoint = GeoPointDto(centerLat, centerLng),
                        zones = listOf(
                            ServiceArea(
                                id = "draft_zone",
                                name = name.ifBlank { "Draft Zone" },
                                centerLat = centerLat,
                                centerLng = centerLng,
                                radiusKm = radiusKm.toDoubleOrNull() ?: 8.0,
                                polygonPoints = polygonPts,
                                isActive = true
                            ).toMapZoneOverlay()
                        ),
                        interactivePinMode = mapEditMode,
                        onMapTap = { lat, lng ->
                            if (mapEditMode == "zone_center") {
                                centerLat = lat
                                centerLng = lng
                            } else {
                                polygonPts = polygonPts + GeoPointDto(lat, lng)
                            }
                        },
                        height = 170.dp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = mapEditMode == "zone_center",
                            onClick = { mapEditMode = "zone_center" },
                            label = { Text("Tap Center") }
                        )
                        FilterChip(
                            selected = mapEditMode == "zone_vertex",
                            onClick = { mapEditMode = "zone_vertex" },
                            label = { Text("Add Vertex (${polygonPts.size})") }
                        )
                        if (polygonPts.isNotEmpty()) {
                            TextButton(onClick = { polygonPts = emptyList() }) { Text("Clear Poly") }
                        }
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Zone Name *") }, singleLine = true)
                    OutlinedTextField(value = nameBn, onValueChange = { nameBn = it }, label = { Text("Bengali Name") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = district, onValueChange = { district = it }, label = { Text("District *") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = block, onValueChange = { block = it }, label = { Text("Block") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = pincode, onValueChange = { pincode = it }, label = { Text("PIN Code") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = radiusKm, onValueChange = { radiusKm = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Radius (km)") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = zoneType, onValueChange = { zoneType = it }, label = { Text("Zone Type (village, town, railway_station, bus_stand)") }, singleLine = true)
                    OutlinedTextField(value = villagesText, onValueChange = { villagesText = it }, label = { Text("Covered Villages (comma-separated)") })
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Availability Note") })

                    Text("Supported Vehicle Categories", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("toto", "auto", "bike", "cab").forEach { cat ->
                            FilterChip(
                                selected = cat in selectedVehicles,
                                onClick = {
                                    selectedVehicles = if (cat in selectedVehicles) selectedVehicles - cat else selectedVehicles + cat
                                },
                                label = { Text(cat.uppercase()) }
                            )
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Zone Active")
                        Switch(checked = isActive, onCheckedChange = { isActive = it })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Pickup Coverage Enabled")
                        Switch(checked = pickupEnabled, onCheckedChange = { pickupEnabled = it })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Drop-off Coverage Enabled")
                        Switch(checked = dropoffEnabled, onCheckedChange = { dropoffEnabled = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = parcelOk, onCheckedChange = { parcelOk = it })
                        Text("Parcel Delivery Approved")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val parsedVillages = villagesText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    adminViewModel.saveOrUpdateServiceArea(
                        base.copy(
                            name = name.trim(),
                            nameBn = nameBn.trim(),
                            district = district.trim(),
                            block = block.trim(),
                            pincode = pincode.trim(),
                            zoneType = zoneType.trim().ifBlank { "village" },
                            lat = centerLat,
                            lng = centerLng,
                            centerLat = centerLat,
                            centerLng = centerLng,
                            radiusKm = radiusKm.toDoubleOrNull()?.coerceAtLeast(1.0) ?: 8.0,
                            polygonPoints = polygonPts,
                            villages = parsedVillages,
                            pickupEnabled = pickupEnabled,
                            dropoffEnabled = dropoffEnabled,
                            supportedVehicleCategories = selectedVehicles.toList().ifEmpty { listOf("toto") },
                            availabilityNote = note.trim(),
                            isActive = isActive,
                            parcelApproved = parcelOk
                        )
                    )
                    showEditor = false
                }) { Text("Save Zone") }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("Cancel") } }
        )
    }
}
