const BARS_PER_TRADING_DAY = {
  "1m": 390,
  "5m": 78,
  "30m": 13,
  "1h": 7,
};

const RANGE_TRADING_DAYS = {
  "1D": 1,
  "5D": 5,
  "1M": 20,
  "3M": 60,
  YTD: 120,
};

const SHORT_MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const MAX_FORMAT_CACHE_SIZE = 2048;
const tickMarkCache = new Map();
const timeLabelCache = new Map();

export function rangeBarsForTimeframe(range, timeframe) {
  if (range === "ALL") return Number.POSITIVE_INFINITY;
  const tradingDays = RANGE_TRADING_DAYS[range] || RANGE_TRADING_DAYS["1D"];
  const barsPerDay = BARS_PER_TRADING_DAY[timeframe] || BARS_PER_TRADING_DAY["1m"];
  return Math.max(1, tradingDays * barsPerDay);
}

export function shouldApplyProgrammaticRange({
  candleCount,
  chartKey,
  previousChartKey,
  selectedRange,
  previousRange,
  rangeRevision = 0,
  previousRangeRevision = 0,
}) {
  if (!Number.isFinite(Number(candleCount)) || Number(candleCount) <= 0) return false;
  return !previousChartKey
    || chartKey !== previousChartKey
    || selectedRange !== previousRange
    || Number(rangeRevision) !== Number(previousRangeRevision);
}

export function shouldApplyChartSeriesSync({ incomingChartKey, activeChartKey }) {
  const incoming = String(incomingChartKey || "").trim().toUpperCase();
  const active = String(activeChartKey || "").trim().toUpperCase();
  return !incoming || !active || incoming === active;
}

export function visibleRangeForCandles(candles, range, timeframe) {
  if (!Array.isArray(candles) || candles.length === 0) return null;
  const bars = rangeBarsForTimeframe(range, timeframe);
  if (range === "ALL" || !Number.isFinite(bars) || candles.length < 2 || candles.length <= bars) {
    return { fitContent: true };
  }
  const end = candles.length - 1;
  const start = Math.max(0, end - bars + 1);
  return {
    from: candles[start].time,
    to: candles[end].time,
  };
}

export function volumeHistogramData(candles) {
  const source = Array.isArray(candles) ? candles : [];
  const stats = volumeDisplayStats(source);
  const cap = volumeDisplayCap(stats);
  const outlierDisplayValue = Math.max(stats.highTypical, stats.median * 2, 1);

  return source.map((candle) => {
    const volume = safeNumber(candle?.volume);
    return {
      time: candle.time,
      value: cap > 0 && volume > cap ? outlierDisplayValue : volume,
      color: Number(candle?.close) >= Number(candle?.open)
        ? "rgba(21, 200, 170, 0.28)"
        : "rgba(255, 77, 100, 0.3)",
    };
  });
}

export function formatChartTickMark(time) {
  const key = chartTimeCacheKey(time);
  return cachedFormat(tickMarkCache, key, () => {
    const date = chartTimeToDate(time);
    if (!date) return "";
    return formatClockWithPeriod(date);
  });
}

export function formatChartTimeLabel(time) {
  const key = chartTimeCacheKey(time);
  return cachedFormat(timeLabelCache, key, () => {
    const date = chartTimeToDate(time);
    if (!date) return "";
    const dateLabel = `${SHORT_MONTHS[date.getMonth()]} ${String(date.getDate()).padStart(2, "0")} ${String(date.getFullYear()).slice(-2)}`;
    return `${dateLabel} ${formatClockWithPeriod(date)}`;
  });
}

export function liveWorkspacePropsAreEqual(previousProps, nextProps) {
  return liveWorkspaceRenderSignature(previousProps) === liveWorkspaceRenderSignature(nextProps);
}

export function liveWorkspaceRenderSignature(props) {
  const symbol = String(props?.symbol || "").toUpperCase();
  const timeframe = String(props?.timeframe || "1m");
  const status = [
    Boolean(props?.botStarted),
    Boolean(props?.isTransitioning),
    Boolean(props?.warmupPending),
    Boolean(props?.backendOffline),
    Boolean(props?.marketIdle),
    props?.dataSource || "",
    Number(props?.capturedBars || 0),
    props?.graphReadiness?.ready === true ? "ready" : props?.graphReadiness?.message || "",
  ].join(":");
  return [
    symbol,
    timeframe,
    Number(props?.uiRevision || 0),
    status,
    symbolsSignature(props?.symbols),
    tradesSignature(props?.trades),
    props?.sidebarSignature || "",
  ].join("|");
}

export function chartSourceStatus(dataSource, capturedBars) {
  const source = String(dataSource || "").trim().toUpperCase();
  const capturedCount = Number(capturedBars || 0);
  if (source.includes("LIVE_CAPTURED_BARS") || capturedCount > 0) {
    return {
      label: "Captured bars",
      detail: capturedCount > 0 ? `${formatInteger(capturedCount)} captured candles` : "Captured minute bars",
      tone: "is-live",
    };
  }
  if (source.includes("LIVE_ONLY")) {
    return {
      label: "Source pending",
      detail: "Chart source pending",
      tone: "is-waiting",
    };
  }
  if (source.includes("SIGNALR")) {
    return {
      label: source.includes("WAITING") ? "Waiting" : "Live stream",
      detail: source.includes("WAITING") ? "Waiting for live candles" : "Realtime aggregate fallback",
      tone: source.includes("WAITING") ? "is-waiting" : "is-fallback",
    };
  }
  if (source.includes("WARMUP") || source.includes("HISTORY")) {
    return {
      label: "Warmup",
      detail: "History warmup candles",
      tone: "is-waiting",
    };
  }
  return {
    label: "Source pending",
    detail: "Chart source pending",
    tone: "is-waiting",
  };
}

