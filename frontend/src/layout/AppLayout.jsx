import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { useEffect, useState } from "react";

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

function navClassName({ isActive }) {
  return isActive ? "app-nav-link active" : "app-nav-link";
}

export default function AppLayout({ accountEmail, accountRole, backendMode = "online", onLogout }) {
  const location = useLocation();
  const navigate = useNavigate();
  const routeMarket = marketForPath(location.pathname);
  const [selectedMarket, setSelectedMarket] = useState(routeMarket || "stocks");
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
          <div className="app-market-switch" aria-label="Market navigation">
            {Object.entries(marketSections).map(([market, section]) => (
              <button
                type="button"
                key={market}
                className={market === selectedMarket ? "app-market-tab active" : "app-market-tab"}
                onClick={() => switchMarket(market)}
              >
                {section.label}
              </button>
            ))}
          </div>

          <div className="app-topbar-page-row">
            <div className="fw-bold">{currentPage}</div>
            <div className="d-flex align-items-center gap-2">
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
          <Outlet />
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
