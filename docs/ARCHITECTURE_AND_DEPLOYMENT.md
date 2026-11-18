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
- `users/{userId}` — Core user profile, preferred language (`en`/`bn`/`hi`), low-network mode, account status, `isTestCustomer` flag (Owner Admin managed)
- `customer_profiles/{userId}` — Saved home/work/native village addresses (PII isolated)
- `emergency_contacts/{userId}` — Strictly isolated private emergency contact (Owner + authorized emergency personnel only)
- `driver_profiles/{driverId}` — Private KYC (`legalName`, `phone`, `drivingLicenceNumber`, `rcNumber`, `insuranceNumber`, `permitInfo`, `settlementUpiOrBank`), `isTestPartner` flag (Owner Admin managed)
- `driver_directory/{driverId}` — Public non-PII discovery profile (`verificationStatus == 'verified'`, masked plate, rating, live online toggle, `isTestPartner`)
- `service_areas/{areaId}` — West Bengal districts, blocks, villages, railway stations, operational hours, parcel approval flag
- `fare_configs/{configId}` — Versioned pricing engine (`baseFareInr`, `perKmRateInr`, `minimumFareInr`, `gstPercent`, `commissionPercent`, `minBidFactor`, `maxBidFactor`)
- `bookings/{bookingId}` — Controlled ride lifecycle (`searching -> assigned -> arriving -> arrived -> in_progress -> completed | cancelled`) with explicit `is_test_ride`, `test_mode`, `test_otp_status`, `test_otp_expires_at_ms` isolation fields
- `ride_offers/{offerId}` — Driver fare proposals strictly bounded by parent booking's `[minPermittedFareInr, maxPermittedFareInr]` (blocked on `is_test_ride`)
- `ride_assignments/{assignmentId}` — Keyed by `bookingId` (`status`, `is_test_ride`, `assignedByUid`, `assignedByRole`) guaranteeing atomic single-driver assignment for both real and test rides
- `trip_locations/{bookingId}` & `live_locations/{driverId}` — Live driver/simulator GPS coordinates synchronized during active real and test trips (`isTestRide`, `isSimulated`)
- `test_ride_otps/{bookingId}` & `test_ride_otp_secrets/{bookingId}` — SHA-256 booking-bound 6-digit test OTP challenge (customer write-only verify; zero customer/driver read) & Owner/Ops secret monitor
- `payments/{paymentId}` & `commissions/{commissionId}` — Idempotent commercial completion ledger keyed by `bookingId` (strictly blocked for `is_test_ride == true`)
- `driver_settlements/{settlementId}` & `refunds/{refundId}` — Payout & refund workflows
- `ratings/{ratingId}`, `complaints/{complaintId}`, `emergency_events/{eventId}`, `notifications/{notificationId}`, `support_requests/{requestId}`, `audit_logs/{logId}` (tagged `isTestRide` for test audit queries), `operations_staff/{staffUid}`, `admins/{adminUid}`

---

## 4. Admin-Controlled Test Ride Architecture & Governance
1. **Default Off & Double Authorization**:
   - `platform_settings/global.testRideModeEnabled` defaults to `false`. Only the Owner Admin can toggle Test Ride Mode or edit simulation parameters.
   - Customers can opt into a `TEST RIDE` only when `testRideModeEnabled == true` AND their `users/{uid}.isTestCustomer == true` (set by Owner Admin in the Customers module). Ordinary customers are blocked in both client UI and `firestore.rules`.
2. **Test Partner & Simulated Unit Dispatch**:
   - Operations Staff and Owner Admin can assign either a verified driver marked `isTestPartner == true` (`driver_profiles` / Riders module) or a `test_driver_*` simulated unit (`TestDriverCatalog`). Unverified or non-test real drivers are rejected by `firestore.rules`.
   - Assignment commits atomically across `bookings`, `ride_assignments`, `test_ride_otps`, `test_ride_otp_secrets`, `trip_locations`, `live_locations`, and `audit_logs` (`isTestRide = true`).
3. **Strict 6-Digit OTP Verification & Override Governance**:
   - Neither Drivers nor Operations Staff can transition a test ride `arrived -> in_progress` without a verified or Owner-overridden challenge in `test_ride_otps/{bookingId}`.
   - Operations Staff can log an audited **Request OTP Override** (`overrideRequestedReason`), while only the **Owner Admin** can execute `overridden = true` (`TEST_OTP_ADMIN_OVERRIDE`).
4. **Zero Commercial & Analytics Impact**:
   - Test rides settle at ₹0 non-commercially, `firestore.rules` rejects `payments`/`commissions` on `is_test_ride == true`, Cloud Functions (`onBookingWriteNotify`) suppress searching test broadcasts to regular online drivers, and `todaysCompletedRides` + Admin KPIs exclude test rides.

---

## 5. Setup & Deployment Instructions

### A. Firebase & Security Rules
1. Provision Firebase via AI Studio (`google-services.json` and `firebase_applet_config.xml`).
2. Validate rules & unit tests locally with the Auth + Firestore emulators:
   - `firebase emulators:exec --only auth,firestore "FIRESTORE_EMULATOR_HOST=127.0.0.1:8085 node --test firestore.test.js"`
   - `firebase emulators:exec --only auth,firestore "gradle :app:testDebugUnitTest"`
3. Deploy `firestore.rules` via AI Studio RPC or `firebase deploy --only firestore:rules`.
4. Deploy Cloud Functions from `/functions`:
   - `firebase deploy --only functions`
5. Create Firestore composite index if queried via Admin/Ops Test Audit History:
   - Collection `audit_logs`: `isTestRide` (ASC) + `createdAt` (DESC).

### B. Cloud Functions & Payment Webhook (Server-Side)
To activate automated UPI/gateway webhooks and FCM push notifications:
1. Configure `PAYMENT_GATEWAY_KEY_SECRET` and `PAYMENT_WEBHOOK_SECRET` in Firebase Secret Manager (never commit to GitHub).
2. Implement an HTTPS webhook endpoint verifying the gateway HMAC signature before updating `payments/{bookingId}.status = "paid"`.

### C. Android Build
- Debug build: `gradle assembleDebug`
- Unit & Robolectric suite: `gradle :app:testDebugUnitTest`
- Release bundle: Export via AI Studio / `gradle bundleRelease` with upload keystore environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`).

---

## 6. Production-Readiness Checklist (West Bengal Launch)
- [ ] Keep `platform_settings/global.testRideModeEnabled = false` in production outside controlled QA sessions.
- [ ] Verify West Bengal Motor Vehicles Rules aggregator licensing & bike-taxi / e-rickshaw (Toto) permit compliance per district.
- [ ] Enable `parcel` category in `fare_configs` only after separate commercial carriage approval.
- [ ] Deploy server-side Cloud Function webhook (`functions/index.js`) & configure FCM + UPI secrets.
- [ ] Configure Firebase App Check (Play Integrity) for release builds.
- [ ] Configure Cloud Firestore composite indexes (`audit_logs`: `isTestRide` + `createdAt`, `bookings`: `is_test_ride` + `createdAt`).
- [ ] Verify 24x7 SOS safety desk escalation routing and customer support helpline.
