import { useMemo, useState } from "react";
import { EASTERN_TIME_LABEL, formatEstTime } from "../utils/time.js";

export default function TradeMarketChart({
  candles,
  symbol,
  timeframe,
  side,
  annotations = [],
  zones = [],
  entryTime,
  exitTime,
  emptyMessage = "No chart data is available for this trade.",
}) {
  const [hoveredIndex, setHoveredIndex] = useState(null);
  const chartCandles = useMemo(() => enrichCandles(candles), [candles]);

  if (!Array.isArray(chartCandles) || chartCandles.length === 0) {
    return <div className="app-chart-empty">{emptyMessage}</div>;
  }

  const width = 980;
  const height = 360;
  const lineAnnotations = Array.isArray(annotations) ? annotations.filter((annotation) => Number(annotation?.value) > 0) : [];
  const chartZones = Array.isArray(zones) ? zones.filter((zone) => Number(zone?.high) > Number(zone?.low)) : [];
  const prices = chartCandles
    .flatMap((candle) => [
      Number(candle.high || 0),
      Number(candle.low || 0),
      Number(candle.vwap || 0),
      Number(candle.ema9 || 0),
      Number(candle.ema20 || 0),
    ])
    .concat(lineAnnotations.map((annotation) => Number(annotation.value || 0)))
    .concat(chartZones.flatMap((zone) => [Number(zone.low || 0), Number(zone.high || 0)]))
    .filter((value) => Number.isFinite(value) && value > 0);

  if (prices.length === 0) prices.push(0, 1);

  const min = Math.min(...prices);
  const max = Math.max(...prices);
  const range = max - min || 1;
  const bodyWidth = width / Math.max(chartCandles.length, 1);
  const hoveredCandle = chartCandles[Math.min(Math.max(hoveredIndex ?? chartCandles.length - 1, 0), chartCandles.length - 1)];
  const entryIndex = findNearestCandleIndex(chartCandles, entryTime);
  const exitIndex = findNearestCandleIndex(chartCandles, exitTime);
  const entryLine = lineAnnotations.find((annotation) => annotation.key === "entry");
  const exitLine = lineAnnotations.find((annotation) => annotation.key === "exit");

  const toY = (price) => height - (((Number(price || 0) - min) / range) * (height - 40) + 20);
  const toX = (index) => index * bodyWidth + bodyWidth / 2;
  const pointPath = (key) =>
    chartCandles
      .map((candle, index) => {
        const value = Number(candle[key] || 0);
        if (!Number.isFinite(value) || value <= 0) return null;
        return `${index === 0 ? "M" : "L"} ${toX(index)} ${toY(value)}`;
      })
      .filter(Boolean)
      .join(" ");
  const annotationLabels = spreadAnnotationLabels(lineAnnotations, toY, height);
  const vwapPath = pointPath("vwap");
  const ema9Path = pointPath("ema9");
  const ema20Path = pointPath("ema20");

  return (
    <div className="app-chart-shell mt-3">
      <div className="app-chart-hover">
        <strong>{hoveredCandle?.time ? formatEstTime(hoveredCandle.time) : symbol}</strong>
        <div>
          <span>O {formatCurrency(hoveredCandle?.open)}</span>
          <span>H {formatCurrency(hoveredCandle?.high)}</span>
          <span>L {formatCurrency(hoveredCandle?.low)}</span>
          <span>C {formatCurrency(hoveredCandle?.close)}</span>
          <span>Vol {formatInteger(hoveredCandle?.volume)}</span>
          <span>RSI {formatIndicator(hoveredCandle?.rsi14)}</span>
        </div>
      </div>

      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="app-market-svg"
        role="img"
        aria-label={`${symbol || "Trade"} ${timeframe || ""} trade candles`}
        onMouseLeave={() => setHoveredIndex(null)}
      >
        {chartZones.map((zone) => {
          const yTop = toY(zone.high);
          const yBottom = toY(zone.low);
          return (
            <rect
              key={zone.key || zone.label}
              x="0"
              y={Math.min(yTop, yBottom)}
              width={width}
              height={Math.max(2, Math.abs(yBottom - yTop))}
              className={`app-chart-zone ${zone.kind || ""}`}
            />
          );
        })}

        {lineAnnotations.map((annotation) => (
          <line
            key={annotation.key || annotation.label}
            x1="0"
            x2={width}
            y1={toY(annotation.value)}
            y2={toY(annotation.value)}
            className={annotationClassName(annotation)}
          />
        ))}

        {entryIndex != null && (
          <VerticalMarker x={toX(entryIndex)} height={height} label="Entry Time" kind="entry" />
        )}
        {exitIndex != null && (
          <VerticalMarker x={toX(exitIndex)} height={height} label="Exit Time" kind="exit" />
        )}

        {vwapPath && <path d={vwapPath} className="app-indicator-line vwap" />}
        {ema9Path && <path d={ema9Path} className="app-indicator-line ema-fast" />}
        {ema20Path && <path d={ema20Path} className="app-indicator-line ema-slow" />}

        {hoveredIndex != null && (
          <line
            x1={toX(hoveredIndex)}
            x2={toX(hoveredIndex)}
            y1="10"
            y2={height - 10}
            className="app-hover-line"
          />
        )}

        {chartCandles.map((candle, index) => {
          const x = toX(index);
          const openY = toY(candle.open);
          const closeY = toY(candle.close);
          const highY = toY(candle.high);
          const lowY = toY(candle.low);
          const rising = Number(candle.close || 0) >= Number(candle.open || 0);
          const candleBodyWidth = Math.max(3, Math.min(12, bodyWidth * 0.5));

          return (
            <g key={`${candle.time || index}-${index}`} onMouseEnter={() => setHoveredIndex(index)} onMouseMove={() => setHoveredIndex(index)}>
              <line x1={x} x2={x} y1={highY} y2={lowY} className={rising ? "app-candle-wick up" : "app-candle-wick down"} />
              <rect
                x={x - candleBodyWidth / 2}
                y={Math.min(openY, closeY)}
                width={candleBodyWidth}
                height={Math.max(2, Math.abs(closeY - openY))}
                rx="2"
                className={rising ? "app-candle-body up" : "app-candle-body down"}
              />
              <rect x={x - bodyWidth / 2} y="0" width={Math.max(bodyWidth, 8)} height={height} fill="transparent" />
            </g>
          );
        })}

        {entryIndex != null && entryLine && (
          <TradeMarker x={toX(entryIndex)} y={toY(entryLine.value)} side={side} kind="entry" />
        )}
        {exitIndex != null && exitLine && (
          <TradeMarker x={toX(exitIndex)} y={toY(exitLine.value)} side={side} kind="exit" />
        )}

        {annotationLabels.map((annotation) => (
          <g key={`${annotation.key || annotation.label}-label`}>
            <text x={width - 8} y={annotation.labelY} textAnchor="end" className={`app-chart-label ${annotation.kind || ""}`}>
              {annotation.label} {formatCurrency(annotation.value)}
            </text>
          </g>
        ))}
      </svg>

      <div className="app-chart-caption">
        <span>{symbol || "--"} {timeframe || ""}</span>
        <span>Times {EASTERN_TIME_LABEL}</span>
        <span>VWAP / EMA9 / EMA20</span>
        {chartZones.map((zone) => (
          <span key={zone.key || zone.label}>{zone.label}</span>
        ))}
      </div>
    </div>
  );
}

