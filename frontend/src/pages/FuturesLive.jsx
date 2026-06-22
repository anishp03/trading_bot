import { startTransition, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useOutletContext } from "react-router-dom";
import LiveTradingWorkspace from "../components/LiveTradingWorkspace.jsx";
import TradeAnalysisModal from "../components/TradeAnalysisModal.jsx";
import {
  chartCandlesForMonitorTimeline,
  displayCandlesForChart,
  liveBotControlState,
  liveMonitorCacheKey,
  liveMonitorMatchesCacheKey,
  liveMarksRequestKey,
  liveMonitorRequestKey,
  monitorLimitForTimeframe,
  mergeSeriesCandleVolume,
  resolveLivePatchVolume,
  shouldAppendLivePatchCandle,
  visibleLiveEventDetailEntries,
} from "./futuresLiveChartUtils.js";
import {
  composeTradeCacheRealizedPnl,
  isBrokerConfirmedTradeCacheRow,
  mergeTradeCachePnlFields,
  shouldCreateLocalClosedTradeCacheRow,
} from "./liveTradeCacheUtils.js";
import { apiFetch, isApiNetworkError } from "../utils/api.js";
import { EASTERN_TIME_LABEL, formatEstTime } from "../utils/time.js";

const DEFAULT_SYMBOLS = ["MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL"];
const TIMEFRAME_OPTIONS = [
  { value: "1m", label: "1m" },
  { value: "5m", label: "5m" },
  { value: "30m", label: "30m" },
  { value: "1h", label: "1h" },
  { value: "4h", label: "4h" },
];
const LIVE_MARKS_REFRESH_MS = 1000;
const LIVE_MARKS_UI_REFRESH_MS = LIVE_MARKS_REFRESH_MS;
const CHART_UI_REFRESH_MS = LIVE_MARKS_REFRESH_MS;
const LIVE_RECONCILE_REFRESH_MS = 10000;
const HEALTH_WARN_HOLD_MS = 25000;
const MARKET_DATA_STALE_SECONDS = 30;
const MIN_OPENING_CHART_BARS = 24;
const BROKER_SOURCE_TOPSTEPX = "TOPSTEPX";
const LIVE_TRADE_CACHE_VERSION = 1;
const LIVE_TRADE_CACHE_MAX_ROWS = 10000;
const LIVE_HISTORY_REQUEST_LIMIT = 10000;
const LIVE_ALL_TRADES_PAGE_SIZE = 500;
const DEFAULT_PROFILE = "TOPSTEP_50K";
const DEFAULT_ACCOUNT_PROFILE = "";
const DEFAULT_STRATEGY_PRESET = "bestbiasfree";
const RISK_SIZING_STATIC = "STATIC_WITHDRAW_DAILY";
const RISK_SIZING_DYNAMIC = "DYNAMIC_COMPOUND_MLL";
const LIVE_RISK_SIZING_MODE = RISK_SIZING_DYNAMIC;
const RISK_SIZING_OPTIONS = [
  {
    value: RISK_SIZING_STATIC,
    label: "Static Baseline",
    policy: {
      stopRiskBufferMultiplier: 1,
      dailyRoomUsagePct: 1,
      mllRoomUsagePct: 1,
      maxRiskMultiplier: 1,
      dllReserveDollars: 0,
      mllReserveDollars: 0,
    },
  },
  {
    value: RISK_SIZING_DYNAMIC,
    label: "Dynamic DLL/MLL",
    policy: {
      stopRiskBufferMultiplier: 1.2,
      dailyRoomUsagePct: 0.55,
      mllRoomUsagePct: 0.3,
      maxRiskMultiplier: 3,
      dllReserveDollars: 200,
      mllReserveDollars: 0,
    },
  },
];
const CONTROL_STRATEGY_PRESET = "backtestbias92k";
const BIAS_FREE_STRATEGY_PRESET = "biasfree92k";
const BEST_BIAS_FREE_STRATEGY_PRESET = "bestbiasfree";
const CANONICAL_STRATEGY_PRESETS = [
  { name: CONTROL_STRATEGY_PRESET, label: "Backtest Bias 92k" },
  { name: BIAS_FREE_STRATEGY_PRESET, label: "Bias-Free 92k" },
  { name: BEST_BIAS_FREE_STRATEGY_PRESET, label: "Best Bias-Free" },
];
const MICRO_SYMBOLS = new Set(["MES", "MNQ", "M2K", "MGC", "MYM", "MCL"]);
const EMPTY_TOPSTEP_ACCOUNT = { code: "", name: "No saved account", accountId: "", label: "No saved account" };
const RISK_PROFILE_FALLBACKS = [
  {
    code: "TOPSTEP_50K",
    name: "50K",
    accountSize: 50000,
    maxTrailingDrawdown: 2000,
    dailyLossLimit: 1000,
    maxRiskPerTrade: 700,
    maxContracts: 5,
    maxMicroContracts: 50,
    maxOpenPositions: 3,
    maxAggregateContracts: 50,
    maxAggregateMiniUnits: 5,
    profitTarget: 0,
  },
  {
    code: "TOPSTEP_100K",
    name: "100K",
    accountSize: 100000,
    maxTrailingDrawdown: 3000,
    dailyLossLimit: 2000,
    maxRiskPerTrade: 1400,
    maxContracts: 10,
    maxMicroContracts: 100,
    maxOpenPositions: 3,
    maxAggregateContracts: 100,
    maxAggregateMiniUnits: 10,
    profitTarget: 0,
  },
  {
    code: "TOPSTEP_150K",
    name: "150K",
    accountSize: 150000,
    maxTrailingDrawdown: 4500,
    dailyLossLimit: 3000,
    maxRiskPerTrade: 2100,
    maxContracts: 15,
    maxMicroContracts: 150,
    maxOpenPositions: 3,
    maxAggregateContracts: 150,
    maxAggregateMiniUnits: 15,
    profitTarget: 0,
  },
];
const DEFAULT_TRADE_FILTERS = {
  outcome: "all",
  symbol: "all",
  side: "all",
  strategy: "all",
  sort: "newest",
  startDate: "",
  endDate: "",
};
const EMPTY_TRADE_METRICS = {
  totalPnl: 0,
  totalFees: 0,
  returnPct: 0,
  trades: 0,
  winRate: 0,
  avgWin: 0,
  avgLoss: 0,
  avgDay: 0,
  avgWeek: 0,
  avgMonth: 0,
};
const FALLBACK_PROFILE = {
  code: "TOPSTEP_50K",
  name: "50K",
  accountSize: 50000,
  maxTrailingDrawdown: 2000,
  dailyLossLimit: 1000,
  maxRiskPerTrade: 700,
  maxContracts: 5,
  maxMicroContracts: 50,
  maxOpenPositions: 3,
  maxAggregateContracts: 50,
  maxAggregateMiniUnits: 5,
  profitTarget: 0,
};
const DEFAULT_LIVE_RISK_CONFIG = {
  referenceSymbol: "MNQ",
  accountSize: "50000",
  maxTrailingDrawdown: "2000",
  dailyLossLimit: "1000",
  maxRiskPerTrade: "700",
  maxContracts: "50",
  commissionPerContract: "1.24",
  slippageTicks: "1",
  profitTarget: "0",
  maxOpenPositions: "3",
  maxAggregateContracts: "50",
  maxAggregateMiniUnits: "5",
};

function useStableCallback(callback) {
  const callbackRef = useRef(callback);
  useEffect(() => {
    callbackRef.current = callback;
  }, [callback]);
  return useCallback((...args) => callbackRef.current?.(...args), []);
}

