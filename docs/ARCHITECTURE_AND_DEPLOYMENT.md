# GOLOCAL by NR Next — Architecture, Deployment & Production-Readiness Guide

## 1. Platform Overview
- **Product**: GOLOCAL by NR Next (*Your City. Your Ride.*)
- **Initial Market**: Rural and semi-urban West Bengal, India
- **Client Runtime**: Android (Kotlin + Jetpack Compose + Material 3, Light & Dark Themes, EN / বাংলা / हिन्दी)
- **Backend**: Firebase Authentication (Google Sign-In via Jetpack Credential Manager) & Cloud Firestore Enterprise (`ai-studio-android-golocal-e1a2a68b-450b-4d1b-817a-6a38c9c578bb`)

---

## 2. Configured vs. External Integration Boundaries
| Integration | Status | Notes |
|---|---|---|
| Firebase Auth (Google Sign-In) | **CONFIGURED** | Credential Manager (`GetSignInWithGoogleOption`) + silent auto sign-in (`GetGoogleIdOption`) |
| Cloud Firestore & Security Rules | **CONFIGURED** | Zero-trust rules with schema validation, PII isolation, RBAC, and atomic transactions |
| Android Fused Location (GPS) | **CONFIGURED** | Runtime `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` + manual WB catalog fallback |
| Offline Draft Recovery & Prefs | **CONFIGURED** | Jetpack DataStore (`PreferencesRepository`) for low-network draft recovery |
| Payment Gateway Webhooks (Razorpay/Cashfree) | **NOT CONFIGURED (BOUNDARY READY)** | Client records `pending_verification` for UPI/gateway; never marks online payment `paid` without server webhook verification |
| Firebase Cloud Functions & FCM Push | **NOT CONFIGURED (BOUNDARY READY)** | Requires Firebase Blaze plan & Cloud Functions deployment for server-side push & webhook reconciliation |
| External Map Tiles / Directions SDK | **NOT CONFIGURED (BOUNDARY READY)** | Add `MAPS_API_KEY` in AI Studio Secrets / Cloud Functions for external turn-by-turn routing |

---

## 3. Database Collections (`firebase-blueprint.json` & `firestore.rules`)
- `users/{userId}` — Core user profile, preferred language (`en`/`bn`/`hi`), low-network mode, account status
- `customer_profiles/{userId}` — Saved home/work/native village addresses & SOS emergency contact (PII isolated)
- `driver_profiles/{driverId}` — Private KYC (`legalName`, `phone`, `drivingLicenceNumber`, `rcNumber`, `insuranceNumber`, `permitInfo`, `settlementUpiOrBank`)
- `driver_directory/{driverId}` — Public non-PII discovery profile (`verificationStatus == 'verified'`, masked plate, rating, live online toggle)
- `service_areas/{areaId}` — West Bengal districts, blocks, villages, railway stations, operational hours, parcel approval flag
- `fare_configs/{configId}` — Versioned pricing engine (`baseFareInr`, `perKmRateInr`, `minimumFareInr`, `gstPercent`, `commissionPercent`, `minBidFactor`, `maxBidFactor`)
- `bookings/{bookingId}` — Controlled ride lifecycle (`searching -> assigned -> arriving -> arrived -> in_progress -> completed | cancelled`)
- `ride_offers/{offerId}` — Driver fare proposals strictly bounded by parent booking's `[minPermittedFareInr, maxPermittedFareInr]`
- `ride_assignments/{assignmentId}` — Keyed by `bookingId` to guarantee atomic single-driver assignment
- `trip_locations/{bookingId}` — Live driver GPS coordinates during active trips
- `payments/{paymentId}` & `commissions/{commissionId}` — Idempotent completion ledger keyed by `bookingId`
- `driver_settlements/{settlementId}` & `refunds/{refundId}` — Payout & refund workflows
- `ratings/{ratingId}`, `complaints/{complaintId}`, `emergency_events/{eventId}`, `notifications/{notificationId}`, `support_requests/{requestId}`, `audit_logs/{logId}`, `admins/{adminUid}`

---

## 4. Setup & Deployment Instructions

### A. Firebase & Security Rules
1. Provision Firebase via AI Studio (`google-services.json` and `firebase_applet_config.xml`).
2. Validate rules locally:
   - `FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js`
   - `gradle :app:testDebugUnitTest --tests '*Rule*'`
3. Deploy `firestore.rules` using the Firebase deploy action.

### B. Cloud Functions & Payment Webhook (Server-Side)
To activate automated UPI/gateway webhooks and FCM push notifications:
1. Initialize `functions/` in your Firebase project (`firebase init functions`).
2. Configure `PAYMENT_GATEWAY_KEY_SECRET` and `PAYMENT_WEBHOOK_SECRET` in Firebase Secret Manager (never commit to GitHub).
3. Implement an HTTPS webhook endpoint verifying the gateway HMAC signature before updating `payments/{bookingId}.status = "paid"`.

### C. Android Build
- Debug build: `gradle assembleDebug`
- Unit & Robolectric suite: `gradle :app:testDebugUnitTest`
- Release bundle: Export via AI Studio / `gradle bundleRelease` with upload keystore environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`).

---

## 5. Production-Readiness Checklist (West Bengal Launch)
- [ ] Verify West Bengal Motor Vehicles Rules aggregator licensing & bike-taxi / e-rickshaw (Toto) permit compliance per district.
- [ ] Enable `parcel` category in `fare_configs` only after separate commercial carriage approval.
- [ ] Deploy server-side Cloud Function webhook for UPI/Razorpay/Cashfree signature verification.
- [ ] Configure Firebase App Check (Play Integrity) for release builds.
- [ ] Configure Cloud Firestore composite indexes for multi-field admin queries if needed.
- [ ] Verify 24x7 SOS safety desk escalation routing and customer support helpline.
