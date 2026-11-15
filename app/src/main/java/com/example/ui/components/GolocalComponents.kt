package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AppLanguage
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.TripLocation
import com.example.ui.theme.*
import kotlin.math.max
import kotlin.math.min

@Composable
fun GolocalStatusBadge(
    label: String,
    tone: String = "info", // info, success, warning, error
    modifier: Modifier = Modifier
) {
    val (bg, fg) = when (tone) {
        "success" -> GolocalSuccessContainer to GolocalSuccess
        "warning" -> GolocalWarningContainer to Color(0xFF9C6500)
        "error" -> GolocalErrorContainer to GolocalError
        else -> GolocalLightBlue to GolocalPrimaryBlue
    }
    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun GolocalMetricCard(
    title: String,
    value: String,
    subtitle: String = "",
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = JetBrainsMono,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun GolocalEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_empty_rides),
                contentDescription = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun LanguageSelectorRow(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AppLanguage.entries.forEach { lang ->
            FilterChip(
                selected = selected == lang,
                onClick = { onSelect(lang) },
                label = { Text(lang.displayLabel) }
            )
        }
    }
}

@Composable
fun LowNetworkBanner(
    isLowNetwork: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (isLowNetwork) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = GolocalWarningContainer,
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.SignalCellularAlt,
                        contentDescription = null,
                        tint = Color(0xFF9C6500),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Low-Network Mode active: lightweight UI & draft recovery enabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6B4500)
                    )
                }
                TextButton(onClick = { onToggle(false) }) {
                    Text("Standard")
                }
            }
        }
    }
}

@Composable
fun InteractiveBengalMapCanvas(
    pickupLat: Double,
    pickupLng: Double,
    pickupLabel: String,
    destLat: Double,
    destLng: Double,
    destLabel: String,
    onlineDrivers: List<DriverDirectoryEntry>,
    liveTripLocation: TripLocation?,
    lowNetworkMode: Boolean,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (lowNetworkMode) 128.dp else 196.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        surfaceColor
                    )
                )
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
    ) {
        if (!lowNetworkMode) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val padX = w * 0.14f
                val padY = h * 0.22f

                // Subtle corridor grid
                for (i in 1..4) {
                    val y = h * (i / 5f)
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
                }
                for (j in 1..5) {
                    val x = w * (j / 6f)
                    drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1.dp.toPx())
                }

                // Normalize pickup/destination/driver bounds dynamically
                val lats = mutableListOf(pickupLat, destLat)
                val lngs = mutableListOf(pickupLng, destLng)
                onlineDrivers.filter { it.isOnline }.forEach {
                    lats.add(it.currentLat)
                    lngs.add(it.currentLng)
                }
                liveTripLocation?.let {
                    lats.add(it.lat)
                    lngs.add(it.lng)
                }

                val minLat = (lats.minOrNull() ?: 22.5) - 0.03
                val maxLat = (lats.maxOrNull() ?: 23.0) + 0.03
                val minLng = (lngs.minOrNull() ?: 88.1) - 0.03
                val maxLng = (lngs.maxOrNull() ?: 88.6) + 0.03

                fun project(lat: Double, lng: Double): Offset {
                    val nx = ((lng - minLng) / max(0.01, maxLng - minLng)).toFloat().coerceIn(0f, 1f)
                    val ny = (1f - ((lat - minLat) / max(0.01, maxLat - minLat)).toFloat()).coerceIn(0f, 1f)
                    return Offset(padX + nx * (w - 2 * padX), padY + ny * (h - 2 * padY))
                }

                val p1 = project(pickupLat, pickupLng)
                val p2 = project(destLat, destLng)

                // Curved route arc
                val mid = Offset((p1.x + p2.x) / 2f, min(p1.y, p2.y) - 24.dp.toPx())
                val routePath = Path().apply {
                    moveTo(p1.x, p1.y)
                    quadraticTo(mid.x, mid.y, p2.x, p2.y)
                }
                drawPath(
                    path = routePath,
                    color = primaryColor,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 0f)
                    )
                )

                // Real online driver markers from Firestore (never simulated)
                onlineDrivers.filter { it.isOnline }.forEach { driver ->
                    val dPos = project(driver.currentLat, driver.currentLng)
                    drawCircle(color = GolocalPureWhite, radius = 7.dp.toPx(), center = dPos)
                    drawCircle(color = secondaryColor, radius = 5.dp.toPx(), center = dPos)
                }

                // Live assigned driver location
                liveTripLocation?.let { loc ->
                    val livePos = project(loc.lat, loc.lng)
                    drawCircle(color = GolocalSuccess.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = livePos)
                    drawCircle(color = GolocalSuccess, radius = 6.dp.toPx(), center = livePos)
                }

                // Pickup & Destination markers
                drawCircle(color = GolocalSuccess.copy(alpha = 0.22f), radius = 13.dp.toPx(), center = p1)
                drawCircle(color = GolocalSuccess, radius = 6.5.dp.toPx(), center = p1)
                drawCircle(color = primaryColor.copy(alpha = 0.22f), radius = 13.dp.toPx(), center = p2)
                drawCircle(color = primaryColor, radius = 6.5.dp.toPx(), center = p2)
            }
        }

        // Map overlay pills
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(999.dp),
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Map,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (lowNetworkMode) "Lite Route View" else "West Bengal Mobility Corridor",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(999.dp),
                    shadowElevation = 2.dp
                ) {
                    val onlineCount = onlineDrivers.count { it.isOnline }
                    Text(
                        text = if (onlineCount == 0) "No drivers online nearby" else "$onlineCount verified online",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (onlineCount > 0) GolocalSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shape = RoundedCornerShape(14.dp),
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = pickupLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = GolocalSuccess,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .size(14.dp)
                    )
                    Text(
                        text = destLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = primaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun VehicleCategoryChip(
    id: String,
    title: String,
    subtitle: String,
    etaOrStatus: String,
    fareLabel: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f) else MaterialTheme.colorScheme.surface
    Surface(
        modifier = modifier
            .width(156.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = container,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = if (selected) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = fareLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = JetBrainsMono,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = etaOrStatus,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MaterialTheme.colorScheme.primary else GolocalWarning,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun GoLocalLogoMark(
    modifier: Modifier = Modifier,
    onDarkBackground: Boolean = false
) {
    Image(
        painter = painterResource(id = R.drawable.ic_golocal_mark),
        contentDescription = "GoLocal Icon",
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(12.dp))
    )
}

@Composable
fun GoLocalFullLogo(
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(id = R.drawable.ic_golocal_logo_full),
        contentDescription = "GoLocal Logo",
        contentScale = ContentScale.Fit,
        modifier = modifier.clip(RoundedCornerShape(22.dp))
    )
}

@Composable
fun GolocalStatusMessageBanner(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!message.isNullOrBlank()) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            }
        }
    }
}

