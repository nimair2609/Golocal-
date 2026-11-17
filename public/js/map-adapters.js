// GoLocal OpenStreetMap, MapLibre GL JS, OSRM & Geocoding Adapters (Web Portals)
// Provider & Cost Safety:
// - Tile URL template, OSRM base URL, and Geocoding URL are configurable via window.GOLOCAL_MAP_CONFIG.
// - Never sends keystroke autocomplete traffic to public Nominatim.
// - Caches OSRM routes (10 min) and geocoding searches (15 min) with throttling.
// - Falls back gracefully to Haversine road-factor estimation on routing failures.

export const BORAI_LAUNCH = Object.freeze({
  lat: 21.8160,
  lng: 87.3703,
  zoom: 13.2,
  pincode: "721457",
  locality: "Borai",
  block: "Mohanpur",
  district: "Paschim Medinipur",
  label: "Borai Market & Stand (PIN 721457)",
  defaultVillages: [
    "Borai",
    "Mohanpur",
    "Gholai",
    "Paikbar",
    "Agarara",
    "Jaysankhapur",
    "Nilda",
    "Shialsai",
    "Kumarda",
    "Tanua"
  ],
  defaultPolygon: [
    { lat: 21.8720, lng: 87.3100 },
    { lat: 21.8820, lng: 87.4450 },
    { lat: 21.7550, lng: 87.4500 },
    { lat: 21.7480, lng: 87.3050 }
  ]
});

export function getMapProviderConfig() {
  const override = (typeof window !== "undefined" && window.GOLOCAL_MAP_CONFIG) || {};
  return {
    osmTileTemplate: override.osmTileTemplate || "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    osrmBaseUrl: String(override.osrmBaseUrl || "https://router.project-osrm.org").replace(/\/+$/, ""),
    geocodingBaseUrl: String(override.geocodingBaseUrl || "https://nominatim.openstreetmap.org").replace(/\/+$/, ""),
    attribution: override.attribution || "© OpenStreetMap contributors"
  };
}

