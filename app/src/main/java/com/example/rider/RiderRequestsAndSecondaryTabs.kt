package com.example.rider

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Booking
import com.example.data.model.DriverProfile
import com.example.shared.NotificationPermissionBannerCard
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalMetricCard
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.DriverViewModel
import kotlin.math.roundToInt

@Composable
fun RiderRequestsTab(
    profile: DriverProfile,
    driverViewModel: DriverViewModel,
    currentLat: Double = 22.9041,
    currentLng: Double = 88.3976
) {
    val openRequests by driverViewModel.openRequests.collectAsStateWithLifecycle()
    val openRequestsError by driverViewModel.openRequestsError.collectAsStateWithLifecycle()
    val myOffers by driverViewModel.myOffers.collectAsStateWithLifecycle()
    var proposalTarget by remember { mutableStateOf<Booking?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Incoming Ride Requests",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        if (!openRequestsError.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rider_requests_error_card")
            ) {
                Text(
                    text = "Request listener error: $openRequestsError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (!profile.isOnline) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Tip: Toggle Online on the Home map so nearby passengers see your vehicle availability.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (openRequests.isEmpty()) {
            GolocalEmptyState(
                title = "No Open Ride Requests",
                message = "New customer ride bookings in West Bengal will stream here live."
            )
        } else {
            openRequests.forEach { booking ->
                val passengerFirst = booking.customerName.trim().substringBefore(" ").ifBlank { "Passenger" }
                val netEstimate = com.example.domain.FareCalculator.estimateDriverNetEarningsInr(
                    grossFareInr = booking.estimatedFareInr,
                    gstPercent = booking.gstPercent,
                    commissionPercent = booking.commissionPercent
                ).toInt()
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                GolocalStatusBadge(label = booking.vehicleCategory.uppercase(), tone = "info")
                                Text(
                                    text = "#${booking.id.take(8)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = JetBrainsMono,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Est. ₹${booking.estimatedFareInr.toInt()}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontFamily = JetBrainsMono,
                                    color = GolocalPrimaryBlue
                                )
                                Text(
                                    text = "Net ~₹$netEstimate",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMono,
                                    color = GolocalSuccess
                                )
                            }
                        }
                        Text("${booking.pickupLabel} → ${booking.destinationLabel}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Passenger: $passengerFirst • ${booking.distanceKm} km (~${booking.estimatedDurationMin} min) • ${booking.paymentMethod.uppercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Permitted Proposal Range: ₹${booking.minPermittedFareInr.toInt()} – ₹${booking.maxPermittedFareInr.toInt()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = GolocalSuccess
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    driverViewModel.rejectRequest(booking.id)
                                    com.example.notifications.NotificationHelper.cancelRideRequestNotification(context, booking.id)
                                },
                                modifier = Modifier.weight(0.9f).heightIn(min = 48.dp)
                            ) {
                                Text("Decline", color = GolocalError)
                            }
                            OutlinedButton(
                                onClick = { proposalTarget = booking },
                                modifier = Modifier.weight(1.0f).heightIn(min = 48.dp)
                            ) {
                                Text("Offer")
                            }
                            Button(
                                onClick = {
                                    com.example.notifications.NotificationHelper.cancelRideRequestNotification(context, booking.id)
                                    driverViewModel.acceptRideDirectly(
                                        booking = booking,
                                        agreedFareInr = booking.estimatedFareInr,
                                        lat = currentLat,
                                        lng = currentLng
                                    )
                                },
                                modifier = Modifier
                                    .weight(1.1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("requests_tab_accept_${booking.id}")
                            ) {
                                Text("Accept Ride")
                            }
                        }
                    }
                }
            }
        }

        Text("My Submitted Fare Proposals (${myOffers.size})", style = MaterialTheme.typography.titleMedium)
        if (myOffers.isEmpty()) {
            Text(
                text = "No proposals submitted yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            myOffers.forEach { offer ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Booking #${offer.bookingId.take(8)} • ₹${offer.proposedFareInr.toInt()}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "ETA: ${offer.etaMinutes} min • Status: ${offer.status.uppercase()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (offer.status == "pending") {
                            TextButton(
                                onClick = { driverViewModel.withdrawOffer(offer.id) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Withdraw", color = GolocalError)
                            }
                        } else {
                            GolocalStatusBadge(
                                label = offer.status.uppercase(),
                                tone = if (offer.status == "accepted") "success" else "info"
                            )
                        }
                    }
                }
            }
        }
    }

    proposalTarget?.let { booking ->
        val minFare = booking.minPermittedFareInr.toFloat().coerceAtLeast(1f)
        val maxFare = booking.maxPermittedFareInr.toFloat().coerceAtLeast(minFare)
        var fare by remember(booking.id) { mutableFloatStateOf(booking.estimatedFareInr.toFloat().coerceIn(minFare, maxFare)) }
        var eta by remember(booking.id) { mutableStateOf("6") }

        AlertDialog(
            onDismissRequest = { proposalTarget = null },
            title = { Text("Propose Your Fare") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${booking.pickupLabel} → ${booking.destinationLabel}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = "Proposed Fare: ₹${fare.roundToInt()}",
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = JetBrainsMono,
                        color = GolocalPrimaryBlue
                    )
                    Slider(
                        value = fare,
                        onValueChange = { fare = it },
                        valueRange = minFare..maxFare
                    )
                    Text(
                        text = "Regulated Range: ₹${minFare.toInt()} – ₹${maxFare.toInt()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = eta,
                        onValueChange = { eta = it.filter { ch -> ch.isDigit() } },
                        label = { Text("ETA to Pickup (minutes)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    driverViewModel.submitOffer(booking, fare.roundToInt().toDouble(), eta.toLongOrNull() ?: 5L)
                    proposalTarget = null
                }) { Text("Send Proposal") }
            },
            dismissButton = { TextButton(onClick = { proposalTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun RiderEarningsHistoryTab(
    profile: DriverProfile,
    driverViewModel: DriverViewModel
) {
    val commissions by driverViewModel.commissions.collectAsStateWithLifecycle()
    val settlements by driverViewModel.settlements.collectAsStateWithLifecycle()
    val assignedBookings by driverViewModel.assignedBookings.collectAsStateWithLifecycle()
    val ratings by driverViewModel.myRatings.collectAsStateWithLifecycle()
    val appeals by driverViewModel.myAppeals.collectAsStateWithLifecycle()

    var subTab by remember { mutableIntStateOf(0) }
    var showWithdrawDialog by remember { mutableStateOf(false) }
    var passengerRateTarget by remember { mutableStateOf<Booking?>(null) }
    var appealTarget by remember { mutableStateOf<Booking?>(null) }

    val totalGross = commissions.sumOf { it.grossFareInr }
    val totalComm = commissions.sumOf { it.commissionAmountInr }
    val totalNet = commissions.sumOf { it.netDriverEarningsInr }
    val withdrawnOrRequested = settlements.filter { it.status != "rejected" }.sumOf { it.amountInr }
    val availableBalance = (totalNet - withdrawnOrRequested).coerceAtLeast(0.0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Earnings & Trip Ledger",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Earnings") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Trips") })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text("Ratings") })
        }

        when (subTab) {
            0 -> {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GolocalMetricCard("Gross Fares", "₹${totalGross.toInt()}", "Total collected", Icons.Filled.AccountBalanceWallet, Modifier.weight(1f))
                        GolocalMetricCard("Net Earnings", "₹${totalNet.toInt()}", "Fee: ₹${totalComm.toInt()}", Icons.Filled.CurrencyRupee, Modifier.weight(1f))
                    }
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Withdrawable Balance", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    text = "₹${availableBalance.toInt()}",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontFamily = JetBrainsMono,
                                    color = GolocalPrimaryBlue
                                )
                            }
                            Button(
                                onClick = { showWithdrawDialog = true },
                                enabled = availableBalance >= 50.0
                            ) { Text("Withdraw") }
                        }
                    }

                    Text("Withdrawal & Settlement Requests (${settlements.size})", style = MaterialTheme.typography.titleMedium)
                    if (settlements.isEmpty()) {
                        GolocalEmptyState(title = "No Withdrawals Yet", message = "Completed rides accrue withdrawable earnings here (minimum ₹50).")
                    } else {
                        settlements.forEach { s ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("₹${s.amountInr.toInt()} → ${s.upiOrBankRef}", style = MaterialTheme.typography.titleSmall)
                                        Text("Settlement #${s.id.take(8)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    GolocalStatusBadge(label = s.status.uppercase(), tone = if (s.status == "paid") "success" else "warning")
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                if (assignedBookings.isEmpty()) {
                    GolocalEmptyState(title = "No Booking History", message = "Your assigned and completed trips will appear here.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(assignedBookings, key = { it.id }) { b ->
                            val passengerFirst = b.customerName.trim().substringBefore(" ").ifBlank { "Passenger" }
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            if (b.isTestRide || b.testMode) {
                                                GolocalStatusBadge(label = "TEST RIDE", tone = "warning")
                                            }
                                            GolocalStatusBadge(label = b.status.uppercase(), tone = if (b.status == "completed") "success" else "info")
                                        }
                                        Text(
                                            text = if (b.isTestRide || b.testMode) "₹0 (TEST)" else "₹${b.agreedFareInr.toInt()}",
                                            fontFamily = JetBrainsMono,
                                            color = GolocalPrimaryBlue
                                        )
                                    }
                                    Text("${b.pickupLabel} → ${b.destinationLabel}", style = MaterialTheme.typography.titleSmall)
                                    Text("Passenger: $passengerFirst • ${b.distanceKm} km", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (b.status == "completed") {
                                        if (b.customerFeedbackRating == null) {
                                            OutlinedButton(onClick = { passengerRateTarget = b }) {
                                                Text("Rate Passenger")
                                            }
                                        } else {
                                            GolocalStatusBadge(label = "Passenger Rated ★ ${b.customerFeedbackRating}", tone = "success")
                                        }
                                    } else if (b.status == "cancelled") {
                                        val existingAppeal = appeals.firstOrNull { it.bookingId == b.id }
                                        if (existingAppeal != null) {
                                            GolocalStatusBadge(label = "APPEAL: ${existingAppeal.status.uppercase()}", tone = "warning")
                                        } else {
                                            OutlinedButton(onClick = { appealTarget = b }) {
                                                Text("Appeal Cancellation")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                if (ratings.isEmpty()) {
                    GolocalEmptyState(title = "No Passenger Reviews Yet", message = "Ratings given by customers after completed trips appear here.")
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
                                    Text("★ ${r.stars} / 5 • Booking #${r.bookingId.take(8)}", style = MaterialTheme.typography.titleSmall, color = GolocalPrimaryBlue)
                                    if (r.review.isNotBlank()) Text(r.review, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (passengerRateTarget != null) {
        val booking = passengerRateTarget!!
        var stars by remember { mutableIntStateOf(5) }
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { passengerRateTarget = null },
            title = { Text("Rate Passenger") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..5).forEach { s ->
                            FilterChip(selected = stars == s, onClick = { stars = s }, label = { Text("$s★") })
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Behavior / trip note (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    driverViewModel.submitCustomerFeedback(booking.id, stars, note)
                    passengerRateTarget = null
                }) { Text("Submit") }
            },
            dismissButton = { TextButton(onClick = { passengerRateTarget = null }) { Text("Cancel") } }
        )
    }

    if (appealTarget != null) {
        val booking = appealTarget!!
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { appealTarget = null },
            title = { Text("Appeal Trip Cancellation") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Explain cancellation circumstances") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    driverViewModel.submitAppeal(booking.id, reason)
                    appealTarget = null
                }) { Text("Submit Appeal") }
            },
            dismissButton = { TextButton(onClick = { appealTarget = null }) { Text("Cancel") } }
        )
    }

    if (showWithdrawDialog) {
        var amount by remember { mutableStateOf(availableBalance.toInt().toString()) }
        var upi by remember { mutableStateOf(profile.settlementUpiOrBank) }
        AlertDialog(
            onDismissRequest = { showWithdrawDialog = false },
            title = { Text("Request Settlement Withdrawal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() } }, label = { Text("Amount (₹)") }, singleLine = true)
                    OutlinedTextField(value = upi, onValueChange = { upi = it }, label = { Text("UPI ID / Bank Account") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val amt = amount.toDoubleOrNull() ?: 0.0
                    driverViewModel.requestWithdrawal(amt, upi)
                    showWithdrawDialog = false
                }) { Text("Submit") }
            },
            dismissButton = { TextButton(onClick = { showWithdrawDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun RiderNotificationsTab(
    sessionViewModel: AppSessionViewModel,
    testOnly: Boolean = false,
    excludeTestNotifications: Boolean = false,
    onNotificationClick: (com.example.notifications.NotificationDeepLinkTarget) -> Unit = {}
) {
    val allNotifications by sessionViewModel.notificationsState.collectAsStateWithLifecycle()
    val notifications = remember(allNotifications, testOnly, excludeTestNotifications) {
        when {
            testOnly -> allNotifications.filter { it.isTestNotification() }
            excludeTestNotifications -> allNotifications.filter { !it.isTestNotification() }
            else -> allNotifications
        }
    }
    val unreadIds = remember(notifications) { notifications.filter { !it.read }.map { it.id } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (testOnly) "Test Rider Sandbox Alerts" else "Rider Alerts & Updates",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            if (unreadIds.isNotEmpty()) {
                TextButton(
                    onClick = { sessionViewModel.markAllNotificationsRead(unreadIds) },
                    modifier = Modifier.testTag("rider_mark_all_read_button")
                ) {
                    Text("Mark all read (${unreadIds.size})")
                }
            }
        }
        NotificationPermissionBannerCard(
            title = if (testOnly) "Enable Test Rider Push Alerts" else "Enable Rider Dispatch & Chat Alerts",
            description = if (testOnly) {
                "Receive non-commercial Test Ride dispatches, Test OTP readiness, and approval updates."
            } else {
                "Get instant ride requests, trip assignments, customer messages, and KYC status alerts."
            }
        )
        if (notifications.isEmpty()) {
            GolocalEmptyState(
                title = if (testOnly) "No Test Rider Alerts Yet" else "No Alerts Yet",
                message = if (testOnly) {
                    "Isolated TEST RIDE requests, sandbox trip status updates, and Test Rider approval notices appear here."
                } else {
                    "KYC verification updates, ride confirmations, chat messages, and payout statuses appear here."
                }
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
                            .clickable {
                                if (!notif.read) sessionViewModel.markNotificationRead(notif.id)
                                onNotificationClick(com.example.notifications.NotificationDeepLinkTarget.fromAppNotification(notif))
                            }
                            .testTag("rider_notification_item_${notif.id}")
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = notif.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (notif.isTestNotification()) {
                                        GolocalStatusBadge(label = "TEST", tone = "warning")
                                    }
                                    if (!notif.read) GolocalStatusBadge(label = "NEW", tone = "info")
                                }
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
fun RiderProfileTab(
    profile: DriverProfile,
    sessionViewModel: AppSessionViewModel,
    driverViewModel: DriverViewModel? = null,
    onOpenEarnings: () -> Unit = {},
    onOpenNotifications: () -> Unit = {}
) {
    RiderProfileScreen(
        profile = profile,
        sessionViewModel = sessionViewModel,
        driverViewModel = driverViewModel,
        onOpenEarnings = onOpenEarnings,
        onOpenNotifications = onOpenNotifications
    )
}
