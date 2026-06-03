import { useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import { apiFetch } from "../utils/api.js";
import { formatEstTime } from "../utils/time.js";

export default function TradeAnalysisModal({ trade, source = "trade", onClose }) {
  const [analysis, setAnalysis] = useState(null);
  const [status, setStatus] = useState("idle");
  const [error, setError] = useState("");

  useEffect(() => {
    if (!trade) return undefined;
    let cancelled = false;
    setStatus("loading");
    setError("");
    setAnalysis(null);

    const params = tradeAnalysisParams(trade);
    apiFetch(`/api/futures/trade-analysis?${params.toString()}`)
      .then(async (response) => {
        const payload = await response.json().catch(() => ({}));
        if (!response.ok || payload?.success === false) {
          throw new Error(payload?.message || "Failed to load trade analysis.");
        }
        if (!cancelled) {
          setAnalysis(payload);
          setStatus("ready");
        }
      })
      .catch((nextError) => {
        if (!cancelled) {
          setError(nextError.message || "Failed to load trade analysis.");
          setStatus("error");
        }
      });

    return () => {
      cancelled = true;
    };
  }, [trade]);

  useEffect(() => {
    if (!trade) return undefined;
    const onKeyDown = (event) => {
      if (event.key === "Escape") onClose?.();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose, trade]);

  const metricTiles = useMemo(() => tradeMetricTiles(trade, analysis), [analysis, trade]);
  const detailSections = useMemo(() => tradeDetailSections(trade), [trade]);

  if (!trade) return null;

  return createPortal(
    <div className="app-modal-backdrop trade-analysis-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose?.();
    }}>
      <section className="app-modal-card app-trade-analysis-modal" role="dialog" aria-modal="true" aria-label="Trade analysis">
        <header className="trade-analysis-header">
          <div>
            <span className="app-label">{source === "live" ? "Live Bot All Trades" : "Backtest Trade"}</span>
            <h3>{tradeSymbol(trade)} {displayStrategy(trade)}</h3>
          </div>
          <button type="button" className="app-btn app-btn-small px-3" onClick={onClose}>Close</button>
        </header>

        <div className="trade-analysis-metric-grid">
          {metricTiles.map((tile) => (
            <div className="trade-analysis-metric" key={tile.label}>
              <span>{tile.label}</span>
              <strong className={tile.accent > 0 ? "app-pnl-pos" : tile.accent < 0 ? "app-pnl-neg" : ""}>{tile.value}</strong>
            </div>
          ))}
        </div>

        {status === "loading" && <div className="app-empty">Loading trade chart...</div>}
        {status === "error" && <div className="app-error">{error}</div>}
        {status === "ready" && (
          <>
            <TradeAnalysisChart
              candles={analysis?.candles}
              annotations={analysis?.annotations}
              side={trade?.side}
              symbol={tradeSymbol(trade)}
              trade={trade}
            />
            <div className="trade-analysis-market-grid">
              {marketContextTiles(analysis?.marketContext).map((tile) => (
                <div className="trade-analysis-market-tile" key={tile.label}>
                  <span>{tile.label}</span>
                  <strong>{tile.value}</strong>
                </div>
              ))}
            </div>
          </>
        )}

        <div className="trade-analysis-detail-grid">
          {detailSections.map((section) => (
            <article className="trade-analysis-detail" key={section.label}>
              <span>{section.label}</span>
              <p>{section.text}</p>
            </article>
          ))}
        </div>
      </section>
    </div>,
    document.body
  );
}

