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
import Settings from "./pages/Settings.jsx";
import NotFound from "./pages/NotFound.jsx";

const PRIMARY_ACCOUNT_EMAIL = import.meta.env.VITE_PRIMARY_ACCOUNT_EMAIL || "patelanish203@gmail.com";
const PRIMARY_ACCOUNT_ROLE = import.meta.env.VITE_PRIMARY_ACCOUNT_ROLE || "admin";

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Navigate to="/dashboard" replace />} />
      <Route path="/create-account" element={<Navigate to="/dashboard" replace />} />

      <Route
        element={
          <AppLayout
            accountEmail={PRIMARY_ACCOUNT_EMAIL}
            accountRole={PRIMARY_ACCOUNT_ROLE}
          />
        }
      >
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={<Dashboard accountEmail={PRIMARY_ACCOUNT_EMAIL} />} />
        <Route path="/strategy" element={<Strategy />} />
        <Route path="/backtest" element={<Backtest accountEmail={PRIMARY_ACCOUNT_EMAIL} />} />
        <Route path="/backtest-history" element={<BacktestHistory />} />
        <Route path="/futures-strategy" element={<FuturesStrategy />} />
        <Route path="/futures-backtest" element={<FuturesBacktest />} />
        <Route path="/futures-backtest-history" element={<FuturesBacktestHistory />} />
        <Route path="/futures-live" element={<FuturesLive />} />
        <Route path="/settings" element={<Settings accountEmail={PRIMARY_ACCOUNT_EMAIL} />} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}
