import { useEffect, useMemo, useState } from "react";
import { formatEstTime } from "../utils/time.js";

export default function RunPreview({
  run,
  trades = null,
  totalTradeCount = null,
  tradePreviewLimit = null,
  loadTradesPage = null,
  showTradeLogs = true,
  showCapitalCards = true,
  onOpenTrade = null,
}) {
  const serverTradeMode = typeof loadTradesPage === "function";
  const [outcomeFilter, setOutcomeFilter] = useState("all");
  const [symbolFilter, setSymbolFilter] = useState("all");
  const [sideFilter, setSideFilter] = useState("all");
  const [strategyFilter, setStrategyFilter] = useState("all");
  const [tradeSort, setTradeSort] = useState("newest");
  const [startDateFilter, setStartDateFilter] = useState("");
  const [endDateFilter, setEndDateFilter] = useState("");
  const [tradePage, setTradePage] = useState(1);
  const [serverTrades, setServerTrades] = useState({
    trades: [],
    total: 0,
    filteredTotal: 0,
    filteredPnl: 0,
    filteredWinRate: 0,
    symbols: [],
    strategies: [],
  });
  const [isLoadingTrades, setIsLoadingTrades] = useState(false);
  const activeFilterCount = [
    outcomeFilter !== "all",
    symbolFilter !== "all",
    sideFilter !== "all",
    strategyFilter !== "all",
    startDateFilter,
    endDateFilter,
  ].filter(Boolean).length;

  useEffect(() => {
    setOutcomeFilter("all");
    setSymbolFilter("all");
    setSideFilter("all");
    setStrategyFilter("all");
    setTradeSort("newest");
    setStartDateFilter("");
    setEndDateFilter("");
    setTradePage(1);
    setServerTrades({
      trades: [],
      total: 0,
      filteredTotal: 0,
      filteredPnl: 0,
      filteredWinRate: 0,
      symbols: [],
      strategies: [],
    });
  }, [run?.id]);

  useEffect(() => {
    setTradePage(1);
  }, [endDateFilter, outcomeFilter, sideFilter, startDateFilter, strategyFilter, symbolFilter, tradeSort]);

  useEffect(() => {
    if (!serverTradeMode || !run?.id) {
      return;
    }
    let cancelled = false;
    setIsLoadingTrades(true);
    loadTradesPage({
      page: tradePage,
      limit: tradePreviewLimit || 500,
      outcome: outcomeFilter,
      symbol: symbolFilter,
      side: sideFilter,
      strategy: strategyFilter,
      sort: tradeSort,
      startDate: startDateFilter,
      endDate: endDateFilter,
    })
      .then((data) => {
        if (cancelled) return;
        setServerTrades({
          trades: Array.isArray(data?.trades) ? data.trades : [],
          total: Number(data?.total || 0),
          filteredTotal: Number(data?.filteredTotal || 0),
          filteredPnl: Number(data?.filteredPnl || 0),
          filteredWinRate: Number(data?.filteredWinRate || 0),
          symbols: Array.isArray(data?.symbols) ? data.symbols : [],
          strategies: Array.isArray(data?.strategies) ? data.strategies : [],
        });
      })
      .catch((error) => {
        if (cancelled) return;
        console.error("Error loading paged trades:", error);
        setServerTrades({
          trades: [],
          total: 0,
          filteredTotal: 0,
          filteredPnl: 0,
          filteredWinRate: 0,
          symbols: [],
          strategies: [],
        });
      })
      .finally(() => {
        if (!cancelled) setIsLoadingTrades(false);
      });
    return () => {
      cancelled = true;
    };
  }, [endDateFilter, loadTradesPage, outcomeFilter, run?.id, serverTradeMode, sideFilter, startDateFilter, strategyFilter, symbolFilter, tradePage, tradePreviewLimit, tradeSort]);

  const visibleTrades = serverTradeMode ? serverTrades.trades : trades;
  const symbols = useMemo(() => serverTradeMode ? serverTrades.symbols : uniqueTradeValues(trades, "symbol"), [serverTradeMode, serverTrades.symbols, trades]);
  const strategies = useMemo(() => serverTradeMode ? serverTrades.strategies : uniqueTradeValues(trades, "strategyName"), [serverTradeMode, serverTrades.strategies, trades]);

  const filteredTrades = useMemo(() => {
    if (!Array.isArray(visibleTrades)) return [];
    if (serverTradeMode) return visibleTrades;

    let nextTrades = visibleTrades.filter((trade) => {
      const pnl = Number(trade?.pnl ?? 0);
      if (outcomeFilter === "profits" && pnl <= 0) return false;
      if (outcomeFilter === "losses" && pnl >= 0) return false;
      if (outcomeFilter === "flat" && pnl !== 0) return false;
      if (symbolFilter !== "all" && String(trade?.symbol || "") !== symbolFilter) return false;
      if (sideFilter !== "all" && normalizeSide(trade?.side) !== sideFilter) return false;
      if (strategyFilter !== "all" && String(trade?.strategyName || "") !== strategyFilter) return false;
      if (!isTradeWithinDateRange(trade, startDateFilter, endDateFilter)) return false;
      return true;
    });

    nextTrades = [...nextTrades];
    nextTrades.sort((firstTrade, secondTrade) => {
      const firstPnl = Number(firstTrade?.pnl ?? 0);
      const secondPnl = Number(secondTrade?.pnl ?? 0);

      if (tradeSort === "largestLoss") return firstPnl - secondPnl;
      if (tradeSort === "largestWin") return secondPnl - firstPnl;

      const firstTime = parseTradeTimestamp(firstTrade?.time || firstTrade?.openedAt || firstTrade?.closedAt) || 0;
      const secondTime = parseTradeTimestamp(secondTrade?.time || secondTrade?.openedAt || secondTrade?.closedAt) || 0;
      if (tradeSort === "oldest") return firstTime - secondTime;
      return secondTime - firstTime;
    });

    return nextTrades;
  }, [endDateFilter, outcomeFilter, serverTradeMode, sideFilter, startDateFilter, strategyFilter, symbolFilter, tradeSort, visibleTrades]);

  const filteredPnl = serverTradeMode ? serverTrades.filteredPnl : filteredTrades.reduce((total, trade) => total + Number(trade?.pnl ?? 0), 0);
  const filteredWins = filteredTrades.filter((trade) => Number(trade?.pnl ?? 0) > 0).length;
  const filteredCount = serverTradeMode ? serverTrades.filteredTotal : filteredTrades.length;
  const filteredWinRate = serverTradeMode ? serverTrades.filteredWinRate : (filteredTrades.length > 0 ? (filteredWins / filteredTrades.length) * 100 : 0);
  const filteredTotalReturn = calculateFilteredTotalReturn(run, filteredTrades, filteredPnl);
  const renderedTrades = serverTradeMode ? filteredTrades : filteredTrades.slice(0, 250);
  const totalTrades = serverTradeMode ? serverTrades.total : Number(totalTradeCount ?? run?.trades ?? trades?.length ?? 0);
  const loadedTrades = Array.isArray(visibleTrades) ? visibleTrades.length : 0;
  const isTradePreviewLimited = !serverTradeMode && Array.isArray(visibleTrades) && totalTrades > loadedTrades;
  const isRenderLimited = !serverTradeMode && filteredTrades.length > renderedTrades.length;
  const pageSize = tradePreviewLimit || 500;
  const totalTradePages = Math.max(1, Math.ceil(filteredCount / pageSize));
  const boundedTradePage = Math.min(tradePage, totalTradePages);

  return (
    <div className="app-panel">
      <div className="d-flex align-items-start justify-content-between gap-2">
        <div className="fw-bold app-kicker">Run Preview</div>
      </div>

      <div className="app-subpanel mt-3">
        <div className="fw-bold app-kicker">Run Summary</div>

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
              <div className="fw-bold app-kicker">All Trades</div>
              <div className="app-muted app-kicker">
                {Array.isArray(trades)
                  ? serverTradeMode ? pagedTradeLogSummary({
                    page: boundedTradePage,
                    totalPages: totalTradePages,
                    pageCount: renderedTrades.length,
                    filteredCount,
                    totalCount: totalTrades,
                    loading: isLoadingTrades,
                  }) : tradeLogSummary({
                    filteredCount: filteredTrades.length,
                    renderedCount: renderedTrades.length,
                    loadedCount: loadedTrades,
                    totalCount: totalTrades,
                    previewLimit: tradePreviewLimit,
                    previewLimited: isTradePreviewLimited,
                    renderLimited: isRenderLimited,
                  })
                : "No per-trade data attached to this run yet."}
              </div>
            </div>
            {activeFilterCount > 0 && (
              <button type="button" className="app-btn app-btn-small px-3" onClick={() => {
                setOutcomeFilter("all");
                setSymbolFilter("all");
                setSideFilter("all");
                setStrategyFilter("all");
                setStartDateFilter("");
                setEndDateFilter("");
              }}>
                Clear Filters
              </button>
            )}
          </div>

          {Array.isArray(trades) && (
            <>
              <div className="app-trade-toolbar app-backtest-trade-toolbar mt-3">
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
                  <span className="app-label">Symbol</span>
                  <select className="form-select app-input" value={symbolFilter} onChange={(event) => setSymbolFilter(event.target.value)}>
                    <option value="all">All Symbols</option>
                    {symbols.map((symbol) => (
                      <option key={symbol} value={symbol}>{symbol}</option>
                    ))}
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
                    <option value="newest">Most Recent</option>
                    <option value="oldest">Earliest First</option>
                    <option value="largestWin">Largest Win</option>
                    <option value="largestLoss">Largest Loss</option>
                  </select>
                </label>
                <label className="d-grid gap-1">
                  <span className="app-label">Start Date</span>
                  <input
                    className="form-control app-input"
                    type="date"
                    value={startDateFilter}
                    max={endDateFilter || undefined}
                    onInput={(event) => setStartDateFilter(event.target.value)}
                    onChange={(event) => setStartDateFilter(event.target.value)}
                  />
                </label>
                <label className="d-grid gap-1">
                  <span className="app-label">End Date</span>
                  <input
                    className="form-control app-input"
                    type="date"
                    value={endDateFilter}
                    min={startDateFilter || undefined}
                    onInput={(event) => setEndDateFilter(event.target.value)}
                    onChange={(event) => setEndDateFilter(event.target.value)}
                  />
                </label>
              </div>

              {activeFilterCount > 0 && (
                <div className="row g-2 mt-1">
                  <MetricCard title="Filtered P/L" value={formatSignedMoney(filteredPnl)} accent={filteredPnl} />
                  <MetricCard title="Filtered Trades" value={filteredCount} />
                  <MetricCard title="Filtered Win Rate" value={`${formatNumber(filteredWinRate)}%`} />
                  <MetricCard title="Filtered Total Return" value={`${formatNumber(filteredTotalReturn)}%`} accent={filteredTotalReturn} />
                </div>
              )}
              {serverTradeMode && (
                <div className="d-flex align-items-center justify-content-between gap-2 mt-3 flex-wrap">
                  <button type="button" className="app-btn px-3" disabled={boundedTradePage <= 1 || isLoadingTrades} onClick={() => setTradePage((current) => Math.max(1, current - 1))}>
                    Prev Trades
                  </button>
                  <div className="app-muted app-kicker">
                    Trade Page <b>{boundedTradePage}</b> of <b>{totalTradePages}</b> · {formatNumber(pageSize, 0)} rows/page
                  </div>
                  <button type="button" className="app-btn px-3" disabled={boundedTradePage >= totalTradePages || isLoadingTrades} onClick={() => setTradePage((current) => Math.min(totalTradePages, current + 1))}>
                    Next Trades
                  </button>
                </div>
              )}
            </>
          )}

          <div className="mobile-trade-card-list">
            {!Array.isArray(trades) ? (
              <div className="app-empty">No trades to display for this run.</div>
            ) : (
              <>
                {renderedTrades.map((trade, index) => (
                  <article
                    className={onOpenTrade ? "mobile-trade-card trade-analysis-clickable" : "mobile-trade-card"}
                    key={`preview-${trade.id ?? trade.time ?? "t"}-${index}`}
                    role={onOpenTrade ? "button" : undefined}
                    tabIndex={onOpenTrade ? 0 : undefined}
                    onClick={onOpenTrade ? () => onOpenTrade(trade) : undefined}
                    onKeyDown={onOpenTrade ? (event) => {
                      if (event.key !== "Enter" && event.key !== " ") return;
                      event.preventDefault();
                      onOpenTrade(trade);
                    } : undefined}
                  >
                    <div className="mobile-trade-card-head">
                      <div>
                        <span className="app-label">{trade.contractName || trade.symbol || "--"} / {trade.strategyName || trade.strategyCode || "--"}</span>
                        <strong className={trade?.pnl == null ? "app-muted" : trade?.pnl >= 0 ? "app-pnl-pos" : "app-pnl-neg"}>
                          {trade?.pnl == null ? "--" : formatSignedMoney(trade.pnl)}
                        </strong>
                      </div>
                      <span className={normalizeSide(trade?.side) === "short" ? "app-side-pill short" : "app-side-pill long"}>
                        {trade.side ?? "--"}
                      </span>
                    </div>

                    <div className="mobile-trade-meta-grid">
                      <span>
                        <b>Time</b>
                        <em>{formatEstTime(trade.time ?? "--")}</em>
                      </span>
                      <span>
                        <b>Duration</b>
                        <em>{formatTradeDuration(trade)}</em>
                      </span>
                      <span>
                        <b>Qty</b>
                        <em>{formatNumber(trade.qty)}</em>
                      </span>
                      <span>
                        <b>Entry</b>
                        <em>{trade.entry == null ? "--" : formatMoney(trade.entry)}</em>
                      </span>
                      <span>
                        <b>Exit</b>
                        <em>{trade.exit == null ? "--" : formatMoney(trade.exit)}</em>
                      </span>
                      <span>
                        <b>Fees</b>
                        <em>{formatTradeFees(trade)}</em>
                      </span>
                    </div>

                    <details className="mobile-trade-details">
                      <summary>Trade details</summary>
                      <div>
                        <span>Notes</span>
                        <p>{trade?.tradeNotes?.trim() ? trade.tradeNotes : "--"}</p>
                      </div>
                      {trade.closedAt && (
                        <div>
                          <span>Closed</span>
                          <p>{formatEstTime(trade.closedAt)}</p>
                        </div>
                      )}
                    </details>
                  </article>
                ))}

                {isLoadingTrades && <div className="app-empty">Loading trades...</div>}
                {!isLoadingTrades && filteredTrades.length === 0 && <div className="app-empty">No trades match this filter.</div>}
                {isRenderLimited && <div className="app-empty">Narrow the filters to inspect more matching trades.</div>}
              </>
            )}
          </div>

          <div className="app-table-wrap desktop-trade-table">
            <div className={onOpenTrade ? "app-grid-head trades-grid has-action" : "app-grid-head trades-grid"}>
              <div>Time</div>
              <div>Duration</div>
              <div>Symbol</div>
              <div>Strategy</div>
              <div>Side</div>
              <div>Qty</div>
              <div>Entry</div>
              <div>Exit</div>
              <div>PnL</div>
              <div>Fees</div>
              {onOpenTrade && <div>Action</div>}
            </div>

            {!Array.isArray(trades) ? (
              <div className="app-empty">No trades to display for this run.</div>
            ) : (
              <>
                {renderedTrades.map((trade, index) => (
                  <div
                    key={`${trade.id ?? trade.time ?? "t"}-${index}`}
                    className={onOpenTrade ? "app-grid-row trades-grid has-action trade-analysis-clickable" : "app-grid-row trades-grid"}
                    role={onOpenTrade ? "button" : undefined}
                    tabIndex={onOpenTrade ? 0 : undefined}
                    onClick={onOpenTrade ? () => onOpenTrade(trade) : undefined}
                    onKeyDown={onOpenTrade ? (event) => {
                      if (event.key !== "Enter" && event.key !== " ") return;
                      event.preventDefault();
                      onOpenTrade(trade);
                    } : undefined}
                  >
                    <div className="app-time-cell">
                      <strong>{formatEstTime(trade.time ?? "--")}</strong>
                      {trade.closedAt && <span>{formatEstTime(trade.closedAt)}</span>}
                    </div>
                    <div>{formatTradeDuration(trade)}</div>
                    <div>
                      <strong>{trade.symbol || "--"}</strong>
                      {trade.contractName && <span className="app-muted d-block">{trade.contractName}</span>}
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
                    <div className="app-trade-fees">{formatTradeFees(trade)}</div>
                    {onOpenTrade && (
                      <div>
                        <button type="button" className="app-btn app-btn-small px-3" onClick={(event) => {
                          event.stopPropagation();
                          onOpenTrade(trade);
                        }}>
                          Open
                        </button>
                      </div>
                    )}
                  </div>
                ))}

                {isLoadingTrades && <div className="app-empty">Loading trades...</div>}
                {!isLoadingTrades && filteredTrades.length === 0 && <div className="app-empty">No trades match this filter.</div>}
                {isRenderLimited && <div className="app-empty">Narrow the filters to inspect more matching trades.</div>}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function tradeLogSummary({
  filteredCount,
  renderedCount,
  loadedCount,
  totalCount,
  previewLimit,
  previewLimited,
  renderLimited,
}) {
  if (previewLimited) {
    const limitText = previewLimit ? `top ${formatNumber(previewLimit, 0)}` : formatNumber(loadedCount, 0);
    const renderedText = renderLimited ? ` Rendering ${formatNumber(renderedCount, 0)} rows to keep the page responsive.` : "";
    return `Loaded ${limitText} material trades from ${formatNumber(totalCount, 0)} total. Filtered ${formatNumber(filteredCount, 0)} loaded trades.${renderedText}`;
  }
  if (renderLimited) {
    return `Showing ${formatNumber(renderedCount, 0)} of ${formatNumber(filteredCount, 0)} matching trades. Narrow the filters to inspect more.`;
  }
  return `Showing ${formatNumber(filteredCount, 0)} of ${formatNumber(loadedCount, 0)} trades.`;
}

function pagedTradeLogSummary({
  page,
  totalPages,
  pageCount,
  filteredCount,
  totalCount,
  loading,
}) {
  if (loading) {
    return `Loading page ${formatNumber(page, 0)} of ${formatNumber(totalPages, 0)}.`;
  }
  return `Showing ${formatNumber(pageCount, 0)} trades on page ${formatNumber(page, 0)} of ${formatNumber(totalPages, 0)}. Filtered ${formatNumber(filteredCount, 0)} of ${formatNumber(totalCount, 0)} total trades.`;
}

function formatTradeDuration(trade) {
  const start = parseTradeTimestamp(trade?.openedAt || trade?.time);
  const end = parseTradeTimestamp(trade?.closedAt);
  if (!start || !end || end < start) return "--";
  return formatDurationMs(end - start);
}

function parseTradeTimestamp(value) {
  if (!value) return null;
  const parsed = Date.parse(String(value).trim().replace(" ", "T"));
  return Number.isNaN(parsed) ? null : parsed;
}

function formatDurationMs(durationMs) {
  const totalMinutes = Math.max(0, Math.round(durationMs / 60000));
  if (totalMinutes < 60) return `${totalMinutes}m`;
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
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

function isTradeWithinDateRange(trade, startDate, endDate) {
  if (!startDate && !endDate) return true;

  const tradeDate = extractTradeDate(trade?.time ?? trade?.openedAt ?? trade?.closedAt);
  if (!tradeDate) return false;
  if (startDate && tradeDate < startDate) return false;
  if (endDate && tradeDate > endDate) return false;
  return true;
}

function extractTradeDate(value) {
  if (value == null || value === "") return "";

  const raw = String(value).trim();
  const isoDate = raw.match(/^(\d{4})-(\d{2})-(\d{2})/);
  if (isoDate) {
    const [, year, month, day] = isoDate;
    return `${year}-${month}-${day}`;
  }

  const usDate = raw.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})/);
  if (usDate) {
    const [, month, day, year] = usDate;
    return `${year}-${pad2(month)}-${pad2(day)}`;
  }

  const parsed = new Date(raw.replace(/\s+(ET|EST|EDT)\b/i, ""));
  if (Number.isNaN(parsed.getTime())) return "";

  const year = parsed.getFullYear();
  const month = pad2(parsed.getMonth() + 1);
  const day = pad2(parsed.getDate());
  return `${year}-${month}-${day}`;
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

function formatTradeFees(trade) {
  const raw = trade?.fees ?? trade?.totalFees ?? trade?.commission;
  if (raw == null || raw === "") return "--";
  const amount = Number(raw);
  if (!Number.isFinite(amount)) return "--";
  return formatMoney(Math.abs(amount));
}

function pad2(value) {
  return String(value).padStart(2, "0");
}
