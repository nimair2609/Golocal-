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
  writeBatch,
  serverTimestamp,
  Timestamp
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
  generateOtpAndHash,
  escapeHtml,
  formatEpochTime,
  formatEpochDateTime,
  downloadCsvFile
} from "./firebase-config.js";
import {
  DEFAULT_FARE_CONFIGS,
  maskPlate,
  buildCsvContent
} from "./golocal-domain.js";
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
const ownerActionToast = document.getElementById("ownerActionToast");

let mainMapCtrl = null;
let zoneMapCtrl = null;
let currentUser = null;
let polygonDraft = [];
let selectedRouteCoords = [];
let ownerAssignBookingId = null;
const unsubscribers = [];

const state = {
  bookings: [],
  driversDir: [],
  driverProfiles: [],
  users: [],
  fareConfigs: [],
  payments: [],
  settlements: [],
  refunds: [],
  complaints: [],
  disputes: [],
  platformSettings: null,
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

function showToast(msg, tone = "success") {
  if (!msg) {
    ownerActionToast.className = "alert hidden";
    ownerActionToast.textContent = "";
    return;
  }
  ownerActionToast.className = `alert alert-${tone}`;
  ownerActionToast.textContent = msg;
}

function clearSubscriptions() {
  while (unsubscribers.length) {
    const fn = unsubscribers.pop();
    try { fn(); } catch (_) {}
  }
}

ownerSignOutBtn.addEventListener("click", async () => {
  clearSubscriptions();
  await logoutPortal();
  window.location.href = "./index.html";
});

// Tabs
const ALL_TABS = [
  "mapTab",
  "partnersTab",
  "customersTab",
  "pricingTab",
  "financialsTab",
  "zonesTab",
  "opsAccessTab"
];
document.querySelectorAll(".tab-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".tab-btn").forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    const target = btn.dataset.tab;
    ALL_TABS.forEach((id) => {
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

// Filters & Place Search
["filterStatus", "filterCategory", "filterDistrict"].forEach((id) => {
  document.getElementById(id).addEventListener("change", renderAll);
});
document.getElementById("kycStatusFilter").addEventListener("change", renderPartnersTab);
document.getElementById("custSearchInput").addEventListener("input", renderCustomersTab);

document.getElementById("closeOwnerDocModalBtn").addEventListener("click", () => {
  document.getElementById("ownerDocModal").classList.add("hidden");
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

// Zone Map Click & Form Controls
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

// Operations Staff Form
document.getElementById("opsForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!currentUser) return;
  const uid = document.getElementById("opsUid").value.trim();
  const displayName = document.getElementById("opsName").value.trim();
  const contactIdentifier = document.getElementById("opsPhone").value.trim();
  const assignedDistrict = document.getElementById("opsDistrict").value.trim();
  const isActive = document.getElementById("opsActive").checked;
  if (!uid || displayName.length < 2 || assignedDistrict.length < 2) {
    showToast("Enter valid UID, Display Name (min 2 chars), and District (min 2 chars).", "error");
    return;
  }
  const existing = state.opsStaff.find((x) => x.userId === uid || x.id === uid);
  try {
    await setDoc(doc(db, "operations_staff", uid), {
      userId: uid,
      displayName,
      contactIdentifier,
      assignedDistrict,
      isActive,
      grantedBy: currentUser.uid,
      grantedByEmail: currentUser.email || currentUser.phoneNumber || "",
      revokedBy: isActive ? "" : currentUser.uid,
      createdAt: existing?.createdAt || serverTimestamp(),
      updatedAt: serverTimestamp()
    }, { merge: true });

    if (isActive) {
      await rtdbSet(rtdbRef(rtdb, `auth_roles/ops/${uid}`), { uid, updatedAt: Date.now() });
    } else {
      await rtdbRemove(rtdbRef(rtdb, `auth_roles/ops/${uid}`));
    }

    await recordAudit("upsert_ops_staff", "operations_staff", uid, `${isActive ? "Enabled" : "Disabled"} Operations access for ${displayName}`);
    document.getElementById("opsForm").reset();
    document.getElementById("opsDistrict").value = BORAI_LAUNCH.district;
    showToast(`Operations Staff "${displayName}" saved (${isActive ? "ACTIVE" : "REVOKED"}).`, "success");
  } catch (err) {
    showToast(`Failed saving Operations Staff: ${err?.message}`, "error");
  }
});

// Fare Config Form
document.getElementById("fCat").addEventListener("change", () => {
  populateFareFormForCategory(document.getElementById("fCat").value);
});

function populateFareFormForCategory(cat) {
  const existing = state.fareConfigs.find((f) => f.id === cat || f.vehicleCategory === cat);
  const dflt = DEFAULT_FARE_CONFIGS[cat] || {};
  const src = existing || dflt;
  document.getElementById("fBase").value = src.baseFareInr ?? 25;
  document.getElementById("fPerKm").value = src.perKmRateInr ?? 12;
  document.getElementById("fMin").value = src.minimumFareInr ?? 25;
  document.getElementById("fWait").value = src.waitingChargePerMinInr ?? 1;
  document.getElementById("fCancel").value = src.cancellationFeeInr ?? 15;
  document.getElementById("fGst").value = src.gstPercent ?? 5;
  document.getElementById("fComm").value = src.commissionPercent ?? 12;
  document.getElementById("fMinBid").value = src.minBidFactor ?? 0.9;
  document.getElementById("fMaxBid").value = src.maxBidFactor ?? 1.2;
  document.getElementById("fBidAllow").checked = src.allowDriverFareProposal !== false;
  document.getElementById("fEnabled").checked = src.isEnabled !== false;
}

document.getElementById("fareForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!currentUser) return;
  const cat = document.getElementById("fCat").value;
  const existing = state.fareConfigs.find((f) => f.id === cat || f.vehicleCategory === cat);
  const nextVersion = Number(existing?.version || 0) + 1;
  try {
    await setDoc(doc(db, "fare_configs", cat), {
      vehicleCategory: cat,
      version: nextVersion,
      baseFareInr: Number(document.getElementById("fBase").value),
      perKmRateInr: Number(document.getElementById("fPerKm").value),
      minimumFareInr: Number(document.getElementById("fMin").value),
      waitingChargePerMinInr: Number(document.getElementById("fWait").value || 0),
      cancellationFeeInr: Number(document.getElementById("fCancel").value || 0),
      gstPercent: Number(document.getElementById("fGst").value),
      commissionPercent: Number(document.getElementById("fComm").value),
      minBidFactor: Number(document.getElementById("fMinBid").value),
      maxBidFactor: Number(document.getElementById("fMaxBid").value),
      allowDriverFareProposal: document.getElementById("fBidAllow").checked,
      isEnabled: document.getElementById("fEnabled").checked,
      updatedBy: currentUser.uid,
      createdAt: existing?.createdAt || serverTimestamp(),
      updatedAt: serverTimestamp()
    }, { merge: true });
    await recordAudit("update_fare_config", "fare_configs", cat, `Published ${cat.toUpperCase()} fare v${nextVersion}`);
    showToast(`Published ${cat.toUpperCase()} fare configuration (v${nextVersion}).`, "success");
  } catch (err) {
    showToast(`Fare update failed: ${err?.message}`, "error");
  }
});

// Platform Settings Form
function populatePlatformSettingsForm() {
  const s = state.platformSettings;
  if (!s) return;
  document.getElementById("pHelpline").value = s.helplineNumber || "";
  document.getElementById("pCommPct").value = s.defaultCommissionPct ?? 12;
  document.getElementById("pMaint").value = s.maintenanceNotice || "";
  document.getElementById("pPromoTitle").value = s.promoBannerTitle || "";
  document.getElementById("pPromoText").value = s.promoBannerText || "";
  document.getElementById("pPromoCode").value = s.promoCode || "";
  document.getElementById("pPromoDisc").value = s.promoDiscountPct ?? 0;
  document.getElementById("pGrievName").value = s.grievanceOfficerName || "";
  document.getElementById("pGrievEmail").value = s.grievanceOfficerEmail || "";
  document.getElementById("pCancelSummary").value = s.cancellationPolicySummary || "";
  document.getElementById("pTestMode").checked = Boolean(s.testRideModeEnabled);
  document.getElementById("pBikeCommercial").checked = Boolean(s.bikeTaxiCommercialApproved);
}

document.getElementById("platformSettingsForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!currentUser) return;
  try {
    await setDoc(doc(db, "platform_settings", "global"), {
      helplineNumber: document.getElementById("pHelpline").value.trim(),
      defaultCommissionPct: Number(document.getElementById("pCommPct").value || 12),
      maintenanceNotice: document.getElementById("pMaint").value.trim(),
      promoBannerTitle: document.getElementById("pPromoTitle").value.trim(),
      promoBannerText: document.getElementById("pPromoText").value.trim(),
      promoCode: document.getElementById("pPromoCode").value.trim().toUpperCase(),
      promoDiscountPct: Number(document.getElementById("pPromoDisc").value || 0),
      grievanceOfficerName: document.getElementById("pGrievName").value.trim(),
      grievanceOfficerEmail: document.getElementById("pGrievEmail").value.trim(),
      cancellationPolicySummary: document.getElementById("pCancelSummary").value.trim(),
      testRideModeEnabled: document.getElementById("pTestMode").checked,
      bikeTaxiCommercialApproved: document.getElementById("pBikeCommercial").checked,
      updatedAt: serverTimestamp()
    }, { merge: true });
    await recordAudit("update_platform_settings", "platform_settings", "global", "Updated global platform settings & promotions");
    showToast("Global platform settings saved.", "success");
  } catch (err) {
    showToast(`Failed to update settings: ${err?.message}`, "error");
  }
});

// CSV Exports
document.getElementById("exportBookingsCsvBtn").addEventListener("click", () => {
  const csv = buildCsvContent(
    ["Booking ID", "Status", "Category", "Pickup", "Drop", "Customer", "Driver", "Fare (INR)", "Payment Method", "Payment Status"],
    state.bookings.map((b) => [
      b.id, b.status, b.vehicleCategory, b.pickupLabel, b.destinationLabel,
      b.customerName, b.assignedDriverName || "", b.agreedFareInr || b.estimatedFareInr,
      b.paymentMethod, b.paymentStatus
    ])
  );
  downloadCsvFile(`golocal_bookings_${Date.now()}.csv`, csv);
});

document.getElementById("exportPartnersCsvBtn").addEventListener("click", () => {
  const csv = buildCsvContent(
    ["Driver UID", "Legal Name", "Phone", "Category", "Reg No", "District", "KYC Status", "Online", "Completed Rides"],
    state.driverProfiles.map((p) => [
      p.driverId, p.legalName, p.phone, p.vehicleCategory, p.vehicleRegNumber,
      p.district, p.verificationStatus, p.isOnline ? "Yes" : "No", p.completedRides || 0
    ])
  );
  downloadCsvFile(`golocal_partners_${Date.now()}.csv`, csv);
});

document.getElementById("exportFaresCsvBtn").addEventListener("click", () => {
  const csv = buildCsvContent(
    ["Category", "Version", "Base Fare", "Per Km", "Min Fare", "Waiting/min", "Cancel Fee", "GST %", "Commission %", "Enabled"],
    state.fareConfigs.map((f) => [
      f.vehicleCategory, f.version, f.baseFareInr, f.perKmRateInr, f.minimumFareInr,
      f.waitingChargePerMinInr || 0, f.cancellationFeeInr || 0, f.gstPercent, f.commissionPercent,
      f.isEnabled ? "Yes" : "No"
    ])
  );
  downloadCsvFile(`golocal_fare_configs_${Date.now()}.csv`, csv);
});

document.getElementById("exportPaymentsCsvBtn").addEventListener("click", () => {
  const csv = buildCsvContent(
    ["Booking ID", "Customer ID", "Driver ID", "Gross INR", "GST INR", "Commission INR", "Net Driver INR", "Method", "Status", "Gateway Ref"],
    state.payments.map((p) => [
      p.bookingId, p.customerId, p.driverId, p.grossFareInr, p.gstInr,
      p.platformCommissionInr, p.netDriverEarningsInr, p.paymentMethod, p.status, p.gatewayReference || ""
    ])
  );
  downloadCsvFile(`golocal_payments_${Date.now()}.csv`, csv);
});

document.getElementById("exportAuditCsvBtn").addEventListener("click", () => {
  const csv = buildCsvContent(
    ["Timestamp", "Action", "Collection", "Target ID", "Actor ID", "Summary"],
    state.auditLogs.map((a) => [
      formatEpochDateTime(a.createdAt), a.action, a.targetCollection, a.targetId, a.actorId, a.summary
    ])
  );
  downloadCsvFile(`golocal_audit_logs_${Date.now()}.csv`, csv);
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

// Partner KYC Decision Handler
async function reviewPartnerKyc(partner, decision) {
  if (!currentUser || !partner) return;
  let rejectionReason = "";
  if (decision === "rejected" || decision === "suspended") {
    const promptMsg = decision === "rejected"
      ? "Enter mandatory reason for KYC rejection (shown to partner):"
      : "Enter reason for partner suspension:";
    const input = window.prompt(promptMsg, "");
    if (!input || input.trim().length < 3) {
      showToast("A clear reason (min 3 chars) is required for rejection/suspension.", "warning");
      return;
    }
    rejectionReason = input.trim();
  }

  const profileRef = doc(db, "driver_profiles", partner.driverId);
  const dirRef = doc(db, "driver_directory", partner.driverId);
  const nextOnline = decision === "verified" ? Boolean(partner.isOnline) : false;

  try {
    await updateDoc(profileRef, {
      verificationStatus: decision,
      rejectionReason,
      reviewedBy: currentUser.uid,
      reviewNote: `Reviewed by Owner Admin (${decision})`,
      verificationVersion: Number(partner.verificationVersion || 0) + 1,
      isOnline: nextOnline,
      updatedAt: serverTimestamp()
    });

    await setDoc(dirRef, {
      driverId: partner.driverId,
      displayName: partner.legalName || "Partner",
      vehicleCategory: partner.vehicleCategory || "toto",
      vehicleModel: partner.vehicleModel || "",
      maskedPlate: maskPlate(partner.vehicleRegNumber),
      district: partner.district || BORAI_LAUNCH.district,
      block: partner.block || "",
      verificationStatus: decision,
      isOnline: nextOnline,
      isTestPartner: Boolean(partner.isTestPartner),
      ratingAvg: Number(partner.ratingAvg ?? 5.0),
      completedRides: Number(partner.completedRides ?? 0),
      updatedAt: serverTimestamp()
    }, { merge: true });

    await addDoc(collection(db, "driver_verification_history"), {
      driverId: partner.driverId,
      reviewerId: currentUser.uid,
      decision,
      reason: rejectionReason,
      createdAt: serverTimestamp()
    });

    await addDoc(collection(db, "notifications"), {
      userId: partner.driverId,
      title: decision === "verified" ? "Partner KYC Approved" : `Partner KYC ${decision.toUpperCase()}`,
      body: decision === "verified"
        ? "Your documents are verified! You can now go ONLINE to accept commercial rides."
        : (rejectionReason || `Your partner status is now ${decision}.`),
      type: "kyc_decision",
      entityId: partner.driverId,
      targetApp: "rider",
      read: false,
      createdAt: serverTimestamp()
    });

    await recordAudit("kyc_decision", "driver_profiles", partner.driverId, `Set ${partner.legalName} (${partner.driverId}) KYC = ${decision}${rejectionReason ? `: ${rejectionReason}` : ""}`);
    showToast(`Updated ${partner.legalName} KYC status to ${decision.toUpperCase()}.`, "success");
  } catch (err) {
    showToast(`KYC update failed: ${err?.message}`, "error");
  }
}

function openDocModal(title, url) {
  const modal = document.getElementById("ownerDocModal");
  document.getElementById("ownerDocModalTitle").textContent = title;
  const body = document.getElementById("ownerDocModalBody");
  const isPdf = /\.pdf(\?|$)/i.test(url);
  body.innerHTML = isPdf
    ? `<p>PDF document hosted on Firebase Storage:</p><a class="btn btn-primary" href="${escapeHtml(url)}" target="_blank" rel="noopener">Open PDF in New Tab</a>`
    : `<img src="${escapeHtml(url)}" alt="${escapeHtml(title)}" style="max-width:100%;border-radius:10px;border:1px solid var(--border);" /><a class="btn btn-secondary" href="${escapeHtml(url)}" target="_blank" rel="noopener">Open Full Resolution</a>`;
  modal.classList.remove("hidden");
}

function renderPartnersTab() {
  const filter = document.getElementById("kycStatusFilter").value;
  const listEl = document.getElementById("partnersKycList");
  const filtered = state.driverProfiles.filter((p) => filter === "all" || p.verificationStatus === filter);

  if (!filtered.length) {
    listEl.innerHTML = `<div class="list-item-muted">No driver partners match "${escapeHtml(filter)}".</div>`;
    return;
  }

  listEl.innerHTML = "";
  filtered.forEach((p) => {
    const st = p.verificationStatus || "pending";
    const card = document.createElement("div");
    card.className = "list-item stack";
    card.style.gap = "8px";

    const docs = [
      ["Driving Licence", p.dlPhotoUri || p.documentPhotoUri],
      ["RC Certificate", p.rcPhotoUri],
      ["Insurance", p.insurancePhotoUri],
      ["Permit / Vehicle", p.permitPhotoUri],
      ["Fitness", p.fitnessPhotoUri],
      ["PUC", p.pucPhotoUri]
    ].filter(([, url]) => Boolean(url && String(url).startsWith("http")));

    card.innerHTML = `
      <div class="space-between">
        <div>
          <strong>${escapeHtml(p.legalName)}</strong>
          <span class="badge badge-info">${escapeHtml((p.vehicleCategory || "").toUpperCase())}</span>
          <span class="mono list-item-muted">${escapeHtml(p.vehicleRegNumber)}</span>
        </div>
        <span class="badge ${st === "verified" ? "badge-success" : st === "rejected" || st === "suspended" ? "badge-danger" : "badge-warning"}">${escapeHtml(st.toUpperCase())}</span>
      </div>
      <div class="list-item-muted">
        Phone: ${escapeHtml(p.phone)} • District: ${escapeHtml(p.district)}${p.block ? ` (${escapeHtml(p.block)})` : ""} • UID: <span class="mono">${escapeHtml(p.driverId)}</span>
      </div>
      <div class="list-item-muted">
        DL: <strong>${escapeHtml(p.drivingLicenceNumber)}</strong> • RC: <strong>${escapeHtml(p.rcNumber || "—")}</strong> • Insurance: <strong>${escapeHtml(p.insuranceNumber || "—")}</strong> • UPI: <strong>${escapeHtml(p.settlementUpiOrBank || "—")}</strong>
      </div>
      ${p.rejectionReason ? `<div class="alert alert-warning">Reason: ${escapeHtml(p.rejectionReason)}</div>` : ""}
    `;

    if (docs.length) {
      const docRow = document.createElement("div");
      docRow.className = "row";
      docs.forEach(([label, url]) => {
        const b = document.createElement("button");
        b.type = "button";
        b.className = "btn btn-secondary";
        b.textContent = `View ${label}`;
        b.onclick = () => openDocModal(`${p.legalName} — ${label}`, url);
        docRow.appendChild(b);
      });
      card.appendChild(docRow);
    } else {
      const note = document.createElement("div");
      note.className = "list-item-muted";
      note.textContent = "No uploaded Storage photo URLs attached yet.";
      card.appendChild(note);
    }

    const actions = document.createElement("div");
    actions.className = "row";
    if (st !== "verified") {
      const appBtn = document.createElement("button");
      appBtn.type = "button";
      appBtn.className = "btn btn-success";
      appBtn.textContent = "Approve KYC";
      appBtn.onclick = () => reviewPartnerKyc(p, "verified");
      actions.appendChild(appBtn);
    }
    if (st !== "rejected") {
      const rejBtn = document.createElement("button");
      rejBtn.type = "button";
      rejBtn.className = "btn btn-danger";
      rejBtn.textContent = "Reject KYC";
      rejBtn.onclick = () => reviewPartnerKyc(p, "rejected");
      actions.appendChild(rejBtn);
    }
    if (st === "verified") {
      const suspBtn = document.createElement("button");
      suspBtn.type = "button";
      suspBtn.className = "btn btn-danger";
      suspBtn.textContent = "Suspend Partner";
      suspBtn.onclick = () => reviewPartnerKyc(p, "suspended");
      actions.appendChild(suspBtn);
    }
    card.appendChild(actions);
    listEl.appendChild(card);
  });
}

function renderCustomersTab() {
  const q = (document.getElementById("custSearchInput").value || "").trim().toLowerCase();
  const custEl = document.getElementById("customersList");
  const filteredUsers = state.users.filter((u) => {
    if (!q) return true;
    return String(u.fullName || "").toLowerCase().includes(q) ||
      String(u.phone || "").toLowerCase().includes(q) ||
      String(u.email || "").toLowerCase().includes(q);
  });

  if (!filteredUsers.length) {
    custEl.innerHTML = `<div class="list-item-muted">No matching user accounts.</div>`;
  } else {
    custEl.innerHTML = "";
    filteredUsers.slice(0, 80).forEach((u) => {
      const isRestricted = u.accountStatus === "restricted";
      const div = document.createElement("div");
      div.className = "list-item space-between";
      div.innerHTML = `
        <div>
          <strong>${escapeHtml(u.fullName)}</strong> <span class="badge badge-info">${escapeHtml(String(u.role || "customer").toUpperCase())}</span>
          <div class="list-item-muted">${escapeHtml(u.phone || "—")} • ${escapeHtml(u.email || "No email")}</div>
          <div class="list-item-muted mono">UID: ${escapeHtml(u.userId || u.id)}</div>
        </div>
      `;
      const right = document.createElement("div");
      right.className = "row";
      const badge = document.createElement("span");
      badge.className = `badge ${isRestricted ? "badge-danger" : "badge-success"}`;
      badge.textContent = String(u.accountStatus || "active").toUpperCase();
      right.appendChild(badge);

      const toggleBtn = document.createElement("button");
      toggleBtn.type = "button";
      toggleBtn.className = `btn ${isRestricted ? "btn-success" : "btn-danger"}`;
      toggleBtn.textContent = isRestricted ? "Reactivate" : "Restrict";
      toggleBtn.onclick = async () => {
        const nextStatus = isRestricted ? "active" : "restricted";
        await updateDoc(doc(db, "users", u.id), {
          accountStatus: nextStatus,
          updatedAt: serverTimestamp()
        });
        await recordAudit("update_user_status", "users", u.id, `Set ${u.fullName} accountStatus=${nextStatus}`);
        showToast(`User ${u.fullName} set to ${nextStatus.toUpperCase()}.`, "info");
      };
      right.appendChild(toggleBtn);
      div.appendChild(right);
      custEl.appendChild(div);
    });
  }

  // Complaints, Disputes & Emergencies
  const dispEl = document.getElementById("ownerDisputesList");
  const allItems = [
    ...state.emergencies.map((e) => ({ col: "emergency_events", kind: "SOS", id: e.id, status: e.status, title: e.notes || "Emergency SOS", sub: `User ${e.userId}` })),
    ...state.complaints.map((c) => ({ col: "complaints", kind: "COMPLAINT", id: c.id, status: c.status, title: `${c.category}: ${c.description}`, sub: c.resolutionNote || "" })),
    ...state.disputes.map((d) => ({ col: "disputes", kind: "DISPUTE", id: d.id, status: d.status, title: `${d.category}: ${d.description}`, sub: d.adminResolutionNote || "" }))
  ];
  if (!allItems.length) {
    dispEl.innerHTML = `<div class="list-item-muted">No complaints, disputes, or SOS events.</div>`;
  } else {
    dispEl.innerHTML = "";
    allItems.slice(0, 60).forEach((item) => {
      const done = item.status === "resolved" || item.status === "resolved_closed";
      const div = document.createElement("div");
      div.className = "list-item stack";
      div.style.gap = "6px";
      div.innerHTML = `
        <div class="space-between">
          <span class="badge ${item.kind === "SOS" ? "badge-danger" : "badge-warning"}">${item.kind} • ${escapeHtml(item.status.toUpperCase())}</span>
          <span class="list-item-muted mono">${escapeHtml(item.id.slice(0, 8))}</span>
        </div>
        <div>${escapeHtml(item.title)}</div>
        ${item.sub ? `<div class="list-item-muted">${escapeHtml(item.sub)}</div>` : ""}
      `;
      if (!done) {
        const resBtn = document.createElement("button");
        resBtn.type = "button";
        resBtn.className = "btn btn-success";
        resBtn.style.alignSelf = "flex-start";
        resBtn.textContent = "Resolve & Close";
        resBtn.onclick = async () => {
          const note = window.prompt("Resolution note:", "Resolved by Owner Admin");
          if (!note) return;
          if (item.col === "disputes") {
            await updateDoc(doc(db, "disputes", item.id), {
              status: "resolved_closed",
              adminResolutionNote: note.trim(),
              updatedAt: serverTimestamp()
            });
          } else if (item.col === "complaints") {
            await updateDoc(doc(db, "complaints", item.id), {
              status: "resolved",
              resolutionNote: note.trim(),
              updatedAt: serverTimestamp()
            });
          } else {
            await updateDoc(doc(db, "emergency_events", item.id), {
              status: "resolved",
              notes: note.trim(),
              updatedAt: serverTimestamp()
            });
          }
          await recordAudit("resolve_escalation", item.col, item.id, `${item.kind} resolved: ${note.trim()}`);
        };
        div.appendChild(resBtn);
      }
      dispEl.appendChild(div);
    });
  }
}

function renderPricingTab() {
  const el = document.getElementById("fareConfigsList");
  const cats = ["toto", "auto", "bike", "cab", "outstation", "scheduled"];
  const byCat = {};
  state.fareConfigs.forEach((f) => { byCat[f.vehicleCategory || f.id] = f; });

  el.innerHTML = "";
  cats.forEach((cat) => {
    const f = byCat[cat] || { ...DEFAULT_FARE_CONFIGS[cat], vehicleCategory: cat, version: 0, isEnabled: true };
    const div = document.createElement("div");
    div.className = "list-item space-between";
    div.innerHTML = `
      <div>
        <strong>${escapeHtml(cat.toUpperCase())}</strong> <span class="badge badge-info">v${f.version || 0}</span>
        <div class="list-item-muted">Base ₹${f.baseFareInr} • ₹${f.perKmRateInr}/km • Min ₹${f.minimumFareInr} • GST ${f.gstPercent}% • Comm ${f.commissionPercent}%</div>
      </div>
      <div class="row">
        <span class="badge ${f.isEnabled !== false ? "badge-success" : "badge-muted"}">${f.isEnabled !== false ? "ENABLED" : "DISABLED"}</span>
        <button type="button" class="btn btn-secondary">Edit</button>
      </div>
    `;
    div.querySelector("button").onclick = () => {
      document.getElementById("fCat").value = cat;
      populateFareFormForCategory(cat);
    };
    el.appendChild(div);
  });
}

function renderFinancialsTab() {
  // Settlements
  const setEl = document.getElementById("ownerSettlementsList");
  if (!state.settlements.length) {
    setEl.innerHTML = `<div class="list-item-muted">No withdrawal requests submitted yet.</div>`;
  } else {
    setEl.innerHTML = "";
    state.settlements.forEach((s) => {
      const div = document.createElement("div");
      div.className = "list-item stack";
      div.style.gap = "6px";
      div.innerHTML = `
        <div class="space-between">
          <strong>${escapeHtml(s.driverName)} — <span class="mono">₹${Math.round(s.amountInr)}</span></strong>
          <span class="badge ${s.status === "paid" ? "badge-success" : s.status === "rejected" ? "badge-danger" : "badge-warning"}">${escapeHtml(s.status.toUpperCase())}</span>
        </div>
        <div class="list-item-muted">Payout Dest: ${escapeHtml(s.upiOrBankRef)} • ${escapeHtml(formatEpochDateTime(s.createdAt))}</div>
      `;
      if (s.status === "requested" || s.status === "processing") {
        const row = document.createElement("div");
        row.className = "row";
        if (s.status === "requested") {
          const procBtn = document.createElement("button");
          procBtn.className = "btn btn-secondary";
          procBtn.textContent = "Mark Processing";
          procBtn.onclick = async () => {
            await updateDoc(doc(db, "driver_settlements", s.id), { status: "processing", updatedAt: serverTimestamp() });
            await recordAudit("settlement_processing", "driver_settlements", s.id, `Processing ₹${s.amountInr} for ${s.driverName}`);
          };
          row.appendChild(procBtn);
        }
        const paidBtn = document.createElement("button");
        paidBtn.className = "btn btn-success";
        paidBtn.textContent = "Mark Paid";
        paidBtn.onclick = async () => {
          await updateDoc(doc(db, "driver_settlements", s.id), { status: "paid", updatedAt: serverTimestamp() });
          await recordAudit("settlement_paid", "driver_settlements", s.id, `Paid ₹${s.amountInr} to ${s.driverName}`);
        };
        const rejBtn = document.createElement("button");
        rejBtn.className = "btn btn-danger";
        rejBtn.textContent = "Reject";
        rejBtn.onclick = async () => {
          await updateDoc(doc(db, "driver_settlements", s.id), { status: "rejected", updatedAt: serverTimestamp() });
          await recordAudit("settlement_rejected", "driver_settlements", s.id, `Rejected ₹${s.amountInr} for ${s.driverName}`);
        };
        row.append(paidBtn, rejBtn);
        div.appendChild(row);
      }
      setEl.appendChild(div);
    });
  }

  // Refunds
  const refEl = document.getElementById("ownerRefundsList");
  if (!state.refunds.length) {
    refEl.innerHTML = `<div class="list-item-muted">No refund requests submitted yet.</div>`;
  } else {
    refEl.innerHTML = "";
    state.refunds.forEach((r) => {
      const div = document.createElement("div");
      div.className = "list-item stack";
      div.style.gap = "6px";
      div.innerHTML = `
        <div class="space-between">
          <strong>Booking ${escapeHtml(r.bookingId.slice(0, 8))} — <span class="mono">₹${Math.round(r.amountInr)}</span></strong>
          <span class="badge ${r.status === "processed" ? "badge-success" : r.status === "rejected" ? "badge-danger" : "badge-warning"}">${escapeHtml(r.status.toUpperCase())}</span>
        </div>
        <div class="list-item-muted">${escapeHtml(r.reason)}</div>
      `;
      if (r.status === "requested" || r.status === "approved") {
        const row = document.createElement("div");
        row.className = "row";
        const procBtn = document.createElement("button");
        procBtn.className = "btn btn-success";
        procBtn.textContent = "Mark Processed";
        procBtn.onclick = async () => {
          await updateDoc(doc(db, "refunds", r.id), { status: "processed", updatedAt: serverTimestamp() });
          await recordAudit("refund_processed", "refunds", r.id, `Processed ₹${r.amountInr} refund for booking ${r.bookingId}`);
        };
        const rejBtn = document.createElement("button");
        rejBtn.className = "btn btn-danger";
        rejBtn.textContent = "Reject";
        rejBtn.onclick = async () => {
          await updateDoc(doc(db, "refunds", r.id), { status: "rejected", updatedAt: serverTimestamp() });
          await recordAudit("refund_rejected", "refunds", r.id, `Rejected refund for booking ${r.bookingId}`);
        };
        row.append(procBtn, rejBtn);
        div.appendChild(row);
      }
      refEl.appendChild(div);
    });
  }

  // Payments Ledger
  const payEl = document.getElementById("ownerPaymentsList");
  if (!state.payments.length) {
    payEl.innerHTML = `<div class="list-item-muted">No completed payment ledger entries yet.</div>`;
  } else {
    payEl.innerHTML = state.payments.map((p) => `
      <div class="list-item space-between">
        <div>
          <strong>Booking ${escapeHtml(p.bookingId.slice(0, 8))}</strong> (${escapeHtml((p.paymentMethod || "").toUpperCase())})
          <div class="list-item-muted">Gross ₹${Number(p.grossFareInr || 0).toFixed(2)} • GST ₹${Number(p.gstInr || 0).toFixed(2)} • Platform Fee ₹${Number(p.platformCommissionInr || 0).toFixed(2)} • Partner Net ₹${Number(p.netDriverEarningsInr || 0).toFixed(2)}</div>
          ${p.gatewayReference ? `<div class="list-item-muted mono">Gateway Ref: ${escapeHtml(p.gatewayReference)}</div>` : ""}
        </div>
        <span class="badge ${p.status === "paid" || p.status === "settled_cash" ? "badge-success" : "badge-warning"}">${escapeHtml(String(p.status || "").toUpperCase())}</span>
      </div>
    `).join("");
  }
}

function renderOwnerAssignBox(booking) {
  const box = document.getElementById("ownerAssignBox");
  if (!booking) {
    box.classList.add("hidden");
    box.innerHTML = "";
    return;
  }
  const verifiedOnline = state.driverProfiles.filter((d) => d.verificationStatus === "verified");
  box.classList.remove("hidden");
  box.innerHTML = `
    <div class="space-between" style="margin-bottom:6px;">
      <strong>Admin Assign Partner: ${escapeHtml(booking.pickupLabel)}</strong>
      <button id="closeOwnerAssignBtn" class="btn btn-secondary" type="button">Close</button>
    </div>
    <div id="ownerAssignCandidates" class="stack" style="gap:6px;"></div>
  `;
  document.getElementById("closeOwnerAssignBtn").onclick = () => {
    ownerAssignBookingId = null;
    renderOwnerAssignBox(null);
  };
  const cand = document.getElementById("ownerAssignCandidates");
  if (!verifiedOnline.length) {
    cand.innerHTML = `<div class="list-item-muted">No verified commercial partners available.</div>`;
    return;
  }
  verifiedOnline.slice(0, 10).forEach((drv) => {
    const row = document.createElement("div");
    row.className = "space-between";
    row.style.background = "#fff";
    row.style.padding = "6px 8px";
    row.style.borderRadius = "8px";
    row.innerHTML = `<span><strong>${escapeHtml(drv.legalName)}</strong> (${escapeHtml(drv.vehicleCategory.toUpperCase())} • ${drv.isOnline ? "ONLINE" : "offline"})</span>`;
    const btn = document.createElement("button");
    btn.className = "btn btn-primary";
    btn.textContent = "Assign";
    btn.onclick = async () => {
      const lockedFare = Math.max(Number(booking.minPermittedFareInr || 0), Number(booking.agreedFareInr || booking.estimatedFareInr || 0));
      const { otpPlaintext, otpHash } = await generateOtpAndHash(booking.id);
      const expiresAtMs = Date.now() + 600 * 1000;
      const expiresAtTs = Timestamp.fromMillis(expiresAtMs);
      const batch = writeBatch(db);
      batch.update(doc(db, "bookings", booking.id), {
        status: "assigned",
        assignedDriverId: drv.driverId,
        assignedDriverName: drv.legalName,
        assignedVehicleLabel: `${drv.vehicleCategory.toUpperCase()} • ${maskPlate(drv.vehicleRegNumber)}`,
        agreedFareInr: lockedFare,
        otp_status: "pending",
        otp_expires_at_ms: expiresAtMs,
        assignedByOpsUid: currentUser.uid,
        opsAssignmentNote: "Assigned from Owner Admin Portal",
        updatedAt: serverTimestamp()
      });
      batch.set(doc(db, "ride_assignments", booking.id), {
        bookingId: booking.id,
        customerId: booking.customerId,
        driverId: drv.driverId,
        offerId: "",
        lockedFareInr: lockedFare,
        status: "active",
        is_test_ride: Boolean(booking.is_test_ride),
        assignedByUid: currentUser.uid,
        assignedByRole: "admin",
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
        isTestRide: Boolean(booking.is_test_ride),
        status: "pending",
        expiresAt: expiresAtTs,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      });
      await batch.commit();
      await recordAudit("admin_assign_partner", "bookings", booking.id, `Assigned ${drv.legalName} to booking ${booking.id}`);
      ownerAssignBookingId = null;
      renderOwnerAssignBox(null);
      showToast(`Assigned ${drv.legalName} to booking.`, "success");
    };
    row.appendChild(btn);
    cand.appendChild(row);
  });
}

function renderAll() {
  const activeStates = new Set(["searching", "assigned", "arriving", "arrived", "in_progress"]);
  const activeCount = state.bookings.filter((b) => activeStates.has(b.status)).length;
  const searchingCount = state.bookings.filter((b) => b.status === "searching").length;
  const completedCount = state.bookings.filter((b) => b.status === "completed").length;
  const pendingKycCount = state.driverProfiles.filter((p) => p.verificationStatus === "pending").length;
  const liveDriverList = Array.from(state.liveDriversRtdb.values()).filter((d) => d.trackingStatus !== "stopped");

  document.getElementById("mActiveBookings").textContent = String(activeCount);
  document.getElementById("mSearchingBookings").textContent = String(searchingCount);
  document.getElementById("mPendingKyc").textContent = String(pendingKycCount);
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
          coords: [[b.pickupLng, b.pickupLat], [b.destinationLng, b.destinationLat]]
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
  demandEl.innerHTML = demandCounts.size === 0
    ? `<div class="list-item-muted">No recorded bookings yet.</div>`
    : Array.from(demandCounts.entries()).sort((a, b) => b[1] - a[1]).slice(0, 12).map(([loc, count]) => `
        <div class="list-item space-between">
          <strong>${escapeHtml(loc)}</strong>
          <span class="badge badge-info">${count} trips</span>
        </div>
      `).join("");

  // Bookings List with Inspect + Assign + Admin Cancel
  const bookingsEl = document.getElementById("ownerBookingsList");
  if (!filteredBookings.length) {
    bookingsEl.innerHTML = `<div class="list-item-muted">No bookings match the selected filters.</div>`;
  } else {
    bookingsEl.innerHTML = "";
    filteredBookings.slice(0, 40).forEach((b) => {
      const div = document.createElement("div");
      div.className = "list-item stack";
      div.style.gap = "6px";
      div.innerHTML = `
        <div class="space-between">
          <span class="badge ${b.status === "searching" ? "badge-warning" : b.status === "completed" ? "badge-success" : b.status === "cancelled" ? "badge-danger" : "badge-info"}">${escapeHtml(b.status.toUpperCase())}</span>
          <span class="mono">₹${Math.round(b.agreedFareInr || b.estimatedFareInr || 0)}</span>
        </div>
        <div><strong>${escapeHtml(b.pickupLabel)}</strong> → ${escapeHtml(b.destinationLabel)}</div>
        <div class="list-item-muted">Customer: ${escapeHtml(b.customerName)} • Driver: ${escapeHtml(b.assignedDriverName || "Unassigned")} • ${escapeHtml(b.vehicleCategory.toUpperCase())}</div>
      `;
      const row = document.createElement("div");
      row.className = "row";
      if (b.pickupLat && b.pickupLng) {
        const focusBtn = document.createElement("button");
        focusBtn.type = "button";
        focusBtn.className = "btn btn-secondary";
        focusBtn.textContent = "Inspect OSRM Route";
        focusBtn.onclick = async () => {
          mainMapCtrl?.flyTo(b.pickupLat, b.pickupLng, 13.4);
          if (b.destinationLat && b.destinationLng) {
            const route = await fetchOsrmRoute(b.pickupLat, b.pickupLng, b.destinationLat, b.destinationLng);
            selectedRouteCoords = route.coords;
            renderAll();
          }
        };
        row.appendChild(focusBtn);
      }
      if (b.status === "searching") {
        const assignBtn = document.createElement("button");
        assignBtn.type = "button";
        assignBtn.className = "btn btn-primary";
        assignBtn.textContent = "Assign Partner";
        assignBtn.onclick = () => {
          ownerAssignBookingId = b.id;
          renderOwnerAssignBox(b);
        };
        row.appendChild(assignBtn);
      }
      if (activeStates.has(b.status)) {
        const cancelBtn = document.createElement("button");
        cancelBtn.type = "button";
        cancelBtn.className = "btn btn-danger";
        cancelBtn.textContent = "Admin Cancel";
        cancelBtn.onclick = async () => {
          const reason = window.prompt("Admin cancellation reason:", "Cancelled by Owner Admin");
          if (!reason) return;
          await updateDoc(doc(db, "bookings", b.id), {
            status: "cancelled",
            paymentStatus: b.paymentStatus || "pending",
            cancellationReason: reason.trim(),
            cancelledBy: currentUser.uid,
            cancelledByRole: "admin",
            cancellationCategory: "admin_override",
            updatedAt: serverTimestamp()
          });
          await recordAudit("admin_cancel_booking", "bookings", b.id, `Cancelled booking ${b.id}: ${reason.trim()}`);
        };
        row.appendChild(cancelBtn);
      }
      div.appendChild(row);
      bookingsEl.appendChild(div);
    });
  }
  if (ownerAssignBookingId) {
    renderOwnerAssignBox(state.bookings.find((b) => b.id === ownerAssignBookingId && b.status === "searching") || null);
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
      editBtn.onclick = () => {
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
      };
      const toggleBtn = document.createElement("button");
      toggleBtn.type = "button";
      toggleBtn.className = "btn btn-secondary";
      toggleBtn.textContent = z.isActive ? "Disable" : "Enable";
      toggleBtn.onclick = async () => {
        await updateDoc(doc(db, "service_areas", z.id), { isActive: !z.isActive, updatedAt: serverTimestamp() });
      };
      const delBtn = document.createElement("button");
      delBtn.type = "button";
      delBtn.className = "btn btn-danger";
      delBtn.textContent = "Delete";
      delBtn.onclick = async () => {
        await deleteDoc(doc(db, "service_areas", z.id));
        await recordAudit("delete_service_zone", "service_areas", z.id, `Deleted zone ${z.name}`);
      };
      actions.append(editBtn, toggleBtn, delBtn);
      div.appendChild(actions);
      zonesEl.appendChild(div);
    });
  }
  updateZoneMapPreview();

  // Operations Staff List with Quick Toggle & Remove
  const opsEl = document.getElementById("opsStaffList");
  if (!state.opsStaff.length) {
    opsEl.innerHTML = `<div class="list-item-muted">No Operations Staff allowlisted yet.</div>`;
  } else {
    opsEl.innerHTML = "";
    state.opsStaff.forEach((op) => {
      const uid = op.userId || op.id;
      const div = document.createElement("div");
      div.className = "list-item space-between";
      div.innerHTML = `
        <div>
          <strong>${escapeHtml(op.displayName)}</strong>
          <div class="list-item-muted mono">${escapeHtml(uid)} • ${escapeHtml(op.assignedDistrict || "All")}</div>
        </div>
      `;
      const right = document.createElement("div");
      right.className = "row";
      const badge = document.createElement("span");
      badge.className = `badge ${op.isActive ? "badge-success" : "badge-muted"}`;
      badge.textContent = op.isActive ? "ACTIVE OPS" : "REVOKED";
      const toggleBtn = document.createElement("button");
      toggleBtn.type = "button";
      toggleBtn.className = `btn ${op.isActive ? "btn-danger" : "btn-success"}`;
      toggleBtn.textContent = op.isActive ? "Revoke" : "Activate";
      toggleBtn.onclick = async () => {
        const nextActive = !op.isActive;
        await updateDoc(doc(db, "operations_staff", uid), {
          isActive: nextActive,
          revokedBy: nextActive ? "" : currentUser.uid,
          updatedAt: serverTimestamp()
        });
        if (nextActive) {
          await rtdbSet(rtdbRef(rtdb, `auth_roles/ops/${uid}`), { uid, updatedAt: Date.now() });
        } else {
          await rtdbRemove(rtdbRef(rtdb, `auth_roles/ops/${uid}`));
        }
        await recordAudit("toggle_ops_staff", "operations_staff", uid, `${nextActive ? "Activated" : "Revoked"} ${op.displayName}`);
      };
      right.append(badge, toggleBtn);
      div.appendChild(right);
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

  renderPartnersTab();
  renderCustomersTab();
  renderPricingTab();
  renderFinancialsTab();
}

function attachListeners() {
  clearSubscriptions();

  unsubscribers.push(
    onSnapshot(query(collection(db, "bookings"), orderBy("createdAt", "desc"), limit(150)), (snap) => {
      state.bookings = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      updateDistrictOptions();
      renderAll();
    })
  );

  unsubscribers.push(
    onSnapshot(collection(db, "driver_profiles"), (snap) => {
      state.driverProfiles = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
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
    onSnapshot(query(collection(db, "users"), orderBy("createdAt", "desc"), limit(150)), (snap) => {
      state.users = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderCustomersTab();
    })
  );

  unsubscribers.push(
    onSnapshot(collection(db, "fare_configs"), (snap) => {
      state.fareConfigs = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      populateFareFormForCategory(document.getElementById("fCat").value);
      renderPricingTab();
    })
  );

  unsubscribers.push(
    onSnapshot(query(collection(db, "payments"), orderBy("createdAt", "desc"), limit(100)), (snap) => {
      state.payments = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderFinancialsTab();
    })
  );

  unsubscribers.push(
    onSnapshot(query(collection(db, "driver_settlements"), orderBy("createdAt", "desc"), limit(80)), (snap) => {
      state.settlements = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderFinancialsTab();
    })
  );

  unsubscribers.push(
    onSnapshot(query(collection(db, "refunds"), orderBy("createdAt", "desc"), limit(80)), (snap) => {
      state.refunds = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderFinancialsTab();
    })
  );

  unsubscribers.push(
    onSnapshot(query(collection(db, "complaints"), orderBy("createdAt", "desc"), limit(60)), (snap) => {
      state.complaints = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderCustomersTab();
    })
  );

  unsubscribers.push(
    onSnapshot(query(collection(db, "disputes"), orderBy("createdAt", "desc"), limit(60)), (snap) => {
      state.disputes = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderCustomersTab();
    })
  );

  unsubscribers.push(
    onSnapshot(doc(db, "platform_settings", "global"), (snap) => {
      state.platformSettings = snap.exists() ? snap.data() : null;
      populatePlatformSettingsForm();
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
    onSnapshot(query(collection(db, "audit_logs"), orderBy("createdAt", "desc"), limit(80)), (snap) => {
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
