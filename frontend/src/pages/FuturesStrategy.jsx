import { useEffect, useMemo, useState } from "react";
import { apiFetch } from "../utils/api.js";

const DEFAULT_SETTINGS = {
  orb: { enabled: true, maxTradesPerDay: 2 },
  openingMomentum: { enabled: false, maxTradesPerDay: 2 },
  sweep: { enabled: true, maxTradesPerDay: 3 },
  vwapPullback: { enabled: false, maxTradesPerDay: 1 },
  vwapMeanReversion: { enabled: false, maxTradesPerDay: 1 },
  fvg: { enabled: false, maxTradesPerDay: 1 },
  ifvg: { enabled: false, maxTradesPerDay: 1 },
  closeMomentum: { enabled: false, maxTradesPerDay: 1 },
  afternoonContinuation: { enabled: false, maxTradesPerDay: 2 },
  marketIntradayMomentum: { enabled: false, maxTradesPerDay: 1 },
  keltnerScalp: { enabled: false, maxTradesPerDay: 8 },
  keltnerReversion: { enabled: false, maxTradesPerDay: 6 },
  microScalp: { enabled: false, maxTradesPerDay: 6 },
  mclEiaContinuation: { enabled: false, maxTradesPerDay: 2 },
  mclCrudeSessionOpen: { enabled: false, maxTradesPerDay: 2 },
  mymIndexConfirmation: { enabled: false, maxTradesPerDay: 3 },
  mymOrbRetest: { enabled: false, maxTradesPerDay: 2 },
  mymBreadthConfirmation: { enabled: false, maxTradesPerDay: 6 },
  mclTrendContinuation: { enabled: false, maxTradesPerDay: 6 },
  liquidityReclaim: { enabled: false, maxTradesPerDay: 50 },
  liquidityReclaimSourceCodes: "FVG,VWAP,AFT,SWEEP,PDB,KREV,SHDW,VPB",
  liquidityReclaimStartMinute: 570,
  liquidityReclaimEndMinute: 930,
  liquidityReclaimAllowDuplicates: true,
  liquidityReclaimMaxContracts: 0,
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
  fvgTradeInversions: false,
  fvgRequireInversionStructureBreak: false,
  fvgInversionBreakBars: 10,
  fvgInversionStructureBars: 20,
  fvgMinInversionBodyPct: 0,
  fvgSourceMode: "NONE",
  fvgSourceRangeBars: 0,
  fvgMinSourceBreakTicks: 0,
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
  allowMclEiaLongs: true,
  allowMclEiaShorts: true,
  mclEiaRangeStartMinute: 626,
  mclEiaRangeEndMinute: 630,
  mclEiaStartMinute: 660,
  mclEiaEndMinute: 750,
  mclEiaBreakoutBufferTicks: 1,
  mclEiaStopTicks: 24,
  mclEiaRewardRisk: 1.35,
  mclEiaMinVolumeRatio: 0,
  mclEiaMinBodyPct: 0,
  mclEiaMaxHoldBars: 60,
  allowMclCrudeOpenLongs: true,
  allowMclCrudeOpenShorts: true,
  mclCrudeOpenRangeStartMinute: 540,
  mclCrudeOpenRangeEndMinute: 550,
  mclCrudeOpenStartMinute: 551,
  mclCrudeOpenEndMinute: 660,
  mclCrudeOpenBreakoutBufferTicks: 2,
  mclCrudeOpenStopTicks: 22,
  mclCrudeOpenRewardRisk: 1.1,
  mclCrudeOpenMinVolumeRatio: 0.35,
  mclCrudeOpenMinBodyPct: 20,
  mclCrudeOpenMaxHoldBars: 45,
  allowMymIndexConfirmationLongs: true,
  allowMymIndexConfirmationShorts: true,
  mymIndexConfirmationStartMinute: 570,
  mymIndexConfirmationEndMinute: 920,
  mymIndexConfirmationBucketMinutes: 20,
  mymIndexConfirmationLookbackBars: 12,
  mymIndexConfirmationMaxRiskTicks: 90,
  mymIndexConfirmationRewardRisk: 0.85,
  mymIndexConfirmationMinVolumeRatio: 0.55,
  mymIndexConfirmationMinBodyPct: 20,
  mymIndexConfirmationMinTrendSlopeTicks: 0.5,
  mymIndexConfirmationMaxHoldBars: 35,
  allowMymOrbRetestLongs: true,
  allowMymOrbRetestShorts: true,
  mymOrbRetestStartMinute: 590,
  mymOrbRetestEndMinute: 690,
  mymOrbRetestBreakoutBufferTicks: 2,
  mymOrbRetestRetestTicks: 5,
  mymOrbRetestMaxRiskTicks: 110,
  mymOrbRetestRewardRisk: 0.9,
  mymOrbRetestMinVolumeRatio: 0.55,
  mymOrbRetestMinBodyPct: 20,
  mymOrbRetestMaxHoldBars: 45,
  allowMymBreadthLongs: true,
  allowMymBreadthShorts: true,
  mymBreadthStartMinute: 585,
  mymBreadthEndMinute: 900,
  mymBreadthBucketMinutes: 18,
  mymBreadthLookbackBars: 12,
  mymBreadthMinAlignedMarkets: 2,
  mymBreadthMaxRiskTicks: 90,
  mymBreadthRewardRisk: 0.95,
  mymBreadthMinVolumeRatio: 0.65,
  mymBreadthMinBodyPct: 22,
  mymBreadthMinTrendSlopeTicks: 0.75,
  mymBreadthMaxHoldBars: 35,
  allowMclTrendLongs: true,
  allowMclTrendShorts: true,
  mclTrendStartMinute: 570,
  mclTrendEndMinute: 900,
  mclTrendBucketMinutes: 30,
  mclTrendLookbackBars: 12,
  mclTrendBreakoutBufferTicks: 1,
  mclTrendMinOpenMoveTicks: 18,
  mclTrendMaxRiskTicks: 30,
  mclTrendRewardRisk: 1.1,
  mclTrendMinVolumeRatio: 0.7,
  mclTrendMinBodyPct: 18,
  mclTrendMinTrendSlopeTicks: 0.6,
  mclTrendMaxHoldBars: 40,
  maxInitialRiskTicks: 220,
  managedStopBreakevenTriggerR: 0.75,
  managedStopTrailTriggerR: 1.15,
  managedStopTrailDistanceR: 0.55,
  managedStopMinTrailTicks: 8,
  enableManagedGivebackExit: false,
  managedGivebackTriggerR: 0.95,
  managedGivebackR: 0.45,
  managedGivebackMinBars: 3,
};