function TradeAnalysisChart({ candles = [], annotations = [], side = "", symbol = "", trade = null }) {
  const rawSeries = Array.isArray(candles) ? candles.filter((candle) => Number(candle?.close || 0) > 0) : [];
  const series = focusTradeSeries(rawSeries, trade);
  const marks = Array.isArray(annotations) ? annotations : [];
  if (!series.length) {
    return <div className="app-empty">No local candle data is available around this trade yet.</div>;
  }

  const width = 960;
  const height = 410;
  const priceTop = 20;
  const priceBottom = 292;
  const volumeTop = 318;
  const volumeHeight = 48;
  const plotLeft = 18;
  const plotRight = 900;
  const plotWidth = plotRight - plotLeft;
  const candleWidth = Math.max(4.5, Math.min(13, (plotWidth / Math.max(1, series.length)) * 0.66));
  const values = [];
  series.forEach((candle) => {
    values.push(Number(candle.high), Number(candle.low), Number(candle.vwap), Number(candle.ema20), Number(candle.ema50));
  });
  [tradeEntryPrice(trade), tradeExitPrice(trade)].forEach((value) => {
    if (Number.isFinite(value) && value > 0) values.push(value);
  });
  const candleValues = values.filter(Number.isFinite);
  const candleMin = Math.min(...candleValues);
  const candleMax = Math.max(...candleValues);
  const candleRange = Math.max(candleMax - candleMin, 1);
  const relevantFloor = candleMin - Math.max(candleRange * 0.7, 10);
  const relevantCeiling = candleMax + Math.max(candleRange * 0.7, 10);
  marks.forEach((mark) => {
    ["price", "high", "low", "gapHigh", "gapLow"].forEach((key) => {
      const value = Number(mark?.[key]);
      if (value > 0 && value >= relevantFloor && value <= relevantCeiling) values.push(value);
    });
  });
  const minValue = Math.min(...values.filter(Number.isFinite));
  const maxValue = Math.max(...values.filter(Number.isFinite));
  const tradePrices = [tradeEntryPrice(trade), tradeExitPrice(trade)].filter((value) => Number.isFinite(value) && value > 0);
  const tradeFloor = tradePrices.length ? Math.min(...tradePrices) : minValue;
  const tradeCeiling = tradePrices.length ? Math.max(...tradePrices) : maxValue;
  const padding = Math.max((maxValue - minValue) * 0.2, Math.max(tradeCeiling - tradeFloor, 0) * 0.55, 3.5);
  const min = minValue - padding;
  const max = maxValue + padding;
  const range = Math.max(max - min, 1);
  const maxVolume = Math.max(1, ...series.map((candle) => Number(candle.volume || 0)));
  const indexByTime = new Map(series.map((candle, index) => [String(candle.time || ""), index]));
  const entryTone = String(side || "").toUpperCase() === "SHORT" ? "short" : "long";

  const toX = (index) => plotLeft + (index * (plotWidth / Math.max(1, series.length - 1 || 1)));
  const toY = (price) => priceBottom - (((Number(price || 0) - min) / range) * (priceBottom - priceTop));
  const xForTime = (time) => {
    const index = indexByTime.get(String(time || ""));
    return index == null ? null : toX(index);
  };
  const xForTradeEvent = (value, price) => {
    const timestamp = parseTradeTimestamp(value);
    const timeIndex = nearestSeriesIndex(series, timestamp);
    const priceIndex = nearestPriceActionIndex(series, Number(price), timeIndex);
    const edgePinned = timeIndex <= 0 || timeIndex >= series.length - 1;
    const index = edgePinned && priceIndex >= 0 ? priceIndex : timeIndex >= 0 ? timeIndex : priceIndex;
    return index < 0 ? null : toX(index);
  };
  const vwapPath = indicatorPath(series, toX, toY, "vwap");
  const ema20Path = indicatorPath(series, toX, toY, "ema20");
  const ema50Path = indicatorPath(series, toX, toY, "ema50");
  const eventMarkers = tradeEventMarkers(trade, side, { xForTradeEvent, toY, priceTop, priceBottom });
  const levels = levelTapeRows(marks);
  const clipId = `trade-analysis-plot-${cleanSvgId(symbol || "trade")}`;

  return (
    <div className="trade-analysis-chart-shell">
      <div className="trade-analysis-chart-title">
        <strong>{symbol}</strong>
        <span>{formatEstTime(series[0]?.time)} to {formatEstTime(series[series.length - 1]?.time)}</span>
      </div>
      <div className="trade-analysis-chart-stage">
        <svg viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" className="trade-analysis-svg" role="img" aria-label={`${symbol} trade analysis chart`}>
          <defs>
            <clipPath id={clipId}>
              <rect x={plotLeft} y={priceTop} width={plotWidth} height={priceBottom - priceTop} />
            </clipPath>
          </defs>

          {[0, 1, 2, 3, 4].map((step) => {
            const price = min + ((range * step) / 4);
            return (
              <g key={`grid-${step}`}>
                <line x1={plotLeft} x2={plotRight} y1={toY(price)} y2={toY(price)} className="trade-analysis-grid-line" />
                <text x={width - 8} y={toY(price) - 4} textAnchor="end" className="trade-analysis-axis-label">{formatPrice(price)}</text>
              </g>
            );
          })}

          {series.map((candle, index) => {
            const x = toX(index);
            const rising = Number(candle.close || 0) >= Number(candle.open || 0);
            const volumeHeightValue = Math.max(2, (Number(candle.volume || 0) / maxVolume) * volumeHeight);
            return (
              <rect
                key={`vol-${candle.time}-${index}`}
                x={x - candleWidth / 2}
                y={volumeTop + volumeHeight - volumeHeightValue}
                width={candleWidth}
                height={volumeHeightValue}
                className={rising ? "trade-analysis-volume up" : "trade-analysis-volume down"}
              />
            );
          })}

          <line x1={plotLeft} x2={plotRight} y1={volumeTop - 12} y2={volumeTop - 12} className="trade-analysis-volume-divider" />

          <g clipPath={`url(#${clipId})`}>
            {vwapPath && <path d={vwapPath} className="trade-analysis-line vwap" />}
            {ema20Path && <path d={ema20Path} className="trade-analysis-line ema20" />}
            {ema50Path && <path d={ema50Path} className="trade-analysis-line ema50" />}

            {series.map((candle, index) => {
              const x = toX(index);
              const openY = toY(candle.open);
              const closeY = toY(candle.close);
              const highY = toY(candle.high);
              const lowY = toY(candle.low);
              const rising = Number(candle.close || 0) >= Number(candle.open || 0);
              return (
                <g key={`candle-${candle.time}-${index}`}>
                  <line x1={x} x2={x} y1={highY} y2={lowY} className={rising ? "trade-analysis-wick up" : "trade-analysis-wick down"} />
                  <rect
                    x={x - candleWidth / 2}
                    y={Math.min(openY, closeY)}
                    width={candleWidth}
                    height={Math.max(2, Math.abs(closeY - openY))}
                    rx="1.5"
                    className={rising ? `trade-analysis-candle up ${entryTone}` : `trade-analysis-candle down ${entryTone}`}
                  />
                </g>
              );
            })}
          </g>

          {marks.map((mark, index) => renderAnnotation(mark, index, { toX, toY, xForTime, plotLeft, plotRight, priceTop, priceBottom }))}
          {eventMarkers.map((marker, index) => renderTradeEventMarker(marker, index, { plotLeft, plotRight, priceTop, priceBottom }))}

          {timeAxisLabels(series).map(({ candle, index }) => (
            <text key={`time-${candle.time}-${index}`} x={toX(index)} y="394" textAnchor={timeAxisAnchor(index, series.length)} className="trade-analysis-time-label">
              {compactTime(candle.time)}
            </text>
          ))}
        </svg>
        <LevelTape levels={levels} />
      </div>
      <div className="trade-analysis-chart-footer" aria-hidden="true">
        <span><i className="vwap" />VWAP</span>
        <span><i className="ema20" />EMA 20</span>
        <span><i className="ema50" />EMA 50</span>
        <span><i className="entry" />Entry</span>
        <span><i className="exit" />Exit</span>
      </div>
    </div>
  );
}

