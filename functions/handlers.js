/**
 * GoLocal by NR Next — Modular Cloud Functions Notification Handlers
 *
 * Pure + dependency-injected notification engine supporting:
 * - Multi-device FCM routing with variant matching & stale/invalid token cleanup
 * - Server-side idempotent deduplication (`notification_dedupe`)
 * - In-app Notification Centre synchronization (`notifications`)
 * - Strict Test Rider vs Commercial separation (`golocal_test_rides` channel & recipients)
 * - Strict PII/OTP/PIN redaction in push & notification payloads
 */

const crypto = require("node:crypto");

const INVALID_TOKEN_CODES = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

function verifyHmacSha256Signature(rawBody, signatureHeader, secret) {
  if (!secret || typeof secret !== "string" || !secret.trim()) return false;
  if (!signatureHeader || typeof signatureHeader !== "string") return false;
  const cleanSig = signatureHeader.trim().replace(/^sha256=/i, "").toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(cleanSig)) return false;
  const expected = crypto
    .createHmac("sha256", secret.trim())
    .update(Buffer.isBuffer(rawBody) ? rawBody : String(rawBody || ""))
    .digest("hex")
    .toLowerCase();
  return crypto.timingSafeEqual(Buffer.from(cleanSig, "hex"), Buffer.from(expected, "hex"));
}

function normalizeAppVariant(roleOrApp) {
  const clean = String(roleOrApp || "customer").trim().toLowerCase();
  if (clean === "rider" || clean === "driver") return "rider";
  if (clean === "admin" || clean === "ops" || clean === "ops_staff") return "admin";
  return "customer";
}

function clickActionForApp(targetApp) {
  switch (normalizeAppVariant(targetApp)) {
    case "rider":
      return "com.aistudio.golocal.OPEN_RIDER";
    case "admin":
      return "com.aistudio.golocal.OPEN_ADMIN";
    default:
      return "com.aistudio.golocal.OPEN_CUSTOMER";
  }
}

function channelForPayload(payload) {
  const type = String(payload?.type || "").trim().toLowerCase();
  const isSample = payload?.isSampleRide === true || type.startsWith("sample_");
  const isTest = payload?.isTestRide === true || type.startsWith("test_");
  if (payload?.channelId) return String(payload.channelId);
  if (type === "sample_request") return "golocal_sample_ride_requests_v2";
  if (isSample) return "golocal_sample_rides";
  if (type === "test_request") return "golocal_test_ride_requests_v2";
  if (isTest) return "golocal_test_rides";
  if (type === "new_ride_request") return "golocal_ride_requests_v2";
  if (type === "chat" || type === "booking_chat") return "golocal_chat";
  if (type === "sos" || type === "emergency" || type === "sos_alert" || type === "dispute_opened") {
    return "golocal_sos";
  }
  if (
    type.startsWith("booking") ||
    type.startsWith("driver_booking") ||
    type === "ride_offer" ||
    type === "driver_assigned" ||
    type === "trip_status" ||
    type === "kyc_status_update" ||
    type === "document_expiry_reminder" ||
    type.startsWith("bus_")
  ) {
    return "golocal_rides";
  }
  return "golocal_general";
}

