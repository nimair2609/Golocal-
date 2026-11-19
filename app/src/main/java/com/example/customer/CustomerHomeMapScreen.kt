package com.example.customer

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.Booking
import com.example.data.model.BookingCallContact
import com.example.data.model.BookingChatMessage
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.PlatformSettings
import com.example.data.model.TripLocation
import com.example.data.model.UiState
import com.example.domain.WestBengalCatalog
import com.example.map.GolocalMapContainer
import com.example.shared.BookingChatModalSheet
import com.example.shared.BookingCommunicationBar
import com.example.shared.CallPeerConfirmDialog
import com.example.shared.LocationAccessState
import com.example.shared.LocationCoordinator
import com.example.shared.NotificationPermissionBannerCard
import com.example.ui.components.GoLocalLogoMark
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalLightBlue
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.JetBrainsMono

@Composable
fun CustomerHomeMapScreen(
    firstName: String,
    unreadNotifications: Int,
    activeBooking: Booking?,
    bookingsState: UiState<List<Booking>>,
    verifiedDrivers: List<DriverDirectoryEntry>,
    platformSettings: PlatformSettings?,
    onOpenBookRide: (presetType: String?) -> Unit,
    onOpenExplore: () -> Unit,
    onOpenBookings: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenHelp: () -> Unit,
    onQuickSos: () -> Unit,
    currentUserId: String = "",
    activeTripLocation: TripLocation? = null,
    lowNetworkMode: Boolean = false,
    activeCallContact: BookingCallContact? = null,
    activeChatMessages: List<BookingChatMessage> = emptyList(),
    unreadChatCount: Int = 0,
    onSendChatMessage: (Booking, String) -> Unit = { _, _ -> },
    onMarkChatRead: () -> Unit = {},
    onOpenBusBooking: () -> Unit = {},
    userLat: Double? = null,
    userLng: Double? = null,
    gpsError: String? = null,
    onRequestLocationDetect: () -> Unit = {}
) {
    val context = LocalContext.current
    val onlineCount = verifiedDrivers.count { it.isOnline }
    val allBookings = (bookingsState as? UiState.Success)?.data.orEmpty()
    val defaultOrigin = WestBengalCatalog.places.first()
    val defaultDest = WestBengalCatalog.places[1]

    var sheetExpanded by remember { mutableStateOf(false) }
    var showCallDialog by remember { mutableStateOf(false) }
    var showChatSheet by remember { mutableStateOf(false) }
    var permissionDeniedOnce by remember { mutableStateOf(false) }
    var accessState by remember { mutableStateOf(LocationCoordinator.resolveAccessState(context)) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        accessState = LocationCoordinator.resolveAccessState(context)
        if (perms.values.any { it }) {
            permissionDeniedOnce = false
            onRequestLocationDetect()
        } else {
            permissionDeniedOnce = true
        }
    }

    val mapOriginLat = activeBooking?.pickupLat ?: userLat ?: defaultOrigin.lat
    val mapOriginLng = activeBooking?.pickupLng ?: userLng ?: defaultOrigin.lng
    val mapDestLat = activeBooking?.destinationLat ?: (userLat ?: defaultDest.lat)
    val mapDestLng = activeBooking?.destinationLng ?: (userLng ?: defaultDest.lng)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactSheetMaxHeight = (maxHeight * 0.42f).coerceIn(220.dp, 330.dp)
        val expandedSheetMaxHeight = (maxHeight * 0.72f).coerceIn(340.dp, 580.dp)
        val sheetHeightLimit = if (sheetExpanded) expandedSheetMaxHeight else compactSheetMaxHeight

        // 1. Full-bleed Map Background (dominates 70%+ when sheet collapsed)
        GolocalMapContainer(
            pickupLat = mapOriginLat,
            pickupLng = mapOriginLng,
            pickupLabel = activeBooking?.pickupLabel ?: "Pickup",
            destLat = mapDestLat,
            destLng = mapDestLng,
            destLabel = activeBooking?.destinationLabel ?: "",
            onlineDrivers = verifiedDrivers,
            liveTripLocation = activeTripLocation,
            lowNetworkMode = lowNetworkMode,
            fillContainer = true,
            cornerRadius = 0.dp,
            showBorder = false,
            showTopRouteChip = false,
            recenterBottomPadding = sheetHeightLimit + 12.dp,
            userLat = userLat,
            userLng = userLng
        )

        // 2. Floating Top Bar (Greeting Pill + Alerts + SOS + Location Status Banner if needed)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GoLocalLogoMark(modifier = Modifier.size(30.dp))
                        Column {
                            Text(
                                text = "Hello, $firstName",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val activeSubtitle = when {
                                platformSettings?.testRideModeEnabled == true -> "Test Ride Mode Active • Controlled Simulation"
                                onlineCount > 0 -> "$onlineCount verified riders nearby"
                                else -> "West Bengal Local Mobility"
                            }
                            Text(
                                text = activeSubtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (platformSettings?.testRideModeEnabled == true) GolocalPrimaryBlue else if (onlineCount > 0) GolocalSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onQuickSos,
                        shape = CircleShape,
                        color = GolocalError,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("home_sos_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "SOS",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                    }

                    BadgedBox(
                        badge = {
                            if (unreadNotifications > 0) {
                                Badge { Text(unreadNotifications.toString()) }
                            }
                        }
                    ) {
                        Surface(
                            onClick = onOpenNotifications,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("home_notifications_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Notifications,
                                    contentDescription = "Notifications",
                                    tint = GolocalPrimaryBlue
                                )
                            }
                        }
                    }
                }
            }

            // Location Permission / GPS Disabled Pill (non-intrusive; never repeats nag when ready)
            if (accessState != LocationAccessState.GRANTED_READY || gpsError != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (accessState == LocationAccessState.GRANTED_READY) Icons.Filled.MyLocation else Icons.Filled.LocationOff,
                            contentDescription = null,
                            tint = GolocalPrimaryBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = when {
                                accessState == LocationAccessState.PERMISSION_REQUIRED && permissionDeniedOnce ->
                                    "Location access declined. Set pickup manually or allow GPS."
                                accessState == LocationAccessState.PERMISSION_REQUIRED ->
                                    "Enable location for automatic pickup & nearby riders."
                                accessState == LocationAccessState.GPS_DISABLED ->
                                    "GPS is off. Turn on location services or pick pickup manually."
                                else -> gpsError ?: "Location signal weak."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                accessState = LocationCoordinator.resolveAccessState(context)
                                when (accessState) {
                                    LocationAccessState.PERMISSION_REQUIRED -> {
                                        if (permissionDeniedOnce) {
                                            LocationCoordinator.openAppPermissionSettings(context)
                                        } else {
                                            locationLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                                )
                                            )
                                        }
                                    }
                                    LocationAccessState.GPS_DISABLED -> LocationCoordinator.openLocationSettings(context)
                                    LocationAccessState.GRANTED_READY -> onRequestLocationDetect()
                                }
                            },
                            modifier = Modifier.testTag("home_location_recovery_button")
                        ) {
                            Text(
                                when (accessState) {
                                    LocationAccessState.PERMISSION_REQUIRED -> if (permissionDeniedOnce) "Settings" else "Allow"
                                    LocationAccessState.GPS_DISABLED -> "Turn On"
                                    LocationAccessState.GRANTED_READY -> "Retry"
                                }
                            )
                        }
                    }
                }
            }
        }

        // 3. Anchored White Bottom Sheet ("Where to?" + Quick Actions + Recent Trips)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = sheetHeightLimit)
                .animateContentSize(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Drag / Expand Toggle Handle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { sheetExpanded = !sheetExpanded }
                        .padding(vertical = 4.dp)
                        .testTag("customer_home_sheet_handle"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.outline)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = if (sheetExpanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                        contentDescription = if (sheetExpanded) "Collapse sheet" else "Expand sheet",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Prominent "Where to?" Search Entry Pill
                Surface(
                    onClick = { onOpenBookRide("now") },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                        .testTag("home_search_destination")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(GolocalPrimaryBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Search destination",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Where would you like to go?",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Pickup • Station • Bus Stand • Airport • Village",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = GolocalPrimaryBlue
                        )
                    }
                }

                // Active Trip Quick Card
                if (activeBooking != null) {
                    val isTestRide = activeBooking.isTestRide || activeBooking.testMode
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, GolocalPrimaryBlue, RoundedCornerShape(18.dp))
                            .clickable { onOpenBookRide(null) }
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
                                    if (isTestRide) {
                                        GolocalStatusBadge(label = "TEST RIDE", tone = "warning")
                                    }
                                    GolocalStatusBadge(
                                        label = "ACTIVE: ${activeBooking.status.uppercase()}",
                                        tone = if (activeBooking.status == "searching") "warning" else "success"
                                    )
                                }
                                if (!isTestRide && activeBooking.tripPin.isNotBlank()) {
                                    Text(
                                        text = "PIN ${activeBooking.tripPin}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontFamily = JetBrainsMono,
                                        color = GolocalPrimaryBlue
                                    )
                                }
                            }
                            Text(
                                text = "${activeBooking.pickupLabel} -> ${activeBooking.destinationLabel}",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (activeBooking.assignedDriverId.isNotBlank()) {
                                if (isTestRide) {
                                    Text(
                                        text = "TEST DRIVER: ${activeBooking.assignedDriverName} (${activeBooking.assignedVehicleLabel.ifBlank { "TEST VEHICLE" }})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                } else {
                                    BookingCommunicationBar(
                                        booking = activeBooking,
                                        viewerRole = "customer",
                                        callContact = activeCallContact,
                                        unreadCount = unreadChatCount,
                                        onCallClick = { showCallDialog = true },
                                        onChatClick = {
                                            onMarkChatRead()
                                            showChatSheet = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Quick Service Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HomeServiceChip(
                        icon = Icons.Filled.DirectionsCar,
                        title = "Book Ride",
                        subtitle = "Toto, Auto, Cab",
                        onClick = { onOpenBookRide("now") },
                        modifier = Modifier.testTag("home_hero_book_ride")
                    )
                    HomeServiceChip(
                        icon = Icons.Filled.DirectionsBus,
                        title = "Private Bus",
                        subtitle = "Seat & QR Pass",
                        onClick = onOpenBusBooking,
                        modifier = Modifier.testTag("home_private_bus_button")
                    )
                    HomeServiceChip(
                        icon = Icons.Filled.Train,
                        title = "Local Services",
                        subtitle = "Station & Stand",
                        onClick = { onOpenBookRide("station_pickup") }
                    )
                    HomeServiceChip(
                        icon = Icons.Filled.Explore,
                        title = "Explore",
                        subtitle = "Verified Fleet",
                        onClick = onOpenExplore
                    )
                }

                NotificationPermissionBannerCard()

                // Recent Trips Preview
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recent Trips", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onOpenBookings) { Text("See All") }
                }

                if (allBookings.isEmpty()) {
                    GolocalEmptyState(
                        title = "No recent bookings",
                        message = "Tap Book Ride above to request verified local transport across West Bengal.",
                        actionLabel = "Book Your First Ride",
                        onAction = { onOpenBookRide("now") }
                    )
                } else {
                    allBookings.take(3).forEach { booking ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                                .clickable { onOpenBookings() }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "${booking.pickupLabel} -> ${booking.destinationLabel}",
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${booking.vehicleCategory.uppercase()} • ${booking.distanceKm} km",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                val fare = if (booking.agreedFareInr > 0) booking.agreedFareInr else booking.estimatedFareInr
                                Text(
                                    text = "₹${fare.toInt()}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontFamily = JetBrainsMono,
                                    color = GolocalPrimaryBlue
                                )
                            }
                        }
                    }
                }

                // Compact Safety Desk Strip
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("24x7 Safety Desk", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = platformSettings?.helplineNumber ?: "+91-1800-103-0000",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = onOpenHelp, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Support")
                        }
                    }
                }
            }
        }
    }

    if (showCallDialog && activeBooking != null && !activeBooking.isTestRide && !activeBooking.testMode && activeCallContact != null && activeCallContact.driverPhone.isNotBlank()) {
        CallPeerConfirmDialog(
            peerLabel = activeBooking.assignedDriverName.ifBlank { "Driver" },
            phoneNumber = activeCallContact.driverPhone,
            onDismiss = { showCallDialog = false },
            onOpenChat = {
                onMarkChatRead()
                showChatSheet = true
            }
        )
    }

    if (showChatSheet && activeBooking != null && !activeBooking.isTestRide && !activeBooking.testMode) {
        BookingChatModalSheet(
            booking = activeBooking,
            viewerUid = currentUserId,
            viewerRole = "customer",
            messages = activeChatMessages,
            onSendMessage = { txt -> onSendChatMessage(activeBooking, txt) },
            onMarkRead = onMarkChatRead,
            onDismiss = { showChatSheet = false }
        )
    }
}

@Composable
private fun HomeServiceChip(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(122.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
            }
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
