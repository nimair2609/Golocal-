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
  writeBatch,
  increment,
  serverTimestamp,
  Timestamp
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";
import {
  ref as rtdbRef,
  set as rtdbSet,
  update as rtdbUpdate
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-database.js";
import {
  ref as storageRef,
  uploadBytes,
  getDownloadURL
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-storage.js";
import {
  db,
  rtdb,
  storage,
  auth,
  normalizeIndianPhone,
  resolveAppIdentity,
  createPhoneRecaptcha,
  sendPhoneOtp,
  logoutPortal,
  watchAuth,
  generateOtpAndHash,
  hashBookingOtp,
  escapeHtml,
  formatEpochDateTime,
  downloadCsvFile
} from "../js/firebase-config.js";
import {
  maskPlate,
  haversineKm,
  computeSettlement,
  buildCsvContent
} from "../js/golocal-domain.js";
import {
  BORAI_LAUNCH,
  createOsmMap,
  fetchOsrmRoute
} from "../js/map-adapters.js";

const el = (id) => document.getElementById(id);

let currentUser = null;
let confirmationResult = null;
let recaptchaVerifier = null;
let mapCtrl = null;
let geoWatchId = null;
let sampleModeActive = false;
let activeChatUnsub = null;
let activeChatBookingId = "";
let uploadedDocUrls = {
  dlPhotoUri: "",
  rcPhotoUri: "",
  insurancePhotoUri: "",
  permitPhotoUri: ""
};
const unsubscribers = [];

const state = {
  identity: null,
  driverProfile: null,
  sampleRiderProfile: null,
  fareConfigsMap: {},
  platformSettings: null,
  searchingBookings: [],
  myBookings: [],
  rejectedBookingIds: new Set(),
  commissions: [],
  settlements: [],
  notifications: [],
  sampleIncomingRides: [],
  sampleMyRides: [],
  activeChatMessages: [],
  currentLat: BORAI_LAUNCH.lat,
  currentLng: BORAI_LAUNCH.lng,
  hasRealGps: false
};

function showStatus(msg, tone = "info") {
  const box = el("riderStatusAlert");
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
    try {
      unsubscribers.pop()();
    } catch (_) {}
  }
  if (activeChatUnsub) {
    try { activeChatUnsub(); } catch (_) {}
    activeChatUnsub = null;
  }
  activeChatBookingId = "";
  if (geoWatchId !== null && navigator.geolocation) {
    navigator.geolocation.clearWatch(geoWatchId);
    geoWatchId = null;
  }
}

// Tabs
document.querySelectorAll(".tab-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".tab-btn").forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    const target = btn.dataset.tab;
    ["dispatchTab", "earningsTab", "historyTab", "kycTab", "alertsTab"].forEach((id) => {
      el(id).classList.toggle("hidden", id !== target);
    });
    if (target === "dispatchTab" && mapCtrl) {
      setTimeout(() => mapCtrl.map.resize(), 60);
    }
  });
});

// Phone OTP Auth
el("riderSendOtpBtn").addEventListener("click", async () => {
  const phone = normalizeIndianPhone(el("riderPhoneInput").value);
  if (!phone || phone.length < 10) {
    showStatus("Enter a valid 10-digit Indian mobile number or E.164 number.", "error");
    return;
  }
  try {
    el("riderSendOtpBtn").disabled = true;
    showStatus("Sending Partner SMS OTP...", "info");
    if (!recaptchaVerifier) {
      recaptchaVerifier = createPhoneRecaptcha("riderRecaptcha");
    }
    confirmationResult = await sendPhoneOtp(phone, recaptchaVerifier);
    el("riderPhoneStep").classList.add("hidden");
    el("riderOtpStep").classList.remove("hidden");
    el("riderOtpLabel").textContent = `6-digit verification code sent to ${phone}.`;
    showStatus("");
  } catch (err) {
    showStatus(`OTP send failed: ${err?.message || "Try again"}`, "error");
  } finally {
    el("riderSendOtpBtn").disabled = false;
  }
});

el("riderChangePhoneBtn").addEventListener("click", () => {
  confirmationResult = null;
  el("riderOtpStep").classList.add("hidden");
  el("riderPhoneStep").classList.remove("hidden");
});

el("riderVerifyOtpBtn").addEventListener("click", async () => {
  const code = (el("riderOtpInput").value || "").trim();
  if (!confirmationResult || code.length < 6) {
    showStatus("Enter the 6-digit verification code.", "error");
    return;
  }
  try {
    el("riderVerifyOtpBtn").disabled = true;
    await confirmationResult.confirm(code);
    showStatus("");
  } catch (err) {
    showStatus(`Invalid OTP: ${err?.message || "Verification failed"}`, "error");
  } finally {
    el("riderVerifyOtpBtn").disabled = false;
  }
});

el("riderSignOutBtn").addEventListener("click", async () => {
  clearSubscriptions();
  await logoutPortal();
});

// Firebase Storage Document Uploads
function bindStorageUploader(inputId, statusId, docKey) {
  el(inputId).addEventListener("change", async (e) => {
    const file = e.target.files?.[0];
    if (!file || !currentUser) return;
    if (file.size >= 5 * 1024 * 1024) {
      el(statusId).textContent = "Error: File exceeds 5 MB limit.";
      return;
    }
    const statusEl = el(statusId);
    statusEl.textContent = `Uploading ${file.name} to Firebase Storage...`;
    try {
      const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, "_");
      const path = `kyc_docs/${currentUser.uid}/${docKey}_${Date.now()}_${safeName}`;
      const sRef = storageRef(storage, path);
      await uploadBytes(sRef, file, { contentType: file.type || "image/jpeg" });
      const url = await getDownloadURL(sRef);
      uploadedDocUrls[docKey] = url;
      statusEl.innerHTML = `<span class="badge badge-success">Uploaded</span> <a href="${escapeHtml(url)}" target="_blank" rel="noopener">View Document</a>`;
    } catch (err) {
      statusEl.textContent = `Upload failed: ${err?.message || "Check permissions"}`;
    }
  });
}

bindStorageUploader("fileDl", "dlUploadStatus", "dlPhotoUri");
bindStorageUploader("fileRc", "rcUploadStatus", "rcPhotoUri");
bindStorageUploader("fileInsurance", "insuranceUploadStatus", "insurancePhotoUri");
bindStorageUploader("filePermit", "permitUploadStatus", "permitPhotoUri");

