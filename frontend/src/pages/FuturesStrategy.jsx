import { useEffect, useState } from "react";
import { apiFetch } from "../utils/api.js";

const DEFAULT_SETTINGS = {
  orb: { enabled: true, maxTradesPerDay: 1 },
  openingMomentum: { enabled: false, maxTradesPerDay: 2 },
  sweep: { enabled: true, maxTradesPerDay: 3 },
  vwapPullback: { enabled: false, maxTradesPerDay: 1 },
  vwapMeanReversion: { enabled: false, maxTradesPerDay: 1 },
  fvg: { enabled: false, maxTradesPerDay: 1 },
  closeMomentum: { enabled: false, maxTradesPerDay: 1 },
  afternoonContinuation: { enabled: false, maxTradesPerDay: 2 },
  marketIntradayMomentum: { enabled: false, maxTradesPerDay: 1 },
  keltnerScalp: { enabled: false, maxTradesPerDay: 8 },
  keltnerReversion: { enabled: false, maxTradesPerDay: 6 },
  microScalp: { enabled: false, maxTradesPerDay: 6 },
  enableEarlySweep: true,
  enableLateSweep: true,
  enableSweepSecondChance: true,
  enableOrbRetest: false,
  enableCompressedOrbBreakout: false,
  skipMidmorningOrbRetest: false,
  requireHigherTimeframeGuard: true,
  allowShorts: true,
  openingMomentumRangeMinutes: 10,
  openingMomentumMaxHoldBars: 120,
  openingMomentumVolumeRatio: 0.5,
  openingMomentumRewardRisk: 0.8,
  earlySweepReclaimTicks: 6,
  lateSweepReclaimTicks: 8,
  sweepCloseLocation: 0.6,
  lateSweepCloseLocation: 0.45,
  minBodyPct: 28,
  vwapMinVolumeRatio: 1.05,
  vwapMinTrendSlopeTicks: 3,
  vwapMaxDistanceTicks: 36,
  meanReversionMinDistanceTicks: 36,
  meanReversionOversoldRsi: 30,
  meanReversionOverboughtRsi: 70,
  minRewardRisk: 1.15,
  closeMomentumMinMoveTicks: 24,
  closeMomentumVolumeRatio: 0.8,
  closeMomentumRewardRisk: 0.9,
  orbCompressedMaxRiskTicks: 60,
  afternoonMinVolumeRatio: 0.9,
  afternoonMaxRiskTicks: 48,
  afternoonRewardRisk: 1.0,
  keltnerAtrMultiplier: 1.3,
  keltnerMinVolumeRatio: 0.75,
  keltnerMaxRiskTicks: 22,
  keltnerRewardRisk: 0.85,
  keltnerMinBodyPct: 16,
  keltnerMinTrendSlopeTicks: 0.5,
  keltnerMinBandWidthTicks: 8,
  keltnerMaxHoldBars: 10,
  keltnerBucketMinutes: 12,
  maxInitialRiskTicks: 220,
};

const DEFAULT_STRATEGY_PRESET = "80kprofit";

const MODULES = [
  ["orb", "Opening Range Breakout"],
  ["openingMomentum", "Opening Momentum"],
  ["sweep", "Prior-Day Sweep"],
  ["vwapPullback", "VWAP Pullback"],
  ["vwapMeanReversion", "VWAP Mean Reversion"],
  ["fvg", "Fair Value Gap"],
  ["closeMomentum", "Close Momentum"],
  ["afternoonContinuation", "Afternoon Continuation"],
  ["marketIntradayMomentum", "Market Intraday Momentum"],
  ["keltnerScalp", "Keltner ATR Scalp"],
  ["keltnerReversion", "Keltner Reversion"],
  ["microScalp", "Micro Trend Scalp"],
];

