import TradeMarketChart from "./TradeMarketChart.jsx";
import { formatEstTime } from "../utils/time.js";

const TRADE_CHART_TIMEFRAMES = [
  { value: "1Min", label: "1m" },
  { value: "5Min", label: "5m" },
  { value: "30Min", label: "30m" },
  { value: "1Hour", label: "1h" },
];

export default function BacktestTradeReviewModal({
  trade,
  chartData,
  timeframe,
  loading,
  error,
  onTimeframeChange,
  onClose,
}) {
  const detail = chartData?.trade || trade || {};
  const metrics = chartData?.metrics || {};
  const keyPoints = Array.isArray(chartData?.keyPoints) ? chartData.keyPoints : [];
  const strategyLabel = detail.strategyName || detail.strategyCode || trade?.strategyName || trade?.strategyCode || "--";
  const metricRows = [
    ["Symbol", detail.symbol || "--"],
    ["Strategy", strategyLabel],
    ["Side", detail.side || "--"],
    ["Quantity", formatNumber(detail.qty)],
    ["Entry Time", formatEstTime(detail.entryTime || detail.time || "--")],
    ["Exit Time", formatEstTime(detail.exitTime || detail.closedAt || "--")],
    ["Entry Price", formatCurrency(detail.entryPrice ?? detail.entry)],
    ["Exit Price", formatCurrency(detail.exitPrice ?? detail.exit)],
    ["P/L", formatSignedCurrency(detail.pnl), Number(detail.pnl || 0)],
    ["Return", `${formatNumber(detail.returnPct)}%`, Number(detail.returnPct || 0)],
    ["Duration", `${formatNumber(detail.durationMinutes, 0)} min`],
    ["Bars Held", formatNumber(metrics.barsHeld, 0)],
    ["Trade High", formatCurrency(metrics.tradeHigh)],
    ["Trade Low", formatCurrency(metrics.tradeLow)],
    ["Session High", formatCurrency(metrics.sessionHigh)],
    ["Session Low", formatCurrency(metrics.sessionLow)],
    ["MFE", `${formatNumber(metrics.mfePct)}%`, Number(metrics.mfePct || 0)],
    ["MAE", `${formatNumber(metrics.maePct)}%`, Number(metrics.maePct || 0)],
  ];

  return (
    <div className="app-modal-backdrop" onClick={onClose}>
      <div className="app-modal-card app-trade-modal" role="dialog" aria-modal="true" onClick={(event) => event.stopPropagation()}>
        <div className="d-flex align-items-start justify-content-between gap-2 mb-3">
          <div>
            <div className="fw-bold">Trade Review</div>
            <div className="app-muted app-kicker">{`Trade #${detail.id || trade?.id || "--"} | ${detail.symbol || trade?.symbol || "--"} | ${strategyLabel}`}</div>
          </div>
          <button type="button" className="app-btn px-3" onClick={onClose}>
            Close
          </button>
        </div>

        <div className="app-detail-grid">
          {metricRows.map(([label, value, accent]) => (
            <div key={label} className="app-subpanel">
              <div className="app-label">{label}</div>
              <div className={metricValueClass(accent)}>{value || "--"}</div>
            </div>
          ))}
        </div>

        {(keyPoints.length > 0 || detail.tradeNotes) && (
          <div className="app-subpanel mt-3">
            <div className="fw-bold app-kicker">Signal Details</div>
            {keyPoints.length > 0 && (
              <div className="app-keypoint-grid mt-2">
                {keyPoints.map((point) => (
                  <div key={`${point.label}-${point.value}`} className="app-data-chip">
                    <span>{point.label}</span>
                    <strong>{point.value}</strong>
                  </div>
                ))}
              </div>
            )}
            {detail.tradeNotes && <div className="app-trade-notes mt-3">{detail.tradeNotes}</div>}
          </div>
        )}

        <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap mt-3">
          <div className="fw-bold app-kicker">Trade Snapshot</div>
          <div className="app-timeframe-row">
            {TRADE_CHART_TIMEFRAMES.map((option) => (
              <button
                key={option.value}
                type="button"
                className={timeframe === option.value ? "app-filter-btn active" : "app-filter-btn"}
                onClick={() => onTimeframeChange(option.value)}
                disabled={loading}
              >
                {option.label}
              </button>
            ))}
          </div>
        </div>

        {error && <div className="app-error mt-3">{error}</div>}
        {loading && <div className="app-chart-empty">Loading trade chart...</div>}
        {!loading && !error && (
          <TradeMarketChart
            annotations={chartData?.annotations || []}
            candles={chartData?.candles || []}
            emptyMessage="No cached Alpaca candles were found for this trade date."
            entryTime={detail.entryTime || detail.time}
            exitTime={detail.exitTime || detail.closedAt}
            side={detail.side || trade?.side}
            symbol={detail.symbol || trade?.symbol || "--"}
            timeframe={timeframe}
            zones={chartData?.zones || []}
          />
        )}
      </div>
    </div>
  );
}

function metricValueClass(accent) {
  if (Number(accent || 0) > 0) return "fw-bold mt-1 app-pnl-pos";
  if (Number(accent || 0) < 0) return "fw-bold mt-1 app-pnl-neg";
  return "fw-bold mt-1";
}

function formatNumber(value, fractionDigits = 2) {
  if (value == null || value === "") return "--";
  const amount = Number(value);
  if (!Number.isFinite(amount)) return "--";
  return new Intl.NumberFormat("en-US", {
    minimumFractionDigits: amount % 1 === 0 ? 0 : Math.min(2, fractionDigits),
    maximumFractionDigits: fractionDigits,
  }).format(amount);
}

function formatCurrency(value) {
  if (value == null || value === "") return "--";
  const amount = Number(value);
  if (!Number.isFinite(amount)) return "--";
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

function formatSignedCurrency(value) {
  if (value == null || value === "") return "--";
  const amount = Number(value || 0);
  return `${amount > 0 ? "+" : ""}${formatCurrency(amount)}`;
}
