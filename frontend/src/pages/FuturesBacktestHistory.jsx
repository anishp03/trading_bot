import { useCallback, useEffect, useMemo, useState } from "react";
import RunPreview from "../components/RunPreview.jsx";
import { apiFetch } from "../utils/api.js";
import { formatEstTime } from "../utils/time.js";

const PAGE_SIZE = 5;
const PORTFOLIO_PAGE_SIZE = 8;

export default function FuturesBacktestHistory() {
  const [runs, setRuns] = useState([]);
  const [page, setPage] = useState(1);
  const [selectedRunId, setSelectedRunId] = useState(null);
  const [selectedTrades, setSelectedTrades] = useState([]);
  const [selectedSegments, setSelectedSegments] = useState({ monthly: [], quarterly: [] });
  const [isClearing, setIsClearing] = useState(false);
  const [portfolioRuns, setPortfolioRuns] = useState([]);
  const [portfolioPage, setPortfolioPage] = useState(1);
  const [selectedPortfolioRunId, setSelectedPortfolioRunId] = useState(null);
  const [selectedPortfolioTrades, setSelectedPortfolioTrades] = useState([]);
  const [selectedPortfolioSegments, setSelectedPortfolioSegments] = useState({ monthly: [], quarterly: [] });
  const [selectedPortfolioSymbols, setSelectedPortfolioSymbols] = useState([]);
  const [isClearingPortfolio, setIsClearingPortfolio] = useState(false);

  const loadRuns = useCallback((preferredId = null) => {
    apiFetch("/api/futures/backtests")
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures runs.");
        return response.json();
      })
      .then((data) => {
        const nextRuns = decorateRuns(Array.isArray(data) ? data : []);
        setRuns(nextRuns);
        setPage(1);
        setSelectedRunId((currentId) => {
          if (preferredId && nextRuns.some((run) => run.id === preferredId)) return preferredId;
          if (nextRuns.some((run) => run.id === currentId)) return currentId;
          return nextRuns[0]?.id ?? null;
        });
        if (nextRuns.length === 0) {
          setSelectedTrades([]);
          setSelectedSegments({ monthly: [], quarterly: [] });
        }
      })
      .catch((error) => {
        console.error("Error loading futures backtests:", error);
        setRuns([]);
        setSelectedRunId(null);
        setSelectedTrades([]);
        setSelectedSegments({ monthly: [], quarterly: [] });
      });
  }, []);

  const loadPortfolioRuns = useCallback((preferredId = null) => {
    apiFetch("/api/futures/portfolio-backtests")
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures portfolio runs.");
        return response.json();
      })
      .then((data) => {
        const nextRuns = decorateRuns(Array.isArray(data) ? data : []);
        setPortfolioRuns(nextRuns);
        setPortfolioPage(1);
        setSelectedPortfolioRunId((currentId) => {
          if (preferredId && nextRuns.some((run) => run.id === preferredId)) return preferredId;
          if (nextRuns.some((run) => run.id === currentId)) return currentId;
          return nextRuns[0]?.id ?? null;
        });
        if (nextRuns.length === 0) {
          setSelectedPortfolioTrades([]);
          setSelectedPortfolioSegments({ monthly: [], quarterly: [] });
          setSelectedPortfolioSymbols([]);
        }
      })
      .catch((error) => {
        console.error("Error loading futures portfolio backtests:", error);
        setPortfolioRuns([]);
        setPortfolioPage(1);
        setSelectedPortfolioRunId(null);
        setSelectedPortfolioTrades([]);
        setSelectedPortfolioSegments({ monthly: [], quarterly: [] });
        setSelectedPortfolioSymbols([]);
      });
  }, []);

  useEffect(() => {
    loadRuns();
    loadPortfolioRuns();
  }, [loadRuns, loadPortfolioRuns]);

  useEffect(() => {
    if (!selectedRunId) {
      return;
    }

    apiFetch(`/api/futures/backtests/${selectedRunId}/trades`)
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures trades.");
        return response.json();
      })
      .then((data) => setSelectedTrades(Array.isArray(data) ? data : []))
      .catch((error) => {
        console.error("Error loading futures trades:", error);
        setSelectedTrades([]);
      });

    apiFetch(`/api/futures/backtests/${selectedRunId}/segments`)
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures segment analytics.");
        return response.json();
      })
      .then((data) => {
        setSelectedSegments({
          monthly: Array.isArray(data.monthly) ? data.monthly : [],
          quarterly: Array.isArray(data.quarterly) ? data.quarterly : [],
        });
      })
      .catch((error) => {
        console.error("Error loading futures segment analytics:", error);
        setSelectedSegments({ monthly: [], quarterly: [] });
      });
  }, [selectedRunId]);

  useEffect(() => {
    if (!selectedPortfolioRunId) {
      return;
    }

    apiFetch(`/api/futures/portfolio-backtests/${selectedPortfolioRunId}/trades`)
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures portfolio trades.");
        return response.json();
      })
      .then((data) => setSelectedPortfolioTrades(Array.isArray(data) ? data : []))
      .catch((error) => {
        console.error("Error loading futures portfolio trades:", error);
        setSelectedPortfolioTrades([]);
      });

    apiFetch(`/api/futures/portfolio-backtests/${selectedPortfolioRunId}/segments`)
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures portfolio segments.");
        return response.json();
      })
      .then((data) => {
        setSelectedPortfolioSegments({
          monthly: Array.isArray(data.monthly) ? data.monthly : [],
          quarterly: Array.isArray(data.quarterly) ? data.quarterly : [],
        });
      })
      .catch((error) => {
        console.error("Error loading futures portfolio segments:", error);
        setSelectedPortfolioSegments({ monthly: [], quarterly: [] });
      });

    apiFetch(`/api/futures/portfolio-backtests/${selectedPortfolioRunId}/symbols`)
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures portfolio symbol stats.");
        return response.json();
      })
      .then((data) => setSelectedPortfolioSymbols(Array.isArray(data) ? data : []))
      .catch((error) => {
        console.error("Error loading futures portfolio symbol stats:", error);
        setSelectedPortfolioSymbols([]);
      });
  }, [selectedPortfolioRunId]);

  function clearRuns() {
    setIsClearing(true);
    apiFetch("/api/futures/backtests/clear", { method: "POST" })
      .then((response) => {
        if (!response.ok) throw new Error("Failed to clear futures backtests.");
        setRuns([]);
        setPage(1);
        setSelectedRunId(null);
        setSelectedTrades([]);
        setSelectedSegments({ monthly: [], quarterly: [] });
      })
      .catch((error) => {
        console.error("Error clearing futures backtests:", error);
      })
      .finally(() => {
        setIsClearing(false);
      });
  }

  function clearPortfolioRuns() {
    setIsClearingPortfolio(true);
    apiFetch("/api/futures/portfolio-backtests/clear", { method: "POST" })
      .then((response) => {
        if (!response.ok) throw new Error("Failed to clear futures portfolio backtests.");
        setPortfolioRuns([]);
        setPortfolioPage(1);
        setSelectedPortfolioRunId(null);
        setSelectedPortfolioTrades([]);
        setSelectedPortfolioSegments({ monthly: [], quarterly: [] });
        setSelectedPortfolioSymbols([]);
      })
      .catch((error) => {
        console.error("Error clearing futures portfolio backtests:", error);
      })
      .finally(() => {
        setIsClearingPortfolio(false);
      });
  }

  const totalPages = Math.max(1, Math.ceil(runs.length / PAGE_SIZE));
  const boundedPage = Math.min(page, totalPages);
  const pageRuns = runs.slice((boundedPage - 1) * PAGE_SIZE, boundedPage * PAGE_SIZE);
  const portfolioTotalPages = Math.max(1, Math.ceil(portfolioRuns.length / PORTFOLIO_PAGE_SIZE));
  const boundedPortfolioPage = Math.min(portfolioPage, portfolioTotalPages);
  const portfolioPageRuns = portfolioRuns.slice(
    (boundedPortfolioPage - 1) * PORTFOLIO_PAGE_SIZE,
    boundedPortfolioPage * PORTFOLIO_PAGE_SIZE
  );
  const selectedRun = runs.find((run) => run.id === selectedRunId) || null;
  const previewRun = useMemo(() => toPreviewRun(selectedRun), [selectedRun]);
  const previewTrades = useMemo(() => selectedTrades.map(toPreviewTrade), [selectedTrades]);
  const selectedPortfolioRun = portfolioRuns.find((run) => run.id === selectedPortfolioRunId) || null;
  const previewPortfolioRun = useMemo(() => toPreviewPortfolioRun(selectedPortfolioRun), [selectedPortfolioRun]);
  const previewPortfolioTrades = useMemo(() => selectedPortfolioTrades.map(toPreviewTrade), [selectedPortfolioTrades]);

  return (
    <div className="app-page futures-history-page">
      <h2 className="app-title">Futures Backtest History</h2>

      <div className="app-panel">
        <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap">
          <div className="fw-bold app-kicker">True Portfolio Runs</div>
          <button type="button" className="app-btn app-btn-danger px-3" onClick={clearPortfolioRuns} disabled={isClearingPortfolio || portfolioRuns.length === 0}>
            {isClearingPortfolio ? "Clearing..." : "Clear Portfolio Runs"}
          </button>
        </div>

        <div className="app-table-wrap">
          <div className="app-grid-head futures-portfolio-run-grid">
            <div>Run</div>
            <div>Symbols</div>
            <div>Range</div>
            <div>Win</div>
            <div>Profit</div>
            <div>Return</div>
            <div>Drawdown</div>
            <div>Trades</div>
            <div>Max Pos</div>
            <div>Max Ctr / Units</div>
            <div>Max MAE</div>
            <div>Overlaps</div>
            <div>Rules</div>
            <div className="text-end">Action</div>
          </div>

          {portfolioPageRuns.map((run) => {
            const selected = run.id === selectedPortfolioRunId;
            return (
              <div key={run.id} className={selected ? "app-grid-row futures-portfolio-run-grid selected" : "app-grid-row futures-portfolio-run-grid"}>
                <div>#{run.visibleRunNumber}</div>
                <div>{run.symbols}</div>
                <div>{formatEstTime(run.startDate)} to {formatEstTime(run.endDate)}</div>
                <div>{formatPercent(run.winRate)}</div>
                <div className={run.totalProfit >= 0 ? "app-pnl-pos" : "app-pnl-neg"}>{formatCurrency(run.totalProfit)}</div>
                <div>{formatPercent(run.returnPct)}</div>
                <div>{formatPercent(run.maxDrawdownPct)}</div>
                <div>{run.trades}</div>
                <div>{run.maxConcurrentPositions}</div>
                <div>{run.maxConcurrentContracts} / {formatNumber(run.maxConcurrentMiniUnits, 1)}</div>
                <div className={run.maxAggregateMae >= 0 ? "app-pnl-pos" : "app-pnl-neg"}>{formatCurrency(run.maxAggregateMae)}</div>
                <div>{run.overlapRejections}</div>
                <div>
                  <span className={run.trades === 0 ? "app-badge app-neutral-badge" : run.ruleViolation ? "app-badge app-risk-badge" : "app-badge app-positive-badge"}>
                    {run.trades === 0 ? "No Trades" : run.ruleViolation ? "Violation" : "Pass"}
                  </span>
                </div>
                <div className="text-end">
                  <button
                    type="button"
                    className={selected ? "app-btn app-btn-selected px-3" : "app-btn px-3"}
                    onClick={() => setSelectedPortfolioRunId(run.id)}
                  >
                    {selected ? "Selected" : "Select"}
                  </button>
                </div>
              </div>
            );
          })}

          {portfolioRuns.length === 0 && <div className="app-empty">No portfolio futures runs yet.</div>}
        </div>

        <div className="d-flex align-items-center justify-content-between gap-2 mt-3">
          <button type="button" className="app-btn px-3" disabled={boundedPortfolioPage === 1} onClick={() => setPortfolioPage((current) => Math.max(1, current - 1))}>
            Prev
          </button>
          <div className="app-muted app-kicker">
            Page <b>{boundedPortfolioPage}</b> of <b>{portfolioTotalPages}</b>
          </div>
          <button type="button" className="app-btn px-3" disabled={boundedPortfolioPage === portfolioTotalPages} onClick={() => setPortfolioPage((current) => Math.min(portfolioTotalPages, current + 1))}>
            Next
          </button>
        </div>
      </div>

      {selectedPortfolioRun && previewPortfolioRun && (
        <>
          <div className="app-panel">
            <div className="fw-bold app-kicker">Portfolio Contribution / Monthly Quality Check</div>
            <div className="row g-3 mt-1">
              <SymbolTable symbols={selectedPortfolioSymbols} />
              <SegmentTable title="Portfolio Monthly" segments={selectedPortfolioSegments.monthly} />
            </div>
          </div>

          <RunPreview
            run={previewPortfolioRun}
            trades={previewPortfolioTrades}
            showCapitalCards={true}
            showTradeLogs={true}
          />
        </>
      )}

      <div className="app-panel">
        <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap">
          <div className="fw-bold app-kicker">Previous Runs</div>
          <button type="button" className="app-btn app-btn-danger px-3" onClick={clearRuns} disabled={isClearing || runs.length === 0}>
            {isClearing ? "Clearing..." : "Clear Runs"}
          </button>
        </div>

        <div className="app-table-wrap">
          <div className="app-grid-head futures-run-grid">
            <div>Run</div>
            <div>Contract</div>
            <div>Range</div>
            <div>Win Rate</div>
            <div>Profit</div>
            <div>Return</div>
            <div>Drawdown</div>
            <div>Trades</div>
            <div>Rules</div>
            <div className="text-end">Action</div>
          </div>

          {pageRuns.map((run) => {
            const selected = run.id === selectedRunId;
            return (
              <div key={run.id} className={selected ? "app-grid-row futures-run-grid selected" : "app-grid-row futures-run-grid"}>
                <div>#{run.visibleRunNumber}</div>
                <div>{run.symbol}</div>
                <div>{formatEstTime(run.startDate)} to {formatEstTime(run.endDate)}</div>
                <div>{formatPercent(run.winRate)}</div>
                <div className={run.totalProfit >= 0 ? "app-pnl-pos" : "app-pnl-neg"}>{formatCurrency(run.totalProfit)}</div>
                <div>{formatPercent(run.returnPct)}</div>
                <div>{formatPercent(run.maxDrawdownPct)}</div>
                <div>{run.trades}</div>
                <div>
                  <span className={run.trades === 0 ? "app-badge app-neutral-badge" : run.ruleViolation ? "app-badge app-risk-badge" : "app-badge app-positive-badge"}>
                    {run.trades === 0 ? "No Trades" : run.ruleViolation ? "Violation" : "Pass"}
                  </span>
                </div>
                <div className="text-end">
                  <button
                    type="button"
                    className={selected ? "app-btn app-btn-selected px-3" : "app-btn px-3"}
                    onClick={() => setSelectedRunId(run.id)}
                  >
                    {selected ? "Selected" : "Select"}
                  </button>
                </div>
              </div>
            );
          })}

          {runs.length === 0 && <div className="app-empty">No futures runs yet.</div>}
        </div>

        <div className="d-flex align-items-center justify-content-between gap-2 mt-3">
          <button type="button" className="app-btn px-3" disabled={boundedPage === 1} onClick={() => setPage((current) => Math.max(1, current - 1))}>
            Prev
          </button>
          <div className="app-muted app-kicker">
            Page <b>{boundedPage}</b> of <b>{totalPages}</b>
          </div>
          <button type="button" className="app-btn px-3" disabled={boundedPage === totalPages} onClick={() => setPage((current) => Math.min(totalPages, current + 1))}>
            Next
          </button>
        </div>
      </div>

      {selectedRun && previewRun && (
        <>
          <div className="app-panel">
            <div className="fw-bold app-kicker">Monthly / Quarterly Quality Check</div>
            <div className="row g-3 mt-1">
              <SegmentTable title="Monthly" segments={selectedSegments.monthly} />
              <SegmentTable title="Quarterly" segments={selectedSegments.quarterly} />
            </div>
          </div>

          <RunPreview
            run={previewRun}
            trades={previewTrades}
            showCapitalCards={true}
            showTradeLogs={true}
          />
        </>
      )}
    </div>
  );
}

