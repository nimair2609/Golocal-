package com.example.customer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.GolocalDeepNavy
import com.example.ui.theme.GolocalLightBlue
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalPureWhite
import com.example.ui.theme.GolocalRoyalBlueBright
import com.example.ui.theme.JetBrainsMono

@Composable
fun CustomerTravelSafeDialog(
    helplineNumber: String,
    onTriggerSos: () -> Unit,
    onSubmitSupportTicket: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showSupportForm by remember { mutableStateOf(false) }
    var supportDetails by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .testTag("customer_travel_safe_dialog"),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Royal Blue Skyline & Shield Visual Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(GolocalDeepNavy, GolocalPrimaryBlue, GolocalLightBlue.copy(alpha = 0.35f))
                            )
                        )
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val baseH = size.height
                        val buildingHeights = listOf(0.45f, 0.62f, 0.38f, 0.72f, 0.52f, 0.66f, 0.42f, 0.58f)
                        val bw = size.width / buildingHeights.size
                        buildingHeights.forEachIndexed { idx, ratio ->
                            val bh = baseH * ratio * 0.58f
                            drawRect(
                                color = Color.White.copy(alpha = if (idx % 2 == 0) 0.14f else 0.22f),
                                topLeft = Offset(idx * bw + 4f, baseH - bh),
                                size = Size(bw - 8f, bh)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = GolocalPureWhite
                        )
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(GolocalRoyalBlueBright.copy(alpha = 0.28f)),
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
                                imageVector = Icons.Filled.Shield,
                                contentDescription = "GoLocal Safety Shield",
                                tint = GolocalPureWhite,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Travel Safe with GoLocal",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Your safety is our priority. Use SOS in case of emergency.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    ) {
                        Text(
                            text = "24x7 Helpline: $helplineNumber",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = JetBrainsMono,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = {
                            onTriggerSos()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GolocalPrimaryBlue,
                            contentColor = GolocalPureWhite
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("travel_safe_call_sos_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocalPolice,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Call SOS", fontWeight = FontWeight.Bold)
                    }

                    if (!showSupportForm) {
                        OutlinedButton(
                            onClick = { showSupportForm = true },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("travel_safe_need_help_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SupportAgent,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Need Help?", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        OutlinedTextField(
                            value = supportDetails,
                            onValueChange = { supportDetails = it },
                            label = { Text("Describe your question or ride issue") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("travel_safe_support_input"),
                            minLines = 2,
                            maxLines = 4
                        )
                        Button(
                            onClick = {
                                onSubmitSupportTicket(supportDetails)
                                onDismiss()
                            },
                            enabled = supportDetails.isNotBlank(),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("travel_safe_submit_help_button")
                        ) {
                            Text("Submit Support Request")
                        }
                        TextButton(onClick = { showSupportForm = false }) {
                            Text("Back")
                        }
                    }
                }
            }
        }
    }
}
