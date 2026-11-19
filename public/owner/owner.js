import {
  collection,
  doc,
  onSnapshot,
  setDoc,
  addDoc,
  updateDoc,
  deleteDoc,
  serverTimestamp,
} from "https://www.gstatic.com/firebasejs/11.0.2/firebase-firestore.js";
import { ref as rtdbRef, onValue } from "https://www.gstatic.com/firebasejs/11.0.2/firebase-database.js";
import {
  BORAI_DEFAULT_CENTER,
  auth,
  db,
  rtdb,
  getMapSettings,
  buildOsmRasterStyle,
  serviceAreasToGeoJson,
  fetchOsrmRoute,
  searchNominatim,
  bindPhoneOtpLogin,
} from "../shared/golocal-firebase.js";

const el = (id) => document.getElementById(id);
const esc = (v) =>
  String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

let map = null;
let centerMarker = null;
let driverMarkers = [];
let unsubZones = null;
let unsubFares = null;
let unsubDrivers = null;
let unsubBookings = null;
let unsubOps = null;
let unsubRtdbDrivers = null;

let polyMode = false;
let polyVertices = [];
let cachedZones = [];

// Tabs
document.querySelectorAll(".tab-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".tab-btn").forEach((b) => b.classList.remove("active"));
    document.querySelectorAll(".tab-panel").forEach((p) => p.classList.add("hidden"));
    btn.classList.add("active");
    el(`tab-${btn.dataset.tab}`).classList.remove("hidden");
    if (btn.dataset.tab === "zones" && map) map.resize();
  });
});

function initEndpointConfig() {
  const cfg = getMapSettings();
  el("cfgTileUrl").value = cfg.osmTileTemplate;
  el("cfgOsrmUrl").value = cfg.osrmBaseUrl;
  el("cfgNominatimUrl").value = cfg.nominatimBaseUrl;
}

el("saveEndpointCfgBtn").addEventListener("click", () => {
  localStorage.setItem("golocal_osm_tile", el("cfgTileUrl").value.trim());
  localStorage.setItem("golocal_osrm_url", el("cfgOsrmUrl").value.trim());
  localStorage.setItem("golocal_nominatim_url", el("cfgNominatimUrl").value.trim());
  location.reload();
});
el("resetEndpointCfgBtn").addEventListener("click", () => {
  localStorage.removeItem("golocal_osm_tile");
  localStorage.removeItem("golocal_osrm_url");
  localStorage.removeItem("golocal_nominatim_url");
  location.reload();
});

function ensureMap() {
  if (map) return;
  map = new maplibregl.Map({
    container: "ownerMap",
    style: buildOsmRasterStyle(),
    center: [BORAI_DEFAULT_CENTER.lng, BORAI_DEFAULT_CENTER.lat],
    zoom: BORAI_DEFAULT_CENTER.zoom,
  });
  map.addControl(new maplibregl.NavigationControl(), "top-right");

  centerMarker = new maplibregl.Marker({ color: "#10b981", draggable: true })
    .setLngLat([BORAI_DEFAULT_CENTER.lng, BORAI_DEFAULT_CENTER.lat])
    .addTo(map);
  centerMarker.on("dragend", () => {
    const pos = centerMarker.getLngLat();
    el("zoneLat").value = pos.lat.toFixed(5);
    el("zoneLng").value = pos.lng.toFixed(5);
  });

  map.on("load", () => {
    map.addSource("service-zones", { type: "geojson", data: { type: "FeatureCollection", features: [] } });
    map.addLayer({
      id: "service-zones-fill",
      type: "fill",
      source: "service-zones",
      paint: { "fill-color": ["case", ["get", "active"], "#10b981", "#f59e0b"], "fill-opacity": 0.16 },
    });
    map.addLayer({
      id: "service-zones-line",
      type: "line",
      source: "service-zones",
      paint: { "line-color": ["case", ["get", "active"], "#059669", "#d97706"], "line-width": 2 },
    });

    map.addSource("osrm-test-route", { type: "geojson", data: { type: "FeatureCollection", features: [] } });
    map.addLayer({
      id: "osrm-test-line",
      type: "line",
      source: "osrm-test-route",
      paint: { "line-color": "#38bdf8", "line-width": 4 },
    });

    map.addSource("draft-poly", { type: "geojson", data: { type: "FeatureCollection", features: [] } });
    map.addLayer({
      id: "draft-poly-line",
      type: "line",
      source: "draft-poly",
      paint: { "line-color": "#f43f5e", "line-width": 2.5, "line-dasharray": [2, 2] },
    });

    refreshZoneOverlay();
  });

  map.on("click", (e) => {
    const { lng, lat } = e.lngLat;
    if (polyMode) {
      polyVertices.push([Number(lng.toFixed(5)), Number(lat.toFixed(5))]);
      const ring = polyVertices.length >= 3 ? [...polyVertices, polyVertices[0]] : polyVertices;
      el("zonePolygonJson").value = JSON.stringify(ring);
      map.getSource("draft-poly")?.setData({
        type: "Feature",
        geometry: { type: polyVertices.length >= 3 ? "Polygon" : "LineString", coordinates: polyVertices.length >= 3 ? [ring] : ring },
      });
    } else {
      el("zoneLat").value = lat.toFixed(5);
      el("zoneLng").value = lng.toFixed(5);
      centerMarker.setLngLat([lng, lat]);
    }
  });
}