function VerticalMarker({ x, height, label, kind }) {
  return (
    <g>
      <line x1={x} x2={x} y1="8" y2={height - 8} className={`app-trade-time-line ${kind}`} />
      <text x={x + 5} y="18" className={`app-trade-time-label ${kind}`}>
        {label}
      </text>
    </g>
  );
}

function TradeMarker({ x, y, side, kind }) {
  const shortSide = String(side || "").toLowerCase() === "short";
  const points = trianglePoints(x, y, kind === "entry" && shortSide ? "down" : kind === "exit" && !shortSide ? "down" : "up");
  return <polygon points={points} className={`app-trade-marker ${kind}`} />;
}

function trianglePoints(x, y, direction) {
  if (direction === "down") {
    return `${x - 7},${y - 7} ${x + 7},${y - 7} ${x},${y + 7}`;
  }
  return `${x - 7},${y + 7} ${x + 7},${y + 7} ${x},${y - 7}`;
}

function annotationClassName(annotation) {
  const kind = String(annotation?.kind || "");
  if (kind === "orb-high") return "app-orb-line high";
  if (kind === "orb-low") return "app-orb-line low";
  if (kind === "entry") return "app-trade-line entry";
  if (kind === "exit") return "app-trade-line exit";
  if (kind === "vwap") return "app-trade-line vwap";
  if (kind === "ifvg") return "app-trade-line ifvg";
  if (kind.includes("low")) return "app-trade-line market-low";
  if (kind.includes("high")) return "app-trade-line market-high";
  if (kind === "previous-close") return "app-trade-line previous-close";
  return "app-trade-line";
}