export default function FuturesStrategy() {
  const [settings, setSettings] = useState(DEFAULT_SETTINGS);
  const [selectedSymbol, setSelectedSymbol] = useState("MNQ");
  const [selectedPreset, setSelectedPreset] = useState(DEFAULT_STRATEGY_PRESET);
  const [strategyPresets, setStrategyPresets] = useState([]);
  const [newPresetName, setNewPresetName] = useState("");
  const [instruments, setInstruments] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [saveStatus, setSaveStatus] = useState("");

  function loadInstruments() {
    apiFetch("/api/futures/instruments")
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures instruments.");
        return response.json();
      })
      .then((data) => {
        const nextInstruments = Array.isArray(data) ? data : [];
        setInstruments(nextInstruments);
        if (nextInstruments.length && !nextInstruments.some((instrument) => instrument.symbol === selectedSymbol)) {
          setSelectedSymbol(nextInstruments[0].symbol);
        }
      })
      .catch((error) => {
        console.error("Error loading futures instruments:", error);
        setInstruments([]);
      });
  }

  function loadStrategyPresets() {
    apiFetch("/api/futures/strategy-presets")
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load strategy presets.");
        return response.json();
      })
      .then((data) => {
        const presets = Array.isArray(data) ? data : [];
        setStrategyPresets(presets);
        if (presets.length && !presets.some((preset) => preset.name === selectedPreset)) {
          setSelectedPreset(presets[0].name);
        }
      })
      .catch((error) => {
        console.error("Error loading futures strategy presets:", error);
        setStrategyPresets([{ name: DEFAULT_STRATEGY_PRESET, label: DEFAULT_STRATEGY_PRESET }]);
      });
  }

  function loadSettings(symbol = selectedSymbol, preset = selectedPreset) {
    setIsLoading(true);
    const params = new URLSearchParams({ symbol, preset });
    apiFetch(`/api/futures/strategy?${params.toString()}`)
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load futures strategy settings.");
        return response.json();
      })
      .then((data) => setSettings(normalizeSettings(data)))
      .catch((error) => {
        console.error("Error loading futures strategy settings:", error);
        setSettings(DEFAULT_SETTINGS);
        setSaveStatus(`Loaded local defaults for ${symbol}`);
      })
      .finally(() => setIsLoading(false));
  }

  useEffect(() => {
    loadInstruments();
    loadStrategyPresets();
  }, []);

  useEffect(() => {
    loadSettings(selectedSymbol, selectedPreset);
  }, [selectedSymbol, selectedPreset]);

  function updateModule(moduleKey, field, value) {
    setSettings((current) => ({
      ...current,
      [moduleKey]: {
        ...current[moduleKey],
        [field]: value,
      },
    }));
  }

  function updateField(field, value) {
    setSettings((current) => ({ ...current, [field]: value }));
  }

  async function saveSettings() {
    setIsSaving(true);
    setSaveStatus("");

    const strategyParams = new URLSearchParams({
      symbol: selectedSymbol,
      preset: selectedPreset,
      orbEnabled: String(settings.orb.enabled),
      orbMaxTradesPerDay: String(settings.orb.maxTradesPerDay),
      openingMomentumEnabled: String(settings.openingMomentum.enabled),
      openingMomentumMaxTradesPerDay: String(settings.openingMomentum.maxTradesPerDay),
      sweepEnabled: String(settings.sweep.enabled),
      sweepMaxTradesPerDay: String(settings.sweep.maxTradesPerDay),
      vwapPullbackEnabled: String(settings.vwapPullback.enabled),
      vwapPullbackMaxTradesPerDay: String(settings.vwapPullback.maxTradesPerDay),
      vwapMeanReversionEnabled: String(settings.vwapMeanReversion.enabled),
      vwapMeanReversionMaxTradesPerDay: String(settings.vwapMeanReversion.maxTradesPerDay),
      fvgEnabled: String(settings.fvg.enabled),
      fvgMaxTradesPerDay: String(settings.fvg.maxTradesPerDay),
      closeMomentumEnabled: String(settings.closeMomentum.enabled),
      closeMomentumMaxTradesPerDay: String(settings.closeMomentum.maxTradesPerDay),
      afternoonContinuationEnabled: String(settings.afternoonContinuation.enabled),
      afternoonContinuationMaxTradesPerDay: String(settings.afternoonContinuation.maxTradesPerDay),
      marketIntradayMomentumEnabled: String(settings.marketIntradayMomentum.enabled),
      marketIntradayMomentumMaxTradesPerDay: String(settings.marketIntradayMomentum.maxTradesPerDay),
      keltnerScalpEnabled: String(settings.keltnerScalp.enabled),
      keltnerScalpMaxTradesPerDay: String(settings.keltnerScalp.maxTradesPerDay),
      keltnerReversionEnabled: String(settings.keltnerReversion.enabled),
      keltnerReversionMaxTradesPerDay: String(settings.keltnerReversion.maxTradesPerDay),
      microScalpEnabled: String(settings.microScalp.enabled),
      microScalpMaxTradesPerDay: String(settings.microScalp.maxTradesPerDay),
      enableEarlySweep: String(settings.enableEarlySweep),
      enableLateSweep: String(settings.enableLateSweep),
      enableSweepSecondChance: String(settings.enableSweepSecondChance),
      enableOrbRetest: String(settings.enableOrbRetest),
      enableCompressedOrbBreakout: String(settings.enableCompressedOrbBreakout),
      skipMidmorningOrbRetest: String(settings.skipMidmorningOrbRetest),
      requireHigherTimeframeGuard: String(settings.requireHigherTimeframeGuard),
      allowShorts: String(settings.allowShorts),
      openingMomentumRangeMinutes: String(settings.openingMomentumRangeMinutes),
      openingMomentumMaxHoldBars: String(settings.openingMomentumMaxHoldBars),
      openingMomentumVolumeRatio: String(settings.openingMomentumVolumeRatio),
      openingMomentumRewardRisk: String(settings.openingMomentumRewardRisk),
      earlySweepReclaimTicks: String(settings.earlySweepReclaimTicks),
      lateSweepReclaimTicks: String(settings.lateSweepReclaimTicks),
      sweepCloseLocation: String(settings.sweepCloseLocation),
      lateSweepCloseLocation: String(settings.lateSweepCloseLocation),
      minBodyPct: String(settings.minBodyPct),
      vwapMinVolumeRatio: String(settings.vwapMinVolumeRatio),
      vwapMinTrendSlopeTicks: String(settings.vwapMinTrendSlopeTicks),
      vwapMaxDistanceTicks: String(settings.vwapMaxDistanceTicks),
      meanReversionMinDistanceTicks: String(settings.meanReversionMinDistanceTicks),
      meanReversionOversoldRsi: String(settings.meanReversionOversoldRsi),
      meanReversionOverboughtRsi: String(settings.meanReversionOverboughtRsi),
      minRewardRisk: String(settings.minRewardRisk),
      closeMomentumMinMoveTicks: String(settings.closeMomentumMinMoveTicks),
      closeMomentumVolumeRatio: String(settings.closeMomentumVolumeRatio),
      closeMomentumRewardRisk: String(settings.closeMomentumRewardRisk),
      orbCompressedMaxRiskTicks: String(settings.orbCompressedMaxRiskTicks),
      afternoonMinVolumeRatio: String(settings.afternoonMinVolumeRatio),
      afternoonMaxRiskTicks: String(settings.afternoonMaxRiskTicks),
      afternoonRewardRisk: String(settings.afternoonRewardRisk),
      keltnerAtrMultiplier: String(settings.keltnerAtrMultiplier),
      keltnerMinVolumeRatio: String(settings.keltnerMinVolumeRatio),
      keltnerMaxRiskTicks: String(settings.keltnerMaxRiskTicks),
      keltnerRewardRisk: String(settings.keltnerRewardRisk),
      keltnerMinBodyPct: String(settings.keltnerMinBodyPct),
      keltnerMinTrendSlopeTicks: String(settings.keltnerMinTrendSlopeTicks),
      keltnerMinBandWidthTicks: String(settings.keltnerMinBandWidthTicks),
      keltnerMaxHoldBars: String(settings.keltnerMaxHoldBars),
      keltnerBucketMinutes: String(settings.keltnerBucketMinutes),
      maxInitialRiskTicks: String(settings.maxInitialRiskTicks),
    });

    try {
      const strategyResponse = await apiFetch(`/api/futures/strategy?${strategyParams.toString()}`, { method: "POST" });
      if (!strategyResponse.ok) throw new Error("Failed to save futures strategy settings.");
      const savedStrategy = await strategyResponse.json();

      setSettings(normalizeSettings(savedStrategy));
      setSaveStatus(`Saved ${selectedPreset} for ${savedStrategy.symbol || selectedSymbol}`);
    } catch (error) {
      console.error("Error saving futures strategy settings:", error);
      setSaveStatus(error.message || "Save failed");
    } finally {
      setIsSaving(false);
    }
  }

  async function createPreset() {
    const presetName = newPresetName.trim();
    if (!presetName) {
      setSaveStatus("Enter a preset name first.");
      return;
    }
    setIsSaving(true);
    setSaveStatus("");
    try {
      const params = new URLSearchParams({ preset: presetName, sourcePreset: selectedPreset });
      const response = await apiFetch(`/api/futures/strategy-presets?${params.toString()}`, { method: "POST" });
      const payload = await response.json();
      if (!response.ok || payload?.success === false) throw new Error(payload?.message || "Failed to create strategy preset.");
      setStrategyPresets(Array.isArray(payload.presets) ? payload.presets : strategyPresets);
      setSelectedPreset(payload.preset?.name || presetName);
      setNewPresetName("");
      setSaveStatus(payload.message || `Created ${presetName}.`);
    } catch (error) {
      console.error("Error creating strategy preset:", error);
      setSaveStatus(error.message || "Failed to create strategy preset.");
    } finally {
      setIsSaving(false);
    }
  }

  const selectedInstrument = instruments.find((instrument) => instrument.symbol === selectedSymbol) || null;
  const enabledCount = MODULES.filter(([key]) => settings[key]?.enabled).length;
  const presetOptions = strategyPresets.length ? strategyPresets : [{ name: DEFAULT_STRATEGY_PRESET, label: DEFAULT_STRATEGY_PRESET }];

  return (
    <div className="app-page futures-config-page">
      <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap mb-3">
        <h2 className="app-title m-0">Futures Strategy Configurations</h2>
        <div className="d-flex gap-2 flex-wrap">
          <button type="button" className="app-btn app-btn-primary px-3" onClick={saveSettings} disabled={isSaving || isLoading}>
            {isSaving ? "Saving..." : "Save Strategy Preset"}
          </button>
        </div>
      </div>

      <div className="app-panel">
        <div className="row g-3 align-items-end">
          <Field label="Strategy Config" className="col-12 col-md-4 col-xl-3">
            <select
              value={selectedPreset}
              onChange={(event) => {
                setSaveStatus("");
                setSelectedPreset(event.target.value);
              }}
              className="form-select app-input"
              disabled={isLoading}
            >
              {presetOptions.map((preset) => (
                <option key={preset.name} value={preset.name}>
                  {preset.label || preset.name}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Contract" className="col-12 col-md-4 col-xl-3">
            <select
              value={selectedSymbol}
              onChange={(event) => {
                setSaveStatus("");
                setSelectedSymbol(event.target.value);
              }}
              className="form-select app-input"
              disabled={isLoading}
            >
              {(instruments.length ? instruments : [{ symbol: "MNQ", name: "Micro E-mini Nasdaq-100" }]).map((instrument) => (
                <option key={instrument.symbol} value={instrument.symbol}>
                  {instrument.symbol} - {instrument.name}
                </option>
              ))}
            </select>
          </Field>

          <Field label="New Preset" className="col-12 col-md-4 col-xl-3">
            <div className="d-flex gap-2">
              <input
                type="text"
                value={newPresetName}
                onChange={(event) => setNewPresetName(event.target.value)}
                className="form-control app-input"
                placeholder="preset-name"
              />
              <button type="button" className="app-btn px-3" onClick={createPreset} disabled={isSaving || !newPresetName.trim()}>
                Create
              </button>
            </div>
          </Field>

          <Readout label="Enabled" value={`${enabledCount} / ${MODULES.length}`} />
          <Readout label="Tick Value" value={selectedInstrument ? `$${selectedInstrument.tickValue}` : "--"} />
          <Readout label="Status" value={saveStatus || "Ready"} />
        </div>
      </div>

      <div className="app-panel">
        <div className="fw-bold app-kicker mb-3">Modules</div>
        <div className="app-table-wrap">
          <div className="app-grid-head futures-settings-grid">
            <span>Strategy</span>
            <span>Enabled</span>
            <span>Max / Day</span>
          </div>
          {MODULES.map(([key, name]) => (
            <div className="app-grid-row futures-settings-grid" key={key}>
              <span className="fw-bold">{name}</span>
              <label className="app-toggle-row">
                <input
                  type="checkbox"
                  checked={Boolean(settings[key]?.enabled)}
                  onChange={(event) => updateModule(key, "enabled", event.target.checked)}
                  disabled={isLoading}
                />
                {settings[key]?.enabled ? "On" : "Off"}
              </label>
              <input
                type="number"
                min="0"
                max={key === "keltnerScalp" || key === "keltnerReversion" || key === "microScalp" ? "20" : "5"}
                value={settings[key]?.maxTradesPerDay ?? 1}
                onChange={(event) => updateModule(key, "maxTradesPerDay", event.target.value)}
                className="form-control app-input"
                disabled={isLoading}
              />
            </div>
          ))}
        </div>
      </div>

      <details className="app-panel">
        <summary className="fw-bold app-kicker">Advanced Rules</summary>

        <fieldset className="row g-3 mt-2 futures-fieldset" disabled={isLoading}>
          <ToggleField label="Early Sweep" field="enableEarlySweep" settings={settings} updateField={updateField} />
          <ToggleField label="Late Sweep" field="enableLateSweep" settings={settings} updateField={updateField} />
          <ToggleField label="Second-Chance Sweep" field="enableSweepSecondChance" settings={settings} updateField={updateField} />
          <ToggleField label="ORB Retest" field="enableOrbRetest" settings={settings} updateField={updateField} />
          <ToggleField label="Compressed ORB Stop" field="enableCompressedOrbBreakout" settings={settings} updateField={updateField} />
          <ToggleField label="Skip 10:00 ORB Retest" field="skipMidmorningOrbRetest" settings={settings} updateField={updateField} />
          <ToggleField label="Higher-Timeframe Guard" field="requireHigherTimeframeGuard" settings={settings} updateField={updateField} />
          <ToggleField label="Allow Shorts" field="allowShorts" settings={settings} updateField={updateField} />

          <NumberField label="Opening Range Minutes" field="openingMomentumRangeMinutes" settings={settings} updateField={updateField} />
          <NumberField label="Opening Max Hold Bars" field="openingMomentumMaxHoldBars" settings={settings} updateField={updateField} />
          <NumberField label="Opening Volume Ratio" field="openingMomentumVolumeRatio" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Opening Reward/Risk" field="openingMomentumRewardRisk" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Early Reclaim Ticks" field="earlySweepReclaimTicks" settings={settings} updateField={updateField} />
          <NumberField label="Late Reclaim Ticks" field="lateSweepReclaimTicks" settings={settings} updateField={updateField} />
          <NumberField label="Sweep Close Location" field="sweepCloseLocation" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Late Close Location" field="lateSweepCloseLocation" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Minimum Body %" field="minBodyPct" settings={settings} updateField={updateField} />
          <NumberField label="VWAP Volume Ratio" field="vwapMinVolumeRatio" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="VWAP Slope Ticks" field="vwapMinTrendSlopeTicks" settings={settings} updateField={updateField} />
          <NumberField label="VWAP Max Distance" field="vwapMaxDistanceTicks" settings={settings} updateField={updateField} />
          <NumberField label="MRVWAP Min Distance" field="meanReversionMinDistanceTicks" settings={settings} updateField={updateField} />
          <NumberField label="Oversold RSI" field="meanReversionOversoldRsi" settings={settings} updateField={updateField} />
          <NumberField label="Overbought RSI" field="meanReversionOverboughtRsi" settings={settings} updateField={updateField} />
          <NumberField label="Minimum Reward/Risk" field="minRewardRisk" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Close Momentum Min Move" field="closeMomentumMinMoveTicks" settings={settings} updateField={updateField} />
          <NumberField label="Close Momentum Volume" field="closeMomentumVolumeRatio" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Close Momentum Reward/Risk" field="closeMomentumRewardRisk" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="ORB Compressed Max Risk" field="orbCompressedMaxRiskTicks" settings={settings} updateField={updateField} />
          <NumberField label="Afternoon Volume Ratio" field="afternoonMinVolumeRatio" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Afternoon Max Risk" field="afternoonMaxRiskTicks" settings={settings} updateField={updateField} />
          <NumberField label="Afternoon Reward/Risk" field="afternoonRewardRisk" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Keltner ATR Multiplier" field="keltnerAtrMultiplier" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Keltner Volume Ratio" field="keltnerMinVolumeRatio" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Keltner Max Risk" field="keltnerMaxRiskTicks" settings={settings} updateField={updateField} />
          <NumberField label="Keltner Reward/Risk" field="keltnerRewardRisk" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Keltner Body %" field="keltnerMinBodyPct" settings={settings} updateField={updateField} />
          <NumberField label="Keltner Slope Ticks" field="keltnerMinTrendSlopeTicks" settings={settings} updateField={updateField} step="0.25" />
          <NumberField label="Keltner Band Width" field="keltnerMinBandWidthTicks" settings={settings} updateField={updateField} />
          <NumberField label="Keltner Max Hold" field="keltnerMaxHoldBars" settings={settings} updateField={updateField} />
          <NumberField label="Keltner Bucket Minutes" field="keltnerBucketMinutes" settings={settings} updateField={updateField} />
          <NumberField label="Max Initial Risk Ticks" field="maxInitialRiskTicks" settings={settings} updateField={updateField} />
        </fieldset>
      </details>
    </div>
  );
}

function normalizeSettings(data) {
  return {
    ...DEFAULT_SETTINGS,
    ...(data || {}),
    orb: { ...DEFAULT_SETTINGS.orb, ...(data?.orb || {}) },
    openingMomentum: { ...DEFAULT_SETTINGS.openingMomentum, ...(data?.openingMomentum || {}) },
    sweep: { ...DEFAULT_SETTINGS.sweep, ...(data?.sweep || {}) },
    vwapPullback: { ...DEFAULT_SETTINGS.vwapPullback, ...(data?.vwapPullback || {}) },
    vwapMeanReversion: { ...DEFAULT_SETTINGS.vwapMeanReversion, ...(data?.vwapMeanReversion || {}) },
    fvg: { ...DEFAULT_SETTINGS.fvg, ...(data?.fvg || {}) },
    closeMomentum: { ...DEFAULT_SETTINGS.closeMomentum, ...(data?.closeMomentum || {}) },
    afternoonContinuation: { ...DEFAULT_SETTINGS.afternoonContinuation, ...(data?.afternoonContinuation || {}) },
    marketIntradayMomentum: { ...DEFAULT_SETTINGS.marketIntradayMomentum, ...(data?.marketIntradayMomentum || {}) },
    keltnerScalp: { ...DEFAULT_SETTINGS.keltnerScalp, ...(data?.keltnerScalp || {}) },
    keltnerReversion: { ...DEFAULT_SETTINGS.keltnerReversion, ...(data?.keltnerReversion || {}) },
    microScalp: { ...DEFAULT_SETTINGS.microScalp, ...(data?.microScalp || {}) },
  };
}

function Readout({ label, value }) {
  return (
    <div className="col-12 col-sm-4 col-lg-2">
      <div className="app-data-chip h-100">
        <span className="app-label">{label}</span>
        <strong>{value}</strong>
      </div>
    </div>
  );
}

function ToggleField({ label, field, settings, updateField, disabled = false }) {
  return (
    <div className="col-12 col-sm-6 col-xl-4">
      <label className="app-toggle-row">
        <input
          type="checkbox"
          checked={Boolean(settings[field])}
          onChange={(event) => updateField(field, event.target.checked)}
          disabled={disabled}
        />
        {label}
      </label>
    </div>
  );
}

function NumberField({ label, field, settings, updateField, step = "1", disabled = false }) {
  return (
    <Field label={label} className="col-12 col-sm-6 col-xl-3">
      <input
        type="number"
        step={step}
        value={settings[field] ?? ""}
        onChange={(event) => updateField(field, event.target.value)}
        className="form-control app-input"
        disabled={disabled}
      />
    </Field>
  );
}

function Field({ label, children, className = "col" }) {
  return (
    <div className={className}>
      <label className="d-grid gap-1">
        <span className="app-label">{label}</span>
        {children}
      </label>
    </div>
  );
}
