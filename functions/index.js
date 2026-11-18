/**
 * GoLocal by NR Next — Production Firebase Cloud Functions Reference
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

initializeApp();

const DATABASE_ID = "ai-studio-android-golocal-e1a2a68b-450b-4d1b-817a-6a38c9c578bb";
const db = getFirestore(DATABASE_ID);
const messaging = getMessaging();

const INVALID_TOKEN_CODES = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

async function sendToUserIds(userIds, payload) {
  const uniqueIds = [...new Set(userIds.filter(Boolean))];
  if (uniqueIds.length === 0) return;

  for (const uid of uniqueIds) {
    const userTokensSnap = await db.collection("user_tokens").where("userId", "==", uid).where("active", "==", true).get();
    const legacyTokensSnap = userTokensSnap.empty
      ? await db.collection("fcm_tokens").where("userId", "==", uid).where("active", "==", true).get()
      : null;
    const snap = !userTokensSnap.empty ? userTokensSnap : legacyTokensSnap;
    if (!snap || snap.empty) continue;

    const docs = snap.docs;
    const tokens = docs.map((d) => d.data().token).filter(Boolean);
    if (tokens.length === 0) continue;

    const response = await messaging.sendEachForMulticast({
      tokens,
      notification: {
        title: payload.title,
        body: payload.body,
      },
      data: {
        eventId: payload.eventId,
        type: payload.type,
        targetRole: payload.targetRole || "customer",
        targetScreen: payload.targetScreen || "notifications",
        entityId: payload.entityId || "",
      },
      android: {
        priority: "high",
        notification: {
          channelId: payload.channelId || "golocal_bookings",
        },
      },
    });

    const deletions = [];
    response.responses.forEach((res, idx) => {
      if (!res.success && res.error && INVALID_TOKEN_CODES.has(res.error.code)) {
        deletions.push(docs[idx].ref.delete());
      }
    });
    if (deletions.length > 0) {
      await Promise.all(deletions);
    }
  }
}

async function getAdminUserIds() {
  const snap = await db.collection("admins").get();
  return snap.docs.map((d) => d.data().uid || d.id).filter(Boolean);
}

exports.onBookingWriteNotify = onDocumentWritten(
  { document: "bookings/{bookingId}", database: DATABASE_ID },
  async (event) => {
    const after = event.data?.after?.data();
    const before = event.data?.before?.data();
    if (!after) return;

    const bookingId = event.params.bookingId;
    const prevStatus = before?.status || "";
    const newStatus = after.status || "";
    const isTestRide = after.is_test_ride === true || after.test_mode === true;
    const prefix = isTestRide ? "[TEST RIDE] " : "";

    if (!before && newStatus === "searching") {
      await sendToUserIds([after.customerId], {
        eventId: `booking_created_${bookingId}`,
        type: isTestRide ? "test_booking_confirmation" : "booking_confirmation",
        targetRole: "customer",
        targetScreen: "bookings",
        entityId: bookingId,
        title: isTestRide ? "TEST RIDE Created — Awaiting Ops Dispatch" : "Booking Confirmed — Searching Nearby",
        body: `${prefix}${(after.vehicleCategory || "ride").toUpperCase()} request from ${after.pickupLabel} to ${after.destinationLabel}.`,
      });
      // Test rides NEVER broadcast publicly to production online drivers
      if (isTestRide) {
        return;
      }
      const onlineDrivers = await db
        .collection("driver_directory")
        .where("verificationStatus", "==", "verified")
        .where("isOnline", "==", true)
        .where("vehicleCategory", "==", after.vehicleCategory)
        .limit(35)
        .get();
      await sendToUserIds(
        onlineDrivers.docs.map((d) => d.data().driverId || d.id),
        {
          eventId: `driver_new_req_${bookingId}`,
          type: "new_ride_request",
          targetRole: "driver",
          targetScreen: "requests",
          entityId: bookingId,
          channelId: "golocal_driver_dispatch",
          title: "New Nearby Ride Request",
          body: `${after.pickupLabel} -> ${after.destinationLabel} (Est. ₹${Math.round(after.estimatedFareInr || 0)})`,
        }
      );
      return;
    }

    if (prevStatus === newStatus) return;

    const customerMessages = isTestRide
      ? {
          assigned: [`${prefix}Test Rider Dispatched`, `${after.assignedDriverName || "Test Partner"} assigned. Wait at pickup for OTP verification.`],
          arriving: [`${prefix}Rider Approaching Pickup`, `${after.assignedDriverName || "Test Partner"} is on the way.`],
          arrived: [`${prefix}Rider Arrived at Pickup`, "Verify your 6-digit Test Ride OTP in the app to start."],
          in_progress: [`${prefix}Test Ride Started`, `Trip to ${after.destinationLabel} is underway.`],
          completed: [`${prefix}Test Ride Completed`, "Simulation completed with ₹0 commercial impact."],
          cancelled: [`${prefix}Test Ride Cancelled`, `Booking cancelled (${after.cancellationReason || "no reason provided"}).`],
        }
      : {
          assigned: ["Rider Assigned", `${after.assignedDriverName || "Your driver"} confirmed. Trip PIN: ${after.tripPin}.`],
          arriving: ["Rider Approaching Pickup", `${after.assignedDriverName || "Driver"} is on the way.`],
          arrived: ["Rider Arrived at Pickup", `Share Trip PIN ${after.tripPin} to start your ride.`],
          in_progress: ["Ride Started", `Trip to ${after.destinationLabel} is underway.`],
          completed: ["Ride Completed", `Final fare ₹${Math.round(after.agreedFareInr || after.estimatedFareInr || 0)}. Rate your ride.`],
          cancelled: ["Ride Cancelled", `Booking cancelled (${after.cancellationReason || "no reason provided"}).`],
        };

    const msg = customerMessages[newStatus];
    if (msg) {
      await sendToUserIds([after.customerId], {
        eventId: `booking_${newStatus}_${bookingId}`,
        type: `booking_${newStatus}`,
        targetRole: "customer",
        targetScreen: "bookings",
        entityId: bookingId,
        title: msg[0],
        body: msg[1],
      });
    }

    if (after.assignedDriverId && !String(after.assignedDriverId).startsWith("test_driver_")) {
      await sendToUserIds([after.assignedDriverId], {
        eventId: `driver_booking_${newStatus}_${bookingId}`,
        type: isTestRide ? `test_driver_booking_${newStatus}` : `driver_booking_${newStatus}`,
        targetRole: "driver",
        targetScreen: "home",
        entityId: bookingId,
        channelId: "golocal_driver_dispatch",
        title: newStatus === "cancelled" ? `${prefix}Trip Cancelled` : `${prefix}Trip Status Updated`,
        body: `${prefix}Booking #${bookingId.slice(0, 6)} is now ${newStatus.replace("_", " ")}.`,
      });
    }
  }
);

exports.onDriverProfileWriteNotify = onDocumentWritten(
  { document: "driver_profiles/{driverId}", database: DATABASE_ID },
  async (event) => {
    const before = event.data?.before?.data();
    const after = event.data?.after?.data();
    if (!after) return;

    const driverId = event.params.driverId;
    if (!before || (before.verificationStatus !== "pending" && after.verificationStatus === "pending")) {
      const admins = await getAdminUserIds();
      await sendToUserIds(admins, {
        eventId: `admin_kyc_${driverId}_${after.verificationStatus}`,
        type: "pending_verification",
        targetRole: "admin",
        targetScreen: "riders",
        entityId: driverId,
        channelId: "golocal_safety_alerts",
        title: "Rider KYC Verification Pending",
        body: `${after.legalName} (${after.vehicleCategory.toUpperCase()}) submitted documents for review.`,
      });
    } else if (before.verificationStatus !== after.verificationStatus) {
      await sendToUserIds([driverId], {
        eventId: `rider_kyc_${driverId}_${after.verificationStatus}`,
        type: "kyc_status_update",
        targetRole: "driver",
        targetScreen: "profile",
        entityId: driverId,
        channelId: "golocal_driver_dispatch",
        title: `KYC Status: ${after.verificationStatus.toUpperCase()}`,
        body:
          after.verificationStatus === "verified"
            ? "Documents verified! You can now go online and accept rides."
            : after.rejectionReason || "Please review admin feedback and resubmit.",
      });
    }
  }
);

exports.onDisputeOrEmergencyNotifyAdmins = onDocumentWritten(
  { document: "disputes/{disputeId}", database: DATABASE_ID },
  async (event) => {
    if (event.data?.before?.exists) return;
    const after = event.data?.after?.data();
    if (!after) return;
    const admins = await getAdminUserIds();
    await sendToUserIds(admins, {
      eventId: `admin_dispute_${event.params.disputeId}`,
      type: "dispute_opened",
      targetRole: "admin",
      targetScreen: "bookings",
      entityId: event.params.disputeId,
      channelId: "golocal_safety_alerts",
      title: "New Trip/Fare Dispute Opened",
      body: `${after.category}: ${after.description}`,
    });
  }
);

exports.onBusBookingWriteNotify = onDocumentWritten(
  { document: "bus_bookings/{ticketId}", database: DATABASE_ID },
  async (event) => {
    const before = event.data?.before?.data();
    const after = event.data?.after?.data();
    if (!after) return;
    const ticketId = event.params.ticketId;
    if (!before || before.status !== after.status) {
      await sendToUserIds([after.customerId], {
        eventId: `bus_${after.status}_${ticketId}`,
        type: `bus_${after.status}`,
        targetRole: "customer",
        targetScreen: "bus",
        entityId: ticketId,
        title: `Bus Ticket ${after.status.toUpperCase()}`,
        body: `${after.routeLabel} • Seats: ${(after.seatIds || []).join(", ")}`,
      });
    }
  }
);

exports.dailyDocumentExpiryReminder = onSchedule("every 24 hours", async () => {
  const driversSnap = await db.collection("driver_profiles").where("verificationStatus", "==", "verified").get();
  const soonCutoffMs = Date.now() + 30 * 24 * 60 * 60 * 1000;
  for (const doc of driversSnap.docs) {
    const data = doc.data();
    const docs = Array.isArray(data.documents) ? data.documents : [];
    const expiring = docs.filter((item) => item.expiryDateIso && Date.parse(item.expiryDateIso) <= soonCutoffMs);
    if (expiring.length > 0) {
      await sendToUserIds([data.driverId || doc.id], {
        eventId: `doc_expiry_${doc.id}_${new Date().toISOString().slice(0, 10)}`,
        type: "document_expiry_reminder",
        targetRole: "driver",
        targetScreen: "profile",
        entityId: doc.id,
        channelId: "golocal_driver_dispatch",
        title: "Vehicle Document Expiring Soon",
        body: `${expiring.map((e) => e.docType).join(", ")} expires soon. Renew to stay online.`,
      });
    }
  }
});
