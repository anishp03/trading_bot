import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useOutletContext } from "react-router-dom";
import { apiFetch, isApiNetworkError } from "../utils/api.js";
import { EASTERN_TIME_LABEL, formatEstTime } from "../utils/time.js";

const DEFAULT_SYMBOLS = ["MES", "MNQ", "NQ", "MGC", "ES", "M2K"];
const TIMEFRAME_OPTIONS = [
  { value: "1m", label: "1m" },
  { value: "5m", label: "5m" },
  { value: "30m", label: "30m" },
  { value: "1h", label: "1h" },
];
const LIVE_MONITOR_REFRESH_MS = 30000;
const MIN_OPENING_CHART_BARS = 24;
const DEFAULT_PROFILE = "TOPSTEP_50K_RESEARCH";
const PROFILE_ACCOUNTS = {
  TOPSTEP_50K_RESEARCH: { label: "50K Research Paper", accountId: "22539378" },
  TOPSTEP_150K_PRACTICE: { label: "150K Practice", accountId: "22539378" },
  TOPSTEP_50K_COMBINE: { label: "50K Combine", accountId: "22529998" },
};
const FALLBACK_PROFILE = {
  code: "TOPSTEP_50K_RESEARCH",
  name: "Topstep 50K Research",
  accountSize: 50000,
  maxTrailingDrawdown: 2000,
  dailyLossLimit: 1000,
  maxRiskPerTrade: 700,
  maxContracts: 5,
  maxMicroContracts: 50,
  maxOpenPositions: 3,
  maxAggregateContracts: 50,
  maxAggregateMiniUnits: 5,
};

