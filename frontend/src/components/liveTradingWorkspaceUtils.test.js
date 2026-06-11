import assert from "node:assert/strict";
import test from "node:test";

import {
  chartSeriesSyncPlan,
  formatChartTickMark,
  formatChartTimeLabel,
  liveWorkspacePropsAreEqual,
  rangeBarsForTimeframe,
  shouldApplyChartSeriesSync,
  shouldApplyProgrammaticRange,
  volumeHistogramData,
} from "./liveTradingWorkspaceUtils.js";

test("rangeBarsForTimeframe scales visible ranges by candle timeframe", () => {
  assert.equal(rangeBarsForTimeframe("1D", "1m"), 390);
  assert.equal(rangeBarsForTimeframe("1D", "5m"), 78);
  assert.equal(rangeBarsForTimeframe("1D", "30m"), 13);
  assert.equal(rangeBarsForTimeframe("1D", "1h"), 7);
  assert.equal(rangeBarsForTimeframe("5D", "5m"), 390);
});

test("shouldApplyProgrammaticRange only moves the viewport for intentional chart/range changes", () => {
  assert.equal(
    shouldApplyProgrammaticRange({
      candleCount: 520,
      chartKey: "MES|1m",
      previousChartKey: "MES|1m",
      selectedRange: "1D",
      previousRange: "1D",
    }),
    false
  );

  assert.equal(
    shouldApplyProgrammaticRange({
      candleCount: 520,
      chartKey: "NQ|1m",
      previousChartKey: "MES|1m",
      selectedRange: "1D",
      previousRange: "1D",
    }),
    true
  );

  assert.equal(
    shouldApplyProgrammaticRange({
      candleCount: 520,
      chartKey: "NQ|1m",
      previousChartKey: "NQ|1m",
      selectedRange: "5D",
      previousRange: "1D",
    }),
    true
  );

  assert.equal(
    shouldApplyProgrammaticRange({
      candleCount: 520,
      chartKey: "NQ|1m",
      previousChartKey: "NQ|1m",
      selectedRange: "5D",
      previousRange: "5D",
      rangeRevision: 2,
      previousRangeRevision: 1,
    }),
    true
  );
});

test("shouldApplyChartSeriesSync rejects stale imperative updates from previous symbols", () => {
  assert.equal(
    shouldApplyChartSeriesSync({
      incomingChartKey: "MES|1m",
      activeChartKey: "NQ|1m",
    }),
    false
  );

  assert.equal(
    shouldApplyChartSeriesSync({
      incomingChartKey: "NQ|1m",
      activeChartKey: "NQ|1m",
    }),
    true
  );

  assert.equal(
    shouldApplyChartSeriesSync({
      incomingChartKey: "",
      activeChartKey: "NQ|1m",
    }),
    true
  );
});

test("volumeHistogramData caps runaway live volume without flattening history", () => {
  const data = volumeHistogramData([
    { time: 1, open: 100, close: 101, volume: 100 },
    { time: 2, open: 101, close: 102, volume: 110 },
    { time: 3, open: 102, close: 101, volume: 5_000_000 },
  ]);

  assert.equal(data[0].value, 100);
  assert.equal(data[1].value, 110);
  assert.ok(data[2].value < 5_000_000);
  assert.ok(data[2].value >= data[1].value);
  assert.ok(data[2].value <= 250);
});

test("chart time formatters include AM PM labels", () => {
  const timestamp = Math.floor(new Date(2026, 5, 4, 14, 47).getTime() / 1000);

  assert.equal(formatChartTickMark(timestamp), "2:47 PM");
  assert.equal(formatChartTimeLabel(timestamp), "Jun 04 26 2:47 PM");
  assert.equal(formatChartTickMark(timestamp), "2:47 PM");
  assert.equal(formatChartTimeLabel(timestamp), "Jun 04 26 2:47 PM");
});

