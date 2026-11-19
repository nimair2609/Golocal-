const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const crypto = require("crypto");

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
      is_test_ride: false,
      test_mode: false,
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

  // Verified driver CAN list non-test searching bookings, reject out-of-range offers, and accept valid offers
  const driverDb = testEnv.authenticatedContext(DRIVER_UID).firestore();
  await assertFails(driverDb.collection("bookings").where("status", "==", "searching").get());
  await assertSucceeds(
    driverDb.collection("bookings").where("status", "==", "searching").where("is_test_ride", "==", false).get()
  );
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

test("Two-way booking chat & participant contact bridge: scoped to booking participants and locked on completion", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("bookings").doc("chat_trip_1").set({
      customerId: CUSTOMER_UID,
      customerName: "Alice Sen",
      assignedDriverId: DRIVER_UID,
      assignedDriverName: "Rahul Das",
      bookingType: "now",
      vehicleCategory: "toto",
      pickupLabel: "Bandel Station",
      destinationLabel: "Chinsurah Court",
      distanceKm: 4.5,
      estimatedFareInr: 70,
      minPermittedFareInr: 60,
      maxPermittedFareInr: 95,
      paymentMethod: "cash",
      paymentStatus: "pending",
      status: "arriving",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
  });

  const custDb = testEnv.authenticatedContext(CUSTOMER_UID).firestore();
  const driverDb = testEnv.authenticatedContext(DRIVER_UID).firestore();
  const rogueDb = testEnv.authenticatedContext(OTHER_UID).firestore();

  // Customer and assigned driver can exchange messages during active trip
  await assertSucceeds(
    custDb.collection("booking_chats").doc("chat_trip_1").collection("messages").doc("msg_1").set({
      bookingId: "chat_trip_1",
      customerId: CUSTOMER_UID,
      driverId: DRIVER_UID,
      senderId: CUSTOMER_UID,
      senderRole: "customer",
      senderName: "Alice",
      text: "Near Gate 2 now",
      deliveryState: "sent",
      readByCustomer: true,
      readByDriver: false,
      createdAt: nowTs(),
    })
  );
  await assertSucceeds(
    driverDb.collection("booking_chats").doc("chat_trip_1").collection("messages").doc("msg_1").update({
      deliveryState: "read",
      readByDriver: true,
    })
  );
  await assertSucceeds(
    driverDb.collection("booking_chats").doc("chat_trip_1").collection("messages").doc("msg_2").set({
      bookingId: "chat_trip_1",
      customerId: CUSTOMER_UID,
      driverId: DRIVER_UID,
      senderId: DRIVER_UID,
      senderRole: "driver",
      senderName: "Rahul Das",
      text: "Reaching in 2 mins",
      deliveryState: "sent",
      readByCustomer: false,
      readByDriver: true,
      createdAt: nowTs(),
    })
  );

  // Outsider cannot read or write chat messages
  await assertFails(
    rogueDb.collection("booking_chats").doc("chat_trip_1").collection("messages").doc("msg_1").get()
  );
  await assertFails(
    rogueDb.collection("booking_chats").doc("chat_trip_1").collection("messages").doc("msg_rogue").set({
      bookingId: "chat_trip_1",
      customerId: CUSTOMER_UID,
      driverId: DRIVER_UID,
      senderId: OTHER_UID,
      senderRole: "customer",
      senderName: "Bob",
      text: "Spoof",
      deliveryState: "sent",
      createdAt: nowTs(),
    })
  );

  // Active-trip call contact bridge readable only by participants while active
  await assertSucceeds(
    custDb.collection("booking_contacts").doc("chat_trip_1").set({
      bookingId: "chat_trip_1",
      customerId: CUSTOMER_UID,
      driverId: DRIVER_UID,
      customerFirstName: "Alice",
      customerPhone: "+919800000001",
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(driverDb.collection("booking_contacts").doc("chat_trip_1").get());
  await assertFails(rogueDb.collection("booking_contacts").doc("chat_trip_1").get());

  // Complete the trip -> new messages & contact lookup are blocked; reading chat history still allowed for participants
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("bookings").doc("chat_trip_1").update({
      status: "completed",
      updatedAt: nowTs(),
    });
  });

  await assertFails(
    custDb.collection("booking_chats").doc("chat_trip_1").collection("messages").doc("msg_3").set({
      bookingId: "chat_trip_1",
      customerId: CUSTOMER_UID,
      driverId: DRIVER_UID,
      senderId: CUSTOMER_UID,
      senderRole: "customer",
      senderName: "Alice",
      text: "Post trip text",
      deliveryState: "sent",
      createdAt: nowTs(),
    })
  );
  await assertFails(driverDb.collection("booking_contacts").doc("chat_trip_1").get());
  await assertSucceeds(
    custDb.collection("booking_chats").doc("chat_trip_1").collection("messages").doc("msg_1").get()
  );
});

