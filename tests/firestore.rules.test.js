const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const crypto = require("crypto");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-golocal-v2";
const ADMIN_EMAIL = "owner@example.com";
const CUSTOMER_UID = "cust_1";
const DRIVER_UID = "drv_1";
const UNVERIFIED_UID = "drv_pending";
const OPS_UID = "ops_1";

const [host, portStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const port = parseInt(portStr, 10);

function now() {
  return new Date();
}

function sha256Otp(bookingId, otp) {
  return crypto.createHash("sha256").update(`${bookingId}:${otp}`).digest("hex");
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules, host, port }
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  if (testEnv) await testEnv.clearFirestore();
});

test("Partner KYC accepts Firebase Storage HTTPS download URLs up to 1000 chars and blocks self-verification", async () => {
  const drvDb = testEnv.authenticatedContext(DRIVER_UID, { phone_number: "+919800001111" }).firestore();
  const longStorageUrl = "https://firebasestorage.googleapis.com/v0/b/gen-lang-client-0818250755.firebasestorage.app/o/kyc_docs%2Fdrv_1%2Fdl.jpg?alt=media&token=1234567890abcdef";

  // Self-verifying on create is denied
  await assertFails(
    drvDb.collection("driver_profiles").doc(DRIVER_UID).set({
      driverId: DRIVER_UID,
      legalName: "Ramesh Mondal",
      phone: "+919800001111",
      district: "Paschim Medinipur",
      vehicleCategory: "toto",
      vehicleRegNumber: "WB34AB1234",
      drivingLicenceNumber: "WB34-20220001",
      dlPhotoUri: longStorageUrl,
      verificationStatus: "verified",
      isOnline: false,
      createdAt: now(),
      updatedAt: now()
    })
  );

  // Pending creation with Firebase Storage URL succeeds
  await assertSucceeds(
    drvDb.collection("driver_profiles").doc(DRIVER_UID).set({
      driverId: DRIVER_UID,
      legalName: "Ramesh Mondal",
      phone: "+919800001111",
      district: "Paschim Medinipur",
      vehicleCategory: "toto",
      vehicleRegNumber: "WB34AB1234",
      drivingLicenceNumber: "WB34-20220001",
      dlPhotoUri: longStorageUrl,
      verificationStatus: "pending",
      isOnline: false,
      createdAt: now(),
      updatedAt: now()
    })
  );
});