function toPreviewRun(run) {
  if (!run) return null;
  const runNumber = run.visibleRunNumber ?? run.id;
  return {
    id: `Futures #${runNumber}`,
    name: `Futures #${runNumber}`,
    equity: run.symbol,
    start: run.startDate,
    end: run.endDate,
    startingCapital: run.startingBalance,
    endingCapital: run.endingBalance,
    totalProfit: run.totalProfit,
    winRate: run.winRate,
    totalReturn: run.returnPct,
    trades: run.trades,
    profitFactor: run.profitFactor,
    drawdown: run.maxDrawdownPct,
  };
}

function toPreviewPortfolioRun(run) {
  if (!run) return null;
  const runNumber = run.visibleRunNumber ?? run.id;
  return {
    id: `Portfolio #${runNumber}`,
    name: `Portfolio #${runNumber}`,
    equity: run.symbols,
    start: run.startDate,
    end: run.endDate,
    startingCapital: run.startingBalance,
    endingCapital: run.endingBalance,
    totalProfit: run.totalProfit,
    winRate: run.winRate,
    totalReturn: run.returnPct,
    trades: run.trades,
    profitFactor: run.profitFactor,
    drawdown: run.maxDrawdownPct,
  };
}

function toPreviewTrade(trade) {
  return {
    ...trade,
    time: trade.openedAt,
    qty: trade.contracts,
    tradeNotes: [
      trade.exitReason,
      trade.tradeNotes,
      `MFE ${formatCurrency(trade.mfe)} / MAE ${formatCurrency(trade.mae)}`,
    ]
      .filter(Boolean)
      .join(" | "),
  };
}