export default function FuturesLive() {
  const [selectedChartSymbol, setSelectedChartSymbol] = useState(DEFAULT_SYMBOLS[0]);
  const [selectedTimeframe, setSelectedTimeframe] = useState("1m");
  const [selectedProfileCode, setSelectedProfileCode] = useState(DEFAULT_PROFILE);
  const [fundedProfiles, setFundedProfiles] = useState([]);
  const [liveStatus, setLiveStatus] = useState(null);
  const [realtimeStatus, setRealtimeStatus] = useState(null);
  const [snapshotState, setSnapshotState] = useState(null);
  const [liveDecisions, setLiveDecisions] = useState([]);
  const [liveMetrics, setLiveMetrics] = useState(null);
  const [liveMonitor, setLiveMonitor] = useState(null);
  const [monitorCache, setMonitorCache] = useState({});
  const [feedback, setFeedback] = useState("");
  const [busyAction, setBusyAction] = useState("");
  const [chartTransitioning, setChartTransitioning] = useState(false);
  const [lastMonitorRefreshAt, setLastMonitorRefreshAt] = useState("");
  const [backendOnline, setBackendOnline] = useState(true);
  const chartTransitionTimer = useRef(null);
  const botStartedRef = useRef(false);
  const {
    futuresSidebarOnline = true,
    futuresSidebarStatus = null,
    refreshFuturesSidebarStatus = null,
  } = useOutletContext() || {};

  const selectedProfile = useMemo(() => {
    return fundedProfiles.find((profile) => profile.code === selectedProfileCode) || FALLBACK_PROFILE;
  }, [fundedProfiles, selectedProfileCode]);

  const activeSnapshot = snapshotState?.snapshot || liveStatus?.liveStrategySnapshot || null;
  const snapshotSymbols = parseSymbolCsv(activeSnapshot?.symbols);
  const liveStrategySymbols = snapshotSymbols.length ? snapshotSymbols : DEFAULT_SYMBOLS;
  const monitorSymbols = DEFAULT_SYMBOLS;
  const accountPreset = PROFILE_ACCOUNTS[selectedProfileCode] || PROFILE_ACCOUNTS[DEFAULT_PROFILE];
  const symbolsCsv = monitorSymbols.join(",");
  const backendOffline = backendOnline === false || futuresSidebarOnline === false || futuresSidebarStatus?.backend?.online === false;
  const botStarted = !backendOffline && Boolean(liveStatus?.running);
  const feedRunning = !backendOffline && Boolean(realtimeStatus?.running || liveMonitor?.realtimeRunning);
  const monitorFeedOpen = !backendOffline && Boolean(feedRunning || liveMonitor?.historyPolling);
  const monitorDataActive = monitorFeedOpen;
  const graphReadiness = liveMonitor?.graphReadiness || null;
  const graphReady = monitorDataActive && Boolean(graphReadiness?.ready);
  const graphBuilding = monitorDataActive && !graphReady;
  const botControlActive = Boolean(liveStatus?.running || realtimeStatus?.running || liveMonitor?.realtimeRunning);
  const displayedDecisions = useMemo(() => (botStarted ? liveDecisions : []), [botStarted, liveDecisions]);
  const liveTrades = displayedDecisions.filter((decision) => isEntryDecision(decision));
  const symbolStates = useMemo(() => (Array.isArray(liveMonitor?.symbolStates) ? liveMonitor.symbolStates : []), [liveMonitor?.symbolStates]);
  const displayMonitor = useMemo(
    () => resolveDisplayMonitor(liveMonitor, monitorCache, selectedTimeframe, monitorDataActive),
    [monitorDataActive, liveMonitor, monitorCache, selectedTimeframe]
  );
  const rawChartCandles = useMemo(
    () => (graphReady ? displayMonitor?.marketData?.[selectedChartSymbol] || [] : []),
    [graphReady, displayMonitor, selectedChartSymbol]
  );
  const chartCandles = useMemo(
    () => {
      if (!graphReady) return [];
      const normalized = rawChartCandles
        .map(normalizeCandle)
        .filter((candle) => candle.time && Number(candle.close || 0) > 0);
      return normalized;
    },
    [graphReady, rawChartCandles]
  );
  const chartHasWarmupWindow = chartCandles.some((candle) => !candle.live) || chartCandles.length >= MIN_OPENING_CHART_BARS;
  const warmupPending = graphBuilding || (graphReady && chartCandles.length > 0 && !chartHasWarmupWindow);
  const chartDisplayCandles = chartCandles;
  const selectedHasLiveCandles = graphReady && chartCandles.length > 0;
  const selectedSymbolState = useMemo(
    () => symbolStates.find((state) => String(state.symbol || "").toUpperCase() === selectedChartSymbol) || null,
    [selectedChartSymbol, symbolStates]
  );
  const selectedChartMarkPrice = Number(selectedSymbolState?.lastPrice || latestChartPrice(chartDisplayCandles));
  const feedStaleSeconds = Number(liveMonitor?.feedStaleSeconds ?? -1);
  const marketSession = liveMonitor?.marketSession || liveStatus?.marketSession || estimateFuturesMarketSession();
  const marketIdle = !backendOffline && !monitorDataActive;
  const metrics = botStarted ? liveMetrics : null;
  const sidebarStartReady = Boolean(futuresSidebarStatus?.strategyConfig?.active && futuresSidebarStatus?.topstepApi?.ready);
  const realChartTrades = useMemo(
    () => buildChartTrades(displayedDecisions, selectedChartSymbol, selectedChartMarkPrice),
    [displayedDecisions, selectedChartMarkPrice, selectedChartSymbol]
  );
  const chartTrades = realChartTrades;
  const botTrackers = useMemo(
    () => buildSymbolTrackers({
      symbols: monitorSymbols,
      states: symbolStates,
      decisions: displayedDecisions,
      marketData: liveMonitor?.marketData || {},
      botStarted,
    }),
    [botStarted, displayedDecisions, liveMonitor?.marketData, monitorSymbols, symbolStates]
  );
  const canStartLiveBot = !backendOffline && Boolean(sidebarStartReady && activeSnapshot && !liveStatus?.running);
  const controlMessage = feedback || (botStarted ? liveStatus?.lastDecision || realtimeStatus?.lastMessage : feedRunning ? realtimeStatus?.lastMessage || liveMonitor?.realtimeMessage : "");
  const launchTone = backendOffline ? "offline" : botControlActive ? "live" : sidebarStartReady ? "ready" : "pending";
  const launchLabel = backendOffline ? "Bot Status: OFF" : botStarted ? "Running" : feedRunning ? "Feed Live" : sidebarStartReady ? "Ready" : marketIdle && !marketSession?.entryWindowOpen ? "Closed" : "Setup";

  useEffect(() => {
    loadFundedProfiles();
  }, []);

  useEffect(() => {
    if (!monitorSymbols.includes(selectedChartSymbol)) {
      setSelectedChartSymbol(monitorSymbols[0] || DEFAULT_SYMBOLS[0]);
    }
  }, [symbolsCsv, selectedChartSymbol]);

  useEffect(() => {
    let resetFrame = null;
    if (botStarted && !botStartedRef.current) {
      resetFrame = requestAnimationFrame(() => {
        setSelectedChartSymbol(monitorSymbols[0] || DEFAULT_SYMBOLS[0]);
        setSelectedTimeframe("1m");
      });
    }
    botStartedRef.current = botStarted;
    return () => {
      if (resetFrame) cancelAnimationFrame(resetFrame);
    };
  }, [botStarted, monitorSymbols]);

  useEffect(() => {
    refreshLiveData();
    const intervalId = window.setInterval(refreshLiveData, LIVE_MONITOR_REFRESH_MS);
    return () => window.clearInterval(intervalId);
  }, [symbolsCsv, selectedTimeframe, selectedProfileCode]);

  useEffect(() => {
    return () => {
      if (chartTransitionTimer.current) {
        window.clearTimeout(chartTransitionTimer.current);
      }
    };
  }, []);

  function refreshLiveData() {
    loadLiveStatus();
    loadRealtimeStatus();
    loadLiveSnapshot();
    loadLiveMetrics();
    loadLiveMonitor();
  }

  function noteBackendResponse(response) {
    setBackendOnline(true);
    if (!response.ok) {
      throw new Error(`Backend returned ${response.status}`);
    }
    return response;
  }

  function noteBackendError(label, error) {
    console.error(label, error);
    if (isApiNetworkError(error)) {
      setBackendOnline(false);
      setLastMonitorRefreshAt(new Date().toISOString());
    } else {
      setBackendOnline(true);
    }
  }

  function loadFundedProfiles() {
    apiFetch("/api/futures/funded-rule-profiles")
      .then(noteBackendResponse)
      .then((response) => response.json())
      .then((data) => {
        const topstepProfiles = Array.isArray(data)
          ? data.filter((profile) => profile.code === "TOPSTEP_50K_RESEARCH" || profile.code === "TOPSTEP_150K_PRACTICE" || profile.code === "TOPSTEP_50K_COMBINE")
          : [];
        setFundedProfiles(topstepProfiles.length ? topstepProfiles : [FALLBACK_PROFILE]);
      })
      .catch((error) => {
        noteBackendError("Error loading funded profiles:", error);
        setFundedProfiles([FALLBACK_PROFILE]);
      });
  }

  function loadLiveStatus() {
    apiFetch("/api/futures/live/status")
      .then(noteBackendResponse)
      .then((response) => response.json())
      .then((data) => {
        setLiveStatus(data || null);
        loadLiveDecisions(data || null);
      })
      .catch((error) => {
        noteBackendError("Error loading live status:", error);
        if (isApiNetworkError(error)) {
          setLiveStatus(null);
          setLiveDecisions([]);
        }
      });
  }

  function loadRealtimeStatus() {
    apiFetch("/api/futures/live/realtime/status")
      .then(noteBackendResponse)
      .then((response) => response.json())
      .then((data) => setRealtimeStatus(data || null))
      .catch((error) => {
        noteBackendError("Error loading realtime status:", error);
        if (isApiNetworkError(error)) setRealtimeStatus(null);
      });
  }

  function loadLiveSnapshot() {
    apiFetch("/api/futures/live/strategy-snapshot")
      .then(noteBackendResponse)
      .then((response) => response.json())
      .then((data) => setSnapshotState(data || null))
      .catch((error) => {
        noteBackendError("Error loading live strategy snapshot:", error);
        if (isApiNetworkError(error)) setSnapshotState(null);
      });
  }

  function loadLiveDecisions(status = liveStatus) {
    const sessionId = Number(status?.running ? status?.sessionId || 0 : 0);
    if (!sessionId) {
      setLiveDecisions([]);
      return;
    }
    apiFetch(`/api/futures/live/decisions?sessionId=${sessionId}&limit=160`)
      .then(noteBackendResponse)
      .then((response) => response.json())
      .then((data) => setLiveDecisions(Array.isArray(data) ? data : []))
      .catch((error) => {
        noteBackendError("Error loading live decisions:", error);
        setLiveDecisions([]);
      });
  }

  function loadLiveMetrics() {
    apiFetch("/api/futures/live/metrics")
      .then(noteBackendResponse)
      .then((response) => response.json())
      .then((data) => setLiveMetrics(data || null))
      .catch((error) => {
        noteBackendError("Error loading live metrics:", error);
        if (isApiNetworkError(error)) setLiveMetrics(null);
      });
  }

  function loadLiveMonitor() {
    const params = new URLSearchParams({
      symbols: symbolsCsv || DEFAULT_SYMBOLS.join(","),
      limit: String(monitorLimitForTimeframe(selectedTimeframe)),
      timeframe: selectedTimeframe,
    });
    apiFetch(`/api/futures/live/monitor?${params.toString()}`)
      .then(noteBackendResponse)
      .then((response) => response.json())
      .then((data) => {
        if (data) {
          const responseTimeframe = normalizeClientTimeframe(data.timeframe || selectedTimeframe);
          const monitorWithTimeframe = { ...data, timeframe: responseTimeframe };
          setLiveMonitor(monitorWithTimeframe);
          setMonitorCache((current) => ({
            ...current,
            [responseTimeframe]: monitorWithTimeframe,
          }));
        } else {
          setLiveMonitor(null);
        }
        setLastMonitorRefreshAt(new Date().toISOString());
      })
      .catch((error) => {
        noteBackendError("Error loading live monitor:", error);
        if (isApiNetworkError(error)) {
          setLiveMonitor(null);
          setMonitorCache({});
        }
      });
  }

  function selectChartSymbol(symbol) {
    if (symbol === selectedChartSymbol) return;
    transitionChart(() => setSelectedChartSymbol(symbol));
  }

  function changeTimeframe(value) {
    if (value === selectedTimeframe) return;
    transitionChart(() => setSelectedTimeframe(value));
  }

  function transitionChart(update) {
    setChartTransitioning(true);
    if (chartTransitionTimer.current) {
      window.clearTimeout(chartTransitionTimer.current);
    }
    update();
    chartTransitionTimer.current = window.setTimeout(() => setChartTransitioning(false), 120);
  }

  async function updateLiveStrategy() {
    await runAction("update-live", async () => {
      const params = new URLSearchParams({ symbols: symbolsCsv, fundedProfile: selectedProfile.code });
      const response = await apiFetch(`/api/futures/live/strategy-snapshot?${params.toString()}`, { method: "POST" });
      const payload = await readApiResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(payload.json?.message || payload.text || "Failed to update Live Strategy.");
      }
      setSnapshotState(payload.json || null);
      setFeedback(payload.json?.message || "Live Strategy updated.");
      refreshFuturesSidebarStatus?.();
      refreshLiveData();
    });
  }

  async function startLiveBot() {
    await runAction("start", async () => {
      if (!activeSnapshot) {
        throw new Error("Copy the Backtest Strategy into the Live Strategy slot before starting the live bot.");
      }
      const freshSidebarStatus = typeof refreshFuturesSidebarStatus === "function"
        ? await refreshFuturesSidebarStatus()
        : futuresSidebarStatus;
      validateSidebarStartStatus(freshSidebarStatus);
      const openingSymbol = monitorSymbols[0] || "MNQ";
      setMonitorCache({});
      setLiveMonitor(null);
      setSelectedChartSymbol(openingSymbol);
      setSelectedTimeframe("1m");
      const params = new URLSearchParams({
        symbol: openingSymbol,
        executionMode: "TOPSTEPX",
        fundedProfile: selectedProfile.code,
        accountSize: String(selectedProfile.accountSize ?? 50000),
        maxTrailingDrawdown: String(selectedProfile.maxTrailingDrawdown ?? 2000),
        dailyLossLimit: String(selectedProfile.dailyLossLimit ?? 1000),
        maxRiskPerTrade: String(selectedProfile.maxRiskPerTrade ?? 400),
        maxContracts: String(selectedProfile.maxMicroContracts ?? selectedProfile.maxContracts ?? 50),
        maxAggregateMiniUnits: String(selectedProfile.maxAggregateMiniUnits ?? 5),
      });
      const response = await apiFetch(`/api/futures/live/start?${params.toString()}`, { method: "POST" });
      const payload = await readApiResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(payload.json?.message || payload.text || "Failed to start live bot.");
      }
      setLiveStatus(payload.json?.status || null);
      setFeedback(payload.json?.message || "Live bot started with TopstepX 150K practice order automation.");
      refreshFuturesSidebarStatus?.();
      refreshLiveData();
    });
  }

  async function stopLiveBot() {
    await runAction("stop", async () => {
      const response = await apiFetch("/api/futures/live/stop", { method: "POST" });
      const payload = await readApiResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(payload.json?.message || payload.text || "Failed to stop live bot.");
      }
      setLiveStatus(payload.json?.status || null);
      if (feedRunning) {
        const priceResponse = await apiFetch("/api/futures/live/realtime/stop", { method: "POST" });
        const pricePayload = await readApiResponse(priceResponse);
        if (priceResponse.ok && pricePayload.json?.success !== false) {
          setRealtimeStatus(pricePayload.json?.status || null);
        }
      }
      setFeedback(payload.json?.message || "Live bot stopped.");
      refreshFuturesSidebarStatus?.();
      refreshLiveData();
    });
  }

  function validateSidebarStartStatus(status) {
    if (!status?.backend?.online) {
      throw new Error("Backend Status is OFF in the sidebar. Start Live Bot will unlock when the backend status is back on.");
    }
    if (!status?.strategyConfig?.active) {
      throw new Error("Strategy Config is OFF in the sidebar. Copy Backtest Strategy into the Live Strategy slot first.");
    }
    if (!status?.topstepApi?.ready) {
      throw new Error("TopStep API is OFF in the sidebar. Save and test the TopStep API connection first.");
    }
  }

  async function runAction(action, handler) {
    setBusyAction(action);
    setFeedback("");
    try {
      await handler();
    } catch (error) {
      console.error(error);
      if (isApiNetworkError(error)) {
        setBackendOnline(false);
      }
      setFeedback(error.message || "Action failed.");
    } finally {
      setBusyAction("");
    }
  }

  return (
    <div className="app-page futures-live-page">
      <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap">
        <h2 className="app-title m-0">Live Futures Bot</h2>
        {!backendOffline && (
          <span className={liveStatus?.running ? "app-badge app-positive-badge" : "app-badge app-neutral-badge"}>
            {liveStatus?.running ? "Running" : "Stopped"}
          </span>
        )}
      </div>

      <section className="app-panel futures-live-control-panel">
        <div className="futures-launch-shell">
          <div className="futures-launch-main">
            <div className={`futures-launch-state ${launchTone}`}>
              <span aria-hidden="true" />
              {launchLabel}
            </div>
            <div className="futures-launch-title-row">
              <div className="fw-bold app-kicker">Live Controls</div>
            </div>
            {controlMessage && <div className="app-muted app-kicker mt-1">{controlMessage}</div>}
          </div>

          <div className="futures-live-action-row futures-launch-actions">
            <button
              type="button"
              className={botControlActive ? "app-btn app-btn-danger px-3" : "app-btn app-btn-primary px-3"}
              onClick={botControlActive ? stopLiveBot : startLiveBot}
              disabled={busyAction === "start" || busyAction === "stop" || (!botControlActive && !canStartLiveBot)}
            >
              {busyAction === "start" ? "Starting..." : busyAction === "stop" ? "Stopping..." : botStarted ? "Stop Live Bot" : feedRunning ? "Stop Market Feed" : "Start Live Bot"}
            </button>
            <button type="button" className="app-btn px-3" onClick={updateLiveStrategy} disabled={backendOffline || busyAction === "update-live" || liveStatus?.running || !monitorSymbols.length}>
              {busyAction === "update-live" ? "Copying..." : "Copy Backtest To Live"}
            </button>
          </div>
        </div>

        <div className="futures-launch-config">
          <Field label="Topstep Account" className="futures-launch-account-field">
            <select value={selectedProfileCode} onChange={(event) => setSelectedProfileCode(event.target.value)} className="form-select app-input">
              {(fundedProfiles.length ? fundedProfiles : [FALLBACK_PROFILE]).map((profile) => (
                <option key={profile.code} value={profile.code}>
                  {PROFILE_ACCOUNTS[profile.code]?.label || profile.name}
                </option>
              ))}
            </select>
          </Field>

          <div className={activeSnapshot ? "futures-launch-chip ready" : "futures-launch-chip"}>
            <span>Live Strategy</span>
            <strong>{activeSnapshot ? "Live Slot" : "Not Set"}</strong>
            <small>{activeSnapshot?.updatedAt ? `Updated ${shortTime(activeSnapshot.updatedAt)}` : "Copy backtest first"}</small>
          </div>

          <div className="futures-launch-chip">
            <span>Account ID</span>
            <strong>{accountPreset.accountId}</strong>
            <small>{formatCompactCurrency(selectedProfile.accountSize)}</small>
          </div>

          <div className="futures-launch-symbols">
            <span>Symbols</span>
            <div className="futures-launch-symbol-list">
              {liveStrategySymbols.map((symbol) => (
                <b key={symbol}>{symbol}</b>
              ))}
            </div>
          </div>
        </div>

      </section>

      <section className="app-panel futures-monitor-panel">
        <div className="d-flex align-items-start justify-content-between gap-2 flex-wrap">
          <div className="fw-bold app-kicker">Live Market Monitor</div>
        </div>

        {marketIdle && !backendOffline && (
          <div className="futures-live-data-notice idle">
            Market data feed is idle. The monitor will reopen automatically when ProjectX realtime data starts again.
          </div>
        )}

        {monitorDataActive && marketSession && !marketSession.entryWindowOpen && (
          <div className="futures-live-data-notice">
            {marketSession.label}: {marketSession.detail} Market data stays visible while the ProjectX feed is still running; new practice entries remain blocked.
          </div>
        )}

        {graphBuilding && (
          <div className="futures-live-data-notice">
            {graphReadiness?.message || "Currently building futures graph history for every symbol and timeframe."}
            {" "}
            {Number(graphReadiness?.totalItems || 0) > 0 ? `${Number(graphReadiness?.readyItems || 0)}/${Number(graphReadiness?.totalItems || 0)} graph sets ready.` : "Graph sets are queued."}
          </div>
        )}

        {monitorDataActive && graphReady && !selectedHasLiveCandles && (
          <div className="futures-live-data-notice">
            Give it a bit. The futures graph engine is syncing ProjectX candles for {selectedChartSymbol} and will open the chart as soon as the monitor feed returns a valid candle.
          </div>
        )}

        {monitorDataActive && selectedHasLiveCandles && feedStaleSeconds >= 180 && (
          <div className="futures-live-data-notice">
            ProjectX has not emitted a fresh tick for {formatDuration(feedStaleSeconds)}. The chart is preserving minute snapshots from the last known price until the feed moves again.
          </div>
        )}

        <div className="futures-market-layout">
          <FuturesMarketChart
            botStarted={monitorDataActive}
            candles={chartDisplayCandles}
            isTransitioning={chartTransitioning}
            symbol={selectedChartSymbol}
            symbols={monitorSymbols}
            timeframe={selectedTimeframe}
            onSymbolChange={selectChartSymbol}
            onTimeframeChange={changeTimeframe}
            marketSession={marketSession}
            trades={chartTrades}
            lastRefreshAt={lastMonitorRefreshAt}
            serverTime={liveMonitor?.serverTime}
            feedStaleSeconds={feedStaleSeconds}
            warmupPending={warmupPending}
            graphReadiness={graphReadiness}
            backendOffline={backendOffline}
            marketIdle={marketIdle}
          />
          <FuturesBotTrackerPanel
            trackers={botTrackers}
            selectedSymbol={selectedChartSymbol}
            botStarted={monitorDataActive}
          />
        </div>
      </section>

      <section className="app-panel">
        <div className="d-flex align-items-start justify-content-between gap-2 flex-wrap">
          <div>
            <div className="fw-bold app-kicker">Live Trades</div>
          </div>
          <button type="button" className="app-btn app-btn-small px-3" onClick={refreshLiveData}>
            Refresh
          </button>
        </div>
        <TradesTable trades={liveTrades} mode="live" />
      </section>

      <section className="app-live-grid futures-live-summary-grid">
        <MetricCard label="Current PnL" value={formatCurrency(metrics?.currentPnl)} accent={Number(metrics?.currentPnl || 0)} />
        <MetricCard label="Drawdown" value={formatCurrency(-Math.abs(Number(metrics?.drawdown || 0)))} accent={-Math.abs(Number(metrics?.drawdown || 0))} />
        <MetricCard label="Return %" value={formatPct(metrics?.returnPct)} accent={Number(metrics?.returnPct || 0)} />
        <MetricCard label="Trades" value={String(metrics?.numberOfTrades || 0)} />
      </section>

      <section className="app-panel">
        <div className="d-flex align-items-start justify-content-between gap-2 flex-wrap">
          <div>
            <div className="fw-bold app-kicker">All Trades</div>
          </div>
          <span className="app-badge app-neutral-badge">{displayedDecisions.length} rows</span>
        </div>
        <TradesTable trades={displayedDecisions} mode="all" />
      </section>
    </div>
  );
}

