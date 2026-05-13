import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "../utils/api.js";
import { formatEstTime } from "../utils/time.js";

const marketSections = {
  stocks: {
    label: "Stocks",
    defaultPath: "/dashboard",
    items: [
      { to: "/dashboard", label: "Live Stock" },
      { to: "/strategy", label: "Stock Strategy" },
      { to: "/backtest", label: "Backtest" },
      { to: "/backtest-history", label: "Backtest History" },
    ],
  },
  futures: {
    label: "Futures",
    defaultPath: "/futures-live",
    items: [
      { to: "/futures-live", label: "Live Futures" },
      { to: "/futures-strategy", label: "Futures Strategy" },
      { to: "/futures-backtest", label: "Backtest" },
      { to: "/futures-backtest-history", label: "Backtest History" },
    ],
  },
};

const systemItems = [{ to: "/settings", label: "Settings" }];
const navItems = [
  ...marketSections.stocks.items,
  ...marketSections.futures.items,
  ...systemItems,
];
const FUTURES_STATUS_REFRESH_MS = 30000;

function navClassName({ isActive }) {
  return isActive ? "app-nav-link active" : "app-nav-link";
}

export default function AppLayout({ accountEmail, accountRole, backendMode = "online", onLogout }) {
  const location = useLocation();
  const navigate = useNavigate();
  const routeMarket = marketForPath(location.pathname);
  const [selectedMarket, setSelectedMarket] = useState(routeMarket || "stocks");
  const [futuresSidebarStatus, setFuturesSidebarStatus] = useState(null);
  const [futuresSidebarOnline, setFuturesSidebarOnline] = useState(backendMode !== "offline");
  const activeMarket = marketSections[selectedMarket] || marketSections.stocks;
  const currentPage = navItems.find((item) => item.to === location.pathname)?.label || "Trading Bot";

  useEffect(() => {
    if (routeMarket) {
      setSelectedMarket(routeMarket);
    }
  }, [routeMarket]);

  function switchMarket(market) {
    setSelectedMarket(market);
    navigate(marketSections[market].defaultPath);
  }

  const loadFuturesSidebarStatus = useCallback(() => {
    return apiFetch("/api/futures/live/sidebar-status")
      .then((response) => {
        if (!response.ok) {
          throw new Error(`Backend returned ${response.status}`);
        }
        return response.json();
      })
      .then((data) => {
        setFuturesSidebarOnline(true);
        setFuturesSidebarStatus(data || null);
        return data || null;
      })
      .catch((error) => {
        console.error("Error loading futures sidebar status:", error);
        setFuturesSidebarOnline(false);
        setFuturesSidebarStatus(null);
        return null;
      });
  }, []);

  useEffect(() => {
    if (selectedMarket !== "futures") {
      return undefined;
    }

    loadFuturesSidebarStatus();
    const intervalId = window.setInterval(loadFuturesSidebarStatus, FUTURES_STATUS_REFRESH_MS);
    return () => {
      window.clearInterval(intervalId);
    };
  }, [loadFuturesSidebarStatus, selectedMarket]);

  return (
    <div className="app-shell">
      <aside className="app-sidebar d-flex flex-column gap-3">
        <div className="app-brand-row d-flex align-items-center gap-2">
          <div className="app-brand-pill d-flex align-items-center justify-content-center">
            TB
          </div>
          <div className="app-brand-name">trading_bot</div>
        </div>

        <nav className="app-nav-sections">
          <div className="app-nav-section">
            <div className="app-nav-section-label">{activeMarket.label}</div>
            <div className="d-grid gap-2">
              {activeMarket.items.map((item) => (
                <NavLink key={item.to} to={item.to} className={navClassName}>
                  {item.label}
                </NavLink>
              ))}
            </div>
            {selectedMarket === "futures" && (
              <FuturesSidebarStatus
                backendOnline={backendMode !== "offline" && futuresSidebarOnline}
                status={futuresSidebarStatus}
              />
            )}
          </div>
        </nav>

        <div className="mt-auto app-system-nav">
          <div className="app-nav-section">
            <div className="app-nav-section-label">System</div>
            <div className="d-grid gap-2">
              {systemItems.map((item) => (
                <NavLink key={item.to} to={item.to} className={navClassName}>
                  {item.label}
                </NavLink>
              ))}
            </div>
          </div>
          <div className="border-top pt-2 app-sidebar-text">trading_bot</div>
        </div>
      </aside>

      <main className="app-main">
        <header className="app-topbar">
          <div className="app-topbar-inner">
            <div className="app-topbar-left">
              <div className="app-market-switch" aria-label="Market navigation">
                {Object.entries(marketSections).map(([market, section]) => (
                  <button
                    type="button"
                    key={market}
                    className={market === selectedMarket ? "app-market-tab active" : "app-market-tab"}
                    aria-pressed={market === selectedMarket}
                    onClick={() => switchMarket(market)}
                  >
                    {section.label}
                  </button>
                ))}
              </div>

              <div className="app-topbar-heading">
                <span className="app-topbar-kicker">{activeMarket.label}</span>
                <div className="app-current-page">{currentPage}</div>
              </div>
            </div>

            <div className="app-topbar-account-group">
              {backendMode === "offline" && <span className="app-topbar-status offline">Backend Offline</span>}
              <span className="app-topbar-btn">{accountRole || "viewer"}</span>
              <span className="app-topbar-account">{accountEmail}</span>
              {onLogout && (
                <button type="button" className="app-topbar-btn app-topbar-action" onClick={onLogout}>
                  Logout
                </button>
              )}
            </div>
          </div>
        </header>

        <section className="app-content">
          <Outlet
            context={{
              futuresSidebarOnline,
              futuresSidebarStatus,
              refreshFuturesSidebarStatus: loadFuturesSidebarStatus,
            }}
          />
        </section>
      </main>
    </div>
  );
}

