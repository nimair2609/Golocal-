package com.example.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Booking
import com.example.data.model.BusBooking
import com.example.data.model.UiState
import com.example.shared.BookingChatModalSheet
import com.example.shared.NotificationPermissionBannerCard
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalGoldStar
import com.example.ui.theme.GolocalLightBlue
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalPureWhite
import com.example.ui.theme.GolocalRoyalBlueBright
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.GolocalSuccessContainer
import com.example.ui.theme.GolocalWarning
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.CustomerViewModel
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun CustomerBookingsHistoryTab(
    customerViewModel: CustomerViewModel,
    onOpenBookRide: () -> Unit,
    onOpenBusBooking: () -> Unit = {}
) {
    val bookingsState by customerViewModel.myBookingsState.collectAsStateWithLifecycle()
    val payments by customerViewModel.myPayments.collectAsStateWithLifecycle()
    val ratings by customerViewModel.myRatings.collectAsStateWithLifecycle()
    val appeals by customerViewModel.myAppeals.collectAsStateWithLifecycle()
    val myBusBookings by customerViewModel.myBusBookings.collectAsStateWithLifecycle()
    val archivedMessages by customerViewModel.archivedChatMessages.collectAsStateWithLifecycle()

    var subTab by remember { mutableIntStateOf(0) }
    var rideStatusFilter by remember { mutableStateOf("all") }
    var ratingTarget by remember { mutableStateOf<Booking?>(null) }
    var refundTarget by remember { mutableStateOf<Booking?>(null) }
    var complaintTarget by remember { mutableStateOf<Booking?>(null) }
    var appealTarget by remember { mutableStateOf<Booking?>(null) }
    var chatArchiveTarget by remember { mutableStateOf<Booking?>(null) }
    var qrTicketDialog by remember { mutableStateOf<BusBooking?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Bookings & Transactions",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        TabRow(
            selectedTabIndex = subTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Rides", maxLines = 1) })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Buses", maxLines = 1) })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text("Payments", maxLines = 1) })
            Tab(selected = subTab == 3, onClick = { subTab = 3 }, text = { Text("Ratings", maxLines = 1) })
        }

        when (subTab) {
            0 -> {
                val allList = (bookingsState as? UiState.Success)?.data.orEmpty()
                val activeStatuses = setOf("searching", "assigned", "arriving", "arrived", "in_progress")
                val filteredList = remember(allList, rideStatusFilter) {
                    when (rideStatusFilter) {
                        "active" -> allList.filter { it.status in activeStatuses && it.bookingType != "scheduled" }
                        "upcoming" -> allList.filter { it.bookingType == "scheduled" && it.status in activeStatuses }
                        "completed" -> allList.filter { it.status == "completed" }
                        "cancelled" -> allList.filter { it.status == "cancelled" }
                        else -> allList
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "all" to "All",
                        "active" to "Active",
                        "upcoming" to "Upcoming",
                        "completed" to "Completed",
                        "cancelled" to "Cancelled"
                    ).forEach { (id, label) ->
                        val selected = rideStatusFilter == id
                        Surface(
                            onClick = { rideStatusFilter = id },
                            shape = RoundedCornerShape(999.dp),
                            color = if (selected) GolocalPrimaryBlue else MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .border(
                                    1.dp,
                                    if (selected) GolocalPrimaryBlue else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(999.dp)
                                )
                                .testTag("booking_filter_$id")
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) GolocalPureWhite else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                if (bookingsState is UiState.Loading) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (filteredList.isEmpty()) {
                    GolocalEmptyState(
                        title = "No Booking History Yet",
                        message = if (rideStatusFilter == "all") {
                            "Your completed, scheduled, and active West Bengal trips will appear here."
                        } else {
                            "No $rideStatusFilter rides found yet. Book a ride to get started."
                        },
                        actionLabel = "Book a Ride",
                        onAction = onOpenBookRide
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(filteredList, key = { it.id }) { booking ->
                            val isTestRide = booking.isTestRide || booking.testMode
                            val isActive = booking.status in activeStatuses
                            val fare = if (booking.agreedFareInr > 0) booking.agreedFareInr else booking.estimatedFareInr
                            val dateLabel = remember(booking.createdAt) {
                                booking.createdAt?.toDate()?.let {
                                    SimpleDateFormat("dd MMM • hh:mm a", Locale.getDefault()).format(it)
                                } ?: ""
                            }
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 1.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "#GL${booking.id.take(8).uppercase()}",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontFamily = JetBrainsMono,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isTestRide) {
                                                GolocalStatusBadge(label = "TEST RIDE", tone = "warning")
                                            }
                                            GolocalStatusBadge(
                                                label = booking.status.replace('_', ' ').replaceFirstChar { it.uppercase() },
                                                tone = when (booking.status) {
                                                    "completed" -> "success"
                                                    "cancelled" -> "error"
                                                    "searching" -> "warning"
                                                    else -> "info"
                                                }
                                            )
                                        }
                                    }

                                    // Pickup -> Drop Vertical Timeline
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(9.dp)
                                                    .clip(CircleShape)
                                                    .background(GolocalPrimaryBlue)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .width(2.dp)
                                                    .height(22.dp)
                                                    .background(MaterialTheme.colorScheme.outline)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .size(9.dp)
                                                    .clip(CircleShape)
                                                    .background(GolocalError)
                                            )
                                        }
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Column {
                                                Text(
                                                    text = "Pickup",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = booking.pickupLabel,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Column {
                                                Text(
                                                    text = "Drop",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = booking.destinationLabel,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }

                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${booking.vehicleCategory.replaceFirstChar { it.uppercase() }} • ₹${fare.toInt()} • ${booking.distanceKm} km",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (dateLabel.isNotBlank() || booking.assignedDriverName.isNotBlank()) {
                                                Text(
                                                    text = listOfNotNull(
                                                        dateLabel.takeIf { it.isNotBlank() },
                                                        booking.assignedDriverName.takeIf { it.isNotBlank() }?.let { "Rider: $it" }
                                                    ).joinToString(" • "),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        Text(
                                            text = booking.paymentMethod.uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = JetBrainsMono,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    if (isActive) {
                                        OutlinedButton(
                                            onClick = onOpenBookRide,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                        ) {
                                            Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Track Ride", fontWeight = FontWeight.SemiBold)
                                        }
                                    }

                                    if (booking.status == "completed") {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (booking.customerRating == null) {
                                                OutlinedButton(onClick = { ratingTarget = booking }) { Text("Rate Trip") }
                                            } else {
                                                GolocalStatusBadge(label = "Rated ★ ${booking.customerRating}", tone = "success")
                                            }
                                            if (!isTestRide) {
                                                TextButton(onClick = { refundTarget = booking }) { Text("Refund") }
                                                TextButton(onClick = { complaintTarget = booking }) { Text("Dispute", color = GolocalError) }
                                            }
                                        }
                                    }
                                    if (booking.status == "cancelled") {
                                        val existingAppeal = appeals.firstOrNull { it.bookingId == booking.id }
                                        if (booking.cancellationReason.isNotBlank()) {
                                            Text(
                                                "Reason: ${booking.cancellationReason}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (!isTestRide) {
                                            if (existingAppeal != null) {
                                                GolocalStatusBadge(
                                                    label = "APPEAL: ${existingAppeal.status.uppercase()}",
                                                    tone = if (existingAppeal.status == "approved") "success" else "warning"
                                                )
                                            } else {
                                                OutlinedButton(onClick = { appealTarget = booking }) {
                                                    Text("Appeal Cancellation")
                                                }
                                            }
                                        }
                                    }
                                    if (!isTestRide && booking.assignedDriverId.isNotBlank() && booking.status in setOf("completed", "cancelled")) {
                                        TextButton(
                                            onClick = {
                                                customerViewModel.openChatArchive(booking.id)
                                                chatArchiveTarget = booking
                                            }
                                        ) {
                                            Text("View Chat Archive")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                if (myBusBookings.isEmpty()) {
                    GolocalEmptyState(
                        title = "No Bus Tickets Yet",
                        message = "Reserve seats on approved West Bengal private buses to see digital QR boarding passes.",
                        actionLabel = "Browse Private Buses",
                        onAction = onOpenBusBooking
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(myBusBookings, key = { it.id }) { ticket ->
                            BusTicketSummaryCard(
                                ticket = ticket,
                                onShowQr = { qrTicketDialog = ticket },
                                onCancel = { customerViewModel.cancelBusBooking(ticket, "Cancelled by passenger") }
                            )
                        }
                    }
                }
            }
            2 -> {
                if (payments.isEmpty()) {
                    GolocalEmptyState(
                        title = "No Payment Transactions",
                        message = "Completed rides generate digital GST receipts and payment records here."
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(payments, key = { it.id }) { pay ->
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Booking #${pay.bookingId.take(8).uppercase()}",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontFamily = JetBrainsMono,
                                            fontWeight = FontWeight.Bold
                                        )
                                        GolocalStatusBadge(
                                            label = pay.status.replace('_', ' ').uppercase(),
                                            tone = if (pay.status == "settled_cash" || pay.status == "paid") "success" else "warning"
                                        )
                                    }
                                    Text(
                                        "Gross Paid: ₹${pay.grossFareInr.toInt()} (incl. GST ₹${pay.gstInr})",
                                        fontFamily = JetBrainsMono,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "Method: ${pay.paymentMethod.uppercase()} • Ref: ${pay.gatewayReference}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (pay.invoiceEmail.isNotBlank()) {
                                        Text(
                                            text = "Invoice delivery email: ${pay.invoiceEmail}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            3 -> {
                if (ratings.isEmpty()) {
                    GolocalEmptyState(
                        title = "No Ratings Submitted",
                        message = "Rate your completed GoLocal rides from Booking History to build community trust."
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(ratings, key = { it.id }) { r ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "★ ${r.stars} / 5 • Booking #${r.bookingId.take(8).uppercase()}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "Behavior ${r.driverBehaviorRating}★ • Vehicle ${r.vehicleConditionRating}★ • Punctuality ${r.punctualityRating}★ • Safety ${r.safetyRating}★",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (r.review.isNotBlank()) Text(r.review, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    ratingTarget?.let { target ->
        var stars by remember { mutableIntStateOf(5) }
        var behaviorRating by remember { mutableIntStateOf(5) }
        var vehicleRating by remember { mutableIntStateOf(5) }
        var punctualityRating by remember { mutableIntStateOf(5) }
        var safetyRating by remember { mutableIntStateOf(5) }
        var review by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { ratingTarget = null },
            title = { Text("Rate Your Rider") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Overall Rating", style = MaterialTheme.typography.labelMedium)
                    StarSelectorRow(selected = stars, onSelect = { stars = it })
                    Text("Driver Behavior", style = MaterialTheme.typography.labelMedium)
                    StarSelectorRow(selected = behaviorRating, onSelect = { behaviorRating = it })
                    Text("Vehicle Condition", style = MaterialTheme.typography.labelMedium)
                    StarSelectorRow(selected = vehicleRating, onSelect = { vehicleRating = it })
                    Text("Punctuality", style = MaterialTheme.typography.labelMedium)
                    StarSelectorRow(selected = punctualityRating, onSelect = { punctualityRating = it })
                    Text("Safety & Route Adherence", style = MaterialTheme.typography.labelMedium)
                    StarSelectorRow(selected = safetyRating, onSelect = { safetyRating = it })
                    OutlinedTextField(
                        value = review,
                        onValueChange = { review = it },
                        label = { Text("Review comment (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    customerViewModel.submitRating(
                        booking = target,
                        stars = stars,
                        review = review,
                        driverBehaviorRating = behaviorRating,
                        vehicleConditionRating = vehicleRating,
                        punctualityRating = punctualityRating,
                        safetyRating = safetyRating
                    )
                    ratingTarget = null
                }) { Text("Submit Rating") }
            },
            dismissButton = { TextButton(onClick = { ratingTarget = null }) { Text("Cancel") } }
        )
    }

    appealTarget?.let { target ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { appealTarget = null },
            title = { Text("Appeal Cancellation") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Explain why this cancellation shouldn't count against you") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    customerViewModel.submitAppeal(target.id, reason)
                    appealTarget = null
                }) { Text("Submit Appeal") }
            },
            dismissButton = { TextButton(onClick = { appealTarget = null }) { Text("Cancel") } }
        )
    }

    chatArchiveTarget?.let { booking ->
        BookingChatModalSheet(
            booking = booking,
            viewerUid = booking.customerId,
            viewerRole = "customer",
            messages = archivedMessages,
            onSendMessage = {},
            onMarkRead = {},
            onDismiss = {
                customerViewModel.openChatArchive(null)
                chatArchiveTarget = null
            }
        )
    }

    qrTicketDialog?.let { ticket ->
        BusQrTicketDialog(ticket = ticket, onDismiss = { qrTicketDialog = null })
    }

    refundTarget?.let { target ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { refundTarget = null },
            title = { Text("Request Refund") },
            text = {
                OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("Reason for refund") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(onClick = {
                    val amt = if (target.agreedFareInr > 0) target.agreedFareInr else target.estimatedFareInr
                    customerViewModel.requestRefund(target.id, amt, reason)
                    refundTarget = null
                }) { Text("Request") }
            },
            dismissButton = { TextButton(onClick = { refundTarget = null }) { Text("Cancel") } }
        )
    }

    complaintTarget?.let { target ->
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { complaintTarget = null },
            title = { Text("File Trip Complaint") },
            text = {
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Describe the issue") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(onClick = {
                    customerViewModel.submitComplaint(target.id, "customer_dispute", desc)
                    complaintTarget = null
                }) { Text("Submit") }
            },
            dismissButton = { TextButton(onClick = { complaintTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StarSelectorRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..5).forEach { s ->
            FilterChip(
                selected = selected == s,
                onClick = { onSelect(s) },
                label = { Text("$s★") }
            )
        }
    }
}

@Composable
fun CustomerExploreTab(
    customerViewModel: CustomerViewModel,
    onSelectServicePreset: (bookingType: String, category: String) -> Unit,
    onOpenBusBooking: () -> Unit = {}
) {
    val drivers by customerViewModel.verifiedDrivers.collectAsStateWithLifecycle()
    val serviceAreas by customerViewModel.serviceAreas.collectAsStateWithLifecycle()
    var filterCat by remember { mutableStateOf("all") }

    val filteredDrivers = remember(drivers, filterCat) {
        if (filterCat == "all") drivers else drivers.filter { it.vehicleCategory == filterCat }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Explore Local Drivers & Services",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // Royal Blue Private Bus Tickets Hero Card
        Surface(
            onClick = onOpenBusBooking,
            shape = RoundedCornerShape(22.dp),
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("explore_private_bus_card")
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(GolocalDeepNavy, GolocalPrimaryBlue, GolocalRoyalBlueBright)
                        )
                    )
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "West Bengal Private Bus Tickets",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = GolocalPureWhite
                        )
                        Text(
                            text = "Book verified operator seats with official tariff & QR boarding pass.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GolocalLightBlue
                        )
                        Surface(
                            color = GolocalPureWhite,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Book Now",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
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
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(GolocalPureWhite.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DirectionsBus,
                            contentDescription = "Private Bus",
                            tint = GolocalPureWhite,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            }
        }

        // Local Mobility Services
        Text(
            text = "Local Mobility Services",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        val services = listOf(
            Triple("station_pickup", "Railway Station Pickups", "Howrah, Sealdah, Bandel, NJP, Barddhaman, Kharagpur"),
            Triple("bus_stand_pickup", "Bus Stand Transfers", "Esplanade, Arambagh, Durgapur City Centre, Siliguri"),
            Triple("airport_transfer", "Airport Transfers", "Kolkata (CCU), Andal (RDP), Bagdogra (IXB)"),
            Triple("village_to_town", "Village-to-Town & Hospitals", "Direct rural panchayat, bazaar & hospital links")
        )
        services.forEach { (type, title, desc) ->
            val serviceIcon = when (type) {
                "station_pickup" -> Icons.Filled.Train
                "bus_stand_pickup" -> Icons.Filled.DirectionsBus
                "airport_transfer" -> Icons.Filled.Flight
                else -> Icons.Filled.LocalHospital
            }
            Surface(
                onClick = { onSelectServicePreset(type, if (type == "airport_transfer") "cab" else "toto") },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = serviceIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Select $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Verified Local Driver Discovery
        Text(
            text = "Verified Local Riders (${filteredDrivers.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("all" to "All", "toto" to "Toto", "auto" to "Auto", "bike" to "Bike", "cab" to "Cab").forEach { (id, label) ->
                val selected = filterCat == id
                Surface(
                    onClick = { filterCat = id },
                    shape = RoundedCornerShape(999.dp),
                    color = if (selected) GolocalPrimaryBlue else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.border(
                        1.dp,
                        if (selected) GolocalPrimaryBlue else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(999.dp)
                    )
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) GolocalPureWhite else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        if (filteredDrivers.isEmpty()) {
            GolocalEmptyState(
                title = "No Verified Drivers Registered Yet",
                message = "As local Toto, Auto, Bike, and Cab partners pass admin KYC verification, they appear here."
            )
        } else {
            filteredDrivers.forEach { d ->
                val initials = d.displayName.trim().split(" ").filter { it.isNotBlank() }.take(2)
                    .joinToString("") { it.first().uppercaseChar().toString() }.ifBlank { "DR" }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = initials,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(d.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Icon(
                                    imageVector = Icons.Filled.Verified,
                                    contentDescription = "Verified Driver",
                                    tint = GolocalPrimaryBlue,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Text(
                                "${d.vehicleCategory.uppercase()} • ${d.vehicleModel} (${d.maskedPlate})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val statsParts = buildList {
                                if (d.district.isNotBlank()) add(d.district)
                                if (d.ratingAvg > 0.0) add("★ ${String.format(Locale.US, "%.1f", d.ratingAvg)}")
                                add("${d.completedRides} rides")
                            }
                            Text(
                                text = statsParts.joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            GolocalStatusBadge(label = if (d.isOnline) "ONLINE" else "OFFLINE", tone = if (d.isOnline) "success" else "info")
                            Button(
                                onClick = { onSelectServicePreset("now", d.vehicleCategory) },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.heightIn(min = 36.dp)
                            ) {
                                Text("Book", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        if (serviceAreas.isNotEmpty()) {
            Text(
                text = "Configured Service Areas (${serviceAreas.count { it.isActive }})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            serviceAreas.filter { it.isActive }.forEach { area ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(area.name, style = MaterialTheme.typography.titleSmall)
                            Text("${area.district} • ${area.zoneType.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (area.parcelApproved) {
                            GolocalStatusBadge(label = "Parcel OK", tone = "success")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerNotificationsTab(
    sessionViewModel: AppSessionViewModel,
    onOpenDeepLink: (com.example.notifications.NotificationDeepLinkTarget) -> Unit = {}
) {
    val notifications by sessionViewModel.notificationsState.collectAsStateWithLifecycle()
    val unreadIds = remember(notifications) { notifications.filter { !it.read }.map { it.id } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Notifications",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            if (unreadIds.isNotEmpty()) {
                TextButton(
                    onClick = { sessionViewModel.markAllNotificationsRead(unreadIds) },
                    modifier = Modifier.testTag("customer_mark_all_read_button")
                ) {
                    Icon(Icons.Filled.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Mark all read (${unreadIds.size})", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        NotificationPermissionBannerCard()
        if (notifications.isEmpty()) {
            GolocalEmptyState(
                title = "All Caught Up",
                message = "Ride confirmations, arrival alerts, chat messages, and safety alerts appear here."
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(notifications, key = { it.id }) { notif ->
                    val typeNorm = notif.type.lowercase()
                    val (iconVector, iconBg, iconTint) = when {
                        typeNorm.contains("complete") || typeNorm.contains("payment") ->
                            Triple(Icons.Filled.CheckCircle, GolocalSuccessContainer, GolocalSuccess)
                        typeNorm.contains("bus") ->
                            Triple(Icons.Filled.DirectionsBus, MaterialTheme.colorScheme.primaryContainer, GolocalPrimaryBlue)
                        typeNorm.contains("sos") || typeNorm.contains("safety") ->
                            Triple(Icons.Filled.Shield, MaterialTheme.colorScheme.primaryContainer, GolocalDeepNavy)
                        typeNorm.contains("arrived") || typeNorm.contains("assign") || typeNorm.contains("booking") || typeNorm.contains("ride") ->
                            Triple(Icons.Filled.DirectionsCar, MaterialTheme.colorScheme.primaryContainer, GolocalPrimaryBlue)
                        else ->
                            Triple(Icons.Filled.Notifications, MaterialTheme.colorScheme.primaryContainer, GolocalPrimaryBlue)
                    }
                    val dateLabel = remember(notif.createdAt) {
                        notif.createdAt?.toDate()?.let {
                            SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(it)
                        } ?: ""
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (notif.read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                if (notif.read) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                if (!notif.read) sessionViewModel.markNotificationRead(notif.id)
                                val target = com.example.notifications.NotificationDeepLinkTarget.fromAppNotification(notif)
                                if (target.bookingId.isNotBlank() || target.targetScreen.isNotBlank() || target.type !in setOf("general", "kyc_review")) {
                                    onOpenDeepLink(target)
                                }
                            }
                            .testTag("customer_notification_item_${notif.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(iconBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = iconVector,
                                    contentDescription = null,
                                    tint = iconTint,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = notif.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = if (notif.read) FontWeight.SemiBold else FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (notif.isTestNotification()) {
                                            GolocalStatusBadge(label = "TEST RIDE", tone = "warning")
                                        }
                                        if (!notif.read) {
                                            GolocalStatusBadge(label = "NEW", tone = "info")
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(GolocalPrimaryBlue)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = notif.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (dateLabel.isNotBlank()) {
                                    Text(
                                        text = dateLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
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

@Composable
fun CustomerProfileTab(
    sessionViewModel: AppSessionViewModel,
    bookings: List<Booking> = emptyList(),
    ratingsGiven: List<com.example.data.model.RatingRecord> = emptyList(),
    onOpenBookings: () -> Unit = {},
    onOpenNotifications: () -> Unit = {}
) {
    CustomerProfileScreen(
        sessionViewModel = sessionViewModel,
        bookings = bookings,
        ratingsGiven = ratingsGiven,
        onOpenBookings = onOpenBookings,
        onOpenNotifications = onOpenNotifications
    )
}

