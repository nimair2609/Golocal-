package com.example.rider

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
import com.example.data.model.DriverProfile
import com.example.shared.SharedAppSettingsAndSignOutSection
import com.example.ui.components.GolocalEmptyState
import com.example.ui.components.GolocalMetricCard
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AppSessionViewModel
import com.example.viewmodel.DriverViewModel
import kotlin.math.roundToInt

@Composable
fun RiderRequestsTab(
    profile: DriverProfile,
    driverViewModel: DriverViewModel
) {
    val openRequests by driverViewModel.openRequests.collectAsStateWithLifecycle()
    val myOffers by driverViewModel.myOffers.collectAsStateWithLifecycle()
    var proposalTarget by remember { mutableStateOf<Booking?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Incoming Ride Requests", style = MaterialTheme.typography.headlineSmall)

        if (!profile.isOnline) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Tip: Toggle Online on the Home tab so nearby customers see your vehicle availability.",
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
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            GolocalStatusBadge(label = booking.vehicleCategory.uppercase(), tone = "info")
                            Text(
                                text = "Est. ₹${booking.estimatedFareInr.toInt()}",
                                style = MaterialTheme.typography.titleLarge,
                                fontFamily = JetBrainsMono,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text("${booking.pickupLabel} → ${booking.destinationLabel}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Passenger: ${booking.customerName} • ${booking.distanceKm} km (~${booking.estimatedDurationMin} min) • ${booking.paymentMethod.uppercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Permitted Proposal Range: ₹${booking.minPermittedFareInr.toInt()} – ₹${booking.maxPermittedFareInr.toInt()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = GolocalSuccess
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { driverViewModel.rejectRequest(booking.id) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Reject", color = GolocalError)
                            }
                            Button(
                                onClick = { proposalTarget = booking },
                                modifier = Modifier.weight(1.4f)
                            ) {
                                Text("Propose Fare")
                            }
                        }
                    }
                }
            }
        }

        Text("My Submitted Fare Proposals (${myOffers.size})", style = MaterialTheme.typography.titleMedium)
        if (myOffers.isEmpty()) {
            Text("No proposals submitted yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            myOffers.forEach { offer ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                ) {
                    Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Booking #${offer.bookingId.take(8)} • ₹${offer.proposedFareInr.toInt()}", style = MaterialTheme.typography.titleSmall)
                            Text("ETA: ${offer.etaMinutes} min • Status: ${offer.status.uppercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (offer.status == "pending") {
                            TextButton(onClick = { driverViewModel.withdrawOffer(offer.id) }) {
                                Text("Withdraw", color = GolocalError)
                            }
                        } else {
                            GolocalStatusBadge(label = offer.status.uppercase(), tone = if (offer.status == "accepted") "success" else "info")
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
                    Text("Proposed Fare: ₹${fare.roundToInt()}", style = MaterialTheme.typography.titleLarge, fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.primary)
                    Slider(
                        value = fare,
                        onValueChange = { fare = it },
                        valueRange = minFare..maxFare
                    )
                    Text("Regulated Range: ₹${minFare.toInt()} – ₹${maxFare.toInt()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    var subTab by remember { mutableIntStateOf(0) }
    var showWithdrawDialog by remember { mutableStateOf(false) }

    val totalGross = commissions.sumOf { it.grossFareInr }
    val totalComm = commissions.sumOf { it.commissionAmountInr }
    val totalNet = commissions.sumOf { it.netDriverEarningsInr }
    val withdrawnOrRequested = settlements.filter { it.status != "rejected" }.sumOf { it.amountInr }
    val availableBalance = (totalNet - withdrawnOrRequested).coerceAtLeast(0.0)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Earnings & Trip Ledger", style = MaterialTheme.typography.headlineSmall)
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
                        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Withdrawable Balance", style = MaterialTheme.typography.labelLarge)
                                Text("₹${availableBalance.toInt()}", style = MaterialTheme.typography.headlineMedium, fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.primary)
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
                                modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                            ) {
                                Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        GolocalStatusBadge(label = b.status.uppercase(), tone = if (b.status == "completed") "success" else "info")
                                        Text("₹${b.agreedFareInr.toInt()}", fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Text("${b.pickupLabel} → ${b.destinationLabel}", style = MaterialTheme.typography.titleSmall)
                                    Text("Passenger: ${b.customerName} • ${b.distanceKm} km", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("★ ${r.stars} / 5 • Booking #${r.bookingId.take(8)}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                    if (r.review.isNotBlank()) Text(r.review, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
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
fun RiderNotificationsTab(sessionViewModel: AppSessionViewModel) {
    val notifications by sessionViewModel.notificationsState.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Rider Alerts & Updates", style = MaterialTheme.typography.headlineSmall)
        if (notifications.isEmpty()) {
            GolocalEmptyState(title = "No Alerts Yet", message = "KYC verification updates, ride confirmations, and payout statuses appear here.")
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
fun RiderProfileTab(
    profile: DriverProfile,
    sessionViewModel: AppSessionViewModel
) {
    val prefs by sessionViewModel.clientPreferences.collectAsStateWithLifecycle()
    var showSupportDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Rider Profile & KYC", style = MaterialTheme.typography.headlineSmall)

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.legalName, style = MaterialTheme.typography.titleMedium)
                    GolocalStatusBadge(label = "VERIFIED PARTNER", tone = "success")
                }
                Text("Vehicle: ${profile.vehicleCategory.uppercase()} • ${profile.vehicleModel} (${profile.vehicleRegNumber})", style = MaterialTheme.typography.bodyMedium)
                Text("District: ${profile.district} ${profile.block}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Driving Licence: ${profile.drivingLicenceNumber}", style = MaterialTheme.typography.bodySmall)
                Text("Settlement UPI: ${profile.settlementUpiOrBank.ifBlank { "Not configured" }}", style = MaterialTheme.typography.bodySmall)
            }
        }

        com.example.ui.auth.LinkedPhoneAccountCard(
            fallbackAccountPhone = profile.phone,
            onPhoneLinked = sessionViewModel::onPhoneNumberLinked
        )

        SharedAppSettingsAndSignOutSection(
            prefs = prefs,
            sessionViewModel = sessionViewModel,
            supportHelpLabel = "Rider Partner Support",
            onOpenSupportDialog = { showSupportDialog = true }
        )
    }

    if (showSupportDialog) {
        var issue by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            title = { Text("Rider Partner Support") },
            text = {
                OutlinedTextField(value = issue, onValueChange = { issue = it }, label = { Text("Describe payout, KYC, or trip issue") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(onClick = {
                    sessionViewModel.submitSupportOrDeletion("rider_support", issue)
                    showSupportDialog = false
                }) { Text("Submit") }
            },
            dismissButton = { TextButton(onClick = { showSupportDialog = false }) { Text("Cancel") } }
        )
    }
}