function decorateRuns(runs) {
  return runs.map((run) => ({
    ...run,
    visibleRunNumber: run.id,
  }));
}

function SegmentTable({ title, segments }) {
  return (
    <div className="col-12 col-xl-6">
      <div className="app-card h-100">
        <div className="fw-bold app-kicker mb-2">{title}</div>
        <div className="app-table-wrap strategy-table-wrap">
          <div className="app-grid-head futures-segment-grid">
            <div>Period</div>
            <div>P/L</div>
            <div>Trades</div>
            <div>Win</div>
            <div>Avg</div>
          </div>
          {segments.map((segment) => (
            <div key={segment.segment} className="app-grid-row futures-segment-grid">
              <div>{formatSegmentPeriod(segment.segment)}</div>
              <div className={segment.pnl >= 0 ? "app-pnl-pos" : "app-pnl-neg"}>{formatCurrency(segment.pnl)}</div>
              <div>{segment.trades}</div>
              <div>{formatPercent(segment.winRate)}</div>
              <div>{formatCurrency(segment.avgPnl)}</div>
            </div>
          ))}
          {segments.length === 0 && <div className="app-empty">No segment data yet.</div>}
        </div>
      </div>
    </div>
  );
}

function SymbolTable({ symbols }) {
  return (
    <div className="col-12 col-xl-6">
      <div className="app-card h-100">
        <div className="fw-bold app-kicker mb-2">By Contract</div>
        <div className="app-table-wrap strategy-table-wrap">
          <div className="app-grid-head futures-segment-grid">
            <div>Symbol</div>
            <div>P/L</div>
            <div>Trades</div>
            <div>Win</div>
            <div>Avg</div>
          </div>
          {symbols.map((symbol) => (
            <div key={symbol.symbol} className="app-grid-row futures-segment-grid">
              <div>{symbol.symbol}</div>
              <div className={symbol.pnl >= 0 ? "app-pnl-pos" : "app-pnl-neg"}>{formatCurrency(symbol.pnl)}</div>
              <div>{symbol.trades}</div>
              <div>{formatPercent(symbol.winRate)}</div>
              <div>{formatCurrency(symbol.avgPnl)}</div>
            </div>
          ))}
          {symbols.length === 0 && <div className="app-empty">No symbol contribution data yet.</div>}
        </div>
      </div>
    </div>
  );
}

function formatCurrency(value) {
  const numeric = Number(value || 0);
  const sign = numeric > 0 ? "+" : "";
  return `${sign}$${numeric.toFixed(2)}`;
}

function formatPercent(value) {
  return `${Number(value || 0).toFixed(2)}%`;
}

function formatNumber(value, decimals = 0) {
  return Number(value || 0).toFixed(decimals);
}

function formatSegmentPeriod(value) {
  const raw = String(value || "");
  const monthly = raw.match(/^(\d{4})-(\d{2})$/);
  if (monthly) {
    return `${monthly[2]}/${monthly[1]}`;
  }
  const quarterly = raw.match(/^(\d{4})-Q([1-4])$/);
  if (quarterly) {
    return `Q${quarterly[2]} ${quarterly[1]}`;
  }
  return formatEstTime(raw);
}