function sanitizeNotificationText(raw, maxLen = 280) {
  if (!raw) return "";
  const withoutSecrets = String(raw).replace(
    /\b(otp|pin|trip\s*pin|passcode|password)\b\s*[:=\-#]?\s*[0-9]{4,8}\b/gi,
    (_, kw) => `${String(kw || "CODE").toUpperCase()} protected (view in app)`
  );
  const withoutPhones = withoutSecrets.replace(
    /(^|[^\w])(?:\+?91[\s-]?)?[6-9][0-9]{9}(?!\w)/g,
    "$1[protected-contact]"
  );
  return withoutPhones.trim().slice(0, maxLen);
}

function makeSafeDocId(raw) {
  return String(raw || `evt_${Date.now()}`)
    .replace(/[^a-zA-Z0-9_-]/g, "_")
    .slice(0, 110);
}

function createNotificationHandlers({ db, messaging, fieldValue }) {
  async function claimDedupeKey(dedupeKey) {
    const safeKey = makeSafeDocId(dedupeKey);
    const ref = db.collection("notification_dedupe").doc(safeKey);
    try {
      await ref.create({
        key: String(dedupeKey),
        createdAt: fieldValue.serverTimestamp(),
      });
      return { acquired: true, safeKey };
    } catch (err) {
      const code = err?.code;
      const msg = String(err?.message || "");
      if (code === 6 || code === "already-exists" || /already exists/i.test(msg)) {
        return { acquired: false, safeKey };
      }
      throw err;
    }
  }

  async function getAdminUserIds() {
    const snap = await db.collection("admins").get();
    return snap.docs.map((d) => d.data().uid || d.id).filter(Boolean);
  }

  async function getOpsStaffUserIds() {
    const snap = await db.collection("operations_staff").where("isActive", "==", true).get();
    return snap.docs.map((d) => d.data().userId || d.id).filter(Boolean);
  }

  async function getAdminAndOpsUserIds() {
    const [admins, ops] = await Promise.all([getAdminUserIds(), getOpsStaffUserIds()]);
    return [...new Set([...admins, ...ops].filter(Boolean))];
  }

  async function syncInAppNotification(uid, safeDedupeKey, payload, safeTitle, safeBody) {
    if (payload.skipInAppSync === true) return;
    const notifId = makeSafeDocId(`${safeDedupeKey}_${uid}`);
    const targetApp = normalizeAppVariant(payload.targetApp || payload.targetRole || "customer");
    await db
      .collection("notifications")
      .doc(notifId)
      .set({
        userId: uid,
        title: safeTitle.slice(0, 120),
        body: safeBody.slice(0, 300),
        type: String(payload.type || "general").slice(0, 50),
        bookingId: String(payload.bookingId || ""),
        entityId: String(payload.entityId || payload.bookingId || ""),
        targetApp,
        targetScreen: String(payload.targetScreen || "notifications").slice(0, 64),
        isTestRide: Boolean(payload.isTestRide),
        isSampleRide: Boolean(payload.isSampleRide),
        read: false,
        createdAt: fieldValue.serverTimestamp(),
      });
  }

  async function sendToUserIds(userIds, payload) {
    const uniqueIds = [...new Set((userIds || []).filter(Boolean))];
    if (uniqueIds.length === 0) {
      return { sentRecipients: 0, skippedDuplicate: false, deletedTokens: 0 };
    }

    const dedupeKey = payload.dedupeKey || payload.eventId;
    const claim = dedupeKey
      ? await claimDedupeKey(dedupeKey)
      : { acquired: true, safeKey: makeSafeDocId(`evt_${Date.now()}`) };
    if (!claim.acquired) {
      return { sentRecipients: 0, skippedDuplicate: true, deletedTokens: 0 };
    }

    const safeTitle = sanitizeNotificationText(payload.title || "GoLocal", 120) || "GoLocal";
    const safeBody = sanitizeNotificationText(payload.body || "", 300);
    const targetApp = normalizeAppVariant(payload.targetApp || payload.targetRole || "customer");
    const channelId = channelForPayload({ ...payload, targetApp });
    const clickAction = clickActionForApp(targetApp);

    let sentRecipients = 0;
    let deletedTokens = 0;

    for (const uid of uniqueIds) {
      await syncInAppNotification(uid, claim.safeKey, payload, safeTitle, safeBody);

      const userTokensSnap = await db
        .collection("user_tokens")
        .where("userId", "==", uid)
        .where("active", "==", true)
        .get();
      const legacyTokensSnap = userTokensSnap.empty
        ? await db.collection("fcm_tokens").where("userId", "==", uid).where("active", "==", true).get()
        : null;
      const snap = !userTokensSnap.empty ? userTokensSnap : legacyTokensSnap;
      if (!snap || snap.empty) continue;

      const allDocs = snap.docs.filter((d) => Boolean(d.data()?.token));
      if (allDocs.length === 0) continue;

      // Prefer tokens registered for the matching app variant; fall back if user has only one role token
      const variantDocs = allDocs.filter(
        (d) => normalizeAppVariant(d.data()?.appVariant || targetApp) === targetApp
      );
      const selectedDocs = variantDocs.length > 0 ? variantDocs : allDocs;

      // Deduplicate token strings so one physical device isn't notified twice
      const tokenToDocs = new Map();
      for (const doc of selectedDocs) {
        const tok = String(doc.data().token).trim();
        if (!tok) continue;
        if (!tokenToDocs.has(tok)) tokenToDocs.set(tok, []);
        tokenToDocs.get(tok).push(doc);
      }
      const tokens = [...tokenToDocs.keys()];
      if (tokens.length === 0) continue;

      const isRideRequestChannel =
        channelId === "golocal_ride_requests_v2" ||
        channelId === "golocal_test_ride_requests_v2" ||
        channelId === "golocal_sample_ride_requests_v2";
      const androidSound = isRideRequestChannel ? "golocal_ride_request" : "default";

      const response = await messaging.sendEachForMulticast({
        tokens,
        notification: {
          title: safeTitle,
          body: safeBody,
        },
        data: {
          eventId: String(payload.eventId || claim.safeKey),
          dedupeKey: String(dedupeKey || claim.safeKey),
          type: String(payload.type || "general"),
          title: safeTitle,
          body: safeBody,
          targetApp,
          targetRole: String(payload.targetRole || targetApp),
          targetScreen: String(payload.targetScreen || "notifications"),
          bookingId: String(payload.bookingId || ""),
          entityId: String(payload.entityId || payload.bookingId || ""),
          isTestRide: String(Boolean(payload.isTestRide)),
          isSampleRide: String(Boolean(payload.isSampleRide)),
          requestExpiresAtMs: payload.requestExpiresAtMs ? String(payload.requestExpiresAtMs) : "",
          channelId,
          timestampMs: String(Date.now()),
        },
        android: {
          priority: "high",
          notification: {
            channelId,
            sound: androidSound,
            clickAction,
            tag: String(payload.tag || payload.bookingId || payload.entityId || payload.type || "golocal"),
          },
        },
      });
      sentRecipients += 1;

      const deletions = [];
      (response.responses || []).forEach((res, idx) => {
        if (!res.success && res.error && INVALID_TOKEN_CODES.has(res.error.code)) {
          const badToken = tokens[idx];
          const matchingDocs = tokenToDocs.get(badToken) || [];
          for (const d of matchingDocs) {
            deletions.push(d.ref.delete());
            deletedTokens += 1;
          }
        }
      });
      if (deletions.length > 0) {
        await Promise.all(deletions);
      }
    }

    return { sentRecipients, skippedDuplicate: false, deletedTokens };
  }

  async function handleBookingWrite(bookingId, before, after) {
    if (!after) return;
    const prevStatus = before?.status || "";
    const newStatus = after.status || "";
    const isTestRide = after.is_test_ride === true || after.test_mode === true;
    const prefix = isTestRide ? "[TEST RIDE] " : "";

    if (!before && newStatus === "searching") {
      await sendToUserIds([after.customerId], {
        eventId: `booking_created_${bookingId}`,
        dedupeKey: `booking_created_${bookingId}`,
        type: isTestRide ? "test_booking_confirmation" : "booking_confirmation",
        targetApp: "customer",
        targetRole: "customer",
        targetScreen: isTestRide ? "booking" : "bookings",
        bookingId,
        entityId: bookingId,
        isTestRide,
        channelId: isTestRide ? "golocal_test_rides" : "golocal_rides",
        title: isTestRide ? "TEST RIDE Created — Awaiting Dispatch" : "Booking Confirmed — Finding Rider",
        body: `${prefix}${(after.vehicleCategory || "ride").toUpperCase()} from ${after.pickupLabel} to ${after.destinationLabel}.`,
      });

      if (isTestRide) {
        // Test rides NEVER notify commercial production riders
        const testRiders = await db
          .collection("test_riders")
          .where("status", "==", "approved")
          .where("isOnline", "==", true)
          .limit(35)
          .get();
        await sendToUserIds(
          testRiders.docs.map((d) => d.data().uid || d.id),
          {
            eventId: `test_rider_new_req_${bookingId}`,
            dedupeKey: `test_rider_new_req_${bookingId}`,
            type: "test_request",
            targetApp: "rider",
            targetRole: "driver",
            targetScreen: "rides",
            bookingId,
            entityId: bookingId,
            isTestRide: true,
            requestExpiresAtMs: Number(after.requestExpiresAtMs || Date.now() + 300000),
            channelId: "golocal_test_ride_requests_v2",
            title: "[TEST RIDE] New Sandbox Request",
            body: `${after.pickupLabel} -> ${after.destinationLabel} (₹0 Test Simulation)`,
          }
        );
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
          dedupeKey: `driver_new_req_${bookingId}`,
          type: "new_ride_request",
          targetApp: "rider",
          targetRole: "driver",
          targetScreen: "requests",
          bookingId,
          entityId: bookingId,
          isTestRide: false,
          requestExpiresAtMs: Number(after.requestExpiresAtMs || Date.now() + 45000),
          channelId: "golocal_ride_requests_v2",
          title: "New Nearby Ride Request",
          body: `${after.pickupLabel} -> ${after.destinationLabel} (Est. ₹${Math.round(after.estimatedFareInr || 0)})`,
        }
      );
      return;
    }

    if (prevStatus === newStatus) return;

    const customerMessages = isTestRide
      ? {
          assigned: [
            `${prefix}Test Rider Assigned`,
            `${after.assignedDriverName || "Test Partner"} assigned. Open Active Ride to view your 6-digit Test OTP.`,
          ],
          arriving: [
            `${prefix}Rider Approaching Pickup`,
            `${after.assignedDriverName || "Test Partner"} is on the way to pickup.`,
          ],
          arrived: [
            `${prefix}Rider Arrived at Pickup`,
            "Your Test Rider arrived. Open Active Ride to share or verify your Test Ride OTP.",
          ],
          in_progress: [`${prefix}Test Ride Started`, `Trip to ${after.destinationLabel} is underway.`],
          completed: [`${prefix}Test Ride Completed`, "Sandbox ride completed with ₹0 commercial impact."],
          cancelled: [
            `${prefix}Test Ride Cancelled`,
            `Booking cancelled (${after.cancellationReason || "cancelled"}).`,
          ],
        }
      : {
          assigned: [
            "Rider Assigned",
            `${after.assignedDriverName || "Your rider"} confirmed. Open Active Ride for your 6-digit Pickup OTP.`,
          ],
          arriving: [
            "Rider Approaching Pickup",
            `${after.assignedDriverName || "Your rider"} is heading to ${after.pickupLabel}.`,
          ],
          arrived: [
            "Rider Arrived at Pickup",
            `${after.assignedDriverName || "Your rider"} has arrived. Open Active Ride to view your 6-digit Pickup OTP.`,
          ],
          in_progress: ["Ride Started", `Your trip to ${after.destinationLabel} is underway.`],
          completed: [
            "Ride Completed",
            `Final fare ₹${Math.round(after.agreedFareInr || after.estimatedFareInr || 0)}. View receipt & rate your ride.`,
          ],
          cancelled: [
            "Ride Cancelled",
            `Booking cancelled (${after.cancellationReason || "cancelled"}).`,
          ],
        };

    const msg = customerMessages[newStatus];
    if (msg) {
      await sendToUserIds([after.customerId], {
        eventId: `booking_${newStatus}_${bookingId}`,
        dedupeKey: `booking_${newStatus}_${bookingId}`,
        type: `booking_${newStatus}`,
        targetApp: "customer",
        targetRole: "customer",
        targetScreen: newStatus === "completed" || newStatus === "cancelled" ? "bookings" : "booking",
        bookingId,
        entityId: bookingId,
        isTestRide,
        channelId: isTestRide ? "golocal_test_rides" : "golocal_rides",
        title: msg[0],
        body: msg[1],
      });
    }

    if (after.assignedDriverId && !String(after.assignedDriverId).startsWith("test_driver_")) {
      const driverTitle =
        newStatus === "cancelled"
          ? `${prefix}Booking Cancelled`
          : newStatus === "assigned"
            ? `${prefix}Booking Assigned to You`
            : `${prefix}Trip Update: ${newStatus.replace("_", " ").toUpperCase()}`;
      await sendToUserIds([after.assignedDriverId], {
        eventId: `driver_booking_${newStatus}_${bookingId}`,
        dedupeKey: `driver_booking_${newStatus}_${bookingId}`,
        type: isTestRide ? `test_driver_booking_${newStatus}` : `driver_booking_${newStatus}`,
        targetApp: "rider",
        targetRole: "driver",
        targetScreen: isTestRide ? "test_rider_dashboard" : "home",
        bookingId,
        entityId: bookingId,
        isTestRide,
        channelId: isTestRide ? "golocal_test_rides" : "golocal_rides",
        title: driverTitle,
        body: `${prefix}Trip #${bookingId.slice(0, 6)}: ${after.pickupLabel} -> ${after.destinationLabel}.`,
      });
    }

    // Post-assignment cancellation alerts go to Admin & Operations Team as booking exception
    const activeStatuses = new Set(["assigned", "arriving", "arrived", "in_progress"]);
    if (activeStatuses.has(prevStatus) && newStatus === "cancelled" && !isTestRide) {
      const adminOps = await getAdminAndOpsUserIds();
      await sendToUserIds(adminOps, {
        eventId: `booking_exception_cancel_${bookingId}`,
        dedupeKey: `booking_exception_cancel_${bookingId}`,
        type: "booking_exception",
        targetApp: "admin",
        targetRole: "admin",
        targetScreen: "bookings",
        bookingId,
        entityId: bookingId,
        isTestRide: false,
        channelId: "golocal_general",
        title: "Booking Exception: Post-Assignment Cancel",
        body: `Booking #${bookingId.slice(0, 6)} cancelled from ${prevStatus}. Reason: ${after.cancellationReason || "unspecified"}.`,
      });
    }
  }

  async function handleRideOfferWrite(offerId, before, after) {
    if (!after) return;
    const prevStatus = before?.status || "";
    const newStatus = after.status || "pending";
    const isTestRide = after.is_test_ride === true || after.test_mode === true;
    const channelId = isTestRide ? "golocal_test_rides" : "golocal_rides";

    if (!before && newStatus === "pending") {
      await sendToUserIds([after.customerId], {
        eventId: `offer_created_${offerId}`,
        dedupeKey: `offer_created_${offerId}`,
        type: isTestRide ? "test_ride" : "ride_offer",
        targetApp: "customer",
        targetRole: "customer",
        targetScreen: "booking",
        bookingId: after.bookingId || "",
        entityId: offerId,
        isTestRide,
        channelId,
        title: "New Rider Offer Received",
        body: `${after.driverName || "Verified Rider"} offered ₹${Math.round(after.proposedFareInr || 0)} (${after.etaMinutes || 5} min ETA).`,
      });
      return;
    }

    if (prevStatus !== newStatus && (newStatus === "accepted" || newStatus === "rejected")) {
      await sendToUserIds([after.driverId], {
        eventId: `offer_${newStatus}_${offerId}`,
        dedupeKey: `offer_${newStatus}_${offerId}`,
        type: newStatus === "accepted" ? "driver_booking_assigned" : "ride_offer",
        targetApp: "rider",
        targetRole: "driver",
        targetScreen: newStatus === "accepted" ? "home" : "requests",
        bookingId: after.bookingId || "",
        entityId: offerId,
        isTestRide,
        channelId,
        title: newStatus === "accepted" ? "Offer Accepted — Proceed to Pickup" : "Offer Not Selected",
        body:
          newStatus === "accepted"
            ? `Customer accepted ₹${Math.round(after.proposedFareInr || 0)}. Navigate to pickup now.`
            : "Another rider was selected for this request.",
      });
    }
  }

  async function handleBookingChatMessageCreate(bookingId, messageId, msg) {
    if (!msg) return;
    const recipientId = msg.senderId === msg.customerId ? msg.driverId : msg.customerId;
    if (!recipientId || String(recipientId).startsWith("test_driver_")) return;

    const targetApp = msg.senderRole === "customer" ? "rider" : "customer";
    let isTestRide = msg.isTestRide === true || msg.is_test_ride === true;
    if (!isTestRide && bookingId) {
      const bookingSnap = await db.collection("bookings").doc(bookingId).get();
      const b = bookingSnap.data();
      isTestRide = b?.is_test_ride === true || b?.test_mode === true;
    }

    await sendToUserIds([recipientId], {
      eventId: `chat_${bookingId}_${messageId}`,
      dedupeKey: `chat_${bookingId}_${messageId}`,
      type: "chat",
      targetApp,
      targetRole: targetApp === "rider" ? "driver" : "customer",
      targetScreen: "chat",
      bookingId,
      entityId: messageId,
      isTestRide,
      channelId: isTestRide ? "golocal_test_rides" : "golocal_chat",
      title: `Message from ${msg.senderName || (msg.senderRole === "customer" ? "Passenger" : "Rider")}`,
      body: msg.text || "Sent a trip message",
    });
  }

  async function handleDriverProfileWrite(driverId, before, after) {
    if (!after) return;
    if (!before || (before.verificationStatus !== "pending" && after.verificationStatus === "pending")) {
      const adminOps = await getAdminAndOpsUserIds();
      await sendToUserIds(adminOps, {
        eventId: `admin_kyc_${driverId}_${after.verificationStatus}_${after.updatedAt?.seconds || "0"}`,
        dedupeKey: `admin_kyc_${driverId}_${after.verificationStatus}`,
        type: "new_rider_application",
        targetApp: "admin",
        targetRole: "admin",
        targetScreen: "riders",
        entityId: driverId,
        channelId: "golocal_general",
        title: "New Rider KYC Application",
        body: `${after.legalName || "Applicant"} (${String(after.vehicleCategory || "").toUpperCase()}) submitted KYC documents for review.`,
      });
    } else if (before.verificationStatus !== after.verificationStatus) {
      await sendToUserIds([driverId], {
        eventId: `rider_kyc_${driverId}_${after.verificationStatus}`,
        dedupeKey: `rider_kyc_${driverId}_${after.verificationStatus}`,
        type: "kyc_status_update",
        targetApp: "rider",
        targetRole: "driver",
        targetScreen: "profile",
        entityId: driverId,
        channelId: "golocal_rides",
        title: `KYC Status: ${String(after.verificationStatus || "").toUpperCase()}`,
        body:
          after.verificationStatus === "verified"
            ? "Documents verified! You can now go online and accept commercial rides."
            : after.rejectionReason || "Please review Admin feedback and update your documents.",
      });
    }
  }

  async function handleTestRiderRequestWrite(uid, before, after) {
    if (!after) return;
    const prevStatus = before?.status || "";
    const newStatus = after.status || "pending";

    if (!before || (prevStatus !== "pending" && newStatus === "pending")) {
      const adminOps = await getAdminAndOpsUserIds();
      await sendToUserIds(adminOps, {
        eventId: `test_rider_pending_${uid}`,
        dedupeKey: `test_rider_pending_${uid}_${after.updatedAt?.seconds || newStatus}`,
        type: "test_rider_request",
        targetApp: "admin",
        targetRole: "admin",
        targetScreen: "test_rides",
        entityId: uid,
        isTestRide: true,
        channelId: "golocal_test_rides",
        title: "[TEST RIDER] New Access Request",
        body: `${after.fullName} requested Test Rider access (${String(after.vehicleCategory || "").toUpperCase()}).`,
      });
      return;
    }

    if (
      before &&
      before.opsRecommendation !== after.opsRecommendation &&
      after.opsRecommendation
    ) {
      const admins = await getAdminUserIds();
      await sendToUserIds(admins, {
        eventId: `test_rider_ops_rec_${uid}_${after.opsRecommendation}`,
        dedupeKey: `test_rider_ops_rec_${uid}_${after.opsRecommendation}`,
        type: "test_rider_request",
        targetApp: "admin",
        targetRole: "admin",
        targetScreen: "test_rides",
        entityId: uid,
        isTestRide: true,
        channelId: "golocal_test_rides",
        title: "[TEST RIDER] Ops Recommendation Added",
        body: `${after.fullName}: Operations recommended "${after.opsRecommendation}".`,
      });
    }

    if (prevStatus !== newStatus && ["approved", "rejected", "suspended", "revoked"].includes(newStatus)) {
      const reasonSuffix = after.rejectionReason ? ` Reason: ${after.rejectionReason}` : "";
      const bodyMap = {
        approved: "Your Test Rider access is approved! Open GoLocal Rider to go online in Test Mode.",
        rejected: `Your Test Rider request was rejected.${reasonSuffix}`,
        suspended: `Your Test Rider access has been suspended.${reasonSuffix}`,
        revoked: `Your Test Rider access has been revoked.${reasonSuffix}`,
      };
      await sendToUserIds([uid], {
        eventId: `test_rider_decision_${uid}_${newStatus}`,
        dedupeKey: `test_rider_decision_${uid}_${newStatus}`,
        type: "test_rider_status",
        targetApp: "rider",
        targetRole: "driver",
        targetScreen: "test_rider_dashboard",
        entityId: uid,
        isTestRide: true,
        channelId: "golocal_test_rides",
        title: `[TEST RIDER] Access ${newStatus.toUpperCase()}`,
        body: bodyMap[newStatus] || `Status updated to ${newStatus}.`,
      });
    }
  }

  async function handleEmergencyEventCreate(eventId, after) {
    if (!after) return;
    const adminOps = await getAdminAndOpsUserIds();
    await sendToUserIds(adminOps, {
      eventId: `sos_${eventId}`,
      dedupeKey: `sos_${eventId}`,
      type: "sos_alert",
      targetApp: "admin",
      targetRole: "admin",
      targetScreen: "bookings",
      bookingId: after.bookingId || "",
      entityId: eventId,
      channelId: "golocal_sos",
      title: "URGENT SOS Alert Triggered",
      body: after.notes || "Emergency assistance requested. Open Operations Console immediately.",
    });
  }

  async function handleDisputeCreate(disputeId, after) {
    if (!after) return;
    const adminOps = await getAdminAndOpsUserIds();
    await sendToUserIds(adminOps, {
      eventId: `admin_dispute_${disputeId}`,
      dedupeKey: `admin_dispute_${disputeId}`,
      type: "dispute_opened",
      targetApp: "admin",
      targetRole: "admin",
      targetScreen: "bookings",
      bookingId: after.bookingId || "",
      entityId: disputeId,
      channelId: "golocal_sos",
      title: "New Trip / Fare Dispute Opened",
      body: `${after.category || "Dispute"}: ${after.description || "Requires operations review."}`,
    });
  }

  async function handleComplaintWrite(complaintId, before, after) {
    if (!after || before) return;
    const adminOps = await getAdminAndOpsUserIds();
    await sendToUserIds(adminOps, {
      eventId: `admin_complaint_${complaintId}`,
      dedupeKey: `admin_complaint_${complaintId}`,
      type: "complaint_opened",
      targetApp: "admin",
      targetRole: "admin",
      targetScreen: "bookings",
      bookingId: after.bookingId || "",
      entityId: complaintId,
      channelId: "golocal_general",
      title: "New Customer/Rider Complaint",
      body: `${after.category || "Complaint"}: ${after.description || "Open Admin Bookings to resolve."}`,
    });
  }

  async function handleCancellationAppealWrite(appealId, before, after) {
    if (!after) return;
    if (!before) {
      const adminOps = await getAdminAndOpsUserIds();
      await sendToUserIds(adminOps, {
        eventId: `admin_appeal_${appealId}`,
        dedupeKey: `admin_appeal_${appealId}`,
        type: "cancellation_appeal",
        targetApp: "admin",
        targetRole: "admin",
        targetScreen: "bookings",
        bookingId: after.bookingId || "",
        entityId: appealId,
        channelId: "golocal_general",
        title: "New Cancellation Appeal Submitted",
        body: `Booking #${String(after.bookingId || appealId).slice(0, 6)}: ${after.reason || "Needs review."}`,
      });
      return;
    }

    if (before.status !== after.status && ["accepted", "rejected"].includes(after.status)) {
      const targetApp = after.appellantRole === "driver" ? "rider" : "customer";
      await sendToUserIds([after.appellantId], {
        eventId: `appeal_decision_${appealId}_${after.status}`,
        dedupeKey: `appeal_decision_${appealId}_${after.status}`,
        type: "booking_exception",
        targetApp,
        targetRole: after.appellantRole === "driver" ? "driver" : "customer",
        targetScreen: "bookings",
        bookingId: after.bookingId || "",
        entityId: appealId,
        channelId: "golocal_rides",
        title: `Cancellation Appeal ${String(after.status).toUpperCase()}`,
        body: after.resolutionNote || `Your cancellation appeal has been ${after.status}.`,
      });
    }
  }

  async function handleBusBookingWrite(ticketId, before, after) {
    if (!after) return;
    if (!before || before.status !== after.status) {
      await sendToUserIds([after.customerId], {
        eventId: `bus_${after.status}_${ticketId}`,
        dedupeKey: `bus_${after.status}_${ticketId}`,
        type: `bus_${after.status}`,
        targetApp: "customer",
        targetRole: "customer",
        targetScreen: "bus",
        bookingId: ticketId,
        entityId: ticketId,
        channelId: "golocal_rides",
        title: `Bus Ticket ${String(after.status || "").toUpperCase()}`,
        body: `${after.routeLabel || "Bus Service"} • Seats: ${(after.seatIds || after.seatNumbers || []).join(", ")}`,
      });
    }
  }

  async function runDailyDocumentExpiryReminder(dateIso = new Date().toISOString().slice(0, 10)) {
    const driversSnap = await db.collection("driver_profiles").where("verificationStatus", "==", "verified").get();
    const soonCutoffMs = Date.now() + 30 * 24 * 60 * 60 * 1000;
    let notified = 0;
    for (const doc of driversSnap.docs) {
      const data = doc.data();
      const docs = Array.isArray(data.documents) ? data.documents : [];
      const expiring = docs.filter((item) => item.expiryDateIso && Date.parse(item.expiryDateIso) <= soonCutoffMs);
      if (expiring.length > 0) {
        await sendToUserIds([data.driverId || doc.id], {
          eventId: `doc_expiry_${doc.id}_${dateIso}`,
          dedupeKey: `doc_expiry_${doc.id}_${dateIso}`,
          type: "document_expiry_reminder",
          targetApp: "rider",
          targetRole: "driver",
          targetScreen: "profile",
          entityId: doc.id,
          channelId: "golocal_rides",
          title: "Vehicle Document Expiring Soon",
          body: `${expiring.map((e) => e.docType).join(", ")} expires soon. Renew to stay online.`,
        });
        notified += 1;
      }
    }
    return notified;
  }

  async function handleSampleRideWrite(rideId, before, after) {
    if (!after) return;
    const prevStatus = before?.status || "";
    const newStatus = after.status || "incoming";
    const routeLabel = `${after.pickupLabel || "Pickup"} -> ${after.destinationLabel || "Destination"}`;

    if (!before && newStatus === "incoming") {
      const sampleSnap = await db
        .collection("sample_riders")
        .where("verificationStatus", "==", "sample_approved")
        .where("availabilityStatus", "==", "online")
        .get();
      const targetRiders = sampleSnap.docs.map((d) => d.data().uid || d.id).filter(Boolean);
      if (targetRiders.length > 0) {
        await sendToUserIds(targetRiders, {
          eventId: `sample_request_${rideId}`,
          dedupeKey: `sample_request_${rideId}`,
          type: "sample_request",
          targetApp: "rider",
          targetRole: "driver",
          targetScreen: "requests",
          bookingId: rideId,
          entityId: rideId,
          isSampleRide: true,
          channelId: "golocal_sample_ride_requests_v2",
          title: `SAMPLE RIDE • ₹${Math.round(after.estimatedFareInr || 0)}`,
          body: `${routeLabel} • ${(after.distanceKm || 0).toFixed(1)} km`,
        });
      }
      return;
    }

    if (prevStatus !== newStatus) {
      if (newStatus === "accepted" && after.customerId) {
        await sendToUserIds([after.customerId], {
          eventId: `sample_accepted_${rideId}`,
          dedupeKey: `sample_accepted_${rideId}`,
          type: "sample_status",
          targetApp: "customer",
          targetRole: "customer",
          targetScreen: "booking",
          bookingId: rideId,
          entityId: rideId,
          isSampleRide: true,
          channelId: "golocal_sample_rides",
          title: "SAMPLE RIDER Assigned",
          body: `${after.assignedSampleRiderName || "Sample Rider"} accepted your sample ride.`,
        });
      } else if (newStatus === "arrived" && after.customerId) {
        await sendToUserIds([after.customerId], {
          eventId: `sample_arrived_${rideId}`,
          dedupeKey: `sample_arrived_${rideId}`,
          type: "sample_status",
          targetApp: "customer",
          targetRole: "customer",
          targetScreen: "booking",
          bookingId: rideId,
          entityId: rideId,
          isSampleRide: true,
          channelId: "golocal_sample_rides",
          title: "SAMPLE RIDER Arrived at Pickup",
          body: "Share your local 6-digit Sample OTP displayed in the app to start.",
        });
      } else if (newStatus === "in_progress" && after.customerId) {
        await sendToUserIds([after.customerId], {
          eventId: `sample_started_${rideId}`,
          dedupeKey: `sample_started_${rideId}`,
          type: "sample_status",
          targetApp: "customer",
          targetRole: "customer",
          targetScreen: "booking",
          bookingId: rideId,
          entityId: rideId,
          isSampleRide: true,
          channelId: "golocal_sample_rides",
          title: "Sample Ride Started",
          body: routeLabel,
        });
      } else if (newStatus === "completed" && after.customerId) {
        await sendToUserIds([after.customerId], {
          eventId: `sample_completed_${rideId}`,
          dedupeKey: `sample_completed_${rideId}`,
          type: "sample_status",
          targetApp: "customer",
          targetRole: "customer",
          targetScreen: "booking",
          bookingId: rideId,
          entityId: rideId,
          isSampleRide: true,
          channelId: "golocal_sample_rides",
          title: "Sample Ride Completed",
          body: `Simulated sample fare ₹${Math.round(after.estimatedFareInr || 0)}.`,
        });
      }
    }
  }

  async function handlePaymentWebhook({ rawBody, headers = {}, secret = "" }) {
    if (!secret || !String(secret).trim()) {
      return { statusCode: 503, body: { error: "PAYMENT_WEBHOOK_SECRET not configured" } };
    }
    const lowerHeaders = Object.fromEntries(
      Object.entries(headers || {}).map(([k, v]) => [String(k).toLowerCase(), String(v || "")])
    );
    const sig = lowerHeaders["x-golocal-signature"] || lowerHeaders["x-razorpay-signature"] || "";
    if (!verifyHmacSha256Signature(rawBody, sig, secret)) {
      return { statusCode: 401, body: { error: "Invalid webhook signature" } };
    }

    let payload;
    try {
      const text = Buffer.isBuffer(rawBody) ? rawBody.toString("utf8") : String(rawBody || "{}");
      payload = JSON.parse(text);
    } catch (_) {
      return { statusCode: 400, body: { error: "Invalid JSON payload" } };
    }

    const bookingId = String(payload.bookingId || payload.notes?.bookingId || "").trim();
    const eventType = String(payload.event || payload.status || "").trim().toLowerCase();
    const gatewayReference = String(payload.gatewayReference || payload.paymentId || payload.id || "").trim();

    if (!bookingId || !/^[a-zA-Z0-9_-]{1,128}$/.test(bookingId)) {
      return { statusCode: 400, body: { error: "Valid bookingId required" } };
    }

    const bookingRef = db.collection("bookings").doc(bookingId);
    const bookingSnap = await bookingRef.get();
    if (!bookingSnap.exists) {
      return { statusCode: 404, body: { error: "Booking not found" } };
    }
    const booking = bookingSnap.data() || {};
    if (booking.is_test_ride === true || booking.test_mode === true) {
      return { statusCode: 403, body: { error: "Test/sample rides cannot be settled via commercial webhook" } };
    }

    const isPaidEvent = eventType === "payment.captured" || eventType === "paid" || eventType === "captured";
    const isRefundEvent = eventType === "refund.processed" || eventType === "refunded";
    if (!isPaidEvent && !isRefundEvent) {
      return { statusCode: 200, body: { ok: true, ignored: true, event: eventType } };
    }

    const nextPaymentStatus = isRefundEvent ? "refunded" : "paid";
    const paymentRef = db.collection("payments").doc(bookingId);
    const paymentSnap = await paymentRef.get();
    const existingPayment = paymentSnap.exists ? paymentSnap.data() || {} : {};

    if (
      existingPayment.status === nextPaymentStatus &&
      booking.paymentStatus === nextPaymentStatus &&
      (!gatewayReference || existingPayment.gatewayReference === gatewayReference)
    ) {
      return { statusCode: 200, body: { ok: true, idempotent: true, bookingId, status: nextPaymentStatus } };
    }

    const nowTs = fieldValue.serverTimestamp();
    await paymentRef.set(
      {
        bookingId,
        customerId: booking.customerId || "",
        driverId: booking.assignedDriverId || "",
        paymentMethod: booking.paymentMethod || "upi",
        status: nextPaymentStatus,
        gatewayReference: gatewayReference || existingPayment.gatewayReference || "",
        updatedAt: nowTs,
      },
      { merge: true }
    );
    await bookingRef.set(
      {
        paymentStatus: nextPaymentStatus,
        updatedAt: nowTs,
      },
      { merge: true }
    );
    await db.collection("audit_logs").doc(makeSafeDocId(`pay_wh_${bookingId}_${nextPaymentStatus}_${gatewayReference || "evt"}`)).set(
      {
        actorId: "system_payment_webhook",
        actorEmail: "webhook@golocal.internal",
        action: isRefundEvent ? "WEBHOOK_PAYMENT_REFUNDED" : "WEBHOOK_PAYMENT_CAPTURED",
        targetCollection: "payments",
        targetId: bookingId,
        summary: `Verified webhook ${eventType} (${nextPaymentStatus}) ref=${gatewayReference || "n/a"}`,
        createdAt: nowTs,
      },
      { merge: true }
    );

    if (booking.customerId) {
      await sendToUserIds([booking.customerId], {
        eventId: `pay_${nextPaymentStatus}_${bookingId}`,
        dedupeKey: `pay_${nextPaymentStatus}_${bookingId}`,
        type: "payment_update",
        targetApp: "customer",
        targetRole: "customer",
        targetScreen: "bookings",
        bookingId,
        entityId: bookingId,
        channelId: "golocal_rides",
        title: isRefundEvent ? "Refund Processed" : "UPI Payment Verified",
        body: isRefundEvent
          ? `Refund verified for booking #${bookingId.slice(0, 8).toUpperCase()}.`
          : `Online payment verified for booking #${bookingId.slice(0, 8).toUpperCase()}.`,
      });
    }

    return { statusCode: 200, body: { ok: true, bookingId, status: nextPaymentStatus } };
  }

  return {
    sendToUserIds,
    handleBookingWrite,
    handleRideOfferWrite,
    handleBookingChatMessageCreate,
    handleDriverProfileWrite,
    handleTestRiderRequestWrite,
    handleSampleRideWrite,
    handleEmergencyEventCreate,
    handleDisputeCreate,
    handleComplaintWrite,
    handleCancellationAppealWrite,
    handleBusBookingWrite,
    runDailyDocumentExpiryReminder,
    handlePaymentWebhook,
  };
}

module.exports = {
  INVALID_TOKEN_CODES,
  normalizeAppVariant,
  clickActionForApp,
  channelForPayload,
  sanitizeNotificationText,
  makeSafeDocId,
  verifyHmacSha256Signature,
  createNotificationHandlers,
};
