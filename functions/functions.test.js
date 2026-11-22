const test = require("node:test");
const assert = require("node:assert/strict");
const {
  createNotificationHandlers,
  sanitizeNotificationText,
  clickActionForApp,
  channelForPayload,
} = require("./handlers");

function createFakeFirestore(seed = {}) {
  const store = new Map();
  for (const [col, docs] of Object.entries(seed)) {
    const colMap = new Map();
    for (const [id, data] of Object.entries(docs)) {
      colMap.set(id, { ...data });
    }
    store.set(col, colMap);
  }

  function getColMap(name) {
    if (!store.has(name)) store.set(name, new Map());
    return store.get(name);
  }

  function makeDocRef(colName, id) {
    return {
      id,
      path: `${colName}/${id}`,
      async get() {
        const col = getColMap(colName);
        const val = col.get(id);
        return {
          id,
          exists: val !== undefined,
          data: () => (val ? { ...val } : undefined),
        };
      },
      async set(data, opts) {
        const col = getColMap(colName);
        if (opts && opts.merge && col.has(id)) {
          col.set(id, { ...col.get(id), ...data });
        } else {
          col.set(id, { ...data });
        }
      },
      async create(data) {
        const col = getColMap(colName);
        if (col.has(id)) {
          const err = new Error("Document already exists");
          err.code = 6;
          throw err;
        }
        col.set(id, { ...data });
      },
      async delete() {
        getColMap(colName).delete(id);
      },
    };
  }

  function makeQuery(colName, filters = [], limitCount = null) {
    return {
      where(field, op, value) {
        return makeQuery(colName, [...filters, { field, op, value }], limitCount);
      },
      limit(n) {
        return makeQuery(colName, filters, n);
      },
      async get() {
        const col = getColMap(colName);
        let entries = [...col.entries()].filter(([, doc]) =>
          filters.every((f) => {
            if (f.op === "==") return doc[f.field] === f.value;
            return true;
          })
        );
        if (typeof limitCount === "number") entries = entries.slice(0, limitCount);
        const docs = entries.map(([id, doc]) => ({
          id,
          ref: makeDocRef(colName, id),
          data: () => ({ ...doc }),
        }));
        return {
          empty: docs.length === 0,
          docs,
        };
      },
    };
  }

  return {
    store,
    collection(colName) {
      return {
        doc: (id) => makeDocRef(colName, id),
        where: (field, op, value) => makeQuery(colName, [{ field, op, value }], null),
        limit: (n) => makeQuery(colName, [], n),
        get: () => makeQuery(colName, [], null).get(),
      };
    },
  };
}

function createMockMessaging({ failTokens = new Set() } = {}) {
  const sent = [];
  return {
    sent,
    async sendEachForMulticast(msg) {
      sent.push(msg);
      return {
        responses: msg.tokens.map((tok) => {
          if (failTokens.has(tok)) {
            return {
              success: false,
              error: { code: "messaging/registration-token-not-registered" },
            };
          }
          return { success: true };
        }),
      };
    },
  };
}

const fakeFieldValue = { serverTimestamp: () => ({ _serverTimestamp: true }) };

test("sanitizeNotificationText scrubs OTP, PIN, password, and raw Indian phone numbers", () => {
  assert.equal(
    sanitizeNotificationText("Share Trip PIN: 4829 with driver"),
    "Share TRIP PIN protected (view in app) with driver"
  );
  assert.equal(
    sanitizeNotificationText("TEST OTP: 918273 for pickup"),
    "TEST OTP protected (view in app) for pickup"
  );
  assert.equal(
    sanitizeNotificationText("Call +919876543210 immediately"),
    "Call [protected-contact] immediately"
  );
});

