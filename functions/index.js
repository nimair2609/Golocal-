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
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { onDocumentWritten } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { createNotificationHandlers } = require("./handlers");

initializeApp();

const DATABASE_ID = "ai-studio-android-golocal-e1a2a68b-450b-4d1b-817a-6a38c9c578bb";
const db = getFirestore(DATABASE_ID);
const messaging = getMessaging();

const handlers = createNotificationHandlers({
  db,
  messaging,
  fieldValue: FieldValue,
});

exports.onBookingWriteNotify = onDocumentWritten(
  { document: "bookings/{bookingId}", database: DATABASE_ID },
  async (event) => {
    await handlers.handleBookingWrite(
      event.params.bookingId,
      event.data?.before?.data(),
      event.data?.after?.data()
    );
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
