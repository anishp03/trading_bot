import { NavLink, Outlet, useLocation } from "react-router-dom";

const navSections = [
  {
    label: "Stocks",
    items: [
      { to: "/dashboard", label: "Live Stock" },
      { to: "/strategy", label: "Stock Strategy" },
      { to: "/backtest", label: "Backtest" },
      { to: "/backtest-history", label: "Backtest History" },
    ],
  },
  {
    label: "Futures",
    items: [
      { to: "/futures-live", label: "Live Futures" },
      { to: "/futures-strategy", label: "Futures Strategy" },
      { to: "/futures-backtest", label: "Backtest" },
      { to: "/futures-backtest-history", label: "Backtest History" },
    ],
  },
  {
    label: "System",
    items: [{ to: "/settings", label: "Settings" }],
  },
];

function navClassName({ isActive }) {
  return isActive ? "app-nav-link active" : "app-nav-link";
}

export default function AppLayout({ accountEmail, accountRole, onLogout }) {
  const location = useLocation();
  const currentPage =
    navSections
      .flatMap((section) => section.items)
      .find((item) => item.to === location.pathname)?.label || "Trading Bot";

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
          {navSections.map((section) => (
            <div className="app-nav-section" key={section.label}>
              <div className="app-nav-section-label">{section.label}</div>
              <div className="d-grid gap-2">
                {section.items.map((item) => (
                  <NavLink key={item.to} to={item.to} className={navClassName}>
                    {item.label}
                  </NavLink>
                ))}
              </div>
            </div>
          ))}
        </nav>

        <div className="mt-auto border-top pt-2 app-sidebar-text">trading_bot</div>
      </aside>

      <main className="app-main">
        <header className="app-topbar d-flex align-items-center justify-content-between">
          <div className="fw-bold">{currentPage}</div>
          <div className="d-flex align-items-center gap-2">
            <span className="app-topbar-btn">{accountRole || "viewer"}</span>
            <span className="app-topbar-account">{accountEmail}</span>
            <button type="button" className="app-topbar-btn app-topbar-action" onClick={onLogout}>
              Logout
            </button>
          </div>
        </header>

        <section className="app-content">
          <Outlet />
        </section>
      </main>
    </div>
  );
}
