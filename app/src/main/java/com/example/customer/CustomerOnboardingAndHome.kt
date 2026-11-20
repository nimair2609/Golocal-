package com.example.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Booking
import com.example.data.model.BookingCallContact
import com.example.data.model.BookingChatMessage
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.PlatformSettings
import com.example.data.model.TripLocation
import com.example.data.model.UiState
import com.example.ui.components.GoLocalLogoMark
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalLightBlue
import com.example.ui.theme.GolocalSoftBlue
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalPureWhite

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
                    colors = listOf(GolocalPureWhite, GolocalSoftBlue, GolocalLightBlue.copy(alpha = 0.72f))
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(22.dp)
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
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = GolocalPureWhite,
                    shadowElevation = 3.dp
                ) {
                    GoLocalLogoMark(modifier = Modifier.size(38.dp))
                }
                Column {
                    Text(
                        text = "GoLocal",
                        style = MaterialTheme.typography.titleMedium,
                        color = GolocalDeepNavy,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Your Local. Our Priority.",
                        style = MaterialTheme.typography.labelSmall,
                        color = GolocalPrimaryBlue
                    )
                }
            }
            TextButton(onClick = onComplete) {
                Text("Skip", color = GolocalPrimaryBlue, fontWeight = FontWeight.SemiBold)
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 500.dp)
                .align(Alignment.Center)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(GolocalPrimaryBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = GolocalPureWhite,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
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
                                .width(if (idx == step) 28.dp else 8.dp)
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
                        .height(54.dp)
                        .testTag("customer_onboarding_done"),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = if (step < slides.lastIndex) "Continue" else "Get Started",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
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
    CustomerHomeMapScreen(
        firstName = firstName,
        unreadNotifications = unreadNotifications,
        activeBooking = activeBooking,
        bookingsState = bookingsState,
        verifiedDrivers = verifiedDrivers,
        platformSettings = platformSettings,
        onOpenBookRide = onOpenBookRide,
        onOpenExplore = onOpenExplore,
        onOpenBookings = onOpenBookings,
        onOpenNotifications = onOpenNotifications,
        onOpenHelp = onOpenHelp,
        onQuickSos = onQuickSos,
        currentUserId = currentUserId,
        activeTripLocation = activeTripLocation,
        lowNetworkMode = lowNetworkMode,
        activeCallContact = activeCallContact,
        activeChatMessages = activeChatMessages,
        unreadChatCount = unreadChatCount,
        onSendChatMessage = onSendChatMessage,
        onMarkChatRead = onMarkChatRead,
        onOpenBusBooking = onOpenBusBooking,
        userLat = userLat,
        userLng = userLng,
        gpsError = gpsError,
        onRequestLocationDetect = onRequestLocationDetect
    )
}