test("clickActionForApp and channelForPayload route role and Test Rider isolation properly", () => {
  assert.equal(clickActionForApp("customer"), "com.aistudio.golocal.OPEN_CUSTOMER");
  assert.equal(clickActionForApp("rider"), "com.aistudio.golocal.OPEN_RIDER");
  assert.equal(clickActionForApp("admin"), "com.aistudio.golocal.OPEN_ADMIN");
  assert.equal(channelForPayload({ type: "booking_assigned", isTestRide: true }), "golocal_test_rides");
  assert.equal(channelForPayload({ type: "test_request", isTestRide: true }), "golocal_test_ride_requests_v2");
  assert.equal(channelForPayload({ type: "sample_request", isSampleRide: true }), "golocal_sample_ride_requests_v2");
  assert.equal(channelForPayload({ type: "sample_status", isSampleRide: true }), "golocal_sample_rides");
  assert.equal(channelForPayload({ type: "new_ride_request", isTestRide: false }), "golocal_ride_requests_v2");
  assert.equal(channelForPayload({ type: "sos_alert", isTestRide: false }), "golocal_sos");
  assert.equal(channelForPayload({ type: "chat", isTestRide: false }), "golocal_chat");
});

test("Booking lifecycle never leaks Trip PIN, syncs in-app notifications, dedupes retries, and cleans invalid tokens", async () => {
  const db = createFakeFirestore({
    user_tokens: {
      "cust1_customer_devA": { userId: "cust1", deviceId: "devA", appVariant: "customer", active: true, token: "tok_good" },
      "cust1_customer_devB": { userId: "cust1", deviceId: "devB", appVariant: "customer", active: true, token: "tok_dead" },
      "driver1_rider_devC": { userId: "driver1", deviceId: "devC", appVariant: "rider", active: true, token: "tok_driver" },
    },
    admins: { admin1: { uid: "admin1" } },
    operations_staff: { ops1: { userId: "ops1", isActive: true } },
  });
  const messaging = createMockMessaging({ failTokens: new Set(["tok_dead"]) });
  const handlers = createNotificationHandlers({ db, messaging, fieldValue: fakeFieldValue });

  const before = { status: "searching", customerId: "cust1" };
  const after = {
    status: "assigned",
    customerId: "cust1",
    assignedDriverId: "driver1",
    assignedDriverName: "Rahul",
    tripPin: "7391",
    pickupLabel: "Bandel Station",
    destinationLabel: "Chinsurah Court",
    is_test_ride: false,
  };

  await handlers.handleBookingWrite("bk_1001", before, after);
  // Retrying same event must be deduped
  await handlers.handleBookingWrite("bk_1001", before, after);

  // One message to customer + one message to driver (no duplicates on retry)
  assert.equal(messaging.sent.length, 2);
  const custPush = messaging.sent[0];
  assert.equal(custPush.android.notification.clickAction, "com.aistudio.golocal.OPEN_CUSTOMER");
  assert.equal(custPush.android.notification.channelId, "golocal_rides");
  assert.ok(!custPush.notification.body.includes("7391"), "Customer push must NOT contain raw tripPin");

  // Dead token cleaned up from Firestore
  assert.equal(db.store.get("user_tokens").has("cust1_customer_devB"), false);
  assert.equal(db.store.get("user_tokens").has("cust1_customer_devA"), true);

  // Synced to in-app notifications collection
  const notifEntries = [...db.store.get("notifications").values()];
  assert.equal(notifEntries.length, 2);
  assert.ok(notifEntries.some((n) => n.userId === "cust1" && n.bookingId === "bk_1001" && n.targetScreen === "booking"));
  assert.ok(notifEntries.some((n) => n.userId === "driver1" && n.targetApp === "rider"));
});

