// Pure GoLocal Domain Utilities (shared across Web apps and Node unit tests)

export const DEFAULT_FARE_CONFIGS = Object.freeze({
  toto: {
    vehicleCategory: "toto",
    version: 1,
    baseFareInr: 25,
    perKmRateInr: 11,
    minimumFareInr: 25,
    waitingChargePerMinInr: 1.5,
    cancellationFeeInr: 15,
    gstPercent: 5,
    commissionPercent: 10,
    allowDriverFareProposal: true,
    minBidFactor: 0.85,
    maxBidFactor: 1.25,
    isEnabled: true
  },
  auto: {
    vehicleCategory: "auto",
    version: 1,
    baseFareInr: 30,
    perKmRateInr: 13,
    minimumFareInr: 30,
    waitingChargePerMinInr: 2,
    cancellationFeeInr: 20,
    gstPercent: 5,
    commissionPercent: 10,
    allowDriverFareProposal: true,
    minBidFactor: 0.85,
    maxBidFactor: 1.25,
    isEnabled: true
  },
  bike: {
    vehicleCategory: "bike",
    version: 1,
    baseFareInr: 20,
    perKmRateInr: 8,
    minimumFareInr: 20,
    waitingChargePerMinInr: 1,
    cancellationFeeInr: 15,
    gstPercent: 5,
    commissionPercent: 12,
    allowDriverFareProposal: true,
    minBidFactor: 0.85,
    maxBidFactor: 1.2,
    isEnabled: true
  },
  cab: {
    vehicleCategory: "cab",
    version: 1,
    baseFareInr: 55,
    perKmRateInr: 16,
    minimumFareInr: 65,
    waitingChargePerMinInr: 2.5,
    cancellationFeeInr: 35,
    gstPercent: 5,
    commissionPercent: 12,
    allowDriverFareProposal: true,
    minBidFactor: 0.85,
    maxBidFactor: 1.25,
    isEnabled: true
  },
  outstation: {
    vehicleCategory: "outstation",
    version: 1,
    baseFareInr: 150,
    perKmRateInr: 18,
    minimumFareInr: 250,
    waitingChargePerMinInr: 3,
    cancellationFeeInr: 50,
    gstPercent: 5,
    commissionPercent: 12,
    allowDriverFareProposal: true,
    minBidFactor: 0.9,
    maxBidFactor: 1.25,
    isEnabled: true
  },
  scheduled: {
    vehicleCategory: "scheduled",
    version: 1,
    baseFareInr: 40,
    perKmRateInr: 14,
    minimumFareInr: 50,
    waitingChargePerMinInr: 2,
    cancellationFeeInr: 25,
    gstPercent: 5,
    commissionPercent: 10,
    allowDriverFareProposal: true,
    minBidFactor: 0.85,
    maxBidFactor: 1.25,
    isEnabled: true
  }
});

export function escapeHtml(str) {
  return String(str ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
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

export function maskPhone(phone) {
  const clean = String(phone || "").trim();
  if (clean.length <= 4) return clean || "—";
  return `${clean.slice(0, 3)}••••${clean.slice(-4)}`;
}

export function maskPlate(plate) {
  const clean = String(plate || "").trim().toUpperCase();
  if (clean.length <= 4) return clean || "—";
  return `WB••${clean.slice(-4)}`;
}

export function haversineKm(lat1, lng1, lat2, lng2) {
  const toRad = (deg) => (deg * Math.PI) / 180;
  const r = 6371;
  const dLat = toRad(Number(lat2) - Number(lat1));
  const dLng = toRad(Number(lng2) - Number(lng1));
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(Number(lat1))) * Math.cos(toRad(Number(lat2))) * Math.sin(dLng / 2) ** 2;
  return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

export function resolveFareConfig(category, fareConfigsMap = {}) {
  const key = String(category || "toto").toLowerCase();
  const base = DEFAULT_FARE_CONFIGS[key] || DEFAULT_FARE_CONFIGS.toto;
  const remote = fareConfigsMap?.[key];
  if (!remote) return { ...base };
  return {
    ...base,
    ...remote,
    vehicleCategory: key
  };
}

export function computeFareEstimate({
  category = "toto",
  distanceKm = 2,
  fareConfigsMap = {},
  isRoundTrip = false,
  promoDiscountPct = 0
}) {
  const cfg = resolveFareConfig(category, fareConfigsMap);
  const effectiveKm = Math.max(0.5, Number(distanceKm || 0)) * (isRoundTrip ? 1.85 : 1.0);
  const rawFare = Number(cfg.baseFareInr) + Math.max(0, effectiveKm - 1.0) * Number(cfg.perKmRateInr);
  const beforePromo = Math.max(Number(cfg.minimumFareInr || 0), Math.round(rawFare));
  const safePromoPct = Math.min(50, Math.max(0, Number(promoDiscountPct || 0)));
  const discounted = safePromoPct > 0
    ? Math.max(Number(cfg.minimumFareInr || 0), Math.round(beforePromo * (1 - safePromoPct / 100)))
    : beforePromo;

  const minFactor = Math.min(1.0, Math.max(0.6, Number(cfg.minBidFactor || 0.85)));
  const maxFactor = Math.max(1.0, Math.min(2.0, Number(cfg.maxBidFactor || 1.25)));
  const minPermittedFareInr = Math.max(Number(cfg.minimumFareInr || 0), Math.round(discounted * minFactor));
  const maxPermittedFareInr = Math.max(minPermittedFareInr, Math.round(discounted * maxFactor));

  return {
    vehicleCategory: cfg.vehicleCategory,
    fareConfigVersion: Number(cfg.version || 1),
    isEnabled: cfg.isEnabled !== false,
    allowDriverFareProposal: cfg.allowDriverFareProposal !== false,
    baseFareInr: Number(cfg.baseFareInr || 0),
    perKmRateInr: Number(cfg.perKmRateInr || 0),
    minimumFareInr: Number(cfg.minimumFareInr || 0),
    waitingChargePerMinInr: Number(cfg.waitingChargePerMinInr || 0),
    cancellationFeeInr: Number(cfg.cancellationFeeInr || 0),
    gstPercent: Number(cfg.gstPercent ?? 5),
    commissionPercent: Number(cfg.commissionPercent ?? 10),
    estimatedFareInr: discounted,
    minPermittedFareInr,
    maxPermittedFareInr
  };
}

export function computeSettlement({
  grossFareInr = 0,
  commissionPercent = 10,
  gstPercent = 5
}) {
  const gross = Math.max(0, Number(grossFareInr || 0));
  const commPct = Math.min(40, Math.max(0, Number(commissionPercent || 0)));
  const gstPct = Math.min(28, Math.max(0, Number(gstPercent || 0)));
  const gstInr = Math.round((gross * gstPct / 100) * 100) / 100;
  const commissionAmountInr = Math.round((gross * commPct / 100) * 100) / 100;
  const netDriverEarningsInr = Math.max(0, Math.round((gross - commissionAmountInr) * 100) / 100);
  return {
    grossFareInr: gross,
    gstInr,
    commissionAmountInr,
    netDriverEarningsInr
  };
}

export function buildCsvContent(headers, rows) {
  const escapeCell = (val) => {
    const str = String(val ?? "");
    if (/[",\n\r]/.test(str)) {
      return `"${str.replaceAll('"', '""')}"`;
    }
    return str;
  };
  const headerLine = headers.map(escapeCell).join(",");
  const rowLines = (rows || []).map((row) => row.map(escapeCell).join(","));
  return [headerLine, ...rowLines].join("\n");
}
