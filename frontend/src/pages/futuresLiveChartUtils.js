export function displayCandlesForChart(candles) {
  return Array.isArray(candles) ? candles : [];
}

export function chartCandlesForMonitorTimeline(candles) {
  return Array.isArray(candles) ? candles : [];
}

export function monitorLimitForTimeframe() {
  return 2000;
}

export function liveMonitorRequestKey(symbolsCsv, timeframe) {
  return `liveMonitor:${normalizeMonitorTimeframe(timeframe)}:${String(symbolsCsv || "").trim()}`;
}

export function liveMonitorCacheKey(symbolsCsv, timeframe) {
  return liveMonitorRequestKey(symbolsCsv, timeframe);
}

export function liveMonitorMatchesCacheKey(monitor, cacheKey, timeframe) {
  if (!monitor) return false;
  const normalizedTimeframe = normalizeMonitorTimeframe(timeframe);
  if (normalizeMonitorTimeframe(monitor.timeframe) !== normalizedTimeframe) return false;
  return !monitor.monitorCacheKey || monitor.monitorCacheKey === cacheKey;
}

export function mergeCurrentCandleIntoSeries(series, currentCandle, timeframe = "1m", options = {}) {
  const candles = Array.isArray(series) ? [...series] : [];
  const patch = normalizeCandle(currentCandle);
  if (!patch.time || positiveNumber(patch.close) <= 0) return candles;
  const lastIndex = candles.length - 1;
  if (lastIndex < 0) return candles;
  const last = normalizeCandle(candles[lastIndex]);
  const lastTime = chartTimeMs(last.time);
  const patchTime = chartTimeMs(patch.time);
  if (last.time === patch.time) {
    const resolvedVolume = resolveLivePatchVolume(candles, patch, last);
    candles[lastIndex] = {
      ...last,
      ...patch,
      high: Math.max(positiveNumber(last.high), positiveNumber(patch.high || patch.close)),
      low: Math.min(positiveNumber(last.low || last.close), positiveNumber(patch.low || patch.close)),
      volume: resolvedVolume,
      vwap: positiveNumber(last.vwap) || positiveNumber(patch.vwap),
      ema9: positiveNumber(last.ema9) || positiveNumber(patch.ema9),
      ema20: positiveNumber(last.ema20) || positiveNumber(patch.ema20),
      rsi14: positiveNumber(last.rsi14) || positiveNumber(patch.rsi14),
      live: true,
    };
    return candles;
  }
  if (
    patchTime
    && (!lastTime || patchTime > lastTime)
    && (options.allowSessionJump || shouldAppendLivePatchCandle({ series: candles, patch, timeframe }))
  ) {
    candles.push({
      ...patch,
      volume: resolveLivePatchVolume(candles, patch, null),
      live: true,
    });
  }
  return candles;
}

export function timeAxisLabelIndexes(candles, timeframe = "1m") {
  const count = Array.isArray(candles) ? candles.length : 0;
  if (count <= 0) return [];
  const normalized = normalizeMonitorTimeframe(timeframe);
  let step;
  if (normalized === "1m") {
    step = count <= 48 ? 1 : count <= 96 ? 2 : 5;
  } else if (normalized === "5m") {
    step = count <= 48 ? 3 : 6;
  } else {
    step = Math.max(1, Math.floor(count / 6));
  }
  const indexes = [];
  for (let index = 0; index < count; index += step) {
    indexes.push(index);
  }
  if (indexes[indexes.length - 1] !== count - 1) {
    indexes.push(count - 1);
  }
  return indexes;
}

export function liveBotControlState({ botStarted = false, busyAction = "" } = {}) {
  if (busyAction === "start") return { active: false, label: "Starting..." };
  if (busyAction === "stop") return { active: true, label: "Stopping..." };
  return botStarted
    ? { active: true, label: "Stop Live Bot" }
    : { active: false, label: "Start Live Bot" };
}

export function visibleLiveEventDetailEntries(entry) {
  const details = entry?.details && typeof entry.details === "object" && !Array.isArray(entry.details) ? entry.details : {};
  const hiddenKeys = new Set([
    "entryReason",
    "exitReason",
    "tradeReason",
    "dtmDetails",
    "marketDataReconciliation",
    "level1CaptureMerge",
    "level2GapFill",
  ]);
  const priority = [
    "symbols",
    "symbol",
    "logSource",
    "source",
    "sessionId",
    "strategyConfig",
    "riskConfig",
    "marketData",
    "strategy",
    "side",
    "contracts",
    "entry",
    "stop",
    "target",
    "orderId",
    "seconds",
    "downtime",
    "marketEventGap",
    "lastEvent",
    "gate",
    "profile",
    "account",
    "mode",
    "action",
    "status",
    "dtmAction",
    "entryReason",
    "exitReason",
    "exitPrice",
    "pnl",
    "reason",
  ];
  const seen = new Set();
  const ordered = [];
  priority.forEach((key) => {
    if (!hiddenKeys.has(key) && Object.prototype.hasOwnProperty.call(details, key)) {
      ordered.push([key, details[key]]);
      seen.add(key);
    }
  });
  Object.entries(details).forEach(([key, value]) => {
    if (!seen.has(key) && !hiddenKeys.has(key)) ordered.push([key, value]);
  });
  return ordered
    .filter(([, value]) => liveEventDetailValueVisible(value))
    .slice(0, 10);
}

