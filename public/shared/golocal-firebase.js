import { initializeApp } from "https://www.gstatic.com/firebasejs/11.0.2/firebase-app.js";
import {
  getAuth,
  onAuthStateChanged,
  RecaptchaVerifier,
  signInWithPhoneNumber,
  signOut,
} from "https://www.gstatic.com/firebasejs/11.0.2/firebase-auth.js";
import {
  getFirestore,
  doc,
  getDoc,
} from "https://www.gstatic.com/firebasejs/11.0.2/firebase-firestore.js";
import { getDatabase } from "https://www.gstatic.com/firebasejs/11.0.2/firebase-database.js";

export const BORAI_DEFAULT_CENTER = {
  lat: 21.816,
  lng: 87.3703,
  zoom: 13.5,
  label: "Borai (PIN 721457), Mohanpur, Paschim Medinipur",
};

export const FIRESTORE_DATABASE_ID =
  "ai-studio-android-golocal-e1a2a68b-450b-4d1b-817a-6a38c9c578bb";

const DEFAULT_MAP_SETTINGS = {
  osmTileTemplate:
    window.GOLOCAL_MAP_CONFIG?.osmTileTemplate ||
    "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
  osrmBaseUrl:
    window.GOLOCAL_MAP_CONFIG?.osrmBaseUrl || "https://router.project-osrm.org",
  nominatimBaseUrl:
    window.GOLOCAL_MAP_CONFIG?.nominatimBaseUrl ||
    "https://nominatim.openstreetmap.org",
};

// Existing GoLocal Firebase project configuration (same project as /app/google-services.json)
const firebaseConfig = {
  apiKey: window.GOLOCAL_FIREBASE_CONFIG?.apiKey || "AIzaSyBcK_PdErt1N4JMF_nFFaKpnKsPrj_aSLk",
  authDomain: "gen-lang-client-0818250755.firebaseapp.com",
  projectId: "gen-lang-client-0818250755",
  databaseURL: "https://gen-lang-client-0818250755-default-rtdb.firebaseio.com",
  storageBucket: "gen-lang-client-0818250755.firebasestorage.app",
  messagingSenderId: "629202329249",
  appId: "1:629202329249:web:f2cfdd8b9872a90a461ae0",
};

const app = initializeApp(firebaseConfig);
export const auth = getAuth(app);
export const db = getFirestore(app, FIRESTORE_DATABASE_ID);
export const rtdb = getDatabase(app);

export function getMapSettings() {
  return {
    osmTileTemplate:
      localStorage.getItem("golocal_osm_tile") || DEFAULT_MAP_SETTINGS.osmTileTemplate,
    osrmBaseUrl:
      localStorage.getItem("golocal_osrm_url") || DEFAULT_MAP_SETTINGS.osrmBaseUrl,
    nominatimBaseUrl:
      localStorage.getItem("golocal_nominatim_url") || DEFAULT_MAP_SETTINGS.nominatimBaseUrl,
  };
}

export function buildOsmRasterStyle(tileTemplate = getMapSettings().osmTileTemplate) {
  return {
    version: 8,
    sources: {
      "osm-tiles": {
        type: "raster",
        tiles: [tileTemplate],
        tileSize: 256,
        attribution: "© OpenStreetMap contributors",
      },
    },
    layers: [
      {
        id: "osm-base",
        type: "raster",
        source: "osm-tiles",
        minzoom: 0,
        maxzoom: 19,
      },
    ],
  };
}

export function createCirclePolygonCoordinates(centerLat, centerLng, radiusKm, steps = 48) {
  const coords = [];
  const earthRadiusKm = 6371.0;
  const latRad = (centerLat * Math.PI) / 180;
  const lngRad = (centerLng * Math.PI) / 180;
  const angularDistance = radiusKm / earthRadiusKm;

  for (let i = 0; i <= steps; i++) {
    const bearing = (2 * Math.PI * i) / steps;
    const pLat = Math.asin(
      Math.sin(latRad) * Math.cos(angularDistance) +
        Math.cos(latRad) * Math.sin(angularDistance) * Math.cos(bearing)
    );
    const pLng =
      lngRad +
      Math.atan2(
        Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(latRad),
        Math.cos(angularDistance) - Math.sin(latRad) * Math.sin(pLat)
      );
    coords.push([(pLng * 180) / Math.PI, (pLat * 180) / Math.PI]);
  }
  return [coords];
}

export function serviceAreasToGeoJson(areas) {
  const features = [];
  for (const area of areas) {
    let geometry = null;
    if (area.boundaryPolygonJson && area.boundaryPolygonJson.trim()) {
      try {
        const parsed = JSON.parse(area.boundaryPolygonJson);
        if (parsed.type === "Polygon" && Array.isArray(parsed.coordinates)) {
          geometry = parsed;
        } else if (Array.isArray(parsed) && parsed.length >= 3) {
          const ring = parsed.map((p) =>
            Array.isArray(p) ? [Number(p[0]), Number(p[1])] : [Number(p.lng), Number(p.lat)]
          );
          const first = ring[0];
          const last = ring[ring.length - 1];
          if (first[0] !== last[0] || first[1] !== last[1]) ring.push(first);
          geometry = { type: "Polygon", coordinates: [ring] };
        }
      } catch (_) {
        geometry = null;
      }
    }
    if (!geometry && typeof area.lat === "number" && typeof area.lng === "number") {
      geometry = {
        type: "Polygon",
        coordinates: createCirclePolygonCoordinates(
          area.lat,
          area.lng,
          Math.max(0.5, Number(area.radiusKm || 8.0))
        ),
      };
    }
    if (geometry) {
      features.push({
        type: "Feature",
        properties: {
          id: area.id || area.name,
          name: area.name,
          active: Boolean(area.isActive && area.serviceAvailable !== false),
        },
        geometry,
      });
    }
  }
  return { type: "FeatureCollection", features };
}

