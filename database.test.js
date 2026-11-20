const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const DRIVER_UID = "driver_rahul";
const CUSTOMER_UID = "customer_alice";
const OTHER_UID = "outsider_bob";
const OWNER_UID = "owner_renuka";
const OPS_UID = "ops_member";
const [dbHost, dbPortStr] = (process.env.FIREBASE_DATABASE_EMULATOR_HOST || "127.0.0.1:9000").split(":");

before(async () => {
  const rules = fs.readFileSync("./database.rules.json", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    database: {
      rules,
      host: dbHost,
      port: parseInt(dbPortStr, 10),
    },
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  if (testEnv) await testEnv.clearDatabase();
});

test("RTDB live_locations/drivers: only owning driver can write own coords and invalid payloads are rejected", async () => {
  const driverDb = testEnv.authenticatedContext(DRIVER_UID).database();
  const otherDb = testEnv.authenticatedContext(OTHER_UID).database();
  const unauthDb = testEnv.unauthenticatedContext().database();

  await assertSucceeds(
    driverDb.ref(`live_locations/drivers/${DRIVER_UID}`).set({
      driverId: DRIVER_UID,
      lat: 21.816,
      lng: 87.3703,
      trackingStatus: "active",
      speedMps: 6.4,
      updatedAtEpochMs: Date.now(),
    })
  );

  // Reject out-of-range latitude or missing trackingStatus
  await assertFails(
    driverDb.ref(`live_locations/drivers/${DRIVER_UID}`).set({
      driverId: DRIVER_UID,
      lat: 121.816,
      lng: 87.3703,
      trackingStatus: "active",
      updatedAtEpochMs: Date.now(),
    })
  );

  // Another user cannot overwrite driver's coordinates or read driver's live feed without owner/ops role
  await assertFails(
    otherDb.ref(`live_locations/drivers/${DRIVER_UID}`).set({
      driverId: OTHER_UID,
      lat: 21.9,
      lng: 87.4,
      trackingStatus: "active",
      updatedAtEpochMs: Date.now(),
    })
  );
  await assertFails(unauthDb.ref(`live_locations/drivers/${DRIVER_UID}`).get());
  await assertFails(otherDb.ref(`live_locations/drivers/${DRIVER_UID}`).get());
  await assertSucceeds(driverDb.ref(`live_locations/drivers/${DRIVER_UID}`).get());
});

test("RTDB live_locations/trips: customer & assigned driver can read, only assigned driver can write, outsiders denied", async () => {
  const driverDb = testEnv.authenticatedContext(DRIVER_UID).database();
  const custDb = testEnv.authenticatedContext(CUSTOMER_UID).database();
  const otherDb = testEnv.authenticatedContext(OTHER_UID).database();

  await assertSucceeds(
    driverDb.ref("live_locations/trips/booking_rtdb_1").set({
      bookingId: "booking_rtdb_1",
      customerId: CUSTOMER_UID,
      driverId: DRIVER_UID,
      lat: 21.821,
      lng: 87.381,
      trackingStatus: "active",
      tripStatus: "in_progress",
      updatedAtEpochMs: Date.now(),
    })
  );

  await assertSucceeds(custDb.ref("live_locations/trips/booking_rtdb_1").get());
  await assertFails(otherDb.ref("live_locations/trips/booking_rtdb_1").get());
  await assertFails(
    custDb.ref("live_locations/trips/booking_rtdb_1").update({ lat: 22.0 })
  );

  // Assigned driver stops sharing after trip completion by deleting live node
  await assertSucceeds(driverDb.ref("live_locations/trips/booking_rtdb_1").remove());
});

test("RTDB auth_roles: only bootstrapped owner can provision owner/ops roles", async () => {
  const ownerDb = testEnv
    .authenticatedContext(OWNER_UID, { email: "renukadas083@gmail.com", email_verified: true })
    .database();
  const otherDb = testEnv.authenticatedContext(OTHER_UID, { email: "attacker@example.com", email_verified: true }).database();

  await assertFails(
    otherDb.ref(`auth_roles/owners/${OTHER_UID}`).set({ uid: OTHER_UID, updatedAt: Date.now() })
  );
  await assertFails(
    otherDb.ref(`auth_roles/ops/${OTHER_UID}`).set({ uid: OTHER_UID, updatedAt: Date.now() })
  );

  await assertSucceeds(
    ownerDb.ref(`auth_roles/owners/${OWNER_UID}`).set({ uid: OWNER_UID, updatedAt: Date.now() })
  );
  await assertSucceeds(
    ownerDb.ref(`auth_roles/ops/${OPS_UID}`).set({ uid: OPS_UID, updatedAt: Date.now() })
  );
  await assertSucceeds(
    ownerDb.ref("live_locations/drivers").get()
  );
});
