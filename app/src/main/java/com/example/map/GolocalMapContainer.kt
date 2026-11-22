package com.example.map

import android.animation.ValueAnimator
import android.os.Build
import android.view.animation.LinearInterpolator
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.map.MapProviderConfig
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.TripLocation
import com.example.ui.components.InteractiveBengalMapCanvas
import com.example.ui.theme.rememberPrefersReducedMotion
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

private const val SRC_ROUTE = "golocal-route-src"
private const val SRC_PICKUP = "golocal-pickup-src"
private const val SRC_DROP = "golocal-drop-src"
private const val SRC_FLEET = "golocal-fleet-src"
private const val SRC_DRIVER = "golocal-driver-src"
private const val SRC_USER_LOC = "golocal-user-loc-src"

private const val LYR_ROUTE = "golocal-route-lyr"
private const val LYR_PICKUP = "golocal-pickup-lyr"
private const val LYR_DROP = "golocal-drop-lyr"
private const val LYR_FLEET = "golocal-fleet-lyr"
private const val LYR_DRIVER = "golocal-driver-lyr"
private const val LYR_USER_LOC = "golocal-user-loc-lyr"

internal fun isRobolectricEnvironment(): Boolean {
    val fp = Build.FINGERPRINT.orEmpty()
    val hw = Build.HARDWARE.orEmpty()
    return fp.contains("robolectric", ignoreCase = true) || hw.contains("robolectric", ignoreCase = true)
}

