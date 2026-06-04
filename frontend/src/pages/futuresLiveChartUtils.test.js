import assert from "node:assert/strict";
import test from "node:test";

import {
  displayCandlesForChart,
  liveMonitorRequestKey,
  mergeSeriesCandleVolume,
  resolveLivePatchVolume,
} from "./futuresLiveChartUtils.js";

test("displayCandlesForChart keeps short live-only series visible instead of blanking the chart", () => {
  const liveOnlyCandles = [
    { time: "2026-06-03 23:25", close: 7541, live: true },
    { time: "2026-06-03 23:30", close: 7542, live: true },
    { time: "2026-06-03 23:35", close: 7543, live: true },
  ];

  assert.deepEqual(displayCandlesForChart(liveOnlyCandles, 24), liveOnlyCandles);
});

test("liveMonitorRequestKey separates in-flight monitor requests by selected timeframe and symbols", () => {
  assert.equal(
    liveMonitorRequestKey("MES,MNQ,NQ", "1m"),
    "liveMonitor:1m:MES,MNQ,NQ"
  );
  assert.equal(
    liveMonitorRequestKey("MES,MNQ,NQ", "5m"),
    "liveMonitor:5m:MES,MNQ,NQ"
  );
});

test("resolveLivePatchVolume rejects cumulative live mark volume outliers", () => {
  const series = [
    { time: "2026-06-04 00:07", volume: 220 },
    { time: "2026-06-04 00:08", volume: 131 },
    { time: "2026-06-04 00:09", volume: 18 },
  ];
  const patch = {
    time: "2026-06-04 00:09",
    volume: 1_800_379,
    events: 143,
    live: true,
  };

  assert.equal(resolveLivePatchVolume(series, patch, series[2]), 143);
});

test("resolveLivePatchVolume keeps normal live mark bar volume", () => {
  const series = [
    { time: "2026-06-04 00:07", volume: 220 },
    { time: "2026-06-04 00:08", volume: 131 },
  ];
  const patch = {
    time: "2026-06-04 00:09",
    volume: 185,
    events: 90,
    live: true,
  };

  assert.equal(resolveLivePatchVolume(series, patch, null), 185);
});

test("mergeSeriesCandleVolume lets authoritative monitor volume replace stale live patch outliers", () => {
  const poisonedLiveCandle = { time: "2026-06-04 00:09", volume: 1_800_379, live: true };
  const monitorCandle = { time: "2026-06-04 00:09", volume: 180, live: false };

  assert.equal(mergeSeriesCandleVolume(poisonedLiveCandle, monitorCandle), 180);
});