function refreshZoneOverlay() {
  const src = map?.getSource("service-zones");
  if (src) src.setData(serviceAreasToGeoJson(cachedZones));
}

el("togglePolyDrawBtn").addEventListener("click", () => {
  polyMode = !polyMode;
  el("togglePolyDrawBtn").textContent = `Draw Polygon Vertices: ${polyMode ? "ON" : "OFF"}`;
});
el("clearPolyBtn").addEventListener("click", () => {
  polyVertices = [];
  el("zonePolygonJson").value = "";
  map?.getSource("draft-poly")?.setData({ type: "FeatureCollection", features: [] });
});
el("resetBoraiBtn").addEventListener("click", () => {
  el("zoneLat").value = BORAI_DEFAULT_CENTER.lat.toFixed(4);
  el("zoneLng").value = BORAI_DEFAULT_CENTER.lng.toFixed(4);
  centerMarker?.setLngLat([BORAI_DEFAULT_CENTER.lng, BORAI_DEFAULT_CENTER.lat]);
  map?.flyTo({ center: [BORAI_DEFAULT_CENTER.lng, BORAI_DEFAULT_CENTER.lat], zoom: BORAI_DEFAULT_CENTER.zoom });
});

el("zoneGeoSearchBtn").addEventListener("click", async () => {
  const results = await searchNominatim(el("zoneGeoSearchInput").value);
  if (!results.length) {
    el("mapActionHint").textContent = "No Nominatim match found. Try adding district or West Bengal.";
    return;
  }
  const hit = results[0];
  el("zoneLat").value = hit.lat.toFixed(5);
  el("zoneLng").value = hit.lng.toFixed(5);
  el("zoneName").value = hit.label.split(",")[0].trim();
  centerMarker?.setLngLat([hit.lng, hit.lat]);
  map?.flyTo({ center: [hit.lng, hit.lat], zoom: 13 });
  el("mapActionHint").textContent = `Centered on: ${hit.label}`;
});

el("testOsrmBtn").addEventListener("click", async () => {
  const fromLat = Number(el("zoneLat").value) || BORAI_DEFAULT_CENTER.lat;
  const fromLng = Number(el("zoneLng").value) || BORAI_DEFAULT_CENTER.lng;
  const route = await fetchOsrmRoute(fromLat, fromLng, 22.346, 87.232);
  map?.getSource("osrm-test-route")?.setData(route.geojson);
  el("mapActionHint").textContent = `Test Route (${route.source}): ${route.distanceKm} km • ${route.durationMin} min`;
});

function populateZoneForm(z) {
  el("zoneFormTitle").textContent = z ? `Editing: ${z.name}` : "Configure Service Area";
  el("zoneDocId").value = z?.id || "";
  el("zoneName").value = z?.name || "Borai Launch Zone";
  el("zoneNameBn").value = z?.nameBn || "";
  el("zoneDistrict").value = z?.district || "Paschim Medinipur";
  el("zoneBlock").value = z?.block || "Mohanpur";
  el("zonePin").value = z?.pinCode || "721457";
  el("zoneType").value = z?.zoneType || "village";
  el("zoneLat").value = z?.lat ?? BORAI_DEFAULT_CENTER.lat;
  el("zoneLng").value = z?.lng ?? BORAI_DEFAULT_CENTER.lng;
  el("zoneRadiusKm").value = z?.radiusKm ?? 8;
  el("zoneHours").value = z?.operationalHours || "24x7";
  el("zoneVillages").value = z?.villagesCsv || "";
  el("zonePolygonJson").value = z?.boundaryPolygonJson || "";
  el("zonePickupEnabled").checked = z?.pickupEnabled !== false;
  el("zoneDropoffEnabled").checked = z?.dropoffEnabled !== false;
  el("zoneServiceAvailable").checked = z?.serviceAvailable !== false && z?.isActive !== false;
  el("zoneParcelApproved").checked = Boolean(z?.parcelApproved);
  const cats = new Set(z?.supportedVehicleCategories || ["toto", "auto", "bike", "cab"]);
  document.querySelectorAll(".veh-cat").forEach((cb) => { cb.checked = cats.has(cb.value); });
  if (z?.lat && z?.lng) {
    centerMarker?.setLngLat([z.lng, z.lat]);
    map?.flyTo({ center: [z.lng, z.lat], zoom: 12.5 });
  }
}

