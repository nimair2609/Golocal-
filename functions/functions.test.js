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
      async set(data) {
        getColMap(colName).set(id, { ...data });
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
  assert.equal(messaging.sent[1].android.notification.channelId, "golocal_test_ride_requests_v2");
  assert.equal(messaging.sent[1].android.notification.sound, "golocal_ride_request");
  assert.equal(messaging.sent[1].data.isTestRide, "true");
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
