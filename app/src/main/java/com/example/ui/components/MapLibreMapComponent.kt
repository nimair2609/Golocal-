package com.example.ui.components

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.map.MapProviderConfig
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.GeoPointDto
import com.example.data.model.ServiceArea
import com.example.domain.BoraiLaunchConfig
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalPrimaryBlue
import com.example.ui.theme.GolocalPureWhite
import com.example.ui.theme.GolocalSuccess
import com.example.ui.theme.JetBrainsMono
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

data class MapMarkerPin(
    val id: String,
    val lat: Double,
    val lng: Double,
    val title: String,
    val subtitle: String = "",
    val colorHex: String = "#1565C0",
    val radiusPx: Float = 8f
)

data class MapZoneOverlay(
    val id: String,
    val name: String,
    val centerLat: Double,
    val centerLng: Double,
    val radiusKm: Double,
    val polygonPoints: List<GeoPointDto> = emptyList(),
    val isActive: Boolean = true,
    val pickupEnabled: Boolean = true,
    val dropoffEnabled: Boolean = true
)

fun ServiceArea.toMapZoneOverlay(): MapZoneOverlay = MapZoneOverlay(
    id = id,
    name = name,
    centerLat = if (centerLat != 0.0) centerLat else lat,
    centerLng = if (centerLng != 0.0) centerLng else lng,
    radiusKm = radiusKm,
    polygonPoints = polygonPoints,
    isActive = isActive,
    pickupEnabled = pickupEnabled,
    dropoffEnabled = dropoffEnabled
)