function marketForPath(pathname) {
  if (marketSections.futures.items.some((item) => item.to === pathname)) {
    return "futures";
  }
  if (marketSections.stocks.items.some((item) => item.to === pathname)) {
    return "stocks";
  }
  return "";
}

function FuturesSidebarStatus({ backendOnline, status }) {
  const backendOn = Boolean(backendOnline && status?.backend?.online !== false);
  const botOn = Boolean(backendOn && status?.bot?.running);
  const marketDataOn = Boolean(backendOn && status?.marketData?.receiving);
  const topstepApiOn = Boolean(backendOn && status?.topstepApi?.ready);
  const tradingOn = Boolean(backendOn && status?.trading?.enabled);
  const strategyOn = Boolean(backendOn && status?.strategyConfig?.active);
  const strategy = status?.strategyConfig || {};
  const strategyResult = strategyOn
    ? `${formatSidebarCurrency(strategy.totalProfit)} | ${formatSidebarPercent(strategy.winRate)} | ${Number(strategy.trades || 0).toLocaleString()} trades`
    : "Copy Backtest Strategy";
  const cards = [
    {
      label: "Backend Status",
      value: backendOn ? "ON" : "OFF",
      tone: backendOn ? "on" : "off",
      detail: backendOn ? "API live" : "API offline",
    },
    {
      label: "Bot Status",
      value: botOn ? "ON" : "OFF",
      tone: botOn ? "on" : "off",
      detail: botOn ? "Live runner active" : "Runner stopped",
    },
    {
      label: "Market Data",
      value: marketDataOn ? "ON" : "OFF",
      tone: marketDataOn ? "on" : "off",
      detail: marketDataOn ? `Fresh ${shortSidebarTime(status?.marketData?.lastEventAt)}` : status?.marketData?.running ? "Feed waiting" : "Feed stopped",
    },
    {
      label: "TopStep API",
      value: topstepApiOn ? "ON" : "OFF",
      tone: topstepApiOn ? "on" : "off",
      detail: topstepApiOn ? `Acct ${status?.topstepApi?.accountId || "--"}` : "Needs test",
    },
    {
      label: "Trading Enabled",
      value: tradingOn ? "ON" : "OFF",
      tone: tradingOn ? "on" : "off",
      detail: status?.trading?.marketSession?.entryWindowOpen ? "9:35-3:45 ET" : status?.trading?.marketSession?.label || "Session closed",
    },
    {
      label: "Strategy Config",
      value: strategyOn ? "ON" : "OFF",
      tone: strategyOn ? "on" : "off",
      detail: strategyOn ? `${shortSidebarTime(strategy.updatedAt)} | ${strategyResult}` : strategyResult,
    },
  ];

  return (
    <div className="app-sidebar-status-grid" aria-label="Futures live status">
      {cards.map((card) => (
        <div className={`app-sidebar-status-card ${card.tone}`} key={card.label} aria-label={`${card.label} ${card.value}: ${card.detail}`}>
          <div>
            <span>{card.label}</span>
          </div>
          <small>{card.detail}</small>
        </div>
      ))}
    </div>
  );
}

function shortSidebarTime(value) {
  const formatted = formatEstTime(value);
  if (!formatted || formatted === "--") {
    return "--";
  }
  return formatted.replace(/\s+(EST|EDT|ET)$/i, "");
}

function formatSidebarCurrency(value) {
  const number = Number(value || 0);
  if (!Number.isFinite(number)) return "$0";
  const abs = Math.abs(number);
  if (abs >= 1000) {
    return `${number < 0 ? "-" : ""}$${(abs / 1000).toFixed(abs >= 100000 ? 0 : 1)}k`;
  }
  return `${number < 0 ? "-" : ""}$${abs.toFixed(0)}`;
}

function formatSidebarPercent(value) {
  const number = Number(value || 0);
  if (!Number.isFinite(number)) return "0.00%";
  return `${number.toFixed(2)}%`;
}