function renderAnnotation(mark, index, chart) {
  const color = displayColorForAnnotation(mark);
  if (mark?.type === "priceLine" && Number(mark.price) > 0) {
    if (isTradeEventPriceLine(mark)) return null;
    const y = chart.toY(mark.price);
    if (!isYInsidePriceArea(y, chart)) return null;
    const guideClass = `trade-analysis-price-level ${isPlanLevel(mark) ? "plan" : "structure"} ${levelRoleClass(mark)}`;
    return (
      <g key={`ann-${index}`}>
        <line x1={chart.plotLeft} x2={chart.plotRight} y1={y} y2={y} stroke={color} className={guideClass} />
        <circle cx={chart.plotRight} cy={y} r="3.2" fill={color} className={`trade-analysis-level-dot ${levelRoleClass(mark)}`} />
      </g>
    );
  }
  if (mark?.type === "range") {
    const startX = chart.xForTime(mark.startTime);
    const endX = chart.xForTime(mark.endTime);
    if (startX == null || endX == null || !(Number(mark.high) > Number(mark.low))) return null;
    const y1 = chart.toY(mark.high);
    const y2 = chart.toY(mark.low);
    if (!rangeOverlapsPriceArea(y1, y2, chart)) return null;
    const top = clampY(Math.min(y1, y2), chart);
    const bottom = clampY(Math.max(y1, y2), chart);
    const left = Math.max(chart.plotLeft, Math.min(startX, endX));
    const right = Math.min(chart.plotRight, Math.max(startX, endX));
    const label = compactAnnotationLabel(mark.label);
    const badgeWidth = Math.min(162, Math.max(54, label.length * 6.5 + 18));
    const placement = structureBadgePlacement({ label, left, right, top, bottom, badgeWidth, chart });
    return (
      <g key={`ann-${index}`}>
        <rect x={left} y={top} width={Math.max(8, right - left)} height={Math.max(4, bottom - top)} rx="4" fill={color} className="trade-analysis-range-fill" />
        {isYInsidePriceArea(y1, chart) && <line x1={left} x2={right} y1={y1} y2={y1} stroke={color} className="trade-analysis-structure-line" />}
        {isYInsidePriceArea(y2, chart) && <line x1={left} x2={right} y1={y2} y2={y2} stroke={color} className="trade-analysis-structure-line" />}
        {placement.leader && <line x1={placement.anchorX} x2={placement.leaderX} y1={placement.anchorY} y2={placement.leaderY} stroke={color} className="trade-analysis-structure-leader" />}
        <rect x={placement.labelX} y={placement.labelY - 13} width={badgeWidth} height="18" rx="5" className="trade-analysis-structure-badge" />
        <text x={placement.labelX + 9} y={placement.labelY} fill={color} className="trade-analysis-annotation-label">{label}</text>
      </g>
    );
  }
  if (mark?.type === "gap") {
    const startX = chart.xForTime(mark.startTime);
    const endX = chart.xForTime(mark.endTime);
    if (startX == null || endX == null || !(Number(mark.gapHigh) > Number(mark.gapLow))) return null;
    const y1 = chart.toY(mark.gapHigh);
    const y2 = chart.toY(mark.gapLow);
    if (!rangeOverlapsPriceArea(y1, y2, chart)) return null;
    const top = clampY(Math.min(y1, y2), chart);
    const bottom = clampY(Math.max(y1, y2), chart);
    const left = Math.max(chart.plotLeft, Math.min(startX, endX));
    const right = Math.min(chart.plotRight, Math.max(startX, endX));
    const label = compactAnnotationLabel(mark.label);
    const badgeWidth = Math.min(166, Math.max(54, label.length * 6.5 + 18));
    const placement = structureBadgePlacement({ label, left, right, top, bottom, badgeWidth, chart });
    return (
      <g key={`ann-${index}`}>
        <rect x={left} y={top} width={Math.max(12, right - left)} height={Math.max(5, bottom - top)} rx="4" fill={color} className="trade-analysis-gap-fill" />
        {placement.leader && <line x1={placement.anchorX} x2={placement.leaderX} y1={placement.anchorY} y2={placement.leaderY} stroke={color} className="trade-analysis-structure-leader" />}
        <rect x={placement.labelX} y={placement.labelY - 13} width={badgeWidth} height="18" rx="5" className="trade-analysis-structure-badge" />
        <text x={placement.labelX + 9} y={placement.labelY} fill={color} className="trade-analysis-annotation-label">{label}</text>
      </g>
    );
  }
  if (mark?.type === "candle") {
    const x = chart.xForTime(mark.time);
    if (x == null) return null;
    return (
      <g key={`ann-${index}`}>
        <rect x={x - 8} y={chart.priceTop} width="16" height={chart.priceBottom - chart.priceTop} fill={color} className="trade-analysis-candle-highlight" />
        <rect x={Math.min(x + 8, chart.plotRight - 92)} y={chart.priceTop + 5} width="84" height="18" rx="5" className="trade-analysis-structure-badge" />
        <text x={Math.min(x + 16, chart.plotRight - 84)} y={chart.priceTop + 18} fill={color} className="trade-analysis-annotation-label">{compactAnnotationLabel(mark.label)}</text>
      </g>
    );
  }
  return null;
}

