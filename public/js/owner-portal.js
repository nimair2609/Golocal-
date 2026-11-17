import {
  collection,
  doc,
  addDoc,
  setDoc,
  updateDoc,
  deleteDoc,
  onSnapshot,
  query,
  orderBy,
  limit,
  serverTimestamp
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";
import {
  ref as rtdbRef,
  onValue,
  set as rtdbSet,
  remove as rtdbRemove
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-database.js";
import {
  db,
  rtdb,
  PORTAL_ROLES,
  resolvePortalRole,
  logoutPortal,
  watchAuth,
  escapeHtml,
  formatEpochTime
} from "./firebase-config.js";
import {
  BORAI_LAUNCH,
  createOsmMap,
  fetchOsrmRoute,
  searchOsmPlaces
} from "./map-adapters.js";

const ownerContent = document.getElementById("ownerContent");
const accessGateAlert = document.getElementById("accessGateAlert");
const ownerBadge = document.getElementById("ownerBadge");
const ownerSignOutBtn = document.getElementById("ownerSignOutBtn");

let mainMapCtrl = null;
let zoneMapCtrl = null;
let currentUser = null;
let polygonDraft = [];
let selectedRouteCoords = [];
const unsubscribers = [];

const state = {
  bookings: [],
  driversDir: [],
  liveDriversRtdb: new Map(),
  zones: [],
  emergencies: [],
  opsStaff: [],
  auditLogs: []
};

function showGateError(msg) {
  ownerContent.classList.add("hidden");
  accessGateAlert.innerHTML = `${escapeHtml(msg)} <a href="./index.html" style="margin-left:8px;font-weight:700;">Return to Sign-In</a>`;
  accessGateAlert.classList.remove("hidden");
}

function clearSubscriptions() {
  while (unsubscribers.length) {
    const fn = unsubscribers.pop();
    try {
      fn();
    } catch (_) {}
  }
}

ownerSignOutBtn.addEventListener("click", async () => {
  clearSubscriptions();
  await logoutPortal();
  window.location.href = "./index.html";
});

// Tabs
document.querySelectorAll(".tab-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".tab-btn").forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    const target = btn.dataset.tab;
    ["mapTab", "zonesTab", "opsAccessTab"].forEach((id) => {
      document.getElementById(id).classList.toggle("hidden", id !== target);
    });
    if (target === "mapTab" && mainMapCtrl) {
      setTimeout(() => mainMapCtrl.map.resize(), 60);
    }
    if (target === "zonesTab" && zoneMapCtrl) {
      setTimeout(() => zoneMapCtrl.map.resize(), 60);
    }
  });
});

// Filters & Place search
["filterStatus", "filterCategory", "filterDistrict"].forEach((id) => {
  document.getElementById(id).addEventListener("change", renderAll);
});

document.getElementById("focusBoraiBtn").addEventListener("click", () => {
  mainMapCtrl?.flyTo(BORAI_LAUNCH.lat, BORAI_LAUNCH.lng, BORAI_LAUNCH.zoom);
});

document.getElementById("searchPlaceBtn").addEventListener("click", () => {
  document.getElementById("placeSearchBar").classList.toggle("hidden");
});

document.getElementById("runPlaceSearchBtn").addEventListener("click", async () => {
  const q = document.getElementById("placeQueryInput").value.trim();
  const box = document.getElementById("placeResultsBox");
  box.classList.remove("hidden");
  box.innerHTML = `<span class="list-item-muted">Searching OpenStreetMap...</span>`;
  try {
    const places = await searchOsmPlaces(q);
    if (!places.length) {
      box.innerHTML = `<span class="list-item-muted">No matching places returned.</span>`;
      return;
    }
    box.innerHTML = "";
    places.forEach((p) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "btn btn-secondary";
      btn.textContent = p.displayName;
      btn.title = p.fullAddress;
      btn.addEventListener("click", () => {
        mainMapCtrl?.flyTo(p.lat, p.lng, 14.2);
      });
      box.appendChild(btn);
    });
  } catch (err) {
    box.innerHTML = `<span class="list-item-muted">Search error: ${escapeHtml(err?.message || "Unavailable")}</span>`;
  }
});

