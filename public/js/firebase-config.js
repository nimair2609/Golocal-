// GoLocal Web Portal Firebase Configuration & Strict Phone-OTP RBAC
// Uses the existing Firebase project, named Firestore database, and RTDB instance.
// Google Sign-In is strictly disabled across all GoLocal surfaces; Phone OTP only.

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

export const PORTAL_ROLES = Object.freeze({
  OWNER_ADMIN: "OWNER_ADMIN",
  OPERATIONS_STAFF: "OPERATIONS_STAFF",
  NONE: "NONE"
});

/**
 * Resolves the signed-in user's server-verified role from Firestore allowlists.
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

export function normalizeIndianPhone(raw) {
  const digits = String(raw || "").replace(/[^\d+]/g, "").trim();
  if (digits.startsWith("+") && digits.length >= 11 && digits.length <= 16) {
    return digits;
  }
  const onlyDigits = digits.replace(/\D/g, "");
  if (onlyDigits.length === 10) {
    return `+91${onlyDigits}`;
  }
  if (onlyDigits.length === 12 && onlyDigits.startsWith("91")) {
    return `+${onlyDigits}`;
  }
  return "";
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

export function escapeHtml(str) {
  return String(str ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
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