function renderTradeEventMarker(marker, index, chart) {
  if (!marker || !Number.isFinite(marker.x) || !Number.isFinite(marker.y)) return null;
  const y = clampY(marker.y, chart);
  const label = marker.label.toUpperCase();
  const priceLabel = formatPrice(marker.price);
  const labelWidth = marker.kind === "entry" ? 94 : 78;
  const preferredX = marker.kind === "entry" ? marker.x - labelWidth - 16 : marker.x + 16;
  const labelX = Math.min(Math.max(preferredX, chart.plotLeft + 4), chart.plotRight - labelWidth - 4);
  const preferredY = marker.kind === "entry" ? y - 20 : y + 30;
  const labelY = Math.min(Math.max(preferredY, chart.priceTop + 32), chart.priceBottom - 10);
  const leaderTargetX = labelX + (marker.kind === "entry" ? labelWidth : 0);
  return (
    <g key={`event-${marker.kind}-${index}`} className={`trade-analysis-event ${marker.kind}`}>
      <line x1={marker.x} x2={marker.x} y1={chart.priceTop} y2={chart.priceBottom} className="trade-analysis-event-rail" />
      <line x1={marker.x} x2={leaderTargetX} y1={y} y2={labelY - 8} className="trade-analysis-event-leader" />
      <path d={eventDiamondPath(marker.x, y)} className="trade-analysis-event-dot" />
      <rect x={labelX} y={labelY - 24} width={labelWidth} height="34" rx="7" className="trade-analysis-event-label-bg" />
      <text x={labelX + 9} y={labelY - 10} className="trade-analysis-event-label">{label}</text>
      <text x={labelX + 9} y={labelY + 3} className="trade-analysis-event-price">{priceLabel}</text>
    </g>
  );
}

function eventDiamondPath(x, y) {
  return `M ${x} ${y - 5.5} L ${x + 5.5} ${y} L ${x} ${y + 5.5} L ${x - 5.5} ${y} Z`;
}

function structureBadgePlacement({ label, left, right, top, bottom, badgeWidth, chart }) {
  const anchorX = left + ((right - left) / 2);
  const anchorY = top + ((bottom - top) / 2);
  if (label !== "FVG") {
    return {
      labelX: Math.min(Math.max(left + 6, chart.plotLeft + 6), chart.plotRight - badgeWidth - 6),
      labelY: Math.max(chart.priceTop + 16, top + 15),
      leader: false,
      anchorX,
      anchorY,
      leaderX: anchorX,
      leaderY: anchorY,
    };
  }

  const preferAbove = top - chart.priceTop > 38;
  const labelX = Math.min(Math.max(anchorX + 26, chart.plotLeft + 6), chart.plotRight - badgeWidth - 6);
  const labelY = preferAbove
    ? chart.priceTop + 26
    : Math.min(chart.priceBottom - 10, bottom + 44);
  return {
    labelX,
    labelY,
    leader: true,
    anchorX,
    anchorY,
    leaderX: labelX + 8,
    leaderY: labelY - 4,
  };
}

function LevelTape({ levels }) {
  if (!levels.length) return null;
  const plan = levels.filter((level) => level.group === "plan");
  const structure = levels.filter((level) => level.group !== "plan");
  return (
    <aside className="trade-analysis-level-panel" aria-label="Trade price levels">
      <div className="trade-analysis-level-title">
        <span>Levels</span>
        <strong>{levels.length}</strong>
      </div>
      {plan.length > 0 && <LevelTapeGroup title="Trade" levels={plan} />}
      {structure.length > 0 && <LevelTapeGroup title="Structure" levels={structure} />}
    </aside>
  );
}

function LevelTapeGroup({ title, levels }) {
  return (
    <div className="trade-analysis-level-group">
      <span>{title}</span>
      {levels.map((level, index) => (
        <div className={`trade-analysis-level-row ${level.group} ${level.role}`} key={`${level.label}-${level.value}-${index}`}>
          <i style={{ "--level-color": level.color }} />
          <em>{level.label}</em>
          <strong>{level.value}</strong>
        </div>
      ))}
    </div>
  );
}

function focusTradeSeries(series, trade) {
  const maxBars = 64;
  if (!Array.isArray(series) || series.length <= maxBars) return series;
  const entryTime = parseTradeTimestamp(tradeOpenedAt(trade));
  const exitTime = parseTradeTimestamp(tradeClosedAt(trade));
  const entryIndex = nearestSeriesIndex(series, entryTime);
  if (entryIndex < 0) {
    return series.slice(Math.max(0, series.length - maxBars));
  }
  const rawExitIndex = nearestSeriesIndex(series, exitTime);
  const exitIndex = Math.max(entryIndex, rawExitIndex >= 0 ? rawExitIndex : entryIndex);
  let start = Math.max(0, entryIndex - 24);
  let end = Math.min(series.length, exitIndex + 25);
  if (end - start > maxBars) {
    const midpoint = Math.round((entryIndex + exitIndex) / 2);
    start = Math.max(0, midpoint - Math.floor(maxBars / 2));
    end = Math.min(series.length, start + maxBars);
    start = Math.max(0, end - maxBars);
  }
  return series.slice(start, end);
}

