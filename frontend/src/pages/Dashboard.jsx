import { useCallback, useEffect, useMemo, useState } from "react";
import RunPreview from "../components/RunPreview.jsx";
import { apiFetch } from "../utils/api.js";
import { formatEstTime } from "../utils/time.js";

const MARKET_TIMEFRAMES = [
  { value: "1Min", label: "1m" },
  { value: "5Min", label: "5m" },
  { value: "30Min", label: "30m" },
  { value: "1Hour", label: "1h" },
];

const DEFAULT_STATUS = {
  success: true,
  requiresConfirmation: false,
  running: false,
  message: "",
  symbol: "SPY",
  perTradeBuyingPower: 0,
  takeProfit: 0,
  lossLimit: 0,
  cash: 0,
  accountEquity: 0,
  startingEquity: 0,
  grossPnl: 0,
  realizedPnl: 0,
  unrealizedPnl: 0,
  totalReturn: 0,
  winRate: 0,
  drawdown: 0,
  profitFactor: 0,
  trades: 0,
  closedTrades: 0,
  pullCount: 0,
  liveBotId: 0,
  startedAt: "",
  lastPulledAt: "",
  nextPullAt: "",
  latestBarTime: "",
  latestOpen: 0,
  latestHigh: 0,
  latestLow: 0,
  latestPrice: 0,
  latestVolume: 0,
  latestVwap: 0,
  latestSma9: 0,
  latestSma20: 0,
  latestEma9: 0,
  latestEma20: 0,
  latestRsi14: 0,
  latestAtr14: 0,
  latestVolumeAverage20: 0,
  latestVolumeRatio: 0,
  orbHigh: 0,
  orbLow: 0,
  lastDecision: "Live bot is idle.",
  enabledStrategies: [],
  activeTrade: null,
  activeTrades: [],
  tradeLogs: [],
  marketData: {
    "1Min": [],
    "5Min": [],
    "30Min": [],
    "1Hour": [],
  },
  equityCurve: [],
};