@Composable
fun GolocalSkeletonCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(92.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.30f))
            )
        }
    }
}

@Composable
fun BookingsOverviewChart(
    bookings: List<com.example.data.model.Booking>,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outline

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, outline, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
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
                Text("Bookings Overview", style = MaterialTheme.typography.titleMedium)
                GolocalStatusBadge(label = "${bookings.size} total", tone = "info")
            }

            if (bookings.isEmpty()) {
                Text(
                    text = "No real bookings recorded yet. Trends appear automatically as trips are created.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val buckets = listOf(
                    "Search" to bookings.count { it.status == "searching" },
                    "Active" to bookings.count { it.status in setOf("assigned", "arriving", "arrived", "in_progress") },
                    "Done" to bookings.count { it.status == "completed" },
                    "Cancel" to bookings.count { it.status == "cancelled" }
                )
                val maxVal = max(1, buckets.maxOf { it.second })

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val padX = 24.dp.toPx()
                    val padY = 16.dp.toPx()
                    val usableW = w - padX * 2
                    val usableH = h - padY * 2

                    for (g in 0..2) {
                        val y = padY + usableH * (g / 2f)
                        drawLine(
                            color = outline.copy(alpha = 0.6f),
                            start = Offset(padX, y),
                            end = Offset(w - padX, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    val points = buckets.mapIndexed { idx, (_, count) ->
                        val x = padX + (idx.toFloat() / max(1, buckets.lastIndex)) * usableW
                        val y = padY + usableH * (1f - count.toFloat() / maxVal.toFloat())
                        Offset(x, y)
                    }

                    val fillPath = Path().apply {
                        moveTo(points.first().x, h - padY)
                        points.forEach { lineTo(it.x, it.y) }
                        lineTo(points.last().x, h - padY)
                        close()
                    }
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(primary.copy(alpha = 0.25f), Color.Transparent)
                        )
                    )

                    val linePath = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(
                        path = linePath,
                        color = primary,
                        style = Stroke(width = 3.dp.toPx())
                    )

                    points.forEach { pt ->
                        drawCircle(color = Color.White, radius = 6.dp.toPx(), center = pt)
                        drawCircle(color = secondary, radius = 4.dp.toPx(), center = pt)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    buckets.forEach { (label, count) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = JetBrainsMono,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

