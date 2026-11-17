package com.example.customer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.Booking
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.PlatformSettings
import com.example.data.model.UiState
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
    onOpenBus: () -> Unit = {},
    onOpenExplore: () -> Unit,
    onOpenBookings: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenHelp: () -> Unit,
    onQuickSos: () -> Unit
) {
    val scroll = rememberScrollState()
    val onlineCount = verifiedDrivers.count { it.isOnline }
    val allBookings = (bookingsState as? UiState.Success)?.data.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Personalized Welcome Header
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
                GoLocalLogoMark(modifier = Modifier.size(40.dp))
                Column {
                    Text(
                        text = "Hello, $firstName",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Where would you like to go?",
                        style = MaterialTheme.typography.bodyMedium,
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

        // Prominent Search Bar Pill
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                .clickable { onOpenBookRide(null) }
                .testTag("home_search_destination"),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Search destination, station, bus stand, village...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Royal Blue Hero Card (matching mockup)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .clickable { onOpenBookRide("now") }
                .testTag("home_hero_book_ride"),
            shape = RoundedCornerShape(22.dp),
            shadowElevation = 4.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(GolocalDeepNavy, GolocalPrimaryBlue, GolocalSecondaryBlue)
                        )
                    )
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Book Your Ride Anywhere, Anytime",
                            style = MaterialTheme.typography.titleLarge,
                            color = GolocalPureWhite
                        )
                        Text(
                            text = "Safe • Fast • Affordable • Verified Locals",
                            style = MaterialTheme.typography.labelMedium,
                            color = GolocalLightBlue
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = GolocalPureWhite,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (activeBooking != null) "View Active Ride" else "Book Now",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = GolocalPrimaryBlue
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = GolocalPrimaryBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    Image(
                        painter = painterResource(id = R.drawable.img_golocal_hero),
                        contentDescription = "GoLocal Ride",
                        modifier = Modifier
                            .size(92.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }

        // Active Trip Highlight Banner (if any)
        if (activeBooking != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                    .clickable { onOpenBookRide(null) },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        GolocalStatusBadge(
                            label = "ACTIVE: ${activeBooking.status.uppercase()}",
                            tone = if (activeBooking.status == "searching") "warning" else "success"
                        )
                        Text(
                            text = "${activeBooking.pickupLabel} → ${activeBooking.destinationLabel}",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (activeBooking.tripPin.isNotBlank()) {
                            Text(
                                text = "Trip Start PIN: ${activeBooking.tripPin}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Open active ride",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 3 Quick Action Tiles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickTile(
                icon = Icons.Filled.DirectionsCar,
                title = "Book Ride",
                subtitle = "Toto, Auto, Cab",
                onClick = { onOpenBookRide("now") },
                modifier = Modifier.weight(1f)
            )
            QuickTile(
                icon = Icons.Filled.DirectionsBus,
                title = "Bus Tickets",
                subtitle = "Seats & QR Pass",
                onClick = onOpenBus,
                modifier = Modifier.weight(1f)
            )
            QuickTile(
                icon = Icons.Filled.Explore,
                title = "Explore",
                subtitle = "Find Drivers",
                onClick = onOpenExplore,
                modifier = Modifier.weight(1f)
            )
        }

        // Nearby Available Verified Services Card (Live Firestore data)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                .clickable(onClick = onOpenExplore)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(GolocalSuccessContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ElectricRickshaw,
                            contentDescription = null,
                            tint = GolocalSuccess
                        )
                    }
                    Column {
                        Text("Nearby Verified Services", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (onlineCount > 0) "$onlineCount verified riders currently online nearby"
                            else "No riders online right now • Schedule or send a request anytime",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }

        // Recent Bookings Section (Real Firestore Data)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Recent Bookings", style = MaterialTheme.typography.titleMedium)
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
                                text = "${booking.pickupLabel} → ${booking.destinationLabel}",
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

        // Emergency & Support Quick Access
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Safety & Community Support", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "24x7 Helpline: ${platformSettings?.helplineNumber ?: "+91-1800-103-0000"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onQuickSos,
                        colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("home_sos_button")
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Emergency SOS")
                    }
                    OutlinedButton(
                        onClick = onOpenHelp,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Filled.SupportAgent, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Help Desk")
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
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
