package com.example.customer

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Commute
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ElectricRickshaw
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.UiState
import com.example.domain.AbuseDetectionEngine
import com.example.domain.FareCalculator
import com.example.map.GolocalMapContainer
import com.example.map.RouteUiState
import com.example.shared.BookingChatModalSheet
import com.example.shared.CallPeerConfirmDialog
import com.example.shared.CircularBackButton
import com.example.shared.LocationCoordinator
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalLightBlue
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.GolocalWarningContainer
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.CustomerViewModel
import kotlin.math.roundToInt

private enum class RideSortFilter(val label: String) {
    RECOMMENDED("Recommended"),
    FASTER("Faster"),
    CHEAPER("Cheaper"),
    ALL("All")
}

private data class VehicleOptionUi(
    val id: String,
    val title: String,
    val tagline: String,
    val seatsLabel: String,
    val speedFactor: Double,
    val recommendedRank: Int,
    val icon: ImageVector
)

private val VEHICLE_OPTIONS = listOf(
    VehicleOptionUi("toto", "Toto / E-Rickshaw", "Everyday short local rides", "3 Seats", 1.15, 0, Icons.Filled.ElectricRickshaw),
    VehicleOptionUi("auto", "Auto Rickshaw", "Quick street & station hops", "3 Seats", 1.0, 1, Icons.Filled.LocalTaxi),
    VehicleOptionUi("bike", "Bike Taxi", "Fastest solo commuter option", "1 Seat", 0.78, 2, Icons.Filled.TwoWheeler),
    VehicleOptionUi("cab", "AC Cab", "Comfortable family rides", "4 Seats", 0.92, 3, Icons.Filled.DirectionsCar),
    VehicleOptionUi("outstation", "Outstation Cab", "Inter-district Bengal trips", "4 Seats", 0.95, 4, Icons.Filled.Commute),
    VehicleOptionUi("scheduled", "Scheduled Cab", "Pre-booked arrival guarantee", "4 Seats", 0.95, 5, Icons.Filled.Schedule),
    VehicleOptionUi("parcel", "Local Parcel", "Verified local package drop", "Parcel", 1.0, 6, Icons.Filled.Inventory2)
)

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
    val serviceAreas by customerViewModel.serviceAreas.collectAsStateWithLifecycle()
    val bookingsState by customerViewModel.myBookingsState.collectAsStateWithLifecycle()
    val activeBooking by customerViewModel.activeBooking.collectAsStateWithLifecycle()
    val activeOffers by customerViewModel.activeOffers.collectAsStateWithLifecycle()
    val activeTripLocation by customerViewModel.activeTripLocation.collectAsStateWithLifecycle()
    val activeChatMessages by customerViewModel.activeChatMessages.collectAsStateWithLifecycle()
    val unreadChatCount by customerViewModel.unreadChatCount.collectAsStateWithLifecycle()
    val activeCallContact by customerViewModel.activeCallContact.collectAsStateWithLifecycle()
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val accountState by sessionViewModel.userAccountState.collectAsStateWithLifecycle()
    val customerProfile by sessionViewModel.customerProfileState.collectAsStateWithLifecycle()
    val gps by sessionViewModel.gpsLocation.collectAsStateWithLifecycle()
    val isSubmitting by customerViewModel.isSubmitting.collectAsStateWithLifecycle()
    val platformSettings by customerViewModel.platformSettings.collectAsStateWithLifecycle()
    val isVerifyingTestOtp by customerViewModel.isVerifyingTestOtp.collectAsStateWithLifecycle()
    val testOtpError by customerViewModel.testOtpError.collectAsStateWithLifecycle()
    val activeRideOtpSecret by customerViewModel.activeRideOtpSecret.collectAsStateWithLifecycle()

    val customerAccount = (accountState as? UiState.Success)?.data
    val customerInvoiceEmail = customerAccount?.email.orEmpty()
    val customerUid = customerAccount?.uid.orEmpty()
    val customerPhone = customerAccount?.effectiveMobileNumber().orEmpty()
    val isAuthorizedTestCustomer = customerAccount?.isTestCustomer == true
    val canSelectTestRide = platformSettings?.testRideModeEnabled == true && isAuthorizedTestCustomer
    val allBookings = (bookingsState as? UiState.Success)?.data.orEmpty()

    LaunchedEffect(canSelectTestRide) {
        if (!canSelectTestRide && draft.bookAsTestRide) {
            customerViewModel.setBookAsTestRide(false)
        }
    }

    var searchTarget by remember { mutableStateOf<LocationFieldTarget?>(null) }
    var selectedFilter by remember { mutableStateOf(RideSortFilter.RECOMMENDED) }
    var showServiceOptionsSheet by remember { mutableStateOf(false) }
    var showPaymentDialog by remember { mutableStateOf(false) }
    var showSosDialog by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showComplaintDialog by remember { mutableStateOf(false) }
    var showCallDialog by remember { mutableStateOf(false) }
    var showChatSheet by remember { mutableStateOf(false) }
    var routeUiState by remember { mutableStateOf<RouteUiState>(RouteUiState.Idle) }

    LaunchedEffect(activeBooking?.id, customerPhone) {
        val active = activeBooking
        if (active != null && !active.isTestRide && !active.testMode && customerPhone.isNotBlank()) {
            customerViewModel.syncMyContactForActiveBooking(customerPhone)
        }
    }

    BackHandler {
        if (searchTarget != null) searchTarget = null else onBackHome()
    }

    if (searchTarget != null) {
        CustomerLocationSearchSheet(
            initialTarget = searchTarget!!,
            draft = draft,
            gps = gps,
            customerProfile = customerProfile,
            recentBookings = allBookings,
            serviceAreas = serviceAreas,
            onlineDrivers = drivers,
            lowNetworkMode = prefs.lowNetworkMode,
            onSelectCatalogPlace = { target, place ->
                if (target == LocationFieldTarget.PICKUP) customerViewModel.setPickupPlace(place)
                else customerViewModel.setDestinationPlace(place)
            },
            onSelectCoordinates = { target, lbl, lat, lng, dist ->
                if (target == LocationFieldTarget.PICKUP) {
                    customerViewModel.setPickupCoordinates(lbl, lat, lng, dist)
                } else {
                    customerViewModel.setDestinationCoordinates(lbl, lat, lng, dist)
                }
            },
            onSwapLocations = { customerViewModel.swapLocations() },
            onRequestGps = { sessionViewModel.detectDeviceLocation(context) },
            onClose = { searchTarget = null }
        )
        return
    }

    val currentBooking = activeBooking
    if (currentBooking != null) {
        CustomerActiveRideView(
            booking = currentBooking,
            offers = activeOffers,
            drivers = drivers,
            tripLocation = activeTripLocation,
            lowNetwork = prefs.lowNetworkMode,
            callContact = activeCallContact,
            unreadChatCount = unreadChatCount,
            onBackHome = onBackHome,
            onOpenCall = { showCallDialog = true },
            onOpenChat = {
                customerViewModel.markActiveChatRead()
                showChatSheet = true
            },
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
            onOpenCancel = { showCancelDialog = true },
            isVerifyingTestOtp = isVerifyingTestOtp,
            testOtpError = testOtpError,
            rideOtpSecret = activeRideOtpSecret,
            onVerifyTestOtp = { otp -> customerViewModel.verifyTestRideOtp(currentBooking, otp) },
            onClearTestOtpError = { customerViewModel.clearTestOtpError() }
        )
    } else {
        // Route Preview & Vehicle Selection Screen (modeled on Image 1 Panels 1 & 2)
        val pickupLat = draft.pickupPlace.lat
        val pickupLng = draft.pickupPlace.lng
        val pickupLabel = draft.customPickupLabel.ifBlank { draft.pickupPlace.nameEn }
        val destLabel = draft.customDestinationLabel.ifBlank { draft.destinationPlace.nameEn }
        val roadDistanceKm = (routeUiState as? RouteUiState.Ready)?.distanceKm ?: quote.distanceKm
        val roadDurationMin = (routeUiState as? RouteUiState.Ready)?.durationMin ?: quote.estimatedDurationMin

        val sortedVehicles = remember(selectedFilter, fareConfigs, quote.distanceKm, draft.isRoundTrip) {
            val enriched = VEHICLE_OPTIONS.map { opt ->
                val cfg = fareConfigs.firstOrNull { it.vehicleCategory == opt.id }
                val enabled = cfg?.isEnabled ?: (opt.id != "parcel")
                val est = if (cfg != null) {
                    FareCalculator.calculateFareQuote(quote.distanceKm, cfg, draft.isRoundTrip).estimatedFareInr
                } else 0.0
                Triple(opt, enabled, est)
            }
            when (selectedFilter) {
                RideSortFilter.RECOMMENDED -> enriched.sortedBy { (!it.second).compareTo(false) * 100 + it.first.recommendedRank }
                RideSortFilter.FASTER -> enriched.sortedBy { if (!it.second) 999.0 else it.first.speedFactor }
                RideSortFilter.CHEAPER -> enriched.sortedBy { if (!it.second || it.third <= 0.0) Double.MAX_VALUE else it.third }
                RideSortFilter.ALL -> enriched
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val bottomSheetHeight = (maxHeight * 0.56f).coerceIn(340.dp, 540.dp)

            GolocalMapContainer(
                pickupLat = pickupLat,
                pickupLng = pickupLng,
                pickupLabel = pickupLabel,
                destLat = draft.destinationPlace.lat,
                destLng = draft.destinationPlace.lng,
                destLabel = destLabel,
                onlineDrivers = drivers,
                liveTripLocation = null,
                lowNetworkMode = prefs.lowNetworkMode,
                fillContainer = true,
                cornerRadius = 0.dp,
                showBorder = false,
                showTopRouteChip = false,
                recenterBottomPadding = bottomSheetHeight + 12.dp,
                userLat = gps.lat,
                userLng = gps.lng,
                onRouteComputed = { routeUiState = it }
            )

            // Floating Top Back + Destination & Pickup Pills
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularBackButton(onClick = onBackHome, testTag = "booking_back_button")
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            text = "${String.format(java.util.Locale.US, "%.1f", roadDistanceKm)} km • ~$roadDurationMin min",
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = JetBrainsMono,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = { searchTarget = LocationFieldTarget.PICKUP },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        shadowElevation = 5.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("booking_pickup_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Filled.TripOrigin, contentDescription = null, tint = GolocalSuccess, modifier = Modifier.size(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Pickup", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(pickupLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    Surface(
                        onClick = { searchTarget = LocationFieldTarget.DESTINATION },
                        shape = RoundedCornerShape(14.dp),
                        color = GolocalDeepNavy,
                        shadowElevation = 5.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("booking_destination_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = GolocalLightBlue, modifier = Modifier.size(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Destination", style = MaterialTheme.typography.labelSmall, color = GolocalLightBlue)
                                Text(destLabel, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            // Bottom Sheet: Choose a Ride + Sort Filters + Pinned Fare & Find Rider Bar
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = bottomSheetHeight),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(top = 10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(42.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Choose a ride:",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (platformSettings?.testRideModeEnabled == true) {
                                GolocalStatusBadge(
                                    label = "TEST RIDE MODE",
                                    tone = "warning",
                                    modifier = Modifier.testTag("booking_test_ride_mode_pill")
                                )
                            }
                        }
                        Surface(
                            onClick = { showServiceOptionsSheet = true },
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.testTag("booking_service_options_chip")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(14.dp))
                                Text(
                                    text = formatBookingTypeLabel(draft.bookingType),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Sort Filter Pills (Recommended / Faster / Cheaper / All)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RideSortFilter.entries.forEach { filter ->
                            val selected = selectedFilter == filter
                            val icon = when (filter) {
                                RideSortFilter.RECOMMENDED -> Icons.Filled.Star
                                RideSortFilter.FASTER -> Icons.Filled.Bolt
                                RideSortFilter.CHEAPER -> Icons.Filled.Savings
                                RideSortFilter.ALL -> Icons.Filled.DirectionsCar
                            }
                            Surface(
                                onClick = { selectedFilter = filter },
                                shape = RoundedCornerShape(999.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(999.dp)
                                    )
                                    .testTag("ride_filter_${filter.name.lowercase()}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = filter.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Scrollable Vehicle Options
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        items(sortedVehicles, key = { it.first.id }) { (option, enabled, estFare) ->
                            val onlineInCategory = drivers.count { it.isOnline && it.vehicleCategory == option.id }
                            val realDriverEta = LocationCoordinator.estimateNearestDriverEtaMinutes(
                                onlineDrivers = drivers,
                                pickupLat = pickupLat,
                                pickupLng = pickupLng,
                                category = option.id
                            )
                            val tripEstMinutes = (roadDurationMin * option.speedFactor).roundToInt().coerceAtLeast(2)
                            val availabilityText = when {
                                !enabled -> "Admin approval required"
                                realDriverEta != null -> "$realDriverEta min away • $onlineInCategory online"
                                onlineInCategory > 0 -> "$onlineInCategory rider(s) online"
                                else -> "$tripEstMinutes min ride • Dispatching nearby"
                            }

                            val isSelected = draft.selectedCategory == option.id
                            Surface(
                                onClick = { customerViewModel.selectCategory(option.id) },
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) GolocalPrimaryBlue else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .testTag("vehicle_card_${option.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isSelected) GolocalPrimaryBlue else MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = option.icon,
                                            contentDescription = option.title,
                                            tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = option.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = option.tagline,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "$availabilityText • ${option.seatsLabel}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (enabled) MaterialTheme.colorScheme.primary else GolocalError,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = if (enabled) "₹${estFare.toInt()}" else "Locked",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontFamily = JetBrainsMono,
                                        fontWeight = FontWeight.Bold,
                                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Pinned Payment + Fare Band + Find Rider Dock
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 10.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    onClick = { showPaymentDialog = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.testTag("payment_method_selector")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (draft.paymentMethod == "upi") Icons.Filled.QrCode2 else Icons.Filled.Payments,
                                            contentDescription = null,
                                            tint = GolocalPrimaryBlue,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = if (draft.paymentMethod == "upi") "UPI (Webhook Pending)" else "Cash at Drop",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "₹${quote.estimatedFareInr.toInt()}",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontFamily = JetBrainsMono,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Band ₹${quote.minPermittedFareInr.toInt()}–₹${quote.maxPermittedFareInr.toInt()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = platformSettings?.cancellationPolicySummary?.takeIf { it.isNotBlank() }
                                    ?: "Free cancellation pre-arrival. Nominal fee applies after arrival or waiting.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (canSelectTestRide) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = GolocalWarningContainer,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, GolocalWarning, RoundedCornerShape(12.dp))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "TEST RIDE MODE (Authorized Account)",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = GolocalDeepNavy
                                            )
                                            Text(
                                                text = "Non-commercial ₹0 simulated or test-partner trip",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = GolocalDeepNavy.copy(alpha = 0.78f)
                                            )
                                        }
                                        Switch(
                                            checked = draft.bookAsTestRide,
                                            onCheckedChange = { customerViewModel.setBookAsTestRide(it) },
                                            modifier = Modifier.testTag("book_as_test_ride_switch")
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    customerViewModel.submitBooking(
                                        invoiceEmail = customerInvoiceEmail,
                                        isAuthorizedTestCustomer = isAuthorizedTestCustomer
                                    )
                                },
                                enabled = !isSubmitting,
                                colors = ButtonDefaults.buttonColors(containerColor = GolocalPrimaryBlue),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .testTag("submit_booking_button"),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                if (isSubmitting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(
                                        text = if (canSelectTestRide && draft.bookAsTestRide) "Start Test Ride" else "Find Rider",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (canSelectTestRide) {
                                    OutlinedButton(
                                        onClick = {
                                            customerViewModel.submitSampleRideBooking(
                                                context = context,
                                                quote = quote,
                                                pickupLabel = pickupLabel,
                                                pickupLat = pickupLat,
                                                pickupLng = pickupLng,
                                                destinationLabel = destLabel,
                                                destinationLat = draft.destinationPlace.lat,
                                                destinationLng = draft.destinationPlace.lng,
                                                vehicleCategory = draft.selectedCategory,
                                                onCreated = onBackHome
                                            )
                                        },
                                        enabled = !isSubmitting,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag("submit_sample_ride_button"),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Text(
                                            text = "Dispatch Sample Ride (Isolated)",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showServiceOptionsSheet) {
        AlertDialog(
            onDismissRequest = { showServiceOptionsSheet = false },
            title = { Text("Service Type & Trip Details") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { customerViewModel.setBookingType(id) }
                        ) {
                            RadioButton(selected = draft.bookingType == id, onClick = { customerViewModel.setBookingType(id) })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    if (draft.bookingType in setOf("station_pickup", "scheduled", "native_village", "airport_transfer", "bus_stand_pickup")) {
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
                            label = { Text("Arrival Date & Time (e.g. Today 06:30 PM)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    if (draft.bookingType == "family") {
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
                            Text("Passenger consented to receive Trip PIN & updates.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { showServiceOptionsSheet = false }) { Text("Done") } }
        )
    }

    if (showPaymentDialog) {
        AlertDialog(
            onDismissRequest = { showPaymentDialog = false },
            title = { Text("Choose Payment Method") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { customerViewModel.setPaymentMethod("cash"); showPaymentDialog = false }
                    ) {
                        RadioButton(selected = draft.paymentMethod == "cash", onClick = { customerViewModel.setPaymentMethod("cash"); showPaymentDialog = false })
                        Text("Cash at Drop")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { customerViewModel.setPaymentMethod("upi"); showPaymentDialog = false }
                    ) {
                        RadioButton(selected = draft.paymentMethod == "upi", onClick = { customerViewModel.setPaymentMethod("upi"); showPaymentDialog = false })
                        Column {
                            Text("UPI Payment")
                            Text(
                                "Note: Online UPI gateway webhook requires backend configuration; settles as pending_verification.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPaymentDialog = false }) { Text("Close") } }
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
        var selectedCategory by remember { mutableStateOf(AbuseDetectionEngine.customerCancellationReasons.first()) }
        var extraNote by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel Ride?") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Select a reason so we can improve pickup reliability:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AbuseDetectionEngine.customerCancellationReasons.forEach { reasonOpt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedCategory = reasonOpt }
                                .padding(vertical = 2.dp)
                        ) {
                            RadioButton(selected = selectedCategory == reasonOpt, onClick = { selectedCategory = reasonOpt })
                            Text(reasonOpt, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    OutlinedTextField(
                        value = extraNote,
                        onValueChange = { extraNote = it },
                        label = { Text("Additional details (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = GolocalError),
                    onClick = {
                        val combined = if (extraNote.isBlank()) selectedCategory else "$selectedCategory: ${extraNote.trim()}"
                        customerViewModel.cancelBooking(activeBooking!!.id, combined, selectedCategory)
                        showCancelDialog = false
                    },
                    modifier = Modifier.testTag("confirm_cancel_ride_button")
                ) { Text("Confirm Cancel") }
            },
            dismissButton = { TextButton(onClick = { showCancelDialog = false }) { Text("Keep Ride") } }
        )
    }

    if (showCallDialog && activeBooking != null && activeCallContact != null && activeCallContact!!.driverPhone.isNotBlank()) {
        CallPeerConfirmDialog(
            peerLabel = activeBooking!!.assignedDriverName.ifBlank { "Driver" },
            phoneNumber = activeCallContact!!.driverPhone,
            onDismiss = { showCallDialog = false },
            onOpenChat = {
                customerViewModel.markActiveChatRead()
                showChatSheet = true
            }
        )
    }

    if (showChatSheet && activeBooking != null) {
        BookingChatModalSheet(
            booking = activeBooking!!,
            viewerUid = customerUid,
            viewerRole = "customer",
            messages = activeChatMessages,
            onSendMessage = { text -> customerViewModel.sendChatMessage(activeBooking!!, text) },
            onMarkRead = { customerViewModel.markActiveChatRead() },
            onDismiss = { showChatSheet = false }
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

private fun formatBookingTypeLabel(type: String): String = when (type) {
    "station_pickup" -> "Station"
    "bus_stand_pickup" -> "Bus Stand"
    "airport_transfer" -> "Airport"
    "village_to_town" -> "Village"
    "scheduled" -> "Scheduled"
    "family" -> "Family"
    "return_trip" -> "Round Trip"
    else -> "Local Ride"
}
