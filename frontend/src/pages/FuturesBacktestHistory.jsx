import { useCallback, useEffect, useMemo, useState } from "react";
import RunPreview from "../components/RunPreview.jsx";
import TradeAnalysisModal from "../components/TradeAnalysisModal.jsx";
import { apiFetch } from "../utils/api.js";
import { formatEstTime } from "../utils/time.js";

const PAGE_SIZE = 8;
const TRADE_PAGE_SIZE = 500;
const EMPTY_SEGMENTS = { daily: [], weekly: [], monthly: [], summary: null };

export default function FuturesBacktestHistory() {
  const [runs, setRuns] = useState([]);
  const [page, setPage] = useState(1);
  const [selectedRunId, setSelectedRunId] = useState(null);
  const [selectedSegments, setSelectedSegments] = useState(EMPTY_SEGMENTS);
  const [selectedSymbols, setSelectedSymbols] = useState([]);
  const [isClearing, setIsClearing] = useState(false);
  const [selectedTrade, setSelectedTrade] = useState(null);

  const loadRuns = useCallback((preferredId = null) => {
    apiFetch("/api/futures/portfolio-backtests")
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures backtest runs.");
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
          setSelectedSegments(EMPTY_SEGMENTS);
          setSelectedSymbols([]);
        }
      })
      .catch((error) => {
        console.error("Error loading futures backtest runs:", error);
        setRuns([]);
        setPage(1);
        setSelectedRunId(null);
        setSelectedSegments(EMPTY_SEGMENTS);
        setSelectedSymbols([]);
      });
  }, []);

  useEffect(() => {
    loadRuns();
  }, [loadRuns]);

  useEffect(() => {
    if (!selectedRunId) {
      return;
    }

    apiFetch(`/api/futures/portfolio-backtests/${selectedRunId}/segments`)
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures segments.");
        return response.json();
      })
      .then((data) => {
        setSelectedSegments({
          daily: Array.isArray(data.daily) ? data.daily : [],
          weekly: Array.isArray(data.weekly) ? data.weekly : [],
          monthly: Array.isArray(data.monthly) ? data.monthly : [],
          summary: data.summary && typeof data.summary === "object" ? data.summary : null,
        });
      })
      .catch((error) => {
        console.error("Error loading futures segments:", error);
        setSelectedSegments(EMPTY_SEGMENTS);
      });

    apiFetch(`/api/futures/portfolio-backtests/${selectedRunId}/symbols`)
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures symbol stats.");
        return response.json();
      })
      .then((data) => setSelectedSymbols(Array.isArray(data) ? data : []))
      .catch((error) => {
        console.error("Error loading futures symbol stats:", error);
        setSelectedSymbols([]);
      });
  }, [selectedRunId]);

  async function clearRuns() {
    setIsClearing(true);
    try {
      const response = await apiFetch("/api/futures/portfolio-backtests/clear", { method: "POST" });
      if (!response.ok) throw new Error("Failed to clear futures portfolio runs.");
      setRuns([]);
      setPage(1);
      setSelectedRunId(null);
      setSelectedSegments(EMPTY_SEGMENTS);
      setSelectedSymbols([]);
      loadRuns();
    } catch (error) {
      console.error("Error clearing futures backtest runs:", error);
    } finally {
      setIsClearing(false);
    }
  }

  const totalPages = Math.max(1, Math.ceil(runs.length / PAGE_SIZE));
  const boundedPage = Math.min(page, totalPages);
  const pageRuns = runs.slice((boundedPage - 1) * PAGE_SIZE, boundedPage * PAGE_SIZE);
  const selectedRun = runs.find((run) => run.id === selectedRunId) || null;
  const previewRun = useMemo(() => toPreviewRun(selectedRun, selectedSegments.summary), [selectedRun, selectedSegments.summary]);
  const loadSelectedTradesPage = useCallback(async ({
    page: tradePage = 1,
    limit = TRADE_PAGE_SIZE,
    outcome = "all",
    symbol = "all",
    side = "all",
    strategy = "all",
    sort = "largestWin",
    startDate = "",
    endDate = "",
  } = {}) => {
    if (!selectedRunId) {
      return { trades: [], total: 0, filteredTotal: 0, filteredPnl: 0, filteredWinRate: 0, limit, offset: 0, symbols: [], strategies: [] };
    }
    const params = new URLSearchParams({
      paged: "true",
      limit: String(limit),
      offset: String(Math.max(0, tradePage - 1) * limit),
      outcome,
      symbol,
      side,
      strategy,
      sort,
      startDate,
      endDate,
    });
    const response = await apiFetch(`/api/futures/portfolio-backtests/${selectedRunId}/trades?${params.toString()}`);
    if (!response.ok) throw new Error("Failed to load futures trades.");
    const data = await response.json();
    return {
      ...data,
      trades: Array.isArray(data.trades) ? data.trades.map(toPreviewTrade) : [],
      symbols: Array.isArray(data.symbols) ? data.symbols : [],
      strategies: Array.isArray(data.strategies) ? data.strategies : [],
    };
  }, [selectedRunId]);

  return (
    <div className="app-page futures-history-page">
      <h2 className="app-title">Futures Portfolio Runs</h2>

      <div className="app-panel">
        <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap">
          <div className="fw-bold app-kicker">Portfolio Runs</div>
          <button type="button" className="app-btn app-btn-danger px-3" onClick={clearRuns} disabled={isClearing || runs.length === 0}>
            {isClearing ? "Clearing..." : "Clear Runs"}
          </button>
        </div>

        <div className="mobile-run-card-list">
          {pageRuns.map((run) => {
            const selected = run.id === selectedRunId;
            return (
              <article key={`mobile-${run.id}`} className={selected ? "mobile-run-card selected" : "mobile-run-card"}>
                <div className="mobile-run-card-head">
                  <div>
                    <span className="app-label">Run #{run.visibleRunNumber}</span>
                    <strong>{run.symbols}</strong>
                  </div>
                  <span className={run.trades === 0 ? "app-badge app-neutral-badge" : run.ruleViolation ? "app-badge app-risk-badge" : "app-badge app-positive-badge"}>
                    {run.trades === 0 ? "No Trades" : run.ruleViolation ? (run.continueAfterRuleViolation ? "Violation Trail" : "Violation") : "Pass"}
                  </span>
                </div>
                <div className="mobile-run-meta-grid">
                  <span>
                    <b>Profit</b>
                    <em className={run.totalProfit >= 0 ? "app-pnl-pos" : "app-pnl-neg"}>{formatCurrency(run.totalProfit)}</em>
                  </span>
                  <span>
                    <b>Return</b>
                    <em>{formatPercent(run.returnPct)}</em>
                  </span>
                  <span>
                    <b>Win</b>
                    <em>{formatPercent(run.winRate)}</em>
                  </span>
                  <span>
                    <b>Trades</b>
                    <em>{run.trades}</em>
                  </span>
                </div>
                <div className="mobile-run-card-foot">
                  <span>{formatEstTime(run.startDate)} to {formatEstTime(run.endDate)}</span>
                  <button
                    type="button"
                    className={selected ? "app-btn app-btn-selected px-3" : "app-btn px-3"}
                    onClick={() => setSelectedRunId(run.id)}
                  >
                    {selected ? "Selected" : "Select"}
                  </button>
                </div>
              </article>
            );
          })}
          {runs.length === 0 && <div className="app-empty">No futures portfolio runs yet.</div>}
        </div>

        <div className="app-table-wrap desktop-run-table">
          <div className="app-grid-head futures-portfolio-run-grid">
            <div>Run</div>
            <div>Strategy</div>
            <div>Risk</div>
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

          {pageRuns.map((run) => {
            const selected = run.id === selectedRunId;
            return (
              <div key={run.id} className={selected ? "app-grid-row futures-portfolio-run-grid selected" : "app-grid-row futures-portfolio-run-grid"}>
                <div>#{run.visibleRunNumber}</div>
                <div>{formatStrategyConfig(run)}</div>
                <div>{formatRiskConfig(run)}</div>
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
                    {run.trades === 0 ? "No Trades" : run.ruleViolation ? (run.continueAfterRuleViolation ? "Violation Trail" : "Violation") : "Pass"}
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

          {runs.length === 0 && <div className="app-empty">No futures portfolio runs yet.</div>}
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
            <div className="fw-bold app-kicker">Contribution / Monthly Quality Check</div>
            <AnalyticsSummary summary={selectedSegments.summary} />
            <div className="row g-3 mt-1">
              <SymbolTable symbols={selectedSymbols} />
              <SegmentTable title="Daily" segments={selectedSegments.daily} />
              <SegmentTable title="Weekly" segments={selectedSegments.weekly} />
              <SegmentTable title="Monthly" segments={selectedSegments.monthly} />
            </div>
          </div>

          <RunPreview
            run={previewRun}
            trades={[]}
            totalTradeCount={selectedRun.trades}
            tradePreviewLimit={TRADE_PAGE_SIZE}
            loadTradesPage={loadSelectedTradesPage}
            showCapitalCards={true}
            showTradeLogs={true}
            onOpenTrade={setSelectedTrade}
          />
        </>
      )}

      <TradeAnalysisModal
        trade={selectedTrade}
        source="backtest"
        onClose={() => setSelectedTrade(null)}
      />
    </div>
  );
}

function toPreviewRun(run, summary = null) {
  if (!run) return null;
  const runNumber = run.visibleRunNumber ?? run.id;
  return {
    id: `Futures #${runNumber}`,
    name: `Futures #${runNumber}`,
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
    ruleViolation: run.ruleViolation,
    ruleMessage: run.ruleMessage,
    continueAfterRuleViolation: run.continueAfterRuleViolation,
    expectancy: summary?.expectancy,
    averageRiskReward: summary?.averageRiskReward,
    avgWin: summary?.avgWin,
    avgLoss: summary?.avgLoss,
    payoffRatio: summary?.payoffRatio,
    avgDailyPnl: summary?.daily?.avgPnl,
    avgWeeklyPnl: summary?.weekly?.avgPnl,
    avgMonthlyPnl: summary?.monthly?.avgPnl,
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
    <div className="col-12 col-lg-6">
      <div className="app-card h-100 futures-segment-card">
        <div className="fw-bold app-kicker mb-2">{title}</div>
        <div className="app-table-wrap strategy-table-wrap futures-segment-scroll">
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
    <div className="col-12 col-lg-6">
      <div className="app-card h-100 futures-segment-card">
        <div className="fw-bold app-kicker mb-2">By Contract</div>
        <div className="app-table-wrap strategy-table-wrap futures-segment-scroll">
          <div className="app-grid-head futures-segment-grid">
            <div>Symbol</div>
            <div>P/L</div>
            <div>Trades</div>
            <div>Win</div>
            <div>Avg</div>
          </div>
          {symbols.map((symbol) => (
            <div key={symbol.symbol} className="app-grid-row futures-segment-grid">
              <div>
                <strong>{symbol.symbol}</strong>
                {symbol.contractName && <div className="app-muted">{symbol.contractName}</div>}
              </div>
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

function AnalyticsSummary({ summary }) {
  if (!summary) return null;
  return (
    <div className="futures-analytics-grid mt-3">
      <AnalyticsTile label="Avg Daily P/L" value={formatCurrency(summary.daily?.avgPnl)} accent={summary.daily?.avgPnl} />
      <AnalyticsTile label="Avg Weekly P/L" value={formatCurrency(summary.weekly?.avgPnl)} accent={summary.weekly?.avgPnl} />
      <AnalyticsTile label="Avg Monthly P/L" value={formatCurrency(summary.monthly?.avgPnl)} accent={summary.monthly?.avgPnl} />
      <AnalyticsTile label="Best Day" value={formatPeriodPnl(summary.daily?.best)} accent={summary.daily?.best?.pnl} />
      <AnalyticsTile label="Worst Day" value={formatPeriodPnl(summary.daily?.worst)} accent={summary.daily?.worst?.pnl} />
      <AnalyticsTile label="Best Week" value={formatPeriodPnl(summary.weekly?.best)} accent={summary.weekly?.best?.pnl} />
      <AnalyticsTile label="Worst Week" value={formatPeriodPnl(summary.weekly?.worst)} accent={summary.weekly?.worst?.pnl} />
      <AnalyticsTile label="Best Month" value={formatPeriodPnl(summary.monthly?.best)} accent={summary.monthly?.best?.pnl} />
      <AnalyticsTile label="Worst Month" value={formatPeriodPnl(summary.monthly?.worst)} accent={summary.monthly?.worst?.pnl} />
      <AnalyticsTile label="Positive Days" value={formatPercent(summary.daily?.positivePct)} />
      <AnalyticsTile label="Expectancy" value={formatCurrency(summary.expectancy)} accent={summary.expectancy} />
      <AnalyticsTile label="Avg R/R" value={formatNumber(summary.averageRiskReward, 2)} />
    </div>
  );
}

function AnalyticsTile({ label, value, accent = 0 }) {
  const valueClass = Number(accent || 0) > 0 ? "app-pnl-pos" : Number(accent || 0) < 0 ? "app-pnl-neg" : "";
  return (
    <div className="futures-analytics-tile">
      <div className="app-label">{label}</div>
      <div className={`fw-bold ${valueClass}`}>{value ?? "--"}</div>
    </div>
  );
}

function formatPeriodPnl(period) {
  if (!period?.segment) return "--";
  return `${formatSegmentPeriod(period.segment)} ${formatCurrency(period.pnl)}`;
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

function formatStrategyConfig(run) {
  const label = String(run?.strategyConfig || "").trim();
  if (label) return label;
  const preset = String(run?.strategyPreset || "").trim();
  if (preset === "backtestbias92k") return "Backtest Bias 92k";
  if (preset === "biasfree92k") return "Bias-Free 92k";
  if (preset === "bestbiasfree") return "Best Bias-Free";
  return preset || "--";
}

function formatRiskConfig(run) {
  const label = String(run?.riskConfig || "").trim();
  if (label) return label;
  const code = String(run?.riskConfigCode || run?.fundedProfile || "").trim();
  if (code === "TOPSTEP_50K") return "50K";
  if (code === "TOPSTEP_100K") return "100K";
  if (code === "TOPSTEP_150K") return "150K";
  return code || "--";
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
  const weekly = raw.match(/^(\d{4})-W(\d{2})$/);
  if (weekly) {
    return `W${weekly[2]} ${weekly[1]}`;
  }
  return formatEstTime(raw);
}
