import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiFetch } from "../utils/api.js";

const DEFAULT_RISK_PROFILE = "TOPSTEP_50K";
const MAIN_PORTFOLIO_SYMBOLS = ["MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL"];
const MICRO_SYMBOLS = new Set(["MES", "MNQ", "M2K", "MYM", "MGC", "MCL"]);
const CONTROL_STRATEGY_PRESET = "backtestbias92k";
const BIAS_FREE_STRATEGY_PRESET = "biasfree92k";
const BEST_BIAS_FREE_STRATEGY_PRESET = "bestbiasfree";
const DEFAULT_STRATEGY_PRESET = BEST_BIAS_FREE_STRATEGY_PRESET;
const CANONICAL_STRATEGY_PRESETS = [
  { name: CONTROL_STRATEGY_PRESET, label: "Backtest Bias 92k" },
  { name: BIAS_FREE_STRATEGY_PRESET, label: "Bias-Free 92k" },
  { name: BEST_BIAS_FREE_STRATEGY_PRESET, label: "Best Bias-Free" },
];
const INSTRUMENT_FALLBACKS = [
  { symbol: "MES", name: "Micro E-mini S&P 500", exchange: "CME", tickSize: 0.25, tickValue: 1.25 },
  { symbol: "MNQ", name: "Micro E-mini Nasdaq-100", exchange: "CME", tickSize: 0.25, tickValue: 0.5 },
  { symbol: "NQ", name: "E-mini Nasdaq-100", exchange: "CME", tickSize: 0.25, tickValue: 5 },
  { symbol: "MGC", name: "Micro Gold", exchange: "COMEX", tickSize: 0.1, tickValue: 1 },
  { symbol: "ES", name: "E-mini S&P 500", exchange: "CME", tickSize: 0.25, tickValue: 12.5 },
  { symbol: "M2K", name: "Micro E-mini Russell 2000", exchange: "CME", tickSize: 0.1, tickValue: 0.5 },
  { symbol: "MYM", name: "Micro E-mini Dow", exchange: "CBOT", tickSize: 1, tickValue: 0.5 },
  { symbol: "MCL", name: "Micro WTI Crude Oil", exchange: "NYMEX", tickSize: 0.01, tickValue: 1 },
  { symbol: "GC", name: "Gold", exchange: "COMEX", tickSize: 0.1, tickValue: 10 },
];
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

const DEFAULT_CONFIG = {
  strategyPreset: DEFAULT_STRATEGY_PRESET,
  referenceSymbol: "MNQ",
  fundedProfile: DEFAULT_RISK_PROFILE,
  startDate: "2025-05-01",
  endDate: defaultEndDate(),
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
  useSavedRisk: "false",
  continueAfterRuleViolation: "true",
  qualitativeRiskEnabled: "true",
  dtmEnabled: "true",
  riskSizingMode: "STATIC_WITHDRAW_DAILY",
  sourcePortfolioBacktestId: "0",
};

