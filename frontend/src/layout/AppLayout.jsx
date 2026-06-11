import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { useCallback, useEffect, useState } from "react";
import { apiFetch, readApiErrorMessage } from "../utils/api.js";
import { formatEstTime } from "../utils/time.js";

const marketSections = {
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

const systemItems = [
  { to: "/documents", label: "Documents" },
  { to: "/settings", label: "Settings" },
];
const navItems = [
  ...marketSections.futures.items,
  ...systemItems,
];
const FUTURES_STATUS_REFRESH_MS = 30000;
const FUTURES_MARKET_DATA_STALE_SECONDS = 30;

function navClassName({ isActive }) {
  return isActive ? "app-nav-link active" : "app-nav-link";
}

function mobileNavClassName({ isActive }) {
  return isActive ? "app-mobile-nav-link active" : "app-mobile-nav-link";
}

function mobileNavLabel(label) {
  const labels = {
    "Live Futures": "Live",
    "Futures Strategy": "Strategy",
    "Backtest": "Test",
    "Backtest History": "History",
    "Documents": "Docs",
    "Settings": "Settings",
  };
  return labels[label] || label;
}

export default function AppLayout({ accountEmail, accountRole, backendMode = "online", onLogout }) {
  const location = useLocation();
  const navigate = useNavigate();
  const routeMarket = marketForPath(location.pathname);
  const [selectedMarket, setSelectedMarket] = useState(routeMarket || "futures");
  const [futuresSidebarStatus, setFuturesSidebarStatus] = useState(null);
  const [futuresSidebarOnline, setFuturesSidebarOnline] = useState(backendMode !== "offline");
  const [futuresSelectedAccountId, setFuturesSelectedAccountId] = useState("");
  const [backendUpdate, setBackendUpdate] = useState({ busy: false, message: "" });
  const [backendUpdatePopup, setBackendUpdatePopup] = useState(null);
  const activeMarket = marketSections[selectedMarket] || marketSections.futures;
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

  async function handleUpdateBackendClick() {
    if (backendUpdate.busy) return;

    const confirmed = window.confirm("Run the backend update now?");
    if (!confirmed) return;

    setBackendUpdate({
      busy: true,
      message: "Starting update...",
    });
    setBackendUpdatePopup(null);

    try {
      const response = await apiFetch("/api/system/backend-update", { method: "POST" });
      const payload = await readJsonResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(payload.json?.message || payload.text || "Backend update failed to start.");
      }
      const message = payload.json?.message || "Backend update started. The API may briefly disconnect.";

      setBackendUpdate({
        busy: false,
        message,
      });
      setBackendUpdatePopup({
        title: "Backend Update Started Successfully",
        message,
        detail: payload.json?.logPath ? `Log: ${payload.json.logPath}` : "The API may briefly disconnect while the live backend restarts.",
      });
    } catch (error) {
      console.error("Error starting backend update:", error);
      setBackendUpdate({
        busy: false,
        message: error.message || "Backend update failed to start.",
      });
    }
  }

  return (
    <div className="app-shell">
      <aside className="app-sidebar d-flex flex-column gap-3">
        <div className="app-brand-row d-flex align-items-center gap-2">
          <div className="app-brand-pill d-flex align-items-center justify-content-center">
            TB
          </div>
          <div className="app-brand-name">Trading Bot</div>
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
                selectedAccountId={futuresSelectedAccountId}
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
          <button
            type="button"
            className="app-update-backend-btn"
            onClick={handleUpdateBackendClick}
            disabled={backendUpdate.busy}
          >
            {backendUpdate.busy ? "Updating..." : "Update Backend"}
          </button>
          {backendUpdate.message && <div className="app-update-backend-status">{backendUpdate.message}</div>}
        </div>
      </aside>

      {backendUpdatePopup && (
        <BackendUpdateSuccessPopup
          popup={backendUpdatePopup}
          onClose={() => setBackendUpdatePopup(null)}
        />
      )}

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
              setFuturesSidebarAccountId: setFuturesSelectedAccountId,
            }}
          />
        </section>
      </main>

      <nav className="app-mobile-nav" aria-label="Primary mobile navigation">
        {navItems.map((item) => (
          <NavLink key={item.to} to={item.to} className={mobileNavClassName}>
            <span>{mobileNavLabel(item.label)}</span>
          </NavLink>
        ))}
      </nav>
    </div>
  );
}

function BackendUpdateSuccessPopup({ popup, onClose }) {
  return (
    <div className="app-backend-update-popup-backdrop" role="presentation" onClick={onClose}>
      <div className="app-backend-update-popup" role="dialog" aria-modal="true" aria-labelledby="backend-update-popup-title" onClick={(event) => event.stopPropagation()}>
        <div className="app-backend-update-popup-icon" aria-hidden="true">OK</div>
        <div className="app-backend-update-popup-copy">
          <h3 id="backend-update-popup-title">{popup.title}</h3>
          <p>{popup.message}</p>
          {popup.detail && <small>{popup.detail}</small>}
        </div>
        <button type="button" className="app-backend-update-popup-close" onClick={onClose} aria-label="Close backend update message">
          Close
        </button>
      </div>
    </div>
  );
}

async function readJsonResponse(response) {
  if (!response.ok) {
    return { json: null, text: await readApiErrorMessage(response, "Backend update failed to start.") };
  }

  const text = await response.text();
  if (!text) {
    return { json: null, text: "" };
  }

  try {
    return { json: JSON.parse(text), text };
  } catch {
    return { json: null, text };
  }
}

function marketForPath(pathname) {
  if (pathname === "/documents") {
    return "futures";
  }
  if (marketSections.futures.items.some((item) => item.to === pathname)) {
    return "futures";
  }
  return "";
}

function FuturesSidebarStatus({ backendOnline, status, selectedAccountId = "" }) {
  const backendOn = Boolean(backendOnline && status?.backend?.online !== false);
  const botOn = Boolean(backendOn && status?.bot?.running);
  const marketDataOn = Boolean(botOn && status?.marketData?.receiving);
  const marketDataStale = Boolean(
    botOn
      && status?.marketData?.running
      && Number(status?.marketData?.staleSeconds ?? -1) > FUTURES_MARKET_DATA_STALE_SECONDS
  );
  const topstepApiOn = Boolean(backendOn && status?.topstepApi?.ready);
  const topstepAccountId = String(selectedAccountId || status?.topstepApi?.accountId || "").trim();
  const tradingOn = Boolean(backendOn && status?.trading?.enabled);
  const cards = [
    {
      label: "Backend Status",
      value: backendOn ? "ON" : "OFF",
      tone: backendOn ? "on" : "off",
      detail: backendOn ? "API live" : "API offline",
    },
    {
      label: "TopStep API",
      value: topstepApiOn ? "ON" : "OFF",
      tone: topstepApiOn ? "on" : "off",
      detail: topstepApiOn ? `Acct ${topstepAccountId || "--"}` : "Needs test",
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
      detail: marketDataOn ? `Fresh ${shortSidebarTime(status?.marketData?.lastEventAt)}` : marketDataStale ? "Data stopped" : status?.marketData?.running ? "Feed waiting" : "Feed stopped",
    },
    {
      label: "Trading Enabled",
      value: tradingOn ? "ON" : "OFF",
      tone: tradingOn ? "on" : "off",
      detail: status?.trading?.marketSession?.entryWindowOpen ? "9:35-3:45 ET" : status?.trading?.marketSession?.label || "Session closed",
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