function nearestSeriesIndex(series, timestamp) {
  if (!Number.isFinite(timestamp)) return -1;
  let bestIndex = -1;
  let bestDistance = Number.POSITIVE_INFINITY;
  series.forEach((candle, index) => {
    const value = parseTradeTimestamp(candle?.time);
    const distance = Number.isFinite(value) ? Math.abs(value - timestamp) : Number.POSITIVE_INFINITY;
    if (distance < bestDistance) {
      bestDistance = distance;
      bestIndex = index;
    }
  });
  return bestIndex;
}

function nearestPriceActionIndex(series, price, preferredIndex = -1) {
  if (!Number.isFinite(price) || price <= 0) return -1;
  let bestIndex = -1;
  let bestScore = Number.POSITIVE_INFINITY;
  (Array.isArray(series) ? series : []).forEach((candle, index) => {
    const high = Number(candle?.high);
    const low = Number(candle?.low);
    const open = Number(candle?.open);
    const close = Number(candle?.close);
    const priceValues = [high, low, open, close].filter(Number.isFinite);
    if (!priceValues.length) return;
    const insideBar = Number.isFinite(high)
      && Number.isFinite(low)
      && price <= Math.max(high, low)
      && price >= Math.min(high, low);
    const nearestDistance = insideBar ? 0 : Math.min(...priceValues.map((value) => Math.abs(value - price)));
    const timePenalty = preferredIndex >= 0 ? Math.abs(index - preferredIndex) * 0.02 : index * 0.001;
    const score = nearestDistance + timePenalty + (insideBar ? -1 : 0);
    if (score < bestScore) {
      bestScore = score;
      bestIndex = index;
    }
  });
  return bestIndex;
}

function parseTradeTimestamp(value) {
  const text = String(value || "").trim();
  if (!text || text === "--") return Number.NaN;
  if (/(Z|[+-]\d{2}:?\d{2})$/i.test(text)) {
    const parsedOffset = Date.parse(text);
    return Number.isFinite(parsedOffset) ? parsedOffset : Number.NaN;
  }
  const isoMatch = text.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/);
  if (isoMatch) {
    const [, year, month, day, hour, minute] = isoMatch;
    return new Date(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute)).getTime();
  }
  const slashMatch = text.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})\s+(\d{1,2}):(\d{2})\s*(AM|PM)/i);
  if (slashMatch) {
    const [, month, day, year, rawHour, minute, meridiem] = slashMatch;
    const baseHour = Number(rawHour) % 12;
    const hour = baseHour + (meridiem.toUpperCase() === "PM" ? 12 : 0);
    return new Date(Number(year), Number(month) - 1, Number(day), hour, Number(minute)).getTime();
  }
  const parsed = Date.parse(text.replace(/\s+EDT$/, " -0400").replace(/\s+EST$/, " -0500"));
  return Number.isFinite(parsed) ? parsed : Number.NaN;
}

function tradeEventMarkers(trade, side, chart) {
  const entryPrice = tradeEntryPrice(trade);
  const exitPrice = tradeExitPrice(trade);
  const entryX = chart.xForTradeEvent(tradeOpenedAt(trade), entryPrice);
  const exitX = chart.xForTradeEvent(tradeClosedAt(trade), exitPrice);
  const direction = String(side || trade?.side || "").toUpperCase();
  return [
    {
      kind: "entry",
      label: direction === "SHORT" ? "Short" : "Long",
      price: entryPrice,
      x: entryX,
      y: chart.toY(entryPrice),
    },
    {
      kind: "exit",
      label: "Exit",
      price: exitPrice,
      x: exitX,
      y: chart.toY(exitPrice),
    },
  ].filter((marker) => (
    Number.isFinite(marker.price) &&
    marker.price > 0 &&
    Number.isFinite(marker.x) &&
    Number.isFinite(marker.y)
  ));
}

function levelTapeRows(annotations = []) {
  const planOrder = new Map([
    ["entry", 0],
    ["exit", 1],
    ["target", 2],
    ["stop", 3],
  ]);
  return annotations
    .filter((mark) => mark?.type === "priceLine" && Number(mark.price) > 0 && !isTradeEventPriceLine(mark))
    .map((mark) => {
      const rawLabel = String(mark.label || "Level");
      const label = compactAnnotationLabel(rawLabel);
      const lower = rawLabel.toLowerCase();
      const planKey = [...planOrder.keys()].find((key) => lower.includes(key));
      return {
        label,
        value: formatPrice(mark.price),
        color: displayColorForAnnotation(mark),
        group: planKey ? "plan" : "structure",
        role: levelRoleClass(mark),
        order: planKey ? planOrder.get(planKey) : structureLevelOrder(lower),
      };
    })
    .sort((a, b) => (a.group === b.group ? a.order - b.order : a.group === "plan" ? -1 : 1));
}