test("Test Ride isolation: test booking search notifies ONLY approved online test_riders on golocal_test_rides channel", async () => {
  const db = createFakeFirestore({
    driver_directory: {
      prod_driver: { driverId: "prod_driver", verificationStatus: "verified", isOnline: true, vehicleCategory: "toto" },
    },
    test_riders: {
      test_rider_1: { uid: "test_rider_1", status: "approved", isOnline: true },
    },
    user_tokens: {
      "cust1_customer_d1": { userId: "cust1", appVariant: "customer", active: true, token: "tok_c" },
      "prod_driver_rider_d2": { userId: "prod_driver", appVariant: "rider", active: true, token: "tok_prod" },
      "test_rider_1_rider_d3": { userId: "test_rider_1", appVariant: "rider", active: true, token: "tok_test_r" },
    },
  });
  const messaging = createMockMessaging();
  const handlers = createNotificationHandlers({ db, messaging, fieldValue: fakeFieldValue });

  await handlers.handleBookingWrite("test_bk_1", undefined, {
    status: "searching",
    customerId: "cust1",
    vehicleCategory: "toto",
    pickupLabel: "Hooghly Ghat",
    destinationLabel: "Bandel Church",
    is_test_ride: true,
  });

  const sentTokens = messaging.sent.flatMap((m) => m.tokens);
  assert.ok(sentTokens.includes("tok_c"));
  assert.ok(sentTokens.includes("tok_test_r"));
  assert.ok(!sentTokens.includes("tok_prod"), "Commercial driver must NEVER receive Test Ride push");
  assert.equal(messaging.sent[0].notification.title, "TEST RIDE Created — Finding Test Rider");
  assert.equal(messaging.sent[1].notification, undefined, "Ride request push must be data-only to wake background service");
  assert.equal(messaging.sent[1].android.notification, undefined);
  assert.equal(messaging.sent[1].android.priority, "high");
  assert.equal(messaging.sent[1].android.ttl, 45000);
  assert.equal(messaging.sent[1].data.channelId, "golocal_test_ride_requests_v2");
  assert.equal(messaging.sent[1].data.isTestRide, "true");
  assert.ok(Number(messaging.sent[1].data.requestExpiresAtMs) > 0, "Ride request push should carry expiry timestamp");
});

test("Admin & Operations alerts fire for SOS, complaints, KYC pending, Test Rider requests, and post-assignment cancellations", async () => {
  const db = createFakeFirestore({
    admins: { admin1: { uid: "admin1" } },
    operations_staff: {
      ops_active: { userId: "ops_active", isActive: true },
      ops_revoked: { userId: "ops_revoked", isActive: false },
    },
    user_tokens: {
      "admin1_admin_d1": { userId: "admin1", appVariant: "admin", active: true, token: "tok_admin" },
      "ops_active_admin_d2": { userId: "ops_active", appVariant: "admin", active: true, token: "tok_ops" },
      "ops_revoked_admin_d3": { userId: "ops_revoked", appVariant: "admin", active: true, token: "tok_revoked" },
    },
  });
  const messaging = createMockMessaging();
  const handlers = createNotificationHandlers({ db, messaging, fieldValue: fakeFieldValue });

  await handlers.handleEmergencyEventCreate("sos_1", { bookingId: "bk_1", notes: "Panic alert" });
  await handlers.handleComplaintWrite("cmp_1", undefined, { bookingId: "bk_1", category: "overcharge", description: "Issue" });
  await handlers.handleTestRiderRequestWrite("applicant_1", undefined, { fullName: "QA Tester", vehicleCategory: "toto", status: "pending" });

  const allTokens = messaging.sent.flatMap((m) => m.tokens);
  assert.ok(allTokens.includes("tok_admin"));
  assert.ok(allTokens.includes("tok_ops"));
  assert.ok(!allTokens.includes("tok_revoked"));
});