// Zone Map click & form controls
document.getElementById("clearPolygonBtn").addEventListener("click", () => {
  polygonDraft = [];
  updateZoneMapPreview();
});

document.getElementById("resetZoneFormBtn").addEventListener("click", () => {
  document.getElementById("zId").value = "";
  document.getElementById("zoneForm").reset();
  document.getElementById("zDistrict").value = BORAI_LAUNCH.district;
  document.getElementById("zBlock").value = BORAI_LAUNCH.block;
  document.getElementById("zPincode").value = BORAI_LAUNCH.pincode;
  document.getElementById("zLat").value = BORAI_LAUNCH.lat;
  document.getElementById("zLng").value = BORAI_LAUNCH.lng;
  document.getElementById("zRadiusKm").value = "12";
  document.getElementById("zVillages").value = BORAI_LAUNCH.defaultVillages.join(", ");
  document.getElementById("zHours").value = "05:00 - 22:30";
  polygonDraft = [];
  updateZoneMapPreview();
});

document.getElementById("seedBoraiBtn").addEventListener("click", async () => {
  if (!currentUser) return;
  const zoneId = "borai_721457_zone";
  const payload = {
    name: "Borai & Mohanpur Launch Zone",
    nameBn: "বড়াই ও মোহনপুর লঞ্চ জোন",
    district: BORAI_LAUNCH.district,
    block: BORAI_LAUNCH.block,
    pincode: BORAI_LAUNCH.pincode,
    zoneType: "village",
    lat: BORAI_LAUNCH.lat,
    lng: BORAI_LAUNCH.lng,
    centerLat: BORAI_LAUNCH.lat,
    centerLng: BORAI_LAUNCH.lng,
    radiusKm: 12.0,
    polygonPoints: BORAI_LAUNCH.defaultPolygon,
    villages: BORAI_LAUNCH.defaultVillages,
    pickupEnabled: true,
    dropoffEnabled: true,
    supportedVehicleCategories: ["toto", "auto", "bike", "cab"],
    availabilityNote: "Default rural & town mobility launch zone around Borai (PIN 721457), Mohanpur.",
    isActive: true,
    parcelApproved: false,
    operationalHours: "05:00 - 22:30",
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  };
  await setDoc(doc(db, "service_areas", zoneId), payload, { merge: true });
  await recordAudit("seed_borai_zone", "service_areas", zoneId, "Seeded Borai (PIN 721457) launch zone from Owner Web Portal");
  showZoneStatus("Seeded Borai (PIN 721457), Mohanpur launch zone.");
});

document.getElementById("zoneForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!currentUser) return;
  const zoneId = document.getElementById("zId").value.trim() || `zone_${Date.now()}`;
  const centerLat = Number(document.getElementById("zLat").value);
  const centerLng = Number(document.getElementById("zLng").value);
  const radiusKm = Number(document.getElementById("zRadiusKm").value || 8);
  const villages = document.getElementById("zVillages").value
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  const vehicles = Array.from(document.querySelectorAll('#zVehiclesBox input[type="checkbox"]:checked'))
    .map((el) => el.value);

  const existing = state.zones.find((z) => z.id === zoneId);
  const payload = {
    name: document.getElementById("zName").value.trim(),
    nameBn: existing?.nameBn || "",
    district: document.getElementById("zDistrict").value.trim(),
    block: document.getElementById("zBlock").value.trim(),
    pincode: document.getElementById("zPincode").value.trim(),
    zoneType: document.getElementById("zType").value,
    lat: centerLat,
    lng: centerLng,
    centerLat,
    centerLng,
    radiusKm,
    polygonPoints: polygonDraft.length >= 3 ? polygonDraft : [],
    villages,
    pickupEnabled: document.getElementById("zPickup").checked,
    dropoffEnabled: document.getElementById("zDropoff").checked,
    supportedVehicleCategories: vehicles,
    availabilityNote: document.getElementById("zNote").value.trim(),
    isActive: document.getElementById("zActive").checked,
    parcelApproved: existing?.parcelApproved ?? false,
    operationalHours: document.getElementById("zHours").value.trim() || "05:00 - 22:30",
    createdAt: existing?.createdAt || serverTimestamp(),
    updatedAt: serverTimestamp()
  };

  try {
    await setDoc(doc(db, "service_areas", zoneId), payload, { merge: true });
    await recordAudit("save_service_zone", "service_areas", zoneId, `Updated zone ${payload.name} (${payload.pincode || payload.district})`);
    showZoneStatus(`Saved service zone "${payload.name}".`);
  } catch (err) {
    showZoneStatus(`Error saving zone: ${err?.message || "Permission denied"}`, true);
  }
});