export default function FuturesLive() {
  const [selectedChartSymbol, setSelectedChartSymbol] = useState(DEFAULT_SYMBOLS[0]);
  const [selectedTimeframe, setSelectedTimeframe] = useState("1m");
  const [selectedAccountProfileCode, setSelectedAccountProfileCode] = useState(DEFAULT_ACCOUNT_PROFILE);
  const [selectedProfileCode, setSelectedProfileCode] = useState(DEFAULT_PROFILE);
  const [selectedStrategyPreset, setSelectedStrategyPreset] = useState(DEFAULT_STRATEGY_PRESET);
  const [selectedRiskSizingMode, setSelectedRiskSizingMode] = useState(LIVE_RISK_SIZING_MODE);
  const [dtmEnabled, setDtmEnabled] = useState(true);
  const [liveRiskConfig, setLiveRiskConfig] = useState(DEFAULT_LIVE_RISK_CONFIG);
  const [fundedProfiles, setFundedProfiles] = useState([]);
  const [topstepAccounts, setTopstepAccounts] = useState([]);
  const [strategyPresets, setStrategyPresets] = useState([]);
  const [liveStatus, setLiveStatus] = useState(null);
  const [realtimeStatus, setRealtimeStatus] = useState(null);
  const [liveDecisions, setLiveDecisions] = useState([]);
  const [liveDecisionHistory, setLiveDecisionHistory] = useState([]);
  const [liveOrders, setLiveOrders] = useState([]);
  const [liveThinking, setLiveThinking] = useState([]);
  const [, setLiveThinkingStatus] = useState("idle");
  const [observedThinking, setObservedThinking] = useState([]);
  const [logDrawerOpen, setLogDrawerOpen] = useState(false);
  const [liveMetrics, setLiveMetrics] = useState(null);
  const [liveMonitor, setLiveMonitor] = useState(null);
  const [liveMarks, setLiveMarks] = useState(null);
  const [monitorCache, setMonitorCache] = useState({});
  const [accountMetricCache, setAccountMetricCache] = useState({});
  const [stableEquityReviewStatus, setStableEquityReviewStatus] = useState(null);
  const [feedback, setFeedback] = useState("");
  const [busyAction, setBusyAction] = useState("");
  const [chartTransitioning, setChartTransitioning] = useState(false);
  const [chartUiRevision, setChartUiRevision] = useState(0);
  const [lastMonitorRefreshAt, setLastMonitorRefreshAt] = useState("");
  const [backendOnline, setBackendOnline] = useState(true);
  const [allTradeFilters, setAllTradeFilters] = useState(DEFAULT_TRADE_FILTERS);
  const [allTradePage, setAllTradePage] = useState(1);
  const [cachedAllTradeRows, setCachedAllTradeRows] = useState([]);
  const [tradeCacheReady, setTradeCacheReady] = useState(false);
  const [selectedAnalysisTrade, setSelectedAnalysisTrade] = useState(null);
  const chartTransitionTimer = useRef(null);
  const liveWorkspaceRef = useRef(null);
  const latestChartSyncSignature = useRef("");
  const chartUiRevisionAt = useRef(0);
  const chartUiCandleCount = useRef(0);
  const liveMonitorRef = useRef(null);
  const monitorCacheRef = useRef({});
  const liveMarksUiCommitAt = useRef(0);
  const chartInteractionUntil = useRef(0);
  const botStartedRef = useRef(false);
  const observedThinkingSession = useRef(0);
  const requestControllers = useRef(new Map());
  const decisionSidecarSignature = useRef("");
  const latestMonitorRequestKey = useRef("");
  const liveMarksRef = useRef(null);
  const sidebarFeedStateSignature = useRef("");
  const tradeCachePersistSignature = useRef("");
  const tradeCacheAccount = useRef("");
  const {
    futuresSidebarOnline = true,
    futuresSidebarStatus = null,
    refreshFuturesSidebarStatus = null,
    setFuturesSidebarAccountId = null,
  } = useOutletContext() || {};

  const riskProfileOptions = useMemo(() => buildRiskProfileOptions(fundedProfiles), [fundedProfiles]);

  const topstepAccountProfiles = useMemo(() => {
    const accounts = Array.isArray(topstepAccounts) ? topstepAccounts : [];
    const profiles = accounts
      .map((account) => {
        const accountId = String(account?.accountId || "").trim();
        if (!accountId) return null;
        const name = String(account?.name || accountId).trim();
        return {
          code: accountId,
          name,
          label: name,
          accountId,
          active: Boolean(account?.active),
        };
      })
      .filter(Boolean);
    return profiles.length ? profiles : [EMPTY_TOPSTEP_ACCOUNT];
  }, [topstepAccounts]);

  const selectedProfile = useMemo(() => {
    return riskProfileOptions.find((profile) => profile.code === selectedProfileCode) || riskProfileOptions[0] || FALLBACK_PROFILE;
  }, [riskProfileOptions, selectedProfileCode]);

  const selectedAccountProfile = useMemo(() => {
    return topstepAccountProfiles.find((profile) => profile.code === selectedAccountProfileCode) || topstepAccountProfiles[0] || EMPTY_TOPSTEP_ACCOUNT;
  }, [selectedAccountProfileCode, topstepAccountProfiles]);

  const monitorSymbols = DEFAULT_SYMBOLS;
  const liveStrategySymbols = monitorSymbols;
  const presetOptions = useMemo(
    () => mergeStrategyPresets(strategyPresets),
    [strategyPresets]
  );
  const activeStrategyPreset = liveStatus?.running && liveStatus?.strategyPreset ? liveStatus.strategyPreset : selectedStrategyPreset;
  const activeRiskSizingMode = normalizeRiskSizingMode(liveStatus?.running && liveStatus?.riskSizingMode ? liveStatus.riskSizingMode : selectedRiskSizingMode);
  const accountPreset = {
    label: selectedAccountProfile.label || selectedAccountProfile.name || "Topstep Account",
    accountId: selectedAccountProfile.accountId || "",
  };
  const accountScopeId = String(accountPreset.accountId || "").trim();
  const selectedAccountSize = Number(selectedProfile.accountSize || liveRiskConfig.accountSize || FALLBACK_PROFILE.accountSize);
  const liveRiskAccountSize = Number(liveRiskConfig.accountSize || selectedProfile.accountSize || FALLBACK_PROFILE.accountSize || selectedAccountSize);
  const symbolsCsv = monitorSymbols.join(",");
  const backendOffline = backendOnline === false || futuresSidebarOnline === false || futuresSidebarStatus?.backend?.online === false;
  const botStarted = !backendOffline && Boolean(liveStatus?.running);
  const feedRunning = botStarted && !backendOffline && Boolean(realtimeStatus?.running || liveMonitor?.realtimeRunning);
  const monitorFeedOpen = !backendOffline && Boolean(feedRunning || liveMonitor?.historyPolling);
  const monitorDataActive = monitorFeedOpen;
  const graphReadiness = liveMonitor?.graphReadiness || null;
  const graphReady = monitorDataActive && Boolean(graphReadiness?.ready);
  const graphBuilding = monitorDataActive && !graphReady;
  const botControl = liveBotControlState({ botStarted, busyAction });
  const dtmToggleOn = botStarted ? Boolean(liveStatus?.dtmEnabled) : dtmEnabled;
  const displayedDecisions = useMemo(() => (botStarted ? liveDecisions : []), [botStarted, liveDecisions]);
  const displayTradeRows = useMemo(() => mergeLiveTradeDecisions(displayedDecisions), [displayedDecisions]);
  const localDecisionTradeRows = useMemo(() => mergeLiveTradeDecisions(liveDecisionHistory), [liveDecisionHistory]);
  const symbolStates = useMemo(() => (Array.isArray(liveMonitor?.symbolStates) ? liveMonitor.symbolStates : []), [liveMonitor?.symbolStates]);
  const augmentedLiveMetrics = useMemo(
    () => augmentTopstepMetricsWithMarks(liveMetrics, symbolStates),
    [liveMetrics, symbolStates]
  );
  const rawAccountScopedMetrics = useMemo(
    () => scopeBrokerMetricsToAccount(augmentedLiveMetrics, accountScopeId, liveRiskAccountSize),
    [accountScopeId, augmentedLiveMetrics, liveRiskAccountSize]
  );
  const accountScopedMetrics = useMemo(
    () => stabilizeAccountMetrics(rawAccountScopedMetrics, accountMetricCache[accountScopeId], accountScopeId),
    [accountMetricCache, accountScopeId, rawAccountScopedMetrics]
  );
  const effectiveLiveAccountSize = liveAccountSizeFromMetrics(accountScopedMetrics, liveRiskAccountSize);
  const brokerSnapshot = accountScopedMetrics?.broker?.success ? accountScopedMetrics.broker : null;
  const brokerAuthoritative = isAuthoritativeTopstepBrokerSnapshot(brokerSnapshot, accountScopedMetrics);
  const brokerHistoryDataActive = brokerAuthoritative;
  const accountAnalyticsActive = brokerHistoryDataActive;
  const botAccountDataActive = botStarted && brokerAuthoritative;
  const localTradeProvenance = useMemo(
    () => buildLocalTradeProvenance(liveDecisionHistory, liveOrders, accountScopeId),
    [accountScopeId, liveDecisionHistory, liveOrders]
  );
  const brokerOpenTradeRows = useMemo(
    () => buildBrokerOpenTradeRows(brokerSnapshot?.positions, localTradeProvenance),
    [brokerSnapshot, localTradeProvenance]
  );
  const brokerClosedTradeRows = useMemo(
    () => buildBrokerClosedTradeRows(brokerSnapshot?.trades, localTradeProvenance),
    [brokerSnapshot, localTradeProvenance]
  );
  const localClosedTradeCacheRows = useMemo(
    () => buildLocalClosedTradeCacheRows(localDecisionTradeRows, accountScopeId),
    [accountScopeId, localDecisionTradeRows]
  );
  const durableBrokerTradeCacheRows = useMemo(
    () => compactBrokerTradeCacheRows(cachedAllTradeRows, accountScopeId).filter(isBrokerConfirmedTradeCacheRow),
    [accountScopeId, cachedAllTradeRows]
  );
  const allClosedTradeCacheRows = useMemo(
    () => compactBrokerTradeCacheRows([...durableBrokerTradeCacheRows, ...localClosedTradeCacheRows], accountScopeId),
    [accountScopeId, durableBrokerTradeCacheRows, localClosedTradeCacheRows]
  );
  const hydratedTradeCacheRows = useMemo(
    () => brokerHistoryDataActive
      ? mergeBrokerTradeCacheRows(
          brokerClosedTradeRows,
          allClosedTradeCacheRows,
          { accountId: accountScopeId, includeUnmatchedCached: false }
        )
      : [],
    [accountScopeId, allClosedTradeCacheRows, brokerClosedTradeRows, brokerHistoryDataActive]
  );
  const enrichedClosedTradeRows = useMemo(
    () => brokerHistoryDataActive
      ? mergeBrokerTradeCacheRows(hydratedTradeCacheRows, durableBrokerTradeCacheRows, { accountId: accountScopeId })
      : allClosedTradeCacheRows,
    [accountScopeId, allClosedTradeCacheRows, brokerHistoryDataActive, durableBrokerTradeCacheRows, hydratedTradeCacheRows]
  );
  const dtmThinkingEvents = useMemo(
    () => extractDtmThinkingEvents(liveThinking),
    [liveThinking]
  );
  const liveTrades = useMemo(
    () => botAccountDataActive ? attachDtmThinkingToTrades(brokerOpenTradeRows, dtmThinkingEvents) : [],
    [botAccountDataActive, brokerOpenTradeRows, dtmThinkingEvents]
  );
  const allTradeRows = useMemo(
    () => attachDtmThinkingToTrades(enrichedClosedTradeRows, dtmThinkingEvents),
    [dtmThinkingEvents, enrichedClosedTradeRows]
  );
  const filteredAllTradeRows = useMemo(
    () => filterTradeRows(allTradeRows, allTradeFilters),
    [allTradeFilters, allTradeRows]
  );
  const allTradeMetrics = useMemo(
    () => calculateTradeMetrics(allTradeRows, liveRiskAccountSize),
    [allTradeRows, liveRiskAccountSize]
  );
  const filteredAllTradeMetrics = useMemo(
    () => calculateTradeMetrics(filteredAllTradeRows, liveRiskAccountSize),
    [filteredAllTradeRows, liveRiskAccountSize]
  );
  const allTradeTotalPages = Math.max(1, Math.ceil(filteredAllTradeRows.length / LIVE_ALL_TRADES_PAGE_SIZE));
  const boundedAllTradePage = Math.min(allTradePage, allTradeTotalPages);
  const pagedAllTradeRows = useMemo(
    () => filteredAllTradeRows.slice((boundedAllTradePage - 1) * LIVE_ALL_TRADES_PAGE_SIZE, boundedAllTradePage * LIVE_ALL_TRADES_PAGE_SIZE),
    [boundedAllTradePage, filteredAllTradeRows]
  );
  const tradeMetricCount = allTradeRows.length;
  const displayMonitor = useMemo(
    () => resolveDisplayMonitor(liveMonitor, monitorCache, symbolsCsv, selectedTimeframe, selectedChartSymbol),
    [liveMonitor, monitorCache, selectedChartSymbol, selectedTimeframe, symbolsCsv]
  );
  const rawChartCandles = useMemo(
    () => displayMonitor?.marketData?.[selectedChartSymbol] || [],
    [displayMonitor, selectedChartSymbol]
  );
  const chartCandles = useMemo(
    () => {
      const normalized = rawChartCandles
        .map(normalizeCandle)
        .filter((candle) => candle.time && Number(candle.close || 0) > 0);
      return chartCandlesForMonitorTimeline(normalized);
    },
    [rawChartCandles]
  );
  const warmupPending = graphBuilding && chartCandles.length < MIN_OPENING_CHART_BARS;
  const chartDisplayCandles = displayCandlesForChart(chartCandles, MIN_OPENING_CHART_BARS);
  const liveChartDataKey = `${selectedChartSymbol}|${selectedTimeframe}`;
  const selectedSymbolState = useMemo(
    () => symbolStates.find((state) => String(state.symbol || "").toUpperCase() === selectedChartSymbol) || null,
    [selectedChartSymbol, symbolStates]
  );
  const selectedAccountDisabled = Boolean(!accountPreset?.accountId);
  const selectedChartMarkPrice = Number(selectedSymbolState?.lastPrice || latestChartPrice(chartDisplayCandles));
  const feedStaleSeconds = Number(liveMonitor?.feedStaleSeconds ?? -1);
  const marketSession = liveMonitor?.marketSession || liveStatus?.marketSession || estimateFuturesMarketSession();
  const marketIdle = !backendOffline && !monitorDataActive;
  const metrics = useMemo(
    () => accountAnalyticsActive ? accountScopedMetrics : defaultLiveAccountMetrics(effectiveLiveAccountSize, accountScopeId),
    [accountAnalyticsActive, accountScopeId, accountScopedMetrics, effectiveLiveAccountSize]
  );
  const riskHeartbeat = useMemo(
    () => buildLiveRiskHeartbeat({
      liveStatus,
      metrics,
      profile: selectedProfile,
      accountId: accountScopeId,
      marketDate: marketSession?.marketDate || "",
      riskSizingMode: activeRiskSizingMode,
      botStarted,
      backendOffline,
    }),
    [accountScopeId, activeRiskSizingMode, backendOffline, botStarted, liveStatus, marketSession?.marketDate, metrics, selectedProfile]
  );
  const balanceTracksPnl = metricsBalanceTracksPnl(metrics);
  const balanceCardValue = balanceTracksPnl
    ? Number(metrics?.currentPnl ?? metrics?.currentBalance ?? 0)
    : Number(metrics?.currentBalance ?? Number(metrics?.accountSize || 0) + Number(metrics?.currentPnl || 0));
  const brokerTotalFees = finiteNumberOrNull(metrics?.totalFees ?? metrics?.broker?.totalFees);
  const totalFeesCardValue = brokerTotalFees != null && metrics?.brokerMetricsReady !== false
    ? brokerTotalFees
    : allTradeMetrics.totalFees;
  const sidebarStartReady = Boolean(futuresSidebarStatus?.topstepApi?.ready);
  const chartLiveTradeRows = useMemo(
    () => liveTrades,
    [liveTrades]
  );
  const realChartTrades = useMemo(
    () => buildChartTrades(chartLiveTradeRows, selectedChartSymbol, selectedChartMarkPrice),
    [chartLiveTradeRows, selectedChartMarkPrice, selectedChartSymbol]
  );
  const chartTrades = realChartTrades;
  useEffect(() => {
    pushChartCandlesFromMonitor(displayMonitor, selectedChartSymbol, selectedTimeframe);
  }, [displayMonitor, selectedChartSymbol, selectedTimeframe]);

  useEffect(() => {
    chartUiRevisionAt.current = 0;
    chartUiCandleCount.current = 0;
    latestChartSyncSignature.current = "";
    liveMarksUiCommitAt.current = 0;
    setChartUiRevision((revision) => revision + 1);
  }, [liveChartDataKey]);

  useEffect(() => {
    const previousCount = chartUiCandleCount.current;
    const nextCount = chartDisplayCandles.length;
    const now = Date.now();
    const firstCandlesArrived = previousCount === 0 && nextCount > 0;
    chartUiCandleCount.current = nextCount;
    if (!firstCandlesArrived && now - chartUiRevisionAt.current < CHART_UI_REFRESH_MS) return;
    chartUiRevisionAt.current = now;
    setChartUiRevision((revision) => revision + 1);
  }, [chartDisplayCandles]);

  const botTrackers = useMemo(
    () => buildSymbolTrackers({
      symbols: monitorSymbols,
      states: botAccountDataActive ? symbolStates : [],
      decisions: botAccountDataActive ? displayTradeRows : [],
      marketData: botAccountDataActive ? liveMonitor?.marketData || {} : {},
      brokerPositions: botAccountDataActive ? brokerSnapshot?.positions || [] : [],
      brokerClosedTrades: botAccountDataActive ? allTradeRows : [],
      brokerAuthoritative: botAccountDataActive,
      botStarted: botAccountDataActive,
    }),
    [allTradeRows, botAccountDataActive, brokerSnapshot, displayTradeRows, liveMonitor?.marketData, monitorSymbols, symbolStates]
  );
  const botTrackerSignature = useMemo(
    () => botTrackers.map((tracker) => [
      tracker.symbol,
      tracker.lastPrice,
      tracker.changePct,
      tracker.pnl,
      tracker.totalTrades,
      tracker.liveTrades,
      tracker.signal,
      tracker.signalTone,
      tracker.healthTone,
      tracker.healthStatusText,
      tracker.orderFlowLabel,
      tracker.detail,
    ].join(":")).join("|"),
    [botTrackers]
  );
  const symbolTrackerSidebar = useMemo(
    () => (
      <SymbolTrackerSidebar
        trackers={botTrackers}
        selectedSymbol={selectedChartSymbol}
      />
    ),
    [botTrackers, selectedChartSymbol]
  );
  const rawEquityReviewStatus = useMemo(
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
  const equityReviewStatus = stableEquityReviewStatus || rawEquityReviewStatus;
  const backendThinkingEntries = useMemo(
    () => Array.isArray(liveThinking) ? liveThinking.filter((entry) => entry && (entry.summary || entry.detail)).slice(0, 1000) : [],
    [liveThinking]
  );
  const thinkingEntries = backendThinkingEntries.length > 0 ? backendThinkingEntries : observedThinking;
  const logDrawerEntries = useMemo(() => coalesceLiveBotLogEntries(thinkingEntries), [thinkingEntries]);
  const canStartLiveBot = !backendOffline && !selectedAccountDisabled && Boolean(sidebarStartReady && activeStrategyPreset && !liveStatus?.running);
  const launchTone = backendOffline ? "offline" : botStarted ? "live" : sidebarStartReady ? "ready" : "pending";
  const launchLabel = backendOffline ? "Bot Status: OFF" : botStarted ? "Running" : sidebarStartReady ? "Ready" : marketIdle && !marketSession?.entryWindowOpen ? "Closed" : "Setup";
  const noteChartInteraction = useCallback(() => {
    chartInteractionUntil.current = Date.now() + 2500;
  }, []);
  const loadFundedProfilesCallback = useStableCallback(loadFundedProfiles);
  const loadTopstepAccountsCallback = useStableCallback(loadTopstepAccounts);
  const loadStrategyPresetsCallback = useStableCallback(loadStrategyPresets);
  const loadLiveTradeCacheCallback = useStableCallback(loadLiveTradeCache);
  const persistLiveTradeCacheRowsCallback = useStableCallback(persistLiveTradeCacheRows);
  const refreshLiveDataCallback = useStableCallback(refreshLiveData);
  const loadLiveMarksCallback = useStableCallback(loadLiveMarks);
  const refreshLiveReconciliationCallback = useStableCallback(refreshLiveReconciliation);

  useEffect(() => {
    loadFundedProfilesCallback();
    loadTopstepAccountsCallback();
    loadStrategyPresetsCallback();
  }, [loadFundedProfilesCallback, loadStrategyPresetsCallback, loadTopstepAccountsCallback]);

  useEffect(() => {
    if (!riskProfileOptions.some((profile) => profile.code === selectedProfileCode)) {
      setSelectedProfileCode(riskProfileOptions[0]?.code || DEFAULT_PROFILE);
    }
  }, [riskProfileOptions, selectedProfileCode]);

  useEffect(() => {
    setLiveRiskConfig((current) => applyLiveFundedProfile(current, selectedProfile));
  }, [selectedProfile]);

  useEffect(() => {
    if (!topstepAccountProfiles.some((profile) => profile.code === selectedAccountProfileCode)) {
      const activeAccount = topstepAccountProfiles.find((profile) => profile.active);
      const nextAccount = activeAccount || topstepAccountProfiles[0] || EMPTY_TOPSTEP_ACCOUNT;
      setSelectedAccountProfileCode(nextAccount.code || DEFAULT_ACCOUNT_PROFILE);
      const inferredRiskProfileCode = riskProfileCodeForTopstepAccount(nextAccount, riskProfileOptions);
      if (inferredRiskProfileCode) {
        setSelectedProfileCode(inferredRiskProfileCode);
      }
    }
  }, [riskProfileOptions, selectedAccountProfileCode, topstepAccountProfiles]);

  useEffect(() => {
    if (liveStatus?.running) return;
    const inferredRiskProfileCode = riskProfileCodeForTopstepAccount(selectedAccountProfile, riskProfileOptions);
    if (inferredRiskProfileCode && inferredRiskProfileCode !== selectedProfileCode) {
      setSelectedProfileCode(inferredRiskProfileCode);
    }
  }, [liveStatus?.running, riskProfileOptions, selectedAccountProfile, selectedProfileCode]);

  useEffect(() => {
    if (liveStatus?.running) return;
    if (presetOptions.some((preset) => preset.name === selectedStrategyPreset)) return;
    setSelectedStrategyPreset(presetOptions[0]?.name || DEFAULT_STRATEGY_PRESET);
  }, [liveStatus?.running, presetOptions, selectedStrategyPreset]);

  useEffect(() => {
    const runningPreset = String(liveStatus?.strategyPreset || "").trim();
    if (liveStatus?.running && runningPreset && runningPreset !== selectedStrategyPreset) {
      setSelectedStrategyPreset(runningPreset);
    }
  }, [liveStatus?.running, liveStatus?.strategyPreset, selectedStrategyPreset]);

  useEffect(() => {
    const runningRiskSizingMode = normalizeRiskSizingMode(liveStatus?.riskSizingMode || "");
    if (liveStatus?.running && runningRiskSizingMode !== selectedRiskSizingMode) {
      setSelectedRiskSizingMode(runningRiskSizingMode);
    }
  }, [liveStatus?.running, liveStatus?.riskSizingMode, selectedRiskSizingMode]);

  useEffect(() => {
    const runningProfile = String(liveStatus?.fundedProfile || "").trim();
    if (
      liveStatus?.running
      && runningProfile
      && runningProfile !== selectedProfileCode
      && riskProfileOptions.some((profile) => profile.code === runningProfile)
    ) {
      setSelectedProfileCode(runningProfile);
    }
  }, [liveStatus?.running, liveStatus?.fundedProfile, selectedProfileCode, riskProfileOptions]);

  useEffect(() => {
    const runningAccountId = String(
      liveStatus?.practiceAccountId
        || liveStatus?.accountId
        || liveStatus?.liveStrategySnapshot?.practiceAccountId
        || ""
    ).trim();
    if (!liveStatus?.running || !runningAccountId) return;
    const runningAccountProfileCode = accountProfileCodeForAccountId(runningAccountId, topstepAccountProfiles);
    if (runningAccountProfileCode && runningAccountProfileCode !== selectedAccountProfileCode) {
      setSelectedAccountProfileCode(runningAccountProfileCode);
    }
  }, [
    liveStatus?.running,
    liveStatus?.practiceAccountId,
    liveStatus?.accountId,
    liveStatus?.liveStrategySnapshot?.practiceAccountId,
    selectedAccountProfileCode,
    topstepAccountProfiles,
  ]);

  useEffect(() => {
    setAllTradeFilters(DEFAULT_TRADE_FILTERS);
    setAllTradePage(1);
    loadLiveTradeCacheCallback(accountScopeId);
  }, [accountScopeId, loadLiveTradeCacheCallback]);

  useEffect(() => {
    setAllTradePage(1);
  }, [allTradeFilters]);

  useEffect(() => {
    if (!tradeCacheReady || tradeCacheAccount.current !== accountScopeId) return;
    persistLiveTradeCacheRowsCallback(allTradeRows);
  }, [accountScopeId, allTradeRows, persistLiveTradeCacheRowsCallback, tradeCacheReady]);

  useEffect(() => {
    if (!isUsableAccountMetricsSnapshot(rawAccountScopedMetrics, accountScopeId)) return;
    setAccountMetricCache((current) => {
      const previous = current[accountScopeId] || null;
      const next = mergeStableAccountMetrics(rawAccountScopedMetrics, previous, accountScopeId);
      if (accountMetricsSignature(previous) === accountMetricsSignature(next)) {
        return current;
      }
      return { ...current, [accountScopeId]: next };
    });
  }, [accountScopeId, rawAccountScopedMetrics]);

  useEffect(() => {
    setStableEquityReviewStatus((current) => stabilizeEquityReviewStatus(current, rawEquityReviewStatus));
  }, [rawEquityReviewStatus]);

  useEffect(() => {
    liveMonitorRef.current = liveMonitor;
  }, [liveMonitor]);

  useEffect(() => {
    monitorCacheRef.current = monitorCache;
  }, [monitorCache]);

  useEffect(() => {
    if (!monitorSymbols.includes(selectedChartSymbol)) {
      setSelectedChartSymbol(monitorSymbols[0] || DEFAULT_SYMBOLS[0]);
    }
  }, [monitorSymbols, selectedChartSymbol]);

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
    refreshLiveDataCallback();
  }, [accountScopeId, refreshLiveDataCallback, selectedProfileCode, selectedTimeframe, symbolsCsv]);

  useEffect(() => {
    if (typeof setFuturesSidebarAccountId !== "function") {
      return undefined;
    }
    setFuturesSidebarAccountId(accountScopeId);
    return () => setFuturesSidebarAccountId("");
  }, [accountScopeId, setFuturesSidebarAccountId]);

  useEffect(() => {
    if (!botStarted) {
      liveMarksRef.current = null;
      setLiveMarks(null);
      return undefined;
    }
    loadLiveMarksCallback();
    const intervalId = window.setInterval(loadLiveMarksCallback, LIVE_MARKS_REFRESH_MS);
    return () => window.clearInterval(intervalId);
  }, [accountScopeId, botStarted, loadLiveMarksCallback, selectedTimeframe, symbolsCsv]);

  useEffect(() => {
    if (typeof refreshFuturesSidebarStatus !== "function" || !feedRunning || feedStaleSeconds < 0) {
      return;
    }
    const nextSignature = feedStaleSeconds > MARKET_DATA_STALE_SECONDS ? "stopped" : "fresh";
    if (sidebarFeedStateSignature.current === nextSignature) {
      return;
    }
    sidebarFeedStateSignature.current = nextSignature;
    refreshFuturesSidebarStatus();
  }, [feedRunning, feedStaleSeconds, refreshFuturesSidebarStatus]);

  useEffect(() => {
    const intervalId = window.setInterval(refreshLiveReconciliationCallback, LIVE_RECONCILE_REFRESH_MS);
    return () => window.clearInterval(intervalId);
  }, [accountScopeId, refreshLiveReconciliationCallback, selectedTimeframe, symbolsCsv]);

  useEffect(() => {
    const sessionId = Number(liveStatus?.sessionId || 0);
    if (sessionId > 0 && observedThinkingSession.current !== sessionId) {
      observedThinkingSession.current = sessionId;
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
      brokerOpenTradeRows,
      brokerClosedTradeRows,
      accountScopeId,
      liveMetrics,
      symbolStates,
    });
    setObservedThinking(observedEntries.slice(0, 1000));
  }, [accountScopeId, backendOffline, botStarted, brokerClosedTradeRows, brokerOpenTradeRows, feedRunning, liveStatus, realtimeStatus, liveMonitor, liveMarks, liveDecisions, liveMetrics, symbolStates]);

  useEffect(() => {
    const controllers = requestControllers.current;
    return () => {
      if (chartTransitionTimer.current) {
        window.clearTimeout(chartTransitionTimer.current);
      }
      controllers.forEach((controller) => controller.abort());
      controllers.clear();
    };
  }, []);

  async function loadLiveTradeCache(accountId = accountScopeId) {
    const cleanAccountId = String(accountId || "").trim();
    tradeCacheAccount.current = cleanAccountId;
    tradeCachePersistSignature.current = "";
    setTradeCacheReady(false);
    if (!cleanAccountId) {
      setCachedAllTradeRows([]);
      setTradeCacheReady(true);
      return [];
    }

    const localRows = compactBrokerTradeCacheRows(readLocalTradeCacheRows(cleanAccountId), cleanAccountId)
      .filter(isBrokerConfirmedTradeCacheRow);
    setCachedAllTradeRows(localRows);
    tradeCachePersistSignature.current = brokerTradeCacheSignature(localRows);
    setTradeCacheReady(true);

    try {
      const params = new URLSearchParams({ accountId: cleanAccountId });
      const response = await apiFetch(`/api/futures/live/trade-cache?${params.toString()}`);
      const payload = await readApiResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(payload.json?.message || payload.text || "Failed to load live trade cache.");
      }
      const remoteRows = compactBrokerTradeCacheRows(payload.json?.rows, cleanAccountId)
        .filter(isBrokerConfirmedTradeCacheRow);
      const mergedRows = compactBrokerTradeCacheRows(mergeBrokerTradeCacheRows(remoteRows, localRows, { accountId: cleanAccountId }), cleanAccountId);
      if (tradeCacheAccount.current !== cleanAccountId) return mergedRows;
      setCachedAllTradeRows(mergedRows);
      tradeCachePersistSignature.current = brokerTradeCacheSignature(mergedRows);
      writeLocalTradeCacheRows(cleanAccountId, mergedRows);
      return mergedRows;
    } catch (error) {
      if (isApiNetworkError(error)) noteBackendError("Error loading live trade cache:", error);
      return localRows;
    }
  }

  async function persistLiveTradeCacheRows(rows, { force = false } = {}) {
    const cleanAccountId = String(accountScopeId || "").trim();
    if (!cleanAccountId) return [];
    const nextRows = compactBrokerTradeCacheRows(rows, cleanAccountId)
      .filter(isBrokerConfirmedTradeCacheRow);
    const nextSignature = brokerTradeCacheSignature(nextRows);
    if (!force && nextSignature === tradeCachePersistSignature.current) {
      return nextRows;
    }
    tradeCachePersistSignature.current = nextSignature;
    writeLocalTradeCacheRows(cleanAccountId, nextRows);
    setCachedAllTradeRows((current) => {
      const mergedRows = compactBrokerTradeCacheRows(mergeBrokerTradeCacheRows(nextRows, current, { accountId: cleanAccountId }), cleanAccountId);
      return brokerTradeCacheSignature(current) === brokerTradeCacheSignature(mergedRows) ? current : mergedRows;
    });
    try {
      const params = new URLSearchParams({ accountId: cleanAccountId });
      await apiFetch(`/api/futures/live/trade-cache?${params.toString()}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          success: true,
          version: LIVE_TRADE_CACHE_VERSION,
          accountId: cleanAccountId,
          updatedAt: new Date().toISOString(),
          rows: nextRows,
        }),
      });
    } catch (error) {
      if (isApiNetworkError(error)) noteBackendError("Error saving live trade cache:", error);
    }
    return nextRows;
  }

  function refreshLiveData() {
    loadLiveStatus({ forceSidecars: true });
    loadRealtimeStatus();
    loadLiveOrders();
    loadLiveMetrics();
    loadLiveMonitor();
    loadLiveMarks();
  }

  function refreshLiveReconciliation() {
    if (Date.now() < chartInteractionUntil.current) {
      return;
    }
    loadLiveStatus();
    loadRealtimeStatus();
    loadLiveOrders();
    loadLiveMetrics();
    loadLiveMonitor();
  }

  function pushChartCandlesFromMonitor(monitor, symbol, timeframe) {
    const selectedSymbol = String(symbol || "").toUpperCase();
    const normalizedTimeframe = normalizeClientTimeframe(timeframe);
    const sourceCandles = monitor?.marketData?.[selectedSymbol] || [];
    const normalizedCandles = (Array.isArray(sourceCandles) ? sourceCandles : [])
      .map(normalizeCandle)
      .filter((candle) => candle.time && Number(candle.close || 0) > 0);
    const displayCandles = displayCandlesForChart(normalizedCandles, MIN_OPENING_CHART_BARS);
    const latest = displayCandles[displayCandles.length - 1] || null;
    const chartDataKey = `${selectedSymbol}|${normalizedTimeframe}`;
    const signature = [
      chartDataKey,
      displayCandles.length,
      latest?.time,
      latest?.open,
      latest?.high,
      latest?.low,
      latest?.close,
      latest?.volume,
      candleHistorySignature(displayCandles),
    ].join("|");
    if (signature === latestChartSyncSignature.current) return;
    latestChartSyncSignature.current = signature;
    liveWorkspaceRef.current?.syncCandles(displayCandles, {
      chartDataKey,
      timeframe: normalizedTimeframe,
    });
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
    loadLiveOrders();
  }

  function loadFundedProfiles() {
    requestJson("fundedProfiles", "/api/futures/funded-rule-profiles", (data) => {
        const topstepProfiles = Array.isArray(data) ? data.filter((profile) => String(profile?.provider || "").toUpperCase() === BROKER_SOURCE_TOPSTEPX) : [];
        setFundedProfiles(topstepProfiles.length ? topstepProfiles : RISK_PROFILE_FALLBACKS);
      }, (error) => {
        noteBackendError("Error loading funded profiles:", error);
        setFundedProfiles(RISK_PROFILE_FALLBACKS);
      });
  }

  function loadTopstepAccounts() {
    requestJson("topstepAccounts", "/api/futures/topstepx/accounts", (data) => {
        setTopstepAccounts(Array.isArray(data) ? data : []);
      }, (error) => {
        noteBackendError("Error loading Topstep accounts:", error);
        setTopstepAccounts([]);
      });
  }

  function loadStrategyPresets() {
    requestJson("strategyPresets", "/api/futures/strategy-presets", (data) => {
        const presets = mergeStrategyPresets(data);
        setStrategyPresets(presets);
        if (presets.length && !presets.some((preset) => preset.name === selectedStrategyPreset)) {
          setSelectedStrategyPreset(presets[0].name);
        }
      }, (error) => {
        noteBackendError("Error loading strategy presets:", error);
        setStrategyPresets(CANONICAL_STRATEGY_PRESETS);
      });
  }

  function loadLiveStatus(options = {}) {
    requestJson("liveStatus", "/api/futures/live/status", (data) => {
        setLiveStatus(data || null);
        refreshDecisionSidecars(data || null, Boolean(options.forceSidecars));
      }, (error) => {
        noteBackendError("Error loading live status:", error);
      });
  }

  function loadRealtimeStatus() {
    requestJson("realtimeStatus", "/api/futures/live/realtime/status", (data) => setRealtimeStatus(data || null), (error) => {
        noteBackendError("Error loading realtime status:", error);
      });
  }

  function loadLiveDecisions(status = liveStatus) {
    const sessionId = Number(status?.running ? status?.sessionId || 0 : 0);
    if (!sessionId) {
      return;
    }
    const params = new URLSearchParams({ sessionId: String(sessionId), limit: "160" });
    if (accountScopeId) params.set("accountId", accountScopeId);
    requestJson("liveDecisions", `/api/futures/live/decisions?${params.toString()}`, (data) => setLiveDecisions(Array.isArray(data) ? data : []), (error) => {
        noteBackendError("Error loading live decisions:", error);
      });
  }

  function loadLiveDecisionHistory() {
    const params = new URLSearchParams({ limit: String(LIVE_HISTORY_REQUEST_LIMIT) });
    if (accountScopeId) params.set("accountId", accountScopeId);
    requestJson("liveDecisionHistory", `/api/futures/live/decisions?${params.toString()}`, (data) => setLiveDecisionHistory(Array.isArray(data) ? data : []), (error) => {
        noteBackendError("Error loading live decision history:", error);
      });
  }

  function loadLiveOrders() {
    const params = new URLSearchParams({ limit: String(LIVE_HISTORY_REQUEST_LIMIT) });
    if (accountScopeId) params.set("accountId", accountScopeId);
    requestJson("liveOrders", `/api/futures/live/orders?${params.toString()}`, (data) => setLiveOrders(Array.isArray(data) ? data : []), (error) => {
        noteBackendError("Error loading live order ledger:", error);
      });
  }

  function loadLiveThinking() {
    const key = "liveThinking";
    if (requestControllers.current.has(key)) {
      return;
    }
    const controller = new AbortController();
    requestControllers.current.set(key, controller);
    apiFetch("/api/futures/live/thinking?limit=1000", { signal: controller.signal })
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
      })
      .finally(() => {
        if (requestControllers.current.get(key) === controller) {
          requestControllers.current.delete(key);
        }
      });
  }

  function loadLiveMetrics() {
    const params = new URLSearchParams();
    if (accountScopeId) params.set("accountId", accountScopeId);
    const path = `/api/futures/live/metrics${params.toString() ? `?${params.toString()}` : ""}`;
    requestJson("liveMetrics", path, (data) => setLiveMetrics(data || null), (error) => {
        noteBackendError("Error loading live metrics:", error);
      });
  }

  function loadLiveMarks() {
    if (!botStartedRef.current) {
      liveMarksRef.current = null;
      setLiveMarks(null);
      return;
    }
    const requestSymbols = symbolsCsv || DEFAULT_SYMBOLS.join(",");
    const requestTimeframe = selectedTimeframe;
    const params = new URLSearchParams({
      symbols: requestSymbols,
      timeframe: requestTimeframe,
    });
    if (accountScopeId) params.set("accountId", accountScopeId);
    requestJson(liveMarksRequestKey(requestSymbols, requestTimeframe), `/api/futures/live/marks?${params.toString()}`, (data) => {
        if (!data?.success) return;
        liveMarksRef.current = data;
        const normalizedTimeframe = normalizeClientTimeframe(data.timeframe || requestTimeframe);
        const cacheKey = liveMonitorCacheKey(requestSymbols, normalizedTimeframe);
        const currentMonitor = liveMonitorRef.current;
        const currentCache = monitorCacheRef.current || {};
        const mergedMonitor = liveMonitorMatchesCacheKey(currentMonitor, cacheKey, normalizedTimeframe)
          ? mergeMonitorWithMarks(currentMonitor, data, normalizedTimeframe)
          : null;
        const mergedCacheMonitor = mergeMonitorWithMarks(currentCache?.[cacheKey], data, normalizedTimeframe);
        pushChartCandlesFromMonitor(mergedMonitor || mergedCacheMonitor, selectedChartSymbol, normalizedTimeframe);
        const now = Date.now();
        const shouldCommitUi = now >= chartInteractionUntil.current && now - liveMarksUiCommitAt.current >= LIVE_MARKS_UI_REFRESH_MS;
        if (!shouldCommitUi) return;
        liveMarksUiCommitAt.current = now;
        if (mergedMonitor && mergedMonitor !== currentMonitor) liveMonitorRef.current = mergedMonitor;
        if (mergedCacheMonitor) {
          const nextCache = { ...currentCache, [cacheKey]: mergedCacheMonitor };
          monitorCacheRef.current = nextCache;
          startTransition(() => {
            setLiveMarks(data);
            if (mergedMonitor && mergedMonitor !== currentMonitor) setLiveMonitor(mergedMonitor);
            setMonitorCache(nextCache);
          });
        } else {
          startTransition(() => {
            setLiveMarks(data);
            if (mergedMonitor && mergedMonitor !== currentMonitor) setLiveMonitor(mergedMonitor);
          });
        }
      }, (error) => {
        noteBackendError("Error loading live marks:", error);
        setLiveMarks((current) => current || {
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
    const requestSymbols = symbolsCsv || DEFAULT_SYMBOLS.join(",");
    const requestTimeframe = selectedTimeframe;
    const requestKey = liveMonitorRequestKey(requestSymbols, requestTimeframe);
    latestMonitorRequestKey.current = requestKey;
    const params = new URLSearchParams({
      symbols: requestSymbols,
      limit: String(monitorLimitForTimeframe(requestTimeframe)),
      timeframe: requestTimeframe,
    });
    requestJson(requestKey, `/api/futures/live/monitor?${params.toString()}`, (data) => {
        if (data) {
          const responseTimeframe = normalizeClientTimeframe(data.timeframe || requestTimeframe);
          const responseCacheKey = liveMonitorCacheKey(requestSymbols, responseTimeframe);
          const monitorWithTimeframe = { ...data, timeframe: responseTimeframe, monitorCacheKey: responseCacheKey };
          const monitorWithMarks = mergeMonitorWithMarks(monitorWithTimeframe, liveMarksRef.current, responseTimeframe) || monitorWithTimeframe;
          const currentCache = monitorCacheRef.current || {};
          const nextCache = {
            ...currentCache,
            [responseCacheKey]: mergeMonitorWithCachedMarketData(monitorWithMarks, currentCache?.[responseCacheKey]),
          };
          monitorCacheRef.current = nextCache;
          startTransition(() => setMonitorCache(nextCache));
          if (latestMonitorRequestKey.current === requestKey) {
            const nextMonitor = mergeMonitorWithCachedMarketData(monitorWithMarks, liveMonitorRef.current);
            liveMonitorRef.current = nextMonitor;
            startTransition(() => setLiveMonitor(nextMonitor));
          }
        } else {
          startTransition(() => setLiveMonitor((current) => current));
        }
        if (latestMonitorRequestKey.current === requestKey) {
          startTransition(() => setLastMonitorRefreshAt(new Date().toISOString()));
        }
      }, (error) => {
        noteBackendError("Error loading live monitor:", error);
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

  async function changeTopstepAccountProfile(code) {
    const nextAccount = topstepAccountProfiles.find((profile) => profile.code === code);
    setSelectedAccountProfileCode(code);
    const inferredRiskProfileCode = riskProfileCodeForTopstepAccount(nextAccount, riskProfileOptions);
    if (inferredRiskProfileCode) {
      setSelectedProfileCode(inferredRiskProfileCode);
    }
    if (!nextAccount?.accountId) return;
    await runAction("account", async () => {
      const response = await apiFetch(`/api/futures/topstepx/accounts/${encodeURIComponent(nextAccount.accountId)}/activate`, { method: "POST" });
      const payload = await readApiResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(payload.json?.message || payload.text || "Failed to switch Topstep account.");
      }
      if (Array.isArray(payload.json?.accounts)) {
        setTopstepAccounts(payload.json.accounts);
      } else {
        loadTopstepAccounts();
      }
      setFeedback(payload.json?.message || `${nextAccount.name} is active for ProjectX.`);
      refreshFuturesSidebarStatus?.();
      refreshLiveData();
    });
  }

  function transitionChart(update) {
    setChartTransitioning(true);
    if (chartTransitionTimer.current) {
      window.clearTimeout(chartTransitionTimer.current);
    }
    update();
    chartTransitionTimer.current = window.setTimeout(() => setChartTransitioning(false), 120);
  }

  async function startLiveBot() {
    await runAction("start", async () => {
      const freshSidebarStatus = typeof refreshFuturesSidebarStatus === "function"
        ? await refreshFuturesSidebarStatus()
        : futuresSidebarStatus;
      validateSidebarStartStatus(freshSidebarStatus);
      const openingSymbol = monitorSymbols[0] || "MNQ";
      setSelectedChartSymbol(openingSymbol);
      setSelectedTimeframe("1m");
      await loadLiveTradeCache(accountScopeId);
      const riskProfileForStart = selectedProfile || FALLBACK_PROFILE;
      const params = new URLSearchParams({
        symbol: openingSymbol,
        executionMode: "TOPSTEPX",
        fundedProfile: riskProfileForStart.code,
        accountId: accountScopeId,
        accountSize: String(riskProfileForStart.accountSize || liveRiskConfig.accountSize || 50000),
        maxTrailingDrawdown: String(riskProfileForStart.maxTrailingDrawdown || liveRiskConfig.maxTrailingDrawdown || 2000),
        dailyLossLimit: String(riskProfileForStart.dailyLossLimit || liveRiskConfig.dailyLossLimit || 1000),
        maxRiskPerTrade: String(riskProfileForStart.maxRiskPerTrade || liveRiskConfig.maxRiskPerTrade || 400),
        maxContracts: String(contractLimitForProfile(riskProfileForStart, liveRiskConfig.referenceSymbol)),
        commissionPerContract: String(liveRiskConfig.commissionPerContract || "1.24"),
        slippageTicks: String(liveRiskConfig.slippageTicks || "1"),
        profitTarget: String(riskProfileForStart.profitTarget || liveRiskConfig.profitTarget || 0),
        maxOpenPositions: String(riskProfileForStart.maxOpenPositions || liveRiskConfig.maxOpenPositions || 1),
        maxAggregateContracts: String(riskProfileForStart.maxAggregateContracts || liveRiskConfig.maxAggregateContracts || 50),
        maxAggregateMiniUnits: String(riskProfileForStart.maxAggregateMiniUnits || liveRiskConfig.maxAggregateMiniUnits || 5),
        symbols: monitorSymbols.join(","),
        strategyPreset: selectedStrategyPreset || DEFAULT_STRATEGY_PRESET,
        riskSizingMode: activeRiskSizingMode,
        dtmEnabled: String(dtmEnabled),
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
      await persistLiveTradeCacheRows(allTradeRows, { force: true });
      const response = await apiFetch("/api/futures/live/stop", { method: "POST" });
      const payload = await readApiResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(payload.json?.message || payload.text || "Failed to stop live bot.");
      }
      setLiveStatus(payload.json?.status || null);
      botStartedRef.current = false;
      liveMarksRef.current = null;
      setLiveMarks(null);
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
      persistLiveTradeCacheRows(allTradeRows, { force: true });
    });
  }

  async function clearLiveLogs() {
    await runAction("clearLogs", async () => {
      const response = await apiFetch("/api/futures/live/thinking/clear", { method: "POST" });
      const payload = await readApiResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(payload.json?.message || payload.text || "Failed to clear live logs.");
      }
      setLiveThinking([]);
      setObservedThinking([]);
      setFeedback(payload.json?.message || "Live logs cleared.");
      loadLiveThinking();
    });
  }

  function validateSidebarStartStatus(status) {
    if (!status?.backend?.online) {
      throw new Error("Backend Status is OFF in the sidebar. Start Live Bot will unlock when the backend status is back on.");
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
      <div className="futures-live-header">
        <div className="futures-live-header-copy">
          <h2 className="app-title m-0">Live Futures Bot</h2>
          <span>{activeStrategyPreset || DEFAULT_STRATEGY_PRESET}</span>
        </div>
        <div className="futures-live-header-actions">
          {!backendOffline && (
            <span className={liveStatus?.running ? "app-badge app-positive-badge" : "app-badge app-neutral-badge"}>
              {liveStatus?.running ? "Running" : "Stopped"}
            </span>
          )}
          <button type="button" className="app-btn app-btn-small px-3 futures-live-header-logs" onClick={() => setLogDrawerOpen(true)}>
            Logs {logDrawerEntries.length ? `(${logDrawerEntries.length})` : ""}
          </button>
        </div>
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
          </div>

          <div className="futures-live-action-row futures-launch-actions">
            <label className={`futures-feature-toggle ${dtmToggleOn ? "is-on" : "is-off"}`}>
              <input
                type="checkbox"
                checked={dtmToggleOn}
                onChange={(event) => setDtmEnabled(event.target.checked)}
                disabled={Boolean(liveStatus?.running) || busyAction === "start" || busyAction === "stop"}
              />
              <span aria-hidden="true" />
              <b>DTM</b>
            </label>
            <button
              type="button"
              className={botControl.active ? "app-btn app-btn-danger px-3" : "app-btn app-btn-primary px-3"}
              onClick={botControl.active ? stopLiveBot : startLiveBot}
              disabled={busyAction === "start" || busyAction === "stop" || (!botControl.active && !canStartLiveBot)}
            >
              {botControl.label}
            </button>
          </div>
        </div>

        <div className="futures-launch-config">
          <Field label="Topstep Account" className="futures-launch-account-field">
            <select
              value={selectedAccountProfileCode}
              onChange={(event) => changeTopstepAccountProfile(event.target.value)}
              className="form-select app-input"
              disabled={Boolean(liveStatus?.running)}
            >
              {topstepAccountProfiles.map((profile) => {
                return (
                  <option key={profile.code} value={profile.code}>
                    {profile.label || profile.name}
                  </option>
                );
              })}
            </select>
          </Field>

          <Field label="Strategy Config" className="futures-launch-account-field">
            <select
              value={activeStrategyPreset || DEFAULT_STRATEGY_PRESET}
              onChange={(event) => setSelectedStrategyPreset(event.target.value)}
              className="form-select app-input"
              disabled={Boolean(liveStatus?.running)}
            >
              {presetOptions.map((preset) => (
                <option key={preset.name} value={preset.name}>
                  {preset.label || preset.name}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Risk Config" className="futures-launch-account-field">
            <select
              value={selectedProfileCode}
              onChange={(event) => setSelectedProfileCode(event.target.value)}
              className="form-select app-input"
              disabled={Boolean(liveStatus?.running)}
            >
              {riskProfileOptions.map((profile) => (
                <option key={profile.code} value={profile.code}>
                  {profile.name}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Risk Sizing" className="futures-launch-account-field">
            <select
              value={activeRiskSizingMode}
              onChange={(event) => setSelectedRiskSizingMode(normalizeRiskSizingMode(event.target.value))}
              className="form-select app-input"
              disabled={Boolean(liveStatus?.running)}
            >
              {RISK_SIZING_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </Field>

          <div className="futures-launch-chip">
            <span>Account ID</span>
            <strong>{accountPreset.accountId || "Not connected"}</strong>
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

        {feedback && <div className="app-muted app-kicker">{feedback}</div>}
      </section>

      <section className="app-live-grid futures-live-summary-grid">
        <MetricCard label="Current Balance" value={formatAccountCurrency(balanceCardValue)} accent={balanceTracksPnl ? balanceCardValue : undefined} />
        <MetricCard label="Total Fees" value={formatFees(totalFeesCardValue)} />
        <MetricCard label="Return %" value={formatPct(metrics?.returnPct)} accent={Number(metrics?.returnPct || 0)} />
        <MetricCard label="Trades" value={String(tradeMetricCount)} />
        <MetricCard label="Win Rate" value={formatRate(allTradeMetrics.winRate)} />
        <MetricCard label="Avg Win" value={formatCurrency(allTradeMetrics.avgWin)} accent={allTradeMetrics.avgWin} />
        <MetricCard label="Avg Loss" value={formatCurrency(allTradeMetrics.avgLoss)} accent={allTradeMetrics.avgLoss} />
        <MetricCard label="Avg Day" value={formatCurrency(allTradeMetrics.avgDay)} accent={allTradeMetrics.avgDay} />
        <MetricCard label="Avg Week" value={formatCurrency(allTradeMetrics.avgWeek)} accent={allTradeMetrics.avgWeek} />
        <MetricCard label="Avg Month" value={formatCurrency(allTradeMetrics.avgMonth)} accent={allTradeMetrics.avgMonth} />
      </section>

      <LiveRiskHeartbeatCard heartbeat={riskHeartbeat} />

      <section className="futures-monitor-workspace-panel">
        <LiveTradingWorkspace
          ref={liveWorkspaceRef}
          botStarted={botStarted}
          candles={chartDisplayCandles}
          isTransitioning={chartTransitioning}
          symbol={selectedChartSymbol}
          symbols={monitorSymbols}
          activeTradeSymbols={botTrackers}
          timeframe={selectedTimeframe}
          onSymbolChange={selectChartSymbol}
          onTimeframeChange={changeTimeframe}
          trades={chartTrades}
          lastRefreshAt={lastMonitorRefreshAt}
          serverTime={liveMonitor?.serverTime}
          lastRealtimeEventAt={liveMonitor?.lastRealtimeEventAt}
          feedStaleSeconds={feedStaleSeconds}
          dataSource={selectedSymbolState?.dataSource || liveMonitor?.dataSource || ""}
          capturedBars={Number(selectedSymbolState?.capturedBars || 0)}
          realtimeRunning={Boolean(realtimeStatus?.running || liveMonitor?.realtimeRunning)}
          historyPolling={Boolean(liveMonitor?.historyPolling)}
          warmupPending={warmupPending}
          graphReadiness={graphReadiness}
          backendOffline={backendOffline}
          marketIdle={marketIdle}
          onChartInteraction={noteChartInteraction}
          sidebarBeforeTrades={symbolTrackerSidebar}
          sidebarSignature={botTrackerSignature}
          uiRevision={chartUiRevision}
        />
      </section>

      <section className="app-panel">
        <div className="d-flex align-items-start justify-content-between gap-2 flex-wrap">
          <div>
            <div className="fw-bold app-kicker">All Trades</div>
          </div>
          <span className="app-badge app-neutral-badge">{formatInteger(filteredAllTradeRows.length)} / {formatInteger(allTradeRows.length)} rows</span>
        </div>
        <TradeFilters
          trades={allTradeRows}
          filteredTrades={filteredAllTradeRows}
          filters={allTradeFilters}
          onChange={setAllTradeFilters}
          summaryText={pagedTradeSummary({
            page: boundedAllTradePage,
            totalPages: allTradeTotalPages,
            pageCount: pagedAllTradeRows.length,
            filteredCount: filteredAllTradeRows.length,
            totalCount: allTradeRows.length,
          })}
        />
        <TradeMetricsGrid
          metrics={filteredAllTradeMetrics}
          pnlLabel="Filtered P/L"
          tradeLabel="Filtered Trades"
          returnLabel="Filtered Return"
        />
        <TradePagination
          page={boundedAllTradePage}
          totalPages={allTradeTotalPages}
          pageSize={LIVE_ALL_TRADES_PAGE_SIZE}
          onPrev={() => setAllTradePage((current) => Math.max(1, current - 1))}
          onNext={() => setAllTradePage((current) => Math.min(allTradeTotalPages, current + 1))}
        />
        <TradesTable trades={pagedAllTradeRows} mode="all" onOpenTrade={(trade) => setSelectedAnalysisTrade(toTradeAnalysisRow(trade))} />
      </section>

      <TradeAnalysisModal
        trade={selectedAnalysisTrade}
        source="live"
        onClose={() => setSelectedAnalysisTrade(null)}
      />

      <FuturesLiveLogDrawer
        open={logDrawerOpen}
        onOpen={() => setLogDrawerOpen(true)}
        onClose={() => setLogDrawerOpen(false)}
        entries={logDrawerEntries}
        status={equityReviewStatus}
        onClear={clearLiveLogs}
        clearBusy={busyAction === "clearLogs"}
      />
    </div>
  );
}

function LiveRiskHeartbeatCard({ heartbeat }) {
  const safe = heartbeat || buildLiveRiskHeartbeat({});
  return (
    <div className={`futures-risk-heartbeat-panel ${safe.tone}`}>
      <div className="futures-risk-heartbeat-header">
        <strong>Live Risk Heartbeat</strong>
      </div>
      <div className="futures-risk-heartbeat-status">{safe.status}</div>
      <div className="futures-risk-heartbeat-grid">
        <RiskHeartbeatMetric label={safe.balanceMode === "PNL" ? "Funded PnL" : "Risk Equity"} value={formatAccountCurrency(safe.riskCurrentBalance)} accent={safe.dailyPnl} />
        <RiskHeartbeatMetric label="Daily PnL" value={formatCurrency(safe.dailyPnl)} accent={safe.dailyPnl} />
        <RiskHeartbeatMetric label="DLL Room" value={formatAccountCurrency(safe.dailyRiskRoom)} accent={safe.dailyRiskRoom > safe.configuredMaxRisk ? 1 : safe.dailyRiskRoom <= 0 ? -1 : 0} />
        <RiskHeartbeatMetric label="MLL Room" value={formatAccountCurrency(safe.trailingCushion)} accent={safe.trailingCushion > 0 ? 1 : -1} />
        <RiskHeartbeatMetric label="Configured Cap" value={formatAccountCurrency(safe.configuredMaxRisk)} />
        <RiskHeartbeatMetric label="Max Risk Now" value={formatAccountCurrency(safe.effectiveMaxRisk)} accent={safe.effectiveMaxRisk <= 0 ? -1 : safe.effectiveMaxRisk < safe.configuredMaxRisk ? -0.5 : 1} />
      </div>
      <div className="futures-risk-heartbeat-meta">
        <span>Mode <b>{safe.riskSizingLabel}</b></span>
        <span>Limited by <b>{safe.limitingBudgetLabel}</b></span>
        <span>Account <b>{safe.accountId || "Not selected"}</b></span>
        <span>Source <b>{safe.source}</b></span>
      </div>
    </div>
  );
}

function RiskHeartbeatMetric({ label, value, accent = 0 }) {
  const tone = accent > 0 ? "pos" : accent < 0 ? "neg" : "";
  return (
    <div className="futures-risk-heartbeat-metric">
      <span>{label}</span>
      <strong className={tone}>{value}</strong>
    </div>
  );
}

function SymbolTrackerSidebar({ trackers, selectedSymbol }) {
  const normalizedSelectedSymbol = String(selectedSymbol || "").toUpperCase();
  const tracker = (Array.isArray(trackers) ? trackers : []).find(
    (candidate) => String(candidate?.symbol || "").toUpperCase() === normalizedSelectedSymbol
  ) || (Array.isArray(trackers) ? trackers[0] : null);

  if (!tracker) return null;

  return (
    <div className="futures-symbol-tracker-sidebar">
      <div className="futures-symbol-tracker-heading">Symbol Tracker</div>
      <div className="futures-symbol-tracker-card active focused">
        <div className="futures-symbol-tracker-topline">
          <span>{tracker.symbol}</span>
          <strong>{formatPrice(tracker.lastPrice)}</strong>
        </div>
        <div className="futures-symbol-tracker-pnl">
          <em className={tracker.pnl > 0 ? "app-pnl-pos" : tracker.pnl < 0 ? "app-pnl-neg" : "app-muted"}>{formatCurrency(tracker.pnl)}</em>
          <small className={tracker.changePct > 0 ? "app-pnl-pos" : tracker.changePct < 0 ? "app-pnl-neg" : "app-muted"}>{formatPct(tracker.changePct)}</small>
        </div>
        <div className="futures-symbol-tracker-meta">
          <span><b>{tracker.totalTrades}</b> trades</span>
          <span><b>{tracker.liveTrades}</b> live</span>
          <span className={`futures-bot-signal ${tracker.signalTone}`}>{tracker.signal}</span>
        </div>
        <div className="futures-symbol-tracker-flow">{tracker.orderFlowLabel}</div>
        <div className="futures-symbol-tracker-health">
          <span className={`futures-health-pill ${tracker.healthTone}`}>Health: {tracker.healthStatusText}</span>
        </div>
        {tracker.detail && <p className="futures-symbol-tracker-detail">{tracker.detail}</p>}
      </div>
    </div>
  );
}

function FuturesLiveLogDrawer({ open, onOpen, onClose, entries, status, onClear, clearBusy }) {
  const rows = Array.isArray(entries) ? entries.slice(0, 1000) : [];
  const statusText = `Status : ${compactLogDrawerStatus(status?.healthLabel || "Waiting")}`;

  useEffect(() => {
    if (typeof document === "undefined" || !open) return undefined;
    const body = document.body;
    const previousOverflow = body.style.overflow;
    const previousOverscrollBehavior = body.style.overscrollBehavior;
    body.style.overflow = "hidden";
    body.style.overscrollBehavior = "contain";
    return () => {
      body.style.overflow = previousOverflow;
      body.style.overscrollBehavior = previousOverscrollBehavior;
    };
  }, [open]);

  const drawer = (
    <>
      <button
        type="button"
        className={open ? "futures-log-tab open" : "futures-log-tab"}
        onClick={open ? onClose : onOpen}
        aria-label={open ? "Close live logs" : "Open live logs"}
      >
        <span>Logs</span>
        <b>{rows.length}</b>
      </button>
      {open && <button type="button" className="futures-log-backdrop" aria-label="Close live logs" onClick={onClose} />}
      <aside className={open ? "futures-log-drawer open" : "futures-log-drawer"} aria-hidden={!open} aria-modal={open ? "true" : undefined} role="dialog">
        <div className="futures-log-drawer-head">
          <div className="futures-log-drawer-title">Live Logs</div>
          <div className="futures-log-drawer-controls">
            <button
              type="button"
              className="app-btn app-btn-small app-btn-danger"
              onClick={onClear}
              disabled={clearBusy || rows.length === 0}
            >
              {clearBusy ? "Clearing..." : "Clear"}
            </button>
            <span className={`futures-review-status ${status?.healthTone || "idle"}`}>{statusText}</span>
            <button type="button" className="app-btn app-btn-small" onClick={onClose}>Close</button>
          </div>
        </div>
        <div className="futures-log-drawer-list" role="log" aria-label="Live bot log drawer">
          {rows.length ? (
            rows.map((entry, index) => (
              <div className={thinkingLogRowClass(entry)} key={entry.id || `${entry.createdAt}-${index}`}>
                <div className="futures-log-drawer-time">
                  <time>{formatEstTime(entry.createdAt || entry.barTime)}</time>
                  {entry.coalescedCount > 1 && <span>{entry.coalescedCount}x</span>}
                </div>
                <div className="futures-event-body">
                  <div className="futures-event-topline">
                    <span className={`futures-event-tag ${eventToneClass(entry)}`}>{eventLogCode(entry)}</span>
                    {eventContextText(entry) && <span className="futures-event-context">{eventContextText(entry)}</span>}
                  </div>
                  <div className="futures-event-title">{eventTitle(entry)}</div>
                  {compactEventSubtext(entry) && <div className="futures-event-subtext">{compactEventSubtext(entry)}</div>}
                  {eventDetailEntries(entry).length > 0 && (
                    <div className="futures-event-details">
                      {eventDetailEntries(entry).slice(0, 3).map(([key, value]) => (
                        <span className="futures-event-detail" key={`${entry.id || index}-${key}`}>
                          <b>{detailLabel(key)}</b>
                          <em>{formatEventDetailValue(value)}</em>
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            ))
          ) : (
            <div className="app-empty">Waiting for live bot events.</div>
          )}
        </div>
      </aside>
    </>
  );
  return typeof document === "undefined" ? drawer : createPortal(drawer, document.body);
}

function compactLogDrawerStatus(value) {
  const label = String(value || "Waiting").trim();
  return label.replace(/^Health[:\s-]*/i, "") || "Waiting";
}

function FuturesThinkingLog({ entries, status }) {
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
        </div>
      </div>
      <div className="futures-thinking-log" role="log" aria-label="Live bot decision log">
        {visibleRows.length > 0 && (
          <div className="futures-thinking-log-head">
            <span>Date / Time</span>
            <span>Event</span>
          </div>
        )}
        {visibleRows.length ? (
          visibleRows.map((entry, index) => (
            <div className={thinkingLogRowClass(entry)} key={entry.id || `${entry.createdAt}-${index}`}>
              <time>{formatEstTime(entry.createdAt || entry.barTime)}</time>
              <div className="futures-event-body">
                <div className="futures-event-topline">
                  <span className={`futures-event-tag ${eventToneClass(entry)}`}>{eventLogCode(entry)}</span>
                  {eventContextText(entry) && <span className="futures-event-context">{eventContextText(entry)}</span>}
                </div>
                <div className="futures-event-title">{eventTitle(entry)}</div>
                {eventSubtext(entry) && <div className="futures-event-subtext">{eventSubtext(entry)}</div>}
                {eventDetailEntries(entry).length > 0 && (
                  <div className="futures-event-details">
                    {eventDetailEntries(entry).map(([key, value]) => (
                      <span className="futures-event-detail" key={`${entry.id || index}-${key}`}>
                        <b>{detailLabel(key)}</b>
                        <em>{formatEventDetailValue(value)}</em>
                      </span>
                    ))}
                  </div>
                )}
              </div>
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
  liveMonitor,
  liveMarks,
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
      eventType: "BROKER_SYNC_ERROR",
      phase: "Backend",
      tone: "blocked",
      summary: "Backend API is not responding.",
      detail: "Live Bot status cannot be refreshed until the API responds again.",
      details: {},
    }));
    return entries;
  }

  if (botStarted) {
    entries.push(observedLogEntry({
      key: `bot-running|${sessionKey}|${liveStatus?.startedAt || ""}`,
      sessionId,
      createdAt: liveStatus?.startedAt || liveStatus?.lastUpdatedAt || observedAt,
      eventType: "BOT_STARTED",
      phase: "Live Bot",
      tone: "active",
      summary: "Live bot started.",
      detail: "Waiting for the backend event stream.",
      details: {
        symbols: cleanLogText(liveStatus?.symbols || DEFAULT_SYMBOLS.join(", ")),
        mode: cleanLogText(liveStatus?.executionMode || "Live"),
        profile: cleanLogText(liveStatus?.fundedProfile || "practice"),
      },
    }));
  } else if (liveStatus) {
    entries.push(observedLogEntry({
      key: `bot-idle|${sessionKey}|${liveStatus?.lastDecision || ""}`,
      sessionId,
      createdAt: liveStatus?.lastUpdatedAt || observedAt,
      eventType: "BOT_STOPPED",
      phase: "Live Bot",
      tone: "closed",
      summary: "Live bot stopped.",
      detail: "No new strategy scans or Topstep orders will run until the bot is started again.",
      details: { sessionId },
    }));
  }

  const feedStaleSeconds = Number(liveMarks?.feedStaleSeconds ?? liveMonitor?.feedStaleSeconds ?? -1);
  if (feedRunning && feedStaleSeconds > MARKET_DATA_STALE_SECONDS) {
    entries.push(observedLogEntry({
      key: `feed-stopped|${sessionKey}|${feedStaleSeconds}`,
      sessionId,
      createdAt: liveMarks?.lastEventAt || liveMonitor?.realtimeLastEventAt || observedAt,
      eventType: "MARKET_DATA_STOPPED",
      phase: "Market Data",
      tone: "error",
      summary: "Market data stopped.",
      detail: "Strategy scans and Topstep entries are paused until fresh data resumes.",
      details: {
        seconds: feedStaleSeconds,
        lastEvent: cleanLogText(liveMarks?.lastEventAt || liveMonitor?.realtimeLastEventAt || ""),
      },
    }));
  }

  const marketSession = liveStatus?.marketSession || liveMonitor?.marketSession || null;
  if (marketSession) {
    const marketCode = cleanLogText(marketSession.code || "");
    const closeCodes = new Set(["CLOSING_GUARD", "CANCEL_RESTING_ORDERS", "FLATTEN_WINDOW", "POST_CLOSE_VERIFY", "RTH_CLOSED"]);
    if (marketSession.entryWindowOpen || closeCodes.has(marketCode)) {
      entries.push(observedLogEntry({
        key: `entry-gate|${sessionKey}|${marketSession.marketDate || ""}|${Boolean(marketSession.entryWindowOpen)}`,
        sessionId,
        createdAt: marketSession.now || liveStatus?.lastUpdatedAt || observedAt,
        eventType: marketSession.entryWindowOpen ? "TRADING_ENABLED" : "ENTRY_GATE_CLOSED",
        phase: marketSession.entryWindowOpen ? "Trading Enabled" : "Entry Gate",
        tone: marketSession.entryWindowOpen ? "active" : "closed",
        summary: marketSession.entryWindowOpen ? "Trading Enabled" : "Trading entries disabled.",
        detail: marketSession.entryWindowOpen
          ? "Market window, realtime data, strategy config, and risk config are ready for live entries."
          : "",
        details: {
          gate: cleanLogText(marketSession.label || ""),
          code: marketCode,
          marketDate: cleanLogText(marketSession.marketDate || ""),
          strategyConfig: cleanLogText(liveStatus?.strategyPreset || ""),
          riskConfig: cleanLogText(liveStatus?.fundedProfile || ""),
          marketData: cleanLogText(liveStatus?.dataMode || liveMonitor?.dataMode || ""),
        },
      }));
    }
  }

  return entries;
}

function observedLogEntry({ key, sessionId, createdAt, eventType = "", phase, tone, symbol = "", barTime = "", summary, detail, details = {} }) {
  return {
    id: `observed-${key}`,
    observedKey: key,
    logSource: "ui_observed",
    sessionId,
    createdAt: createdAt || liveLogNow(),
    eventType,
    phase,
    tone,
    symbol,
    barTime,
    title: summary,
    subtext: detail,
    summary,
    detail,
    details: {
      logSource: "ui_observed",
      ...details,
    },
  };
}

function cleanLogText(value) {
  return String(value ?? "").replace(/\s+/g, " ").trim();
}

function coalesceLiveBotLogEntries(entries) {
  if (!Array.isArray(entries)) return [];
  const sorted = [...entries]
    .filter((entry) => entry && (entry.summary || entry.detail || entry.title || entry.subtext))
    .filter((entry) => !["SESSION_PREP"].includes(eventLogCode(entry)))
    .sort((first, second) => String(second?.createdAt || second?.barTime || "").localeCompare(String(first?.createdAt || first?.barTime || "")));
  const grouped = new Map();
  sorted.forEach((entry) => {
    const key = coalesceLiveBotLogKey(entry);
    const current = grouped.get(key);
    if (!current) {
      grouped.set(key, { ...entry, coalescedCount: 1, firstAt: entry.createdAt || entry.barTime || "", lastAt: entry.createdAt || entry.barTime || "" });
      return;
    }
    current.coalescedCount += 1;
    current.firstAt = entry.createdAt || entry.barTime || current.firstAt;
  });
  return Array.from(grouped.values()).slice(0, 1000);
}

function coalesceLiveBotLogKey(entry) {
  const code = eventLogCode(entry);
  const sessionId = entry?.sessionId || "current";
  const details = entry?.details || {};
  if (["BACKEND_RESTARTED", "BOT_STARTED", "BOT_STOPPED", "BOT_START_BLOCKED", "STOP_FLATTEN_SWEEP"].includes(code)) {
    return [code, sessionId, entry?.createdAt || "", entry?.id || ""].join("|");
  }
  if (["ENTRY_GATE_CLOSED", "ENTRY_GATE_OPENED", "TRADING_ENABLED"].includes(code)) {
    return [code, sessionId, details.marketDate || ""].join("|");
  }
  if (["MARKET_DATA_STOPPED", "MARKET_DATA_RESUMED"].includes(code)) {
    return [code, sessionId, entry?.createdAt || "", entry?.id || ""].join("|");
  }
  if (["POST_CLOSE_CLEANUP"].includes(code)) {
    return [code, sessionId, details.marketDate || "", details.code || "", details.gate || "", details.action || ""].join("|");
  }
  if (code.includes("DATA") || code.includes("METRIC")) {
    return [code, sessionId, cleanLogText(entry?.phase || ""), cleanLogText(eventTitle(entry))].join("|");
  }
  return [code, sessionId, cleanLogText(entry?.symbol || details.symbol || ""), cleanLogText(eventTitle(entry)), cleanLogText(entry?.barTime || "")].join("|");
}

function compactEventSubtext(entry) {
  const code = eventLogCode(entry);
  if (["BACKEND_RESTARTED", "ENTRY_GATE_CLOSED", "ENTRY_GATE_OPENED", "POST_CLOSE_CLEANUP", "SESSION_PREP"].includes(code)) {
    return "";
  }
  const text = eventSubtext(entry);
  const maxLength = ["ORDER_SUBMITTED", "POSITION_EXITED", "DTM_DECISION", "TRADING_ENABLED", "MARKET_DATA_STOPPED", "MARKET_DATA_RESUMED"].includes(code) ? 420 : 140;
  return text.length > maxLength ? `${text.slice(0, maxLength - 3)}...` : text;
}

function buildEquityReviewStatus({ backendOffline, botStarted, feedRunning, liveMonitor, liveMarks, symbolStates, metrics }) {
  const healthIssues = [];
  if (backendOffline) healthIssues.push({ tone: "error" });
  if (botStarted && !feedRunning) healthIssues.push({ tone: "error" });
  if (Number(liveMonitor?.feedStaleSeconds ?? -1) > MARKET_DATA_STALE_SECONDS) healthIssues.push({ tone: "error" });
  const marksSeverity = liveMarks?.checks?.overall?.severity || "";
  if (marksSeverity === "error") healthIssues.push({ tone: "error" });
  if (marksSeverity === "warn") healthIssues.push({ tone: "warn" });
  if (metrics && metrics.brokerMetricsReady === false) healthIssues.push({ tone: "warn" });
  const marksHealthySymbols = new Set();
  Object.entries(liveMarks?.symbols || {}).forEach(([rawSymbol, mark]) => {
    const symbol = String(rawSymbol || "").toUpperCase();
    if (symbol && (mark?.currentCandle || Number(mark?.lastPrice || 0) > 0 || liveMarks?.feedFresh)) {
      marksHealthySymbols.add(symbol);
    }
  });
  (Array.isArray(symbolStates) ? symbolStates : []).forEach((state) => {
    const health = String(state?.healthStatus || "").toLowerCase();
    const code = String(state?.errorCode || "");
    const symbol = String(state?.symbol || "").toUpperCase();
    if (!botStarted && (code === "FEED_STOPPED" || code === "ENTRY_GATE_CLOSED")) return;
    if ((health === "warn" || health === "error") && marksHealthySymbols.has(symbol) && code !== "FEED_STALE" && code !== "MARKET_DATA_STOPPED") return;
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
  if (phase.includes("PROVENANCE")) return tone === "ERROR" ? "PROVENANCE_ERROR" : tone === "WARN" ? "PROVENANCE_WARN" : "PROVENANCE_CHECK";
  if (phase.includes("BROKER")) return tone === "ERROR" ? "BROKER_ERROR" : tone === "WARN" ? "BROKER_WARN" : "BROKER_CHECK";
  if (phase.includes("RISK")) return tone === "ERROR" || tone === "BLOCKED" ? "RISK_BLOCK" : "RISK_CHECK";
  if (phase.includes("TOPSTEP") || phase.includes("ORDER")) return tone === "ERROR" || tone === "BLOCKED" ? "ORDER_ERROR" : "ORDER_CHECK";
  if (phase.includes("EXIT") || phase.includes("CLOSE") || phase.includes("POST CLOSE")) return tone === "ERROR" ? "SELL_ERROR" : "SELL_CHECK";
  if (phase.includes("POTENTIAL")) return tone === "BLOCKED" ? "POTENTIAL_BLOCK" : "POTENTIAL_TRADE";
  if (phase.includes("SIGNAL") || phase.includes("CANDIDATE")) return "TRADE_THINKING";
  if (tone === "ERROR" || tone === "BLOCKED") return "SYSTEM_ERROR";
  if (tone === "WARN") return "SYSTEM_WARN";
  return "STATUS";
}

function eventLogCode(entry) {
  const type = String(entry?.eventType || "").trim();
  return type || equityReviewCode(entry);
}

function eventTitle(entry) {
  return cleanLogText(entry?.title || entry?.summary || "--");
}

function eventSubtext(entry) {
  return compactTradeEventSubtext(entry) || cleanLogText(entry?.subtext || entry?.detail || "");
}

function eventContextText(entry) {
  const symbol = String(entry?.symbol || entry?.details?.symbol || "").trim().toUpperCase();
  const source = logEntrySourceLabel(entry);
  return [source, symbol].filter(Boolean).join(" | ");
}

function logEntrySourceLabel(entry) {
  const rawSource = cleanLogText(entry?.logSource || entry?.details?.logSource || entry?.source || entry?.details?.source || "");
  if (rawSource === "ui_observed" || entry?.observedKey || String(entry?.id || "").startsWith("observed-")) {
    return "UI observed";
  }
  return "Backend";
}

function eventToneClass(entry) {
  return cleanLogText(entry?.tone || "info").toLowerCase();
}

function compactTradeEventSubtext(entry) {
  const code = eventLogCode(entry);
  const reason = normalizeTradeReasonPayload(entry?.details?.tradeReason);
  if (code === "ORDER_SUBMITTED") {
    const strategy = compactEntryStrategyText(reason.entry, entry);
    const risk = compactEntryRiskText(reason.entry, entry);
    return [strategy, risk].filter(Boolean).join(" ");
  }
  if (code === "POSITION_EXITED") {
    const exit = normalizeReasonSection(reason.exit);
    const dtm = compactDtmText(exit);
    const final = compactFinalExitText(exit, entry, { includePnl: true });
    const review = compactReviewText(exit);
    return [dtm ? `DTM: ${dtm}` : "", final, review ? `Review: ${review}` : ""].filter(Boolean).join(" ");
  }
  return "";
}

function eventDetailEntries(entry) {
  return visibleLiveEventDetailEntries(entry);
}

function detailLabel(key) {
  const labels = {
    symbols: "symbols",
    symbol: "symbol",
    logSource: "source",
    source: "source",
    strategyConfig: "strategy config",
    riskConfig: "risk config",
    marketData: "market data",
    strategy: "strategy",
    side: "side",
    contracts: "contracts",
    entry: "entry",
    stop: "stop",
    target: "target",
    orderId: "order",
    seconds: "age",
    downtime: "downtime",
    marketEventGap: "event gap",
    lastEvent: "last event",
    gate: "gate",
    profile: "profile",
    account: "account",
    mode: "mode",
    action: "action",
    status: "status",
    dtmAction: "DTM",
    entryReason: "entry reason",
    exitReason: "exit reason",
    exitPrice: "exit",
    pnl: "PnL",
    reason: "reason",
  };
  return labels[key] || String(key || "").replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
}

function formatEventDetailValue(value) {
  if (value === null || value === undefined) return "";
  if (typeof value === "number") {
    return Number.isInteger(value) ? formatInteger(value) : formatPrice(value);
  }
  if (typeof value === "boolean") return value ? "yes" : "no";
  if (Array.isArray(value)) return value.map(formatEventDetailValue).join(", ");
  if (typeof value === "object") return cleanLogText(JSON.stringify(value));
  return cleanLogText(value);
}

function thinkingLogRowClass(entry) {
  const text = `${entry?.summary || ""} ${entry?.detail || ""} ${entry?.title || ""} ${entry?.subtext || ""}`.toLowerCase();
  const bracketAlert = text.includes("auto oco brackets") || text.includes("position brackets");
  return `futures-thinking-row ${eventToneClass(entry)} ${eventLogCode(entry).toLowerCase().replaceAll("_", "-")}${bracketAlert ? " bracket-alert" : ""}`;
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
  lastRealtimeEventAt,
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
  const wheelDraftRef = useRef({ offset: 0, visibleBars: 96 });
  const wheelFrameRef = useRef(null);
  const touchGestureRef = useRef(null);
  const touchPanFrameRef = useRef(null);
  const touchInertiaFrameRef = useRef(null);
  const touchPanOffsetRef = useRef(0);
  const scrollOffsetRef = useRef(0);
  const visibleBarsRef = useRef(visibleBars);
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
  const priceTop = 8;
  const priceBottom = 430;
  const volumeTop = 450;
  const volumeHeight = 52;
  const axisY = 536;
  const rightAxisGutter = 58;
  const plotWidth = width - rightAxisGutter;
  const minVisibleBars = candleSeries.length > 0 ? Math.min(24, Math.max(8, candleSeries.length)) : 8;
  const maxVisibleBars = Math.min(220, Math.max(minVisibleBars, candleSeries.length));
  const safeVisibleBars = Math.max(minVisibleBars, Math.min(visibleBars, maxVisibleBars));
  const maxOffset = Math.max(0, candleSeries.length - safeVisibleBars);
  const offset = Math.min(scrollOffset, maxOffset);
  const windowEndIndex = Math.max(0, candleSeries.length - offset);
  const windowStartIndex = Math.max(0, windowEndIndex - safeVisibleBars);
  const startIndex = Math.max(0, Math.floor(windowStartIndex));
  const endIndex = Math.min(candleSeries.length, Math.ceil(windowEndIndex));
  const visibleCandles = candleSeries.slice(startIndex, endIndex);
  const visibleWindowSpan = Math.max(0, windowEndIndex - windowStartIndex);
  const leadingSlots = Math.max(0, safeVisibleBars - visibleWindowSpan);
  const slotWidth = plotWidth / safeVisibleBars;
  const latestCandle = candleSeries[candleSeries.length - 1];
  const latestPrice = Number(latestCandle?.close || 0);
  const livePinned = offset === 0;
  const visibleCandleToleranceMs = timeframeMinutesForClient(timeframe) * 60000 * 1.6;
  const visibleTrades = Array.isArray(trades) ? trades.filter((trade) => Number(trade.entryPrice || 0) > 0) : [];
  const chartDomainTrades = visibleTrades.filter((trade) => tradeTouchesVisibleWindow(trade, visibleCandles, visibleCandleToleranceMs));
  const targetPriceDomain = buildChartPriceDomain({
    candles: visibleCandles,
    trades: chartDomainTrades,
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
  const marketDataStopped = !backendOffline && botStarted && feedStaleSeconds >= 0 && feedStaleSeconds > MARKET_DATA_STALE_SECONDS;
  const lastFeedEventLabel = lastRealtimeEventAt ? `Last event ${formatEstTime(lastRealtimeEventAt)}` : "Last event unavailable";

  const toY = (price) => priceBottom - (((Number(price || 0) - min) / range) * (priceBottom - priceTop));
  const toX = (index) => (leadingSlots + ((startIndex + index) - windowStartIndex)) * slotWidth + slotWidth / 2;
  const findVisibleIndex = (time) => findNearestCandleIndex(visibleCandles, time, visibleCandleToleranceMs);
  const setClampedOffset = (next) => setScrollOffset(Math.max(0, Math.min(maxOffset, next)));
  const panBy = (steps) => setClampedOffset(offset + steps);
  const clampVisibleBarsValue = useCallback((value) => Math.max(minVisibleBars, Math.min(maxVisibleBars, value)), [maxVisibleBars, minVisibleBars]);
  const zoomBy = (delta) => setVisibleBars((value) => clampVisibleBarsValue(value + delta));
  const clampOffsetValue = useCallback((value) => Math.max(0, Math.min(maxOffset, value)), [maxOffset]);
  const applyWheelDraft = useCallback(() => {
    wheelFrameRef.current = null;
    const draft = wheelDraftRef.current;
    setVisibleBars(clampVisibleBarsValue(draft.visibleBars));
    setScrollOffset(clampOffsetValue(draft.offset));
  }, [clampOffsetValue, clampVisibleBarsValue]);
  const queueTouchPanOffset = useCallback((nextOffset) => {
    touchPanOffsetRef.current = clampOffsetValue(nextOffset);
    if (touchPanFrameRef.current) return;
    touchPanFrameRef.current = requestAnimationFrame(() => {
      touchPanFrameRef.current = null;
      scrollOffsetRef.current = touchPanOffsetRef.current;
      setScrollOffset(touchPanOffsetRef.current);
    });
  }, [clampOffsetValue]);
  const handleWheel = useCallback((event) => {
    if (!hasCandles) return;
    if (event.target?.closest?.(".futures-chart-actionbar, .futures-chart-range-row")) return;
    event.preventDefault();
    event.stopPropagation();
    const deltaScale = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? 160 : 1;
    const deltaX = event.deltaX * deltaScale;
    const deltaY = event.deltaY * deltaScale;
    const currentVisibleBars = wheelFrameRef.current ? wheelDraftRef.current.visibleBars : visibleBarsRef.current;
    const currentOffset = wheelFrameRef.current ? wheelDraftRef.current.offset : scrollOffsetRef.current;

    if (event.altKey || event.ctrlKey || event.metaKey) {
      const rect = chartShellRef.current?.getBoundingClientRect();
      const chartRatio = rect?.width ? Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)) : 0.5;
      const zoomSource = Math.abs(deltaY) >= Math.abs(deltaX) ? deltaY : deltaX;
      const zoomDelta = Math.max(-18, Math.min(18, zoomSource * 0.08));
      const nextVisibleBars = clampVisibleBarsValue(currentVisibleBars + zoomDelta);
      const rightIndex = candleSeries.length - currentOffset;
      const anchorIndex = rightIndex - (currentVisibleBars * (1 - chartRatio));
      const nextRightIndex = anchorIndex + (nextVisibleBars * (1 - chartRatio));
      const nextOffset = clampOffsetValue(candleSeries.length - nextRightIndex);
      wheelDraftRef.current = { offset: nextOffset, visibleBars: nextVisibleBars };
      if (!wheelFrameRef.current) wheelFrameRef.current = requestAnimationFrame(applyWheelDraft);
      return;
    }

    const dominantDelta = Math.abs(deltaX) > Math.abs(deltaY) ? deltaX : deltaY;
    const panDelta = event.shiftKey ? deltaY : dominantDelta;
    const barsPerPixel = Math.max(0.018, Math.min(0.08, currentVisibleBars / Math.max(480, chartShellRef.current?.clientWidth || 1)));
    const nextOffset = clampOffsetValue(currentOffset + (panDelta * barsPerPixel));
    wheelDraftRef.current = { offset: nextOffset, visibleBars: currentVisibleBars };
    if (!wheelFrameRef.current) wheelFrameRef.current = requestAnimationFrame(applyWheelDraft);
  }, [applyWheelDraft, candleSeries.length, clampOffsetValue, clampVisibleBarsValue, hasCandles]);

  useEffect(() => {
    const node = chartShellRef.current;
    if (!node) return undefined;
    node.addEventListener("wheel", handleWheel, { passive: false });
    return () => {
      node.removeEventListener("wheel", handleWheel);
      if (wheelFrameRef.current) {
        cancelAnimationFrame(wheelFrameRef.current);
        wheelFrameRef.current = null;
      }
    };
  }, [handleWheel, hasCandles, symbol, timeframe]);

  useEffect(() => {
    scrollOffsetRef.current = offset;
  }, [offset]);

  useEffect(() => {
    visibleBarsRef.current = safeVisibleBars;
    wheelDraftRef.current = { offset, visibleBars: safeVisibleBars };
  }, [offset, safeVisibleBars]);

  useEffect(() => {
    const node = chartShellRef.current;
    if (!node) return undefined;

    const clampVisibleBars = (value) => Math.max(minVisibleBars, Math.min(220, value));
    const stopTouchInertia = () => {
      if (touchInertiaFrameRef.current) {
        cancelAnimationFrame(touchInertiaFrameRef.current);
        touchInertiaFrameRef.current = null;
      }
    };
    const touchDistance = (touches) => {
      if (!touches || touches.length < 2) return 0;
      const first = touches[0];
      const second = touches[1];
      return Math.hypot(second.clientX - first.clientX, second.clientY - first.clientY);
    };

    const onTouchStart = (event) => {
      if (!hasCandles) return;
      stopTouchInertia();
      if (event.touches.length >= 2) {
        const distance = touchDistance(event.touches);
        touchGestureRef.current = {
          mode: "pinch",
          startDistance: distance,
          startVisibleBars: safeVisibleBars,
        };
        if (event.cancelable) event.preventDefault();
        return;
      }

      const touch = event.touches[0];
      touchGestureRef.current = {
        mode: "pending",
        startX: touch.clientX,
        startY: touch.clientY,
        startOffset: scrollOffsetRef.current,
        lastX: touch.clientX,
        lastAt: performance.now(),
        velocityBars: 0,
      };
    };

    const onTouchMove = (event) => {
      if (!hasCandles || !touchGestureRef.current) return;

      if (event.touches.length >= 2) {
        const gesture = touchGestureRef.current;
        const distance = touchDistance(event.touches);
        if (gesture.mode !== "pinch" || !gesture.startDistance) {
          touchGestureRef.current = {
            mode: "pinch",
            startDistance: distance,
            startVisibleBars: safeVisibleBars,
          };
          if (event.cancelable) event.preventDefault();
          return;
        }

        if (distance > 8) {
          const scale = gesture.startDistance / distance;
          setVisibleBars(clampVisibleBars(Math.round(gesture.startVisibleBars * scale)));
        }
        if (event.cancelable) event.preventDefault();
        return;
      }

      const gesture = touchGestureRef.current;
      const touch = event.touches[0];
      if (!touch || gesture.mode === "pinch") return;

      const deltaX = touch.clientX - gesture.startX;
      const deltaY = touch.clientY - gesture.startY;
      const now = performance.now();
      const absX = Math.abs(deltaX);
      const absY = Math.abs(deltaY);
      if (gesture.mode === "pending") {
        if (absY > 12 && absY > absX * 1.15) {
          touchGestureRef.current = null;
          return;
        }
        if (absX < 12 || absX < absY * 1.05) return;
        gesture.mode = "pan";
      }

      const mobilePanWindow = Math.max(safeVisibleBars * 1.35, 32);
      const panSteps = Math.round((deltaX / Math.max(node.clientWidth, 1)) * mobilePanWindow);
      const elapsed = Math.max(16, now - Number(gesture.lastAt || now));
      const deltaBars = ((touch.clientX - Number(gesture.lastX || touch.clientX)) / Math.max(node.clientWidth, 1)) * mobilePanWindow;
      gesture.velocityBars = deltaBars / elapsed;
      gesture.lastX = touch.clientX;
      gesture.lastAt = now;
      queueTouchPanOffset(Number(gesture.startOffset || 0) + panSteps);
      if (event.cancelable) event.preventDefault();
    };

    const startTouchInertia = () => {
      const gesture = touchGestureRef.current;
      let velocity = Number(gesture?.velocityBars || 0) * 16;
      if (Math.abs(velocity) < 0.08) return;
      const animate = () => {
        const current = scrollOffsetRef.current;
        const next = clampOffsetValue(current + velocity);
        if (next === current || Math.abs(velocity) < 0.02) {
          touchInertiaFrameRef.current = null;
          return;
        }
        scrollOffsetRef.current = next;
        setScrollOffset(next);
        velocity *= 0.9;
        touchInertiaFrameRef.current = requestAnimationFrame(animate);
      };
      touchInertiaFrameRef.current = requestAnimationFrame(animate);
    };

    const onTouchEnd = (event) => {
      if (event.touches.length === 1) {
        const touch = event.touches[0];
        touchGestureRef.current = {
          mode: "pending",
          startX: touch.clientX,
          startY: touch.clientY,
          startOffset: scrollOffsetRef.current,
          lastX: touch.clientX,
          lastAt: performance.now(),
          velocityBars: 0,
        };
        return;
      }
      if (touchGestureRef.current?.mode === "pan") {
        startTouchInertia();
      }
      touchGestureRef.current = null;
    };

    node.addEventListener("touchstart", onTouchStart, { passive: false });
    node.addEventListener("touchmove", onTouchMove, { passive: false });
    node.addEventListener("touchend", onTouchEnd, { passive: false });
    node.addEventListener("touchcancel", onTouchEnd, { passive: false });
    return () => {
      node.removeEventListener("touchstart", onTouchStart);
      node.removeEventListener("touchmove", onTouchMove);
      node.removeEventListener("touchend", onTouchEnd);
      node.removeEventListener("touchcancel", onTouchEnd);
      if (touchPanFrameRef.current) {
        cancelAnimationFrame(touchPanFrameRef.current);
        touchPanFrameRef.current = null;
      }
      stopTouchInertia();
    };
  }, [clampOffsetValue, hasCandles, maxOffset, minVisibleBars, queueTouchPanOffset, safeVisibleBars, symbol, timeframe]);

  useEffect(() => {
    wheelDraftRef.current = { offset: 0, visibleBars: defaultChartVisibleBars(chartShellRef.current) };
    if (wheelFrameRef.current) {
      cancelAnimationFrame(wheelFrameRef.current);
      wheelFrameRef.current = null;
    }
    touchGestureRef.current = null;
    touchPanOffsetRef.current = 0;
    if (touchPanFrameRef.current) {
      cancelAnimationFrame(touchPanFrameRef.current);
      touchPanFrameRef.current = null;
    }
    if (touchInertiaFrameRef.current) {
      cancelAnimationFrame(touchInertiaFrameRef.current);
      touchInertiaFrameRef.current = null;
    }
    let cancelled = false;
    queueMicrotask(() => {
      if (cancelled) return;
      setHoveredIndex(null);
      setHoveredTradeIndex(null);
      setSelectedTradeKey(null);
      setScrollOffset(0);
      setVisibleBars(defaultChartVisibleBars(chartShellRef.current));
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

  if (marketDataStopped) {
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
              <strong>Feed Stopped</strong>
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

        <div className="app-chart-empty futures-chart-sync-empty futures-chart-feed-stopped-empty">
          <strong>Feed Stopped</strong>
          <div className="futures-chart-sync-grid">
            <span>{symbol}</span>
            <span>{timeframeLabel(timeframe)}</span>
            <span>Market Data Stopped</span>
            <span>{lastFeedEventLabel}</span>
            <span>Feed age {formatDuration(feedStaleSeconds)}</span>
          </div>
        </div>
      </div>
    );
  }

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
        preserveAspectRatio="none"
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
          step="0.01"
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
  const sessionId = Number(status?.sessionId || 0);
  return [
    sessionId,
    Boolean(status?.running),
    Number(status?.decisionCount || 0),
    Number(status?.acceptedDecisionCount || 0),
    Number(status?.rejectedDecisionCount || 0),
    status?.lastProcessedLiveBarTime || "",
  ].join("|");
}

function mergeMonitorWithMarks(monitor, marks, timeframe) {
  if (!marks?.success || !marks.symbols) return monitor;
  const normalizedTimeframe = normalizeClientTimeframe(timeframe || marks.timeframe || monitor?.timeframe);
  if (marks.timeframe && normalizeClientTimeframe(marks.timeframe) !== normalizedTimeframe) return monitor;
  const baseMonitor = monitor || { timeframe: normalizedTimeframe, marketData: {}, symbolStates: [] };
  const marketData = { ...(baseMonitor.marketData || {}) };
  Object.entries(marks.symbols || {}).forEach(([rawSymbol, patch]) => {
    const symbol = String(rawSymbol || "").toUpperCase();
    if (!symbol || !patch?.currentCandle) return;
    marketData[symbol] = mergeCurrentCandleIntoSeries(marketData[symbol], patch.currentCandle, normalizedTimeframe);
  });
  return {
    ...baseMonitor,
    timeframe: normalizeClientTimeframe(baseMonitor.timeframe || normalizedTimeframe),
    realtimeRunning: baseMonitor.realtimeRunning || Boolean(marks.feedFresh),
    lastRealtimeEventAt: marks.lastEventAt || baseMonitor.lastRealtimeEventAt,
    serverTime: marks.serverTime || baseMonitor.serverTime,
    feedStaleSeconds: Number.isFinite(Number(marks.feedStaleSeconds)) ? Number(marks.feedStaleSeconds) : baseMonitor.feedStaleSeconds,
    marketData,
    symbolStates: mergeSymbolStatesWithMarks(baseMonitor.symbolStates, marks),
  };
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

function mergeCurrentCandleIntoSeries(series, currentCandle, timeframe = "1m") {
  const candles = Array.isArray(series) ? [...series] : [];
  const patch = normalizeCandle(currentCandle);
  if (!patch.time || Number(patch.close || 0) <= 0) return candles;
  const lastIndex = candles.length - 1;
  if (lastIndex < 0) return candles;
  const last = normalizeCandle(candles[lastIndex]);
  const lastTime = parseChartTime(last.time);
  const patchTime = parseChartTime(patch.time);
  if (last.time === patch.time) {
    const resolvedVolume = resolveLivePatchVolume(candles, patch, last);
    candles[lastIndex] = {
      ...last,
      ...patch,
      high: Math.max(Number(last.high || 0), Number(patch.high || patch.close || 0)),
      low: Math.min(Number(last.low || patch.low || patch.close || 0), Number(patch.low || patch.close || 0)),
      volume: resolvedVolume,
      vwap: Number(last.vwap || 0) || Number(patch.vwap || 0),
      ema9: Number(last.ema9 || 0) || Number(patch.ema9 || 0),
      ema20: Number(last.ema20 || 0) || Number(patch.ema20 || 0),
      rsi14: Number(last.rsi14 || 0) || Number(patch.rsi14 || 0),
      live: true,
    };
    return candles;
  }
  if (
    patchTime
    && (!lastTime || patchTime > lastTime)
    && shouldAppendLivePatchCandle({ series: candles, patch, timeframe })
  ) {
    candles.push({
      ...patch,
      volume: resolveLivePatchVolume(candles, patch, null),
    });
  }
  return candles;
}

function mergeMonitorWithCachedMarketData(primary, fallback) {
  if (!primary) return fallback || null;
  if (!fallback) return primary;
  const primaryTimeframe = normalizeClientTimeframe(primary.timeframe);
  const fallbackTimeframe = normalizeClientTimeframe(fallback.timeframe);
  if (primaryTimeframe !== fallbackTimeframe) return primary;
  if (primary.monitorCacheKey && fallback.monitorCacheKey && primary.monitorCacheKey !== fallback.monitorCacheKey) return primary;
  const marketData = { ...(fallback.marketData || {}), ...(primary.marketData || {}) };
  const symbols = new Set([
    ...Object.keys(fallback.marketData || {}),
    ...Object.keys(primary.marketData || {}),
  ]);
  symbols.forEach((symbol) => {
    marketData[symbol] = mergeCandleSeriesByTime(fallback.marketData?.[symbol], primary.marketData?.[symbol]);
  });
  return {
    ...fallback,
    ...primary,
    marketData,
  };
}

function mergeCandleSeriesByTime(baseSeries, patchSeries) {
  const base = Array.isArray(baseSeries) ? baseSeries : [];
  const patches = Array.isArray(patchSeries) ? patchSeries : [];
  if (!base.length) return patches;
  if (!patches.length) return base;
  const byTime = new Map();
  [...base, ...patches].forEach((rawCandle) => {
    const candle = normalizeCandle(rawCandle);
    if (!candle.time || Number(candle.close || 0) <= 0) return;
    const existing = byTime.get(candle.time);
    if (!existing) {
      byTime.set(candle.time, candle);
      return;
    }
    byTime.set(candle.time, {
      ...existing,
      ...candle,
      high: Math.max(Number(existing.high || 0), Number(candle.high || candle.close || 0)),
      low: Math.min(Number(existing.low || existing.close || 0), Number(candle.low || candle.close || 0)),
      volume: mergeSeriesCandleVolume(existing, candle),
      live: Boolean(existing.live || candle.live),
    });
  });
  return Array.from(byTime.values())
    .sort((first, second) => (parseChartTime(first.time) || 0) - (parseChartTime(second.time) || 0))
    .slice(-Math.max(base.length, patches.length));
}

function mergeSymbolStatesWithMarks(symbolStates, marks) {
  const bySymbol = new Map((Array.isArray(symbolStates) ? symbolStates : []).map((state) => [String(state.symbol || "").toUpperCase(), state]));
  const feedStaleSeconds = Number(marks?.feedStaleSeconds ?? -1);
  const marketDataStopped = feedStaleSeconds >= 0
    ? feedStaleSeconds > MARKET_DATA_STALE_SECONDS
    : marks?.feedFresh === false;
  Object.entries(marks.symbols || {}).forEach(([rawSymbol, patch]) => {
    const symbol = String(rawSymbol || "").toUpperCase();
    if (!symbol) return;
    const current = bySymbol.get(symbol) || { symbol };
    const lastPrice = Number(patch?.lastPrice || current.lastPrice || 0);
    const currentCandleTime = patch?.currentCandle?.time || current.lastBarTime || "";
    const marksHealthy = !marketDataStopped && Boolean(patch?.currentCandle || lastPrice > 0 || marks.feedFresh);
    bySymbol.set(symbol, {
      ...current,
      symbol,
      dataSource: current.dataSource || "LIVE_MARKS_CACHE",
      analysisStatus: marketDataStopped ? "Market Data Stopped" : marksHealthy ? "Tracking live candles" : (current.analysisStatus || "Waiting for live marks"),
      healthStatus: marketDataStopped ? "error" : marksHealthy ? "ok" : (current.healthStatus || "warn"),
      errorCode: marketDataStopped ? "MARKET_DATA_STOPPED" : marksHealthy ? "" : current.errorCode,
      healthDetail: marketDataStopped ? "Market Data Stopped" : marksHealthy ? "Live marks are updating." : (current.healthDetail || "Fast marks are waiting for a fresh ProjectX event."),
      lastPrice,
      lastBarTime: currentCandleTime,
      liveEvents: Math.max(Number(current.liveEvents || 0), Number(patch?.currentCandle?.events || 0)),
    });
  });
  return Array.from(bySymbol.values());
}

function resolveDisplayMonitor(liveMonitor, monitorCache, symbolsCsv, selectedTimeframe, selectedSymbol) {
  const normalizedTimeframe = normalizeClientTimeframe(selectedTimeframe);
  const cacheKey = liveMonitorCacheKey(symbolsCsv, normalizedTimeframe);
  const liveTimeframe = normalizeClientTimeframe(liveMonitor?.timeframe);
  const cachedMonitor = monitorCache?.[cacheKey] || null;
  if (liveMonitorMatchesCacheKey(liveMonitor, cacheKey, normalizedTimeframe) || (liveMonitor && !liveMonitor.monitorCacheKey && liveTimeframe === normalizedTimeframe)) {
    return chooseBestDisplayMonitor(liveMonitor, cachedMonitor, selectedSymbol);
  }
  if (cachedMonitor) return cachedMonitor;
  return null;
}

function chooseBestDisplayMonitor(liveMonitor, cachedMonitor, selectedSymbol) {
  if (!cachedMonitor) return liveMonitor;
  const merged = mergeMonitorWithCachedMarketData(liveMonitor, cachedMonitor);
  const symbol = String(selectedSymbol || "").toUpperCase();
  const liveLength = Array.isArray(liveMonitor?.marketData?.[symbol]) ? liveMonitor.marketData[symbol].length : 0;
  const cachedLength = Array.isArray(cachedMonitor?.marketData?.[symbol]) ? cachedMonitor.marketData[symbol].length : 0;
  const mergedLength = Array.isArray(merged?.marketData?.[symbol]) ? merged.marketData[symbol].length : 0;
  if (mergedLength >= Math.max(liveLength, cachedLength, MIN_OPENING_CHART_BARS)) return merged;
  return cachedLength > liveLength ? cachedMonitor : liveMonitor;
}

function normalizeClientTimeframe(value) {
  if (value === "5m" || value === "30m" || value === "1h") return value;
  return "1m";
}

function augmentTopstepMetricsWithMarks(metrics, symbolStates) {
  if (!isAuthoritativeTopstepBrokerSnapshot(metrics?.broker, metrics)) return metrics;
  const stateBySymbol = new Map((Array.isArray(symbolStates) ? symbolStates : []).map((state) => [String(state.symbol || "").toUpperCase(), state]));
  const broker = metrics.broker;
  let brokerPositionPnl = 0;
  let markedPositionPnl = 0;
  const positions = (Array.isArray(broker.positions) ? broker.positions : []).map((position) => {
    const symbol = String(position?.symbol || "").toUpperCase();
    const markPrice = Number(stateBySymbol.get(symbol)?.lastPrice || position?.markPrice || 0);
    const entryPrice = Number(position?.averagePrice || position?.entryPrice || 0);
    const contracts = Number(position?.contracts || 0);
    const brokerPnl = Number(position?.displayPnl ?? position?.markPnl ?? position?.unrealizedPnl ?? position?.pnl ?? 0);
    const markPnl = markPrice > 0 && entryPrice > 0 && contracts > 0
      ? calculateFuturesPnl(symbol, position.side, entryPrice, markPrice, contracts)
      : null;
    brokerPositionPnl += brokerPnl;
    markedPositionPnl += markPnl ?? brokerPnl;
    return {
      ...position,
      symbol,
      markPrice,
      currentPrice: markPrice,
      entryPrice,
      averagePrice: entryPrice,
      markPnl,
      displayPnl: markPnl ?? brokerPnl,
    };
  });
  const positionPnlAdjustment = markedPositionPnl - brokerPositionPnl;
  const brokerCurrentPnl = Number(broker.currentPnl ?? metrics.currentPnl ?? 0);
  const brokerUnrealizedPnl = Number(broker.unrealizedPnl ?? brokerPositionPnl);
  const markedCurrentPnl = brokerCurrentPnl + positionPnlAdjustment;
  const markedUnrealizedPnl = brokerUnrealizedPnl + positionPnlAdjustment;
  const currentBalance = Number(metrics.currentBalance ?? broker.currentBalance ?? 0);
  const balanceTracksPnl = metricsBalanceTracksPnl(metrics);
  const markedCurrentBalance = Number.isFinite(currentBalance) && (currentBalance > 0 || balanceTracksPnl)
    ? currentBalance + positionPnlAdjustment
    : currentBalance;
  const riskCurrentBalance = Number(metrics.riskCurrentBalance ?? metrics.equityBalance ?? broker.riskCurrentBalance ?? broker.equityBalance ?? 0);
  const markedRiskCurrentBalance = Number.isFinite(riskCurrentBalance) && riskCurrentBalance > 0
    ? riskCurrentBalance + positionPnlAdjustment
    : riskCurrentBalance;
  const accountSize = Number(metrics.accountSize || broker.accountSize || 0);
  return {
    ...metrics,
    currentPnl: markedCurrentPnl,
    currentBalance: markedCurrentBalance,
    riskCurrentBalance: markedRiskCurrentBalance,
    equityBalance: markedRiskCurrentBalance,
    unrealizedPnl: markedUnrealizedPnl,
    returnPct: accountSize > 0 ? (markedCurrentPnl / accountSize) * 100 : metrics.returnPct,
    broker: {
      ...broker,
      positions,
      currentPnl: markedCurrentPnl,
      currentBalance: markedCurrentBalance,
      riskCurrentBalance: markedRiskCurrentBalance,
      equityBalance: markedRiskCurrentBalance,
      unrealizedPnl: markedUnrealizedPnl,
      displayPnlSource: "LIVE_MARKS_PRICE_ONLY",
    },
  };
}

function isAuthoritativeTopstepBrokerSnapshot(broker, metrics = null) {
  if (!broker?.success) return false;
  const source = String(broker.source || metrics?.dataSource || "").toUpperCase();
  return source === BROKER_SOURCE_TOPSTEPX && broker.authoritative !== false;
}

function scopeBrokerMetricsToAccount(metrics, accountId, accountSizeFallback = 0) {
  if (!metrics || !accountId) return metrics;
  const cleanAccountId = String(accountId || "").trim();
  const broker = metrics.broker || {};
  const brokerAccountId = String(broker.accountId || metrics.accountId || "").trim();
  const brokerReportsMismatch = broker.brokerAccountMatched === false;
  const accountMatches = !brokerReportsMismatch && (!brokerAccountId || brokerAccountId === cleanAccountId);
  const positions = accountMatches ? filterByAccountId(broker.positions, cleanAccountId) : [];
  const trades = accountMatches ? filterByAccountId(broker.trades, cleanAccountId) : [];
  const orders = accountMatches ? filterByAccountId(broker.orders, cleanAccountId) : [];
  const scopedBrokerReady = accountMatches && isAuthoritativeTopstepBrokerSnapshot(broker, metrics);
  if (!accountMatches) {
    const scopedAccountSize = Number(accountSizeFallback || 0);
    return {
      ...metrics,
      brokerMetricsReady: false,
      accountSize: scopedAccountSize,
      currentPnl: 0,
      currentBalance: scopedAccountSize,
      riskCurrentBalance: scopedAccountSize,
      equityBalance: scopedAccountSize,
      balanceMode: "EQUITY",
      balanceTracksPnl: false,
      returnPct: 0,
      realizedPnl: 0,
      unrealizedPnl: 0,
      drawdown: 0,
      numberOfTrades: 0,
      openTrades: 0,
      broker: {
        ...broker,
        success: false,
        accountId: brokerAccountId || cleanAccountId,
        brokerAccountMatched: false,
        positions: [],
        trades: [],
        orders: [],
      },
    };
  }
  return {
    ...metrics,
    numberOfTrades: Number.isFinite(Number(metrics.numberOfTrades)) ? metrics.numberOfTrades : trades.length,
    openTrades: Number.isFinite(Number(metrics.openTrades)) ? metrics.openTrades : positions.length,
    broker: {
      ...broker,
      success: scopedBrokerReady,
      source: broker.source || metrics.dataSource,
      accountId: brokerAccountId || cleanAccountId,
      brokerAccountMatched: true,
      positions,
      trades,
      orders,
    },
  };
}

function defaultLiveAccountMetrics(accountSize = 0, accountId = "") {
  const normalizedAccountSize = Number(accountSize || 0);
  const normalizedAccountId = String(accountId || "").trim();
  const idleBalance = 0;
  return {
    success: true,
    dataSource: "IDLE",
    brokerMetricsReady: false,
    accountSize: normalizedAccountSize,
    currentPnl: 0,
    currentBalance: idleBalance,
    riskCurrentBalance: normalizedAccountSize,
    equityBalance: normalizedAccountSize,
    balanceMode: "EQUITY",
    balanceTracksPnl: false,
    returnPct: 0,
    drawdown: 0,
    numberOfTrades: 0,
    openTrades: 0,
    broker: {
      success: false,
      source: "IDLE",
      accountId: normalizedAccountId,
      brokerAccountMatched: Boolean(normalizedAccountId),
      accountSize: normalizedAccountSize,
      currentPnl: 0,
      currentBalance: idleBalance,
      riskCurrentBalance: normalizedAccountSize,
      equityBalance: normalizedAccountSize,
      balanceMode: "EQUITY",
      balanceTracksPnl: false,
      realizedPnl: 0,
      unrealizedPnl: 0,
      returnPct: 0,
      drawdown: 0,
      numberOfTrades: 0,
      openTrades: 0,
      positions: [],
      trades: [],
      orders: [],
    },
  };
}

function stabilizeAccountMetrics(metrics, cachedMetrics, accountId) {
  if (isUsableAccountMetricsSnapshot(metrics, accountId)) {
    return mergeStableAccountMetrics(metrics, cachedMetrics, accountId);
  }
  if (isUsableAccountMetricsSnapshot(cachedMetrics, accountId)) {
    return {
      ...cachedMetrics,
      staleWhileRevalidating: true,
      broker: {
        ...(cachedMetrics.broker || {}),
        staleWhileRevalidating: true,
      },
    };
  }
  return metrics;
}

function mergeStableAccountMetrics(metrics, cachedMetrics, accountId) {
  if (!metrics) return cachedMetrics || metrics;
  const broker = metrics.broker || {};
  const cachedBroker = cachedMetrics?.broker || {};
  const sameAccount = accountMatches({ accountId: broker.accountId || metrics.accountId }, accountId);
  const previousTrades = sameAccount && Array.isArray(cachedBroker.trades) ? cachedBroker.trades : [];
  const incomingTrades = Array.isArray(broker.trades) ? broker.trades : [];
  const incomingOrders = Array.isArray(broker.orders) ? broker.orders : [];
  const mergedTrades = mergeStableBrokerRows(previousTrades, incomingTrades);
  const exactOrders = incomingOrders;
  const historyIncomplete = previousTrades.length > incomingTrades.length;
  const balanceTracksPnl = metricsBalanceTracksPnl(metrics);
  const stableCurrentPnl = historyIncomplete && Number(metrics.currentPnl || 0) === 0
    ? cachedMetrics.currentPnl
    : metrics.currentPnl;
  const stableCurrentBalance = historyIncomplete && !balanceTracksPnl && Number(metrics.currentBalance || 0) <= 0
    ? cachedMetrics.currentBalance
    : metrics.currentBalance;
  const stableRiskCurrentBalance = historyIncomplete && Number(metrics.riskCurrentBalance || metrics.equityBalance || 0) <= 0
    ? cachedMetrics.riskCurrentBalance || cachedMetrics.equityBalance
    : metrics.riskCurrentBalance || metrics.equityBalance;
  const stableDrawdown = historyIncomplete && Number(metrics.drawdown || 0) === 0
    ? cachedMetrics.drawdown
    : metrics.drawdown;
  const numberOfTrades = Math.max(Number(metrics.numberOfTrades || 0), mergedTrades.length);
  return {
    ...metrics,
    currentPnl: stableCurrentPnl,
    currentBalance: stableCurrentBalance,
    riskCurrentBalance: stableRiskCurrentBalance,
    equityBalance: stableRiskCurrentBalance,
    drawdown: stableDrawdown,
    numberOfTrades,
    broker: {
      ...broker,
      currentPnl: stableCurrentPnl,
      currentBalance: stableCurrentBalance,
      riskCurrentBalance: stableRiskCurrentBalance,
      equityBalance: stableRiskCurrentBalance,
      drawdown: stableDrawdown,
      numberOfTrades,
      trades: mergedTrades,
      orders: exactOrders,
    },
  };
}

function mergeStableBrokerRows(previousRows, incomingRows) {
  const merged = new Map();
  (Array.isArray(previousRows) ? previousRows : []).forEach((row, index) => {
    merged.set(stableBrokerRowKey(row, index), row);
  });
  (Array.isArray(incomingRows) ? incomingRows : []).forEach((row, index) => {
    const key = stableBrokerRowKey(row, index);
    merged.set(key, { ...(merged.get(key) || {}), ...row });
  });
  return Array.from(merged.values()).sort((first, second) => {
    return (parseChartTime(second?.createdAt || second?.updatedAt) || 0) - (parseChartTime(first?.createdAt || first?.updatedAt) || 0);
  });
}

function stableBrokerRowKey(row, index) {
  return [
    row?.accountId || "",
    row?.id || "",
    row?.orderId || row?.brokerOrderId || "",
    row?.contractId || "",
    row?.symbol || "",
    row?.side || "",
    row?.createdAt || row?.updatedAt || index,
  ].map((part) => String(part ?? "").trim()).join("|");
}

function isUsableAccountMetricsSnapshot(metrics, accountId) {
  if (!metrics?.broker) return false;
  const broker = metrics.broker || {};
  if (broker.brokerAccountMatched === false) return false;
  const brokerAccountId = String(broker.accountId || metrics.accountId || "").trim();
  if (accountId && brokerAccountId && brokerAccountId !== String(accountId).trim()) return false;
  return broker.success === true;
}

function accountMetricsSignature(metrics) {
  if (!metrics?.broker) return "";
  const broker = metrics.broker;
  const rowKeys = (rows) => (Array.isArray(rows) ? rows : []).map(stableBrokerRowKey).join(",");
  return [
    broker.accountId || "",
    Number(metrics.currentPnl || 0).toFixed(2),
    Number(metrics.currentBalance || 0).toFixed(2),
    Number(metrics.drawdown || 0).toFixed(2),
    rowKeys(broker.positions),
    rowKeys(broker.trades),
    rowKeys(broker.orders),
  ].join("::");
}

function stabilizeEquityReviewStatus(current, next) {
  if (!next) return current || next;
  const now = Date.now();
  const currentTone = String(current?.healthTone || "");
  const nextTone = String(next.healthTone || "");
  if (nextTone === "ok" || nextTone === "idle" || nextTone === "error") {
    return { ...next, updatedAt: now, pendingWarnAt: 0 };
  }
  if (nextTone === "warn" && currentTone === "ok") {
    const pendingWarnAt = current?.pendingWarnAt || now;
    if (now - pendingWarnAt < HEALTH_WARN_HOLD_MS) {
      return {
        ...current,
        pendingWarnAt,
        suppressedHealthLabel: next.healthLabel,
        suppressedIssueCount: next.issueCount,
      };
    }
  }
  return { ...next, updatedAt: now, pendingWarnAt: 0 };
}

function filterByAccountId(rows, accountId) {
  const cleanAccountId = String(accountId || "").trim();
  const list = Array.isArray(rows) ? rows : [];
  if (!cleanAccountId) return list;
  return list.filter((row) => String(row?.accountId || "").trim() === cleanAccountId);
}

function buildLocalTradeProvenance(decisions = [], orders = [], accountId = "") {
  const cleanAccountId = String(accountId || "").trim();
  const provenance = [];
  (Array.isArray(decisions) ? decisions : []).forEach((decision) => {
    if (!accountMatches(decision, cleanAccountId)) return;
    if (!isEntryDecision(decision)) return;
    const strategyCode = usableStrategyCode(decision.strategyCode);
    if (!strategyCode) return;
    provenance.push({
      ...decision,
      strategyCode,
      strategyName: decision.strategyName,
      side: String(decision.side || "").toUpperCase(),
      accountId: decision.accountId,
      brokerOrderId: decision.brokerOrderId || decision.orderId || "",
      customTag: decision.customTag || "",
      source: "decision",
    });
  });
  (Array.isArray(orders) ? orders : []).forEach((order) => {
    if (!accountMatches(order, cleanAccountId)) return;
    const strategyCode = usableStrategyCode(order.strategyCode);
    if (!strategyCode) return;
    const orderType = String(order.orderType || "").toUpperCase();
    if (orderType.includes("CLOSE")) return;
    const entrySide = normalizeTopstepTradeSide(order.side);
    const side = entrySide ? positionSideFromEntrySide(entrySide) : String(order.side || "").toUpperCase();
    provenance.push({
      ...order,
      strategyCode,
      strategyName: order.strategyName,
      side,
      signalTime: order.signalTime || order.entryTime || order.createdAt,
      entryTime: order.entryTime || order.createdAt,
      brokerOrderId: order.brokerOrderId || order.orderId || "",
      customTag: order.customTag || "",
      status: order.status || "SUBMITTED_TOPSTEPX",
      source: "order",
    });
  });
  return provenance;
}

function accountMatches(row, accountId) {
  const cleanAccountId = String(accountId || "").trim();
  if (!cleanAccountId) return true;
  return String(row?.accountId || "").trim() === cleanAccountId;
}

function usableStrategyCode(value) {
  const code = String(value || "").trim().toUpperCase();
  if (!code || code === "TOPSTEP" || code === "UNTRACKED") return "";
  return code;
}

function finiteNumberOrNull(value) {
  if (value == null || value === "") return null;
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : null;
}

function tradeCostTotal(row) {
  const explicitTotal = finiteNumberOrNull(row?.totalFees);
  if (explicitTotal != null) return explicitTotal;
  const fees = finiteNumberOrNull(row?.fees ?? row?.brokerFees);
  const commission = finiteNumberOrNull(row?.commission ?? row?.commissions);
  if (fees == null && commission == null) return null;
  return (fees || 0) + (commission || 0);
}

function buildBrokerOpenTradeRows(positions, provenance = []) {
  return (Array.isArray(positions) ? positions : [])
    .filter((position) => Number(position?.contracts || 0) > 0)
    .map((position, index) => {
      const symbol = String(position.symbol || "").toUpperCase();
      const side = String(position.side || "").toUpperCase() === "SHORT" ? "SHORT" : "LONG";
      const contracts = Number(position.contracts || 0);
      const entryPrice = Number(position.entryPrice || position.averagePrice || 0);
      const matchedDecision = findBrokerDecisionMeta(provenance, {
        symbol,
        side,
        contracts,
        entryPrice,
        entryTime: position.createdAt,
        createdAt: position.createdAt,
        orderId: position.orderId || position.brokerOrderId,
        customTag: position.customTag,
      });
      return {
        id: brokerStableRowId("topstep-position", position.accountId, symbol, side, position.id || position.contractId || position.createdAt || index),
        symbol,
        accountId: position.accountId,
        strategyCode: tradeStrategyCode(matchedDecision, "UNTRACKED"),
        strategyName: matchedDecision?.strategyName || "Untracked Broker Position",
        side,
        contracts,
        entryPrice,
        exitPrice: 0,
        pnl: Number(position.displayPnl ?? position.markPnl ?? position.unrealizedPnl ?? position.pnl ?? 0),
        status: "LIVE_TOPSTEP",
        tradeReason: matchedDecision?.tradeReason || {},
        entryReasoning: matchedDecision?.entryReasoning || matchedDecision?.tradeReason?.entry || {},
        exitReasoning: matchedDecision?.exitReasoning || matchedDecision?.tradeReason?.exit || {},
        entryReason: tradeReasonEntryText(matchedDecision?.tradeReason) || matchedDecision?.entryReason || buildEntryReason({
          strategyCode: matchedDecision?.strategyCode,
          strategyName: matchedDecision?.strategyName,
          side,
          contracts,
          entryPrice,
          stopPrice: matchedDecision?.stopPrice,
          targetPrice: matchedDecision?.targetPrice,
          signalTime: matchedDecision?.signalTime,
          fallback: unmatchedBrokerEntryReason("open position"),
        }),
        exitReason: "",
        structuredExitReason: matchedDecision?.structuredExitReason || tradeReasonExitText(matchedDecision?.tradeReason),
        dtmDetails: tradeDtmDetailsText(matchedDecision),
        fees: null,
        stopPrice: finiteNumberOrNull(matchedDecision?.stopPrice),
        targetPrice: finiteNumberOrNull(matchedDecision?.targetPrice),
        signalTime: matchedDecision?.signalTime || position.createdAt,
        reason: "Open position from Topstep Position/searchOpen; mark PnL updates from live ProjectX price.",
        entryTime: position.createdAt,
        createdAt: position.createdAt,
        brokerOrderId: position.brokerOrderId || position.orderId || "",
        orderId: position.orderId || position.brokerOrderId || "",
        customTag: position.customTag || "",
      };
    });
}

function buildBrokerClosedTradeRows(trades, provenance = []) {
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
          accountId: trade.accountId,
          orderId: trade.orderId || trade.brokerOrderId,
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
    let entryOrderId = "";
    lots.forEach((lot) => {
      if (remaining <= 0 || lot.remaining <= 0 || lot.side === tradeSide) return;
      const take = Math.min(lot.remaining, remaining);
      lot.remaining -= take;
      remaining -= take;
      matchedContracts += take;
      weightedEntry += lot.price * take;
      entryTime = entryTime || lot.createdAt;
      entrySide = entrySide || lot.side;
      entryOrderId = entryOrderId || lot.orderId;
    });
    openLots.set(key, lots.filter((lot) => lot.remaining > 0));

    const fees = tradeCostTotal(trade);
    const entryPrice = matchedContracts > 0 ? weightedEntry / matchedContracts : 0;
    const rowContracts = matchedContracts || contracts;
    const positionSide = entrySide ? positionSideFromEntrySide(entrySide) : positionSideFromClosingSide(tradeSide);
    const matchedDecision = findBrokerDecisionMeta(provenance, {
      symbol,
      side: positionSide,
      contracts: rowContracts,
      entryPrice,
      entryTime,
      createdAt: trade.createdAt,
      orderId: entryOrderId || trade.orderId || trade.brokerOrderId,
      customTag: trade.customTag,
      closed: true,
    });
    const reason = entryPrice > 0
      ? "Topstep Trade/search paired the entry and close fills."
      : "Topstep Trade/search reported a close fill with no entry fill in the current broker window.";
    const pnlFields = mergeTradeCachePnlFields(
      {
        pnl: Number(trade.pnl || 0),
        finalLegPnl: Number(trade.pnl || 0),
        dtmRealizedPnl: matchedDecision?.dtmRealizedPnl,
        dtmPartialContractsClosed: matchedDecision?.dtmPartialContractsClosed,
      },
      matchedDecision || {}
    );
    rows.push({
      id: brokerStableRowId("topstep-trade", trade.accountId, symbol, positionSide, entryTime || trade.createdAt, trade.id || trade.orderId || index),
      symbol,
      accountId: trade.accountId,
      strategyCode: tradeStrategyCode(matchedDecision, "UNTRACKED"),
      strategyName: matchedDecision?.strategyName || "Untracked Broker Trade",
      side: positionSide,
      contracts: rowContracts,
      entryPrice,
      exitPrice: fillPrice,
      ...pnlFields,
      status: "SOLD_TOPSTEP",
      tradeReason: matchedDecision?.tradeReason || {},
      entryReasoning: matchedDecision?.entryReasoning || matchedDecision?.tradeReason?.entry || {},
      exitReasoning: matchedDecision?.exitReasoning || matchedDecision?.tradeReason?.exit || {},
      entryReason: tradeReasonEntryText(matchedDecision?.tradeReason) || matchedDecision?.entryReason || buildEntryReason({
        strategyCode: matchedDecision?.strategyCode,
        strategyName: matchedDecision?.strategyName,
        side: positionSide,
        contracts: rowContracts,
        entryPrice,
        stopPrice: matchedDecision?.stopPrice,
        targetPrice: matchedDecision?.targetPrice,
        signalTime: matchedDecision?.signalTime,
        fallback: entryPrice > 0
          ? unmatchedBrokerEntryReason("closed trade")
          : "Topstep reported the close fill, but the matching entry fill is outside the current broker sync window.",
      }),
      exitReason: tradeReasonExitText(matchedDecision?.tradeReason) || matchedDecision?.structuredExitReason || buildExitReason({
        strategyCode: matchedDecision?.strategyCode,
        strategyName: matchedDecision?.strategyName,
        exitReason: matchedDecision?.exitReason,
        fillPrice,
        fallback: matchedDecision
          ? "Broker close fill matched the saved strategy entry, but the local close reason is still syncing."
          : "Topstep closed this trade, but no local strategy exit provenance matched this account.",
      }),
      structuredExitReason: matchedDecision?.structuredExitReason || tradeReasonExitText(matchedDecision?.tradeReason),
      dtmDetails: tradeDtmDetailsText(matchedDecision),
      fees,
      brokerFees: finiteNumberOrNull(trade.brokerFees ?? trade.fees),
      commission: finiteNumberOrNull(trade.commission ?? trade.commissions),
      totalFees: fees,
      stopPrice: finiteNumberOrNull(matchedDecision?.stopPrice),
      targetPrice: finiteNumberOrNull(matchedDecision?.targetPrice),
      signalTime: matchedDecision?.signalTime || entryTime,
      reason,
      entryTime: entryTime || trade.createdAt,
      createdAt: trade.createdAt,
      brokerOrderId: entryOrderId || trade.orderId || trade.brokerOrderId || "",
      orderId: trade.orderId || trade.brokerOrderId || entryOrderId || "",
      customTag: trade.customTag || "",
    });
  });
  return rows.sort((first, second) => (parseChartTime(second.createdAt) || 0) - (parseChartTime(first.createdAt) || 0));
}

function buildLocalClosedTradeCacheRows(trades, accountId = "") {
  const cleanAccountId = String(accountId || "").trim();
  return (Array.isArray(trades) ? trades : [])
    .filter((trade) => isEntryDecision(trade) && isClosedTradeDecision(trade) && shouldCreateLocalClosedTradeCacheRow(trade))
    .map((trade, index) => {
      const strategyCode = usableStrategyCode(trade.strategyCode);
      const symbol = String(trade.symbol || "").toUpperCase();
      if (!strategyCode || !symbol || !accountMatches(trade, cleanAccountId)) return null;
      const side = String(trade.side || "").toUpperCase() === "SHORT" ? "SHORT" : "LONG";
      const entryTime = trade.entryTime || trade.signalTime || trade.createdAt || "";
      const exitTime = trade.exitTime || trade.closedAt || trade.updatedAt || trade.createdAt || entryTime;
      const entryPrice = Number(trade.entryPrice || 0);
      const exitPrice = Number(trade.exitPrice || trade.currentPrice || 0);
      const contracts = Number(trade.contracts || 0);
      if (entryPrice <= 0 || contracts <= 0) return null;
      return {
        id: brokerStableRowId("local-trade-cache", cleanAccountId || trade.accountId, symbol, side, entryTime, trade.closedDecisionId || trade.id || index),
        cacheSource: "local-decision",
        symbol,
        accountId: cleanAccountId || trade.accountId || "",
        strategyCode,
        strategyName: trade.strategyName || liveStrategyNameFallback(strategyCode),
        side,
        contracts,
        entryPrice,
        exitPrice,
        pnl: composeTradeCacheRealizedPnl(trade),
        finalLegPnl: finiteNumberOrNull(trade.finalLegPnl),
        dtmRealizedPnl: finiteNumberOrNull(trade.dtmRealizedPnl),
        dtmPartialContractsClosed: finiteNumberOrNull(trade.dtmPartialContractsClosed),
        status: "SOLD_TOPSTEP",
        tradeReason: trade.tradeReason || {},
        entryReasoning: trade.entryReasoning || trade.tradeReason?.entry || {},
        exitReasoning: trade.exitReasoning || trade.tradeReason?.exit || {},
        entryReason: tradeReasonEntryText(trade.tradeReason) || trade.entryReason || trade.reason || "Saved live strategy entry.",
        exitReason: tradeReasonExitText(trade.tradeReason) || trade.structuredExitReason || trade.exitReason || trade.reason || "Saved live strategy close.",
        structuredExitReason: trade.structuredExitReason || tradeReasonExitText(trade.tradeReason),
        dtmDetails: tradeDtmDetailsText(trade),
        reason: trade.reason || "Saved from local live strategy decision history.",
        fees: tradeCostTotal(trade),
        brokerFees: finiteNumberOrNull(trade.brokerFees ?? trade.fees),
        commission: finiteNumberOrNull(trade.commission ?? trade.commissions),
        totalFees: tradeCostTotal(trade),
        stopPrice: finiteNumberOrNull(trade.stopPrice),
        targetPrice: finiteNumberOrNull(trade.targetPrice),
        signalTime: trade.signalTime || entryTime,
        entryTime,
        exitTime,
        createdAt: exitTime,
        brokerOrderId: trade.brokerOrderId || trade.orderId || "",
        orderId: trade.orderId || trade.brokerOrderId || "",
        customTag: trade.customTag || "",
        cachedAt: new Date().toISOString(),
      };
    })
    .filter(Boolean);
}

function mergeBrokerTradeCacheRows(currentRows, cachedRows, options = {}) {
  const cacheRows = compactBrokerTradeCacheRows(cachedRows, options.accountId);
  const includeUnmatchedCached = options.includeUnmatchedCached !== false;
  if (!cacheRows.length) {
    return (Array.isArray(currentRows) ? currentRows : []).slice().sort(compareTradeRowsDesc);
  }
  const cacheIndex = new Map();
  cacheRows.forEach((row) => {
    brokerTradeCacheKeys(row).forEach((key) => {
      if (!cacheIndex.has(key)) cacheIndex.set(key, row);
    });
  });

  const seenCacheIds = new Set();
  const mergedRows = (Array.isArray(currentRows) ? currentRows : []).map((row) => {
    const cachedRow = findCachedBrokerTradeRow(row, cacheRows, cacheIndex);
    if (!cachedRow) return row;
    seenCacheIds.add(brokerTradeCacheIdentity(cachedRow));
    return hydrateBrokerTradeRow(row, cachedRow);
  });

  if (includeUnmatchedCached) {
    cacheRows.forEach((row) => {
      const identity = brokerTradeCacheIdentity(row);
      if (!seenCacheIds.has(identity) && !mergedRows.some((current) => brokerTradeRowsMatch(current, row))) {
        mergedRows.push(row);
      }
    });
  }
  return mergedRows.sort(compareTradeRowsDesc);
}

function compactBrokerTradeCacheRows(rows, accountId = "") {
  const cleanAccountId = String(accountId || "").trim();
  const byKey = new Map();
  (Array.isArray(rows) ? rows : []).forEach((row) => {
    const normalized = normalizeCachedBrokerTradeRow(row, cleanAccountId);
    if (!normalized) return;
    const key = brokerTradeCacheIdentity(normalized);
    const existing = byKey.get(key);
    byKey.set(key, choosePreferredCachedTradeRow(existing, normalized));
  });
  return Array.from(byKey.values()).sort(compareTradeRowsDesc).slice(0, LIVE_TRADE_CACHE_MAX_ROWS);
}

function normalizeCachedBrokerTradeRow(row, accountId = "") {
  const strategyCode = usableStrategyCode(row?.strategyCode);
  const symbol = String(row?.symbol || "").toUpperCase();
  const side = String(row?.side || "").toUpperCase() === "SHORT" ? "SHORT" : "LONG";
  const requiredAccountId = String(accountId || "").trim();
  const rowAccountId = String(row?.accountId || "").trim();
  if (requiredAccountId && rowAccountId !== requiredAccountId) return null;
  const cleanAccountId = rowAccountId || requiredAccountId;
  const entryPrice = finiteNumberOrNull(row?.entryPrice) || 0;
  const exitPrice = finiteNumberOrNull(row?.exitPrice) || 0;
  const contracts = finiteNumberOrNull(row?.contracts) || 0;
  const entryTime = row?.entryTime || row?.signalTime || "";
  const createdAt = row?.createdAt || row?.exitTime || row?.closedAt || row?.updatedAt || entryTime;
  if (!strategyCode || !symbol || !cleanAccountId || contracts <= 0 || (!entryTime && !createdAt)) return null;
  return {
    id: row?.id || brokerStableRowId("cached-trade", cleanAccountId, symbol, side, entryTime || createdAt, roundedTradePrice(entryPrice), roundedTradePrice(exitPrice)),
    cacheSource: row?.cacheSource || "topstep-enriched",
    symbol,
    accountId: cleanAccountId,
    strategyCode,
    strategyName: row?.strategyName || liveStrategyNameFallback(strategyCode),
    side,
    contracts,
    entryPrice,
    exitPrice,
    pnl: composeTradeCacheRealizedPnl(row),
    finalLegPnl: finiteNumberOrNull(row?.finalLegPnl),
    dtmRealizedPnl: finiteNumberOrNull(row?.dtmRealizedPnl),
    dtmPartialContractsClosed: finiteNumberOrNull(row?.dtmPartialContractsClosed),
    status: row?.status || "SOLD_TOPSTEP",
    entryReason: row?.entryReason || "",
    exitReason: row?.exitReason || "",
    structuredExitReason: row?.structuredExitReason || "",
    dtmDetails: row?.dtmDetails || tradeDtmDetailsText(row),
    tradeReason: normalizeTradeReasonPayload(row?.tradeReason),
    entryReasoning: normalizeReasonSection(row?.entryReasoning || row?.tradeReason?.entry),
    exitReasoning: normalizeReasonSection(row?.exitReasoning || row?.tradeReason?.exit),
    reason: row?.reason || "",
    fees: tradeCostTotal(row),
    brokerFees: finiteNumberOrNull(row?.brokerFees ?? row?.fees),
    commission: finiteNumberOrNull(row?.commission ?? row?.commissions),
    totalFees: tradeCostTotal(row),
    stopPrice: finiteNumberOrNull(row?.stopPrice),
    targetPrice: finiteNumberOrNull(row?.targetPrice),
    signalTime: row?.signalTime || entryTime,
    entryTime,
    exitTime: row?.exitTime || row?.closedAt || row?.updatedAt || createdAt,
    createdAt,
    brokerOrderId: row?.brokerOrderId || row?.orderId || "",
    orderId: row?.orderId || row?.brokerOrderId || "",
    customTag: row?.customTag || "",
    cachedAt: row?.cachedAt || new Date().toISOString(),
  };
}

function choosePreferredCachedTradeRow(existing, next) {
  if (!existing) return next;
  const existingSource = String(existing.cacheSource || "");
  const nextSource = String(next.cacheSource || "");
  if (existingSource === "local-decision" && nextSource !== "local-decision") {
    return hydrateBrokerTradeRow(next, existing);
  }
  if (nextSource === "local-decision" && existingSource !== "local-decision") {
    return hydrateBrokerTradeRow(existing, next);
  }
  return tradeSortTimestamp(next) >= tradeSortTimestamp(existing) ? hydrateBrokerTradeRow(next, existing) : hydrateBrokerTradeRow(existing, next);
}

function hydrateBrokerTradeRow(row, cachedRow) {
  const currentCode = usableStrategyCode(row?.strategyCode);
  const cachedCode = usableStrategyCode(cachedRow?.strategyCode);
  if (!cachedCode) return row;
  const useCachedStrategy = !currentCode;
  const mergedTradeReason = mergeTradeReasonPayloads(cachedRow?.tradeReason, row?.tradeReason);
  const entryReason = useCachedStrategy || isUntrackedReason(row?.entryReason)
    ? tradeReasonEntryText(mergedTradeReason) || cachedRow.entryReason || row?.entryReason
    : tradeReasonEntryText(mergedTradeReason) || row?.entryReason || cachedRow.entryReason;
  const exitReason = useCachedStrategy || isUntrackedReason(row?.exitReason)
    ? tradeReasonExitText(mergedTradeReason) || cachedRow.exitReason || row?.exitReason
    : tradeReasonExitText(mergedTradeReason) || row?.exitReason || cachedRow.exitReason;
  const pnlFields = mergeTradeCachePnlFields(row, cachedRow);
  return {
    ...cachedRow,
    ...row,
    ...pnlFields,
    strategyCode: currentCode || cachedCode,
    strategyName: currentCode ? row.strategyName || cachedRow.strategyName : cachedRow.strategyName || row?.strategyName,
    tradeReason: mergedTradeReason,
    entryReasoning: normalizeReasonSection(row?.entryReasoning || cachedRow?.entryReasoning || mergedTradeReason.entry),
    exitReasoning: normalizeReasonSection(row?.exitReasoning || cachedRow?.exitReasoning || mergedTradeReason.exit),
    entryReason,
    exitReason,
    structuredExitReason: row?.structuredExitReason || cachedRow?.structuredExitReason || tradeReasonExitText(mergedTradeReason),
    dtmDetails: row?.dtmDetails || cachedRow?.dtmDetails || tradeDtmDetailsText({ ...row, tradeReason: mergedTradeReason }),
    reason: useCachedStrategy && cachedRow.reason ? cachedRow.reason : row?.reason || cachedRow.reason,
    stopPrice: finiteNumberOrNull(row?.stopPrice) ?? cachedRow.stopPrice,
    targetPrice: finiteNumberOrNull(row?.targetPrice) ?? cachedRow.targetPrice,
    signalTime: row?.signalTime || cachedRow.signalTime,
    brokerOrderId: row?.brokerOrderId || cachedRow.brokerOrderId,
    orderId: row?.orderId || cachedRow.orderId,
    customTag: row?.customTag || cachedRow.customTag,
    cacheSource: hydratedTradeCacheSource(row, cachedRow),
    cachedAt: cachedRow.cachedAt || row?.cachedAt,
  };
}

function hydratedTradeCacheSource(row, cachedRow) {
  const rowSource = String(row?.cacheSource || "").trim();
  if (rowSource) return rowSource;
  const cachedSource = String(cachedRow?.cacheSource || "").trim();
  return cachedSource && cachedSource !== "local-decision" ? cachedSource : "topstep-enriched";
}

function findCachedBrokerTradeRow(row, cacheRows, cacheIndex) {
  for (const key of brokerTradeCacheKeys(row)) {
    if (cacheIndex.has(key)) return cacheIndex.get(key);
  }
  const rowAccountId = String(row?.accountId || "").trim();
  const rowSymbol = String(row?.symbol || "").toUpperCase();
  const rowSide = String(row?.side || "").toUpperCase();
  const rowContracts = Number(row?.contracts || 0);
  const rowEntry = Number(row?.entryPrice || 0);
  const rowExit = Number(row?.exitPrice || 0);
  const rowEntryTime = parseChartTime(row?.entryTime || row?.signalTime);
  const rowCloseTime = parseChartTime(row?.createdAt || row?.exitTime || row?.closedAt);
  const tick = instrumentTickSize(rowSymbol);
  let bestRow = null;
  let bestScore = Infinity;
  cacheRows.forEach((cachedRow) => {
    if (String(cachedRow.accountId || "").trim() !== rowAccountId) return;
    if (String(cachedRow.symbol || "").toUpperCase() !== rowSymbol) return;
    if (String(cachedRow.side || "").toUpperCase() !== rowSide) return;
    const contractPenalty = rowContracts > 0 && Number(cachedRow.contracts || 0) > 0 && rowContracts !== Number(cachedRow.contracts || 0) ? 12 : 0;
    const cachedEntryTime = parseChartTime(cachedRow.entryTime || cachedRow.signalTime);
    const cachedCloseTime = parseChartTime(cachedRow.createdAt || cachedRow.exitTime || cachedRow.closedAt);
    const entryMinutes = rowEntryTime && cachedEntryTime ? Math.abs(rowEntryTime - cachedEntryTime) / 60000 : 60;
    const closeMinutes = rowCloseTime && cachedCloseTime ? Math.abs(rowCloseTime - cachedCloseTime) / 60000 : 60;
    const entryTicks = rowEntry > 0 && Number(cachedRow.entryPrice || 0) > 0
      ? Math.abs(rowEntry - Number(cachedRow.entryPrice || 0)) / Math.max(tick, 0.01)
      : 0;
    const exitTicks = rowExit > 0 && Number(cachedRow.exitPrice || 0) > 0
      ? Math.abs(rowExit - Number(cachedRow.exitPrice || 0)) / Math.max(tick, 0.01)
      : 0;
    const score = Math.min(entryMinutes, closeMinutes) + entryTicks * 1.5 + exitTicks + contractPenalty;
    if (score < bestScore) {
      bestScore = score;
      bestRow = cachedRow;
    }
  });
  return bestScore <= 45 ? bestRow : null;
}

function brokerTradeRowsMatch(first, second) {
  return brokerTradeCacheKeys(first).some((key) => brokerTradeCacheKeys(second).includes(key));
}

function brokerTradeCacheIdentity(row) {
  return brokerTradeCacheKeys(row)[0] || brokerStableRowId(
    "trade-cache-row",
    row?.accountId,
    row?.symbol,
    row?.side,
    row?.entryTime || row?.createdAt,
    row?.id
  );
}

function brokerTradeCacheKeys(row) {
  const accountId = String(row?.accountId || "").trim();
  const symbol = String(row?.symbol || "").toUpperCase();
  const side = String(row?.side || "").toUpperCase();
  const contracts = Number(row?.contracts || 0);
  const entryPrice = roundedTradePrice(row?.entryPrice);
  const exitPrice = roundedTradePrice(row?.exitPrice);
  const entryBucket = tradeTimeBucket(row?.entryTime || row?.signalTime);
  const closeBucket = tradeTimeBucket(row?.createdAt || row?.exitTime || row?.closedAt);
  const orderId = String(row?.brokerOrderId || row?.orderId || "").trim();
  const customTag = String(row?.customTag || "").trim();
  return [
    row?.id ? `id:${row.id}` : "",
    orderId ? `order:${accountId}|${orderId}` : "",
    customTag ? `tag:${accountId}|${customTag}` : "",
    accountId && symbol && side && entryBucket && entryPrice ? `entry:${accountId}|${symbol}|${side}|${contracts}|${entryBucket}|${entryPrice}` : "",
    accountId && symbol && side && closeBucket && exitPrice ? `close:${accountId}|${symbol}|${side}|${contracts}|${closeBucket}|${exitPrice}` : "",
    accountId && symbol && side && entryBucket && closeBucket ? `time:${accountId}|${symbol}|${side}|${contracts}|${entryBucket}|${closeBucket}` : "",
  ].filter(Boolean);
}

function brokerTradeCacheSignature(rows) {
  return (Array.isArray(rows) ? rows : [])
    .map((row) => [
      brokerTradeCacheIdentity(row),
      usableStrategyCode(row?.strategyCode),
      row?.strategyName || "",
      row?.pnl ?? "",
      row?.entryReason || "",
      row?.exitReason || "",
      row?.createdAt || "",
    ].join("::"))
    .join("||");
}

function readLocalTradeCacheRows(accountId) {
  if (typeof window === "undefined") return [];
  try {
    const raw = window.localStorage.getItem(liveTradeCacheStorageKey(accountId));
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    const cleanAccountId = String(accountId || "").trim();
    if (cleanAccountId && String(parsed?.accountId || "").trim() !== cleanAccountId) return [];
    return Array.isArray(parsed?.rows) ? parsed.rows : [];
  } catch {
    return [];
  }
}

function writeLocalTradeCacheRows(accountId, rows) {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(liveTradeCacheStorageKey(accountId), JSON.stringify({
      version: LIVE_TRADE_CACHE_VERSION,
      accountId: String(accountId || "").trim(),
      updatedAt: new Date().toISOString(),
      rows: compactBrokerTradeCacheRows(rows, accountId),
    }));
  } catch {
    // Browser storage is a fallback only; the backend JSON cache remains authoritative.
  }
}

function liveTradeCacheStorageKey(accountId) {
  return `futures-live-trade-cache:v${LIVE_TRADE_CACHE_VERSION}:${String(accountId || "default").trim()}`;
}

function roundedTradePrice(value) {
  const numeric = Number(value || 0);
  return Number.isFinite(numeric) && numeric > 0 ? numeric.toFixed(2) : "";
}

function tradeTimeBucket(value) {
  const timestamp = parseChartTime(value);
  if (!timestamp) return "";
  return String(Math.floor(timestamp / (5 * 60 * 1000)));
}

function compareTradeRowsDesc(first, second) {
  return (tradeSortTimestamp(second) || 0) - (tradeSortTimestamp(first) || 0);
}

function isUntrackedReason(value) {
  return String(value || "").toUpperCase().includes("UNTRACKED");
}

function liveStrategyNameFallback(strategyCode) {
  const code = String(strategyCode || "").trim();
  return code ? code.replace(/_/g, " ") : "";
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

function brokerStableRowId(prefix, ...parts) {
  return [prefix, ...parts]
    .map((part) => String(part ?? "").trim().replace(/\s+/g, "_"))
    .filter(Boolean)
    .join("|");
}

function unmatchedBrokerEntryReason(rowType) {
  return `UNTRACKED: Topstep supplied this ${rowType}, but no saved live strategy/order record matched this account fill.`;
}

function tradeStrategyCode(decision, fallback = "UNTRACKED") {
  const code = usableStrategyCode(decision?.strategyCode);
  return code || fallback;
}

function findBrokerDecisionMeta(decisions, target) {
  const symbol = String(target?.symbol || "").toUpperCase();
  const side = String(target?.side || "").toUpperCase();
  if (!symbol || !side) return null;
  const targetOrderId = String(target?.orderId || target?.brokerOrderId || "").trim();
  const targetCustomTag = String(target?.customTag || "").trim();
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
    const strategyCode = usableStrategyCode(decision.strategyCode);
    if (!strategyCode) return;
    const decisionOrderId = String(decision.brokerOrderId || decision.orderId || "").trim();
    const decisionCustomTag = String(decision.customTag || "").trim();
    const decisionTime = parseChartTime(decision.entryTime || decision.signalTime || decision.createdAt);
    const decisionPrice = Number(decision.entryPrice || 0);
    const decisionContracts = Number(decision.contracts || 0);
    const timeMinutes = targetTime && decisionTime ? Math.abs(targetTime - decisionTime) / 60000 : 999;
    const priceTicks = targetPrice > 0 && decisionPrice > 0 ? Math.abs(targetPrice - decisionPrice) / Math.max(tick, 0.01) : 24;
    const contractPenalty = targetContracts > 0 && decisionContracts > 0 && targetContracts !== decisionContracts ? 18 : 0;
    const orderMatchBonus = targetOrderId && decisionOrderId && brokerOrderIdsRelated(decisionOrderId, targetOrderId) ? -10000 : 0;
    const customTagBonus = targetCustomTag && decisionCustomTag && targetCustomTag.startsWith(decisionCustomTag) ? -9000 : 0;
    const score = orderMatchBonus + customTagBonus + timeMinutes + priceTicks * 2 + contractPenalty;
    if (score < bestScore) {
      bestScore = score;
      best = decision;
    }
  });
  if (bestScore > 90) return null;
  return target?.closed ? enrichBrokerDecisionWithExit(best, decisions) : best;
}

function brokerOrderIdsRelated(entryOrderId, targetOrderId) {
  const entry = String(entryOrderId || "").trim();
  const target = String(targetOrderId || "").trim();
  if (!entry || !target) return false;
  if (entry === target) return true;
  const entryNumber = Number(entry);
  const targetNumber = Number(target);
  return Number.isFinite(entryNumber)
    && Number.isFinite(targetNumber)
    && targetNumber > entryNumber
    && targetNumber <= entryNumber + 4;
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
    finalLegPnl: exit.finalLegPnl,
    dtmRealizedPnl: exit.dtmRealizedPnl,
    dtmPartialContractsClosed: exit.dtmPartialContractsClosed,
    exitPrice: exit.exitPrice,
    tradeReason: mergeTradeReasonPayloads(decision.tradeReason, exit.tradeReason),
    entryReasoning: decision.entryReasoning || decision.tradeReason?.entry || {},
    exitReasoning: exit.exitReasoning || exit.tradeReason?.exit || {},
    entryReason: decision.entryReason || tradeReasonEntryText(decision.tradeReason),
    exitReason: exit.structuredExitReason || tradeReasonExitText(exit.tradeReason) || exit.exitReason || exit.reason,
    structuredExitReason: exit.structuredExitReason || tradeReasonExitText(exit.tradeReason),
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
    Number(entryPrice || 0) > 0 ? `Entry: ${formatPrice(entryPrice)}` : "",
    Number(stopPrice || 0) > 0 ? `Stop: ${formatPrice(stopPrice)}` : "",
    Number(targetPrice || 0) > 0 ? `Target: ${formatPrice(targetPrice)}` : "",
  ].filter(Boolean).join("; ");
  const sizeText = Number(contracts || 0) > 0 ? `${contracts} contract${Number(contracts) === 1 ? "" : "s"}` : "live size";
  const timeText = signalTime ? `; Signal: ${formatEstTime(signalTime)}` : "";
  return `${readableName} wanted a ${direction} because ${thesis}. Size: ${sizeText}${pricePlan ? `; ${pricePlan}` : ""}${timeText}.`;
}

function strategyEntryThesis(strategyCode, direction) {
  const code = String(strategyCode || "").toUpperCase();
  const dir = direction === "short" ? "downside" : "upside";
  const map = {
    AFT: `afternoon continuation filters for ${dir} trend pressure`,
    CMOM: "late-session momentum and close-guard filters",
    ECHO: "delayed confirmation from a prior micro signal",
    FVG: "fair-value-gap retest and continuation filters",
    IPB: "impulse pullback continuation filters",
    KELT: "Keltner trend scalp filters",
    KREV: "Keltner rejection and mean-reversion filters",
    LORB: "late opening-range continuation filters",
    MIM: "market momentum breadth filters",
    MRVWAP: "VWAP reversion filters",
    MSCALP: "micro impulse scalp filters",
    OMOM: "opening momentum and volume filters",
    ORB: "opening-range breakout filters",
    ORB2: "opening-range retest continuation filters",
    PDB: "prior-day breakout continuation filters",
    RCB: "compression breakout filters",
    SHDW: "larger-contract shadow confirmation filters",
    SWEEP: "liquidity sweep and reclaim filters",
    SWEEP2: "confirmed prior-day sweep filters",
    TLAD: "trend ladder continuation filters",
    VPB: "value-area reclaim filters",
    VRCL: "VWAP reclaim continuation filters",
    VWAP: "VWAP pullback continuation filters",
    WFT: "winner follow-through continuation filters",
  };
  return map[code] || `the configured strategy filters for ${dir} expectancy`;
}

function buildExitReason({ strategyCode, strategyName, exitReason, fillPrice, fallback }) {
  const code = String(strategyCode || "").trim();
  const cleanedExit = String(exitReason || "").trim();
  const priceText = Number(fillPrice || 0) > 0 ? ` at ${formatPrice(fillPrice)}` : "";
  if (!code || code.toUpperCase() === "TOPSTEP" || code.toUpperCase() === "UNTRACKED") {
    return fallback || `Closed by Topstep fill${priceText}.`;
  }
  const readableName = strategyName && strategyName !== code ? `${code} (${strategyName})` : code;
  if (cleanedExit) {
    return `Exit: ${readableName}; Reason: ${compactExitReason(cleanedExit)}${priceText}.`;
  }
  return `Exit: ${readableName}; Reason: broker fill${priceText}; Sync: pending local strategy reason.`;
}

function compactExitReason(reason) {
  const cleaned = String(reason || "").trim().replace(/\s+/g, " ");
  if (!cleaned) return "strategy exit fired";
  if (cleaned.length <= 96) return cleaned;
  return `${cleaned.slice(0, 93).trim()}...`;
}

function buildSymbolTrackers({ symbols, states, decisions, marketData, brokerPositions, brokerClosedTrades, brokerAuthoritative, botStarted }) {
  if (!botStarted) {
    return (Array.isArray(symbols) ? symbols : DEFAULT_SYMBOLS).map(idleSymbolTracker);
  }
  const stateBySymbol = new Map((Array.isArray(states) ? states : []).map((state) => [String(state.symbol || "").toUpperCase(), state]));
  const brokerBySymbol = buildBrokerPositionMap(brokerPositions);
  const closedBrokerBySymbol = buildBrokerClosedTradeMap(brokerClosedTrades);
  const decisionRows = Array.isArray(decisions) ? decisions : [];
  return (Array.isArray(symbols) ? symbols : DEFAULT_SYMBOLS).map((symbol) => {
    const normalizedSymbol = String(symbol || "").toUpperCase();
    const state = stateBySymbol.get(normalizedSymbol) || null;
    const brokerPosition = brokerBySymbol.get(normalizedSymbol) || null;
    const candles = Array.isArray(marketData?.[normalizedSymbol]) ? marketData[normalizedSymbol] : [];
    const sessionCandles = chartCandlesForMonitorTimeline(candles.map(normalizeCandle).filter((candle) => candle.time && Number(candle.close || 0) > 0));
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
    const liveTradeCount = brokerAuthoritative ? brokerLiveTrades : liveTrades.length;
    const totalTrades = brokerAuthoritative
      ? brokerClosed.count + brokerLiveTrades
      : trades.length;
    const pnl = brokerAuthoritative
      ? Number(brokerClosed.pnl || 0) + Number(brokerPosition?.unrealizedPnl ?? brokerPosition?.pnl ?? 0)
      : localPnl;
    const signal = trackerSignalLabel(state, liveTradeCount, botStarted);
    const health = trackerHealthLabel(state, botStarted);
    const healthStatusText = trackerHealthStatusText(state, health, signal, botStarted, lastPrice, brokerLiveTrades > 0);
    const orderFlow = state?.orderFlow || {};
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
      healthStatusText,
      orderFlow,
      orderFlowLabel: orderFlowTrackerLabel(orderFlow),
      detail: brokerLiveTrades > 0 ? "Topstep open position verified; PnL is marked from live price." : trackerDetail(state, signal, botStarted, lastPrice),
    };
  });
}

function idleSymbolTracker(symbol) {
  return {
    symbol: String(symbol || "").toUpperCase(),
    lastPrice: 0,
    changePct: 0,
    pnl: 0,
    totalTrades: 0,
    liveTrades: 0,
    signal: "Idle",
    signalTone: "idle",
    healthLabel: "Health Idle",
    healthTone: "idle",
    healthStatusText: "Bot Off",
    orderFlow: {},
    orderFlowLabel: "OF idle",
    detail: "Not started",
  };
}

function orderFlowTrackerLabel(orderFlow) {
  if (!orderFlow?.available) return "OF waiting";
  const imbalance = Number(orderFlow.depthImbalance5 || 0);
  const tape = Number(orderFlow.tapeDelta || 0);
  const spread = Number(orderFlow.spreadTicks || 0);
  const side = imbalance > 0.18 ? "Bid" : imbalance < -0.18 ? "Ask" : "Bal";
  return `${side} ${formatSignedNumber(imbalance, 2)} | Δ ${formatSignedNumber(tape, 0)} | Spr ${spread ? spread.toFixed(1) : "0"}t`;
}

function buildBrokerPositionMap(positions) {
  const map = new Map();
  (Array.isArray(positions) ? positions : []).forEach((position) => {
    const symbol = String(position?.symbol || "").toUpperCase();
    if (!symbol) return;
    const existing = map.get(symbol) || { symbol, contracts: 0, pnl: 0, unrealizedPnl: 0 };
    existing.contracts += Number(position.contracts || 0);
    existing.pnl += Number(position.displayPnl ?? position.markPnl ?? position.pnl ?? 0);
    existing.unrealizedPnl += Number(position.displayPnl ?? position.markPnl ?? position.unrealizedPnl ?? position.pnl ?? 0);
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
  if (errorCode === "FEED_STALE" || errorCode === "MARKET_DATA_STOPPED") {
    return { label: "Health Error", tone: "error", errorCode, detail: "Market Data Stopped" };
  }
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

function trackerHealthStatusText(state, health, signal, botStarted, lastPrice, brokerLivePosition) {
  if (!botStarted) return "Bot Off";
  const errorCode = String(health?.errorCode || state?.errorCode || "").trim().toUpperCase();
  if (["FEED_STOPPED", "FEED_STALE", "MARKET_DATA_STOPPED", "LIVE_CANDLE_MISSING"].includes(errorCode)) {
    return "Market Feed Off";
  }
  if (brokerLivePosition || signal?.tone === "trading") return "Live Position Verified";
  const raw = cleanLogText(state?.analysisStatus || health?.detail || "");
  const normalized = raw.toLowerCase();
  if (!raw || normalized === "not started" || normalized.includes("feed stopped")) return "Market Feed Off";
  if (normalized.includes("market data stopped")) return "Market Feed Off";
  if (normalized.includes("tracking live candles")) return "Tracking Live Candles";
  if (normalized.includes("polling projectx history")) return "Polling ProjectX History";
  if (normalized.includes("waiting for live ticks")) return "Waiting For Live Ticks";
  if (normalized.includes("entry gate") || normalized.includes("market closed")) return "Market Closed";
  if (lastPrice > 0 && health?.tone === "ok") return "Tracking Live Candles";
  return titleCaseStatus(raw);
}

function titleCaseStatus(value) {
  return String(value || "")
    .trim()
    .replace(/\s+/g, " ")
    .split(" ")
    .map((word) => word.length <= 2 ? word.toUpperCase() : `${word.slice(0, 1).toUpperCase()}${word.slice(1).toLowerCase()}`)
    .join(" ");
}

function trackerDetail(state, signal, botStarted, lastPrice) {
  if (!botStarted) return "Not started";
  const errorCode = String(state?.errorCode || "").trim();
  if (errorCode === "FEED_STALE" || errorCode === "MARKET_DATA_STOPPED") {
    return "Market Data Stopped";
  }
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

  const baseDomainMin = domainMin;
  const baseDomainMax = domainMax;
  const baseRange = Math.max(baseDomainMax - baseDomainMin, minimumRange);
  const anchorFencePadding = Math.max(baseRange * 1.1, tick * 20);
  anchorPrices.forEach((price) => {
    const nearDomain = price >= baseDomainMin - anchorFencePadding && price <= baseDomainMax + anchorFencePadding;
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

  const padding = Math.max((domainMax - domainMin) * 0.08, tick * 8);
  return {
    min: domainMin - padding,
    max: domainMax + padding,
  };
}

function tradeTouchesVisibleWindow(trade, candles, toleranceMs = 0) {
  const series = Array.isArray(candles) ? candles : [];
  if (!trade || series.length === 0) return false;
  const firstTime = parseChartTime(series[0]?.time);
  const lastTime = parseChartTime(series[series.length - 1]?.time);
  if (!firstTime || !lastTime) return false;
  const start = Math.min(firstTime, lastTime) - Math.max(0, toleranceMs);
  const end = Math.max(firstTime, lastTime) + Math.max(0, toleranceMs);
  const touchesWindow = (value) => {
    const time = parseChartTime(value);
    return Boolean(time && time >= start && time <= end);
  };
  return touchesWindow(trade.entryTime)
    || touchesWindow(trade.signalTime)
    || touchesWindow(trade.exitTime)
    || touchesWindow(trade.createdAt)
    || touchesWindow(trade.updatedAt);
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
        tradeReason: mergeTradeReasonPayloads(decision.tradeReason, exit.tradeReason),
        entryReasoning: decision.entryReasoning || decision.tradeReason?.entry || {},
        exitReasoning: exit.exitReasoning || exit.tradeReason?.exit || {},
        entryReason: decision.entryReason || tradeReasonEntryText(decision.tradeReason),
        exitReason: exit.structuredExitReason || tradeReasonExitText(exit.tradeReason) || exit.exitReason || exit.reason,
        structuredExitReason: exit.structuredExitReason || tradeReasonExitText(exit.tradeReason),
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
    .sort((first, second) => (parseChartTime(first.entryTime || first.signalTime || first.createdAt) || 0) - (parseChartTime(second.entryTime || second.signalTime || second.createdAt) || 0));

  return entries.map((trade) => {
    const exitPrice = Number(trade.exitPrice || 0);
    const status = String(trade.status || "").toUpperCase();
    const closed = exitPrice > 0 || status.includes("EXIT") || status.includes("CLOSED") || status.includes("FLAT") || status.includes("SOLD");
    const markPrice = closed ? exitPrice || Number(trade.entryPrice || 0) : Number(latestPrice || trade.entryPrice || 0);
    const contracts = Number(trade.contracts || 0);
    const entryPrice = Number(trade.entryPrice || 0);
    const managedStopPrice = Number(trade.managedStopPrice || 0);
    const originalStopPrice = Number(trade.originalStopPrice || 0);
    const stopPrice = Number(trade.stopPrice || 0);
    const livePnl = calculateFuturesPnl(selectedSymbol, trade.side, entryPrice, markPrice, contracts);
    const realizedPnl = Number(trade.pnl || 0);
    return {
      id: stringifyChartValue(trade.id),
      symbol: stringifyChartValue(trade.symbol),
      strategyCode: stringifyChartValue(trade.strategyCode),
      side: String(trade.side || "").toUpperCase() === "SHORT" ? "SHORT" : "LONG",
      contracts,
      entryPrice,
      stopPrice: managedStopPrice > 0 ? managedStopPrice : stopPrice,
      originalStopPrice,
      managedStopPrice,
      dtmStopManaged: Boolean(trade.dtmStopManaged || managedStopPrice > 0),
      targetPrice: Number(trade.targetPrice || 0),
      originalTargetPrice: Number(trade.originalTargetPrice || 0),
      managedTargetPrice: Number(trade.managedTargetPrice || 0),
      dtmTargetManaged: Boolean(trade.dtmTargetManaged || Number(trade.managedTargetPrice || 0) > 0),
      entryTime: stringifyChartValue(trade.entryTime || trade.signalTime || trade.createdAt),
      exitTime: stringifyChartValue(trade.exitTime || trade.closedAt || trade.updatedAt || trade.createdAt),
      currentPrice: markPrice,
      closed,
      unrealizedPnl: closed && realizedPnl !== 0 ? realizedPnl : livePnl,
      entryReason: stringifyChartValue(trade.entryReason),
      exitReason: stringifyChartValue(trade.exitReason),
      structuredExitReason: stringifyChartValue(trade.structuredExitReason),
      tradeReason: stringifyChartValue(trade.tradeReason),
      entryReasoning: stringifyChartValue(trade.entryReasoning),
      exitReasoning: stringifyChartValue(trade.exitReasoning),
      dtmDetails: stringifyChartValue(trade.dtmDetails || tradeDtmDetailsText(trade)),
      fees: tradeCostTotal(trade),
    };
  });
}

function stringifyChartValue(value) {
  if (value == null) return "";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (value?.summary) return stringifyChartValue(value.summary);
  if (value?.reason) return stringifyChartValue(value.reason);
  if (value?.text) return stringifyChartValue(value.text);
  if (value?.entryReasonText) return stringifyChartValue(value.entryReasonText);
  if (value?.exitReasonText) return stringifyChartValue(value.exitReasonText);
  try {
    const serialized = JSON.stringify(value);
    return serialized === "{}" ? "" : serialized;
  } catch {
    return "";
  }
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

function mergeStrategyPresets(apiPresets = []) {
  const canonical = new Set(CANONICAL_STRATEGY_PRESETS.map((preset) => preset.name));
  const byName = new Map(CANONICAL_STRATEGY_PRESETS.map((preset) => [preset.name, preset]));
  const presets = Array.isArray(apiPresets) ? apiPresets : [];
  presets.forEach((preset) => {
    const name = String(preset?.name || "").trim();
    if (!canonical.has(name)) return;
    byName.set(name, { ...preset, name, label: byName.get(name)?.label || preset.label || name });
  });
  return Array.from(byName.values());
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
    MYM: { tickSize: 1, tickValue: 0.5 },
    ES: { tickSize: 0.25, tickValue: 12.5 },
    NQ: { tickSize: 0.25, tickValue: 5 },
    MGC: { tickSize: 0.1, tickValue: 1 },
    MCL: { tickSize: 0.01, tickValue: 1 },
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
  if (value === "4h") return 240;
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

function filterTradeRows(trades, filters = DEFAULT_TRADE_FILTERS) {
  const rows = Array.isArray(trades) ? trades : [];
  const nextRows = rows.filter((trade) => {
    const pnl = Number(trade?.pnl ?? 0);
    if (filters.outcome === "profits" && pnl <= 0) return false;
    if (filters.outcome === "losses" && pnl >= 0) return false;
    if (filters.outcome === "flat" && pnl !== 0) return false;
    if (filters.symbol !== "all" && String(trade?.symbol || "").toUpperCase() !== filters.symbol) return false;
    if (filters.side !== "all" && normalizeTradeSideForFilter(trade?.side) !== filters.side) return false;
    if (filters.strategy !== "all" && tradeStrategyFilterValue(trade) !== filters.strategy) return false;
    if (!isTradeWithinDateRange(trade, filters.startDate, filters.endDate)) return false;
    return true;
  });
  nextRows.sort((firstTrade, secondTrade) => {
    const firstPnl = Number(firstTrade?.pnl ?? 0);
    const secondPnl = Number(secondTrade?.pnl ?? 0);
    if (filters.sort === "largestWin") return secondPnl - firstPnl;
    if (filters.sort === "largestLoss") return firstPnl - secondPnl;
    return (tradeSortTimestamp(secondTrade) || 0) - (tradeSortTimestamp(firstTrade) || 0);
  });
  return nextRows;
}

function calculateTradeMetrics(trades, accountSize = 0) {
  const rows = Array.isArray(trades) ? trades : [];
  const totalPnl = rows.reduce((total, trade) => total + Number(trade?.pnl || 0), 0);
  const totalFees = rows.reduce((total, trade) => total + Number(tradeCostTotal(trade) || 0), 0);
  const wins = rows.filter((trade) => Number(trade?.pnl || 0) > 0);
  const losses = rows.filter((trade) => Number(trade?.pnl || 0) < 0);
  const winPnl = wins.reduce((total, trade) => total + Number(trade?.pnl || 0), 0);
  const lossPnl = losses.reduce((total, trade) => total + Number(trade?.pnl || 0), 0);
  const baseAccountSize = Number(accountSize || 0);
  return {
    totalPnl,
    totalFees,
    returnPct: baseAccountSize > 0 ? (totalPnl / baseAccountSize) * 100 : 0,
    trades: rows.length,
    winRate: rows.length > 0 ? (wins.length / rows.length) * 100 : 0,
    avgWin: wins.length > 0 ? winPnl / wins.length : 0,
    avgLoss: losses.length > 0 ? lossPnl / losses.length : 0,
    avgDay: averageTradePeriodPnl(rows, "day"),
    avgWeek: averageTradePeriodPnl(rows, "week"),
    avgMonth: averageTradePeriodPnl(rows, "month"),
  };
}

function averageTradePeriodPnl(trades, period) {
  const totals = new Map();
  (Array.isArray(trades) ? trades : []).forEach((trade) => {
    const key = tradePeriodKey(trade, period);
    if (!key) return;
    totals.set(key, (totals.get(key) || 0) + Number(trade?.pnl || 0));
  });
  if (totals.size === 0) return 0;
  return Array.from(totals.values()).reduce((total, value) => total + value, 0) / totals.size;
}

function tradePeriodKey(trade, period) {
  const timestamp = tradeSortTimestamp(trade);
  if (!timestamp) return "";
  const dayKey = localDateKey(timestamp);
  if (period === "day") return dayKey;
  if (period === "month") return dayKey.slice(0, 7);
  if (period === "week") return weekStartKey(dayKey);
  return dayKey;
}

function weekStartKey(dayKey) {
  const parts = String(dayKey || "").split("-").map((value) => Number(value));
  if (parts.length !== 3 || parts.some((value) => !Number.isFinite(value))) return "";
  const date = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2]));
  const day = date.getUTCDay();
  const mondayOffset = day === 0 ? -6 : 1 - day;
  date.setUTCDate(date.getUTCDate() + mondayOffset);
  return date.toISOString().slice(0, 10);
}

function pagedTradeSummary({ page, totalPages, pageCount, filteredCount, totalCount }) {
  return `Showing ${formatInteger(pageCount)} trades on page ${formatInteger(page)} of ${formatInteger(totalPages)}. Filtered ${formatInteger(filteredCount)} of ${formatInteger(totalCount)} total trades.`;
}

function uniqueTradeValues(trades, selector) {
  const values = new Set();
  (Array.isArray(trades) ? trades : []).forEach((trade) => {
    const value = String(selector(trade) || "").trim();
    if (value) values.add(value);
  });
  return Array.from(values).sort();
}

function tradeStrategyFilterValue(trade) {
  return displayTradeStrategyLabel(trade);
}

function displayTradeStrategyLabel(trade) {
  const code = String(trade?.strategyCode || "").trim();
  if (code.toUpperCase() === "UNTRACKED") return "UNTRACKED";
  return String(trade?.strategyName || code || "").trim() || "--";
}

function toTradeAnalysisRow(trade) {
  if (!trade) return trade;
  const openedAt = trade.entryTime || trade.openedAt || trade.signalTime || trade.time || trade.createdAt || "";
  const closedAt = trade.exitTime || trade.closedAt || trade.updatedAt || trade.createdAt || "";
  const entry = finiteNumberOrNull(trade.entry ?? trade.entryPrice) ?? 0;
  const exit = finiteNumberOrNull(trade.exit ?? trade.exitPrice) ?? 0;
  return {
    ...trade,
    strategyCode: trade.strategyCode || trade.strategy || "",
    strategyName: trade.strategyName || displayTradeStrategyLabel(trade),
    openedAt,
    closedAt,
    entry,
    exit,
    qty: trade.qty ?? trade.contracts ?? 0,
    contracts: trade.contracts ?? trade.qty ?? 0,
    stop: finiteNumberOrNull(trade.stop ?? trade.stopPrice) ?? 0,
    target: finiteNumberOrNull(trade.target ?? trade.targetPrice) ?? 0,
    entryPrice: entry,
    exitPrice: exit,
    stopPrice: finiteNumberOrNull(trade.stopPrice ?? trade.stop) ?? 0,
    targetPrice: finiteNumberOrNull(trade.targetPrice ?? trade.target) ?? 0,
    entryReasoning: normalizeReasonSection(trade.entryReasoning || trade.tradeReason?.entry),
    exitReasoning: normalizeReasonSection(trade.exitReasoning || trade.tradeReason?.exit),
    entryReason: trade.entryReason || tradeReasonEntryText(trade.tradeReason),
    exitReason: trade.exitReason || tradeReasonExitText(trade.tradeReason),
    structuredExitReason: trade.structuredExitReason || tradeReasonExitText(trade.tradeReason),
    dtmDetails: trade.dtmDetails || tradeDtmDetailsText(trade),
  };
}

function normalizeTradeSideForFilter(side) {
  const value = String(side || "").toLowerCase();
  if (value === "short" || value === "sell") return "short";
  if (value === "long" || value === "buy") return "long";
  return value;
}

function tradeSortTimestamp(trade) {
  return parseChartTime(trade?.createdAt || trade?.entryTime || trade?.signalTime || trade?.closedAt || trade?.time);
}

function formatLiveTradeDuration(trade) {
  const start = parseChartTime(trade?.entryTime || trade?.signalTime || trade?.openedAt);
  const end = parseChartTime(trade?.exitTime || trade?.closedAt || trade?.updatedAt || trade?.createdAt);
  if (!start || !end || end < start) return "--";
  return formatDurationMs(end - start);
}

function formatDurationMs(durationMs) {
  const totalMinutes = Math.max(0, Math.round(durationMs / 60000));
  if (totalMinutes < 60) return `${totalMinutes}m`;
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
}

function isTradeWithinDateRange(trade, startDate, endDate) {
  if (!startDate && !endDate) return true;
  const timestamp = tradeSortTimestamp(trade);
  if (!timestamp) return true;
  const tradeDate = localDateKey(timestamp);
  if (startDate && tradeDate < startDate) return false;
  if (endDate && tradeDate > endDate) return false;
  return true;
}

function localDateKey(timestamp) {
  const date = new Date(timestamp);
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "America/New_York",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${values.year}-${values.month}-${values.day}`;
}

function TradeMetricsGrid({ metrics, pnlLabel = "P/L", tradeLabel = "Trades", returnLabel = "Return %" }) {
  const safeMetrics = metrics || EMPTY_TRADE_METRICS;
  return (
    <div className="futures-live-filtered-metrics-stack">
      <div className="app-live-grid futures-live-filtered-primary-grid">
        <MetricCard label={pnlLabel} value={formatCurrency(safeMetrics.totalPnl)} accent={safeMetrics.totalPnl} />
        <MetricCard label="Total Fees" value={formatFees(safeMetrics.totalFees)} />
        <MetricCard label={returnLabel} value={formatPct(safeMetrics.returnPct)} accent={safeMetrics.returnPct} />
        <MetricCard label={tradeLabel} value={formatInteger(safeMetrics.trades)} />
        <MetricCard label="Win Rate" value={formatRate(safeMetrics.winRate)} />
      </div>
    </div>
  );
}

function TradePagination({ page, totalPages, pageSize, onPrev, onNext }) {
  return (
    <div className="futures-trade-pagination d-flex align-items-center justify-content-between gap-2 mt-3 flex-wrap">
      <button type="button" className="app-btn px-3" disabled={page <= 1} onClick={onPrev}>
        Prev Trades
      </button>
      <div className="app-muted app-kicker">
        Trade Page <b>{formatInteger(page)}</b> of <b>{formatInteger(totalPages)}</b> · {formatInteger(pageSize)} rows/page
      </div>
      <button type="button" className="app-btn px-3" disabled={page >= totalPages} onClick={onNext}>
        Next Trades
      </button>
    </div>
  );
}

function TradeFilters({ trades, filteredTrades, filters, onChange, summaryText = "" }) {
  const rows = useMemo(() => Array.isArray(trades) ? trades : [], [trades]);
  const visibleRows = useMemo(() => Array.isArray(filteredTrades) ? filteredTrades : [], [filteredTrades]);
  const symbols = useMemo(() => uniqueTradeValues(rows, (trade) => String(trade?.symbol || "").toUpperCase()), [rows]);
  const strategies = useMemo(() => uniqueTradeValues(rows, tradeStrategyFilterValue), [rows]);
  const activeFilterCount = [
    filters.outcome !== "all",
    filters.symbol !== "all",
    filters.side !== "all",
    filters.strategy !== "all",
    filters.sort !== "newest",
    filters.startDate,
    filters.endDate,
  ].filter(Boolean).length;
  const setFilter = (field, value) => onChange((current) => ({ ...current, [field]: value }));
  const clearFilters = () => onChange(DEFAULT_TRADE_FILTERS);

  return (
    <>
      <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap mt-2">
        <div className="app-muted app-kicker">
          {summaryText || `Showing ${formatInteger(visibleRows.length)} of ${formatInteger(rows.length)} trades.`}
        </div>
        {activeFilterCount > 0 && (
          <button type="button" className="app-btn app-btn-small px-3" onClick={clearFilters}>
            Clear Filters
          </button>
        )}
      </div>
      <div className="app-trade-toolbar futures-live-trade-toolbar mt-3">
        <label className="d-grid gap-1">
          <span className="app-label">Outcome</span>
          <select className="form-select app-input" value={filters.outcome} onChange={(event) => setFilter("outcome", event.target.value)}>
            <option value="all">All Trades</option>
            <option value="profits">Profits</option>
            <option value="losses">Losses</option>
            <option value="flat">Flat</option>
          </select>
        </label>
        <label className="d-grid gap-1">
          <span className="app-label">Symbol</span>
          <select className="form-select app-input" value={filters.symbol} onChange={(event) => setFilter("symbol", event.target.value)}>
            <option value="all">All Symbols</option>
            {symbols.map((symbol) => (
              <option key={symbol} value={symbol}>{symbol}</option>
            ))}
          </select>
        </label>
        <label className="d-grid gap-1">
          <span className="app-label">Side</span>
          <select className="form-select app-input" value={filters.side} onChange={(event) => setFilter("side", event.target.value)}>
            <option value="all">All Sides</option>
            <option value="long">Long</option>
            <option value="short">Short</option>
          </select>
        </label>
        <label className="d-grid gap-1">
          <span className="app-label">Strategy</span>
          <select className="form-select app-input" value={filters.strategy} onChange={(event) => setFilter("strategy", event.target.value)}>
            <option value="all">All Strategies</option>
            {strategies.map((strategy) => (
              <option key={strategy} value={strategy}>{strategy}</option>
            ))}
          </select>
        </label>
        <label className="d-grid gap-1">
          <span className="app-label">Sort</span>
          <select className="form-select app-input" value={filters.sort} onChange={(event) => setFilter("sort", event.target.value)}>
            <option value="newest">Newest</option>
            <option value="largestWin">Largest Win</option>
            <option value="largestLoss">Largest Loss</option>
          </select>
        </label>
        <label className="d-grid gap-1">
          <span className="app-label">Start Date</span>
          <input
            className="form-control app-input"
            type="date"
            value={filters.startDate}
            max={filters.endDate || undefined}
            onInput={(event) => setFilter("startDate", event.target.value)}
            onChange={(event) => setFilter("startDate", event.target.value)}
          />
        </label>
        <label className="d-grid gap-1">
          <span className="app-label">End Date</span>
          <input
            className="form-control app-input"
            type="date"
            value={filters.endDate}
            min={filters.startDate || undefined}
            onInput={(event) => setFilter("endDate", event.target.value)}
            onChange={(event) => setFilter("endDate", event.target.value)}
          />
        </label>
      </div>
    </>
  );
}

function TradesTable({ trades, mode, onOpenTrade = null }) {
  const gridClass = mode === "live" ? "futures-live-trades-grid" : "futures-live-all-trades-grid";
  const emptyText = mode === "live" ? "No live trade intents yet." : "No live bot trade records yet.";
  const rowClickable = mode === "all" && typeof onOpenTrade === "function";
  const showReasonColumns = mode !== "all";
  return (
    <>
      <div className="mobile-trade-card-list">
        {trades.length ? (
          trades.map((trade, index) => (
            <article
              className={rowClickable ? "mobile-trade-card trade-analysis-clickable" : "mobile-trade-card"}
              key={`${mode}-${trade.id || trade.entryTime || trade.createdAt || index}`}
              role={rowClickable ? "button" : undefined}
              tabIndex={rowClickable ? 0 : undefined}
              onClick={rowClickable ? () => onOpenTrade(trade) : undefined}
              onKeyDown={rowClickable ? (event) => {
                if (event.key !== "Enter" && event.key !== " ") return;
                event.preventDefault();
                onOpenTrade(trade);
              } : undefined}
            >
              <div className="mobile-trade-card-head">
                <div>
                  <span className="app-label">{trade.symbol || "--"} / {displayTradeStrategyLabel(trade)}</span>
                  <strong className={Number(trade.pnl || 0) > 0 ? "app-pnl-pos" : Number(trade.pnl || 0) < 0 ? "app-pnl-neg" : ""}>{formatCurrency(trade.pnl)}</strong>
                </div>
                <span className={String(trade.side || "").toUpperCase() === "SHORT" ? "app-side-pill short" : "app-side-pill long"}>
                  {trade.side || "--"}
                </span>
              </div>
              <div className="mobile-trade-meta-grid">
                <span>
                  <b>Time</b>
                  <em>{formatEstTime(trade.entryTime || trade.signalTime || trade.createdAt || "--")}</em>
                </span>
                <span>
                  <b>Duration</b>
                  <em>{formatLiveTradeDuration(trade)}</em>
                </span>
                <span>
                  <b>Qty</b>
                  <em>{trade.contracts || 0}</em>
                </span>
                <span>
                  <b>Entry</b>
                  <em>{formatPrice(trade.entryPrice)}</em>
                </span>
                <span>
                  <b>Exit</b>
                  <em>{formatPrice(trade.exitPrice)}</em>
                </span>
              </div>
              <details className="mobile-trade-details">
                <summary>Trade details</summary>
                <div>
                  <span>Entry Reason</span>
                  <TradeReasonStack trade={trade} kind="entry" />
                </div>
                <div>
                  <span>Exit Reason</span>
                  <TradeReasonStack trade={trade} kind="exit" emptyText={mode === "live" ? "--" : "not available"} />
                </div>
                <div>
                  <span>Fees</span>
                  <p>{mode === "live" ? "--" : formatFees(trade.fees)}</p>
                </div>
              </details>
            </article>
          ))
        ) : (
          <div className="app-empty">{emptyText}</div>
        )}
      </div>

      <div className="app-table-wrap desktop-trade-table">
        <div className={`app-grid-head ${gridClass}`}>
          <div>Time</div>
          <div>Duration</div>
          <div>Symbol</div>
          <div>Strategy</div>
          <div>Side</div>
          <div>Qty</div>
          <div>Entry</div>
          <div>Exit</div>
          <div>PnL</div>
          {showReasonColumns && <div>Entry Reason</div>}
          {showReasonColumns && <div>Exit Reason</div>}
          <div>Fees</div>
        </div>
        {trades.length ? (
          trades.map((trade) => (
            <div
              className={rowClickable ? `app-grid-row ${gridClass} trade-analysis-clickable` : `app-grid-row ${gridClass}`}
              key={trade.id}
              role={rowClickable ? "button" : undefined}
              tabIndex={rowClickable ? 0 : undefined}
              onClick={rowClickable ? () => onOpenTrade(trade) : undefined}
              onKeyDown={rowClickable ? (event) => {
                if (event.key !== "Enter" && event.key !== " ") return;
                event.preventDefault();
                onOpenTrade(trade);
              } : undefined}
            >
              <div className="app-time-cell">{formatEstTime(trade.entryTime || trade.signalTime || trade.createdAt || "--")}</div>
              <div>{formatLiveTradeDuration(trade)}</div>
              <div>{trade.symbol || "--"}</div>
              <div>{displayTradeStrategyLabel(trade)}</div>
              <div>{trade.side || "--"}</div>
              <div>{trade.contracts || 0}</div>
              <div>{formatPrice(trade.entryPrice)}</div>
              <div>{formatPrice(trade.exitPrice)}</div>
              <div className={Number(trade.pnl || 0) > 0 ? "app-pnl-pos" : Number(trade.pnl || 0) < 0 ? "app-pnl-neg" : ""}>{formatCurrency(trade.pnl)}</div>
              {showReasonColumns && <div className="app-trade-notes"><TradeReasonStack trade={trade} kind="entry" /></div>}
              {showReasonColumns && <div className="app-trade-notes"><TradeReasonStack trade={trade} kind="exit" emptyText={mode === "live" ? "--" : "not available"} /></div>}
              <div className="app-trade-fees">{mode === "live" ? "--" : formatFees(trade.fees)}</div>
            </div>
          ))
        ) : (
          <div className="app-empty">{emptyText}</div>
        )}
      </div>
    </>
  );
}

function TradeReasonStack({ trade, kind, emptyText = "not available" }) {
  const sections = kind === "entry" ? entryReasonSectionsForTrade(trade) : exitReasonSectionsForTrade(trade);
  if (!sections.length) {
    return <span className="trade-reason-empty">{emptyText}</span>;
  }
  return (
    <div className="trade-reason-stack">
      {sections.map((section) => (
        <div className="trade-reason-line" key={`${kind}-${section.label}`}>
          <b>{section.label}</b>
          <span>{section.text}</span>
        </div>
      ))}
    </div>
  );
}

function entryReasonSectionsForTrade(trade) {
  const entry = normalizeReasonSection(trade?.entryReasoning || trade?.tradeReason?.entry);
  const strategyText = compactEntryStrategyText(entry, trade)
    || cleanDisplayText(entry.strategyText || entry.strategyReason || entry.strategyThesis);
  const riskText = compactEntryRiskText(entry, trade)
    || cleanDisplayText(entry.riskSizingExplanation || entry.riskText);
  if (strategyText || riskText) {
    return [
      strategyText ? { label: "Strategy", text: stripReasonPrefix(strategyText, "Strategy") } : null,
      riskText ? { label: "Risk", text: stripReasonPrefix(riskText, "Risk") } : null,
    ].filter(Boolean);
  }
  const fallback = cleanDisplayText(entryReasonForTrade(trade));
  return fallback ? [{ label: "Entry", text: fallback }] : [];
}

function exitReasonSectionsForTrade(trade) {
  const exit = normalizeReasonSection(trade?.exitReasoning || trade?.tradeReason?.exit);
  const dtmText = compactDtmText(exit) || cleanDisplayText(exit.dtmSummary);
  const finalTrigger = compactFinalExitText(exit, trade)
    || cleanDisplayText(exit.finalExitTrigger || exit.exitText || trade?.exitReason || trade?.structuredExitReason);
  const review = compactReviewText(exit) || cleanDisplayText(exit.exitReview);
  if (dtmText || finalTrigger || review) {
    return [
      dtmText ? { label: "DTM", text: stripReasonPrefix(dtmText, "DTM") } : null,
      finalTrigger ? { label: "Final Exit", text: stripReasonPrefix(finalTrigger, "Final exit") } : null,
      review ? { label: "Review", text: stripReasonPrefix(review, "Review") } : null,
    ].filter(Boolean);
  }
  const fallback = cleanDisplayText(exitReasonForTrade(trade));
  return fallback && fallback !== "--" ? [{ label: "Exit", text: fallback }] : [];
}

function entryReasonForTrade(trade) {
  if (trade?.entryReason) return trade.entryReason;
  const structured = tradeReasonEntryText(trade?.tradeReason);
  if (structured) return structured;
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
  const structured = tradeReasonExitText(trade?.tradeReason) || trade?.structuredExitReason;
  if (structured) return structured;
  if (trade?.exitReason) return trade.exitReason;
  if (isClosedTradeDecision(trade)) {
    return trade?.reason || "Closed trade; broker close fill has been reported.";
  }
  return "--";
}

function normalizeTradeReasonPayload(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  return {
    ...value,
    entry: normalizeReasonSection(value.entry),
    exit: normalizeReasonSection(value.exit),
  };
}

function normalizeReasonSection(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

function mergeTradeReasonPayloads(first, second) {
  const left = normalizeTradeReasonPayload(first);
  const right = normalizeTradeReasonPayload(second);
  const entry = Object.keys(right.entry || {}).length ? right.entry : left.entry || {};
  const exit = Object.keys(right.exit || {}).length ? right.exit : left.exit || {};
  return {
    ...left,
    ...right,
    entry,
    exit,
    entryReasonText: right.entryReasonText || left.entryReasonText || "",
    exitReasonText: right.exitReasonText || left.exitReasonText || "",
  };
}

function tradeReasonEntryText(reason) {
  const payload = normalizeTradeReasonPayload(reason);
  return compactEntryReasonText(payload.entry, payload) || cleanDisplayText(payload.entryReasonText || payload.entry?.entryText || [
    payload.entry?.strategyText,
    payload.entry?.riskSizingExplanation,
  ].filter(Boolean).join(" "));
}

function tradeReasonExitText(reason) {
  const payload = normalizeTradeReasonPayload(reason);
  return compactExitReasonText(payload.exit, payload) || cleanDisplayText(payload.exitReasonText || payload.exit?.exitText || [
    payload.exit?.dtmSummary ? `DTM: ${payload.exit.dtmSummary}` : "",
    payload.exit?.finalExitTrigger ? `Final exit: ${payload.exit.finalExitTrigger}` : "",
    payload.exit?.exitReview ? `Review: ${payload.exit.exitReview}` : "",
  ].filter(Boolean).join(" "));
}

function compactEntryReasonText(entry, source) {
  const strategy = compactEntryStrategyText(entry, source);
  const risk = compactEntryRiskText(entry, source);
  return [strategy ? `Strategy: ${strategy}` : "", risk ? `Risk: ${risk}` : ""].filter(Boolean).join(" ");
}

function compactExitReasonText(exit, source) {
  const dtm = compactDtmText(exit);
  const final = compactFinalExitText(exit, source);
  const review = compactReviewText(exit);
  return [dtm ? `DTM: ${dtm}` : "", final, review ? `Review: ${review}` : ""].filter(Boolean).join(" ");
}

function compactEntryStrategyText(entry, source) {
  const section = normalizeReasonSection(entry);
  const details = normalizeReasonSection(source?.details);
  const strategy = cleanDisplayText(section.strategyCode || source?.strategyCode || details.strategy || details.strategyCode);
  const side = cleanDisplayText(source?.side || details.side).toUpperCase();
  const pattern = compactReasonClause(section.strategyThesis || section.strategyText || section.strategyPattern || section.strategyReason);
  const confirmation = compactConfirmationText(section.strategyConfirmations || section.orderFlowAction || "");
  const invalidation = compactReasonClause(section.strategyInvalidation || "");
  if (!strategy && !pattern && !confirmation && !invalidation) return "";
  const intro = strategy
    ? `${strategy} wanted ${side ? `a ${side.toLowerCase()}` : "this trade"}`
    : "The strategy wanted this trade";
  const sentences = [
    pattern ? `${intro} because ${pattern}.` : `${intro}.`,
    confirmation ? `Confirmations: ${confirmation}.` : "",
    invalidation && invalidation.toLowerCase() !== "not available" ? `Invalidation: ${invalidation}.` : "",
  ];
  return sentences.filter(Boolean).join(" ");
}

function compactEntryRiskText(entry, source) {
  const section = normalizeReasonSection(entry);
  const details = normalizeReasonSection(source?.details);
  const contracts = firstFiniteNumber(section.contracts, source?.contracts, details.contracts);
  const entryPrice = firstFiniteNumber(section.entryPrice, source?.entryPrice, details.entry);
  const stopPrice = firstFiniteNumber(section.initialStop, source?.stopPrice, details.stop);
  const targetPrice = firstFiniteNumber(section.initialTarget, source?.targetPrice, details.target);
  const riskTicks = firstFiniteNumber(section.riskTicks);
  const maxLoss = firstFiniteNumber(section.estimatedMaxLoss);
  const guardText = compactGuardText(section.portfolioGuardsPassed);
  const pieces = [
    contracts > 0 ? `Size: ${formatContractsText(contracts)}` : "",
    entryPrice > 0 ? `Entry: ${formatPrice(entryPrice)}` : "",
    stopPrice > 0 ? `Stop: ${formatPrice(stopPrice)}` : "",
    targetPrice > 0 ? `Target: ${formatPrice(targetPrice)}` : "",
    riskTicks > 0 || maxLoss > 0 ? `Risk: ${[
      riskTicks > 0 ? `${formatNumber(riskTicks, 1)}t` : "",
      maxLoss > 0 ? formatAbsoluteCurrency(maxLoss) : "",
    ].filter(Boolean).join(" / ")}` : "",
    guardText ? `Guards: ${guardText}` : "",
    section.compressedRiskPlan ? "Adjust: stop compressed before sizing" : "",
  ];
  return pieces.filter(Boolean).join("; ");
}

function compactDtmText(exit) {
  const section = normalizeReasonSection(exit);
  const timelineText = compactDtmTimelineText(section.dtmDecisionTimeline);
  if (timelineText) return timelineText;
  const action = cleanDisplayText(section.dtmFinalAction);
  const actionText = humanizeReasonCode(action);
  const summary = compactReasonClause(section.dtmSummary || "");
  const normalized = `${action} ${summary}`.toLowerCase();
  if (!summary && !action) return "";
  if (normalized.includes("dtm_no_override") || normalized.includes("no dtm") || normalized.includes("original bracket") || summary.startsWith("none;")) {
    return "No DTM decisions.";
  }
  return [
    actionText ? `Action: ${actionText}` : "",
    summary ? summary : "",
  ].filter(Boolean).join("; ");
}

function tradeDtmDetailsText(trade) {
  const exit = normalizeReasonSection(trade?.exitReasoning || trade?.tradeReason?.exit);
  return compactDtmText(exit)
    || cleanDisplayText(trade?.dtmDetails)
    || cleanDisplayText(exit.dtmSummary)
    || "";
}

function extractDtmThinkingEvents(entries) {
  return (Array.isArray(entries) ? entries : [])
    .filter((entry) => eventLogCode(entry) === "DTM_DECISION")
    .map((entry) => {
      const details = normalizeReasonSection(entry?.details);
      const dtmDetails = normalizeReasonSection(details.dtmDetails);
      return {
        sessionId: entry?.sessionId || 0,
        symbol: String(entry?.symbol || details.symbol || "").toUpperCase(),
        strategyCode: String(details.strategy || details.strategyCode || "").toUpperCase(),
        side: String(details.side || "").toUpperCase(),
        time: entry?.barTime || entry?.createdAt || details.time || "",
        actionCode: cleanDisplayText(details.action || details.actionCode || details.dtmAction),
        reason: cleanDisplayText(details.reason || entry?.detail || entry?.summary),
        details: dtmDetails,
      };
    })
    .filter((event) => event.symbol && event.actionCode);
}

function attachDtmThinkingToTrades(trades, dtmEvents) {
  const events = Array.isArray(dtmEvents) ? dtmEvents : [];
  if (!events.length) return Array.isArray(trades) ? trades : [];
  return (Array.isArray(trades) ? trades : []).map((trade) => {
    const existingDtm = tradeDtmDetailsText(trade);
    const matchedEvents = events.filter((event) => dtmEventMatchesTrade(event, trade));
    if (!matchedEvents.length) return trade;
    const timeline = matchedEvents.map((event) => ({
      time: event.time,
      actionCode: event.actionCode,
      reason: event.reason,
      details: event.details,
    }));
    const last = matchedEvents[matchedEvents.length - 1];
    const dtmSummary = matchedEvents.map(dtmThinkingEventText).filter(Boolean).join(" | ");
    const managedLevels = dtmManagedLevelsFromEvents(matchedEvents);
    const originalStopPrice = firstPositiveNumber(trade.originalStopPrice, trade.stopPrice);
    const originalTargetPrice = firstPositiveNumber(trade.originalTargetPrice, trade.targetPrice);
    const managedStopPrice = managedLevels.stopPrice > 0 ? managedLevels.stopPrice : 0;
    const managedTargetPrice = managedLevels.targetPrice > 0 ? managedLevels.targetPrice : 0;
    const currentReason = normalizeTradeReasonPayload(trade?.tradeReason);
    const currentExit = normalizeReasonSection(trade?.exitReasoning || currentReason.exit);
    const exitReasoning = {
      ...currentExit,
      dtmSummary,
      dtmDecisionTimeline: timeline,
      dtmFinalAction: last.actionCode,
    };
    const tradeReason = {
      ...currentReason,
      exit: exitReasoning,
      exitReasonText: currentReason.exitReasonText || trade?.structuredExitReason || trade?.exitReason || "",
    };
    return {
      ...trade,
      originalStopPrice: originalStopPrice > 0 ? originalStopPrice : trade.originalStopPrice,
      managedStopPrice: managedStopPrice || trade.managedStopPrice,
      dtmStopManaged: Boolean(managedStopPrice || trade.dtmStopManaged),
      stopPrice: managedStopPrice || trade.stopPrice,
      originalTargetPrice: originalTargetPrice > 0 ? originalTargetPrice : trade.originalTargetPrice,
      managedTargetPrice: managedTargetPrice || trade.managedTargetPrice,
      dtmTargetManaged: Boolean(managedTargetPrice || trade.dtmTargetManaged),
      targetPrice: managedTargetPrice || trade.targetPrice,
      tradeReason,
      exitReasoning,
      dtmDetails: dtmSummary || (existingDtm && !isNoDtmText(existingDtm) ? existingDtm : ""),
    };
  });
}

function dtmManagedLevelsFromEvents(events) {
  return (Array.isArray(events) ? events : []).reduce((levels, event) => {
    const details = normalizeReasonSection(event?.details);
    const brokerModify = normalizeReasonSection(details.brokerModify);
    const nextStop = firstPositiveNumber(
      details.newStop,
      brokerModify.stopPrice,
      brokerModify.stop,
      brokerModify.activeStop,
      details.stopPrice
    );
    const nextTarget = firstPositiveNumber(
      details.newTarget,
      brokerModify.targetPrice,
      brokerModify.target,
      details.targetPrice
    );
    return {
      stopPrice: nextStop > 0 ? nextStop : levels.stopPrice,
      targetPrice: nextTarget > 0 ? nextTarget : levels.targetPrice,
    };
  }, { stopPrice: 0, targetPrice: 0 });
}

function dtmEventMatchesTrade(event, trade) {
  if (!event || !trade) return false;
  const symbol = String(trade.symbol || "").toUpperCase();
  const strategy = String(trade.strategyCode || "").toUpperCase();
  const side = String(trade.side || "").toUpperCase();
  if (event.symbol && symbol && event.symbol !== symbol) return false;
  if (event.strategyCode && strategy && event.strategyCode !== strategy) return false;
  if (event.side && side && event.side !== side) return false;
  const eventTime = parseChartTime(event.time);
  const entryTime = parseChartTime(trade.entryTime || trade.signalTime || trade.openedAt || trade.createdAt);
  const status = String(trade.status || "").toUpperCase();
  const closed = Number(trade.exitPrice || 0) > 0
    || status.includes("EXIT")
    || status.includes("CLOSED")
    || status.includes("FLAT")
    || status.includes("SOLD");
  const exitTime = parseChartTime(trade.exitTime || trade.closedAt || (closed ? trade.updatedAt || trade.createdAt : ""));
  if (!eventTime || !entryTime) return true;
  if (eventTime < entryTime - 60_000) return false;
  if (exitTime && eventTime > exitTime + 5 * 60_000) return false;
  return true;
}

function dtmThinkingEventText(event) {
  const details = normalizeReasonSection(event?.details);
  const favorableR = firstFiniteNumber(details.favorableR, details.favorableCloseR);
  const adverseR = firstFiniteNumber(details.adverseR, details.adverseCloseR);
  const movement = favorableR > 0 || adverseR > 0
    ? `move +${formatNumber(favorableR, 2)}R / -${formatNumber(adverseR, 2)}R`
    : "";
  return [
    humanizeReasonCode(event?.actionCode),
    cleanDisplayText(event?.reason),
    movement,
  ].filter(Boolean).join(": ");
}

function isNoDtmText(value) {
  const normalized = cleanDisplayText(value).toLowerCase();
  return !normalized
    || normalized.includes("no dtm decisions")
    || normalized.includes("dtm_no_override")
    || normalized.includes("original bracket");
}

function compactDtmTimelineText(timeline) {
  const events = Array.isArray(timeline) ? timeline : [];
  if (!events.length) return "";
  return events
    .map((event) => {
      const action = humanizeReasonCode(cleanDisplayText(event?.actionCode || event?.action || event?.normalizedAction));
      const reason = compactReasonClause(event?.reason || "");
      const details = normalizeReasonSection(event?.details);
      const favorableR = firstFiniteNumber(details.favorableR, details.favorableCloseR);
      const adverseR = firstFiniteNumber(details.adverseR, details.adverseCloseR);
      const movement = favorableR > 0 || adverseR > 0
        ? `move +${formatNumber(favorableR, 2)}R / -${formatNumber(adverseR, 2)}R`
        : "";
      return [action, reason, movement].filter(Boolean).join(": ");
    })
    .filter(Boolean)
    .join(" | ");
}

function compactFinalExitText(exit, source, options = {}) {
  const section = normalizeReasonSection(exit);
  const details = normalizeReasonSection(source?.details);
  const trigger = compactExitTriggerText(section.finalExitTrigger || section.exitText || source?.exitReason || source?.structuredExitReason || details.exitReason || "", section, source);
  const exitPrice = firstFiniteNumber(section.exitPrice, source?.exitPrice, details.exitPrice);
  const contracts = firstFiniteNumber(section.exitContracts, source?.contracts, details.contracts);
  const pnl = firstFiniteNumber(source?.pnl, details.pnl);
  const pieces = [
    trigger ? `Result: ${trigger}` : "",
    exitPrice > 0 ? `Price: ${formatPrice(exitPrice)}` : "",
    contracts > 0 ? `Contracts: ${formatContractsText(contracts)}` : "",
    options.includePnl && Number.isFinite(pnl) && pnl !== 0 ? `PnL: ${formatCurrency(pnl)}` : "",
  ];
  return pieces.filter(Boolean).join("; ");
}

function compactReviewText(exit) {
  const section = normalizeReasonSection(exit);
  const review = compactReasonClause(section.exitReview || "");
  if (review) return compactMfeMaeText(review);
  const mfe = firstFiniteNumber(section.mfe);
  const mae = firstFiniteNumber(section.mae);
  if (mfe !== 0 || mae !== 0) {
    return `MFE: ${formatCurrency(mfe)}; MAE: ${formatCurrency(mae)}`;
  }
  return "";
}

function compactConfirmationText(value) {
  return compactReasonClause(value)
    .replace(/^configured\s+\S+\s+filters passed and the next-bar live signal fired;?\s*/i, "filters passed; next-bar signal fired; ")
    .replace(/^configured\s+\S+\s+filters passed;\s*/i, "filters passed; ")
    .replace(/\s*;\s*$/g, "");
}

function compactExitTriggerText(value, exit = {}, source = {}) {
  const section = normalizeReasonSection(exit);
  const text = compactReasonClause(value)
    .replace(/^Final exit:\s*/i, "")
    .replace(/^Exit:\s*/i, "");
  const prefix = exitHitPrefix(section, source, text);
  const oldBroker = text.match(/^Broker TP\/SL fill reconciled from TopstepX Trade\/search order\s+(.+)$/i);
  if (oldBroker) return `${prefix}broker TP/SL fill; order ${compactReasonClause(oldBroker[1])}`;
  const newBroker = text.match(/^Broker TP\/SL fill;\s*Topstep order\s+(.+)$/i);
  if (newBroker) return `${prefix}broker TP/SL fill; order ${compactReasonClause(newBroker[1])}`;
  if (/^Broker flat sync from TopstepX Position\/searchOpen$/i.test(text)) return `${prefix}broker flat sync; no open Topstep position`;
  if (/^Broker flat sync; no open Topstep position$/i.test(text)) return `${prefix}broker flat sync; no open Topstep position`;
  return `${prefix}${text}`;
}

function exitHitPrefix(exit, source, trigger) {
  const section = normalizeReasonSection(exit);
  const code = cleanDisplayText(section.finalExitReasonCode).toUpperCase();
  const text = cleanDisplayText(trigger).toUpperCase();
  if (code.includes("TARGET") || text.includes("TARGET")) return "target hit; ";
  if (code.includes("STOP") || text.includes("STOP")) return "stop hit; ";
  const exitPrice = firstFiniteNumber(section.exitPrice, source?.exitPrice, source?.details?.exitPrice);
  const targetPrice = firstFiniteNumber(source?.targetPrice, source?.details?.target, source?.target);
  const stopPrice = firstFiniteNumber(source?.stopPrice, source?.details?.stop, source?.stop);
  if (exitPrice > 0 && targetPrice > 0 && Math.abs(exitPrice - targetPrice) <= 0.25) return "target hit; ";
  if (exitPrice > 0 && stopPrice > 0 && Math.abs(exitPrice - stopPrice) <= 0.25) return "stop hit; ";
  if (text.includes("TP/SL FILL") || text.includes("BRACKET")) {
    const pnl = firstFiniteNumber(source?.pnl, source?.details?.pnl);
    if (pnl > 0) return "take-profit fill; ";
    if (pnl < 0) return "stop-loss fill; ";
  }
  return "";
}

function compactMfeMaeText(value) {
  const match = cleanDisplayText(value).match(/^captured live MFE\s+([^ ]+)\s+and MAE\s+([^ ]+)\s+for later DTM comparison\.?$/i);
  if (!match) return value;
  return `MFE: ${match[1]}; MAE: ${match[2]}; saved for DTM review`;
}

function compactGuardText(value) {
  const text = compactReasonClause(value)
    .replace(/\bdaily loss room\b/gi, "daily loss")
    .replace(/\btrailing drawdown safety\b/gi, "trailing drawdown");
  if (!text || text.toLowerCase() === "not available") return "";
  return text.includes("passed") ? text : `${text} passed`;
}

function compactReasonClause(value) {
  let text = cleanDisplayText(value)
    .replace(/^Strategy:\s*/i, "")
    .replace(/^Risk:\s*/i, "")
    .replace(/^DTM:\s*/i, "")
    .replace(/^Review:\s*/i, "")
    .replace(/\b([A-Z0-9]+(?:_[A-Z0-9]+)+)\b/g, (match) => humanizeReasonCode(match));
  while (text.endsWith(".")) text = text.slice(0, -1).trim();
  return text;
}

function humanizeReasonCode(value) {
  return cleanDisplayText(value).replaceAll("_", " ").toLowerCase();
}

function firstFiniteNumber(...values) {
  for (const value of values) {
    const numeric = Number(value);
    if (Number.isFinite(numeric)) return numeric;
  }
  return 0;
}

function formatContractsText(value) {
  const numeric = Math.max(0, Math.round(Number(value || 0)));
  return `${numeric} contract${numeric === 1 ? "" : "s"}`;
}

function formatAbsoluteCurrency(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return "--";
  return `$${Math.abs(numeric).toFixed(2)}`;
}

function cleanDisplayText(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

function stripReasonPrefix(value, prefix) {
  const text = cleanDisplayText(value);
  const pattern = new RegExp(`^${prefix}\\s*:\\s*`, "i");
  return text.replace(pattern, "");
}

function formatFees(value) {
  if (value == null || value === "") return "--";
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return "--";
  return `$${Math.abs(numeric).toFixed(2)}`;
}

function MetricCard({ label, value, accent = 0 }) {
  const valueClass = accent > 0 ? "app-live-value app-pnl-pos" : accent < 0 ? "app-live-value app-pnl-neg" : "app-live-value";
  return (
    <div className="app-subpanel app-live-card">
      <div className="app-label">{label}</div>
      <div className={valueClass}>{value}</div>
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

function buildRiskProfileOptions(fundedProfiles = []) {
  return RISK_PROFILE_FALLBACKS.map((fallback) => {
    const backendProfile = fundedProfiles.find((profile) => profile.code === fallback.code);
    return {
      ...fallback,
      ...(backendProfile || {}),
      name: fallback.name,
    };
  });
}

function applyLiveFundedProfile(config, profile) {
  if (!profile) {
    return config;
  }
  const referenceSymbol = config.referenceSymbol || DEFAULT_LIVE_RISK_CONFIG.referenceSymbol;
  return {
    ...config,
    maxTrailingDrawdown: String(profile.maxTrailingDrawdown ?? config.maxTrailingDrawdown),
    dailyLossLimit: String(profile.dailyLossLimit ?? config.dailyLossLimit),
    maxRiskPerTrade: String(profile.maxRiskPerTrade ?? config.maxRiskPerTrade),
    maxContracts: String(contractLimitForProfile(profile, referenceSymbol)),
    profitTarget: String(profile.profitTarget ?? config.profitTarget),
    maxOpenPositions: String(profile.maxOpenPositions ?? config.maxOpenPositions),
    maxAggregateContracts: String(profile.maxAggregateContracts ?? config.maxAggregateContracts),
    maxAggregateMiniUnits: String(profile.maxAggregateMiniUnits ?? config.maxAggregateMiniUnits),
  };
}

function liveAccountSizeFromMetrics(metrics, fallback = 0) {
  const broker = metrics?.broker || {};
  const balanceTracksPnl = metricsBalanceTracksPnl(metrics);
  return firstPositiveNumber(
    broker.riskCurrentBalance,
    metrics?.riskCurrentBalance,
    broker.equityBalance,
    metrics?.equityBalance,
    balanceTracksPnl ? 0 : broker.currentBalance,
    balanceTracksPnl ? 0 : metrics?.currentBalance,
    broker.accountSize,
    metrics?.accountSize,
    fallback
  );
}

function riskProfileCodeForTopstepAccount(account, riskProfiles = []) {
  const label = String(account?.name || account?.label || account?.code || "").toUpperCase();
  const desiredSize = label.includes("150") ? 150000 : label.includes("100") ? 100000 : label.includes("50") ? 50000 : 0;
  if (!desiredSize) return "";
  const desiredShortLabel = desiredSize === 150000 ? "150K" : desiredSize === 100000 ? "100K" : "50K";
  const desiredCode = `TOPSTEP_${desiredShortLabel}`;
  const match = (Array.isArray(riskProfiles) ? riskProfiles : []).find((profile) => {
    const profileLabel = String(profile?.name || profile?.label || profile?.code || "").toUpperCase();
    return Number(profile?.accountSize || 0) === desiredSize
      || String(profile?.code || "").toUpperCase() === desiredCode
      || profileLabel.includes(desiredShortLabel);
  });
  return match?.code || desiredCode;
}

function metricsBalanceTracksPnl(metrics) {
  const broker = metrics?.broker || {};
  return Boolean(
    metrics?.balanceTracksPnl
      || broker.balanceTracksPnl
      || String(metrics?.balanceMode || "").toUpperCase() === "PNL"
      || String(broker.balanceMode || "").toUpperCase() === "PNL"
  );
}

function buildLiveRiskHeartbeat({
  liveStatus = null,
  metrics = null,
  profile = FALLBACK_PROFILE,
  accountId = "",
  marketDate = "",
  riskSizingMode = LIVE_RISK_SIZING_MODE,
  botStarted = false,
  backendOffline = false,
} = {}) {
  const broker = metrics?.broker || {};
  const balanceTracksPnl = metricsBalanceTracksPnl(metrics);
  const balanceMode = balanceTracksPnl ? "PNL" : String(metrics?.balanceMode || broker.balanceMode || "EQUITY").toUpperCase();
  const configuredMaxRisk = firstPositiveNumber(liveStatus?.maxRiskPerTrade, profile?.maxRiskPerTrade, FALLBACK_PROFILE.maxRiskPerTrade);
  const dailyLossLimit = Math.abs(firstPositiveNumber(liveStatus?.dailyLossLimit, profile?.dailyLossLimit, FALLBACK_PROFILE.dailyLossLimit));
  const trailingLimit = Math.abs(firstPositiveNumber(metrics?.trailingDrawdownLimit, liveStatus?.drawdownCushion, profile?.maxTrailingDrawdown, FALLBACK_PROFILE.maxTrailingDrawdown));
  const accountSize = firstPositiveNumber(metrics?.accountSize, broker.accountSize, liveStatus?.accountSize, profile?.accountSize, FALLBACK_PROFILE.accountSize);
  const currentPnl = firstFiniteNumber(metrics?.currentPnl, broker.currentPnl, broker.realizedPnl, 0);
  const fallbackRiskBalance = accountSize > 0 ? Math.max(0, accountSize + currentPnl) : 0;
  const riskCurrentBalance = firstFiniteNumber(
    metrics?.riskCurrentBalance,
    metrics?.equityBalance,
    broker.riskCurrentBalance,
    broker.equityBalance,
    balanceTracksPnl ? fallbackRiskBalance : 0,
    metrics?.currentBalance,
    broker.currentBalance,
    fallbackRiskBalance,
    accountSize
  );
  const sameDayAccountPnl = sameDayAccountPnlFromBrokerTrades(broker.trades, marketDate);
  const dayStartBalance = riskCurrentBalance > 0 ? Math.max(0, riskCurrentBalance - sameDayAccountPnl) : accountSize;
  const dailyPnl = riskCurrentBalance - dayStartBalance;
  const dailyRiskRoom = Math.max(0, dailyLossLimit + dailyPnl);
  const trailingFloor = riskCurrentBalance > 0 && trailingLimit > 0
    ? Math.max(accountSize - trailingLimit, Math.min(accountSize, riskCurrentBalance - trailingLimit))
    : 0;
  const trailingCushion = firstFiniteNumber(
    metrics?.drawdownCushion,
    broker.drawdownCushion,
    riskCurrentBalance > 0 && trailingLimit > 0
      ? Math.max(0, riskCurrentBalance - trailingFloor)
      : trailingLimit,
    0
  );
  const selectedRiskSizingMode = normalizeRiskSizingMode(liveStatus?.riskSizingMode || riskSizingMode);
  const riskOption = riskSizingOptionFor(selectedRiskSizingMode);
  const riskPolicy = normalizeRiskPolicy(liveStatus?.dynamicRiskPolicy, riskOption.policy);
  const mllBase = Math.max(1, trailingLimit);
  const dynamicMultiplier = selectedRiskSizingMode === RISK_SIZING_DYNAMIC
    ? clampNumber(trailingCushion / mllBase, 0, Math.max(0, riskPolicy.maxRiskMultiplier))
    : 1;
  const dynamicMaxRisk = Math.max(0, configuredMaxRisk * dynamicMultiplier);
  const dllRiskBudget = Math.max(0, dailyRiskRoom - Math.max(0, riskPolicy.dllReserveDollars)) * clampNumber(riskPolicy.dailyRoomUsagePct, 0, 1);
  const mllRiskBudget = Math.max(0, trailingCushion - Math.max(0, riskPolicy.mllReserveDollars)) * clampNumber(riskPolicy.mllRoomUsagePct, 0, 1);
  const effectiveMaxRisk = Math.min(dynamicMaxRisk, dllRiskBudget, mllRiskBudget);
  const modeCapLabel = selectedRiskSizingMode === RISK_SIZING_DYNAMIC ? "MLL scaled" : "Static cap";
  const budgetLimits = [
    { label: modeCapLabel, value: dynamicMaxRisk },
    { label: "DLL budget", value: dllRiskBudget },
    { label: "MLL budget", value: mllRiskBudget },
  ];
  const limitingBudget = budgetLimits.reduce((lowest, candidate) => (
    candidate.value < lowest.value ? candidate : lowest
  ), budgetLimits[0]);
  const limitingBudgetLabel = `${limitingBudget.label} ${formatAccountCurrency(limitingBudget.value)}`;
  const brokerReady = metrics?.brokerMetricsReady !== false && (metrics?.dataSource === BROKER_SOURCE_TOPSTEPX || isAuthoritativeTopstepBrokerSnapshot(broker, metrics));
  const selectedAccountId = String(accountId || metrics?.accountId || broker.accountId || liveStatus?.practiceAccountId || "").trim();
  let tone = "ok";
  let status = "Live";
  if (backendOffline) {
    tone = "error";
    status = "Offline";
  } else if (!botStarted) {
    tone = "idle";
    status = "Idle";
  } else if (!brokerReady) {
    tone = "warn";
    status = "Waiting";
  } else if (dailyRiskRoom <= 0 || trailingCushion <= 0) {
    tone = "error";
    status = "Blocked";
  } else if (dailyRiskRoom < configuredMaxRisk || (trailingLimit > 0 && trailingCushion < trailingLimit * 0.35)) {
    tone = "warn";
    status = "Reduced";
  }
  const source = brokerReady ? "TopstepX" : metrics?.dataSource || "Local";
  return {
    accountId: selectedAccountId,
    balanceMode,
    riskCurrentBalance,
    dayStartBalance,
    dailyPnl,
    dailyRiskRoom,
    trailingCushion,
    configuredMaxRisk,
    dynamicMaxRisk,
    dllRiskBudget,
    mllRiskBudget,
    effectiveMaxRisk,
    limitingBudgetLabel,
    riskSizingMode: selectedRiskSizingMode,
    riskSizingLabel: riskOption.label,
    source,
    status,
    tone,
  };
}

function sameDayAccountPnlFromBrokerTrades(trades, marketDate) {
  const targetDate = String(marketDate || "").slice(0, 10);
  if (!targetDate || !Array.isArray(trades)) return 0;
  return trades.reduce((total, trade) => {
    const timestamp = trade.createdAt || trade.closedAt || "";
    if (!isValidTimestamp(timestamp)) return total;
    const tradeDate = localDateKey(timestamp);
    if (tradeDate !== targetDate) return total;
    const cost = tradeCostTotal(trade);
    const tradeCost = Number.isFinite(Number(cost)) ? Number(cost) : 0;
    if (!trade?.closed) return total - tradeCost;
    const explicitGross = finiteNumberOrNull(trade.grossPnl ?? trade.profitAndLoss);
    const grossPnl = explicitGross != null
      ? explicitGross
      : Number(trade.pnl || 0) + tradeCost;
    return total + grossPnl - tradeCost;
  }, 0);
}

function isValidTimestamp(timestamp) {
  if (!timestamp) return false;
  return Number.isFinite(new Date(timestamp).getTime());
}

function firstPositiveNumber(...values) {
  for (const value of values) {
    const number = Number(value);
    if (Number.isFinite(number) && number > 0) {
      return number;
    }
  }
  return 0;
}

function normalizeRiskSizingMode(value) {
  const normalized = String(value || "").trim().toUpperCase();
  return RISK_SIZING_OPTIONS.some((option) => option.value === normalized)
    ? normalized
    : LIVE_RISK_SIZING_MODE;
}

function riskSizingOptionFor(value) {
  const normalized = normalizeRiskSizingMode(value);
  return RISK_SIZING_OPTIONS.find((option) => option.value === normalized) || RISK_SIZING_OPTIONS[0];
}

function normalizeRiskPolicy(policy, fallbackPolicy) {
  const fallback = fallbackPolicy || RISK_SIZING_OPTIONS[0].policy;
  return {
    stopRiskBufferMultiplier: firstPositiveNumber(policy?.stopRiskBufferMultiplier, fallback.stopRiskBufferMultiplier),
    dailyRoomUsagePct: firstFiniteNumber(policy?.dailyRoomUsagePct, fallback.dailyRoomUsagePct),
    mllRoomUsagePct: firstFiniteNumber(policy?.mllRoomUsagePct, fallback.mllRoomUsagePct),
    maxRiskMultiplier: firstPositiveNumber(policy?.maxRiskMultiplier, fallback.maxRiskMultiplier),
    dllReserveDollars: Math.max(0, firstFiniteNumber(policy?.dllReserveDollars, fallback.dllReserveDollars)),
    mllReserveDollars: Math.max(0, firstFiniteNumber(policy?.mllReserveDollars, fallback.mllReserveDollars)),
  };
}

function clampNumber(value, min, max) {
  const number = Number(value);
  if (!Number.isFinite(number)) return min;
  return Math.max(min, Math.min(max, number));
}

function contractLimitForProfile(profile, symbol) {
  if (!profile) return 1;
  return MICRO_SYMBOLS.has(String(symbol || "").toUpperCase())
    ? profile.maxMicroContracts || profile.maxAggregateContracts || 50
    : profile.maxContracts || 5;
}

function accountProfileCodeForAccountId(accountId, profiles = []) {
  const cleanAccountId = String(accountId || "").trim();
  if (!cleanAccountId) return "";
  const match = profiles.find((profile) => {
    const profileAccountId = String(profile?.accountId || profile?.code || "").trim();
    return profileAccountId === cleanAccountId;
  });
  return match?.code || "";
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

function timeframeLabel(value) {
  if (value === "5m") return "5 minute";
  if (value === "30m") return "30 minute";
  if (value === "1h") return "1 hour";
  if (value === "4h") return "4 hour";
  return "1 minute";
}

function defaultChartVisibleBars(node) {
  const nodeWidth = Number(node?.clientWidth || 0);
  const mobileViewport = typeof window !== "undefined" && window.matchMedia?.("(max-width: 760px)")?.matches;
  return mobileViewport || (nodeWidth > 0 && nodeWidth <= 620) ? 48 : 96;
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

function formatPct(value) {
  const numeric = Number(value || 0);
  const sign = numeric > 0 ? "+" : "";
  return `${sign}${numeric.toFixed(2)}%`;
}

function formatRate(value) {
  const numeric = Number(value || 0);
  return `${numeric.toFixed(2)}%`;
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

function formatNumber(value, digits = 2) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return "--";
  return numeric.toLocaleString("en-US", { minimumFractionDigits: 0, maximumFractionDigits: digits });
}

function formatSignedNumber(value, digits = 2) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return "--";
  const sign = numeric > 0 ? "+" : "";
  return `${sign}${numeric.toFixed(digits)}`;
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
