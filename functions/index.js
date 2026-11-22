/**
 * GoLocal by NR Next — Production Firebase Cloud Functions v2 Triggers
 *
 * Targets the named Firestore database used by the Android app:
 *   ai-studio-android-golocal-e1a2a68b-450b-4d1b-817a-6a38c9c578bb
 *
 * Deploy with Firebase Blaze plan:
 *   firebase deploy --only functions
 */
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getDatabase } = require("firebase-admin/database");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { onDocumentWritten } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { onRequest, onCall, HttpsError } = require("firebase-functions/v2/https");
const { createNotificationHandlers, WAVE_2_DELAY_MS, RIDE_REQUEST_TIMEOUT_MS } = require("./handlers");

initializeApp();

const DATABASE_ID = "ai-studio-android-golocal-e1a2a68b-450b-4d1b-817a-6a38c9c578bb";
const db = getFirestore(DATABASE_ID);
const messaging = getMessaging();

const handlers = createNotificationHandlers({
  db,
  messaging,
  fieldValue: FieldValue,
});

function delayMs(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

exports.paymentWebhook = onRequest({ cors: false }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "Method not allowed" });
    return;
  }
  const rawBody = req.rawBody || JSON.stringify(req.body || {});
  const result = await handlers.handlePaymentWebhook({
    rawBody,
    headers: req.headers,
    secret: process.env.PAYMENT_WEBHOOK_SECRET || "",
  });
  res.status(result.statusCode).json(result.body);
});

exports.onBookingWriteNotify = onDocumentWritten(
  { document: "bookings/{bookingId}", database: DATABASE_ID, timeoutSeconds: 120 },
  async (event) => {
    const before = event.data?.before?.data();
    const after = event.data?.after?.data();
    const bookingId = event.params.bookingId;
    await handlers.handleBookingWrite(bookingId, before, after);

    if (!before && after?.status === "searching") {
      const isTestRide = after.is_test_ride === true || after.test_mode === true;
      if (!isTestRide) {
        await delayMs(WAVE_2_DELAY_MS);
        const w2 = await handlers.expandDispatchWaveIfNeeded(bookingId);
        if (w2.reason === "not_searching" || w2.reason === "missing") return;
      }
      const remainingMs = Math.max(
        1_000,
        Math.min(RIDE_REQUEST_TIMEOUT_MS, Number(after.requestExpiresAtMs || Date.now() + RIDE_REQUEST_TIMEOUT_MS) - Date.now())
      );
      await delayMs(isTestRide ? remainingMs : Math.max(1_000, remainingMs));
      await handlers.expireSearchingBookingIfUnanswered(bookingId);
    }
  }
);

exports.onRideOfferWriteNotify = onDocumentWritten(
  { document: "ride_offers/{offerId}", database: DATABASE_ID },
  async (event) => {
    await handlers.handleRideOfferWrite(
      event.params.offerId,
      event.data?.before?.data(),
      event.data?.after?.data()
    );
  }
);

exports.onBookingChatMessageNotify = onDocumentWritten(
  { document: "booking_chats/{bookingId}/messages/{messageId}", database: DATABASE_ID },
  async (event) => {
    if (event.data?.before?.exists) return;
    await handlers.handleBookingChatMessageCreate(
      event.params.bookingId,
      event.params.messageId,
      event.data?.after?.data()
    );
  }
);

exports.onDriverProfileWriteNotify = onDocumentWritten(
  { document: "driver_profiles/{driverId}", database: DATABASE_ID },
  async (event) => {
    await handlers.handleDriverProfileWrite(
      event.params.driverId,
      event.data?.before?.data(),
      event.data?.after?.data()
    );
  }
);

exports.onTestRiderRequestWriteNotify = onDocumentWritten(
  { document: "test_rider_requests/{uid}", database: DATABASE_ID },
  async (event) => {
    await handlers.handleTestRiderRequestWrite(
      event.params.uid,
      event.data?.before?.data(),
      event.data?.after?.data()
    );
  }
);

exports.onSampleRideWriteNotify = onDocumentWritten(
  { document: "sample_rides/{rideId}", database: DATABASE_ID },
  async (event) => {
    await handlers.handleSampleRideWrite(
      event.params.rideId,
      event.data?.before?.data(),
      event.data?.after?.data()
    );
  }
);

exports.onEmergencyEventNotifyAdmins = onDocumentWritten(
  { document: "emergency_events/{eventId}", database: DATABASE_ID },
  async (event) => {
    if (event.data?.before?.exists) return;
    await handlers.handleEmergencyEventCreate(event.params.eventId, event.data?.after?.data());
  }
);

exports.onDisputeOrEmergencyNotifyAdmins = onDocumentWritten(
  { document: "disputes/{disputeId}", database: DATABASE_ID },
  async (event) => {
    if (event.data?.before?.exists) return;
    await handlers.handleDisputeCreate(event.params.disputeId, event.data?.after?.data());
  }
);

exports.onComplaintWriteNotifyAdmins = onDocumentWritten(
  { document: "complaints/{complaintId}", database: DATABASE_ID },
  async (event) => {
    await handlers.handleComplaintWrite(
      event.params.complaintId,
      event.data?.before?.data(),
      event.data?.after?.data()
    );
  }
);

exports.onCancellationAppealWriteNotify = onDocumentWritten(
  { document: "cancellation_appeals/{appealId}", database: DATABASE_ID },
  async (event) => {
    await handlers.handleCancellationAppealWrite(
      event.params.appealId,
      event.data?.before?.data(),
      event.data?.after?.data()
    );
  }
);

exports.onBusBookingWriteNotify = onDocumentWritten(
  { document: "bus_bookings/{ticketId}", database: DATABASE_ID },
  async (event) => {
    await handlers.handleBusBookingWrite(
      event.params.ticketId,
      event.data?.before?.data(),
      event.data?.after?.data()
    );
  }
);

exports.dailyDocumentExpiryReminder = onSchedule("every 24 hours", async () => {
  await handlers.runDailyDocumentExpiryReminder();
});

exports.sweepExpiredBookings = onSchedule("every 1 minutes", async () => {
  await handlers.sweepExpiredSearchingBookings();
});

exports.syncOwnerAdminAccess = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Authentication required.");
  }
  const result = await handlers.syncOwnerAdminClaims({
    uid: request.auth.uid,
    email: request.auth.token.email,
    emailVerified: request.auth.token.email_verified === true,
    adminEmailsEnv: process.env.ADMIN_EMAILS || "",
    authApi: getAuth(),
    rtdbApi: getDatabase(),
  });
  return result;
});

exports.onAdminDocWriteSyncClaims = onDocumentWritten(
  { document: "admins/{adminUid}", database: DATABASE_ID },
  async (event) => {
    await handlers.syncDelegatedAdminClaims({
      uid: event.params.adminUid,
      afterExists: Boolean(event.data?.after?.exists),
      authApi: getAuth(),
    });
  }
);
