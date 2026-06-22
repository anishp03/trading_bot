import { forwardRef, memo, useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState } from "react";
import {
  CandlestickSeries,
  ColorType,
  CrosshairMode,
  HistogramSeries,
  LineStyle,
  createChart,
  createSeriesMarkers,
} from "lightweight-charts";
import { EASTERN_TIME_LABEL, formatEstTime } from "../utils/time.js";
import {
  chartSourceStatus,
  chartSeriesSyncPlan,
  formatChartTickMark,
  formatChartTimeLabel,
  liveWorkspacePropsAreEqual,
  shouldApplyChartSeriesSync,
  shouldApplyProgrammaticRange,
  visibleRangeForCandles,
  volumeHistogramData,
} from "./liveTradingWorkspaceUtils.js";

const TIMEFRAME_OPTIONS = ["1m", "5m", "30m", "1h"];
const DEFAULT_VISIBLE_RANGE = "1D";

function LiveTradingWorkspaceComponent({
  botStarted,
  candles,
  isTransitioning,
  symbol,
  symbols,
  timeframe,
  onSymbolChange,
  onTimeframeChange,
  trades,
  lastRefreshAt,
  serverTime,
  lastRealtimeEventAt,
  feedStaleSeconds,
  dataSource,
  capturedBars,
  realtimeRunning = false,
  historyPolling = false,
  warmupPending,
  graphReadiness,
  backendOffline,
  marketIdle,
  onChartInteraction,
  sidebarBeforeTrades = null,
  uiRevision = 0,
  sidebarSignature = "",
}, ref) {
  const chartHostRef = useRef(null);
  const chartRef = useRef(null);
  const candleSeriesRef = useRef(null);
  const volumeSeriesRef = useRef(null);
  const markerApiRef = useRef(null);
  const priceLinesRef = useRef([]);
  const lastViewportRef = useRef({ chartKey: "", range: "", rangeRevision: 0 });
  const lastSeriesUpdateRef = useRef({ chartKey: "", count: 0, lastTime: null, historicalSignature: "" });
  const [selectedTradeId, setSelectedTradeId] = useState("");
  const [chartReady, setChartReady] = useState(false);
  void uiRevision;
  void sidebarSignature;

  const chartCandles = useMemo(() => normalizeCandles(candles), [candles]);
  const activeTrades = useMemo(() => normalizeTrades(trades), [trades]);
  const chartDataKey = `${String(symbol || "").toUpperCase()}|${timeframe || "1m"}`;
  const selectedTrade = activeTrades.find((trade) => trade.id === selectedTradeId)
    || activeTrades.find((trade) => !trade.closed)
    || activeTrades[activeTrades.length - 1]
    || null;
  const latestCandle = chartCandles[chartCandles.length - 1] || null;
  const latestPrice = Number(latestCandle?.close || selectedTrade?.currentPrice || 0);
  const previousClose = Number(chartCandles[chartCandles.length - 2]?.close || latestPrice || 0);
  const priceChange = latestPrice && previousClose ? latestPrice - previousClose : 0;
  const priceChangePct = previousClose ? (priceChange / previousClose) * 100 : 0;
  const chartStatus = readinessLabel({
    backendOffline,
    warmupPending,
    marketIdle,
    botStarted,
    chartCandles,
    graphReadiness,
  });
  const feedStatus = feedHealthLabel(feedStaleSeconds, lastRealtimeEventAt, serverTime);
  const sourceStatus = chartSourceStatus(dataSource, capturedBars, {
    backendOffline,
    botStarted,
    realtimeRunning,
    historyPolling,
    feedStaleSeconds,
  });
  const chartSnapshotWarning = sourceStatus.state === "stale" || sourceStatus.state === "offline";
  const visibleHover = latestCandle;

  const syncChartCandles = useCallback((nextCandles, options = {}) => {
    if (!candleSeriesRef.current || !volumeSeriesRef.current) return;
    const normalizedCandles = normalizeCandles(nextCandles);
    const nextChartDataKey = options.chartDataKey || chartDataKey;
    if (!shouldApplyChartSeriesSync({ incomingChartKey: nextChartDataKey, activeChartKey: chartDataKey })) {
      return;
    }
    const nextTimeframe = options.timeframe || timeframe;
    const previousViewport = lastViewportRef.current;
    const previousSeriesUpdate = lastSeriesUpdateRef.current;
    const latest = normalizedCandles[normalizedCandles.length - 1] || null;
    const historicalSignature = candleHistorySignature(normalizedCandles);
    const syncPlan = chartSeriesSyncPlan({
      chartKey: nextChartDataKey,
      previousChartKey: previousSeriesUpdate.chartKey,
      candleCount: normalizedCandles.length,
      previousCount: previousSeriesUpdate.count,
      latestTime: latest?.time,
      previousLastTime: previousSeriesUpdate.lastTime,
      historicalSignature,
      previousHistoricalSignature: previousSeriesUpdate.historicalSignature,
    });
    if (syncPlan === "ignore-empty") return;

    if (syncPlan === "reset") {
      candleSeriesRef.current.setData(normalizedCandles);
      volumeSeriesRef.current.setData(volumeHistogramData(normalizedCandles));
    } else if (normalizedCandles.length > 0) {
      const startIndex = firstTailUpdateIndex(normalizedCandles, previousSeriesUpdate.lastTime);
      const volumeData = volumeHistogramData(normalizedCandles);
      for (let index = startIndex; index < normalizedCandles.length; index += 1) {
        candleSeriesRef.current.update(normalizedCandles[index]);
        if (volumeData[index]) volumeSeriesRef.current.update(volumeData[index]);
      }
    }

    if (shouldApplyProgrammaticRange({
      candleCount: normalizedCandles.length,
      chartKey: nextChartDataKey,
      previousChartKey: previousViewport.chartKey,
      selectedRange: DEFAULT_VISIBLE_RANGE,
      previousRange: previousViewport.range,
      rangeRevision: 0,
      previousRangeRevision: previousViewport.rangeRevision,
    })) {
      applyRange(chartRef.current, normalizedCandles, DEFAULT_VISIBLE_RANGE, nextTimeframe);
    }

    lastViewportRef.current = { chartKey: nextChartDataKey, range: DEFAULT_VISIBLE_RANGE, rangeRevision: 0 };
    lastSeriesUpdateRef.current = {
      chartKey: nextChartDataKey,
      count: normalizedCandles.length,
      lastTime: latest?.time || null,
      historicalSignature,
    };
  }, [chartDataKey, timeframe]);

  useImperativeHandle(ref, () => ({
    syncCandles: syncChartCandles,
  }), [syncChartCandles]);

  useEffect(() => {
    if (!activeTrades.length) {
      setSelectedTradeId("");
      return;
    }
    if (selectedTradeId && activeTrades.some((trade) => trade.id === selectedTradeId)) return;
    const preferredTrade = activeTrades.find((trade) => !trade.closed) || activeTrades[activeTrades.length - 1];
    setSelectedTradeId(preferredTrade?.id || "");
  }, [activeTrades, selectedTradeId]);

  useEffect(() => {
    const host = chartHostRef.current;
    if (!host || chartRef.current) return undefined;

    const chart = createChart(host, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: "#020405" },
        textColor: "rgba(236, 242, 249, 0.78)",
        fontFamily: "Inter, ui-sans-serif, system-ui, sans-serif",
        fontSize: 12,
      },
      grid: {
        vertLines: { color: "rgba(255, 255, 255, 0.055)" },
        horzLines: { color: "rgba(255, 255, 255, 0.075)" },
      },
      crosshair: {
        mode: CrosshairMode.Normal,
        vertLine: {
          color: "rgba(255, 255, 255, 0.72)",
          labelBackgroundColor: "rgba(77, 85, 96, 0.92)",
          style: LineStyle.Dashed,
        },
        horzLine: {
          color: "rgba(255, 255, 255, 0.72)",
          labelBackgroundColor: "rgba(77, 85, 96, 0.92)",
          style: LineStyle.Dashed,
        },
      },
      rightPriceScale: {
        borderColor: "rgba(255, 255, 255, 0.12)",
        scaleMargins: { top: 0.08, bottom: 0.2 },
      },
      timeScale: {
        borderColor: "rgba(255, 255, 255, 0.12)",
        rightOffset: 12,
        barSpacing: 8,
        minBarSpacing: 3,
        timeVisible: true,
        secondsVisible: false,
        tickMarkFormatter: formatChartTickMark,
      },
      handleScroll: {
        horzTouchDrag: true,
        vertTouchDrag: false,
        mouseWheel: true,
        pressedMouseMove: true,
      },
      handleScale: {
        axisPressedMouseMove: true,
        mouseWheel: true,
        pinch: true,
      },
      localization: {
        priceFormatter: (price) => formatPrice(price),
        timeFormatter: formatChartTimeLabel,
      },
    });

    const candleSeries = chart.addSeries(CandlestickSeries, {
      upColor: "#15c8aa",
      downColor: "#ff4d64",
      borderUpColor: "#15c8aa",
      borderDownColor: "#ff4d64",
      wickUpColor: "#15c8aa",
      wickDownColor: "#ff4d64",
      priceLineColor: "rgba(21, 200, 170, 0.95)",
      lastValueVisible: true,
      priceLineVisible: true,
    });
    const volumeSeries = chart.addSeries(HistogramSeries, {
      priceFormat: { type: "volume" },
      priceScaleId: "",
      color: "rgba(125, 211, 252, 0.26)",
      lastValueVisible: false,
      priceLineVisible: false,
      baseLineVisible: false,
    });
    volumeSeries.applyOptions({
      lastValueVisible: false,
      priceLineVisible: false,
      baseLineVisible: false,
    });
    volumeSeries.priceScale().applyOptions({
      scaleMargins: { top: 0.82, bottom: 0.03 },
    });

    chartRef.current = chart;
    candleSeriesRef.current = candleSeries;
    volumeSeriesRef.current = volumeSeries;
    setChartReady(true);

    return () => {
      setChartReady(false);
      chart.remove();
      chartRef.current = null;
      candleSeriesRef.current = null;
      volumeSeriesRef.current = null;
      markerApiRef.current = null;
      priceLinesRef.current = [];
      lastSeriesUpdateRef.current = { chartKey: "", count: 0, lastTime: null, historicalSignature: "" };
    };
  }, []);

  useEffect(() => {
    syncChartCandles(chartCandles, { chartDataKey, timeframe });
  }, [chartCandles, chartDataKey, syncChartCandles, timeframe]);

  useEffect(() => {
    if (!candleSeriesRef.current) return;
    if (!markerApiRef.current) {
      markerApiRef.current = createSeriesMarkers(candleSeriesRef.current, tradeMarkers(activeTrades));
    } else {
      markerApiRef.current.setMarkers(tradeMarkers(activeTrades));
    }
  }, [activeTrades]);

  useEffect(() => {
    const series = candleSeriesRef.current;
    if (!series) return;
    priceLinesRef.current.forEach((line) => series.removePriceLine(line));
    priceLinesRef.current = chartCandles.length > 0 ? buildTradePriceLines(series, selectedTrade, latestPrice) : [];
  }, [chartCandles.length, chartDataKey, latestPrice, selectedTrade]);

  return (
    <div className={`live-workspace ${isTransitioning ? "is-transitioning" : ""}`}>
      <div className="live-workspace-topbar">
        <div className="live-workspace-identity">
          <span className="live-workspace-badge">{symbol}</span>
          <div>
            <strong>{symbol || "Chart"}</strong>
            <span>{timeframeLabel(timeframe)} candles · {EASTERN_TIME_LABEL}</span>
            <span className={`live-workspace-source-pill ${sourceStatus.tone}`}>{sourceStatus.label}</span>
          </div>
        </div>

        <div className="live-workspace-symbols" aria-label="Chart symbol">
          {(Array.isArray(symbols) ? symbols : []).map((candidate) => (
            <button
              type="button"
              key={candidate}
              className={String(candidate).toUpperCase() === String(symbol).toUpperCase() ? "active" : ""}
              onClick={() => onSymbolChange?.(candidate)}
            >
              {candidate}
            </button>
          ))}
        </div>

        <div className="live-workspace-timeframes" aria-label="Chart timeframe">
          {TIMEFRAME_OPTIONS.map((option) => (
            <button
              type="button"
              key={option}
              className={option === timeframe ? "active" : ""}
              onClick={() => onTimeframeChange?.(option)}
            >
              {option}
            </button>
          ))}
        </div>
      </div>

      <div className="live-workspace-main">
        <div className="live-workspace-chart-panel">
          <div className="live-workspace-readout">
            <div>
              <span className="live-workspace-muted">O</span> {formatPrice(visibleHover?.open)}
              <span className="live-workspace-muted"> H</span> {formatPrice(visibleHover?.high)}
              <span className="live-workspace-muted"> L</span> {formatPrice(visibleHover?.low)}
              <span className="live-workspace-muted"> C</span> {formatPrice(visibleHover?.close)}
              <span className={priceChange >= 0 ? "live-workspace-positive" : "live-workspace-negative"}>
                {" "}{formatSigned(priceChange)} ({formatSigned(priceChangePct)}%)
              </span>
            </div>
            <div className="live-workspace-feed-stack">
              <span>{feedStatus}</span>
              <span>{sourceStatus.detail}</span>
            </div>
          </div>

          <div
            className={chartSnapshotWarning ? "live-workspace-chart-frame has-runtime-warning" : "live-workspace-chart-frame"}
            onPointerDown={onChartInteraction}
            onPointerMove={onChartInteraction}
            onTouchMove={onChartInteraction}
            onWheel={onChartInteraction}
          >
            <div ref={chartHostRef} className="live-workspace-chart" />
            {chartSnapshotWarning && chartCandles.length > 0 && (
              <div className={`live-workspace-chart-state-banner ${sourceStatus.tone}`}>
                <strong>{sourceStatus.label}</strong>
                <span>{sourceStatus.detail}</span>
              </div>
            )}
            {(!chartReady || chartCandles.length === 0) && (
              <div className="live-workspace-empty">
                <strong>{chartStatus.title}</strong>
                <span>{chartStatus.copy}</span>
              </div>
            )}
            <div className="live-workspace-watermark">Trading Bot</div>
          </div>

          <div className="live-workspace-bottom">
            <div className="live-workspace-clock">
              <span>UI {formatEstTime(lastRefreshAt)}</span>
              <span>Server {formatEstTime(serverTime)}</span>
            </div>
          </div>
        </div>

        <TradeInspector
          latestPrice={latestPrice}
          selectedTrade={selectedTrade}
          sidebarBeforeTrades={sidebarBeforeTrades}
        />
      </div>

      <div className="live-workspace-mobile-sheet">
        <TradeInspector
          compact
          latestPrice={latestPrice}
          selectedTrade={selectedTrade}
        />
      </div>
    </div>
  );
}