document.getElementById("opsForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const uid = document.getElementById("opsUid").value.trim();
  const displayName = document.getElementById("opsName").value.trim();
  if (!uid || !displayName) return;
  const phoneOrEmail = document.getElementById("opsPhone").value.trim();
  const assignedDistrict = document.getElementById("opsDistrict").value.trim();
  const isActive = document.getElementById("opsActive").checked;

  await setDoc(doc(db, "operations_staff", uid), {
    userId: uid,
    displayName,
    phoneOrEmail,
    assignedDistrict,
    isActive,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  }, { merge: true });

  if (isActive) {
    await rtdbSet(rtdbRef(rtdb, `auth_roles/ops/${uid}`), {
      uid,
      updatedAt: Date.now()
    });
  } else {
    await rtdbRemove(rtdbRef(rtdb, `auth_roles/ops/${uid}`));
  }

  await recordAudit("upsert_ops_staff", "operations_staff", uid, `${isActive ? "Enabled" : "Disabled"} Operations access for ${displayName}`);
  document.getElementById("opsForm").reset();
});

function showZoneStatus(msg, isError = false) {
  const el = document.getElementById("zoneFormStatus");
  el.textContent = msg;
  el.className = `alert ${isError ? "alert-error" : "alert-success"}`;
  el.classList.remove("hidden");
}

async function recordAudit(action, targetCollection, targetId, summary) {
  if (!currentUser) return;
  try {
    await addDoc(collection(db, "audit_logs"), {
      actorId: currentUser.uid,
      actorEmail: currentUser.phoneNumber || currentUser.email || "",
      action,
      targetCollection,
      targetId,
      summary,
      createdAt: serverTimestamp()
    });
  } catch (_) {}
}

function initMaps() {
  if (!mainMapCtrl) {
    mainMapCtrl = createOsmMap("ownerMap", {
      center: [BORAI_LAUNCH.lng, BORAI_LAUNCH.lat],
      zoom: BORAI_LAUNCH.zoom
    });
  }
  if (!zoneMapCtrl) {
    zoneMapCtrl = createOsmMap("zoneConfigMap", {
      center: [BORAI_LAUNCH.lng, BORAI_LAUNCH.lat],
      zoom: 12.2,
      onMapClick: (lat, lng) => {
        const mode = document.querySelector('input[name="zoneClickMode"]:checked')?.value || "center";
        if (mode === "vertex") {
          polygonDraft.push({ lat: Number(lat.toFixed(5)), lng: Number(lng.toFixed(5)) });
        } else {
          document.getElementById("zLat").value = lat.toFixed(5);
          document.getElementById("zLng").value = lng.toFixed(5);
        }
        updateZoneMapPreview();
      }
    });
  }
}

function updateZoneMapPreview() {
  const cLat = Number(document.getElementById("zLat").value || BORAI_LAUNCH.lat);
  const cLng = Number(document.getElementById("zLng").value || BORAI_LAUNCH.lng);
  const rad = Number(document.getElementById("zRadiusKm").value || 10);

  document.getElementById("zonePolygonCount").textContent = polygonDraft.length >= 3
    ? `Polygon vertices: ${polygonDraft.length} (polygon boundary active)`
    : `Polygon vertices: ${polygonDraft.length} (using ${rad} km radius boundary)`;

  if (!zoneMapCtrl) return;
  const draftZone = {
    id: "draft_preview",
    name: document.getElementById("zName").value || "Draft Zone",
    centerLat: cLat,
    centerLng: cLng,
    radiusKm: rad,
    polygonPoints: polygonDraft,
    isActive: document.getElementById("zActive").checked
  };
  zoneMapCtrl.updateZones([...state.zones, draftZone]);
  const markers = [
    { id: "draft_center", lat: cLat, lng: cLng, title: "Zone Center", color: "#0D47A1", radius: 8 },
    ...polygonDraft.map((pt, idx) => ({
      id: `v_${idx}`,
      lat: pt.lat,
      lng: pt.lng,
      title: `Vertex ${idx + 1}`,
      color: "#F59E0B",
      radius: 6
    }))
  ];
  zoneMapCtrl.updateMarkers(markers);
}

