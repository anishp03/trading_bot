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
const LIVE_MARKS_REFRESH_MS = 1000;
const LIVE_RECONCILE_REFRESH_MS = 10000;
const MIN_OPENING_CHART_BARS = 24;
const DEFAULT_PROFILE = "TOPSTEP_150K_PRACTICE";
const PROFILE_ACCOUNTS = {
  TOPSTEP_50K_COMBINE: { label: "50K Combine", accountId: "22529998" },
  TOPSTEP_150K_PRACTICE: { label: "150K Combine", accountId: "22539378" },
};
const FALLBACK_PROFILE = {
  code: "TOPSTEP_150K_PRACTICE",
  name: "Topstep 150K Combine",
  accountSize: 150000,
  maxTrailingDrawdown: 4500,
  dailyLossLimit: 3000,
  maxRiskPerTrade: 900,
  maxContracts: 15,
  maxMicroContracts: 150,
  maxOpenPositions: 3,
  maxAggregateContracts: 150,
  maxAggregateMiniUnits: 15,
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
  const [liveDecisionHistory, setLiveDecisionHistory] = useState([]);
  const [liveThinking, setLiveThinking] = useState([]);
  const [, setLiveThinkingStatus] = useState("idle");
  const [observedThinking, setObservedThinking] = useState([]);
  const [liveMetrics, setLiveMetrics] = useState(null);
  const [liveMonitor, setLiveMonitor] = useState(null);
  const [liveMarks, setLiveMarks] = useState(null);
  const [monitorCache, setMonitorCache] = useState({});
  const [feedback, setFeedback] = useState("");
  const [busyAction, setBusyAction] = useState("");
  const [chartTransitioning, setChartTransitioning] = useState(false);
  const [lastMonitorRefreshAt, setLastMonitorRefreshAt] = useState("");
  const [backendOnline, setBackendOnline] = useState(true);
  const chartTransitionTimer = useRef(null);
  const botStartedRef = useRef(false);
  const observedThinkingKeys = useRef(new Set());
  const observedThinkingSession = useRef(0);
  const requestControllers = useRef(new Map());
  const decisionSidecarSignature = useRef("");
  const {
    futuresSidebarOnline = true,
    futuresSidebarStatus = null,
    refreshFuturesSidebarStatus = null,
  } = useOutletContext() || {};

  const liveAccountProfiles = useMemo(() => {
    const profileSource = fundedProfiles.length ? fundedProfiles : [FALLBACK_PROFILE];
    const profiles = profileSource.filter((profile) => PROFILE_ACCOUNTS[profile.code]?.accountId);
    return profiles.length ? profiles : [FALLBACK_PROFILE];
  }, [fundedProfiles]);

  const selectedProfile = useMemo(() => {
    return liveAccountProfiles.find((profile) => profile.code === selectedProfileCode) || liveAccountProfiles[0] || FALLBACK_PROFILE;
  }, [liveAccountProfiles, selectedProfileCode]);

  const activeSnapshot = snapshotState?.snapshot || liveStatus?.liveStrategySnapshot || null;
  const snapshotSymbols = parseSymbolCsv(activeSnapshot?.symbols);
  const liveStrategySymbols = snapshotSymbols.length ? snapshotSymbols : DEFAULT_SYMBOLS;
  const monitorSymbols = DEFAULT_SYMBOLS;
  const accountPreset = PROFILE_ACCOUNTS[selectedProfile.code] || PROFILE_ACCOUNTS[DEFAULT_PROFILE];
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
  const displayTradeRows = useMemo(() => mergeLiveTradeDecisions(displayedDecisions), [displayedDecisions]);
  const symbolStates = useMemo(() => (Array.isArray(liveMonitor?.symbolStates) ? liveMonitor.symbolStates : []), [liveMonitor?.symbolStates]);
  const augmentedLiveMetrics = useMemo(
    () => augmentTopstepMetricsWithMarks(liveMetrics, symbolStates),
    [liveMetrics, symbolStates]
  );
  const brokerSnapshot = augmentedLiveMetrics?.broker?.success ? augmentedLiveMetrics.broker : null;
  const brokerAuthoritative = Boolean(brokerSnapshot);
  const brokerOpenTradeRows = useMemo(
    () => buildBrokerOpenTradeRows(brokerSnapshot?.positions, liveDecisionHistory),
    [brokerSnapshot, liveDecisionHistory]
  );
  const brokerClosedTradeRows = useMemo(
    () => buildBrokerClosedTradeRows(brokerSnapshot?.trades, liveDecisionHistory),
    [brokerSnapshot, liveDecisionHistory]
  );
  const localLiveTrades = displayTradeRows.filter((decision) => isEntryDecision(decision) && !isClosedTradeDecision(decision));
  const liveTrades = brokerAuthoritative ? brokerOpenTradeRows : localLiveTrades;
  const allTradeRows = brokerAuthoritative ? brokerClosedTradeRows : displayTradeRows;
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
  const selectedSymbolState = useMemo(
    () => symbolStates.find((state) => String(state.symbol || "").toUpperCase() === selectedChartSymbol) || null,
    [selectedChartSymbol, symbolStates]
  );
  const selectedAccountDisabled = Boolean(!accountPreset?.accountId);
  const selectedChartMarkPrice = Number(selectedSymbolState?.lastPrice || latestChartPrice(chartDisplayCandles));
  const feedStaleSeconds = Number(liveMonitor?.feedStaleSeconds ?? -1);
  const marketSession = liveMonitor?.marketSession || liveStatus?.marketSession || estimateFuturesMarketSession();
  const marketIdle = !backendOffline && !monitorDataActive;
  const metrics = brokerAuthoritative ? augmentedLiveMetrics : (botStarted ? liveMetrics : null);
  const sidebarStartReady = Boolean(futuresSidebarStatus?.strategyConfig?.active && futuresSidebarStatus?.topstepApi?.ready);
  const brokerChartTradeRows = useMemo(
    () => brokerAuthoritative ? [...brokerClosedTradeRows, ...brokerOpenTradeRows] : [],
    [brokerAuthoritative, brokerClosedTradeRows, brokerOpenTradeRows]
  );
  const realChartTrades = useMemo(
    () => buildChartTrades(brokerChartTradeRows.length ? brokerChartTradeRows : displayTradeRows, selectedChartSymbol, selectedChartMarkPrice),
    [brokerChartTradeRows, displayTradeRows, selectedChartMarkPrice, selectedChartSymbol]
  );
  const chartTrades = realChartTrades;
  const botTrackers = useMemo(
    () => buildSymbolTrackers({
      symbols: monitorSymbols,
      states: symbolStates,
      decisions: displayTradeRows,
      marketData: liveMonitor?.marketData || {},
      brokerPositions: brokerSnapshot?.positions || [],
      brokerClosedTrades: brokerClosedTradeRows,
      brokerAuthoritative,
      botStarted,
    }),
    [botStarted, brokerAuthoritative, brokerClosedTradeRows, brokerSnapshot, displayTradeRows, liveMonitor?.marketData, monitorSymbols, symbolStates]
  );
  const equityReviewStatus = useMemo(
    () => buildEquityReviewStatus({
      backendOffline,
      botStarted,
      feedRunning,
      liveMonitor,
      liveMarks,
      symbolStates,
      metrics,
    }),
    [backendOffline, botStarted, feedRunning, liveMonitor, liveMarks, symbolStates, metrics]
  );
  const backendThinkingEntries = useMemo(
    () => Array.isArray(liveThinking) ? liveThinking.filter((entry) => entry && (entry.summary || entry.detail)).slice(0, 1000) : [],
    [liveThinking]
  );
  const thinkingEntries = backendThinkingEntries.length > 0 ? backendThinkingEntries : observedThinking;
  const canStartLiveBot = !backendOffline && !selectedAccountDisabled && Boolean(sidebarStartReady && activeSnapshot && !liveStatus?.running);
  const controlMessage = feedback || (botStarted ? liveStatus?.lastDecision || realtimeStatus?.lastMessage : feedRunning ? realtimeStatus?.lastMessage || liveMonitor?.realtimeMessage : "");
  const launchTone = backendOffline ? "offline" : botControlActive ? "live" : sidebarStartReady ? "ready" : "pending";
  const liveStrategySlotSummary = activeSnapshot ? formatStrategySlotSummary(activeSnapshot.sourceMetrics) : "Copy backtest first";
  const launchLabel = backendOffline ? "Bot Status: OFF" : botStarted ? "Running" : feedRunning ? "Feed Live" : sidebarStartReady ? "Ready" : marketIdle && !marketSession?.entryWindowOpen ? "Closed" : "Setup";

  useEffect(() => {
    loadFundedProfiles();
  }, []);

  useEffect(() => {
    if (!liveAccountProfiles.some((profile) => profile.code === selectedProfileCode)) {
      setSelectedProfileCode(liveAccountProfiles[0]?.code || DEFAULT_PROFILE);
    }
  }, [liveAccountProfiles, selectedProfileCode]);

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
  }, [symbolsCsv, selectedTimeframe, selectedProfileCode]);

  useEffect(() => {
    loadLiveMarks();
    const intervalId = window.setInterval(loadLiveMarks, LIVE_MARKS_REFRESH_MS);
    return () => window.clearInterval(intervalId);
  }, [symbolsCsv, selectedTimeframe]);

  useEffect(() => {
    const intervalId = window.setInterval(refreshLiveReconciliation, LIVE_RECONCILE_REFRESH_MS);
    return () => window.clearInterval(intervalId);
  }, [symbolsCsv, selectedTimeframe]);

  useEffect(() => {
    const sessionId = Number(liveStatus?.sessionId || 0);
    if (sessionId > 0 && observedThinkingSession.current !== sessionId) {
      observedThinkingSession.current = sessionId;
      observedThinkingKeys.current = new Set();
      setObservedThinking([]);
    }
  }, [liveStatus?.sessionId]);

  useEffect(() => {
    const observedEntries = buildObservedLiveBotLogEntries({
      backendOffline,
      botStarted,
      feedRunning,
      liveStatus,
      realtimeStatus,
      liveMonitor,
      liveMarks,
      liveDecisions,
      liveMetrics,
      symbolStates,
    });
    if (!observedEntries.length) return;
    const additions = [];
    observedEntries.forEach((entry) => {
      if (!entry?.observedKey || observedThinkingKeys.current.has(entry.observedKey)) return;
      observedThinkingKeys.current.add(entry.observedKey);
      additions.push(entry);
    });
    if (!additions.length) return;
    setObservedThinking((current) => additions.concat(current).slice(0, 1000));
  }, [backendOffline, botStarted, feedRunning, liveStatus, realtimeStatus, liveMonitor, liveMarks, liveDecisions, liveMetrics, symbolStates]);

  useEffect(() => {
    return () => {
      if (chartTransitionTimer.current) {
        window.clearTimeout(chartTransitionTimer.current);
      }
      requestControllers.current.forEach((controller) => controller.abort());
      requestControllers.current.clear();
    };
  }, []);

  function refreshLiveData() {
    loadLiveStatus({ forceSidecars: true });
    loadRealtimeStatus();
    loadLiveSnapshot();
    loadLiveMetrics();
    loadLiveMonitor();
    loadLiveMarks();
  }

  function refreshLiveReconciliation() {
    loadLiveStatus();
    loadRealtimeStatus();
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

  function requestJson(key, path, onData, onError = null) {
    if (requestControllers.current.has(key)) {
      return;
    }
    const controller = new AbortController();
    requestControllers.current.set(key, controller);
    apiFetch(path, { signal: controller.signal })
      .then(noteBackendResponse)
      .then((response) => response.json())
      .then((data) => onData(data))
      .catch((error) => {
        if (error?.name === "AbortError") return;
        if (onError) {
          onError(error);
        } else {
          noteBackendError(`Error loading ${key}:`, error);
        }
      })
      .finally(() => {
        if (requestControllers.current.get(key) === controller) {
          requestControllers.current.delete(key);
        }
      });
  }

  function refreshDecisionSidecars(status, force = false) {
    const signature = liveDecisionSidecarSignature(status);
    if (!force && signature === decisionSidecarSignature.current) {
      return;
    }
    decisionSidecarSignature.current = signature;
    loadLiveDecisions(status);
    loadLiveThinking(status);
    loadLiveDecisionHistory();
  }

  function loadFundedProfiles() {
    requestJson("fundedProfiles", "/api/futures/funded-rule-profiles", (data) => {
        const topstepProfiles = Array.isArray(data)
          ? data.filter((profile) => profile.code === "TOPSTEP_150K_PRACTICE" || profile.code === "TOPSTEP_50K_COMBINE")
          : [];
        setFundedProfiles(topstepProfiles.length ? topstepProfiles : [FALLBACK_PROFILE]);
      }, (error) => {
        noteBackendError("Error loading funded profiles:", error);
        setFundedProfiles([FALLBACK_PROFILE]);
      });
  }

  function loadLiveStatus(options = {}) {
    requestJson("liveStatus", "/api/futures/live/status", (data) => {
        setLiveStatus(data || null);
        refreshDecisionSidecars(data || null, Boolean(options.forceSidecars));
      }, (error) => {
        noteBackendError("Error loading live status:", error);
        if (isApiNetworkError(error)) {
          setLiveStatus(null);
          setLiveDecisions([]);
          setLiveThinking([]);
          setLiveThinkingStatus("idle");
        }
      });
  }

  function loadRealtimeStatus() {
    requestJson("realtimeStatus", "/api/futures/live/realtime/status", (data) => setRealtimeStatus(data || null), (error) => {
        noteBackendError("Error loading realtime status:", error);
        if (isApiNetworkError(error)) setRealtimeStatus(null);
      });
  }

  function loadLiveSnapshot() {
    requestJson("liveSnapshot", "/api/futures/live/strategy-snapshot", (data) => setSnapshotState(data || null), (error) => {
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
    requestJson("liveDecisions", `/api/futures/live/decisions?sessionId=${sessionId}&limit=160`, (data) => setLiveDecisions(Array.isArray(data) ? data : []), (error) => {
        noteBackendError("Error loading live decisions:", error);
        setLiveDecisions([]);
      });
  }

  function loadLiveDecisionHistory() {
    requestJson("liveDecisionHistory", "/api/futures/live/decisions?limit=500", (data) => setLiveDecisionHistory(Array.isArray(data) ? data : []), (error) => {
        noteBackendError("Error loading live decision history:", error);
        if (isApiNetworkError(error)) setLiveDecisionHistory([]);
      });
  }

  function loadLiveThinking(status = liveStatus) {
    const sessionId = Number(status?.running ? status?.sessionId || 0 : 0);
    if (!sessionId) {
      setLiveThinking([]);
      setLiveThinkingStatus("idle");
      return;
    }
    const key = "liveThinking";
    if (requestControllers.current.has(key)) {
      return;
    }
    const controller = new AbortController();
    requestControllers.current.set(key, controller);
    apiFetch(`/api/futures/live/thinking?sessionId=${sessionId}&limit=1000`, { signal: controller.signal })
      .then((response) => {
        if (response.status === 404) {
          setLiveThinkingStatus("observed");
          return [];
        }
        setLiveThinkingStatus("ready");
        return noteBackendResponse(response).json();
      })
      .then((data) => setLiveThinking(Array.isArray(data) ? data : []))
      .catch((error) => {
        if (error?.name === "AbortError") return;
        if (isApiNetworkError(error)) noteBackendError("Error loading live thinking:", error);
        setLiveThinkingStatus("observed");
        setLiveThinking([]);
      })
      .finally(() => {
        if (requestControllers.current.get(key) === controller) {
          requestControllers.current.delete(key);
        }
      });
  }

  function loadLiveMetrics() {
    requestJson("liveMetrics", "/api/futures/live/metrics", (data) => setLiveMetrics(data || null), (error) => {
        noteBackendError("Error loading live metrics:", error);
        if (isApiNetworkError(error)) setLiveMetrics(null);
      });
  }

  function loadLiveMarks() {
    const params = new URLSearchParams({
      symbols: symbolsCsv || DEFAULT_SYMBOLS.join(","),
      timeframe: selectedTimeframe,
    });
    requestJson("liveMarks", `/api/futures/live/marks?${params.toString()}`, (data) => {
        if (!data?.success) return;
        setLiveMarks(data);
        const normalizedTimeframe = normalizeClientTimeframe(data.timeframe || selectedTimeframe);
        setLiveMetrics((current) => mergeMetricsWithMarks(current, data));
        setLiveMonitor((current) => mergeMonitorWithMarks(current, data, normalizedTimeframe));
        setMonitorCache((current) => {
          const base = current?.[normalizedTimeframe] || null;
          const merged = mergeMonitorWithMarks(base, data, normalizedTimeframe);
          return merged ? { ...current, [normalizedTimeframe]: merged } : current;
        });
      }, (error) => {
        noteBackendError("Error loading live marks:", error);
        setLiveMarks({
          success: false,
          serverTime: new Date().toISOString(),
          checks: {
            overall: {
              ok: false,
              severity: "warn",
              message: "Fast live marks endpoint did not respond; slower monitor and metrics polling remain active.",
            },
          },
        });
      });
  }

  function loadLiveMonitor() {
    const params = new URLSearchParams({
      symbols: symbolsCsv || DEFAULT_SYMBOLS.join(","),
      limit: String(monitorLimitForTimeframe(selectedTimeframe)),
      timeframe: selectedTimeframe,
    });
    requestJson("liveMonitor", `/api/futures/live/monitor?${params.toString()}`, (data) => {
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
      }, (error) => {
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
      setFeedback(payload.json?.message || `Live bot started with ${accountPreset.label || selectedProfile.name} order automation.`);
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
              {liveAccountProfiles.map((profile) => {
                const account = PROFILE_ACCOUNTS[profile.code] || {};
                return (
                <option key={profile.code} value={profile.code}>
                  {account.label || profile.name}
                </option>
              );
              })}
            </select>
          </Field>

          <div className={activeSnapshot ? "futures-launch-chip ready" : "futures-launch-chip"}>
            <span>Live Strategy</span>
            <strong>{activeSnapshot ? "Live Slot" : "Not Set"}</strong>
            <small>{liveStrategySlotSummary}</small>
          </div>

          <div className="futures-launch-chip">
            <span>Account ID</span>
            <strong>{accountPreset.accountId || "Not connected"}</strong>
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

      <section className="app-live-grid futures-live-summary-grid">
        <MetricCard label="Current Balance" value={formatAccountCurrency(Number(metrics?.currentBalance ?? Number(metrics?.accountSize || 0) + Number(metrics?.currentPnl || 0)))} detail={metricSourceDetail(metrics, "balance")} />
        <MetricCard label="Current PnL" value={formatCurrency(metrics?.currentPnl)} accent={Number(metrics?.currentPnl || 0)} detail={metricSourceDetail(metrics, "pnl")} />
        <MetricCard label="Drawdown" value={formatCurrency(-Math.abs(Number(metrics?.drawdown || 0)))} accent={-Math.abs(Number(metrics?.drawdown || 0))} detail={metricSourceDetail(metrics, "drawdown")} />
        <MetricCard label="Return %" value={formatPct(metrics?.returnPct)} accent={Number(metrics?.returnPct || 0)} detail={metricSourceDetail(metrics, "return")} />
        <MetricCard label="Trades" value={String(metrics?.numberOfTrades || 0)} detail={metricSourceDetail(metrics, "trades")} />
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

      <section className="app-panel futures-monitor-panel">
        <div className="d-flex align-items-start justify-content-between gap-2 flex-wrap">
          <div className="fw-bold app-kicker">Live Market Monitor</div>
        </div>

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
            <div className="fw-bold app-kicker">All Trades</div>
          </div>
          <span className="app-badge app-neutral-badge">{allTradeRows.length} rows</span>
        </div>
        <TradesTable trades={allTradeRows} mode="all" />
      </section>

      <FuturesThinkingLog entries={thinkingEntries} status={equityReviewStatus} onRefresh={refreshLiveData} />
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
            <div className={`futures-bot-tracker-health ${tracker.healthDetail || tracker.errorCode ? "" : "compact"}`}>
              <span className={`futures-health-pill ${tracker.healthTone}`}>{tracker.healthLabel}</span>
              {(tracker.errorCode || tracker.healthDetail) && <small>{tracker.errorCode || tracker.healthDetail}</small>}
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

function FuturesThinkingLog({ entries, status, onRefresh }) {
  const rows = Array.isArray(entries) ? entries.slice(0, 1000) : [];
  const pageSize = 10;
  const [page, setPage] = useState(1);
  const totalPages = Math.max(1, Math.ceil(rows.length / pageSize));
  const safePage = Math.min(page, totalPages);
  const pageStart = (safePage - 1) * pageSize;
  const visibleRows = rows.slice(pageStart, pageStart + pageSize);
  const visibleStart = rows.length ? pageStart + 1 : 0;
  const visibleEnd = Math.min(pageStart + pageSize, rows.length);
  useEffect(() => {
    setPage((current) => Math.min(Math.max(1, current), totalPages));
  }, [totalPages]);
  return (
    <section className="app-panel futures-thinking-panel">
      <div className="futures-thinking-header">
        <div>
          <div className="fw-bold app-kicker">Equity Review</div>
        </div>
        <div className="futures-thinking-actions">
          <span className={`futures-review-status ${status?.healthTone || "idle"}`}>Health: {status?.healthLabel || "Waiting"}</span>
          <span className="app-badge app-neutral-badge">{rows.length} logs</span>
          <div className="futures-thinking-pager" aria-label="Thinking log pages">
            <button type="button" className="app-btn app-btn-small" onClick={() => setPage((value) => Math.max(1, value - 1))} disabled={safePage <= 1}>
              Prev
            </button>
            <span>{visibleStart}-{visibleEnd} / {rows.length}</span>
            <button type="button" className="app-btn app-btn-small" onClick={() => setPage((value) => Math.min(totalPages, value + 1))} disabled={safePage >= totalPages}>
              Next
            </button>
          </div>
          <button type="button" className="app-btn app-btn-small px-3" onClick={onRefresh}>
            Refresh
          </button>
        </div>
      </div>
      <div className="futures-thinking-log" role="log" aria-label="Live bot decision log">
        {visibleRows.length > 0 && (
          <div className="futures-thinking-log-head">
            <span>Date / Time</span>
            <span>Error Code / Status</span>
          </div>
        )}
        {visibleRows.length ? (
          visibleRows.map((entry, index) => (
            <div className={thinkingLogRowClass(entry)} key={entry.id || `${entry.createdAt}-${index}`}>
              <time>{formatEstTime(entry.createdAt || entry.barTime)}</time>
              <span><b>{equityReviewCode(entry)}</b> {thinkingDecisionLine(entry)}</span>
            </div>
          ))
        ) : (
          <div className="app-empty">Waiting for equity review events.</div>
        )}
      </div>
    </section>
  );
}

function buildObservedLiveBotLogEntries({
  backendOffline,
  botStarted,
  feedRunning,
  liveStatus,
  realtimeStatus,
  liveMonitor,
  liveMarks,
  liveDecisions,
  symbolStates,
}) {
  const entries = [];
  const sessionId = Number(liveStatus?.sessionId || 0);
  const sessionKey = sessionId > 0 ? sessionId : "current";
  const observedAt = liveMonitor?.serverTime || liveStatus?.lastUpdatedAt || liveLogNow();
  if (backendOffline) {
    entries.push(observedLogEntry({
      key: "backend-offline",
      sessionId,
      createdAt: observedAt,
      phase: "Backend",
      tone: "blocked",
      summary: "Backend API is not responding.",
      detail: "Live Bot status cannot be refreshed until the API responds again.",
    }));
    return entries;
  }

  if (botStarted) {
    entries.push(observedLogEntry({
      key: `bot-running|${sessionKey}|${liveStatus?.startedAt || ""}`,
      sessionId,
      createdAt: liveStatus?.startedAt || liveStatus?.lastUpdatedAt || observedAt,
      phase: "Started",
      tone: "active",
      summary: `${String(liveStatus?.executionMode || "Live").replaceAll("_", " ")} runner is on.`,
      detail: `Session ${sessionId || "--"}; tracking ${cleanLogText(liveStatus?.symbols || DEFAULT_SYMBOLS.join(", "))}; account ${cleanLogText(liveStatus?.fundedProfile || "practice")}.`,
    }));
  } else if (liveStatus) {
    entries.push(observedLogEntry({
      key: `bot-idle|${sessionKey}|${liveStatus?.lastDecision || ""}`,
      sessionId,
      createdAt: liveStatus?.lastUpdatedAt || observedAt,
      phase: "Stopped",
      tone: "closed",
      summary: "Live runner is idle.",
      detail: "No signal scans or broker submissions are running.",
    }));
  }

  if (feedRunning) {
    const states = Array.isArray(symbolStates) ? symbolStates : [];
    const trackedSymbols = states.map((state) => state?.symbol).filter(Boolean);
    entries.push(observedLogEntry({
      key: `feed-running|${sessionKey}|${liveMonitor?.dataSource || realtimeStatus?.dataMode || "feed"}`,
      sessionId,
      createdAt: realtimeStatus?.lastEventAt || liveMonitor?.realtimeLastEventAt || liveMonitor?.serverTime || observedAt,
      phase: "Market Data",
      tone: "active",
      summary: "Tracking ProjectX prices.",
      detail: `${trackedSymbols.length || DEFAULT_SYMBOLS.length} symbols are being monitored. ${cleanLogText(realtimeStatus?.lastMessage || liveMonitor?.realtimeMessage || "Price feed is active.")}`,
    }));
  }

  appendLiveMarksCheckLogEntries(entries, {
    liveMarks,
    feedRunning,
    sessionId,
    sessionKey,
    observedAt,
  });

  const marketSession = liveStatus?.marketSession || liveMonitor?.marketSession || null;
  if (marketSession) {
    entries.push(observedLogEntry({
      key: `entry-gate|${sessionKey}|${marketSession.code || ""}|${marketSession.marketDate || ""}|${Boolean(marketSession.entryWindowOpen)}`,
      sessionId,
      createdAt: marketSession.now || liveStatus?.lastUpdatedAt || observedAt,
      phase: "Entry Gate",
      tone: marketSession.entryWindowOpen ? "active" : "closed",
      summary: cleanLogText(marketSession.label || "Trading gate checked."),
      detail: `${cleanLogText(marketSession.detail || "")}${marketSession.entryWindowOpen ? " Strategy entries can be evaluated." : " New entries are blocked while this gate is closed."}`,
    }));
  }

  const lastProcessed = liveStatus?.lastProcessedLiveBarTime || liveStatus?.lastBarTime || "";
  if (botStarted && lastProcessed) {
    const decisions = Number(liveStatus?.decisionCount || 0);
    entries.push(observedLogEntry({
      key: `scan|${sessionKey}|${lastProcessed}|${decisions}`,
      sessionId,
      createdAt: liveStatus?.lastUpdatedAt || observedAt,
      phase: "Signal Scan",
      tone: decisions > 0 ? "active" : "info",
      barTime: lastProcessed,
      summary: decisions > 0 ? `${decisions} live decision(s) recorded.` : "No trade decision recorded for the latest processed bar.",
      detail: `Processed ${formatInteger(liveStatus?.automationCycles || 0)} automation cycle(s). ${cleanLogText(liveStatus?.lastDecision || "")}`,
    }));
  }

  (Array.isArray(symbolStates) ? symbolStates : []).forEach((state) => {
    const symbol = String(state?.symbol || "").toUpperCase();
    if (!symbol) return;
    if (feedRunning && state?.analysisStatus) {
      entries.push(observedLogEntry({
        key: `tracker|${sessionKey}|${symbol}|${state.analysisStatus}`,
        sessionId,
        createdAt: state?.lastBarTime || observedAt,
        phase: "Tracker",
        tone: "active",
        symbol,
        barTime: state?.lastBarTime || "",
        summary: `${symbol} ${cleanLogText(state.analysisStatus)}.`,
        detail: `Last price ${formatPrice(state.lastPrice)}; enabled strategies ${Number(state.enabledStrategies || 0)}; active signals ${Number(state.activeSignalCount || 0)}.`,
      }));
    }
    const latestSignal = currentTrackerSignal(state);
    if (latestSignal?.strategyCode) {
      entries.push(observedLogEntry({
        key: `potential|${sessionKey}|${symbol}|${latestSignal.strategyCode}|${latestSignal.side || ""}|${latestSignal.entryTime || latestSignal.time || state.currentSignalTime || state.lastSignalTime || ""}`,
        sessionId,
        createdAt: latestSignal.entryTime || latestSignal.time || state.currentSignalTime || state.lastSignalTime || observedAt,
        phase: "Potential Trade",
        tone: "setup",
        symbol,
        barTime: latestSignal.entryTime || latestSignal.time || state.currentSignalTime || state.lastSignalTime || "",
        summary: `${symbol} ${latestSignal.strategyCode} ${String(latestSignal.side || "").toUpperCase()} signal appeared.`,
        detail: `Entry ${formatPrice(latestSignal.entryPrice)}; stop ${formatPrice(latestSignal.stopPrice)}; target ${formatPrice(latestSignal.targetPrice)}. Waiting for live candidate/risk validation.`,
      }));
    }
  });

  (Array.isArray(liveDecisions) ? liveDecisions : []).slice(0, 40).forEach((decision) => {
    const event = observedDecisionLogEntry(decision, sessionId, observedAt);
    if (event) entries.push(event);
  });

  return entries;
}

function appendLiveMarksCheckLogEntries(entries, { liveMarks, feedRunning, sessionId, sessionKey, observedAt }) {
  if (!liveMarks && feedRunning) {
    entries.push(observedLogEntry({
      key: `marks-missing|${sessionKey}`,
      sessionId,
      createdAt: observedAt,
      phase: "Metric Checks",
      tone: "warn",
      summary: "Fast marks have not returned yet.",
      detail: "The page is still using slower monitor and metrics polling until the marks endpoint returns a status payload.",
    }));
    return;
  }
  if (!liveMarks) return;
  const checks = liveMarks.checks || {};
  const counts = checks.counts || {};
  const overall = checks.overall || {};
  entries.push(observedLogEntry({
    key: `marks-overall|${sessionKey}|${overall.severity || "unknown"}|${Boolean(overall.ok)}`,
    sessionId,
    createdAt: liveMarks.serverTime || observedAt,
    phase: "Metric Checks",
    tone: checkTone(overall),
    summary: cleanLogText(overall.message || (overall.ok ? "Fast live marks checks are healthy." : "Fast live marks need attention.")),
    detail: metricCheckCountsDetail(counts),
  }));
  [
    ["marketFeed", "Market Data"],
    ["symbolMarks", "Missing Data"],
    ["currentCandles", "Candle Data"],
    ["brokerAccount", "Broker Account"],
    ["brokerSync", "Broker Sync"],
  ].forEach(([key, phase]) => {
    const check = checks[key];
    if (!check || check.ok) return;
    entries.push(observedLogEntry({
      key: `marks-check|${sessionKey}|${key}|${check.severity || "warn"}|${cleanLogText(check.message)}`,
      sessionId,
      createdAt: liveMarks.serverTime || observedAt,
      phase,
      tone: checkTone(check),
      summary: cleanLogText(check.message || `${phase} check needs attention.`),
      detail: metricCheckCountsDetail(counts),
    }));
  });
}

function checkTone(check) {
  const severity = String(check?.severity || "").toLowerCase();
  if (severity === "error") return "error";
  if (severity === "warn") return "warn";
  return check?.ok === false ? "warn" : "active";
}

function metricCheckCountsDetail(counts = {}) {
  const parts = [
    `positions ${formatInteger(counts.positions ?? 0)}`,
    `orders ${formatInteger(counts.orders ?? 0)}`,
    `trades ${formatInteger(counts.trades ?? 0)}`,
  ];
  if (Number(counts.feedStaleSeconds) >= 0) parts.push(`feed stale ${formatInteger(counts.feedStaleSeconds)}s`);
  if (Number(counts.brokerSyncAgeSeconds) >= 0) parts.push(`broker sync age ${formatInteger(counts.brokerSyncAgeSeconds)}s`);
  if (Number(counts.brokerEventAgeSeconds) >= 0) parts.push(`broker event age ${formatInteger(counts.brokerEventAgeSeconds)}s`);
  return parts.join("; ");
}

function observedDecisionLogEntry(decision, sessionId, fallbackTime) {
  const id = decision?.id || `${decision?.symbol || ""}-${decision?.strategyCode || ""}-${decision?.signalTime || ""}-${decision?.status || ""}`;
  const status = String(decision?.status || "").toUpperCase();
  const symbol = String(decision?.symbol || "").toUpperCase();
  let phase = "Live Decision";
  let tone = "info";
  if (status.includes("SUBMITTED") || status.includes("ACCEPTED")) {
    phase = "Trade Entry";
    tone = "accepted";
  } else if (status.includes("REJECTED")) {
    phase = "Risk Gate";
    tone = "blocked";
  } else if (status.includes("BLOCK")) {
    phase = "TopstepX";
    tone = "blocked";
  } else if (status.includes("EXIT") || status.includes("CLOSED") || status.includes("FLAT") || status.includes("SOLD")) {
    phase = "Trade Exit";
    tone = "closed";
  }
  return observedLogEntry({
    key: `decision|${id}|${status}`,
    sessionId,
    createdAt: decision?.createdAt || decision?.entryTime || decision?.signalTime || fallbackTime,
    phase,
    tone,
    symbol,
    barTime: decision?.entryTime || decision?.signalTime || "",
    summary: `${symbol || "Signal"} ${cleanLogText(decision?.strategyCode || "strategy")} ${cleanLogText(decision?.side || "")} ${cleanLogText(decision?.status || "decision")}`.trim(),
    detail: cleanLogText(decision?.requiresAutoOcoBrackets
      ? "ProjectX rejected bracket attachments because this TopstepX account is still using Position Brackets. Enable Auto OCO Brackets in TopstepX Risk Settings before live strategy orders can submit. No entry-only fallback was used."
      : decision?.reason || "Decision recorded by the Live Bot."),
  });
}

function observedLogEntry({ key, sessionId, createdAt, phase, tone, symbol = "", barTime = "", summary, detail }) {
  return {
    id: `observed-${key}`,
    observedKey: key,
    sessionId,
    createdAt: createdAt || liveLogNow(),
    phase,
    tone,
    symbol,
    barTime,
    summary,
    detail,
  };
}

function cleanLogText(value) {
  return String(value ?? "").replace(/\s+/g, " ").trim();
}

function buildEquityReviewStatus({ backendOffline, botStarted, feedRunning, liveMonitor, liveMarks, symbolStates, metrics }) {
  const healthIssues = [];
  if (backendOffline) healthIssues.push({ tone: "error" });
  if (botStarted && !feedRunning) healthIssues.push({ tone: "error" });
  if (Number(liveMonitor?.feedStaleSeconds ?? -1) > 180) healthIssues.push({ tone: "error" });
  const marksSeverity = liveMarks?.checks?.overall?.severity || "";
  if (marksSeverity === "error") healthIssues.push({ tone: "error" });
  if (marksSeverity === "warn") healthIssues.push({ tone: "warn" });
  if (metrics && metrics.brokerMetricsReady === false) healthIssues.push({ tone: "warn" });
  (Array.isArray(symbolStates) ? symbolStates : []).forEach((state) => {
    const health = String(state?.healthStatus || "").toLowerCase();
    const code = String(state?.errorCode || "");
    if (!botStarted && (code === "FEED_STOPPED" || code === "ENTRY_GATE_CLOSED")) return;
    if (health === "error" || health === "warn") healthIssues.push({ tone: health });
  });
  const hasError = healthIssues.some((issue) => issue.tone === "error");
  const hasWarn = healthIssues.some((issue) => issue.tone === "warn");
  return {
    healthLabel: hasError ? "Error" : hasWarn ? "Attention" : botStarted || feedRunning ? "OK" : "Idle",
    healthTone: hasError ? "error" : hasWarn ? "warn" : botStarted || feedRunning ? "ok" : "idle",
    issueCount: healthIssues.length,
  };
}

function equityReviewCode(entry) {
  const phase = String(entry?.phase || "").toUpperCase();
  const tone = String(entry?.tone || "").toUpperCase();
  if (phase.includes("MARKET") || phase.includes("TRACKER") || phase.includes("MISSING") || phase.includes("CANDLE")) return tone === "ERROR" ? "DATA_ERROR" : "DATA_CHECK";
  if (phase.includes("METRIC")) return tone === "ERROR" ? "METRIC_ERROR" : tone === "WARN" ? "METRIC_WARN" : "METRIC_CHECK";
  if (phase.includes("BROKER")) return tone === "ERROR" ? "BROKER_ERROR" : tone === "WARN" ? "BROKER_WARN" : "BROKER_CHECK";
  if (phase.includes("RISK")) return tone === "ERROR" || tone === "BLOCKED" ? "RISK_BLOCK" : "RISK_CHECK";
  if (phase.includes("TOPSTEP") || phase.includes("ORDER")) return tone === "ERROR" || tone === "BLOCKED" ? "ORDER_ERROR" : "ORDER_CHECK";
  if (phase.includes("EXIT") || phase.includes("CLOSE") || phase.includes("POST CLOSE")) return tone === "ERROR" ? "SELL_ERROR" : "SELL_CHECK";
  if (phase.includes("SIGNAL") || phase.includes("CANDIDATE")) return "TRADE_THINKING";
  if (tone === "ERROR" || tone === "BLOCKED") return "SYSTEM_ERROR";
  if (tone === "WARN") return "SYSTEM_WARN";
  return "STATUS";
}

function thinkingDecisionLine(entry) {
  const phase = String(entry?.phase || "Live Bot").trim();
  const symbol = String(entry?.symbol || "").trim();
  const barTime = String(entry?.barTime || "").trim();
  const summary = String(entry?.summary || "--").trim();
  const detail = String(entry?.detail || "").trim();
  const context = [
    symbol,
    barTime ? `bar ${formatEstTime(barTime)}` : "",
  ].filter(Boolean).join(", ");
  let reason = detail;
  if (summary && detail.toLowerCase().startsWith(`${summary.toLowerCase()}:`)) {
    reason = detail.slice(summary.length + 1).trim();
  }
  return `${phase}${context ? ` (${context})` : ""}: ${summary}${reason ? ` - ${reason}` : ""}`;
}

function thinkingLogRowClass(entry) {
  const text = `${entry?.summary || ""} ${entry?.detail || ""}`.toLowerCase();
  const bracketAlert = text.includes("auto oco brackets") || text.includes("position brackets");
  return `futures-thinking-row ${cleanLogText(entry?.tone || "info").toLowerCase()}${bracketAlert ? " bracket-alert" : ""}`;
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
  const visibleCandleToleranceMs = timeframeMinutesForClient(timeframe) * 60000 * 1.6;
  const findVisibleIndex = (time) => findNearestCandleIndex(visibleCandles, time, visibleCandleToleranceMs);
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
    const emptyTitle = graphIsBuilding ? "Graph Sync" : botStarted || marketIdle || backendOffline ? "Chart Pending" : "Chart Idle";
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
          if (entryX == null && (!trade.closed || exitX == null)) {
            return null;
          }
          const popoverWidth = 372;
          const popoverHeight = 132;
          const anchorX = entryX ?? exitX ?? 0;
          const anchorY = entryX == null ? markY : entryY;
          const preferredPopoverX = anchorX + popoverWidth + 34 < plotWidth ? anchorX + 28 : Number(anchorX || 0) - popoverWidth - 28;
          const popoverX = Math.max(10, Math.min(plotWidth - popoverWidth - 10, preferredPopoverX));
          const preferredPopoverY = anchorY - popoverHeight - 34 > priceTop ? anchorY - popoverHeight - 34 : anchorY + 38;
          const popoverY = Math.max(priceTop + 8, Math.min(priceBottom - popoverHeight - 10, preferredPopoverY));
          const tradeHovered = hoveredTradeIndex === index;
          const tradeKey = `${symbol || "chart"}-${timeframe || "tf"}-${trade.id || index}-${trade.entryTime || ""}`;
          const tradeActive = Boolean((entryX != null || exitX != null) && selectedTradeKey === tradeKey);
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

function liveDecisionSidecarSignature(status) {
  const sessionId = Number(status?.running ? status?.sessionId || 0 : 0);
  return [
    sessionId,
    Number(status?.decisionCount || 0),
    Number(status?.acceptedDecisionCount || 0),
    Number(status?.rejectedDecisionCount || 0),
    status?.lastProcessedLiveBarTime || "",
  ].join("|");
}

function mergeMonitorWithMarks(monitor, marks, timeframe) {
  if (!monitor || !marks?.success || !marks.symbols) return monitor;
  const normalizedTimeframe = normalizeClientTimeframe(timeframe || marks.timeframe || monitor.timeframe);
  const marketData = { ...(monitor.marketData || {}) };
  Object.entries(marks.symbols || {}).forEach(([rawSymbol, patch]) => {
    const symbol = String(rawSymbol || "").toUpperCase();
    if (!symbol || !patch?.currentCandle) return;
    marketData[symbol] = mergeCurrentCandleIntoSeries(marketData[symbol], patch.currentCandle);
  });
  return {
    ...monitor,
    timeframe: normalizeClientTimeframe(monitor.timeframe || normalizedTimeframe),
    realtimeRunning: monitor.realtimeRunning || Boolean(marks.feedFresh),
    lastRealtimeEventAt: marks.lastEventAt || monitor.lastRealtimeEventAt,
    serverTime: marks.serverTime || monitor.serverTime,
    feedStaleSeconds: Number.isFinite(Number(marks.feedStaleSeconds)) ? Number(marks.feedStaleSeconds) : monitor.feedStaleSeconds,
    marketData,
    symbolStates: mergeSymbolStatesWithMarks(monitor.symbolStates, marks),
  };
}

function mergeCurrentCandleIntoSeries(series, currentCandle) {
  const candles = Array.isArray(series) ? [...series] : [];
  const patch = normalizeCandle(currentCandle);
  if (!patch.time || Number(patch.close || 0) <= 0) return candles;
  const lastIndex = candles.length - 1;
  if (lastIndex < 0) return [patch];
  const last = normalizeCandle(candles[lastIndex]);
  const lastTime = parseChartTime(last.time);
  const patchTime = parseChartTime(patch.time);
  if (last.time === patch.time) {
    candles[lastIndex] = {
      ...last,
      ...patch,
      high: Math.max(Number(last.high || 0), Number(patch.high || patch.close || 0)),
      low: Math.min(Number(last.low || patch.low || patch.close || 0), Number(patch.low || patch.close || 0)),
      volume: Math.max(Number(last.volume || 0), Number(patch.volume || 0)),
      vwap: Number(last.vwap || 0) || Number(patch.vwap || 0),
      ema9: Number(last.ema9 || 0) || Number(patch.ema9 || 0),
      ema20: Number(last.ema20 || 0) || Number(patch.ema20 || 0),
      rsi14: Number(last.rsi14 || 0) || Number(patch.rsi14 || 0),
      live: true,
    };
    return candles;
  }
  if (patchTime && (!lastTime || patchTime > lastTime)) {
    candles.push(patch);
  }
  return candles;
}

function mergeSymbolStatesWithMarks(symbolStates, marks) {
  const bySymbol = new Map((Array.isArray(symbolStates) ? symbolStates : []).map((state) => [String(state.symbol || "").toUpperCase(), state]));
  Object.entries(marks.symbols || {}).forEach(([rawSymbol, patch]) => {
    const symbol = String(rawSymbol || "").toUpperCase();
    if (!symbol) return;
    const current = bySymbol.get(symbol) || { symbol };
    const lastPrice = Number(patch?.lastPrice || current.lastPrice || 0);
    const currentCandleTime = patch?.currentCandle?.time || current.lastBarTime || "";
    bySymbol.set(symbol, {
      ...current,
      symbol,
      dataSource: current.dataSource || "LIVE_MARKS_CACHE",
      analysisStatus: current.analysisStatus || (marks.feedFresh ? "Tracking live marks" : "Waiting for live marks"),
      healthStatus: current.healthStatus || (marks.feedFresh ? "ok" : "warn"),
      healthDetail: current.healthDetail || (marks.feedFresh ? "Live marks are updating." : "Fast marks are waiting for a fresh ProjectX event."),
      lastPrice,
      lastBarTime: currentCandleTime,
      liveEvents: Math.max(Number(current.liveEvents || 0), Number(patch?.currentCandle?.events || 0)),
    });
  });
  return Array.from(bySymbol.values());
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

function augmentTopstepMetricsWithMarks(metrics, symbolStates) {
  if (!metrics?.broker?.success) return metrics;
  const stateBySymbol = new Map((Array.isArray(symbolStates) ? symbolStates : []).map((state) => [String(state.symbol || "").toUpperCase(), state]));
  const broker = metrics.broker;
  const positions = (Array.isArray(broker.positions) ? broker.positions : []).map((position) => {
    const symbol = String(position?.symbol || "").toUpperCase();
    const markPrice = Number(stateBySymbol.get(symbol)?.lastPrice || position?.markPrice || 0);
    const entryPrice = Number(position?.averagePrice || position?.entryPrice || 0);
    const contracts = Number(position?.contracts || 0);
    const calculatedPnl = markPrice > 0 && entryPrice > 0 && contracts > 0
      ? calculateFuturesPnl(symbol, position.side, entryPrice, markPrice, contracts)
      : Number(position?.pnl || 0);
    return {
      ...position,
      symbol,
      markPrice,
      currentPrice: markPrice,
      entryPrice,
      averagePrice: entryPrice,
      pnl: calculatedPnl,
      unrealizedPnl: calculatedPnl,
    };
  });
  const unrealizedPnl = positions.reduce((total, position) => total + Number(position.unrealizedPnl || 0), 0);
  const realizedPnl = Number(broker.realizedPnl ?? metrics.currentPnl ?? 0);
  const accountSize = Number(metrics.accountSize || broker.accountSize || 0);
  const cashBalance = Number(broker.balance || broker.cashBalance || metrics.currentBalance || 0);
  const currentPnl = realizedPnl + unrealizedPnl;
  const currentBalance = cashBalance > 0 ? cashBalance + unrealizedPnl : accountSize + currentPnl;
  return {
    ...metrics,
    currentPnl,
    currentBalance,
    returnPct: accountSize > 0 ? (currentPnl / accountSize) * 100 : Number(metrics.returnPct || 0),
    openTrades: positions.length,
    broker: {
      ...broker,
      positions,
      unrealizedPnl,
      realizedPnl,
      currentPnl,
      currentBalance,
    },
  };
}

function mergeMetricsWithMarks(metrics, marks) {
  if (!marks?.success) return metrics;
  const account = marks.account || {};
  const current = metrics || {};
  const currentBroker = current.broker || {};
  const positions = Array.isArray(marks.positions) ? marks.positions : (Array.isArray(currentBroker.positions) ? currentBroker.positions : []);
  const trades = Array.isArray(marks.trades) ? marks.trades : (Array.isArray(currentBroker.trades) ? currentBroker.trades : []);
  const orders = Array.isArray(marks.orders) ? marks.orders : (Array.isArray(currentBroker.orders) ? currentBroker.orders : []);
  const accountSize = finiteNumber(account.accountSize, current.accountSize || currentBroker.accountSize || 0);
  const currentPnl = finiteNumber(account.currentPnl, current.currentPnl || 0);
  const currentBalance = finiteNumber(account.currentBalance, current.currentBalance || (accountSize + currentPnl));
  const realizedPnl = finiteNumber(account.realizedPnl, currentBroker.realizedPnl ?? currentPnl);
  const unrealizedPnl = finiteNumber(account.unrealizedPnl, currentBroker.unrealizedPnl ?? 0);
  const returnPct = finiteNumber(account.returnPct, accountSize > 0 ? (currentPnl / accountSize) * 100 : current.returnPct || 0);
  const drawdown = finiteNumber(account.drawdown, current.drawdown || 0);
  const numberOfTrades = Number.isFinite(Number(account.numberOfTrades)) ? Number(account.numberOfTrades) : Number(current.numberOfTrades || trades.length || 0);
  const openTrades = Number.isFinite(Number(account.openTrades)) ? Number(account.openTrades) : Number(current.openTrades || positions.length || 0);
  return {
    ...current,
    success: true,
    dataSource: current.dataSource === "TOPSTEPX" ? current.dataSource : "LIVE_MARKS_CACHE",
    brokerMetricsReady: current.brokerMetricsReady || positions.length > 0 || trades.length > 0,
    currentPnl,
    currentBalance,
    returnPct,
    numberOfTrades,
    openTrades,
    drawdown,
    accountSize,
    lastUpdated: marks.serverTime || current.lastUpdated,
    broker: {
      ...currentBroker,
      success: currentBroker.success || positions.length > 0 || trades.length > 0 || finiteNumberOrNull(account.currentBalance) != null,
      source: currentBroker.source || "LIVE_MARKS_CACHE",
      syncedAt: marks.lastBrokerSyncAt || currentBroker.syncedAt,
      lastEventAt: marks.lastBrokerEventAt || currentBroker.lastEventAt,
      accountSize,
      currentPnl,
      currentBalance,
      realizedPnl,
      unrealizedPnl,
      returnPct,
      drawdown,
      numberOfTrades,
      openTrades,
      positions,
      trades,
      orders,
    },
  };
}

function finiteNumber(value, fallback = 0) {
  if (value == null || value === "") return Number(fallback || 0);
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : Number(fallback || 0);
}

function finiteNumberOrNull(value) {
  if (value == null || value === "") return null;
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : null;
}

function buildBrokerOpenTradeRows(positions, decisions = []) {
  return (Array.isArray(positions) ? positions : [])
    .filter((position) => Number(position?.contracts || 0) > 0)
    .map((position, index) => {
      const symbol = String(position.symbol || "").toUpperCase();
      const side = String(position.side || "").toUpperCase() === "SHORT" ? "SHORT" : "LONG";
      const contracts = Number(position.contracts || 0);
      const entryPrice = Number(position.entryPrice || position.averagePrice || 0);
      const matchedDecision = findBrokerDecisionMeta(decisions, {
        symbol,
        side,
        contracts,
        entryPrice,
        entryTime: position.createdAt,
        createdAt: position.createdAt,
      });
      return {
        id: `topstep-position-${position.id || position.contractId || position.symbol || index}`,
        symbol,
        strategyCode: tradeStrategyCode(matchedDecision, "Topstep"),
        strategyName: matchedDecision?.strategyName || "Topstep Open Position",
        side,
        contracts,
        entryPrice,
        exitPrice: 0,
        pnl: Number(position.unrealizedPnl ?? position.pnl ?? 0),
        status: "LIVE_TOPSTEP",
        entryReason: buildEntryReason({
          strategyCode: matchedDecision?.strategyCode,
          strategyName: matchedDecision?.strategyName,
          side,
          contracts,
          entryPrice,
          stopPrice: matchedDecision?.stopPrice,
          targetPrice: matchedDecision?.targetPrice,
          signalTime: matchedDecision?.signalTime,
          fallback: "Open position from Topstep Position/searchOpen; mark PnL updates from live ProjectX price.",
        }),
        exitReason: "Open trade. Exit reason and fees will populate after Topstep reports the close.",
        fees: null,
        reason: "Open position from Topstep Position/searchOpen; mark PnL updates from live ProjectX price.",
        entryTime: position.createdAt,
        createdAt: position.createdAt,
      };
    });
}

function buildBrokerClosedTradeRows(trades, decisions = []) {
  const openLots = new Map();
  const rows = [];
  const sortedTrades = (Array.isArray(trades) ? trades : [])
    .filter((trade) => trade && !trade.voided)
    .sort((first, second) => (parseChartTime(first.createdAt) || 0) - (parseChartTime(second.createdAt) || 0));
  sortedTrades.forEach((trade, index) => {
    const symbol = String(trade.symbol || "").toUpperCase();
    const key = brokerTradePairKey(trade);
    const tradeSide = normalizeTopstepTradeSide(trade.side);
    const contracts = Math.max(0, Number(trade.contracts || 0));
    const fillPrice = Number(trade.price || trade.exitPrice || 0);
    if (!trade.closed) {
      if (contracts > 0 && fillPrice > 0 && tradeSide) {
        const lots = openLots.get(key) || [];
        lots.push({
          id: trade.id || trade.orderId || `${symbol}-${index}`,
          side: tradeSide,
          remaining: contracts,
          price: fillPrice,
          createdAt: trade.createdAt,
        });
        openLots.set(key, lots);
      }
      return;
    }

    const lots = openLots.get(key) || [];
    let remaining = contracts || 1;
    let matchedContracts = 0;
    let weightedEntry = 0;
    let entryTime = "";
    let entrySide = "";
    lots.forEach((lot) => {
      if (remaining <= 0 || lot.remaining <= 0 || lot.side === tradeSide) return;
      const take = Math.min(lot.remaining, remaining);
      lot.remaining -= take;
      remaining -= take;
      matchedContracts += take;
      weightedEntry += lot.price * take;
      entryTime = entryTime || lot.createdAt;
      entrySide = entrySide || lot.side;
    });
    openLots.set(key, lots.filter((lot) => lot.remaining > 0));

    const fees = Number(trade.fees || 0);
    const entryPrice = matchedContracts > 0 ? weightedEntry / matchedContracts : 0;
    const rowContracts = matchedContracts || contracts;
    const positionSide = entrySide ? positionSideFromEntrySide(entrySide) : positionSideFromClosingSide(tradeSide);
    const matchedDecision = findBrokerDecisionMeta(decisions, {
      symbol,
      side: positionSide,
      contracts: rowContracts,
      entryPrice,
      entryTime,
      createdAt: trade.createdAt,
      closed: true,
    });
    const reason = entryPrice > 0
      ? `Topstep Trade/search paired entry and close fills${fees ? `; fees ${formatCurrency(fees)}` : ""}.`
      : `Topstep Trade/search closed fill${fees ? `; fees ${formatCurrency(fees)}` : ""}; entry fill outside current sync window.`;
    rows.push({
      id: `topstep-trade-${trade.id || trade.orderId || index}`,
      symbol,
      strategyCode: tradeStrategyCode(matchedDecision, "Topstep"),
      strategyName: matchedDecision?.strategyName || "Topstep Closed Trade",
      side: positionSide,
      contracts: rowContracts,
      entryPrice,
      exitPrice: fillPrice,
      pnl: Number(trade.pnl || 0),
      status: "SOLD_TOPSTEP",
      entryReason: buildEntryReason({
        strategyCode: matchedDecision?.strategyCode,
        strategyName: matchedDecision?.strategyName,
        side: positionSide,
        contracts: rowContracts,
        entryPrice,
        stopPrice: matchedDecision?.stopPrice,
        targetPrice: matchedDecision?.targetPrice,
        signalTime: matchedDecision?.signalTime,
        fallback: entryPrice > 0
          ? "Topstep paired this row from broker entry and close fills. No local strategy decision matched this fill, so the exact strategy source is unavailable in this sync."
          : "Topstep reported the close fill, but the matching entry fill is outside the current broker sync window.",
      }),
      exitReason: matchedDecision?.exitReason || `Closed by Topstep fill at ${formatPrice(fillPrice)}. Net PnL uses broker-reported fees.`,
      fees,
      reason,
      entryTime: entryTime || trade.createdAt,
      createdAt: trade.createdAt,
    });
  });
  return rows.sort((first, second) => (parseChartTime(second.createdAt) || 0) - (parseChartTime(first.createdAt) || 0));
}

function brokerTradePairKey(trade) {
  const symbol = String(trade?.symbol || "").toUpperCase();
  const contractId = String(trade?.contractId || "").toUpperCase();
  return `${symbol}:${contractId}`;
}

function normalizeTopstepTradeSide(side) {
  const value = String(side || "").toUpperCase();
  if (value === "0" || value === "BUY" || value === "BID") return "BUY";
  if (value === "1" || value === "SELL" || value === "ASK") return "SELL";
  return value === "LONG" ? "BUY" : value === "SHORT" ? "SELL" : "";
}

function positionSideFromEntrySide(entrySide) {
  return entrySide === "SELL" ? "SHORT" : "LONG";
}

function positionSideFromClosingSide(closingSide) {
  return closingSide === "BUY" ? "SHORT" : "LONG";
}

function tradeStrategyCode(decision, fallback = "Topstep") {
  const code = String(decision?.strategyCode || "").trim();
  return code || fallback;
}

function findBrokerDecisionMeta(decisions, target) {
  const symbol = String(target?.symbol || "").toUpperCase();
  const side = String(target?.side || "").toUpperCase();
  if (!symbol || !side) return null;
  const targetTime = parseChartTime(target?.entryTime || target?.createdAt);
  const targetPrice = Number(target?.entryPrice || 0);
  const targetContracts = Number(target?.contracts || 0);
  const tick = instrumentTickSize(symbol);
  let best = null;
  let bestScore = Infinity;
  (Array.isArray(decisions) ? decisions : []).forEach((decision) => {
    if (!isEntryDecision(decision)) return;
    if (String(decision.symbol || "").toUpperCase() !== symbol) return;
    if (String(decision.side || "").toUpperCase() !== side) return;
    const strategyCode = String(decision.strategyCode || "").trim();
    if (!strategyCode || strategyCode.toUpperCase() === "TOPSTEP") return;
    const decisionTime = parseChartTime(decision.entryTime || decision.signalTime || decision.createdAt);
    const decisionPrice = Number(decision.entryPrice || 0);
    const decisionContracts = Number(decision.contracts || 0);
    const timeMinutes = targetTime && decisionTime ? Math.abs(targetTime - decisionTime) / 60000 : 999;
    const priceTicks = targetPrice > 0 && decisionPrice > 0 ? Math.abs(targetPrice - decisionPrice) / Math.max(tick, 0.01) : 24;
    const contractPenalty = targetContracts > 0 && decisionContracts > 0 && targetContracts !== decisionContracts ? 18 : 0;
    const score = timeMinutes + priceTicks * 2 + contractPenalty;
    if (score < bestScore) {
      bestScore = score;
      best = decision;
    }
  });
  if (bestScore > 90) return null;
  return target?.closed ? enrichBrokerDecisionWithExit(best, decisions) : best;
}

function enrichBrokerDecisionWithExit(decision, decisions) {
  if (!decision) return null;
  const entryKey = tradeExitLookupKey(decision);
  const exit = (Array.isArray(decisions) ? decisions : []).find((candidate) => (
    candidate?.id !== decision.id
      && isClosedTradeDecision(candidate)
      && tradeEntryKey(candidate) === entryKey
  ));
  if (!exit) return decision;
  return {
    ...decision,
    status: exit.status || decision.status,
    pnl: exit.pnl,
    exitPrice: exit.exitPrice,
    exitReason: exit.exitReason || exit.reason,
    reason: exit.reason || decision.reason,
    exitTime: exit.entryTime || exit.createdAt,
    closedDecisionId: exit.id,
  };
}

function buildEntryReason({
  strategyCode,
  strategyName,
  side,
  contracts,
  entryPrice,
  stopPrice,
  targetPrice,
  signalTime,
  fallback,
}) {
  const code = String(strategyCode || "").trim();
  if (!code || code.toUpperCase() === "TOPSTEP") {
    return fallback || "Broker-sourced Topstep trade; no local live-strategy decision was matched.";
  }
  const readableName = strategyName && strategyName !== code ? `${code} (${strategyName})` : code;
  const direction = String(side || "").toUpperCase() === "SHORT" ? "short" : "long";
  const thesis = strategyEntryThesis(code, direction);
  const pricePlan = [
    Number(entryPrice || 0) > 0 ? `entry ${formatPrice(entryPrice)}` : "",
    Number(stopPrice || 0) > 0 ? `stop ${formatPrice(stopPrice)}` : "",
    Number(targetPrice || 0) > 0 ? `target ${formatPrice(targetPrice)}` : "",
  ].filter(Boolean).join(", ");
  const sizeText = Number(contracts || 0) > 0 ? `${contracts} contract${Number(contracts) === 1 ? "" : "s"}` : "live size";
  const timeText = signalTime ? ` Signal candle: ${formatEstTime(signalTime)}.` : "";
  return `${readableName} triggered a ${direction} setup for ${sizeText}. ${thesis}${pricePlan ? ` Plan: ${pricePlan}.` : ""}${timeText}`;
}

function strategyEntryThesis(strategyCode, direction) {
  const code = String(strategyCode || "").toUpperCase();
  const dir = direction === "short" ? "downside" : "upside";
  const map = {
    AFT: `Afternoon continuation expects the intraday trend/channel pressure to keep carrying ${dir}.`,
    CMOM: `Close momentum expects late-session participation to continue in the trade direction before the close guard.`,
    FVG: `Fair-value-gap continuation expects the imbalance retest to hold and release in the trade direction.`,
    KREV: `Keltner reversion expects an extended move to mean-revert after price rejects the outer volatility band.`,
    LORB: `Late ORB continuation expects a delayed opening-range break to keep follow-through.`,
    MSCALP: `Micro scalp expects a short-duration impulse with tight risk and quick reward/risk completion.`,
    OMOM: `Opening momentum expects the early range break and volume impulse to continue before the setup window decays.`,
    ORB: `Opening range expects the session range break to attract continuation flow.`,
    ORB2: `Second-wave opening range expects the retest/continuation leg to follow the earlier range break.`,
    PDB: `Prior-day breakout expects yesterday's reference level break to pull in continuation flow.`,
    SHDW: `Mini shadow follows the larger contract signal when the micro contract confirms the same directional pressure.`,
    SWEEP: `Liquidity sweep expects the stop-run/reclaim pattern to trap the other side and continue.`,
    VPB: `Value-area reclaim expects acceptance back through value to continue in the reclaim direction.`,
    VRCL: `VWAP reclaim expects the move back through VWAP to hold and continue.`,
    VWAP: `VWAP pullback expects trend participation after price respects VWAP.`,
  };
  return map[code] || `The live strategy rules marked the candle as having favorable ${dir} expectancy after the configured filters passed.`;
}

function metricSourceDetail(metrics, type) {
  if (!metrics) return "Waiting for Topstep sync";
  if (metrics?.broker?.success) {
    if (type === "pnl" && Math.abs(Number(metrics.broker.unrealizedPnl || 0)) > 0) {
      return `Topstep balance + live mark PnL ${formatCurrency(metrics.broker.unrealizedPnl)}`;
    }
    if (type === "trades") return "Closed trades from Topstep Trade/search";
    if (type === "balance") return "Balance from Topstep Account/search";
    if (type === "drawdown") return "Topstep account drawdown fallback guarded";
    return "Topstep broker-sourced";
  }
  return "Local fallback until Topstep responds";
}

function buildSymbolTrackers({ symbols, states, decisions, marketData, brokerPositions, brokerClosedTrades, brokerAuthoritative, botStarted }) {
  const stateBySymbol = new Map((Array.isArray(states) ? states : []).map((state) => [String(state.symbol || "").toUpperCase(), state]));
  const brokerBySymbol = buildBrokerPositionMap(brokerPositions);
  const closedBrokerBySymbol = buildBrokerClosedTradeMap(brokerClosedTrades);
  const decisionRows = Array.isArray(decisions) ? decisions : [];
  return (Array.isArray(symbols) ? symbols : DEFAULT_SYMBOLS).map((symbol) => {
    const normalizedSymbol = String(symbol || "").toUpperCase();
    const state = stateBySymbol.get(normalizedSymbol) || null;
    const brokerPosition = brokerBySymbol.get(normalizedSymbol) || null;
    const candles = Array.isArray(marketData?.[normalizedSymbol]) ? marketData[normalizedSymbol] : [];
    const sessionCandles = latestSessionChartCandles(candles.map(normalizeCandle).filter((candle) => candle.time && Number(candle.close || 0) > 0));
    const lastPrice = Number(latestChartPrice(sessionCandles) || state?.lastPrice || 0);
    const firstPrice = Number(sessionCandles[0]?.close || 0);
    const changePct = firstPrice > 0 ? ((lastPrice - firstPrice) / firstPrice) * 100 : Number(state?.changePct || 0);
    const trades = decisionRows.filter((decision) => isEntryDecision(decision) && String(decision.symbol || "").toUpperCase() === normalizedSymbol);
    const liveTrades = trades.filter((trade) => !isClosedTradeDecision(trade));
    const localPnl = trades.reduce((total, trade) => {
      const closed = isClosedTradeDecision(trade);
      const entryPrice = Number(trade.entryPrice || 0);
      const exitPrice = Number(trade.exitPrice || 0);
      const contracts = Number(trade.contracts || 0);
      const markPrice = closed ? exitPrice || entryPrice : lastPrice || entryPrice;
      const realizedPnl = Number(trade.pnl || 0);
      const calculatedPnl = calculateFuturesPnl(normalizedSymbol, trade.side, entryPrice, markPrice, contracts);
      return total + (closed && realizedPnl !== 0 ? realizedPnl : calculatedPnl);
    }, 0);
    const brokerLiveTrades = Number(brokerPosition?.contracts || 0) > 0 ? 1 : 0;
    const brokerClosed = closedBrokerBySymbol.get(normalizedSymbol) || { count: 0, pnl: 0 };
    const liveTradeCount = Math.max(liveTrades.length, brokerLiveTrades);
    const totalTrades = brokerAuthoritative
      ? brokerClosed.count + brokerLiveTrades
      : trades.length;
    const pnl = brokerAuthoritative
      ? Number(brokerClosed.pnl || 0) + Number(brokerPosition?.unrealizedPnl ?? brokerPosition?.pnl ?? 0)
      : localPnl;
    const signal = trackerSignalLabel(state, liveTradeCount, botStarted);
    const health = trackerHealthLabel(state, botStarted);
    return {
      symbol: normalizedSymbol,
      lastPrice,
      changePct,
      pnl,
      totalTrades,
      liveTrades: liveTradeCount,
      signal: signal.label,
      signalTone: signal.tone,
      healthLabel: health.label,
      healthTone: health.tone,
      errorCode: health.errorCode,
      healthDetail: health.tone === "ok" ? "" : health.detail,
      detail: brokerLiveTrades > 0 ? "Topstep open position verified; PnL is marked from live price." : trackerDetail(state, signal, botStarted, lastPrice),
    };
  });
}

function buildBrokerPositionMap(positions) {
  const map = new Map();
  (Array.isArray(positions) ? positions : []).forEach((position) => {
    const symbol = String(position?.symbol || "").toUpperCase();
    if (!symbol) return;
    const existing = map.get(symbol) || { symbol, contracts: 0, pnl: 0, unrealizedPnl: 0 };
    existing.contracts += Number(position.contracts || 0);
    existing.pnl += Number(position.pnl || 0);
    existing.unrealizedPnl += Number(position.unrealizedPnl ?? position.pnl ?? 0);
    existing.entryPrice = Number(position.entryPrice || position.averagePrice || existing.entryPrice || 0);
    existing.side = position.side || existing.side || "LONG";
    map.set(symbol, existing);
  });
  return map;
}

function buildBrokerClosedTradeMap(trades) {
  const map = new Map();
  (Array.isArray(trades) ? trades : []).forEach((trade) => {
    const symbol = String(trade?.symbol || "").toUpperCase();
    if (!symbol) return;
    const existing = map.get(symbol) || { count: 0, pnl: 0 };
    existing.count += 1;
    existing.pnl += Number(trade.pnl || 0);
    map.set(symbol, existing);
  });
  return map;
}

function trackerSignalLabel(state, liveTrades, botStarted) {
  if (!botStarted) return { label: "Idle", tone: "idle" };
  if (liveTrades > 0) return { label: "Trading", tone: "trading" };
  const currentSignal = currentTrackerSignal(state);
  if (currentSignal?.strategyCode) {
    return {
      label: `${currentSignal.strategyCode} ${String(currentSignal.side || "").toUpperCase()}`,
      tone: "setup",
      currentSignal,
    };
  }
  if (Number(state?.currentSignalCount || 0) > 0) {
    return { label: "Setup", tone: "setup" };
  }
  if (Number(state?.enabledStrategies || 0) > 0) {
    return { label: "Looking", tone: "looking" };
  }
  return { label: "Idle", tone: "idle" };
}

function trackerHealthLabel(state, botStarted) {
  if (!botStarted) {
    return { label: "Health Idle", tone: "idle", errorCode: "", detail: "Bot is not running." };
  }
  const health = String(state?.healthStatus || "").toLowerCase();
  const errorCode = String(state?.errorCode || "").trim();
  const detail = state?.healthDetail || state?.analysisStatus || "Waiting for health check.";
  if (health === "error") {
    return { label: "Health Error", tone: "error", errorCode: errorCode || "HEALTH_ERROR", detail };
  }
  if (health === "warn") {
    return { label: "Health Check", tone: "warn", errorCode: errorCode || "HEALTH_WARN", detail };
  }
  if (health === "idle") {
    return { label: "Health Idle", tone: "idle", errorCode, detail };
  }
  return { label: "Health OK", tone: "ok", errorCode, detail };
}

function trackerDetail(state, signal, botStarted, lastPrice) {
  if (!botStarted) return "Not started";
  if (signal?.tone === "trading") return "In trade; PnL updates with current mark price.";
  if (signal?.currentSignal?.strategyCode) {
    const entryTime = signal.currentSignal.entryTime || signal.currentSignal.time || signal.currentSignal.signalTime || state?.currentSignalTime || "";
    return `Potential trade at ${formatEstTime(entryTime)}; waiting for live validation.`;
  }
  const latestCode = state?.lastSignalCode || (Array.isArray(state?.latestSignals) ? state.latestSignals[0]?.strategyCode : "");
  const latestSide = state?.lastSignalSide || (Array.isArray(state?.latestSignals) ? state.latestSignals[0]?.side : "");
  const latestTime = state?.lastSignalTime || (Array.isArray(state?.latestSignals) ? state.latestSignals[0]?.time : "");
  if (latestCode && latestTime) {
    return `${state?.analysisStatus || "Tracking market data"}; last signal ${latestCode} ${String(latestSide || "").toUpperCase()} at ${formatEstTime(latestTime)}.`;
  }
  return botStarted ? state?.analysisStatus || (lastPrice > 0 ? "Tracking market data" : "Waiting for data") : "Not started";
}

function currentTrackerSignal(state) {
  const explicitCurrent = Array.isArray(state?.currentSignals) ? state.currentSignals[0] : null;
  if (explicitCurrent?.strategyCode) return explicitCurrent;
  if (state?.currentSignalCode) {
    return {
      strategyCode: state.currentSignalCode,
      strategyName: state.currentSignalName,
      side: state.currentSignalSide,
      entryTime: state.currentSignalTime,
      time: state.currentSignalTime,
    };
  }
  const latestSignal = Array.isArray(state?.latestSignals) ? state.latestSignals[0] : null;
  const latestTime = latestSignal?.time || state?.lastSignalTime || "";
  if (latestSignal?.strategyCode && isFreshPotentialSignal(latestTime, state?.lastBarTime)) {
    return latestSignal;
  }
  return null;
}

function isFreshPotentialSignal(signalTime, lastBarTime) {
  const signalMs = parseChartTime(signalTime);
  const barMs = parseChartTime(lastBarTime);
  if (!signalMs || !barMs) return false;
  const diffMinutes = Math.abs(barMs - signalMs) / 60000;
  return diffMinutes <= 2;
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

function mergeLiveTradeDecisions(decisions) {
  const rows = Array.isArray(decisions) ? decisions : [];
  const exitsByEntryKey = new Map();
  rows.forEach((decision) => {
    if (!isClosedTradeDecision(decision)) return;
    exitsByEntryKey.set(tradeEntryKey(decision), decision);
  });

  const usedExitIds = new Set();
  const mergedRows = rows
    .filter((decision) => !isClosedTradeDecision(decision))
    .map((decision) => {
      if (!isEntryDecision(decision)) return decision;
      const exit = exitsByEntryKey.get(tradeExitLookupKey(decision));
      if (!exit) return decision;
      usedExitIds.add(exit.id);
      return {
        ...decision,
        status: exit.status || decision.status,
        exitPrice: exit.exitPrice,
        pnl: exit.pnl,
        mfe: exit.mfe,
        mae: exit.mae,
        exitReason: exit.exitReason || exit.reason,
        reason: exit.reason || decision.reason,
        exitTime: exit.entryTime || exit.createdAt,
        closedDecisionId: exit.id,
      };
    });

  rows.forEach((decision) => {
    if (isClosedTradeDecision(decision) && !usedExitIds.has(decision.id)) {
      mergedRows.push(decision);
    }
  });
  return mergedRows;
}

function tradeEntryKey(decision) {
  return [
    String(decision?.symbol || "").toUpperCase(),
    String(decision?.strategyCode || "").toUpperCase(),
    String(decision?.signalTime || decision?.entryTime || ""),
  ].join("|");
}

function tradeExitLookupKey(decision) {
  return [
    String(decision?.symbol || "").toUpperCase(),
    String(decision?.strategyCode || "").toUpperCase(),
    String(decision?.entryTime || decision?.signalTime || ""),
  ].join("|");
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
      entryReason: trade.entryReason,
      exitReason: trade.exitReason,
      fees: trade.fees,
    };
  });
}

function isEntryDecision(decision) {
  const status = String(decision?.status || "").toUpperCase();
  const hasEntry = Number(decision?.entryPrice || 0) > 0;
  return status.includes("ACCEPTED") || status.includes("SUBMITTED") || status.includes("LIVE_TOPSTEP") || (hasEntry && (status.includes("EXIT") || status.includes("CLOSED") || status.includes("FLAT") || status.includes("SOLD")));
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

function findNearestCandleIndex(candles, targetTime, maxDistanceMs = Infinity) {
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
  if (nearestDistance > maxDistanceMs) return null;
  return nearestIndex;
}

function timeframeMinutesForClient(value) {
  if (value === "5m") return 5;
  if (value === "30m") return 30;
  if (value === "1h") return 60;
  return 1;
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
        <div>Entry Reason</div>
        <div>Exit Reason</div>
        <div>Fees</div>
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
            <div className="app-trade-notes">{entryReasonForTrade(trade)}</div>
            <div className="app-trade-notes">{exitReasonForTrade(trade)}</div>
            <div className="app-trade-fees">{formatFees(trade.fees)}</div>
          </div>
        ))
      ) : (
        <div className="app-empty">{mode === "live" ? "No live trade intents yet." : "No live bot trade records yet."}</div>
      )}
    </div>
  );
}

function entryReasonForTrade(trade) {
  if (trade?.entryReason) return trade.entryReason;
  return buildEntryReason({
    strategyCode: trade?.strategyCode,
    strategyName: trade?.strategyName,
    side: trade?.side,
    contracts: trade?.contracts,
    entryPrice: trade?.entryPrice,
    stopPrice: trade?.stopPrice,
    targetPrice: trade?.targetPrice,
    signalTime: trade?.signalTime,
    fallback: trade?.reason || "Entry source is waiting on broker or live-decision sync.",
  });
}

function exitReasonForTrade(trade) {
  if (trade?.exitReason) return trade.exitReason;
  if (isClosedTradeDecision(trade)) {
    return trade?.reason || "Closed trade; broker close fill has been reported.";
  }
  return "Open trade. Exit reason will populate after the trade closes.";
}

function formatFees(value) {
  if (value == null || value === "") return "--";
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return "--";
  return formatCurrency(Math.abs(numeric));
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
  if (value.includes("ACCEPTED") || value.includes("SUBMITTED") || value.includes("EXIT") || value.includes("FLAT") || value.includes("SOLD") || value.includes("LIVE_TOPSTEP")) return "app-badge app-positive-badge";
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

function formatAccountCurrency(value) {
  const numeric = Number(value || 0);
  return `$${numeric.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatCompactCurrency(value) {
  const numeric = Number(value || 0);
  if (numeric >= 1000) return `$${Math.round(numeric / 1000)}K`;
  return `$${numeric.toFixed(0)}`;
}

function formatCompactSignedCurrency(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return "";
  const sign = numeric > 0 ? "+" : numeric < 0 ? "-" : "";
  const abs = Math.abs(numeric);
  if (abs >= 1000) {
    return `${sign}$${(abs / 1000).toFixed(abs >= 100000 ? 0 : 1)}k`;
  }
  return `${sign}$${abs.toFixed(0)}`;
}

function formatStrategySlotSummary(metrics) {
  const sourceMetrics = metrics || {};
  const profit = firstFiniteMetric(sourceMetrics, ["totalProfit", "resultTotalProfit", "profit"]);
  const winRate = firstFiniteMetric(sourceMetrics, ["winRate"]);
  const tradeCount = firstFiniteMetric(sourceMetrics, ["trades", "numTrades", "resultTrades"]);
  const parts = [];
  if (Number.isFinite(winRate)) parts.push(`${winRate.toFixed(2)}% win`);
  if (Number.isFinite(profit)) parts.push(`${formatCompactSignedCurrency(profit)} PnL`);
  if (Number.isFinite(tradeCount)) parts.push(`${formatInteger(tradeCount)} trades`);
  return parts.length ? parts.join(" | ") : "Copy backtest first";
}

function firstFiniteMetric(metrics, keys) {
  for (const key of keys) {
    const numeric = Number(metrics?.[key]);
    if (Number.isFinite(numeric)) return numeric;
  }
  return Number.NaN;
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

function liveLogNow() {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "America/New_York",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(new Date());
  const lookup = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${lookup.year}-${lookup.month}-${lookup.day} ${lookup.hour}:${lookup.minute}`;
}

function formatIndicator(value) {
  const numeric = Number(value || 0);
  return numeric > 0 ? numeric.toFixed(1) : "--";
}
