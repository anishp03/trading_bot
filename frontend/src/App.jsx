import { useEffect, useMemo, useState } from "react";
import { Routes, Route, Navigate, useLocation } from "react-router-dom";
import AppLayout from "./layout/AppLayout.jsx";
import Dashboard from "./pages/Dashboard.jsx";
import Strategy from "./pages/Strategy.jsx";
import Backtest from "./pages/Backtest.jsx";
import BacktestHistory from "./pages/BacktestHistory.jsx";
import FuturesBacktest from "./pages/FuturesBacktest.jsx";
import FuturesBacktestHistory from "./pages/FuturesBacktestHistory.jsx";
import FuturesStrategy from "./pages/FuturesStrategy.jsx";
import FuturesLive from "./pages/FuturesLive.jsx";
import Settings from "./pages/Settings.jsx";
import NotFound from "./pages/NotFound.jsx";
import Login from "./pages/Login.jsx";
import { apiFetch, clearStoredAuth, readStoredAuth, writeStoredAuth } from "./utils/api.js";

const PRIMARY_ACCOUNT_EMAIL = import.meta.env.VITE_PRIMARY_ACCOUNT_EMAIL || "local@example.invalid";

function ProtectedLayout({ auth, onLogout }) {
  const location = useLocation();

  if (auth.status === "loading") {
    return (
      <div className="auth-page">
        <div className="app-panel auth-panel">
          <h2 className="app-title text-center">Trading Bot</h2>
          <div className="app-muted text-center">Checking session...</div>
        </div>
      </div>
    );
  }

  if (!auth.token) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  return <AppLayout accountEmail={auth.email} accountRole={auth.role} onLogout={onLogout} />;
}

export default function App() {
  const [auth, setAuth] = useState(() => {
    const stored = readStoredAuth();
    return {
      status: stored?.token ? "loading" : "anonymous",
      token: stored?.token || "",
      email: stored?.email || PRIMARY_ACCOUNT_EMAIL,
      role: stored?.role || "viewer",
      expiresAt: stored?.expiresAt || "",
    };
  });

  useEffect(() => {
    let isMounted = true;
    const stored = readStoredAuth();

    if (!stored?.token) {
      return undefined;
    }

    apiFetch("/api/session")
      .then(async (response) => {
        if (!isMounted) {
          return;
        }

        if (!response.ok) {
          clearStoredAuth();
          setAuth({
            status: "anonymous",
            token: "",
            email: PRIMARY_ACCOUNT_EMAIL,
            role: "viewer",
            expiresAt: "",
          });
          return;
        }

        const session = await response.json();
        const nextAuth = {
          token: stored.token,
          email: session.email || stored.email || PRIMARY_ACCOUNT_EMAIL,
          role: session.role || stored.role || "viewer",
          expiresAt: session.expiresAt || stored.expiresAt || "",
        };
        writeStoredAuth(nextAuth);
        setAuth({ ...nextAuth, status: "authenticated" });
      })
      .catch((error) => {
        console.error(error);
        if (isMounted) {
          clearStoredAuth();
          setAuth((current) => ({ ...current, status: "anonymous", token: "" }));
        }
      });

    return () => {
      isMounted = false;
    };
  }, []);

  const isAuthenticated = Boolean(auth.token);
  const loginAuth = useMemo(
    () => ({
      isAuthenticated,
      setSession(session) {
        const nextAuth = {
          token: session.token,
          email: session.email || PRIMARY_ACCOUNT_EMAIL,
          role: session.role || "viewer",
          expiresAt: session.expiresAt || "",
        };
        writeStoredAuth(nextAuth);
        setAuth({ ...nextAuth, status: "authenticated" });
      },
    }),
    [isAuthenticated]
  );

  function handleLogout() {
    apiFetch("/api/logout", { method: "POST" }).catch((error) => console.error(error));
    clearStoredAuth();
    setAuth({
      status: "anonymous",
      token: "",
      email: PRIMARY_ACCOUNT_EMAIL,
      role: "viewer",
      expiresAt: "",
    });
  }

  return (
    <Routes>
      <Route
        path="/login"
        element={
          loginAuth.isAuthenticated ? (
            <Navigate to="/dashboard" replace />
          ) : (
            <Login onLogin={loginAuth.setSession} />
          )
        }
      />
      <Route path="/create-account" element={<Navigate to="/dashboard" replace />} />

      <Route element={<ProtectedLayout auth={auth} onLogout={handleLogout} />}>
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={<Dashboard accountEmail={auth.email} />} />
        <Route path="/strategy" element={<Strategy />} />
        <Route path="/backtest" element={<Backtest accountEmail={auth.email} />} />
        <Route path="/backtest-history" element={<BacktestHistory />} />
        <Route path="/futures-strategy" element={<FuturesStrategy />} />
        <Route path="/futures-backtest" element={<FuturesBacktest />} />
        <Route path="/futures-backtest-history" element={<FuturesBacktestHistory />} />
        <Route path="/futures-live" element={<FuturesLive />} />
        <Route path="/settings" element={<Settings accountEmail={auth.email} />} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}