export default function Dashboard({ accountEmail }) {
  const [alpacaCash, setAlpacaCash] = useState("Loading...");
  const [liveSettings, setLiveSettings] = useState({
    equity: "SPY",
    perTradeBuyingPower: "5000",
    lossLimit: "250",
    profitTake: "500",
  });
  const [liveStatus, setLiveStatus] = useState(DEFAULT_STATUS);
  const [enabledStrategies, setEnabledStrategies] = useState([]);
  const [isStarting, setIsStarting] = useState(false);
  const [isStopping, setIsStopping] = useState(false);
  const [showStopModal, setShowStopModal] = useState(false);
  const [feedbackMessage, setFeedbackMessage] = useState("");
  const [selectedMarketTimeframe, setSelectedMarketTimeframe] = useState("1Min");
  const [selectedTrade, setSelectedTrade] = useState(null);

  const applyStatusPayload = useCallback((payload) => {
    const nextStatus = {
      ...DEFAULT_STATUS,
      ...payload,
      enabledStrategies: Array.isArray(payload?.enabledStrategies) ? payload.enabledStrategies : [],
      tradeLogs: Array.isArray(payload?.tradeLogs) ? payload.tradeLogs : [],
      activeTrade: payload?.activeTrade || null,
      activeTrades: Array.isArray(payload?.activeTrades)
        ? payload.activeTrades
        : payload?.activeTrade
          ? [payload.activeTrade]
          : [],
      marketData: {
        ...DEFAULT_STATUS.marketData,
        ...(payload?.marketData || {}),
      },
      equityCurve: Array.isArray(payload?.equityCurve) ? payload.equityCurve : [],
    };

    setLiveStatus(nextStatus);

    if (nextStatus.running) {
      setLiveSettings({
        equity: nextStatus.symbol || "SPY",
        perTradeBuyingPower: String(nextStatus.perTradeBuyingPower ?? 0),
        lossLimit: String(nextStatus.lossLimit ?? 0),
        profitTake: String(nextStatus.takeProfit ?? 0),
      });
    }
  }, []);

  const loadAccountBalance = useCallback(() => {
    if (!accountEmail) {
      setAlpacaCash("No Broker Linked");
      return;
    }

    apiFetch(`/api/balance?email=${encodeURIComponent(accountEmail)}`)
      .then((response) => {
        if (!response.ok) {
          throw new Error("Broker keys not configured.");
        }

        return response.json();
      })
      .then((data) => {
        const rawBalance = parseFloat(data.cash || data.equity || data.portfolio_value || 0);
        setAlpacaCash(formatCurrency(rawBalance));
      })
      .catch((error) => {
        console.error("Error loading Alpaca balance:", error);
        setAlpacaCash("API Disconnected");
      });
  }, [accountEmail]);

  const loadStrategySettings = useCallback(() => {
    apiFetch("/api/strategy")
      .then((response) => {
        if (!response.ok) {
          throw new Error("Failed to load strategy settings.");
        }

        return response.json();
      })
      .then((data) => {
        setEnabledStrategies(Array.isArray(data.enabledStrategies) ? data.enabledStrategies : []);
      })
      .catch((error) => {
        console.error("Error loading strategy settings:", error);
        setEnabledStrategies([]);
      });
  }, []);

  const loadLiveStatus = useCallback(() => {
    if (!accountEmail) {
      setLiveStatus(DEFAULT_STATUS);
      return;
    }

    apiFetch(`/api/live-bot/status?email=${encodeURIComponent(accountEmail)}`)
      .then((response) => {
        if (!response.ok) {
          throw new Error("Failed to load live bot status.");
        }

        return response.json();
      })
      .then((data) => {
        applyStatusPayload(data);
      })
      .catch((error) => {
        console.error("Error loading live bot status:", error);
      });
  }, [accountEmail, applyStatusPayload]);

  useEffect(() => {
    loadAccountBalance();
  }, [loadAccountBalance]);

  useEffect(() => {
    loadStrategySettings();
  }, [loadStrategySettings]);

  useEffect(() => {
    if (!accountEmail) {
      return undefined;
    }

    loadLiveStatus();
    const intervalId = window.setInterval(loadLiveStatus, 5000);
    return () => window.clearInterval(intervalId);
  }, [accountEmail, loadLiveStatus]);

  async function handleStartTrading() {
    if (!accountEmail) {
      return;
    }

    setIsStarting(true);
    setFeedbackMessage("");

    const params = new URLSearchParams({
      email: accountEmail,
      symbol: liveSettings.equity.trim().toUpperCase() || "SPY",
      perTradeBuyingPower: liveSettings.perTradeBuyingPower,
      takeProfit: liveSettings.profitTake,
      lossLimit: liveSettings.lossLimit,
    });

    try {
      const response = await apiFetch(`/api/live-bot/start?${params.toString()}`, { method: "POST" });
      const payload = await readApiResponse(response);

      if (!response.ok) {
        throw new Error(payload.message || payload.text || "Failed to start the live bot.");
      }

      if (payload.json) {
        applyStatusPayload(payload.json);
      }

      setFeedbackMessage(payload.message || "Live bot is running.");
      loadAccountBalance();
    } catch (error) {
      console.error("Error starting live bot:", error);
      setFeedbackMessage(error.message || "Failed to start the live bot.");
    } finally {
      setIsStarting(false);
    }
  }

  async function handleStopTrading(force = false) {
    if (!accountEmail) {
      return;
    }

    setIsStopping(true);
    setFeedbackMessage("");

    const params = new URLSearchParams({
      email: accountEmail,
      force: String(force),
    });

    try {
      const response = await apiFetch(`/api/live-bot/stop?${params.toString()}`, { method: "POST" });
      const payload = await readApiResponse(response);

      if (response.status === 409) {
        if (payload.json) {
          applyStatusPayload(payload.json);
        }
        setFeedbackMessage(payload.message || "There is an active trade in progress.");
        setShowStopModal(true);
        return;
      }

      if (!response.ok) {
        throw new Error(payload.message || payload.text || "Failed to stop the live bot.");
      }

      if (payload.json) {
        applyStatusPayload(payload.json);
      }

      setShowStopModal(false);
      setFeedbackMessage(payload.message || "Live bot stopped.");
      loadAccountBalance();
    } catch (error) {
      console.error("Error stopping live bot:", error);
      setFeedbackMessage(error.message || "Failed to stop the live bot.");
    } finally {
      setIsStopping(false);
    }
  }

  const botEnabled = liveStatus.running;
  const savedStrategies =
    liveStatus.enabledStrategies.length > 0 ? liveStatus.enabledStrategies : enabledStrategies;
  const inputsLocked = botEnabled || isStarting || isStopping;
  const displayedCash =
    liveStatus.cash > 0 ? formatCurrency(liveStatus.cash) : alpacaCash;
  const activeTrades = Array.isArray(liveStatus.activeTrades)
    ? liveStatus.activeTrades
    : liveStatus.activeTrade
      ? [liveStatus.activeTrade]
      : [];
  const selectedCandles = Array.isArray(liveStatus.marketData?.[selectedMarketTimeframe])
    ? liveStatus.marketData[selectedMarketTimeframe]
    : [];
  const latestTrackedPoint = selectedCandles.length > 0 ? selectedCandles[selectedCandles.length - 1] : null;

  const liveRunPreview = {
    id: "live_preview",
    name: "live_preview",
    equity: liveStatus.symbol || liveSettings.equity,
    start: liveStatus.startedAt ? formatEstTime(liveStatus.startedAt) : "—",
    end: liveStatus.lastPulledAt ? formatEstTime(liveStatus.lastPulledAt) : "—",
    startingEquity: liveStatus.startingEquity,
    totalProfit: liveStatus.grossPnl,
    totalReturn: liveStatus.totalReturn,
    trades: liveStatus.trades,
    winRate: liveStatus.winRate,
    drawdown: liveStatus.drawdown,
    profitFactor: liveStatus.profitFactor,
  };

  const liveTradeLogs = useMemo(() => liveStatus.tradeLogs.map((trade) => ({
    id: trade.id,
    symbol: trade.symbol || liveStatus.symbol,
    strategyCode: trade.strategyCode,
    strategyName: trade.strategyName || trade.strategyCode,
    time: trade.time,
    closedAt: trade.closedAt,
    side: trade.side,
    qty: trade.qty,
    entry: trade.entry,
    exit: trade.exit,
    pnl: trade.pnl,
    status: trade.status,
    tradeNotes: trade.tradeNotes,
    returnPct: trade.entry && trade.exit
      ? calculateTradeReturnPct(trade.side, trade.entry, trade.exit)
      : 0,
  })), [liveStatus.symbol, liveStatus.tradeLogs]);

  return (
    <div className="app-page">
      <h2 className="app-title">Live Stock</h2>

      <div className="app-panel">
        <div className="d-flex align-items-start justify-content-between gap-3 flex-wrap">
          <div className="d-flex align-items-center gap-2 flex-wrap">
            <div className="app-status-pill d-inline-flex align-items-center">
              <span className={botEnabled ? "app-status-dot on" : "app-status-dot off"} />
              <span className="app-status-text">Bot Status: {botEnabled ? "ON" : "OFF"}</span>
            </div>
          </div>

          <div className="d-flex align-items-center gap-2 flex-wrap justify-content-end">
            <span className="app-label">Strategies :</span>

            {savedStrategies.length === 0 ? (
              <span className="app-badge">No Strategies Enabled</span>
            ) : (
              savedStrategies.map((strategyName) => (
                <span key={strategyName} className="app-badge">
                  {strategyName}
                </span>
              ))
            )}
          </div>
        </div>

        <div className="row g-3 mt-1">
          <Field label="Equity">
            <input
              type="text"
              value={liveSettings.equity}
              onChange={(event) =>
                setLiveSettings((current) => ({ ...current, equity: event.target.value.toUpperCase() }))
              }
              className="form-control app-input"
              disabled={inputsLocked}
            />
          </Field>

          <Field label="Per Trade Buying Power ($)">
            <input
              type="number"
              value={liveSettings.perTradeBuyingPower}
              onChange={(event) =>
                setLiveSettings((current) => ({ ...current, perTradeBuyingPower: event.target.value }))
              }
              className="form-control app-input"
              disabled={inputsLocked}
            />
          </Field>

          <Field label="Take Loss ($)">
            <input
              type="number"
              value={liveSettings.lossLimit}
              onChange={(event) =>
                setLiveSettings((current) => ({ ...current, lossLimit: event.target.value }))
              }
              className="form-control app-input"
              disabled={inputsLocked}
            />
          </Field>

          <Field label="Profit Take ($)">
            <input
              type="number"
              value={liveSettings.profitTake}
              onChange={(event) =>
                setLiveSettings((current) => ({ ...current, profitTake: event.target.value }))
              }
              className="form-control app-input"
              disabled={inputsLocked}
            />
          </Field>
        </div>

        <div className="d-flex justify-content-start align-items-center gap-3 pt-3 flex-wrap">
          <button
            type="button"
            className={
              botEnabled
                ? "app-btn app-btn-danger app-btn-run px-3"
                : "app-btn app-btn-primary app-btn-run px-3"
            }
            onClick={botEnabled ? () => handleStopTrading(false) : handleStartTrading}
            disabled={isStarting || isStopping || (!botEnabled && (!accountEmail || savedStrategies.length === 0))}
          >
            {isStarting ? "Starting..." : isStopping ? "Stopping..." : botEnabled ? "Stop Live Bot" : "Start Live Bot"}
          </button>
        </div>

        {feedbackMessage && (
          <div className="app-live-feedback mt-3">
            {feedbackMessage}
          </div>
        )}
      </div>

      <div className="row g-3">
        <SummaryCard label="Available Cash" value={displayedCash} />
        <SummaryCard label="Gross P/L" value={formatSignedCurrency(liveStatus.grossPnl)} accent={liveStatus.grossPnl} />
        <SummaryCard
          label="Latest Price"
          value={liveStatus.latestPrice > 0 ? formatCurrency(liveStatus.latestPrice) : "Waiting..."}
        />
        <SummaryCard
          label="Open Trades"
          value={activeTrades.length > 0 ? `${activeTrades.length} open • ${formatSignedCurrency(liveStatus.unrealizedPnl)}` : "Flat"}
          accent={liveStatus.unrealizedPnl}
        />
      </div>

      <div className="app-panel">
        <div className="d-flex align-items-start justify-content-between gap-2 flex-wrap">
          <div className="fw-bold app-kicker mb-2">Live Market Graph</div>

          <div className="app-timeframe-row">
            {MARKET_TIMEFRAMES.map((timeframe) => (
              <button
                key={timeframe.value}
                type="button"
                className={selectedMarketTimeframe === timeframe.value ? "app-filter-btn active" : "app-filter-btn"}
                onClick={() => setSelectedMarketTimeframe(timeframe.value)}
              >
                {timeframe.label}
              </button>
            ))}
          </div>
        </div>

        <LiveMarketChart
          activeTrades={activeTrades}
          candles={selectedCandles}
          orbHigh={liveStatus.orbHigh}
          orbLow={liveStatus.orbLow}
          symbol={liveStatus.symbol || liveSettings.equity}
        />
      </div>

      <div className="app-panel">
        <div className="d-flex align-items-start justify-content-between gap-2 flex-wrap">
          <div className="fw-bold app-kicker mb-2">Live Data</div>

          <div className="app-muted app-kicker">
            Pulls completed: {liveStatus.pullCount}
          </div>
        </div>

        <div className="app-live-grid mt-3">
          <LiveDataCard
            label="Last Pull"
            value={liveStatus.lastPulledAt ? formatEstTime(liveStatus.lastPulledAt) : "Waiting..."}
            detail={liveStatus.nextPullAt ? `Next pull: ${formatEstTime(liveStatus.nextPullAt)}` : "Next pull starts after launch."}
          />
          <LiveDataCard
            label="Latest Minute"
            value={liveStatus.latestBarTime ? formatEstTime(liveStatus.latestBarTime) : "No bar yet"}
            detail={
              liveStatus.latestBarTime
                ? `Open ${formatCurrency(liveStatus.latestOpen)} • High ${formatCurrency(liveStatus.latestHigh)} • Low ${formatCurrency(liveStatus.latestLow)}`
                : "Waiting for Alpaca minute data."
            }
          />
          <LiveDataCard
            label="Current Price"
            value={liveStatus.latestPrice > 0 ? formatCurrency(liveStatus.latestPrice) : "Waiting..."}
            detail={
              liveStatus.latestVolume > 0
                ? `Volume ${formatInteger(liveStatus.latestVolume)}`
                : "Volume will appear after the first minute bar arrives."
            }
          />
          <LiveDataCard
            label="VWAP / RSI"
            value={liveStatus.latestVwap > 0 ? formatCurrency(liveStatus.latestVwap) : "Waiting..."}
            detail={`RSI 14: ${formatIndicator(liveStatus.latestRsi14)} • VWAP dev: ${formatPercentDistance(liveStatus.latestPrice, liveStatus.latestVwap)}`}
          />
          <LiveDataCard
            label="Trend Averages"
            value={latestTrackedPoint ? `EMA9 ${formatCurrency(latestTrackedPoint.ema9)}` : "Waiting..."}
            detail={
              latestTrackedPoint
                ? `EMA20 ${formatCurrency(latestTrackedPoint.ema20)} • SMA20 ${formatCurrency(latestTrackedPoint.sma20)}`
                : "Averages populate after candle data arrives."
            }
          />
          <LiveDataCard
            label="Volatility / Volume"
            value={liveStatus.latestAtr14 > 0 ? `ATR ${formatCurrency(liveStatus.latestAtr14)}` : "Waiting..."}
            detail={
              liveStatus.latestVolumeAverage20 > 0
                ? `20-bar avg vol ${formatInteger(liveStatus.latestVolumeAverage20)} • Ratio ${formatIndicator(liveStatus.latestVolumeRatio)}x`
                : "Volume averages populate after enough bars arrive."
            }
          />
          <LiveDataCard
            label="ORB Range"
            value={liveStatus.orbHigh > 0 ? `${formatCurrency(liveStatus.orbHigh)} / ${formatCurrency(liveStatus.orbLow)}` : "Waiting..."}
            detail={
              liveStatus.orbHigh > liveStatus.orbLow
                ? `Range ${formatCurrency(liveStatus.orbHigh - liveStatus.orbLow)}`
                : "Opening range appears after the first market bars."
            }
          />
          <LiveDataCard
            label="Strategy Decision"
            value={botEnabled ? "Evaluating" : "Idle"}
            detail={liveStatus.lastDecision || "No live decision yet."}
          />
        </div>
      </div>

      <div className="app-panel">
        <div className="fw-bold app-kicker mb-2">Balance Tracking</div>
        <EquityCurveChart points={liveStatus.equityCurve} />
      </div>

      <RunPreview
        run={liveRunPreview}
        trades={liveTradeLogs}
        showTradeLogs={true}
        showCapitalCards={false}
        onOpenTrade={setSelectedTrade}
      />

      <ActiveTradesPanel
        activeTrades={activeTrades}
        symbol={liveStatus.symbol || liveSettings.equity}
      />

      {selectedTrade && (
        <TradeDetailModal
          candlesByTimeframe={liveStatus.marketData}
          onClose={() => setSelectedTrade(null)}
          trade={selectedTrade}
        />
      )}

      {showStopModal && (
        <DashboardModal onClose={() => setShowStopModal(false)}>
          <div className="fw-bold mb-2">Stop Live Bot?</div>
          <div className="app-muted mb-3">
            There are active trades in progress. Confirming stop will send exit orders first and then halt the live engine.
          </div>

          <div className="d-flex justify-content-end gap-2 flex-wrap">
            <button type="button" className="app-btn px-3" onClick={() => setShowStopModal(false)} disabled={isStopping}>
              Cancel
            </button>
            <button
              type="button"
              className="app-btn app-btn-danger px-3"
              onClick={() => handleStopTrading(true)}
              disabled={isStopping}
            >
              {isStopping ? "Stopping..." : "Confirm Stop"}
            </button>
          </div>
        </DashboardModal>
      )}
    </div>
  );
}

