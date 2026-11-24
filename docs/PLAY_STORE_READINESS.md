# GoLocal by NR Next — Play Store Readiness & Firebase Release Checklist

This document summarizes the Android, Google Play Policy, Firebase backend, and real-device QA status for **GoLocal (`com.aistudio.golocal.wbmxrq`)** before submitting to Google Play Console.

---

## 1. Google Play Policy, Target SDK & Build Hardening
- **Target & Compile SDK**: Android API 36 (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 24`).
- **Release Optimization & Backup**:
  - `isMinifyEnabled = true`, `isShrinkResources = true` with R8/ProGuard keep rules covering `com.example.data.model.**`, `com.example.domain.**`, `com.example.data.map.*`, MapLibre, and Credential Manager (`app/proguard-rules.pro`).
  - `android:allowBackup="false"`, `cleartextTrafficPermitted="false"` (`network_security_config.xml`), and release `ENABLE_TEST_SANDBOX = false` by default (`app/build.gradle.kts`).
  - `AppCheckHelper` installs `PlayIntegrityAppCheckProviderFactory` in `release` and `DebugAppCheckProviderFactory` in `debug`.
- **Media Selection (Zero Storage Permissions)**:
  - Driver KYC, Operator Permit, and Bus Vehicle document uploads use `ActivityResultContracts.PickVisualMedia`.
  - No `READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, or `ACCESS_BACKGROUND_LOCATION` permissions are declared.
- **No Self-Update Installer Permission**:
  - `REQUEST_INSTALL_PACKAGES` is absent from `AndroidManifest.xml`; release update prompts open the HTTPS release page externally.
- **Account Deletion Compliance (In-App + Web)**:
  - **In-App**: **Profile → Delete Account & Data** requires typing `DELETE`, blocks active commercial rides, enforces recent login, records `support_requests` (`account_deletion`), deletes `emergency_contacts/{uid}` and `customer_profiles/{uid}`, takes `driver_profiles/{uid}`/`driver_directory/{uid}` offline, anonymizes `/users/{uid}`, and deletes the Firebase Auth credential (`user.delete()`).
  - **Web**: Hosted at `/privacy.html` and `/account-deletion.html` (referenced via `privacy_policy_url` and `account_deletion_url` in `strings.xml`).
- **Single-Instance & Background Push Compliance**:
  - `MainActivity`, `RiderActivity`, and `AdminActivity` use `android:launchMode="singleTop"`.
  - Commercial ride requests use data-only high-priority FCM (`45s` TTL, 2-wave `3km -> 6km` dispatch) with `RideRequestActivity` full-screen intent, looping tone, and `Accept`/`Decline` notification actions (`RideRequestActionReceiver`).

---

## 2. Firebase Console & Cloud Functions Setup (Owner Action Required)
1. **Deploy Hosting, Rules & Functions**:
   ```bash
   cd functions && npm install
   firebase deploy --only firestore:rules,database,storage,functions,hosting
   ```
2. **Firebase Secrets & Auth**:
   - Set `PAYMENT_WEBHOOK_SECRET` and `ADMIN_EMAILS` in Firebase Functions Secrets / `.env` (never hardcode keys or emails in source).
   - Verify **Phone** provider + **SMS region policy** allows **India (+91)**.
   - Add **Debug**, **Upload**, and **Play App Signing** SHA-1 & SHA-256 fingerprints in Firebase Console → Project Settings → Android App (`com.aistudio.golocal.wbmxrq`) and download updated `google-services.json`.
3. **Firebase App Check**:
   - Register the Android app with **Play Integrity** in **Firebase Console → App Check** (using your Play App Signing SHA-256).

---

## 3. Play Console Submission & App Packaging Decisions
- **Launcher & Package Note**:
  - The current single-module build ships `MainActivity` (`GoLocal`), `RiderActivity` (`GoLocal Rider`), and `AdminActivity` (`GoLocal Admin`) so all three Android roles work inside one APK, with strict Firebase RBAC gating on `AdminActivity`.
  - Before publishing to the public Play Store, confirm whether you want to keep the existing `applicationId` (`com.aistudio.golocal.wbmxrq`) or create separate Play Store consumer/partner listings, and consider removing `<category android:name="android.intent.category.LAUNCHER" />` (setting `android:exported="false"`) on `AdminActivity` in your public Play consumer build so general users only use Owner/Ops via `owner.html` / `operations.html`.
- **Upload Keystore**:
  - Export `KEYSTORE_PATH`, `STORE_PASSWORD`, and `KEY_PASSWORD` before running `gradle :app:bundleRelease`.
- **Data Safety & Privacy Policy**:
  - Deploy `/public/privacy.html` & `/public/account-deletion.html` via Firebase Hosting and enter both HTTPS URLs in Google Play Console → **App Content → Privacy Policy** & **Data Safety**.

---

## 4. Physical Device Sign-Off Matrix (Before Production Rollout)
Because CI/sandbox environments have no cellular radio, Play Services device attestation, or real Razorpay callbacks, verify on physical Android 13+ and Android 10–12 hardware:
1. Phone OTP SMS + Play Integrity App Check token verification.
2. Killed/locked-screen commercial ride request alert (data-only FCM -> `RideRequestActivity` full-screen intent + looping ringtone + `Accept`/`Decline` actions + 45s expiry).
3. Foreground GPS service live-tracking continuity (`RiderLocationForegroundService` + RTDB sync + OSRM reroute).
4. Razorpay/UPI webhook capture (`PAYMENT_WEBHOOK_SECRET` HMAC-SHA256) and GST receipt reconciliation.
5. In-app (`DELETE` confirmation) & Web (`/account-deletion.html`) account deletion end-to-end.