export default function FuturesBacktest() {
  const navigate = useNavigate();
  const [config, setConfig] = useState(DEFAULT_CONFIG);
  const [instruments, setInstruments] = useState([]);
  const [marketData, setMarketData] = useState({ symbols: [], rowsBySymbol: {}, message: "", storagePath: "" });
  const [isMarketDataLoading, setIsMarketDataLoading] = useState(true);
  const [isMarketDataRefreshing, setIsMarketDataRefreshing] = useState(false);
  const [isRunning, setIsRunning] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [batchSymbols, setBatchSymbols] = useState(MAIN_PORTFOLIO_SYMBOLS);
  const [fundedProfiles, setFundedProfiles] = useState([]);
  const [strategyPresets, setStrategyPresets] = useState([]);

  useEffect(() => {
    loadInstruments();
    loadMarketDataStatus();
    loadFundedProfiles();
    loadStrategyPresets();
    loadPortfolioDefaults();
    // The initial page bootstrap should run once; each loader owns its own state updates.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const selectedSymbols = useMemo(() => {
    const available = new Set((instruments.length ? instruments : INSTRUMENT_FALLBACKS).map((instrument) => instrument.symbol));
    const filtered = batchSymbols.map((symbol) => symbol.toUpperCase()).filter((symbol) => available.has(symbol));
    return filtered.length ? filtered : MAIN_PORTFOLIO_SYMBOLS;
  }, [batchSymbols, instruments]);

  const selectedRows = selectedSymbols.reduce((total, symbol) => total + Number(marketData.rowsBySymbol?.[symbol] || 0), 0);
  const selectedLevel2CapturedRows = selectedSymbols.reduce((total, symbol) => total + Number(marketData.level2StatsBySymbol?.[symbol]?.capturedRows || 0), 0);
  const selectedLevel2DerivedRows = selectedSymbols.reduce((total, symbol) => total + Number(marketData.level2StatsBySymbol?.[symbol]?.derivedRows || 0), 0);
  const selectedLevel2Coverage = selectedSymbols.length
    ? selectedSymbols.reduce((total, symbol) => total + Number(marketData.level2StatsBySymbol?.[symbol]?.coveragePct || 0), 0) / selectedSymbols.length
    : 0;
  const missingDataSymbols = selectedSymbols.filter((symbol) => Number(marketData.rowsBySymbol?.[symbol] || 0) <= 0);
  const selectedInstrument = (instruments.length ? instruments : INSTRUMENT_FALLBACKS).find((instrument) => instrument.symbol === config.referenceSymbol) || INSTRUMENT_FALLBACKS[0] || null;
  const canRun = Boolean(config.startDate && config.endDate && selectedSymbols.length > 0 && missingDataSymbols.length === 0);
  const presetOptions = mergeStrategyPresets(strategyPresets);
  const selectedPresetLabel = presetOptions.find((preset) => preset.name === config.strategyPreset)?.label || config.strategyPreset || DEFAULT_STRATEGY_PRESET;
  const riskProfileOptions = useMemo(() => buildRiskProfileOptions(fundedProfiles), [fundedProfiles]);
  const riskConfigLocked = config.fundedProfile !== "CUSTOM";
  const ruleModeLabel = config.continueAfterRuleViolation === "true" ? "Full trail" : "Stop on breach";

  function loadInstruments() {
    apiFetch("/api/futures/instruments")
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures instruments.");
        return response.json();
      })
      .then((data) => setInstruments(buildInstrumentOptions(data)))
      .catch((error) => {
        console.error("Error loading futures instruments:", error);
        setInstruments(INSTRUMENT_FALLBACKS);
      });
  }

  function loadPortfolioDefaults() {
    apiFetch("/api/futures/portfolio-backtests/default-config")
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load portfolio defaults.");
        return response.json();
      })
      .then((data) => applyPortfolioDefaults(data))
      .catch((error) => {
        console.error("Error loading futures portfolio defaults:", error);
        applyPortfolioDefaults(null);
      });
  }

  function loadMarketDataStatus() {
    setIsMarketDataLoading(true);
    apiFetch("/api/futures/market-data")
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures market-data status.");
        return response.json();
      })
      .then((data) => {
        const nextMarketData = {
          symbols: Array.isArray(data.symbols) ? data.symbols : [],
          rowsBySymbol: data.rowsBySymbol || {},
          rawRowsBySymbol: data.rawRowsBySymbol || {},
          firstDateBySymbol: data.firstDateBySymbol || {},
          lastDateBySymbol: data.lastDateBySymbol || {},
          overallStartDate: data.overallStartDate || "",
          overallEndDate: data.overallEndDate || "",
          commonStartDate: data.commonStartDate || "",
          commonEndDate: data.commonEndDate || "",
          level2StatsBySymbol: data.level2StatsBySymbol || {},
          latestReconciliation: data.latestReconciliation || null,
          message: data.message || "",
          storagePath: data.storagePath || "market_data/futures",
          timeframe: data.timeframe || "1Min",
        };
        setMarketData(nextMarketData);
      })
      .catch((error) => {
        console.error("Error loading futures market data:", error);
        setMarketData({ symbols: [], rowsBySymbol: {}, message: "", storagePath: "market_data/futures" });
      })
      .finally(() => setIsMarketDataLoading(false));
  }

  function loadFundedProfiles() {
    apiFetch("/api/futures/funded-rule-profiles")
      .then((response) => response.json())
      .then((data) => setFundedProfiles(Array.isArray(data) ? data : []))
      .catch((error) => {
        console.error("Error loading funded rule profiles:", error);
        setFundedProfiles([]);
      });
  }

  function loadStrategyPresets() {
    apiFetch("/api/futures/strategy-presets")
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load strategy presets.");
        return response.json();
      })
      .then((data) => {
        const presets = mergeStrategyPresets(data);
        setStrategyPresets(presets);
        if (presets.length && !presets.some((preset) => preset.name === config.strategyPreset)) {
          setConfig((current) => ({ ...current, strategyPreset: presets[0].name }));
        }
      })
      .catch((error) => {
        console.error("Error loading futures strategy presets:", error);
        setStrategyPresets(CANONICAL_STRATEGY_PRESETS);
      });
  }

  function applyPortfolioDefaults(payload) {
    const symbolList = parseSymbols(payload?.symbolList || payload?.symbols || MAIN_PORTFOLIO_SYMBOLS.join(","));
    const nextSymbols = mainPortfolioOrPayloadSymbols(symbolList);
    const nextConfig = {
      strategyPreset: DEFAULT_CONFIG.strategyPreset,
      referenceSymbol: nextSymbols.includes(DEFAULT_CONFIG.referenceSymbol) ? DEFAULT_CONFIG.referenceSymbol : nextSymbols[0],
      fundedProfile: normalizeRiskProfileCode(payload?.fundedProfile || DEFAULT_CONFIG.fundedProfile),
      startDate: String(payload?.startDate || DEFAULT_CONFIG.startDate),
      endDate: defaultEndDate(),
      accountSize: String(payload?.accountSize ?? DEFAULT_CONFIG.accountSize),
      maxTrailingDrawdown: String(payload?.maxTrailingDrawdown ?? DEFAULT_CONFIG.maxTrailingDrawdown),
      dailyLossLimit: String(payload?.dailyLossLimit ?? DEFAULT_CONFIG.dailyLossLimit),
      maxRiskPerTrade: String(payload?.maxRiskPerTrade ?? DEFAULT_CONFIG.maxRiskPerTrade),
      maxContracts: String(payload?.maxContracts ?? DEFAULT_CONFIG.maxContracts),
      commissionPerContract: String(payload?.commissionPerContract ?? DEFAULT_CONFIG.commissionPerContract),
      slippageTicks: String(payload?.slippageTicks ?? DEFAULT_CONFIG.slippageTicks),
      profitTarget: String(payload?.profitTarget ?? DEFAULT_CONFIG.profitTarget),
      maxOpenPositions: String(payload?.maxOpenPositions ?? DEFAULT_CONFIG.maxOpenPositions),
      maxAggregateContracts: String(payload?.maxAggregateContracts ?? DEFAULT_CONFIG.maxAggregateContracts),
      maxAggregateMiniUnits: String(payload?.maxAggregateMiniUnits ?? DEFAULT_CONFIG.maxAggregateMiniUnits),
      useSavedRisk: DEFAULT_CONFIG.useSavedRisk,
      continueAfterRuleViolation: String(payload?.continueAfterRuleViolation ?? DEFAULT_CONFIG.continueAfterRuleViolation),
      qualitativeRiskEnabled: DEFAULT_CONFIG.qualitativeRiskEnabled,
      dtmEnabled: String(payload?.dtmEnabled ?? DEFAULT_CONFIG.dtmEnabled),
      riskSizingMode: DEFAULT_CONFIG.riskSizingMode,
      sourcePortfolioBacktestId: String(payload?.sourcePortfolioBacktestId ?? DEFAULT_CONFIG.sourcePortfolioBacktestId),
    };
    setConfig(nextConfig);
    setBatchSymbols(nextSymbols);
  }

  function updateConfig(field, value) {
    setConfig((current) => {
      const next = { ...current, [field]: value };
      if (field === "fundedProfile") {
        const selectedProfile = riskProfileOptions.find((profile) => profile.code === value);
        return selectedProfile ? applyFundedProfile(next, selectedProfile) : next;
      }
      return next;
    });
  }

  function toggleBatchSymbol(symbol) {
    setBatchSymbols((current) => {
      if (current.includes(symbol)) {
        const next = current.filter((item) => item !== symbol);
        return next.length ? next : current;
      }
      return [...current, symbol];
    });
  }

  async function startRun() {
    if (!canRun) {
      const missing = missingDataSymbols.length ? ` Missing data: ${missingDataSymbols.join(", ")}.` : "";
      setFeedback(`Portfolio run needs every selected contract to have data.${missing}`);
      return;
    }

    setIsRunning(true);
    setFeedback(`Starting portfolio run for ${selectedSymbols.join(", ")}...`);

    const params = new URLSearchParams({
      strategyPreset: config.strategyPreset || DEFAULT_STRATEGY_PRESET,
      symbols: selectedSymbols.join(","),
      startDate: config.startDate,
      endDate: config.endDate,
      fundedProfile: config.fundedProfile,
      accountSize: config.accountSize,
      maxTrailingDrawdown: config.maxTrailingDrawdown,
      dailyLossLimit: config.dailyLossLimit,
      maxRiskPerTrade: config.maxRiskPerTrade,
      maxContracts: config.maxContracts,
      commissionPerContract: config.commissionPerContract,
      slippageTicks: config.slippageTicks,
      profitTarget: config.profitTarget,
      maxOpenPositions: config.maxOpenPositions,
      maxAggregateContracts: config.maxAggregateContracts,
      maxAggregateMiniUnits: config.maxAggregateMiniUnits,
      useSavedRisk: "false",
      continueAfterRuleViolation: config.continueAfterRuleViolation === "true" ? "true" : "false",
      qualitativeRiskEnabled: "true",
      dtmEnabled: config.dtmEnabled === "true" ? "true" : "false",
      riskSizingMode: config.riskSizingMode || DEFAULT_CONFIG.riskSizingMode,
      sourcePortfolioBacktestId: config.sourcePortfolioBacktestId || DEFAULT_CONFIG.sourcePortfolioBacktestId,
    });

    try {
      const response = await apiFetch(`/api/futures/portfolio-backtests/generate?${params.toString()}`, {
        method: "POST",
      });
      const payload = await readApiResponse(response);
      if (!response.ok) {
        throw new Error(payload.json?.message || payload.text || "Failed to generate futures portfolio run.");
      }
      navigate("/futures-backtest-history");
    } catch (error) {
      console.error("Error generating futures portfolio run:", error);
      setFeedback(error.message || "Failed to generate futures portfolio run.");
    } finally {
      setIsRunning(false);
    }
  }

  async function refreshMarketData() {
    setIsMarketDataRefreshing(true);
    setFeedback(`Refreshing data for ${selectedSymbols.join(", ")}...`);

    const params = new URLSearchParams({
      symbols: selectedSymbols.join(","),
      startDate: config.startDate,
      endDate: config.endDate,
      schema: "ohlcv-1m",
    });

    try {
      const response = await apiFetch(`/api/futures/market-data/update-backtest-data?${params.toString()}`, {
        method: "POST",
      });
      const payload = await readApiResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(payload.json?.message || payload.text || "Failed to refresh futures market data.");
      }
      setFeedback(payload.json?.message || "Market data refreshed.");
      loadMarketDataStatus();
    } catch (error) {
      console.error("Error refreshing futures market data:", error);
      setFeedback(error.message || "Failed to refresh futures market data.");
    } finally {
      setIsMarketDataRefreshing(false);
    }
  }

  return (
    <div className="app-page futures-config-page">
      <div className="d-flex align-items-start justify-content-between gap-3 flex-wrap mb-3">
        <div>
          <div className="app-kicker">Futures</div>
          <h2 className="app-title mb-0">Portfolio Backtest</h2>
        </div>
        <button type="button" className="app-btn px-3" onClick={loadPortfolioDefaults}>
          Reset Run Config
        </button>
      </div>

      <div className="app-panel">
        <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap mb-3">
          <div className="fw-bold app-kicker">Strategy Config</div>
        </div>
        <div className="app-data-toolbar futures-strategy-slot-toolbar">
          <div className="app-data-chip">
            <span className="app-label">Selected Preset</span>
            <strong>{selectedPresetLabel}</strong>
          </div>
          <div className="app-data-chip">
            <span className="app-label">Mode</span>
            <strong>Backtest</strong>
          </div>
          <div className="app-data-chip">
            <span className="app-label">Contracts</span>
            <strong>{selectedSymbols.join(", ")}</strong>
          </div>
        </div>
      </div>

      <div className="app-panel">
        <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap mb-3">
          <div className="fw-bold app-kicker">Portfolio Market Data</div>
          <button
            type="button"
            className="app-btn app-btn-small px-3"
            onClick={refreshMarketData}
            disabled={isMarketDataRefreshing || isMarketDataLoading || selectedSymbols.length === 0}
          >
            {isMarketDataRefreshing ? "Updating..." : "Update Data"}
          </button>
        </div>

        <div className="app-data-toolbar futures-data-toolbar">
          <div className="app-data-chip futures-data-chip futures-storage-chip" title={marketData.storagePath || "market_data/futures"}>
            <span className="app-label">Storage</span>
            <strong>{marketData.storagePath || "market_data/futures"}</strong>
          </div>
          <div className="app-data-chip futures-data-chip" title={marketData.timeframe || "1Min"}>
            <span className="app-label">Timeframe</span>
            <strong>{marketData.timeframe || "1Min"}</strong>
          </div>
          <div className="app-data-chip futures-data-chip">
            <span className="app-label">Selected L1 Rows</span>
            <strong>{isMarketDataLoading ? "Loading..." : formatNumber(selectedRows, 0)}</strong>
          </div>
          <div className="app-data-chip futures-data-chip">
            <span className="app-label">Selected L2 Real</span>
            <strong>{isMarketDataLoading ? "Loading..." : formatNumber(selectedLevel2CapturedRows, 0)}</strong>
          </div>
          <div className="app-data-chip futures-data-chip">
            <span className="app-label">Selected L2 Derived</span>
            <strong>{isMarketDataLoading ? "Loading..." : formatNumber(selectedLevel2DerivedRows, 0)}</strong>
          </div>
          <div className="app-data-chip futures-data-chip">
            <span className="app-label">L2 Coverage</span>
            <strong>{isMarketDataLoading ? "Loading..." : `${formatNumber(selectedLevel2Coverage, 1)}%`}</strong>
          </div>
        </div>
        {marketData.latestReconciliation && (
          <div className="app-muted app-kicker mt-3">
            Last reconciliation: {marketData.latestReconciliation.status || "unknown"} {marketData.latestReconciliation.completedAt ? `at ${marketData.latestReconciliation.completedAt}` : ""}
          </div>
        )}
      </div>

      <div className="app-panel">
        <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap mb-3">
          <div className="fw-bold app-kicker">Portfolio Run Builder</div>
          <button type="button" className="app-btn app-btn-primary px-3" onClick={startRun} disabled={isRunning || !canRun}>
            {isRunning ? "Running..." : "Start Portfolio Run"}
          </button>
        </div>

        <div className="row g-3">
          <Field label="Strategy Config" className="col-12 col-md-4 col-xl-3">
            <select
              value={config.strategyPreset || DEFAULT_STRATEGY_PRESET}
              onChange={(event) => updateConfig("strategyPreset", event.target.value)}
              className="form-select app-input"
            >
              {presetOptions.map((preset) => (
                <option key={preset.name} value={preset.name}>
                  {preset.label || preset.name}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Risk Config" className="col-12 col-md-4 col-xl-3">
            <select
              value={config.fundedProfile}
              onChange={(event) => updateConfig("fundedProfile", event.target.value)}
              className="form-select app-input"
            >
              {riskProfileOptions.map((profile) => (
                <option key={profile.code} value={profile.code}>
                  {profile.name}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Run Start" className="col-12 col-md-4 col-xl-3">
            <input type="date" value={config.startDate} onChange={(event) => updateConfig("startDate", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Run End" className="col-12 col-md-4 col-xl-3">
            <input type="date" value={config.endDate} onChange={(event) => updateConfig("endDate", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Account Size ($)" className="col-12 col-md-4 col-xl-3">
            <input type="number" value={config.accountSize} onChange={(event) => updateConfig("accountSize", event.target.value)} className="form-control app-input" readOnly={riskConfigLocked} />
          </Field>

          <Field label="Trailing Drawdown ($)" className="col-12 col-md-4 col-xl-3">
            <input type="number" value={config.maxTrailingDrawdown} onChange={(event) => updateConfig("maxTrailingDrawdown", event.target.value)} className="form-control app-input" readOnly={riskConfigLocked} />
          </Field>

          <Field label="Daily Loss Limit ($)" className="col-12 col-md-4 col-xl-3">
            <input type="number" value={config.dailyLossLimit} onChange={(event) => updateConfig("dailyLossLimit", event.target.value)} className="form-control app-input" readOnly={riskConfigLocked} />
          </Field>

          <Field label="Max Risk / Trade ($)" className="col-12 col-md-4 col-xl-3">
            <input type="number" value={config.maxRiskPerTrade} onChange={(event) => updateConfig("maxRiskPerTrade", event.target.value)} className="form-control app-input" readOnly={riskConfigLocked} />
          </Field>

          <Field label="Max Contracts" className="col-12 col-md-4 col-xl-3">
            <input type="number" value={config.maxContracts} onChange={(event) => updateConfig("maxContracts", event.target.value)} className="form-control app-input" readOnly={riskConfigLocked} />
          </Field>

          <Field label="Commission / Contract ($)" className="col-12 col-md-4 col-xl-3">
            <input type="number" step="0.01" value={config.commissionPerContract} onChange={(event) => updateConfig("commissionPerContract", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Slippage (Ticks)" className="col-12 col-md-4 col-xl-3">
            <input type="number" step="0.25" value={config.slippageTicks} onChange={(event) => updateConfig("slippageTicks", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Profit Target Stop ($)" className="col-12 col-md-4 col-xl-3">
            <input type="number" value={config.profitTarget} onChange={(event) => updateConfig("profitTarget", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Max Open Positions" className="col-12 col-md-4 col-xl-3">
            <input type="number" min="1" value={config.maxOpenPositions} onChange={(event) => updateConfig("maxOpenPositions", event.target.value)} className="form-control app-input" readOnly={riskConfigLocked} />
          </Field>

          <Field label="Max Aggregate Contracts" className="col-12 col-md-4 col-xl-3">
            <input type="number" min="1" value={config.maxAggregateContracts} onChange={(event) => updateConfig("maxAggregateContracts", event.target.value)} className="form-control app-input" readOnly={riskConfigLocked} />
          </Field>

          <Field label="Funded Contract Units" className="col-12 col-md-4 col-xl-3">
            <input type="number" min="0" step="0.1" value={config.maxAggregateMiniUnits} onChange={(event) => updateConfig("maxAggregateMiniUnits", event.target.value)} className="form-control app-input" readOnly={riskConfigLocked} />
          </Field>

          <Field label="Risk Sizing" className="col-12 col-md-4 col-xl-3">
            <select
              value={config.riskSizingMode || DEFAULT_CONFIG.riskSizingMode}
              onChange={(event) => updateConfig("riskSizingMode", event.target.value)}
              className="form-select app-input"
            >
              <option value="STATIC_WITHDRAW_DAILY">Static baseline</option>
              <option value="DYNAMIC_COMPOUND_MLL">Dynamic DLL/MLL</option>
            </select>
          </Field>

          <div className="col-12 col-md-4 col-xl-3">
            <label className="app-toggle-row h-100">
              <input
                type="checkbox"
                checked={config.continueAfterRuleViolation === "true"}
                onChange={(event) => updateConfig("continueAfterRuleViolation", event.target.checked ? "true" : "false")}
              />
              {ruleModeLabel}
            </label>
          </div>

          <div className="col-12 col-md-4 col-xl-3">
            <label className="app-toggle-row h-100">
              <input
                type="checkbox"
                checked={config.dtmEnabled === "true"}
                onChange={(event) => updateConfig("dtmEnabled", event.target.checked ? "true" : "false")}
              />
              DTM
            </label>
          </div>

          <Field label="Reference Contract" className="col-12 col-md-4 col-xl-3">
            <select
              value={config.referenceSymbol}
              onChange={(event) => updateConfig("referenceSymbol", event.target.value)}
              className="form-select app-input"
            >
              {(instruments.length ? instruments : INSTRUMENT_FALLBACKS).map((instrument) => (
                <option key={instrument.symbol} value={instrument.symbol}>
                  {instrument.symbol} - {instrument.name}
                </option>
              ))}
            </select>
          </Field>

          <div className="col-12 col-md-4 col-xl-3">
            <div className="app-data-chip h-100">
              <span className="app-label">Template Specs</span>
              <strong>{selectedInstrument ? `${selectedInstrument.tickSize} tick / $${selectedInstrument.tickValue}` : "Loading"}</strong>
            </div>
          </div>

          <div className="col-12">
            <div className="app-subpanel futures-run-builder">
              <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap">
                <div>
                  <div className="fw-bold app-kicker">Contracts in Portfolio</div>
                  <div className={missingDataSymbols.length ? "app-pnl-neg app-kicker" : "app-muted app-kicker"}>
                    {selectedSymbols.length} selected{missingDataSymbols.length ? `, missing data for ${missingDataSymbols.join(", ")}` : ""}
                  </div>
                </div>
              </div>

              <div className="futures-contract-selector">
                {(instruments.length ? instruments : INSTRUMENT_FALLBACKS).map((instrument) => {
                  const rows = Number(marketData.rowsBySymbol?.[instrument.symbol] || 0);
                  return (
                    <label className="app-toggle-row" key={instrument.symbol}>
                      <input type="checkbox" checked={selectedSymbols.includes(instrument.symbol)} onChange={() => toggleBatchSymbol(instrument.symbol)} />
                      {instrument.symbol} ({isMarketDataLoading ? "..." : formatNumber(rows, 0)})
                    </label>
                  );
                })}
              </div>
            </div>
          </div>

          {feedback && <div className="col-12 app-muted app-kicker">{feedback}</div>}
        </div>
      </div>
    </div>
  );
}

function Field({ label, children, className = "col" }) {
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

function defaultEndDate() {
  const date = new Date();
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function formatNumber(value, decimals = 2) {
  return Number(value || 0).toLocaleString(undefined, {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

function buildInstrumentOptions(apiInstruments = []) {
  const bySymbol = new Map(INSTRUMENT_FALLBACKS.map((instrument) => [instrument.symbol, { ...instrument }]));
  const apiList = Array.isArray(apiInstruments) ? apiInstruments : [];
  apiList.forEach((instrument) => {
    const symbol = String(instrument?.symbol || "").trim().toUpperCase();
    if (!symbol) return;
    bySymbol.set(symbol, { ...(bySymbol.get(symbol) || {}), ...instrument, symbol });
  });

  const orderedSymbols = [...MAIN_PORTFOLIO_SYMBOLS, "GC"];
  const ordered = [];
  const seen = new Set();
  orderedSymbols.forEach((symbol) => {
    const instrument = bySymbol.get(symbol);
    if (instrument && !seen.has(symbol)) {
      ordered.push(instrument);
      seen.add(symbol);
    }
  });
  apiList.forEach((instrument) => {
    const symbol = String(instrument?.symbol || "").trim().toUpperCase();
    if (!symbol || seen.has(symbol)) return;
    ordered.push(bySymbol.get(symbol));
    seen.add(symbol);
  });
  return ordered.length ? ordered : INSTRUMENT_FALLBACKS;
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

function mainPortfolioOrPayloadSymbols(symbols = []) {
  const cleaned = symbols.filter(Boolean);
  const hasAllMainSymbols = MAIN_PORTFOLIO_SYMBOLS.every((symbol) => cleaned.includes(symbol));
  return hasAllMainSymbols ? cleaned : MAIN_PORTFOLIO_SYMBOLS;
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

function normalizeRiskProfileCode(code) {
  const normalized = String(code || "").trim().toUpperCase();
  if (normalized === "TOPSTEP_50K_COMBINE" || normalized === "TOPSTEP_50K_RESEARCH") return "TOPSTEP_50K";
  if (normalized === "TOPSTEP_100K_COMBINE" || normalized === "TOPSTEP_100K_RESEARCH") return "TOPSTEP_100K";
  if (normalized === "TOPSTEP_150K_PRACTICE" || normalized === "TOPSTEP_150K_RESEARCH") return "TOPSTEP_150K";
  if (RISK_PROFILE_FALLBACKS.some((profile) => profile.code === normalized)) return normalized;
  return DEFAULT_RISK_PROFILE;
}

function applyFundedProfile(config, profile) {
  if (!profile) {
    return config;
  }
  return {
    ...config,
    fundedProfile: profile.code,
    accountSize: String(profile.accountSize ?? config.accountSize),
    maxTrailingDrawdown: String(profile.maxTrailingDrawdown ?? config.maxTrailingDrawdown),
    dailyLossLimit: String(profile.dailyLossLimit ?? config.dailyLossLimit),
    maxRiskPerTrade: String(profile.maxRiskPerTrade ?? config.maxRiskPerTrade),
    maxContracts: String(contractLimitForProfile(profile, config.referenceSymbol)),
    profitTarget: String(profile.profitTarget ?? config.profitTarget),
    maxOpenPositions: String(profile.maxOpenPositions ?? config.maxOpenPositions),
    maxAggregateContracts: String(profile.maxAggregateContracts ?? config.maxAggregateContracts),
    maxAggregateMiniUnits: String(profile.maxAggregateMiniUnits ?? config.maxAggregateMiniUnits),
  };
}

function contractLimitForProfile(profile, symbol) {
  if (!profile) return "1";
  return MICRO_SYMBOLS.has(symbol) ? profile.maxMicroContracts || profile.maxAggregateContracts || 50 : profile.maxContracts || 5;
}

function parseSymbols(value) {
  if (Array.isArray(value)) {
    return value.map((item) => String(item || "").trim().toUpperCase()).filter(Boolean);
  }
  return String(value || "")
    .split(",")
    .map((item) => item.trim().toUpperCase())
    .filter(Boolean);
}