export function haversineKm(lat1, lng1, lat2, lng2) {
  const R = 6371.0;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLng = ((lng2 - lng1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) *
      Math.cos((lat2 * Math.PI) / 180) *
      Math.sin(dLng / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

export async function fetchOsrmRoute(fromLat, fromLng, toLat, toLng) {
  const { osrmBaseUrl } = getMapSettings();
  const cleanBase = osrmBaseUrl.replace(/\/+$/, "");
  const url = `${cleanBase}/route/v1/driving/${fromLng},${fromLat};${toLng},${toLat}?overview=full&geometries=geojson`;
  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    const route = data?.routes?.[0];
    if (!route?.geometry?.coordinates?.length) throw new Error("No route geometry");
    return {
      source: "osrm",
      distanceKm: Number((route.distance / 1000).toFixed(2)),
      durationMin: Math.max(1, Math.round(route.duration / 60)),
      geojson: {
        type: "Feature",
        geometry: route.geometry,
      },
    };
  } catch (_) {
    const straight = haversineKm(fromLat, fromLng, toLat, toLng);
    const roadApprox = Number((straight * 1.28).toFixed(2));
    return {
      source: "haversine_fallback",
      distanceKm: roadApprox,
      durationMin: Math.max(2, Math.round((roadApprox / 26) * 60)),
      geojson: {
        type: "Feature",
        geometry: {
          type: "LineString",
          coordinates: [
            [fromLng, fromLat],
            [toLng, toLat],
          ],
        },
      },
    };
  }
}

let lastNominatimMs = 0;
export async function searchNominatim(query) {
  const clean = (query || "").trim();
  if (clean.length < 3) return [];
  const waitMs = Math.max(0, 1100 - (Date.now() - lastNominatimMs));
  if (waitMs > 0) await new Promise((r) => setTimeout(r, waitMs));
  lastNominatimMs = Date.now();

  const { nominatimBaseUrl } = getMapSettings();
  const cleanBase = nominatimBaseUrl.replace(/\/+$/, "");
  const url = `${cleanBase}/search?format=jsonv2&addressdetails=1&limit=6&q=${encodeURIComponent(clean)}`;
  const res = await fetch(url, { headers: { Accept: "application/json" } });
  if (!res.ok) return [];
  const items = await res.json();
  return (items || []).map((item) => ({
    label: item.display_name,
    lat: Number(item.lat),
    lng: Number(item.lon),
  }));
}

export async function resolvePortalRole(user) {
  if (!user) return { isOwnerAdmin: false, isOperationsStaff: false };
  if (user.emailVerified && user.email === "renukadas083@gmail.com") {
    return { isOwnerAdmin: true, isOperationsStaff: false };
  }
  const [adminSnap, opsSnap] = await Promise.all([
    getDoc(doc(db, "admins", user.uid)).catch(() => null),
    getDoc(doc(db, "operations_staff", user.uid)).catch(() => null),
  ]);
  const isOwnerAdmin = Boolean(adminSnap && adminSnap.exists());
  const isOperationsStaff = Boolean(
    opsSnap && opsSnap.exists() && opsSnap.data()?.isActive === true
  );
  return { isOwnerAdmin, isOperationsStaff };
}

export function bindPhoneOtpLogin({
  formEl,
  phoneInputEl,
  otpInputEl,
  sendBtnEl,
  verifyBtnEl,
  recaptchaContainerId,
  statusEl,
  signOutBtnEl,
  onAuthorizedUser,
}) {
  let recaptcha = null;
  let confirmationResult = null;

  sendBtnEl.addEventListener("click", async () => {
    const raw = (phoneInputEl.value || "").trim();
    const phone = raw.startsWith("+") ? raw : `+91${raw.replace(/\D/g, "")}`;
    if (phone.length < 10) {
      statusEl.textContent = "Enter a valid phone number (e.g. +919876543210).";
      return;
    }
    try {
      sendBtnEl.disabled = true;
      if (!recaptcha) {
        recaptcha = new RecaptchaVerifier(auth, recaptchaContainerId, { size: "invisible" });
      }
      confirmationResult = await signInWithPhoneNumber(auth, phone, recaptcha);
      otpInputEl.parentElement.classList.remove("hidden");
      verifyBtnEl.classList.remove("hidden");
      statusEl.textContent = `OTP sent to ${phone}. Enter the 6-digit verification code.`;
    } catch (err) {
      statusEl.textContent = `OTP send failed: ${err.message}`;
    } finally {
      sendBtnEl.disabled = false;
    }
  });

  formEl.addEventListener("submit", async (e) => {
    e.preventDefault();
    if (!confirmationResult) return;
    try {
      verifyBtnEl.disabled = true;
      await confirmationResult.confirm((otpInputEl.value || "").trim());
    } catch (err) {
      statusEl.textContent = `Invalid OTP: ${err.message}`;
    } finally {
      verifyBtnEl.disabled = false;
    }
  });

  if (signOutBtnEl) {
    signOutBtnEl.addEventListener("click", () => signOut(auth));
  }

  onAuthStateChanged(auth, async (user) => {
    if (!user) {
      onAuthorizedUser(null, { isOwnerAdmin: false, isOperationsStaff: false });
      return;
    }
    const roles = await resolvePortalRole(user);
    onAuthorizedUser(user, roles);
  });
}