@Composable
fun GoLocalOsmMapCard(
    centerLat: Double = BoraiLaunchConfig.DEFAULT_LAT,
    centerLng: Double = BoraiLaunchConfig.DEFAULT_LNG,
    zoom: Double = BoraiLaunchConfig.DEFAULT_ZOOM,
    pickupPoint: GeoPointDto? = null,
    pickupLabel: String = "",
    destinationPoint: GeoPointDto? = null,
    destinationLabel: String = "",
    driverLivePoint: GeoPointDto? = null,
    onlineDrivers: List<DriverDirectoryEntry> = emptyList(),
    extraMarkers: List<MapMarkerPin> = emptyList(),
    routePoints: List<GeoPointDto> = emptyList(),
    zones: List<MapZoneOverlay> = emptyList(),
    lowNetworkMode: Boolean = false,
    interactivePinMode: String = "none", // "none", "pickup", "destination", "zone_center", "zone_vertex"
    onMapTap: ((lat: Double, lng: Double) -> Unit)? = null,
    onDragPickup: ((lat: Double, lng: Double) -> Unit)? = null,
    onDragDestination: ((lat: Double, lng: Double) -> Unit)? = null,
    statusBadgeText: String? = null,
    height: Dp = 260.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val endpoints = remember(context) { MapProviderConfig.loadEndpoints(context) }
    val nativeReady = remember(context) { ensureMapLibreInitialized(context) }

    val latestOnMapTap by rememberUpdatedState(onMapTap)
    val latestPinMode by rememberUpdatedState(interactivePinMode)
    val latestOnDragPickup by rememberUpdatedState(onDragPickup)
    val latestOnDragDest by rememberUpdatedState(onDragDestination)

    val zonesGeoJson = remember(zones) { buildZonesGeoJson(zones) }
    val routeGeoJson = remember(routePoints, pickupPoint, destinationPoint) {
        buildRouteGeoJson(routePoints, pickupPoint, destinationPoint)
    }
    val markersGeoJson = remember(
        pickupPoint, destinationPoint, driverLivePoint, onlineDrivers, extraMarkers
    ) {
        buildMarkersGeoJson(
            pickupPoint = pickupPoint,
            destinationPoint = destinationPoint,
            driverLivePoint = driverLivePoint,
            onlineDrivers = onlineDrivers,
            extraMarkers = extraMarkers
        )
    }

    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }

    // Keep sources synced whenever coordinates/routes/zones change
    LaunchedEffect(mapInstance, styleReady, zonesGeoJson, routeGeoJson, markersGeoJson) {
        val map = mapInstance ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        map.getStyle { style ->
            (style.getSource(SRC_ZONES) as? GeoJsonSource)?.setGeoJson(zonesGeoJson)
            (style.getSource(SRC_ROUTE) as? GeoJsonSource)?.setGeoJson(routeGeoJson)
            (style.getSource(SRC_MARKERS) as? GeoJsonSource)?.setGeoJson(markersGeoJson)
        }
    }

    // Smooth camera fit to active bounds
    LaunchedEffect(mapInstance, styleReady, centerLat, centerLng, pickupPoint, destinationPoint, driverLivePoint) {
        val map = mapInstance ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val pts = buildList {
            pickupPoint?.let { add(LatLng(it.lat, it.lng)) }
            destinationPoint?.let { add(LatLng(it.lat, it.lng)) }
            driverLivePoint?.let { add(LatLng(it.lat, it.lng)) }
        }
        runCatching {
            if (pts.size >= 2) {
                val builder = LatLngBounds.Builder()
                pts.forEach { builder.include(it) }
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 90), 450)
            } else {
                val target = pts.firstOrNull() ?: LatLng(centerLat, centerLng)
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, zoom), 450)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(22.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
                .testTag("osm_map_container")
        ) {
            if (nativeReady && !lowNetworkMode) {
                MapLibreAndroidViewHost(
                    tileUrlTemplate = endpoints.osmTileUrlTemplate,
                    centerLat = centerLat,
                    centerLng = centerLng,
                    zoom = zoom,
                    zonesGeoJson = zonesGeoJson,
                    routeGeoJson = routeGeoJson,
                    markersGeoJson = markersGeoJson,
                    onMapReady = { map ->
                        mapInstance = map
                        styleReady = true
                    },
                    onMapClick = { lat, lng ->
                        when (latestPinMode) {
                            "pickup" -> latestOnDragPickup?.invoke(lat, lng) ?: latestOnMapTap?.invoke(lat, lng)
                            "destination" -> latestOnDragDest?.invoke(lat, lng) ?: latestOnMapTap?.invoke(lat, lng)
                            else -> latestOnMapTap?.invoke(lat, lng)
                        }
                    }
                )
            } else {
                LiteOsmFallbackCanvas(
                    pickupPoint = pickupPoint,
                    destinationPoint = destinationPoint,
                    driverLivePoint = driverLivePoint,
                    onlineDrivers = onlineDrivers,
                    extraMarkers = extraMarkers,
                    routePoints = routePoints,
                    lowNetworkMode = lowNetworkMode
                )
            }

            // Top HUD row: OSM status + live drivers pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
                    shape = RoundedCornerShape(999.dp),
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = if (lowNetworkMode) Icons.Filled.SignalCellularAlt else Icons.Filled.Map,
                            contentDescription = null,
                            tint = GolocalPrimaryBlue,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = statusBadgeText ?: if (lowNetworkMode) {
                                "OSM Vector-Lite (Low Data)"
                            } else {
                                "OpenStreetMap • MapLibre"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (interactivePinMode != "none") {
                    Surface(
                        color = GolocalPrimaryBlue,
                        shape = RoundedCornerShape(999.dp),
                        shadowElevation = 2.dp
                    ) {
                        Text(
                            text = when (interactivePinMode) {
                                "pickup" -> "Tap map to place Pickup"
                                "destination" -> "Tap map to place Drop"
                                "zone_center" -> "Tap map for Zone Center"
                                "zone_vertex" -> "Tap map to add Polygon Point"
                                else -> "Tap map"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = GolocalPureWhite,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                } else {
                    val onlineCount = onlineDrivers.count { it.isOnline }
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
                        shape = RoundedCornerShape(999.dp),
                        shadowElevation = 2.dp
                    ) {
                        Text(
                            text = if (onlineCount > 0) "$onlineCount live riders" else "Borai 721457 Ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (onlineCount > 0) GolocalSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            // Bottom HUD: Attribution & quick legend
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = endpoints.tileAttribution,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                if (pickupLabel.isNotBlank() || destinationLabel.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.widthIn(max = 210.dp)
                    ) {
                        Text(
                            text = listOf(pickupLabel, destinationLabel)
                                .filter { it.isNotBlank() }
                                .joinToString(" -> "),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // Accessible Marker Nudge / Drag Controls when editing pickup or destination pins
        if (interactivePinMode == "pickup" && pickupPoint != null && onDragPickup != null) {
            PinDragNudgeBar(
                label = "Adjust Pickup Pin (${String.format("%.4f", pickupPoint.lat)}, ${String.format("%.4f", pickupPoint.lng)})",
                accent = GolocalSuccess,
                onNudge = { dLat, dLng ->
                    onDragPickup(pickupPoint.lat + dLat, pickupPoint.lng + dLng)
                }
            )
        } else if (interactivePinMode == "destination" && destinationPoint != null && onDragDestination != null) {
            PinDragNudgeBar(
                label = "Adjust Destination Pin (${String.format("%.4f", destinationPoint.lat)}, ${String.format("%.4f", destinationPoint.lng)})",
                accent = GolocalPrimaryBlue,
                onNudge = { dLat, dLng ->
                    onDragDestination(destinationPoint.lat + dLat, destinationPoint.lng + dLng)
                }
            )
        }
    }
}

@Composable
private fun PinDragNudgeBar(
    label: String,
    accent: Color,
    onNudge: (dLat: Double, dLng: Double) -> Unit
) {
    val step = 0.0012 // ~130 meters per nudge
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMono,
                color = accent,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = { onNudge(step, 0.0) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move pin North")
                }
                IconButton(onClick = { onNudge(-step, 0.0) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move pin South")
                }
                IconButton(onClick = { onNudge(0.0, -step) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Move pin West")
                }
                IconButton(onClick = { onNudge(0.0, step) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Move pin East")
                }
            }
        }
    }
}

@Composable
private fun MapLibreAndroidViewHost(
    tileUrlTemplate: String,
    centerLat: Double,
    centerLng: Double,
    zoom: Double,
    zonesGeoJson: String,
    routeGeoJson: String,
    markersGeoJson: String,
    onMapReady: (MapLibreMap) -> Unit,
    onMapClick: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            runCatching {
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                    else -> Unit
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching {
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    AndroidView(
        factory = {
            mapView.apply {
                runCatching {
                    onCreate(null)
                    getMapAsync { map ->
                        map.uiSettings.isAttributionEnabled = false
                        map.uiSettings.isLogoEnabled = false
                        val styleJson = MapProviderConfig.buildMapLibreStyleJson(
                            tileUrlTemplate = tileUrlTemplate,
                            lowNetworkLite = false
                        )
                        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                            installGoLocalLayers(
                                style = style,
                                zonesGeoJson = zonesGeoJson,
                                routeGeoJson = routeGeoJson,
                                markersGeoJson = markersGeoJson
                            )
                            runCatching {
                                map.moveCamera(
                                    CameraUpdateFactory.newLatLngZoom(
                                        LatLng(centerLat, centerLng),
                                        zoom
                                    )
                                )
                            }
                            map.addOnMapClickListener { latLng ->
                                onMapClick(latLng.latitude, latLng.longitude)
                                true
                            }
                            map.addOnMapLongClickListener { latLng ->
                                onMapClick(latLng.latitude, latLng.longitude)
                                true
                            }
                            onMapReady(map)
                        }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun LiteOsmFallbackCanvas(
    pickupPoint: GeoPointDto?,
    destinationPoint: GeoPointDto?,
    driverLivePoint: GeoPointDto?,
    onlineDrivers: List<DriverDirectoryEntry>,
    extraMarkers: List<MapMarkerPin>,
    routePoints: List<GeoPointDto>,
    lowNetworkMode: Boolean
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val padX = w * 0.14f
            val padY = h * 0.22f
            for (i in 1..4) {
                val y = h * (i / 5f)
                drawLine(outline.copy(alpha = 0.4f), Offset(0f, y), Offset(w, y), 1.dp.toPx())
            }
            for (j in 1..5) {
                val x = w * (j / 6f)
                drawLine(outline.copy(alpha = 0.4f), Offset(x, 0f), Offset(x, h), 1.dp.toPx())
            }

            val allPts = buildList {
                pickupPoint?.let { add(it) }
                destinationPoint?.let { add(it) }
                driverLivePoint?.let { add(it) }
                addAll(routePoints)
                onlineDrivers.filter { it.isOnline }.forEach { add(GeoPointDto(it.currentLat, it.currentLng)) }
                extraMarkers.forEach { add(GeoPointDto(it.lat, it.lng)) }
            }.ifEmpty { listOf(GeoPointDto(BoraiLaunchConfig.DEFAULT_LAT, BoraiLaunchConfig.DEFAULT_LNG)) }

            val minLat = allPts.minOf { it.lat } - 0.015
            val maxLat = allPts.maxOf { it.lat } + 0.015
            val minLng = allPts.minOf { it.lng } - 0.015
            val maxLng = allPts.maxOf { it.lng } + 0.015

            fun project(lat: Double, lng: Double): Offset {
                val nx = ((lng - minLng) / max(0.005, maxLng - minLng)).toFloat().coerceIn(0f, 1f)
                val ny = (1f - ((lat - minLat) / max(0.005, maxLat - minLat)).toFloat()).coerceIn(0f, 1f)
                return Offset(padX + nx * (w - 2 * padX), padY + ny * (h - 2 * padY))
            }

            val effectiveRoute = when {
                routePoints.size >= 2 -> routePoints
                pickupPoint != null && destinationPoint != null -> listOf(pickupPoint, destinationPoint)
                else -> emptyList()
            }
            if (effectiveRoute.size >= 2) {
                val path = Path().apply {
                    val first = project(effectiveRoute.first().lat, effectiveRoute.first().lng)
                    moveTo(first.x, first.y)
                    effectiveRoute.drop(1).forEach { pt ->
                        val p = project(pt.lat, pt.lng)
                        lineTo(p.x, p.y)
                    }
                }
                drawPath(
                    path = path,
                    color = primaryColor,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        pathEffect = if (lowNetworkMode) PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f) else null
                    )
                )
            }

            onlineDrivers.filter { it.isOnline }.forEach { d ->
                val p = project(d.currentLat, d.currentLng)
                drawCircle(GolocalPureWhite, radius = 6.dp.toPx(), center = p)
                drawCircle(Color(0xFF0D9488), radius = 4.dp.toPx(), center = p)
            }
            extraMarkers.forEach { m ->
                val p = project(m.lat, m.lng)
                drawCircle(GolocalPureWhite, radius = 6.dp.toPx(), center = p)
                drawCircle(GolocalError, radius = 4.dp.toPx(), center = p)
            }
            driverLivePoint?.let { live ->
                val p = project(live.lat, live.lng)
                drawCircle(GolocalSuccess.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = p)
                drawCircle(GolocalSuccess, radius = 6.dp.toPx(), center = p)
            }
            pickupPoint?.let { p ->
                val o = project(p.lat, p.lng)
                drawCircle(GolocalSuccess.copy(alpha = 0.22f), radius = 12.dp.toPx(), center = o)
                drawCircle(GolocalSuccess, radius = 6.dp.toPx(), center = o)
            }
            destinationPoint?.let { d ->
                val o = project(d.lat, d.lng)
                drawCircle(primaryColor.copy(alpha = 0.22f), radius = 12.dp.toPx(), center = o)
                drawCircle(primaryColor, radius = 6.dp.toPx(), center = o)
            }
        }
    }
}

private const val SRC_ZONES = "golocal-zones-src"
private const val SRC_ROUTE = "golocal-route-src"
private const val SRC_MARKERS = "golocal-markers-src"

private fun ensureMapLibreInitialized(context: Context): Boolean = try {
    MapLibre.getInstance(context.applicationContext)
    true
} catch (_: Throwable) {
    false
}

private fun installGoLocalLayers(
    style: Style,
    zonesGeoJson: String,
    routeGeoJson: String,
    markersGeoJson: String
) {
    style.addSource(GeoJsonSource(SRC_ZONES, zonesGeoJson))
    style.addSource(GeoJsonSource(SRC_ROUTE, routeGeoJson))
    style.addSource(GeoJsonSource(SRC_MARKERS, markersGeoJson))

    style.addLayer(
        FillLayer("golocal-zones-fill", SRC_ZONES).withProperties(
            PropertyFactory.fillColor(AndroidColor.parseColor("#1D4ED8")),
            PropertyFactory.fillOpacity(0.12f)
        )
    )
    style.addLayer(
        LineLayer("golocal-zones-outline", SRC_ZONES).withProperties(
            PropertyFactory.lineColor(AndroidColor.parseColor("#1D4ED8")),
            PropertyFactory.lineWidth(2.0f)
        )
    )
    style.addLayer(
        LineLayer("golocal-route-line", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(AndroidColor.parseColor("#1565C0")),
            PropertyFactory.lineWidth(4.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    style.addLayer(
        CircleLayer("golocal-markers-circle", SRC_MARKERS).withProperties(
            PropertyFactory.circleRadius(Expression.get("radius")),
            PropertyFactory.circleColor(Expression.get("color")),
            PropertyFactory.circleStrokeWidth(2.5f),
            PropertyFactory.circleStrokeColor(AndroidColor.WHITE)
        )
    )
}

internal fun buildZonesGeoJson(zones: List<MapZoneOverlay>): String {
    val features = JSONArray()
    zones.filter { it.isActive }.forEach { zone ->
        val ringPts = if (zone.polygonPoints.size >= 3) {
            zone.polygonPoints
        } else if (zone.centerLat != 0.0 && zone.centerLng != 0.0) {
            approximateCirclePolygon(zone.centerLat, zone.centerLng, zone.radiusKm.coerceAtLeast(1.0))
        } else {
            emptyList()
        }
        if (ringPts.size >= 3) {
            val ring = JSONArray()
            ringPts.forEach { pt ->
                ring.put(JSONArray().put(pt.lng).put(pt.lat))
            }
            ring.put(JSONArray().put(ringPts.first().lng).put(ringPts.first().lat))
            val coords = JSONArray().put(ring)
            val geometry = JSONObject().put("type", "Polygon").put("coordinates", coords)
            val props = JSONObject().put("id", zone.id).put("name", zone.name)
            features.put(JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", props))
        }
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

internal fun buildRouteGeoJson(
    routePoints: List<GeoPointDto>,
    pickupPoint: GeoPointDto?,
    destinationPoint: GeoPointDto?
): String {
    val effective = when {
        routePoints.size >= 2 -> routePoints
        pickupPoint != null && destinationPoint != null -> listOf(pickupPoint, destinationPoint)
        else -> emptyList()
    }
    val features = JSONArray()
    if (effective.size >= 2) {
        val coords = JSONArray()
        effective.forEach { pt -> coords.put(JSONArray().put(pt.lng).put(pt.lat)) }
        val geometry = JSONObject().put("type", "LineString").put("coordinates", coords)
        features.put(JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", JSONObject()))
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

internal fun buildMarkersGeoJson(
    pickupPoint: GeoPointDto?,
    destinationPoint: GeoPointDto?,
    driverLivePoint: GeoPointDto?,
    onlineDrivers: List<DriverDirectoryEntry>,
    extraMarkers: List<MapMarkerPin>
): String {
    val features = JSONArray()
    fun addPoint(lat: Double, lng: Double, color: String, radius: Float, title: String) {
        val geom = JSONObject()
            .put("type", "Point")
            .put("coordinates", JSONArray().put(lng).put(lat))
        val props = JSONObject()
            .put("color", color)
            .put("radius", radius.toDouble())
            .put("title", title)
        features.put(JSONObject().put("type", "Feature").put("geometry", geom).put("properties", props))
    }

    onlineDrivers.filter { it.isOnline }.forEach { d ->
        addPoint(d.currentLat, d.currentLng, "#0D9488", 7.0f, d.displayName)
    }
    extraMarkers.forEach { m ->
        addPoint(m.lat, m.lng, m.colorHex, m.radiusPx, m.title)
    }
    pickupPoint?.let { addPoint(it.lat, it.lng, "#16A34A", 9.0f, "Pickup") }
    destinationPoint?.let { addPoint(it.lat, it.lng, "#1565C0", 9.0f, "Destination") }
    driverLivePoint?.let { addPoint(it.lat, it.lng, "#F59E0B", 10.0f, "Live Driver") }

    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

private fun approximateCirclePolygon(centerLat: Double, centerLng: Double, radiusKm: Double): List<GeoPointDto> {
    val steps = 24
    val latDelta = radiusKm / 111.32
    val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.2)
    val lngDelta = radiusKm / (111.32 * cosLat)
    return (0 until steps).map { idx ->
        val angle = 2.0 * Math.PI * idx / steps
        GeoPointDto(
            lat = centerLat + latDelta * sin(angle),
            lng = centerLng + lngDelta * cos(angle)
        )
    }
}