test("Sample Ride dispatch notifies ONLY online sample_riders on golocal_sample_ride_requests_v2 and never leaks commercial tokens", async () => {
  const db = createFakeFirestore({
    driver_directory: {
      prod_driver: { driverId: "prod_driver", verificationStatus: "verified", isOnline: true },
    },
    sample_riders: {
      sample_online: { uid: "sample_online", verificationStatus: "sample_approved", availabilityStatus: "online" },
      sample_offline: { uid: "sample_offline", verificationStatus: "sample_approved", availabilityStatus: "offline" },
    },
    user_tokens: {
      "prod_driver_rider_d1": { userId: "prod_driver", appVariant: "rider", active: true, token: "tok_prod" },
      "sample_online_rider_d2": { userId: "sample_online", appVariant: "rider", active: true, token: "tok_sample_online" },
      "sample_offline_rider_d3": { userId: "sample_offline", appVariant: "rider", active: true, token: "tok_sample_offline" },
      "cust1_customer_d4": { userId: "cust1", appVariant: "customer", active: true, token: "tok_cust" },
    },
  });
  const messaging = createMockMessaging();
  const handlers = createNotificationHandlers({ db, messaging, fieldValue: fakeFieldValue });

  await handlers.handleSampleRideWrite("sr_1", undefined, {
    status: "incoming",
    customerId: "cust1",
    pickupLabel: "Borai Station",
    destinationLabel: "Chinsurah Court",
    distanceKm: 2.5,
    estimatedFareInr: 45,
  });

  assert.equal(messaging.sent.length, 1);
  assert.deepEqual(messaging.sent[0].tokens, ["tok_sample_online"]);
  assert.equal(messaging.sent[0].notification, undefined);
  assert.equal(messaging.sent[0].android.notification, undefined);
  assert.equal(messaging.sent[0].android.ttl, 45000);
  assert.equal(messaging.sent[0].data.channelId, "golocal_sample_ride_requests_v2");

  await handlers.handleSampleRideWrite(
    "sr_1",
    { status: "incoming" },
    {
      status: "accepted",
      customerId: "cust1",
      assignedSampleRiderId: "sample_online",
      assignedSampleRiderName: "Sample Rider One",
      pickupLabel: "Borai Station",
      destinationLabel: "Chinsurah Court",
    }
  );
  assert.equal(messaging.sent.length, 2);
  assert.deepEqual(messaging.sent[1].tokens, ["tok_cust"]);
  assert.equal(messaging.sent[1].android.notification.channelId, "golocal_sample_rides");
});

test("Payment webhook verifies HMAC-SHA256 signature, updates payment & booking idempotently, and blocks test rides", async () => {
  const crypto = require("node:crypto");
  const secret = "test_wh_secret_123456";
  const db = createFakeFirestore({
    bookings: {
      bk_real_1: { customerId: "cust1", assignedDriverId: "drv1", paymentMethod: "upi", paymentStatus: "pending_verification", is_test_ride: false },
      bk_test_1: { customerId: "cust1", assignedDriverId: "test_driver_1", paymentMethod: "upi", paymentStatus: "pending", is_test_ride: true },
    },
    payments: {
      bk_real_1: { bookingId: "bk_real_1", customerId: "cust1", driverId: "drv1", status: "pending_verification", paymentMethod: "upi" },
    },
    user_tokens: {
      cust1_c_1: { userId: "cust1", appVariant: "customer", active: true, token: "tok_c1" },
    },
  });
  const messaging = createMockMessaging();
  const handlers = createNotificationHandlers({ db, messaging, fieldValue: fakeFieldValue });

  const bodyStr = JSON.stringify({ event: "payment.captured", bookingId: "bk_real_1", gatewayReference: "pay_9911" });
  const validSig = crypto.createHmac("sha256", secret).update(bodyStr).digest("hex");

  const noSecretRes = await handlers.handlePaymentWebhook({ rawBody: bodyStr, headers: { "x-golocal-signature": validSig }, secret: "" });
  assert.equal(noSecretRes.statusCode, 503);

  const badSigRes = await handlers.handlePaymentWebhook({ rawBody: bodyStr, headers: { "x-golocal-signature": "0".repeat(64) }, secret });
  assert.equal(badSigRes.statusCode, 401);

  const okRes = await handlers.handlePaymentWebhook({ rawBody: bodyStr, headers: { "x-golocal-signature": `sha256=${validSig}` }, secret });
  assert.equal(okRes.statusCode, 200);
  assert.equal(okRes.body.status, "paid");
  assert.equal(db.store.get("bookings").get("bk_real_1").paymentStatus, "paid");
  assert.equal(db.store.get("payments").get("bk_real_1").gatewayReference, "pay_9911");

  const dupRes = await handlers.handlePaymentWebhook({ rawBody: bodyStr, headers: { "x-golocal-signature": validSig }, secret });
  assert.equal(dupRes.statusCode, 200);
  assert.equal(dupRes.body.idempotent, true);

  const testBody = JSON.stringify({ event: "payment.captured", bookingId: "bk_test_1", gatewayReference: "pay_test" });
  const testSig = crypto.createHmac("sha256", secret).update(testBody).digest("hex");
  const testRes = await handlers.handlePaymentWebhook({ rawBody: testBody, headers: { "x-golocal-signature": testSig }, secret });
  assert.equal(testRes.statusCode, 403);
});

