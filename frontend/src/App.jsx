import { useEffect, useState } from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import AppLayout from "./layout/AppLayout.jsx";
import Dashboard from "./pages/Dashboard.jsx";
import Strategy from "./pages/Strategy.jsx";
import Backtest from "./pages/Backtest.jsx";
import BacktestHistory from "./pages/BacktestHistory.jsx";
import FuturesBacktest from "./pages/FuturesBacktest.jsx";
import FuturesBacktestHistory from "./pages/FuturesBacktestHistory.jsx";
import FuturesStrategy from "./pages/FuturesStrategy.jsx";
import FuturesLive from "./pages/FuturesLive.jsx";
import Documents from "./pages/Documents.jsx";
import Settings from "./pages/Settings.jsx";
import Login from "./pages/Login.jsx";
import NotFound from "./pages/NotFound.jsx";
import { apiFetch, clearStoredAuth, readStoredAuth, writeStoredAuth } from "./utils/api.js";

export default function App() {
  const [auth, setAuth] = useState(null);
  const [authChecked, setAuthChecked] = useState(false);

  useEffect(() => {
    const storedAuth = readStoredAuth();
    if (!storedAuth?.token) {
      setAuthChecked(true);
      return;
    }

    apiFetch("/api/session")
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`Session check returned ${response.status}`);
        }
        const session = await response.json();
        const refreshedAuth = {
          ...storedAuth,
          email: session.email || storedAuth.email,
          role: session.role || storedAuth.role,
          expiresAt: session.expiresAt || storedAuth.expiresAt,
        };
        writeStoredAuth(refreshedAuth);
        setAuth(refreshedAuth);
      })
      .catch((error) => {
        console.error("Session check failed:", error);
        clearStoredAuth();
        setAuth(null);
      })
      .finally(() => setAuthChecked(true));
  }, []);

  function handleLogin(session) {
    writeStoredAuth(session);
    setAuth(session);
  }

  function handleLogout() {
    apiFetch("/api/logout", { method: "POST" }).catch((error) => {
      console.error("Logout failed:", error);
    });
    clearStoredAuth();
    setAuth(null);
  }

  if (!authChecked) {
    return <div className="auth-page" aria-label="Loading" />;
  }

  const accountEmail = auth?.email || "";
  const accountRole = auth?.role || "viewer";

  return (
    <Routes>
      <Route
        path="/login"
        element={auth ? <Navigate to="/dashboard" replace /> : <Login onLogin={handleLogin} />}
      />
      <Route path="/create-account" element={<Navigate to="/login" replace />} />

      <Route
        element={
          auth
            ? (
                <AppLayout
                  accountEmail={accountEmail}
                  accountRole={accountRole}
                  onLogout={handleLogout}
                />
              )
            : <Navigate to="/login" replace />
        }
      >
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={<Dashboard accountEmail={accountEmail} />} />
        <Route path="/strategy" element={<Strategy />} />
        <Route path="/backtest" element={<Backtest accountEmail={accountEmail} />} />
        <Route path="/backtest-history" element={<BacktestHistory />} />
        <Route path="/futures-strategy" element={<FuturesStrategy />} />
        <Route path="/futures-backtest" element={<FuturesBacktest />} />
        <Route path="/futures-backtest-history" element={<FuturesBacktestHistory />} />
        <Route path="/futures-live" element={<FuturesLive />} />
        <Route path="/documents" element={<Documents />} />
        <Route path="/settings" element={<Settings accountEmail={accountEmail} />} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}
