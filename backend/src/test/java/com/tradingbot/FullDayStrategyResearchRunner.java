package com.tradingbot;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class FullDayStrategyResearchRunner {
	private static final String SYMBOLS = System.getProperty("fullDay.symbols", "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL");
	private static final String[] SYMBOL_LIST = new String[] { "MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL" };
	private static final String START_DATE = System.getProperty("fullDay.startDate", "2025-05-01");
	private static final String END_DATE = System.getProperty("fullDay.endDate", "2026-06-04");
	private static final String PROFILE = "TOPSTEP_50K";
	private static final String BASE_PRESET = "bestbiasfree";
	private static final String WIP_PRESET = "wip";
	private static final String WIP_SLOT = FuturesManager.strategyPresetSlot(WIP_PRESET);
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private interface ScenarioConfig {
		void apply(String symbol, FuturesManager.FuturesStrategySettings settings);
	}

	private static final class Scenario {
		final String family;
		final String name;
		final String rationale;
		final String[] codes;
		final ScenarioConfig config;

		Scenario(String family, String name, String rationale, String[] codes, ScenarioConfig config) {
			this.family = family;
			this.name = name;
			this.rationale = rationale;
			this.codes = codes;
			this.config = config;
		}
	}

	private static final class RunSummary {
		int id;
		String family;
		String name;
		String mode;
		String rationale;
		String[] codes;
		int trades;
		double pnl;
		double winRate;
		double profitFactor;
		double maxDrawdownPct;
		double maxIntradayLoss;
		double maxAggregateMae;
		int dailyLossBreaches;
		int trailingDrawdownBreaches;
		int maeBreaches;
		int ruleViolation;
		int overlapRejections;
		int exposureRejections;
		int riskRejections;
		int familyTrades;
		double familyPnl;
		double familyWinRate;
	}

	public static void main(String[] args) throws Exception {
		Path backendDir = Paths.get("").toAbsolutePath();
		Path sourceDb = args.length > 0 && !args[0].trim().isEmpty()
			? Paths.get(args[0]).toAbsolutePath()
			: backendDir.resolve("tradingbot.db").toAbsolutePath();
		Path futuresDataDir = args.length > 1 && !args[1].trim().isEmpty()
			? Paths.get(args[1]).toAbsolutePath()
			: backendDir.resolve("market_data/futures").toAbsolutePath();
		String label = args.length > 2 && !args[2].trim().isEmpty() ? args[2].trim() : "full-day-strategy-research";

		Path outputDir = backendDir.resolve("target/full-day-strategy-research");
		Files.createDirectories(outputDir);
		Path analysisDb = outputDir.resolve("tradingbot-" + safeFileName(label) + "-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		copyWalIfPresent(sourceDb, analysisDb);

		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", futuresDataDir.toString());
		FuturesManager.initializeStore();
		resetAnalysisResults();

		System.out.println("ANALYSIS_DB=" + analysisDb);
		System.out.println("FUTURES_DATA_DIR=" + futuresDataDir);
		System.out.println("WINDOW=" + START_DATE + ".." + END_DATE + " profile=" + PROFILE + " preset=" + WIP_PRESET + " dtm=true qualitativeRisk=true");
		printLevel2Coverage();

		List<RunSummary> allRuns = new ArrayList<RunSummary>();
		List<RunSummary> additiveRuns = new ArrayList<RunSummary>();
		RunSummary baseline = runPortfolio("BASELINE", "bestbiasfree_control", "solo", BASE_PRESET, new String[] { }, "Current main config control.");
		allRuns.add(baseline);
		printSummary(baseline);

		List<Scenario> scenarios = buildScenarios();
		for (Scenario scenario : scenarios) {
			if (!shouldRunFamily(scenario.family)) {
				continue;
			}
			resetWipFromBase();
			applySoloScenario(scenario);
			RunSummary solo = runPortfolio(scenario.family, scenario.name, "solo", WIP_PRESET, scenario.codes, scenario.rationale);
			allRuns.add(solo);
			printSummary(solo);
			printStrategyBreakdown(solo.id);
			printSymbolBreakdown(solo.id, scenario.codes);
			printFailureShape(solo.id, scenario.codes);

			if (clearsSoloGate(solo)) {
				resetWipFromBase();
				applyAdditiveScenario(scenario);
				RunSummary additive = runPortfolio(scenario.family, "bestbiasfree_plus_" + scenario.name, "additive", WIP_PRESET, scenario.codes, "Solo gate cleared; additive test against current Best Bias Free inside copied DB.");
				allRuns.add(additive);
				additiveRuns.add(additive);
				printSummary(additive);
				printStrategyBreakdown(additive.id);
				printSymbolBreakdown(additive.id, scenario.codes);
				System.out.println("ADDITIVE_DELTA family=" + scenario.family
					+ " name=" + scenario.name
					+ " pnl=" + round(additive.pnl - baseline.pnl)
					+ " trades=" + (additive.trades - baseline.trades)
					+ " familyPnl=" + round(additive.familyPnl)
					+ " familyTrades=" + additive.familyTrades);
			} else {
				System.out.println("ADDITIVE_SKIPPED family=" + scenario.family
					+ " name=" + scenario.name
					+ " reason=solo gate not cleared"
					+ " gate=familyPnl>0,familyTrades>=50,PF>=1.05,noRuleViolation");
			}
		}

		Path reportPath = outputDir.resolve("full-day-strategy-research-" + RUN_TAG + ".md");
		Files.write(reportPath, buildReport(analysisDb, allRuns, additiveRuns).getBytes(StandardCharsets.UTF_8));
		System.out.println("REPORT=" + reportPath);
		System.out.println("ANALYSIS_DB=" + analysisDb);
	}

	private static List<Scenario> buildScenarios() {
		List<Scenario> scenarios = new ArrayList<Scenario>();
		scenarios.add(new Scenario("LIQREC", "liqrec_source_stack_all_day", "Source-stack liquidity reclaim benchmark: price structure produces setup, DTM/order-flow manages the trade.", new String[] { "LIQREC" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				configureLiqrec(settings, true, true, 570, 930, "MNQ".equals(symbol) ? 30 : 0);
			}
		}));
		scenarios.add(new Scenario("LIQREC", "liqrec_no_mcl_m2k", "Remove the contracts that prior notes flagged as unstable/noisy for LIQREC.", new String[] { "LIQREC" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				boolean enabled = !"MCL".equals(symbol) && !"M2K".equals(symbol);
				configureLiqrec(settings, enabled, enabled, 570, 930, "MNQ".equals(symbol) ? 30 : 0);
			}
		}));
		scenarios.add(new Scenario("LIQREC", "liqrec_after_11_quality", "Trade liquidity reclaims only after the open has formed cleaner intraday structure.", new String[] { "LIQREC" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				boolean enabled = !"MCL".equals(symbol);
				configureLiqrec(settings, enabled, enabled, 660, 930, "MNQ".equals(symbol) ? 24 : 0);
			}
		}));
		scenarios.add(new Scenario("LIQREC", "liqrec_equity_metal_stack", "Keep broad index/metal contracts and exclude crude from source-stack repair.", new String[] { "LIQREC" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				boolean enabled = Arrays.asList("MES", "MNQ", "NQ", "MGC", "ES", "MYM").contains(symbol);
				configureLiqrec(settings, enabled, enabled, 570, 930, "MNQ".equals(symbol) ? 28 : 0);
			}
		}));

		scenarios.add(new Scenario("VWAP_TREND", "vwap_ema_pullback_broad", "Manual futures playbook: trend above/below VWAP and EMA stack, pullback, continuation candle.", new String[] { "TLAD", "MSCALP", "VWAP", "VRCL" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				configureVwapTrend(settings, 0.55, 0.50, 18.0, 1.0, false);
			}
		}));
		scenarios.add(new Scenario("VWAP_TREND", "vwap_ema_pullback_htf", "Add higher-timeframe alignment to avoid countertrend chop.", new String[] { "TLAD", "MSCALP", "VWAP", "VRCL" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				configureVwapTrend(settings, 0.70, 0.65, 18.0, 1.2, true);
			}
		}));
		scenarios.add(new Scenario("VWAP_TREND", "vwap_ema_pullback_no_crude", "Exclude crude and require stronger participation/body quality.", new String[] { "TLAD", "MSCALP", "VWAP", "VRCL" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				if (!"MCL".equals(symbol)) {
					configureVwapTrend(settings, 0.85, 0.75, 16.0, 1.4, true);
				}
			}
		}));
		scenarios.add(new Scenario("VWAP_TREND", "vwap_ema_pullback_micros_dense", "Dense micro-contract pullback ladder for the 500+ trade-count target while keeping mini exposure bounded.", new String[] { "TLAD", "MSCALP", "VWAP", "VRCL" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				if ("MES".equals(symbol) || "MNQ".equals(symbol) || "MGC".equals(symbol) || "M2K".equals(symbol) || "MYM".equals(symbol) || "MCL".equals(symbol)) {
					configureVwapTrend(settings, 0.50, 0.45, 14.0, 0.8, false);
					settings.trendLadder.maxTradesPerDay = 18;
					settings.microScalp.maxTradesPerDay = 14;
				}
			}
		}));

		scenarios.add(new Scenario("BREAKOUT_RETEST", "breakout_retest_broad", "Prior levels/FVG/compression retests: accepted break first, then enter on reclaim/hold instead of chasing.", new String[] { "PDB", "FVG", "IFVG", "RCB", "SWEEP", "SWEEP2" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				configureBreakoutRetest(settings, 0.75, 42.0, false, "NONE");
			}
		}));
		scenarios.add(new Scenario("BREAKOUT_RETEST", "breakout_retest_core_quality", "Require stronger displacement and EMA/VWAP alignment before accepting the retest.", new String[] { "PDB", "FVG", "IFVG", "RCB", "SWEEP", "SWEEP2" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				configureBreakoutRetest(settings, 0.85, 36.0, true, "ANY_CONTEXT");
			}
		}));
		scenarios.add(new Scenario("BREAKOUT_RETEST", "breakout_retest_no_crude_tight", "Exclude crude and tighten FVG/retest depth to avoid late failed breaks.", new String[] { "PDB", "FVG", "IFVG", "RCB", "SWEEP", "SWEEP2" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				if (!"MCL".equals(symbol)) {
					configureBreakoutRetest(settings, 0.95, 32.0, true, "ANY_CONTEXT");
					settings.fvgMaxRetestDepthPct = 0.70;
					settings.fvgMaxEntryExtensionTicks = 18.0;
				}
			}
		}));
		scenarios.add(new Scenario("BREAKOUT_RETEST", "breakout_retest_htf_runners", "Use higher-timeframe confirmation and longer holds for continuation days.", new String[] { "PDB", "FVG", "IFVG", "RCB", "SWEEP", "SWEEP2" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				configureBreakoutRetest(settings, 0.80, 44.0, true, "HTF_BREAKOUT");
				settings.requireHigherTimeframeGuard = true;
				settings.fvgMaxHoldBars = 28;
				settings.priorDayBreakoutRewardRisk = 1.20;
				settings.rangeCompressionRewardRisk = 0.95;
			}
		}));

		scenarios.add(new Scenario("VALUE_REVERSION", "value_vwap_reclaim_broad", "Mean-reversion/value-area playbook: failed auction away from value, reclaim with VWAP/EMA confirmation.", new String[] { "VPB", "MRVWAP", "KREV", "SHDW" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				configureValueReversion(settings, 0.65, 36.0, false);
			}
		}));
		scenarios.add(new Scenario("VALUE_REVERSION", "value_vwap_reclaim_participation", "Add stronger volume participation so reversal is not just a weak drift back to value.", new String[] { "VPB", "MRVWAP", "KREV", "SHDW" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				configureValueReversion(settings, 0.85, 32.0, true);
			}
		}));
		scenarios.add(new Scenario("VALUE_REVERSION", "value_vwap_reclaim_no_mcl", "Exclude crude and tighten risk geometry around value-area boundaries.", new String[] { "VPB", "MRVWAP", "KREV", "SHDW" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				if (!"MCL".equals(symbol)) {
					configureValueReversion(settings, 0.80, 28.0, true);
					settings.valueAreaReclaimTicks = 2.0;
					settings.keltnerRewardRisk = 0.95;
				}
			}
		}));
		scenarios.add(new Scenario("VALUE_REVERSION", "value_vwap_reclaim_micro_repair", "Micro-contract value reclaim with shadow entries, accepting more trades but capped risk.", new String[] { "VPB", "MRVWAP", "KREV", "SHDW" }, new ScenarioConfig() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				disableAllStrategies(settings);
				if ("MES".equals(symbol) || "MNQ".equals(symbol) || "MGC".equals(symbol) || "M2K".equals(symbol) || "MYM".equals(symbol)) {
					configureValueReversion(settings, 0.60, 26.0, false);
					settings.valueAreaReclaim.maxTradesPerDay = 8;
					settings.microShadow.maxTradesPerDay = 8;
				}
			}
		}));
		return scenarios;
	}

	private static void configureLiqrec(FuturesManager.FuturesStrategySettings settings, boolean enabled, boolean duplicates, int startMinute, int endMinute, int maxContracts) {
		settings.liquidityReclaim.enabled = enabled;
		settings.liquidityReclaim.maxTradesPerDay = 50;
		settings.liquidityReclaimSourceCodes = "FVG,VWAP,AFT,SWEEP,PDB,KREV,SHDW,VPB";
		settings.liquidityReclaimAllowDuplicates = duplicates;
		settings.liquidityReclaimStartMinute = startMinute;
		settings.liquidityReclaimEndMinute = endMinute;
		settings.liquidityReclaimMaxContracts = maxContracts;
	}

	private static void configureVwapTrend(FuturesManager.FuturesStrategySettings settings, double trendVolume, double scalpVolume, double maxRiskTicks, double minTrendSlopeTicks, boolean htfGuard) {
		settings.requireHigherTimeframeGuard = htfGuard;
		settings.vwapRequireHigherTimeframeGuard = htfGuard;
		settings.vwapPullback.enabled = true;
		settings.vwapPullback.maxTradesPerDay = 6;
		settings.vwapMinVolumeRatio = Math.max(0.45, trendVolume);
		settings.vwapMinTrendSlopeTicks = Math.max(0.5, minTrendSlopeTicks);
		settings.vwapMaxDistanceTicks = 48.0;
		settings.vwapMaxRiskTicks = Math.max(16.0, maxRiskTicks + 8.0);
		settings.vwapStartMinute = 570;
		settings.vwapEndMinute = 920;
		settings.vwapReclaim.enabled = true;
		settings.vwapReclaim.maxTradesPerDay = 6;
		settings.vwapReclaimMinVolumeRatio = Math.max(0.55, trendVolume);
		settings.vwapReclaimMaxRiskTicks = Math.max(16.0, maxRiskTicks + 4.0);
		settings.vwapReclaimRewardRisk = 0.95;
		settings.vwapReclaimBucketMinutes = 20;
		settings.trendLadder.enabled = true;
		settings.trendLadder.maxTradesPerDay = 12;
		settings.trendLadderStartMinute = 570;
		settings.trendLadderEndMinute = 920;
		settings.trendLadderBucketMinutes = 10;
		settings.trendLadderMinVolumeRatio = trendVolume;
		settings.trendLadderMaxRiskTicks = maxRiskTicks;
		settings.trendLadderRewardRisk = 0.80;
		settings.trendLadderPullbackTicks = 8.0;
		settings.trendLadderMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.trendLadderMaxHoldBars = 16;
		settings.microScalp.enabled = true;
		settings.microScalp.maxTradesPerDay = 10;
		settings.microScalpStartMinute = 570;
		settings.microScalpEndMinute = 920;
		settings.microScalpBucketMinutes = 12;
		settings.microScalpMinVolumeRatio = scalpVolume;
		settings.microScalpMaxRiskTicks = Math.min(18.0, maxRiskTicks);
		settings.microScalpRewardRisk = 0.80;
		settings.microScalpMinBodyPct = 18.0;
		settings.microScalpMinTrendSlopeTicks = Math.max(0.35, minTrendSlopeTicks * 0.5);
		settings.microScalpMaxHoldBars = 10;
	}

	private static void configureBreakoutRetest(FuturesManager.FuturesStrategySettings settings, double volumeRatio, double maxRiskTicks, boolean coreQuality, String fvgSourceMode) {
		settings.requireHigherTimeframeGuard = coreQuality;
		settings.relaxPatternHardWindows = true;
		settings.sweep.enabled = true;
		settings.sweep.maxTradesPerDay = 4;
		settings.enableEarlySweep = true;
		settings.enableLateSweep = true;
		settings.enableSweepSecondChance = true;
		settings.earlySweepReclaimTicks = 6.0;
		settings.lateSweepReclaimTicks = 8.0;
		settings.sweepCloseLocation = 0.60;
		settings.lateSweepCloseLocation = 0.50;
		settings.priorDayBreakout.enabled = true;
		settings.priorDayBreakout.maxTradesPerDay = 8;
		settings.priorDayBreakoutStartMinute = 570;
		settings.priorDayBreakoutEndMinute = 920;
		settings.priorDayBreakoutBucketMinutes = 25;
		settings.priorDayBreakoutMinBreakTicks = 6.0;
		settings.priorDayBreakoutRetestTicks = 6.0;
		settings.priorDayBreakoutMinVolumeRatio = volumeRatio;
		settings.priorDayBreakoutMaxRiskTicks = maxRiskTicks;
		settings.priorDayBreakoutRewardRisk = 1.05;
		settings.fvg.enabled = true;
		settings.fvg.maxTradesPerDay = 8;
		settings.ifvg.enabled = true;
		settings.ifvg.maxTradesPerDay = 4;
		settings.fvgStartMinute = 570;
		settings.fvgEndMinute = 920;
		settings.fvgMinWidthTicks = 4.0;
		settings.fvgMinVolumeRatio = Math.max(0.65, volumeRatio);
		settings.fvgMinRiskTicks = 12.0;
		settings.fvgMaxRiskTicks = maxRiskTicks;
		settings.fvgRewardRisk = 1.15;
		settings.fvgMaxHoldBars = 22;
		settings.fvgRetestBars = 10;
		settings.fvgRequireCoreQuality = coreQuality;
		settings.fvgRequireEmaStack = coreQuality;
		settings.fvgRequireHigherTimeframeGuard = false;
		settings.fvgMinImpulseBodyPct = coreQuality ? 45.0 : 25.0;
		settings.fvgMinReclaimCloseLocation = coreQuality ? 0.70 : 0.58;
		settings.fvgMaxRetestDepthPct = coreQuality ? 0.85 : 0.0;
		settings.fvgMaxVwapDistanceTicks = 96.0;
		settings.fvgMaxEntryExtensionTicks = coreQuality ? 28.0 : 0.0;
		settings.fvgMinTrendSlopeTicks = coreQuality ? 0.75 : 0.0;
		settings.fvgSourceMode = fvgSourceMode;
		settings.fvgSourceRangeBars = 24;
		settings.fvgMinSourceBreakTicks = coreQuality ? 3.0 : 0.0;
		settings.rangeCompressionBreakout.enabled = true;
		settings.rangeCompressionBreakout.maxTradesPerDay = 8;
		settings.rangeCompressionStartMinute = 570;
		settings.rangeCompressionEndMinute = 920;
		settings.rangeCompressionBucketMinutes = 10;
		settings.rangeCompressionBars = 5;
		settings.rangeCompressionMaxAtrRatio = 0.70;
		settings.rangeCompressionMinVolumeRatio = Math.max(0.75, volumeRatio);
		settings.rangeCompressionMaxRiskTicks = Math.min(24.0, maxRiskTicks);
		settings.rangeCompressionRewardRisk = 0.85;
		settings.rangeCompressionMinBodyPct = coreQuality ? 22.0 : 16.0;
		settings.rangeCompressionMinTrendSlopeTicks = coreQuality ? 0.50 : 0.20;
		settings.rangeCompressionMaxHoldBars = 12;
	}

	private static void configureValueReversion(FuturesManager.FuturesStrategySettings settings, double volumeRatio, double maxRiskTicks, boolean htfGuard) {
		settings.requireHigherTimeframeGuard = htfGuard;
		settings.vwapMeanReversion.enabled = true;
		settings.vwapMeanReversion.maxTradesPerDay = 4;
		settings.meanReversionMinDistanceTicks = Math.max(24.0, maxRiskTicks);
		settings.meanReversionOversoldRsi = 30.0;
		settings.meanReversionOverboughtRsi = 70.0;
		settings.minRewardRisk = 1.05;
		settings.keltnerReversion.enabled = true;
		settings.keltnerReversion.maxTradesPerDay = 8;
		settings.keltnerAtrMultiplier = 1.3;
		settings.keltnerMinVolumeRatio = volumeRatio;
		settings.keltnerMaxRiskTicks = Math.min(24.0, maxRiskTicks);
		settings.keltnerRewardRisk = 0.85;
		settings.keltnerMinBodyPct = 18.0;
		settings.keltnerMinTrendSlopeTicks = 0.25;
		settings.valueAreaReclaim.enabled = true;
		settings.valueAreaReclaim.maxTradesPerDay = 6;
		settings.valueAreaStartMinute = 570;
		settings.valueAreaEndMinute = 920;
		settings.valueAreaBucketMinutes = 30;
		settings.valueAreaPct = 0.70;
		settings.valueAreaReclaimTicks = 3.0;
		settings.valueAreaMinVolumeRatio = volumeRatio;
		settings.valueAreaMaxRiskTicks = maxRiskTicks;
		settings.valueAreaRewardRisk = 0.90;
		settings.valueAreaMaxHoldBars = 30;
		settings.microShadow.enabled = true;
		settings.microShadow.maxTradesPerDay = 5;
		settings.microShadowStartMinute = 570;
		settings.microShadowEndMinute = 920;
		settings.microShadowBucketMinutes = 20;
		settings.microShadowMinVolumeRatio = Math.max(0.55, volumeRatio * 0.75);
		settings.microShadowMaxRiskTicks = Math.min(14.0, maxRiskTicks);
		settings.microShadowRewardRisk = 0.85;
		settings.microShadowSourceCodes = "KREV,VPB,MRVWAP";
	}

	private static void applySoloScenario(Scenario scenario) throws Exception {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
			scenario.config.apply(symbol, settings);
			FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		}
	}

	private static void applyAdditiveScenario(Scenario scenario) throws Exception {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
			scenario.config.apply(symbol, settings);
			FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		}
	}

	private static void resetWipFromBase() {
		String result = FuturesManager.createStrategyPreset(WIP_PRESET, BASE_PRESET);
		if (result == null || !result.contains("\"success\":true")) {
			throw new IllegalStateException("Failed to reset WIP preset from " + BASE_PRESET + ": " + result);
		}
	}

	private static RunSummary runPortfolio(String family, String name, String mode, String preset, String[] codes, String rationale) throws Exception {
		int id = FuturesManager.generatePortfolioBacktest(
			SYMBOLS,
			START_DATE,
			END_DATE,
			50000.0,
			2000.0,
			1000.0,
			700.0,
			50,
			1.24,
			1.0,
			3,
			50,
			5.0,
			true,
			0.0,
			PROFILE,
			preset,
			0,
			true,
			true,
			true
		);
		labelRun(id, family + ":" + mode + ":" + name);
		RunSummary summary = loadSummary(id, codes);
		summary.family = family;
		summary.name = name;
		summary.mode = mode;
		summary.codes = codes;
		summary.rationale = rationale;
		return summary;
	}

	private static boolean clearsSoloGate(RunSummary summary) {
		return summary != null
			&& "solo".equals(summary.mode)
			&& summary.ruleViolation == 0
			&& summary.familyTrades >= 50
			&& summary.familyPnl > 0.0
			&& summary.profitFactor >= 1.05;
	}

	private static RunSummary loadSummary(int id, String[] codes) throws Exception {
		RunSummary summary = new RunSummary();
		summary.id = id;
		Set<String> codeSet = new HashSet<String>(Arrays.asList(codes));
		try (Connection conn = DatabaseManager.getConnection()) {
			try (PreparedStatement stmt = conn.prepareStatement(
					"SELECT totalProfit, winRate, numTrades, profitFactor, maxDrawdownPct, maxIntradayLoss, maxAggregateMae, "
					+ "dailyLossBreaches, trailingDrawdownBreaches, maeBreaches, ruleViolation, overlapRejections, exposureRejections, riskRejections "
					+ "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ?")) {
				stmt.setInt(1, id);
				try (ResultSet rs = stmt.executeQuery()) {
					if (rs.next()) {
						summary.pnl = rs.getDouble("totalProfit");
						summary.winRate = rs.getDouble("winRate");
						summary.trades = rs.getInt("numTrades");
						summary.profitFactor = rs.getDouble("profitFactor");
						summary.maxDrawdownPct = rs.getDouble("maxDrawdownPct");
						summary.maxIntradayLoss = rs.getDouble("maxIntradayLoss");
						summary.maxAggregateMae = rs.getDouble("maxAggregateMae");
						summary.dailyLossBreaches = rs.getInt("dailyLossBreaches");
						summary.trailingDrawdownBreaches = rs.getInt("trailingDrawdownBreaches");
						summary.maeBreaches = rs.getInt("maeBreaches");
						summary.ruleViolation = rs.getInt("ruleViolation");
						summary.overlapRejections = rs.getInt("overlapRejections");
						summary.exposureRejections = rs.getInt("exposureRejections");
						summary.riskRejections = rs.getInt("riskRejections");
					}
				}
			}
			if (!codeSet.isEmpty()) {
				String inList = sqlInList(codeSet);
				try (PreparedStatement stmt = conn.prepareStatement(
						"SELECT COUNT(*) AS trades, COALESCE(SUM(pnl),0) AS pnl, "
						+ "COALESCE(100.0 * SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 0) AS winRate "
						+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND strategyCode IN (" + inList + ")")) {
					stmt.setInt(1, id);
					try (ResultSet rs = stmt.executeQuery()) {
						if (rs.next()) {
							summary.familyTrades = rs.getInt("trades");
							summary.familyPnl = rs.getDouble("pnl");
							summary.familyWinRate = rs.getDouble("winRate");
						}
					}
				}
			}
		}
		return summary;
	}

	private static String sqlInList(Set<String> codes) {
		StringBuilder sql = new StringBuilder();
		int index = 0;
		for (String code : codes) {
			if (index++ > 0) {
				sql.append(",");
			}
			sql.append("'").append(code.replace("'", "''")).append("'");
		}
		return sql.toString();
	}

	private static void labelRun(int id, String label) throws Exception {
		if (id <= 0) {
			return;
		}
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("UPDATE FuturesPortfolioBacktests SET dataSource = COALESCE(dataSource, '') || ? WHERE portfolioBacktestID = ?")) {
			stmt.setString(1, " | FULL_DAY_RESEARCH:" + label);
			stmt.setInt(2, id);
			stmt.executeUpdate();
		}
	}

	private static void printSummary(RunSummary summary) {
		System.out.println("SUMMARY family=" + summary.family
			+ " name=" + summary.name
			+ " mode=" + summary.mode
			+ " id=" + summary.id
			+ " pnl=" + round(summary.pnl)
			+ " trades=" + summary.trades
			+ " win=" + round(summary.winRate)
			+ " pf=" + round(summary.profitFactor)
			+ " dd=" + round(summary.maxDrawdownPct)
			+ " intradayLoss=" + round(summary.maxIntradayLoss)
			+ " aggregateMae=" + round(summary.maxAggregateMae)
			+ " breaches=" + summary.dailyLossBreaches + "/" + summary.trailingDrawdownBreaches + "/" + summary.maeBreaches
			+ " violation=" + summary.ruleViolation
			+ " familyTrades=" + summary.familyTrades
			+ " familyPnl=" + round(summary.familyPnl)
			+ " familyWin=" + round(summary.familyWinRate)
			+ " rejections=" + summary.overlapRejections + "/" + summary.exposureRejections + "/" + summary.riskRejections);
	}

	private static void printStrategyBreakdown(int id) throws Exception {
		if (id <= 0) {
			return;
		}
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT strategyCode, COUNT(*) AS trades, COALESCE(SUM(pnl),0) AS pnl, COALESCE(AVG(pnl),0) AS avgPnl, "
				 + "COALESCE(100.0 * SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0),0) AS winRate "
				 + "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? GROUP BY strategyCode ORDER BY pnl DESC")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					System.out.println("STRATEGY_BREAKDOWN id=" + id
						+ " code=" + rs.getString("strategyCode")
						+ " trades=" + rs.getInt("trades")
						+ " pnl=" + round(rs.getDouble("pnl"))
						+ " avg=" + round(rs.getDouble("avgPnl"))
						+ " win=" + round(rs.getDouble("winRate")));
				}
			}
		}
	}

	private static void printSymbolBreakdown(int id, String[] codes) throws Exception {
		if (id <= 0 || codes == null || codes.length == 0) {
			return;
		}
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT symbol, strategyCode, COUNT(*) AS trades, COALESCE(SUM(pnl),0) AS pnl, COALESCE(AVG(pnl),0) AS avgPnl, "
				 + "COALESCE(100.0 * SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0),0) AS winRate "
				 + "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND strategyCode IN (" + sqlInList(new HashSet<String>(Arrays.asList(codes))) + ") "
				 + "GROUP BY symbol, strategyCode ORDER BY pnl DESC")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					System.out.println("FAMILY_SYMBOL_BREAKDOWN id=" + id
						+ " symbol=" + rs.getString("symbol")
						+ " code=" + rs.getString("strategyCode")
						+ " trades=" + rs.getInt("trades")
						+ " pnl=" + round(rs.getDouble("pnl"))
						+ " avg=" + round(rs.getDouble("avgPnl"))
						+ " win=" + round(rs.getDouble("winRate")));
				}
			}
		}
	}

	private static void printFailureShape(int id, String[] codes) throws Exception {
		if (id <= 0 || codes == null || codes.length == 0) {
			return;
		}
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT exitReason, COUNT(*) AS trades, COALESCE(SUM(pnl),0) AS pnl "
				 + "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND strategyCode IN (" + sqlInList(new HashSet<String>(Arrays.asList(codes))) + ") "
				 + "GROUP BY exitReason ORDER BY pnl ASC LIMIT 8")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					System.out.println("FAILURE_SHAPE id=" + id
						+ " exit=\"" + clean(rs.getString("exitReason")) + "\""
						+ " trades=" + rs.getInt("trades")
						+ " pnl=" + round(rs.getDouble("pnl")));
				}
			}
		}
	}

	private static String buildReport(Path analysisDb, List<RunSummary> allRuns, List<RunSummary> additiveRuns) {
		StringBuilder report = new StringBuilder();
		report.append("# Full-Day Strategy Research\n\n");
		report.append("- Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(" local\n");
		report.append("- Analysis DB copy: `").append(analysisDb).append("`\n");
		report.append("- Range: `").append(START_DATE).append("` to `").append(END_DATE).append("`\n");
		report.append("- Account/risk: `TOPSTEP_50K`, $50k balance, $2k trailing drawdown, $1k daily loss, $700 max risk/trade, DTM `true`, qualitative risk `true`.\n");
		report.append("- Additive gate: family PnL > 0, family trades >= 50, PF >= 1.05, and no funded-rule violation in solo mode.\n\n");
		report.append("## Runs\n\n");
		report.append("| Family | Run | Mode | Total PnL | Total Trades | Win % | PF | DD % | Rule | Family Trades | Family PnL | Family Win % | Rationale |\n");
		report.append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
		for (RunSummary run : allRuns) {
			report.append("| ").append(run.family)
				.append(" | `").append(run.name).append("` | ")
				.append(run.mode).append(" | ")
				.append(money(run.pnl)).append(" | ")
				.append(run.trades).append(" | ")
				.append(round(run.winRate)).append(" | ")
				.append(round(run.profitFactor)).append(" | ")
				.append(round(run.maxDrawdownPct)).append(" | ")
				.append(run.ruleViolation).append(" | ")
				.append(run.familyTrades).append(" | ")
				.append(money(run.familyPnl)).append(" | ")
				.append(round(run.familyWinRate)).append(" | ")
				.append(escapeMarkdown(run.rationale)).append(" |\n");
		}
		report.append("\n");
		if (additiveRuns.isEmpty()) {
			report.append("No family cleared the standalone gate for additive Best Bias Free testing.\n");
		}
		return report.toString();
	}

	private static boolean shouldRunFamily(String family) {
		String filter = System.getProperty("fullDay.families", "").trim();
		if (filter.length() == 0) {
			return true;
		}
		for (String part : filter.split(",")) {
			if (family.equalsIgnoreCase(part.trim())) {
				return true;
			}
		}
		return false;
	}

	private static void disableAllStrategies(FuturesManager.FuturesStrategySettings settings) {
		settings.orb.enabled = false;
		settings.enableOrbRetest = false;
		settings.lateOrbContinuation.enabled = false;
		settings.openingMomentum.enabled = false;
		settings.sweep.enabled = false;
		settings.priorDayBreakout.enabled = false;
		settings.vwapPullback.enabled = false;
		settings.vwapReclaim.enabled = false;
		settings.vwapMeanReversion.enabled = false;
		settings.fvg.enabled = false;
		settings.ifvg.enabled = false;
		settings.closeMomentum.enabled = false;
		settings.afternoonContinuation.enabled = false;
		settings.marketIntradayMomentum.enabled = false;
		settings.keltnerScalp.enabled = false;
		settings.keltnerReversion.enabled = false;
		settings.microScalp.enabled = false;
		settings.microShadow.enabled = false;
		settings.microEcho.enabled = false;
		settings.winnerFollowThrough.enabled = false;
		settings.trendLadder.enabled = false;
		settings.rangeCompressionBreakout.enabled = false;
		settings.valueAreaReclaim.enabled = false;
		settings.mclEiaContinuation.enabled = false;
		settings.mclCrudeSessionOpen.enabled = false;
		settings.mymIndexConfirmation.enabled = false;
		settings.mymOrbRetest.enabled = false;
		settings.mymBreadthConfirmation.enabled = false;
		settings.mclTrendContinuation.enabled = false;
		settings.liquidityReclaim.enabled = false;
		settings.rangeMidpointContinuation.enabled = false;
	}

	private static void resetAnalysisResults() throws Exception {
		try (Connection conn = DatabaseManager.getConnection()) {
			executeIfTableExists(conn, "DELETE FROM FuturesLiveSignalDecisions");
			executeIfTableExists(conn, "DELETE FROM FuturesLiveRiskEvents");
			executeIfTableExists(conn, "DELETE FROM FuturesLiveOrderLedger");
			executeIfTableExists(conn, "DELETE FROM FuturesLiveEngineSessions");
			executeIfTableExists(conn, "DELETE FROM FuturesLiveStrategySnapshots");
			executeIfTableExists(conn, "DELETE FROM FuturesPortfolioBacktestSettings");
			executeIfTableExists(conn, "DELETE FROM FuturesPortfolioTrades");
			executeIfTableExists(conn, "DELETE FROM FuturesPortfolioBacktests");
			executeIfTableExists(conn, "DELETE FROM sqlite_sequence WHERE name IN ('FuturesPortfolioBacktests', 'FuturesPortfolioTrades')");
		}
	}

	private static void executeIfTableExists(Connection conn, String sql) throws Exception {
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.executeUpdate();
		}
	}

	private static void printLevel2Coverage() throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT symbol, source, COUNT(*) AS rows, MIN(timestamp) AS firstTs, MAX(timestamp) AS lastTs "
				 + "FROM FuturesHistoricalLevel2Snapshots GROUP BY symbol, source ORDER BY symbol, source")) {
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					System.out.println("LEVEL2_DB symbol=" + rs.getString("symbol")
						+ " source=" + rs.getString("source")
						+ " rows=" + rs.getInt("rows")
						+ " first=" + rs.getString("firstTs")
						+ " last=" + rs.getString("lastTs"));
				}
			}
		} catch (Exception e) {
			System.out.println("LEVEL2_DB unavailable reason=" + clean(e.getMessage()));
		}
	}

	private static void copyWalIfPresent(Path sourceDb, Path analysisDb) throws Exception {
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String safeFileName(String value) {
		return value == null ? "full-day-strategy-research" : value.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static String money(double value) {
		return "$" + String.format(Locale.US, "%.2f", value);
	}

	private static String clean(String value) {
		return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
	}

	private static String escapeMarkdown(String value) {
		return clean(value).replace("|", "\\|");
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