function updateDistrictOptions() {
  const sel = document.getElementById("filterDistrict");
  const prev = sel.value;
  const districts = new Set();
  state.bookings.forEach((b) => b.pickupDistrict && districts.add(b.pickupDistrict));
  state.zones.forEach((z) => z.district && districts.add(z.district));
  sel.innerHTML = `<option value="all">All Districts</option>` +
    Array.from(districts).sort().map((d) => `<option value="${escapeHtml(d)}">${escapeHtml(d)}</option>`).join("");
  if ([...sel.options].some((o) => o.value === prev)) {
    sel.value = prev;
  }
}

function renderAll() {
  const activeStates = new Set(["searching", "assigned", "arriving", "arrived", "in_progress"]);
  const activeCount = state.bookings.filter((b) => activeStates.has(b.status)).length;
  const searchingCount = state.bookings.filter((b) => b.status === "searching").length;
  const completedCount = state.bookings.filter((b) => b.status === "completed").length;
  const liveDriverList = Array.from(state.liveDriversRtdb.values()).filter((d) => d.trackingStatus !== "stopped");

  document.getElementById("mActiveBookings").textContent = String(activeCount);
  document.getElementById("mSearchingBookings").textContent = String(searchingCount);
  document.getElementById("mLiveDrivers").textContent = String(liveDriverList.length);
  document.getElementById("mActiveZones").textContent = String(state.zones.filter((z) => z.isActive).length);
  document.getElementById("mCompletedBookings").textContent = String(completedCount);

  const fStatus = document.getElementById("filterStatus").value;
  const fCat = document.getElementById("filterCategory").value;
  const fDist = document.getElementById("filterDistrict").value;

  const filteredBookings = state.bookings.filter((b) => {
    const okStatus = fStatus === "all"
      ? true
      : fStatus === "active"
        ? activeStates.has(b.status)
        : b.status === fStatus;
    const okCat = fCat === "all" || b.vehicleCategory === fCat;
    const okDist = fDist === "all" ||
      String(b.pickupDistrict || "").toLowerCase() === fDist.toLowerCase() ||
      String(b.destinationDistrict || "").toLowerCase() === fDist.toLowerCase();
    return okStatus && okCat && okDist;
  });

  // Update Owner Main Map
  if (mainMapCtrl) {
    mainMapCtrl.updateZones(state.zones);
    const markers = [];
    const routes = [];

    filteredBookings.slice(0, 60).forEach((b) => {
      if (b.pickupLat && b.pickupLng) {
        markers.push({
          id: `bp_${b.id}`,
          lat: b.pickupLat,
          lng: b.pickupLng,
          title: `${b.status.toUpperCase()}: ${b.pickupLabel}`,
          subtitle: `To ${b.destinationLabel} • ${b.vehicleCategory.toUpperCase()} • ₹${Math.round(b.agreedFareInr || b.estimatedFareInr || 0)}`,
          color: b.status === "searching" ? "#F59E0B" : "#1565C0",
          radius: 7
        });
      }
      if (b.destinationLat && b.destinationLng && activeStates.has(b.status)) {
        markers.push({
          id: `bd_${b.id}`,
          lat: b.destinationLat,
          lng: b.destinationLng,
          title: `Drop: ${b.destinationLabel}`,
          subtitle: `Booking ${b.id.slice(0, 8)}`,
          color: "#475569",
          radius: 5
        });
      }
      if (b.pickupLat && b.pickupLng && b.destinationLat && b.destinationLng && activeStates.has(b.status)) {
        routes.push({
          id: `r_${b.id}`,
          color: b.status === "searching" ? "#F59E0B" : "#1565C0",
          coords: [
            [b.pickupLng, b.pickupLat],
            [b.destinationLng, b.destinationLat]
          ]
        });
      }
    });

    if (selectedRouteCoords.length >= 2) {
      routes.push({ id: "selected_osrm", color: "#0D47A1", coords: selectedRouteCoords });
    }

    liveDriverList.forEach((d) => {
      const dir = state.driversDir.find((x) => x.driverId === d.driverId);
      markers.push({
        id: `drv_${d.driverId}`,
        lat: d.lat,
        lng: d.lng,
        title: `${dir?.displayName || "Partner"} (${d.trackingStatus})`,
        subtitle: `${dir?.vehicleCategory?.toUpperCase() || "PARTNER"} • ${formatEpochTime(d.updatedAtEpochMs)}`,
        color: d.trackingStatus === "paused_gps_lost" ? "#DC2626" : "#15803D",
        radius: 8
      });
    });

    state.emergencies.filter((e) => e.status !== "resolved" && e.lat && e.lng).forEach((e) => {
      markers.push({
        id: `sos_${e.id}`,
        lat: e.lat,
        lng: e.lng,
        title: `SOS Alert (${e.status})`,
        subtitle: e.notes || "Emergency triggered",
        color: "#DC2626",
        radius: 10
      });
    });

    mainMapCtrl.updateMarkers(markers);
    mainMapCtrl.updateRoutes(routes);
  }

  // Demand summary
  const demandCounts = new Map();
  state.bookings.forEach((b) => {
    const key = b.pickupDistrict || b.pickupLabel || "Unspecified";
    demandCounts.set(key, (demandCounts.get(key) || 0) + 1);
  });
  const demandEl = document.getElementById("demandList");
  if (demandCounts.size === 0) {
    demandEl.innerHTML = `<div class="list-item-muted">No recorded bookings yet. Real demand counts populate automatically from Firestore bookings.</div>`;
  } else {
    demandEl.innerHTML = Array.from(demandCounts.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 12)
      .map(([loc, count]) => `
        <div class="list-item space-between">
          <strong>${escapeHtml(loc)}</strong>
          <span class="badge badge-info">${count} trips</span>
        </div>
      `).join("");
  }

  // Filtered bookings list
  const bookingsEl = document.getElementById("ownerBookingsList");
  if (!filteredBookings.length) {
    bookingsEl.innerHTML = `<div class="list-item-muted">No bookings match the selected filters.</div>`;
  } else {
    bookingsEl.innerHTML = "";
    filteredBookings.slice(0, 40).forEach((b) => {
      const div = document.createElement("div");
      div.className = "list-item stack";
      div.style.gap = "4px";
      div.innerHTML = `
        <div class="space-between">
          <span class="badge ${b.status === "searching" ? "badge-warning" : b.status === "completed" ? "badge-success" : "badge-info"}">${escapeHtml(b.status.toUpperCase())}</span>
          <span class="mono">₹${Math.round(b.agreedFareInr || b.estimatedFareInr || 0)}</span>
        </div>
        <div><strong>${escapeHtml(b.pickupLabel)}</strong> → ${escapeHtml(b.destinationLabel)}</div>
        <div class="list-item-muted">Customer: ${escapeHtml(b.customerName)} • Driver: ${escapeHtml(b.assignedDriverName || "Unassigned")} • ${escapeHtml(b.vehicleCategory.toUpperCase())}</div>
      `;
      if (b.pickupLat && b.pickupLng) {
        const focusBtn = document.createElement("button");
        focusBtn.type = "button";
        focusBtn.className = "btn btn-secondary";
        focusBtn.style.alignSelf = "flex-start";
        focusBtn.textContent = "Inspect OSRM Route";
        focusBtn.addEventListener("click", async () => {
          mainMapCtrl?.flyTo(b.pickupLat, b.pickupLng, 13.4);
          if (b.destinationLat && b.destinationLng) {
            const route = await fetchOsrmRoute(b.pickupLat, b.pickupLng, b.destinationLat, b.destinationLng);
            selectedRouteCoords = route.coords;
            renderAll();
          }
        });
        div.appendChild(focusBtn);
      }
      bookingsEl.appendChild(div);
    });
  }

  // Zones List
  const zonesEl = document.getElementById("zonesList");
  if (!state.zones.length) {
    zonesEl.innerHTML = `<div class="list-item-muted">No zones saved yet. Click "Seed Borai (PIN 721457) Launch Zone" or create one.</div>`;
  } else {
    zonesEl.innerHTML = "";
    state.zones.forEach((z) => {
      const div = document.createElement("div");
      div.className = "list-item stack";
      div.style.gap = "6px";
      div.innerHTML = `
        <div class="space-between">
          <strong>${escapeHtml(z.name)}</strong>
          <span class="badge ${z.isActive ? "badge-success" : "badge-muted"}">${z.isActive ? "ACTIVE" : "PAUSED"}</span>
        </div>
        <div class="list-item-muted">${escapeHtml(z.block || "")} • ${escapeHtml(z.district)} • PIN ${escapeHtml(z.pincode || "—")} • Radius ${z.radiusKm || 8} km • Vertices: ${(z.polygonPoints || []).length}</div>
        <div class="list-item-muted">Pickup: ${z.pickupEnabled !== false ? "On" : "Off"} • Drop-off: ${z.dropoffEnabled !== false ? "On" : "Off"} • Vehicles: ${escapeHtml((z.supportedVehicleCategories || []).join(", ").toUpperCase() || "ALL")}</div>
      `;
      const actions = document.createElement("div");
      actions.className = "row";

      const editBtn = document.createElement("button");
      editBtn.type = "button";
      editBtn.className = "btn btn-secondary";
      editBtn.textContent = "Edit";
      editBtn.addEventListener("click", () => {
        document.getElementById("zId").value = z.id;
        document.getElementById("zName").value = z.name || "";
        document.getElementById("zType").value = z.zoneType || "village";
        document.getElementById("zDistrict").value = z.district || "";
        document.getElementById("zBlock").value = z.block || "";
        document.getElementById("zPincode").value = z.pincode || "";
        document.getElementById("zLat").value = z.centerLat || z.lat || BORAI_LAUNCH.lat;
        document.getElementById("zLng").value = z.centerLng || z.lng || BORAI_LAUNCH.lng;
        document.getElementById("zRadiusKm").value = z.radiusKm || 10;
        document.getElementById("zVillages").value = (z.villages || []).join(", ");
        document.getElementById("zHours").value = z.operationalHours || "05:00 - 22:30";
        document.getElementById("zNote").value = z.availabilityNote || "";
        document.getElementById("zActive").checked = z.isActive !== false;
        document.getElementById("zPickup").checked = z.pickupEnabled !== false;
        document.getElementById("zDropoff").checked = z.dropoffEnabled !== false;
        const allowed = new Set(z.supportedVehicleCategories || []);
        document.querySelectorAll('#zVehiclesBox input[type="checkbox"]').forEach((cb) => {
          cb.checked = allowed.size === 0 || allowed.has(cb.value);
        });
        polygonDraft = (z.polygonPoints || []).map((p) => ({ lat: p.lat, lng: p.lng }));
        updateZoneMapPreview();
        zoneMapCtrl?.flyTo(Number(document.getElementById("zLat").value), Number(document.getElementById("zLng").value), 12.6);
      });

      const toggleBtn = document.createElement("button");
      toggleBtn.type = "button";
      toggleBtn.className = "btn btn-secondary";
      toggleBtn.textContent = z.isActive ? "Disable" : "Enable";
      toggleBtn.addEventListener("click", async () => {
        await updateDoc(doc(db, "service_areas", z.id), {
          isActive: !z.isActive,
          updatedAt: serverTimestamp()
        });
      });

      const delBtn = document.createElement("button");
      delBtn.type = "button";
      delBtn.className = "btn btn-danger";
      delBtn.textContent = "Delete";
      delBtn.addEventListener("click", async () => {
        await deleteDoc(doc(db, "service_areas", z.id));
        await recordAudit("delete_service_zone", "service_areas", z.id, `Deleted zone ${z.name}`);
      });

      actions.append(editBtn, toggleBtn, delBtn);
      div.appendChild(actions);
      zonesEl.appendChild(div);
    });
  }
  updateZoneMapPreview();

  // Operations Staff List
  const opsEl = document.getElementById("opsStaffList");
  if (!state.opsStaff.length) {
    opsEl.innerHTML = `<div class="list-item-muted">No Operations Staff allowlisted yet.</div>`;
  } else {
    opsEl.innerHTML = "";
    state.opsStaff.forEach((op) => {
      const div = document.createElement("div");
      div.className = "list-item space-between";
      div.innerHTML = `
        <div>
          <strong>${escapeHtml(op.displayName)}</strong>
          <div class="list-item-muted mono">${escapeHtml(op.userId)} • ${escapeHtml(op.assignedDistrict || "All")}</div>
        </div>
        <span class="badge ${op.isActive ? "badge-success" : "badge-muted"}">${op.isActive ? "ACTIVE OPS" : "REVOKED"}</span>
      `;
      opsEl.appendChild(div);
    });
  }

  // Audit Logs
  const auditEl = document.getElementById("auditLogsList");
  if (!state.auditLogs.length) {
    auditEl.innerHTML = `<div class="list-item-muted">No audit logs recorded yet.</div>`;
  } else {
    auditEl.innerHTML = state.auditLogs.map((a) => `
      <div class="list-item">
        <div class="space-between">
          <strong>${escapeHtml(a.action)}</strong>
          <span class="list-item-muted">${escapeHtml(formatEpochTime(a.createdAt?.seconds))}</span>
        </div>
        <div class="list-item-muted">${escapeHtml(a.summary)}</div>
        <div class="list-item-muted mono">Actor: ${escapeHtml(a.actorId)} • ${escapeHtml(a.targetCollection)}/${escapeHtml(a.targetId)}</div>
      </div>
    `).join("");
  }
}