function TradeInspector({ selectedTrade, latestPrice, compact = false, sidebarBeforeTrades = null }) {
  if (!selectedTrade) {
    return (
      <aside className={`live-workspace-inspector ${compact ? "is-compact" : ""}`}>
        {!compact && sidebarBeforeTrades}
        <div className="live-workspace-inspector-head">
          <span>Live Trades</span>
          <strong>No live trade on this symbol</strong>
        </div>
      </aside>
    );
  }

  const pnl = Number(selectedTrade.unrealizedPnl || 0);
  const dtmHistory = dtmHistoryItems(selectedTrade.dtmDetails);
  const stopPrice = Number(selectedTrade.managedStopPrice || selectedTrade.stopPrice || 0);
  const originalStopPrice = Number(selectedTrade.originalStopPrice || 0);
  const stopManaged = Boolean(selectedTrade.dtmStopManaged && stopPrice > 0);
  const targetPrice = Number(selectedTrade.managedTargetPrice || selectedTrade.targetPrice || 0);
  const originalTargetPrice = Number(selectedTrade.originalTargetPrice || 0);
  const targetManaged = Boolean(selectedTrade.dtmTargetManaged && targetPrice > 0);

  return (
    <aside className={`live-workspace-inspector ${compact ? "is-compact" : ""}`}>
      {!compact && sidebarBeforeTrades}
      <div className="live-workspace-inspector-head">
        <span>Selected Live Trade</span>
        <strong>{textValue(selectedTrade.symbol) || "Live position"}</strong>
      </div>
      <div className="live-workspace-level-grid">
        <Metric label="Side" value={selectedTrade.side || "--"} />
        <Metric label="Contracts" value={selectedTrade.contracts || 0} />
        <Metric label="Strategy" value={textValue(selectedTrade.strategyCode) || "Strategy"} />
        <Metric label="Live PnL" value={formatCurrency(pnl)} positive={pnl > 0} negative={pnl < 0} />
        <Metric label="Entry Price" value={formatPrice(selectedTrade.entryPrice)} />
        <Metric label="Current Mark Price" value={formatPrice(selectedTrade.currentPrice || latestPrice)} />
        <Metric label={stopManaged ? "DTM Stop" : "Stop Loss"} value={formatPrice(stopPrice)} negative />
        {stopManaged && originalStopPrice > 0 && originalStopPrice !== stopPrice && (
          <Metric label="Original Stop" value={formatPrice(originalStopPrice)} negative />
        )}
        <Metric label={targetManaged ? "DTM Target" : "Take Profit"} value={formatPrice(targetPrice)} positive />
        {targetManaged && originalTargetPrice > 0 && originalTargetPrice !== targetPrice && (
          <Metric label="Original Target" value={formatPrice(originalTargetPrice)} positive />
        )}
      </div>
      {dtmHistory.length > 0 && (
        <div className="live-workspace-dtm">
          <span>DTM Management History</span>
          {dtmHistory.map((item) => (
            <p key={item}>{item}</p>
          ))}
        </div>
      )}
    </aside>
  );
}