test("Admin custom-claim bootstrap only grants ownerAdmin to verified allowlisted ADMIN_EMAILS", async () => {
  const db = createFakeFirestore();
  const messaging = createMockMessaging();
  const handlers = createNotificationHandlers({ db, messaging, fieldValue: fakeFieldValue });

  const claimsStore = new Map([["owner_1", { existing: "yes" }], ["other_1", {}]]);
  const authApi = {
    async getUser(uid) {
      return { uid, customClaims: claimsStore.get(uid) || {} };
    },
    async setCustomUserClaims(uid, claims) {
      claimsStore.set(uid, claims);
    },
  };
  const rtdbWrites = [];
  const rtdbApi = {
    ref(path) {
      return {
        async set(val) {
          rtdbWrites.push({ path, val });
        },
      };
    },
  };

  const deniedUnverified = await handlers.syncOwnerAdminClaims({
    uid: "owner_1",
    email: "owner@nrnext.in",
    emailVerified: false,
    adminEmailsEnv: "owner@nrnext.in",
    authApi,
    rtdbApi,
  });
  assert.equal(deniedUnverified.granted, false);

  const deniedNonAllowlist = await handlers.syncOwnerAdminClaims({
    uid: "other_1",
    email: "attacker@example.com",
    emailVerified: true,
    adminEmailsEnv: "owner@nrnext.in",
    authApi,
    rtdbApi,
  });
  assert.equal(deniedNonAllowlist.granted, false);

  const granted = await handlers.syncOwnerAdminClaims({
    uid: "owner_1",
    email: "Owner@nrnext.in",
    emailVerified: true,
    adminEmailsEnv: "ops-backup@nrnext.in, owner@nrnext.in",
    authApi,
    rtdbApi,
  });
  assert.equal(granted.granted, true);
  assert.deepEqual(claimsStore.get("owner_1"), { existing: "yes", admin: true, ownerAdmin: true });
  assert.equal(db.store.get("admins").get("owner_1").role, "owner_admin");
  assert.equal(rtdbWrites[0].path, "auth_roles/owners/owner_1");

  await handlers.syncDelegatedAdminClaims({ uid: "other_1", afterExists: true, authApi });
  assert.equal(claimsStore.get("other_1").admin, true);
  await handlers.syncDelegatedAdminClaims({ uid: "other_1", afterExists: false, authApi });
  assert.equal(claimsStore.get("other_1").admin, undefined);
});