test("Operations Staff can triage complaints, flag bookings, and atomically assign verified riders, but cannot touch fare_configs or KYC", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const admin = ctx.firestore();
    await admin.collection("operations_staff").doc(OPS_UID).set({
      userId: OPS_UID,
      displayName: "Desk Ops",
      assignedDistrict: "Paschim Medinipur",
      isActive: true,
      createdAt: now(),
      updatedAt: now()
    });
    await admin.collection("driver_profiles").doc(DRIVER_UID).set({
      driverId: DRIVER_UID,
      legalName: "Verified Partner",
      phone: "+919800002222",
      district: "Paschim Medinipur",
      vehicleCategory: "toto",
      vehicleRegNumber: "WB34E5555",
      drivingLicenceNumber: "WB34-999",
      verificationStatus: "verified",
      isOnline: true,
      createdAt: now(),
      updatedAt: now()
    });
    await admin.collection("bookings").doc("bk_ops_1").set({
      customerId: CUSTOMER_UID,
      customerName: "Arijit",
      bookingType: "now",
      vehicleCategory: "toto",
      pickupLabel: "Borai",
      destinationLabel: "Mohanpur",
      distanceKm: 5.5,
      estimatedFareInr: 80,
      minPermittedFareInr: 72,
      maxPermittedFareInr: 100,
      paymentMethod: "cash",
      paymentStatus: "pending",
      status: "searching",
      is_test_ride: false,
      createdAt: now(),
      updatedAt: now()
    });
    await admin.collection("complaints").doc("cmp_1").set({
      reporterId: CUSTOMER_UID,
      bookingId: "bk_ops_1",
      category: "delay",
      description: "Waiting over 12 mins at Borai stand",
      status: "open",
      createdAt: now(),
      updatedAt: now()
    });
  });

  const opsDb = testEnv.authenticatedContext(OPS_UID, { phone_number: "+919800003333" }).firestore();

  // Ops Staff cannot alter fare_configs or approve driver KYC
  await assertFails(
    opsDb.collection("fare_configs").doc("toto").set({
      vehicleCategory: "toto",
      version: 9,
      baseFareInr: 100,
      perKmRateInr: 30,
      minimumFareInr: 100,
      gstPercent: 5,
      commissionPercent: 10,
      allowDriverFareProposal: true,
      isEnabled: true,
      createdAt: now(),
      updatedAt: now()
    })
  );
  await assertFails(
    opsDb.collection("driver_profiles").doc(DRIVER_UID).update({
      verificationStatus: "rejected",
      updatedAt: now()
    })
  );

  // Ops Staff can add dispatch note & escalation flag on booking
  await assertSucceeds(
    opsDb.collection("bookings").doc("bk_ops_1").update({
      opsDispatchNote: "Calling nearest toto near Borai market",
      opsEscalationFlag: true,
      opsUpdatedBy: OPS_UID,
      updatedAt: now()
    })
  );

  // Ops Staff can atomically assign verified commercial driver + ride_assignment + ride_otps
  const expiresAt = new Date(Date.now() + 600000);
  const batch = opsDb.batch();
  batch.update(opsDb.collection("bookings").doc("bk_ops_1"), {
    status: "assigned",
    assignedDriverId: DRIVER_UID,
    assignedDriverName: "Verified Partner",
    assignedVehicleLabel: "TOTO • WB••5555",
    agreedFareInr: 80,
    otp_status: "pending",
    otp_expires_at_ms: Date.now() + 600000,
    assignedByOpsUid: OPS_UID,
    opsAssignmentNote: "Dispatched from Ops",
    updatedAt: now()
  });
  batch.set(opsDb.collection("ride_assignments").doc("bk_ops_1"), {
    bookingId: "bk_ops_1",
    customerId: CUSTOMER_UID,
    driverId: DRIVER_UID,
    offerId: "",
    lockedFareInr: 80,
    status: "active",
    is_test_ride: false,
    assignedByUid: OPS_UID,
    assignedByRole: "ops_staff",
    createdAt: now(),
    updatedAt: now()
  });
  batch.set(opsDb.collection("ride_otps").doc("bk_ops_1"), {
    bookingId: "bk_ops_1",
    customerId: CUSTOMER_UID,
    otpHash: sha256Otp("bk_ops_1", "482910"),
    submittedHash: "",
    used: false,
    overridden: false,
    overrideReason: "",
    overriddenByUid: "",
    verifiedByUid: "",
    attempts: 0,
    maxAttempts: 5,
    lastAttemptAtMs: 0,
    expiresAt,
    createdAt: now(),
    updatedAt: now()
  });
  await assertSucceeds(batch.commit());

  // Ops Staff can triage complaint open -> investigating -> resolved
  await assertSucceeds(
    opsDb.collection("complaints").doc("cmp_1").update({
      status: "investigating",
      resolutionNote: "Partner contacted",
      updatedAt: now()
    })
  );
  await assertSucceeds(
    opsDb.collection("complaints").doc("cmp_1").update({
      status: "resolved",
      resolutionNote: "Trip picked up safely",
      updatedAt: now()
    })
  );
});

test("Owner Admin can configure platform promo banners and Web user_tokens register cleanly", async () => {
  const adminDb = testEnv.authenticatedContext("owner_uid", {
    email: ADMIN_EMAIL,
    email_verified: true,
    admin: true,
    ownerAdmin: true
  }).firestore();

  await assertSucceeds(
    adminDb.collection("platform_settings").doc("global").set({
      helplineNumber: "+911800123456",
      promoBannerTitle: "Borai Launch Offer",
      promoBannerText: "10% off first Toto ride",
      promoCode: "BORAI10",
      promoDiscountPct: 10,
      cancellationPolicySummary: "Free cancellation before partner arrives.",
      updatedAt: now()
    })
  );

  const custDb = testEnv.authenticatedContext(CUSTOMER_UID, { phone_number: "+919800004444" }).firestore();
  await assertSucceeds(
    custDb.collection("user_tokens").doc(`${CUSTOMER_UID}_web`).set({
      userId: CUSTOMER_UID,
      platform: "web",
      appVariant: "customer",
      active: true,
      updatedAt: now()
    })
  );
});