function Metric({ label, value, positive = false, negative = false }) {
  const className = positive ? "live-workspace-positive" : negative ? "live-workspace-negative" : "";
  return (
    <div>
      <span>{label}</span>
      <strong className={className}>{value}</strong>
    </div>
  );
}

function normalizeCandles(candles) {
  const byTime = new Map();
  (Array.isArray(candles) ? candles : []).forEach((candle) => {
    const time = toChartTime(candle?.time || candle?.barTime || candle?.timestamp);
    const close = Number(candle?.close || 0);
    if (!time || close <= 0) return;
    byTime.set(time, {
      time,
      open: Number(candle?.open || close),
      high: Number(candle?.high || close),
      low: Number(candle?.low || close),
      close,
      volume: Number(candle?.volume || 0),
    });
  });
  return Array.from(byTime.values()).sort((a, b) => Number(a.time) - Number(b.time));
}

function normalizeTrades(trades) {
  return (Array.isArray(trades) ? trades : [])
    .filter((trade) => Number(trade?.entryPrice || 0) > 0)
    .map((trade, index) => {
      const chartTime = toChartTime(trade.entryTime || trade.signalTime || trade.createdAt);
      const exitChartTime = toChartTime(trade.exitTime || trade.closedAt || trade.updatedAt || trade.createdAt);
      return {
        ...trade,
        id: String(trade.id || `${trade.symbol || "trade"}-${trade.entryTime || trade.createdAt || index}`),
        chartTime,
        exitChartTime,
      };
    })
    .filter((trade) => trade.chartTime)
    .sort((a, b) => Number(a.chartTime) - Number(b.chartTime));
}

