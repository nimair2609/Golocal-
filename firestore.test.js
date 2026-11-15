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

test("Unauthenticated user: denied reading bookings and users", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("bookings").get());
  await assertFails(unauthDb.collection("users").doc(CUSTOMER_UID).get());
  await assertFails(unauthDb.collection("platform_settings").doc("global").get());
});

test("User registration & update: prevents self-assigning or escalating to admin role", async () => {
  const db = testEnv.authenticatedContext(CUSTOMER_UID, { email: "alice@example.com", email_verified: true }).firestore();
  await assertFails(
    db.collection("users").doc(CUSTOMER_UID).set({
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
      userId: CUSTOMER_UID,
      fullName: "Alice Sen",
      role: "customer",
      preferredLanguage: "bn",
      accountStatus: "active",
      createdAt: nowTs(),
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

test("PII Isolation: another user cannot read Alice's user account or driver KYC", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    await adminDb.collection("users").doc(CUSTOMER_UID).set({
      userId: CUSTOMER_UID,
      fullName: "Alice Sen",
      phone: "+919800000001",
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

  // Primary bootstrapped admin delegates secondary admin
  await assertSucceeds(
    realAdminDb.collection("admins").doc(DELEGATED_ADMIN_UID).set({
      uid: DELEGATED_ADMIN_UID,
      email: "ops@nrnext.in",
      displayName: "Ops Manager",
      addedBy: "admin_uid",
      createdAt: nowTs(),
    })
  );

  // Delegated admin can list users & update platform_settings (including APK update manifest), but cannot modify /admins
  const subAdminDb = testEnv.authenticatedContext(DELEGATED_ADMIN_UID, { email: "ops@nrnext.in", email_verified: true }).firestore();
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
  const custDb = testEnv.authenticatedContext(CUSTOMER_UID, { email: "alice@example.com", email_verified: true }).firestore();
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
