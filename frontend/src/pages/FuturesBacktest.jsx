import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { API_BASE_URL, apiFetch } from "../utils/api.js";

const TOPSTEP_RESEARCH_PROFILE = "TOPSTEP_50K_RESEARCH";
const MAIN_PORTFOLIO_SYMBOLS = ["MES", "MNQ", "NQ", "MGC", "ES", "M2K"];
const MICRO_SYMBOLS = new Set(["MES", "MNQ", "M2K", "MGC"]);

const DEFAULT_CONFIG = {
  sourcePortfolioBacktestId: "3154",
  referenceSymbol: "MNQ",
  fundedProfile: TOPSTEP_RESEARCH_PROFILE,
  startDate: "2025-05-01",
  endDate: "2026-05-04",
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
  useSavedRisk: "true",
};

const DEFAULT_SOURCE_SUMMARY = {
  totalProfit: 80249.81,
  trades: 999,
  winRate: 75.28,
  profitFactor: 2.9,
};

const DEFAULT_DATA_CONFIG = {
  startDate: DEFAULT_CONFIG.startDate,
  endDate: defaultEndDate(),
  schema: "ohlcv-1m",
};

export default function FuturesBacktest() {
  const navigate = useNavigate();
  const [config, setConfig] = useState(DEFAULT_CONFIG);
  const [sourceSummary, setSourceSummary] = useState(DEFAULT_SOURCE_SUMMARY);
  const [dataConfig, setDataConfig] = useState(DEFAULT_DATA_CONFIG);
  const [instruments, setInstruments] = useState([]);
  const [marketData, setMarketData] = useState({ symbols: [], rowsBySymbol: {}, message: "", storagePath: "" });
  const [isMarketDataLoading, setIsMarketDataLoading] = useState(true);
  const [isUpdatingData, setIsUpdatingData] = useState(false);
  const [isRunning, setIsRunning] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [dataFeedback, setDataFeedback] = useState("");
  const [batchSymbols, setBatchSymbols] = useState(MAIN_PORTFOLIO_SYMBOLS);
  const [fundedProfiles, setFundedProfiles] = useState([]);

  useEffect(() => {
    loadInstruments();
    loadMarketDataStatus(true);
    loadFundedProfiles();
    loadPortfolioDefaults();
    // The initial page bootstrap should run once; each loader owns its own state updates.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const selectedSymbols = useMemo(() => {
    const available = new Set((instruments.length ? instruments : MAIN_PORTFOLIO_SYMBOLS.map((symbol) => ({ symbol }))).map((instrument) => instrument.symbol));
    const filtered = batchSymbols.map((symbol) => symbol.toUpperCase()).filter((symbol) => available.has(symbol));
    return filtered.length ? filtered : MAIN_PORTFOLIO_SYMBOLS;
  }, [batchSymbols, instruments]);

  const selectedRows = selectedSymbols.reduce((total, symbol) => total + Number(marketData.rowsBySymbol?.[symbol] || 0), 0);
  const missingDataSymbols = selectedSymbols.filter((symbol) => Number(marketData.rowsBySymbol?.[symbol] || 0) <= 0);
  const selectedInstrument = instruments.find((instrument) => instrument.symbol === config.referenceSymbol) || instruments[0] || null;
  const canRun = Boolean(config.startDate && config.endDate && selectedSymbols.length > 0 && missingDataSymbols.length === 0);
  const canUpdateData = Boolean(dataConfig.startDate && dataConfig.endDate && selectedSymbols.length > 0);

  function loadInstruments() {
    apiFetch("/api/futures/instruments")
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures instruments.");
        return response.json();
      })
      .then((data) => setInstruments(Array.isArray(data) ? data : []))
      .catch((error) => {
        console.error("Error loading futures instruments:", error);
        setInstruments([]);
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

  function loadMarketDataStatus(syncDateRange = false) {
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
          message: data.message || "",
          storagePath: data.storagePath || "market_data/futures",
          timeframe: data.timeframe || "1Min",
        };
        setMarketData(nextMarketData);
        if (syncDateRange) {
          applyMarketDateRangeToData(nextMarketData);
        }
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

  function applyPortfolioDefaults(payload) {
    const symbolList = parseSymbols(payload?.symbolList || payload?.symbols || MAIN_PORTFOLIO_SYMBOLS.join(","));
    const nextSymbols = symbolList.length ? symbolList : MAIN_PORTFOLIO_SYMBOLS;
    const nextConfig = {
      sourcePortfolioBacktestId: String(payload?.sourcePortfolioBacktestId || DEFAULT_CONFIG.sourcePortfolioBacktestId),
      referenceSymbol: nextSymbols.includes(DEFAULT_CONFIG.referenceSymbol) ? DEFAULT_CONFIG.referenceSymbol : nextSymbols[0],
      fundedProfile: String(payload?.fundedProfile || DEFAULT_CONFIG.fundedProfile),
      startDate: String(payload?.startDate || DEFAULT_CONFIG.startDate),
      endDate: String(payload?.endDate || DEFAULT_CONFIG.endDate),
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
      useSavedRisk: String(payload?.useSavedRisk ?? DEFAULT_CONFIG.useSavedRisk),
    };
    setConfig(nextConfig);
    setBatchSymbols(nextSymbols);
    setSourceSummary({
      totalProfit: Number(payload?.sourceMetrics?.totalProfit ?? DEFAULT_SOURCE_SUMMARY.totalProfit),
      trades: Number(payload?.sourceMetrics?.trades ?? DEFAULT_SOURCE_SUMMARY.trades),
      winRate: Number(payload?.sourceMetrics?.winRate ?? DEFAULT_SOURCE_SUMMARY.winRate),
      profitFactor: Number(payload?.sourceMetrics?.profitFactor ?? DEFAULT_SOURCE_SUMMARY.profitFactor),
    });
  }

  function applyMarketDateRangeToData(data) {
    const startDate = data.commonStartDate || data.overallStartDate || DEFAULT_DATA_CONFIG.startDate;
    const endDate = data.commonEndDate || data.overallEndDate || DEFAULT_DATA_CONFIG.endDate;
    setDataConfig((current) => ({ ...current, startDate, endDate }));
  }

  function updateConfig(field, value) {
    setConfig((current) => {
      const next = { ...current, [field]: value };
      if (field === "fundedProfile") {
        const selectedProfile = fundedProfiles.find((profile) => profile.code === value);
        return selectedProfile ? applyFundedProfile(next, selectedProfile) : next;
      }
      return next;
    });
  }

  function updateDataConfig(field, value) {
    setDataConfig((current) => ({ ...current, [field]: value }));
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

  async function updateBacktestData() {
    if (!canUpdateData) {
      setDataFeedback("Select at least one contract and a valid date range.");
      return;
    }

    setIsUpdatingData(true);
    setDataFeedback(`Updating portfolio data for ${selectedSymbols.join(", ")}...`);

    const endpoint = "/api/futures/market-data/update-backtest-data";
    const params = new URLSearchParams({
      ...dataConfig,
      symbols: selectedSymbols.join(","),
    });

    try {
      const response = await apiFetch(`${endpoint}?${params.toString()}`, {
        method: "POST",
      });
      const payload = await readApiResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(formatUpdateErrorMessage(response, payload, {
          endpoint,
          symbols: selectedSymbols,
          startDate: dataConfig.startDate,
          endDate: dataConfig.endDate,
        }));
      }
      setDataFeedback(formatDataUpdateMessage(payload.json));
      loadMarketDataStatus(true);
    } catch (error) {
      console.error("Error updating futures backtest data:", error);
      setDataFeedback(error.message || "Backtest data update failed.");
    } finally {
      setIsUpdatingData(false);
    }
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
      sourcePortfolioBacktestId: config.sourcePortfolioBacktestId,
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
      useSavedRisk: config.useSavedRisk,
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

  return (
    <div className="app-page futures-config-page">
      <div className="d-flex align-items-start justify-content-between gap-3 flex-wrap mb-3">
        <div>
          <div className="app-kicker">Futures</div>
          <h2 className="app-title mb-0">Portfolio Backtest</h2>
        </div>
        <button type="button" className="app-btn px-3" onClick={loadPortfolioDefaults}>
          Reset Strategy Slot
        </button>
      </div>

      <div className="app-panel">
        <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap mb-3">
          <div className="fw-bold app-kicker">Backtest Strategy Slot</div>
        </div>
        <div className="app-data-toolbar futures-strategy-slot-toolbar">
          <Metric label="Win" value={`${formatNumber(sourceSummary.winRate, 2)}%`} />
          <Metric label="PnL" value={formatCurrency(sourceSummary.totalProfit)} tone="pos" />
          <Metric label="Trades" value={formatNumber(sourceSummary.trades, 0)} />
        </div>
      </div>

      <div className="app-panel">
        <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap mb-3">
          <div className="fw-bold app-kicker">Portfolio Market Data</div>
          <button
            type="button"
            className="app-btn app-btn-primary px-3"
            onClick={updateBacktestData}
            disabled={isUpdatingData || !canUpdateData}
          >
            {isUpdatingData ? "Updating..." : "Update Data"}
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
            <span className="app-label">Selected Rows</span>
            <strong>{isMarketDataLoading ? "Loading..." : formatNumber(selectedRows, 0)}</strong>
          </div>
          <Field label="Update Start" className="futures-data-field">
            <input
              type="date"
              value={dataConfig.startDate}
              max={dataConfig.endDate || undefined}
              onChange={(event) => updateDataConfig("startDate", event.target.value)}
              className="form-control app-input"
            />
          </Field>
          <Field label="Update End" className="futures-data-field">
            <input
              type="date"
              value={dataConfig.endDate}
              min={dataConfig.startDate || undefined}
              onChange={(event) => updateDataConfig("endDate", event.target.value)}
              className="form-control app-input"
            />
          </Field>
        </div>
        {dataFeedback && (
          <div
            className={`${dataFeedback.startsWith("Backtest data update failed") ? "app-pnl-neg" : "app-muted"} app-kicker mt-3`}
            style={{ whiteSpace: "pre-line" }}
          >
            {dataFeedback}
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
          <Field label="Topstep Account" className="col-12 col-md-4 col-xl-3">
            <select
              value={config.fundedProfile}
              onChange={(event) => updateConfig("fundedProfile", event.target.value)}
              className="form-select app-input"
            >
              {(fundedProfiles.length ? fundedProfiles : [{ code: TOPSTEP_RESEARCH_PROFILE, name: "Topstep 50K Research" }]).map((profile) => (
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
            <input type="number" value={config.accountSize} onChange={(event) => updateConfig("accountSize", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Trailing Drawdown ($)" className="col-12 col-md-4 col-xl-3">
            <input type="number" value={config.maxTrailingDrawdown} onChange={(event) => updateConfig("maxTrailingDrawdown", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Daily Loss Limit ($)" className="col-12 col-md-4 col-xl-3">
            <input type="number" value={config.dailyLossLimit} onChange={(event) => updateConfig("dailyLossLimit", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Max Risk / Trade ($)" className="col-12 col-md-4 col-xl-3">
            <input type="number" value={config.maxRiskPerTrade} onChange={(event) => updateConfig("maxRiskPerTrade", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Max Contracts" className="col-12 col-md-4 col-xl-3">
            <input type="number" value={config.maxContracts} onChange={(event) => updateConfig("maxContracts", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Commission / Contract ($)" className="col-12 col-md-4 col-xl-3">
            <input type="number" step="0.01" value={config.commissionPerContract} onChange={(event) => updateConfig("commissionPerContract", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Slippage (Ticks)" className="col-12 col-md-4 col-xl-3">
            <input type="number" step="0.25" value={config.slippageTicks} onChange={(event) => updateConfig("slippageTicks", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Profit Target ($)" className="col-12 col-md-4 col-xl-3">
            <input type="number" value={config.profitTarget} onChange={(event) => updateConfig("profitTarget", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Max Open Positions" className="col-12 col-md-4 col-xl-3">
            <input type="number" min="1" value={config.maxOpenPositions} onChange={(event) => updateConfig("maxOpenPositions", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Max Aggregate Contracts" className="col-12 col-md-4 col-xl-3">
            <input type="number" min="1" value={config.maxAggregateContracts} onChange={(event) => updateConfig("maxAggregateContracts", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Funded Contract Units" className="col-12 col-md-4 col-xl-3">
            <input type="number" min="0" step="0.1" value={config.maxAggregateMiniUnits} onChange={(event) => updateConfig("maxAggregateMiniUnits", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="Reference Contract" className="col-12 col-md-4 col-xl-3">
            <select
              value={config.referenceSymbol}
              onChange={(event) => updateConfig("referenceSymbol", event.target.value)}
              className="form-select app-input"
            >
              {(instruments.length ? instruments : MAIN_PORTFOLIO_SYMBOLS.map((symbol) => ({ symbol, name: symbol }))).map((instrument) => (
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
                {(instruments.length ? instruments : MAIN_PORTFOLIO_SYMBOLS.map((symbol) => ({ symbol }))).map((instrument) => {
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

function Metric({ label, value, tone = "" }) {
  const toneClass = tone === "pos" ? "app-pnl-pos" : tone === "neg" ? "app-pnl-neg" : "";
  return (
    <div className="app-data-chip">
      <span className="app-label">{label}</span>
      <strong className={toneClass}>{value}</strong>
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

function formatUpdateErrorMessage(response, payload, request) {
  const serverMessage = cleanErrorText(payload.json?.message || payload.json?.error || payload.text);
  const statusLine = response.ok
    ? "Backtest data update failed."
    : `Backtest data update failed: HTTP ${response.status}${response.statusText ? ` ${response.statusText}` : ""}.`;
  const details = [
    statusLine,
    `Endpoint: POST ${request.endpoint}`,
    `Backend: ${API_BASE_URL}`,
    `Symbols: ${request.symbols.join(", ")}`,
    `Range: ${request.startDate} to ${request.endDate}`,
  ];
  if (serverMessage) {
    details.push(`Server said: ${serverMessage}`);
  }
  if (response.status === 404) {
    details.push("Likely cause: this frontend is pointed at a backend that does not have the update-backtest-data route yet.");
  } else if (response.status === 401 || response.status === 403) {
    details.push("Likely cause: the backend rejected the request because the current session or credentials are not allowed.");
  } else if (response.status >= 500) {
    details.push("Likely cause: the backend hit an internal error while pulling or merging futures data.");
  } else if (payload.json?.success === false && !serverMessage) {
    details.push("Likely cause: the backend returned success=false without a message.");
  }
  return details.join("\n");
}

function cleanErrorText(value) {
  const text = String(value || "").trim();
  if (!text) return "";
  return text.length > 500 ? `${text.slice(0, 500)}...` : text;
}

function defaultEndDate() {
  const date = new Date();
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function formatCurrency(value) {
  const numeric = Number(value || 0);
  const sign = numeric > 0 ? "+" : "";
  return `${sign}$${numeric.toFixed(2)}`;
}

function formatNumber(value, decimals = 2) {
  return Number(value || 0).toLocaleString(undefined, {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

function formatDataUpdateMessage(payload) {
  if (!payload || !Array.isArray(payload.symbols)) {
    return payload?.message || "Backtest data updated.";
  }
  const updated = payload.symbols
    .filter((symbol) => symbol?.success)
    .map((symbol) => `${symbol.symbol}: ${formatNumber(symbol.finalRows, 0)} rows`)
    .join(", ");
  return updated ? `${payload.message} ${updated}.` : payload.message || "Backtest data updated.";
}

function applyFundedProfile(config, profile) {
  if (!profile || profile.code === "CUSTOM") {
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