function structureLevelOrder(label) {
  if (label.includes("orb high")) return 10;
  if (label.includes("orb low")) return 11;
  if (label.includes("session open")) return 12;
  if (label.includes("vwap")) return 20;
  if (label.includes("ema 20")) return 21;
  if (label.includes("ema 50")) return 22;
  if (label.includes("keltner upper")) return 30;
  if (label.includes("keltner lower")) return 31;
  if (label.includes("prior poc")) return 40;
  if (label.includes("prior high")) return 41;
  if (label.includes("prior low")) return 42;
  if (label.includes("value area high")) return 50;
  if (label.includes("value area low")) return 51;
  return 99;
}

function displayColorForAnnotation(mark) {
  const role = levelRoleClass(mark);
  const colors = {
    "level-entry": "#7dd3fc",
    "level-exit": "#facc15",
    "level-target": "#2df39f",
    "level-stop": "#ff5c7a",
    "level-orb-high": "#f97316",
    "level-orb-low": "#fb923c",
    "level-vwap": "#facc15",
    "level-ema20": "#38bdf8",
    "level-ema50": "#a78bfa",
    "level-session-open": "#e879f9",
    "level-keltner-upper": "#fb923c",
    "level-keltner-lower": "#fb923c",
    "level-poc": "#ff4fb8",
    "level-prior-high": "#22d3ee",
    "level-prior-low": "#60a5fa",
    "level-vah": "#c084fc",
    "level-val": "#a78bfa",
  };
  return colors[role] || mark?.color || "#93c5fd";
}

function levelRoleClass(mark) {
  const label = String(mark?.label || mark || "").toLowerCase();
  if (label.includes("entry")) return "level-entry";
  if (label.includes("exit")) return "level-exit";
  if (label.includes("target")) return "level-target";
  if (label.includes("stop")) return "level-stop";
  if (label.includes("orb high") || label.includes("opening range high")) return "level-orb-high";
  if (label.includes("orb low") || label.includes("opening range low")) return "level-orb-low";
  if (label === "vwap" || label.includes("vwap level")) return "level-vwap";
  if (label.includes("ema 20")) return "level-ema20";
  if (label.includes("ema 50")) return "level-ema50";
  if (label.includes("session open")) return "level-session-open";
  if (label.includes("keltner upper")) return "level-keltner-upper";
  if (label.includes("keltner lower")) return "level-keltner-lower";
  if (label.includes("poc")) return "level-poc";
  if (label.includes("prior high")) return "level-prior-high";
  if (label.includes("prior low")) return "level-prior-low";
  if (label.includes("value area high")) return "level-vah";
  if (label.includes("value area low")) return "level-val";
  if (label === "vah") return "level-vah";
  if (label === "val") return "level-val";
  return "level-generic";
}

function cleanSvgId(value) {
  return String(value || "trade").replace(/[^a-zA-Z0-9_-]/g, "-");
}

function compactAnnotationLabel(value) {
  const label = String(value || "").trim();
  const lower = label.toLowerCase();
  if (lower.includes("fair value")) return "FVG";
  if (lower === "value area high") return "VAH";
  if (lower === "value area low") return "VAL";
  if (lower === "prior poc") return "POC";
  if (lower === "prior high") return "Prior H";
  if (lower === "prior low") return "Prior L";
  if (lower === "opening range high") return "ORB H";
  if (lower === "opening range low") return "ORB L";
  if (lower === "orb high") return "ORB H";
  if (lower === "orb low") return "ORB L";
  if (lower === "session open") return "Open";
  if (lower === "keltner upper") return "Keltner U";
  if (lower === "keltner lower") return "Keltner L";
  if (lower.includes("liquidity sweep")) return "Sweep";
  if (lower.includes("liquidity reclaim")) return "Reclaim";
  if (lower.includes("inventory release")) return "EIA Range";
  return label;
}

function isTradeEventPriceLine(mark) {
  const label = String(mark?.label || "").toLowerCase();
  return label.includes("entry") || label.includes("exit");
}

function isPlanLevel(mark) {
  const label = String(mark?.label || "").toLowerCase();
  return label.includes("stop") || label.includes("target");
}

function isYInsidePriceArea(y, chart) {
  return Number.isFinite(y) && y >= chart.priceTop && y <= chart.priceBottom;
}

function rangeOverlapsPriceArea(y1, y2, chart) {
  const top = Math.min(y1, y2);
  const bottom = Math.max(y1, y2);
  return Number.isFinite(top) && Number.isFinite(bottom) && bottom >= chart.priceTop && top <= chart.priceBottom;
}

function clampY(y, chart) {
  return Math.max(chart.priceTop, Math.min(chart.priceBottom, y));
}

function tradeEntryPrice(trade) {
  const value = Number(trade?.entry ?? trade?.entryPrice);
  return Number.isFinite(value) ? value : 0;
}

function tradeExitPrice(trade) {
  const value = Number(trade?.exit ?? trade?.exitPrice);
  return Number.isFinite(value) ? value : 0;
}

function tradeOpenedAt(trade) {
  return trade?.openedAt || trade?.entryTime || trade?.signalTime || trade?.time || "";
}

function tradeClosedAt(trade) {
  const directClose = trade?.closedAt || trade?.exitTime || trade?.updatedAt || "";
  if (directClose) return directClose;
  const exitPrice = tradeExitPrice(trade);
  const status = String(trade?.status || "").toUpperCase();
  const closedStatus = status.includes("SOLD") || status.includes("CLOSED") || status.includes("FLAT");
  return exitPrice > 0 || closedStatus ? trade?.createdAt || "" : "";
}