test("Commercial 2-wave radius dispatch (3km -> 6km), data-only 45s TTL push, and automatic expiry notification", async () => {
  const pickupLat = 21.8400;
  const pickupLng = 87.3800;
  const db = createFakeFirestore({
    driver_directory: {
      drv_near: {
        driverId: "drv_near",
        verificationStatus: "verified",
        isOnline: true,
        vehicleCategory: "toto",
        currentLat: 21.852, // ~1.3 km
        currentLng: 87.382,
      },
      drv_mid: {
        driverId: "drv_mid",
        verificationStatus: "verified",
        isOnline: true,
        vehicleCategory: "toto",
        currentLat: 21.880, // ~4.5 km
        currentLng: 87.380,
      },
      drv_far: {
        driverId: "drv_far",
        verificationStatus: "verified",
        isOnline: true,
        vehicleCategory: "toto",
        currentLat: 21.950, // ~12.2 km
        currentLng: 87.380,
      },
    },
    user_tokens: {
      cust1_c: { userId: "cust1", appVariant: "customer", active: true, token: "tok_cust" },
      drv_near_r: { userId: "drv_near", appVariant: "rider", active: true, token: "tok_near" },
      drv_mid_r: { userId: "drv_mid", appVariant: "rider", active: true, token: "tok_mid" },
      drv_far_r: { userId: "drv_far", appVariant: "rider", active: true, token: "tok_far" },
    },
  });
  const messaging = createMockMessaging();
  const handlers = createNotificationHandlers({ db, messaging, fieldValue: fakeFieldValue });

  const now = Date.now();
  const bookingDoc = {
    status: "searching",
    customerId: "cust1",
    customerName: "Ananya Roy",
    vehicleCategory: "toto",
    pickupLabel: "Borai More",
    destinationLabel: "Mohanpur Bus Stand",
    pickupLat,
    pickupLng,
    destinationLat: 21.855,
    destinationLng: 87.395,
    distanceKm: 3.1,
    estimatedDurationMin: 12,
    estimatedFareInr: 48,
    minPermittedFareInr: 40,
    maxPermittedFareInr: 60,
    paymentMethod: "cash",
    requestExpiresAtMs: now + 45_000,
    is_test_ride: false,
  };
  await db.collection("bookings").doc("bk_wave_1").set(bookingDoc);

  // Wave 1 on booking create
  await handlers.handleBookingWrite("bk_wave_1", undefined, bookingDoc);

  assert.equal(messaging.sent.length, 2);
  assert.equal(messaging.sent[0].notification.title, "Searching for Nearby Rider");
  assert.ok(!messaging.sent[0].notification.title.includes("Confirmed"));
  assert.deepEqual(messaging.sent[1].tokens, ["tok_near"]);
  assert.equal(messaging.sent[1].notification, undefined);
  assert.equal(messaging.sent[1].android.priority, "high");
  assert.equal(messaging.sent[1].android.ttl, 45_000);
  assert.equal(messaging.sent[1].data.dispatchWave, "1");
  assert.equal(messaging.sent[1].data.pickupLabel, "Borai More");

  // Wave 2 after 20s expands to 6km without duplicating Wave 1 or reaching >6km driver
  const w2 = await handlers.expandDispatchWaveIfNeeded("bk_wave_1", now + 21_000);
  assert.equal(w2.expanded, true);
  assert.equal(w2.notifiedCount, 1);
  assert.equal(messaging.sent.length, 3);
  assert.deepEqual(messaging.sent[2].tokens, ["tok_mid"]);
  assert.equal(messaging.sent[2].data.dispatchWave, "2");
  assert.ok(!messaging.sent.flatMap((m) => m.tokens).includes("tok_far"));

  // Expire after 45s
  const beforeExp = await db.collection("bookings").doc("bk_wave_1").get();
  const expRes = await handlers.expireSearchingBookingIfUnanswered("bk_wave_1", now + 46_000);
  assert.equal(expRes.expired, true);
  const afterExp = await db.collection("bookings").doc("bk_wave_1").get();
  assert.equal(afterExp.data().status, "expired");

  // Trigger lifecycle notification on searching -> expired
  await handlers.handleBookingWrite("bk_wave_1", beforeExp.data(), afterExp.data());
  const dismissalMsgs = messaging.sent.filter((m) => m.data.type === "ride_request_expired");
  assert.equal(dismissalMsgs.length, 2); // drv_near + drv_mid
  const customerExpired = messaging.sent.find((m) => m.data.type === "booking_expired");
  assert.ok(customerExpired);
  assert.equal(customerExpired.notification.title, "No Rider Found Nearby");
});