const DEFAULT_STRATEGY_PRESET = "backtestbias92k";
const BIAS_FREE_STRATEGY_PRESET = "biasfree92k";
const BEST_BIAS_FREE_STRATEGY_PRESET = "bestbiasfree";
const READ_ONLY_STRATEGY_PRESETS = new Set([DEFAULT_STRATEGY_PRESET]);
const CANONICAL_STRATEGY_PRESETS = [
  { name: DEFAULT_STRATEGY_PRESET, label: "Backtest Bias 92k" },
  { name: BIAS_FREE_STRATEGY_PRESET, label: "Bias-Free 92k" },
  { name: BEST_BIAS_FREE_STRATEGY_PRESET, label: "Best Bias-Free" },
];
const INSTRUMENT_FALLBACKS = [
  { symbol: "MES", name: "Micro E-mini S&P 500", exchange: "CME", tickSize: 0.25, tickValue: 1.25 },
  { symbol: "MNQ", name: "Micro E-mini Nasdaq-100", exchange: "CME", tickSize: 0.25, tickValue: 0.5 },
  { symbol: "NQ", name: "E-mini Nasdaq-100", exchange: "CME", tickSize: 0.25, tickValue: 5 },
  { symbol: "MGC", name: "Micro Gold", exchange: "COMEX", tickSize: 0.1, tickValue: 1 },
  { symbol: "ES", name: "E-mini S&P 500", exchange: "CME", tickSize: 0.25, tickValue: 12.5 },
  { symbol: "M2K", name: "Micro E-mini Russell 2000", exchange: "CME", tickSize: 0.1, tickValue: 0.5 },
  { symbol: "MYM", name: "Micro E-mini Dow", exchange: "CBOT", tickSize: 1, tickValue: 0.5 },
  { symbol: "MCL", name: "Micro WTI Crude Oil", exchange: "NYMEX", tickSize: 0.01, tickValue: 1 },
  { symbol: "GC", name: "Gold", exchange: "COMEX", tickSize: 0.1, tickValue: 10 },
];

const MODULES = [
  ["orb", "Opening Range Breakout"],
  ["openingMomentum", "Opening Momentum"],
  ["sweep", "Prior-Day Sweep"],
  ["vwapPullback", "VWAP Pullback"],
  ["vwapMeanReversion", "VWAP Mean Reversion"],
  ["fvg", "Fair Value Gap"],
  ["ifvg", "Inversion Fair Value Gap"],
  ["closeMomentum", "Close Momentum"],
  ["afternoonContinuation", "Afternoon Continuation"],
  ["marketIntradayMomentum", "Market Intraday Momentum"],
  ["keltnerScalp", "Keltner ATR Scalp"],
  ["keltnerReversion", "Keltner Reversion"],
  ["microScalp", "Micro Trend Scalp"],
  ["mclEiaContinuation", "MCL EIA Continuation"],
  ["mclCrudeSessionOpen", "MCL Crude Session Open"],
  ["mymIndexConfirmation", "MYM Index Confirmation"],
  ["mymOrbRetest", "MYM ORB Retest"],
  ["mymBreadthConfirmation", "MYM Breadth Fade"],
  ["mclTrendContinuation", "MCL Trend Fade"],
  ["liquidityReclaim", "Liquidity Reclaim"],
];

const BEST_BIAS_FREE_LIVE_STRATEGIES = [];

const HIGH_CAP_MODULES = new Set(["keltnerScalp", "keltnerReversion", "microScalp"]);
const CUSTOM_MODULE_CAPS = {
  mclEiaContinuation: 8,
  mclCrudeSessionOpen: 8,
  mymIndexConfirmation: 12,
  mymOrbRetest: 8,
  mymBreadthConfirmation: 20,
  mclTrendContinuation: 20,
  liquidityReclaim: 100,
};