function tradeAnalysisParams(trade) {
  const params = new URLSearchParams();
  params.set("symbol", tradeSymbol(trade));
  params.set("strategyCode", trade?.strategyCode || trade?.strategy || "");
  params.set("strategyName", trade?.strategyName || "");
  params.set("side", trade?.side || "");
  params.set("openedAt", tradeOpenedAt(trade));
  params.set("closedAt", tradeClosedAt(trade));
  params.set("entryPrice", numberText(tradeEntryPrice(trade)));
  params.set("exitPrice", numberText(tradeExitPrice(trade)));
  params.set("stopPrice", numberText(trade?.stop ?? trade?.stopPrice));
  params.set("targetPrice", numberText(trade?.target ?? trade?.targetPrice));
  return params;
}

function tradeMetricTiles(trade, analysis) {
  const pnl = Number(trade?.pnl || 0);
  const entry = reasonSection(trade?.entryReasoning || trade?.tradeReason?.entry);
  const exit = reasonSection(trade?.exitReasoning || trade?.tradeReason?.exit);
  return [
    { label: "Entry", value: formatPrice(tradeEntryPrice(trade)) },
    { label: "Exit", value: formatPrice(tradeExitPrice(trade)) },
    { label: "Contracts", value: formatNumber(trade?.qty ?? trade?.contracts ?? 0) },
    { label: "P/L", value: formatCurrency(pnl), accent: pnl },
    { label: "Stop", value: formatPrice(firstNumber(trade?.stop, trade?.stopPrice, entry.initialStop, entry.stopPrice)) },
    { label: "Target", value: formatPrice(firstNumber(trade?.target, trade?.targetPrice, entry.initialTarget, entry.targetPrice)) },
    { label: "MFE", value: formatCurrency(firstNumber(trade?.mfe, exit.mfe)) },
    { label: "MAE", value: formatCurrency(firstNumber(trade?.mae, exit.mae)) },
    { label: "Opened", value: formatEstTime(tradeOpenedAt(trade) || analysis?.entryTime || "--") },
    { label: "Closed", value: formatEstTime(tradeClosedAt(trade) || analysis?.exitTime || "--") },
  ];
}

function marketContextTiles(context = {}) {
  return [
    { label: "Volume", value: formatNumber(context.entryVolume, 0) },
    { label: "Vol / SMA20", value: formatNumber(context.entryVolumeRatio) },
    { label: "RSI 14", value: formatNumber(context.entryRsi14) },
    { label: "VWAP", value: formatPrice(context.entryVwap) },
    { label: "EMA 9 / 20 / 50", value: `${formatPrice(context.entryEma9)} / ${formatPrice(context.entryEma20)} / ${formatPrice(context.entryEma50)}` },
    { label: "ATR 14", value: formatPrice(context.entryAtr14) },
    { label: "Range Ticks", value: formatNumber(context.entryRangeTicks) },
    { label: "Body %", value: formatPct(Number(context.entryBodyPct || 0) * 100) },
    { label: "ORB High / Low", value: `${formatPrice(context.orbHigh)} / ${formatPrice(context.orbLow)}` },
    { label: "Prior High / Low", value: `${formatPrice(context.previousHigh)} / ${formatPrice(context.previousLow)}` },
    { label: "Value Area", value: `${formatPrice(context.valueAreaHigh)} / ${formatPrice(context.valueAreaLow)}` },
    { label: "Prior POC", value: formatPrice(context.pointOfControl) },
  ];
}

function tradeDetailSections(trade) {
  const entry = entryDetailText(trade);
  const exit = exitDetailText(trade);
  const dtm = dtmDetailText(trade);
  const tableDetails = tableDetailText(trade);
  return [
    { label: "Entry Reason", text: entry },
    { label: "Exit Reason", text: exit },
    { label: "DTM Decisions", text: dtm },
    { label: "Table Details", text: tableDetails },
  ];
}

function entryDetailText(trade) {
  const entry = reasonSection(trade?.entryReasoning || trade?.tradeReason?.entry);
  return firstText(
    trade?.entryReason,
    entry.strategyText,
    entry.strategyReason,
    entry.strategyThesis,
    trade?.reason,
    `${displayStrategy(trade)} ${String(trade?.side || "").toLowerCase()} entry at ${formatPrice(tradeEntryPrice(trade))}.`
  );
}

function exitDetailText(trade) {
  const exit = reasonSection(trade?.exitReasoning || trade?.tradeReason?.exit);
  const finalTrigger = firstText(
    exit.finalExitTrigger,
    trade?.structuredExitReason,
    stripDtmPrefix(trade?.exitReason),
    stripDtmPrefix(exit.exitText)
  );
  const pieces = [
    finalTrigger,
    tradeExitPrice(trade) > 0 ? `Price ${formatPrice(tradeExitPrice(trade))}` : "",
    Number(trade?.contracts || trade?.qty || 0) > 0 ? `${formatNumber(trade?.contracts || trade?.qty, 0)} contract${Number(trade?.contracts || trade?.qty) === 1 ? "" : "s"}` : "",
    Number.isFinite(Number(trade?.pnl)) ? `P/L ${formatCurrency(trade.pnl)}` : "",
  ];
  return pieces.filter(Boolean).join("; ") || "Final exit details are waiting on broker reconciliation.";
}