el("resetZoneFormBtn").addEventListener("click", () => populateZoneForm(null));

el("saveZoneBtn").addEventListener("click", async () => {
  const cats = Array.from(document.querySelectorAll(".veh-cat")).filter((c) => c.checked).map((c) => c.value);
  const available = el("zoneServiceAvailable").checked;
  const docId = el("zoneDocId").value.trim();
  const payload = {
    name: el("zoneName").value.trim(),
    nameBn: el("zoneNameBn").value.trim(),
    district: el("zoneDistrict").value.trim(),
    block: el("zoneBlock").value.trim(),
    pinCode: el("zonePin").value.trim(),
    zoneType: el("zoneType").value,
    lat: Number(el("zoneLat").value) || BORAI_DEFAULT_CENTER.lat,
    lng: Number(el("zoneLng").value) || BORAI_DEFAULT_CENTER.lng,
    radiusKm: Math.min(250, Math.max(0.5, Number(el("zoneRadiusKm").value) || 8)),
    boundaryPolygonJson: el("zonePolygonJson").value.trim(),
    villagesCsv: el("zoneVillages").value.trim(),
    isActive: available,
    pickupEnabled: el("zonePickupEnabled").checked,
    dropoffEnabled: el("zoneDropoffEnabled").checked,
    serviceAvailable: available,
    supportedVehicleCategories: cats.length ? cats : ["toto", "auto", "bike", "cab"],
    parcelApproved: el("zoneParcelApproved").checked,
    operationalHours: el("zoneHours").value.trim() || "24x7",
    updatedAt: serverTimestamp(),
  };
  try {
    if (docId) {
      await updateDoc(doc(db, "service_areas", docId), payload);
      el("zoneSaveStatus").textContent = "Service area updated in Firestore.";
    } else {
      await addDoc(collection(db, "service_areas"), { ...payload, createdAt: serverTimestamp() });
      el("zoneSaveStatus").textContent = "New service area created in Firestore.";
    }
    populateZoneForm(null);
  } catch (err) {
    el("zoneSaveStatus").textContent = `Save failed: ${err.message}`;
  }
});

el("grantOpsBtn").addEventListener("click", async () => {
  const uid = el("opsUidInput").value.trim();
  const displayName = el("opsNameInput").value.trim() || "Operations Coordinator";
  const contact = el("opsContactInput").value.trim();
  const assignedDistrict = el("opsDistrictInput").value.trim() || "Paschim Medinipur";
  if (!uid) return alert("Enter a Firebase UID.");
  await setDoc(doc(db, "operations_staff", uid), {
    uid,
    displayName,
    phoneOrEmail: contact,
    assignedDistrict,
    active: true,
    addedBy: auth.currentUser?.email || auth.currentUser?.phoneNumber || auth.currentUser?.uid || "",
    createdAt: serverTimestamp(),
  });
  el("opsUidInput").value = "";
  el("opsNameInput").value = "";
  el("opsContactInput").value = "";
});

