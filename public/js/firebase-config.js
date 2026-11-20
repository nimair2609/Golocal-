// GoLocal Multi-Platform Web Firebase Configuration & Phone-OTP Identity / RBAC
// Uses the existing Firebase project, named Firestore database, RTDB, and Storage bucket.
// Google Sign-In is disabled across all GoLocal surfaces; Phone OTP only.

import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  getAuth,
  onAuthStateChanged,
  RecaptchaVerifier,
  signInWithPhoneNumber,
  signOut
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";
import {
  getFirestore,
  doc,
  getDoc
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";
import {
  getDatabase
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-database.js";
import {
  getStorage
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-storage.js";
import { escapeHtml, normalizeIndianPhone } from "./golocal-domain.js";

export { escapeHtml, normalizeIndianPhone };

export const FIREBASE_CONFIG = {
  apiKey: "AIzaSyBcK_PdErt1N4JMF_nFFaKpnKsPrj_aSLk",
  authDomain: "gen-lang-client-0818250755.firebaseapp.com",
  databaseURL: "https://gen-lang-client-0818250755-default-rtdb.firebaseio.com",
  projectId: "gen-lang-client-0818250755",
  storageBucket: "gen-lang-client-0818250755.firebasestorage.app",
  messagingSenderId: "629202329249",
  appId: "1:629202329249:web:f2cfdd8b9872a90a461ae0"
};

export const FIRESTORE_DATABASE_ID = "ai-studio-android-golocal-e1a2a68b-450b-4d1b-817a-6a38c9c578bb";

export const app = initializeApp(FIREBASE_CONFIG);
export const auth = getAuth(app);
export const db = getFirestore(app, FIRESTORE_DATABASE_ID);
export const rtdb = getDatabase(app);
export const storage = getStorage(app);

export const PORTAL_ROLES = Object.freeze({
  OWNER_ADMIN: "OWNER_ADMIN",
  OPERATIONS_STAFF: "OPERATIONS_STAFF",
  NONE: "NONE"
});

/**
 * Resolves the signed-in user's server-verified Admin/Ops role from Firestore allowlists.
 * Client-supplied role overrides are never trusted.
 */
export async function resolvePortalRole(user) {
  if (!user || !user.uid) return PORTAL_ROLES.NONE;
  try {
    const adminSnap = await getDoc(doc(db, "admins", user.uid));
    if (adminSnap.exists()) {
      return PORTAL_ROLES.OWNER_ADMIN;
    }
  } catch (_) {
    // Expected for non-admins due to zero-trust Firestore rules.
  }

  try {
    const opsSnap = await getDoc(doc(db, "operations_staff", user.uid));
    if (opsSnap.exists() && opsSnap.data()?.isActive === true) {
      return PORTAL_ROLES.OPERATIONS_STAFF;
    }
  } catch (_) {
    // Expected for unauthorized users.
  }

  return PORTAL_ROLES.NONE;
}

/**
 * Resolves the signed-in user's full profile memberships for Customer/Rider web apps.
 */
export async function resolveAppIdentity(user) {
  const result = {
    uid: user?.uid || "",
    portalRole: PORTAL_ROLES.NONE,
    userAccount: null,
    driverProfile: null,
    sampleRiderProfile: null,
    isSampleAllowlisted: false
  };
  if (!user || !user.uid) return result;
  result.portalRole = await resolvePortalRole(user);

  const safeGet = async (colName, id) => {
    try {
      const snap = await getDoc(doc(db, colName, id));
      return snap.exists() ? { id: snap.id, ...snap.data() } : null;
    } catch (_) {
      return null;
    }
  };

  const [userAcc, drvProf, sampleProf] = await Promise.all([
    safeGet("users", user.uid),
    safeGet("driver_profiles", user.uid),
    safeGet("sample_riders", user.uid)
  ]);

  result.userAccount = userAcc;
  result.driverProfile = drvProf;
  result.sampleRiderProfile = sampleProf;

  if (user.phoneNumber) {
    const phoneSnap = await safeGet("sample_rider_allowlist", user.phoneNumber);
    result.isSampleAllowlisted = Boolean(phoneSnap && phoneSnap.isActive !== false);
  }
  return result;
}

export function createPhoneRecaptcha(containerId) {
  return new RecaptchaVerifier(auth, containerId, {
    size: "normal"
  });
}

export async function sendPhoneOtp(e164Phone, verifier) {
  return signInWithPhoneNumber(auth, e164Phone, verifier);
}

export async function logoutPortal() {
  await signOut(auth);
}

export function watchAuth(callback) {
  return onAuthStateChanged(auth, callback);
}

export async function generateOtpAndHash(bookingId) {
  const arr = new Uint32Array(1);
  crypto.getRandomValues(arr);
  const otpPlaintext = String(100000 + (arr[0] % 900000));
  const payload = new TextEncoder().encode(`${String(bookingId).trim()}:${otpPlaintext}`);
  const digest = await crypto.subtle.digest("SHA-256", payload);
  const otpHash = Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  return { otpPlaintext, otpHash };
}

export async function hashBookingOtp(bookingId, enteredOtp) {
  const payload = new TextEncoder().encode(`${String(bookingId).trim()}:${String(enteredOtp).trim()}`);
  const digest = await crypto.subtle.digest("SHA-256", payload);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export function formatEpochTime(tsSecondsOrMs) {
  if (!tsSecondsOrMs) return "—";
  const ms = tsSecondsOrMs > 1e12 ? tsSecondsOrMs : tsSecondsOrMs * 1000;
  try {
    return new Date(ms).toLocaleTimeString("en-IN", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit"
    });
  } catch (_) {
    return "—";
  }
}

export function formatEpochDateTime(ts) {
  if (!ts) return "—";
  const ms = typeof ts?.toMillis === "function"
    ? ts.toMillis()
    : ts?.seconds
      ? ts.seconds * 1000
      : Number(ts) > 1e12
        ? Number(ts)
        : Number(ts) * 1000;
  if (!ms || Number.isNaN(ms)) return "—";
  try {
    return new Date(ms).toLocaleString("en-IN", {
      day: "2-digit",
      month: "short",
      hour: "2-digit",
      minute: "2-digit"
    });
  } catch (_) {
    return "—";
  }
}

export function downloadCsvFile(filename, csvText) {
  const blob = new Blob([csvText], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
