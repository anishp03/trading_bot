import assert from "node:assert/strict";
import test from "node:test";

import {
  chartCandlesForMonitorTimeline,
  displayCandlesForChart,
  liveBotControlState,
  mergeCurrentCandleIntoSeries,
  liveMonitorMatchesCacheKey,
  liveMonitorCacheKey,
  liveMonitorRequestKey,
  monitorLimitForTimeframe,
  normalizeMonitorTimeframe,
  mergeSeriesCandleVolume,
  resolveLivePatchVolume,
  shouldAppendLivePatchCandle,
  timeAxisLabelIndexes,
  visibleLiveEventDetailEntries,
} from "./futuresLiveChartUtils.js";

test("displayCandlesForChart keeps short live-only series visible instead of blanking the chart", () => {
  const liveOnlyCandles = [
    { time: "2026-06-03 23:25", close: 7541, live: true },
    { time: "2026-06-03 23:30", close: 7542, live: true },
    { time: "2026-06-03 23:35", close: 7543, live: true },
  ];

  assert.deepEqual(displayCandlesForChart(liveOnlyCandles, 24), liveOnlyCandles);
});

test("chartCandlesForMonitorTimeline preserves warmup and live candles across sessions", () => {
  const candles = [
    { time: "2026-06-18 15:58", close: 7564 },
    { time: "2026-06-18 15:59", close: 7563.5 },
    { time: "2026-06-19 09:30", close: 7562 },
    { time: "2026-06-19 09:31", close: 7562.5 },
  ];

  assert.deepEqual(chartCandlesForMonitorTimeline(candles), candles);
});

test("monitorLimitForTimeframe requests the full live graph timeline", () => {
  assert.equal(monitorLimitForTimeframe("1m"), 2000);
  assert.equal(monitorLimitForTimeframe("5m"), 2000);
  assert.equal(monitorLimitForTimeframe("30m"), 2000);
  assert.equal(monitorLimitForTimeframe("1h"), 2000);
  assert.equal(monitorLimitForTimeframe("4h"), 2000);
});

test("liveBotControlState does not expose a separate stop market feed state", () => {
  assert.deepEqual(
    liveBotControlState({ botStarted: false, feedRunning: true, busyAction: "" }),
    { active: false, label: "Start Live Bot" }
  );
});

test("liveBotControlState stops only the live bot while the bot is running", () => {
  assert.deepEqual(
    liveBotControlState({ botStarted: true, feedRunning: true, busyAction: "" }),
    { active: true, label: "Stop Live Bot" }
  );
});

test("visibleLiveEventDetailEntries hides stopped-bot market data reconciliation payloads", () => {
  const entry = {
    eventType: "BOT_STOPPED",
    details: {
      sessionId: 48,
      marketDataReconciliation: {
        success: true,
        level1CaptureMerge: {
          success: true,
          symbols: [
            { symbol: "MES", success: true, capturedRows: 1545 },
            { symbol: "MNQ", success: true, capturedRows: 1545 },
          ],
        },
        level2GapFill: {
          success: true,
          symbols: [
            { symbol: "MES", success: true, level1Rows: 109980, capturedRows: 1545 },
          ],
        },
      },
    },
  };

  assert.deepEqual(visibleLiveEventDetailEntries(entry), [["sessionId", 48]]);
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
  assert.equal(
    liveMonitorRequestKey("MES,MNQ,NQ", "4h"),
    "liveMonitor:4h:MES,MNQ,NQ"
  );
});

test("normalizeMonitorTimeframe preserves every supported chart timeframe", () => {
  assert.equal(normalizeMonitorTimeframe("1m"), "1m");
  assert.equal(normalizeMonitorTimeframe("5m"), "5m");
  assert.equal(normalizeMonitorTimeframe("30m"), "30m");
  assert.equal(normalizeMonitorTimeframe("1h"), "1h");
  assert.equal(normalizeMonitorTimeframe("4h"), "4h");
});

test("liveMonitorCacheKey separates cached chart data by timeframe and symbol universe", () => {
  assert.equal(
    liveMonitorCacheKey("MES,MNQ,NQ", "1h"),
    "liveMonitor:1h:MES,MNQ,NQ"
  );
  assert.notEqual(
    liveMonitorCacheKey("MES,MNQ,NQ", "1h"),
    liveMonitorCacheKey("MES", "1h")
  );
  assert.notEqual(
    liveMonitorCacheKey("MES,MNQ,NQ", "1h"),
    liveMonitorCacheKey("MES,MNQ,NQ", "5m")
  );
  assert.notEqual(
    liveMonitorCacheKey("MES,MNQ,NQ", "1h"),
    liveMonitorCacheKey("MES,MNQ,NQ", "4h")
  );
});