function tradeMarkers(trades) {
  return trades.flatMap((trade) => {
    const markers = [{
      time: trade.chartTime,
      position: trade.side === "SHORT" ? "aboveBar" : "belowBar",
      color: trade.closed ? "#cbd5e1" : trade.side === "SHORT" ? "#ff4d64" : "#15c8aa",
      shape: trade.side === "SHORT" ? "arrowDown" : "arrowUp",
      text: `${trade.side === "SHORT" ? "S" : "B"} ${trade.contracts || ""}`.trim(),
    }];
    if (trade.closed && trade.exitChartTime) {
      markers.push({
        time: trade.exitChartTime,
        position: trade.side === "SHORT" ? "belowBar" : "aboveBar",
        color: Number(trade.unrealizedPnl || 0) >= 0 ? "#6ee7a8" : "#ff4d64",
        shape: "circle",
        text: `X ${formatCurrency(trade.unrealizedPnl)}`,
      });
    }
    return markers;
  }).sort((a, b) => Number(a.time) - Number(b.time));
}

function textValue(value) {
  if (value == null) return "";
  if (typeof value === "string") return value.trim();
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (value?.summary) return textValue(value.summary);
  if (value?.reason) return textValue(value.reason);
  if (value?.text) return textValue(value.text);
  if (value?.action) return textValue(value.action);
  try {
    const serialized = JSON.stringify(value);
    return serialized === "{}" ? "" : serialized;
  } catch {
    return "";
  }
}