function dtmDetailText(trade) {
  const exit = reasonSection(trade?.exitReasoning || trade?.tradeReason?.exit);
  const timeline = Array.isArray(exit.dtmDecisionTimeline) ? exit.dtmDecisionTimeline : [];
  if (timeline.length) {
    return timeline
      .map((event) => {
        const details = reasonSection(event?.details);
        const action = humanizeCode(firstText(event?.actionCode, event?.action, event?.normalizedAction));
        const reason = firstText(event?.reason);
        const movement = [
          Number.isFinite(Number(details.favorableR)) ? `+${formatNumber(details.favorableR)}R favorable` : "",
          Number.isFinite(Number(details.adverseR)) ? `-${formatNumber(details.adverseR)}R adverse` : "",
          Number.isFinite(Number(details.barsHeld)) ? `${formatNumber(details.barsHeld, 0)} bars held` : "",
        ].filter(Boolean).join(", ");
        return [action, reason, movement].filter(Boolean).join(": ");
      })
      .filter(Boolean)
      .join(" | ");
  }
  const action = humanizeCode(firstText(exit.dtmFinalAction, trade?.dtmAction));
  const summary = firstText(trade?.dtmDetails, exit.dtmSummary, textContaining(trade?.tradeNotes, "DTM"));
  const runner = firstText(exit.runnerDecision);
  const partial = firstText(exit.partialDecision);
  const normalized = `${action} ${summary}`.toLowerCase();
  if (summary && !normalized.includes("no dtm decisions") && !normalized.includes("dtm_no_override")) {
    return [action, summary, partial, runner].filter(Boolean).join("; ");
  }
  if (action && !action.toLowerCase().includes("no override")) {
    return [action, partial, runner].filter(Boolean).join("; ");
  }
  return "DTM evaluated this trade but did not attach an override action to the final row.";
}

function tableDetailText(trade) {
  const pieces = [
    trade?.accountId ? `Account ${trade.accountId}` : "",
    trade?.cacheSource ? `Source ${trade.cacheSource}` : "",
    trade?.status ? `Status ${trade.status}` : "",
    trade?.brokerOrderId || trade?.orderId ? `Order ${trade.brokerOrderId || trade.orderId}` : "",
    trade?.customTag ? `Tag ${trade.customTag}` : "",
    trade?.reason || trade?.tradeNotes || "",
  ];
  return pieces.filter(Boolean).join("; ") || "Broker/live table row was paired from available entry and close data.";
}

function reasonSection(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

function firstNumber(...values) {
  for (const value of values) {
    const number = Number(value);
    if (Number.isFinite(number) && number !== 0) return number;
  }
  return 0;
}

function stripDtmPrefix(value) {
  return String(value || "")
    .replace(/^\s*DTM:\s*[^.]+\.?\s*/i, "")
    .replace(/^\s*DTM:\s*[^;]+;\s*/i, "")
    .trim();
}

function humanizeCode(value) {
  const text = String(value || "").trim();
  if (!text) return "";
  return text
    .replace(/^DTM[_\s-]*/i, "")
    .replace(/_/g, " ")
    .toLowerCase()
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function indicatorPath(series, toX, toY, key) {
  const points = series
    .map((candle, index) => Number(candle?.[key] || 0) > 0 ? `${toX(index)},${toY(candle[key])}` : "")
    .filter(Boolean);
  return points.length > 1 ? `M ${points.join(" L ")}` : "";
}

function timeAxisLabels(series) {
  if (!series.length) return [];
  const last = series.length - 1;
  const targetIndexes = [0, Math.round(last * 0.25), Math.round(last * 0.5), Math.round(last * 0.75), last];
  return [...new Set(targetIndexes)]
    .filter((index) => index >= 0 && index < series.length)
    .map((index) => ({ candle: series[index], index }));
}

function timeAxisAnchor(index, seriesLength) {
  if (index === 0) return "start";
  if (index === seriesLength - 1) return "end";
  return "middle";
}

function displayStrategy(trade) {
  return trade?.strategyName || trade?.strategyCode || trade?.strategy || "--";
}

function tradeSymbol(trade) {
  return String(trade?.symbol || trade?.contractName || "").toUpperCase();
}

function firstText(...values) {
  return values.map((value) => String(value || "").trim()).find(Boolean) || "";
}

function textContaining(value, needle) {
  const text = String(value || "");
  return text.toUpperCase().includes(String(needle || "").toUpperCase()) ? text : "";
}

function compactTime(value) {
  const text = String(value || "");
  const parsed = parseTradeTimestamp(text);
  if (Number.isFinite(parsed)) {
    return new Intl.DateTimeFormat("en-US", {
      timeZone: "America/New_York",
      hour: "numeric",
      minute: "2-digit",
      hour12: true,
    }).format(new Date(parsed));
  }
  const match = text.match(/(\d{1,2}):(\d{2})/);
  if (!match) return text;
  const hour24 = Number(match[1]);
  const minute = match[2];
  const hour12 = hour24 % 12 || 12;
  const suffix = hour24 >= 12 ? "PM" : "AM";
  return `${hour12}:${minute} ${suffix}`;
}

function numberText(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? String(number) : "";
}

function formatCurrency(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "--";
  return `${number < 0 ? "-" : ""}$${Math.abs(number).toLocaleString(undefined, { maximumFractionDigits: 2 })}`;
}

function formatPrice(value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number <= 0) return "--";
  return number.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

function formatNumber(value, decimals = 2) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "--";
  return number.toLocaleString(undefined, { maximumFractionDigits: decimals });
}

function formatPct(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "--";
  return `${number.toLocaleString(undefined, { maximumFractionDigits: 1 })}%`;
}