function spreadAnnotationLabels(annotations, toY, height) {
  const labelGap = 16;
  const labels = annotations
    .filter((annotation) => Number(annotation?.value) > 0)
    .map((annotation) => ({
      ...annotation,
      baseY: Math.max(14, Math.min(height - 8, toY(annotation.value))),
    }))
    .sort((first, second) => first.baseY - second.baseY);

  let lastY = -Infinity;
  for (let index = 0; index < labels.length; index++) {
    const label = labels[index];
    label.labelY = Math.max(label.baseY, lastY + labelGap);
    lastY = label.labelY;
  }

  const overflow = labels.length ? labels[labels.length - 1].labelY - (height - 8) : 0;
  if (overflow > 0) {
    for (let index = labels.length - 1; index >= 0; index--) {
      labels[index].labelY = Math.max(14, labels[index].labelY - overflow);
    }
  }

  return labels;
}

function findNearestCandleIndex(candles, targetTime) {
  const target = parseChartTime(targetTime);
  if (!target || !Array.isArray(candles) || candles.length === 0) {
    return null;
  }

  let nearestIndex = 0;
  let nearestDistance = Infinity;
  for (let index = 0; index < candles.length; index++) {
    const candleTime = parseChartTime(candles[index]?.time);
    if (!candleTime) continue;
    const distance = Math.abs(candleTime - target);
    if (distance < nearestDistance) {
      nearestIndex = index;
      nearestDistance = distance;
    }
  }
  return nearestIndex;
}

function parseChartTime(value) {
  if (!value) return null;
  const parsed = Date.parse(String(value).replace(" ", "T"));
  return Number.isNaN(parsed) ? null : parsed;
}

function enrichCandles(rawCandles) {
  if (!Array.isArray(rawCandles)) return [];

  let cumulativeVolume = 0;
  let cumulativeTypicalVolume = 0;
  let ema9 = null;
  let ema20 = null;
  const closes = [];

  return rawCandles.map((candle) => {
    const open = Number(candle?.open || 0);
    const high = Number(candle?.high || 0);
    const low = Number(candle?.low || 0);
    const close = Number(candle?.close || 0);
    const volume = Number(candle?.volume || 0);
    const typical = (high + low + close) / 3;
    cumulativeVolume += volume;
    cumulativeTypicalVolume += typical * volume;
    ema9 = calculateEma(ema9, close, 9);
    ema20 = calculateEma(ema20, close, 20);
    closes.push(close);

    return {
      ...candle,
      open,
      high,
      low,
      close,
      volume,
      vwap: cumulativeVolume > 0 ? cumulativeTypicalVolume / cumulativeVolume : close,
      ema9,
      ema20,
      rsi14: calculateRsi(closes, 14),
    };
  });
}

function calculateEma(previous, close, period) {
  if (!Number.isFinite(close) || close <= 0) return previous || 0;
  if (previous == null) return close;
  const multiplier = 2 / (period + 1);
  return close * multiplier + previous * (1 - multiplier);
}

function calculateRsi(closes, period) {
  if (!Array.isArray(closes) || closes.length <= period) return 0;

  let gains = 0;
  let losses = 0;
  for (let index = closes.length - period; index < closes.length; index++) {
    const change = closes[index] - closes[index - 1];
    if (change >= 0) {
      gains += change;
    } else {
      losses += Math.abs(change);
    }
  }

  if (losses === 0) return 100;
  const rs = gains / losses;
  return 100 - 100 / (1 + rs);
}

function formatCurrency(value) {
  const amount = Number(value || 0);
  if (!Number.isFinite(amount)) return "--";
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

function formatInteger(value) {
  const amount = Number(value || 0);
  if (!Number.isFinite(amount)) return "--";
  return new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 }).format(amount);
}

function formatIndicator(value) {
  const amount = Number(value || 0);
  if (!Number.isFinite(amount) || amount === 0) return "--";
  return amount.toFixed(2);
}
