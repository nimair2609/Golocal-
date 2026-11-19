package com.example.customer

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.SampleRide
import com.example.data.model.TripLocation
import com.example.domain.LocalSampleOtpEntry
import com.example.map.GolocalMapContainer
import com.example.ui.components.GolocalStatusBadge
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalWarningContainer
import com.example.ui.theme.JetBrainsMono
import java.util.Locale

@Composable
fun CustomerActiveSampleRideCard(
    ride: SampleRide,
    localOtpEntry: LocalSampleOtpEntry?,
    onCancelSampleRide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val liveTripLoc = if (ride.riderLat != null && ride.riderLng != null) {
        TripLocation(
            bookingId = ride.rideId,
            customerId = ride.customerId,
            driverId = ride.assignedSampleRiderId,
            lat = ride.riderLat,
            lng = ride.riderLng,
            isTestRide = true,
            isSimulated = true
        )
    } else {
        null
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .border(1.5.dp, GolocalPrimaryBlue.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .testTag("customer_active_sample_ride_card")
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
                GolocalStatusBadge(
                    label = "SAMPLE RIDE • ${ride.status.uppercase()}",
                    tone = "info"
                )
                Text(
                    text = "₹${ride.estimatedFareInr.toInt()} • ${String.format(Locale.US, "%.1f", ride.distanceKm)} km",
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Bold
                )
            }

            GolocalMapContainer(
                pickupLat = ride.pickupLat,
                pickupLng = ride.pickupLng,
                pickupLabel = ride.pickupLabel,
                destLat = ride.destinationLat,
                destLng = ride.destinationLng,
                destLabel = ride.destinationLabel,
                onlineDrivers = emptyList(),
                liveTripLocation = liveTripLoc,
                lowNetworkMode = false,
                mapHeight = 165.dp,
                showTopRouteChip = false,
                showRecenterFab = false
            )

            Text("${ride.pickupLabel} -> ${ride.destinationLabel}", fontWeight = FontWeight.SemiBold)

            if (ride.assignedSampleRiderName.isNotBlank()) {
                Text(
                    text = "SAMPLE RIDER: ${ride.assignedSampleRiderName}",
                    style = MaterialTheme.typography.labelLarge,
                    color = GolocalDeepNavy,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    text = "Waiting for an online Sample Rider to accept...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!ride.otpVerified) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = GolocalWarningContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (localOtpEntry != null) {
                                "Sample Ride OTP: ${localOtpEntry.otpPlaintext}"
                            } else {
                                "Sample Ride OTP stored strictly on creator device"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = JetBrainsMono,
                            fontWeight = FontWeight.Bold,
                            color = GolocalDeepNavy,
                            modifier = Modifier.testTag("customer_sample_local_otp_text")
                        )
                        Text(
                            text = "Verified via hashed challenge in Firestore (zero readable OTP stored remotely).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (ride.status in setOf("incoming", "accepted", "arriving", "arrived")) {
                OutlinedButton(
                    onClick = onCancelSampleRide,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GolocalError),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text("Cancel Sample Ride")
                }
            }
        }
    }
}
