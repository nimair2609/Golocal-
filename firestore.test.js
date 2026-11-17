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

