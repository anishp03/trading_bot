import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "../utils/api.js";

const INITIAL_STATE = {
  orb: {
    enabled: true,
    trendTimeframe: "30Min",
    orbWindowMinutes: "15",
    maxTradesPerDay: "2",
    breakoutBufferPct: "0.01",
    reclaimWindowBars: "3",
    entryBufferPct: "0",
    riskPerTradePct: "0.5",
    rewardToRiskRatio: "0.75",
    stopBufferPct: "0.03",
    requireTrendAlignment: true,
  },
  ifvg: {
    enabled: true,
    trendTimeframe: "30Min",
    signalTimeframe: "5Min",
    maxTradesPerDay: "5",
    minimumGapPct: "0.05",
    reclaimWindowBars: "8",
    riskPerTradePct: "0.5",
    rewardToRiskRatio: "0.75",
    entryBufferPct: "0",
    stopBufferPct: "0.03",
    requireTrendAlignment: false,
  },
  vwapPullback: {
    enabled: true,
    trendTimeframe: "30Min",
    maxTradesPerDay: "5",
    minimumGapPct: "0.08",
    reclaimWindowBars: "3",
    riskPerTradePct: "0.5",
    rewardToRiskRatio: "0.75",
    entryBufferPct: "0.01",
    stopBufferPct: "0.03",
    requireTrendAlignment: true,
  },
  vwapMeanReversion: {
    enabled: true,
    trendTimeframe: "30Min",
    maxTradesPerDay: "2",
    minimumGapPct: "1.5",
    reclaimWindowBars: "5",
    riskPerTradePct: "0.5",
    rewardToRiskRatio: "2",
    entryBufferPct: "0",
    stopBufferPct: "0.03",
    requireTrendAlignment: true,
  },
  gapGo: {
    enabled: true,
    trendTimeframe: "30Min",
    orbWindowMinutes: "5",
    maxTradesPerDay: "1",
    breakoutBufferPct: "0",
    minimumGapPct: "0.5",
    riskPerTradePct: "0.5",
    rewardToRiskRatio: "0.75",
    stopBufferPct: "0.03",
    requireTrendAlignment: true,
  },
  enabledStrategies: [],
};

