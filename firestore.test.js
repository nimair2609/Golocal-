const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const CUSTOMER_UID = "customer_alice";
const OTHER_UID = "customer_bob";
const DRIVER_UID = "driver_rahul";
const UNVERIFIED_DRIVER_UID = "driver_pending";
const DELEGATED_ADMIN_UID = "admin_sub";
const EMERGENCY_RESPONDER_UID = "sos_responder_1";
const ADMIN_EMAIL = "renukadas083@gmail.com";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function nowTs() {
  return new Date();
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: denied reading bookings, users, and private emergency_contacts", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("bookings").get());
  await assertFails(unauthDb.collection("users").doc(CUSTOMER_UID).get());
  await assertFails(unauthDb.collection("emergency_contacts").doc(CUSTOMER_UID).get());
  await assertFails(unauthDb.collection("platform_settings").doc("global").get());
});

test("User registration & update: supports mobile OTP registration fields and prevents escalating to admin role", async () => {
  const db = testEnv.authenticatedContext(CUSTOMER_UID, { phone_number: "+919876543210" }).firestore();
  await assertFails(
    db.collection("users").doc(CUSTOMER_UID).set({
      uid: CUSTOMER_UID,
      userId: CUSTOMER_UID,
      fullName: "Alice Sen",
      role: "admin",
      preferredLanguage: "en",
      accountStatus: "active",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    db.collection("users").doc(CUSTOMER_UID).set({
      uid: CUSTOMER_UID,
      userId: CUSTOMER_UID,
      fullName: "Alice Sen",
      email: "alice.invoices@example.com",
      phone: "+919876543210",
      mobileNumber: "+919876543210",
      role: "customer",
      preferredLanguage: "bn",
      privacyConsentAccepted: true,
      profileCompleted: true,
      emailVerified: false,
      mobileVerified: true,
      hasEmergencyContact: true,
      accountStatus: "active",
      accountCreatedAt: nowTs(),
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  // Owner can update invoice email & profileCompleted
  await assertSucceeds(
    db.collection("users").doc(CUSTOMER_UID).update({
      email: "alice.gst@example.com",
      updatedAt: nowTs(),
    })
  );
  // Prevent mutating role or accountStatus on update
  await assertFails(
    db.collection("users").doc(CUSTOMER_UID).update({
      role: "admin",
      updatedAt: nowTs(),
    })
  );
});

test("Private Emergency Contacts: strict isolation from other users and drivers, accessible to owner & authorized SOS responders", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    await adminDb.collection("driver_profiles").doc(DRIVER_UID).set({
      driverId: DRIVER_UID,
      legalName: "Rahul Das",
      phone: "+919800000002",
      district: "Hooghly",
      vehicleCategory: "toto",
      vehicleRegNumber: "WB15E1234",
      drivingLicenceNumber: "WB15-20210001",
      verificationStatus: "verified",
      isOnline: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await adminDb.collection("emergency_personnel").doc(EMERGENCY_RESPONDER_UID).set({
      uid: EMERGENCY_RESPONDER_UID,
      displayName: "SOS Desk 1",
    });
  });

  // Owner writes private emergency contact
  const aliceDb = testEnv.authenticatedContext(CUSTOMER_UID, { phone_number: "+919876543210" }).firestore();
  await assertSucceeds(
    aliceDb.collection("emergency_contacts").doc(CUSTOMER_UID).set({
      userId: CUSTOMER_UID,
      emergencyContactNumber: "+919830011223",
      relationshipNote: "Primary Emergency Contact",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  // Owner can read and update their own emergency contact
  await assertSucceeds(aliceDb.collection("emergency_contacts").doc(CUSTOMER_UID).get());
  await assertSucceeds(
    aliceDb.collection("emergency_contacts").doc(CUSTOMER_UID).update({
      emergencyContactNumber: "+919830099887",
      updatedAt: nowTs(),
    })
  );

  // Another user CANNOT read or list or write Alice's emergency contact
  const bobDb = testEnv.authenticatedContext(OTHER_UID).firestore();
  await assertFails(bobDb.collection("emergency_contacts").doc(CUSTOMER_UID).get());
  await assertFails(bobDb.collection("emergency_contacts").get());
  await assertFails(
    bobDb.collection("emergency_contacts").doc(CUSTOMER_UID).set({
      userId: CUSTOMER_UID,
      emergencyContactNumber: "+919999999999",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Verified driver CANNOT read or list Alice's emergency contact
  const driverDb = testEnv.authenticatedContext(DRIVER_UID).firestore();
  await assertFails(driverDb.collection("emergency_contacts").doc(CUSTOMER_UID).get());
  await assertFails(driverDb.collection("emergency_contacts").get());

  // Authorized emergency personnel and Admin CAN read for SOS response
  const responderDb = testEnv.authenticatedContext(EMERGENCY_RESPONDER_UID).firestore();
  await assertSucceeds(responderDb.collection("emergency_contacts").doc(CUSTOMER_UID).get());
  const adminDb = testEnv.authenticatedContext("admin_uid", { email: ADMIN_EMAIL, email_verified: true }).firestore();
  await assertSucceeds(adminDb.collection("emergency_contacts").doc(CUSTOMER_UID).get());
});

test("PII Isolation: another user cannot read Alice's user account or driver KYC", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    await adminDb.collection("users").doc(CUSTOMER_UID).set({
      uid: CUSTOMER_UID,
      userId: CUSTOMER_UID,
      fullName: "Alice Sen",
      phone: "+919800000001",
      mobileNumber: "+919800000001",
      role: "customer",
      preferredLanguage: "en",
      accountStatus: "active",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await adminDb.collection("driver_profiles").doc(DRIVER_UID).set({
      driverId: DRIVER_UID,
      legalName: "Rahul Das",
      phone: "+919800000002",
      district: "Hooghly",
      vehicleCategory: "toto",
      vehicleRegNumber: "WB15E1234",
      drivingLicenceNumber: "WB15-20210001",
      verificationStatus: "pending",
      isOnline: false,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
  });

  const bobDb = testEnv.authenticatedContext(OTHER_UID).firestore();
  await assertFails(bobDb.collection("users").doc(CUSTOMER_UID).get());
  await assertFails(bobDb.collection("driver_profiles").doc(DRIVER_UID).get());
});

test("Driver verification: driver cannot self-verify or go online while pending", async () => {
  const driverDb = testEnv.authenticatedContext(DRIVER_UID).firestore();
  await assertFails(
    driverDb.collection("driver_profiles").doc(DRIVER_UID).set({
      driverId: DRIVER_UID,
      legalName: "Rahul Das",
      phone: "+919800000002",
      district: "Hooghly",
      vehicleCategory: "toto",
      vehicleRegNumber: "WB15E1234",
      drivingLicenceNumber: "WB15-20210001",
      verificationStatus: "verified",
      isOnline: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  await assertSucceeds(
    driverDb.collection("driver_profiles").doc(DRIVER_UID).set({
      driverId: DRIVER_UID,
      legalName: "Rahul Das",
      phone: "+919800000002",
      district: "Hooghly",
      vehicleCategory: "toto",
      vehicleRegNumber: "WB15E1234",
      drivingLicenceNumber: "WB15-20210001",
      verificationStatus: "pending",
      isOnline: false,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  await assertFails(
    driverDb.collection("driver_profiles").doc(DRIVER_UID).update({
      verificationStatus: "verified",
      updatedAt: nowTs(),
    })
  );
});

test("Verified driver isolation: unverified users cannot read searching bookings, create ride_offers, or request settlements", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    await adminDb.collection("driver_profiles").doc(UNVERIFIED_DRIVER_UID).set({
      driverId: UNVERIFIED_DRIVER_UID,
      legalName: "Pending Driver",
      phone: "+919800000003",
      district: "Hooghly",
      vehicleCategory: "toto",
      vehicleRegNumber: "WB15E9999",
      drivingLicenceNumber: "WB15-20210009",
      verificationStatus: "pending",
      isOnline: false,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await adminDb.collection("driver_profiles").doc(DRIVER_UID).set({
      driverId: DRIVER_UID,
      legalName: "Rahul Das",
      phone: "+919800000002",
      district: "Hooghly",
      vehicleCategory: "toto",
      vehicleRegNumber: "WB15E1234",
      drivingLicenceNumber: "WB15-20210001",
      verificationStatus: "verified",
      isOnline: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await adminDb.collection("bookings").doc("booking_1").set({
      customerId: CUSTOMER_UID,
      customerName: "Alice",
      invoiceEmail: "alice.invoices@example.com",
      bookingType: "now",
      vehicleCategory: "toto",
      pickupLabel: "Bandel Station",
      destinationLabel: "Chinsurah Market",
      distanceKm: 5.0,
      estimatedFareInr: 90,
      minPermittedFareInr: 75,
      maxPermittedFareInr: 120,
      paymentMethod: "cash",
      paymentStatus: "pending",
      status: "searching",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
  });

  // Unverified user/driver cannot list searching bookings or bid or request payout
  const pendingDb = testEnv.authenticatedContext(UNVERIFIED_DRIVER_UID).firestore();
  await assertFails(pendingDb.collection("bookings").where("status", "==", "searching").get());
  await assertFails(
    pendingDb.collection("ride_offers").doc("offer_unverified").set({
      bookingId: "booking_1",
      customerId: CUSTOMER_UID,
      driverId: UNVERIFIED_DRIVER_UID,
      driverName: "Pending Driver",
      vehicleCategory: "toto",
      etaMinutes: 4,
      proposedFareInr: 95,
      status: "pending",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    pendingDb.collection("driver_settlements").doc("settle_unverified").set({
      driverId: UNVERIFIED_DRIVER_UID,
      driverName: "Pending Driver",
      amountInr: 200,
      upiOrBankRef: "pending@upi",
      status: "requested",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Verified driver CAN list searching bookings, reject out-of-range offers, and accept valid offers
  const driverDb = testEnv.authenticatedContext(DRIVER_UID).firestore();
  await assertSucceeds(driverDb.collection("bookings").where("status", "==", "searching").get());
  await assertFails(
    driverDb.collection("ride_offers").doc("offer_high").set({
      bookingId: "booking_1",
      customerId: CUSTOMER_UID,
      driverId: DRIVER_UID,
      driverName: "Rahul Das",
      vehicleCategory: "toto",
      etaMinutes: 4,
      proposedFareInr: 180,
      status: "pending",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    driverDb.collection("ride_offers").doc("offer_valid").set({
      bookingId: "booking_1",
      customerId: CUSTOMER_UID,
      driverId: DRIVER_UID,
      driverName: "Rahul Das",
      vehicleCategory: "toto",
      etaMinutes: 4,
      proposedFareInr: 95,
      status: "pending",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    driverDb.collection("driver_settlements").doc("settle_valid").set({
      driverId: DRIVER_UID,
      driverName: "Rahul Das",
      amountInr: 200,
      upiOrBankRef: "rahul@upi",
      status: "requested",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
});

test("Trip location updates strictly bound to assignedDriverId", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("bookings").doc("booking_assigned").set({
      customerId: CUSTOMER_UID,
      customerName: "Alice",
      assignedDriverId: DRIVER_UID,
      bookingType: "now",
      vehicleCategory: "toto",
      pickupLabel: "Bandel Station",
      destinationLabel: "Chinsurah Market",
      distanceKm: 5.0,
      estimatedFareInr: 90,
      minPermittedFareInr: 75,
      maxPermittedFareInr: 120,
      paymentMethod: "cash",
      paymentStatus: "pending",
      status: "arriving",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
  });

  const rogueDb = testEnv.authenticatedContext(OTHER_UID).firestore();
  await assertFails(
    rogueDb.collection("trip_locations").doc("booking_assigned").set({
      bookingId: "booking_assigned",
      customerId: CUSTOMER_UID,
      driverId: OTHER_UID,
      lat: 22.91,
      lng: 88.39,
      updatedAt: nowTs(),
    })
  );

  const driverDb = testEnv.authenticatedContext(DRIVER_UID).firestore();
  await assertSucceeds(
    driverDb.collection("trip_locations").doc("booking_assigned").set({
      bookingId: "booking_assigned",
      customerId: CUSTOMER_UID,
      driverId: DRIVER_UID,
      lat: 22.91,
      lng: 88.39,
      updatedAt: nowTs(),
    })
  );
});

test("Admin RBAC: email spoofing fails, bootstrapped admin & delegated /admins/{uid} succeed", async () => {
  const spoofedDb = testEnv.authenticatedContext("spoof_uid", { email: ADMIN_EMAIL, email_verified: false }).firestore();
  await assertFails(
    spoofedDb.collection("fare_configs").doc("toto_v1").set({
      vehicleCategory: "toto",
      version: 1,
      baseFareInr: 30,
      perKmRateInr: 12,
      minimumFareInr: 30,
      gstPercent: 5,
      commissionPercent: 10,
      allowDriverFareProposal: true,
      isEnabled: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  const realAdminDb = testEnv.authenticatedContext("admin_uid", { email: ADMIN_EMAIL, email_verified: true }).firestore();
  await assertSucceeds(
    realAdminDb.collection("fare_configs").doc("toto_v1").set({
      vehicleCategory: "toto",
      version: 1,
      baseFareInr: 30,
      perKmRateInr: 12,
      minimumFareInr: 30,
      gstPercent: 5,
      commissionPercent: 10,
      allowDriverFareProposal: true,
      isEnabled: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Primary bootstrapped admin delegates secondary phone-authenticated admin by UID
  await assertSucceeds(
    realAdminDb.collection("admins").doc(DELEGATED_ADMIN_UID).set({
      uid: DELEGATED_ADMIN_UID,
      email: "ops@nrnext.in",
      displayName: "Ops Manager",
      addedBy: "admin_uid",
      createdAt: nowTs(),
    })
  );

  // Delegated phone-authenticated admin can list users & update platform_settings, but cannot modify /admins
  const subAdminDb = testEnv.authenticatedContext(DELEGATED_ADMIN_UID, { phone_number: "+919876543211" }).firestore();
  await assertSucceeds(subAdminDb.collection("users").get());
  await assertSucceeds(
    subAdminDb.collection("platform_settings").doc("global").set({
      helplineNumber: "+91-1800-103-0000",
      parcelDeliveryEnabled: false,
      defaultCommissionPct: 10,
      updatesEnabled: true,
      latestVersionName: "1.2.0",
      latestVersionCode: 2,
      minSupportedVersionCode: 1,
      apkDownloadUrl: "https://github.com/nrnext/golocal/releases/download/v1.2.0/GoLocal.apk",
      sha256Checksum: "a".repeat(64),
      releaseNotes: "Automatic update release",
      isMandatoryUpdate: false,
      updateManifestUrl: "https://github.com/nrnext/golocal/releases/latest/download/update-manifest.json",
      updatedAt: nowTs(),
    })
  );
  // Customer cannot overwrite APK update manifest
  const custDb = testEnv.authenticatedContext(CUSTOMER_UID, { phone_number: "+919876543210" }).firestore();
  await assertFails(
    custDb.collection("platform_settings").doc("global").set({
      updatesEnabled: true,
      latestVersionName: "9.9.9",
      latestVersionCode: 99,
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    subAdminDb.collection("admins").doc("someone_else").set({
      uid: "someone_else",
      email: "x@y.com",
    })
  );
});

test("FCM tokens: owner write/delete allowed, outsider read/write denied", async () => {
  const aliceDb = testEnv.authenticatedContext(CUSTOMER_UID, { phone_number: "+919876543210" }).firestore();
  const bobDb = testEnv.authenticatedContext(OTHER_UID).firestore();
  const tokenId = `${CUSTOMER_UID}_abc123token`;

  await assertSucceeds(
    aliceDb.collection("fcm_tokens").doc(tokenId).set({
      userId: CUSTOMER_UID,
      role: "customer",
      token: "fcm_valid_device_token_string_123456",
      platform: "android",
      appPackage: "com.aistudio.golocal.wbmxrq",
      active: true,
      updatedAt: nowTs(),
    })
  );

  await assertFails(bobDb.collection("fcm_tokens").doc(tokenId).get());
  await assertFails(
    bobDb.collection("fcm_tokens").doc(tokenId).set({
      userId: CUSTOMER_UID,
      role: "customer",
      token: "hijack_token_string_123456",
      platform: "android",
      active: true,
      updatedAt: nowTs(),
    })
  );

  await assertSucceeds(aliceDb.collection("fcm_tokens").doc(tokenId).delete());
});

test("Formal disputes & driver cancellation: assigned driver cancels trip; Admin resolves disputes", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("bookings").doc("booking_cancel_test").set({
      customerId: CUSTOMER_UID,
      customerName: "Alice",
      assignedDriverId: DRIVER_UID,
      bookingType: "now",
      vehicleCategory: "toto",
      pickupLabel: "Bandel Station",
      destinationLabel: "Hooghly Imambara",
      distanceKm: 3.5,
      estimatedFareInr: 65,
      minPermittedFareInr: 55,
      maxPermittedFareInr: 90,
      paymentMethod: "cash",
      paymentStatus: "pending",
      status: "assigned",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
  });

  // Unassigned user cannot cancel the booking
  const outsiderDb = testEnv.authenticatedContext(OTHER_UID).firestore();
  await assertFails(
    outsiderDb.collection("bookings").doc("booking_cancel_test").update({
      status: "cancelled",
      cancelledBy: OTHER_UID,
      cancelledByRole: "driver",
      updatedAt: nowTs(),
    })
  );

  // Assigned driver CAN cancel before trip starts
  const driverDb = testEnv.authenticatedContext(DRIVER_UID).firestore();
  await assertSucceeds(
    driverDb.collection("bookings").doc("booking_cancel_test").update({
      status: "cancelled",
      cancellationReason: "Vehicle issue / flat tyre",
      cancelledBy: DRIVER_UID,
      cancelledByRole: "driver",
      cancellationFeeInr: 0,
      refundAmountInr: 0,
      updatedAt: nowTs(),
    })
  );

  // Customer creates dispute (`open`); customer cannot self-resolve; Admin resolves
  const aliceDb = testEnv.authenticatedContext(CUSTOMER_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("disputes").doc("dispute_1").set({
      bookingId: "booking_cancel_test",
      reporterId: CUSTOMER_UID,
      reporterRole: "customer",
      targetUserId: DRIVER_UID,
      category: "no_show_fee",
      description: "Driver asked for extra money outside tariff.",
      requestedRefundInr: 25,
      status: "open",
      adminResolutionNote: "",
      resolvedBy: "",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  await assertFails(
    aliceDb.collection("disputes").doc("dispute_1").update({
      status: "resolved_refund",
      adminResolutionNote: "Self approved",
      updatedAt: nowTs(),
    })
  );

  const adminDb = testEnv.authenticatedContext("admin_uid", { email: ADMIN_EMAIL, email_verified: true }).firestore();
  await assertSucceeds(
    adminDb.collection("disputes").doc("dispute_1").update({
      status: "resolved_refund",
      adminResolutionNote: "Approved ₹25 goodwill refund",
      resolvedBy: "admin_uid",
      updatedAt: nowTs(),
    })
  );
});

test("Ratings: 1 per bookingId, assigned driver note allowed, customer blocked from moderation", async () => {
  const aliceDb = testEnv.authenticatedContext(CUSTOMER_UID).firestore();
  const driverDb = testEnv.authenticatedContext(DRIVER_UID).firestore();
  const adminDb = testEnv.authenticatedContext("admin_uid", { email: ADMIN_EMAIL, email_verified: true }).firestore();

  await assertSucceeds(
    aliceDb.collection("ratings").doc("booking_rate_1").set({
      bookingId: "booking_rate_1",
      customerId: CUSTOMER_UID,
      driverId: DRIVER_UID,
      stars: 5,
      behaviourStars: 5,
      vehicleStars: 4,
      punctualityStars: 5,
      safetyStars: 5,
      review: "Safe ride from Bandel station",
      moderationStatus: "active",
      createdAt: nowTs(),
    })
  );

  // Customer cannot mutate moderationStatus
  await assertFails(
    aliceDb.collection("ratings").doc("booking_rate_1").update({
      moderationStatus: "hidden",
    })
  );

  // Assigned driver can add bidirectional customer rating
  await assertSucceeds(
    driverDb.collection("ratings").doc("booking_rate_1").update({
      riderToCustomerStars: 5,
      riderToCustomerNote: "Polite passenger, timely at pickup.",
    })
  );

  // Admin can moderate rating
  await assertSucceeds(
    adminDb.collection("ratings").doc("booking_rate_1").update({
      moderationStatus: "flagged",
      flaggedReason: "Under review",
    })
  );
});

test("Private Bus Governance: unapproved tariff blocks customer booking; approved schedule allows atomic booking & operator QR check-in", async () => {
  const OPERATOR_UID = "bus_op_1";
  const opDb = testEnv.authenticatedContext(OPERATOR_UID).firestore();
  const aliceDb = testEnv.authenticatedContext(CUSTOMER_UID).firestore();
  const bobDb = testEnv.authenticatedContext(OTHER_UID).firestore();
  const adminDb = testEnv.authenticatedContext("admin_uid", { email: ADMIN_EMAIL, email_verified: true }).firestore();

  // Unverified operator cannot register bus vehicle
  await assertFails(
    opDb.collection("bus_vehicles").doc("bus_v1").set({
      operatorId: OPERATOR_UID,
      busName: "Chinsurah Express",
      registrationNumber: "WB15B1234",
      busType: "AC Seater",
      totalSeats: 36,
      verificationStatus: "pending",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Operator registers and Admin verifies
  await assertSucceeds(
    opDb.collection("bus_operators").doc(OPERATOR_UID).set({
      operatorId: OPERATOR_UID,
      ownerUid: OPERATOR_UID,
      companyName: "Hooghly Stage Carriage",
      contactPhone: "+919800011223",
      operatorLicenseNumber: "WB-OP-881",
      permitNumber: "WB15-SC-2026",
      district: "Hooghly",
      verificationStatus: "pending",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    adminDb.collection("bus_operators").doc(OPERATOR_UID).update({
      verificationStatus: "verified",
      updatedAt: nowTs(),
    })
  );

  // Verified operator submits vehicle & route (`pending_fare_approval`)
  await assertSucceeds(
    opDb.collection("bus_vehicles").doc("bus_v1").set({
      operatorId: OPERATOR_UID,
      busName: "Chinsurah Express",
      registrationNumber: "WB15B1234",
      busType: "AC Seater",
      totalSeats: 36,
      verificationStatus: "pending",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    opDb.collection("bus_routes").doc("route_1").set({
      operatorId: OPERATOR_UID,
      routeName: "Chinsurah -> Kolkata Esplanade",
      originTown: "Chinsurah",
      destinationTown: "Kolkata",
      stops: ["Chinsurah", "Bandel", "Esplanade"],
      distanceKm: 42,
      farePerSeatInr: 75,
      fareSource: "government_gazette",
      authorityRef: "WB-TD-2025-11",
      effectiveDateIso: "2026-01-01",
      fareApprovalStatus: "pending_fare_approval",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    opDb.collection("bus_schedules").doc("sched_1").set({
      routeId: "route_1",
      operatorId: OPERATOR_UID,
      busId: "bus_v1",
      busName: "Chinsurah Express (WB15B1234)",
      originTown: "Chinsurah",
      destinationTown: "Kolkata",
      departureDateIso: "2026-12-01",
      departureTimeLabel: "07:30 AM",
      totalSeats: 36,
      bookedSeatIds: [],
      farePerSeatInr: 75,
      fareApprovalStatus: "pending_fare_approval",
      status: "scheduled",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Customer CANNOT book seats while fareApprovalStatus == pending_fare_approval
  await assertFails(
    aliceDb.collection("bus_bookings").doc("ticket_pending_fail").set({
      customerId: CUSTOMER_UID,
      customerName: "Alice",
      scheduleId: "sched_1",
      routeId: "route_1",
      operatorId: OPERATOR_UID,
      seatIds: ["1A"],
      seatCount: 1,
      totalPaidInr: 78.75,
      qrToken: "GLBUS-TICKET-1234",
      qrValidated: false,
      status: "confirmed",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Admin approves route & schedule
  await assertSucceeds(
    adminDb.collection("bus_schedules").doc("sched_1").update({
      fareApprovalStatus: "approved",
      updatedAt: nowTs(),
    })
  );

  // Customer CAN lock seat & create confirmed bus ticket on approved schedule
  await assertSucceeds(
    aliceDb.collection("bus_seat_locks").doc("sched_1_1A").set({
      scheduleId: "sched_1",
      seatId: "1A",
      lockedByUid: CUSTOMER_UID,
      lockedUntilEpochMs: Date.now() + 60000,
      status: "booked",
    })
  );
  await assertSucceeds(
    aliceDb.collection("bus_bookings").doc("ticket_valid_1").set({
      customerId: CUSTOMER_UID,
      customerName: "Alice",
      scheduleId: "sched_1",
      routeId: "route_1",
      operatorId: OPERATOR_UID,
      seatIds: ["1A"],
      seatCount: 1,
      totalPaidInr: 78.75,
      qrToken: "GLBUS-VALID-112233",
      qrValidated: false,
      status: "confirmed",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Outsider CANNOT mark ticket boarded; verified operator CAN
  await assertFails(
    bobDb.collection("bus_bookings").doc("ticket_valid_1").update({
      status: "boarded",
      qrValidated: true,
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    opDb.collection("bus_bookings").doc("ticket_valid_1").update({
      status: "boarded",
      qrValidated: true,
      updatedAt: nowTs(),
    })
  );
});

test("Operations Staff RBAC: strict separation from Owner Admin and PII isolation, while permitting live dispatch assistance", async () => {
  const OPS_UID = "ops_staff_borai";
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    await adminDb.collection("operations_staff").doc(OPS_UID).set({
      userId: OPS_UID,
      displayName: "Mohanpur Ops Desk",
      phoneOrEmail: "+919800099111",
      isActive: true,
      assignedDistrict: "Paschim Medinipur",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await adminDb.collection("users").doc(CUSTOMER_UID).set({
      uid: CUSTOMER_UID,
      userId: CUSTOMER_UID,
      fullName: "Alice Sen",
      phone: "+919800000001",
      role: "customer",
      preferredLanguage: "en",
      accountStatus: "active",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await adminDb.collection("driver_directory").doc(DRIVER_UID).set({
      driverId: DRIVER_UID,
      displayName: "Rahul Das",
      vehicleCategory: "toto",
      maskedPlate: "WB34****1234",
      district: "Paschim Medinipur",
      block: "Mohanpur",
      verificationStatus: "verified",
      isOnline: true,
      currentLat: 21.8160,
      currentLng: 87.3703,
      updatedAt: nowTs(),
    });
    await adminDb.collection("bookings").doc("borai_booking_1").set({
      customerId: CUSTOMER_UID,
      customerName: "Alice Sen",
      bookingType: "now",
      vehicleCategory: "toto",
      pickupLabel: "Borai Market (721457)",
      pickupDistrict: "Paschim Medinipur",
      pickupLat: 21.8160,
      pickupLng: 87.3703,
      destinationLabel: "Mohanpur Block HQ",
      destinationDistrict: "Paschim Medinipur",
      destinationLat: 21.8439,
      destinationLng: 87.4191,
      distanceKm: 6.2,
      estimatedDurationMin: 16,
      estimatedFareInr: 72,
      minPermittedFareInr: 60,
      maxPermittedFareInr: 95,
      paymentMethod: "cash",
      paymentStatus: "pending",
      status: "searching",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
  });

  const ownerDb = testEnv.authenticatedContext("owner_uid", { email: ADMIN_EMAIL, email_verified: true }).firestore();
  const opsDb = testEnv.authenticatedContext(OPS_UID, { phone_number: "+919800099111" }).firestore();

  // Owner CAN configure Borai service_areas & polygon zones
  await assertSucceeds(
    ownerDb.collection("service_areas").doc("borai_721457_zone").set({
      name: "Borai & Mohanpur Launch Zone",
      district: "Paschim Medinipur",
      block: "Mohanpur",
      pincode: "721457",
      zoneType: "village",
      lat: 21.8160,
      lng: 87.3703,
      centerLat: 21.8160,
      centerLng: 87.3703,
      radiusKm: 12.0,
      villages: ["Borai", "Mohanpur", "Gholai", "Paikbar"],
      pickupEnabled: true,
      dropoffEnabled: true,
      supportedVehicleCategories: ["toto", "auto", "bike", "cab"],
      isActive: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Ops CANNOT modify service areas, fare configs, platform settings, or escalate to Owner Admin
  await assertFails(
    opsDb.collection("service_areas").doc("borai_721457_zone").update({
      isActive: false,
      updatedAt: nowTs(),
    })
  );
  await assertFails(opsDb.collection("admins").doc(OPS_UID).set({ userId: OPS_UID, email: "x@y.com", createdAt: nowTs() }));
  await assertFails(opsDb.collection("operations_staff").doc("other_ops").set({ userId: "other_ops", displayName: "Rogue", isActive: true, createdAt: nowTs(), updatedAt: nowTs() }));
  await assertFails(opsDb.collection("users").doc(CUSTOMER_UID).get());
  await assertFails(opsDb.collection("platform_settings").doc("global").set({ helplineNumber: "+911234", updatedAt: nowTs() }));

  // Ops CAN monitor searching/active bookings & verified driver directory and assign a verified driver
  await assertSucceeds(opsDb.collection("bookings").doc("borai_booking_1").get());
  await assertSucceeds(opsDb.collection("driver_directory").doc(DRIVER_UID).get());
  await assertSucceeds(
    opsDb.collection("bookings").doc("borai_booking_1").update({
      status: "assigned",
      assignedDriverId: DRIVER_UID,
      assignedDriverName: "Rahul Das",
      assignedVehicleLabel: "toto - WB34****1234",
      agreedFareInr: 72,
      tripPin: "4821",
      assignedByOpsUid: OPS_UID,
      opsAssignmentNote: "Dispatched from Borai stand",
      updatedAt: nowTs(),
    })
  );
});