function dtmHistoryItems(value) {
  const text = textValue(value);
  if (!text || isNoDtmText(text)) return [];
  return text.split(/\s+\|\s+/).map((item) => item.trim()).filter(Boolean);
}

function isNoDtmText(value) {
  const normalized = textValue(value).toLowerCase();
  return !normalized
    || normalized.includes("no dtm decisions")
    || normalized.includes("dtm_no_override")
    || normalized.includes("original bracket");
}

function buildTradePriceLines(series, trade, latestPrice) {
  const lines = [];
  if (!trade) return lines;
  const stopPrice = Number(trade.managedStopPrice || trade.stopPrice || 0);
  const targetPrice = Number(trade.managedTargetPrice || trade.targetPrice || 0);
  const stopTitle = trade.dtmStopManaged && stopPrice > 0 ? "DTM STOP" : "STOP";
  const targetTitle = trade.dtmTargetManaged && targetPrice > 0 ? "DTM TARGET" : "TARGET";
  const levels = [
    { price: trade.entryPrice, color: "#7dd3fc", title: "ENTRY", style: LineStyle.Solid },
    { price: stopPrice, color: "#ff4d64", title: stopTitle, style: LineStyle.Dashed },
    { price: targetPrice, color: "#6ee7a8", title: targetTitle, style: LineStyle.Dashed },
    { price: trade.currentPrice || latestPrice, color: "#f8fafc", title: "MARK", style: LineStyle.Dotted },
  ];
  levels.forEach((level) => {
    const price = Number(level.price || 0);
    if (price <= 0) return;
    lines.push(series.createPriceLine({
      price,
      color: level.color,
      lineWidth: level.title === "ENTRY" ? 2 : 1,
      lineStyle: level.style,
      axisLabelVisible: true,
      title: level.title,
    }));
  });
  return lines;
}

