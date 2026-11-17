# GoLocal by NR Next — Play Store Readiness & Firebase Release Checklist

This document summarizes the Android, Google Play Policy, Firebase backend, and real-device QA steps required before submitting **GoLocal (`com.aistudio.golocal.wbmxrq`)** to the Google Play Console.

---

## 1. Google Play Policy & Target SDK Compliance
- **Target & Compile SDK**: Android API 36 (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`).
- **Media Selection (Zero Storage Permissions)**:
  - Driver KYC, Operator Permit, and Bus Vehicle document uploads use `ActivityResultContracts.PickVisualMedia`.
  - No `READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, or `READ_MEDIA_VIDEO` permissions are declared or requested.
- **No Self-Update Installer Permission**:
  - `REQUEST_INSTALL_PACKAGES` has been removed from `AndroidManifest.xml`.
  - `AppUpdateManager` / `AppUpdateUi` automatically detects whether `REQUEST_INSTALL_PACKAGES` is declared in the manifest and falls back to opening the HTTPS update page in the browser on Play Store builds.
- **Account Deletion Compliance**:
  - Users can trigger immediate self-service account deletion from **Profile → Delete Account & Data** (requires typing `DELETE`), which cleans FCM tokens, deletes `emergency_contacts/{uid}` and `customer_profiles/{uid}`, anonymizes `/users/{uid}`, and deletes the Firebase Auth credential (`user.delete()`).
- **Assistive On-Device OCR Disclosure**:
  - ML Kit `TextRecognition` (`com.google.mlkit:text-recognition`) runs on-device to pre-fill Licence/RC/Insurance numbers and expiry dates.
  - UI explicitly states *"Assistive on-device extraction only — does NOT verify government or RTO authenticity."* Final approval requires Admin inspection in `AdminActivity`.
- **Single-Instance Navigation**:
  - `MainActivity`, `RiderActivity`, and `AdminActivity` declare `android:launchMode="singleTop"` to route notification deep-links cleanly without spawning duplicate stacks.

---

## 2. Firebase Console Setup (Before Live Production Traffic)
1. **Authentication Providers & SMS Region Policy (`gen-lang-client-0818250755`)**:
   - Enable **Google** provider and verify the Web Client ID (`629202329249-a8mmo0pfultigodt1amkh9hviq4ms70b.apps.googleusercontent.com` / `default_web_client_id`).
   - Enable **Phone** provider in **Firebase Console → Authentication → Sign-in method**.
   - Under **Authentication → Settings → SMS region policy**, add **India (+91)** (and any other supported countries) to the **Allow** list — otherwise Firebase Auth rejects OTP requests with `17006 SMS unable to be sent until this region enabled by the app developer` (`ERROR_OPERATION_NOT_ALLOWED`).
   - *(Optional for emulator testing)* Add test phone numbers under **Authentication → Sign-in method → Phone → Phone numbers for testing**.
2. **App Signing Fingerprints (CRITICAL)**:
   - Add **both** debug and release **SHA-1** and **SHA-256** fingerprints in **Firebase Console → Project Settings → Your Android App (`com.aistudio.golocal.wbmxrq`)**, then download/update `app/google-services.json`:
     - Current AI Studio debug keystore SHA-1: `9B:83:74:DE:9F:14:C8:35:E3:52:06:2A:EB:E4:A9:9F:B0:95:DC:C8`
     - Current AI Studio debug keystore SHA-256: `72:B9:AD:4D:19:B2:EF:51:F9:B9:61:4E:EB:2C:E7:18:00:A0:DA:2F:74:62:36:AE:C8:63:82:46:64:C6:62:FB`
     - Play App Signing certificate SHA-1/SHA-256 (from Google Play Console → Setup → App signing).
   - Required for both Google Credential Manager ID token verification and Firebase Phone Auth Play Integrity / reCAPTCHA verification (missing SHA-1/SHA-256 triggers `Failed to get reCAPTCHA token... error trying to get your package certificate hash`).
3. **Cloud Storage Activation & Rules Deployment**:
   - Ensure Firebase Storage bucket `gen-lang-client-0818250755.firebasestorage.app` is initialized.
   - Deploy rules:
     ```bash
     firebase deploy --only firestore,storage
     ```
4. **Cloud Functions v2 Deployment (FCM Push Triggers)**:
   - Push triggers reside in `/functions/index.js` (`onBookingWritePush`, `onRideOfferCreatedPush`, `onBusBookingCreatedPush`, `onDriverVerificationPush`, `onNotificationDocCreatedPush`, `onDisputeCreatedPush`, `onEmergencyEventCreatedPush`).
   - Requires Firebase **Blaze** plan to deploy:
     ```bash
     cd functions && npm install
     firebase deploy --only functions
     ```
   - Automatically prunes invalid/unregistered FCM device tokens (`messaging/registration-token-not-registered`, `messaging/invalid-registration-token`) from `fcm_tokens`.

---

## 3. Private Bus Fare Governance
- All private bus fares uploaded in `bus_routes` start in `fareApprovalStatus = "pending_fare_approval"` and require `fareSource` (`government_gazette`, `rto_tariff`, or `operator_submitted`), `authorityRef`, and `effectiveDateIso`.
- Customers and seat locks (`bus_seat_locks`, `bus_bookings`) are blocked by both `BusFareEngine` and `firestore.rules` until an authorized Admin approves the tariff in **Admin Portal → Bus Gov**.

---

## 4. Real-Device Verification Matrix (Must Complete on Physical Hardware Before Production)
Because CI/build environments do not run physical cellular radios or Google Play Services FCM push delivery, perform these checks on physical Android devices (Android 13+ API 33+ and Android 10–12):
1. **Google Sign-In + Phone OTP Login & Account Linking**:
   - Sign in with Google on Customer & Rider apps.
   - Sign in with Phone OTP (`+91`) and verify SMS auto/manual code entry, 60s resend cooldown, and Profile phone-linking.
2. **Push Notifications (4 States)**:
   - Verify notification runtime permission prompt on Android 13+.
   - Confirm push notification delivery & deep-link routing (`golocal_bookings`, `golocal_driver_alerts`, `golocal_admin_alerts`, `golocal_sos_critical`, `golocal_bus_tickets`) when the app is:
     1. Foreground (open)
     2. Background (minimized)
     3. Killed / Force-closed
     4. Screen locked
3. **Multi-User Concurrent Seat Booking Test**:
   - Reserve the same seat simultaneously on two physical devices against the same approved `BusSchedule`; confirm one succeeds and the second receives a clean lock/already-booked error without double booking.
4. **Play Console Data Safety Form & Privacy Policy**:
   - Host your public HTTPS Privacy Policy page matching the URL configured in `strings.xml` (`privacy_policy_url`), and declare Account/Identity (Google/Phone), Approximate/Precise Location (during rides/dispatch), and Photos/Docs (Driver KYC voluntary upload, encrypted in transit, user-deletable).