test("Unverified rider cannot read commercial searching bookings or go online", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const admin = ctx.firestore();
    await admin.collection("driver_profiles").doc(UNVERIFIED_UID).set({
      driverId: UNVERIFIED_UID,
      legalName: "Pending Partner",
      phone: "+919800005555",
      district: "Paschim Medinipur",
      vehicleCategory: "toto",
      vehicleRegNumber: "WB34P0001",
      drivingLicenceNumber: "WB34-0001",
      verificationStatus: "pending",
      isOnline: false,
      createdAt: now(),
      updatedAt: now()
    });
    await admin.collection("bookings").doc("bk_live_1").set({
      customerId: CUSTOMER_UID,
      customerName: "Suman",
      bookingType: "now",
      vehicleCategory: "toto",
      pickupLabel: "Borai",
      destinationLabel: "Egra",
      distanceKm: 10,
      estimatedFareInr: 130,
      minPermittedFareInr: 110,
      maxPermittedFareInr: 160,
      paymentMethod: "cash",
      paymentStatus: "pending",
      status: "searching",
      is_test_ride: false,
      createdAt: now(),
      updatedAt: now()
    });
  });

  const pendingDb = testEnv.authenticatedContext(UNVERIFIED_UID, { phone_number: "+919800005555" }).firestore();
  await assertFails(pendingDb.collection("bookings").doc("bk_live_1").get());
  await assertFails(
    pendingDb.collection("driver_profiles").doc(UNVERIFIED_UID).update({
      isOnline: true,
      updatedAt: now()
    })
  );
});

test("Customer participant can transition their searching booking to expired after 45s timeout while non-participants cannot", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const admin = ctx.firestore();
    await admin.collection("bookings").doc("bk_expire_1").set({
      customerId: CUSTOMER_UID,
      customerName: "Arijit",
      bookingType: "now",
      vehicleCategory: "toto",
      pickupLabel: "Borai",
      destinationLabel: "Mohanpur",
      distanceKm: 5.0,
      estimatedFareInr: 75,
      minPermittedFareInr: 65,
      maxPermittedFareInr: 95,
      paymentMethod: "cash",
      paymentStatus: "pending",
      status: "searching",
      is_test_ride: false,
      createdAt: now(),
      updatedAt: now()
    });
  });

  const otherDb = testEnv.authenticatedContext("other_cust", { phone_number: "+919800009999" }).firestore();
  await assertFails(
    otherDb.collection("bookings").doc("bk_expire_1").update({
      status: "expired",
      cancellationReason: "No rider accepted within 45 seconds",
      updatedAt: now()
    })
  );

  const custDb = testEnv.authenticatedContext(CUSTOMER_UID, { phone_number: "+919800004444" }).firestore();
  await assertSucceeds(
    custDb.collection("bookings").doc("bk_expire_1").update({
      status: "expired",
      cancellationReason: "No rider accepted within 45 seconds",
      updatedAt: now()
    })
  );
});

