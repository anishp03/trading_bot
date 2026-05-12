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
import { apiFetch, clearStoredAuth, isApiNetworkError, readStoredAuth, writeStoredAuth } from "./utils/api.js";

const PRIMARY_ACCOUNT_EMAIL = import.meta.env.VITE_PRIMARY_ACCOUNT_EMAIL || "local@example.invalid";
const ALLOW_OFFLINE_AUTH = import.meta.env.DEV && import.meta.env.VITE_ALLOW_OFFLINE_AUTH === "true";

function anonymousAuthState() {
  return {
    status: "anonymous",
    token: "",
    email: PRIMARY_ACCOUNT_EMAIL,
    role: "viewer",
    expiresAt: "",
    offline: false,
  };
}

function offlineAuthState() {
  return {
    status: "offline",
    token: "",
    email: "backend offline",
    role: "offline",
    expiresAt: "",
    offline: true,
  };
}

function ProtectedLayout({ auth, onLogout }) {
  const location = useLocation();

  if (auth.status === "loading" || auth.status === "checking") {
    return (
      <div className="auth-page">
        <div className="app-panel auth-panel">
          <h2 className="app-title text-center">Trading Bot</h2>
          <div className="app-muted text-center">Checking session...</div>
        </div>
      </div>
    );
  }

  if (!auth.token && !auth.offline) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  return (
    <AppLayout
      accountEmail={auth.email}
      accountRole={auth.role}
      backendMode={auth.offline ? "offline" : "online"}
      onLogout={auth.offline ? null : onLogout}
    />
  );
}

export default function App() {
  const [auth, setAuth] = useState(() => {
    const stored = readStoredAuth();
    return {
      status: stored?.token ? "loading" : "checking",
      token: stored?.token || "",
      email: stored?.email || PRIMARY_ACCOUNT_EMAIL,
      role: stored?.role || "viewer",
      expiresAt: stored?.expiresAt || "",
      offline: false,
    };
  });

  useEffect(() => {
    let isMounted = true;
    const stored = readStoredAuth();

    if (!stored?.token) {
      const controller = new AbortController();
      const timeoutId = window.setTimeout(() => controller.abort(), 1800);
      apiFetch("/api/system/health", { signal: controller.signal })
        .then(() => {
          if (!isMounted) return;
          setAuth(anonymousAuthState());
        })
        .catch((error) => {
          if (!isMounted) return;
          setAuth(isApiNetworkError(error) && ALLOW_OFFLINE_AUTH ? offlineAuthState() : anonymousAuthState());
        })
        .finally(() => window.clearTimeout(timeoutId));
      return () => {
        isMounted = false;
        window.clearTimeout(timeoutId);
        controller.abort();
      };
    }

    apiFetch("/api/session")
      .then(async (response) => {
        if (!isMounted) {
          return;
        }

        if (!response.ok) {
          clearStoredAuth();
          setAuth(anonymousAuthState());
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
        setAuth({ ...nextAuth, status: "authenticated", offline: false });
      })
      .catch((error) => {
        console.error(error);
        if (isMounted) {
          if (isApiNetworkError(error) && ALLOW_OFFLINE_AUTH) {
            setAuth(offlineAuthState());
            return;
          }

          clearStoredAuth();
          setAuth(anonymousAuthState());
        }
      });

    return () => {
      isMounted = false;
    };
  }, []);

  useEffect(() => {
    if (!auth.offline) {
      return undefined;
    }

    let isMounted = true;
    const checkBackend = () => {
      apiFetch("/api/system/health")
        .then((response) => {
          if (!isMounted || !response.ok) return;
          setAuth(anonymousAuthState());
        })
        .catch(() => {});
    };

    const intervalId = window.setInterval(checkBackend, 30000);
    return () => {
      isMounted = false;
      window.clearInterval(intervalId);
    };
  }, [auth.offline]);

  const isAuthenticated = Boolean(auth.token) || Boolean(auth.offline);
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
        setAuth({ ...nextAuth, status: "authenticated", offline: false });
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
      offline: false,
    });
  }

  return (
    <Routes>
      <Route
        path="/login"
        element={
          loginAuth.isAuthenticated ? (
            <Navigate to={auth.offline ? "/futures-live" : "/dashboard"} replace />
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