function applyRange(chart, candles, range, timeframe) {
  if (!chart || !Array.isArray(candles) || candles.length === 0) return;
  const visibleRange = visibleRangeForCandles(candles, range, timeframe);
  if (!visibleRange) return;
  if (visibleRange.fitContent) {
    chart.timeScale().fitContent();
    return;
  }
  chart.timeScale().setVisibleRange(visibleRange);
}

function firstTailUpdateIndex(candles, previousLastTime) {
  if (!Array.isArray(candles) || candles.length === 0) return 0;
  const lastTime = Number(previousLastTime || 0);
  if (lastTime <= 0) return Math.max(0, candles.length - 1);
  const index = candles.findIndex((candle) => Number(candle?.time || 0) >= lastTime);
  return index >= 0 ? index : Math.max(0, candles.length - 1);
}

function candleHistorySignature(candles) {
  const history = Array.isArray(candles) ? candles.slice(0, -1) : [];
  if (!history.length) return "";
  let hash = 2166136261;
  history.forEach((candle) => {
    const text = [
      candle?.time ?? "",
      candle?.open ?? "",
      candle?.high ?? "",
      candle?.low ?? "",
      candle?.close ?? "",
      candle?.volume ?? "",
    ].join(":");
    for (let index = 0; index < text.length; index += 1) {
      hash ^= text.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
  });
  const first = history[0] || {};
  const last = history[history.length - 1] || {};
  return `${history.length}:${first.time || ""}:${last.time || ""}:${hash >>> 0}`;
}

function toChartTime(value) {
  if (!value) return null;
  const clean = String(value).replace(" ", "T");
  const parsed = Date.parse(clean);
  if (Number.isNaN(parsed)) return null;
  return Math.floor(parsed / 1000);
}

function readinessLabel({ backendOffline, warmupPending, marketIdle, botStarted, chartCandles, graphReadiness }) {
  if (backendOffline) return { title: "Backend offline", copy: "Waiting for the dev backend to return market data." };
  if (warmupPending) return { title: "Building chart history", copy: graphReadiness?.message || "Warmup candles are loading." };
  if (!botStarted || marketIdle) return { title: "Live monitor paused", copy: "Start the bot to stream monitor candles into this workspace." };
  if (!chartCandles.length) return { title: "No candles yet", copy: "The chart will fill as soon as the monitor receives bars." };
  return { title: "Chart ready", copy: "" };
}

function feedHealthLabel(feedStaleSeconds, lastRealtimeEventAt, serverTime) {
  const stale = Number(feedStaleSeconds);
  if (Number.isFinite(stale) && stale >= 0) return `Feed ${formatDuration(stale)} · Last ${formatEstTime(lastRealtimeEventAt || serverTime)}`;
  return `Feed syncing · Last ${formatEstTime(lastRealtimeEventAt || serverTime)}`;
}

function timeframeLabel(value) {
  if (value === "5m") return "5 minute";
  if (value === "30m") return "30 minute";
  if (value === "1h") return "1 hour";
  return "1 minute";
}

function formatPrice(value) {
  const numeric = Number(value || 0);
  return numeric > 0 ? numeric.toLocaleString("en-US", { minimumFractionDigits: 1, maximumFractionDigits: 2 }) : "--";
}

function formatSigned(value) {
  const numeric = Number(value || 0);
  const sign = numeric > 0 ? "+" : "";
  return `${sign}${numeric.toFixed(2)}`;
}

function formatCurrency(value) {
  const numeric = Number(value || 0);
  const sign = numeric > 0 ? "+" : numeric < 0 ? "-" : "";
  return `${sign}$${Math.abs(numeric).toFixed(2)}`;
}

function formatDuration(seconds) {
  const numeric = Number(seconds);
  if (!Number.isFinite(numeric) || numeric < 0) return "syncing";
  if (numeric < 60) return `${Math.round(numeric)}s`;
  const minutes = Math.floor(numeric / 60);
  const remainingSeconds = Math.round(numeric % 60);
  if (minutes < 60) return remainingSeconds > 0 ? `${minutes}m ${remainingSeconds}s` : `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  return remainingMinutes > 0 ? `${hours}h ${remainingMinutes}m` : `${hours}h`;
}

const LiveTradingWorkspace = memo(forwardRef(LiveTradingWorkspaceComponent), liveWorkspacePropsAreEqual);

export default LiveTradingWorkspace;