function startSubscriptions() {
  unsubZones = onSnapshot(collection(db, "service_areas"), (snap) => {
    cachedZones = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
    el("kpiZones").textContent = cachedZones.length;
    refreshZoneOverlay();
    const list = el("zonesList");
    list.innerHTML = cachedZones.length
      ? cachedZones.map((z) => `
        <div class="item-row">
          <div class="row-between">
            <strong>${esc(z.name)} ${z.pinCode ? `• PIN ${esc(z.pinCode)}` : ""}</strong>
            <span class="badge ${z.isActive && z.serviceAvailable !== false ? "badge-primary" : "badge-warn"}">
              ${z.isActive && z.serviceAvailable !== false ? "ACTIVE" : "PAUSED"}
            </span>
          </div>
          <div class="muted">${esc(z.district)} ${esc(z.block)} • Radius ${esc(z.radiusKm ?? 8)} km • Pickup: ${z.pickupEnabled !== false ? "ON" : "OFF"} • Drop: ${z.dropoffEnabled !== false ? "ON" : "OFF"}</div>
          <div class="mono">Vehicles: ${(z.supportedVehicleCategories || ["toto", "auto", "bike", "cab"]).join(", ")}</div>
          <div class="muted" style="font-size:0.78rem;">Villages: ${esc(z.villagesCsv || "Zone radius coverage")}</div>
          <div class="row-between">
            <button type="button" class="secondary edit-zone-btn" data-id="${esc(z.id)}">Edit Boundary</button>
            <button type="button" class="danger del-zone-btn" data-id="${esc(z.id)}">Delete</button>
          </div>
        </div>`).join("")
      : `<p class="muted">No service areas stored yet. Add Borai (721457) or any hub above.</p>`;

    list.querySelectorAll(".edit-zone-btn").forEach((b) =>
      b.addEventListener("click", () => populateZoneForm(cachedZones.find((x) => x.id === b.dataset.id)))
    );
    list.querySelectorAll(".del-zone-btn").forEach((b) =>
      b.addEventListener("click", () => deleteDoc(doc(db, "service_areas", b.dataset.id)))
    );
  });

  unsubFares = onSnapshot(collection(db, "fare_configs"), (snap) => {
    const fares = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
    el("faresList").innerHTML = fares.map((f) => `
      <div class="item-row">
        <div class="row-between">
          <strong>${esc(f.vehicleCategory).toUpperCase()} (v${esc(f.version)})</strong>
          <span class="badge ${f.isEnabled ? "badge-primary" : "badge-warn"}">${f.isEnabled ? "ENABLED" : "LOCKED"}</span>
        </div>
        <div class="form-grid">
          <label>Base (₹)<input id="base-${esc(f.id)}" type="number" value="${esc(f.baseFareInr)}" /></label>
          <label>Per Km (₹)<input id="km-${esc(f.id)}" type="number" value="${esc(f.perKmRateInr)}" /></label>
          <label>Commission %<input id="comm-${esc(f.id)}" type="number" value="${esc(f.commissionPercent)}" /></label>
          <label>GST %<input id="gst-${esc(f.id)}" type="number" value="${esc(f.gstPercent)}" /></label>
        </div>
        <div><button type="button" class="save-fare-btn" data-id="${esc(f.id)}" data-ver="${esc(f.version)}">Publish v${Number(f.version) + 1}</button></div>
      </div>`).join("");
    el("faresList").querySelectorAll(".save-fare-btn").forEach((b) => {
      b.addEventListener("click", async () => {
        const id = b.dataset.id;
        await updateDoc(doc(db, "fare_configs", id), {
          baseFareInr: Number(el(`base-${id}`).value),
          perKmRateInr: Number(el(`km-${id}`).value),
          commissionPercent: Number(el(`comm-${id}`).value),
          gstPercent: Number(el(`gst-${id}`).value),
          version: Number(b.dataset.ver) + 1,
          updatedBy: auth.currentUser?.uid || "",
          updatedAt: serverTimestamp(),
        });
      });
    });
  });

  unsubDrivers = onSnapshot(collection(db, "driver_profiles"), (snap) => {
    const drivers = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
    el("kpiPendingKyc").textContent = drivers.filter((d) => d.verificationStatus === "pending").length;
    el("kycList").innerHTML = drivers.map((d) => `
      <div class="item-row">
        <div class="row-between">
          <strong>${esc(d.legalName)} • ${esc(d.vehicleCategory).toUpperCase()} (${esc(d.vehicleRegNumber)})</strong>
          <span class="badge ${d.verificationStatus === "verified" ? "badge-primary" : "badge-warn"}">${esc(d.verificationStatus).toUpperCase()}</span>
        </div>
        <div class="muted">${esc(d.district)} ${esc(d.block)} • DL: ${esc(d.drivingLicenceNumber)}</div>
        <div class="row-between">
          <button type="button" class="kyc-btn" data-id="${esc(d.id)}" data-status="verified">Approve KYC</button>
          <button type="button" class="danger kyc-btn" data-id="${esc(d.id)}" data-status="rejected">Reject</button>
        </div>
      </div>`).join("");
    el("kycList").querySelectorAll(".kyc-btn").forEach((b) => {
      b.addEventListener("click", async () => {
        const status = b.dataset.status;
        await updateDoc(doc(db, "driver_profiles", b.dataset.id), {
          verificationStatus: status,
          rejectionReason: status === "rejected" ? "Rejected by Owner Admin web portal" : "",
          isOnline: false,
          updatedAt: serverTimestamp(),
        });
        await updateDoc(doc(db, "driver_directory", b.dataset.id), {
          verificationStatus: status,
          isOnline: false,
          updatedAt: serverTimestamp(),
        });
      });
    });
  });

  unsubBookings = onSnapshot(collection(db, "bookings"), (snap) => {
    const bookings = snap.docs.map((d) => d.data());
    el("kpiBookings").textContent = bookings.length;
    const rev = bookings.filter((b) => b.status === "completed").reduce((acc, b) => acc + (b.agreedFareInr || b.estimatedFareInr || 0), 0);
    el("kpiRevenue").textContent = `₹${Math.round(rev)}`;
  });

  unsubOps = onSnapshot(collection(db, "operations_staff"), (snap) => {
    const staff = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
    el("opsCount").textContent = staff.length;
    el("opsList").innerHTML = staff.map((s) => `
      <div class="item-row">
        <div class="row-between">
          <strong>${esc(s.displayName)} • ${esc(s.assignedDistrict)}</strong>
          <button type="button" class="danger revoke-ops-btn" data-id="${esc(s.id)}">Revoke</button>
        </div>
        <div class="mono">UID: ${esc(s.uid)} ${s.phoneOrEmail ? `• ${esc(s.phoneOrEmail)}` : ""}</div>
      </div>`).join("");
    el("opsList").querySelectorAll(".revoke-ops-btn").forEach((b) =>
      b.addEventListener("click", () => deleteDoc(doc(db, "operations_staff", b.dataset.id)))
    );
  });

  unsubRtdbDrivers = onValue(rtdbRef(rtdb, "live_drivers"), (snap) => {
    driverMarkers.forEach((m) => m.remove());
    driverMarkers = [];
    const val = snap.val() || {};
    const online = Object.values(val).filter((d) => d && d.isOnline);
    el("kpiOnlineDrivers").textContent = online.length;
    if (map) {
      for (const d of online) {
        const m = new maplibregl.Marker({ color: "#38bdf8" })
          .setLngLat([d.lng, d.lat])
          .setPopup(new maplibregl.Popup().setText(`${(d.vehicleCategory || "driver").toUpperCase()} • ${d.gpsStatus || "LIVE"}`))
          .addTo(map);
        driverMarkers.push(m);
      }
    }
  });
}

