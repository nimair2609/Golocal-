package com.example.ui.components

import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.map.MapProviderConfig
import com.example.data.map.RoutePoint
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.ServiceArea
import com.example.data.model.TripLocation
import com.example.domain.BoraiLaunchConfig
import com.example.ui.theme.GolocalError
import com.example.ui.theme.GolocalSuccess
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.cos
import kotlin.math.sin

object MapLibreInitializer {
    private const val TAG = "MapLibreInit"

    @Volatile
    private var cachedSupported: Boolean? = null

    fun ensureInitialized(context: Context): Boolean {
        cachedSupported?.let { return it }
        return synchronized(this) {
            cachedSupported?.let { return it }
            val ok = try {
                MapLibre.getInstance(context.applicationContext)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "MapLibre native library unavailable in current host (${t.javaClass.simpleName}); using structured map card fallback")
                false
            }
            cachedSupported = ok
            ok
        }
    }
}

@Composable
fun GolocalMapLibreView(
    pickupLat: Double,
    pickupLng: Double,
    pickupLabel: String,
    destLat: Double,
    destLng: Double,
    destLabel: String,
    routePoints: List<RoutePoint> = emptyList(),
    routeSource: String = "osrm",
    onlineDrivers: List<DriverDirectoryEntry> = emptyList(),
    liveTripLocation: TripLocation? = null,
    serviceAreas: List<ServiceArea> = emptyList(),
    lowNetworkMode: Boolean = false,
    height: Dp = 240.dp,
    interactiveCenterPicker: Boolean = false,
    centerLat: Double = BoraiLaunchConfig.DEFAULT_LAT,
    centerLng: Double = BoraiLaunchConfig.DEFAULT_LNG,
    onCameraCenterChanged: ((lat: Double, lng: Double) -> Unit)? = null,
    onMapClicked: ((lat: Double, lng: Double) -> Unit)? = null,
    onRecenterClicked: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val nativeMapReady = remember(context) { MapLibreInitializer.ensureInitialized(context) }

    val routeState = rememberUpdatedState(routePoints)
    val driversState = rememberUpdatedState(onlineDrivers)
    val liveTripState = rememberUpdatedState(liveTripLocation)
    val zonesState = rememberUpdatedState(serviceAreas)
    val endpointsState = rememberUpdatedState(
        QuadPoints(pickupLat, pickupLng, destLat, destLng, routeSource)
    )
    val pickerCallbackState = rememberUpdatedState(onCameraCenterChanged)
    val clickCallbackState = rememberUpdatedState(onMapClicked)

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }

    LaunchedEffect(
        styleReady,
        pickupLat,
        pickupLng,
        destLat,
        destLng,
        routePoints,
        routeSource,
        onlineDrivers,
        liveTripLocation,
        serviceAreas,
        interactiveCenterPicker
    ) {
        val map = mapRef ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect

        updateGeoJsonLayers(
            style = style,
            endpoints = endpointsState.value,
            routePoints = routeState.value,
            drivers = driversState.value,
            liveTrip = liveTripState.value,
            zones = zonesState.value
        )

        if (!interactiveCenterPicker) {
            fitCameraToRouteAndDrivers(
                map = map,
                pickupLat = pickupLat,
                pickupLng = pickupLng,
                destLat = destLat,
                destLng = destLng,
                liveTrip = liveTripLocation
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (lowNetworkMode) 144.dp else height)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("golocal_maplibre_container")
    ) {
        if (!lowNetworkMode && nativeMapReady) {
            val mapView = remember(context) {
                MapView(context).apply {
                    onCreate(null)
                }
            }

            DisposableEffect(lifecycleOwner, mapView) {
                val observer = object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) = mapView.onStart()
                    override fun onResume(owner: LifecycleOwner) = mapView.onResume()
                    override fun onPause(owner: LifecycleOwner) = mapView.onPause()
                    override fun onStop(owner: LifecycleOwner) = mapView.onStop()
                    override fun onDestroy(owner: LifecycleOwner) = mapView.onDestroy()
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    runCatching {
                        mapView.onStop()
                        mapView.onDestroy()
                    }
                }
            }

            AndroidView(
                factory = {
                    mapView.apply {
                        getMapAsync { map ->
                            mapRef = map
                            map.uiSettings.apply {
                                isCompassEnabled = true
                                isAttributionEnabled = false
                                isLogoEnabled = false
                                isRotateGesturesEnabled = true
                                isZoomGesturesEnabled = true
                                isScrollGesturesEnabled = true
                            }
                            val startLat = if (interactiveCenterPicker) centerLat else pickupLat
                            val startLng = if (interactiveCenterPicker) centerLng else pickupLng
                            map.cameraPosition = CameraPosition.Builder()
                                .target(LatLng(startLat, startLng))
                                .zoom(BoraiLaunchConfig.DEFAULT_ZOOM)
                                .build()

                            map.setStyle(
                                Style.Builder().fromJson(MapProviderConfig.buildOsmStyleJson())
                            ) { loadedStyle ->
                                ensureOverlaySourcesAndLayers(loadedStyle)
                                styleReady = true
                                updateGeoJsonLayers(
                                    style = loadedStyle,
                                    endpoints = endpointsState.value,
                                    routePoints = routeState.value,
                                    drivers = driversState.value,
                                    liveTrip = liveTripState.value,
                                    zones = zonesState.value
                                )
                                if (!interactiveCenterPicker) {
                                    fitCameraToRouteAndDrivers(
                                        map = map,
                                        pickupLat = endpointsState.value.pickupLat,
                                        pickupLng = endpointsState.value.pickupLng,
                                        destLat = endpointsState.value.destLat,
                                        destLng = endpointsState.value.destLng,
                                        liveTrip = liveTripState.value
                                    )
                                }
                            }

                            map.addOnMapClickListener { point ->
                                clickCallbackState.value?.invoke(point.latitude, point.longitude)
                                clickCallbackState.value != null
                            }

                            map.addOnCameraIdleListener {
                                val target = map.cameraPosition.target
                                if (target != null) {
                                    pickerCallbackState.value?.invoke(target.latitude, target.longitude)
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Low-network mode OR JVM/Robolectric environment without native libmaplibre.so
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    .padding(16.dp)
                    .testTag("maplibre_fallback_preview"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Filled.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = if (lowNetworkMode) "Low-Network OpenStreetMap Summary" else "OpenStreetMap Route View",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "$pickupLabel → $destLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    liveTripLocation?.let { loc ->
                        Text(
                            text = "Driver Live GPS: ${String.format("%.4f", loc.lat)}, ${String.format("%.4f", loc.lng)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = GolocalSuccess
                        )
                    }
                }
            }
        }

        // Crosshair pin in center picker mode (draggable map under pin)
        if (interactiveCenterPicker) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.AddLocationAlt,
                        contentDescription = "Selected map center",
                        tint = GolocalError,
                        modifier = Modifier.size(36.dp)
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                    )
                }
            }
        }

        // Top badges & recenter button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(999.dp),
                shadowElevation = 2.dp
            ) {
                val activeDrivers = onlineDrivers.count { it.isOnline }
                Text(
                    text = if (routeSource == "osrm") {
                        "OSM + OSRM • $activeDrivers online"
                    } else {
                        "OSM Fallback • $activeDrivers online"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            if (onRecenterClicked != null || mapRef != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shadowElevation = 3.dp
                ) {
                    IconButton(
                        onClick = {
                            onRecenterClicked?.invoke()
                            mapRef?.let { map ->
                                fitCameraToRouteAndDrivers(
                                    map = map,
                                    pickupLat = pickupLat,
                                    pickupLng = pickupLng,
                                    destLat = destLat,
                                    destLng = destLng,
                                    liveTrip = liveTripLocation
                                )
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("map_recenter_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MyLocation,
                            contentDescription = "Center map on route",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Bottom endpoint summary & OpenStreetMap attribution
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!interactiveCenterPicker) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.GpsFixed, contentDescription = null, tint = GolocalSuccess, modifier = Modifier.size(14.dp))
                            Text(
                                text = pickupLabel,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(" → ", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = destLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Surface(
                color = Color.Black.copy(alpha = 0.62f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    text = MapProviderConfig.OSM_ATTRIBUTION,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private data class QuadPoints(
    val pickupLat: Double,
    val pickupLng: Double,
    val destLat: Double,
    val destLng: Double,
    val routeSource: String
)

private const val SRC_ZONES = "golocal-zones-source"
private const val SRC_ROUTE = "golocal-route-source"
private const val SRC_DRIVERS = "golocal-drivers-source"
private const val SRC_ENDPOINTS = "golocal-endpoints-source"

private fun ensureOverlaySourcesAndLayers(style: Style) {
    if (style.getSource(SRC_ZONES) == null) {
        style.addSource(GeoJsonSource(SRC_ZONES))
    }
    if (style.getSource(SRC_ROUTE) == null) {
        style.addSource(GeoJsonSource(SRC_ROUTE))
    }
    if (style.getSource(SRC_DRIVERS) == null) {
        style.addSource(GeoJsonSource(SRC_DRIVERS))
    }
    if (style.getSource(SRC_ENDPOINTS) == null) {
        style.addSource(GeoJsonSource(SRC_ENDPOINTS))
    }

    if (style.getLayer("golocal-zones-fill") == null) {
        style.addLayer(
            FillLayer("golocal-zones-fill", SRC_ZONES).withProperties(
                PropertyFactory.fillColor(AndroidColor.parseColor("#1976D2")),
                PropertyFactory.fillOpacity(0.14f)
            )
        )
    }
    if (style.getLayer("golocal-zones-outline") == null) {
        style.addLayer(
            LineLayer("golocal-zones-outline", SRC_ZONES).withProperties(
                PropertyFactory.lineColor(AndroidColor.parseColor("#0D47A1")),
                PropertyFactory.lineWidth(2f)
            )
        )
    }
    if (style.getLayer("golocal-route-line") == null) {
        style.addLayer(
            LineLayer("golocal-route-line", SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(AndroidColor.parseColor("#1565C0")),
                PropertyFactory.lineWidth(4.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }
    if (style.getLayer("golocal-drivers-circles") == null) {
        style.addLayer(
            CircleLayer("golocal-drivers-circles", SRC_DRIVERS).withProperties(
                PropertyFactory.circleRadius(6.5f),
                PropertyFactory.circleColor(AndroidColor.parseColor("#EF6C00")),
                PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
                PropertyFactory.circleStrokeWidth(2f)
            )
        )
    }
    if (style.getLayer("golocal-pickup-circle") == null) {
        style.addLayer(
            CircleLayer("golocal-pickup-circle", SRC_ENDPOINTS)
                .withFilter(org.maplibre.android.style.expressions.Expression.eq(
                    org.maplibre.android.style.expressions.Expression.get("kind"),
                    org.maplibre.android.style.expressions.Expression.literal("pickup")
                ))
                .withProperties(
                    PropertyFactory.circleRadius(8.5f),
                    PropertyFactory.circleColor(AndroidColor.parseColor("#1B8E3E")),
                    PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
                    PropertyFactory.circleStrokeWidth(2.5f)
                )
        )
    }
    if (style.getLayer("golocal-dest-circle") == null) {
        style.addLayer(
            CircleLayer("golocal-dest-circle", SRC_ENDPOINTS)
                .withFilter(org.maplibre.android.style.expressions.Expression.eq(
                    org.maplibre.android.style.expressions.Expression.get("kind"),
                    org.maplibre.android.style.expressions.Expression.literal("destination")
                ))
                .withProperties(
                    PropertyFactory.circleRadius(8.5f),
                    PropertyFactory.circleColor(AndroidColor.parseColor("#C62828")),
                    PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
                    PropertyFactory.circleStrokeWidth(2.5f)
                )
        )
    }
    if (style.getLayer("golocal-live-driver-circle") == null) {
        style.addLayer(
            CircleLayer("golocal-live-driver-circle", SRC_ENDPOINTS)
                .withFilter(org.maplibre.android.style.expressions.Expression.eq(
                    org.maplibre.android.style.expressions.Expression.get("kind"),
                    org.maplibre.android.style.expressions.Expression.literal("live_driver")
                ))
                .withProperties(
                    PropertyFactory.circleRadius(9.5f),
                    PropertyFactory.circleColor(AndroidColor.parseColor("#0A2463")),
                    PropertyFactory.circleStrokeColor(AndroidColor.parseColor("#FFD600")),
                    PropertyFactory.circleStrokeWidth(3f)
                )
        )
    }
}

private fun updateGeoJsonLayers(
    style: Style,
    endpoints: QuadPoints,
    routePoints: List<RoutePoint>,
    drivers: List<DriverDirectoryEntry>,
    liveTrip: TripLocation?,
    zones: List<ServiceArea>
) {
    style.getSourceAs<GeoJsonSource>(SRC_ZONES)?.setGeoJson(buildZonesGeoJson(zones))

    val pts = if (routePoints.size >= 2) routePoints else listOf(
        RoutePoint(endpoints.pickupLat, endpoints.pickupLng),
        RoutePoint(endpoints.destLat, endpoints.destLng)
    )
    val routeCoords = JSONArray()
    pts.forEach { p -> routeCoords.put(JSONArray().put(p.lng).put(p.lat)) }
    val routeGeoJson = JSONObject()
        .put("type", "FeatureCollection")
        .put(
            "features",
            JSONArray().put(
                JSONObject()
                    .put("type", "Feature")
                    .put("properties", JSONObject().put("source", endpoints.routeSource))
                    .put(
                        "geometry",
                        JSONObject().put("type", "LineString").put("coordinates", routeCoords)
                    )
            )
        )
        .toString()
    style.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(routeGeoJson)

    val driverFeatures = JSONArray()
    drivers.filter { it.isOnline }.forEach { d ->
        driverFeatures.put(
            pointFeature(d.lngOrDefault(), d.latOrDefault(), JSONObject().put("driverId", d.driverId))
        )
    }
    style.getSourceAs<GeoJsonSource>(SRC_DRIVERS)?.setGeoJson(
        JSONObject().put("type", "FeatureCollection").put("features", driverFeatures).toString()
    )

    val endpointFeatures = JSONArray()
        .put(pointFeature(endpoints.pickupLng, endpoints.pickupLat, JSONObject().put("kind", "pickup")))
        .put(pointFeature(endpoints.destLng, endpoints.destLat, JSONObject().put("kind", "destination")))
    if (liveTrip != null) {
        endpointFeatures.put(
            pointFeature(liveTrip.lng, liveTrip.lat, JSONObject().put("kind", "live_driver"))
        )
    }
    style.getSourceAs<GeoJsonSource>(SRC_ENDPOINTS)?.setGeoJson(
        JSONObject().put("type", "FeatureCollection").put("features", endpointFeatures).toString()
    )
}

private fun DriverDirectoryEntry.latOrDefault(): Double =
    if (currentLat in -90.0..90.0 && currentLat != 0.0) currentLat else BoraiLaunchConfig.DEFAULT_LAT

private fun DriverDirectoryEntry.lngOrDefault(): Double =
    if (currentLng in -180.0..180.0 && currentLng != 0.0) currentLng else BoraiLaunchConfig.DEFAULT_LNG

private fun pointFeature(lng: Double, lat: Double, props: JSONObject): JSONObject {
    return JSONObject()
        .put("type", "Feature")
        .put("properties", props)
        .put(
            "geometry",
            JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lng).put(lat))
        )
}

private fun buildZonesGeoJson(zones: List<ServiceArea>): String {
    val features = JSONArray()
    zones.filter { it.isActive && it.serviceAvailable }.forEach { area ->
        val parsedRing = if (area.boundaryPolygonJson.isNotBlank()) {
            runCatching {
                val arr = JSONArray(area.boundaryPolygonJson)
                if (arr.length() >= 3) {
                    val ring = JSONArray()
                    for (i in 0 until arr.length()) {
                        ring.put(arr.getJSONArray(i))
                    }
                    ring.put(arr.getJSONArray(0))
                    ring
                } else null
            }.getOrNull()
        } else null

        val ring = parsedRing ?: buildCirclePolygonRing(area.lat, area.lng, area.radiusKm)
        features.put(
            JSONObject()
                .put("type", "Feature")
                .put("properties", JSONObject().put("id", area.id).put("name", area.name))
                .put(
                    "geometry",
                    JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring))
                )
        )
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

private fun buildCirclePolygonRing(centerLat: Double, centerLng: Double, radiusKm: Double): JSONArray {
    val steps = 28
    val ring = JSONArray()
    val safeRadius = radiusKm.coerceIn(0.5, 100.0)
    val dLat = safeRadius / 111.32
    val dLng = safeRadius / (111.32 * cos(Math.toRadians(centerLat)).coerceAtLeast(0.2))
    for (i in 0..steps) {
        val angle = 2.0 * Math.PI * (i.toDouble() / steps)
        val lat = centerLat + dLat * sin(angle)
        val lng = centerLng + dLng * cos(angle)
        ring.put(JSONArray().put(lng).put(lat))
    }
    return ring
}

private fun fitCameraToRouteAndDrivers(
    map: MapLibreMap,
    pickupLat: Double,
    pickupLng: Double,
    destLat: Double,
    destLng: Double,
    liveTrip: TripLocation?
) {
    val points = mutableListOf(
        LatLng(pickupLat, pickupLng),
        LatLng(destLat, destLng)
    )
    if (liveTrip != null) {
        points.add(LatLng(liveTrip.lat, liveTrip.lng))
    }
    val distinct = points.distinctBy { "${it.latitude},${it.longitude}" }
    if (distinct.size <= 1) {
        val target = distinct.firstOrNull() ?: LatLng(BoraiLaunchConfig.DEFAULT_LAT, BoraiLaunchConfig.DEFAULT_LNG)
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, BoraiLaunchConfig.DEFAULT_ZOOM))
    } else {
        val bounds = LatLngBounds.Builder().includes(distinct).build()
        runCatching {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 88))
        }
    }
}