export default function FuturesStrategy() {
  const [settings, setSettings] = useState(DEFAULT_SETTINGS);
  const [selectedSymbol, setSelectedSymbol] = useState("MNQ");
  const [selectedPreset, setSelectedPreset] = useState(BIAS_FREE_STRATEGY_PRESET);
  const [strategyPresets, setStrategyPresets] = useState([]);
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
        const nextInstruments = buildInstrumentOptions(data);
        setInstruments(nextInstruments);
        if (nextInstruments.length && !nextInstruments.some((instrument) => instrument.symbol === selectedSymbol)) {
          setSelectedSymbol(nextInstruments[0].symbol);
        }
      })
      .catch((error) => {
        console.error("Error loading futures instruments:", error);
        setInstruments(INSTRUMENT_FALLBACKS);
      });
  }

  function loadStrategyPresets() {
    apiFetch("/api/futures/strategy-presets")
      .then((response) => {
        if (!response.ok) throw new Error("Failed to load strategy presets.");
        return response.json();
      })
      .then((data) => {
        const presets = mergeStrategyPresets(data);
        setStrategyPresets(presets);
        if (presets.length && !presets.some((preset) => preset.name === selectedPreset)) {
          setSelectedPreset(presets[0].name);
        }
      })
      .catch((error) => {
        console.error("Error loading futures strategy presets:", error);
        setStrategyPresets(CANONICAL_STRATEGY_PRESETS);
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
      .then((strategyData) => {
        setSettings(normalizeSettings(strategyData));
      })
      .catch((error) => {
        console.error("Error loading futures configuration:", error);
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
    if (READ_ONLY_STRATEGY_PRESETS.has(selectedPreset)) {
      setSaveStatus("backtestbias92k is read-only. Switch to biasfree92k or bestbiasfree to save edits.");
      return;
    }
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
      ifvgEnabled: String(settings.ifvg.enabled),
      ifvgMaxTradesPerDay: String(settings.ifvg.maxTradesPerDay),
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
      mclEiaContinuationEnabled: String(settings.mclEiaContinuation.enabled),
      mclEiaContinuationMaxTradesPerDay: String(settings.mclEiaContinuation.maxTradesPerDay),
      mclCrudeSessionOpenEnabled: String(settings.mclCrudeSessionOpen.enabled),
      mclCrudeSessionOpenMaxTradesPerDay: String(settings.mclCrudeSessionOpen.maxTradesPerDay),
      mymIndexConfirmationEnabled: String(settings.mymIndexConfirmation.enabled),
      mymIndexConfirmationMaxTradesPerDay: String(settings.mymIndexConfirmation.maxTradesPerDay),
      mymOrbRetestEnabled: String(settings.mymOrbRetest.enabled),
      mymOrbRetestMaxTradesPerDay: String(settings.mymOrbRetest.maxTradesPerDay),
      mymBreadthConfirmationEnabled: String(settings.mymBreadthConfirmation.enabled),
      mymBreadthConfirmationMaxTradesPerDay: String(settings.mymBreadthConfirmation.maxTradesPerDay),
      mclTrendContinuationEnabled: String(settings.mclTrendContinuation.enabled),
      mclTrendContinuationMaxTradesPerDay: String(settings.mclTrendContinuation.maxTradesPerDay),
      liquidityReclaimEnabled: String(settings.liquidityReclaim.enabled),
      liquidityReclaimMaxTradesPerDay: String(settings.liquidityReclaim.maxTradesPerDay),
      liquidityReclaimSourceCodes: String(settings.liquidityReclaimSourceCodes || ""),
      liquidityReclaimStartMinute: String(settings.liquidityReclaimStartMinute),
      liquidityReclaimEndMinute: String(settings.liquidityReclaimEndMinute),
      liquidityReclaimAllowDuplicates: String(settings.liquidityReclaimAllowDuplicates),
      liquidityReclaimMaxContracts: String(settings.liquidityReclaimMaxContracts),
      enableEarlySweep: String(settings.enableEarlySweep),
      enableLateSweep: String(settings.enableLateSweep),
      enableSweepSecondChance: String(settings.enableSweepSecondChance),
      enableOrbRetest: String(settings.enableOrbRetest),
      enableCompressedOrbBreakout: String(settings.enableCompressedOrbBreakout),
      orbBreakoutEndMinute: String(settings.orbBreakoutEndMinute),
      orbShortConfirmationMinute: String(settings.orbShortConfirmationMinute),
      skipMidmorningOrbRetest: String(settings.skipMidmorningOrbRetest),
      requireHigherTimeframeGuard: String(settings.requireHigherTimeframeGuard),
      relaxPatternHardWindows: String(settings.relaxPatternHardWindows),
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
      vwapRequireHigherTimeframeGuard: String(settings.vwapRequireHigherTimeframeGuard),
      fvgRequireCoreQuality: String(settings.fvgRequireCoreQuality),
      fvgRequireEmaStack: String(settings.fvgRequireEmaStack),
      fvgRequireHigherTimeframeGuard: String(settings.fvgRequireHigherTimeframeGuard),
      fvgTradeInversions: String(settings.fvgTradeInversions),
      fvgRequireInversionStructureBreak: String(settings.fvgRequireInversionStructureBreak),
      fvgInversionBreakBars: String(settings.fvgInversionBreakBars),
      fvgInversionStructureBars: String(settings.fvgInversionStructureBars),
      fvgMinInversionBodyPct: String(settings.fvgMinInversionBodyPct),
      fvgMinImpulseBodyPct: String(settings.fvgMinImpulseBodyPct),
      fvgMinTrendSlopeTicks: String(settings.fvgMinTrendSlopeTicks),
      fvgMaxVwapDistanceTicks: String(settings.fvgMaxVwapDistanceTicks),
      fvgMaxEntryExtensionTicks: String(settings.fvgMaxEntryExtensionTicks),
      fvgSourceMode: String(settings.fvgSourceMode || "NONE"),
      fvgSourceRangeBars: String(settings.fvgSourceRangeBars),
      fvgMinSourceBreakTicks: String(settings.fvgMinSourceBreakTicks),
      meanReversionMinDistanceTicks: String(settings.meanReversionMinDistanceTicks),
      meanReversionOversoldRsi: String(settings.meanReversionOversoldRsi),
      meanReversionOverboughtRsi: String(settings.meanReversionOverboughtRsi),
      minRewardRisk: String(settings.minRewardRisk),
      closeMomentumMinMoveTicks: String(settings.closeMomentumMinMoveTicks),
      closeMomentumVolumeRatio: String(settings.closeMomentumVolumeRatio),
      closeMomentumRewardRisk: String(settings.closeMomentumRewardRisk),
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
      allowMclEiaLongs: String(settings.allowMclEiaLongs),
      allowMclEiaShorts: String(settings.allowMclEiaShorts),
      mclEiaRangeStartMinute: String(settings.mclEiaRangeStartMinute),
      mclEiaRangeEndMinute: String(settings.mclEiaRangeEndMinute),
      mclEiaStartMinute: String(settings.mclEiaStartMinute),
      mclEiaEndMinute: String(settings.mclEiaEndMinute),
      mclEiaBreakoutBufferTicks: String(settings.mclEiaBreakoutBufferTicks),
      mclEiaStopTicks: String(settings.mclEiaStopTicks),
      mclEiaRewardRisk: String(settings.mclEiaRewardRisk),
      mclEiaMinVolumeRatio: String(settings.mclEiaMinVolumeRatio),
      mclEiaMinBodyPct: String(settings.mclEiaMinBodyPct),
      mclEiaMaxHoldBars: String(settings.mclEiaMaxHoldBars),
      allowMclCrudeOpenLongs: String(settings.allowMclCrudeOpenLongs),
      allowMclCrudeOpenShorts: String(settings.allowMclCrudeOpenShorts),
      mclCrudeOpenRangeStartMinute: String(settings.mclCrudeOpenRangeStartMinute),
      mclCrudeOpenRangeEndMinute: String(settings.mclCrudeOpenRangeEndMinute),
      mclCrudeOpenStartMinute: String(settings.mclCrudeOpenStartMinute),
      mclCrudeOpenEndMinute: String(settings.mclCrudeOpenEndMinute),
      mclCrudeOpenBreakoutBufferTicks: String(settings.mclCrudeOpenBreakoutBufferTicks),
      mclCrudeOpenStopTicks: String(settings.mclCrudeOpenStopTicks),
      mclCrudeOpenRewardRisk: String(settings.mclCrudeOpenRewardRisk),
      mclCrudeOpenMinVolumeRatio: String(settings.mclCrudeOpenMinVolumeRatio),
      mclCrudeOpenMinBodyPct: String(settings.mclCrudeOpenMinBodyPct),
      mclCrudeOpenMaxHoldBars: String(settings.mclCrudeOpenMaxHoldBars),
      allowMymIndexConfirmationLongs: String(settings.allowMymIndexConfirmationLongs),
      allowMymIndexConfirmationShorts: String(settings.allowMymIndexConfirmationShorts),
      mymIndexConfirmationStartMinute: String(settings.mymIndexConfirmationStartMinute),
      mymIndexConfirmationEndMinute: String(settings.mymIndexConfirmationEndMinute),
      mymIndexConfirmationBucketMinutes: String(settings.mymIndexConfirmationBucketMinutes),
      mymIndexConfirmationLookbackBars: String(settings.mymIndexConfirmationLookbackBars),
      mymIndexConfirmationMaxRiskTicks: String(settings.mymIndexConfirmationMaxRiskTicks),
      mymIndexConfirmationRewardRisk: String(settings.mymIndexConfirmationRewardRisk),
      mymIndexConfirmationMinVolumeRatio: String(settings.mymIndexConfirmationMinVolumeRatio),
      mymIndexConfirmationMinBodyPct: String(settings.mymIndexConfirmationMinBodyPct),
      mymIndexConfirmationMinTrendSlopeTicks: String(settings.mymIndexConfirmationMinTrendSlopeTicks),
      mymIndexConfirmationMaxHoldBars: String(settings.mymIndexConfirmationMaxHoldBars),
      allowMymOrbRetestLongs: String(settings.allowMymOrbRetestLongs),
      allowMymOrbRetestShorts: String(settings.allowMymOrbRetestShorts),
      mymOrbRetestStartMinute: String(settings.mymOrbRetestStartMinute),
      mymOrbRetestEndMinute: String(settings.mymOrbRetestEndMinute),
      mymOrbRetestBreakoutBufferTicks: String(settings.mymOrbRetestBreakoutBufferTicks),
      mymOrbRetestRetestTicks: String(settings.mymOrbRetestRetestTicks),
      mymOrbRetestMaxRiskTicks: String(settings.mymOrbRetestMaxRiskTicks),
      mymOrbRetestRewardRisk: String(settings.mymOrbRetestRewardRisk),
      mymOrbRetestMinVolumeRatio: String(settings.mymOrbRetestMinVolumeRatio),
      mymOrbRetestMinBodyPct: String(settings.mymOrbRetestMinBodyPct),
      mymOrbRetestMaxHoldBars: String(settings.mymOrbRetestMaxHoldBars),
      allowMymBreadthLongs: String(settings.allowMymBreadthLongs),
      allowMymBreadthShorts: String(settings.allowMymBreadthShorts),
      mymBreadthStartMinute: String(settings.mymBreadthStartMinute),
      mymBreadthEndMinute: String(settings.mymBreadthEndMinute),
      mymBreadthBucketMinutes: String(settings.mymBreadthBucketMinutes),
      mymBreadthLookbackBars: String(settings.mymBreadthLookbackBars),
      mymBreadthMinAlignedMarkets: String(settings.mymBreadthMinAlignedMarkets),
      mymBreadthMaxRiskTicks: String(settings.mymBreadthMaxRiskTicks),
      mymBreadthRewardRisk: String(settings.mymBreadthRewardRisk),
      mymBreadthMinVolumeRatio: String(settings.mymBreadthMinVolumeRatio),
      mymBreadthMinBodyPct: String(settings.mymBreadthMinBodyPct),
      mymBreadthMinTrendSlopeTicks: String(settings.mymBreadthMinTrendSlopeTicks),
      mymBreadthMaxHoldBars: String(settings.mymBreadthMaxHoldBars),
      allowMclTrendLongs: String(settings.allowMclTrendLongs),
      allowMclTrendShorts: String(settings.allowMclTrendShorts),
      mclTrendStartMinute: String(settings.mclTrendStartMinute),
      mclTrendEndMinute: String(settings.mclTrendEndMinute),
      mclTrendBucketMinutes: String(settings.mclTrendBucketMinutes),
      mclTrendLookbackBars: String(settings.mclTrendLookbackBars),
      mclTrendBreakoutBufferTicks: String(settings.mclTrendBreakoutBufferTicks),
      mclTrendMinOpenMoveTicks: String(settings.mclTrendMinOpenMoveTicks),
      mclTrendMaxRiskTicks: String(settings.mclTrendMaxRiskTicks),
      mclTrendRewardRisk: String(settings.mclTrendRewardRisk),
      mclTrendMinVolumeRatio: String(settings.mclTrendMinVolumeRatio),
      mclTrendMinBodyPct: String(settings.mclTrendMinBodyPct),
      mclTrendMinTrendSlopeTicks: String(settings.mclTrendMinTrendSlopeTicks),
      mclTrendMaxHoldBars: String(settings.mclTrendMaxHoldBars),
      managedStopBreakevenTriggerR: String(settings.managedStopBreakevenTriggerR),
      managedStopTrailTriggerR: String(settings.managedStopTrailTriggerR),
      managedStopTrailDistanceR: String(settings.managedStopTrailDistanceR),
      managedStopMinTrailTicks: String(settings.managedStopMinTrailTicks),
      enableManagedGivebackExit: String(settings.enableManagedGivebackExit),
      managedGivebackTriggerR: String(settings.managedGivebackTriggerR),
      managedGivebackR: String(settings.managedGivebackR),
      managedGivebackMinBars: String(settings.managedGivebackMinBars),
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

  const instrumentOptions = useMemo(() => instruments.length ? instruments : INSTRUMENT_FALLBACKS, [instruments]);
  const selectedInstrument = instrumentOptions.find((instrument) => instrument.symbol === selectedSymbol) || null;
  const presetOptions = mergeStrategyPresets(strategyPresets);
  const selectedPresetReadOnly = READ_ONLY_STRATEGY_PRESETS.has(selectedPreset);
  const liveStrategyRows = BEST_BIAS_FREE_LIVE_STRATEGIES.filter(([, , symbols]) =>
    selectedPreset === BEST_BIAS_FREE_STRATEGY_PRESET && symbols.has(selectedSymbol)
  );
  const liveStrategyKeys = new Set(liveStrategyRows.map(([key]) => key));
  const baseModuleRows = MODULES.filter(([key]) => !liveStrategyKeys.has(key));
  const enabledCount = baseModuleRows.filter(([key]) => settings[key]?.enabled).length;

  return (
    <div className="app-page futures-config-page">
      <div className="d-flex align-items-center justify-content-between gap-2 flex-wrap mb-3">
        <h2 className="app-title m-0">Futures Strategy Configurations</h2>
        <div className="d-flex gap-2 flex-wrap">
          <button type="button" className="app-btn app-btn-primary px-3" onClick={saveSettings} disabled={isSaving || isLoading || selectedPresetReadOnly}>
            {selectedPresetReadOnly ? "Read Only" : isSaving ? "Saving..." : "Save Preset"}
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
              {instrumentOptions.map((instrument) => (
                <option key={instrument.symbol} value={instrument.symbol}>
                  {instrument.symbol} - {instrument.name}
                </option>
              ))}
            </select>
          </Field>

          <Readout label="Enabled" value={`${enabledCount} / ${baseModuleRows.length}`} />
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
          {baseModuleRows.map(([key, name]) => (
            <div className="app-grid-row futures-settings-grid" key={key}>
              <span className="fw-bold">{name}</span>
              <label className="app-toggle-row">
                <input
                  type="checkbox"
                  checked={Boolean(settings[key]?.enabled)}
                  onChange={(event) => updateModule(key, "enabled", event.target.checked)}
                  disabled={isLoading || selectedPresetReadOnly}
                />
                {settings[key]?.enabled ? "On" : "Off"}
              </label>
              <input
                type="number"
                min="0"
                max={moduleMaxTrades(key)}
                value={settings[key]?.maxTradesPerDay ?? 1}
                onChange={(event) => updateModule(key, "maxTradesPerDay", event.target.value)}
                className="form-control app-input"
                disabled={isLoading || selectedPresetReadOnly}
              />
            </div>
          ))}
        </div>
      </div>

      {liveStrategyRows.length > 0 && (
        <div className="app-panel">
          <div className="fw-bold app-kicker mb-3">Live Strategies</div>
          <div className="app-table-wrap">
            <div className="app-grid-head futures-settings-grid">
              <span>Strategy</span>
              <span>Enabled</span>
              <span>Max / Day</span>
            </div>
            {liveStrategyRows.map(([key, name]) => (
              <div className="app-grid-row futures-settings-grid" key={`live-${key}`}>
                <span className="fw-bold">{name}</span>
                <label className="app-toggle-row">
                  <input
                    type="checkbox"
                    checked={Boolean(settings[key]?.enabled)}
                    onChange={(event) => updateModule(key, "enabled", event.target.checked)}
                    disabled={isLoading || selectedPresetReadOnly}
                  />
                  {settings[key]?.enabled ? "On" : "Off"}
                </label>
                <input
                  type="number"
                  min="0"
                  max={moduleMaxTrades(key)}
                  value={settings[key]?.maxTradesPerDay ?? 1}
                  onChange={(event) => updateModule(key, "maxTradesPerDay", event.target.value)}
                  className="form-control app-input"
                  disabled={isLoading || selectedPresetReadOnly}
                />
              </div>
            ))}
          </div>
        </div>
      )}

      <details className="app-panel">
        <summary className="fw-bold app-kicker">Advanced Rules</summary>

        <fieldset className="row g-3 mt-2 futures-fieldset" disabled={isLoading || selectedPresetReadOnly}>
          <ToggleField label="Early Sweep" field="enableEarlySweep" settings={settings} updateField={updateField} />
          <ToggleField label="Late Sweep" field="enableLateSweep" settings={settings} updateField={updateField} />
          <ToggleField label="Second-Chance Sweep" field="enableSweepSecondChance" settings={settings} updateField={updateField} />
          <ToggleField label="ORB Retest" field="enableOrbRetest" settings={settings} updateField={updateField} />
          <ToggleField label="Compressed ORB Stop" field="enableCompressedOrbBreakout" settings={settings} updateField={updateField} />
          <ToggleField label="Skip 10:00 ORB Retest" field="skipMidmorningOrbRetest" settings={settings} updateField={updateField} />
          <ToggleField label="Higher-Timeframe Guard" field="requireHigherTimeframeGuard" settings={settings} updateField={updateField} />
          <ToggleField label="Relax Pattern Windows" field="relaxPatternHardWindows" settings={settings} updateField={updateField} />
          <ToggleField label="Allow Shorts" field="allowShorts" settings={settings} updateField={updateField} />
          <ToggleField label="VWAP HTF Guard" field="vwapRequireHigherTimeframeGuard" settings={settings} updateField={updateField} />
          <ToggleField label="FVG Quality Gate" field="fvgRequireCoreQuality" settings={settings} updateField={updateField} />
          <ToggleField label="FVG EMA Stack" field="fvgRequireEmaStack" settings={settings} updateField={updateField} />
          <ToggleField label="FVG HTF Guard" field="fvgRequireHigherTimeframeGuard" settings={settings} updateField={updateField} />
          <ToggleField label="IFVG Structure Break" field="fvgRequireInversionStructureBreak" settings={settings} updateField={updateField} />
          <ToggleField label="LIQREC Duplicates" field="liquidityReclaimAllowDuplicates" settings={settings} updateField={updateField} />

          <NumberField label="ORB End Minute" field="orbBreakoutEndMinute" settings={settings} updateField={updateField} />
          <NumberField label="ORB Short Confirm" field="orbShortConfirmationMinute" settings={settings} updateField={updateField} />
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
          <NumberField label="IFVG Break Bars" field="fvgInversionBreakBars" settings={settings} updateField={updateField} />
          <NumberField label="IFVG Structure Bars" field="fvgInversionStructureBars" settings={settings} updateField={updateField} />
          <NumberField label="IFVG Break Body %" field="fvgMinInversionBodyPct" settings={settings} updateField={updateField} />
          <NumberField label="FVG Impulse Body %" field="fvgMinImpulseBodyPct" settings={settings} updateField={updateField} />
          <NumberField label="FVG Slope Ticks" field="fvgMinTrendSlopeTicks" settings={settings} updateField={updateField} />
          <NumberField label="FVG VWAP Distance" field="fvgMaxVwapDistanceTicks" settings={settings} updateField={updateField} />
          <NumberField label="FVG Entry Extension" field="fvgMaxEntryExtensionTicks" settings={settings} updateField={updateField} />
          <TextField label="LIQREC Sources" field="liquidityReclaimSourceCodes" settings={settings} updateField={updateField} />
          <NumberField label="LIQREC Start Minute" field="liquidityReclaimStartMinute" settings={settings} updateField={updateField} />
          <NumberField label="LIQREC End Minute" field="liquidityReclaimEndMinute" settings={settings} updateField={updateField} />
          <NumberField label="LIQREC Max Contracts" field="liquidityReclaimMaxContracts" settings={settings} updateField={updateField} />
          <SelectField
            label="FVG Source Mode"
            field="fvgSourceMode"
            settings={settings}
            updateField={updateField}
            options={[
              ["NONE", "None"],
              ["PRIOR_LEVEL_BREAK", "Prior Level Break"],
              ["ORB_BREAK", "ORB Break"],
              ["SWEEP_DISPLACEMENT", "Sweep Displacement"],
              ["VWAP_TREND_RECLAIM", "VWAP Trend Reclaim"],
              ["HTF_BREAKOUT", "HTF Breakout"],
              ["ANY_CONTEXT", "Any Context"]
            ]}
          />
          <NumberField label="FVG Source Bars" field="fvgSourceRangeBars" settings={settings} updateField={updateField} />
          <NumberField label="FVG Source Break" field="fvgMinSourceBreakTicks" settings={settings} updateField={updateField} />
          <NumberField label="MRVWAP Min Distance" field="meanReversionMinDistanceTicks" settings={settings} updateField={updateField} />
          <NumberField label="Oversold RSI" field="meanReversionOversoldRsi" settings={settings} updateField={updateField} />
          <NumberField label="Overbought RSI" field="meanReversionOverboughtRsi" settings={settings} updateField={updateField} />
          <NumberField label="Minimum Reward/Risk" field="minRewardRisk" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Close Momentum Min Move" field="closeMomentumMinMoveTicks" settings={settings} updateField={updateField} />
          <NumberField label="Close Momentum Volume" field="closeMomentumVolumeRatio" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Close Momentum Reward/Risk" field="closeMomentumRewardRisk" settings={settings} updateField={updateField} step="0.05" />
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
          <ToggleField label="MCL EIA Longs" field="allowMclEiaLongs" settings={settings} updateField={updateField} />
          <ToggleField label="MCL EIA Shorts" field="allowMclEiaShorts" settings={settings} updateField={updateField} />
          <NumberField label="MCL EIA Range Start" field="mclEiaRangeStartMinute" settings={settings} updateField={updateField} />
          <NumberField label="MCL EIA Range End" field="mclEiaRangeEndMinute" settings={settings} updateField={updateField} />
          <NumberField label="MCL EIA Start" field="mclEiaStartMinute" settings={settings} updateField={updateField} />
          <NumberField label="MCL EIA End" field="mclEiaEndMinute" settings={settings} updateField={updateField} />
          <NumberField label="MCL EIA Buffer" field="mclEiaBreakoutBufferTicks" settings={settings} updateField={updateField} step="0.25" />
          <NumberField label="MCL EIA Stop Ticks" field="mclEiaStopTicks" settings={settings} updateField={updateField} step="0.25" />
          <NumberField label="MCL EIA Reward/Risk" field="mclEiaRewardRisk" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="MCL EIA Volume" field="mclEiaMinVolumeRatio" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="MCL EIA Body %" field="mclEiaMinBodyPct" settings={settings} updateField={updateField} />
          <NumberField label="MCL EIA Max Hold" field="mclEiaMaxHoldBars" settings={settings} updateField={updateField} />
          <ToggleField label="MCL Crude Open Longs" field="allowMclCrudeOpenLongs" settings={settings} updateField={updateField} />
          <ToggleField label="MCL Crude Open Shorts" field="allowMclCrudeOpenShorts" settings={settings} updateField={updateField} />
          <NumberField label="MCL Open Range Start" field="mclCrudeOpenRangeStartMinute" settings={settings} updateField={updateField} />
          <NumberField label="MCL Open Range End" field="mclCrudeOpenRangeEndMinute" settings={settings} updateField={updateField} />
          <NumberField label="MCL Open Start" field="mclCrudeOpenStartMinute" settings={settings} updateField={updateField} />
          <NumberField label="MCL Open End" field="mclCrudeOpenEndMinute" settings={settings} updateField={updateField} />
          <NumberField label="MCL Open Buffer" field="mclCrudeOpenBreakoutBufferTicks" settings={settings} updateField={updateField} step="0.25" />
          <NumberField label="MCL Open Stop Ticks" field="mclCrudeOpenStopTicks" settings={settings} updateField={updateField} step="0.25" />
          <NumberField label="MCL Open Reward/Risk" field="mclCrudeOpenRewardRisk" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="MCL Open Volume" field="mclCrudeOpenMinVolumeRatio" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="MCL Open Body %" field="mclCrudeOpenMinBodyPct" settings={settings} updateField={updateField} />
          <NumberField label="MCL Open Max Hold" field="mclCrudeOpenMaxHoldBars" settings={settings} updateField={updateField} />
          <ToggleField label="MYM Index Longs" field="allowMymIndexConfirmationLongs" settings={settings} updateField={updateField} />
          <ToggleField label="MYM Index Shorts" field="allowMymIndexConfirmationShorts" settings={settings} updateField={updateField} />
          <NumberField label="MYM Index Start" field="mymIndexConfirmationStartMinute" settings={settings} updateField={updateField} />
          <NumberField label="MYM Index End" field="mymIndexConfirmationEndMinute" settings={settings} updateField={updateField} />
          <NumberField label="MYM Index Bucket" field="mymIndexConfirmationBucketMinutes" settings={settings} updateField={updateField} />
          <NumberField label="MYM Index Lookback" field="mymIndexConfirmationLookbackBars" settings={settings} updateField={updateField} />
          <NumberField label="MYM Index Max Risk" field="mymIndexConfirmationMaxRiskTicks" settings={settings} updateField={updateField} />
          <NumberField label="MYM Index Reward/Risk" field="mymIndexConfirmationRewardRisk" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="MYM Index Volume" field="mymIndexConfirmationMinVolumeRatio" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="MYM Index Body %" field="mymIndexConfirmationMinBodyPct" settings={settings} updateField={updateField} />
          <NumberField label="MYM Index Slope" field="mymIndexConfirmationMinTrendSlopeTicks" settings={settings} updateField={updateField} step="0.25" />
          <NumberField label="MYM Index Max Hold" field="mymIndexConfirmationMaxHoldBars" settings={settings} updateField={updateField} />
          <ToggleField label="MYM ORB2 Longs" field="allowMymOrbRetestLongs" settings={settings} updateField={updateField} />
          <ToggleField label="MYM ORB2 Shorts" field="allowMymOrbRetestShorts" settings={settings} updateField={updateField} />
          <NumberField label="MYM ORB2 Start" field="mymOrbRetestStartMinute" settings={settings} updateField={updateField} />
          <NumberField label="MYM ORB2 End" field="mymOrbRetestEndMinute" settings={settings} updateField={updateField} />
          <NumberField label="MYM ORB2 Buffer" field="mymOrbRetestBreakoutBufferTicks" settings={settings} updateField={updateField} step="0.25" />
          <NumberField label="MYM ORB2 Retest" field="mymOrbRetestRetestTicks" settings={settings} updateField={updateField} step="0.25" />
          <NumberField label="MYM ORB2 Max Risk" field="mymOrbRetestMaxRiskTicks" settings={settings} updateField={updateField} />
          <NumberField label="MYM ORB2 Reward/Risk" field="mymOrbRetestRewardRisk" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="MYM ORB2 Volume" field="mymOrbRetestMinVolumeRatio" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="MYM ORB2 Body %" field="mymOrbRetestMinBodyPct" settings={settings} updateField={updateField} />
          <NumberField label="MYM ORB2 Max Hold" field="mymOrbRetestMaxHoldBars" settings={settings} updateField={updateField} />
          <ToggleField label="MYM Breadth Fade Longs" field="allowMymBreadthLongs" settings={settings} updateField={updateField} />
          <ToggleField label="MYM Breadth Fade Shorts" field="allowMymBreadthShorts" settings={settings} updateField={updateField} />
          <NumberField label="MYM Breadth Start" field="mymBreadthStartMinute" settings={settings} updateField={updateField} />
          <NumberField label="MYM Breadth End" field="mymBreadthEndMinute" settings={settings} updateField={updateField} />
          <NumberField label="MYM Breadth Bucket" field="mymBreadthBucketMinutes" settings={settings} updateField={updateField} />
          <NumberField label="MYM Breadth Lookback" field="mymBreadthLookbackBars" settings={settings} updateField={updateField} />
          <NumberField label="MYM Breadth Align Count" field="mymBreadthMinAlignedMarkets" settings={settings} updateField={updateField} />
          <NumberField label="MYM Breadth Max Risk" field="mymBreadthMaxRiskTicks" settings={settings} updateField={updateField} />
          <NumberField label="MYM Breadth Reward/Risk" field="mymBreadthRewardRisk" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="MYM Breadth Volume" field="mymBreadthMinVolumeRatio" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="MYM Breadth Body %" field="mymBreadthMinBodyPct" settings={settings} updateField={updateField} />
          <NumberField label="MYM Breadth Slope" field="mymBreadthMinTrendSlopeTicks" settings={settings} updateField={updateField} step="0.25" />
          <NumberField label="MYM Breadth Max Hold" field="mymBreadthMaxHoldBars" settings={settings} updateField={updateField} />
          <ToggleField label="MCL Trend Fade Longs" field="allowMclTrendLongs" settings={settings} updateField={updateField} />
          <ToggleField label="MCL Trend Fade Shorts" field="allowMclTrendShorts" settings={settings} updateField={updateField} />
          <NumberField label="MCL Trend Start" field="mclTrendStartMinute" settings={settings} updateField={updateField} />
          <NumberField label="MCL Trend End" field="mclTrendEndMinute" settings={settings} updateField={updateField} />
          <NumberField label="MCL Trend Bucket" field="mclTrendBucketMinutes" settings={settings} updateField={updateField} />
          <NumberField label="MCL Trend Lookback" field="mclTrendLookbackBars" settings={settings} updateField={updateField} />
          <NumberField label="MCL Trend Buffer" field="mclTrendBreakoutBufferTicks" settings={settings} updateField={updateField} step="0.25" />
          <NumberField label="MCL Trend Open Move" field="mclTrendMinOpenMoveTicks" settings={settings} updateField={updateField} />
          <NumberField label="MCL Trend Max Risk" field="mclTrendMaxRiskTicks" settings={settings} updateField={updateField} />
          <NumberField label="MCL Trend Reward/Risk" field="mclTrendRewardRisk" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="MCL Trend Volume" field="mclTrendMinVolumeRatio" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="MCL Trend Body %" field="mclTrendMinBodyPct" settings={settings} updateField={updateField} />
          <NumberField label="MCL Trend Slope" field="mclTrendMinTrendSlopeTicks" settings={settings} updateField={updateField} step="0.25" />
          <NumberField label="MCL Trend Max Hold" field="mclTrendMaxHoldBars" settings={settings} updateField={updateField} />
          <NumberField label="Managed Breakeven R" field="managedStopBreakevenTriggerR" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Managed Trail R" field="managedStopTrailTriggerR" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Managed Trail Distance R" field="managedStopTrailDistanceR" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Managed Min Trail Ticks" field="managedStopMinTrailTicks" settings={settings} updateField={updateField} />
          <ToggleField label="Managed Giveback Exit" field="enableManagedGivebackExit" settings={settings} updateField={updateField} />
          <NumberField label="Giveback Trigger R" field="managedGivebackTriggerR" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Giveback Distance R" field="managedGivebackR" settings={settings} updateField={updateField} step="0.05" />
          <NumberField label="Giveback Min Bars" field="managedGivebackMinBars" settings={settings} updateField={updateField} />
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
    ifvg: { ...DEFAULT_SETTINGS.ifvg, ...(data?.ifvg || {}) },
    closeMomentum: { ...DEFAULT_SETTINGS.closeMomentum, ...(data?.closeMomentum || {}) },
    afternoonContinuation: { ...DEFAULT_SETTINGS.afternoonContinuation, ...(data?.afternoonContinuation || {}) },
    marketIntradayMomentum: { ...DEFAULT_SETTINGS.marketIntradayMomentum, ...(data?.marketIntradayMomentum || {}) },
    keltnerScalp: { ...DEFAULT_SETTINGS.keltnerScalp, ...(data?.keltnerScalp || {}) },
    keltnerReversion: { ...DEFAULT_SETTINGS.keltnerReversion, ...(data?.keltnerReversion || {}) },
    microScalp: { ...DEFAULT_SETTINGS.microScalp, ...(data?.microScalp || {}) },
    mclEiaContinuation: { ...DEFAULT_SETTINGS.mclEiaContinuation, ...(data?.mclEiaContinuation || {}) },
    mclCrudeSessionOpen: { ...DEFAULT_SETTINGS.mclCrudeSessionOpen, ...(data?.mclCrudeSessionOpen || {}) },
    mymIndexConfirmation: { ...DEFAULT_SETTINGS.mymIndexConfirmation, ...(data?.mymIndexConfirmation || {}) },
    mymOrbRetest: { ...DEFAULT_SETTINGS.mymOrbRetest, ...(data?.mymOrbRetest || {}) },
    mymBreadthConfirmation: { ...DEFAULT_SETTINGS.mymBreadthConfirmation, ...(data?.mymBreadthConfirmation || {}) },
    mclTrendContinuation: { ...DEFAULT_SETTINGS.mclTrendContinuation, ...(data?.mclTrendContinuation || {}) },
    liquidityReclaim: { ...DEFAULT_SETTINGS.liquidityReclaim, ...(data?.liquidityReclaim || {}) },
  };
}

function moduleMaxTrades(key) {
  if (CUSTOM_MODULE_CAPS[key]) return String(CUSTOM_MODULE_CAPS[key]);
  return HIGH_CAP_MODULES.has(key) ? "20" : "5";
}

function buildInstrumentOptions(apiInstruments = []) {
  const bySymbol = new Map(INSTRUMENT_FALLBACKS.map((instrument) => [instrument.symbol, { ...instrument }]));
  const apiList = Array.isArray(apiInstruments) ? apiInstruments : [];
  apiList.forEach((instrument) => {
    const symbol = String(instrument?.symbol || "").trim().toUpperCase();
    if (!symbol) return;
    bySymbol.set(symbol, { ...(bySymbol.get(symbol) || {}), ...instrument, symbol });
  });

  const orderedSymbols = ["MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL", "GC"];
  const ordered = [];
  const seen = new Set();
  orderedSymbols.forEach((symbol) => {
    const instrument = bySymbol.get(symbol);
    if (instrument && !seen.has(symbol)) {
      ordered.push(instrument);
      seen.add(symbol);
    }
  });
  apiList.forEach((instrument) => {
    const symbol = String(instrument?.symbol || "").trim().toUpperCase();
    if (!symbol || seen.has(symbol)) return;
    ordered.push(bySymbol.get(symbol));
    seen.add(symbol);
  });
  return ordered.length ? ordered : INSTRUMENT_FALLBACKS;
}

function mergeStrategyPresets(apiPresets = []) {
  const canonical = new Set(CANONICAL_STRATEGY_PRESETS.map((preset) => preset.name));
  const byName = new Map(CANONICAL_STRATEGY_PRESETS.map((preset) => [preset.name, preset]));
  const presets = Array.isArray(apiPresets) ? apiPresets : [];
  presets.forEach((preset) => {
    const name = String(preset?.name || "").trim();
    if (!canonical.has(name)) return;
    byName.set(name, { ...preset, name, label: byName.get(name)?.label || preset.label || name });
  });
  return Array.from(byName.values());
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

function TextField({ label, field, settings, updateField, disabled = false }) {
  return (
    <Field label={label} className="col-12 col-md-6">
      <input
        type="text"
        value={settings[field] ?? ""}
        onChange={(event) => updateField(field, event.target.value)}
        className="form-control app-input"
        disabled={disabled}
      />
    </Field>
  );
}

function SelectField({ label, field, settings, updateField, options, disabled = false }) {
  return (
    <Field label={label} className="col-12 col-sm-6 col-xl-3">
      <select
        value={settings[field] ?? ""}
        onChange={(event) => updateField(field, event.target.value)}
        className="form-select app-input"
        disabled={disabled}
      >
        {options.map(([value, optionLabel]) => (
          <option key={value} value={value}>
            {optionLabel}
          </option>
        ))}
      </select>
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