test("liveMonitorMatchesCacheKey rejects monitors from another timeframe or symbol universe", () => {
  const cacheKey = liveMonitorCacheKey("MES,MNQ,NQ", "1h");
  assert.equal(
    liveMonitorMatchesCacheKey({ timeframe: "1h", monitorCacheKey: cacheKey }, cacheKey, "1h"),
    true
  );
  assert.equal(
    liveMonitorMatchesCacheKey({ timeframe: "5m", monitorCacheKey: liveMonitorCacheKey("MES,MNQ,NQ", "5m") }, cacheKey, "1h"),
    false
  );
  assert.equal(
    liveMonitorMatchesCacheKey({ timeframe: "1h", monitorCacheKey: liveMonitorCacheKey("MES", "1h") }, cacheKey, "1h"),
    false
  );
  assert.equal(
    liveMonitorMatchesCacheKey({ timeframe: "4h", monitorCacheKey: liveMonitorCacheKey("MES,MNQ,NQ", "4h") }, cacheKey, "1h"),
    false
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

test("shouldAppendLivePatchCandle rejects stale session jumps from live marks", () => {
  const series = [
    { time: "2026-06-10 15:47", close: 28590 },
    { time: "2026-06-10 15:48", close: 28577.75 },
  ];
  const livePatch = { time: "2026-06-11 08:08", close: 28894.88, live: true };

  assert.equal(shouldAppendLivePatchCandle({ series, patch: livePatch, timeframe: "1m" }), false);
});

test("mergeCurrentCandleIntoSeries appends a healthy live mark after stale captured history", () => {
  const series = [
    { time: "2026-06-19 13:03", close: 30652.75, high: 30652.75, low: 30652.75 },
    { time: "2026-06-19 13:04", close: 30647, high: 30647, low: 30647 },
  ];
  const livePatch = {
    time: "2026-06-22 05:10",
    open: 30753.13,
    high: 30753.75,
    low: 30752,
    close: 30752.63,
    events: 37,
    live: true,
  };

  const blockedByDefault = mergeCurrentCandleIntoSeries(series, livePatch, "1m");
  assert.equal(blockedByDefault.length, 2);

  const merged = mergeCurrentCandleIntoSeries(series, livePatch, "1m", { allowSessionJump: true });
  assert.equal(merged.length, 3);
  assert.equal(merged[2].time, "2026-06-22 05:10");
  assert.equal(merged[2].close, 30752.63);
});

test("shouldAppendLivePatchCandle does not seed an empty monitor timeline from live marks", () => {
  const livePatch = { time: "2026-06-22 00:59", close: 7535.25, live: true };

  assert.equal(shouldAppendLivePatchCandle({ series: [], patch: livePatch, timeframe: "1m" }), false);
});

test("shouldAppendLivePatchCandle allows normal next live mark candles", () => {
  const series = [
    { time: "2026-06-11 09:30", close: 28890 },
    { time: "2026-06-11 09:31", close: 28892 },
  ];
  const livePatch = { time: "2026-06-11 09:32", close: 28894.88, live: true };

  assert.equal(shouldAppendLivePatchCandle({ series, patch: livePatch, timeframe: "1m" }), true);
});

test("timeAxisLabelIndexes shows true one-minute control instead of coarse ten-minute labels", () => {
  const candles = Array.from({ length: 24 }, (_, index) => ({
    time: `2026-06-22 05:${String(index).padStart(2, "0")}`,
  }));

  assert.deepEqual(timeAxisLabelIndexes(candles, "1m"), Array.from({ length: 24 }, (_, index) => index));
});

test("timeAxisLabelIndexes keeps higher timeframes readable without pretending they are 1m", () => {
  const candles = Array.from({ length: 48 }, (_, index) => ({
    time: `2026-06-22 ${String(Math.floor(index / 2)).padStart(2, "0")}:${index % 2 ? "30" : "00"}`,
  }));

  assert.deepEqual(timeAxisLabelIndexes(candles, "30m"), [0, 8, 16, 24, 32, 40, 47]);
});

test("shouldAppendLivePatchCandle uses 4h timeframe gap tolerance", () => {
  const series = [
    { time: "2026-06-11 08:00", close: 28890 },
    { time: "2026-06-11 12:00", close: 28892 },
  ];
  const nextFourHourPatch = { time: "2026-06-11 16:00", close: 28894.88, live: true };
  const stalePatch = { time: "2026-06-12 09:30", close: 28910, live: true };

  assert.equal(shouldAppendLivePatchCandle({ series, patch: nextFourHourPatch, timeframe: "4h" }), true);
  assert.equal(shouldAppendLivePatchCandle({ series, patch: stalePatch, timeframe: "4h" }), false);
});