test("Payment safety, refund preconditions, and owner self-service PII deletion enforce strict rules", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const admin = ctx.firestore();
    await admin.collection("users").doc(CUSTOMER_UID).set({
      uid: CUSTOMER_UID,
      userId: CUSTOMER_UID,
      fullName: "Arijit Mondal",
      email: "arijit@example.com",
      phone: "+919800004444",
      mobileNumber: "+919800004444",
      photoUrl: "https://example.com/p.jpg",
      role: "customer",
      preferredLanguage: "en",
      accountStatus: "active",
      hasEmergencyContact: true,
      createdAt: now(),
      updatedAt: now()
    });
    await admin.collection("emergency_contacts").doc(CUSTOMER_UID).set({
      userId: CUSTOMER_UID,
      emergencyContactNumber: "+919800009999",
      relationshipNote: "Father",
      createdAt: now(),
      updatedAt: now()
    });
    await admin.collection("bookings").doc("bk_upi_trip").set({
      customerId: CUSTOMER_UID,
      customerName: "Arijit",
      assignedDriverId: DRIVER_UID,
      bookingType: "now",
      vehicleCategory: "toto",
      pickupLabel: "Borai",
      destinationLabel: "Mohanpur",
      distanceKm: 5.0,
      estimatedFareInr: 105,
      agreedFareInr: 105,
      minPermittedFareInr: 90,
      maxPermittedFareInr: 130,
      paymentMethod: "upi",
      paymentStatus: "pending",
      status: "in_progress",
      is_test_ride: false,
      createdAt: now(),
      updatedAt: now()
    });
    await admin.collection("bookings").doc("bk_test_completed").set({
      customerId: CUSTOMER_UID,
      customerName: "Arijit",
      assignedDriverId: DRIVER_UID,
      bookingType: "now",
      vehicleCategory: "toto",
      pickupLabel: "Borai",
      destinationLabel: "Mohanpur",
      distanceKm: 2.0,
      estimatedFareInr: 40,
      minPermittedFareInr: 35,
      maxPermittedFareInr: 50,
      paymentMethod: "cash",
      paymentStatus: "pending",
      status: "completed",
      is_test_ride: true,
      test_mode: true,
      createdAt: now(),
      updatedAt: now()
    });
  });

  const drvDb = testEnv.authenticatedContext(DRIVER_UID, { phone_number: "+919800002222" }).firestore();
  const custDb = testEnv.authenticatedContext(CUSTOMER_UID, { phone_number: "+919800004444" }).firestore();

  // Driver CANNOT mark commercial UPI booking as 'paid' directly
  await assertFails(
    drvDb.collection("bookings").doc("bk_upi_trip").update({
      status: "completed",
      paymentStatus: "paid",
      updatedAt: now()
    })
  );
  // Driver CANNOT create a commercial UPI payment record with status 'paid'
  await assertFails(
    drvDb.collection("payments").doc("bk_upi_trip").set({
      bookingId: "bk_upi_trip",
      customerId: CUSTOMER_UID,
      driverId: DRIVER_UID,
      grossFareInr: 105,
      gstInr: 5,
      platformCommissionInr: 10,
      netDriverEarningsInr: 90,
      paymentMethod: "upi",
      status: "paid",
      createdAt: now(),
      updatedAt: now()
    })
  );
  // Driver CAN complete commercial UPI trip with pending_verification
  await assertSucceeds(
    drvDb.collection("bookings").doc("bk_upi_trip").update({
      status: "completed",
      paymentStatus: "pending_verification",
      updatedAt: now()
    })
  );

  // Customer CANNOT request refund on test rides, CAN request refund on completed commercial ride
  await assertFails(
    custDb.collection("refunds").doc("ref_test_1").set({
      bookingId: "bk_test_completed",
      customerId: CUSTOMER_UID,
      amountInr: 40,
      reason: "Test ride refund should fail",
      status: "requested",
      createdAt: now(),
      updatedAt: now()
    })
  );
  await assertSucceeds(
    custDb.collection("refunds").doc("ref_real_1").set({
      bookingId: "bk_upi_trip",
      customerId: CUSTOMER_UID,
      amountInr: 105,
      reason: "Double UPI debit during settlement",
      status: "requested",
      createdAt: now(),
      updatedAt: now()
    })
  );

  // Arbitrary accountStatus mutation blocked, but full PII-scrub self-anonymization + emergency_contacts self-delete succeed
  await assertFails(
    custDb.collection("users").doc(CUSTOMER_UID).update({
      accountStatus: "restricted",
      updatedAt: now()
    })
  );
  await assertSucceeds(
    custDb.collection("users").doc(CUSTOMER_UID).update({
      fullName: "Deleted User",
      email: "",
      phone: "",
      mobileNumber: "",
      photoUrl: "",
      hasEmergencyContact: false,
      accountStatus: "deletion_requested",
      updatedAt: now()
    })
  );
  await assertSucceeds(custDb.collection("emergency_contacts").doc(CUSTOMER_UID).delete());
});
