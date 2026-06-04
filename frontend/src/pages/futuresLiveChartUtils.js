export function displayCandlesForChart(candles) {
  return Array.isArray(candles) ? candles : [];
}

export function liveMonitorRequestKey(symbolsCsv, timeframe) {
  return `liveMonitor:${normalizeMonitorTimeframe(timeframe)}:${String(symbolsCsv || "").trim()}`;
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

function normalizeMonitorTimeframe(value) {
  if (value === "5m" || value === "30m" || value === "1h") return value;
  return "1m";
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