function stopSubscriptions() {
  [unsubZones, unsubFares, unsubDrivers, unsubBookings, unsubOps, unsubRtdbDrivers].forEach((fn) => fn && fn());
}

initEndpointConfig();
bindPhoneOtpLogin({
  formEl: el("ownerAuthForm"),
  phoneInputEl: el("ownerPhoneInput"),
  otpInputEl: el("ownerOtpInput"),
  sendBtnEl: el("ownerSendOtpBtn"),
  verifyBtnEl: el("ownerVerifyOtpBtn"),
  recaptchaContainerId: "ownerRecaptcha",
  statusEl: el("ownerAuthStatus"),
  signOutBtnEl: el("ownerSignOutBtn"),
  onAuthorizedUser: (user, roles) => {
    stopSubscriptions();
    if (!user) {
      el("ownerAuthGate").classList.remove("hidden");
      el("ownerDashboard").classList.add("hidden");
      el("ownerSignOutBtn").classList.add("hidden");
      el("ownerAuthStatus").textContent = "Sign in with Owner Admin Phone OTP.";
      return;
    }
    if (!roles.isOwnerAdmin) {
      el("ownerAuthGate").classList.remove("hidden");
      el("ownerDashboard").classList.add("hidden");
      el("ownerSignOutBtn").classList.remove("hidden");
      el("ownerAuthStatus").textContent = `UID ${user.uid} is not in /admins. Use Operations Dashboard if authorized as ops staff.`;
      return;
    }
    el("ownerAuthGate").classList.add("hidden");
    el("ownerDashboard").classList.remove("hidden");
    el("ownerSignOutBtn").classList.remove("hidden");
    ensureMap();
    startSubscriptions();
  },
});
