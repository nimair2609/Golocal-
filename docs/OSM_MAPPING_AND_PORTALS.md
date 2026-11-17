# GoLocal OpenStreetMap, MapLibre, OSRM & Multi-Portal Architecture

## 1. Overview
GoLocal uses an OpenStreetMap-based mapping stack across all four surfaces without any Google Maps billing SDKs or paid API keys:

| Surface | Technology | Entry Point |
|---|---|---|
| **Customer Android App** | MapLibre Native OpenGL (`org.maplibre.gl:android-sdk-opengl:13.7.1`) | `com.example.MainActivity` -> `CustomerAppRoot` |
| **Partner / Rider Android App** | MapLibre Native OpenGL + foreground `FusedLocationProviderClient` | `com.example.rider.RiderActivity` -> `RiderAppRoot` |
| **Owner Admin (Android + Web)** | MapLibre Native + MapLibre GL JS `4.7.1` | `com.example.admin.AdminActivity` & `/public/owner.html` |
| **Operations Dashboard (Android + Web)** | MapLibre Native + MapLibre GL JS `4.7.1` | `PortalRole.OPERATIONS_STAFF` & `/public/operations.html` |

---

## 2. Borai Launch Configuration (Starting Point, Not a Restriction)
- Default map starting point: **Borai, PIN 721457, Mohanpur, Paschim Medinipur, West Bengal, India** (`21.8160, 87.3703`, zoom `13.2`).
- Defined in `BoraiLaunchConfig.kt` (Android) and `BORAI_LAUNCH` in `/public/js/map-adapters.js` (Web).
- **Non-restrictive design**: When no `service_areas` boundaries are configured or enabled, rides across all West Bengal stands remain permitted (`BoraiLaunchConfig.evaluateTripCoverage`).
- Owner Admins can dynamically create, edit, enable/disable, or delete radius or polygon `service_areas`, configure village coverage lists, toggle **Pickup** vs **Drop-off** coverage, and set **Supported Vehicle Categories** (`toto`, `auto`, `bike`, `cab`).

---

## 3. Configurable Map Providers & Rate-Limit / Cost Safety
All endpoints are isolated behind configurable adapters—zero hardcoded provider assumptions in UI composables:

- **Android (`app/src/main/res/values/strings.xml` + `MapProviderConfig.kt`)**:
  - `osm_tile_url_template`: Default `https://tile.openstreetmap.org/{z}/{x}/{y}.png`
  - `osrm_base_url`: Default `https://router.project-osrm.org`
  - `geocoding_base_url`: Default `https://nominatim.openstreetmap.org`
- **Web Portals (`/public/js/map-adapters.js`)**:
  - Reads optional runtime overrides from `window.GOLOCAL_MAP_CONFIG` (`osmTileTemplate`, `osrmBaseUrl`, `geocodingBaseUrl`, `attribution`).

### Built-in Usage Policy & Quota Safeguards
1. **No Nominatim Autocomplete Traffic**: Instant typing in destination/place search ONLY queries the offline `WestBengalCatalog` (including Borai / Mohanpur villages) and Owner-configured Firestore `service_areas`. Remote Nominatim calls happen only when the user explicitly submits a search query (`>= 3` characters), throttled to `>= 1100 ms` with a `15-minute` in-memory LRU cache and a descriptive `User-Agent`.
2. **OSRM Demo Throttling & Fallback**: `OsrmRoutingAdapter` caches routes for `10 minutes` (50-entry LRU), throttles live calls to `>= 800 ms`, and automatically falls back to a Haversine road-factor (`1.24x`) estimate on timeouts, HTTP errors, or offline conditions.
3. **Low-Network Lite Mode & Attribution**: `GoLocalOsmMapCard` supports `lowNetworkMode` (renders GeoJSON zones, routes, and pins without downloading raster tiles on slow rural connections) and always renders `© OpenStreetMap contributors`.
4. **Swapping to Self-Hosted / Commercial Providers**: Replace the three URL strings in `strings.xml` (and optionally `window.GOLOCAL_MAP_CONFIG` in Web hosting) to point at your own OpenMapTiles / TileServer GL / self-hosted OSRM / Pelias / Photon cluster with zero Kotlin UI refactoring.

---

## 4. Firebase Firestore & Realtime Database Security Model
- **Authentication**: **Phone OTP only** across Customer, Partner, and Web/Android Admin & Operations portals. Google Sign-In has been completely removed.
- **Role Separation (Server-Verified)**:
  - **Owner Admin** (`admins/{uid}`): Full governance, fare configuration, KYC approval, settlements, service-area boundary management, and Operations Staff provisioning.
  - **Operations Staff** (`operations_staff/{uid}` with `isActive: true` + RTDB `auth_roles/ops/{uid}`): Scoped access to live map monitoring, pickup delay alerts, partner assignment on `searching` bookings, complaint investigation (`investigating`), dispute escalation (`under_review`), and SOS visibility. Blocked by `firestore.rules` from modifying fares, settlements, service boundaries, or admin allowlists.
- **Live Telemetry (Firebase Realtime Database — `database.rules.json`)**:
  - `live_locations/drivers/{driverId}`: Driver-owned writes validated on coordinates and `trackingStatus` (`active`, `paused_gps_lost`, `stopped`); readable only by the driver, Owner Admin, or active Operations Staff.
  - `live_locations/trips/{bookingId}`: Written only by the assigned driver; readable strictly by the assigned booking's `customerId`, `driverId`, Owner Admin, or active Operations Staff.
  - **Play Policy Location Compliance**: Uses foreground-only permissions (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) during active duty or assigned trips; stops sharing automatically upon trip completion/cancellation, offline toggle, or sign-out (`DriverViewModel.stopAllTrackingForSignOut`). No `ACCESS_BACKGROUND_LOCATION`.

---

## 5. External Public Service Limitations
- `https://tile.openstreetmap.org/{z}/{x}/{y}.png` is a volunteer-operated public tile server subject to OSM Tile Usage Policy (heavy commercial scale requires self-hosted vector/raster tiles or an approved tile CDN).
- `https://router.project-osrm.org` and `https://nominatim.openstreetmap.org` are demo/community servers with no production SLA; deploy self-hosted OSRM (`car.lua` tuned for Paschim Medinipur roads) and Photon/Pelias before high-concurrency commercial launch.