function populateKycForm() {
  const dp = state.driverProfile;
  if (!dp) {
    el("kycFormStatusBadge").className = "badge badge-muted";
    el("kycFormStatusBadge").textContent = "NOT SUBMITTED";
    return;
  }
  el("kLegalName").value = dp.legalName || "";
  el("kDistrict").value = dp.district || "Paschim Medinipur";
  el("kBlock").value = dp.block || "Mohanpur";
  el("kVehCat").value = dp.vehicleCategory || "toto";
  el("kVehModel").value = dp.vehicleModel || "";
  el("kVehReg").value = dp.vehicleRegNumber || "";
  el("kDlNum").value = dp.drivingLicenceNumber || "";
  el("kRcNum").value = dp.rcNumber || "";
  el("kInsNum").value = dp.insuranceNumber || "";
  el("kPermit").value = dp.permitInfo || "";
  el("kUpi").value = dp.settlementUpiOrBank || "";
  el("kAddress").value = dp.address || "";
  el("kEmergName").value = dp.emergencyContactName || "";
  el("kEmergPhone").value = dp.emergencyContactPhone || "";
  el("kTermsCheck").checked = Boolean(dp.complianceTermsAccepted);

  uploadedDocUrls.dlPhotoUri = dp.dlPhotoUri || "";
  uploadedDocUrls.rcPhotoUri = dp.rcPhotoUri || "";
  uploadedDocUrls.insurancePhotoUri = dp.insurancePhotoUri || "";
  uploadedDocUrls.permitPhotoUri = dp.permitPhotoUri || "";

  const updateUploadLabel = (id, url) => {
    el(id).innerHTML = url
      ? `<span class="badge badge-success">Uploaded</span> <a href="${escapeHtml(url)}" target="_blank" rel="noopener">View Document</a>`
      : "No file uploaded";
  };
  updateUploadLabel("dlUploadStatus", uploadedDocUrls.dlPhotoUri);
  updateUploadLabel("rcUploadStatus", uploadedDocUrls.rcPhotoUri);
  updateUploadLabel("insuranceUploadStatus", uploadedDocUrls.insurancePhotoUri);
  updateUploadLabel("permitUploadStatus", uploadedDocUrls.permitPhotoUri);

  // Lock immutable registration identity fields once created unless creating fresh
  el("kLegalName").readOnly = true;
  el("kVehCat").disabled = true;
  el("kVehReg").readOnly = true;

  const st = dp.verificationStatus || "pending";
  el("kycFormStatusBadge").className = `badge ${
    st === "verified" ? "badge-success" : st === "rejected" || st === "suspended" ? "badge-danger" : "badge-warning"
  }`;
  el("kycFormStatusBadge").textContent = st.toUpperCase();
}

el("kycForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!currentUser) return;
  const legalName = el("kLegalName").value.trim();
  const district = el("kDistrict").value.trim();
  const block = el("kBlock").value.trim();
  const vehicleCategory = el("kVehCat").value;
  const vehicleModel = el("kVehModel").value.trim();
  const vehicleRegNumber = el("kVehReg").value.trim().toUpperCase();
  const dlNum = el("kDlNum").value.trim();
  const rcNum = el("kRcNum").value.trim();
  const insNum = el("kInsNum").value.trim();
  const permitInfo = el("kPermit").value.trim();
  const upi = el("kUpi").value.trim();
  const address = el("kAddress").value.trim();
  const emergName = el("kEmergName").value.trim();
  const emergPhone = normalizeIndianPhone(el("kEmergPhone").value);
  const existing = state.driverProfile;

  try {
    el("saveKycBtn").disabled = true;
    const phone = normalizeIndianPhone(currentUser.phoneNumber || existing?.phone || "+919000000000");

    // Upsert users role=driver if missing
    await setDoc(doc(db, "users", currentUser.uid), {
      uid: currentUser.uid,
      userId: currentUser.uid,
      fullName: legalName,
      phone,
      mobileNumber: phone,
      role: state.identity?.userAccount?.role || "driver",
      preferredLanguage: "en",
      accountStatus: "active",
      mobileVerified: true,
      hasEmergencyContact: Boolean(emergPhone),
      privacyConsentAccepted: true,
      profileCompleted: true,
      createdAt: state.identity?.userAccount?.createdAt || serverTimestamp(),
      updatedAt: serverTimestamp()
    }, { merge: true });

    if (!existing) {
      await setDoc(doc(db, "driver_profiles", currentUser.uid), {
        driverId: currentUser.uid,
        legalName,
        phone,
        address,
        district,
        block,
        vehicleCategory,
        vehicleModel,
        vehicleRegNumber,
        drivingLicenceNumber: dlNum,
        rcNumber: rcNum,
        insuranceNumber: insNum,
        permitInfo,
        settlementUpiOrBank: upi,
        documentPhotoUri: uploadedDocUrls.dlPhotoUri || "",
        dlPhotoUri: uploadedDocUrls.dlPhotoUri || "",
        rcPhotoUri: uploadedDocUrls.rcPhotoUri || "",
        insurancePhotoUri: uploadedDocUrls.insurancePhotoUri || "",
        permitPhotoUri: uploadedDocUrls.permitPhotoUri || "",
        verificationStatus: "pending",
        rejectionReason: "",
        isOnline: false,
        isTestPartner: false,
        ratingAvg: 5.0,
        completedRides: 0,
        emergencyContactName: emergName,
        emergencyContactPhone: emergPhone,
        operatingState: "WB",
        driverTrainingCompleted: true,
        complianceTermsAccepted: true,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      });

      await setDoc(doc(db, "driver_directory", currentUser.uid), {
        driverId: currentUser.uid,
        displayName: legalName,
        vehicleCategory,
        vehicleModel,
        maskedPlate: maskPlate(vehicleRegNumber),
        district,
        block,
        verificationStatus: "pending",
        isOnline: false,
        isTestPartner: false,
        currentLat: state.currentLat,
        currentLng: state.currentLng,
        ratingAvg: 5.0,
        completedRides: 0,
        updatedAt: serverTimestamp()
      });
    } else {
      const updatePayload = {
        address,
        district,
        block,
        vehicleModel,
        drivingLicenceNumber: dlNum,
        rcNumber: rcNum,
        insuranceNumber: insNum,
        permitInfo,
        settlementUpiOrBank: upi,
        dlPhotoUri: uploadedDocUrls.dlPhotoUri || existing.dlPhotoUri || "",
        rcPhotoUri: uploadedDocUrls.rcPhotoUri || existing.rcPhotoUri || "",
        insurancePhotoUri: uploadedDocUrls.insurancePhotoUri || existing.insurancePhotoUri || "",
        permitPhotoUri: uploadedDocUrls.permitPhotoUri || existing.permitPhotoUri || "",
        documentPhotoUri: uploadedDocUrls.dlPhotoUri || existing.dlPhotoUri || existing.documentPhotoUri || "",
        emergencyContactName: emergName,
        emergencyContactPhone: emergPhone,
        complianceTermsAccepted: true,
        updatedAt: serverTimestamp()
      };
      if (existing.verificationStatus === "rejected") {
        updatePayload.verificationStatus = "pending";
        updatePayload.rejectionReason = "";
        updatePayload.isOnline = false;
      }
      await updateDoc(doc(db, "driver_profiles", currentUser.uid), updatePayload);
    }

    showStatus("KYC documents saved & submitted for GoLocal Owner Admin verification.", "success");
  } catch (err) {
    showStatus(`Could not save KYC: ${err?.message || "Validation error"}`, "error");
  } finally {
    el("saveKycBtn").disabled = false;
  }
});