function FuturesBotTrackerPanel({ trackers, selectedSymbol, botStarted }) {
  const activeCount = trackers.filter((tracker) => tracker.liveTrades > 0).length;
  const totalPnl = trackers.reduce((total, tracker) => total + Number(tracker.pnl || 0), 0);
  const trackerTiles = [
    ...trackers,
    reservedTrackerTile("NEXT", "Future equity slot"),
    reservedTrackerTile("NEXT 2", "Future equity slot"),
  ];
  return (
    <div className="futures-bot-tracker-panel">
      <div className="futures-bot-tracker-header">
        <div>
          <strong>Bot Symbol Trackers</strong>
          <span>{botStarted ? `${activeCount} live positions | ${formatCurrency(totalPnl)} tracked PnL` : "Start the live bot to populate tracker state"}</span>
        </div>
        <span className={activeCount > 0 ? "app-badge app-positive-badge" : "app-badge app-neutral-badge"}>
          {activeCount > 0 ? `${activeCount} trading` : "No open trades"}
        </span>
      </div>
      <div className="futures-bot-tracker-grid">
        {trackerTiles.map((tracker) => (
          <div
            key={tracker.symbol}
            className={[
              "futures-bot-tracker-card",
              selectedSymbol === tracker.symbol ? "active" : "",
              tracker.reserved ? "reserved" : "",
            ].filter(Boolean).join(" ")}
          >
            <div className="futures-bot-tracker-topline">
              <span>{tracker.symbol}</span>
              <strong>{formatPrice(tracker.lastPrice)}</strong>
            </div>
            <div className="futures-bot-tracker-pnl">
              <em className={tracker.pnl > 0 ? "app-pnl-pos" : tracker.pnl < 0 ? "app-pnl-neg" : "app-muted"}>{formatCurrency(tracker.pnl)}</em>
              <small className={tracker.changePct > 0 ? "app-pnl-pos" : tracker.changePct < 0 ? "app-pnl-neg" : "app-muted"}>{formatPct(tracker.changePct)}</small>
            </div>
            <div className="futures-bot-tracker-stats">
              <span><b>{tracker.totalTrades}</b> trades</span>
              <span><b>{tracker.liveTrades}</b> live</span>
              <span className={`futures-bot-signal ${tracker.signalTone}`}>{tracker.signal}</span>
            </div>
            <div className="futures-bot-tracker-foot">
              <span>{tracker.detail}</span>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function reservedTrackerTile(symbol, detail) {
  return {
    symbol,
    reserved: true,
    lastPrice: 0,
    pnl: 0,
    changePct: 0,
    totalTrades: 0,
    liveTrades: 0,
    signal: "Reserved",
    signalTone: "idle",
    detail,
  };
}

function FuturesMarketChart({
  candles,
  symbol,
  symbols = DEFAULT_SYMBOLS,
  timeframe,
  onSymbolChange,
  onTimeframeChange,
  botStarted,
  isTransitioning,
  marketSession,
  trades = [],
  lastRefreshAt,
  serverTime,
  feedStaleSeconds = -1,
  warmupPending = false,
  graphReadiness = null,
  backendOffline = false,
  marketIdle = false,
}) {
  const [hoveredIndex, setHoveredIndex] = useState(null);
  const [hoveredTradeIndex, setHoveredTradeIndex] = useState(null);
  const [selectedTradeKey, setSelectedTradeKey] = useState(null);
  const [visibleBars, setVisibleBars] = useState(96);
  const [scrollOffset, setScrollOffset] = useState(0);
  const [displayedPriceDomain, setDisplayedPriceDomain] = useState(null);
  const [displayedVolumeMax, setDisplayedVolumeMax] = useState(null);
  const chartShellRef = useRef(null);
  const wheelAccumulatorRef = useRef({ x: 0, y: 0 });
  const displayedDomainRef = useRef(null);
  const domainAnimationFrameRef = useRef(null);
  const domainResetSymbolRef = useRef("");
  const displayedVolumeMaxRef = useRef(null);
  const volumeAnimationFrameRef = useRef(null);
  const volumeResetSymbolRef = useRef("");
  const candleSeries = Array.isArray(candles) ? candles : [];
  const hasCandles = candleSeries.length > 0;

  const width = 1680;
  const height = 560;
  const priceTop = 14;
  const priceBottom = 400;
  const volumeTop = 424;
  const volumeHeight = 58;
  const axisY = 540;
  const rightAxisGutter = 58;
  const plotWidth = width - rightAxisGutter;
  const maxVisibleBars = Math.min(220, Math.max(24, candleSeries.length));
  const safeVisibleBars = Math.max(24, Math.min(visibleBars, maxVisibleBars));
  const maxOffset = Math.max(0, candleSeries.length - safeVisibleBars);
  const offset = Math.min(scrollOffset, maxOffset);
  const endIndex = Math.max(0, candleSeries.length - offset);
  const startIndex = Math.max(0, endIndex - safeVisibleBars);
  const visibleCandles = candleSeries.slice(startIndex, endIndex);
  const leadingSlots = Math.max(0, safeVisibleBars - visibleCandles.length);
  const slotWidth = plotWidth / safeVisibleBars;
  const latestCandle = candleSeries[candleSeries.length - 1];
  const latestPrice = Number(latestCandle?.close || 0);
  const visibleTrades = Array.isArray(trades) ? trades.filter((trade) => Number(trade.entryPrice || 0) > 0) : [];
  const livePinned = offset === 0;
  const targetPriceDomain = buildChartPriceDomain({
    candles: visibleCandles,
    trades: visibleTrades,
    latestPrice,
    includeLatestPrice: livePinned,
    symbol,
  });
  const targetDomainMin = targetPriceDomain.min;
  const targetDomainMax = targetPriceDomain.max;
  const domainSymbolKey = String(symbol || "");
  const activePriceDomain = displayedPriceDomain?.symbol === domainSymbolKey ? displayedPriceDomain : targetPriceDomain;
  const min = activePriceDomain.min;
  const max = activePriceDomain.max;
  const range = Math.max(max - min, instrumentTickSize(symbol) * 16);
  const hoveredLocalIndex = Math.min(Math.max(hoveredIndex ?? visibleCandles.length - 1, 0), visibleCandles.length - 1);
  const hoveredCandle = visibleCandles[hoveredLocalIndex];
  const gridValues = Array.from({ length: 6 }, (_, index) => min + ((max - min) * index) / 5);
  const targetVolumeMax = buildChartVolumeMax(visibleCandles);
  const activeVolumeMax = displayedVolumeMax?.symbol === domainSymbolKey ? displayedVolumeMax.value : targetVolumeMax;
  const maxVolume = Math.max(activeVolumeMax, 1);
  const priceClipId = `futures-price-clip-${String(symbol || "chart").replace(/[^a-z0-9_-]/gi, "")}-${String(timeframe || "tf").replace(/[^a-z0-9_-]/gi, "")}`;

  const toY = (price) => priceBottom - (((Number(price || 0) - min) / range) * (priceBottom - priceTop));
  const toX = (index) => (leadingSlots + index) * slotWidth + slotWidth / 2;
  const findVisibleIndex = (time) => findNearestCandleIndex(visibleCandles, time);
  const setClampedOffset = (next) => setScrollOffset(Math.max(0, Math.min(maxOffset, next)));
  const panBy = (steps) => setClampedOffset(offset + steps);
  const zoomBy = (delta) => setVisibleBars((value) => Math.max(24, Math.min(220, value + delta)));
  const handleWheel = useCallback((event) => {
    if (!hasCandles) return;
    event.preventDefault();
    event.stopPropagation();
    const deltaScale = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? 160 : 1;
    const deltaX = event.deltaX * deltaScale;
    const deltaY = event.deltaY * deltaScale;

    if (event.altKey || event.ctrlKey || event.metaKey) {
      const threshold = 78;
      wheelAccumulatorRef.current.y += Math.abs(deltaY) > Math.abs(deltaX) ? deltaY : deltaX;
      const units = Math.max(-1, Math.min(1, Math.trunc(wheelAccumulatorRef.current.y / threshold)));
      if (units !== 0) {
        wheelAccumulatorRef.current.y -= units * threshold;
        setVisibleBars((value) => Math.max(24, Math.min(220, value + units * 2)));
      }
      return;
    }

    const panDelta = event.shiftKey || Math.abs(deltaX) > Math.abs(deltaY) ? (Math.abs(deltaX) > Math.abs(deltaY) ? deltaX : deltaY) : deltaY;
    const threshold = 36;
    wheelAccumulatorRef.current.x += panDelta;
    const units = Math.max(-5, Math.min(5, Math.trunc(wheelAccumulatorRef.current.x / threshold)));
    if (units !== 0) {
      wheelAccumulatorRef.current.x -= units * threshold;
      setScrollOffset((value) => Math.max(0, Math.min(maxOffset, value + units)));
    }
  }, [hasCandles, maxOffset]);

  useEffect(() => {
    const node = chartShellRef.current;
    if (!node) return undefined;
    node.addEventListener("wheel", handleWheel, { passive: false });
    return () => node.removeEventListener("wheel", handleWheel);
  }, [handleWheel, hasCandles, symbol, timeframe]);

  useEffect(() => {
    wheelAccumulatorRef.current = { x: 0, y: 0 };
    let cancelled = false;
    queueMicrotask(() => {
      if (cancelled) return;
      setHoveredIndex(null);
      setHoveredTradeIndex(null);
      setSelectedTradeKey(null);
      setScrollOffset(0);
      setVisibleBars(96);
    });
    return () => {
      cancelled = true;
    };
  }, [symbol, timeframe]);

  useEffect(() => {
    const resetKey = String(symbol || "");
    const target = { value: targetVolumeMax, symbol: resetKey };
    if (!hasCandles || volumeResetSymbolRef.current !== resetKey || !displayedVolumeMaxRef.current) {
      volumeResetSymbolRef.current = resetKey;
      if (volumeAnimationFrameRef.current) cancelAnimationFrame(volumeAnimationFrameRef.current);
      displayedVolumeMaxRef.current = target;
      volumeAnimationFrameRef.current = requestAnimationFrame(() => {
        volumeAnimationFrameRef.current = null;
        setDisplayedVolumeMax(target);
      });
      return () => {
        if (volumeAnimationFrameRef.current) {
          cancelAnimationFrame(volumeAnimationFrameRef.current);
          volumeAnimationFrameRef.current = null;
        }
      };
    }

    const start = displayedVolumeMaxRef.current;
    if (Math.abs(start.value - target.value) < 1) {
      displayedVolumeMaxRef.current = target;
      volumeAnimationFrameRef.current = requestAnimationFrame(() => {
        volumeAnimationFrameRef.current = null;
        setDisplayedVolumeMax(target);
      });
      return () => {
        if (volumeAnimationFrameRef.current) {
          cancelAnimationFrame(volumeAnimationFrameRef.current);
          volumeAnimationFrameRef.current = null;
        }
      };
    }

    if (volumeAnimationFrameRef.current) cancelAnimationFrame(volumeAnimationFrameRef.current);
    const duration = 180;
    let startedAt = null;
    const animate = (now) => {
      if (startedAt == null) startedAt = now;
      const progress = Math.min(1, (now - startedAt) / duration);
      const eased = 1 - Math.pow(1 - progress, 3);
      const next = {
        value: start.value + (target.value - start.value) * eased,
        symbol: resetKey,
      };
      displayedVolumeMaxRef.current = next;
      setDisplayedVolumeMax(next);
      if (progress < 1) {
        volumeAnimationFrameRef.current = requestAnimationFrame(animate);
      } else {
        volumeAnimationFrameRef.current = null;
      }
    };
    volumeAnimationFrameRef.current = requestAnimationFrame(animate);

    return () => {
      if (volumeAnimationFrameRef.current) {
        cancelAnimationFrame(volumeAnimationFrameRef.current);
        volumeAnimationFrameRef.current = null;
      }
    };
  }, [hasCandles, symbol, targetVolumeMax]);

  useEffect(() => {
    const resetKey = String(symbol || "");
    const target = { min: targetDomainMin, max: targetDomainMax, symbol: resetKey };
    if (!hasCandles || domainResetSymbolRef.current !== resetKey || !displayedDomainRef.current) {
      domainResetSymbolRef.current = resetKey;
      if (domainAnimationFrameRef.current) cancelAnimationFrame(domainAnimationFrameRef.current);
      displayedDomainRef.current = target;
      domainAnimationFrameRef.current = requestAnimationFrame(() => {
        domainAnimationFrameRef.current = null;
        setDisplayedPriceDomain(target);
      });
      return () => {
        if (domainAnimationFrameRef.current) {
          cancelAnimationFrame(domainAnimationFrameRef.current);
          domainAnimationFrameRef.current = null;
        }
      };
    }

    const start = displayedDomainRef.current;
    const tick = instrumentTickSize(symbol);
    const changeSize = Math.abs(start.min - target.min) + Math.abs(start.max - target.max);
    if (changeSize < tick * 0.5) {
      displayedDomainRef.current = target;
      domainAnimationFrameRef.current = requestAnimationFrame(() => {
        domainAnimationFrameRef.current = null;
        setDisplayedPriceDomain(target);
      });
      return () => {
        if (domainAnimationFrameRef.current) {
          cancelAnimationFrame(domainAnimationFrameRef.current);
          domainAnimationFrameRef.current = null;
        }
      };
    }

    if (domainAnimationFrameRef.current) cancelAnimationFrame(domainAnimationFrameRef.current);
    const duration = 220;
    let startedAt = null;
    const animate = (now) => {
      if (startedAt == null) startedAt = now;
      const progress = Math.min(1, (now - startedAt) / duration);
      const eased = 1 - Math.pow(1 - progress, 3);
      const next = {
        min: start.min + (target.min - start.min) * eased,
        max: start.max + (target.max - start.max) * eased,
        symbol: resetKey,
      };
      displayedDomainRef.current = next;
      setDisplayedPriceDomain(next);
      if (progress < 1) {
        domainAnimationFrameRef.current = requestAnimationFrame(animate);
      } else {
        domainAnimationFrameRef.current = null;
      }
    };
    domainAnimationFrameRef.current = requestAnimationFrame(animate);

    return () => {
      if (domainAnimationFrameRef.current) {
        cancelAnimationFrame(domainAnimationFrameRef.current);
        domainAnimationFrameRef.current = null;
      }
    };
  }, [hasCandles, symbol, targetDomainMin, targetDomainMax]);

  if (!hasCandles) {
    const graphReadyItems = Number(graphReadiness?.readyItems || 0);
    const graphTotalItems = Number(graphReadiness?.totalItems || 0);
    const graphIsBuilding = botStarted && graphReadiness && !graphReadiness.ready;
    const emptyTitle = backendOffline
      ? "Feed paused."
      : marketIdle ? "Feed stopped." : graphIsBuilding ? "Currently building." : botStarted ? "Waiting for data." : "Start the live bot.";
    const emptyMessage = graphIsBuilding
      ? "The graph will stay hidden until ProjectX history is ready for every tracked futures symbol and every timeframe."
      : backendOffline
      ? "The chart will resume when live futures data is available."
      : marketIdle
      ? "ProjectX realtime data is not running right now. The monitor will open again when the feed starts."
      : botStarted
      ? marketSession && !marketSession.entryWindowOpen
        ? `${marketSession.label}: entries are blocked, but the chart will stay open while ProjectX continues sending candles.`
        : `The ${symbol} graph infrastructure is syncing ProjectX candle data and will update here automatically.`
      : "The futures graph will begin building after the live feed starts.";
    return (
      <div
        ref={chartShellRef}
        className={isTransitioning ? "app-chart-shell futures-market-chart-shell futures-market-chart-shell-empty is-transitioning" : "app-chart-shell futures-market-chart-shell futures-market-chart-shell-empty"}
        tabIndex={0}
      >
        <div className="futures-chart-actionbar">
          <div className="futures-chart-left-controls">
            <div className="futures-chart-selector-stack">
              <div className="futures-chart-symbol-mini-row" aria-label="Chart symbol">
                {symbols.map((optionSymbol) => (
                  <button
                    key={optionSymbol}
                    type="button"
                    className={symbol === optionSymbol ? "futures-chart-symbol-mini-btn active" : "futures-chart-symbol-mini-btn"}
                    onClick={() => onSymbolChange?.(optionSymbol)}
                  >
                    {optionSymbol}
                  </button>
                ))}
              </div>
              <div className="futures-timeframe-row futures-timeframe-row-chart" aria-label="Chart timeframe">
                {TIMEFRAME_OPTIONS.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    className={timeframe === option.value ? "futures-timeframe-btn active" : "futures-timeframe-btn"}
                    onClick={() => onTimeframeChange?.(option.value)}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            </div>
            <div className="futures-chart-status-copy">
              <strong>Graph Sync</strong>
              <span>UI {formatEstTime(lastRefreshAt)} | Server {formatEstTime(serverTime)} | Feed {formatDuration(feedStaleSeconds)}</span>
            </div>
          </div>
          <div className="futures-chart-actions" aria-label="Chart navigation">
            <button type="button" disabled title="Scroll back">←</button>
            <button type="button" disabled title="Scroll forward">→</button>
            <button type="button" disabled title="Zoom in">+</button>
            <button type="button" disabled title="Zoom out">−</button>
            <button type="button" disabled title="Jump to live">LIVE</button>
          </div>
        </div>

        <div className="app-chart-empty futures-chart-sync-empty">
          <strong>{emptyTitle}</strong>
          <span>{emptyMessage}</span>
          <div className="futures-chart-sync-grid">
            <span>{symbol}</span>
            <span>{timeframeLabel(timeframe)}</span>
            {graphTotalItems > 0 && <span>{graphReadyItems}/{graphTotalItems} ready</span>}
            {!backendOffline && <span>{marketIdle ? "Feed stopped" : warmupPending ? "Warmup syncing" : botStarted ? "Feed active" : "Feed idle"}</span>}
          </div>
        </div>
      </div>
    );
  }

  const timeLabelEvery = Math.max(1, Math.floor(visibleCandles.length / 6));

  return (
    <div
      ref={chartShellRef}
      className={isTransitioning ? "app-chart-shell futures-market-chart-shell is-transitioning" : "app-chart-shell futures-market-chart-shell"}
      tabIndex={0}
    >
      <div className="futures-chart-actionbar">
        <div className="futures-chart-left-controls">
          <div className="futures-chart-selector-stack">
            <div className="futures-chart-symbol-mini-row" aria-label="Chart symbol">
              {symbols.map((optionSymbol) => (
                <button
                  key={optionSymbol}
                  type="button"
                  className={symbol === optionSymbol ? "futures-chart-symbol-mini-btn active" : "futures-chart-symbol-mini-btn"}
                  onClick={() => onSymbolChange?.(optionSymbol)}
                >
                  {optionSymbol}
                </button>
              ))}
            </div>
            <div className="futures-timeframe-row futures-timeframe-row-chart" aria-label="Chart timeframe">
              {TIMEFRAME_OPTIONS.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  className={timeframe === option.value ? "futures-timeframe-btn active" : "futures-timeframe-btn"}
                  onClick={() => onTimeframeChange?.(option.value)}
                >
                  {option.label}
                </button>
              ))}
            </div>
          </div>
          <div className="futures-chart-status-copy">
            <strong>{livePinned ? "Live View" : "History View"}</strong>
            <span>UI {formatEstTime(lastRefreshAt)} | Server {formatEstTime(serverTime)} | Feed {formatDuration(feedStaleSeconds)}</span>
          </div>
        </div>
        <div className="futures-chart-actions" aria-label="Chart navigation">
          <button type="button" onClick={() => panBy(Math.ceil(safeVisibleBars / 4))} title="Scroll back">←</button>
          <button type="button" onClick={() => panBy(-Math.ceil(safeVisibleBars / 4))} title="Scroll forward">→</button>
          <button type="button" onClick={() => zoomBy(-12)} title="Zoom in">+</button>
          <button type="button" onClick={() => zoomBy(12)} title="Zoom out">−</button>
          <button type="button" onClick={() => setClampedOffset(0)} title="Jump to live">LIVE</button>
        </div>
      </div>

      <div className="app-chart-hover">
        <strong>{hoveredCandle?.time ? formatEstTime(hoveredCandle.time) : symbol}</strong>
        <div>
          <span>O {formatPrice(hoveredCandle?.open)}</span>
          <span>H {formatPrice(hoveredCandle?.high)}</span>
          <span>L {formatPrice(hoveredCandle?.low)}</span>
          <span>C {formatPrice(hoveredCandle?.close)}</span>
          <span>Vol {formatInteger(chartVolumeForCandle(hoveredCandle))}</span>
          <span>RSI {formatIndicator(hoveredCandle?.rsi14)}</span>
          <span>{hoveredCandle?.pollSnapshot ? "Poll snapshot" : hoveredCandle?.live ? "ProjectX live" : "Warmup history"}</span>
        </div>
      </div>

      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="app-market-svg futures-market-svg"
        role="img"
        aria-label={`${symbol} live futures market candles`}
        onMouseLeave={() => {
          setHoveredIndex(null);
          setHoveredTradeIndex(null);
        }}
      >
        <defs>
          <clipPath id={priceClipId}>
            <rect x="0" y={priceTop} width={plotWidth} height={priceBottom - priceTop} />
          </clipPath>
        </defs>
        {gridValues.map((value) => (
          <g key={`grid-${value}`}>
            <line x1="0" x2={plotWidth} y1={toY(value)} y2={toY(value)} className="futures-price-grid" />
            <text x={width - 8} y={toY(value) - 5} textAnchor="end" className="futures-price-label">
              {formatPrice(value)}
            </text>
          </g>
        ))}

        {visibleCandles.map((candle, index) => {
          const x = toX(index);
          const volume = chartVolumeForCandle(candle);
          const barHeight = Math.max(2, Math.min(1, volume / maxVolume) * volumeHeight);
          const rising = Number(candle.close || 0) >= Number(candle.open || 0);
          return (
            <rect
              key={`volume-${candle.time || index}-${index}`}
              x={x - Math.max(2, Math.min(8, slotWidth * 0.36)) / 2}
              y={volumeTop + volumeHeight - barHeight}
              width={Math.max(2, Math.min(8, slotWidth * 0.36))}
              height={barHeight}
              className={rising ? "futures-volume-bar up" : "futures-volume-bar down"}
            />
          );
        })}

        <line x1="0" x2={plotWidth} y1={volumeTop - 10} y2={volumeTop - 10} className="futures-volume-divider" />

        {hoveredIndex != null && (
          <line
            x1={toX(hoveredIndex)}
            x2={toX(hoveredIndex)}
            y1="10"
            y2={axisY}
            className="app-hover-line"
          />
        )}

        <g clipPath={`url(#${priceClipId})`}>
          {latestCandle?.close > 0 && (
            <line x1="0" x2={plotWidth} y1={toY(latestCandle.close)} y2={toY(latestCandle.close)} className="futures-current-price-line" />
          )}

          {visibleCandles.map((candle, index) => {
            const x = toX(index);
            const openY = toY(candle.open);
            const closeY = toY(candle.close);
            const highY = toY(candle.high);
            const lowY = toY(candle.low);
            const rising = Number(candle.close || 0) >= Number(candle.open || 0);
            const candleBodyWidth = Math.max(3, Math.min(13, slotWidth * 0.56));
            const liveClass = candle.live ? " live" : "";
            return (
              <g key={`${candle.time || index}-${index}`} onMouseEnter={() => setHoveredIndex(index)} onMouseMove={() => setHoveredIndex(index)}>
                <line x1={x} x2={x} y1={highY} y2={lowY} className={rising ? `app-candle-wick up${liveClass}` : `app-candle-wick down${liveClass}`} />
                <rect
                  x={x - candleBodyWidth / 2}
                  y={Math.min(openY, closeY)}
                  width={candleBodyWidth}
                  height={Math.max(2, Math.abs(closeY - openY))}
                  rx="2"
                  className={rising ? `app-candle-body up${liveClass}` : `app-candle-body down${liveClass}`}
                />
                {candle.pollSnapshot && <circle cx={x} cy={Math.min(openY, closeY) - 8} r="3" className="futures-poll-dot" />}
                <rect x={x - slotWidth / 2} y="0" width={Math.max(slotWidth, 8)} height={axisY} fill="transparent" />
              </g>
            );
          })}
        </g>

        {visibleTrades.map((trade, index) => {
          const entryIndex = findVisibleIndex(trade.entryTime);
          const exitIndex = findVisibleIndex(trade.exitTime);
          const entryX = entryIndex == null ? null : toX(entryIndex);
          const exitX = exitIndex == null ? null : toX(exitIndex);
          const entryY = toY(trade.entryPrice);
          const markY = toY(trade.currentPrice || latestPrice);
          const popoverWidth = 372;
          const popoverHeight = 132;
          const preferredPopoverX = entryX != null && entryX + popoverWidth + 34 < plotWidth ? entryX + 28 : Number(entryX || 0) - popoverWidth - 28;
          const popoverX = Math.max(10, Math.min(plotWidth - popoverWidth - 10, preferredPopoverX));
          const preferredPopoverY = entryY - popoverHeight - 34 > priceTop ? entryY - popoverHeight - 34 : entryY + 38;
          const popoverY = Math.max(priceTop + 8, Math.min(priceBottom - popoverHeight - 10, preferredPopoverY));
          const tradeHovered = hoveredTradeIndex === index;
          const tradeKey = `${symbol || "chart"}-${timeframe || "tf"}-${trade.id || index}-${trade.entryTime || ""}`;
          const tradeActive = Boolean(entryX != null && selectedTradeKey === tradeKey);
          const pnlClass = Number(trade.unrealizedPnl || 0) >= 0 ? "positive" : "negative";
          const statusLabel = trade.closed ? "SOLD" : "LIVE";
          const toggleTradePopover = (event) => {
            event.stopPropagation();
            setSelectedTradeKey((current) => (current === tradeKey ? null : tradeKey));
          };
          return (
            <g
              key={`trade-${trade.id || index}`}
              className={`futures-trade-overlay ${pnlClass} ${trade.closed ? "closed" : "open"} ${tradeActive ? "active" : ""} ${tradeHovered ? "hovered" : ""}`}
              role="button"
              tabIndex={0}
              aria-label={`${statusLabel} ${trade.side} trade PnL ${formatCurrency(trade.unrealizedPnl)}`}
              onMouseEnter={() => setHoveredTradeIndex(index)}
              onMouseLeave={() => setHoveredTradeIndex(null)}
              onKeyDown={(event) => {
                if (event.key !== "Enter" && event.key !== " ") return;
                event.preventDefault();
                setSelectedTradeKey((current) => (current === tradeKey ? null : tradeKey));
              }}
              onClick={toggleTradePopover}
            >
              {entryX != null && (
                <g className="futures-trade-entry-control" onClick={toggleTradePopover}>
                  <circle cx={entryX} cy={entryY} r="18" className="futures-trade-point-halo" />
                  <circle cx={entryX} cy={entryY} r="9" className="futures-trade-point" />
                  <rect x={entryX + 16} y={entryY - 15} width="58" height="30" rx="15" className="futures-trade-point-button" />
                  <text x={entryX + 45} y={entryY + 5} textAnchor="middle" className="futures-trade-point-button-text">
                    PnL
                  </text>
                </g>
              )}
              {trade.closed && exitX != null && (
                <circle cx={exitX} cy={markY} r="7" className="futures-trade-exit-glyph" />
              )}
              {tradeActive && (
                <g className="futures-trade-popover">
                  <rect x={popoverX} y={popoverY} width={popoverWidth} height={popoverHeight} rx="10" className="futures-trade-popover-box" />
                  <text x={popoverX + 16} y={popoverY + 24} className="futures-trade-popover-title">
                    {statusLabel} {trade.side} {trade.contracts}
                  </text>
                  <text x={popoverX + 16} y={popoverY + 62} className="futures-trade-popover-pnl">
                    <tspan className="futures-trade-pnl-value">{formatCurrency(trade.unrealizedPnl)}</tspan>
                  </text>
                  <text x={popoverX + 16} y={popoverY + 92} className="futures-trade-popover-text">
                    Entry {formatPrice(trade.entryPrice)}
                  </text>
                  <text x={popoverX + 202} y={popoverY + 92} className="futures-trade-popover-text">
                    Current {formatPrice(trade.currentPrice || latestPrice)}
                  </text>
                  <text x={popoverX + 16} y={popoverY + 116} className="futures-trade-popover-text futures-trade-popover-risk">
                    Stop {formatPrice(trade.stopPrice)}
                  </text>
                  <text x={popoverX + 202} y={popoverY + 116} className="futures-trade-popover-text futures-trade-popover-risk">
                    Target {formatPrice(trade.targetPrice)}
                  </text>
                </g>
              )}
            </g>
          );
        })}

        {visibleCandles.map((candle, index) => (
          index % timeLabelEvery === 0 || index === visibleCandles.length - 1 ? (
            <text key={`time-${candle.time || index}-${index}`} x={toX(index)} y={axisY} textAnchor="middle" className="futures-time-axis-label">
              {compactTime(candle.time)}
            </text>
          ) : null
        ))}

        <text x="12" y={axisY} className="futures-timezone-svg-label">
          {EASTERN_TIME_LABEL}
        </text>
      </svg>

      <div className="futures-chart-range-row">
        <span>{formatEstTime(visibleCandles[0]?.time)}</span>
        <input
          type="range"
          min="0"
          max={maxOffset}
          value={maxOffset - offset}
          onChange={(event) => setClampedOffset(maxOffset - Number(event.target.value || 0))}
          disabled={maxOffset <= 0}
          aria-label="Scroll chart history"
        />
        <span>{formatEstTime(visibleCandles[visibleCandles.length - 1]?.time)}</span>
      </div>

      <div className="app-chart-caption">
        <span>{symbol}</span>
        <span>{timeframeLabel(timeframe)} candles</span>
      </div>
    </div>
  );
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

function latestSessionChartCandles(candles) {
  const series = Array.isArray(candles) ? candles : [];
  if (series.length <= 1) return series;
  const datedCandles = series
    .map((candle) => ({ candle, time: parseChartTime(candle?.time) }))
    .filter((entry) => entry.time);
  if (datedCandles.length <= 1) return series;
  const dateKeys = new Set(datedCandles.map((entry) => chartDateKey(entry.time)).filter(Boolean));
  if (dateKeys.size <= 1) return series;
  const latestDateKey = chartDateKey(datedCandles[datedCandles.length - 1].time);
  if (!latestDateKey) return series;
  const latestSession = datedCandles
    .filter((entry) => chartDateKey(entry.time) === latestDateKey)
    .map((entry) => entry.candle);
  return latestSession.length > 0 ? latestSession : series;
}

function resolveDisplayMonitor(liveMonitor, monitorCache, selectedTimeframe, monitorActive) {
  if (!monitorActive) return null;
  const normalizedTimeframe = normalizeClientTimeframe(selectedTimeframe);
  const liveTimeframe = normalizeClientTimeframe(liveMonitor?.timeframe);
  if (liveMonitor && liveTimeframe === normalizedTimeframe) return liveMonitor;
  if (monitorCache?.[normalizedTimeframe]) return monitorCache[normalizedTimeframe];
  return null;
}

function normalizeClientTimeframe(value) {
  if (value === "5m" || value === "30m" || value === "1h") return value;
  return "1m";
}

function chartDateKey(time) {
  const date = new Date(time);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "America/New_York",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(date);
}

function buildSymbolTrackers({ symbols, states, decisions, marketData, botStarted }) {
  const stateBySymbol = new Map((Array.isArray(states) ? states : []).map((state) => [String(state.symbol || "").toUpperCase(), state]));
  const decisionRows = Array.isArray(decisions) ? decisions : [];
  return (Array.isArray(symbols) ? symbols : DEFAULT_SYMBOLS).map((symbol) => {
    const normalizedSymbol = String(symbol || "").toUpperCase();
    const state = stateBySymbol.get(normalizedSymbol) || null;
    const candles = Array.isArray(marketData?.[normalizedSymbol]) ? marketData[normalizedSymbol] : [];
    const sessionCandles = latestSessionChartCandles(candles.map(normalizeCandle).filter((candle) => candle.time && Number(candle.close || 0) > 0));
    const lastPrice = Number(latestChartPrice(sessionCandles) || state?.lastPrice || 0);
    const firstPrice = Number(sessionCandles[0]?.close || 0);
    const changePct = firstPrice > 0 ? ((lastPrice - firstPrice) / firstPrice) * 100 : Number(state?.changePct || 0);
    const trades = decisionRows.filter((decision) => isEntryDecision(decision) && String(decision.symbol || "").toUpperCase() === normalizedSymbol);
    const liveTrades = trades.filter((trade) => !isClosedTradeDecision(trade));
    const pnl = trades.reduce((total, trade) => {
      const closed = isClosedTradeDecision(trade);
      const entryPrice = Number(trade.entryPrice || 0);
      const exitPrice = Number(trade.exitPrice || 0);
      const contracts = Number(trade.contracts || 0);
      const markPrice = closed ? exitPrice || entryPrice : lastPrice || entryPrice;
      const realizedPnl = Number(trade.pnl || 0);
      const calculatedPnl = calculateFuturesPnl(normalizedSymbol, trade.side, entryPrice, markPrice, contracts);
      return total + (closed && realizedPnl !== 0 ? realizedPnl : calculatedPnl);
    }, 0);
    const signal = trackerSignalLabel(state, liveTrades.length, botStarted);
    return {
      symbol: normalizedSymbol,
      lastPrice,
      changePct,
      pnl,
      totalTrades: trades.length,
      liveTrades: liveTrades.length,
      signal: signal.label,
      signalTone: signal.tone,
      detail: botStarted ? state?.analysisStatus || (lastPrice > 0 ? "Tracking market data" : "Waiting for data") : "Not started",
    };
  });
}

function trackerSignalLabel(state, liveTrades, botStarted) {
  if (!botStarted) return { label: "Idle", tone: "idle" };
  if (liveTrades > 0) return { label: "Trading", tone: "trading" };
  const latestSignal = Array.isArray(state?.latestSignals) ? state.latestSignals[0] : null;
  if (latestSignal?.strategyCode) {
    return { label: `${latestSignal.strategyCode} ${String(latestSignal.side || "").toUpperCase()}`, tone: "setup" };
  }
  if (Number(state?.activeSignalCount || 0) > 0) {
    return { label: "Setup", tone: "setup" };
  }
  if (Number(state?.enabledStrategies || 0) > 0) {
    return { label: "Looking", tone: "looking" };
  }
  return { label: "Idle", tone: "idle" };
}

function chartVolumeForCandle(candle) {
  const rawVolume = Number(candle?.volume || 0);
  const eventCount = Number(candle?.events || 0) + Number(candle?.pollEvents || 0);
  if (candle?.live && eventCount > 0 && rawVolume > eventCount * 2500) {
    return eventCount;
  }
  return Math.max(0, rawVolume);
}

function buildChartVolumeMax(candles) {
  const volumes = (Array.isArray(candles) ? candles : [])
    .map(chartVolumeForCandle)
    .filter((volume) => Number.isFinite(volume) && volume > 0)
    .sort((first, second) => first - second);
  if (volumes.length === 0) return 1;
  const rawMax = volumes[volumes.length - 1];
  if (volumes.length < 18) return Math.max(rawMax, 1);
  const median = quantileSorted(volumes, 0.5);
  const highVolume = quantileSorted(volumes, 0.9);
  const robustMax = Math.max(highVolume * 1.65, median * 3.5, 1);
  return rawMax > robustMax * 3 ? robustMax : rawMax;
}

function buildChartPriceDomain({ candles, trades, latestPrice, includeLatestPrice, symbol }) {
  const tick = instrumentTickSize(symbol);
  const minimumRange = tick * 32;
  const candlePrices = [];
  (Array.isArray(candles) ? candles : []).forEach((candle) => {
    [candle?.open, candle?.high, candle?.low, candle?.close].forEach((value) => {
      const price = Number(value || 0);
      if (Number.isFinite(price) && price > 0) candlePrices.push(price);
    });
  });

  const sortedCandlePrices = [...candlePrices].sort((first, second) => first - second);
  const anchorPrices = [];
  (Array.isArray(trades) ? trades : []).forEach((trade) => {
    [trade?.entryPrice, trade?.stopPrice, trade?.targetPrice, trade?.currentPrice].forEach((value) => {
      const price = Number(value || 0);
      if (Number.isFinite(price) && price > 0) anchorPrices.push(price);
    });
  });
  const markPrice = Number(latestPrice || 0);
  if (includeLatestPrice && Number.isFinite(markPrice) && markPrice > 0) anchorPrices.push(markPrice);

  if (sortedCandlePrices.length === 0 && anchorPrices.length === 0) {
    return { min: 0, max: 1 };
  }

  let domainMin = sortedCandlePrices[0] ?? Math.min(...anchorPrices);
  let domainMax = sortedCandlePrices[sortedCandlePrices.length - 1] ?? Math.max(...anchorPrices);

  if (sortedCandlePrices.length >= 48) {
    const lowQuantile = quantileSorted(sortedCandlePrices, 0.08);
    const highQuantile = quantileSorted(sortedCandlePrices, 0.92);
    const robustRange = Math.max(highQuantile - lowQuantile, minimumRange);
    const fullRange = Math.max(domainMax - domainMin, minimumRange);
    const lowFence = lowQuantile - robustRange * 0.35;
    const highFence = highQuantile + robustRange * 0.35;
    const outsideFenceCount = sortedCandlePrices.filter((price) => price < lowFence || price > highFence).length;
    const sparseOutliers = outsideFenceCount <= Math.max(8, sortedCandlePrices.length * 0.1);

    if (fullRange > robustRange * 2.4 && sparseOutliers) {
      domainMin = lowQuantile;
      domainMax = highQuantile;
    }
  }

  anchorPrices.forEach((price) => {
    const range = Math.max(domainMax - domainMin, minimumRange);
    const nearDomain = price >= domainMin - range * 1.35 && price <= domainMax + range * 1.35;
    if (nearDomain) {
      domainMin = Math.min(domainMin, price);
      domainMax = Math.max(domainMax, price);
    }
  });

  if (domainMax - domainMin < minimumRange) {
    const mid = (domainMin + domainMax) / 2;
    domainMin = mid - minimumRange / 2;
    domainMax = mid + minimumRange / 2;
  }

  const padding = Math.max((domainMax - domainMin) * 0.14, tick * 12);
  return {
    min: domainMin - padding,
    max: domainMax + padding,
  };
}

function quantileSorted(values, quantile) {
  if (!Array.isArray(values) || values.length === 0) return 0;
  const index = (values.length - 1) * quantile;
  const lowerIndex = Math.floor(index);
  const upperIndex = Math.ceil(index);
  if (lowerIndex === upperIndex) return values[lowerIndex];
  const weight = index - lowerIndex;
  return values[lowerIndex] * (1 - weight) + values[upperIndex] * weight;
}

function buildChartTrades(decisions, symbol, latestPrice) {
  const selectedSymbol = String(symbol || "").toUpperCase();
  const entries = (Array.isArray(decisions) ? decisions : [])
    .filter((decision) => isEntryDecision(decision) && String(decision.symbol || "").toUpperCase() === selectedSymbol)
    .sort((first, second) => (parseChartTime(first.entryTime || first.signalTime || first.createdAt) || 0) - (parseChartTime(second.entryTime || second.signalTime || second.createdAt) || 0))
    .slice(-8);

  return entries.map((trade) => {
    const exitPrice = Number(trade.exitPrice || 0);
    const status = String(trade.status || "").toUpperCase();
    const closed = exitPrice > 0 || status.includes("EXIT") || status.includes("CLOSED") || status.includes("FLAT") || status.includes("SOLD");
    const markPrice = closed ? exitPrice || Number(trade.entryPrice || 0) : Number(latestPrice || trade.entryPrice || 0);
    const contracts = Number(trade.contracts || 0);
    const entryPrice = Number(trade.entryPrice || 0);
    const livePnl = calculateFuturesPnl(selectedSymbol, trade.side, entryPrice, markPrice, contracts);
    const realizedPnl = Number(trade.pnl || 0);
    return {
      id: trade.id,
      symbol: trade.symbol,
      strategyCode: trade.strategyCode,
      side: String(trade.side || "").toUpperCase() === "SHORT" ? "SHORT" : "LONG",
      contracts,
      entryPrice,
      stopPrice: Number(trade.stopPrice || 0),
      targetPrice: Number(trade.targetPrice || 0),
      entryTime: trade.entryTime || trade.signalTime || trade.createdAt,
      exitTime: trade.exitTime || trade.updatedAt || trade.createdAt,
      currentPrice: markPrice,
      closed,
      unrealizedPnl: closed && realizedPnl !== 0 ? realizedPnl : livePnl,
    };
  });
}

function isEntryDecision(decision) {
  const status = String(decision?.status || "").toUpperCase();
  const hasEntry = Number(decision?.entryPrice || 0) > 0;
  return status.includes("ACCEPTED") || status.includes("SUBMITTED") || (hasEntry && (status.includes("EXIT") || status.includes("CLOSED") || status.includes("FLAT") || status.includes("SOLD")));
}

function isClosedTradeDecision(decision) {
  const status = String(decision?.status || "").toUpperCase();
  return Number(decision?.exitPrice || 0) > 0 || status.includes("EXIT") || status.includes("CLOSED") || status.includes("FLAT") || status.includes("SOLD");
}

function latestChartPrice(candles) {
  if (!Array.isArray(candles) || candles.length === 0) return 0;
  return Number(candles[candles.length - 1]?.close || 0);
}

function calculateFuturesPnl(symbol, side, entryPrice, markPrice, contracts) {
  if (!entryPrice || !markPrice || !contracts) return 0;
  const spec = instrumentSpec(symbol);
  const short = String(side || "").toUpperCase() === "SHORT";
  const ticks = short ? (entryPrice - markPrice) / spec.tickSize : (markPrice - entryPrice) / spec.tickSize;
  return ticks * spec.tickValue * contracts;
}

function instrumentSpec(symbol) {
  const specs = {
    MES: { tickSize: 0.25, tickValue: 1.25 },
    MNQ: { tickSize: 0.25, tickValue: 0.5 },
    M2K: { tickSize: 0.1, tickValue: 0.5 },
    ES: { tickSize: 0.25, tickValue: 12.5 },
    NQ: { tickSize: 0.25, tickValue: 5 },
    MGC: { tickSize: 0.1, tickValue: 1 },
    GC: { tickSize: 0.1, tickValue: 10 },
  };
  return specs[String(symbol || "").toUpperCase()] || specs.MNQ;
}

function instrumentTickSize(symbol) {
  return instrumentSpec(symbol).tickSize;
}

function findNearestCandleIndex(candles, targetTime) {
  const target = parseChartTime(targetTime);
  if (!target || !Array.isArray(candles) || candles.length === 0) return null;
  let nearestIndex = null;
  let nearestDistance = Infinity;
  candles.forEach((candle, index) => {
    const time = parseChartTime(candle?.time);
    if (!time) return;
    const distance = Math.abs(time - target);
    if (distance < nearestDistance) {
      nearestIndex = index;
      nearestDistance = distance;
    }
  });
  return nearestIndex;
}

function parseChartTime(value) {
  if (!value) return null;
  const clean = String(value).replace(" ", "T");
  const parsed = Date.parse(clean);
  return Number.isNaN(parsed) ? null : parsed;
}

function compactTime(value) {
  const parsed = parseChartTime(value);
  if (!parsed) return "";
  return new Intl.DateTimeFormat("en-US", {
    timeZone: "America/New_York",
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
  }).format(new Date(parsed));
}

function TradesTable({ trades, mode }) {
  const gridClass = mode === "live" ? "futures-live-trades-grid" : "futures-live-all-trades-grid";
  return (
    <div className="app-table-wrap">
      <div className={`app-grid-head ${gridClass}`}>
        <div>Time</div>
        <div>Symbol</div>
        <div>Strategy</div>
        <div>Side</div>
        <div>Qty</div>
        <div>Entry</div>
        <div>Exit</div>
        <div>PnL</div>
        <div>Status</div>
        <div>Reason</div>
      </div>
      {trades.length ? (
        trades.map((trade) => (
          <div className={`app-grid-row ${gridClass}`} key={trade.id}>
            <div className="app-time-cell">{formatEstTime(trade.entryTime || trade.signalTime || trade.createdAt || "--")}</div>
            <div>{trade.symbol || "--"}</div>
            <div>{trade.strategyCode || "--"}</div>
            <div>{trade.side || "--"}</div>
            <div>{trade.contracts || 0}</div>
            <div>{formatPrice(trade.entryPrice)}</div>
            <div>{formatPrice(trade.exitPrice)}</div>
            <div className={Number(trade.pnl || 0) > 0 ? "app-pnl-pos" : Number(trade.pnl || 0) < 0 ? "app-pnl-neg" : ""}>{formatCurrency(trade.pnl)}</div>
            <div>
              <span className={statusClass(trade.status)}>{trade.status || "--"}</span>
            </div>
            <div className="app-trade-notes">{trade.exitReason || trade.reason || "--"}</div>
          </div>
        ))
      ) : (
        <div className="app-empty">{mode === "live" ? "No live trade intents yet." : "No live bot trade records yet."}</div>
      )}
    </div>
  );
}

function MetricCard({ label, value, detail = "", accent = 0 }) {
  const valueClass = accent > 0 ? "app-live-value app-pnl-pos" : accent < 0 ? "app-live-value app-pnl-neg" : "app-live-value";
  return (
    <div className="app-subpanel app-live-card">
      <div className="app-label">{label}</div>
      <div className={valueClass}>{value}</div>
      <div className="app-live-detail">{detail || " "}</div>
    </div>
  );
}

function Field({ label, children, className = "" }) {
  return (
    <div className={className}>
      <label className="d-grid gap-1">
        <span className="app-label">{label}</span>
        {children}
      </label>
    </div>
  );
}

async function readApiResponse(response) {
  const text = await response.text();
  if (!text) return { json: null, text: "" };
  try {
    return { json: JSON.parse(text), text };
  } catch {
    return { json: null, text };
  }
}

function statusClass(status) {
  const value = String(status || "");
  if (value.includes("ACCEPTED") || value.includes("EXIT") || value.includes("FLAT")) return "app-badge app-positive-badge";
  if (value.includes("REJECTED") || value.includes("BLOCK")) return "app-badge app-risk-badge";
  return "app-badge app-neutral-badge";
}

function estimateFuturesMarketSession(now = new Date()) {
  const parts = easternDateParts(now);
  const minuteOfDay = parts.hour * 60 + parts.minute;
  const weekday = parts.weekday;
  const dailyBreak = weekday >= 1 && weekday <= 4 && minuteOfDay >= 17 * 60 && minuteOfDay < 18 * 60;
  const weekendClosed = weekday === 6 || (weekday === 5 && minuteOfDay >= 17 * 60) || (weekday === 0 && minuteOfDay < 18 * 60);
  const globexOpen = !dailyBreak && !weekendClosed;
  const regularSessionOpen = weekday >= 1 && weekday <= 5 && minuteOfDay >= 9 * 60 + 30 && minuteOfDay < 16 * 60;
  const entryWindowOpen = weekday >= 1 && weekday <= 5 && minuteOfDay >= 9 * 60 + 35 && minuteOfDay < 15 * 60 + 45;

  if (!globexOpen) {
    return {
      label: "Market Closed",
      detail: "Futures are outside the normal Globex availability window, so the 24/7 frontend is idle.",
      entryWindowOpen: false,
      tradingEnabled: false,
    };
  }

  if (!entryWindowOpen) {
    return {
      label: regularSessionOpen ? "Entry Window Closed" : "Market Closed",
      detail: regularSessionOpen
        ? "New entries are disabled outside the 9:35 AM-3:45 PM ET NY-session trade window."
        : "Futures may be trading, but the live bot is outside its regular-session entry window and should sit idle.",
      entryWindowOpen: false,
      tradingEnabled: false,
    };
  }

  return {
    label: "Market Open",
    detail: "Regular session is open. Live data appears once the backend feed is running.",
    entryWindowOpen: true,
    tradingEnabled: true,
  };
}

function easternDateParts(date) {
  const formatter = new Intl.DateTimeFormat("en-US", {
    timeZone: "America/New_York",
    weekday: "short",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
  const values = Object.fromEntries(formatter.formatToParts(date).map((part) => [part.type, part.value]));
  const weekdayMap = { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 };
  return {
    weekday: weekdayMap[values.weekday] ?? 0,
    hour: Number(values.hour === "24" ? 0 : values.hour || 0),
    minute: Number(values.minute || 0),
  };
}

function monitorLimitForTimeframe(value) {
  if (value === "1h") return 160;
  if (value === "30m") return 220;
  if (value === "5m") return 360;
  return 240;
}

function timeframeLabel(value) {
  if (value === "5m") return "5 minute";
  if (value === "30m") return "30 minute";
  if (value === "1h") return "1 hour";
  return "1 minute";
}

function parseSymbolCsv(value) {
  if (!value) return [];
  return String(value)
    .split(",")
    .map((symbol) => symbol.trim().toUpperCase())
    .filter(Boolean);
}

function formatCurrency(value) {
  const numeric = Number(value || 0);
  const sign = numeric > 0 ? "+" : numeric < 0 ? "-" : "";
  return `${sign}$${Math.abs(numeric).toFixed(2)}`;
}

function formatCompactCurrency(value) {
  const numeric = Number(value || 0);
  if (numeric >= 1000) return `$${Math.round(numeric / 1000)}K`;
  return `$${numeric.toFixed(0)}`;
}

function formatPct(value) {
  const numeric = Number(value || 0);
  const sign = numeric > 0 ? "+" : "";
  return `${sign}${numeric.toFixed(2)}%`;
}

function formatDuration(seconds) {
  const numeric = Number(seconds);
  if (!Number.isFinite(numeric) || numeric < 0) return "--";
  if (numeric < 60) return `${Math.round(numeric)}s ago`;
  const minutes = Math.floor(numeric / 60);
  const remainingSeconds = Math.round(numeric % 60);
  if (minutes < 60) return remainingSeconds > 0 ? `${minutes}m ${remainingSeconds}s ago` : `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  return remainingMinutes > 0 ? `${hours}h ${remainingMinutes}m ago` : `${hours}h ago`;
}

function formatPrice(value) {
  const numeric = Number(value || 0);
  return numeric > 0 ? numeric.toFixed(2) : "--";
}

function formatInteger(value) {
  const numeric = Number(value || 0);
  return Number.isFinite(numeric) ? numeric.toLocaleString("en-US", { maximumFractionDigits: 0 }) : "--";
}

function formatIndicator(value) {
  const numeric = Number(value || 0);
  return numeric > 0 ? numeric.toFixed(1) : "--";
}

function shortTime(value) {
  return formatEstTime(value);
}
