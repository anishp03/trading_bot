import { useEffect, useState } from "react";
import BacktestTradeReviewModal from "../components/BacktestTradeReviewModal.jsx";
import RunPreview from "../components/RunPreview.jsx";
import { apiFetch } from "../utils/api.js";
import { formatEstTime } from "../utils/time.js";

const PAGE_SIZE = 5;
const EMPTY_TRADE_REVIEW = {
  trade: null,
  chartData: null,
  timeframe: "1Min",
  loading: false,
  error: "",
};

export default function BacktestHistory() {
  const [runs, setRuns] = useState([]);
  const [page, setPage] = useState(1);
  const [selectedRecordId, setSelectedRecordId] = useState(null);
  const [selectedTrades, setSelectedTrades] = useState([]);
  const [isClearingRuns, setIsClearingRuns] = useState(false);
  const [tradeReview, setTradeReview] = useState(EMPTY_TRADE_REVIEW);

  function loadRuns() {
    apiFetch("/api/backtests")
      .then((res) => res.json())
      .then((data) => {
        setRuns(data);
        setPage(1);
        setTradeReview(EMPTY_TRADE_REVIEW);
        setSelectedRecordId((currentRecordId) => {
          if (data.some((run) => run.recordId === currentRecordId)) {
            return currentRecordId;
          }
          return data[0]?.recordId ?? null;
        });
      })
      .catch((err) => {
        console.error("Error fetching backtests:", err);
        setRuns([]);
        setSelectedRecordId(null);
        setSelectedTrades([]);
      });
  }

  function clearRuns() {
    setIsClearingRuns(true);

    apiFetch("/api/backtests/clear", {
      method: "POST",
    })
      .then((response) => {
        if (!response.ok) {
          throw new Error("Failed to clear backtests.");
        }

        setRuns([]);
        setPage(1);
        setSelectedRecordId(null);
        setSelectedTrades([]);
        setTradeReview(EMPTY_TRADE_REVIEW);
      })
      .catch((error) => {
        console.error("Error clearing backtests:", error);
      })
      .finally(() => {
        setIsClearingRuns(false);
      });
  }

  useEffect(() => {
    loadRuns();
  }, []);

  useEffect(() => {
    if (!selectedRecordId) {
      return;
    }

    apiFetch(`/api/backtests/${selectedRecordId}/trades`)
      .then((res) => res.json())
      .then((data) => setSelectedTrades(data))
      .catch((err) => {
        console.error("Error fetching trades:", err);
        setSelectedTrades([]);
      });
  }, [selectedRecordId]);

  function selectRun(recordId) {
    setTradeReview(EMPTY_TRADE_REVIEW);
    setSelectedRecordId(recordId);
  }

  function openTradeReview(trade) {
    if (!trade?.id || !selectedRecordId) {
      return;
    }

    setTradeReview({
      trade,
      chartData: null,
      timeframe: "1Min",
      loading: true,
      error: "",
    });
    loadTradeChart(trade, "1Min");
  }

  function changeTradeTimeframe(timeframe) {
    if (!tradeReview.trade) {
      return;
    }

    setTradeReview((current) => ({
      ...current,
      timeframe,
      loading: true,
      error: "",
    }));
    loadTradeChart(tradeReview.trade, timeframe);
  }

  function loadTradeChart(trade, timeframe) {
    apiFetch(`/api/backtests/${selectedRecordId}/trades/${trade.id}/chart?timeframe=${encodeURIComponent(timeframe)}`)
      .then((response) => {
        if (!response.ok) {
          throw new Error("Could not load this trade chart.");
        }
        return response.json();
      })
      .then((chartData) => {
        setTradeReview((current) => ({
          ...current,
          trade,
          chartData,
          timeframe,
          loading: false,
          error: "",
        }));
      })
      .catch((error) => {
        console.error("Error fetching trade chart:", error);
        setTradeReview((current) => ({
          ...current,
          trade,
          chartData: null,
          timeframe,
          loading: false,
          error: error.message || "Could not load this trade chart.",
        }));
      });
  }

  function closeTradeReview() {
    setTradeReview(EMPTY_TRADE_REVIEW);
  }

  const totalPages = Math.max(1, Math.ceil(runs.length / PAGE_SIZE));
  const boundedPage = Math.min(page, totalPages);
  const pageRuns = runs.slice((boundedPage - 1) * PAGE_SIZE, boundedPage * PAGE_SIZE);
  const selectedRun = runs.find((run) => run.recordId === selectedRecordId) ?? null;

  return (
    <div className="app-page">
      <h2 className="app-title">Backtest History</h2>

      <div className="app-panel">
        <div className="d-flex align-items-center justify-content-between gap-2">
          <div className="fw-bold app-kicker">Previous Runs</div>
          <button
            type="button"
            className="app-btn app-btn-danger px-3"
            onClick={clearRuns}
            disabled={runs.length === 0 || isClearingRuns}
          >
            {isClearingRuns ? "Clearing..." : "Clear Runs"}
          </button>
        </div>

        <div className="app-table-wrap">
          <div className="app-grid-head history-grid">
            <div>Run</div>
            <div>Equity</div>
            <div>Range</div>
            <div>Win Rate</div>
            <div>Total Profit</div>
            <div>Return</div>
            <div>Drawdown</div>
            <div>Trades</div>
            <div className="text-end">Action</div>
          </div>

          {pageRuns.map((run) => {
            const isSelected = run.recordId === selectedRecordId;

            return (
              <div
                key={run.recordId ?? run.id}
                className={isSelected ? "app-grid-row history-grid selected" : "app-grid-row history-grid"}
              >
                <div>{run.name ?? run.id}</div>
                <div>{run.equity}</div>
                <div>
                  {formatEstTime(run.start)} → {formatEstTime(run.end)}
                </div>
                <div>{run?.winRate == null ? "—" : formatPercent(run.winRate)}</div>
                <div
                  className={
                    run?.totalProfit == null ? "app-muted" : run?.totalProfit >= 0 ? "app-pnl-pos" : "app-pnl-neg"
                  }
                >
                  {run?.totalProfit == null ? "—" : formatCurrency(run.totalProfit)}
                </div>
                <div>{run?.totalReturn == null ? "—" : formatPercent(run.totalReturn)}</div>
                <div>{run?.drawdown == null ? "—" : formatPercent(run.drawdown)}</div>
                <div>{run.trades ?? "—"}</div>
                <div className="text-end">
                  <button
                    type="button"
                    className={isSelected ? "app-btn app-btn-selected px-3" : "app-btn px-3"}
                    onClick={() => selectRun(run.recordId)}
                  >
                    {isSelected ? "Selected" : "Select"}
                  </button>
                </div>
              </div>
            );
          })}

          {runs.length === 0 && <div className="app-empty">No backtest runs yet. Run a backtest to populate history.</div>}
        </div>

        <div className="d-flex align-items-center justify-content-between gap-2 mt-3">
          <button
            type="button"
            className="app-btn px-3"
            disabled={boundedPage === 1}
            onClick={() => setPage((currentPage) => Math.max(1, currentPage - 1))}
          >
            Prev
          </button>

          <div className="app-muted app-kicker">
            Page <b>{boundedPage}</b> of <b>{totalPages}</b>
          </div>

          <button
            type="button"
            className="app-btn px-3"
            disabled={boundedPage === totalPages}
            onClick={() => setPage((currentPage) => Math.min(totalPages, currentPage + 1))}
          >
            Next
          </button>
        </div>
      </div>

      {selectedRun && (
        <RunPreview
          run={selectedRun}
          trades={selectedTrades}
          showTradeLogs={true}
          onOpenTrade={openTradeReview}
        />
      )}

      {tradeReview.trade && (
        <BacktestTradeReviewModal
          chartData={tradeReview.chartData}
          error={tradeReview.error}
          loading={tradeReview.loading}
          onClose={closeTradeReview}
          onTimeframeChange={changeTradeTimeframe}
          timeframe={tradeReview.timeframe}
          trade={tradeReview.trade}
        />
      )}
    </div>
  );
}

function formatPercent(value) {
  return `${Number(value).toFixed(2)}%`;
}

function formatCurrency(value) {
  const numeric = Number(value);
  const sign = numeric > 0 ? "+" : "";
  return `${sign}$${numeric.toFixed(2)}`;
}
