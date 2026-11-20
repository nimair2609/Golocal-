# GoLocal 2.0 by NR Next — Multi-Platform Architecture, Deployment & Production Guide

## 1. Multi-Platform Ecosystem Overview
- **Product**: **GoLocal by NR Next** (*Your City. Your Ride.*)
- **Firebase Project**: `gen-lang-client-0818250755`
- **Firestore Database ID**: `ai-studio-android-golocal-e1a2a68b-450b-4d1b-817a-6a38c9c578bb`
- **Realtime Database**: `https://gen-lang-client-0818250755-default-rtdb.firebaseio.com`
- **Android Package / Application ID**: `com.aistudio.golocal.wbmxrq` (namespace `com.example`)
- **Four Connected Applications Sharing One Backend**:
  1. **Customer App** — Android (`MainActivity`) & Web (`/public/customer/`): Phone OTP login, OSM + OSRM routing, transparent fare quotes, promo/cancellation disclosures, live driver tracking, Customer-only 6-digit Pickup OTP, in-trip chat, SOS, complaints/ratings, and itemized GST receipts.
  2. **Rider Partner App** — Android (`RiderActivity`) & Web (`/public/rider/`): Phone OTP login, commercial KYC document upload to Firebase Storage (`kyc_docs/{uid}/...`), strict approval gate (`verificationStatus == 'verified'`), live GPS/RTDB push, searching queue + fare bidding + direct atomic accept, SHA-256 Pickup OTP verification with brute-force cooldown, trip completion/settlement ledger, 24h/7d/30d net earnings, CSV statements, and allowlisted Sample Rider isolated sandbox.
  3. **Owner Admin Portal** — Web (`/public/owner.html`) & Android (`AdminActivity`): Full governance — Partner KYC document preview & approval/rejection/suspension, Service Zones polygon & radius editor (Borai PIN 721457 ready), Fare & Commission versioned config editor, Customer restrict/reactivate, Payments/Settlements/Refunds ledger, Operations Staff allowlist management, Promotions/Platform settings, Booking overrides, and immutable CSV-exportable Audit Logs.
  4. **Operations Portal** — Web (`/public/operations.html`) & Android Ops tab: RBAC-restricted (`admins` or active `operations_staff`) live dispatch monitor, delayed booking alerts (>=10m), manual verified-partner atomic assignment, dispatch notes & escalation flags, complaint/dispute/SOS triage, and shift CSV export — with zero access to Owner pricing, KYC approvals, or settlements.

---

## 2. Authentication, RBAC & Zero-Trust Enforcement
- **Phone OTP Only**: All 4 user-facing surfaces sign in with Firebase Phone Authentication (`SignInWithPhoneNumber` / Web `RecaptchaVerifier`). Google Sign-In is removed from user flows.
- **Role Resolution Order**:
  - `admins/{uid}` (or bootstrapped verified admin email `renukadas083@gmail.com`) -> `OWNER_ADMIN`
  - `operations_staff/{uid}` where `isActive == true` -> `OPERATIONS_STAFF`
  - `driver_profiles/{uid}` -> Partner onboarding & commercial status (`pending` / `verified` / `rejected` / `suspended`)
  - `sample_rider_allowlist/{phoneE164}` + `sample_riders/{uid}` -> Isolated Sample Rider sandbox
  - `users/{uid}` -> Customer / Partner core account
- **Security Rules Enforced Server-Side**:
  - `firestore.rules`: Rejects self-promotion, protects `emergency_contacts` & `driver_profiles` PII, hides `ride_otps` plaintext from riders, enforces atomic `ride_assignments` + `ride_otps` on assignment, blocks `payments`/`commissions` for `is_test_ride == true`, and enforces fail-closed Ops vs. Owner separation.
  - `database.rules.json`: Validates `live_locations/drivers/{uid}` & `live_locations/trips/{bookingId}` payloads and isolates `auth_roles/ops/{uid}`.
  - `storage.rules`: Restricts `kyc_docs/{uid}/{file}` & `bus_docs/{uid}/{file}` uploads (<5 MB images/PDFs) strictly to owner UID or Owner Admin read/delete.

---

## 3. Cloud Functions (`/functions`) & Payment Webhooks
Implemented in `/functions/index.js` & `/functions/handlers.js`:
- `onBookingWriteNotify` — Dispatches FCM push notifications to assigned customers/drivers & broadcasts new searching requests (suppresses test-ride broadcasts to regular commercial drivers).
- `onRideOfferCreate` — Pushes incoming fare-offer notifications to the booking customer.
- `onEmergencyCreated` — Alerts Owner Admins and Operations Staff on SOS triggers.
- `paymentWebhook` (HTTPS v2) — Verifies `x-golocal-signature` / `x-razorpay-signature` HMAC-SHA256 using Firebase Secret `PAYMENT_WEBHOOK_SECRET`, enforces idempotency against duplicate webhook delivery, and transitions `payments/{bookingId}` -> `paid` and `bookings/{bookingId}` -> `paymentStatus: 'paid'`.

**Requires Blaze Plan + Secrets to activate live webhook/FCM**:
```bash
firebase functions:secrets:set PAYMENT_WEBHOOK_SECRET
firebase deploy --only functions
```

---

## 4. Web Hosting & Firebase Deployment
1. Validate Firebase rules & domain tests locally:
   ```bash
   node --test functions/functions.test.js tests/domain.test.js
   firebase emulators:exec --only auth,firestore "FIRESTORE_EMULATOR_HOST=127.0.0.1:8085 node --test firestore.test.js tests/firestore.rules.test.js"
   ```
2. Deploy rules, indexes, and web apps/portals:
   ```bash
   firebase deploy --only firestore:rules,firestore:indexes,database,storage,hosting
   ```
3. Configure `.env` (see `/.env.example`) for self-hosted OSM tile mirror, OSRM routing server, Nominatim URL, or custom update manifest if scaling beyond public OSM/OSRM rate limits.

---

## 5. Android Build, Signing & Google Play Policy Compliance
- **Debug APK / Unit Tests**:
  - `gradle :app:testDebugUnitTest`
  - `gradle :app:assembleDebug`
- **Production Release Signing (`/.github/workflows/android-apk-release.yml`)**:
  - Generate upload keystore locally:
    `keytool -genkey -v -keystore my-upload-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias golocal-upload`
  - Add GitHub Actions Repository Secrets: `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD`.
- **Google Play Policy Compliance**:
  - Zero broad storage permissions (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`): KYC photos and bus documents use Android Photo Picker (`PickVisualMedia`) + Firebase Storage upload.
  - Strictly least-privilege runtime permissions (`ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS` on Android 13+).
  - No `debug.keystore*` edits and no hardcoded secrets in source.
