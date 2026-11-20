import {
  collection,
  doc,
  addDoc,
  setDoc,
  updateDoc,
  getDoc,
  onSnapshot,
  query,
  where,
  orderBy,
  limit,
  runTransaction,
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
  auth,
  normalizeIndianPhone,
  createPhoneRecaptcha,
  sendPhoneOtp,
  logoutPortal,
  watchAuth,
  generateOtpAndHash,
  escapeHtml,
  formatEpochDateTime
} from "../js/firebase-config.js";
import {
  computeFareEstimate,
  haversineKm
} from "../js/golocal-domain.js";
import {
  BORAI_LAUNCH,
  createOsmMap,
  fetchOsrmRoute,
  searchOsmPlaces
} from "../js/map-adapters.js";

const el = (id) => document.getElementById(id);

let currentUser = null;
let customerAccount = null;
let mapCtrl = null;
let confirmationResult = null;
let recaptchaVerifier = null;
let activeTripRtdbUnsub = null;
let activeOffersUnsub = null;
let activeOtpSecretUnsub = null;
let activeChatUnsub = null;
let activeTripIdWatching = "";
const unsubscribers = [];

const bookingDraft = {
  pickupLabel: BORAI_LAUNCH.label,
  pickupDistrict: BORAI_LAUNCH.district,
  pickupLat: BORAI_LAUNCH.lat,
  pickupLng: BORAI_LAUNCH.lng,
  destinationLabel: "Mohanpur Bus Stand",
  destinationDistrict: BORAI_LAUNCH.district,
  destinationLat: 21.8442,
  destinationLng: 87.4190,
  distanceKm: 5.8,
  durationMin: 14,
  routeCoords: [],
  selectedCategory: "toto"
};

const state = {
  fareConfigsMap: {},
  platformSettings: null,
  driversDir: [],
  bookings: [],
  payments: [],
  notifications: [],
  activeOffers: [],
  activeOtpSecret: null,
  activeTripRtdb: null,
  activeChatMessages: [],
  customerProfile: null,
  emergencyContact: null
};

function showStatus(msg, tone = "info") {
  const box = el("custStatusAlert");
  if (!msg) {
    box.className = "alert hidden";
    box.textContent = "";
    return;
  }
  box.className = `alert alert-${tone}`;
  box.textContent = msg;
}

function clearSubscriptions() {
  while (unsubscribers.length) {
    try { unsubscribers.pop()(); } catch (_) {}
  }
  clearActiveTripListeners();
}

function clearActiveTripListeners() {
  [activeTripRtdbUnsub, activeOffersUnsub, activeOtpSecretUnsub, activeChatUnsub].forEach((fn) => {
    try { fn && fn(); } catch (_) {}
  });
  activeTripRtdbUnsub = null;
  activeOffersUnsub = null;
  activeOtpSecretUnsub = null;
  activeChatUnsub = null;
  activeTripIdWatching = "";
  state.activeOffers = [];
  state.activeOtpSecret = null;
  state.activeTripRtdb = null;
  state.activeChatMessages = [];
}

// Navigation tabs
document.querySelectorAll(".tab-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".tab-btn").forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    const target = btn.dataset.tab;
    ["bookTab", "ridesTab", "paymentsTab", "alertsTab", "profileTab"].forEach((id) => {
      el(id).classList.toggle("hidden", id !== target);
    });
    if (target === "bookTab" && mapCtrl) {
      setTimeout(() => mapCtrl.map.resize(), 60);
    }
  });
});

// Auth handlers
el("custSendOtpBtn").addEventListener("click", async () => {
  showStatus("");
  const e164 = normalizeIndianPhone(el("custPhoneInput").value);
  if (!e164) return showStatus("Enter a valid 10-digit Indian mobile number or E.164 phone.", "error");
  el("custSendOtpBtn").disabled = true;
  el("custSendOtpBtn").textContent = "Sending OTP...";
  try {
    if (!recaptchaVerifier) {
      recaptchaVerifier = createPhoneRecaptcha("custRecaptcha");
    }
    confirmationResult = await sendPhoneOtp(e164, recaptchaVerifier);
    el("custOtpLabel").textContent = `Verification SMS sent to ${e164}`;
    el("custPhoneStep").classList.add("hidden");
    el("custOtpStep").classList.remove("hidden");
  } catch (err) {
    showStatus(err?.message || "Failed to send OTP.", "error");
  } finally {
    el("custSendOtpBtn").disabled = false;
    el("custSendOtpBtn").textContent = "Send 6-Digit OTP";
  }
});

el("custChangePhoneBtn").addEventListener("click", () => {
  confirmationResult = null;
  el("custOtpInput").value = "";
  el("custOtpStep").classList.add("hidden");
  el("custPhoneStep").classList.remove("hidden");
});

el("custVerifyOtpBtn").addEventListener("click", async () => {
  showStatus("");
  const code = el("custOtpInput").value.trim();
  if (!/^\d{6}$/.test(code)) return showStatus("Enter the exact 6-digit OTP code.", "error");
  if (!confirmationResult) return showStatus("Request an OTP first.", "error");
  el("custVerifyOtpBtn").disabled = true;
  el("custVerifyOtpBtn").textContent = "Verifying...";
  try {
    await confirmationResult.confirm(code);
  } catch (err) {
    showStatus(err?.message || "Invalid or expired OTP.", "error");
  } finally {
    el("custVerifyOtpBtn").disabled = false;
    el("custVerifyOtpBtn").textContent = "Verify & Continue";
  }
});

el("custSignOutBtn").addEventListener("click", async () => {
  clearSubscriptions();
  await logoutPortal();
});