test("workspace render comparator ignores hot candle ticks until the UI revision advances", () => {
  const previous = {
    symbol: "MES",
    timeframe: "1m",
    uiRevision: 7,
    candles: [{ time: 1, close: 100 }],
    symbols: ["MES", "NQ"],
    trades: [],
    botStarted: true,
  };
  const nextTick = {
    ...previous,
    candles: [{ time: 1, close: 100 }, { time: 2, close: 101 }],
    serverTime: "2026-06-04T04:00:00Z",
    lastRefreshAt: "2026-06-04T04:00:00Z",
  };

  assert.equal(liveWorkspacePropsAreEqual(previous, nextTick), true);
  assert.equal(liveWorkspacePropsAreEqual(previous, { ...nextTick, uiRevision: 8 }), false);
  assert.equal(liveWorkspacePropsAreEqual(previous, { ...nextTick, symbol: "NQ" }), false);
  assert.equal(liveWorkspacePropsAreEqual(previous, { ...nextTick, trades: [{ id: "t1", currentPrice: 101, stopPrice: 99 }] }), false);
});

test("chartSeriesSyncPlan does not clear same-chart live candles for transient empty payloads", () => {
  assert.equal(
    chartSeriesSyncPlan({
      chartKey: "MES|1m",
      previousChartKey: "MES|1m",
      candleCount: 0,
      previousCount: 80,
      latestTime: null,
      previousLastTime: 1000,
    }),
    "ignore-empty"
  );

  assert.equal(
    chartSeriesSyncPlan({
      chartKey: "NQ|1m",
      previousChartKey: "MES|1m",
      candleCount: 0,
      previousCount: 80,
      latestTime: null,
      previousLastTime: 1000,
    }),
    "reset"
  );
});

test("chartSeriesSyncPlan updates live tail instead of resetting when same-chart history jumps forward", () => {
  assert.equal(
    chartSeriesSyncPlan({
      chartKey: "MES|1m",
      previousChartKey: "MES|1m",
      candleCount: 96,
      previousCount: 80,
      latestTime: 1100,
      previousLastTime: 1000,
    }),
    "update-tail"
  );

  assert.equal(
    chartSeriesSyncPlan({
      chartKey: "MES|1m",
      previousChartKey: "MES|1m",
      candleCount: 96,
      previousCount: 80,
      latestTime: 900,
      previousLastTime: 1000,
    }),
    "reset"
  );
});

test("chartSeriesSyncPlan resets when a fuller backend snapshot repairs same-latest history", () => {
  assert.equal(
    chartSeriesSyncPlan({
      chartKey: "MGC|1m",
      previousChartKey: "MGC|1m",
      candleCount: 350,
      previousCount: 70,
      latestTime: 1781115300,
      previousLastTime: 1781115300,
    }),
    "reset"
  );

  assert.equal(
    chartSeriesSyncPlan({
      chartKey: "MGC|1m",
      previousChartKey: "MGC|1m",
      candleCount: 350,
      previousCount: 350,
      latestTime: 1781115300,
      previousLastTime: 1781115300,
      historicalSignature: "350:fixed",
      previousHistoricalSignature: "350:sparse",
    }),
    "reset"
  );

  assert.equal(
    chartSeriesSyncPlan({
      chartKey: "MGC|1m",
      previousChartKey: "MGC|1m",
      candleCount: 350,
      previousCount: 350,
      latestTime: 1781115300,
      previousLastTime: 1781115300,
      historicalSignature: "350:fixed",
      previousHistoricalSignature: "350:fixed",
    }),
    "update-tail"
  );

  assert.equal(
    chartSeriesSyncPlan({
      chartKey: "MGC|1m",
      previousChartKey: "MGC|1m",
      candleCount: 351,
      previousCount: 350,
      latestTime: 1781115360,
      previousLastTime: 1781115300,
      historicalSignature: "351:new-minute",
      previousHistoricalSignature: "350:fixed",
    }),
    "update-tail"
  );
});