function LiveMarketChart({ candles, symbol, orbHigh, orbLow, activeTrades = [] }) {
  const [hoveredIndex, setHoveredIndex] = useState(null);

  if (!Array.isArray(candles) || candles.length === 0) {
    return (
      <div className="app-chart-empty">
        Start the live bot to populate the market graph from Alpaca candles.
      </div>
    );
  }

  const width = 980;
  const height = 330;
  const prices = candles.flatMap((candle) => [
    Number(candle.high || 0),
    Number(candle.low || 0),
    Number(candle.vwap || 0),
    Number(candle.ema9 || 0),
    Number(candle.ema20 || 0),
  ]).filter((value) => Number.isFinite(value) && value > 0);

  if (Number(orbHigh) > 0) prices.push(Number(orbHigh));
  if (Number(orbLow) > 0) prices.push(Number(orbLow));
  activeTrades.forEach((trade) => {
    if (trade?.entryPrice) prices.push(Number(trade.entryPrice));
    if (trade?.effectiveStopPrice) prices.push(Number(trade.effectiveStopPrice));
    if (trade?.effectiveTargetPrice) prices.push(Number(trade.effectiveTargetPrice));
  });
  if (prices.length === 0) prices.push(0, 1);

  const min = Math.min(...prices);
  const max = Math.max(...prices);
  const range = max - min || 1;
  const bodyWidth = width / Math.max(candles.length, 1);
  const hoveredCandle = candles[Math.min(Math.max(hoveredIndex ?? candles.length - 1, 0), candles.length - 1)];

  const toY = (price) => height - (((Number(price || 0) - min) / range) * (height - 36) + 18);
  const pointPath = (key) => candles
    .map((candle, index) => {
      const x = index * bodyWidth + bodyWidth / 2;
      const y = toY(candle[key]);
      return `${index === 0 ? "M" : "L"} ${x} ${y}`;
    })
    .join(" ");

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
        aria-label={`${symbol} live market candles`}
        onMouseLeave={() => setHoveredIndex(null)}
      >
        {Number(orbHigh) > 0 && <line x1="0" x2={width} y1={toY(orbHigh)} y2={toY(orbHigh)} className="app-orb-line high" />}
        {Number(orbLow) > 0 && <line x1="0" x2={width} y1={toY(orbLow)} y2={toY(orbLow)} className="app-orb-line low" />}
        {activeTrades.map((trade, index) => (
          <g key={`active-trade-${trade.id || index}`}>
            {trade?.entryPrice && <line x1="0" x2={width} y1={toY(trade.entryPrice)} y2={toY(trade.entryPrice)} className="app-trade-line entry" />}
            {trade?.effectiveStopPrice && <line x1="0" x2={width} y1={toY(trade.effectiveStopPrice)} y2={toY(trade.effectiveStopPrice)} className="app-trade-line stop" />}
            {trade?.effectiveTargetPrice && <line x1="0" x2={width} y1={toY(trade.effectiveTargetPrice)} y2={toY(trade.effectiveTargetPrice)} className="app-trade-line target" />}
          </g>
        ))}

        <path d={pointPath("vwap")} className="app-indicator-line vwap" />
        <path d={pointPath("ema9")} className="app-indicator-line ema-fast" />
        <path d={pointPath("ema20")} className="app-indicator-line ema-slow" />

        {hoveredIndex != null && (
          <line
            x1={hoveredIndex * bodyWidth + bodyWidth / 2}
            x2={hoveredIndex * bodyWidth + bodyWidth / 2}
            y1="10"
            y2={height - 10}
            className="app-hover-line"
          />
        )}

        {candles.map((candle, index) => {
          const x = index * bodyWidth + bodyWidth / 2;
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
      </svg>

      <div className="app-chart-caption">
        <span>{symbol}</span>
        <span>VWAP / EMA9 / EMA20</span>
        {Number(orbHigh) > 0 && <span>ORB {formatCurrency(orbHigh)} / {formatCurrency(orbLow)}</span>}
        {activeTrades.length > 0 && <span>{activeTrades.length} open trade{activeTrades.length === 1 ? "" : "s"}</span>}
      </div>
    </div>
  );
}

function EquityCurveChart({ points }) {
  const [hoveredIndex, setHoveredIndex] = useState(null);

  if (!Array.isArray(points) || points.length === 0) {
    return <div className="app-chart-empty">Equity snapshots will appear after the first live pull.</div>;
  }

  const width = 980;
  const height = 230;
  const values = points.map((point) => Number(point.equity || 0));
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  const step = points.length > 1 ? width / (points.length - 1) : width;
  const hoveredPoint = points[Math.min(Math.max(hoveredIndex ?? points.length - 1, 0), points.length - 1)];
  const toY = (value) => height - (((Number(value || 0) - min) / range) * (height - 26) + 13);
  const path = points
    .map((point, index) => `${index === 0 ? "M" : "L"} ${index * step} ${toY(point.equity)}`)
    .join(" ");

  return (
    <div className="app-chart-shell">
      <div className="app-chart-hover">
        <strong>{hoveredPoint?.time ? formatEstTime(hoveredPoint.time) : "Equity"}</strong>
        <div>
          <span>Equity {formatCurrency(hoveredPoint?.equity)}</span>
          <span>Cash {formatCurrency(hoveredPoint?.cash)}</span>
          <span>P/L {formatSignedCurrency(hoveredPoint?.totalPnl)}</span>
        </div>
      </div>
      <svg viewBox={`0 0 ${width} ${height}`} className="app-equity-svg" role="img" aria-label="Live equity curve" onMouseLeave={() => setHoveredIndex(null)}>
        <path d={path} className="app-equity-line" />
        {hoveredIndex != null && (
          <line x1={hoveredIndex * step} x2={hoveredIndex * step} y1="10" y2={height - 10} className="app-hover-line" />
        )}
        {points.map((point, index) => (
          <circle
            key={`${point.time || index}-${index}`}
            cx={index * step}
            cy={toY(point.equity)}
            r={points.length === 1 ? 5 : 3}
            className="app-equity-hit"
            onMouseEnter={() => setHoveredIndex(index)}
            onMouseMove={() => setHoveredIndex(index)}
          />
        ))}
      </svg>
      <div className="app-chart-caption">
        <span>Start {formatCurrency(points[0]?.equity)}</span>
        <span>Current {formatCurrency(points[points.length - 1]?.equity)}</span>
      </div>
    </div>
  );
}

function ActiveTradesPanel({ activeTrades, symbol }) {
  const trades = Array.isArray(activeTrades) ? activeTrades : [];

  return (
    <div className="app-panel">
      <div className="d-flex align-items-start justify-content-between gap-2 flex-wrap">
        <div>
          <div className="fw-bold app-kicker mb-2">Currently Live Trades</div>
        </div>

        <div className="app-muted app-kicker">
          {trades.length} open
        </div>
      </div>

      {trades.length === 0 ? (
        <div className="app-chart-empty mt-3">No active trades.</div>
      ) : (
        <div className="app-table-wrap">
          <div className="app-grid-head app-active-trades-grid">
            <div>Strategy</div>
            <div>Side</div>
            <div>Qty</div>
            <div>Entry</div>
            <div>Stop</div>
            <div>Target</div>
            <div>Current</div>
            <div>Open P/L</div>
            <div>Opened</div>
          </div>

          {trades.map((trade, index) => (
            <div key={`${trade.id || trade.openedAt || index}-${index}`} className="app-grid-row app-active-trades-grid">
              <div>
                <div className="fw-bold">{trade.strategyName || trade.strategyCode || "--"}</div>
                <div className="app-muted app-kicker">{symbol || "--"}</div>
              </div>
              <div>{trade.side || "--"}</div>
              <div>{formatShareCount(trade.qty)}</div>
              <div>{formatCurrency(trade.entryPrice)}</div>
              <div>{formatCurrency(trade.effectiveStopPrice || trade.stopPrice)}</div>
              <div>{formatCurrency(trade.effectiveTargetPrice || trade.targetPrice)}</div>
              <div>{formatCurrency(trade.currentPrice || trade.entryPrice)}</div>
              <div className={Number(trade.unrealizedPnl || 0) < 0 ? "app-pnl-neg fw-bold" : Number(trade.unrealizedPnl || 0) > 0 ? "app-pnl-pos fw-bold" : "fw-bold"}>
                {formatSignedCurrency(trade.unrealizedPnl)}
              </div>
              <div>{formatEstTime(trade.openedAt || "--")}</div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function TradeDetailModal({ trade, candlesByTimeframe, onClose }) {
  const [chartTimeframe, setChartTimeframe] = useState("1Min");
  const candles = Array.isArray(candlesByTimeframe?.[chartTimeframe]) ? candlesByTimeframe[chartTimeframe] : [];
  const metricRows = [
    ["Symbol", trade.symbol],
    ["Strategy", trade.strategyName || trade.strategyCode],
    ["Status", trade.status],
    ["Side", trade.side],
    ["Quantity", formatShareCount(trade.qty)],
    ["Entry Time", formatEstTime(trade.time)],
    ["Exit Time", trade.closedAt ? formatEstTime(trade.closedAt) : "Open"],
    ["Entry Price", trade.entry == null ? "--" : formatCurrency(trade.entry)],
    ["Exit Price", trade.exit == null ? "--" : formatCurrency(trade.exit)],
    ["P/L", trade.pnl == null ? "--" : formatSignedCurrency(trade.pnl)],
    ["Return", `${formatIndicator(trade.returnPct)}%`],
    ["Notes", trade.tradeNotes || "--"],
  ];

  return (
    <div className="app-modal-backdrop" onClick={onClose}>
      <div className="app-modal-card app-trade-modal" role="dialog" aria-modal="true" onClick={(event) => event.stopPropagation()}>
        <div className="d-flex align-items-start justify-content-between gap-2 mb-3">
          <div>
            <div className="fw-bold">Trade Review</div>
            <div className="app-muted app-kicker">{`Trade #${trade.id || "--"} • ${trade.symbol || "--"}`}</div>
          </div>
          <button type="button" className="app-btn px-3" onClick={onClose}>
            Close
          </button>
        </div>

        <div className="app-detail-grid">
          {metricRows.map(([label, value]) => (
            <div key={label} className="app-subpanel">
              <div className="app-label">{label}</div>
              <div className={label === "P/L" && Number(trade.pnl || 0) < 0 ? "fw-bold mt-1 app-pnl-neg" : label === "P/L" && Number(trade.pnl || 0) > 0 ? "fw-bold mt-1 app-pnl-pos" : "fw-bold mt-1"}>
                {value || "--"}
              </div>
            </div>
          ))}
        </div>

        <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap mt-3">
          <div className="fw-bold app-kicker">Trade Snapshot</div>
          <div className="app-timeframe-row">
            {MARKET_TIMEFRAMES.map((timeframe) => (
              <button
                key={timeframe.value}
                type="button"
                className={chartTimeframe === timeframe.value ? "app-filter-btn active" : "app-filter-btn"}
                onClick={() => setChartTimeframe(timeframe.value)}
              >
                {timeframe.label}
              </button>
            ))}
          </div>
        </div>

        <LiveMarketChart
          activeTrades={[{
            entryPrice: trade.entry,
            effectiveStopPrice: null,
            effectiveTargetPrice: null,
          }]}
          candles={candles}
          orbHigh={0}
          orbLow={0}
          symbol={trade.symbol || "--"}
        />
      </div>
    </div>
  );
}

function Field({ label, children }) {
  return (
    <div className="col-12 col-md-6 col-xl-3">
      <label className="d-grid gap-1">
        <span className="app-label">{label}</span>
        {children}
      </label>
    </div>
  );
}

function SummaryCard({ label, value, accent = 0 }) {
  const valueClass = accent > 0 ? "app-card-value app-pnl-pos" : accent < 0 ? "app-card-value app-pnl-neg" : "app-card-value";

  return (
    <div className="col-12 col-md-6 col-xl-3">
      <div className="app-card h-100">
        <div className="app-card-label">{label}</div>
        <div className={valueClass}>{value}</div>
      </div>
    </div>
  );
}

function LiveDataCard({ label, value, detail, accent = 0 }) {
  const valueClass = accent > 0 ? "app-live-value app-pnl-pos" : accent < 0 ? "app-live-value app-pnl-neg" : "app-live-value";

  return (
    <div className="app-subpanel app-live-card">
      <div className="app-label">{label}</div>
      <div className={valueClass}>{value}</div>
      <div className="app-live-detail">{detail}</div>
    </div>
  );
}

function DashboardModal({ children, onClose }) {
  return (
    <div className="app-modal-backdrop">
      <div className="app-modal-card">
        <div className="d-flex align-items-center justify-content-between gap-2 mb-3">
          <div className="fw-bold">Live Bot Warning</div>
          <button type="button" className="app-btn px-3" onClick={onClose}>
            Close
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

async function readApiResponse(response) {
  const text = await response.text();
  try {
    const json = JSON.parse(text);
    return {
      json,
      text,
      message: json?.message || "",
    };
  } catch {
    return {
      json: null,
      text,
      message: text,
    };
  }
}

function formatCurrency(value) {
  const amount = Number(value || 0);
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

function formatSignedCurrency(value) {
  const amount = Number(value || 0);
  const prefix = amount > 0 ? "+" : "";
  return `${prefix}${formatCurrency(amount)}`;
}

function formatInteger(value) {
  const amount = Number(value || 0);
  return new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 }).format(amount);
}

function formatIndicator(value) {
  const amount = Number(value || 0);
  if (!Number.isFinite(amount)) {
    return "--";
  }

  return new Intl.NumberFormat("en-US", {
    minimumFractionDigits: amount % 1 === 0 ? 0 : 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

function formatPercentDistance(price, baseline) {
  const currentPrice = Number(price || 0);
  const basePrice = Number(baseline || 0);
  if (!Number.isFinite(currentPrice) || !Number.isFinite(basePrice) || basePrice <= 0) {
    return "--";
  }

  return `${formatIndicator(((currentPrice - basePrice) / basePrice) * 100)}%`;
}

function calculateTradeReturnPct(side, entry, exit) {
  const entryPrice = Number(entry || 0);
  const exitPrice = Number(exit || 0);
  if (entryPrice <= 0 || exitPrice <= 0) {
    return 0;
  }

  const rawReturn = String(side || "").toUpperCase() === "SHORT"
    ? ((entryPrice - exitPrice) / entryPrice) * 100
    : ((exitPrice - entryPrice) / entryPrice) * 100;
  return Number(rawReturn.toFixed(2));
}

function formatShareCount(value) {
  const shares = Number(value || 0);
  if (!Number.isFinite(shares)) {
    return "0";
  }

  return shares % 1 === 0 ? `${shares}` : shares.toFixed(2);
}