export default function Strategy() {
  const [form, setForm] = useState(INITIAL_STATE);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [saveStatus, setSaveStatus] = useState("");

  const loadSettings = useCallback(() => {
    apiFetch("/api/strategy")
      .then((response) => {
        if (!response.ok) {
          throw new Error("Failed to load strategy settings.");
        }

        return response.json();
      })
      .then((data) => {
        setForm(toFormState(data));
      })
      .catch((error) => {
        console.error("Error loading strategy settings:", error);
        setForm(INITIAL_STATE);
      })
      .finally(() => {
        setIsLoading(false);
      });
  }, []);

  useEffect(() => {
    loadSettings();
  }, [loadSettings]);

  function handleChange(section, field, value) {
    setForm((currentForm) => ({
      ...currentForm,
      [section]: {
        ...currentForm[section],
        [field]: value,
      },
    }));
  }

  function saveSettings() {
    setIsSaving(true);
    setSaveStatus("");

    const params = new URLSearchParams({
      orbEnabled: String(form.orb.enabled),
      orbTrendTimeframe: form.orb.trendTimeframe,
      orbWindowMinutes: form.orb.orbWindowMinutes,
      orbMaxTradesPerDay: form.orb.maxTradesPerDay,
      orbBreakoutBufferPct: form.orb.breakoutBufferPct,
      orbReclaimWindowBars: form.orb.reclaimWindowBars,
      orbEntryBufferPct: form.orb.entryBufferPct,
      orbRiskPerTradePct: form.orb.riskPerTradePct,
      orbRewardToRiskRatio: form.orb.rewardToRiskRatio,
      orbStopBufferPct: form.orb.stopBufferPct,
      orbRequireTrendAlignment: String(form.orb.requireTrendAlignment),
      ifvgEnabled: String(form.ifvg.enabled),
      ifvgTrendTimeframe: form.ifvg.trendTimeframe,
      ifvgSignalTimeframe: form.ifvg.signalTimeframe,
      ifvgMaxTradesPerDay: form.ifvg.maxTradesPerDay,
      ifvgMinimumGapPct: form.ifvg.minimumGapPct,
      ifvgReclaimWindowBars: form.ifvg.reclaimWindowBars,
      ifvgRiskPerTradePct: form.ifvg.riskPerTradePct,
      ifvgRewardToRiskRatio: form.ifvg.rewardToRiskRatio,
      ifvgEntryBufferPct: form.ifvg.entryBufferPct,
      ifvgStopBufferPct: form.ifvg.stopBufferPct,
      ifvgRequireTrendAlignment: String(form.ifvg.requireTrendAlignment),
      vwapEnabled: String(form.vwapPullback.enabled),
      vwapTrendTimeframe: form.vwapPullback.trendTimeframe,
      vwapMaxTradesPerDay: form.vwapPullback.maxTradesPerDay,
      vwapMinimumGapPct: form.vwapPullback.minimumGapPct,
      vwapReclaimWindowBars: form.vwapPullback.reclaimWindowBars,
      vwapRiskPerTradePct: form.vwapPullback.riskPerTradePct,
      vwapRewardToRiskRatio: form.vwapPullback.rewardToRiskRatio,
      vwapEntryBufferPct: form.vwapPullback.entryBufferPct,
      vwapStopBufferPct: form.vwapPullback.stopBufferPct,
      vwapRequireTrendAlignment: String(form.vwapPullback.requireTrendAlignment),
      vwapMeanReversionEnabled: String(form.vwapMeanReversion.enabled),
      vwapMeanReversionTrendTimeframe: form.vwapMeanReversion.trendTimeframe,
      vwapMeanReversionMaxTradesPerDay: form.vwapMeanReversion.maxTradesPerDay,
      vwapMeanReversionMinimumGapPct: form.vwapMeanReversion.minimumGapPct,
      vwapMeanReversionReclaimWindowBars: form.vwapMeanReversion.reclaimWindowBars,
      vwapMeanReversionRiskPerTradePct: form.vwapMeanReversion.riskPerTradePct,
      vwapMeanReversionRewardToRiskRatio: form.vwapMeanReversion.rewardToRiskRatio,
      vwapMeanReversionEntryBufferPct: form.vwapMeanReversion.entryBufferPct,
      vwapMeanReversionStopBufferPct: form.vwapMeanReversion.stopBufferPct,
      vwapMeanReversionRequireTrendAlignment: String(form.vwapMeanReversion.requireTrendAlignment),
      gapGoEnabled: String(form.gapGo.enabled),
      gapGoTrendTimeframe: form.gapGo.trendTimeframe,
      gapGoOrbWindowMinutes: form.gapGo.orbWindowMinutes,
      gapGoMaxTradesPerDay: form.gapGo.maxTradesPerDay,
      gapGoBreakoutBufferPct: form.gapGo.breakoutBufferPct,
      gapGoMinimumGapPct: form.gapGo.minimumGapPct,
      gapGoRiskPerTradePct: form.gapGo.riskPerTradePct,
      gapGoRewardToRiskRatio: form.gapGo.rewardToRiskRatio,
      gapGoStopBufferPct: form.gapGo.stopBufferPct,
      gapGoRequireTrendAlignment: String(form.gapGo.requireTrendAlignment),
    });

    apiFetch(`/api/strategy?${params.toString()}`, {
      method: "POST",
    })
      .then((response) => {
        if (!response.ok) {
          throw new Error("Failed to save strategy settings.");
        }

        return response.json();
      })
      .then((data) => {
        setForm(toFormState(data));
        setSaveStatus("Saved");
      })
      .catch((error) => {
        console.error("Error saving strategy settings:", error);
        setSaveStatus("Save failed");
      })
      .finally(() => {
        setIsSaving(false);
      });
  }

  const enabledStrategies = Array.isArray(form.enabledStrategies) ? form.enabledStrategies : [];

  return (
    <div className="app-page">
      <h2 className="app-title">Stock Strategy</h2>

      <div className="app-panel">
        <div className="d-flex justify-content-between align-items-start gap-2 flex-wrap">
          <div className="fw-bold app-kicker">Stock Strategy Configuration</div>

          <div className="d-flex gap-2 flex-wrap justify-content-end">
            {enabledStrategies.length === 0 ? (
              <span className="app-badge">No Strategies Enabled</span>
            ) : (
              enabledStrategies.map((strategyName) => (
                <span key={strategyName} className="app-badge">
                  {strategyName}
                </span>
              ))
            )}
          </div>
        </div>
      </div>

      <div className="row g-3">
        <div className="col-12 col-xl-6">
          <div className="app-panel h-100">
            <div className="fw-bold app-kicker">9:30 AM ORB</div>

            <div className="row g-3 mt-1">
              <Field label="Enabled">
                <select
                  value={String(form.orb.enabled)}
                  onChange={(event) => handleChange("orb", "enabled", event.target.value === "true")}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="true">Enabled</option>
                  <option value="false">Disabled</option>
                </select>
              </Field>

              <Field label="Trend Timeframe">
                <select
                  value={form.orb.trendTimeframe}
                  onChange={(event) => handleChange("orb", "trendTimeframe", event.target.value)}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="30Min">30 Min</option>
                  <option value="1Hour">1 Hour</option>
                </select>
              </Field>

              <Field label="Opening Range (Minutes)">
                <input
                  type="number"
                  value={form.orb.orbWindowMinutes}
                  onChange={(event) => handleChange("orb", "orbWindowMinutes", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Max Trades / Day">
                <input
                  type="number"
                  value={form.orb.maxTradesPerDay}
                  onChange={(event) => handleChange("orb", "maxTradesPerDay", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Breakout Buffer (%)">
                <input
                  type="number"
                  step="0.01"
                  value={form.orb.breakoutBufferPct}
                  onChange={(event) => handleChange("orb", "breakoutBufferPct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Retest Window (Bars)">
                <input
                  type="number"
                  value={form.orb.reclaimWindowBars}
                  onChange={(event) => handleChange("orb", "reclaimWindowBars", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Reclaim Buffer (%)">
                <input
                  type="number"
                  step="0.01"
                  value={form.orb.entryBufferPct}
                  onChange={(event) => handleChange("orb", "entryBufferPct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Risk Per Trade (%)">
                <input
                  type="number"
                  step="0.1"
                  value={form.orb.riskPerTradePct}
                  onChange={(event) => handleChange("orb", "riskPerTradePct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Reward / Risk Ratio">
                <input
                  type="number"
                  step="0.25"
                  value={form.orb.rewardToRiskRatio}
                  onChange={(event) => handleChange("orb", "rewardToRiskRatio", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Stop Buffer (%)">
                <input
                  type="number"
                  step="0.01"
                  value={form.orb.stopBufferPct}
                  onChange={(event) => handleChange("orb", "stopBufferPct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Trend Alignment">
                <select
                  value={String(form.orb.requireTrendAlignment)}
                  onChange={(event) => handleChange("orb", "requireTrendAlignment", event.target.value === "true")}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="true">Required</option>
                  <option value="false">Optional</option>
                </select>
              </Field>
            </div>
          </div>
        </div>

        <div className="col-12 col-xl-6">
          <div className="app-panel h-100">
            <div className="fw-bold app-kicker">IFVG</div>

            <div className="row g-3 mt-1">
              <Field label="Enabled">
                <select
                  value={String(form.ifvg.enabled)}
                  onChange={(event) => handleChange("ifvg", "enabled", event.target.value === "true")}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="true">Enabled</option>
                  <option value="false">Disabled</option>
                </select>
              </Field>

              <Field label="Trend Timeframe">
                <select
                  value={form.ifvg.trendTimeframe}
                  onChange={(event) => handleChange("ifvg", "trendTimeframe", event.target.value)}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="30Min">30 Min</option>
                  <option value="1Hour">1 Hour</option>
                </select>
              </Field>

              <Field label="Gap Timeframe">
                <select
                  value={form.ifvg.signalTimeframe}
                  onChange={(event) => handleChange("ifvg", "signalTimeframe", event.target.value)}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="1Min">1 Min</option>
                  <option value="5Min">5 Min</option>
                  <option value="30Min">30 Min</option>
                </select>
              </Field>

              <Field label="Max Trades / Day">
                <input
                  type="number"
                  value={form.ifvg.maxTradesPerDay}
                  onChange={(event) => handleChange("ifvg", "maxTradesPerDay", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Minimum Gap (%)">
                <input
                  type="number"
                  step="0.005"
                  value={form.ifvg.minimumGapPct}
                  onChange={(event) => handleChange("ifvg", "minimumGapPct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Flip Window (Bars)">
                <input
                  type="number"
                  value={form.ifvg.reclaimWindowBars}
                  onChange={(event) => handleChange("ifvg", "reclaimWindowBars", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Risk Per Trade (%)">
                <input
                  type="number"
                  step="0.1"
                  value={form.ifvg.riskPerTradePct}
                  onChange={(event) => handleChange("ifvg", "riskPerTradePct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Reward / Risk Ratio">
                <input
                  type="number"
                  step="0.25"
                  value={form.ifvg.rewardToRiskRatio}
                  onChange={(event) => handleChange("ifvg", "rewardToRiskRatio", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Entry Buffer (%)">
                <input
                  type="number"
                  step="0.01"
                  value={form.ifvg.entryBufferPct}
                  onChange={(event) => handleChange("ifvg", "entryBufferPct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Stop Buffer (%)">
                <input
                  type="number"
                  step="0.01"
                  value={form.ifvg.stopBufferPct}
                  onChange={(event) => handleChange("ifvg", "stopBufferPct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Trend Alignment">
                <select
                  value={String(form.ifvg.requireTrendAlignment)}
                  onChange={(event) => handleChange("ifvg", "requireTrendAlignment", event.target.value === "true")}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="true">Required</option>
                  <option value="false">Optional</option>
                </select>
              </Field>
            </div>
          </div>
        </div>

        <div className="col-12 col-xl-6">
          <div className="app-panel h-100">
            <div className="fw-bold app-kicker">VWAP Trend Pullback</div>

            <div className="row g-3 mt-1">
              <Field label="Enabled">
                <select
                  value={String(form.vwapPullback.enabled)}
                  onChange={(event) => handleChange("vwapPullback", "enabled", event.target.value === "true")}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="true">Enabled</option>
                  <option value="false">Disabled</option>
                </select>
              </Field>

              <Field label="Trend Timeframe">
                <select
                  value={form.vwapPullback.trendTimeframe}
                  onChange={(event) => handleChange("vwapPullback", "trendTimeframe", event.target.value)}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="30Min">30 Min</option>
                  <option value="1Hour">1 Hour</option>
                </select>
              </Field>

              <Field label="Max Trades / Day">
                <input
                  type="number"
                  value={form.vwapPullback.maxTradesPerDay}
                  onChange={(event) => handleChange("vwapPullback", "maxTradesPerDay", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Min VWAP Distance (%)">
                <input
                  type="number"
                  step="0.01"
                  value={form.vwapPullback.minimumGapPct}
                  onChange={(event) => handleChange("vwapPullback", "minimumGapPct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Pullback Window (Bars)">
                <input
                  type="number"
                  value={form.vwapPullback.reclaimWindowBars}
                  onChange={(event) => handleChange("vwapPullback", "reclaimWindowBars", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Risk Per Trade (%)">
                <input
                  type="number"
                  step="0.1"
                  value={form.vwapPullback.riskPerTradePct}
                  onChange={(event) => handleChange("vwapPullback", "riskPerTradePct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Reward / Risk Ratio">
                <input
                  type="number"
                  step="0.25"
                  value={form.vwapPullback.rewardToRiskRatio}
                  onChange={(event) => handleChange("vwapPullback", "rewardToRiskRatio", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Entry Buffer (%)">
                <input
                  type="number"
                  step="0.01"
                  value={form.vwapPullback.entryBufferPct}
                  onChange={(event) => handleChange("vwapPullback", "entryBufferPct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Stop Buffer (%)">
                <input
                  type="number"
                  step="0.01"
                  value={form.vwapPullback.stopBufferPct}
                  onChange={(event) => handleChange("vwapPullback", "stopBufferPct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Trend Alignment">
                <select
                  value={String(form.vwapPullback.requireTrendAlignment)}
                  onChange={(event) => handleChange("vwapPullback", "requireTrendAlignment", event.target.value === "true")}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="true">Required</option>
                  <option value="false">Optional</option>
                </select>
              </Field>
            </div>
          </div>
        </div>

        <div className="col-12 col-xl-6">
          <div className="app-panel h-100">
            <div className="fw-bold app-kicker">Gap and Go</div>

            <div className="row g-3 mt-1">
              <Field label="Enabled">
                <select
                  value={String(form.gapGo.enabled)}
                  onChange={(event) => handleChange("gapGo", "enabled", event.target.value === "true")}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="true">Enabled</option>
                  <option value="false">Disabled</option>
                </select>
              </Field>

              <Field label="Trend Timeframe">
                <select
                  value={form.gapGo.trendTimeframe}
                  onChange={(event) => handleChange("gapGo", "trendTimeframe", event.target.value)}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="30Min">30 Min</option>
                  <option value="1Hour">1 Hour</option>
                </select>
              </Field>

              <Field label="Opening Range (Minutes)">
                <input
                  type="number"
                  value={form.gapGo.orbWindowMinutes}
                  onChange={(event) => handleChange("gapGo", "orbWindowMinutes", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Max Trades / Day">
                <input
                  type="number"
                  value={form.gapGo.maxTradesPerDay}
                  onChange={(event) => handleChange("gapGo", "maxTradesPerDay", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Min Gap (%)">
                <input
                  type="number"
                  step="0.1"
                  value={form.gapGo.minimumGapPct}
                  onChange={(event) => handleChange("gapGo", "minimumGapPct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Breakout Buffer (%)">
                <input
                  type="number"
                  step="0.01"
                  value={form.gapGo.breakoutBufferPct}
                  onChange={(event) => handleChange("gapGo", "breakoutBufferPct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Risk Per Trade (%)">
                <input
                  type="number"
                  step="0.1"
                  value={form.gapGo.riskPerTradePct}
                  onChange={(event) => handleChange("gapGo", "riskPerTradePct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Reward / Risk Ratio">
                <input
                  type="number"
                  step="0.25"
                  value={form.gapGo.rewardToRiskRatio}
                  onChange={(event) => handleChange("gapGo", "rewardToRiskRatio", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Stop Buffer (%)">
                <input
                  type="number"
                  step="0.01"
                  value={form.gapGo.stopBufferPct}
                  onChange={(event) => handleChange("gapGo", "stopBufferPct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Trend Alignment">
                <select
                  value={String(form.gapGo.requireTrendAlignment)}
                  onChange={(event) => handleChange("gapGo", "requireTrendAlignment", event.target.value === "true")}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="true">Required</option>
                  <option value="false">Optional</option>
                </select>
              </Field>
            </div>
          </div>
        </div>

        <div className="col-12 col-xl-6">
          <div className="app-panel h-100">
            <div className="fw-bold app-kicker">VWAP RSI Mean Reversion</div>

            <div className="row g-3 mt-1">
              <Field label="Enabled">
                <select
                  value={String(form.vwapMeanReversion.enabled)}
                  onChange={(event) => handleChange("vwapMeanReversion", "enabled", event.target.value === "true")}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="true">Enabled</option>
                  <option value="false">Disabled</option>
                </select>
              </Field>

              <Field label="Trend Timeframe">
                <select
                  value={form.vwapMeanReversion.trendTimeframe}
                  onChange={(event) => handleChange("vwapMeanReversion", "trendTimeframe", event.target.value)}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="30Min">30 Min</option>
                  <option value="1Hour">1 Hour</option>
                </select>
              </Field>

              <Field label="Max Trades / Day">
                <input
                  type="number"
                  value={form.vwapMeanReversion.maxTradesPerDay}
                  onChange={(event) => handleChange("vwapMeanReversion", "maxTradesPerDay", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Min VWAP Extension (%)">
                <input
                  type="number"
                  step="0.01"
                  value={form.vwapMeanReversion.minimumGapPct}
                  onChange={(event) => handleChange("vwapMeanReversion", "minimumGapPct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Exhaustion Window (Bars)">
                <input
                  type="number"
                  value={form.vwapMeanReversion.reclaimWindowBars}
                  onChange={(event) => handleChange("vwapMeanReversion", "reclaimWindowBars", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Risk Per Trade (%)">
                <input
                  type="number"
                  step="0.1"
                  value={form.vwapMeanReversion.riskPerTradePct}
                  onChange={(event) => handleChange("vwapMeanReversion", "riskPerTradePct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Reward / Risk Cap">
                <input
                  type="number"
                  step="0.25"
                  value={form.vwapMeanReversion.rewardToRiskRatio}
                  onChange={(event) => handleChange("vwapMeanReversion", "rewardToRiskRatio", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Entry Buffer (%)">
                <input
                  type="number"
                  step="0.01"
                  value={form.vwapMeanReversion.entryBufferPct}
                  onChange={(event) => handleChange("vwapMeanReversion", "entryBufferPct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Stop Buffer (%)">
                <input
                  type="number"
                  step="0.01"
                  value={form.vwapMeanReversion.stopBufferPct}
                  onChange={(event) => handleChange("vwapMeanReversion", "stopBufferPct", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading}
                />
              </Field>

              <Field label="Trend Alignment">
                <select
                  value={String(form.vwapMeanReversion.requireTrendAlignment)}
                  onChange={(event) => handleChange("vwapMeanReversion", "requireTrendAlignment", event.target.value === "true")}
                  className="form-select app-input"
                  disabled={isLoading}
                >
                  <option value="true">Required</option>
                  <option value="false">Optional</option>
                </select>
              </Field>
            </div>
          </div>
        </div>
      </div>

      <div className="app-panel">
        <div className="d-flex justify-content-between align-items-end gap-3 flex-wrap">
          <div className="app-kicker" style={{ minHeight: "24px" }}>
            {saveStatus ? (
              <span className={saveStatus === "Saved" ? "app-badge" : "app-side-pill short"}>{saveStatus}</span>
            ) : null}
          </div>

          <div className="d-flex align-items-center">
            <button
              type="button"
              className="app-btn app-btn-primary px-4"
              onClick={saveSettings}
              disabled={isLoading || isSaving}
            >
              {isSaving ? "Saving..." : "Save"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function toFormState(data) {
  return {
    orb: {
      enabled: Boolean(data?.orb?.enabled),
      trendTimeframe: data?.orb?.trendTimeframe || "30Min",
      orbWindowMinutes: String(data?.orb?.orbWindowMinutes ?? "15"),
      maxTradesPerDay: String(data?.orb?.maxTradesPerDay ?? "2"),
      breakoutBufferPct: String(data?.orb?.breakoutBufferPct ?? "0.01"),
      reclaimWindowBars: String(data?.orb?.reclaimWindowBars ?? "3"),
      entryBufferPct: String(data?.orb?.entryBufferPct ?? "0"),
      riskPerTradePct: String(data?.orb?.riskPerTradePct ?? "0.5"),
      rewardToRiskRatio: String(data?.orb?.rewardToRiskRatio ?? "0.75"),
      stopBufferPct: String(data?.orb?.stopBufferPct ?? "0.03"),
      requireTrendAlignment: Boolean(data?.orb?.requireTrendAlignment ?? true),
    },
    ifvg: {
      enabled: Boolean(data?.ifvg?.enabled),
      trendTimeframe: data?.ifvg?.trendTimeframe || "30Min",
      signalTimeframe: data?.ifvg?.signalTimeframe || "5Min",
      maxTradesPerDay: String(data?.ifvg?.maxTradesPerDay ?? "5"),
      minimumGapPct: String(data?.ifvg?.minimumGapPct ?? "0.05"),
      reclaimWindowBars: String(data?.ifvg?.reclaimWindowBars ?? "8"),
      riskPerTradePct: String(data?.ifvg?.riskPerTradePct ?? "0.5"),
      rewardToRiskRatio: String(data?.ifvg?.rewardToRiskRatio ?? "0.75"),
      entryBufferPct: String(data?.ifvg?.entryBufferPct ?? "0"),
      stopBufferPct: String(data?.ifvg?.stopBufferPct ?? "0.03"),
      requireTrendAlignment: Boolean(data?.ifvg?.requireTrendAlignment ?? false),
    },
    vwapPullback: {
      enabled: Boolean(data?.vwapPullback?.enabled),
      trendTimeframe: data?.vwapPullback?.trendTimeframe || "30Min",
      maxTradesPerDay: String(data?.vwapPullback?.maxTradesPerDay ?? "5"),
      minimumGapPct: String(data?.vwapPullback?.minimumGapPct ?? "0.08"),
      reclaimWindowBars: String(data?.vwapPullback?.reclaimWindowBars ?? "3"),
      riskPerTradePct: String(data?.vwapPullback?.riskPerTradePct ?? "0.5"),
      rewardToRiskRatio: String(data?.vwapPullback?.rewardToRiskRatio ?? "0.75"),
      entryBufferPct: String(data?.vwapPullback?.entryBufferPct ?? "0.01"),
      stopBufferPct: String(data?.vwapPullback?.stopBufferPct ?? "0.03"),
      requireTrendAlignment: Boolean(data?.vwapPullback?.requireTrendAlignment ?? true),
    },
    vwapMeanReversion: {
      enabled: Boolean(data?.vwapMeanReversion?.enabled),
      trendTimeframe: data?.vwapMeanReversion?.trendTimeframe || "30Min",
      maxTradesPerDay: String(data?.vwapMeanReversion?.maxTradesPerDay ?? "2"),
      minimumGapPct: String(data?.vwapMeanReversion?.minimumGapPct ?? "1.5"),
      reclaimWindowBars: String(data?.vwapMeanReversion?.reclaimWindowBars ?? "5"),
      riskPerTradePct: String(data?.vwapMeanReversion?.riskPerTradePct ?? "0.5"),
      rewardToRiskRatio: String(data?.vwapMeanReversion?.rewardToRiskRatio ?? "2"),
      entryBufferPct: String(data?.vwapMeanReversion?.entryBufferPct ?? "0"),
      stopBufferPct: String(data?.vwapMeanReversion?.stopBufferPct ?? "0.03"),
      requireTrendAlignment: Boolean(data?.vwapMeanReversion?.requireTrendAlignment ?? true),
    },
    gapGo: {
      enabled: Boolean(data?.gapGo?.enabled),
      trendTimeframe: data?.gapGo?.trendTimeframe || "30Min",
      orbWindowMinutes: String(data?.gapGo?.orbWindowMinutes ?? "5"),
      maxTradesPerDay: String(data?.gapGo?.maxTradesPerDay ?? "1"),
      breakoutBufferPct: String(data?.gapGo?.breakoutBufferPct ?? "0"),
      minimumGapPct: String(data?.gapGo?.minimumGapPct ?? "0.5"),
      riskPerTradePct: String(data?.gapGo?.riskPerTradePct ?? "0.5"),
      rewardToRiskRatio: String(data?.gapGo?.rewardToRiskRatio ?? "0.75"),
      stopBufferPct: String(data?.gapGo?.stopBufferPct ?? "0.03"),
      requireTrendAlignment: Boolean(data?.gapGo?.requireTrendAlignment ?? true),
    },
    enabledStrategies: Array.isArray(data?.enabledStrategies) ? data.enabledStrategies : [],
  };
}

function Field({ label, children }) {
  return (
    <div className="col-12 col-md-6">
      <label className="d-grid gap-1">
        <span className="app-label">{label}</span>
        {children}
      </label>
    </div>
  );
}
