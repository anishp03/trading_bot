import assert from "node:assert/strict";
import test from "node:test";

import { tradeAnalysisPriceDomain } from "./tradeAnalysisUtils.js";

test("tradeAnalysisPriceDomain keeps low-priced futures trades readable", () => {
  const series = [
    { high: 90.2, low: 90.01, close: 90.1, vwap: 90.08, ema20: 90.06, ema50: 90.04 },
    { high: 90.18, low: 89.99, close: 90.03, vwap: 90.07, ema20: 90.05, ema50: 90.04 },
    { high: 90.22, low: 90.02, close: 90.18, vwap: 90.09, ema20: 90.06, ema50: 90.05 },
  ];
  const annotations = [
    { type: "priceLine", label: "Stop", price: 89.99 },
    { type: "priceLine", label: "Target", price: 90.55 },
  ];

  const domain = tradeAnalysisPriceDomain({
    series,
    annotations,
    tradePrices: [90.18, 90.01],
  });

  assert.ok(domain.min < 89.99);
  assert.ok(domain.max > 90.55);
  assert.ok(domain.max - domain.min < 1.5);
});

test("tradeAnalysisPriceDomain still gives higher-priced futures enough room", () => {
  const domain = tradeAnalysisPriceDomain({
    series: [
      { high: 28840, low: 28795, close: 28820, vwap: 28818, ema20: 28805, ema50: 28798 },
      { high: 28862, low: 28812, close: 28855, vwap: 28826, ema20: 28814, ema50: 28802 },
    ],
    annotations: [
      { type: "priceLine", label: "Stop", price: 28772 },
      { type: "priceLine", label: "Target", price: 28920 },
    ],
    tradePrices: [28830, 28870],
  });

  assert.ok(domain.min < 28772);
  assert.ok(domain.max > 28920);
  assert.ok(domain.max - domain.min > 150);
});
