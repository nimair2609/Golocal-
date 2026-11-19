import {
  collection,
  doc,
  onSnapshot,
  updateDoc,
  addDoc,
  serverTimestamp,
} from "https://www.gstatic.com/firebasejs/11.0.2/firebase-firestore.js";
import { ref as rtdbRef, onValue } from "https://www.gstatic.com/firebasejs/11.0.2/firebase-database.js";
import {
  BORAI_DEFAULT_CENTER,
  auth,
  db,
  rtdb,
  buildOsmRasterStyle,
  serviceAreasToGeoJson,
  fetchOsrmRoute,
  bindPhoneOtpLogin,
} from "../shared/golocal-firebase.js";

const el = (id) => document.getElementById(id);
const esc = (v) =>
  String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

let map = null;
let fleetMarkers = [];
let cachedBookings = [];
let cachedZones = [];
let cachedComplaints = [];
let cachedDisputes = [];
let unsubs = [];

function ensureOpsMap() {
  if (map) return;
  map = new maplibregl.Map({
    container: "opsMap",
    style: buildOsmRasterStyle(),
    center: [BORAI_DEFAULT_CENTER.lng, BORAI_DEFAULT_CENTER.lat],
    zoom: BORAI_DEFAULT_CENTER.zoom,
  });
  map.addControl(new maplibregl.NavigationControl(), "top-right");

  map.on("load", () => {
    map.addSource("ops-zones", { type: "geojson", data: serviceAreasToGeoJson(cachedZones) });
    map.addLayer({
      id: "ops-zones-fill",
      type: "fill",
      source: "ops-zones",
      paint: { "fill-color": "#10b981", "fill-opacity": 0.12 },
    });
    map.addLayer({
      id: "ops-zones-line",
      type: "line",
      source: "ops-zones",
      paint: { "line-color": "#059669", "line-width": 1.8 },
    });
    map.addSource("ops-trip-route", { type: "geojson", data: { type: "FeatureCollection", features: [] } });
    map.addLayer({
      id: "ops-trip-line",
      type: "line",
      source: "ops-trip-route",
      paint: { "line-color": "#38bdf8", "line-width": 4.5 },
    });
  });
}

el("opsCenterBoraiBtn").addEventListener("click", () => {
  map?.flyTo({ center: [BORAI_DEFAULT_CENTER.lng, BORAI_DEFAULT_CENTER.lat], zoom: BORAI_DEFAULT_CENTER.zoom });
});

function isDelayedOrEscalated(b) {
  if (b.opsEscalationFlag) return true;
  if (!["searching", "assigned", "arriving", "arrived"].includes(b.status)) return false;
  const createdMs = b.createdAt?.toMillis ? b.createdAt.toMillis() : 0;
  return createdMs > 0 && Date.now() - createdMs > 15 * 60 * 1000;
}

function renderBookings() {
  const filter = el("opsBookingFilter").value;
  const activeStatuses = new Set(["searching", "assigned", "arriving", "arrived", "in_progress"]);
  const filtered = cachedBookings.filter((b) => {
    if (filter === "active") return activeStatuses.has(b.status);
    if (filter === "delayed") return isDelayedOrEscalated(b);
    if (filter === "searching") return b.status === "searching";
    return true;
  });

  el("opsKpiActiveRides").textContent = cachedBookings.filter((b) => activeStatuses.has(b.status)).length;
  el("opsKpiDelayed").textContent = cachedBookings.filter(isDelayedOrEscalated).length;

  const container = el("opsBookingsList");
  container.innerHTML = filtered.length
    ? filtered.map((b) => `
      <div class="item-row">
        <div class="row-between">
          <strong>${esc(b.pickupLabel)} → ${esc(b.destinationLabel)}</strong>
          <span class="badge ${b.opsEscalationFlag ? "badge-danger" : "badge-info"}">${esc(b.status).toUpperCase()}</span>
        </div>
        <div class="muted">${esc(b.vehicleCategory).toUpperCase()} • ${esc(b.distanceKm)} km • Driver: ${esc(b.assignedDriverName || "Unassigned")}</div>
        <div class="form-grid" style="grid-template-columns:1fr auto;">
          <input id="note-${esc(b.id)}" type="text" maxlength="240" value="${esc(b.opsDispatchNote || "")}" placeholder="Dispatch note…" />
          <label class="checkbox-chip"><input id="esc-${esc(b.id)}" type="checkbox" ${b.opsEscalationFlag ? "checked" : ""} /> Escalate</label>
        </div>
        <div class="row-between">
          <button type="button" class="secondary trace-btn" data-id="${esc(b.id)}">Trace Route on Map</button>
          <button type="button" class="save-note-btn" data-id="${esc(b.id)}">Save Dispatch Note</button>
        </div>
      </div>`).join("")
    : `<p class="muted">No rides match this filter.</p>`;

  container.querySelectorAll(".trace-btn").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const b = cachedBookings.find((x) => x.id === btn.dataset.id);
      if (!b || typeof b.pickupLat !== "number" || typeof b.destinationLat !== "number") return;
      const route = await fetchOsrmRoute(b.pickupLat, b.pickupLng, b.destinationLat, b.destinationLng);
      map?.getSource("ops-trip-route")?.setData(route.geojson);
      map?.flyTo({ center: [b.pickupLng, b.pickupLat], zoom: 13 });
      el("opsMapInfo").textContent = `Tracing #${b.id.slice(0, 8)} (${route.source}): ${route.distanceKm} km • ~${route.durationMin} min`;
    });
  });

  container.querySelectorAll(".save-note-btn").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const id = btn.dataset.id;
      await updateDoc(doc(db, "bookings", id), {
        opsDispatchNote: el(`note-${id}`).value.trim().slice(0, 240),
        opsEscalationFlag: el(`esc-${id}`).checked,
        opsUpdatedBy: auth.currentUser?.uid || "",
        updatedAt: serverTimestamp(),
      });
    });
  });
}

