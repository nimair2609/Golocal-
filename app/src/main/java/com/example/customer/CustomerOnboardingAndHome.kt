package com.example.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.shared.NotificationPermissionBannerCard
import com.example.ui.components.GoLocalLogoMark
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.*

@Composable
fun CustomerOnboardingScreen(
    onComplete: () -> Unit
) {
    val slides = listOf(
        Triple(
            Icons.Filled.DirectionsCar,
            "Book Verified Local Rides",
            "Connect instantly with Toto, Auto, Bike, Cab, Railway Station, Bus Stand, and Airport pickup partners across West Bengal."
        ),
        Triple(
            Icons.Filled.CurrencyRupee,
            "Fair & Transparent Fare Offers",
            "See estimated fares upfront, receive competitive driver proposals within regulated limits, and lock your fare with zero hidden fees."
        ),
        Triple(
            Icons.Filled.Shield,
            "Trip PIN & 24x7 Safety Desk",
            "Every ride starts only with your 4-digit Trip PIN. Trigger Emergency SOS or share live route details anytime."
        )
    )
    var step by remember { mutableIntStateOf(0) }
    val (icon, title, body) = slides[step]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(GolocalDeepNavy, GolocalPrimaryBlue, GolocalSoftBlue)
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GoLocalLogoMark(modifier = Modifier.size(36.dp))
                Text(
                    text = "GoLocal",
                    style = MaterialTheme.typography.titleMedium,
                    color = GolocalPureWhite,
                    fontWeight = FontWeight.Bold
                )
            }
            TextButton(onClick = onComplete) {
                Text("Skip", color = GolocalPureWhite)
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 500.dp)
                .align(Alignment.Center),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    slides.indices.forEach { idx ->
                        Box(
                            modifier = Modifier
                                .height(8.dp)
                                .width(if (idx == step) 26.dp else 8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (idx == step) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                                )
                        )
                    }
                }

                Button(
                    onClick = {
                        if (step < slides.lastIndex) step++ else onComplete()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("customer_onboarding_done"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (step < slides.lastIndex) "Next" else "Get Started")
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

@Composable
fun CustomerHomeTab(
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
    // Minimal optional additions with safe defaults so existing tests/callers remain source-compatible
    currentUserId: String = "",
    activeTripLocation: TripLocation? = null,
    lowNetworkMode: Boolean = false,
    activeCallContact: BookingCallContact? = null,
    activeChatMessages: List<BookingChatMessage> = emptyList(),
    unreadChatCount: Int = 0,
    onSendChatMessage: (Booking, String) -> Unit = { _, _ -> },
    onMarkChatRead: () -> Unit = {},
    onOpenBusBooking: () -> Unit = {}
) {
    val scroll = rememberScrollState()
    val onlineCount = verifiedDrivers.count { it.isOnline }
    val allBookings = (bookingsState as? UiState.Success)?.data.orEmpty()
    val defaultOrigin = WestBengalCatalog.places.first()
    val defaultDest = WestBengalCatalog.places[1]

    var showCallDialog by remember { mutableStateOf(false) }
    var showChatSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Minimalist Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GoLocalLogoMark(modifier = Modifier.size(38.dp))
                Column {
                    Text(
                        text = "Hello, $firstName",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Where are you heading today?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                IconButton(
                    onClick = onOpenNotifications,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .testTag("home_notifications_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = "Notifications",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Clean Edge-to-Edge Live Map Preview + Floating Search Bar Overlay
        Box(modifier = Modifier.fillMaxWidth()) {
            GolocalMapContainer(
                pickupLat = activeBooking?.pickupLat ?: defaultOrigin.lat,
                pickupLng = activeBooking?.pickupLng ?: defaultOrigin.lng,
                pickupLabel = activeBooking?.pickupLabel ?: defaultOrigin.nameEn,
                destLat = activeBooking?.destinationLat ?: defaultDest.lat,
                destLng = activeBooking?.destinationLng ?: defaultDest.lng,
                destLabel = activeBooking?.destinationLabel ?: defaultDest.nameEn,
                onlineDrivers = verifiedDrivers,
                liveTripLocation = activeTripLocation,
                lowNetworkMode = lowNetworkMode,
                mapHeight = 228.dp
            )
        }

        // Clean Floating Destination Pill
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                .clickable { onOpenBookRide("now") }
                .testTag("home_search_destination"),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search destination",
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enter pickup or destination",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (onlineCount > 0) "$onlineCount verified riders active nearby" else "Station, Bus Stand, Airport, Local Town",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Active Trip Live Strip + Direct CALL & CHAT
        if (activeBooking != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                    .clickable { onOpenBookRide(null) },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
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
                        GolocalStatusBadge(
                            label = "ACTIVE: ${activeBooking.status.uppercase()}",
                            tone = if (activeBooking.status == "searching") "warning" else "success"
                        )
                        if (activeBooking.tripPin.isNotBlank()) {
                            Text(
                                text = "PIN ${activeBooking.tripPin}",
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = JetBrainsMono,
                                color = MaterialTheme.colorScheme.primary
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

        NotificationPermissionBannerCard()

        // Clean Horizontal Quick-Service Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickServicePill(
                icon = Icons.Filled.DirectionsCar,
                title = "Book Ride",
                subtitle = "Toto, Auto, Cab",
                onClick = { onOpenBookRide("now") },
                modifier = Modifier.testTag("home_hero_book_ride")
            )
            QuickServicePill(
                icon = Icons.Filled.DirectionsBus,
                title = "Private Bus",
                subtitle = "Seat & QR Pass",
                onClick = onOpenBusBooking,
                modifier = Modifier.testTag("home_private_bus_button")
            )
            QuickServicePill(
                icon = Icons.Filled.Train,
                title = "Local Services",
                subtitle = "Station & Stand",
                onClick = { onOpenBookRide("station_pickup") }
            )
            QuickServicePill(
                icon = Icons.Filled.Explore,
                title = "Explore",
                subtitle = "Verified Fleet",
                onClick = onOpenExplore
            )
        }

        // Recent Bookings Preview
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Recent Trips", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onOpenBookings) {
                Text("See All")
            }
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
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val fare = if (booking.agreedFareInr > 0) booking.agreedFareInr else booking.estimatedFareInr
                            Text(
                                text = "₹${fare.toInt()}",
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = JetBrainsMono,
                                color = MaterialTheme.colorScheme.primary
                            )
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
                    }
                }
            }
        }

        // Compact Safety Bar
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("24x7 Safety Desk", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Helpline: ${platformSettings?.helplineNumber ?: "+91-1800-103-0000"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onOpenHelp,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Support")
                }
                Button(
                    onClick = onQuickSos,
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("home_sos_button")
                ) {
                    Text("SOS")
                }
            }
        }
    }

    if (showCallDialog && activeBooking != null && activeCallContact != null && activeCallContact.driverPhone.isNotBlank()) {
        CallPeerConfirmDialog(
            peerLabel = activeBooking.assignedDriverName.ifBlank { "Driver" },
            phoneNumber = activeCallContact.driverPhone,
            onDismiss = { showCallDialog = false }
        )
    }

    if (showChatSheet && activeBooking != null) {
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
private fun QuickServicePill(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(126.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
