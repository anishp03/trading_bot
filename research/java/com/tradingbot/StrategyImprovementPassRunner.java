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
import java.util.List;

public class StrategyImprovementPassRunner {
	private static final String BASE_PRESET = "94k";
	private static final String WIP_PRESET = "wip";
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final List<String> SYMBOL_LIST = Arrays.asList("MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL");
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-22";
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private static final class RunSummary {
		private int id;
		private String label;
		private int trades;
		private double pnl;
		private double avg;
		private double winRate;
		private double profitFactor;
		private double maxDrawdownPct;
		private int dailyLossBreaches;
		private int trailingDrawdownBreaches;
		private int maeBreaches;
		private int ruleViolation;
		private String ruleMessage;
	}

	public static void main(String[] args) throws Exception {
		Path backendDir = Paths.get("").toAbsolutePath();
		Path sourceDb = args.length > 0 ? Paths.get(args[0]).toAbsolutePath() : backendDir.resolve("tradingbot.db").toAbsolutePath();
		Path outputDir = backendDir.resolve("target/strategy-improvement-pass");
		Files.createDirectories(outputDir);
		Path analysisDb = outputDir.resolve("tradingbot-strategy-improvement-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", backendDir.resolve("market_data/futures").toString());
		FuturesManager.initializeStore();

		List<RunSummary> runs = new ArrayList<RunSummary>();
		RunSummary baseline = run("baseline_94k_full_trail", BASE_PRESET);
		runs.add(baseline);
		resetWipFromBase();
		applyFullSessionPrototypeSettings();
		runs.add(run("prototype_full_session_orb_fvg", WIP_PRESET));
		resetWipFromBase();
		applyOrbDeblockNativeSettings();
		runs.add(run("candidate_orb_deblock_native_window", WIP_PRESET));
		resetWipFromBase();
		applyFvgQualityNativeSettings();
		runs.add(run("candidate_fvg_quality_native_window", WIP_PRESET));
		resetWipFromBase();
		applyFvgQualityPreserveWindowSettings();
		runs.add(run("candidate_fvg_quality_preserve_94k_window", WIP_PRESET));
		resetWipFromBase();
		applyFvgImpulsePreserveWindowSettings();
		runs.add(run("candidate_fvg_impulse_preserve_94k_window", WIP_PRESET));
		resetWipFromBase();
		applyFvgNoChasePreserveWindowSettings();
		runs.add(run("candidate_fvg_no_chase_preserve_94k_window", WIP_PRESET));
		resetWipFromBase();
		applyFvgImpulseNoChasePreserveWindowSettings();
		runs.add(run("candidate_fvg_impulse_no_chase_preserve_94k_window", WIP_PRESET));
		resetWipFromBase();
		applyBalancedWipSettings();
		runs.add(run("candidate_wip_balanced_native_window", WIP_PRESET));
		resetWipFromBase();
		applyBalancedPreserveWindowSettings();
		RunSummary balancedCandidate = run("candidate_wip_balanced_preserve_94k_window", WIP_PRESET);
		runs.add(balancedCandidate);
		resetWipFromBase();
		applyBiasFreeGuardedPatternSettings();
		RunSummary biasFreeCandidate = run("candidate_bias_free_guarded_patterns", WIP_PRESET);
		runs.add(biasFreeCandidate);

		StringBuilder report = new StringBuilder();
		report.append("# Strategy Improvement Pass 1 Report\n\n");
		report.append("- Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(" local\n");
		report.append("- Analysis DB copy: `").append(analysisDb).append("`\n");
		report.append("- Control preset: `").append(BASE_PRESET).append("`\n");
		report.append("- Candidate preset: `").append(WIP_PRESET).append("`\n");
		report.append("- Full Trade Trail: `true`\n\n");
		report.append("## Candidate Logic\n\n");
		report.append("- `94k` remains the frozen control.\n");
		report.append("- Prototype run proves the full-session ORB/FVG expansion is not acceptable by itself.\n");
		report.append("- Final `wip` candidate preserves the frozen `94k` contract/strategy enable map, disables repair-first add-ons (`MSCALP`, `SHDW`, `ECHO`, `WFT`, `VPB`, `MRVWAP`, `KELT`, `TLAD`, `RCB`), and removes fitted skip/day-mask/extra-window blocks from every active contract-level strategy setting.\n");
		report.append("- Time-native strategies keep only broad session phases: opening momentum/opening range, afternoon continuation, close momentum, and late ORB continuation. Pattern and level strategies run under broad RTH-style windows plus quality gates instead of backtest-shaped time exclusions.\n");
		report.append("- FVG repair candidates require impulse quality, EMA/VWAP alignment, trend-slope support, bounded VWAP distance, and no late-chase entry extension.\n\n");
		appendRunTable(report, runs);
		appendStrategyBreakdown(report, baseline.id, "Baseline 94k");
		appendStrategyBreakdown(report, biasFreeCandidate.id, "Candidate Bias-Free WIP");
		appendFvgTaxonomy(report, baseline.id, "Baseline 94k");
		appendFvgTaxonomy(report, biasFreeCandidate.id, "Candidate Bias-Free WIP");
		appendOfflineClassification(report, biasFreeCandidate.id);
		Path reportPath = outputDir.resolve("strategy-improvement-pass-" + RUN_TAG + ".md");
		Files.write(reportPath, report.toString().getBytes(StandardCharsets.UTF_8));

		System.out.println("REPORT=" + reportPath);
		System.out.println("ANALYSIS_DB=" + analysisDb);
		for (RunSummary run : runs) {
			printRun(run);
		}
	}

	private static void resetWipFromBase() {
		FuturesManager.createStrategyPreset(WIP_PRESET, BASE_PRESET);
	}

	private static void applyFullSessionPrototypeSettings() {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			settings.orb.maxTradesPerDay = 1;
			settings.mymOrbRetest.maxTradesPerDay = 1;
			settings.skipMidmorningOrbRetest = false;
			settings.orbShortConfirmationMinute = 0;
			settings.orbBreakoutEndMinute = 660;
			if (settings.fvg.enabled) {
				settings.fvgStartMinute = 570;
				settings.fvgEndMinute = 930;
				settings.fvgSkipStartMinute = 0;
				settings.fvgSkipEndMinute = 0;
				settings.fvgLongSkipDowMask = 0;
				settings.fvgShortSkipDowMask = 0;
				settings.fvgRequireCoreQuality = true;
				settings.fvgRequireEmaStack = true;
				settings.fvgRequireHigherTimeframeGuard = true;
				settings.fvgMinImpulseBodyPct = 45.0;
				settings.fvgMinTrendSlopeTicks = 1.0;
				settings.fvgMaxVwapDistanceTicks = 80.0;
				settings.fvgMaxEntryExtensionTicks = 24.0;
			}
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyOrbDeblockNativeSettings() {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			settings.orb.maxTradesPerDay = 1;
			settings.skipMidmorningOrbRetest = false;
			settings.orbShortConfirmationMinute = 0;
			settings.orbBreakoutEndMinute = 660;
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyFvgQualityNativeSettings() {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			applyFvgQuality(settings, true);
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyFvgQualityPreserveWindowSettings() {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			applyFvgQuality(settings, false);
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyFvgImpulsePreserveWindowSettings() {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			applyFvgCustomQuality(settings, false, false, false, 60.0, 0.0, 0.0, 0.0);
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyFvgNoChasePreserveWindowSettings() {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			applyFvgCustomQuality(settings, false, false, false, 0.0, 0.0, 0.0, 12.0);
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyFvgImpulseNoChasePreserveWindowSettings() {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			applyFvgCustomQuality(settings, false, false, false, 45.0, 0.0, 0.0, 16.0);
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyBalancedWipSettings() {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			settings.orb.maxTradesPerDay = 1;
			settings.skipMidmorningOrbRetest = false;
			settings.orbShortConfirmationMinute = 0;
			settings.orbBreakoutEndMinute = 660;
			applyFvgQuality(settings, true);
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyBalancedPreserveWindowSettings() {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			settings.orb.maxTradesPerDay = 1;
			settings.skipMidmorningOrbRetest = false;
			settings.orbShortConfirmationMinute = 0;
			settings.orbBreakoutEndMinute = 660;
			applyFvgQuality(settings, false);
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyBiasFreeGuardedPatternSettings() {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			settings.relaxPatternHardWindows = true;
			settings.orb.maxTradesPerDay = 1;
			settings.mymOrbRetest.maxTradesPerDay = 1;
			settings.skipMidmorningOrbRetest = false;
			settings.orbShortConfirmationMinute = 0;
			settings.orbBreakoutEndMinute = 660;
			settings.orbLongSkipStartMinute = 0;
			settings.orbLongSkipEndMinute = 0;
			settings.orbShortSkipStartMinute = 0;
			settings.orbShortSkipEndMinute = 0;
			settings.orbRetestSkipStartMinute = 0;
			settings.orbRetestSkipEndMinute = 0;
			settings.orbRetestShortSkipDowMask = 0;
			settings.openingMomentumLongStartMinute = 570;
			settings.openingMomentumLongEndMinute = 660;
			settings.openingMomentumShortStartMinute = 570;
			settings.openingMomentumShortEndMinute = 660;
			settings.openingMomentumLongSkipStartMinute = 0;
			settings.openingMomentumLongSkipEndMinute = 0;
			settings.openingMomentumShortSkipStartMinute = 0;
			settings.openingMomentumShortSkipEndMinute = 0;
			settings.openingMomentumLongSkipDowMask = 0;
			settings.openingMomentumShortSkipDowMask = 0;
			settings.openingMomentumLongExtraWindows = "";
			settings.openingMomentumShortExtraWindows = "";
			settings.openingMomentumShortAltEnabled = false;
			settings.openingMomentumShortAltStartMinute = 0;
			settings.openingMomentumShortAltEndMinute = 0;
			settings.openingMomentumShortAltSkipDowMask = 0;
			settings.priorDayBreakoutStartMinute = 570;
			settings.priorDayBreakoutEndMinute = 930;
			settings.priorDayBreakoutLongSkipStartMinute = 0;
			settings.priorDayBreakoutLongSkipEndMinute = 0;
			settings.priorDayBreakoutShortSkipStartMinute = 0;
			settings.priorDayBreakoutShortSkipEndMinute = 0;
			settings.priorDayBreakoutShortSecondSkipStartMinute = 0;
			settings.priorDayBreakoutShortSecondSkipEndMinute = 0;
			settings.priorDayBreakoutShortThirdSkipStartMinute = 0;
			settings.priorDayBreakoutShortThirdSkipEndMinute = 0;
			settings.vwapRequireHigherTimeframeGuard = true;
			settings.vwapMinVolumeRatio = Math.max(settings.vwapMinVolumeRatio, 1.10);
			settings.vwapMinTrendSlopeTicks = Math.max(settings.vwapMinTrendSlopeTicks, 3.0);
			settings.vwapStartMinute = 0;
			settings.vwapEndMinute = 0;
			settings.vwapSkipStartMinute = 0;
			settings.vwapSkipEndMinute = 0;
			settings.vwapShortSkipDowMask = 0;
			if (settings.fvg.enabled) {
				settings.fvgStartMinute = 570;
				settings.fvgEndMinute = 930;
				settings.fvgSkipStartMinute = 0;
				settings.fvgSkipEndMinute = 0;
				settings.fvgLongSkipDowMask = 0;
				settings.fvgShortSkipDowMask = 0;
				settings.fvgLongDowWindowSkipMask = 0;
				settings.fvgLongDowWindowSkipStartMinute = 0;
				settings.fvgLongDowWindowSkipEndMinute = 0;
				settings.fvgShortDowWindowSkipMask = 0;
				settings.fvgShortDowWindowSkipStartMinute = 0;
				settings.fvgShortDowWindowSkipEndMinute = 0;
			}
			settings.closeMomentumLongStartMinute = 870;
			settings.closeMomentumLongEndMinute = 925;
			settings.closeMomentumShortStartMinute = 870;
			settings.closeMomentumShortEndMinute = 925;
			settings.closeMomentumLongDowWindowSkipMask = 0;
			settings.closeMomentumLongDowWindowSkipStartMinute = 0;
			settings.closeMomentumLongDowWindowSkipEndMinute = 0;
			settings.closeMomentumLongAltEnabled = false;
			settings.closeMomentumLongAltStartMinute = 0;
			settings.closeMomentumLongAltEndMinute = 0;
			settings.closeMomentumLongAltAllowDowMask = 0;
			settings.afternoonStartMinute = 780;
			settings.afternoonEndMinute = 920;
			settings.afternoonLongStartMinute = 0;
			settings.afternoonLongEndMinute = 0;
			settings.afternoonShortStartMinute = 0;
			settings.afternoonShortEndMinute = 0;
			settings.afternoonSkipStartMinute = 0;
			settings.afternoonSkipEndMinute = 0;
			settings.afternoonShortSkipDowMask = 0;
			settings.marketImpulsePullbackStartMinute = 570;
			settings.marketImpulsePullbackEndMinute = 930;
			settings.marketImpulsePullbackSkipStartMinute = 0;
			settings.marketImpulsePullbackSkipEndMinute = 0;
			settings.marketImpulsePullbackLongSkipDowMask = 0;
			settings.marketImpulsePullbackShortSkipDowMask = 0;
			settings.sweepShortSkipStartMinute = 0;
			settings.sweepShortSkipEndMinute = 0;
			settings.microScalpStartMinute = 570;
			settings.microScalpEndMinute = 930;
			settings.microScalpLongStartMinute = 0;
			settings.microScalpLongEndMinute = 0;
			settings.microScalpShortStartMinute = 0;
			settings.microScalpShortEndMinute = 0;
			settings.microScalpSkipStartMinute = 0;
			settings.microScalpSkipEndMinute = 0;
			settings.microScalpSkipDowMask = 0;
			settings.microShadowStartMinute = 570;
			settings.microShadowEndMinute = 930;
			settings.microEchoStartMinute = 570;
			settings.microEchoEndMinute = 930;
			settings.winnerFollowThroughStartMinute = 570;
			settings.winnerFollowThroughEndMinute = 930;
			settings.trendLadderStartMinute = 570;
			settings.trendLadderEndMinute = 930;
			settings.rangeCompressionStartMinute = 570;
			settings.rangeCompressionEndMinute = 930;
			settings.rangeCompressionSkipStartMinute = 0;
			settings.rangeCompressionSkipEndMinute = 0;
			settings.valueAreaStartMinute = 570;
			settings.valueAreaEndMinute = 930;
			settings.mymIndexConfirmationStartMinute = 570;
			settings.mymIndexConfirmationEndMinute = 930;
			settings.mymOrbRetestStartMinute = 590;
			settings.mymOrbRetestEndMinute = 690;
			settings.mymBreadthStartMinute = 570;
			settings.mymBreadthEndMinute = 930;
			settings.mclTrendStartMinute = 570;
			settings.mclTrendEndMinute = 930;
			applyFvgCustomQuality(settings, false, true, true, 55.0, 2.0, 60.0, 16.0);
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyFvgQuality(FuturesManager.FuturesStrategySettings settings, boolean resetWindow) {
		applyFvgCustomQuality(settings, resetWindow, true, true, 55.0, 2.0, 60.0, 16.0);
	}

	private static void applyFvgCustomQuality(
		FuturesManager.FuturesStrategySettings settings,
		boolean resetWindow,
		boolean requireEmaStack,
		boolean requireHigherTimeframeGuard,
		double minImpulseBodyPct,
		double minTrendSlopeTicks,
		double maxVwapDistanceTicks,
		double maxEntryExtensionTicks
	) {
		if (settings == null || !settings.fvg.enabled) {
			return;
		}
		if (resetWindow) {
			settings.fvgStartMinute = 600;
			settings.fvgEndMinute = 900;
			settings.fvgSkipStartMinute = 0;
			settings.fvgSkipEndMinute = 0;
			settings.fvgLongSkipDowMask = 0;
			settings.fvgShortSkipDowMask = 0;
			settings.fvgLongDowWindowSkipMask = 0;
			settings.fvgLongDowWindowSkipStartMinute = 0;
			settings.fvgLongDowWindowSkipEndMinute = 0;
			settings.fvgShortDowWindowSkipMask = 0;
			settings.fvgShortDowWindowSkipStartMinute = 0;
			settings.fvgShortDowWindowSkipEndMinute = 0;
		}
		settings.fvgRequireCoreQuality = true;
		settings.fvgRequireEmaStack = requireEmaStack;
		settings.fvgRequireHigherTimeframeGuard = requireHigherTimeframeGuard;
		settings.fvgMinImpulseBodyPct = minImpulseBodyPct;
		settings.fvgMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.fvgMaxVwapDistanceTicks = maxVwapDistanceTicks;
		settings.fvgMaxEntryExtensionTicks = maxEntryExtensionTicks;
	}

	private static RunSummary run(String label, String preset) throws Exception {
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
			3000.0,
			"TOPSTEP_50K_RESEARCH",
			preset,
			0,
			true
		);
		RunSummary summary = loadRun(id);
		summary.label = label;
		return summary;
	}

	private static RunSummary loadRun(int id) throws Exception {
		RunSummary run = new RunSummary();
		run.id = id;
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("SELECT numTrades,totalProfit,winRate,profitFactor,maxDrawdownPct,dailyLossBreaches,trailingDrawdownBreaches,maeBreaches,ruleViolation,ruleMessage FROM FuturesPortfolioBacktests WHERE portfolioBacktestID=?")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					run.trades = rs.getInt("numTrades");
					run.pnl = rs.getDouble("totalProfit");
					run.avg = run.trades == 0 ? 0.0 : run.pnl / run.trades;
					run.winRate = rs.getDouble("winRate");
					run.profitFactor = rs.getDouble("profitFactor");
					run.maxDrawdownPct = rs.getDouble("maxDrawdownPct");
					run.dailyLossBreaches = rs.getInt("dailyLossBreaches");
					run.trailingDrawdownBreaches = rs.getInt("trailingDrawdownBreaches");
					run.maeBreaches = rs.getInt("maeBreaches");
					run.ruleViolation = rs.getInt("ruleViolation");
					run.ruleMessage = rs.getString("ruleMessage");
				}
			}
		}
		return run;
	}

	private static void appendRunTable(StringBuilder report, List<RunSummary> runs) {
		report.append("## Portfolio Results\n\n");
		report.append("| Run | Trades | PnL | Avg/trade | Win % | PF | Max DD % | Daily | Trail | MAE | Rule |\n");
		report.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
		for (RunSummary run : runs) {
			appendRunRow(report, run);
		}
		report.append("\n");
	}

	private static void appendRunRow(StringBuilder report, RunSummary run) {
		report.append("| `").append(run.label).append("` | ")
			.append(run.trades).append(" | ")
			.append(money(run.pnl)).append(" | ")
			.append(money(run.avg)).append(" | ")
			.append(round(run.winRate)).append(" | ")
			.append(round(run.profitFactor)).append(" | ")
			.append(round(run.maxDrawdownPct)).append(" | ")
			.append(run.dailyLossBreaches).append(" | ")
			.append(run.trailingDrawdownBreaches).append(" | ")
			.append(run.maeBreaches).append(" | ")
			.append(run.ruleViolation == 0 ? "clean" : clean(run.ruleMessage)).append(" |\n");
	}

	private static void appendStrategyBreakdown(StringBuilder report, int id, String title) throws Exception {
		report.append("## ").append(title).append(" Strategy Breakdown\n\n");
		report.append("| Strategy | Symbol | Trades | PnL | Avg | Win % | PF | MFE | MAE | Target % | Stop % |\n");
		report.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT strategyCode,symbol,COUNT(*) trades,SUM(pnl) pnl,AVG(pnl) avgPnl,"
					 + "100.0*SUM(CASE WHEN pnl>0 THEN 1 ELSE 0 END)/COUNT(*) winRate,"
					 + "SUM(CASE WHEN pnl>0 THEN pnl ELSE 0 END) grossWin,"
					 + "-SUM(CASE WHEN pnl<0 THEN pnl ELSE 0 END) grossLoss,"
					 + "AVG(mfe) mfe,AVG(mae) mae,"
					 + "100.0*SUM(CASE WHEN exitReason LIKE '%Target%' THEN 1 ELSE 0 END)/COUNT(*) targetRate,"
					 + "100.0*SUM(CASE WHEN exitReason LIKE '%Stop%' THEN 1 ELSE 0 END)/COUNT(*) stopRate "
					 + "FROM FuturesPortfolioTrades WHERE portfolioBacktestID=? GROUP BY strategyCode,symbol ORDER BY pnl ASC")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					double grossLoss = rs.getDouble("grossLoss");
					double pf = grossLoss == 0.0 ? rs.getDouble("grossWin") : rs.getDouble("grossWin") / grossLoss;
					report.append("| `").append(clean(rs.getString("strategyCode"))).append("` | `").append(clean(rs.getString("symbol"))).append("` | ")
						.append(rs.getInt("trades")).append(" | ")
						.append(money(rs.getDouble("pnl"))).append(" | ")
						.append(money(rs.getDouble("avgPnl"))).append(" | ")
						.append(round(rs.getDouble("winRate"))).append(" | ")
						.append(round(pf)).append(" | ")
						.append(money(rs.getDouble("mfe"))).append(" | ")
						.append(money(rs.getDouble("mae"))).append(" | ")
						.append(round(rs.getDouble("targetRate"))).append(" | ")
						.append(round(rs.getDouble("stopRate"))).append(" |\n");
				}
			}
		}
		report.append("\n");
	}

	private static void appendFvgTaxonomy(StringBuilder report, int id, String title) throws Exception {
		report.append("## ").append(title).append(" FVG Loss Taxonomy\n\n");
		report.append("| Bucket | Trades | PnL | Avg MFE | Avg MAE |\n");
		report.append("|---|---:|---:|---:|---:|\n");
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT CASE "
					 + "WHEN exitReason LIKE '%Stop%' AND ABS(mae) > ABS(mfe) * 2 THEN 'immediate adverse excursion' "
					 + "WHEN exitReason LIKE '%Stop%' THEN 'stop after failed reclaim' "
					 + "WHEN mfe > 0 AND pnl < 0 THEN 'gave back favorable excursion' "
					 + "WHEN pnl < 0 THEN 'low follow-through / chop' "
					 + "ELSE 'winner/flat' END bucket,"
					 + "COUNT(*) trades,SUM(pnl) pnl,AVG(mfe) mfe,AVG(mae) mae "
					 + "FROM FuturesPortfolioTrades WHERE portfolioBacktestID=? AND strategyCode='FVG' AND pnl <= 0 "
					 + "GROUP BY bucket ORDER BY pnl ASC")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					report.append("| ").append(clean(rs.getString("bucket"))).append(" | ")
						.append(rs.getInt("trades")).append(" | ")
						.append(money(rs.getDouble("pnl"))).append(" | ")
						.append(money(rs.getDouble("mfe"))).append(" | ")
						.append(money(rs.getDouble("mae"))).append(" |\n");
				}
			}
		}
		report.append("\n");
	}

	private static void appendOfflineClassification(StringBuilder report, int candidateId) throws Exception {
		report.append("## Offline / Disabled Strategy Classification\n\n");
		report.append("| Strategy | Candidate contribution | Classification |\n");
		report.append("|---|---:|---|\n");
		String[] codes = new String[] { "MSCALP", "VPB", "SHDW", "ECHO", "WFT", "MRVWAP", "KELT", "TLAD", "RCB", "EIA", "COPEN", "IDXCONF", "MYMORB2", "MYMBR", "MCLTC" };
		try (Connection conn = DatabaseManager.getConnection()) {
			for (String code : codes) {
				try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) trades, COALESCE(SUM(pnl),0) pnl FROM FuturesPortfolioTrades WHERE portfolioBacktestID=? AND strategyCode=?")) {
					stmt.setInt(1, candidateId);
					stmt.setString(2, code);
					try (ResultSet rs = stmt.executeQuery()) {
						int trades = rs.next() ? rs.getInt("trades") : 0;
						double pnl = trades == 0 ? 0.0 : rs.getDouble("pnl");
						String classification = trades == 0 ? "disable/archive" : (pnl > 0.0 && trades >= 10 ? "repair" : "dependency-only or archive");
						report.append("| `").append(code).append("` | ")
							.append(trades).append(" / ").append(money(pnl)).append(" | ")
							.append(classification).append(" |\n");
					}
				}
			}
		}
		report.append("\n");
	}

	private static void printRun(RunSummary run) {
		System.out.println(run.label + " id=" + run.id + " trades=" + run.trades + " pnl=" + round(run.pnl) + " win=" + round(run.winRate) + " pf=" + round(run.profitFactor) + " rule=" + run.ruleViolation);
	}

	private static String money(double value) {
		return "$" + round(value);
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	private static String clean(String value) {
		return value == null ? "" : value.replace("|", "/");
	}
}