function attachListeners() {
  clearSubscriptions();

  unsubscribers.push(
    onSnapshot(query(collection(db, "bookings"), orderBy("createdAt", "desc"), limit(120)), (snap) => {
      state.bookings = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      updateDistrictOptions();
      renderAll();
    })
  );

  unsubscribers.push(
    onSnapshot(collection(db, "driver_directory"), (snap) => {
      state.driversDir = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderAll();
    })
  );

  unsubscribers.push(
    onSnapshot(collection(db, "service_areas"), (snap) => {
      state.zones = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      updateDistrictOptions();
      renderAll();
    })
  );

  unsubscribers.push(
    onSnapshot(query(collection(db, "emergency_events"), orderBy("createdAt", "desc"), limit(40)), (snap) => {
      state.emergencies = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderAll();
    })
  );

  unsubscribers.push(
    onSnapshot(collection(db, "operations_staff"), (snap) => {
      state.opsStaff = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderAll();
    })
  );

  unsubscribers.push(
    onSnapshot(query(collection(db, "audit_logs"), orderBy("createdAt", "desc"), limit(60)), (snap) => {
      state.auditLogs = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderAll();
    })
  );

  unsubscribers.push(
    onValue(rtdbRef(rtdb, "live_locations/drivers"), (snap) => {
      state.liveDriversRtdb.clear();
      const val = snap.val() || {};
      Object.entries(val).forEach(([driverId, entry]) => {
        if (entry && typeof entry.lat === "number" && typeof entry.lng === "number") {
          state.liveDriversRtdb.set(driverId, { driverId, ...entry });
        }
      });
      renderAll();
    })
  );
}

watchAuth(async (user) => {
  clearSubscriptions();
  currentUser = user;
  if (!user) {
    showGateError("Please sign in with Phone OTP to open the Owner Admin Portal.");
    return;
  }
  const role = await resolvePortalRole(user);
  if (role !== PORTAL_ROLES.OWNER_ADMIN) {
    showGateError("Owner Admin access required. Operations Staff and Customers cannot open Owner Admin controls.");
    return;
  }

  accessGateAlert.classList.add("hidden");
  ownerBadge.classList.remove("hidden");
  ownerContent.classList.remove("hidden");
  initMaps();
  attachListeners();
});