// GPS Telemetry Push
async function publishGpsTelemetry(lat, lng, accuracy = 15, speed = 0, heading = 0) {
  state.currentLat = lat;
  state.currentLng = lng;
  state.hasRealGps = true;
  el("gpsCoordsText").textContent = `GPS: ${lat.toFixed(5)}, ${lng.toFixed(5)}`;

  if (!currentUser) return;
  const dp = state.driverProfile;
  const activeTrip = findActiveCommercialTrip();
  const isOnline = Boolean(dp?.verificationStatus === "verified" && dp?.isOnline);
  const trackingStatus = isOnline || activeTrip ? "active" : "stopped";

  try {
    await rtdbSet(rtdbRef(rtdb, `live_locations/drivers/${currentUser.uid}`), {
      driverId: currentUser.uid,
      lat,
      lng,
      trackingStatus,
      activeBookingId: activeTrip ? activeTrip.id : "",
      accuracyMeters: Number(accuracy || 15),
      speedMps: Number(speed || 0),
      headingDeg: Number(heading || 0),
      updatedAtEpochMs: Date.now()
    });
  } catch (_) {}

  if (dp?.verificationStatus === "verified" && dp?.isOnline) {
    updateDoc(doc(db, "driver_directory", currentUser.uid), {
      isOnline: true,
      currentLat: lat,
      currentLng: lng,
      updatedAt: serverTimestamp()
    }).catch(() => {});
  }

  if (activeTrip) {
    try {
      await rtdbSet(rtdbRef(rtdb, `live_locations/trips/${activeTrip.id}`), {
        bookingId: activeTrip.id,
        customerId: activeTrip.customerId,
        driverId: currentUser.uid,
        lat,
        lng,
        trackingStatus: "active",
        accuracyMeters: Number(accuracy || 15),
        speedMps: Number(speed || 0),
        headingDeg: Number(heading || 0),
        tripStatus: activeTrip.status,
        isTestRide: Boolean(activeTrip.is_test_ride),
        updatedAtEpochMs: Date.now()
      });
      await setDoc(doc(db, "trip_locations", activeTrip.id), {
        bookingId: activeTrip.id,
        customerId: activeTrip.customerId,
        driverId: currentUser.uid,
        lat,
        lng,
        isTestRide: Boolean(activeTrip.is_test_ride),
        isSimulated: false,
        updatedAt: serverTimestamp()
      }, { merge: true });
    } catch (_) {}
  }

  if (sampleModeActive && state.sampleRiderProfile?.availabilityStatus === "online") {
    updateDoc(doc(db, "sample_riders", currentUser.uid), {
      currentLat: lat,
      currentLng: lng,
      locationUpdatedAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    }).catch(() => {});
  }
}

function startGpsWatch() {
  if (!navigator.geolocation) return;
  if (geoWatchId !== null) navigator.geolocation.clearWatch(geoWatchId);
  geoWatchId = navigator.geolocation.watchPosition(
    (pos) => {
      publishGpsTelemetry(
        pos.coords.latitude,
        pos.coords.longitude,
        pos.coords.accuracy,
        pos.coords.speed,
        pos.coords.heading
      );
      renderAll();
    },
    () => {},
    { enableHighAccuracy: true, maximumAge: 5000, timeout: 12000 }
  );
}

el("refreshGpsBtn").addEventListener("click", () => {
  if (!navigator.geolocation) {
    showStatus("Geolocation is unavailable in this browser.", "error");
    return;
  }
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      publishGpsTelemetry(pos.coords.latitude, pos.coords.longitude, pos.coords.accuracy, pos.coords.speed, pos.coords.heading);
      mapCtrl?.flyTo(pos.coords.latitude, pos.coords.longitude, 15);
      renderAll();
    },
    (err) => showStatus(`Location permission denied or unavailable: ${err.message}`, "warning"),
    { enableHighAccuracy: true }
  );
});

el("centerRiderBoraiBtn").addEventListener("click", () => {
  mapCtrl?.flyTo(BORAI_LAUNCH.lat, BORAI_LAUNCH.lng, BORAI_LAUNCH.zoom);
});

// Shift Online / Offline Toggle
el("toggleShiftBtn").addEventListener("click", async () => {
  if (!currentUser) return;
  if (sampleModeActive && state.sampleRiderProfile) {
    const nextAvail = state.sampleRiderProfile.availabilityStatus === "online" ? "offline" : "online";
    await updateDoc(doc(db, "sample_riders", currentUser.uid), {
      availabilityStatus: nextAvail,
      updatedAt: serverTimestamp()
    });
    showStatus(`Sample Rider availability is now ${nextAvail.toUpperCase()}.`, "info");
    return;
  }

  const dp = state.driverProfile;
  if (!dp || dp.verificationStatus !== "verified") {
    showStatus("Commercial shift blocked: Owner Admin must verify your KYC documents first.", "warning");
    return;
  }
  const nextOnline = !dp.isOnline;
  try {
    await updateDoc(doc(db, "driver_profiles", currentUser.uid), {
      isOnline: nextOnline,
      updatedAt: serverTimestamp()
    });
    await updateDoc(doc(db, "driver_directory", currentUser.uid), {
      isOnline: nextOnline,
      currentLat: state.currentLat,
      currentLng: state.currentLng,
      updatedAt: serverTimestamp()
    });
    await rtdbSet(rtdbRef(rtdb, `live_locations/drivers/${currentUser.uid}`), {
      driverId: currentUser.uid,
      lat: state.currentLat,
      lng: state.currentLng,
      trackingStatus: nextOnline ? "active" : "stopped",
      activeBookingId: "",
      updatedAtEpochMs: Date.now()
    });
    showStatus(nextOnline ? "You are now ONLINE and visible for commercial dispatch." : "You are now OFFLINE.", "success");
  } catch (err) {
    showStatus(`Failed to toggle shift: ${err?.message || "Permission denied"}`, "error");
  }
});

// Sample Rider Toggle & Profile Initialization (Only if allowlisted)
el("toggleSampleModeBtn").addEventListener("click", async () => {
  if (!currentUser || (!state.identity?.isSampleAllowlisted && !state.sampleRiderProfile)) return;
  if (!state.sampleRiderProfile) {
    try {
      const phone = normalizeIndianPhone(currentUser.phoneNumber || "+919000000000");
      await setDoc(doc(db, "sample_riders", currentUser.uid), {
        uid: currentUser.uid,
        role: "rider",
        accountType: "sample",
        isSampleRider: true,
        verificationStatus: "sample_approved",
        availabilityStatus: "offline",
        displayName: state.driverProfile?.legalName || state.identity?.userAccount?.fullName || "Sample Rider",
        phone,
        photoUrl: "",
        vehicleCategory: state.driverProfile?.vehicleCategory || "toto",
        emergencyContactName: "",
        emergencyContactPhone: "",
        completedSampleRides: 0,
        totalSampleEarningsInr: 0,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      });
    } catch (err) {
      showStatus(`Unable to activate Sample Rider profile: ${err?.message || "Not allowlisted"}`, "error");
      return;
    }
  }
  sampleModeActive = !sampleModeActive;
  attachListeners();
  renderAll();
});