@Composable
fun GolocalMapContainer(
    pickupLat: Double,
    pickupLng: Double,
    pickupLabel: String,
    destLat: Double,
    destLng: Double,
    destLabel: String,
    onlineDrivers: List<DriverDirectoryEntry>,
    liveTripLocation: TripLocation?,
    lowNetworkMode: Boolean,
    modifier: Modifier = Modifier,
    mapHeight: Dp = 260.dp,
    fillContainer: Boolean = false,
    cornerRadius: Dp = 22.dp,
    showBorder: Boolean = true,
    showTopRouteChip: Boolean = true,
    showRecenterFab: Boolean = true,
    recenterBottomPadding: Dp = 10.dp,
    userLat: Double? = null,
    userLng: Double? = null,
    rerouteFromLatLng: GeoPoint? = null,
    enableCenterPinSelection: Boolean = false,
    onCenterCoordinateChanged: ((Double, Double) -> Unit)? = null,
    onRouteComputed: ((RouteUiState) -> Unit)? = null
) {
    val context = LocalContext.current
    var routeState by remember { mutableStateOf<RouteUiState>(RouteUiState.Idle) }
    var lastRerouteEpochMs by remember(pickupLat, pickupLng, destLat, destLng) { mutableLongStateOf(0L) }
    val latestCenterCallback by androidx.compose.runtime.rememberUpdatedState(onCenterCoordinateChanged)
    val latestPinMode by androidx.compose.runtime.rememberUpdatedState(enableCenterPinSelection)

    // Debounced OSRM driving route fetch with cancellation when endpoints change
    LaunchedEffect(pickupLat, pickupLng, destLat, destLng, lowNetworkMode) {
        if (lowNetworkMode) {
            routeState = RouteUiState.Idle
            onRouteComputed?.invoke(routeState)
            return@LaunchedEffect
        }
        if (!GpsBroadcastThrottle.isValidCoordinate(pickupLat, pickupLng) ||
            !GpsBroadcastThrottle.isValidCoordinate(destLat, destLng) ||
            (pickupLat == destLat && pickupLng == destLng)
        ) {
            routeState = RouteUiState.Idle
            onRouteComputed?.invoke(routeState)
            return@LaunchedEffect
        }
        delay(250L)
        routeState = RouteUiState.Loading
        onRouteComputed?.invoke(routeState)
        val fetched = OsrmRouteService.fetchDrivingRoute(
            origin = GeoPoint(pickupLat, pickupLng),
            destination = GeoPoint(destLat, destLng)
        )
        routeState = fetched
        onRouteComputed?.invoke(fetched)
    }

    // Dynamic OSRM deviation rerouting when live GPS deviates > 75m from active polyline
    LaunchedEffect(
        rerouteFromLatLng?.lat,
        rerouteFromLatLng?.lng,
        liveTripLocation?.lat,
        liveTripLocation?.lng,
        destLat,
        destLng,
        lowNetworkMode
    ) {
        if (lowNetworkMode || !GpsBroadcastThrottle.isValidCoordinate(destLat, destLng)) return@LaunchedEffect
        val candidate = rerouteFromLatLng ?: liveTripLocation?.let { GeoPoint(it.lat, it.lng) } ?: return@LaunchedEffect
        val activePoints = when (val st = routeState) {
            is RouteUiState.Ready -> st.points
            is RouteUiState.Fallback -> st.points
            else -> emptyList()
        }
        val nowMs = System.currentTimeMillis()
        if (RouteNavigationMath.shouldRecalculateRoute(
                currentLat = candidate.lat,
                currentLng = candidate.lng,
                activeRoute = activePoints,
                lastRerouteEpochMs = lastRerouteEpochMs,
                nowEpochMs = nowMs
            )
        ) {
            lastRerouteEpochMs = nowMs
            val rerouted = OsrmRouteService.fetchDrivingRoute(
                origin = candidate,
                destination = GeoPoint(destLat, destLng),
                bypassCache = true
            )
            routeState = rerouted
            onRouteComputed?.invoke(rerouted)
        }
    }

    val useLiteView = lowNetworkMode || isRobolectricEnvironment()
    val nativeReady = remember(useLiteView) {
        if (useLiteView) {
            false
        } else {
            runCatching {
                MapLibre.getInstance(context.applicationContext)
                true
            }.getOrDefault(false)
        }
    }

    if (!nativeReady) {
        Box(modifier = if (fillContainer) modifier.fillMaxSize() else modifier) {
            InteractiveBengalMapCanvas(
                pickupLat = pickupLat,
                pickupLng = pickupLng,
                pickupLabel = pickupLabel,
                destLat = destLat,
                destLng = destLng,
                destLabel = destLabel,
                onlineDrivers = onlineDrivers,
                liveTripLocation = liveTripLocation,
                lowNetworkMode = lowNetworkMode,
                fillContainer = fillContainer,
                cornerRadius = cornerRadius,
                showBorder = showBorder,
                showBottomRoutePill = showTopRouteChip,
                modifier = if (fillContainer) Modifier.fillMaxSize() else Modifier
            )
            if (enableCenterPinSelection) {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = "Selected map pin",
                    tint = Color(0xFF1769E0),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 24.dp)
                        .size(40.dp)
                )
            }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val prefersReducedMotion = rememberPrefersReducedMotion()
    val mapView = remember { MapView(context) }
    var mapController by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var isMapDestroyed by remember { mutableStateOf(false) }
    var autoFollow by remember { mutableStateOf(!enableCenterPinSelection) }
    var lastCameraUpdateMs by remember { mutableLongStateOf(0L) }
    var lastDriverGeo by remember { mutableStateOf<GeoPoint?>(null) }
    var activeMarkerAnimator by remember { mutableStateOf<ValueAnimator?>(null) }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            if (isMapDestroyed) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> runCatching { mapView.onStart() }
                Lifecycle.Event.ON_RESUME -> runCatching { mapView.onResume() }
                Lifecycle.Event.ON_PAUSE -> runCatching { mapView.onPause() }
                Lifecycle.Event.ON_STOP -> runCatching { mapView.onStop() }
                Lifecycle.Event.ON_DESTROY -> {
                    isMapDestroyed = true
                    styleReady = false
                    activeMarkerAnimator?.cancel()
                    activeMarkerAnimator = null
                    mapController = null
                    runCatching { mapView.onDestroy() }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activeMarkerAnimator?.cancel()
            activeMarkerAnimator = null
            styleReady = false
            mapController = null
            if (!isMapDestroyed) {
                isMapDestroyed = true
                runCatching { mapView.onPause() }
                runCatching { mapView.onStop() }
                runCatching { mapView.onDestroy() }
            }
        }
    }

    fun updateStyleSources(style: Style) {
        val routePoints = when (val st = routeState) {
            is RouteUiState.Ready -> st.points
            is RouteUiState.Fallback -> st.points
            else -> listOf(GeoPoint(pickupLat, pickupLng), GeoPoint(destLat, destLng))
        }
        style.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(buildLineGeoJson(routePoints))
        style.getSourceAs<GeoJsonSource>(SRC_PICKUP)?.setGeoJson(buildSinglePointGeoJson(pickupLat, pickupLng))
        style.getSourceAs<GeoJsonSource>(SRC_DROP)?.setGeoJson(buildSinglePointGeoJson(destLat, destLng))
        val fleetPoints = onlineDrivers
            .filter { it.isOnline && GpsBroadcastThrottle.isValidCoordinate(it.currentLat, it.currentLng) }
            .map { GeoPoint(it.currentLat, it.currentLng) }
        style.getSourceAs<GeoJsonSource>(SRC_FLEET)?.setGeoJson(buildMultiPointGeoJson(fleetPoints))
        if (userLat != null && userLng != null && GpsBroadcastThrottle.isValidCoordinate(userLat, userLng)) {
            style.getSourceAs<GeoJsonSource>(SRC_USER_LOC)?.setGeoJson(buildSinglePointGeoJson(userLat, userLng))
        } else {
            style.getSourceAs<GeoJsonSource>(SRC_USER_LOC)?.setGeoJson(buildMultiPointGeoJson(emptyList()))
        }
    }

    fun recenterCamera(map: MapLibreMap, animated: Boolean = true) {
        val points = buildList {
            if (GpsBroadcastThrottle.isValidCoordinate(pickupLat, pickupLng)) add(LatLng(pickupLat, pickupLng))
            if (GpsBroadcastThrottle.isValidCoordinate(destLat, destLng) &&
                (destLat != pickupLat || destLng != pickupLng)
            ) {
                add(LatLng(destLat, destLng))
            }
            liveTripLocation?.let {
                if (GpsBroadcastThrottle.isValidCoordinate(it.lat, it.lng)) add(LatLng(it.lat, it.lng))
            }
            if (isEmpty() && userLat != null && userLng != null && GpsBroadcastThrottle.isValidCoordinate(userLat, userLng)) {
                add(LatLng(userLat, userLng))
            }
        }
        if (points.size >= 2) {
            val bounds = LatLngBounds.Builder().includes(points).build()
            val update = CameraUpdateFactory.newLatLngBounds(bounds, 108)
            if (animated && !prefersReducedMotion) map.animateCamera(update, 650) else map.moveCamera(update)
        } else if (points.size == 1) {
            val update = CameraUpdateFactory.newLatLngZoom(points.first(), 14.5)
            if (animated && !prefersReducedMotion) map.animateCamera(update, 500) else map.moveCamera(update)
        }
        lastCameraUpdateMs = System.currentTimeMillis()
    }

    // Update static endpoints & route GeoJson without re-creating style layers
    LaunchedEffect(styleReady, routeState, pickupLat, pickupLng, destLat, destLng, onlineDrivers, userLat, userLng) {
        if (isMapDestroyed) return@LaunchedEffect
        val map = mapController ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        if (!styleReady || !style.isFullyLoaded) return@LaunchedEffect
        updateStyleSources(style)
    }

    // Recenter camera only when endpoints or computed route change (never hijack pan/zoom when fleet updates)
    LaunchedEffect(styleReady, routeState, pickupLat, pickupLng, destLat, destLng) {
        if (isMapDestroyed) return@LaunchedEffect
        val map = mapController ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        if (!styleReady || !style.isFullyLoaded) return@LaunchedEffect
        if (autoFollow && !enableCenterPinSelection && System.currentTimeMillis() - lastCameraUpdateMs >= 1500L) {
            recenterCamera(map, animated = !prefersReducedMotion)
        }
    }

    // Smooth marker interpolation decoupled from throttled camera follow
    LaunchedEffect(styleReady, liveTripLocation?.lat, liveTripLocation?.lng, prefersReducedMotion) {
        if (isMapDestroyed) return@LaunchedEffect
        val map = mapController ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        val live = liveTripLocation ?: return@LaunchedEffect
        if (!styleReady || !style.isFullyLoaded || !GpsBroadcastThrottle.isValidCoordinate(live.lat, live.lng)) {
            return@LaunchedEffect
        }
        val target = GeoPoint(live.lat, live.lng)
        val start = lastDriverGeo
        lastDriverGeo = target
        val driverSrc = style.getSourceAs<GeoJsonSource>(SRC_DRIVER) ?: return@LaunchedEffect

        if (start == null || prefersReducedMotion) {
            activeMarkerAnimator?.cancel()
            driverSrc.setGeoJson(buildSinglePointGeoJson(target.lat, target.lng))
        } else {
            activeMarkerAnimator?.cancel()
            activeMarkerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900L
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    if (isMapDestroyed || !style.isFullyLoaded) return@addUpdateListener
                    val fraction = anim.animatedValue as Float
                    val lat = start.lat + (target.lat - start.lat) * fraction
                    val lng = start.lng + (target.lng - start.lng) * fraction
                    driverSrc.setGeoJson(buildSinglePointGeoJson(lat, lng))
                }
                start()
            }
        }

        val now = System.currentTimeMillis()
        if (autoFollow && !enableCenterPinSelection && now - lastCameraUpdateMs >= 1500L) {
            val camUpdate = CameraUpdateFactory.newLatLng(LatLng(target.lat, target.lng))
            if (prefersReducedMotion) map.moveCamera(camUpdate) else map.animateCamera(camUpdate, 800)
            lastCameraUpdateMs = now
        }
    }

    val shape = RoundedCornerShape(cornerRadius)
    val containerMod = (if (fillContainer) modifier.fillMaxSize() else modifier.fillMaxWidth().height(mapHeight))
        .clip(shape)
        .let { if (showBorder) it.border(1.dp, MaterialTheme.colorScheme.outline, shape) else it }

    Box(modifier = containerMod) {
        AndroidView(
            factory = {
                mapView.apply {
                    onCreate(null)
                    getMapAsync { map ->
                        mapController = map
                        map.uiSettings.isRotateGesturesEnabled = false
                        map.uiSettings.isCompassEnabled = false
                        map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                            override fun onMoveBegin(detector: MoveGestureDetector) {
                                autoFollow = false
                            }

                            override fun onMove(detector: MoveGestureDetector) = Unit
                            override fun onMoveEnd(detector: MoveGestureDetector) = Unit
                        })
                        map.addOnCameraIdleListener {
                            if (latestPinMode) {
                                val target = map.cameraPosition.target
                                if (target != null && GpsBroadcastThrottle.isValidCoordinate(target.latitude, target.longitude)) {
                                    latestCenterCallback?.invoke(target.latitude, target.longitude)
                                }
                            }
                        }
                        map.setStyle(Style.Builder().fromJson(MapProviderConfig.buildOsmStyleJson())) { style ->
                            if (isMapDestroyed) return@setStyle
                            style.addSource(GeoJsonSource(SRC_ROUTE, buildLineGeoJson(emptyList())))
                            style.addSource(GeoJsonSource(SRC_PICKUP, buildSinglePointGeoJson(pickupLat, pickupLng)))
                            style.addSource(GeoJsonSource(SRC_DROP, buildSinglePointGeoJson(destLat, destLng)))
                            style.addSource(GeoJsonSource(SRC_FLEET, buildMultiPointGeoJson(emptyList())))
                            style.addSource(GeoJsonSource(SRC_DRIVER, buildMultiPointGeoJson(emptyList())))
                            style.addSource(GeoJsonSource(SRC_USER_LOC, buildMultiPointGeoJson(emptyList())))

                            style.addLayer(
                                LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
                                    PropertyFactory.lineColor("#1769E0"),
                                    PropertyFactory.lineWidth(4.5f),
                                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                                )
                            )
                            style.addLayer(
                                CircleLayer(LYR_USER_LOC, SRC_USER_LOC).withProperties(
                                    PropertyFactory.circleColor("#1769E0"),
                                    PropertyFactory.circleRadius(7f),
                                    PropertyFactory.circleStrokeWidth(3f),
                                    PropertyFactory.circleStrokeColor("#FFFFFF")
                                )
                            )
                            style.addLayer(
                                CircleLayer(LYR_FLEET, SRC_FLEET).withProperties(
                                    PropertyFactory.circleColor("#4D91F5"),
                                    PropertyFactory.circleRadius(6f),
                                    PropertyFactory.circleStrokeWidth(2f),
                                    PropertyFactory.circleStrokeColor("#FFFFFF")
                                )
                            )
                            style.addLayer(
                                CircleLayer(LYR_PICKUP, SRC_PICKUP).withProperties(
                                    PropertyFactory.circleColor("#159B68"),
                                    PropertyFactory.circleRadius(8f),
                                    PropertyFactory.circleStrokeWidth(2.5f),
                                    PropertyFactory.circleStrokeColor("#FFFFFF")
                                )
                            )
                            style.addLayer(
                                CircleLayer(LYR_DROP, SRC_DROP).withProperties(
                                    PropertyFactory.circleColor("#0D234A"),
                                    PropertyFactory.circleRadius(8f),
                                    PropertyFactory.circleStrokeWidth(2.5f),
                                    PropertyFactory.circleStrokeColor("#FFFFFF")
                                )
                            )
                            style.addLayer(
                                CircleLayer(LYR_DRIVER, SRC_DRIVER).withProperties(
                                    PropertyFactory.circleColor("#0D234A"),
                                    PropertyFactory.circleRadius(9.5f),
                                    PropertyFactory.circleStrokeWidth(3f),
                                    PropertyFactory.circleStrokeColor("#159B68")
                                )
                            )
                            styleReady = true
                            updateStyleSources(style)
                            recenterCamera(map, animated = false)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (enableCenterPinSelection) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = "Selected map pin",
                tint = Color(0xFF1769E0),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 26.dp)
                    .size(42.dp)
            )
        }

        if (showTopRouteChip) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val statusLabel = when (val st = routeState) {
                    is RouteUiState.Loading -> "Calculating road route..."
                    is RouteUiState.Ready -> "${st.distanceKm} km · ${st.durationMin} min"
                    is RouteUiState.Fallback -> "${String.format(java.util.Locale.US, "%.1f", st.distanceKm)} km (direct)"
                    RouteUiState.Idle -> "OpenStreetMap Live"
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(999.dp),
                    shadowElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Route,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                if (liveTripLocation != null && (liveTripLocation.speedKmph > 0.5 || liveTripLocation.headingDeg > 0.0)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(999.dp),
                        shadowElevation = 3.dp,
                        modifier = Modifier.testTag("map_live_heading_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Navigation,
                                contentDescription = "Vehicle heading ${liveTripLocation.headingDeg.toInt()} degrees",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(14.dp)
                                    .rotate(liveTripLocation.headingDeg.toFloat())
                            )
                            Text(
                                text = "${liveTripLocation.speedKmph.toInt()} km/h",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        if (showRecenterFab) {
            SmallFloatingActionButton(
                onClick = {
                    autoFollow = true
                    mapController?.let { recenterCamera(it, animated = true) }
                },
                containerColor = if (autoFollow) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
                contentColor = if (autoFollow) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = recenterBottomPadding)
                    .testTag("map_recenter_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.MyLocation,
                    contentDescription = "Recenter map",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun buildSinglePointGeoJson(lat: Double, lng: Double): String {
    if (!GpsBroadcastThrottle.isValidCoordinate(lat, lng)) return buildMultiPointGeoJson(emptyList())
    return buildMultiPointGeoJson(listOf(GeoPoint(lat, lng)))
}

private fun buildMultiPointGeoJson(points: List<GeoPoint>): String {
    val features = JSONArray()
    points.forEach { pt ->
        features.put(
            JSONObject().apply {
                put("type", "Feature")
                put(
                    "geometry",
                    JSONObject().apply {
                        put("type", "Point")
                        put("coordinates", JSONArray().put(pt.lng).put(pt.lat))
                    }
                )
            }
        )
    }
    return JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", features)
    }.toString()
}

private fun buildLineGeoJson(points: List<GeoPoint>): String {
    val features = JSONArray()
    if (points.size >= 2) {
        val coords = JSONArray()
        points.forEach { pt -> coords.put(JSONArray().put(pt.lng).put(pt.lat)) }
        features.put(
            JSONObject().apply {
                put("type", "Feature")
                put(
                    "geometry",
                    JSONObject().apply {
                        put("type", "LineString")
                        put("coordinates", coords)
                    }
                )
            }
        )
    }
    return JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", features)
    }.toString()
}
