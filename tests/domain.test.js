import test from "node:test";
import assert from "node:assert/strict";
import {
  DEFAULT_FARE_CONFIGS,
  escapeHtml,
  normalizeIndianPhone,
  maskPhone,
  maskPlate,
  haversineKm,
  resolveFareConfig,
  computeFareEstimate,
  computeSettlement,
  buildCsvContent
} from "../public/js/golocal-domain.js";

test("normalizeIndianPhone handles 10-digit, 91-prefixed, E.164, and invalid inputs", () => {
  assert.equal(normalizeIndianPhone("9876543210"), "+919876543210");
  assert.equal(normalizeIndianPhone("919876543210"), "+919876543210");
  assert.equal(normalizeIndianPhone("+91 98765-43210"), "+919876543210");
  assert.equal(normalizeIndianPhone("12345"), "");
});

test("escapeHtml escapes dangerous HTML characters to prevent XSS", () => {
  const dirty = `<script>alert("xss")</script> & 'quote'`;
  assert.equal(
    escapeHtml(dirty),
    "&lt;script&gt;alert(&quot;xss&quot;)&lt;/script&gt; &amp; &#39;quote&#39;"
  );
});

test("maskPhone and maskPlate mask sensitive identifiers", () => {
  assert.equal(maskPhone("+919876543210"), "+91••••3210");
  assert.equal(maskPlate("wb34ab1234"), "WB••1234");
});

test("haversineKm calculates approximate Borai to Mohanpur distance", () => {
  const km = haversineKm(21.8160, 87.3703, 21.8442, 87.4190);
  assert.ok(km > 5.0 && km < 7.0, `Expected 5-7 km, got ${km}`);
});

test("computeFareEstimate respects Admin remote fare_configs, round-trip, promo, and bid bounds", () => {
  const remoteMap = {
    toto: {
      ...DEFAULT_FARE_CONFIGS.toto,
      version: 3,
      baseFareInr: 30,
      perKmRateInr: 12,
      minimumFareInr: 30,
      minBidFactor: 0.9,
      maxBidFactor: 1.2
    }
  };
  const q1 = computeFareEstimate({ category: "toto", distanceKm: 6, fareConfigsMap: remoteMap });
  assert.equal(q1.fareConfigVersion, 3);
  // 30 + (6 - 1) * 12 = 90
  assert.equal(q1.estimatedFareInr, 90);
  assert.equal(q1.minPermittedFareInr, 81);
  assert.equal(q1.maxPermittedFareInr, 108);

  const qPromo = computeFareEstimate({ category: "toto", distanceKm: 6, fareConfigsMap: remoteMap, promoDiscountPct: 10 });
  assert.equal(qPromo.estimatedFareInr, 81);

  const qRound = computeFareEstimate({ category: "toto", distanceKm: 6, fareConfigsMap: remoteMap, isRoundTrip: true });
  assert.ok(qRound.estimatedFareInr > q1.estimatedFareInr);
  assert.ok(resolveFareConfig("unknown_cat", remoteMap).vehicleCategory === "toto");
});

test("computeSettlement calculates GST, commission, and net driver earnings accurately", () => {
  const s = computeSettlement({ grossFareInr: 250, commissionPercent: 12, gstPercent: 5 });
  assert.equal(s.grossFareInr, 250);
  assert.equal(s.gstInr, 12.5);
  assert.equal(s.commissionAmountInr, 30);
  assert.equal(s.netDriverEarningsInr, 220);
});

test("buildCsvContent properly quotes commas, quotes, and newlines", () => {
  const csv = buildCsvContent(["ID", "Note"], [["b1", 'He said "Hello", ok\nnext']]);
  assert.equal(csv, 'ID,Note\nb1,"He said ""Hello"", ok\nnext"');
});