export function haversineKm(lat1, lng1, lat2, lng2) {
  const toRad = (deg) => (deg * Math.PI) / 180;
  const r = 6371;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

export function createCirclePolygonCoords(centerLat, centerLng, radiusKm, steps = 40) {
  const ring = [];
  const latRad = (centerLat * Math.PI) / 180;
  const dLat = (radiusKm / 111.32);
  const dLng = radiusKm / (111.32 * Math.max(0.2, Math.cos(latRad)));
  for (let i = 0; i <= steps; i++) {
    const theta = (2 * Math.PI * i) / steps;
    ring.push([
      centerLng + dLng * Math.cos(theta),
      centerLat + dLat * Math.sin(theta)
    ]);
  }
  return ring;
}

export function buildZonesFeatureCollection(zones) {
  const features = [];
  for (const z of zones || []) {
    let ring = null;
    if (Array.isArray(z.polygonPoints) && z.polygonPoints.length >= 3) {
      ring = z.polygonPoints.map((pt) => [Number(pt.lng), Number(pt.lat)]);
      ring.push(ring[0]);
    } else {
      const cLat = Number(z.centerLat || z.lat || 0);
      const cLng = Number(z.centerLng || z.lng || 0);
      const rad = Number(z.radiusKm || 6);
      if (Math.abs(cLat) > 0.001 && Math.abs(cLng) > 0.001) {
        ring = createCirclePolygonCoords(cLat, cLng, Math.max(0.3, rad));
      }
    }
    if (!ring) continue;
    features.push({
      type: "Feature",
      properties: {
        id: z.id || "",
        name: z.name || "Zone",
        isActive: z.isActive !== false ? 1 : 0,
        pickupEnabled: z.pickupEnabled !== false ? 1 : 0,
        dropoffEnabled: z.dropoffEnabled !== false ? 1 : 0
      },
      geometry: {
        type: "Polygon",
        coordinates: [ring]
      }
    });
  }
  return { type: "FeatureCollection", features };
}

export function buildMarkersFeatureCollection(markers) {
  return {
    type: "FeatureCollection",
    features: (markers || [])
      .filter((m) => Number.isFinite(m.lat) && Number.isFinite(m.lng) && (Math.abs(m.lat) > 0.001 || Math.abs(m.lng) > 0.001))
      .map((m) => ({
        type: "Feature",
        properties: {
          id: m.id || "",
          title: m.title || "",
          subtitle: m.subtitle || "",
          color: m.color || "#1565C0",
          radius: Number(m.radius || 7)
        },
        geometry: {
          type: "Point",
          coordinates: [Number(m.lng), Number(m.lat)]
        }
      }))
  };
}

export function buildRoutesFeatureCollection(routes) {
  return {
    type: "FeatureCollection",
    features: (routes || [])
      .filter((r) => Array.isArray(r.coords) && r.coords.length >= 2)
      .map((r) => ({
        type: "Feature",
        properties: {
          id: r.id || "",
          color: r.color || "#1565C0"
        },
        geometry: {
          type: "LineString",
          coordinates: r.coords
        }
      }))
  };
}

/**
 * Initializes a MapLibre GL JS map backed by configurable OpenStreetMap raster tiles.
 */
export function createOsmMap(containerId, options = {}) {
  const cfg = getMapProviderConfig();
  const center = options.center || [BORAI_LAUNCH.lng, BORAI_LAUNCH.lat];
  const zoom = options.zoom ?? BORAI_LAUNCH.zoom;

  const map = new window.maplibregl.Map({
    container: containerId,
    center,
    zoom,
    attributionControl: true,
    style: {
      version: 8,
      name: "GoLocal OpenStreetMap",
      sources: {
        "osm-tiles": {
          type: "raster",
          tiles: [cfg.osmTileTemplate],
          tileSize: 256,
          attribution: cfg.attribution,
          maxzoom: 19
        },
        "golocal-zones": {
          type: "geojson",
          data: buildZonesFeatureCollection([])
        },
        "golocal-routes": {
          type: "geojson",
          data: buildRoutesFeatureCollection([])
        },
        "golocal-markers": {
          type: "geojson",
          data: buildMarkersFeatureCollection([])
        }
      },
      layers: [
        {
          id: "osm-raster",
          type: "raster",
          source: "osm-tiles",
          minzoom: 0,
          maxzoom: 19
        },
        {
          id: "zones-fill",
          type: "fill",
          source: "golocal-zones",
          paint: {
            "fill-color": ["case", ["==", ["get", "isActive"], 1], "#1565C0", "#94A3B8"],
            "fill-opacity": 0.14
          }
        },
        {
          id: "zones-outline",
          type: "line",
          source: "golocal-zones",
          paint: {
            "line-color": ["case", ["==", ["get", "isActive"], 1], "#0D47A1", "#64748B"],
            "line-width": 2
          }
        },
        {
          id: "routes-line",
          type: "line",
          source: "golocal-routes",
          layout: {
            "line-join": "round",
            "line-cap": "round"
          },
          paint: {
            "line-color": ["get", "color"],
            "line-width": 3.5,
            "line-opacity": 0.85
          }
        },
        {
          id: "markers-circle",
          type: "circle",
          source: "golocal-markers",
          paint: {
            "circle-radius": ["get", "radius"],
            "circle-color": ["get", "color"],
            "circle-stroke-width": 2,
            "circle-stroke-color": "#FFFFFF"
          }
        }
      ]
    }
  });

  map.addControl(new window.maplibregl.NavigationControl({ showCompass: false }), "top-right");

  const popup = new window.maplibregl.Popup({ closeButton: true, closeOnClick: true });
  map.on("click", "markers-circle", (e) => {
    const feat = e.features?.[0];
    if (!feat) return;
    const coords = feat.geometry.coordinates.slice();
    const { title, subtitle } = feat.properties || {};
    const html = `<strong>${String(title || "")}</strong>${subtitle ? `<div style="font-size:12px;color:#475569;margin-top:3px;">${String(subtitle)}</div>` : ""}`;
    popup.setLngLat(coords).setHTML(html).addTo(map);
  });
  map.on("mouseenter", "markers-circle", () => {
    map.getCanvas().style.cursor = "pointer";
  });
  map.on("mouseleave", "markers-circle", () => {
    map.getCanvas().style.cursor = "";
  });

  if (typeof options.onMapClick === "function") {
    map.on("click", (e) => {
      options.onMapClick(e.lngLat.lat, e.lngLat.lng);
    });
  }

  return {
    map,
    updateZones(zones) {
      const src = map.getSource("golocal-zones");
      if (src) src.setData(buildZonesFeatureCollection(zones));
    },
    updateRoutes(routes) {
      const src = map.getSource("golocal-routes");
      if (src) src.setData(buildRoutesFeatureCollection(routes));
    },
    updateMarkers(markers) {
      const src = map.getSource("golocal-markers");
      if (src) src.setData(buildMarkersFeatureCollection(markers));
    },
    flyTo(lat, lng, zoomLevel = 14) {
      map.flyTo({ center: [lng, lat], zoom: zoomLevel, speed: 1.4 });
    }
  };
}

// --- Cached + Throttled OSRM & Geocoding Adapters ---
const routeCache = new Map();
let lastOsrmFetchMs = 0;

export async function fetchOsrmRoute(startLat, startLng, endLat, endLng) {
  const key = `${startLat.toFixed(4)},${startLng.toFixed(4)}->${endLat.toFixed(4)},${endLng.toFixed(4)}`;
  const now = Date.now();
  const cached = routeCache.get(key);
  if (cached && now - cached.ts < 10 * 60 * 1000) {
    return cached.value;
  }

  const straightKm = haversineKm(startLat, startLng, endLat, endLng);
  if (now - lastOsrmFetchMs >= 800 && straightKm >= 0.05) {
    lastOsrmFetchMs = now;
    try {
      const cfg = getMapProviderConfig();
      const url = `${cfg.osrmBaseUrl}/route/v1/driving/${startLng},${startLat};${endLng},${endLat}?overview=full&geometries=geojson`;
      const res = await fetch(url);
      if (res.ok) {
        const json = await res.json();
        const route = json.routes?.[0];
        if (route?.geometry?.coordinates?.length >= 2) {
          const val = {
            coords: route.geometry.coordinates,
            distanceKm: Math.round((route.distance / 1000) * 10) / 10,
            durationMin: Math.max(1, Math.round(route.duration / 60)),
            provider: "osrm"
          };
          routeCache.set(key, { ts: now, value: val });
          return val;
        }
      }
    } catch (_) {
      // Fall back cleanly below.
    }
  }

  const roadKm = Math.round(straightKm * 1.24 * 10) / 10;
  return {
    coords: [
      [startLng, startLat],
      [endLng, endLat]
    ],
    distanceKm: roadKm,
    durationMin: Math.max(2, Math.round((roadKm / 26) * 60)),
    provider: "haversine_fallback"
  };
}

const geoCache = new Map();
let lastGeoFetchMs = 0;

/**
 * Explicit-submit geocoding search (never invoked per keystroke).
 */
export async function searchOsmPlaces(query) {
  const q = String(query || "").trim();
  if (q.length < 3) return [];
  const key = q.toLowerCase();
  const now = Date.now();
  const cached = geoCache.get(key);
  if (cached && now - cached.ts < 15 * 60 * 1000) {
    return cached.results;
  }
  if (now - lastGeoFetchMs < 1100) {
    return cached?.results || [];
  }
  lastGeoFetchMs = now;
  const cfg = getMapProviderConfig();
  const url = `${cfg.geocodingBaseUrl}/search?q=${encodeURIComponent(q + ", West Bengal, India")}&format=jsonv2&addressdetails=1&limit=6&countrycodes=in`;
  const res = await fetch(url);
  if (!res.ok) return [];
  const rows = await res.json();
  const results = (rows || []).map((r) => ({
    displayName: r.name || String(r.display_name || "").split(",")[0],
    fullAddress: r.display_name || "",
    lat: Number(r.lat),
    lng: Number(r.lon)
  })).filter((r) => Number.isFinite(r.lat) && Number.isFinite(r.lng));
  geoCache.set(key, { ts: now, results });
  return results;
}