export function liveMarksRequestKey(symbolsCsv, timeframe) {
  return `liveMarks:${normalizeMonitorTimeframe(timeframe)}:${String(symbolsCsv || "").trim()}`;
}

export function resolveLivePatchVolume(series, patch, existingCandle = null) {
  const patchVolume = positiveNumber(patch?.volume);
  const existingVolume = positiveNumber(existingCandle?.volume);
  if (patchVolume <= 0) return existingVolume;

  const stats = volumeStats(series);
  const threshold = liveVolumeOutlierThreshold(stats);
  if (stats.count >= 2 && patchVolume > threshold) {
    const eventCount = positiveNumber(patch?.events);
    if (eventCount > 0 && eventCount < threshold) return Math.max(existingVolume, eventCount);
    if (existingVolume > 0) return existingVolume;
    return Math.max(1, Math.round(stats.median || stats.highTypical || 1));
  }

  return Math.max(existingVolume, patchVolume);
}

export function mergeSeriesCandleVolume(existingCandle, incomingCandle) {
  const existingVolume = positiveNumber(existingCandle?.volume);
  const incomingVolume = positiveNumber(incomingCandle?.volume);
  if (incomingVolume <= 0) return existingVolume;
  if (existingVolume <= 0) return incomingVolume;

  const existingIsLivePatch = Boolean(existingCandle?.live);
  const incomingIsLivePatch = Boolean(incomingCandle?.live);
  if (existingIsLivePatch && !incomingIsLivePatch) return incomingVolume;
  if (!existingIsLivePatch && incomingIsLivePatch && incomingVolume > Math.max(existingVolume * 20, 5_000)) {
    return existingVolume;
  }
  return Math.max(existingVolume, incomingVolume);
}

export function shouldAppendLivePatchCandle({ series, patch, timeframe = "1m" } = {}) {
  const candles = Array.isArray(series) ? series : [];
  const patchTime = chartTimeMs(patch?.time || patch?.barTime || patch?.timestamp);
  if (!patchTime) return false;
  if (!candles.length) return false;
  const last = candles[candles.length - 1] || {};
  const lastTime = chartTimeMs(last.time || last.barTime || last.timestamp);
  if (!lastTime) return false;
  if (patchTime <= lastTime) return false;
  const maxGapMs = Math.max(timeframeMinutes(timeframe) * 60000 * 3, 120000);
  return patchTime - lastTime <= maxGapMs;
}

export function normalizeMonitorTimeframe(value) {
  if (value === "5m" || value === "30m" || value === "1h" || value === "4h") return value;
  return "1m";
}

function timeframeMinutes(value) {
  if (value === "5m") return 5;
  if (value === "30m") return 30;
  if (value === "1h") return 60;
  if (value === "4h") return 240;
  return 1;
}

function chartTimeMs(value) {
  if (!value) return 0;
  const clean = String(value).trim().replace(" ", "T");
  const parsed = Date.parse(clean);
  return Number.isNaN(parsed) ? 0 : parsed;
}

function normalizeCandle(candle) {
  return {
    ...candle,
    open: Number(candle?.open || candle?.close || 0),
    high: Number(candle?.high || candle?.close || 0),
    low: Number(candle?.low || candle?.close || 0),
    close: Number(candle?.close || 0),
    volume: Number(candle?.volume || 0),
    vwap: Number(candle?.vwap || 0),
    ema9: Number(candle?.ema9 || 0),
    ema20: Number(candle?.ema20 || 0),
    rsi14: Number(candle?.rsi14 || 0),
  };
}

function liveEventDetailValueVisible(value) {
  if (value === null || value === undefined) return false;
  if (typeof value === "number") return Number.isFinite(value) && value !== 0;
  if (typeof value === "boolean") return true;
  if (Array.isArray(value)) return value.length > 0 && value.every(isPrimitiveDetailValue);
  if (typeof value === "object") return false;
  return String(value).trim().length > 0;
}

function isPrimitiveDetailValue(value) {
  return value === null || ["string", "number", "boolean"].includes(typeof value);
}

function liveVolumeOutlierThreshold(stats) {
  if (!stats.count) return Number.POSITIVE_INFINITY;
  return Math.max(stats.highTypical * 20, stats.median * 30, 5_000);
}

function volumeStats(series) {
  const volumes = (Array.isArray(series) ? series : [])
    .map((candle) => positiveNumber(candle?.volume))
    .filter((volume) => volume > 0)
    .sort((a, b) => a - b);
  if (!volumes.length) return { count: 0, median: 0, highTypical: 0 };
  return {
    count: volumes.length,
    median: percentileFloor(volumes, 0.5),
    highTypical: percentileFloor(volumes, 0.9),
  };
}

function percentileFloor(sortedValues, percentile) {
  const index = Math.max(0, Math.min(sortedValues.length - 1, Math.floor((sortedValues.length - 1) * percentile)));
  return sortedValues[index] || 0;
}

function positiveNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : 0;
}