test("FCM user tokens & Private Bus tariff gating / atomic seat reservation rules", async () => {
  const adminDb = testEnv.authenticatedContext("admin_uid", { email: ADMIN_EMAIL, email_verified: true }).firestore();
  const custDb = testEnv.authenticatedContext(CUSTOMER_UID).firestore();
  const bobDb = testEnv.authenticatedContext(OTHER_UID).firestore();

  // FCM tokens isolated to owner
  await assertSucceeds(
    custDb.collection("user_tokens").doc(`${CUSTOMER_UID}_customer`).set({
      userId: CUSTOMER_UID,
      token: "fcm_token_sample_123456",
      platform: "android",
      appVariant: "customer",
      active: true,
      updatedAt: nowTs(),
    })
  );
  await assertFails(bobDb.collection("user_tokens").doc(`${CUSTOMER_UID}_customer`).get());

  // Bus route without official fareSource CANNOT be activated even by admin
  await assertFails(
    adminDb.collection("bus_routes").doc("route_no_source").set({
      operatorId: "op_1",
      routeName: "Chinsurah - Dankuni",
      originStop: "Chinsurah",
      destinationStop: "Dankuni",
      distanceKm: 28,
      baseSeatFareInr: 40,
      fareSource: "",
      effectiveDateLabel: "",
      status: "active",
      updatedAt: nowTs(),
    })
  );

  // Admin creates verified route & schedule WITH official fareSource
  await assertSucceeds(
    adminDb.collection("bus_routes").doc("route_1").set({
      operatorId: "op_1",
      operatorName: "Hooghly Express Travels",
      routeName: "Chinsurah - Esplanade",
      originStop: "Chinsurah",
      destinationStop: "Esplanade",
      distanceKm: 42,
      baseSeatFareInr: 25,
      perKmSeatRateInr: 1.5,
      fareSource: "WBTC Stage Carriage Permit Tariff",
      effectiveDateLabel: "2026-04-01",
      status: "active",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    adminDb.collection("bus_schedules").doc("sched_1").set({
      routeId: "route_1",
      operatorId: "op_1",
      operatorName: "Hooghly Express Travels",
      busLabel: "WB-15-B-4421 AC",
      busType: "ac_seater",
      originStop: "Chinsurah",
      destinationStop: "Esplanade",
      departureTimeLabel: "07:30 AM",
      arrivalTimeLabel: "09:15 AM",
      travelDateLabel: "2026-04-20",
      totalSeats: 40,
      bookedSeats: [],
      farePerSeatInr: 88,
      fareSource: "WBTC Stage Carriage Permit Tariff",
      effectiveDateLabel: "2026-04-01",
      gstPercent: 5,
      commissionPercent: 8,
      status: "scheduled",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Customer creates bus booking & atomically reserves seats
  await assertSucceeds(
    custDb.collection("bus_schedules").doc("sched_1").update({
      bookedSeats: ["1A", "1B"],
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    custDb.collection("bus_bookings").doc("bus_book_1").set({
      bookingRef: "GLB-1001",
      customerId: CUSTOMER_UID,
      customerName: "Alice Sen",
      scheduleId: "sched_1",
      routeId: "route_1",
      operatorId: "op_1",
      operatorName: "Hooghly Express Travels",
      routeLabel: "Chinsurah -> Esplanade",
      travelDateLabel: "2026-04-20",
      departureTimeLabel: "07:30 AM",
      seatNumbers: ["1A", "1B"],
      seatCount: 2,
      totalFareInr: 185,
      gstInr: 8.8,
      commissionInr: 14.08,
      paymentMethod: "upi",
      paymentStatus: "pending_verification",
      status: "confirmed",
      qrPayload: "GOLOCAL-BUS|v1|bus_book_1|GLB-1001|sched_1|1A,1B",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Other customer cannot read Alice's bus ticket
  await assertFails(bobDb.collection("bus_bookings").doc("bus_book_1").get());
});

test("Operations Staff RBAC: no default access, admin-only grant/revoke, scoped Live Ops/SOS only (no pricing/payouts/KYC)", async () => {
  const OPS_UID = "ops_staff_1";
  const adminDb = testEnv.authenticatedContext("admin_uid", { email: ADMIN_EMAIL, email_verified: true }).firestore();
  const custDb = testEnv.authenticatedContext(CUSTOMER_UID).firestore();
  const opsDb = testEnv.authenticatedContext(OPS_UID).firestore();
  const bobDb = testEnv.authenticatedContext(OTHER_UID).firestore();

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("bookings").doc("ops_booking_1").set({
      customerId: CUSTOMER_UID,
      customerName: "Alice Sen",
      assignedDriverId: DRIVER_UID,
      bookingType: "now",
      vehicleCategory: "toto",
      pickupLabel: "Borai Market",
      destinationLabel: "Mohanpur Stand",
      distanceKm: 4.0,
      estimatedFareInr: 60,
      minPermittedFareInr: 50,
      maxPermittedFareInr: 80,
      paymentMethod: "cash",
      paymentStatus: "pending",
      status: "in_progress",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await db.collection("trip_locations").doc("ops_booking_1").set({
      bookingId: "ops_booking_1",
      customerId: CUSTOMER_UID,
      driverId: DRIVER_UID,
      lat: 21.8160,
      lng: 87.3703,
      updatedAt: nowTs(),
    });
    await db.collection("emergency_events").doc("sos_ops_1").set({
      userId: CUSTOMER_UID,
      bookingId: "ops_booking_1",
      lat: 21.8160,
      lng: 87.3703,
      notes: "Urgent assistance requested",
      status: "active",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await db.collection("driver_profiles").doc(DRIVER_UID).set({
      driverId: DRIVER_UID,
      legalName: "Rahul Das",
      phone: "+919800000002",
      district: "Paschim Medinipur",
      vehicleCategory: "toto",
      vehicleRegNumber: "WB15E1234",
      drivingLicenceNumber: "WB15-20210001",
      verificationStatus: "verified",
      isOnline: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
  });

  // 1. Unauthorized / non-ops users cannot read bookings, grant themselves ops access, or list operations_staff
  await assertFails(opsDb.collection("bookings").doc("ops_booking_1").get());
  await assertFails(opsDb.collection("emergency_events").doc("sos_ops_1").get());
  await assertFails(opsDb.collection("operations_staff").get());
  await assertFails(
    opsDb.collection("operations_staff").doc(OPS_UID).set({
      userId: OPS_UID,
      displayName: "Rogue Self Grant",
      assignedDistrict: "Paschim Medinipur",
      isActive: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    custDb.collection("operations_staff").doc(OPS_UID).set({
      userId: OPS_UID,
      displayName: "Customer Grant Attempt",
      assignedDistrict: "Paschim Medinipur",
      isActive: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // 2. Authorized Admin grants scoped operations staff access
  await assertSucceeds(
    adminDb.collection("operations_staff").doc(OPS_UID).set({
      userId: OPS_UID,
      displayName: "Subhankar Jana",
      contactIdentifier: "+919800012345",
      assignedDistrict: "Paschim Medinipur",
      isActive: true,
      grantedBy: "admin_uid",
      grantedByEmail: ADMIN_EMAIL,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // 3. Active ops staff CAN inspect their own allowlist entry, Live Ops bookings, trip locations, and handle SOS events
  await assertSucceeds(opsDb.collection("operations_staff").doc(OPS_UID).get());
  await assertSucceeds(opsDb.collection("bookings").doc("ops_booking_1").get());
  await assertSucceeds(opsDb.collection("trip_locations").doc("ops_booking_1").get());
  await assertSucceeds(opsDb.collection("emergency_events").doc("sos_ops_1").get());
  await assertSucceeds(
    opsDb.collection("emergency_events").doc("sos_ops_1").update({
      status: "acknowledged",
      updatedAt: nowTs(),
    })
  );

  // 4. Active ops staff STILL CANNOT list operations_staff, mutate operations_staff, read private KYC, write fare_configs, or write platform_settings/admins
  await assertFails(opsDb.collection("operations_staff").get());
  await assertFails(
    opsDb.collection("operations_staff").doc("other_staff").set({
      userId: "other_staff",
      displayName: "Unauthorized Staff",
      assignedDistrict: "Hooghly",
      isActive: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertFails(opsDb.collection("driver_profiles").doc(DRIVER_UID).get());
  await assertFails(
    opsDb.collection("fare_configs").doc("toto_hack").set({
      vehicleCategory: "toto",
      version: 99,
      baseFareInr: 1,
      perKmRateInr: 1,
      minimumFareInr: 1,
      gstPercent: 0,
      commissionPercent: 0,
      allowDriverFareProposal: false,
      isEnabled: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    opsDb.collection("platform_settings").doc("global").set({
      helplineNumber: "+91-9999",
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    opsDb.collection("admins").doc(OPS_UID).set({
      uid: OPS_UID,
      email: "ops@example.com",
      createdAt: nowTs(),
    })
  );
  await assertFails(bobDb.collection("operations_staff").doc(OPS_UID).get());

  // 5. Unauthorized user CANNOT revoke ops access; Admin revokes -> ops staff immediately loses Live Ops/SOS access
  await assertFails(
    custDb.collection("operations_staff").doc(OPS_UID).update({
      isActive: false,
      revokedBy: CUSTOMER_UID,
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    adminDb.collection("operations_staff").doc(OPS_UID).update({
      isActive: false,
      revokedBy: "admin_uid",
      updatedAt: nowTs(),
    })
  );
  await assertFails(opsDb.collection("bookings").doc("ops_booking_1").get());
  await assertFails(opsDb.collection("emergency_events").doc("sos_ops_1").get());
});

test("Admin-Controlled Live Test Ride Mode (13 Acceptance Scenarios): authorization, atomic dispatch, OTP security, driver bypass prevention, Ops/Owner override split, financial isolation, and mode OFF preservation", async () => {
  const OPS_UID = "ops_test_mgr";
  const adminDb = testEnv.authenticatedContext("admin_uid", { email: ADMIN_EMAIL, email_verified: true }).firestore();
  const custDb = testEnv.authenticatedContext(CUSTOMER_UID, { phone_number: "+919876543210" }).firestore();
  const bobDb = testEnv.authenticatedContext(OTHER_UID, { phone_number: "+919876543211" }).firestore();
  const driverDb = testEnv.authenticatedContext(DRIVER_UID).firestore();
  const unverifiedDriverDb = testEnv.authenticatedContext(UNVERIFIED_DRIVER_UID).firestore();
  const opsDb = testEnv.authenticatedContext(OPS_UID).firestore();

  function sha256Otp(bookingId, otp) {
    return crypto.createHash("sha256").update(`${bookingId}:${otp}`).digest("hex");
  }

  function makeTestBookingPayload(customerId, customerName) {
    return {
      customerId,
      customerName,
      bookingType: "now",
      vehicleCategory: "toto",
      pickupLabel: "Bandel Station",
      destinationLabel: "Chinsurah Court",
      distanceKm: 4.5,
      estimatedFareInr: 70,
      minPermittedFareInr: 60,
      maxPermittedFareInr: 95,
      paymentMethod: "cash",
      paymentStatus: "pending",
      status: "searching",
      is_test_ride: true,
      test_mode: true,
      test_otp_status: "awaiting_assignment",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    };
  }

  // Seed users (CUSTOMER_UID authorized test customer, OTHER_UID ordinary customer),
  // verified real driver (initially NOT a test partner), unverified driver, and active Ops staff.
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(CUSTOMER_UID).set({
      uid: CUSTOMER_UID,
      userId: CUSTOMER_UID,
      fullName: "Alice Test Customer",
      email: "alice@example.com",
      phone: "+919876543210",
      mobileNumber: "+919876543210",
      role: "customer",
      preferredLanguage: "en",
      accountStatus: "active",
      isTestCustomer: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await db.collection("users").doc(OTHER_UID).set({
      uid: OTHER_UID,
      userId: OTHER_UID,
      fullName: "Bob Ordinary Customer",
      email: "bob@example.com",
      phone: "+919876543211",
      mobileNumber: "+919876543211",
      role: "customer",
      preferredLanguage: "en",
      accountStatus: "active",
      isTestCustomer: false,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await db.collection("driver_profiles").doc(DRIVER_UID).set({
      driverId: DRIVER_UID,
      legalName: "Rahul Das",
      phone: "+919800000002",
      district: "Hooghly",
      vehicleCategory: "toto",
      vehicleRegNumber: "WB15E1234",
      drivingLicenceNumber: "WB15-20210001",
      verificationStatus: "verified",
      isOnline: true,
      isTestPartner: false,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await db.collection("driver_profiles").doc(UNVERIFIED_DRIVER_UID).set({
      driverId: UNVERIFIED_DRIVER_UID,
      legalName: "Pending Driver",
      phone: "+919800000003",
      district: "Hooghly",
      vehicleCategory: "toto",
      vehicleRegNumber: "WB15E9999",
      drivingLicenceNumber: "WB15-20210099",
      verificationStatus: "pending",
      isOnline: false,
      isTestPartner: false,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await db.collection("operations_staff").doc(OPS_UID).set({
      userId: OPS_UID,
      displayName: "Ops Tester",
      assignedDistrict: "Hooghly",
      isActive: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
  });

  // Scenario 1: Test mode OFF blocks test booking creation even for authorized test customer
  await assertFails(
    custDb.collection("bookings").doc("test_ride_disabled").set(makeTestBookingPayload(CUSTOMER_UID, "Alice Test Customer"))
  );

  // Non-admin (Customer & Operations Staff) CANNOT toggle testRideModeEnabled; customer CANNOT self-promote isTestCustomer
  await assertFails(
    custDb.collection("platform_settings").doc("global").set({ testRideModeEnabled: true, updatedAt: nowTs() })
  );
  await assertFails(
    opsDb.collection("platform_settings").doc("global").set({ testRideModeEnabled: true, updatedAt: nowTs() })
  );
  await assertFails(
    bobDb.collection("users").doc(OTHER_UID).update({ isTestCustomer: true, updatedAt: nowTs() })
  );

  // Owner Admin enables Test Ride Mode
  await assertSucceeds(
    adminDb.collection("platform_settings").doc("global").set({
      helplineNumber: "+91-1800-103-0000",
      testRideModeEnabled: true,
      testAutoAssignEnabled: false,
      testManualAssignEnabled: true,
      testDriverSpeedKmph: 40,
      testOtpValiditySeconds: 300,
      testAllowAdminOtpOverride: true,
      testAutoCompleteEnabled: false,
      updatedAt: nowTs(),
    })
  );

  // Scenario 2: Test mode ON allows authorized isTestCustomer booking & blocks ordinary non-test customer
  await assertFails(
    bobDb.collection("bookings").doc("test_ride_unauthorized_cust").set(makeTestBookingPayload(OTHER_UID, "Bob Ordinary Customer"))
  );
  const TEST_BOOKING_ID = "test_ride_001";
  await assertSucceeds(
    custDb.collection("bookings").doc(TEST_BOOKING_ID).set(makeTestBookingPayload(CUSTOMER_UID, "Alice Test Customer"))
  );

  // Searching test rides are invisible to verified real drivers and cannot receive commercial offers
  await assertFails(driverDb.collection("bookings").doc(TEST_BOOKING_ID).get());
  await assertFails(
    driverDb.collection("ride_offers").doc("offer_on_test").set({
      bookingId: TEST_BOOKING_ID,
      customerId: CUSTOMER_UID,
      driverId: DRIVER_UID,
      driverName: "Rahul Das",
      vehicleCategory: "toto",
      etaMinutes: 4,
      proposedFareInr: 70,
      status: "pending",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Scenario 4: Unauthorized rider assignment fails (non-test verified driver & unverified driver rejected; Ops cannot flip isTestPartner)
  await assertFails(
    opsDb.collection("bookings").doc(TEST_BOOKING_ID).update({
      status: "assigned",
      assignedDriverId: DRIVER_UID,
      assignedDriverName: "Rahul Das",
      assignedVehicleLabel: "WB15E1234",
      agreedFareInr: 70,
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    opsDb.collection("bookings").doc(TEST_BOOKING_ID).update({
      status: "assigned",
      assignedDriverId: UNVERIFIED_DRIVER_UID,
      assignedDriverName: "Pending Driver",
      assignedVehicleLabel: "WB15E9999",
      agreedFareInr: 70,
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    opsDb.collection("driver_profiles").doc(DRIVER_UID).update({ isTestPartner: true, updatedAt: nowTs() })
  );

  // Owner Admin authorizes verified DRIVER_UID as Test Partner
  await assertSucceeds(
    adminDb.collection("driver_profiles").doc(DRIVER_UID).update({ isTestPartner: true, updatedAt: nowTs() })
  );

  // Scenario 3: Ops assignment succeeds atomically across bookings + ride_assignments + test_ride_otps + test_ride_otp_secrets + trip_locations + live_locations + audit_logs
  const VALID_OTP = "482913";
  const futureDate = new Date(Date.now() + 300 * 1000);
  const assignBatch = opsDb.batch();
  assignBatch.update(opsDb.collection("bookings").doc(TEST_BOOKING_ID), {
    status: "assigned",
    assignedDriverId: DRIVER_UID,
    assignedDriverName: "Rahul Das",
    assignedVehicleLabel: "TEST PARTNER • WB15E1234",
    agreedFareInr: 70,
    test_otp_status: "pending",
    test_otp_expires_at_ms: futureDate.getTime(),
    updatedAt: nowTs(),
  });
  assignBatch.set(opsDb.collection("ride_assignments").doc(TEST_BOOKING_ID), {
    bookingId: TEST_BOOKING_ID,
    customerId: CUSTOMER_UID,
    driverId: DRIVER_UID,
    offerId: "admin_test_dispatch",
    lockedFareInr: 70,
    status: "active",
    is_test_ride: true,
    assignedByUid: OPS_UID,
    assignedByRole: "ops_staff",
    createdAt: nowTs(),
    updatedAt: nowTs(),
  });
  assignBatch.set(opsDb.collection("test_ride_otps").doc(TEST_BOOKING_ID), {
    bookingId: TEST_BOOKING_ID,
    customerId: CUSTOMER_UID,
    otpHash: sha256Otp(TEST_BOOKING_ID, VALID_OTP),
    submittedHash: "",
    used: false,
    overridden: false,
    overrideReason: "",
    overrideRequestedByUid: "",
    overrideRequestedReason: "",
    overriddenByUid: "",
    verifiedByUid: "",
    attempts: 0,
    maxAttempts: 5,
    expiresAt: futureDate,
    createdAt: nowTs(),
    updatedAt: nowTs(),
  });
  assignBatch.set(opsDb.collection("test_ride_otp_secrets").doc(TEST_BOOKING_ID), {
    bookingId: TEST_BOOKING_ID,
    customerId: CUSTOMER_UID,
    otpPlaintext: VALID_OTP,
    status: "pending",
    issuedByUid: OPS_UID,
    issuedByRole: "ops_staff",
    expiresAt: futureDate,
    createdAt: nowTs(),
    updatedAt: nowTs(),
  });
  assignBatch.set(opsDb.collection("trip_locations").doc(TEST_BOOKING_ID), {
    bookingId: TEST_BOOKING_ID,
    customerId: CUSTOMER_UID,
    driverId: DRIVER_UID,
    lat: 22.922,
    lng: 88.378,
    isTestRide: true,
    isSimulated: false,
    updatedAt: nowTs(),
  });
  assignBatch.set(opsDb.collection("live_locations").doc(DRIVER_UID), {
    driverId: DRIVER_UID,
    bookingId: TEST_BOOKING_ID,
    customerId: CUSTOMER_UID,
    lat: 22.922,
    lng: 88.378,
    headingDeg: 0,
    speedKmph: 0,
    isTestRide: true,
    isSimulated: false,
    updatedAt: nowTs(),
  });
  assignBatch.set(opsDb.collection("audit_logs").doc("audit_test_assign_1"), {
    actorId: OPS_UID,
    actorEmail: "",
    action: "TEST_PARTNER_ASSIGNED",
    targetCollection: "bookings",
    targetId: TEST_BOOKING_ID,
    summary: "[ops_staff] Assigned verified test partner Rahul Das",
    isTestRide: true,
    createdAt: nowTs(),
  });
  await assertSucceeds(assignBatch.commit());

  // Assigned Test Partner can read the test booking and advance assigned -> arriving -> arrived,
  // and unverified driver cannot.
  await assertSucceeds(driverDb.collection("bookings").doc(TEST_BOOKING_ID).get());
  await assertFails(unverifiedDriverDb.collection("bookings").doc(TEST_BOOKING_ID).get());
  await assertSucceeds(driverDb.collection("bookings").doc(TEST_BOOKING_ID).update({ status: "arriving", updatedAt: nowTs() }));
  await assertSucceeds(driverDb.collection("bookings").doc(TEST_BOOKING_ID).update({ status: "arrived", updatedAt: nowTs() }));

  // Customer & outsiders CANNOT read test_ride_otps or test_ride_otp_secrets
  await assertFails(custDb.collection("test_ride_otps").doc(TEST_BOOKING_ID).get());
  await assertFails(custDb.collection("test_ride_otp_secrets").doc(TEST_BOOKING_ID).get());
  await assertFails(bobDb.collection("test_ride_otp_secrets").doc(TEST_BOOKING_ID).get());
  await assertFails(driverDb.collection("test_ride_otps").doc(TEST_BOOKING_ID).get());

  // Scenario 9: Driver (and Ops) CANNOT bypass OTP (arrived -> in_progress blocked on test rides)
  await assertFails(
    driverDb.collection("bookings").doc(TEST_BOOKING_ID).update({
      status: "in_progress",
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    opsDb.collection("bookings").doc(TEST_BOOKING_ID).update({
      status: "in_progress",
      updatedAt: nowTs(),
    })
  );

  // Scenario 6: Incorrect OTP hash fails & cannot advance booking
  await assertFails(
    custDb.collection("test_ride_otps").doc(TEST_BOOKING_ID).update({
      used: true,
      submittedHash: sha256Otp(TEST_BOOKING_ID, "000000"),
      verifiedByUid: CUSTOMER_UID,
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    custDb.collection("bookings").doc(TEST_BOOKING_ID).update({
      status: "in_progress",
      test_otp_status: "verified",
      updatedAt: nowTs(),
    })
  );

  // Scenario 5: Expired OTP fails even with valid OTP hash
  const EXPIRED_BOOKING_ID = "test_ride_expired";
  const expiredDate = new Date(Date.now() - 60 * 1000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("bookings").doc(EXPIRED_BOOKING_ID).set({
      ...makeTestBookingPayload(CUSTOMER_UID, "Alice Test Customer"),
      status: "arrived",
      assignedDriverId: "test_driver_toto_01",
      assignedDriverName: "Test Driver – Toto 01",
      assignedVehicleLabel: "TEST VEHICLE • WB-TEST-0101",
      test_otp_status: "pending",
      test_otp_expires_at_ms: expiredDate.getTime(),
    });
    await db.collection("test_ride_otps").doc(EXPIRED_BOOKING_ID).set({
      bookingId: EXPIRED_BOOKING_ID,
      customerId: CUSTOMER_UID,
      otpHash: sha256Otp(EXPIRED_BOOKING_ID, "654321"),
      submittedHash: "",
      used: false,
      overridden: false,
      overrideReason: "",
      verifiedByUid: "",
      attempts: 0,
      maxAttempts: 5,
      expiresAt: expiredDate,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
  });
  const expiredBatch = custDb.batch();
  expiredBatch.update(custDb.collection("test_ride_otps").doc(EXPIRED_BOOKING_ID), {
    used: true,
    submittedHash: sha256Otp(EXPIRED_BOOKING_ID, "654321"),
    verifiedByUid: CUSTOMER_UID,
    updatedAt: nowTs(),
  });
  expiredBatch.update(custDb.collection("bookings").doc(EXPIRED_BOOKING_ID), {
    status: "in_progress",
    test_otp_status: "verified",
    updatedAt: nowTs(),
  });
  await assertFails(expiredBatch.commit());

  // Scenario 8: Valid OTP starts test ride atomically
  const validBatch = custDb.batch();
  validBatch.update(custDb.collection("test_ride_otps").doc(TEST_BOOKING_ID), {
    used: true,
    submittedHash: sha256Otp(TEST_BOOKING_ID, VALID_OTP),
    verifiedByUid: CUSTOMER_UID,
    updatedAt: nowTs(),
  });
  validBatch.update(custDb.collection("bookings").doc(TEST_BOOKING_ID), {
    status: "in_progress",
    test_otp_status: "verified",
    updatedAt: nowTs(),
  });
  await assertSucceeds(validBatch.commit());

  // Scenario 7: Reused OTP fails (cannot submit used OTP again)
  await assertFails(
    custDb.collection("test_ride_otps").doc(TEST_BOOKING_ID).update({
      used: true,
      submittedHash: sha256Otp(TEST_BOOKING_ID, VALID_OTP),
      verifiedByUid: CUSTOMER_UID,
      updatedAt: nowTs(),
    })
  );

  // Scenario 10: Authorized override works & is audited (Ops requests with reason -> Owner Admin executes override)
  const OVERRIDE_BOOKING_ID = "test_ride_override";
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("bookings").doc(OVERRIDE_BOOKING_ID).set({
      ...makeTestBookingPayload(CUSTOMER_UID, "Alice Test Customer"),
      status: "arrived",
      assignedDriverId: "test_driver_toto_01",
      assignedDriverName: "Test Driver – Toto 01",
      assignedVehicleLabel: "TEST VEHICLE • WB-TEST-0101",
      test_otp_status: "pending",
      test_otp_expires_at_ms: futureDate.getTime(),
    });
    await db.collection("test_ride_otps").doc(OVERRIDE_BOOKING_ID).set({
      bookingId: OVERRIDE_BOOKING_ID,
      customerId: CUSTOMER_UID,
      otpHash: sha256Otp(OVERRIDE_BOOKING_ID, "112233"),
      submittedHash: "",
      used: false,
      overridden: false,
      overrideReason: "",
      overrideRequestedByUid: "",
      overrideRequestedReason: "",
      overriddenByUid: "",
      verifiedByUid: "",
      attempts: 0,
      maxAttempts: 5,
      expiresAt: futureDate,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await db.collection("test_ride_otp_secrets").doc(OVERRIDE_BOOKING_ID).set({
      bookingId: OVERRIDE_BOOKING_ID,
      customerId: CUSTOMER_UID,
      otpPlaintext: "112233",
      status: "pending",
      issuedByUid: OPS_UID,
      issuedByRole: "ops_staff",
      expiresAt: futureDate,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await db.collection("audit_logs").doc("prod_audit_secret").set({
      actorId: "admin_uid",
      actorEmail: ADMIN_EMAIL,
      action: "PROD_CONFIG_UPDATE",
      targetCollection: "fare_configs",
      targetId: "toto",
      summary: "Production fare edit",
      isTestRide: false,
      createdAt: nowTs(),
    });
  });

  // Ops CANNOT execute override directly
  await assertFails(
    opsDb.collection("test_ride_otps").doc(OVERRIDE_BOOKING_ID).update({
      used: true,
      overridden: true,
      overrideReason: "Attempt direct bypass",
      overriddenByUid: OPS_UID,
      updatedAt: nowTs(),
    })
  );
  // Ops CAN submit an audited override request
  const opsRequestBatch = opsDb.batch();
  opsRequestBatch.update(opsDb.collection("test_ride_otps").doc(OVERRIDE_BOOKING_ID), {
    overrideRequestedByUid: OPS_UID,
    overrideRequestedReason: "Customer SMS delayed during QA run",
    overrideRequestedAt: nowTs(),
    updatedAt: nowTs(),
  });
  opsRequestBatch.update(opsDb.collection("bookings").doc(OVERRIDE_BOOKING_ID), {
    test_otp_status: "override_requested",
    updatedAt: nowTs(),
  });
  opsRequestBatch.set(opsDb.collection("audit_logs").doc("audit_ops_req_1"), {
    actorId: OPS_UID,
    actorEmail: "",
    action: "TEST_OTP_OVERRIDE_REQUESTED",
    targetCollection: "test_ride_otps",
    targetId: OVERRIDE_BOOKING_ID,
    summary: "[ops_staff] Requested Owner OTP override",
    isTestRide: true,
    createdAt: nowTs(),
  });
  await assertSucceeds(opsRequestBatch.commit());

  // Ops CAN read test audit logs, CANNOT read production audit logs
  await assertSucceeds(opsDb.collection("audit_logs").doc("audit_ops_req_1").get());
  await assertFails(opsDb.collection("audit_logs").doc("prod_audit_secret").get());

  // Owner Admin executes audited OTP override
  const adminOverrideBatch = adminDb.batch();
  adminOverrideBatch.update(adminDb.collection("test_ride_otps").doc(OVERRIDE_BOOKING_ID), {
    used: true,
    overridden: true,
    overrideReason: "Owner approved QA OTP override",
    overriddenByUid: "admin_uid",
    verifiedByUid: "admin_uid",
    updatedAt: nowTs(),
  });
  adminOverrideBatch.update(adminDb.collection("test_ride_otp_secrets").doc(OVERRIDE_BOOKING_ID), {
    status: "overridden",
    updatedAt: nowTs(),
  });
  adminOverrideBatch.update(adminDb.collection("bookings").doc(OVERRIDE_BOOKING_ID), {
    status: "in_progress",
    test_otp_status: "overridden",
    updatedAt: nowTs(),
  });
  adminOverrideBatch.set(adminDb.collection("audit_logs").doc("audit_admin_override_1"), {
    actorId: "admin_uid",
    actorEmail: ADMIN_EMAIL,
    action: "TEST_OTP_ADMIN_OVERRIDE",
    targetCollection: "test_ride_otps",
    targetId: OVERRIDE_BOOKING_ID,
    summary: "[admin] Owner approved QA OTP override",
    isTestRide: true,
    createdAt: nowTs(),
  });
  await assertSucceeds(adminOverrideBatch.commit());

  // Scenario 12: Financial isolation — payments and commissions CANNOT be created for test rides even by Admin
  await assertFails(
    adminDb.collection("payments").doc(TEST_BOOKING_ID).set({
      bookingId: TEST_BOOKING_ID,
      customerId: CUSTOMER_UID,
      driverId: DRIVER_UID,
      grossFareInr: 70,
      gstInr: 3.5,
      platformCommissionInr: 7,
      netDriverEarningsInr: 59.5,
      paymentMethod: "cash",
      status: "settled_cash",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    adminDb.collection("commissions").doc(TEST_BOOKING_ID).set({
      bookingId: TEST_BOOKING_ID,
      driverId: DRIVER_UID,
      grossFareInr: 70,
      commissionRatePct: 10,
      commissionAmountInr: 7,
      netDriverEarningsInr: 63,
      createdAt: nowTs(),
    })
  );

  // Scenario 11: Test ride completion works non-commercially (by assigned Test Partner and by Ops/Admin)
  await assertSucceeds(
    driverDb.collection("bookings").doc(TEST_BOOKING_ID).update({
      status: "completed",
      paymentStatus: "pending",
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    opsDb.collection("ride_assignments").doc(TEST_BOOKING_ID).update({
      status: "completed",
      updatedAt: nowTs(),
    })
  );

  // Scenario 13: Disabling Test Ride Mode blocks new test rides without corrupting existing completed test records
  await assertSucceeds(
    adminDb.collection("platform_settings").doc("global").update({
      testRideModeEnabled: false,
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    custDb.collection("bookings").doc("test_ride_after_off").set(makeTestBookingPayload(CUSTOMER_UID, "Alice Test Customer"))
  );
  const existingAfterOff = await assertSucceeds(custDb.collection("bookings").doc(TEST_BOOKING_ID).get());
  if (!existingAfterOff.exists || existingAfterOff.data().status !== "completed") {
    throw new Error("Existing test ride record was corrupted when testRideModeEnabled was turned off.");
  }

  // Cleanup completed test ride docs succeeds for Staff/Admin
  await assertSucceeds(opsDb.collection("trip_locations").doc(TEST_BOOKING_ID).delete());
  await assertSucceeds(opsDb.collection("live_locations").doc(DRIVER_UID).delete());
  await assertSucceeds(opsDb.collection("test_ride_otps").doc(TEST_BOOKING_ID).delete());
  await assertSucceeds(opsDb.collection("test_ride_otp_secrets").doc(TEST_BOOKING_ID).delete());
  await assertSucceeds(opsDb.collection("ride_assignments").doc(TEST_BOOKING_ID).delete());
  await assertSucceeds(opsDb.collection("bookings").doc(TEST_BOOKING_ID).delete());
});

test("Test Rider Sandbox Mode: applicant onboarding, Ops recommend-only vs Owner Admin approval/revoke, test ride claim, ride_otp_secrets privacy & bi-directional OTP verification", async () => {
  const TEST_RIDER_UID = "test_rider_qa_1";
  const OPS_UID = "ops_sandbox_mgr";
  const ADMIN_UID = "admin_uid";
  const TEST_BOOKING_ID = "sandbox_ride_001";

  const adminDb = testEnv.authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true }).firestore();
  const opsDb = testEnv.authenticatedContext(OPS_UID).firestore();
  const riderDb = testEnv.authenticatedContext(TEST_RIDER_UID, { phone_number: "+919811112222" }).firestore();
  const custDb = testEnv.authenticatedContext(CUSTOMER_UID, { phone_number: "+919876543210" }).firestore();
  const bobDb = testEnv.authenticatedContext(OTHER_UID).firestore();

  function sha256Otp(bookingId, otp) {
    return crypto.createHash("sha256").update(`${bookingId}:${otp}`).digest("hex");
  }

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("operations_staff").doc(OPS_UID).set({
      userId: OPS_UID,
      displayName: "Ops Reviewer",
      assignedDistrict: "Hooghly",
      isActive: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await db.collection("platform_settings").doc("global").set({
      helplineNumber: "+91-1800-103-0000",
      testRideModeEnabled: true,
      updatedAt: nowTs(),
    });
    await db.collection("users").doc(CUSTOMER_UID).set({
      uid: CUSTOMER_UID,
      userId: CUSTOMER_UID,
      fullName: "Alice Sandbox",
      role: "customer",
      preferredLanguage: "en",
      accountStatus: "active",
      isTestCustomer: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await db.collection("bookings").doc("prod_booking_hidden").set({
      customerId: CUSTOMER_UID,
      customerName: "Alice Sandbox",
      bookingType: "now",
      vehicleCategory: "toto",
      pickupLabel: "Bandel",
      destinationLabel: "Chinsurah",
      distanceKm: 4.0,
      estimatedFareInr: 70,
      minPermittedFareInr: 60,
      maxPermittedFareInr: 90,
      paymentMethod: "cash",
      paymentStatus: "pending",
      status: "searching",
      is_test_ride: false,
      test_mode: false,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await db.collection("bookings").doc(TEST_BOOKING_ID).set({
      customerId: CUSTOMER_UID,
      customerName: "Alice Sandbox",
      bookingType: "now",
      vehicleCategory: "toto",
      pickupLabel: "Bandel",
      destinationLabel: "Hooghly Ghat",
      distanceKm: 3.5,
      estimatedFareInr: 0,
      minPermittedFareInr: 0,
      maxPermittedFareInr: 0,
      paymentMethod: "cash",
      paymentStatus: "pending",
      status: "searching",
      is_test_ride: true,
      test_mode: true,
      test_otp_status: "awaiting_assignment",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
  });

  // 1. Applicant CANNOT self-approve or spoof reviewedByUid; CAN submit pending request
  await assertFails(
    riderDb.collection("test_rider_requests").doc(TEST_RIDER_UID).set({
      uid: TEST_RIDER_UID,
      fullName: "QA Test Rider",
      mobileNumber: "+919811112222",
      vehicleCategory: "toto",
      testPurpose: "Field GPS validation",
      status: "approved",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    riderDb.collection("test_riders").doc(TEST_RIDER_UID).set({
      uid: TEST_RIDER_UID,
      displayName: "QA Test Rider",
      vehicleCategory: "toto",
      status: "approved",
      isOnline: true,
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    riderDb.collection("test_rider_requests").doc(TEST_RIDER_UID).set({
      uid: TEST_RIDER_UID,
      fullName: "QA Test Rider",
      mobileNumber: "+919811112222",
      email: "qa@nrnext.in",
      vehicleCategory: "toto",
      testPurpose: "Field GPS validation across Hooghly",
      notes: "",
      status: "pending",
      rejectionReason: "",
      reviewedByUid: "",
      reviewedByRole: "",
      reviewNote: "",
      opsRecommendation: "",
      opsRecommendedByUid: "",
      opsRecommendedNote: "",
      isOnline: false,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Pending rider CANNOT read searching test rides yet
  await assertFails(riderDb.collection("bookings").doc(TEST_BOOKING_ID).get());

  // 2. Operations Staff CAN recommend ONLY (CANNOT approve, reject, or write test_riders)
  await assertFails(
    opsDb.collection("test_rider_requests").doc(TEST_RIDER_UID).update({
      status: "approved",
      reviewedByUid: OPS_UID,
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    opsDb.collection("test_riders").doc(TEST_RIDER_UID).set({
      uid: TEST_RIDER_UID,
      displayName: "QA Test Rider",
      vehicleCategory: "toto",
      status: "approved",
      isOnline: false,
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    opsDb.collection("test_rider_requests").doc(TEST_RIDER_UID).update({
      opsRecommendation: "recommend_approve",
      opsRecommendedByUid: OPS_UID,
      opsRecommendedNote: "Verified internal QA tester",
      updatedAt: nowTs(),
    })
  );

  // 3. Owner Admin rejects with reason -> Applicant reapplies -> Owner Admin approves + creates test_riders doc
  await assertFails(
    adminDb.collection("test_rider_requests").doc(TEST_RIDER_UID).update({
      status: "rejected",
      rejectionReason: "no",
      reviewedByUid: ADMIN_UID,
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    adminDb.collection("test_rider_requests").doc(TEST_RIDER_UID).update({
      status: "rejected",
      rejectionReason: "Please clarify testing district",
      reviewedByUid: ADMIN_UID,
      reviewedByRole: "admin",
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    riderDb.collection("test_rider_requests").doc(TEST_RIDER_UID).update({
      fullName: "QA Test Rider",
      mobileNumber: "+919811112222",
      email: "qa@nrnext.in",
      vehicleCategory: "toto",
      testPurpose: "Field GPS validation in Chinsurah",
      notes: "Reapplied with district details",
      status: "pending",
      rejectionReason: "",
      reviewedByUid: "",
      opsRecommendation: "",
      deviceInfoSummary: "Android",
      updatedAt: nowTs(),
    })
  );

  const approveBatch = adminDb.batch();
  approveBatch.update(adminDb.collection("test_rider_requests").doc(TEST_RIDER_UID), {
    status: "approved",
    rejectionReason: "",
    reviewedByUid: ADMIN_UID,
    reviewedByRole: "admin",
    reviewNote: "Approved for sandbox QA",
    updatedAt: nowTs(),
  });
  approveBatch.set(adminDb.collection("test_riders").doc(TEST_RIDER_UID), {
    uid: TEST_RIDER_UID,
    displayName: "QA Test Rider",
    mobileNumber: "+919811112222",
    vehicleCategory: "toto",
    vehicleLabel: "TEST RIDER • TOTO",
    status: "approved",
    isOnline: false,
    currentLat: 0,
    currentLng: 0,
    completedTestRides: 0,
    updatedAt: nowTs(),
  });
  approveBatch.set(adminDb.collection("test_rider_audit_logs").doc("tr_audit_1"), {
    targetUid: TEST_RIDER_UID,
    actorUid: ADMIN_UID,
    actorRole: "admin",
    action: "TEST_RIDER_APPROVED",
    previousStatus: "pending",
    newStatus: "approved",
    reason: "",
    note: "Approved for sandbox QA",
    createdAt: nowTs(),
  });
  await assertSucceeds(approveBatch.commit());

  // Approved Test Rider does NOT gain commercial KYC verification and CANNOT read production searching rides
  await assertFails(riderDb.collection("bookings").doc("prod_booking_hidden").get());
  await assertSucceeds(riderDb.collection("bookings").doc(TEST_BOOKING_ID).get());
  await assertSucceeds(
    riderDb.collection("bookings").where("status", "==", "searching").where("is_test_ride", "==", true).get()
  );

  // Approved Test Rider can toggle only allowed live presence keys on test_riders
  await assertSucceeds(
    riderDb.collection("test_riders").doc(TEST_RIDER_UID).update({
      isOnline: true,
      currentLat: 22.91,
      currentLng: 88.39,
      updatedAt: nowTs(),
    })
  );

  // 4. Test Rider claims open test ride atomically & initializes 6-digit OTP challenge + Customer-only ride_otp_secrets
  const RIDER_OTP = "593827";
  const futureDate = new Date(Date.now() + 300 * 1000);
  const claimBatch = riderDb.batch();
  claimBatch.update(riderDb.collection("bookings").doc(TEST_BOOKING_ID), {
    status: "assigned",
    assignedDriverId: TEST_RIDER_UID,
    assignedDriverName: "QA Test Rider",
    assignedVehicleLabel: "TEST RIDER • TOTO",
    agreedFareInr: 0,
    test_otp_status: "pending",
    test_otp_expires_at_ms: futureDate.getTime(),
    updatedAt: nowTs(),
  });
  claimBatch.set(riderDb.collection("ride_assignments").doc(TEST_BOOKING_ID), {
    bookingId: TEST_BOOKING_ID,
    customerId: CUSTOMER_UID,
    driverId: TEST_RIDER_UID,
    offerId: "test_rider_claim",
    lockedFareInr: 1,
    status: "active",
    is_test_ride: true,
    assignedByUid: TEST_RIDER_UID,
    assignedByRole: "customer",
    createdAt: nowTs(),
    updatedAt: nowTs(),
  });
  claimBatch.set(riderDb.collection("test_ride_otps").doc(TEST_BOOKING_ID), {
    bookingId: TEST_BOOKING_ID,
    customerId: CUSTOMER_UID,
    otpHash: sha256Otp(TEST_BOOKING_ID, RIDER_OTP),
    submittedHash: "",
    used: false,
    overridden: false,
    overrideReason: "",
    overrideRequestedByUid: "",
    overrideRequestedReason: "",
    overriddenByUid: "",
    verifiedByUid: "",
    attempts: 0,
    maxAttempts: 5,
    expiresAt: futureDate,
    createdAt: nowTs(),
    updatedAt: nowTs(),
  });
  claimBatch.set(riderDb.collection("ride_otp_secrets").doc(TEST_BOOKING_ID), {
    bookingId: TEST_BOOKING_ID,
    customerId: CUSTOMER_UID,
    otpPlaintext: RIDER_OTP,
    isTestRide: true,
    status: "pending",
    expiresAt: futureDate,
    createdAt: nowTs(),
    updatedAt: nowTs(),
  });
  claimBatch.set(riderDb.collection("trip_locations").doc(TEST_BOOKING_ID), {
    bookingId: TEST_BOOKING_ID,
    customerId: CUSTOMER_UID,
    driverId: TEST_RIDER_UID,
    lat: 22.91,
    lng: 88.39,
    isTestRide: true,
    isSimulated: false,
    updatedAt: nowTs(),
  });
  await assertSucceeds(claimBatch.commit());

  // 5. Privacy of ride_otp_secrets: ONLY Customer (and Admin/Ops) can read plaintext OTP; Test Rider and outsiders CANNOT read it
  await assertSucceeds(custDb.collection("ride_otp_secrets").doc(TEST_BOOKING_ID).get());
  await assertFails(riderDb.collection("ride_otp_secrets").doc(TEST_BOOKING_ID).get());
  await assertFails(bobDb.collection("ride_otp_secrets").doc(TEST_BOOKING_ID).get());

  // 6. Test Rider advances assigned -> arriving -> arrived; cannot jump arrived -> in_progress without valid OTP
  await assertSucceeds(riderDb.collection("bookings").doc(TEST_BOOKING_ID).update({ status: "arriving", updatedAt: nowTs() }));
  await assertSucceeds(riderDb.collection("bookings").doc(TEST_BOOKING_ID).update({ status: "arrived", updatedAt: nowTs() }));
  await assertFails(riderDb.collection("bookings").doc(TEST_BOOKING_ID).update({ status: "in_progress", updatedAt: nowTs() }));

  // Wrong OTP hash rejected for Test Rider; valid OTP entered by Test Rider succeeds atomically
  await assertFails(
    riderDb.collection("test_ride_otps").doc(TEST_BOOKING_ID).update({
      used: true,
      submittedHash: sha256Otp(TEST_BOOKING_ID, "000000"),
      verifiedByUid: TEST_RIDER_UID,
      updatedAt: nowTs(),
    })
  );
  const riderOtpBatch = riderDb.batch();
  riderOtpBatch.update(riderDb.collection("test_ride_otps").doc(TEST_BOOKING_ID), {
    used: true,
    submittedHash: sha256Otp(TEST_BOOKING_ID, RIDER_OTP),
    verifiedByUid: TEST_RIDER_UID,
    updatedAt: nowTs(),
  });
  riderOtpBatch.update(riderDb.collection("bookings").doc(TEST_BOOKING_ID), {
    status: "in_progress",
    test_otp_status: "verified",
    updatedAt: nowTs(),
  });
  await assertSucceeds(riderOtpBatch.commit());

  // 7. Owner Admin revokes Test Rider -> rider immediately loses access to searching test rides
  await assertSucceeds(
    adminDb.collection("test_riders").doc(TEST_RIDER_UID).update({
      status: "revoked",
      isOnline: false,
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    riderDb.collection("bookings").where("status", "==", "searching").where("is_test_ride", "==", true).get()
  );
});

test("Multi-device FCM tokens & Notification Centre isolation rules", async () => {
  const custDb = testEnv.authenticatedContext(CUSTOMER_UID).firestore();
  const riderDb = testEnv.authenticatedContext(DRIVER_UID).firestore();
  const rogueDb = testEnv.authenticatedContext(OTHER_UID).firestore();
  const opsDb = testEnv.authenticatedContext("ops_staff_notif").firestore();
  const adminDb = testEnv.authenticatedContext("admin_uid", { email: ADMIN_EMAIL, email_verified: true }).firestore();

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("operations_staff").doc("ops_staff_notif").set({
      userId: "ops_staff_notif",
      displayName: "Ops Notif User",
      assignedDistrict: "Hooghly",
      isActive: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
  });

  // 1. User registers multiple simultaneous devices cleanly; deleting one device preserves the other
  await assertSucceeds(
    custDb.collection("user_tokens").doc(`${CUSTOMER_UID}_customer_devA`).set({
      userId: CUSTOMER_UID,
      deviceId: "devA",
      appVariant: "customer",
      appVersionCode: 10,
      platform: "android",
      token: "fcm_tok_device_A",
      active: true,
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    custDb.collection("user_tokens").doc(`${CUSTOMER_UID}_customer_devB`).set({
      userId: CUSTOMER_UID,
      deviceId: "devB",
      appVariant: "customer",
      appVersionCode: 10,
      platform: "android",
      token: "fcm_tok_device_B",
      active: true,
      updatedAt: nowTs(),
    })
  );

  // Sign out on devA deletes only devA token; outsider cannot spoof or delete another user's token
  await assertFails(rogueDb.collection("user_tokens").doc(`${CUSTOMER_UID}_customer_devB`).delete());
  await assertFails(
    rogueDb.collection("user_tokens").doc(`${CUSTOMER_UID}_customer_devC`).set({
      userId: CUSTOMER_UID,
      deviceId: "devC",
      appVariant: "customer",
      platform: "android",
      token: "spoof_tok",
      active: true,
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(custDb.collection("user_tokens").doc(`${CUSTOMER_UID}_customer_devA`).delete());
  await assertSucceeds(custDb.collection("user_tokens").doc(`${CUSTOMER_UID}_customer_devB`).get());

  // 2. Notification Centre metadata writes & strict recipient read/read-only update isolation
  await assertSucceeds(
    opsDb.collection("notifications").doc("notif_test_1").set({
      userId: CUSTOMER_UID,
      title: "TEST RIDE Assigned",
      body: "Open Active Ride to verify your pickup code.",
      type: "test_otp",
      bookingId: "bk_test_1",
      entityId: "bk_test_1",
      targetApp: "customer",
      targetScreen: "booking",
      isTestRide: true,
      read: false,
      createdAt: nowTs(),
    })
  );
  await assertSucceeds(custDb.collection("notifications").doc("notif_test_1").get());
  await assertSucceeds(custDb.collection("notifications").doc("notif_test_1").update({ read: true }));
  await assertFails(custDb.collection("notifications").doc("notif_test_1").update({ read: true, title: "Hacked" }));
  await assertFails(riderDb.collection("notifications").doc("notif_test_1").get());
  await assertSucceeds(adminDb.collection("notifications").doc("notif_test_1").get());
});

test("Sample Rider: allowlist-gated OTP auto-provisioning, zero plaintext OTP, atomic claim, and commercial isolation", async () => {
  const SAMPLE_RIDER_UID = "sample_rider_phone_uid_1";
  const SECOND_SAMPLE_UID = "sample_rider_phone_uid_2";
  const SAMPLE_PHONE_1 = "+919800001111";
  const SAMPLE_PHONE_2 = "+919800002222";
  const UNAUTHORIZED_PHONE = "+919800009999";
  const SAMPLE_RIDE_ID = "sample_ride_case_1";
  function sha256Otp(rideId, otp) {
    return crypto.createHash("sha256").update(`${rideId}:${otp}`).digest("hex");
  }

  const adminDb = testEnv.authenticatedContext("admin_sample_mgr", { email: ADMIN_EMAIL, email_verified: true }).firestore();
  const sampleRider1Db = testEnv.authenticatedContext(SAMPLE_RIDER_UID, { phone_number: SAMPLE_PHONE_1 }).firestore();
  const sampleRider2Db = testEnv.authenticatedContext(SECOND_SAMPLE_UID, { phone_number: SAMPLE_PHONE_2 }).firestore();
  const unauthorizedPhoneDb = testEnv.authenticatedContext("unlisted_phone_uid", { phone_number: UNAUTHORIZED_PHONE }).firestore();
  const testCustDb = testEnv.authenticatedContext(CUSTOMER_UID).firestore();
  const regularCustDb = testEnv.authenticatedContext(OTHER_UID).firestore();

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(CUSTOMER_UID).set({
      userId: CUSTOMER_UID,
      fullName: "Sample Test Customer",
      role: "customer",
      preferredLanguage: "en",
      accountStatus: "active",
      isTestCustomer: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await ctx.firestore().collection("users").doc(OTHER_UID).set({
      userId: OTHER_UID,
      fullName: "Regular Customer",
      role: "customer",
      preferredLanguage: "en",
      accountStatus: "active",
      isTestCustomer: false,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await ctx.firestore().collection("bookings").doc("commercial_hidden_booking").set({
      customerId: OTHER_UID,
      customerName: "Regular Customer",
      bookingType: "now",
      vehicleCategory: "toto",
      pickupLabel: "Station",
      destinationLabel: "Market",
      distanceKm: 2.0,
      estimatedFareInr: 40,
      minPermittedFareInr: 30,
      maxPermittedFareInr: 50,
      status: "searching",
      paymentMode: "cash",
      paymentStatus: "unpaid",
      is_test_ride: false,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
    await ctx.firestore().collection("driver_profiles").doc("commercial_driver_hidden").set({
      driverId: "commercial_driver_hidden",
      legalName: "Commercial Driver",
      phone: "+919111122222",
      district: "Hooghly",
      vehicleCategory: "toto",
      vehicleRegNumber: "WB12T1234",
      drivingLicenceNumber: "DL123456",
      verificationStatus: "verified",
      isOnline: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
  });

  // 1. Non-allowlisted user CANNOT self-assign sample rider role or create sample_riders profile
  await assertFails(
    unauthorizedPhoneDb.collection("users").doc("unlisted_phone_uid").set({
      userId: "unlisted_phone_uid",
      fullName: "Self Grant Hacker",
      role: "rider",
      accountType: "sample",
      isSampleRider: true,
      verificationStatus: "sample_approved",
      availabilityStatus: "offline",
      preferredLanguage: "en",
      accountStatus: "active",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    unauthorizedPhoneDb.collection("sample_riders").doc("unlisted_phone_uid").set({
      uid: "unlisted_phone_uid",
      role: "rider",
      accountType: "sample",
      isSampleRider: true,
      verificationStatus: "sample_approved",
      availabilityStatus: "offline",
      displayName: "Hacker",
      phone: UNAUTHORIZED_PHONE,
      vehicleCategory: "toto",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // 2. Admin allowlists phone #1 and #2
  await assertSucceeds(
    adminDb.collection("sample_rider_allowlist").doc(SAMPLE_PHONE_1).set({
      phoneE164: SAMPLE_PHONE_1,
      enabled: true,
      label: "Sample Rider One",
      vehicleCategory: "toto",
      addedByUid: "admin_sample_mgr",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    adminDb.collection("sample_rider_allowlist").doc(SAMPLE_PHONE_2).set({
      phoneE164: SAMPLE_PHONE_2,
      enabled: true,
      label: "Sample Rider Two",
      vehicleCategory: "auto",
      addedByUid: "admin_sample_mgr",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Allowlisted phone can check ONLY its own allowlist doc, never list all allowlist entries
  await assertSucceeds(sampleRider1Db.collection("sample_rider_allowlist").doc(SAMPLE_PHONE_1).get());
  await assertFails(sampleRider1Db.collection("sample_rider_allowlist").doc(SAMPLE_PHONE_2).get());
  await assertFails(sampleRider1Db.collection("sample_rider_allowlist").get());

  // 3. Allowlisted phone user provisions /users/{uid} and /sample_riders/{uid} with zero commercial KYC docs
  await assertSucceeds(
    sampleRider1Db.collection("users").doc(SAMPLE_RIDER_UID).set({
      userId: SAMPLE_RIDER_UID,
      fullName: "Sample Rider One",
      role: "rider",
      accountType: "sample",
      isSampleRider: true,
      verificationStatus: "sample_approved",
      availabilityStatus: "offline",
      phone: SAMPLE_PHONE_1,
      mobileNumber: SAMPLE_PHONE_1,
      preferredLanguage: "en",
      accountStatus: "active",
      mobileVerified: true,
      profileCompleted: true,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    sampleRider1Db.collection("sample_riders").doc(SAMPLE_RIDER_UID).set({
      uid: SAMPLE_RIDER_UID,
      role: "rider",
      accountType: "sample",
      isSampleRider: true,
      verificationStatus: "sample_approved",
      availabilityStatus: "offline",
      displayName: "Sample Rider One",
      phone: SAMPLE_PHONE_1,
      vehicleCategory: "toto",
      completedSampleRides: 0,
      totalSampleEarningsInr: 0,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    sampleRider2Db.collection("sample_riders").doc(SECOND_SAMPLE_UID).set({
      uid: SECOND_SAMPLE_UID,
      role: "rider",
      accountType: "sample",
      isSampleRider: true,
      verificationStatus: "sample_approved",
      availabilityStatus: "offline",
      displayName: "Sample Rider Two",
      phone: SAMPLE_PHONE_2,
      vehicleCategory: "auto",
      completedSampleRides: 0,
      totalSampleEarningsInr: 0,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Sample Rider can update real GPS, profile & online/offline status, CANNOT tamper with earnings or role
  await assertSucceeds(
    sampleRider1Db.collection("sample_riders").doc(SAMPLE_RIDER_UID).update({
      availabilityStatus: "online",
      currentLat: 22.905,
      currentLng: 88.392,
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    sampleRider1Db.collection("sample_riders").doc(SAMPLE_RIDER_UID).update({
      totalSampleEarningsInr: 99999,
      updatedAt: nowTs(),
    })
  );

  // Sample Rider is COMPLETELY isolated from commercial bookings and commercial driver_profiles
  await assertFails(sampleRider1Db.collection("bookings").doc("commercial_hidden_booking").get());
  await assertFails(sampleRider1Db.collection("driver_profiles").doc("commercial_driver_hidden").get());

  // 4. Unauthorized regular customer cannot dispatch sample ride; authorized Test Customer creates sample_rides + hashed OTP challenge (NO plaintext OTP)
  const SAMPLE_OTP = "482915";
  const expiresAt = new Date(Date.now() + 180 * 1000);
  await assertFails(
    regularCustDb.collection("sample_rides").doc("unauth_sample").set({
      rideId: "unauth_sample",
      isSample: true,
      customerId: OTHER_UID,
      customerName: "Regular Customer",
      vehicleCategory: "toto",
      pickupLabel: "A",
      pickupLat: 22.9,
      pickupLng: 88.3,
      destinationLabel: "B",
      destinationLat: 22.91,
      destinationLng: 88.31,
      distanceKm: 2.5,
      estimatedFareInr: 45,
      status: "incoming",
      assignedSampleRiderId: "",
      otpVerified: false,
      expiresAt,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Plaintext OTP keys are strictly rejected on sample_rides & sample_ride_otps
  await assertFails(
    testCustDb.collection("sample_rides").doc("bad_plaintext_ride").set({
      rideId: "bad_plaintext_ride",
      isSample: true,
      customerId: CUSTOMER_UID,
      customerName: "Sample Test Customer",
      vehicleCategory: "toto",
      pickupLabel: "Station",
      pickupLat: 22.9041,
      pickupLng: 88.3915,
      destinationLabel: "Hospital",
      destinationLat: 22.9182,
      destinationLng: 88.3991,
      distanceKm: 2.1,
      estimatedFareInr: 40,
      status: "incoming",
      assignedSampleRiderId: "",
      otpVerified: false,
      otp: "123456",
      expiresAt,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  const createBatch = testCustDb.batch();
  createBatch.set(testCustDb.collection("sample_rides").doc(SAMPLE_RIDE_ID), {
    rideId: SAMPLE_RIDE_ID,
    isSample: true,
    customerId: CUSTOMER_UID,
    customerName: "Sample Test Customer",
    createdByUid: CUSTOMER_UID,
    createdByRole: "customer",
    vehicleCategory: "toto",
    pickupLabel: "Borai Railway Crossing",
    pickupLat: 22.9041,
    pickupLng: 88.3915,
    destinationLabel: "Chinsurah Court Gate",
    destinationLat: 22.9182,
    destinationLng: 88.3991,
    distanceKm: 2.4,
    estimatedDurationMin: 9,
    estimatedFareInr: 50,
    status: "incoming",
    assignedSampleRiderId: "",
    assignedSampleRiderName: "",
    assignedSampleRiderPhone: "",
    otpVerified: false,
    expiresAt,
    createdAt: nowTs(),
    updatedAt: nowTs(),
  });
  createBatch.set(testCustDb.collection("sample_ride_otps").doc(SAMPLE_RIDE_ID), {
    rideId: SAMPLE_RIDE_ID,
    customerId: CUSTOMER_UID,
    otpHash: sha256Otp(SAMPLE_RIDE_ID, SAMPLE_OTP),
    submittedHash: "",
    used: false,
    attempts: 0,
    maxAttempts: 5,
    lastAttemptAtMs: 0,
    verifiedByUid: "",
    expiresAt,
    createdAt: nowTs(),
    updatedAt: nowTs(),
  });
  await assertSucceeds(createBatch.commit());

  // 5. Neither Sample Rider nor Customer can read sample_ride_otps directly
  await assertFails(sampleRider1Db.collection("sample_ride_otps").doc(SAMPLE_RIDE_ID).get());
  await assertFails(testCustDb.collection("sample_ride_otps").doc(SAMPLE_RIDE_ID).get());

  // Customer & Active Sample Rider scoped list queries succeed; unfiltered phone-user list fails
  await assertSucceeds(testCustDb.collection("sample_rides").where("customerId", "==", CUSTOMER_UID).get());
  await assertSucceeds(unauthorizedPhoneDb.collection("sample_rides").where("customerId", "==", "unlisted_phone_uid").get());
  await assertSucceeds(sampleRider1Db.collection("sample_rides").where("status", "==", "incoming").get());
  await assertSucceeds(sampleRider1Db.collection("sample_rides").where("assignedSampleRiderId", "==", SAMPLE_RIDER_UID).get());
  await assertFails(unauthorizedPhoneDb.collection("sample_rides").get());
  await assertFails(unauthorizedPhoneDb.collection("sample_rides").where("status", "==", "incoming").get());

  // 6. Atomic acceptance: Rider 1 claims; Rider 2 claim immediately fails because status != 'incoming'
  await assertSucceeds(
    sampleRider1Db.collection("sample_rides").doc(SAMPLE_RIDE_ID).update({
      status: "accepted",
      assignedSampleRiderId: SAMPLE_RIDER_UID,
      assignedSampleRiderName: "Sample Rider One",
      assignedSampleRiderPhone: SAMPLE_PHONE_1,
      riderLat: 22.905,
      riderLng: 88.392,
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    sampleRider2Db.collection("sample_rides").doc(SAMPLE_RIDE_ID).update({
      status: "accepted",
      assignedSampleRiderId: SECOND_SAMPLE_UID,
      assignedSampleRiderName: "Sample Rider Two",
      assignedSampleRiderPhone: SAMPLE_PHONE_2,
      updatedAt: nowTs(),
    })
  );

  // 7. Rider progresses arriving -> arrived; cannot bypass OTP verification to enter in_progress
  await assertSucceeds(
    sampleRider1Db.collection("sample_rides").doc(SAMPLE_RIDE_ID).update({ status: "arriving", updatedAt: nowTs() })
  );
  await assertSucceeds(
    sampleRider1Db.collection("sample_rides").doc(SAMPLE_RIDE_ID).update({ status: "arrived", updatedAt: nowTs() })
  );
  await assertFails(
    sampleRider1Db.collection("sample_rides").doc(SAMPLE_RIDE_ID).update({
      status: "in_progress",
      otpVerified: true,
      updatedAt: nowTs(),
    })
  );

  // Wrong OTP rejected and attempt incremented cleanly; correct OTP starts ride atomically
  await assertFails(
    sampleRider1Db.collection("sample_ride_otps").doc(SAMPLE_RIDE_ID).update({
      used: true,
      submittedHash: sha256Otp(SAMPLE_RIDE_ID, "000000"),
      verifiedByUid: SAMPLE_RIDER_UID,
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    sampleRider1Db.collection("sample_ride_otps").doc(SAMPLE_RIDE_ID).update({
      attempts: 1,
      lastAttemptAtMs: Date.now(),
      updatedAt: nowTs(),
    })
  );

  const startTripBatch = sampleRider1Db.batch();
  startTripBatch.update(sampleRider1Db.collection("sample_ride_otps").doc(SAMPLE_RIDE_ID), {
    used: true,
    submittedHash: sha256Otp(SAMPLE_RIDE_ID, SAMPLE_OTP),
    verifiedByUid: SAMPLE_RIDER_UID,
    updatedAt: nowTs(),
  });
  startTripBatch.update(sampleRider1Db.collection("sample_rides").doc(SAMPLE_RIDE_ID), {
    status: "in_progress",
    otpVerified: true,
    otpVerifiedAt: nowTs(),
    riderLat: 22.9041,
    riderLng: 88.3915,
    updatedAt: nowTs(),
  });
  await assertSucceeds(startTripBatch.commit());

  // Complete sample ride
  await assertSucceeds(
    sampleRider1Db.collection("sample_rides").doc(SAMPLE_RIDE_ID).update({
      status: "completed",
      completedAt: nowTs(),
      riderLat: 22.9182,
      riderLng: 88.3991,
      updatedAt: nowTs(),
    })
  );

  // 8. Admin revokes Sample Rider -> loses access to incoming sample rides
  await assertSucceeds(
    adminDb.collection("sample_riders").doc(SAMPLE_RIDER_UID).update({
      verificationStatus: "revoked",
      availabilityStatus: "offline",
      updatedAt: nowTs(),
    })
  );
  await assertFails(sampleRider1Db.collection("sample_rides").where("status", "==", "incoming").get());
});