// SOS Button
el("riderSosBtn").addEventListener("click", async () => {
  if (!currentUser) return;
  const note = window.prompt("Optional SOS emergency note:", "Partner triggered SOS emergency alert");
  if (note === null) return;
  const activeTrip = findActiveCommercialTrip();
  try {
    await addDoc(collection(db, "emergency_events"), {
      userId: currentUser.uid,
      bookingId: activeTrip?.id || "",
      lat: state.currentLat,
      lng: state.currentLng,
      emergencyContactRef: state.driverProfile?.emergencyContactPhone || "",
      notes: note.trim() || "Partner SOS triggered",
      status: "active",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
    showStatus("SOS alert transmitted with live GPS to GoLocal Operations.", "error");
  } catch (err) {
    showStatus(`SOS failed: ${err?.message}`, "error");
  }
});

// Earnings Settlement Request
el("settlementForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!currentUser || state.driverProfile?.verificationStatus !== "verified") {
    showStatus("Only verified commercial partners can submit withdrawal requests.", "warning");
    return;
  }
  const amount = Number(el("settleAmountInput").value);
  const upiRef = el("settleUpiInput").value.trim();
  if (!Number.isFinite(amount) || amount < 50 || amount > 100000 || upiRef.length < 3) {
    showStatus("Enter a valid settlement amount (min ₹50) and UPI/Bank reference.", "error");
    return;
  }
  try {
    await addDoc(collection(db, "driver_settlements"), {
      driverId: currentUser.uid,
      driverName: state.driverProfile.legalName || "Partner",
      amountInr: amount,
      upiOrBankRef: upiRef,
      status: "requested",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
    el("settlementForm").reset();
    showStatus("Withdrawal request submitted to Owner Admin.", "success");
  } catch (err) {
    showStatus(`Withdrawal request failed: ${err?.message}`, "error");
  }
});

el("exportEarningsCsvBtn").addEventListener("click", () => {
  const headers = ["Booking ID", "Gross Fare (INR)", "Commission (%)", "Commission (INR)", "Net Driver Earnings (INR)", "Recorded At"];
  const rows = state.commissions.map((c) => [
    c.bookingId,
    Number(c.grossFareInr || 0).toFixed(2),
    Number(c.commissionRatePct || 0).toFixed(1),
    Number(c.commissionAmountInr || 0).toFixed(2),
    Number(c.netDriverEarningsInr || 0).toFixed(2),
    formatEpochDateTime(c.createdAt)
  ]);
  const csv = buildCsvContent(headers, rows);
  downloadCsvFile(`golocal_partner_earnings_${Date.now()}.csv`, csv);
});

el("riderMarkAllReadBtn").addEventListener("click", async () => {
  const unread = state.notifications.filter((n) => !n.read);
  await Promise.all(unread.map((n) => updateDoc(doc(db, "notifications", n.id), { read: true }).catch(() => {})));
});

function findActiveCommercialTrip() {
  const activeSet = new Set(["assigned", "arriving", "arrived", "in_progress"]);
  return state.myBookings.find((b) => activeSet.has(b.status) && !b.is_test_ride) || null;
}

// Commercial Dispatch Actions
async function acceptBookingAtFare(booking, agreedFare) {
  const dp = state.driverProfile;
  if (!currentUser || !dp || dp.verificationStatus !== "verified") {
    showStatus("Verified commercial KYC required.", "warning");
    return;
  }
  const fare = Math.max(
    Number(booking.minPermittedFareInr || 0),
    Math.min(Number(booking.maxPermittedFareInr || agreedFare), Number(agreedFare))
  );
  const { otpPlaintext, otpHash } = await generateOtpAndHash(booking.id);
  const expiresAtMs = Date.now() + 600 * 1000;
  const expiresAtTs = Timestamp.fromMillis(expiresAtMs);
  const vehicleLabel = [dp.vehicleCategory?.toUpperCase(), dp.vehicleModel, maskPlate(dp.vehicleRegNumber)]
    .filter(Boolean)
    .join(" • ");

  try {
    await runTransaction(db, async (tx) => {
      const bRef = doc(db, "bookings", booking.id);
      const bSnap = await tx.get(bRef);
      if (!bSnap.exists()) throw new Error("Booking no longer exists.");
      if (bSnap.data().status !== "searching") throw new Error("Ride already claimed by another partner.");
      const aRef = doc(db, "ride_assignments", booking.id);
      const aSnap = await tx.get(aRef);
      if (aSnap.exists()) throw new Error("Ride assignment already exists.");

      tx.update(bRef, {
        status: "assigned",
        assignedDriverId: currentUser.uid,
        assignedDriverName: dp.legalName,
        assignedVehicleLabel: vehicleLabel,
        agreedFareInr: fare,
        otp_status: "pending",
        otp_expires_at_ms: expiresAtMs,
        updatedAt: serverTimestamp()
      });

      tx.set(aRef, {
        bookingId: booking.id,
        customerId: booking.customerId,
        driverId: currentUser.uid,
        offerId: "",
        lockedFareInr: fare,
        status: "active",
        is_test_ride: false,
        assignedByUid: currentUser.uid,
        assignedByRole: "driver",
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
    showStatus("Ride accepted! Ask customer for the 6-digit Pickup OTP upon arrival.", "success");
  } catch (err) {
    showStatus(`Accept failed: ${err?.message || "Ride unavailable"}`, "error");
  }
}

async function sendFareBid(booking, proposedFare) {
  const dp = state.driverProfile;
  if (!currentUser || !dp || dp.verificationStatus !== "verified") return;
  const fare = Math.max(
    Number(booking.minPermittedFareInr || 0),
    Math.min(Number(booking.maxPermittedFareInr || proposedFare), Number(proposedFare))
  );
  const etaMinutes = Math.max(2, Math.round((haversineKm(state.currentLat, state.currentLng, booking.pickupLat, booking.pickupLng) / 22) * 60));
  const vehicleLabel = [dp.vehicleCategory?.toUpperCase(), dp.vehicleModel, maskPlate(dp.vehicleRegNumber)]
    .filter(Boolean)
    .join(" • ");

  try {
    await addDoc(collection(db, "ride_offers"), {
      bookingId: booking.id,
      customerId: booking.customerId,
      driverId: currentUser.uid,
      driverName: dp.legalName,
      vehicleCategory: dp.vehicleCategory || "toto",
      vehicleLabel,
      driverRating: Number(dp.ratingAvg || 5.0),
      driverCompletedRides: Number(dp.completedRides || 0),
      isVerifiedDriver: true,
      etaMinutes,
      proposedFareInr: Math.round(fare),
      status: "pending",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
    showStatus(`Sent ₹${Math.round(fare)} offer to ${booking.customerName}.`, "info");
  } catch (err) {
    showStatus(`Offer failed: ${err?.message}`, "error");
  }
}

async function declineBooking(booking) {
  if (!currentUser) return;
  try {
    const rejId = `${booking.id}_${currentUser.uid}`;
    await setDoc(doc(db, "ride_rejections", rejId), {
      driverId: currentUser.uid,
      bookingId: booking.id,
      reason: "declined_by_partner",
      createdAt: serverTimestamp()
    });
  } catch (_) {}
}

async function verifyPickupOtpAndStartTrip(booking, enteredOtp) {
  const cleanOtp = String(enteredOtp || "").trim();
  if (!/^\d{6}$/.test(cleanOtp)) {
    showStatus("Enter the exact 6-digit Customer Pickup OTP.", "warning");
    return;
  }
  const submittedHash = await hashBookingOtp(booking.id, cleanOtp);
  try {
    const batch = writeBatch(db);
    batch.update(doc(db, "ride_otps", booking.id), {
      used: true,
      submittedHash,
      verifiedByUid: currentUser.uid,
      updatedAt: serverTimestamp()
    });
    batch.update(doc(db, "bookings", booking.id), {
      status: "in_progress",
      otp_status: "verified",
      updatedAt: serverTimestamp()
    });
    await batch.commit();
    updateDoc(doc(db, "ride_otp_secrets", booking.id), {
      status: "verified",
      updatedAt: serverTimestamp()
    }).catch(() => {});
    showStatus("Pickup OTP verified! Trip is now IN PROGRESS.", "success");
  } catch (err) {
    try {
      await updateDoc(doc(db, "ride_otps", booking.id), {
        attempts: increment(1),
        lastAttemptAtMs: Date.now(),
        updatedAt: serverTimestamp()
      });
    } catch (_) {}
    showStatus("Incorrect or expired OTP. Wait 2 seconds before retrying (max 5 attempts).", "error");
  }
}

async function completeCommercialTrip(booking) {
  const grossFare = Number(booking.agreedFareInr || booking.estimatedFareInr || 0);
  const cfg = state.fareConfigsMap[booking.vehicleCategory] || {};
  const gstPct = Number(booking.gstPercent ?? cfg.gstPercent ?? 5);
  const commPct = Number(booking.commissionPercent ?? cfg.commissionPercent ?? state.platformSettings?.defaultCommissionPct ?? 12);
  const settlement = computeSettlement({
    grossFareInr: grossFare,
    commissionPercent: commPct,
    gstPercent: gstPct
  });
  const payStatus = booking.paymentMethod === "cash" ? "settled_cash" : "pending_verification";

  try {
    await updateDoc(doc(db, "bookings", booking.id), {
      status: "completed",
      paymentStatus: payStatus,
      updatedAt: serverTimestamp()
    });
    await updateDoc(doc(db, "ride_assignments", booking.id), {
      status: "completed",
      updatedAt: serverTimestamp()
    }).catch(() => {});

    await setDoc(doc(db, "payments", booking.id), {
      bookingId: booking.id,
      customerId: booking.customerId,
      driverId: currentUser.uid,
      grossFareInr: settlement.grossFareInr,
      gstInr: settlement.gstInr,
      platformCommissionInr: settlement.commissionAmountInr,
      netDriverEarningsInr: settlement.netDriverEarningsInr,
      paymentMethod: booking.paymentMethod || "cash",
      invoiceEmail: booking.invoiceEmail || "",
      gatewayReference: "",
      status: payStatus,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });

    await setDoc(doc(db, "commissions", booking.id), {
      bookingId: booking.id,
      driverId: currentUser.uid,
      grossFareInr: settlement.grossFareInr,
      commissionRatePct: commPct,
      commissionAmountInr: settlement.commissionAmountInr,
      netDriverEarningsInr: settlement.netDriverEarningsInr,
      createdAt: serverTimestamp()
    });

    await rtdbUpdate(rtdbRef(rtdb, `live_locations/trips/${booking.id}`), {
      trackingStatus: "stopped",
      tripStatus: "completed",
      updatedAtEpochMs: Date.now()
    }).catch(() => {});

    showStatus(`Trip completed! Net partner earnings: ₹${settlement.netDriverEarningsInr.toFixed(2)}.`, "success");
  } catch (err) {
    showStatus(`Failed to complete trip: ${err?.message}`, "error");
  }
}

function syncActiveChat(bookingId) {
  if (activeChatBookingId === bookingId) return;
  if (activeChatUnsub) {
    try { activeChatUnsub(); } catch (_) {}
    activeChatUnsub = null;
  }
  activeChatBookingId = bookingId || "";
  state.activeChatMessages = [];
  if (!bookingId) return;
  activeChatUnsub = onSnapshot(
    query(collection(db, "booking_messages", bookingId, "messages"), orderBy("createdAt", "asc"), limit(40)),
    (snap) => {
      state.activeChatMessages = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderActiveTripPanel();
    }
  );
}

function renderActiveTripPanel() {
  const panel = el("riderActiveTripPanel");
  const active = findActiveCommercialTrip();
  if (!active || sampleModeActive) {
    panel.classList.add("hidden");
    panel.innerHTML = "";
    syncActiveChat("");
    return;
  }
  syncActiveChat(active.id);
  panel.classList.remove("hidden");

  const status = active.status;
  panel.innerHTML = `
    <div class="space-between">
      <h2 style="margin:0;">Active Trip</h2>
      <span class="badge badge-info">${escapeHtml(status.replace(/_/g, " ").toUpperCase())}</span>
    </div>
    <div class="list-item stack" style="gap:4px;">
      <div><strong>Pickup:</strong> ${escapeHtml(active.pickupLabel)}</div>
      <div><strong>Drop-off:</strong> ${escapeHtml(active.destinationLabel)}</div>
      <div class="list-item-muted">Customer: ${escapeHtml(active.customerName)} • Fare: ₹${Math.round(active.agreedFareInr || active.estimatedFareInr)} (${escapeHtml(active.paymentMethod.toUpperCase())})</div>
      ${active.opsDispatchNote ? `<div class="list-item-muted"><strong>Ops Note:</strong> ${escapeHtml(active.opsDispatchNote)}</div>` : ""}
    </div>
    <div class="row" id="activeTripActionRow"></div>
    ${status === "arrived" ? `
      <div class="list-item stack" style="gap:8px;background:#eff6ff;">
        <strong>Enter Customer 6-Digit Pickup OTP</strong>
        <div class="row">
          <input id="tripOtpVerifyInput" type="tel" maxlength="6" placeholder="123456" style="max-width:160px;" />
          <button id="verifyTripOtpBtn" class="btn btn-primary" type="button">Verify OTP &amp; Start Trip</button>
        </div>
      </div>
    ` : ""}
    <div class="stack" style="gap:8px;">
      <strong style="font-size:0.9rem;">Trip Chat with Customer</strong>
      <div class="chat-box">
        ${state.activeChatMessages.length ? state.activeChatMessages.map((m) => `
          <div class="chat-msg ${m.senderId === currentUser.uid ? "mine" : "theirs"}">
            ${escapeHtml(m.text)}
          </div>
        `).join("") : `<div class="list-item-muted">No messages yet.</div>`}
      </div>
      <div class="row">
        <input id="riderChatInput" type="text" placeholder="Message customer..." style="flex:1;" />
        <button id="sendRiderChatBtn" class="btn btn-secondary" type="button">Send</button>
      </div>
    </div>
  `;

  const actionRow = el("activeTripActionRow");
  if (status === "assigned") {
    const btn = document.createElement("button");
    btn.className = "btn btn-primary";
    btn.textContent = "Head to Pickup (Arriving)";
    btn.onclick = () => updateDoc(doc(db, "bookings", active.id), {
      status: "arriving",
      paymentStatus: active.paymentStatus || "pending",
      updatedAt: serverTimestamp()
    });
    actionRow.appendChild(btn);
  } else if (status === "arriving") {
    const btn = document.createElement("button");
    btn.className = "btn btn-primary";
    btn.textContent = "Arrived at Pickup";
    btn.onclick = () => updateDoc(doc(db, "bookings", active.id), {
      status: "arrived",
      paymentStatus: active.paymentStatus || "pending",
      updatedAt: serverTimestamp()
    });
    actionRow.appendChild(btn);
  } else if (status === "in_progress") {
    const btn = document.createElement("button");
    btn.className = "btn btn-success";
    btn.textContent = "Complete Trip & Settle";
    btn.onclick = () => completeCommercialTrip(active);
    actionRow.appendChild(btn);
  }

  if (["assigned", "arriving", "arrived"].includes(status)) {
    const cancelBtn = document.createElement("button");
    cancelBtn.className = "btn btn-danger";
    cancelBtn.textContent = "Cancel Before Start";
    cancelBtn.onclick = async () => {
      const reason = window.prompt("Cancellation reason:", "Partner unavoidable delay");
      if (!reason) return;
      await updateDoc(doc(db, "bookings", active.id), {
        status: "cancelled",
        paymentStatus: active.paymentStatus || "pending",
        cancellationReason: reason.trim(),
        cancelledBy: currentUser.uid,
        cancelledByRole: "driver",
        cancellationCategory: "driver_cancelled",
        updatedAt: serverTimestamp()
      });
    };
    actionRow.appendChild(cancelBtn);
  }

  el("verifyTripOtpBtn")?.addEventListener("click", () => {
    verifyPickupOtpAndStartTrip(active, el("tripOtpVerifyInput").value);
  });

  el("sendRiderChatBtn")?.addEventListener("click", async () => {
    const text = (el("riderChatInput").value || "").trim();
    if (!text) return;
    el("riderChatInput").value = "";
    await addDoc(collection(db, "booking_messages", active.id, "messages"), {
      senderId: currentUser.uid,
      senderRole: "driver",
      text,
      deliveryState: "sent",
      readByCustomer: false,
      readByDriver: true,
      createdAt: serverTimestamp()
    });
  });

  if (mapCtrl && active.pickupLat && active.destinationLat) {
    fetchOsrmRoute(active.pickupLat, active.pickupLng, active.destinationLat, active.destinationLng)
      .then((res) => {
        mapCtrl.updateRoutes([{ id: "active_trip", color: "#1565C0", coords: res.coords }]);
      })
      .catch(() => {});
  }
}

function renderSampleRiderPanel() {
  const panel = el("sampleRiderPanel");
  const incomingBox = el("incomingRequestsPanel");
  if (!sampleModeActive || !state.sampleRiderProfile) {
    panel.classList.add("hidden");
    incomingBox.classList.remove("hidden");
    return;
  }
  incomingBox.classList.add("hidden");
  panel.classList.remove("hidden");

  const activeSample = state.sampleMyRides.find((r) => ["accepted", "arriving", "arrived", "in_progress"].includes(r.status));
  panel.innerHTML = `
    <div class="space-between">
      <h2 style="margin:0;">Sample Rider Dispatch (Isolated Sandbox)</h2>
      <span class="badge badge-warning">NO COMMERCIAL BILLING</span>
    </div>
    <div class="list-item-muted">
      Completed Sample Rides: <strong>${state.sampleRiderProfile.completedSampleRides || 0}</strong> •
      Sample Earnings: <strong>₹${Math.round(state.sampleRiderProfile.totalSampleEarningsInr || 0)}</strong>
    </div>
    <div id="sampleBody" class="stack"></div>
  `;

  const body = el("sampleBody");
  if (activeSample) {
    body.innerHTML = `
      <div class="list-item stack" style="gap:6px;">
        <div class="space-between">
          <strong>${escapeHtml(activeSample.customerName)}</strong>
          <span class="badge badge-info">${escapeHtml(activeSample.status.toUpperCase())}</span>
        </div>
        <div>${escapeHtml(activeSample.pickupLabel)} → ${escapeHtml(activeSample.destinationLabel)}</div>
        <div class="list-item-muted">Sample Fare: ₹${Math.round(activeSample.estimatedFareInr)}</div>
      </div>
      <div class="row" id="sampleActionRow"></div>
      ${activeSample.status === "arrived" ? `
        <div class="row">
          <input id="sampleOtpInput" type="tel" maxlength="6" placeholder="Sample OTP" style="max-width:150px;" />
          <button id="verifySampleOtpBtn" class="btn btn-primary" type="button">Verify Sample OTP</button>
        </div>
      ` : ""}
    `;
    const row = el("sampleActionRow");
    if (activeSample.status === "accepted") {
      const b = document.createElement("button");
      b.className = "btn btn-primary";
      b.textContent = "Arriving";
      b.onclick = () => updateDoc(doc(db, "sample_rides", activeSample.id), {
        status: "arriving",
        riderLat: state.currentLat,
        riderLng: state.currentLng,
        updatedAt: serverTimestamp()
      });
      row.appendChild(b);
    } else if (activeSample.status === "arriving") {
      const b = document.createElement("button");
      b.className = "btn btn-primary";
      b.textContent = "Arrived";
      b.onclick = () => updateDoc(doc(db, "sample_rides", activeSample.id), {
        status: "arrived",
        riderLat: state.currentLat,
        riderLng: state.currentLng,
        updatedAt: serverTimestamp()
      });
      row.appendChild(b);
    } else if (activeSample.status === "in_progress") {
      const b = document.createElement("button");
      b.className = "btn btn-success";
      b.textContent = "Complete Sample Ride";
      b.onclick = () => updateDoc(doc(db, "sample_rides", activeSample.id), {
        status: "completed",
        riderLat: state.currentLat,
        riderLng: state.currentLng,
        completedAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      });
      row.appendChild(b);
    }
    el("verifySampleOtpBtn")?.addEventListener("click", async () => {
      const otp = (el("sampleOtpInput").value || "").trim();
      if (!/^\d{6}$/.test(otp)) return;
      const hash = await hashBookingOtp(activeSample.id, otp);
      try {
        const batch = writeBatch(db);
        batch.update(doc(db, "sample_ride_otps", activeSample.id), {
          used: true,
          submittedHash: hash,
          verifiedByUid: currentUser.uid,
          updatedAt: serverTimestamp()
        });
        batch.update(doc(db, "sample_rides", activeSample.id), {
          status: "in_progress",
          otpVerified: true,
          otpVerifiedAt: serverTimestamp(),
          riderLat: state.currentLat,
          riderLng: state.currentLng,
          updatedAt: serverTimestamp()
        });
        await batch.commit();
        showStatus("Sample Ride OTP verified!", "success");
      } catch (_) {
        showStatus("Invalid Sample OTP.", "error");
      }
    });
    return;
  }

  if (!state.sampleIncomingRides.length) {
    body.innerHTML = `<div class="list-item-muted">No incoming sample rides right now. Turn Sample shift ONLINE to receive sample dispatch.</div>`;
    return;
  }

  state.sampleIncomingRides.forEach((sr) => {
    const item = document.createElement("div");
    item.className = "list-item stack";
    item.innerHTML = `
      <div class="space-between">
        <strong>${escapeHtml(sr.pickupLabel)} → ${escapeHtml(sr.destinationLabel)}</strong>
        <span class="mono">₹${Math.round(sr.estimatedFareInr)}</span>
      </div>
      <div class="list-item-muted">Customer: ${escapeHtml(sr.customerName)} • ${sr.distanceKm} km</div>
    `;
    const btn = document.createElement("button");
    btn.className = "btn btn-primary";
    btn.textContent = "Accept Sample Ride";
    btn.onclick = async () => {
      await updateDoc(doc(db, "sample_rides", sr.id), {
        status: "accepted",
        assignedSampleRiderId: currentUser.uid,
        assignedSampleRiderName: state.sampleRiderProfile.displayName,
        assignedSampleRiderPhone: state.sampleRiderProfile.phone,
        riderLat: state.currentLat,
        riderLng: state.currentLng,
        updatedAt: serverTimestamp()
      });
    };
    item.appendChild(btn);
    body.appendChild(item);
  });
}

function renderAll() {
  const dp = state.driverProfile;
  const kycStatus = dp?.verificationStatus || "unregistered";
  const isVerified = kycStatus === "verified";

  el("riderHeaderName").textContent = dp?.legalName || state.sampleRiderProfile?.displayName || state.identity?.userAccount?.fullName || "Partner";
  el("riderVehicleChip").textContent = (dp?.vehicleCategory || state.sampleRiderProfile?.vehicleCategory || "PARTNER").toUpperCase();
  el("riderSampleBadge").classList.toggle("hidden", !sampleModeActive);

  el("riderKycBadge").classList.remove("hidden");
  el("riderKycBadge").className = `badge ${isVerified ? "badge-success" : kycStatus === "rejected" || kycStatus === "suspended" ? "badge-danger" : "badge-warning"}`;
  el("riderKycBadge").textContent = `KYC: ${kycStatus.toUpperCase()}`;

  const canSample = Boolean(state.identity?.isSampleAllowlisted || state.sampleRiderProfile);
  el("toggleSampleModeBtn").classList.toggle("hidden", !canSample);
  el("toggleSampleModeBtn").textContent = sampleModeActive ? "Switch to Commercial Mode" : "Switch to Sample Rider Mode";

  if (sampleModeActive && state.sampleRiderProfile) {
    const isSampleOn = state.sampleRiderProfile.availabilityStatus === "online";
    el("toggleShiftBtn").disabled = false;
    el("toggleShiftBtn").className = `btn ${isSampleOn ? "btn-danger" : "btn-success"}`;
    el("toggleShiftBtn").textContent = isSampleOn ? "Go Sample Offline" : "Go Sample Online";
    el("riderHeaderSub").textContent = `Sample Mode • ${isSampleOn ? "ONLINE" : "OFFLINE"}`;
    el("kycGateBanner").classList.add("hidden");
  } else if (!isVerified) {
    el("toggleShiftBtn").disabled = true;
    el("toggleShiftBtn").textContent = "KYC Verification Required";
    el("riderHeaderSub").textContent = "Commercial dispatch locked until Owner Admin approves KYC";
    el("kycGateBanner").classList.remove("hidden");
    el("kycGateBanner").textContent = kycStatus === "unregistered"
      ? "Complete your KYC & Vehicle Profile tab and upload documents for Owner Admin approval."
      : kycStatus === "rejected"
        ? `KYC Rejected: ${dp?.rejectionReason || "Fix documents in KYC tab and resubmit."}`
        : `Commercial status: ${kycStatus.toUpperCase()}. Dispatch unlocks automatically once Owner Admin approves your documents.`;
  } else {
    el("kycGateBanner").classList.add("hidden");
    el("toggleShiftBtn").disabled = false;
    el("toggleShiftBtn").className = `btn ${dp.isOnline ? "btn-danger" : "btn-success"}`;
    el("toggleShiftBtn").textContent = dp.isOnline ? "Go Offline" : "Go Online";
    el("riderHeaderSub").textContent = `${dp.isOnline ? "ONLINE & Receiving Requests" : "OFFLINE"} • ${dp.vehicleModel || dp.vehicleCategory.toUpperCase()} (${maskPlate(dp.vehicleRegNumber)})`;
  }

  renderActiveTripPanel();
  renderSampleRiderPanel();

  // Incoming commercial queue
  const incomingList = el("incomingRequestsList");
  const eligible = isVerified
    ? state.searchingBookings.filter((b) =>
        !b.is_test_ride &&
        !state.rejectedBookingIds.has(b.id) &&
        (!dp?.vehicleCategory || b.vehicleCategory === dp.vehicleCategory || b.vehicleCategory === "scheduled")
      )
    : [];
  el("incomingCountBadge").textContent = `${eligible.length} live`;

  if (!isVerified) {
    incomingList.innerHTML = `<div class="list-item-muted">Submit & verify commercial KYC to receive commercial rides.</div>`;
  } else if (!eligible.length) {
    incomingList.innerHTML = `<div class="list-item-muted">No open customer requests nearby yet. Stay ONLINE for live requests.</div>`;
  } else {
    incomingList.innerHTML = "";
    eligible.forEach((b) => {
      const distToPickup = haversineKm(state.currentLat, state.currentLng, b.pickupLat || BORAI_LAUNCH.lat, b.pickupLng || BORAI_LAUNCH.lng).toFixed(1);
      const minFare = Math.round(b.minPermittedFareInr || b.estimatedFareInr || 30);
      const maxFare = Math.max(minFare, Math.round(b.maxPermittedFareInr || b.estimatedFareInr || minFare));
      const estFare = Math.round(b.estimatedFareInr || minFare);

      const card = document.createElement("div");
      card.className = "list-item stack";
      card.style.gap = "8px";
      card.innerHTML = `
        <div class="space-between">
          <strong>${escapeHtml(b.pickupLabel)} → ${escapeHtml(b.destinationLabel)}</strong>
          <span class="mono badge badge-success">₹${estFare}</span>
        </div>
        <div class="list-item-muted">${escapeHtml(b.customerName)} • ${distToPickup} km away • Trip ${b.distanceKm} km • ${escapeHtml(b.paymentMethod.toUpperCase())}</div>
        <div class="row" style="gap:8px;">
          <label class="list-item-muted">Fare Bid (₹${minFare}–₹${maxFare}):</label>
          <input type="range" min="${minFare}" max="${maxFare}" value="${estFare}" step="1" class="bid-slider" style="flex:1;" />
          <span class="mono bid-val">₹${estFare}</span>
        </div>
        <div class="row">
          <button type="button" class="btn btn-primary accept-btn">Accept ₹${estFare}</button>
          <button type="button" class="btn btn-secondary bid-btn">Send Bid</button>
          <button type="button" class="btn btn-danger decline-btn">Decline</button>
        </div>
      `;
      const slider = card.querySelector(".bid-slider");
      const valSpan = card.querySelector(".bid-val");
      const bidBtn = card.querySelector(".bid-btn");
      slider.oninput = () => {
        valSpan.textContent = `₹${slider.value}`;
        bidBtn.textContent = `Send Bid ₹${slider.value}`;
      };
      card.querySelector(".accept-btn").onclick = () => acceptBookingAtFare(b, estFare);
      bidBtn.onclick = () => sendFareBid(b, Number(slider.value));
      card.querySelector(".decline-btn").onclick = () => declineBooking(b);
      incomingList.appendChild(card);
    });
  }

  // Map markers
  if (mapCtrl) {
    const markers = [
      { id: "me", lat: state.currentLat, lng: state.currentLng, title: "My Partner Location", color: "#15803D", radius: 9 }
    ];
    eligible.forEach((b) => {
      if (b.pickupLat && b.pickupLng) {
        markers.push({
          id: `req_${b.id}`,
          lat: b.pickupLat,
          lng: b.pickupLng,
          title: `Pickup: ${b.pickupLabel}`,
          subtitle: `₹${Math.round(b.estimatedFareInr)} • ${b.vehicleCategory.toUpperCase()}`,
          color: "#F59E0B",
          radius: 7
        });
      }
    });
    const act = findActiveCommercialTrip();
    if (act?.pickupLat) {
      markers.push({ id: "act_p", lat: act.pickupLat, lng: act.pickupLng, title: `Pickup: ${act.pickupLabel}`, color: "#1565C0", radius: 8 });
      if (act.destinationLat) {
        markers.push({ id: "act_d", lat: act.destinationLat, lng: act.destinationLng, title: `Drop: ${act.destinationLabel}`, color: "#DC2626", radius: 8 });
      }
    }
    mapCtrl.updateMarkers(markers);
  }

  // Earnings cards
  const now = Date.now();
  const sumWithin = (msWindow) =>
    state.commissions.reduce((acc, c) => {
      const ms = c.createdAt?.toMillis ? c.createdAt.toMillis() : (c.createdAt?.seconds || 0) * 1000;
      if (!msWindow || now - ms <= msWindow) {
        return acc + Number(c.netDriverEarningsInr || 0);
      }
      return acc;
    }, 0);

  el("earn24h").textContent = `₹${Math.round(sumWithin(24 * 3600 * 1000))}`;
  el("earn7d").textContent = `₹${Math.round(sumWithin(7 * 24 * 3600 * 1000))}`;
  el("earn30d").textContent = `₹${Math.round(sumWithin(30 * 24 * 3600 * 1000))}`;
  el("earnTotal").textContent = `₹${Math.round(sumWithin(0))}`;

  el("riderCommissionsList").innerHTML = state.commissions.length
    ? state.commissions.map((c) => `
      <div class="list-item space-between">
        <div>
          <strong>Booking ${escapeHtml(c.bookingId.slice(0, 8))}</strong>
          <div class="list-item-muted">Gross ₹${Number(c.grossFareInr || 0).toFixed(0)} • Fee ${Number(c.commissionRatePct || 0)}% (₹${Number(c.commissionAmountInr || 0).toFixed(1)})</div>
        </div>
        <div class="mono" style="color:var(--success);font-weight:700;">+₹${Number(c.netDriverEarningsInr || 0).toFixed(2)}</div>
      </div>
    `).join("")
    : `<div class="list-item-muted">No completed commercial trips recorded yet.</div>`;

  el("riderSettlementsList").innerHTML = state.settlements.length
    ? state.settlements.map((s) => `
      <div class="list-item space-between">
        <div>
          <strong class="mono">₹${Math.round(s.amountInr)}</strong>
          <div class="list-item-muted">${escapeHtml(s.upiOrBankRef)} • ${escapeHtml(formatEpochDateTime(s.createdAt))}</div>
        </div>
        <span class="badge ${s.status === "paid" ? "badge-success" : s.status === "rejected" ? "badge-danger" : "badge-warning"}">${escapeHtml(s.status.toUpperCase())}</span>
      </div>
    `).join("")
    : `<div class="list-item-muted">No withdrawal requests submitted yet.</div>`;

  // History
  const hist = state.myBookings.filter((b) => b.status === "completed" || b.status === "cancelled");
  el("riderHistoryList").innerHTML = hist.length
    ? hist.map((b) => `
      <div class="list-item space-between">
        <div>
          <strong>${escapeHtml(b.pickupLabel)} → ${escapeHtml(b.destinationLabel)}</strong>
          <div class="list-item-muted">${escapeHtml(b.customerName)} • ${escapeHtml(formatEpochDateTime(b.createdAt))} • ${escapeHtml(b.paymentMethod.toUpperCase())}</div>
        </div>
        <div class="row">
          <span class="badge ${b.status === "completed" ? "badge-success" : "badge-danger"}">${escapeHtml(b.status.toUpperCase())}</span>
          <span class="mono">₹${Math.round(b.agreedFareInr || b.estimatedFareInr)}</span>
        </div>
      </div>
    `).join("")
    : `<div class="list-item-muted">No trip history yet.</div>`;

  // Notifications
  el("riderAlertsList").innerHTML = state.notifications.length
    ? state.notifications.map((n) => `
      <div class="list-item space-between" style="${n.read ? "" : "border-color:var(--primary);background:#eff6ff;"}">
        <div>
          <strong>${escapeHtml(n.title)}</strong>
          <div class="list-item-muted">${escapeHtml(n.body)}</div>
        </div>
        <span class="badge ${n.read ? "badge-muted" : "badge-info"}">${n.read ? "READ" : "NEW"}</span>
      </div>
    `).join("")
    : `<div class="list-item-muted">No partner notifications yet.</div>`;
}

function attachListeners() {
  clearSubscriptions();
  if (!currentUser) return;

  unsubscribers.push(
    onSnapshot(doc(db, "driver_profiles", currentUser.uid), (snap) => {
      state.driverProfile = snap.exists() ? { id: snap.id, ...snap.data() } : null;
      populateKycForm();
      renderAll();
    })
  );

  unsubscribers.push(
    onSnapshot(doc(db, "sample_riders", currentUser.uid), (snap) => {
      state.sampleRiderProfile = snap.exists() ? { id: snap.id, ...snap.data() } : null;
      renderAll();
    }, () => {})
  );

  unsubscribers.push(
    onSnapshot(collection(db, "fare_configs"), (snap) => {
      const m = {};
      snap.forEach((d) => { m[d.id] = { id: d.id, ...d.data() }; });
      state.fareConfigsMap = m;
    })
  );

  unsubscribers.push(
    onSnapshot(doc(db, "platform_settings", "global"), (snap) => {
      state.platformSettings = snap.exists() ? snap.data() : null;
    })
  );

  unsubscribers.push(
    onSnapshot(query(collection(db, "bookings"), where("assignedDriverId", "==", currentUser.uid), orderBy("createdAt", "desc"), limit(60)), (snap) => {
      state.myBookings = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderAll();
    })
  );

  unsubscribers.push(
    onSnapshot(query(collection(db, "bookings"), where("status", "==", "searching"), where("is_test_ride", "==", false), orderBy("createdAt", "desc"), limit(30)), (snap) => {
      state.searchingBookings = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderAll();
    }, () => {
      state.searchingBookings = [];
      renderAll();
    })
  );

  unsubscribers.push(
    onSnapshot(query(collection(db, "ride_rejections"), where("driverId", "==", currentUser.uid), limit(100)), (snap) => {
      const set = new Set();
      snap.forEach((d) => d.data().bookingId && set.add(d.data().bookingId));
      state.rejectedBookingIds = set;
      renderAll();
    })
  );

  unsubscribers.push(
    onSnapshot(query(collection(db, "commissions"), where("driverId", "==", currentUser.uid), orderBy("createdAt", "desc"), limit(100)), (snap) => {
      state.commissions = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderAll();
    })
  );

  unsubscribers.push(
    onSnapshot(query(collection(db, "driver_settlements"), where("driverId", "==", currentUser.uid), orderBy("createdAt", "desc"), limit(40)), (snap) => {
      state.settlements = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderAll();
    })
  );

  unsubscribers.push(
    onSnapshot(query(collection(db, "notifications"), where("userId", "==", currentUser.uid), orderBy("createdAt", "desc"), limit(40)), (snap) => {
      state.notifications = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      renderAll();
    })
  );

  if (state.identity?.isSampleAllowlisted || state.identity?.sampleRiderProfile) {
    unsubscribers.push(
      onSnapshot(query(collection(db, "sample_rides"), where("assignedSampleRiderId", "==", currentUser.uid), limit(30)), (snap) => {
        state.sampleMyRides = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
        renderAll();
      }, () => {})
    );
    unsubscribers.push(
      onSnapshot(query(collection(db, "sample_rides"), where("status", "==", "incoming"), limit(20)), (snap) => {
        state.sampleIncomingRides = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
        renderAll();
      }, () => {})
    );
  }
}

watchAuth(async (user) => {
  clearSubscriptions();
  currentUser = user;
  if (!user) {
    el("riderSignOutBtn").classList.add("hidden");
    el("riderSessionBadge").classList.add("hidden");
    el("riderKycBadge").classList.add("hidden");
    el("riderMainSection").classList.add("hidden");
    el("riderAuthCard").classList.remove("hidden");
    return;
  }

  state.identity = await resolveAppIdentity(user);
  state.driverProfile = state.identity.driverProfile;
  state.sampleRiderProfile = state.identity.sampleRiderProfile;

  el("riderAuthCard").classList.add("hidden");
  el("riderMainSection").classList.remove("hidden");
  el("riderSignOutBtn").classList.remove("hidden");
  el("riderSessionBadge").classList.remove("hidden");
  el("riderSessionBadge").textContent = user.phoneNumber || user.email || "Partner";

  if (!mapCtrl) {
    mapCtrl = createOsmMap("riderMap", {
      center: [BORAI_LAUNCH.lng, BORAI_LAUNCH.lat],
      zoom: BORAI_LAUNCH.zoom
    });
  }
  populateKycForm();
  attachListeners();
  startGpsWatch();
  renderAll();
});
