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

test("RTDB live_drivers: only owning driver can write own coords and PII fields are rejected", async () => {
  const driverDb = testEnv.authenticatedContext(DRIVER_UID).database();
  const otherDb = testEnv.authenticatedContext(OTHER_UID).database();
  const unauthDb = testEnv.unauthenticatedContext().database();

  await assertSucceeds(
    driverDb.ref(`live_drivers/${DRIVER_UID}`).set({
      driverId: DRIVER_UID,
      lat: 21.816,
      lng: 87.3703,
      vehicleCategory: "toto",
      isOnline: true,
      gpsStatus: "ACTIVE",
      updatedAtEpochMs: Date.now(),
    })
  );

  // Reject PII phone field in live RTDB telemetry
  await assertFails(
    driverDb.ref(`live_drivers/${DRIVER_UID}`).set({
      driverId: DRIVER_UID,
      lat: 21.816,
      lng: 87.3703,
      phone: "+919800000002",
      isOnline: true,
      updatedAtEpochMs: Date.now(),
    })
  );

  // Another user cannot overwrite driver's coordinates
  await assertFails(
    otherDb.ref(`live_drivers/${DRIVER_UID}`).set({
      driverId: OTHER_UID,
      lat: 21.9,
      lng: 87.4,
      isOnline: true,
      updatedAtEpochMs: Date.now(),
    })
  );

  // Unauthenticated reads fail; authenticated reads succeed
  await assertFails(unauthDb.ref(`live_drivers/${DRIVER_UID}`).get());
  await assertSucceeds(otherDb.ref(`live_drivers/${DRIVER_UID}`).get());
});

test("RTDB active_trips: customer & assigned driver can read, only assigned driver can write, outsiders denied", async () => {
  const driverDb = testEnv.authenticatedContext(DRIVER_UID).database();
  const custDb = testEnv.authenticatedContext(CUSTOMER_UID).database();
  const otherDb = testEnv.authenticatedContext(OTHER_UID).database();

  await assertSucceeds(
    driverDb.ref("active_trips/booking_rtdb_1").set({
      bookingId: "booking_rtdb_1",
      customerId: CUSTOMER_UID,
      driverId: DRIVER_UID,
      lat: 21.821,
      lng: 87.381,
      trackingStatus: "IN_PROGRESS",
      updatedAtEpochMs: Date.now(),
    })
  );

  await assertSucceeds(custDb.ref("active_trips/booking_rtdb_1").get());
  await assertFails(otherDb.ref("active_trips/booking_rtdb_1").get());
  await assertFails(
    custDb.ref("active_trips/booking_rtdb_1").update({ lat: 22.0 })
  );

  // Assigned driver stops sharing after trip completion by deleting live node
  await assertSucceeds(driverDb.ref("active_trips/booking_rtdb_1").remove());
});