el("opsBookingFilter").addEventListener("change", renderBookings);

function renderIssues() {
  const openComplaints = cachedComplaints.filter((c) => c.status !== "resolved");
  const openDisputes = cachedDisputes.filter((d) => !String(d.status).startsWith("resolved"));
  el("opsKpiIssues").textContent = openComplaints.length + openDisputes.length;

  el("opsDisputesList").innerHTML = [
    ...openDisputes.map((d) => `
      <div class="item-row">
        <div class="row-between">
          <strong>Dispute: ${esc(d.category)} (${esc(d.reporterRole)})</strong>
          <span class="badge badge-warn">${esc(d.status).toUpperCase()}</span>
        </div>
        <div class="muted">${esc(d.description)}</div>
        <button type="button" class="resolve-dispute-btn" data-id="${esc(d.id)}" data-reporter="${esc(d.reporterId)}">Close Non-Refund Dispute</button>
      </div>`),
    ...openComplaints.map((c) => `
      <div class="item-row">
        <div class="row-between">
          <strong>Complaint: ${esc(c.category)}</strong>
          <span class="badge badge-warn">${esc(c.status).toUpperCase()}</span>
        </div>
        <div class="muted">${esc(c.description)}</div>
        <button type="button" class="resolve-complaint-btn" data-id="${esc(c.id)}">Mark Resolved</button>
      </div>`),
  ].join("") || `<p class="muted">No open complaints or disputes.</p>`;

  el("opsDisputesList").querySelectorAll(".resolve-dispute-btn").forEach((btn) => {
    btn.addEventListener("click", async () => {
      await updateDoc(doc(db, "disputes", btn.dataset.id), {
        status: "resolved_closed",
        adminResolutionNote: "Resolved by Operations Desk",
        resolvedBy: auth.currentUser?.uid || "",
        updatedAt: serverTimestamp(),
      });
      await addDoc(collection(db, "notifications"), {
        userId: btn.dataset.reporter,
        title: "Dispute Update: RESOLVED CLOSED",
        body: "Reviewed and closed by GoLocal Operations Desk.",
        type: "dispute",
        read: false,
        createdAt: serverTimestamp(),
      });
    });
  });

  el("opsDisputesList").querySelectorAll(".resolve-complaint-btn").forEach((btn) => {
    btn.addEventListener("click", () =>
      updateDoc(doc(db, "complaints", btn.dataset.id), {
        status: "resolved",
        resolutionNote: "Resolved by Operations Desk",
        updatedAt: serverTimestamp(),
      })
    );
  });
}

