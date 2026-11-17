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
    val mapHeight = if (lowNetworkMode) 150.dp else 210.dp
    val validPickup = pickupLat in -85.0..85.0 && pickupLng in -180.0..180.0 && !(pickupLat == 0.0 && pickupLng == 0.0)
    val validDest = destLat in -85.0..85.0 && destLng in -180.0..180.0 && !(destLat == 0.0 && destLng == 0.0)
    val validDrivers = onlineDrivers.filter {
        it.isOnline && it.currentLat in -85.0..85.0 && it.currentLng in -180.0..180.0 && !(it.currentLat == 0.0 && it.currentLng == 0.0)
    }
    val validLive = liveTripLocation?.takeIf {
        it.lat in -85.0..85.0 && it.lng in -180.0..180.0 && !(it.lat == 0.0 && it.lng == 0.0)
    }

    val surfaceBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val routeColor = GolocalPrimaryBlue
    val pickupColor = GolocalSuccess
    val driverColor = GolocalDeepNavy
    val fleetColor = GolocalPrimaryBlue.copy(alpha = 0.72f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(mapHeight)
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        color = surfaceBg
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val padX = 28.dp.toPx()
                val padY = 28.dp.toPx()
                val drawW = max(1f, size.width - padX * 2)
                val drawH = max(1f, size.height - padY * 2)

                for (i in 1..4) {
                    val x = size.width * (i / 5f)
                    val y = size.height * (i / 5f)
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }

                val coords = buildList {
                    if (validPickup) add(pickupLat to pickupLng)
                    if (validDest) add(destLat to destLng)
                    validDrivers.forEach { add(it.currentLat to it.currentLng) }
                    validLive?.let { add(it.lat to it.lng) }
                }

                if (coords.isNotEmpty()) {
                    var minLat = coords.minOf { it.first }
                    var maxLat = coords.maxOf { it.first }
                    var minLng = coords.minOf { it.second }
                    var maxLng = coords.maxOf { it.second }
                    if (maxLat - minLat < 0.01) {
                        minLat -= 0.005
                        maxLat += 0.005
                    }
                    if (maxLng - minLng < 0.01) {
                        minLng -= 0.005
                        maxLng += 0.005
                    }

                    fun project(lat: Double, lng: Double): Offset {
                        val normX = ((lng - minLng) / (maxLng - minLng)).toFloat().coerceIn(0f, 1f)
                        val normY = (1.0 - (lat - minLat) / (maxLat - minLat)).toFloat().coerceIn(0f, 1f)
                        return Offset(padX + normX * drawW, padY + normY * drawH)
                    }

                    val pOffset = if (validPickup) project(pickupLat, pickupLng) else null
                    val dOffset = if (validDest) project(destLat, destLng) else null

                    if (pOffset != null && dOffset != null) {
                        drawLine(
                            color = routeColor,
                            start = pOffset,
                            end = dOffset,
                            strokeWidth = 3.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                        )
                    }

                    validDrivers.forEach { drv ->
                        val pt = project(drv.currentLat, drv.currentLng)
                        drawCircle(color = Color.White, radius = 6.dp.toPx(), center = pt)
                        drawCircle(color = fleetColor, radius = 4.dp.toPx(), center = pt)
                    }

                    pOffset?.let { pt ->
                        drawCircle(color = Color.White, radius = 8.dp.toPx(), center = pt)
                        drawCircle(color = pickupColor, radius = 5.5.dp.toPx(), center = pt)
                    }
                    dOffset?.let { pt ->
                        drawCircle(color = Color.White, radius = 8.dp.toPx(), center = pt)
                        drawCircle(color = routeColor, radius = 5.5.dp.toPx(), center = pt)
                    }
                    validLive?.let { live ->
                        val pt = project(live.lat, live.lng)
                        drawCircle(color = pickupColor, radius = 9.5.dp.toPx(), center = pt)
                        drawCircle(color = driverColor, radius = 6.5.dp.toPx(), center = pt)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GolocalStatusBadge(
                    label = if (lowNetworkMode) "Lite Map (Low Data)" else "OpenStreetMap Lite",
                    tone = if (lowNetworkMode) "warning" else "info"
                )
                if (validDrivers.isNotEmpty()) {
                    GolocalStatusBadge(label = "${validDrivers.size} online", tone = "success")
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = pickupLabel.ifBlank { "Pickup" },
                        style = MaterialTheme.typography.labelSmall,
                        color = pickupColor,
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
                        text = destLabel.ifBlank { "Destination" },
                        style = MaterialTheme.typography.labelSmall,
                        color = routeColor,
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

