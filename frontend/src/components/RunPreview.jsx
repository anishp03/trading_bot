import { useMemo, useState } from "react";
import { formatEstTime } from "../utils/time.js";

export default function RunPreview({
  run,
  trades = null,
  showTradeLogs = true,
  showCapitalCards = true,
  onOpenTrade = null,
}) {
  const [outcomeFilter, setOutcomeFilter] = useState("all");
  const [sideFilter, setSideFilter] = useState("all");
  const [strategyFilter, setStrategyFilter] = useState("all");
  const [tradeSort, setTradeSort] = useState("largestWin");

  const strategies = useMemo(() => uniqueTradeValues(trades, "strategyName"), [trades]);

  const filteredTrades = useMemo(() => {
    if (!Array.isArray(trades)) return [];

    let nextTrades = trades.filter((trade) => {
      const pnl = Number(trade?.pnl ?? 0);
      if (outcomeFilter === "profits" && pnl <= 0) return false;
      if (outcomeFilter === "losses" && pnl >= 0) return false;
      if (outcomeFilter === "flat" && pnl !== 0) return false;
      if (sideFilter !== "all" && normalizeSide(trade?.side) !== sideFilter) return false;
      if (strategyFilter !== "all" && String(trade?.strategyName || "") !== strategyFilter) return false;
      return true;
    });

    nextTrades = [...nextTrades];
    nextTrades.sort((firstTrade, secondTrade) => {
      const firstPnl = Number(firstTrade?.pnl ?? 0);
      const secondPnl = Number(secondTrade?.pnl ?? 0);

      if (tradeSort === "largestLoss") return firstPnl - secondPnl;
      return secondPnl - firstPnl;
    });

    return nextTrades;
  }, [outcomeFilter, sideFilter, strategyFilter, tradeSort, trades]);

  const filteredPnl = filteredTrades.reduce((total, trade) => total + Number(trade?.pnl ?? 0), 0);
  const filteredWins = filteredTrades.filter((trade) => Number(trade?.pnl ?? 0) > 0).length;
  const filteredWinRate = filteredTrades.length > 0 ? (filteredWins / filteredTrades.length) * 100 : 0;
  const filteredTotalReturn = calculateFilteredTotalReturn(run, filteredTrades, filteredPnl);

  return (
    <div className="app-panel">
      <div className="d-flex align-items-start justify-content-between gap-2">
        <div className="fw-bold app-kicker">Run Preview</div>
      </div>

      <div className="app-subpanel mt-3">
        <div className="fw-bold app-kicker">Profit & Metrics</div>

        <div className="row g-2 mt-1">
          {showCapitalCards && (
            <>
              <MetricCard
                title="Starting Capital"
                value={run?.startingCapital == null ? "--" : formatMoney(run.startingCapital)}
              />
              <MetricCard
                title="Ending Capital"
                value={run?.endingCapital == null ? "--" : formatMoney(run.endingCapital)}
              />
            </>
          )}
          <MetricCard title="Total Profit" value={run?.totalProfit == null ? "--" : formatMoney(run.totalProfit)} />
          <MetricCard title="Win Rate" value={run?.winRate == null ? "--" : `${formatNumber(run.winRate)}%`} />
          <MetricCard title="Total Return" value={run?.totalReturn == null ? "--" : `${formatNumber(run.totalReturn)}%`} />
          <MetricCard title="Trades" value={run?.trades ?? "--"} />
          <MetricCard title="Profit Factor" value={run?.profitFactor ?? "--"} />
          <MetricCard title="Drawdown" value={run?.drawdown == null ? "--" : `${formatNumber(run.drawdown)}%`} />
        </div>
      </div>

      {showTradeLogs && (
        <div className="app-subpanel mt-3">
          <div className="d-flex justify-content-between align-items-start gap-2 flex-wrap">
            <div>
              <div className="fw-bold app-kicker">Trades / Logs</div>
              <div className="app-muted app-kicker">
                {Array.isArray(trades)
                  ? `Showing ${filteredTrades.length} of ${trades.length} trades.`
                  : "No per-trade data attached to this run yet."}
              </div>
            </div>
          </div>

          {Array.isArray(trades) && (
            <>
              <div className="app-trade-toolbar mt-3">
                <label className="d-grid gap-1">
                  <span className="app-label">Outcome</span>
                  <select className="form-select app-input" value={outcomeFilter} onChange={(event) => setOutcomeFilter(event.target.value)}>
                    <option value="all">All Trades</option>
                    <option value="profits">Profits</option>
                    <option value="losses">Losses</option>
                    <option value="flat">Flat</option>
                  </select>
                </label>
                <label className="d-grid gap-1">
                  <span className="app-label">Side</span>
                  <select className="form-select app-input" value={sideFilter} onChange={(event) => setSideFilter(event.target.value)}>
                    <option value="all">All Sides</option>
                    <option value="long">Long</option>
                    <option value="short">Short</option>
                  </select>
                </label>
                <label className="d-grid gap-1">
                  <span className="app-label">Strategy</span>
                  <select className="form-select app-input" value={strategyFilter} onChange={(event) => setStrategyFilter(event.target.value)}>
                    <option value="all">All Strategies</option>
                    {strategies.map((strategy) => (
                      <option key={strategy} value={strategy}>{strategy}</option>
                    ))}
                  </select>
                </label>
                <label className="d-grid gap-1">
                  <span className="app-label">Sort</span>
                  <select className="form-select app-input" value={tradeSort} onChange={(event) => setTradeSort(event.target.value)}>
                    <option value="largestWin">Largest Win</option>
                    <option value="largestLoss">Largest Loss</option>
                  </select>
                </label>
              </div>

              <div className="row g-2 mt-1">
                <MetricCard title="Filtered P/L" value={formatSignedMoney(filteredPnl)} accent={filteredPnl} />
                <MetricCard title="Filtered Trades" value={filteredTrades.length} />
                <MetricCard title="Filtered Win Rate" value={`${formatNumber(filteredWinRate)}%`} />
                <MetricCard title="Filtered Total Return" value={`${formatNumber(filteredTotalReturn)}%`} accent={filteredTotalReturn} />
              </div>
            </>
          )}

          <div className="app-table-wrap">
            <div className={onOpenTrade ? "app-grid-head trades-grid has-action" : "app-grid-head trades-grid"}>
              <div>Time</div>
              <div>Strategy</div>
              <div>Side</div>
              <div>Qty</div>
              <div>Entry</div>
              <div>Exit</div>
              <div>P/L</div>
              <div>Trade Notes</div>
              {onOpenTrade && <div>Action</div>}
            </div>

            {!Array.isArray(trades) ? (
              <div className="app-empty">No trades to display for this run.</div>
            ) : (
              <>
                {filteredTrades.map((trade, index) => (
                  <div
                    key={`${trade.id ?? trade.time ?? "t"}-${index}`}
                    className={onOpenTrade ? "app-grid-row trades-grid has-action" : "app-grid-row trades-grid"}
                  >
                    <div className="app-time-cell">
                      <strong>{formatEstTime(trade.time ?? "--")}</strong>
                      {trade.closedAt && <span>{formatEstTime(trade.closedAt)}</span>}
                    </div>
                    <div>{trade.strategyName || trade.strategyCode || "--"}</div>
                    <div>
                      <span className={normalizeSide(trade?.side) === "short" ? "app-side-pill short" : "app-side-pill long"}>
                        {trade.side ?? "--"}
                      </span>
                    </div>
                    <div>{formatNumber(trade.qty)}</div>
                    <div>{trade.entry == null ? "--" : formatMoney(trade.entry)}</div>
                    <div>{trade.exit == null ? "--" : formatMoney(trade.exit)}</div>
                    <div className={trade?.pnl == null ? "app-muted" : trade?.pnl >= 0 ? "app-pnl-pos" : "app-pnl-neg"}>
                      {trade?.pnl == null ? "--" : formatSignedMoney(trade.pnl)}
                    </div>
                    <div className="app-trade-notes">{trade?.tradeNotes?.trim() ? trade.tradeNotes : "--"}</div>
                    {onOpenTrade && (
                      <div>
                        <button type="button" className="app-btn app-btn-small px-3" onClick={() => onOpenTrade(trade)}>
                          Open
                        </button>
                      </div>
                    )}
                  </div>
                ))}

                {filteredTrades.length === 0 && <div className="app-empty">No trades match this filter.</div>}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function MetricCard({ title, value, accent = 0 }) {
  const valueClass = accent > 0 ? "fw-bold fs-5 mt-1 app-pnl-pos" : accent < 0 ? "fw-bold fs-5 mt-1 app-pnl-neg" : "fw-bold fs-5 mt-1";

  return (
    <div className="col-6 col-xl-3">
      <div className="app-subpanel h-100">
        <div className="app-label">{title}</div>
        <div className={valueClass}>{value}</div>
      </div>
    </div>
  );
}

function uniqueTradeValues(trades, key) {
  if (!Array.isArray(trades)) return [];
  return [...new Set(trades.map((trade) => String(trade?.[key] || "").trim()).filter(Boolean))].sort();
}

function normalizeSide(value) {
  const normalized = String(value || "").toLowerCase();
  return normalized === "short" || normalized === "sell" ? "short" : "long";
}

function calculateFilteredTotalReturn(run, filteredTrades, filteredPnl) {
  const startingCapital = Number(run?.startingCapital ?? run?.startingEquity ?? 0);
  if (Number.isFinite(startingCapital) && startingCapital > 0) {
    return (filteredPnl / startingCapital) * 100;
  }

  return filteredTrades.reduce((total, trade) => total + Number(trade?.returnPct ?? 0), 0);
}

function formatNumber(value, fractionDigits = 2) {
  const amount = Number(value || 0);
  if (!Number.isFinite(amount)) return "--";
  return new Intl.NumberFormat("en-US", {
    minimumFractionDigits: amount % 1 === 0 ? 0 : Math.min(2, fractionDigits),
    maximumFractionDigits: fractionDigits,
  }).format(amount);
}

function formatMoney(value) {
  const amount = Number(value || 0);
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

function formatSignedMoney(value) {
  const amount = Number(value || 0);
  return `${amount > 0 ? "+" : ""}${formatMoney(amount)}`;
}
