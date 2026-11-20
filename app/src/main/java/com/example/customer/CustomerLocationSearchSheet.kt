package com.example.customer

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.Booking
import com.example.data.model.CustomerProfile
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.ServiceArea
import com.example.domain.WestBengalCatalog
import com.example.domain.WestBengalPlace
import com.example.map.GolocalMapContainer
import com.example.map.NominatimGeocoder
import com.example.map.NominatimPlaceResult
import com.example.shared.CircularBackButton
import kotlinx.coroutines.delay
import com.example.shared.LocationAccessState
import com.example.shared.LocationCoordinator
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalErrorContainer
import com.example.ui.theme.GolocalLightBlue
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalSuccess
import com.example.viewmodel.BookingDraftState
import com.example.viewmodel.GpsLocationState

enum class LocationFieldTarget {
    PICKUP,
    DESTINATION
}

@Composable
fun CustomerLocationSearchSheet(
    initialTarget: LocationFieldTarget,
    draft: BookingDraftState,
    gps: GpsLocationState,
    customerProfile: CustomerProfile?,
    recentBookings: List<Booking>,
    serviceAreas: List<ServiceArea>,
    onlineDrivers: List<DriverDirectoryEntry>,
    lowNetworkMode: Boolean,
    onSelectCatalogPlace: (LocationFieldTarget, WestBengalPlace) -> Unit,
    onSelectCoordinates: (LocationFieldTarget, label: String, lat: Double, lng: Double, district: String) -> Unit,
    onSwapLocations: () -> Unit,
    onRequestGps: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var activeTarget by remember { mutableStateOf(initialTarget) }
    var searchQuery by remember { mutableStateOf("") }
    var mapPinMode by remember { mutableStateOf(false) }
    var onlinePlaces by remember { mutableStateOf<List<NominatimPlaceResult>>(emptyList()) }

    val defaultCenterLat = when (activeTarget) {
        LocationFieldTarget.PICKUP -> gps.lat ?: draft.pickupPlace.lat
        LocationFieldTarget.DESTINATION -> draft.destinationPlace.lat
    }
    val defaultCenterLng = when (activeTarget) {
        LocationFieldTarget.PICKUP -> gps.lng ?: draft.pickupPlace.lng
        LocationFieldTarget.DESTINATION -> draft.destinationPlace.lng
    }
    var pinLat by remember(mapPinMode, activeTarget) { mutableDoubleStateOf(defaultCenterLat) }
    var pinLng by remember(mapPinMode, activeTarget) { mutableDoubleStateOf(defaultCenterLng) }

    LaunchedEffect(searchQuery, lowNetworkMode, defaultCenterLat, defaultCenterLng) {
        val q = searchQuery.trim()
        if (lowNetworkMode || q.length < 3) {
            onlinePlaces = emptyList()
            return@LaunchedEffect
        }
        delay(450L)
        onlinePlaces = NominatimGeocoder.searchPlaces(
            query = q,
            biasLat = defaultCenterLat,
            biasLng = defaultCenterLng
        )
    }

    var accessState by remember { mutableStateOf(LocationCoordinator.resolveAccessState(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        accessState = LocationCoordinator.resolveAccessState(context)
        if (perms.values.any { it }) {
            onRequestGps()
        }
    }

    BackHandler {
        if (mapPinMode) mapPinMode = false else onClose()
    }

    if (mapPinMode) {
        val resolvedPinLabel = remember(pinLat, pinLng) {
            LocationCoordinator.formatCoordinateLabel(pinLat, pinLng)
        }
        val nearestDistrict = remember(pinLat, pinLng) {
            LocationCoordinator.nearestCatalogPlace(pinLat, pinLng)?.district ?: "West Bengal"
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag("manual_map_pin_picker")
        ) {
            GolocalMapContainer(
                pickupLat = pinLat,
                pickupLng = pinLng,
                pickupLabel = resolvedPinLabel,
                destLat = pinLat,
                destLng = pinLng,
                destLabel = "",
                onlineDrivers = onlineDrivers,
                liveTripLocation = null,
                lowNetworkMode = lowNetworkMode,
                fillContainer = true,
                cornerRadius = 0.dp,
                showBorder = false,
                showTopRouteChip = false,
                recenterBottomPadding = 164.dp,
                userLat = gps.lat,
                userLng = gps.lng,
                enableCenterPinSelection = true,
                onCenterCoordinateChanged = { lat, lng ->
                    pinLat = lat
                    pinLng = lng
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularBackButton(onClick = { mapPinMode = false })
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = if (activeTarget == LocationFieldTarget.PICKUP) "Move map to set pickup pin" else "Move map to set drop pin",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 14.dp
            ) {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (activeTarget == LocationFieldTarget.PICKUP) "Picked Pickup Location" else "Picked Destination Location",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = resolvedPinLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Button(
                        onClick = {
                            onSelectCoordinates(activeTarget, resolvedPinLabel, pinLat, pinLng, nearestDistrict)
                            mapPinMode = false
                            onClose()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("confirm_map_pin_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Filled.PinDrop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Confirm Pin Location")
                    }
                }
            }
        }
        return
    }

    val mergedPlaces = remember(searchQuery, serviceAreas) {
        val catalogMatches = WestBengalCatalog.search(searchQuery)
        val areaMatches = serviceAreas
            .filter { it.isActive && it.lat != 0.0 && it.lng != 0.0 }
            .filter { area ->
                val q = searchQuery.trim().lowercase()
                q.isBlank() ||
                    area.name.lowercase().contains(q) ||
                    area.nameBn.contains(q) ||
                    area.district.lowercase().contains(q) ||
                    area.block.lowercase().contains(q)
            }
            .map { area ->
                WestBengalPlace(
                    id = "area_${area.id.ifBlank { area.name }}",
                    nameEn = area.name,
                    nameBn = area.nameBn.ifBlank { area.name },
                    district = area.district,
                    block = area.block,
                    category = area.zoneType,
                    lat = area.lat,
                    lng = area.lng
                )
            }
        (areaMatches + catalogMatches).distinctBy { it.nameEn.lowercase() }
    }

    val recentPlaces = remember(recentBookings) {
        recentBookings
            .mapNotNull { b ->
                if (b.destinationLabel.isBlank() || b.destinationLat == 0.0) null
                else WestBengalPlace(
                    id = "recent_${b.id}",
                    nameEn = b.destinationLabel,
                    nameBn = b.destinationDistrict,
                    district = b.destinationDistrict.ifBlank { "Recent Trip" },
                    block = "",
                    category = "recent",
                    lat = b.destinationLat,
                    lng = b.destinationLng
                )
            }
            .distinctBy { it.nameEn.lowercase() }
            .take(4)
    }

    val pickupDisplay = draft.customPickupLabel.ifBlank { draft.pickupPlace.nameEn }
    val destDisplay = draft.customDestinationLabel.ifBlank { draft.destinationPlace.nameEn }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("location_search_sheet"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularBackButton(onClick = onClose, testTag = "search_close_button")
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (activeTarget == LocationFieldTarget.PICKUP) "Set Pickup Location" else "Set Destination",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Search West Bengal villages, stations, bus stands or pin on map",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search station, village, market, PIN 721457...") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("location_search_input")
            )

            // Action Strip + Route Pair + Warnings + Results List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .testTag("location_results_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    // Route Pair Editor Card
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RouteStopPill(
                                    label = "PICKUP",
                                    value = pickupDisplay,
                                    selected = activeTarget == LocationFieldTarget.PICKUP,
                                    dotColor = GolocalSuccess,
                                    onClick = { activeTarget = LocationFieldTarget.PICKUP },
                                    modifier = Modifier.testTag("select_pickup_target")
                                )
                                RouteStopPill(
                                    label = "DROP",
                                    value = destDisplay,
                                    selected = activeTarget == LocationFieldTarget.DESTINATION,
                                    dotColor = GolocalDeepNavy,
                                    onClick = { activeTarget = LocationFieldTarget.DESTINATION },
                                    modifier = Modifier.testTag("select_dest_target")
                                )
                            }
                            IconButton(
                                onClick = onSwapLocations,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                                    .testTag("swap_locations_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SwapVert,
                                    contentDescription = "Swap pickup and destination",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                item {
                    // Action Strip: Use GPS + Pick on Map
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                accessState = LocationCoordinator.resolveAccessState(context)
                                when (accessState) {
                                    LocationAccessState.GRANTED_READY -> {
                                        onRequestGps()
                                        if (gps.lat != null && gps.lng != null) {
                                            val lbl = gps.label.ifBlank { LocationCoordinator.formatCoordinateLabel(gps.lat, gps.lng) }
                                            val dist = LocationCoordinator.nearestCatalogPlace(gps.lat, gps.lng)?.district ?: "West Bengal"
                                            onSelectCoordinates(activeTarget, lbl, gps.lat, gps.lng, dist)
                                            onClose()
                                        }
                                    }
                                    LocationAccessState.PERMISSION_REQUIRED -> {
                                        permissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.ACCESS_COARSE_LOCATION
                                            )
                                        )
                                    }
                                    LocationAccessState.GPS_DISABLED -> {
                                        LocationCoordinator.openLocationSettings(context)
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("search_use_gps_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                when {
                                    gps.isDetecting -> "Detecting..."
                                    accessState == LocationAccessState.GPS_DISABLED -> "Turn On GPS"
                                    else -> "Use Current GPS"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = { mapPinMode = true },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("search_pick_on_map_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Filled.PinDrop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pin on Map", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                if (gps.error != null || accessState != LocationAccessState.GRANTED_READY) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Filled.LocationOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = gps.error
                                        ?: if (accessState == LocationAccessState.GPS_DISABLED) {
                                            "Device GPS is off. Enable GPS or pick your stop on map / below."
                                        } else {
                                            "Grant location permission for live pickup detection, or search manually."
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                if (accessState == LocationAccessState.GRANTED_READY) {
                                    TextButton(onClick = onRequestGps) { Text("Retry") }
                                }
                            }
                        }
                    }
                }

                if (onlinePlaces.isNotEmpty() && searchQuery.trim().length >= 3) {
                    item {
                        Text(
                            text = "Online Map Results (${onlinePlaces.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(onlinePlaces, key = { "nom_${it.lat}_${it.lng}_${it.primaryLabel}" }) { place ->
                        LocationResultRow(
                            icon = Icons.Filled.LocationOn,
                            title = place.primaryLabel,
                            subtitle = place.displayName,
                            onClick = {
                                onSelectCoordinates(activeTarget, place.primaryLabel, place.lat, place.lng, place.district)
                                onClose()
                            }
                        )
                    }
                }

                // Use Custom Typed Place Button if query entered
                if (searchQuery.trim().length >= 3) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val baseLat = if (activeTarget == LocationFieldTarget.PICKUP) gps.lat ?: draft.pickupPlace.lat else draft.destinationPlace.lat
                                    val baseLng = if (activeTarget == LocationFieldTarget.PICKUP) gps.lng ?: draft.pickupPlace.lng else draft.destinationPlace.lng
                                    onSelectCoordinates(activeTarget, searchQuery.trim(), baseLat, baseLng, "West Bengal")
                                    onClose()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Filled.AddLocationAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Use \"${searchQuery.trim()}\" as custom locality",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                if (searchQuery.isBlank() && customerProfile != null) {
                    val savedEntries = buildList {
                        if (customerProfile.homeAddress.isNotBlank()) add(Triple("Home", customerProfile.homeAddress, Icons.Filled.Home))
                        if (customerProfile.workAddress.isNotBlank()) add(Triple("Work", customerProfile.workAddress, Icons.Filled.Work))
                        if (customerProfile.nativeVillage.isNotBlank()) add(Triple("Native Village", customerProfile.nativeVillage, Icons.Filled.LocationOn))
                    }
                    if (savedEntries.isNotEmpty()) {
                        item {
                            Text(
                                text = "Saved Addresses",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        items(savedEntries) { (tag, address, icon) ->
                            val matched = WestBengalCatalog.search(address).firstOrNull()
                            val lat = matched?.lat ?: if (activeTarget == LocationFieldTarget.PICKUP) draft.pickupPlace.lat else draft.destinationPlace.lat
                            val lng = matched?.lng ?: if (activeTarget == LocationFieldTarget.PICKUP) draft.pickupPlace.lng else draft.destinationPlace.lng
                            LocationResultRow(
                                icon = icon,
                                title = tag,
                                subtitle = address,
                                onClick = {
                                    onSelectCoordinates(activeTarget, address, lat, lng, matched?.district ?: customerProfile.nativeDistrict.ifBlank { "West Bengal" })
                                    onClose()
                                }
                            )
                        }
                    }
                }

                if (searchQuery.isBlank() && recentPlaces.isNotEmpty()) {
                    item {
                        Text(
                            text = "Recent Stops",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(recentPlaces, key = { it.id }) { place ->
                        LocationResultRow(
                            icon = Icons.Filled.History,
                            title = place.nameEn,
                            subtitle = place.district,
                            onClick = {
                                onSelectCoordinates(activeTarget, place.nameEn, place.lat, place.lng, place.district)
                                onClose()
                            }
                        )
                    }
                }

                item {
                    Text(
                        text = if (searchQuery.isBlank()) "West Bengal Stops & Hubs" else "Matching Locations (${mergedPlaces.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (mergedPlaces.isEmpty()) {
                    item {
                        Text(
                            text = "No verified stops match \"$searchQuery\". Tap \"Use custom locality\" above or pick directly on map.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    items(mergedPlaces, key = { it.id }) { place ->
                        LocationResultRow(
                            icon = Icons.Filled.LocationOn,
                            title = place.nameEn,
                            subtitle = "${place.nameBn} • ${place.district} • ${place.category.replace('_', ' ')}",
                            onClick = {
                                onSelectCatalogPlace(activeTarget, place)
                                onClose()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteStopPill(
    label: String,
    value: String,
    selected: Boolean,
    dotColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(14.dp)
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LocationResultRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