// Onboarding form
el("custOnboardingForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!currentUser) return;
  const fullName = el("obName").value.trim();
  const email = el("obEmail").value.trim();
  const emergency = normalizeIndianPhone(el("obEmergency").value) || el("obEmergency").value.trim();
  const consent = el("obConsent").checked;
  if (fullName.length < 2 || !email.includes("@") || emergency.length < 8 || !consent) {
    return showStatus("Please complete name, valid email, emergency contact, and consent.", "error");
  }
  const phone = currentUser.phoneNumber || "";
  if (emergency === phone) {
    return showStatus("Emergency contact must be different from your mobile number.", "error");
  }
  try {
    await setDoc(doc(db, "users", currentUser.uid), {
      userId: currentUser.uid,
      uid: currentUser.uid,
      fullName,
      email,
      phone,
      mobileNumber: phone,
      role: "customer",
      preferredLanguage: "en",
      accountStatus: "active",
      privacyConsentAccepted: true,
      profileCompleted: true,
      mobileVerified: Boolean(phone),
      hasEmergencyContact: true,
      accountCreatedAt: serverTimestamp(),
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    }, { merge: true });

    await setDoc(doc(db, "customer_profiles", currentUser.uid), {
      userId: currentUser.uid,
      emergencyContactName: "Primary Safety Contact",
      emergencyContactPhone: emergency,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    }, { merge: true });

    await setDoc(doc(db, "emergency_contacts", currentUser.uid), {
      userId: currentUser.uid,
      emergencyContactNumber: emergency,
      relationshipNote: "Emergency Contact",
      contacts: [{ name: "Emergency Contact", phone: emergency, relationship: "Emergency Contact" }],
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    }, { merge: true });

    await bootstrapCustomerWorkspace(currentUser);
  } catch (err) {
    showStatus(`Profile save error: ${err?.message || "Permission denied"}`, "error");
  }
});

function initCustomerMap() {
  if (mapCtrl) return;
  mapCtrl = createOsmMap("custMap", {
    center: [bookingDraft.pickupLng, bookingDraft.pickupLat],
    zoom: 12.6,
    onMapClick: async (lat, lng) => {
      const target = document.querySelector('input[name="mapPickTarget"]:checked')?.value || "pickup";
      if (target === "pickup") {
        bookingDraft.pickupLat = Number(lat.toFixed(5));
        bookingDraft.pickupLng = Number(lng.toFixed(5));
        bookingDraft.pickupLabel = `Pin (${bookingDraft.pickupLat}, ${bookingDraft.pickupLng})`;
        el("pickupSearchInput").value = bookingDraft.pickupLabel;
      } else {
        bookingDraft.destinationLat = Number(lat.toFixed(5));
        bookingDraft.destinationLng = Number(lng.toFixed(5));
        bookingDraft.destinationLabel = `Pin (${bookingDraft.destinationLat}, ${bookingDraft.destinationLng})`;
        el("dropSearchInput").value = bookingDraft.destinationLabel;
      }
      await refreshRouteAndQuote();
    }
  });
  el("pickupSearchInput").value = bookingDraft.pickupLabel;
  el("dropSearchInput").value = bookingDraft.destinationLabel;
}

async function refreshRouteAndQuote() {
  const r = await fetchOsrmRoute(
    bookingDraft.pickupLat,
    bookingDraft.pickupLng,
    bookingDraft.destinationLat,
    bookingDraft.destinationLng
  );
  bookingDraft.distanceKm = Math.max(0.5, r.distanceKm);
  bookingDraft.durationMin = Math.max(2, r.durationMin);
  bookingDraft.routeCoords = r.coords;
  el("routeSummaryBadge").textContent = `Distance: ${bookingDraft.distanceKm} km (~${bookingDraft.durationMin} min via ${r.provider})`;
  renderVehicleOptions();
  renderMapLayers();
}

el("useGpsPickupBtn").addEventListener("click", () => {
  if (!navigator.geolocation) {
    return showStatus("Browser geolocation is unavailable on this device.", "error");
  }
  navigator.geolocation.getCurrentPosition(
    async (pos) => {
      bookingDraft.pickupLat = Number(pos.coords.latitude.toFixed(5));
      bookingDraft.pickupLng = Number(pos.coords.longitude.toFixed(5));
      bookingDraft.pickupLabel = `Current GPS (${bookingDraft.pickupLat}, ${bookingDraft.pickupLng})`;
      el("pickupSearchInput").value = bookingDraft.pickupLabel;
      mapCtrl?.flyTo(bookingDraft.pickupLat, bookingDraft.pickupLng, 14);
      await refreshRouteAndQuote();
    },
    (err) => showStatus(`GPS error: ${err.message}`, "error"),
    { enableHighAccuracy: true, timeout: 10000 }
  );
});

el("centerBoraiBtn").addEventListener("click", () => {
  mapCtrl?.flyTo(BORAI_LAUNCH.lat, BORAI_LAUNCH.lng, BORAI_LAUNCH.zoom);
});

async function bindPlaceSearch(inputId, resultsId, isPickup) {
  const q = el(inputId).value.trim();
  const container = el(resultsId);
  container.innerHTML = `<span class="list-item-muted">Searching OpenStreetMap...</span>`;
  try {
    const places = await searchOsmPlaces(q);
    if (!places.length) {
      container.innerHTML = `<span class="list-item-muted">No matches found. Try adding town/district.</span>`;
      return;
    }
    container.innerHTML = "";
    places.forEach((p) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "btn btn-secondary";
      btn.style.justifyContent = "flex-start";
      btn.textContent = p.displayName;
      btn.title = p.fullAddress;
      btn.addEventListener("click", async () => {
        if (isPickup) {
          bookingDraft.pickupLat = p.lat;
          bookingDraft.pickupLng = p.lng;
          bookingDraft.pickupLabel = p.displayName;
          el("pickupSearchInput").value = p.displayName;
        } else {
          bookingDraft.destinationLat = p.lat;
          bookingDraft.destinationLng = p.lng;
          bookingDraft.destinationLabel = p.displayName;
          el("dropSearchInput").value = p.displayName;
        }
        container.innerHTML = "";
        mapCtrl?.flyTo(p.lat, p.lng, 13.5);
        await refreshRouteAndQuote();
      });
      container.appendChild(btn);
    });
  } catch (err) {
    container.innerHTML = `<span class="list-item-muted">Search failed: ${escapeHtml(err?.message || "Unavailable")}</span>`;
  }
}

