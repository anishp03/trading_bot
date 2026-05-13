import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiFetch } from "../utils/api.js";

const TOPSTEP_PROFILE_CODE = "TOPSTEP_50K_COMBINE";
const MICRO_SYMBOLS = new Set(["MES", "MNQ", "M2K", "MGC"]);

const DEFAULT_CONFIG = {
  symbol: "MNQ",
  fundedProfile: TOPSTEP_PROFILE_CODE,
  startDate: defaultStartDate(),
  endDate: defaultEndDate(),
  accountSize: "50000",
  maxTrailingDrawdown: "2000",
  dailyLossLimit: "1000",
  maxRiskPerTrade: "400",
  maxContracts: "50",
  commissionPerContract: "1.24",
  slippageTicks: "1",
  profitTarget: "3000",
  maxOpenPositions: "2",
  maxAggregateContracts: "50",
  maxAggregateMiniUnits: "5",
};

export default function FuturesBacktest() {
  const navigate = useNavigate();
  const [config, setConfig] = useState(DEFAULT_CONFIG);
  const [instruments, setInstruments] = useState([]);
  const [marketData, setMarketData] = useState({ symbols: [], rowsBySymbol: {}, message: "", storagePath: "" });
  const [isMarketDataLoading, setIsMarketDataLoading] = useState(true);
  const [isRunning, setIsRunning] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [importConfig, setImportConfig] = useState({
    symbol: DEFAULT_CONFIG.symbol,
    startDate: DEFAULT_CONFIG.startDate,
    endDate: DEFAULT_CONFIG.endDate,
    schema: "ohlcv-1m",
  });
  const [isImporting, setIsImporting] = useState(false);
  const [isRebuildingDerived, setIsRebuildingDerived] = useState(false);
  const [importFeedback, setImportFeedback] = useState("");
  const [batchSymbols, setBatchSymbols] = useState(["MNQ"]);
  const [fundedProfiles, setFundedProfiles] = useState([]);

  useEffect(() => {
    loadInstruments();
    loadMarketDataStatus();
    loadFundedProfiles();
  }, []);

  useEffect(() => {
    loadRiskSettings(config.symbol);
  }, [config.symbol]);

  function loadInstruments() {
    apiFetch("/api/futures/instruments")
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures instruments.");
        return response.json();
      })
      .then((data) => {
        const nextInstruments = Array.isArray(data) ? data : [];
        setInstruments(nextInstruments);
        if (nextInstruments.length) {
          setConfig((current) => ({
            ...current,
            symbol: nextInstruments.some((instrument) => instrument.symbol === current.symbol)
              ? current.symbol
              : nextInstruments[0].symbol,
          }));
          setImportConfig((current) => ({
            ...current,
            symbol: nextInstruments.some((instrument) => instrument.symbol === current.symbol)
              ? current.symbol
              : nextInstruments[0].symbol,
          }));
        }
      })
      .catch((error) => {
        console.error("Error loading futures instruments:", error);
        setInstruments([]);
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
        setMarketData({
          symbols: Array.isArray(data.symbols) ? data.symbols : [],
          rowsBySymbol: data.rowsBySymbol || {},
          message: data.message || "",
          storagePath: data.storagePath || "market_data/futures",
          timeframe: data.timeframe || "1Min",
        });
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
      .then((data) => {
        const nextProfiles = Array.isArray(data) ? data : [];
        setFundedProfiles(nextProfiles);
        const topstepProfile = nextProfiles.find((profile) => profile.code === TOPSTEP_PROFILE_CODE);
        if (topstepProfile) {
          setConfig((current) => applyFundedProfile(current, topstepProfile));
        }
      })
      .catch((error) => {
        console.error("Error loading funded rule profiles:", error);
        setFundedProfiles([]);
      });
  }

  async function fetchRiskSettings(symbol) {
    const response = await apiFetch(`/api/futures/risk?symbol=${encodeURIComponent(symbol)}`);
    if (!response.ok) throw new Error(`Failed to load ${symbol} futures risk profile.`);
    return response.json();
  }

  function loadRiskSettings(symbol) {
    fetchRiskSettings(symbol)
      .then((data) => {
        setConfig((current) => {
          if (current.symbol !== symbol) return current;
          const activeProfile = fundedProfiles.find((profile) => profile.code === current.fundedProfile);
          if (activeProfile && activeProfile.code !== "CUSTOM") {
            return {
              ...applyFundedProfile(current, activeProfile),
              maxRiskPerTrade: String(data.maxRiskPerTrade ?? current.maxRiskPerTrade),
              commissionPerContract: String(data.commissionPerContract ?? current.commissionPerContract),
              slippageTicks: String(data.slippageTicks ?? current.slippageTicks),
            };
          }
          return {
            ...current,
            accountSize: String(data.accountSize ?? current.accountSize),
            maxTrailingDrawdown: String(data.maxTrailingDrawdown ?? current.maxTrailingDrawdown),
            dailyLossLimit: String(data.dailyLossLimit ?? current.dailyLossLimit),
            maxRiskPerTrade: String(data.maxRiskPerTrade ?? current.maxRiskPerTrade),
            maxContracts: String(data.maxContracts ?? current.maxContracts),
            commissionPerContract: String(data.commissionPerContract ?? current.commissionPerContract),
            slippageTicks: String(data.slippageTicks ?? current.slippageTicks),
            profitTarget: String(data.profitTarget ?? current.profitTarget),
          };
        });
      })
      .catch((error) => {
        console.error("Error loading futures risk profile:", error);
      });
  }

  async function runBacktest() {
    setIsRunning(true);
    setFeedback("");

    const params = new URLSearchParams(config);

    try {
      const response = await apiFetch(`/api/futures/backtests/generate?${params.toString()}`, {
        method: "POST",
      });
      const payload = await readApiResponse(response);
      if (!response.ok) {
        throw new Error(payload.message || payload.text || "Failed to generate futures backtest.");
      }
      navigate("/futures-backtest-history");
    } catch (error) {
      console.error("Error generating futures backtest:", error);
      setFeedback(error.message || "Failed to generate futures backtest.");
    } finally {
      setIsRunning(false);
    }
  }

  async function runBatchBacktests() {
    const symbolsToRun = batchSymbols.filter((symbol) => Number(marketData.rowsBySymbol?.[symbol] || 0) > 0);
    if (!symbolsToRun.length) {
      setFeedback("Import native futures bars for at least one selected contract before batch testing.");
      return;
    }

    setIsRunning(true);
    setFeedback(`Running ${symbolsToRun.length} contract batch...`);

    try {
      for (const symbol of symbolsToRun) {
        const riskProfile = await fetchRiskSettings(symbol);
        const activeProfile = fundedProfiles.find((profile) => profile.code === config.fundedProfile);
        const baseConfig = activeProfile && activeProfile.code !== "CUSTOM"
          ? applyFundedProfile({ ...config, symbol }, activeProfile)
          : { ...config, symbol };
        const params = new URLSearchParams({
          ...baseConfig,
          symbol,
          accountSize: activeProfile && activeProfile.code !== "CUSTOM" ? baseConfig.accountSize : String(riskProfile.accountSize ?? config.accountSize),
          maxTrailingDrawdown: activeProfile && activeProfile.code !== "CUSTOM" ? baseConfig.maxTrailingDrawdown : String(riskProfile.maxTrailingDrawdown ?? config.maxTrailingDrawdown),
          dailyLossLimit: activeProfile && activeProfile.code !== "CUSTOM" ? baseConfig.dailyLossLimit : String(riskProfile.dailyLossLimit ?? config.dailyLossLimit),
          maxRiskPerTrade: String(riskProfile.maxRiskPerTrade ?? config.maxRiskPerTrade),
          maxContracts: activeProfile && activeProfile.code !== "CUSTOM" ? baseConfig.maxContracts : String(riskProfile.maxContracts ?? config.maxContracts),
          commissionPerContract: String(riskProfile.commissionPerContract ?? config.commissionPerContract),
          slippageTicks: String(riskProfile.slippageTicks ?? config.slippageTicks),
          profitTarget: activeProfile && activeProfile.code !== "CUSTOM" ? baseConfig.profitTarget : String(riskProfile.profitTarget ?? config.profitTarget),
        });
        const response = await apiFetch(`/api/futures/backtests/generate?${params.toString()}`, {
          method: "POST",
        });
        const payload = await readApiResponse(response);
        if (!response.ok) {
          throw new Error(payload.message || payload.text || `Failed to generate ${symbol} futures backtest.`);
        }
      }
      navigate("/futures-backtest-history");
    } catch (error) {
      console.error("Error generating futures batch:", error);
      setFeedback(error.message || "Failed to generate futures batch.");
    } finally {
      setIsRunning(false);
    }
  }

  async function runPortfolioBacktest() {
    const symbolsToRun = batchSymbols.filter((symbol) => Number(marketData.rowsBySymbol?.[symbol] || 0) > 0);
    if (!symbolsToRun.length) {
      setFeedback("Import native futures bars for at least one selected contract before portfolio testing.");
      return;
    }

    setIsRunning(true);
    setFeedback(`Running portfolio test for ${symbolsToRun.join(", ")}...`);

    const params = new URLSearchParams({
      ...config,
      symbols: symbolsToRun.join(","),
      useSavedRisk: "true",
    });

    try {
      const response = await apiFetch(`/api/futures/portfolio-backtests/generate?${params.toString()}`, {
        method: "POST",
      });
      const payload = await readApiResponse(response);
      if (!response.ok) {
        throw new Error(payload.message || payload.text || "Failed to generate futures portfolio backtest.");
      }
      navigate("/futures-backtest-history");
    } catch (error) {
      console.error("Error generating futures portfolio backtest:", error);
      setFeedback(error.message || "Failed to generate futures portfolio backtest.");
    } finally {
      setIsRunning(false);
    }
  }

  function updateConfig(field, value) {
    setConfig((current) => {
      const next = { ...current, [field]: value };
      if (field === "fundedProfile") {
        const selectedProfile = fundedProfiles.find((profile) => profile.code === value);
        return selectedProfile ? applyFundedProfile(next, selectedProfile) : next;
      }
      if (field === "symbol" && next.fundedProfile !== "CUSTOM") {
        const activeProfile = fundedProfiles.find((profile) => profile.code === next.fundedProfile);
        return activeProfile ? applyFundedProfile(next, activeProfile) : next;
      }
      return next;
    });
  }

  function updateImportConfig(field, value) {
    setImportConfig((current) => ({ ...current, [field]: value }));
  }

  function toggleBatchSymbol(symbol) {
    setBatchSymbols((current) => {
      if (current.includes(symbol)) {
        return current.filter((item) => item !== symbol);
      }
      return [...current, symbol];
    });
  }

  async function importDatabentoBars() {
    setIsImporting(true);
    setImportFeedback("");

    const params = new URLSearchParams(importConfig);
    try {
      const response = await apiFetch(`/api/futures/market-data/databento/import?${params.toString()}`, {
        method: "POST",
      });
      const payload = await readApiResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(payload.json?.message || payload.text || "Databento import failed.");
      }
      setImportFeedback(payload.json?.message || "Databento import completed.");
      loadMarketDataStatus();
    } catch (error) {
      console.error("Error importing Databento bars:", error);
      setImportFeedback(error.message || "Databento import failed.");
    } finally {
      setIsImporting(false);
    }
  }

  async function rebuildDerivedBars() {
    setIsRebuildingDerived(true);
    setImportFeedback("");

    const params = new URLSearchParams({ symbol: config.symbol || importConfig.symbol || "MNQ" });
    try {
      const response = await apiFetch(`/api/futures/market-data/rebuild-derived?${params.toString()}`, {
        method: "POST",
      });
      const payload = await readApiResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(payload.json?.message || payload.text || "Derived futures rebuild failed.");
      }
      setImportFeedback(payload.json?.message || "Derived futures files rebuilt.");
      loadMarketDataStatus();
    } catch (error) {
      console.error("Error rebuilding derived futures bars:", error);
      setImportFeedback(error.message || "Derived futures rebuild failed.");
    } finally {
      setIsRebuildingDerived(false);
    }
  }

  const selectedInstrument = instruments.find((instrument) => instrument.symbol === config.symbol) || instruments[0] || null;
  const canRun = Boolean(config.symbol && config.startDate && config.endDate);
  const batchRunnableCount = batchSymbols.filter((symbol) => Number(marketData.rowsBySymbol?.[symbol] || 0) > 0).length;

  return (
    <div className="app-page futures-config-page">
      <h2 className="app-title">Futures Backtest</h2>

      <div className="app-panel">
        <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap mb-3">
          <div className="fw-bold app-kicker">Market Data</div>
          <div className="d-flex gap-2 flex-wrap">
            <button type="button" className="app-btn app-btn-small px-3" onClick={loadMarketDataStatus} disabled={isMarketDataLoading}>
              {isMarketDataLoading ? "Refreshing..." : "Refresh"}
            </button>
            <button type="button" className="app-btn app-btn-small px-3" onClick={rebuildDerivedBars} disabled={isRebuildingDerived}>
              {isRebuildingDerived ? "Rebuilding..." : "Rebuild Derived"}
            </button>
          </div>
        </div>

        <div className="app-data-toolbar">
          <div className="app-data-chip">
            <span className="app-label">Storage</span>
            <strong>{marketData.storagePath || "market_data/futures"}</strong>
          </div>
          <div className="app-data-chip">
            <span className="app-label">Timeframe</span>
            <strong>{marketData.timeframe || "1Min"}</strong>
          </div>
          <div className="app-data-chip">
            <span className="app-label">Selected Rows</span>
            <strong>{isMarketDataLoading ? "Loading..." : formatNumber(marketData.rowsBySymbol?.[config.symbol] || 0, 0)}</strong>
          </div>
        </div>
      </div>

      <div className="app-panel">
        <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap mb-3">
          <div className="fw-bold app-kicker">Databento Import</div>
          <button
            type="button"
            className="app-btn app-btn-primary px-3"
            onClick={importDatabentoBars}
            disabled={isImporting}
          >
            {isImporting ? "Importing..." : "Import Bars"}
          </button>
        </div>

        <div className="row g-3">
          <Field label="Contract" className="col-12 col-md-4">
            <select
              value={importConfig.symbol}
              onChange={(event) => updateImportConfig("symbol", event.target.value)}
              className="form-select app-input"
            >
              {(instruments.length ? instruments : [{ symbol: "MNQ", name: "Micro E-mini Nasdaq-100" }]).map((instrument) => (
                <option key={instrument.symbol} value={instrument.symbol}>
                  {instrument.symbol} - {instrument.name}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Start Date" className="col-12 col-md-4">
            <input
              type="date"
              value={importConfig.startDate}
              onChange={(event) => updateImportConfig("startDate", event.target.value)}
              className="form-control app-input"
            />
          </Field>

          <Field label="End Date" className="col-12 col-md-4">
            <input
              type="date"
              value={importConfig.endDate}
              onChange={(event) => updateImportConfig("endDate", event.target.value)}
              className="form-control app-input"
            />
          </Field>

          {importFeedback && <div className="col-12 app-muted app-kicker">{importFeedback}</div>}
        </div>
      </div>

      <div className="app-panel">
        <div className="fw-bold app-kicker mb-3">Backtest Settings</div>
        <div className="row g-3">
          <Field label="Contract" className="col-12 col-md-4 col-xl-3">
            <select
              value={config.symbol}
              onChange={(event) => updateConfig("symbol", event.target.value)}
              className="form-select app-input"
            >
              {(instruments.length ? instruments : [{ symbol: "MNQ", name: "Micro E-mini Nasdaq-100" }]).map((instrument) => (
                <option key={instrument.symbol} value={instrument.symbol}>
                  {instrument.symbol} - {instrument.name}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Topstep Account" className="col-12 col-md-4 col-xl-3">
            <select
              value={config.fundedProfile}
              onChange={(event) => updateConfig("fundedProfile", event.target.value)}
              className="form-select app-input"
            >
              {(fundedProfiles.length ? fundedProfiles : [{ code: TOPSTEP_PROFILE_CODE, name: "Topstep 50K Combine" }]).map((profile) => (
                <option key={profile.code} value={profile.code}>
                  {profile.name}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Start Date" className="col-12 col-md-4 col-xl-3">
            <input type="date" value={config.startDate} onChange={(event) => updateConfig("startDate", event.target.value)} className="form-control app-input" />
          </Field>

          <Field label="End Date" className="col-12 col-md-4 col-xl-3">
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

          <div className="col-12 col-md-4 col-xl-3">
            <div className="app-data-chip h-100">
              <span className="app-label">Contract Specs</span>
              <strong>{selectedInstrument ? `${selectedInstrument.tickSize} tick / $${selectedInstrument.tickValue}` : "Loading"}</strong>
            </div>
          </div>

          <div className="col-12">
            <div className="app-subpanel">
              <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap">
                <div className="fw-bold app-kicker">Selected Contract Tests</div>
                <div className="d-flex gap-2 flex-wrap">
                  <button type="button" className="app-btn px-3" onClick={runBatchBacktests} disabled={isRunning || !canRun || batchRunnableCount === 0}>
                    Run Selected Separately
                  </button>
                  <button type="button" className="app-btn app-btn-primary px-3" onClick={runPortfolioBacktest} disabled={isRunning || !canRun || batchRunnableCount === 0}>
                    Start Portfolio Backtest
                  </button>
                </div>
              </div>
              <div className="d-flex gap-3 flex-wrap mt-3">
                {(instruments.length ? instruments : [{ symbol: "MNQ", name: "Micro E-mini Nasdaq-100" }]).map((instrument) => {
                  const rows = Number(marketData.rowsBySymbol?.[instrument.symbol] || 0);
                  return (
                    <label className="app-toggle-row" key={instrument.symbol}>
                      <input type="checkbox" checked={batchSymbols.includes(instrument.symbol)} onChange={() => toggleBatchSymbol(instrument.symbol)} />
                      {instrument.symbol} ({isMarketDataLoading ? "..." : formatNumber(rows, 0)})
                    </label>
                  );
                })}
              </div>
              <div className="row g-3 mt-1">
                <Field label="Max Open Positions" className="col-12 col-md-4">
                  <input type="number" min="1" value={config.maxOpenPositions} onChange={(event) => updateConfig("maxOpenPositions", event.target.value)} className="form-control app-input" />
                </Field>
                <Field label="Max Aggregate Contracts" className="col-12 col-md-4">
                  <input type="number" min="1" value={config.maxAggregateContracts} onChange={(event) => updateConfig("maxAggregateContracts", event.target.value)} className="form-control app-input" />
                </Field>
                <Field label="Funded Contract Units" className="col-12 col-md-4">
                  <input type="number" min="0" step="0.1" value={config.maxAggregateMiniUnits} onChange={(event) => updateConfig("maxAggregateMiniUnits", event.target.value)} className="form-control app-input" />
                </Field>
              </div>
            </div>
          </div>

          <div className="col-12 d-flex justify-content-between align-items-center gap-2 flex-wrap pt-1">
            <div className="app-muted app-kicker">{feedback}</div>
            <button type="button" className="app-btn app-btn-primary app-btn-run" onClick={runBacktest} disabled={isRunning || !canRun}>
              {isRunning ? "Running..." : "Run Futures Backtest"}
            </button>
          </div>
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

function defaultStartDate() {
  const date = new Date();
  date.setDate(date.getDate() - 2);
  date.setFullYear(date.getFullYear() - 1);
  return formatIsoDate(date);
}

function defaultEndDate() {
  const date = new Date();
  date.setDate(date.getDate() - 2);
  return formatIsoDate(date);
}

function formatIsoDate(date) {
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
    maxContracts: String(contractLimitForProfile(profile, config.symbol)),
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
