import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiFetch } from "../utils/api.js";

const DEFAULT_SYMBOLS = ["SPY", "QQQ", "AAPL", "NVDA", "TSLA"];
const DATE_RANGE_OPTIONS = [
  { value: "ALL", label: "All Cached Data" },
  { value: "4M", label: "4 Months" },
  { value: "6M", label: "6 Months" },
  { value: "1Y", label: "1 Year" },
  { value: "2Y", label: "2 Years" },
];

export default function Backtest({ accountEmail }) {
  const navigate = useNavigate();
  const [equity, setEquity] = useState("SPY");
  const [dateRange, setDateRange] = useState("ALL");
  const [totalBuyingPower, setTotalBuyingPower] = useState("25000");
  const [perTradeBuyingPower, setPerTradeBuyingPower] = useState("25000");
  const [takeProfit, setTakeProfit] = useState("1000");
  const [lossLimit, setLossLimit] = useState("500");
  const [isRunning, setIsRunning] = useState(false);
  const [isRefreshingData, setIsRefreshingData] = useState(false);
  const [strategySettings, setStrategySettings] = useState({ enabledStrategies: [] });
  const [marketData, setMarketData] = useState({
    symbols: DEFAULT_SYMBOLS,
    startDate: "",
    endDate: "",
    lastUpdatedAt: "",
    totalBars: 0,
    storagePath: "market_data",
    hasData: false,
  });

  function applyMarketDataPayload(data) {
    const symbols = data.symbols && data.symbols.length ? data.symbols : DEFAULT_SYMBOLS;

    setMarketData({
      symbols,
      startDate: data.startDate || "",
      endDate: data.endDate || "",
      lastUpdatedAt: data.lastUpdatedAt || "",
      totalBars: Number.isFinite(data.totalBars) ? data.totalBars : 0,
      storagePath: data.storagePath || "market_data",
      hasData: Boolean(data.hasData),
    });

    setEquity((currentEquity) => (symbols.includes(currentEquity) ? currentEquity : symbols[0]));
  }

  const loadMarketDataStatus = useCallback(() => {
    apiFetch("/api/backtests/market-data")
      .then((response) => {
        if (!response.ok) {
          throw new Error("Failed to load market data status.");
        }

        return response.json();
      })
      .then((data) => {
        applyMarketDataPayload(data);
      })
      .catch((error) => {
        console.error("Error loading market data status:", error);
        setMarketData({
          symbols: DEFAULT_SYMBOLS,
          startDate: "",
          endDate: "",
          lastUpdatedAt: "",
          totalBars: 0,
          storagePath: "market_data",
          hasData: false,
        });
      });
  }, []);

  const loadStrategySettings = useCallback(() => {
    apiFetch("/api/strategy")
      .then((response) => {
        if (!response.ok) {
          throw new Error("Failed to load strategy settings.");
        }

        return response.json();
      })
      .then((data) => {
        setStrategySettings({
          enabledStrategies: Array.isArray(data.enabledStrategies) ? data.enabledStrategies : [],
        });
      })
      .catch((error) => {
        console.error("Error loading strategy settings:", error);
        setStrategySettings({ enabledStrategies: [] });
      });
  }, []);

  function refreshMarketData() {
    if (!accountEmail) {
      return;
    }

    setIsRefreshingData(true);

    apiFetch(`/api/backtests/market-data/refresh?email=${encodeURIComponent(accountEmail)}`, {
      method: "POST",
    })
      .then(async (response) => {
        if (!response.ok) {
          const text = await response.text();
          throw new Error(text || "Failed to refresh market data.");
        }

        return response.json();
      })
      .then((data) => {
        applyMarketDataPayload(data);
      })
      .catch((error) => {
        console.error("Error refreshing market data:", error);
      })
      .finally(() => {
        setIsRefreshingData(false);
      });
  }

  function runBacktest() {
    if (!marketData.hasData || !marketData.endDate || enabledStrategies.length === 0) {
      return;
    }

    setIsRunning(true);
    const startDate = getPresetStartDate(marketData.startDate, marketData.endDate, dateRange);
    const endDate = marketData.endDate;
    const params = new URLSearchParams({
      equity,
      startDate,
      endDate,
      totalBuyingPower,
      perTradeBuyingPower,
      takeProfit,
      lossLimit,
    });

    apiFetch(`/api/backtests/generate?${params.toString()}`, {
      method: "POST",
    })
      .then((response) => {
        if (!response.ok) {
          throw new Error("Failed to run backtest.");
        }

        navigate("/backtest-history");
      })
      .catch((error) => {
        console.error("Error generating backtest:", error);
      })
      .finally(() => {
        setIsRunning(false);
      });
  }

  useEffect(() => {
    loadMarketDataStatus();
    loadStrategySettings();
  }, [loadMarketDataStatus, loadStrategySettings]);

  const currentDataRange = marketData.hasData
    ? `${formatDisplayDate(marketData.startDate)} - ${formatDisplayDate(marketData.endDate)}`
    : "No Data Cached";

  const availableSymbols = marketData.symbols && marketData.symbols.length ? marketData.symbols : DEFAULT_SYMBOLS;
  const enabledStrategies = Array.isArray(strategySettings.enabledStrategies) ? strategySettings.enabledStrategies : [];
  const canRunBacktest = marketData.hasData && enabledStrategies.length > 0;

  return (
    <div className="app-page">
      <h2 className="app-title">Backtest</h2>

      <div className="app-panel">
        <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap mb-3">
          <div className="fw-bold app-kicker">Backtest Settings</div>
        </div>

        <div className="app-data-toolbar mb-3">
          <div className="app-data-chip">
            <span className="app-label">Data Range</span>
            <strong>{currentDataRange}</strong>
          </div>
          <div className="app-data-chip">
            <span className="app-label">Stored Bars</span>
            <strong>{marketData.totalBars.toLocaleString()}</strong>
          </div>
          <div className="app-data-chip">
            <span className="app-label">Storage</span>
            <strong>{marketData.storagePath || "market_data"}</strong>
          </div>
          <button
            type="button"
            className="app-btn app-btn-small px-3"
            onClick={refreshMarketData}
            disabled={isRefreshingData || !accountEmail}
          >
            {isRefreshingData ? "Refreshing..." : "Refresh Data"}
          </button>
        </div>

        <div className="row g-3">
          <Field label="Equity" className="col-12 col-lg-4">
            <select
              value={equity}
              onChange={(event) => setEquity(event.target.value)}
              className="form-select app-input"
            >
              {availableSymbols.map((symbolOption) => (
                <option key={symbolOption} value={symbolOption}>
                  {symbolOption}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Date Range" className="col-12 col-lg-4">
            <select
              value={dateRange}
              onChange={(event) => setDateRange(event.target.value)}
              className="form-select app-input"
            >
              {DATE_RANGE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Total Buying Power ($)" className="col-12 col-lg-4">
            <input
              type="number"
              value={totalBuyingPower}
              onChange={(event) => setTotalBuyingPower(event.target.value)}
              className="form-control app-input"
            />
          </Field>

          <Field label="Loss Limit ($)" className="col-12 col-lg-4">
            <input
              type="number"
              value={lossLimit}
              onChange={(event) => setLossLimit(event.target.value)}
              className="form-control app-input"
            />
          </Field>

          <Field label="Take Profit ($)" className="col-12 col-lg-4">
            <input
              type="number"
              value={takeProfit}
              onChange={(event) => setTakeProfit(event.target.value)}
              className="form-control app-input"
            />
          </Field>

          <Field label="Per Trade Buying Power ($)" className="col-12 col-lg-4">
            <input
              type="number"
              value={perTradeBuyingPower}
              onChange={(event) => setPerTradeBuyingPower(event.target.value)}
              className="form-control app-input"
            />
          </Field>

          <div className="col-12 d-flex justify-content-end pt-1">
            <button
              type="button"
              className="app-btn app-btn-primary app-btn-run"
              onClick={runBacktest}
              disabled={isRunning || !canRunBacktest}
            >
              {isRunning ? "Running..." : "Run"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function getPresetStartDate(cacheStartDate, cacheEndDate, dateRange) {
  if (!cacheEndDate) {
    return "";
  }
  if (dateRange === "ALL") {
    return cacheStartDate || cacheEndDate;
  }

  const startDate = new Date(`${cacheEndDate}T00:00:00`);

  if (dateRange === "4M") {
    startDate.setMonth(startDate.getMonth() - 4);
  } else if (dateRange === "6M") {
    startDate.setMonth(startDate.getMonth() - 6);
  } else if (dateRange === "1Y") {
    startDate.setFullYear(startDate.getFullYear() - 1);
  } else {
    startDate.setFullYear(startDate.getFullYear() - 2);
  }

  let presetStartDate = formatIsoDate(startDate);

  if (cacheStartDate && presetStartDate < cacheStartDate) {
    presetStartDate = cacheStartDate;
  }

  return presetStartDate;
}

function formatIsoDate(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function formatDisplayDate(value) {
  if (!value) {
    return "--/--/----";
  }

  const parts = value.split("-");
  if (parts.length !== 3) {
    return value;
  }

  return `${parts[1]}/${parts[2]}/${parts[0]}`;
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
