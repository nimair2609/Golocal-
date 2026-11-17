package com.example.customer

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Booking
import com.example.data.model.BusBooking
import com.example.data.model.UiState
import com.example.shared.BookingChatModalSheet
import com.example.shared.NotificationPermissionBannerCard
import com.example.shared.SharedAppSettingsAndSignOutSection
import com.example.shared.maskPhoneNumber
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.CustomerViewModel

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
    var ratingTarget by remember { mutableStateOf<Booking?>(null) }
    var refundTarget by remember { mutableStateOf<Booking?>(null) }
    var complaintTarget by remember { mutableStateOf<Booking?>(null) }
    var appealTarget by remember { mutableStateOf<Booking?>(null) }
    var chatArchiveTarget by remember { mutableStateOf<Booking?>(null) }
    var qrTicketDialog by remember { mutableStateOf<BusBooking?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Bookings & Transactions", style = MaterialTheme.typography.headlineSmall)
        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Rides") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Buses") })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text("Payments") })
            Tab(selected = subTab == 3, onClick = { subTab = 3 }, text = { Text("Ratings") })
        }

        when (subTab) {
            0 -> {
                val list = (bookingsState as? UiState.Success)?.data.orEmpty()
                if (bookingsState is UiState.Loading) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (list.isEmpty()) {
                    GolocalEmptyState(
                        title = "No Booking History Yet",
                        message = "Your completed, scheduled, and active West Bengal trips will appear here.",
                        actionLabel = "Book a Ride",
                        onAction = onOpenBookRide
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list, key = { it.id }) { booking ->
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        GolocalStatusBadge(
                                            label = booking.status.uppercase(),
                                            tone = when (booking.status) {
                                                "completed" -> "success"
                                                "cancelled" -> "error"
                                                "searching" -> "warning"
                                                else -> "info"
                                            }
                                        )
                                        val fare = if (booking.agreedFareInr > 0) booking.agreedFareInr else booking.estimatedFareInr
                                        Text("₹${fare.toInt()}", fontFamily = JetBrainsMono, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Text("${booking.pickupLabel} → ${booking.destinationLabel}", style = MaterialTheme.typography.titleSmall)
                                    Text("${booking.vehicleCategory.uppercase()} • ${booking.distanceKm} km • ${booking.paymentMethod.uppercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (booking.assignedDriverName.isNotBlank()) {
                                        Text("Rider: ${booking.assignedDriverName} (${booking.assignedVehicleLabel})", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (booking.status == "completed") {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (booking.customerRating == null) {
                                                OutlinedButton(onClick = { ratingTarget = booking }) { Text("Rate Trip") }
                                            } else {
                                                GolocalStatusBadge(label = "Rated ★ ${booking.customerRating}", tone = "success")
                                            }
                                            TextButton(onClick = { refundTarget = booking }) { Text("Refund") }
                                            TextButton(onClick = { complaintTarget = booking }) { Text("Dispute", color = GolocalError) }
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
                                    if (booking.assignedDriverId.isNotBlank() && booking.status in setOf("completed", "cancelled")) {
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
                                modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Booking #${pay.bookingId.take(8)}", style = MaterialTheme.typography.titleSmall)
                                        GolocalStatusBadge(label = pay.status.uppercase(), tone = if (pay.status == "settled_cash") "success" else "warning")
                                    }
                                    Text("Gross Paid: ₹${pay.grossFareInr.toInt()} (incl. GST ₹${pay.gstInr})", fontFamily = JetBrainsMono, style = MaterialTheme.typography.bodyMedium)
                                    Text("Method: ${pay.paymentMethod.uppercase()} • Ref: ${pay.gatewayReference}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("★ ${r.stars} / 5 • Booking #${r.bookingId.take(8)}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Explore Local Drivers & Services", style = MaterialTheme.typography.headlineSmall)

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                .clickable { onOpenBusBooking() }
                .testTag("explore_private_bus_card")
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("West Bengal Private Bus Tickets", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Book verified operator seats with official tariff & QR boarding pass.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Filled.DirectionsBus, contentDescription = "Private Bus", tint = MaterialTheme.colorScheme.primary)
            }
        }

        // Local Service Shortcuts
        Text("West Bengal Local Mobility Services", style = MaterialTheme.typography.titleMedium)
        val services = listOf(
            Triple("station_pickup", "Railway Station Pickups", "Howrah, Sealdah, Bandel, NJP, Barddhaman, Kharagpur"),
            Triple("bus_stand_pickup", "Bus Stand Transfers", "Esplanade, Arambagh, Durgapur City Centre, Siliguri"),
            Triple("airport_transfer", "Airport Transfers", "Kolkata (CCU), Andal (RDP), Bagdogra (IXB)"),
            Triple("village_to_town", "Village-to-Town & Hospitals", "Direct rural panchayat, bazaar & hospital links")
        )
        services.forEach { (type, title, desc) ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    .clickable { onSelectServicePreset(type, if (type == "airport_transfer") "cab" else "toto") }
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Verified Local Driver Discovery
        Text("Verified Local Riders (${filteredDrivers.size})", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "All", "toto" to "Toto", "auto" to "Auto", "bike" to "Bike", "cab" to "Cab").forEach { (id, label) ->
                FilterChip(selected = filterCat == id, onClick = { filterCat = id }, label = { Text(label) })
            }
        }

        if (filteredDrivers.isEmpty()) {
            GolocalEmptyState(
                title = "No Verified Drivers Registered Yet",
                message = "As local Toto, Auto, Bike, and Cab partners pass admin KYC verification, they appear here."
            )
        } else {
            filteredDrivers.forEach { d ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                ) {
                    Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(d.displayName, style = MaterialTheme.typography.titleSmall)
                            Text("${d.vehicleCategory.uppercase()} • ${d.vehicleModel} (${d.maskedPlate})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${d.district} • ★ ${String.format("%.1f", d.ratingAvg)} (${d.completedRides} rides)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            GolocalStatusBadge(label = if (d.isOnline) "ONLINE" else "OFFLINE", tone = if (d.isOnline) "success" else "info")
                            TextButton(onClick = { onSelectServicePreset("now", d.vehicleCategory) }) { Text("Book") }
                        }
                    }
                }
            }
        }

        if (serviceAreas.isNotEmpty()) {
            Text("Configured Service Areas (${serviceAreas.count { it.isActive }})", style = MaterialTheme.typography.titleMedium)
            serviceAreas.filter { it.isActive }.forEach { area ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                ) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
fun CustomerNotificationsTab(sessionViewModel: AppSessionViewModel) {
    val notifications by sessionViewModel.notificationsState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Notifications", style = MaterialTheme.typography.headlineSmall)
        NotificationPermissionBannerCard()
        if (notifications.isEmpty()) {
            GolocalEmptyState(
                title = "All Caught Up",
                message = "Ride confirmations, Trip PINs, chat messages, and safety alerts appear here."
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(notifications, key = { it.id }) { notif ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (notif.read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                            .clickable { if (!notif.read) sessionViewModel.markNotificationRead(notif.id) }
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(notif.title, style = MaterialTheme.typography.titleSmall)
                                if (!notif.read) GolocalStatusBadge(label = "NEW", tone = "info")
                            }
                            Text(notif.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerProfileTab(sessionViewModel: AppSessionViewModel) {
    val accountState by sessionViewModel.userAccountState.collectAsStateWithLifecycle()
    val customerProfile by sessionViewModel.customerProfileState.collectAsStateWithLifecycle()
    val privateEmergency by sessionViewModel.privateEmergencyContactState.collectAsStateWithLifecycle()
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    val account = (accountState as? UiState.Success)?.data

    var fullName by remember(account?.fullName) { mutableStateOf(account?.fullName ?: "") }
    var invoiceEmail by remember(account?.email) { mutableStateOf(account?.email ?: "") }
    var homeAddress by remember(customerProfile?.homeAddress) { mutableStateOf(customerProfile?.homeAddress ?: "") }
    var workAddress by remember(customerProfile?.workAddress) { mutableStateOf(customerProfile?.workAddress ?: "") }
    var nativeVillage by remember(customerProfile?.nativeVillage) { mutableStateOf(customerProfile?.nativeVillage ?: "") }
    var nativeDistrict by remember(customerProfile?.nativeDistrict) { mutableStateOf(customerProfile?.nativeDistrict ?: "") }

    var showSupportDialog by remember { mutableStateOf(false) }
    var showEmergencyDialog by remember { mutableStateOf(false) }

    val verifiedPhone = account?.effectiveMobileNumber().orEmpty()
    val hasEmergency = account?.hasEmergencyContact == true || privateEmergency != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Customer Profile & Safety", style = MaterialTheme.typography.headlineSmall)

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Personal & Invoice Info", style = MaterialTheme.typography.titleSmall)

                var showFullMobile by remember { mutableStateOf(false) }
                val displayedPhone = when {
                    verifiedPhone.isBlank() -> "Verified via Mobile OTP"
                    showFullMobile -> verifiedPhone
                    else -> maskPhoneNumber(verifiedPhone)
                }
                OutlinedTextField(
                    value = displayedPhone,
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text("Verified Mobile Number (Read-Only)") },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (verifiedPhone.isNotBlank()) {
                                IconButton(onClick = { showFullMobile = !showFullMobile }) {
                                    Icon(
                                        imageVector = if (showFullMobile) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (showFullMobile) "Hide mobile number" else "Show mobile number"
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Filled.VerifiedUser,
                                contentDescription = "Verified",
                                tint = GolocalSuccess
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("profile_verified_mobile_readonly"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("Full Name *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = invoiceEmail,
                    onValueChange = { invoiceEmail = it },
                    label = { Text("Email Address (for Ride Invoices) *") },
                    supportingText = { Text("Used strictly for GST invoice delivery — never for login.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(value = homeAddress, onValueChange = { homeAddress = it }, label = { Text("Saved Home Address") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = workAddress, onValueChange = { workAddress = it }, label = { Text("Saved Work Address") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = nativeVillage, onValueChange = { nativeVillage = it }, label = { Text("Native Village") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = nativeDistrict, onValueChange = { nativeDistrict = it }, label = { Text("District") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Button(
                    onClick = {
                        sessionViewModel.updateAccountAndProfile(
                            fullName = fullName,
                            invoiceEmail = invoiceEmail,
                            homeAddress = homeAddress,
                            workAddress = workAddress,
                            nativeVillage = nativeVillage,
                            nativeDistrict = nativeDistrict
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("save_profile_button")
                ) {
                    Text("Save Customer Profile")
                }
            }
        }

        // Private Emergency Contact Card (Protected; never exposed to drivers or public profiles)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                .testTag("private_emergency_contact_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Private Emergency Contact", style = MaterialTheme.typography.titleSmall)
                    }
                    GolocalStatusBadge(
                        label = if (hasEmergency) "PROTECTED" else "REQUIRED",
                        tone = if (hasEmergency) "success" else "warning"
                    )
                }
                val maskedEmergency = privateEmergency?.emergencyContactNumber
                    ?.takeIf { it.isNotBlank() }
                    ?.let { com.example.ui.auth.AuthDiagnostics.maskPhoneNumber(it) }
                Text(
                    text = if (maskedEmergency != null) {
                        "Protected SOS Contact: $maskedEmergency (hidden from drivers & public views)"
                    } else if (hasEmergency) {
                        "Protected SOS contact configured privately for emergency workflows only."
                    } else {
                        "Add a trusted emergency contact. It stays strictly private for SOS alerts."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { showEmergencyDialog = true },
                    modifier = Modifier.fillMaxWidth().testTag("update_private_emergency_button")
                ) {
                    Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (hasEmergency) "Update Private SOS Contact" else "Set Private SOS Contact")
                }
            }
        }

        com.example.ui.auth.VerifiedMobileBadgeCard(
            fallbackAccountPhone = verifiedPhone
        )

        SharedAppSettingsAndSignOutSection(
            prefs = prefs,
            sessionViewModel = sessionViewModel,
            supportHelpLabel = "Help & Customer Support",
            onOpenSupportDialog = { showSupportDialog = true }
        )
    }

    if (showEmergencyDialog) {
        var newEmergency by remember { mutableStateOf("") }
        var relation by remember { mutableStateOf(privateEmergency?.relationshipNote ?: "Primary Emergency Contact") }
        var localError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showEmergencyDialog = false },
            title = { Text("Update Private SOS Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Stored in isolated emergency records accessible only to you and authorized SOS responders.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (localError != null) {
                        Text(localError ?: "", style = MaterialTheme.typography.bodySmall, color = GolocalError)
                    }
                    OutlinedTextField(
                        value = newEmergency,
                        onValueChange = {
                            newEmergency = it.filter { ch -> ch.isDigit() || ch == '+' }.take(16)
                            localError = null
                        },
                        label = { Text("Emergency Mobile Number (+91...) *") },
                        placeholder = { Text("9876543210") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = relation,
                        onValueChange = { relation = it.take(60) },
                        label = { Text("Relationship Note") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val result = com.example.ui.auth.RegistrationValidator.validateEmergencyContact(
                        country = com.example.ui.auth.SupportedCountryCodes.INDIA,
                        rawEmergency = newEmergency,
                        ownVerifiedMobileE164 = verifiedPhone
                    )
                    when (result) {
                        is com.example.ui.auth.PhoneValidationResult.Invalid -> localError = result.message
                        is com.example.ui.auth.PhoneValidationResult.Valid -> {
                            sessionViewModel.updatePrivateEmergencyContact(result.e164Phone, relation)
                            showEmergencyDialog = false
                        }
                    }
                }) { Text("Save Privately") }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showSupportDialog) {
        var issue by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            title = { Text("Customer Help & Support") },
            text = {
                OutlinedTextField(value = issue, onValueChange = { issue = it }, label = { Text("How can GoLocal Support help?") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(onClick = {
                    sessionViewModel.submitSupportOrDeletion("customer_support", issue)
                    showSupportDialog = false
                }) { Text("Submit Ticket") }
            },
            dismissButton = { TextButton(onClick = { showSupportDialog = false }) { Text("Cancel") } }
        )
    }
}
