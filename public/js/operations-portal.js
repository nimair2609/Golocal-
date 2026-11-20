import {
  collection,
  doc,
  addDoc,
  updateDoc,
  onSnapshot,
  query,
  orderBy,
  limit,
  writeBatch,
  serverTimestamp,
  Timestamp
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";
import {
  ref as rtdbRef,
  onValue
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-database.js";
import {
  db,
  rtdb,
  PORTAL_ROLES,
  resolvePortalRole,
  logoutPortal,
  watchAuth,
  generateOtpAndHash,
  escapeHtml,
  formatEpochTime,
  downloadCsvFile
} from "./firebase-config.js";
import { buildCsvContent } from "./golocal-domain.js";
import {
  BORAI_LAUNCH,
  createOsmMap,
  fetchOsrmRoute
} from "./map-adapters.js";

const opsContent = document.getElementById("opsContent");
const opsGateAlert = document.getElementById("opsGateAlert");
const opsRoleBadge = document.getElementById("opsRoleBadge");
const ownerLinkBtn = document.getElementById("ownerLinkBtn");
const opsSignOutBtn = document.getElementById("opsSignOutBtn");

let opsMapCtrl = null;
let currentUser = null;
let activeRole = PORTAL_ROLES.NONE;
let selectedAssignBookingId = null;
let focusedRouteCoords = [];
const unsubscribers = [];

const state = {
  bookings: [],
  driversDir: [],
  liveDriversRtdb: new Map(),
  zones: [],
  complaints: [],
  disputes: [],
  emergencies: []
};

function showGateError(msg) {
  opsContent.classList.add("hidden");
  opsGateAlert.innerHTML = `${escapeHtml(msg)} <a href="./index.html" style="margin-left:8px;font-weight:700;">Return to Sign-In</a>`;
  opsGateAlert.classList.remove("hidden");
}

function clearSubscriptions() {
  while (unsubscribers.length) {
    try {
      unsubscribers.pop()();
    } catch (_) {}
  }
}

opsSignOutBtn.addEventListener("click", async () => {
  clearSubscriptions();
  await logoutPortal();
  window.location.href = "./index.html";
});

document.getElementById("opsCenterBoraiBtn").addEventListener("click", () => {
  opsMapCtrl?.flyTo(BORAI_LAUNCH.lat, BORAI_LAUNCH.lng, BORAI_LAUNCH.zoom);
});

["opsFilterStatus", "opsFilterCategory", "opsFilterDistrict"].forEach((id) => {
  document.getElementById(id).addEventListener("change", renderAll);
});

document.getElementById("exportOpsCsvBtn").addEventListener("click", () => {
  const csv = buildCsvContent(
    ["Booking ID", "Status", "Category", "Pickup", "Drop", "Customer", "Driver", "Fare (INR)", "Escalated", "Ops Note"],
    state.bookings.map((b) => [
      b.id,
      b.status,
      b.vehicleCategory,
      b.pickupLabel,
      b.destinationLabel,
      b.customerName,
      b.assignedDriverName || "",
      b.agreedFareInr || b.estimatedFareInr || 0,
      b.opsEscalationFlag ? "YES" : "NO",
      b.opsDispatchNote || ""
    ])
  );
  downloadCsvFile(`golocal_ops_shift_summary_${Date.now()}.csv`, csv);
});

function isBookingDelayed(b) {
  if (b.status !== "searching" && b.status !== "assigned") return false;
  const createdSec = b.createdAt?.seconds;
  if (!createdSec) return false;
  return (Date.now() / 1000 - createdSec) >= 600;
}

function updateDistrictOptions() {
  const sel = document.getElementById("opsFilterDistrict");
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

async function assignPartnerToBooking(booking, driver) {
  if (!currentUser || !booking || !driver) return;
  const bookingRef = doc(db, "bookings", booking.id);
  const lockedFare = Math.max(
    Number(booking.minPermittedFareInr || 0),
    Number(booking.agreedFareInr || booking.estimatedFareInr || 0)
  );
  const { otpPlaintext, otpHash } = await generateOtpAndHash(booking.id);
  const expiresAtMs = Date.now() + 600 * 1000;
  const expiresAtTs = Timestamp.fromMillis(expiresAtMs);
  const vehicleLabel = [driver.vehicleCategory?.toUpperCase(), driver.vehicleModel, driver.maskedPlate]
    .filter(Boolean)
    .join(" • ");

  // Atomic batch write satisfies Firestore `existsAfter(ride_assignments)` & `existsAfter(ride_otps)` rules
  const batch = writeBatch(db);
  batch.update(bookingRef, {
    status: "assigned",
    assignedDriverId: driver.driverId,
    assignedDriverName: driver.displayName,
    assignedVehicleLabel: vehicleLabel,
    agreedFareInr: lockedFare,
    otp_status: "pending",
    otp_expires_at_ms: expiresAtMs,
    assignedByOpsUid: currentUser.uid,
    opsAssignmentNote: `Assigned from Operations Portal (${activeRole})`,
    updatedAt: serverTimestamp()
  });

  batch.set(doc(db, "ride_assignments", booking.id), {
    bookingId: booking.id,
    customerId: booking.customerId,
    driverId: driver.driverId,
    offerId: "",
    lockedFareInr: lockedFare,
    status: "active",
    is_test_ride: Boolean(booking.is_test_ride || booking.test_mode),
    assignedByUid: currentUser.uid,
    assignedByRole: activeRole === PORTAL_ROLES.OWNER_ADMIN ? "admin" : "ops_staff",
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });

  batch.set(doc(db, "ride_otps", booking.id), {
    bookingId: booking.id,
    customerId: booking.customerId,
    otpHash,
    submittedHash: "",
    used: false,
    overridden: false,
    overrideReason: "",
    overriddenByUid: "",
    verifiedByUid: "",
    attempts: 0,
    maxAttempts: 5,
    lastAttemptAtMs: 0,
    expiresAt: expiresAtTs,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });

  batch.set(doc(db, "ride_otp_secrets", booking.id), {
    bookingId: booking.id,
    customerId: booking.customerId,
    otpPlaintext,
    isTestRide: Boolean(booking.is_test_ride || booking.test_mode),
    status: "pending",
    expiresAt: expiresAtTs,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });

  await batch.commit();

  await addDoc(collection(db, "audit_logs"), {
    actorId: currentUser.uid,
    actorEmail: currentUser.phoneNumber || "",
    action: "ops_assign_partner",
    targetCollection: "bookings",
    targetId: booking.id,
    summary: `Assigned partner ${driver.displayName} (${driver.driverId}) to booking ${booking.id}`,
    createdAt: serverTimestamp()
  });

  selectedAssignBookingId = null;
  renderAssignPanel(null);
}

function renderAssignPanel(booking) {
  const panel = document.getElementById("opsAssignPanel");
  if (!booking) {
    panel.classList.add("hidden");
    panel.innerHTML = "";
    return;
  }

  const verifiedOnline = state.driversDir.filter((d) => {
    const live = state.liveDriversRtdb.get(d.driverId);
    const isLive = live ? live.trackingStatus !== "stopped" : d.isOnline;
    return d.verificationStatus === "verified" && isLive;
  });

  panel.classList.remove("hidden");
  panel.innerHTML = `
    <div class="space-between" style="margin-bottom:8px;">
      <strong>Assign Partner for ${escapeHtml(booking.pickupLabel)} → ${escapeHtml(booking.destinationLabel)}</strong>
      <button id="cancelAssignBtn" type="button" class="btn btn-secondary">Close</button>
    </div>
    <div id="assignCandidateList" class="stack" style="gap:6px;"></div>
  `;
  document.getElementById("cancelAssignBtn").addEventListener("click", () => {
    selectedAssignBookingId = null;
    renderAssignPanel(null);
  });

  const candList = document.getElementById("assignCandidateList");
  if (!verifiedOnline.length) {
    candList.innerHTML = `<div class="list-item-muted">No verified online partners currently available.</div>`;
    return;
  }

  verifiedOnline.slice(0, 12).forEach((drv) => {
    const row = document.createElement("div");
    row.className = "space-between";
    row.style.padding = "6px 8px";
    row.style.background = "#fff";
    row.style.borderRadius = "8px";
    row.innerHTML = `
      <div>
        <strong>${escapeHtml(drv.displayName)}</strong>
        <span class="list-item-muted">(${escapeHtml(drv.vehicleCategory.toUpperCase())} • ${escapeHtml(drv.district)})</span>
      </div>
    `;
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "btn btn-primary";
    btn.textContent = "Assign";
    btn.addEventListener("click", async () => {
      btn.disabled = true;
      btn.textContent = "Assigning...";
      try {
        await assignPartnerToBooking(booking, drv);
      } catch (err) {
        alert(err?.message || "Assignment failed.");
      }
    });
    row.appendChild(btn);
    candList.appendChild(row);
  });
}

function renderAll() {
  const activeStates = new Set(["searching", "assigned", "arriving", "arrived", "in_progress"]);
  const activeBookings = state.bookings.filter((b) => activeStates.has(b.status));
  const searchingBookings = state.bookings.filter((b) => b.status === "searching");
  const delayedBookings = state.bookings.filter((b) => isBookingDelayed(b));
  const liveDrivers = Array.from(state.liveDriversRtdb.values()).filter((d) => d.trackingStatus !== "stopped");

  const openComplaints = state.complaints.filter((c) => c.status !== "resolved");
  const openDisputes = state.disputes.filter((d) => d.status === "open" || d.status === "under_review");
  const openEmergencies = state.emergencies.filter((e) => e.status !== "resolved");
  const totalEscalations = openComplaints.length + openDisputes.length + openEmergencies.length;

  document.getElementById("oActiveCount").textContent = String(activeBookings.length);
  document.getElementById("oSearchingCount").textContent = String(searchingBookings.length);
  document.getElementById("oDelayedCount").textContent = String(delayedBookings.length);
  document.getElementById("oDriversCount").textContent = String(liveDrivers.length);
  document.getElementById("oEscalationsCount").textContent = String(totalEscalations);

  const fStatus = document.getElementById("opsFilterStatus").value;
  const fCat = document.getElementById("opsFilterCategory").value;
  const fDist = document.getElementById("opsFilterDistrict").value;

  const filtered = state.bookings.filter((b) => {
    const okStatus = fStatus === "all"
      ? true
      : fStatus === "active"
        ? activeStates.has(b.status)
        : fStatus === "delayed"
          ? isBookingDelayed(b)
          : b.status === fStatus;
    const okCat = fCat === "all" || b.vehicleCategory === fCat;
    const okDist = fDist === "all" ||
      String(b.pickupDistrict || "").toLowerCase() === fDist.toLowerCase() ||
      String(b.destinationDistrict || "").toLowerCase() === fDist.toLowerCase();
    return okStatus && okCat && okDist;
  });

  if (opsMapCtrl) {
    opsMapCtrl.updateZones(state.zones);
    const markers = [];
    const routes = [];

    filtered.slice(0, 60).forEach((b) => {
      const delayed = isBookingDelayed(b) || b.opsEscalationFlag;
      if (b.pickupLat && b.pickupLng) {
        markers.push({
          id: `ops_p_${b.id}`,
          lat: b.pickupLat,
          lng: b.pickupLng,
          title: `${delayed ? "[ALERT] " : ""}${b.status.toUpperCase()}: ${b.pickupLabel}`,
          subtitle: `To ${b.destinationLabel} • ${b.vehicleCategory.toUpperCase()} • Driver: ${b.assignedDriverName || "Unassigned"}`,
          color: delayed ? "#DC2626" : b.status === "searching" ? "#F59E0B" : "#1565C0",
          radius: delayed ? 9 : 7
        });
      }
      if (b.pickupLat && b.pickupLng && b.destinationLat && b.destinationLng && activeStates.has(b.status)) {
        routes.push({
          id: `ops_r_${b.id}`,
          color: delayed ? "#DC2626" : b.status === "searching" ? "#F59E0B" : "#1565C0",
          coords: [
            [b.pickupLng, b.pickupLat],
            [b.destinationLng, b.destinationLat]
          ]
        });
      }
    });

    if (focusedRouteCoords.length >= 2) {
      routes.push({ id: "ops_focus", color: "#0D47A1", coords: focusedRouteCoords });
    }

    liveDrivers.forEach((d) => {
      const dir = state.driversDir.find((x) => x.driverId === d.driverId);
      markers.push({
        id: `ops_drv_${d.driverId}`,
        lat: d.lat,
        lng: d.lng,
        title: `${dir?.displayName || "Partner"} (${d.trackingStatus})`,
        subtitle: `${dir?.vehicleCategory?.toUpperCase() || "LIVE"} • Updated ${formatEpochTime(d.updatedAtEpochMs)}`,
        color: d.trackingStatus === "paused_gps_lost" ? "#DC2626" : "#15803D",
        radius: 8
      });
    });

    openEmergencies.filter((e) => e.lat && e.lng).forEach((e) => {
      markers.push({
        id: `ops_sos_${e.id}`,
        lat: e.lat,
        lng: e.lng,
        title: `SOS (${e.status.toUpperCase()})`,
        subtitle: e.notes || "Active emergency event",
        color: "#DC2626",
        radius: 10
      });
    });

    openComplaints.filter((c) => c.lat && c.lng).forEach((c) => {
      markers.push({
        id: `ops_cmp_${c.id}`,
        lat: c.lat,
        lng: c.lng,
        title: `Complaint: ${c.category}`,
        subtitle: c.locationLabel || c.description,
        color: "#DC2626",
        radius: 8
      });
    });

    openDisputes.filter((d) => d.lat && d.lng).forEach((d) => {
      markers.push({
        id: `ops_dsp_${d.id}`,
        lat: d.lat,
        lng: d.lng,
        title: `Dispute: ${d.category}`,
        subtitle: d.locationLabel || d.description,
        color: "#B91C1C",
        radius: 8
      });
    });

    opsMapCtrl.updateMarkers(markers);
    opsMapCtrl.updateRoutes(routes);
  }

  // Zones monitor
  const zonesEl = document.getElementById("opsZonesMonitor");
  if (!state.zones.length) {
    zonesEl.innerHTML = `<div class="list-item-muted">No service zones configured yet.</div>`;
  } else {
    zonesEl.innerHTML = state.zones.map((z) => `
      <div class="list-item space-between">
        <div>
          <strong>${escapeHtml(z.name)}</strong>
          <div class="list-item-muted">${escapeHtml(z.block || "")} • ${escapeHtml(z.district)} • PIN ${escapeHtml(z.pincode || "—")} • Pickup ${z.pickupEnabled !== false ? "ON" : "OFF"} / Drop ${z.dropoffEnabled !== false ? "ON" : "OFF"}</div>
        </div>
        <span class="badge ${z.isActive ? "badge-success" : "badge-muted"}">${z.isActive ? "ACTIVE" : "PAUSED"}</span>
      </div>
    `).join("");
  }

  // Bookings dispatch queue
  const queueEl = document.getElementById("opsBookingsQueue");
  if (!filtered.length) {
    queueEl.innerHTML = `<div class="list-item-muted">No bookings match the selected filters.</div>`;
  } else {
    queueEl.innerHTML = "";
    filtered.slice(0, 40).forEach((b) => {
      const delayed = isBookingDelayed(b);
      const item = document.createElement("div");
      item.className = "list-item stack";
      item.style.gap = "6px";
      item.innerHTML = `
        <div class="space-between">
          <div class="row" style="gap:6px;">
            <span class="badge ${b.status === "searching" ? "badge-warning" : "badge-info"}">${escapeHtml(b.status.toUpperCase())}</span>
            ${delayed ? `<span class="badge badge-danger">PICKUP DELAY &ge;10M</span>` : ""}
            ${b.opsEscalationFlag ? `<span class="badge badge-danger">ESCALATED</span>` : ""}
          </div>
          <span class="mono">₹${Math.round(b.agreedFareInr || b.estimatedFareInr || 0)}</span>
        </div>
        <div><strong>${escapeHtml(b.pickupLabel)}</strong> → ${escapeHtml(b.destinationLabel)}</div>
        <div class="list-item-muted">Customer: ${escapeHtml(b.customerName)} • Partner: ${escapeHtml(b.assignedDriverName || "Unassigned")}</div>
        ${b.opsDispatchNote ? `<div class="list-item-muted"><strong>Ops Note:</strong> ${escapeHtml(b.opsDispatchNote)}</div>` : ""}
      `;

      const row = document.createElement("div");
      row.className = "row";

      if (b.pickupLat && b.pickupLng) {
        const focusBtn = document.createElement("button");
        focusBtn.type = "button";
        focusBtn.className = "btn btn-secondary";
        focusBtn.textContent = "Focus Map";
        focusBtn.addEventListener("click", async () => {
          opsMapCtrl?.flyTo(b.pickupLat, b.pickupLng, 14);
          if (b.destinationLat && b.destinationLng) {
            const route = await fetchOsrmRoute(b.pickupLat, b.pickupLng, b.destinationLat, b.destinationLng);
            focusedRouteCoords = route.coords;
            renderAll();
          }
        });
        row.appendChild(focusBtn);
      }

      if (b.status === "searching") {
        const assignBtn = document.createElement("button");
        assignBtn.type = "button";
        assignBtn.className = "btn btn-primary";
        assignBtn.textContent = "Assign Partner";
        assignBtn.addEventListener("click", () => {
          selectedAssignBookingId = b.id;
          renderAssignPanel(b);
        });
        row.appendChild(assignBtn);
      }

      const noteBtn = document.createElement("button");
      noteBtn.type = "button";
      noteBtn.className = "btn btn-secondary";
      noteBtn.textContent = "Dispatch Note";
      noteBtn.addEventListener("click", async () => {
        const note = window.prompt("Operations dispatch note:", b.opsDispatchNote || "");
        if (note === null) return;
        await updateDoc(doc(db, "bookings", b.id), {
          opsDispatchNote: note.trim(),
          opsEscalationFlag: Boolean(b.opsEscalationFlag),
          opsUpdatedBy: currentUser.uid,
          updatedAt: serverTimestamp()
        });
        await addDoc(collection(db, "audit_logs"), {
          actorId: currentUser.uid,
          actorEmail: currentUser.phoneNumber || "",
          action: "ops_update_booking_note",
          targetCollection: "bookings",
          targetId: b.id,
          summary: `Updated dispatch note on ${b.id}: ${note.trim()}`,
          createdAt: serverTimestamp()
        });
      });
      row.appendChild(noteBtn);

      const escBtn = document.createElement("button");
      escBtn.type = "button";
      escBtn.className = b.opsEscalationFlag ? "btn btn-secondary" : "btn btn-danger";
      escBtn.textContent = b.opsEscalationFlag ? "Clear Flag" : "Escalate";
      escBtn.addEventListener("click", async () => {
        const nextFlag = !b.opsEscalationFlag;
        await updateDoc(doc(db, "bookings", b.id), {
          opsDispatchNote: b.opsDispatchNote || (nextFlag ? "Escalated by Operations" : ""),
          opsEscalationFlag: nextFlag,
          opsUpdatedBy: currentUser.uid,
          updatedAt: serverTimestamp()
        });
        await addDoc(collection(db, "audit_logs"), {
          actorId: currentUser.uid,
          actorEmail: currentUser.phoneNumber || "",
          action: "ops_escalate_booking",
          targetCollection: "bookings",
          targetId: b.id,
          summary: `${nextFlag ? "Flagged" : "Cleared escalation"} on booking ${b.id}`,
          createdAt: serverTimestamp()
        });
      });
      row.appendChild(escBtn);

      item.appendChild(row);
      queueEl.appendChild(item);
    });
  }

  if (selectedAssignBookingId) {
    const target = state.bookings.find((b) => b.id === selectedAssignBookingId && b.status === "searching");
    renderAssignPanel(target || null);
  }

  // Escalations list (SOS, Complaints, Disputes with location context)
  const escEl = document.getElementById("opsEscalationsList");
  const allEsc = [
    ...openEmergencies.map((e) => ({ kind: "SOS", id: e.id, title: e.notes || "Emergency SOS", status: e.status, lat: e.lat, lng: e.lng, label: e.emergencyContactRef || "", col: "emergency_events" })),
    ...openComplaints.map((c) => ({ kind: "COMPLAINT", id: c.id, title: `${c.category}: ${c.description}`, status: c.status, lat: c.lat, lng: c.lng, label: c.locationLabel || "", col: "complaints" })),
    ...openDisputes.map((d) => ({ kind: "DISPUTE", id: d.id, title: `${d.category}: ${d.description}`, status: d.status, lat: d.lat, lng: d.lng, label: d.locationLabel || "", col: "disputes" }))
  ];

  if (!allEsc.length) {
    escEl.innerHTML = `<div class="list-item-muted">No open complaints, disputes, or SOS alerts.</div>`;
  } else {
    escEl.innerHTML = "";
    allEsc.forEach((item) => {
      const div = document.createElement("div");
      div.className = "list-item stack";
      div.style.gap = "6px";
      div.innerHTML = `
        <div class="space-between">
          <span class="badge ${item.kind === "SOS" ? "badge-danger" : "badge-warning"}">${item.kind} • ${escapeHtml(item.status.toUpperCase())}</span>
          <span class="list-item-muted mono">${item.lat && item.lng ? `${Number(item.lat).toFixed(4)}, ${Number(item.lng).toFixed(4)}` : "No GPS"}</span>
        </div>
        <div>${escapeHtml(item.title)}</div>
        ${item.label ? `<div class="list-item-muted">Location: ${escapeHtml(item.label)}</div>` : ""}
      `;
      const row = document.createElement("div");
      row.className = "row";
      if (item.lat && item.lng) {
        const focusBtn = document.createElement("button");
        focusBtn.type = "button";
        focusBtn.className = "btn btn-secondary";
        focusBtn.textContent = "Show on Map";
        focusBtn.addEventListener("click", () => {
          opsMapCtrl?.flyTo(Number(item.lat), Number(item.lng), 15);
        });
        row.appendChild(focusBtn);
      }
      if (item.col === "complaints" && item.status === "open") {
        const invBtn = document.createElement("button");
        invBtn.type = "button";
        invBtn.className = "btn btn-primary";
        invBtn.textContent = "Mark Investigating";
        invBtn.addEventListener("click", async () => {
          await updateDoc(doc(db, "complaints", item.id), {
            status: "investigating",
            resolutionNote: `Operations team reviewing (${currentUser.uid})`,
            updatedAt: serverTimestamp()
          });
          await addDoc(collection(db, "audit_logs"), {
            actorId: currentUser.uid,
            action: "ops_triage_complaint",
            targetCollection: "complaints",
            targetId: item.id,
            summary: `Marked complaint ${item.id} investigating`,
            createdAt: serverTimestamp()
          });
        });
        row.appendChild(invBtn);
      }
      if (item.col === "complaints" && item.status === "investigating") {
        const resBtn = document.createElement("button");
        resBtn.type = "button";
        resBtn.className = "btn btn-success";
        resBtn.textContent = "Mark Resolved";
        resBtn.addEventListener("click", async () => {
          const note = window.prompt("Complaint resolution summary:", "Resolved by Operations Desk");
          if (!note) return;
          await updateDoc(doc(db, "complaints", item.id), {
            status: "resolved",
            resolutionNote: note.trim(),
            updatedAt: serverTimestamp()
          });
          await addDoc(collection(db, "audit_logs"), {
            actorId: currentUser.uid,
            action: "ops_triage_complaint",
            targetCollection: "complaints",
            targetId: item.id,
            summary: `Resolved complaint ${item.id}: ${note.trim()}`,
            createdAt: serverTimestamp()
          });
        });
        row.appendChild(resBtn);
      }
      if (item.col === "disputes" && item.status === "open") {
        const revBtn = document.createElement("button");
        revBtn.type = "button";
        revBtn.className = "btn btn-primary";
        revBtn.textContent = "Mark Under Review";
        revBtn.addEventListener("click", async () => {
          await updateDoc(doc(db, "disputes", item.id), {
            status: "under_review",
            adminResolutionNote: `Escalated by Operations (${currentUser.uid})`,
            updatedAt: serverTimestamp()
          });
          await addDoc(collection(db, "audit_logs"), {
            actorId: currentUser.uid,
            action: "ops_triage_dispute",
            targetCollection: "disputes",
            targetId: item.id,
            summary: `Marked dispute ${item.id} under review`,
            createdAt: serverTimestamp()
          });
        });
        row.appendChild(revBtn);
      }
      if (item.col === "emergency_events") {
        const sosBtn = document.createElement("button");
        sosBtn.type = "button";
        sosBtn.className = item.status === "active" ? "btn btn-primary" : "btn btn-success";
        sosBtn.textContent = item.status === "active" ? "Acknowledge SOS" : "Resolve SOS";
        sosBtn.addEventListener("click", async () => {
          const next = item.status === "active" ? "acknowledged" : "resolved";
          await updateDoc(doc(db, "emergency_events", item.id), {
            status: next,
            updatedAt: serverTimestamp()
          });
          await addDoc(collection(db, "audit_logs"), {
            actorId: currentUser.uid,
            action: "ops_resolve_sos",
            targetCollection: "emergency_events",
            targetId: item.id,
            summary: `Updated SOS ${item.id} -> ${next}`,
            createdAt: serverTimestamp()
          });
        });
        row.appendChild(sosBtn);
      }
      div.appendChild(row);
      escEl.appendChild(div);
    });
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
    onSnapshot(query(collection(db, "complaints"), orderBy("createdAt", "desc"), limit(50)), (snap) => {
      state.complaints = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderAll();
    })
  );

  unsubscribers.push(
    onSnapshot(query(collection(db, "disputes"), orderBy("createdAt", "desc"), limit(50)), (snap) => {
      state.disputes = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
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
    showGateError("Please sign in with Phone OTP to open the Operations Portal.");
    return;
  }
  activeRole = await resolvePortalRole(user);
  if (activeRole === PORTAL_ROLES.NONE) {
    showGateError("Access denied. Only Owner Admin or allowlisted Operations Staff can open this portal.");
    return;
  }

  opsGateAlert.classList.add("hidden");
  opsRoleBadge.classList.remove("hidden");
  opsRoleBadge.textContent = activeRole === PORTAL_ROLES.OWNER_ADMIN ? "OWNER (OPS VIEW)" : "OPERATIONS STAFF";
  ownerLinkBtn.classList.toggle("hidden", activeRole !== PORTAL_ROLES.OWNER_ADMIN);
  opsContent.classList.remove("hidden");

  if (!opsMapCtrl) {
    opsMapCtrl = createOsmMap("opsMap", {
      center: [BORAI_LAUNCH.lng, BORAI_LAUNCH.lat],
      zoom: BORAI_LAUNCH.zoom
    });
  }
  attachListeners();
});
