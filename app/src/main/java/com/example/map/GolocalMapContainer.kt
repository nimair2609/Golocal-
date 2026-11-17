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
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.DriverDirectoryEntry
import com.example.data.model.TripLocation
import com.example.ui.components.InteractiveBengalMapCanvas
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

private const val OSM_STYLE_JSON = """
{
  "version": 8,
  "name": "GoLocal OSM",
  "sources": {
    "osm-raster": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [
    {
      "id": "osm-base",
      "type": "raster",
      "source": "osm-raster",
      "minzoom": 0,
      "maxzoom": 19
    }
  ]
}
"""

private const val SRC_ROUTE = "golocal-route-src"
private const val SRC_PICKUP = "golocal-pickup-src"
private const val SRC_DROP = "golocal-drop-src"
private const val SRC_FLEET = "golocal-fleet-src"
private const val SRC_DRIVER = "golocal-driver-src"

private const val LYR_ROUTE = "golocal-route-lyr"
private const val LYR_PICKUP = "golocal-pickup-lyr"
private const val LYR_DROP = "golocal-drop-lyr"
private const val LYR_FLEET = "golocal-fleet-lyr"
private const val LYR_DRIVER = "golocal-driver-lyr"

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
    onRouteComputed: ((RouteUiState) -> Unit)? = null
) {
    val context = LocalContext.current
    var routeState by remember { mutableStateOf<RouteUiState>(RouteUiState.Idle) }

    // Debounced OSRM driving route fetch with cancellation when endpoints change
    LaunchedEffect(pickupLat, pickupLng, destLat, destLng, lowNetworkMode) {
        if (lowNetworkMode) {
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
            modifier = modifier
        )
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    var mapController by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var autoFollow by remember { mutableStateOf(true) }
    var lastCameraUpdateMs by remember { mutableLongStateOf(0L) }
    var lastDriverGeo by remember { mutableStateOf<GeoPoint?>(null) }
    var activeMarkerAnimator by remember { mutableStateOf<ValueAnimator?>(null) }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    activeMarkerAnimator?.cancel()
                    mapView.onDestroy()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            activeMarkerAnimator?.cancel()
            lifecycleOwner.lifecycle.removeObserver(observer)
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
    }

    fun recenterCamera(map: MapLibreMap, animated: Boolean = true) {
        val points = buildList {
            if (GpsBroadcastThrottle.isValidCoordinate(pickupLat, pickupLng)) add(LatLng(pickupLat, pickupLng))
            if (GpsBroadcastThrottle.isValidCoordinate(destLat, destLng)) add(LatLng(destLat, destLng))
            liveTripLocation?.let {
                if (GpsBroadcastThrottle.isValidCoordinate(it.lat, it.lng)) add(LatLng(it.lat, it.lng))
            }
        }
        if (points.size >= 2) {
            val bounds = LatLngBounds.Builder().includes(points).build()
            val update = CameraUpdateFactory.newLatLngBounds(bounds, 96)
            if (animated) map.animateCamera(update, 650) else map.moveCamera(update)
        } else if (points.size == 1) {
            val update = CameraUpdateFactory.newLatLngZoom(points.first(), 13.5)
            if (animated) map.animateCamera(update, 500) else map.moveCamera(update)
        }
        lastCameraUpdateMs = System.currentTimeMillis()
    }

    // Update static endpoints & route GeoJson without re-creating style layers
    LaunchedEffect(styleReady, routeState, pickupLat, pickupLng, destLat, destLng, onlineDrivers) {
        val map = mapController ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        if (!styleReady || !style.isFullyLoaded) return@LaunchedEffect
        updateStyleSources(style)
        if (autoFollow && System.currentTimeMillis() - lastCameraUpdateMs >= 1500L) {
            recenterCamera(map, animated = true)
        }
    }

    // Smooth marker interpolation decoupled from throttled camera follow
    LaunchedEffect(styleReady, liveTripLocation?.lat, liveTripLocation?.lng) {
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

        if (start == null) {
            driverSrc.setGeoJson(buildSinglePointGeoJson(target.lat, target.lng))
        } else {
            activeMarkerAnimator?.cancel()
            activeMarkerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900L
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val fraction = anim.animatedValue as Float
                    val lat = start.lat + (target.lat - start.lat) * fraction
                    val lng = start.lng + (target.lng - start.lng) * fraction
                    driverSrc.setGeoJson(buildSinglePointGeoJson(lat, lng))
                }
                start()
            }
        }

        val now = System.currentTimeMillis()
        if (autoFollow && now - lastCameraUpdateMs >= 1500L) {
            map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(target.lat, target.lng)), 800)
            lastCameraUpdateMs = now
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(mapHeight)
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
    ) {
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
                        map.setStyle(Style.Builder().fromJson(OSM_STYLE_JSON)) { style ->
                            style.addSource(GeoJsonSource(SRC_ROUTE, buildLineGeoJson(emptyList())))
                            style.addSource(GeoJsonSource(SRC_PICKUP, buildSinglePointGeoJson(pickupLat, pickupLng)))
                            style.addSource(GeoJsonSource(SRC_DROP, buildSinglePointGeoJson(destLat, destLng)))
                            style.addSource(GeoJsonSource(SRC_FLEET, buildMultiPointGeoJson(emptyList())))
                            style.addSource(GeoJsonSource(SRC_DRIVER, buildMultiPointGeoJson(emptyList())))

                            style.addLayer(
                                LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
                                    PropertyFactory.lineColor("#1769E0"),
                                    PropertyFactory.lineWidth(4.5f),
                                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
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
                                    PropertyFactory.circleColor("#1769E0"),
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

        // Compact top status & route chip
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
        }

        // Recenter FAB (always min 48dp touch target)
        SmallFloatingActionButton(
            onClick = {
                autoFollow = true
                mapController?.let { recenterCamera(it, animated = true) }
            },
            containerColor = if (autoFollow) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
            contentColor = if (autoFollow) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
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