function startOpsSubscriptions() {
  // STRICT ISOLATION: Operations Dashboard NEVER subscribes to users, customer_profiles,
  // driver_profiles, emergency_contacts, payments, commissions, driver_settlements, refunds, or audit_logs.
  unsubs.push(
    onSnapshot(collection(db, "bookings"), (snap) => {
      cachedBookings = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderBookings();
    }),
    onSnapshot(collection(db, "service_areas"), (snap) => {
      cachedZones = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      map?.getSource("ops-zones")?.setData(serviceAreasToGeoJson(cachedZones));
      el("opsZonesReadOnlyList").innerHTML = cachedZones.map((z) => `
        <div class="item-row">
          <div class="row-between">
            <strong>${esc(z.name)} ${z.pinCode ? `• ${esc(z.pinCode)}` : ""}</strong>
            <span class="badge ${z.isActive && z.serviceAvailable !== false ? "badge-primary" : "badge-warn"}">
              ${z.isActive && z.serviceAvailable !== false ? "ACTIVE" : "PAUSED"}
            </span>
          </div>
          <div class="muted">${esc(z.district)} • Pickup: ${z.pickupEnabled !== false ? "ON" : "OFF"} • Drop: ${z.dropoffEnabled !== false ? "ON" : "OFF"}</div>
        </div>`).join("") || `<p class="muted">No service zones configured.</p>`;
    }),
    onSnapshot(collection(db, "driver_directory"), (snap) => {
      const fleet = snap.docs.map((d) => d.data());
      el("opsFleetList").innerHTML = fleet.map((d) => `
        <div class="item-row">
          <div class="row-between">
            <strong>${esc(d.displayName)} • ${esc(d.vehicleCategory).toUpperCase()} (${esc(d.maskedPlate)})</strong>
            <span class="badge ${d.isOnline ? "badge-primary" : "badge-warn"}">${d.isOnline ? "ONLINE" : "OFFLINE"}</span>
          </div>
          <div class="muted">${esc(d.district)} ${esc(d.block)} • Rating ${Number(d.ratingAvg || 5).toFixed(1)}★</div>
        </div>`).join("") || `<p class="muted">No drivers registered yet.</p>`;
    }),
    onSnapshot(collection(db, "emergency_events"), (snap) => {
      const sosList = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      const active = sosList.filter((s) => s.status === "active");
      el("opsKpiSos").textContent = active.length;
      el("opsSosList").innerHTML = sosList.filter((s) => s.status !== "resolved").map((s) => `
        <div class="item-row">
          <div class="row-between">
            <strong>SOS #${esc(s.id.slice(0, 8))}</strong>
            <span class="badge badge-danger">${esc(s.status).toUpperCase()}</span>
          </div>
          <div class="muted">Booking: ${esc(s.bookingId || "Home")} • GPS: ${esc(s.lat)}, ${esc(s.lng)} • ${esc(s.notes)}</div>
          <button type="button" class="resolve-sos-btn" data-id="${esc(s.id)}">Mark SOS Resolved</button>
        </div>`).join("") || `<p class="muted">No active SOS incidents.</p>`;
      el("opsSosList").querySelectorAll(".resolve-sos-btn").forEach((b) =>
        b.addEventListener("click", () => updateDoc(doc(db, "emergency_events", b.dataset.id), { status: "resolved", updatedAt: serverTimestamp() }))
      );
    }),
    onSnapshot(collection(db, "complaints"), (snap) => {
      cachedComplaints = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderIssues();
    }),
    onSnapshot(collection(db, "disputes"), (snap) => {
      cachedDisputes = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderIssues();
    }),
    onValue(rtdbRef(rtdb, "live_drivers"), (snap) => {
      fleetMarkers.forEach((m) => m.remove());
      fleetMarkers = [];
      const drivers = Object.values(snap.val() || {}).filter((d) => d && d.isOnline);
      el("opsKpiLiveDrivers").textContent = drivers.length;
      if (map) {
        for (const d of drivers) {
          const m = new maplibregl.Marker({ color: "#10b981" })
            .setLngLat([d.lng, d.lat])
            .setPopup(new maplibregl.Popup().setText(`${(d.vehicleCategory || "driver").toUpperCase()} (${d.gpsStatus || "LIVE"})`))
            .addTo(map);
          fleetMarkers.push(m);
        }
      }
    })
  );
}

function stopOpsSubscriptions() {
  unsubs.forEach((u) => typeof u === "function" && u());
  unsubs = [];
}

bindPhoneOtpLogin({
  formEl: el("opsAuthForm"),
  phoneInputEl: el("opsPhoneInput"),
  otpInputEl: el("opsOtpInput"),
  sendBtnEl: el("opsSendOtpBtn"),
  verifyBtnEl: el("opsVerifyOtpBtn"),
  recaptchaContainerId: "opsRecaptcha",
  statusEl: el("opsAuthStatus"),
  signOutBtnEl: el("opsSignOutBtn"),
  onAuthorizedUser: (user, roles) => {
    stopOpsSubscriptions();
    if (!user) {
      el("opsAuthGate").classList.remove("hidden");
      el("opsDashboard").classList.add("hidden");
      el("opsSignOutBtn").classList.add("hidden");
      el("opsAuthStatus").textContent = "Sign in with Operations Phone OTP.";
      return;
    }
    if (!roles.isOperationsStaff && !roles.isOwnerAdmin) {
      el("opsAuthGate").classList.remove("hidden");
      el("opsDashboard").classList.add("hidden");
      el("opsSignOutBtn").classList.remove("hidden");
      el("opsAuthStatus").textContent = `UID ${user.uid} is not active in /operations_staff or /admins.`;
      return;
    }
    el("opsAuthGate").classList.add("hidden");
    el("opsDashboard").classList.remove("hidden");
    el("opsSignOutBtn").classList.remove("hidden");
    ensureOpsMap();
    startOpsSubscriptions();
  },
});