el("searchPickupBtn").addEventListener("click", () => bindPlaceSearch("pickupSearchInput", "pickupResults", true));
el("searchDropBtn").addEventListener("click", () => bindPlaceSearch("dropSearchInput", "dropResults", false));

el("bookingTypeSelect").addEventListener("change", () => {
  const val = el("bookingTypeSelect").value;
  el("scheduleDateWrapper").classList.toggle("hidden", val !== "scheduled");
  renderVehicleOptions();
});

function renderVehicleOptions() {
  const cats = [
    { id: "toto", title: "GoLocal Toto", subtitle: "Local short-hop E-Rickshaw" },
    { id: "auto", title: "GoLocal Auto", subtitle: "3-wheeler station & market ride" },
    { id: "bike", title: "GoLocal Bike", subtitle: "Quick single-passenger commute" },
    { id: "cab", title: "GoLocal Cab", subtitle: "AC hatchback / sedan" },
    { id: "outstation", title: "Outstation Cab", subtitle: "Inter-district highway trip" }
  ];
  const isRoundTrip = el("bookingTypeSelect").value === "return_trip";
  const promoPct = Number(state.platformSettings?.promoDiscountPct || 0);
  const listEl = el("vehicleOptionsList");
  listEl.innerHTML = "";

  cats.forEach((c) => {
    const q = computeFareEstimate({
      category: c.id,
      distanceKm: bookingDraft.distanceKm,
      fareConfigsMap: state.fareConfigsMap,
      isRoundTrip,
      promoDiscountPct: promoPct
    });
    const onlineNearby = state.driversDir.filter((d) => d.verificationStatus === "verified" && d.isOnline && d.vehicleCategory === c.id).length;
    if (!q.isEnabled && bookingDraft.selectedCategory === c.id) {
      const firstEnabled = cats.find((x) => computeFareEstimate({ category: x.id, distanceKm: bookingDraft.distanceKm, fareConfigsMap: state.fareConfigsMap }).isEnabled);
      if (firstEnabled) bookingDraft.selectedCategory = firstEnabled.id;
    }

    const row = document.createElement("div");
    row.className = `veh-option ${bookingDraft.selectedCategory === c.id ? "selected" : ""} ${!q.isEnabled ? "disabled" : ""}`;
    row.innerHTML = `
      <div>
        <strong>${escapeHtml(c.title)}</strong>
        <div class="list-item-muted">${escapeHtml(c.subtitle)} • ${onlineNearby} online • Band ₹${q.minPermittedFareInr}–₹${q.maxPermittedFareInr}</div>
      </div>
      <div class="mono" style="font-weight:700;font-size:1.05rem;">${q.isEnabled ? `₹${q.estimatedFareInr}` : "Disabled"}</div>
    `;
    if (q.isEnabled) {
      row.addEventListener("click", () => {
        bookingDraft.selectedCategory = c.id;
        renderVehicleOptions();
      });
    }
    listEl.appendChild(row);
  });

  const activeQuote = computeFareEstimate({
    category: bookingDraft.selectedCategory,
    distanceKm: bookingDraft.distanceKm,
    fareConfigsMap: state.fareConfigsMap,
    isRoundTrip,
    promoDiscountPct: promoPct
  });
  const policyText = state.platformSettings?.cancellationPolicySummary ||
    "Free cancellation pre-arrival. Nominal cancellation fee may apply after pickup arrival.";
  el("fareQuoteDisclosure").innerHTML = `
    <strong>Estimated Total: ₹${activeQuote.estimatedFareInr}</strong> (incl. ${activeQuote.gstPercent}% GST) •
    Waiting ₹${activeQuote.waitingChargePerMinInr}/min • Cancellation policy: ${escapeHtml(policyText)}
  `;
  el("confirmBookBtn").disabled = !activeQuote.isEnabled;
}