export function chartSeriesSyncPlan({
  chartKey,
  previousChartKey,
  candleCount,
  previousCount,
  latestTime,
  previousLastTime,
  historicalSignature = "",
  previousHistoricalSignature = "",
}) {
  const nextCount = Number(candleCount || 0);
  const lastCount = Number(previousCount || 0);
  const sameChart = Boolean(previousChartKey) && chartKey === previousChartKey;
  if (nextCount <= 0) return sameChart && lastCount > 0 ? "ignore-empty" : "reset";
  if (!sameChart || lastCount <= 0) return "reset";
  const latest = Number(latestTime || 0);
  const previousLatest = Number(previousLastTime || 0);
  if (latest > 0 && previousLatest > 0 && latest < previousLatest) return "reset";
  const sameLatest = latest > 0 && previousLatest > 0 && latest === previousLatest;
  if (sameLatest && nextCount !== lastCount) return "reset";
  if (nextCount < lastCount) return "reset";
  if (
    latest > previousLatest
    && nextCount === lastCount
    && historicalSignature
    && previousHistoricalSignature
    && historicalSignature !== previousHistoricalSignature
  ) {
    return "reset";
  }
  if (
    sameLatest
    &&
    historicalSignature
    && previousHistoricalSignature
    && historicalSignature !== previousHistoricalSignature
  ) {
    return "reset";
  }
  return "update-tail";
}

function volumeDisplayStats(candles) {
  const volumes = (Array.isArray(candles) ? candles : [])
    .map((candle) => safeNumber(candle?.volume))
    .filter((value) => value > 0)
    .sort((a, b) => a - b);
  if (volumes.length === 0) return { count: 0, median: 0, highTypical: 0 };

  return {
    count: volumes.length,
    median: percentileFloor(volumes, 0.5),
    highTypical: percentileFloor(volumes, 0.9),
  };
}

function volumeDisplayCap(stats) {
  if (!stats.count) return 0;
  return Math.max(stats.highTypical, stats.median * 8, 1);
}

function percentileFloor(sortedValues, percentile) {
  if (!sortedValues.length) return 0;
  const index = Math.max(0, Math.min(sortedValues.length - 1, Math.floor((sortedValues.length - 1) * percentile)));
  return sortedValues[index];
}

function safeNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : 0;
}

function cachedFormat(cache, key, formatter) {
  if (cache.has(key)) return cache.get(key);
  const value = formatter();
  cache.set(key, value);
  if (cache.size > MAX_FORMAT_CACHE_SIZE) {
    cache.delete(cache.keys().next().value);
  }
  return value;
}

function chartTimeCacheKey(time) {
  if (typeof time === "number" || typeof time === "string") return String(time);
  if (time && typeof time === "object") return `${time.year || ""}-${time.month || ""}-${time.day || ""}`;
  return "";
}

function formatClockWithPeriod(date) {
  const hours24 = date.getHours();
  const hours12 = hours24 % 12 || 12;
  const minutes = String(date.getMinutes()).padStart(2, "0");
  return `${hours12}:${minutes} ${hours24 >= 12 ? "PM" : "AM"}`;
}

function chartTimeToDate(time) {
  if (typeof time === "number" && Number.isFinite(time)) {
    return new Date(time * 1000);
  }
  if (typeof time === "string") {
    const parsed = Date.parse(time);
    return Number.isFinite(parsed) ? new Date(parsed) : null;
  }
  if (time && typeof time === "object" && Number.isFinite(time.year) && Number.isFinite(time.month) && Number.isFinite(time.day)) {
    return new Date(time.year, time.month - 1, time.day);
  }
  return null;
}

function formatInteger(value) {
  return Number(value || 0).toLocaleString("en-US", { maximumFractionDigits: 0 });
}

function symbolsSignature(symbols) {
  return (Array.isArray(symbols) ? symbols : [])
    .map((symbol) => String(symbol || "").toUpperCase())
    .join(",");
}

function tradesSignature(trades) {
  return (Array.isArray(trades) ? trades : [])
    .map((trade) => [
      trade?.id,
      trade?.symbol,
      trade?.side,
      trade?.contracts,
      trade?.strategyCode,
      trade?.entryPrice,
      trade?.currentPrice,
      trade?.stopPrice,
      trade?.originalStopPrice,
      trade?.managedStopPrice,
      trade?.dtmStopManaged,
      trade?.targetPrice,
      trade?.originalTargetPrice,
      trade?.managedTargetPrice,
      trade?.dtmTargetManaged,
      trade?.unrealizedPnl,
      trade?.closed,
      stableText(trade?.dtmDetails),
    ].map((value) => String(value ?? "")).join(":"))
    .join(";");
}

function stableText(value) {
  if (value == null) return "";
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return "";
  }
}