el("confirmBookBtn").addEventListener("click", async () => {
  if (!currentUser || !customerAccount) return;
  showStatus("");
  const bookingType = el("bookingTypeSelect").value;
  const isRoundTrip = bookingType === "return_trip";
  const quote = computeFareEstimate({
    category: bookingDraft.selectedCategory,
    distanceKm: bookingDraft.distanceKm,
    fareConfigsMap: state.fareConfigsMap,
    isRoundTrip,
    promoDiscountPct: Number(state.platformSettings?.promoDiscountPct || 0)
  });
  if (!quote.isEnabled) {
    return showStatus("Selected vehicle category is currently disabled by Admin.", "error");
  }
  const scheduledInput = el("scheduleDateInput").value;
  const scheduledAtMs = bookingType === "scheduled" && scheduledInput ? Date.parse(scheduledInput) || 0 : 0;
  if (bookingType === "scheduled" && scheduledAtMs <= Date.now()) {
    return showStatus("Select a valid future date & time for scheduled rides.", "error");
  }

  el("confirmBookBtn").disabled = true;
  el("confirmBookBtn").textContent = "Creating Booking...";
  try {
    const newBookingRef = doc(collection(db, "bookings"));
    const nowMs = Date.now();
    await setDoc(newBookingRef, {
      customerId: currentUser.uid,
      customerName: customerAccount.fullName || "Customer",
      bookingChannel: "app",
      bookingType,
      vehicleCategory: quote.vehicleCategory,
      pickupLabel: bookingDraft.pickupLabel.slice(0, 200),
      pickupDistrict: bookingDraft.pickupDistrict || "West Bengal",
      pickupLat: bookingDraft.pickupLat,
      pickupLng: bookingDraft.pickupLng,
      destinationLabel: bookingDraft.destinationLabel.slice(0, 200),
      destinationDistrict: bookingDraft.destinationDistrict || "West Bengal",
      destinationLat: bookingDraft.destinationLat,
      destinationLng: bookingDraft.destinationLng,
      distanceKm: bookingDraft.distanceKm,
      estimatedDurationMin: bookingDraft.durationMin,
      estimatedFareInr: quote.estimatedFareInr,
      minPermittedFareInr: quote.minPermittedFareInr,
      maxPermittedFareInr: quote.maxPermittedFareInr,
      agreedFareInr: quote.estimatedFareInr,
      fareConfigVersion: quote.fareConfigVersion,
      commissionPercent: quote.commissionPercent,
      gstPercent: quote.gstPercent,
      paymentMethod: el("paymentMethodSelect").value,
      paymentStatus: "pending",
      status: "searching",
      isRoundTrip,
      scheduledAtMs,
      scheduledDateTimeLabel: bookingType === "scheduled" ? scheduledInput : "",
      invoiceEmail: customerAccount.email || "",
      requestExpiresAtMs: nowMs + 15 * 60 * 1000,
      is_test_ride: false,
      test_mode: false,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
    showStatus("Booking dispatched! Looking for nearby verified riders.", "success");
  } catch (err) {
    showStatus(`Unable to create booking: ${err?.message || "Error"}`, "error");
  } finally {
    el("confirmBookBtn").disabled = false;
    el("confirmBookBtn").textContent = "Find Nearby Verified Rider";
  }
});

el("quickSosBtn").addEventListener("click", async () => {
  if (!currentUser) return;
  const active = findActiveBooking();
  const note = prompt("Optional safety note for GoLocal Operations Desk:", "Urgent assistance requested");
  if (note === null) return;
  try {
    await addDoc(collection(db, "emergency_events"), {
      userId: currentUser.uid,
      bookingId: active?.id || "",
      lat: bookingDraft.pickupLat,
      lng: bookingDraft.pickupLng,
      emergencyContactRef: state.emergencyContact?.emergencyContactNumber || "",
      notes: (note || "Emergency SOS triggered").slice(0, 280),
      status: "active",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
    showStatus("SOS alert sent to GoLocal Operations & Safety Desk.", "error");
  } catch (err) {
    showStatus(`Failed to send SOS: ${err?.message}`, "error");
  }
});

function findActiveBooking() {
  const activeSet = new Set(["searching", "assigned", "arriving", "arrived", "in_progress"]);
  return state.bookings.find((b) => activeSet.has(b.status) && !b.is_test_ride && !b.test_mode) || null;
}

function syncActiveTripListeners(activeBooking) {
  const nextId = activeBooking ? activeBooking.id : "";
  if (nextId === activeTripIdWatching) return;
  clearActiveTripListeners();
  if (!activeBooking) return;
  activeTripIdWatching = nextId;

  activeOffersUnsub = onSnapshot(
    query(collection(db, "ride_offers"), where("bookingId", "==", activeBooking.id), where("customerId", "==", currentUser.uid)),
    (snap) => {
      state.activeOffers = snap.docs.map((d) => ({ id: d.id, ...d.data() })).filter((o) => o.status === "pending");
      renderActiveTripPanel();
    }
  );

  activeOtpSecretUnsub = onSnapshot(doc(db, "ride_otp_secrets", activeBooking.id), (snap) => {
    state.activeOtpSecret = snap.exists() ? snap.data() : null;
    renderActiveTripPanel();
  }, () => {});

  activeTripRtdbUnsub = onValue(rtdbRef(rtdb, `live_locations/trips/${activeBooking.id}`), (snap) => {
    state.activeTripRtdb = snap.val() || null;
    renderMapLayers();
    renderActiveTripPanel();
  });

  activeChatUnsub = onSnapshot(
    query(collection(db, "booking_chats", activeBooking.id, "messages"), orderBy("createdAt", "asc"), limit(40)),
    (snap) => {
      state.activeChatMessages = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderActiveTripPanel();
    },
    () => {}
  );
}

async function acceptOfferAtomically(booking, offer) {
  const { otpPlaintext, otpHash } = await generateOtpAndHash(booking.id);
  const expiresAtMs = Date.now() + 600 * 1000;
  const expiresAtTs = Timestamp.fromMillis(expiresAtMs);

  await runTransaction(db, async (tx) => {
    const bRef = doc(db, "bookings", booking.id);
    const bSnap = await tx.get(bRef);
    if (!bSnap.exists() || bSnap.data().status !== "searching") {
      throw new Error("Booking is no longer searching.");
    }
    tx.update(bRef, {
      status: "assigned",
      assignedDriverId: offer.driverId,
      assignedDriverName: offer.driverName,
      assignedVehicleLabel: offer.vehicleLabel || offer.vehicleCategory.toUpperCase(),
      acceptedOfferId: offer.id,
      agreedFareInr: offer.proposedFareInr,
      otp_status: "pending",
      otp_expires_at_ms: expiresAtMs,
      updatedAt: serverTimestamp()
    });
    tx.update(doc(db, "ride_offers", offer.id), {
      status: "accepted",
      updatedAt: serverTimestamp()
    });
    tx.set(doc(db, "ride_assignments", booking.id), {
      bookingId: booking.id,
      customerId: booking.customerId,
      driverId: offer.driverId,
      offerId: offer.id,
      lockedFareInr: offer.proposedFareInr,
      status: "active",
      is_test_ride: false,
      assignedByUid: currentUser.uid,
      assignedByRole: "customer",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
    tx.set(doc(db, "ride_otps", booking.id), {
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
    tx.set(doc(db, "ride_otp_secrets", booking.id), {
      bookingId: booking.id,
      customerId: booking.customerId,
      otpPlaintext,
      isTestRide: false,
      status: "pending",
      expiresAt: expiresAtTs,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
  });
  showStatus(`Rider ${offer.driverName} assigned! Share Pickup OTP when rider arrives.`, "success");
}

function renderActiveTripPanel() {
  const active = findActiveBooking();
  syncActiveTripListeners(active);
  const panel = el("activeTripPanel");
  const bookPanel = el("newBookingPanel");

  if (!active) {
    panel.classList.add("hidden");
    bookPanel.classList.remove("hidden");
    return;
  }

  bookPanel.classList.add("hidden");
  panel.classList.remove("hidden");

  const otpPlain = state.activeOtpSecret?.otpPlaintext || "";
  const livePos = state.activeTripRtdb;
  const distKm = livePos && active.pickupLat ? haversineKm(livePos.lat, livePos.lng, active.pickupLat, active.pickupLng).toFixed(1) : null;

  panel.innerHTML = `
    <div class="space-between">
      <span class="badge badge-info">${escapeHtml(active.status.replace("_", " ").toUpperCase())}</span>
      <span class="mono">Fare: ₹${Math.round(active.agreedFareInr || active.estimatedFareInr)}</span>
    </div>
    <div>
      <strong>${escapeHtml(active.pickupLabel)}</strong> → <strong>${escapeHtml(active.destinationLabel)}</strong>
      <div class="list-item-muted">Booking #${escapeHtml(active.id.slice(0, 8))} • ${escapeHtml(active.vehicleCategory.toUpperCase())} • ${escapeHtml(active.paymentMethod.toUpperCase())}</div>
    </div>
    ${active.assignedDriverId ? `
      <div class="list-item">
        <strong>Rider: ${escapeHtml(active.assignedDriverName)}</strong>
        <div class="list-item-muted">${escapeHtml(active.assignedVehicleLabel || "Verified Partner")}${distKm !== null ? ` • Live distance to pickup: ${distKm} km` : ""}</div>
      </div>
    ` : `<div class="alert alert-info">Searching for nearby verified riders... incoming proposals appear below.</div>`}
    ${otpPlain && active.status !== "in_progress" ? `
      <div class="otp-banner">
        <div>
          <div style="font-size:0.78rem;opacity:0.85;">SHARE PICKUP OTP AT BOARDING</div>
          <div style="font-size:0.86rem;">Do not share until driver reaches pickup.</div>
        </div>
        <div class="otp-code">${escapeHtml(otpPlain)}</div>
      </div>
    ` : ""}
    <div id="offersBox" class="stack" style="gap:8px;"></div>
    ${active.assignedDriverId ? `
      <div class="stack" style="gap:6px;">
        <strong>In-Trip Chat</strong>
        <div id="custChatBox" class="chat-box">
          ${state.activeChatMessages.length ? state.activeChatMessages.map((m) => `
            <div class="chat-msg ${m.senderId === currentUser.uid ? "mine" : "theirs"}">
              ${escapeHtml(m.text)}
            </div>
          `).join("") : `<div class="list-item-muted">No messages yet.</div>`}
        </div>
        <div class="row" style="gap:6px;">
          <input id="custChatInput" type="text" placeholder="Message rider..." style="flex:1;" />
          <button id="custSendChatBtn" class="btn btn-primary" type="button">Send</button>
        </div>
      </div>
    ` : ""}
    ${active.status !== "in_progress" ? `
      <button id="cancelActiveRideBtn" class="btn btn-danger" type="button">Cancel Ride</button>
    ` : `<div class="list-item-muted">Ride is in progress. Use SOS above if you need emergency assistance.</div>`}
  `;

  if (active.status === "searching") {
    const offersBox = el("offersBox");
    state.activeOffers.forEach((offer) => {
      const card = document.createElement("div");
      card.className = "list-item space-between";
      card.innerHTML = `
        <div>
          <strong>${escapeHtml(offer.driverName)}</strong> • ★ ${Number(offer.driverRating || 5).toFixed(1)}
          <div class="list-item-muted">ETA ${offer.etaMinutes} min • Proposed Fare ₹${Math.round(offer.proposedFareInr)}</div>
        </div>
      `;
      const row = document.createElement("div");
      row.className = "row";
      const acceptBtn = document.createElement("button");
      acceptBtn.type = "button";
      acceptBtn.className = "btn btn-success";
      acceptBtn.textContent = `Accept ₹${Math.round(offer.proposedFareInr)}`;
      acceptBtn.addEventListener("click", async () => {
        acceptBtn.disabled = true;
        try {
          await acceptOfferAtomically(active, offer);
        } catch (err) {
          showStatus(err?.message || "Failed to accept offer", "error");
        }
      });
      const declineBtn = document.createElement("button");
      declineBtn.type = "button";
      declineBtn.className = "btn btn-secondary";
      declineBtn.textContent = "Decline";
      declineBtn.addEventListener("click", async () => {
        await updateDoc(doc(db, "ride_offers", offer.id), { status: "rejected", updatedAt: serverTimestamp() });
      });
      row.append(acceptBtn, declineBtn);
      card.appendChild(row);
      offersBox.appendChild(card);
    });
  }

  const chatBtn = el("custSendChatBtn");
  if (chatBtn) {
    chatBtn.addEventListener("click", async () => {
      const txt = el("custChatInput").value.trim();
      if (!txt) return;
      el("custChatInput").value = "";
      await addDoc(collection(db, "booking_chats", active.id, "messages"), {
        bookingId: active.id,
        senderId: currentUser.uid,
        senderRole: "customer",
        senderName: customerAccount?.fullName || "Customer",
        text: txt.slice(0, 300),
        readByCustomer: true,
        readByDriver: false,
        createdAt: serverTimestamp()
      });
    });
  }

  const cancelBtn = el("cancelActiveRideBtn");
  if (cancelBtn) {
    cancelBtn.addEventListener("click", async () => {
      const reason = prompt("Reason for cancelling this ride:", "Change of plans");
      if (reason === null) return;
      await updateDoc(doc(db, "bookings", active.id), {
        status: "cancelled",
        cancelledBy: currentUser.uid,
        cancelledByRole: "customer",
        cancellationCategory: "Customer Cancelled",
        cancellationReason: (reason || "Customer cancelled").slice(0, 200),
        updatedAt: serverTimestamp()
      });
      showStatus("Booking cancelled.", "info");
    });
  }
}

function renderMapLayers() {
  if (!mapCtrl) return;
  const markers = [
    { id: "cust_pickup", lat: bookingDraft.pickupLat, lng: bookingDraft.pickupLng, title: "Pickup", subtitle: bookingDraft.pickupLabel, color: "#1565C0", radius: 8 },
    { id: "cust_drop", lat: bookingDraft.destinationLat, lng: bookingDraft.destinationLng, title: "Drop-off", subtitle: bookingDraft.destinationLabel, color: "#DC2626", radius: 8 }
  ];
  const onlineDrivers = state.driversDir.filter((d) => d.verificationStatus === "verified" && d.isOnline);
  el("onlineRidersCount").textContent = String(onlineDrivers.length);
  onlineDrivers.forEach((d) => {
    markers.push({
      id: `cust_drv_${d.driverId}`,
      lat: d.currentLat,
      lng: d.currentLng,
      title: `${d.displayName} (${d.vehicleCategory.toUpperCase()})`,
      subtitle: `★ ${Number(d.ratingAvg || 5).toFixed(1)}`,
      color: "#15803D",
      radius: 6
    });
  });
  if (state.activeTripRtdb?.lat && state.activeTripRtdb?.lng) {
    markers.push({
      id: "active_trip_driver",
      lat: state.activeTripRtdb.lat,
      lng: state.activeTripRtdb.lng,
      title: "Your Assigned Rider (Live)",
      subtitle: formatEpochDateTime(state.activeTripRtdb.updatedAtEpochMs),
      color: "#0EA5E9",
      radius: 10
    });
  }
  const routes = bookingDraft.routeCoords.length >= 2
    ? [{ id: "cust_route", color: "#1565C0", coords: bookingDraft.routeCoords }]
    : [];
  mapCtrl.updateMarkers(markers);
  mapCtrl.updateRoutes(routes);
}

function openReceiptModal(booking, payment) {
  const idLabel = (booking?.id || payment?.bookingId || "").slice(0, 10).toUpperCase();
  const gross = payment?.grossFareInr ?? booking?.agreedFareInr ?? booking?.estimatedFareInr ?? 0;
  const gst = payment?.gstInr ?? Math.round((gross * (booking?.gstPercent ?? 5) / 100) * 100) / 100;
  const base = Math.max(0, gross - gst - (booking?.waitingFeeInr || 0));
  el("receiptModalBody").innerHTML = `
    <div class="list-item stack" style="gap:6px;">
      <div class="space-between"><strong>GoLocal by NR Next</strong><span class="mono">#GL${escapeHtml(idLabel)}</span></div>
      ${booking ? `<div class="list-item-muted">${escapeHtml(booking.pickupLabel)} → ${escapeHtml(booking.destinationLabel)}</div>
      <div class="list-item-muted">${escapeHtml(booking.vehicleCategory.toUpperCase())} • ${booking.distanceKm} km • Partner: ${escapeHtml(booking.assignedDriverName || "—")}</div>` : ""}
      <hr style="width:100%;border:none;border-top:1px solid var(--border);" />
      <div class="space-between"><span>Base &amp; Distance Fare (excl. GST)</span><span class="mono">₹${base.toFixed(2)}</span></div>
      ${booking?.waitingFeeInr ? `<div class="space-between"><span>Waiting Fee</span><span class="mono">₹${Number(booking.waitingFeeInr).toFixed(2)}</span></div>` : ""}
      <div class="space-between"><span>GST (${booking?.gstPercent ?? 5}%)</span><span class="mono">₹${gst.toFixed(2)}</span></div>
      <hr style="width:100%;border:none;border-top:1px solid var(--border);" />
      <div class="space-between"><strong>Total Paid</strong><strong class="mono">₹${Number(gross).toFixed(2)}</strong></div>
      <div class="list-item-muted">Method: ${escapeHtml((payment?.paymentMethod || booking?.paymentMethod || "cash").toUpperCase())} • Status: ${escapeHtml((payment?.status || booking?.paymentStatus || "settled").toUpperCase())}</div>
      ${payment?.gatewayReference ? `<div class="list-item-muted mono">Gateway Ref: ${escapeHtml(payment.gatewayReference)}</div>` : ""}
    </div>
  `;
  el("receiptModal").classList.remove("hidden");
}

el("closeReceiptModalBtn").addEventListener("click", () => el("receiptModal").classList.add("hidden"));
el("printReceiptBtn").addEventListener("click", () => window.print());

function renderRidesAndPayments() {
  const ridesEl = el("custRidesList");
  if (!state.bookings.length) {
    ridesEl.innerHTML = `<div class="list-item-muted">No ride history yet. Book your first GoLocal trip above!</div>`;
  } else {
    ridesEl.innerHTML = "";
    state.bookings.forEach((b) => {
      const pay = state.payments.find((p) => p.bookingId === b.id);
      const card = document.createElement("div");
      card.className = "list-item stack";
      card.style.gap = "6px";
      card.innerHTML = `
        <div class="space-between">
          <span class="badge ${b.status === "completed" ? "badge-success" : b.status === "cancelled" ? "badge-danger" : "badge-info"}">${escapeHtml(b.status.toUpperCase())}</span>
          <span class="mono">₹${Math.round(b.agreedFareInr || b.estimatedFareInr)} • ${escapeHtml(formatEpochDateTime(b.createdAt))}</span>
        </div>
        <div><strong>${escapeHtml(b.pickupLabel)}</strong> → ${escapeHtml(b.destinationLabel)}</div>
        <div class="list-item-muted">${escapeHtml(b.vehicleCategory.toUpperCase())} • ${b.distanceKm} km • Rider: ${escapeHtml(b.assignedDriverName || "—")}</div>
      `;
      const row = document.createElement("div");
      row.className = "row";
      if (b.status === "completed") {
        const recBtn = document.createElement("button");
        recBtn.type = "button";
        recBtn.className = "btn btn-secondary";
        recBtn.textContent = "GST Receipt";
        recBtn.addEventListener("click", () => openReceiptModal(b, pay));
        row.appendChild(recBtn);

        if (!b.customerRating && b.assignedDriverId) {
          const rateBtn = document.createElement("button");
          rateBtn.type = "button";
          rateBtn.className = "btn btn-primary";
          rateBtn.textContent = "Rate Rider";
          rateBtn.addEventListener("click", async () => {
            const stars = Number(prompt("Rate your rider (1-5 stars):", "5"));
            if (!stars || stars < 1 || stars > 5) return;
            const review = prompt("Optional review comment:", "") || "";
            await addDoc(collection(db, "ratings"), {
              bookingId: b.id,
              customerId: currentUser.uid,
              driverId: b.assignedDriverId,
              stars: Math.round(stars),
              driverBehaviorRating: Math.round(stars),
              vehicleConditionRating: Math.round(stars),
              punctualityRating: Math.round(stars),
              safetyRating: Math.round(stars),
              review: review.slice(0, 250),
              createdAt: serverTimestamp()
            });
            await updateDoc(doc(db, "bookings", b.id), { customerRating: Math.round(stars), updatedAt: serverTimestamp() });
            showStatus("Thank you! Rating recorded.", "success");
          });
          row.appendChild(rateBtn);
        }

        const compBtn = document.createElement("button");
        compBtn.type = "button";
        compBtn.className = "btn btn-danger";
        compBtn.textContent = "Complaint / Refund";
        compBtn.addEventListener("click", async () => {
          const desc = prompt("Describe issue or refund reason (min 5 chars):", "");
          if (!desc || desc.trim().length < 5) return;
          await addDoc(collection(db, "complaints"), {
            reporterId: currentUser.uid,
            bookingId: b.id,
            category: "trip_dispute",
            description: desc.trim().slice(0, 450),
            status: "open",
            lat: b.pickupLat || 0,
            lng: b.pickupLng || 0,
            locationLabel: b.pickupLabel || "",
            createdAt: serverTimestamp(),
            updatedAt: serverTimestamp()
          });
          showStatus("Complaint submitted for Operations & Admin review.", "info");
        });
        row.appendChild(compBtn);
      }
      if (row.children.length) card.appendChild(row);
      ridesEl.appendChild(card);
    });
  }

  const payEl = el("custPaymentsList");
  if (!state.payments.length) {
    payEl.innerHTML = `<div class="list-item-muted">No completed payment records yet.</div>`;
  } else {
    payEl.innerHTML = "";
    state.payments.forEach((p) => {
      const bk = state.bookings.find((b) => b.id === p.bookingId);
      const card = document.createElement("div");
      card.className = "list-item space-between";
      card.innerHTML = `
        <div>
          <strong>Booking #${escapeHtml(p.bookingId.slice(0, 8))} • ₹${Number(p.grossFareInr).toFixed(2)}</strong>
          <div class="list-item-muted">GST ₹${Number(p.gstInr).toFixed(2)} • ${escapeHtml(p.paymentMethod.toUpperCase())} • ${escapeHtml(p.status.toUpperCase())}</div>
        </div>
      `;
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "btn btn-secondary";
      btn.textContent = "Receipt";
      btn.addEventListener("click", () => openReceiptModal(bk, p));
      card.appendChild(btn);
      payEl.appendChild(card);
    });
  }
}

function renderAlerts() {
  const listEl = el("custAlertsList");
  if (!state.notifications.length) {
    listEl.innerHTML = `<div class="list-item-muted">No notifications yet.</div>`;
    return;
  }
  listEl.innerHTML = state.notifications.map((n) => `
    <div class="list-item ${n.read ? "" : "alert-info"}">
      <div class="space-between">
        <strong>${escapeHtml(n.title)}</strong>
        <span class="badge ${n.read ? "badge-muted" : "badge-info"}">${n.read ? "READ" : "NEW"}</span>
      </div>
      <div class="list-item-muted">${escapeHtml(n.body)}</div>
    </div>
  `).join("");
}

el("custMarkAllReadBtn").addEventListener("click", async () => {
  const unread = state.notifications.filter((n) => !n.read);
  await Promise.all(unread.map((n) => updateDoc(doc(db, "notifications", n.id), { read: true })));
});

el("custProfileForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!currentUser) return;
  const emergPhone = el("profEmergPhone").value.trim();
  if (emergPhone.length < 8) return showStatus("Emergency contact phone must be valid.", "error");
  try {
    await setDoc(doc(db, "customer_profiles", currentUser.uid), {
      userId: currentUser.uid,
      homeAddress: el("profHome").value.trim().slice(0, 200),
      workAddress: el("profWork").value.trim().slice(0, 200),
      nativeVillage: el("profVillage").value.trim().slice(0, 100),
      nativeDistrict: el("profDistrict").value.trim().slice(0, 70),
      emergencyContactName: el("profEmergName").value.trim().slice(0, 90),
      emergencyContactPhone: emergPhone.slice(0, 25),
      updatedAt: serverTimestamp()
    }, { merge: true });
    await setDoc(doc(db, "emergency_contacts", currentUser.uid), {
      userId: currentUser.uid,
      emergencyContactNumber: emergPhone.slice(0, 25),
      relationshipNote: el("profEmergName").value.trim() || "Emergency Contact",
      contacts: [{ name: el("profEmergName").value.trim() || "Emergency Contact", phone: emergPhone.slice(0, 25), relationship: "Emergency Contact" }],
      createdAt: state.emergencyContact?.createdAt || serverTimestamp(),
      updatedAt: serverTimestamp()
    }, { merge: true });
    showStatus("Profile & Emergency Contact saved.", "success");
  } catch (err) {
    showStatus(`Failed to save profile: ${err?.message}`, "error");
  }
});

el("custSupportForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!currentUser) return;
  const details = el("supDetails").value.trim();
  if (details.length < 5) return;
  await addDoc(collection(db, "support_requests"), {
    userId: currentUser.uid,
    requestType: el("supType").value,
    details: details.slice(0, 480),
    status: "submitted",
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });
  el("supDetails").value = "";
  showStatus("Support request submitted.", "success");
});

function renderPlatformBanners() {
  const s = state.platformSettings;
  const promoEl = el("custPromoBanner");
  const maintEl = el("custMaintBanner");
  el("helplineDisplay").textContent = `24x7 Safety Helpline: ${s?.helplineNumber || "+91-1800-103-0000"}`;

  if (s && (s.promoBannerTitle || s.promoBannerText)) {
    promoEl.classList.remove("hidden");
    promoEl.innerHTML = `
      <div>
        <strong>${escapeHtml(s.promoBannerTitle || "GoLocal Offer")}</strong>
        <div>${escapeHtml(s.promoBannerText || "")}</div>
      </div>
      ${s.promoCode ? `<span class="badge badge-info mono">${escapeHtml(s.promoCode)}</span>` : ""}
    `;
  } else {
    promoEl.classList.add("hidden");
  }

  if (s?.maintenanceNotice) {
    maintEl.classList.remove("hidden");
    maintEl.textContent = s.maintenanceNotice;
  } else {
    maintEl.classList.add("hidden");
  }
}

async function bootstrapCustomerWorkspace(user) {
  const uSnap = await getDoc(doc(db, "users", user.uid));
  const acc = uSnap.exists() ? uSnap.data() : null;
  if (!acc || !acc.profileCompleted || !acc.email || !acc.hasEmergencyContact) {
    el("custAuthCard").classList.add("hidden");
    el("custMainSection").classList.add("hidden");
    el("custOnboardingCard").classList.remove("hidden");
    if (acc?.fullName) el("obName").value = acc.fullName;
    if (acc?.email) el("obEmail").value = acc.email;
    return;
  }

  customerAccount = acc;
  el("custAuthCard").classList.add("hidden");
  el("custOnboardingCard").classList.add("hidden");
  el("custMainSection").classList.remove("hidden");
  initCustomerMap();
  await refreshRouteAndQuote();

  clearSubscriptions();

  unsubscribers.push(
    onSnapshot(doc(db, "platform_settings", "global"), (snap) => {
      state.platformSettings = snap.exists() ? snap.data() : null;
      renderPlatformBanners();
      renderVehicleOptions();
    })
  );

  unsubscribers.push(
    onSnapshot(collection(db, "fare_configs"), (snap) => {
      state.fareConfigsMap = {};
      snap.docs.forEach((d) => {
        const data = d.data();
        if (data?.vehicleCategory) state.fareConfigsMap[data.vehicleCategory] = data;
      });
      renderVehicleOptions();
    })
  );

  unsubscribers.push(
    onSnapshot(collection(db, "driver_directory"), (snap) => {
      state.driversDir = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderVehicleOptions();
      renderMapLayers();
    })
  );

  unsubscribers.push(
    onSnapshot(
      query(collection(db, "bookings"), where("customerId", "==", user.uid), orderBy("createdAt", "desc"), limit(40)),
      (snap) => {
        state.bookings = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
        renderActiveTripPanel();
        renderRidesAndPayments();
      }
    )
  );

  unsubscribers.push(
    onSnapshot(
      query(collection(db, "payments"), where("customerId", "==", user.uid), orderBy("createdAt", "desc"), limit(40)),
      (snap) => {
        state.payments = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
        renderRidesAndPayments();
      }
    )
  );

  unsubscribers.push(
    onSnapshot(
      query(collection(db, "notifications"), where("userId", "==", user.uid), orderBy("createdAt", "desc"), limit(30)),
      (snap) => {
        state.notifications = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
        renderAlerts();
      }
    )
  );

  unsubscribers.push(
    onSnapshot(doc(db, "customer_profiles", user.uid), (snap) => {
      state.customerProfile = snap.exists() ? snap.data() : null;
      if (state.customerProfile) {
        el("profHome").value = state.customerProfile.homeAddress || "";
        el("profWork").value = state.customerProfile.workAddress || "";
        el("profVillage").value = state.customerProfile.nativeVillage || "";
        el("profDistrict").value = state.customerProfile.nativeDistrict || "";
        el("profEmergName").value = state.customerProfile.emergencyContactName || "";
        if (state.customerProfile.emergencyContactPhone) {
          el("profEmergPhone").value = state.customerProfile.emergencyContactPhone;
        }
      }
    })
  );

  unsubscribers.push(
    onSnapshot(doc(db, "emergency_contacts", user.uid), (snap) => {
      state.emergencyContact = snap.exists() ? snap.data() : null;
      if (state.emergencyContact?.emergencyContactNumber && !el("profEmergPhone").value) {
        el("profEmergPhone").value = state.emergencyContact.emergencyContactNumber;
      }
    })
  );
}

watchAuth(async (user) => {
  clearSubscriptions();
  currentUser = user;
  if (!user) {
    el("custSessionBadge").classList.add("hidden");
    el("custSignOutBtn").classList.add("hidden");
    el("custMainSection").classList.add("hidden");
    el("custOnboardingCard").classList.add("hidden");
    el("custAuthCard").classList.remove("hidden");
    return;
  }
  el("custSessionBadge").classList.remove("hidden");
  el("custSessionBadge").textContent = user.phoneNumber || user.uid.slice(0, 8);
  el("custSignOutBtn").classList.remove("hidden");
  await bootstrapCustomerWorkspace(user);
});
