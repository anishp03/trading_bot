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

public class FvgStrategyResearchRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final List<String> SYMBOL_LIST = Arrays.asList("MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL");
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-22";
	private static final String BASE_PRESET = "bestbiasfree";
	private static final String WIP_PRESET = "wip";
	private static final String PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private static final class RunSummary {
		private String label;
		private int id;
		private int trades;
		private double pnl;
		private double winRate;
		private double profitFactor;
		private double maxDrawdownPct;
		private int ruleViolation;
		private int fvgTrades;
		private double fvgPnl;
		private double fvgWinRate;
	}

	public static void main(String[] args) throws Exception {
		Path backendDir = Paths.get("").toAbsolutePath();
		Path sourceDb = args.length > 0 && !args[0].trim().isEmpty()
			? Paths.get(args[0]).toAbsolutePath()
			: backendDir.resolve("tradingbot.db").toAbsolutePath();
		Path futuresDataDir = args.length > 1 && !args[1].trim().isEmpty()
			? Paths.get(args[1]).toAbsolutePath()
			: backendDir.resolve("market_data/futures").toAbsolutePath();

		Path outputDir = backendDir.resolve("target/fvg-research");
		Files.createDirectories(outputDir);
		Path analysisDb = outputDir.resolve("tradingbot-fvg-research-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		copyWalIfPresent(sourceDb, analysisDb);

		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", futuresDataDir.toString());
		FuturesManager.initializeStore();

		List<RunSummary> runs = new ArrayList<RunSummary>();
		runs.add(runPortfolio("baseline_bestbiasfree", BASE_PRESET, SYMBOLS));

		resetWip();
		applyFvgOverlay(false, false, 35.0, 0.0, 0.0, 0.0, 0.50, 10, 2);
		runs.add(runPortfolio("bestbiasfree_fvg_light_all_contracts", WIP_PRESET, SYMBOLS));

		resetWip();
		applyFvgOverlay(true, false, 45.0, 1.0, 96.0, 28.0, 0.75, 10, 2);
		runs.add(runPortfolio("bestbiasfree_fvg_balanced_all_contracts", WIP_PRESET, SYMBOLS));

		resetWip();
		applyFvgOverlay(true, true, 45.0, 1.0, 96.0, 28.0, 0.75, 10, 2);
		runs.add(runPortfolio("bestbiasfree_fvg_htf_all_contracts", WIP_PRESET, SYMBOLS));

		resetWip();
		applyFvgOverlayForSymbols(Arrays.asList("NQ"), false, false, 35.0, 0.0, 0.0, 0.0, 0.50, 10, 3);
		runs.add(runPortfolio("bestbiasfree_fvg_light_nq_only", WIP_PRESET, SYMBOLS));

		resetWip();
		applyFvgOverlayForSymbols(Arrays.asList("NQ"), true, false, 45.0, 1.0, 96.0, 28.0, 0.75, 10, 3);
		runs.add(runPortfolio("bestbiasfree_fvg_balanced_nq_only", WIP_PRESET, SYMBOLS));

		resetWip();
		applyDynamicPerContractFvgProfile();
		runs.add(runPortfolio("bestbiasfree_fvg_dynamic_profile", WIP_PRESET, SYMBOLS));

		resetWip();
		applyFinalReclaimQualityFvgOverlayForSymbols(SYMBOL_LIST, 3);
		runs.add(runPortfolio("bestbiasfree_fvg_final_logic_all_contracts", WIP_PRESET, SYMBOLS));

		resetWip();
		applyFinalSourceBreakFvgOverlayForSymbols(SYMBOL_LIST, 3, 20, 0.0);
		runs.add(runPortfolio("bestbiasfree_fvg_source20_all_contracts", WIP_PRESET, SYMBOLS));

		resetWip();
		applyFinalSourceBreakFvgOverlayForSymbols(SYMBOL_LIST, 3, 20, 1.0);
		runs.add(runPortfolio("bestbiasfree_fvg_source20_break1_all_contracts", WIP_PRESET, SYMBOLS));

		resetWip();
		applyFinalSourceBreakFvgOverlayForSymbols(SYMBOL_LIST, 3, 30, 0.0);
		runs.add(runPortfolio("bestbiasfree_fvg_source30_all_contracts", WIP_PRESET, SYMBOLS));

		resetWip();
		applyFinalAcceptanceFvgOverlayForSymbols(SYMBOL_LIST, 3, false);
		runs.add(runPortfolio("bestbiasfree_fvg_acceptance_body3_all_contracts", WIP_PRESET, SYMBOLS));

		resetWip();
		applyFinalAcceptanceFvgOverlayForSymbols(SYMBOL_LIST, 3, true);
		runs.add(runPortfolio("bestbiasfree_fvg_acceptance_extreme3_all_contracts", WIP_PRESET, SYMBOLS));

		resetWip();
		applySelectiveAcceptanceProfile(false);
		runs.add(runPortfolio("bestbiasfree_fvg_selective_acceptance_body", WIP_PRESET, SYMBOLS));

		resetWip();
		applySelectiveAcceptanceProfile(true);
		runs.add(runPortfolio("bestbiasfree_fvg_selective_acceptance_extreme", WIP_PRESET, SYMBOLS));

		for (String symbol : SYMBOL_LIST) {
			if ("NQ".equals(symbol)) {
				continue;
			}
			resetWip();
			applyFinalReclaimQualityFvgOverlayForSymbols(Arrays.asList("NQ", symbol), 3);
			runs.add(runPortfolio("bestbiasfree_fvg_final_logic_add_" + symbol, WIP_PRESET, SYMBOLS));

			resetWip();
			applyFinalSourceBreakFvgOverlayForSymbols(Arrays.asList("NQ", symbol), 3, 20, 0.0);
			runs.add(runPortfolio("bestbiasfree_fvg_source20_add_" + symbol, WIP_PRESET, SYMBOLS));
		}

		List<RunSummary> soloRuns = new ArrayList<RunSummary>();
		for (String symbol : SYMBOL_LIST) {
			resetWip();
			applyFvgOnly(symbol, true, false, 45.0, 1.0, 96.0, 28.0, 0.75, 10, 3);
			soloRuns.add(runPortfolio("solo_fvg_balanced_" + symbol, WIP_PRESET, symbol));
		}
		for (String symbol : SYMBOL_LIST) {
			resetWip();
			applyFinalReclaimQualityFvgOnly(symbol, 3);
			soloRuns.add(runPortfolio("solo_fvg_final_logic_" + symbol, WIP_PRESET, symbol));

			resetWip();
			applyFinalSourceBreakFvgOnly(symbol, 3, 20, 0.0);
			soloRuns.add(runPortfolio("solo_fvg_source20_" + symbol, WIP_PRESET, symbol));
		}

		Path reportPath = outputDir.resolve("fvg-research-" + RUN_TAG + ".md");
		Files.write(reportPath, buildReport(analysisDb, runs, soloRuns).getBytes(StandardCharsets.UTF_8));

		System.out.println("REPORT=" + reportPath);
		System.out.println("ANALYSIS_DB=" + analysisDb);
		for (RunSummary run : runs) {
			printRun(run);
		}
		System.out.println("SOLO_FVG");
		for (RunSummary run : soloRuns) {
			printRun(run);
		}
	}

	private static void resetWip() {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(BASE_PRESET));
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyFvgOverlay(boolean requireEmaStack, boolean requireHigherTimeframe, double minImpulseBodyPct, double minTrendSlopeTicks, double maxVwapDistanceTicks, double maxEntryExtensionTicks, double minVolumeRatio, int retestBars, int maxTradesPerDay) {
		applyFvgOverlayForSymbols(SYMBOL_LIST, requireEmaStack, requireHigherTimeframe, minImpulseBodyPct, minTrendSlopeTicks, maxVwapDistanceTicks, maxEntryExtensionTicks, minVolumeRatio, retestBars, maxTradesPerDay);
	}

	private static void applyFvgOverlayForSymbols(List<String> enabledSymbols, boolean requireEmaStack, boolean requireHigherTimeframe, double minImpulseBodyPct, double minTrendSlopeTicks, double maxVwapDistanceTicks, double maxEntryExtensionTicks, double minVolumeRatio, int retestBars, int maxTradesPerDay) {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			if (enabledSymbols.contains(symbol)) {
				applyFvgSettings(settings, requireEmaStack, requireHigherTimeframe, minImpulseBodyPct, minTrendSlopeTicks, maxVwapDistanceTicks, maxEntryExtensionTicks, minVolumeRatio, retestBars, maxTradesPerDay);
			} else {
				settings.fvg.enabled = false;
			}
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyFvgOnly(String symbol, boolean requireEmaStack, boolean requireHigherTimeframe, double minImpulseBodyPct, double minTrendSlopeTicks, double maxVwapDistanceTicks, double maxEntryExtensionTicks, double minVolumeRatio, int retestBars, int maxTradesPerDay) {
		for (String candidate : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(candidate, FuturesManager.strategyPresetSlot(WIP_PRESET));
			disableAllStrategies(settings);
			if (candidate.equals(symbol)) {
				applyFvgSettings(settings, requireEmaStack, requireHigherTimeframe, minImpulseBodyPct, minTrendSlopeTicks, maxVwapDistanceTicks, maxEntryExtensionTicks, minVolumeRatio, retestBars, maxTradesPerDay);
			}
			FuturesManager.saveFuturesStrategySettings(candidate, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyFinalReclaimQualityFvgOverlayForSymbols(List<String> enabledSymbols, int maxTradesPerDay) {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			if (enabledSymbols.contains(symbol)) {
				applyFinalReclaimQualityFvgSettings(settings, maxTradesPerDay);
			} else if (!"NQ".equals(symbol)) {
				settings.fvg.enabled = false;
			}
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyFinalReclaimQualityFvgOnly(String symbol, int maxTradesPerDay) {
		for (String candidate : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(candidate, FuturesManager.strategyPresetSlot(WIP_PRESET));
			disableAllStrategies(settings);
			if (candidate.equals(symbol)) {
				applyFinalReclaimQualityFvgSettings(settings, maxTradesPerDay);
			}
			FuturesManager.saveFuturesStrategySettings(candidate, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyFinalReclaimQualityFvgSettings(FuturesManager.FuturesStrategySettings settings, int maxTradesPerDay) {
		applyFvgSettings(settings, true, false, 45.0, 1.0, 96.0, 28.0, 0.75, 10, maxTradesPerDay);
		settings.fvgStartMinute = 600;
		settings.fvgEndMinute = 900;
		settings.fvgMaxRetestDepthPct = 0.85;
		settings.fvgMinReclaimCloseLocation = 0.78;
	}

	private static void applyFinalSourceBreakFvgOverlayForSymbols(List<String> enabledSymbols, int maxTradesPerDay, int sourceRangeBars, double minSourceBreakTicks) {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			if (enabledSymbols.contains(symbol)) {
				applyFinalSourceBreakFvgSettings(settings, maxTradesPerDay, sourceRangeBars, minSourceBreakTicks);
			} else if (!"NQ".equals(symbol)) {
				settings.fvg.enabled = false;
			}
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyFinalSourceBreakFvgOnly(String symbol, int maxTradesPerDay, int sourceRangeBars, double minSourceBreakTicks) {
		for (String candidate : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(candidate, FuturesManager.strategyPresetSlot(WIP_PRESET));
			disableAllStrategies(settings);
			if (candidate.equals(symbol)) {
				applyFinalSourceBreakFvgSettings(settings, maxTradesPerDay, sourceRangeBars, minSourceBreakTicks);
			}
			FuturesManager.saveFuturesStrategySettings(candidate, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyFinalSourceBreakFvgSettings(FuturesManager.FuturesStrategySettings settings, int maxTradesPerDay, int sourceRangeBars, double minSourceBreakTicks) {
		applyFinalReclaimQualityFvgSettings(settings, maxTradesPerDay);
		settings.fvgSourceRangeBars = sourceRangeBars;
		settings.fvgMinSourceBreakTicks = minSourceBreakTicks;
	}

	private static void applyFinalAcceptanceFvgOverlayForSymbols(List<String> enabledSymbols, int maxTradesPerDay, boolean requireExtremeBreak) {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			if (enabledSymbols.contains(symbol)) {
				applyFinalAcceptanceFvgSettings(settings, maxTradesPerDay, requireExtremeBreak);
			} else {
				settings.fvg.enabled = false;
			}
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyFinalAcceptanceFvgSettings(FuturesManager.FuturesStrategySettings settings, int maxTradesPerDay, boolean requireExtremeBreak) {
		applyFinalReclaimQualityFvgSettings(settings, maxTradesPerDay);
		settings.fvgAcceptanceBars = 3;
		settings.fvgAcceptanceMinCloseLocation = 0.60;
		settings.fvgAcceptanceRequireReclaimExtremeBreak = requireExtremeBreak;
	}

	private static void applySelectiveAcceptanceProfile(boolean includeMgcShortExtreme) {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			settings.fvg.enabled = false;
			if ("NQ".equals(symbol)) {
				applyFinalReclaimQualityFvgSettings(settings, 3);
			} else if ("MES".equals(symbol) || "ES".equals(symbol)) {
				applyFinalAcceptanceFvgSettings(settings, 3, false);
				settings.allowFvgLongs = true;
				settings.allowFvgShorts = true;
			} else if ("MCL".equals(symbol)) {
				applyFinalAcceptanceFvgSettings(settings, 3, false);
				settings.allowFvgLongs = true;
				settings.allowFvgShorts = false;
			} else if (includeMgcShortExtreme && "MGC".equals(symbol)) {
				applyFinalAcceptanceFvgSettings(settings, 2, true);
				settings.allowFvgLongs = false;
				settings.allowFvgShorts = true;
			}
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyFvgSettings(FuturesManager.FuturesStrategySettings settings, boolean requireEmaStack, boolean requireHigherTimeframe, double minImpulseBodyPct, double minTrendSlopeTicks, double maxVwapDistanceTicks, double maxEntryExtensionTicks, double minVolumeRatio, int retestBars, int maxTradesPerDay) {
		settings.fvg.enabled = true;
		settings.fvg.maxTradesPerDay = maxTradesPerDay;
		settings.allowShorts = true;
		settings.allowFvgLongs = true;
		settings.allowFvgShorts = true;
		settings.fvgStartMinute = 570;
		settings.fvgEndMinute = 930;
		settings.fvgSkipStartMinute = 0;
		settings.fvgSkipEndMinute = 0;
		settings.fvgLongSkipDowMask = 0;
		settings.fvgShortSkipDowMask = 0;
		settings.fvgLongDowWindowSkipMask = 0;
		settings.fvgShortDowWindowSkipMask = 0;
		settings.fvgRetestBars = retestBars;
		settings.fvgMinWidthTicks = Math.max(4.0, settings.fvgMinWidthTicks);
		settings.fvgMinVolumeRatio = minVolumeRatio;
		settings.fvgRequireCoreQuality = true;
		settings.fvgRequireEmaStack = requireEmaStack;
		settings.fvgRequireHigherTimeframeGuard = requireHigherTimeframe;
		settings.fvgMinImpulseBodyPct = minImpulseBodyPct;
		settings.fvgMinReclaimBodyPct = 0.0;
		settings.fvgMinReclaimTicks = 0.0;
		settings.fvgMaxRetestDepthPct = 0.0;
		settings.fvgMinReclaimCloseLocation = 0.0;
		settings.fvgMaxPriorMoveTicks = 0.0;
		settings.fvgSourceRangeBars = 0;
		settings.fvgMinSourceBreakTicks = 0.0;
		settings.fvgAcceptanceBars = 0;
		settings.fvgAcceptanceMinCloseLocation = 0.0;
		settings.fvgAcceptanceRequireReclaimExtremeBreak = false;
		settings.fvgMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.fvgMaxVwapDistanceTicks = maxVwapDistanceTicks;
		settings.fvgMaxEntryExtensionTicks = maxEntryExtensionTicks;
	}

	private static void applyDynamicPerContractFvgProfile() {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			settings.fvg.enabled = false;
			if ("NQ".equals(symbol)) {
				applyFvgSettings(settings, true, false, 45.0, 1.0, 96.0, 28.0, 0.75, 10, 3);
				settings.fvgMinReclaimCloseLocation = 0.78;
				settings.fvgMaxRetestDepthPct = 0.85;
			} else if ("MCL".equals(symbol)) {
				applyFvgSettings(settings, true, false, 45.0, 1.0, 2.0, 5.0, 0.75, 10, 2);
				settings.allowFvgLongs = true;
				settings.allowFvgShorts = false;
				settings.fvgMinReclaimBodyPct = 56.0;
			} else if ("M2K".equals(symbol)) {
				applyFvgSettings(settings, true, false, 45.0, 1.0, 96.0, 28.0, 0.75, 10, 1);
				settings.allowFvgLongs = true;
				settings.allowFvgShorts = false;
				settings.fvgMinWidthTicks = 5.0;
				settings.fvgMaxRetestDepthPct = 0.55;
			} else if ("MGC".equals(symbol)) {
				applyFvgSettings(settings, true, false, 45.0, 24.0, 96.0, 28.0, 0.90, 10, 1);
				settings.allowFvgLongs = false;
				settings.allowFvgShorts = true;
			}
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void disableAllStrategies(FuturesManager.FuturesStrategySettings settings) {
		settings.orb.enabled = false;
		settings.lateOrbContinuation.enabled = false;
		settings.openingMomentum.enabled = false;
		settings.sweep.enabled = false;
		settings.priorDayBreakout.enabled = false;
		settings.vwapPullback.enabled = false;
		settings.vwapReclaim.enabled = false;
		settings.vwapMeanReversion.enabled = false;
		settings.fvg.enabled = false;
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
		settings.enableOrbRetest = false;
	}

	private static RunSummary runPortfolio(String label, String preset, String symbols) throws Exception {
		int id = FuturesManager.generatePortfolioBacktest(
			symbols,
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
			true
		);
		RunSummary summary = loadRunSummary(id);
		summary.label = label;
		return summary;
	}

	private static RunSummary loadRunSummary(int id) throws Exception {
		RunSummary summary = new RunSummary();
		summary.id = id;
		try (Connection conn = DatabaseManager.getConnection()) {
			try (PreparedStatement stmt = conn.prepareStatement("SELECT numTrades,totalProfit,winRate,profitFactor,maxDrawdownPct,ruleViolation FROM FuturesPortfolioBacktests WHERE portfolioBacktestID=?")) {
				stmt.setInt(1, id);
				try (ResultSet rs = stmt.executeQuery()) {
					if (rs.next()) {
						summary.trades = rs.getInt("numTrades");
						summary.pnl = rs.getDouble("totalProfit");
						summary.winRate = rs.getDouble("winRate");
						summary.profitFactor = rs.getDouble("profitFactor");
						summary.maxDrawdownPct = rs.getDouble("maxDrawdownPct");
						summary.ruleViolation = rs.getInt("ruleViolation");
					}
				}
			}
			try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) trades, COALESCE(SUM(pnl),0) pnl, COALESCE(100.0*SUM(CASE WHEN pnl>0 THEN 1 ELSE 0 END)/NULLIF(COUNT(*),0),0) winRate FROM FuturesPortfolioTrades WHERE portfolioBacktestID=? AND strategyCode='FVG'")) {
				stmt.setInt(1, id);
				try (ResultSet rs = stmt.executeQuery()) {
					if (rs.next()) {
						summary.fvgTrades = rs.getInt("trades");
						summary.fvgPnl = rs.getDouble("pnl");
						summary.fvgWinRate = rs.getDouble("winRate");
					}
				}
			}
		}
		return summary;
	}

	private static String buildReport(Path analysisDb, List<RunSummary> runs, List<RunSummary> soloRuns) throws Exception {
		StringBuilder report = new StringBuilder();
		report.append("# FVG Strategy Research\n\n");
		report.append("- Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(" local\n");
		report.append("- Analysis DB copy: `").append(analysisDb).append("`\n");
		report.append("- Base preset: `").append(BASE_PRESET).append("`\n");
		report.append("- Range: `").append(START_DATE).append("` to `").append(END_DATE).append("`\n");
		report.append("- Symbols: `").append(SYMBOLS).append("`\n\n");
		report.append("## Portfolio Variants\n\n");
		appendRunTable(report, runs);
		report.append("## Individual FVG-Only Checks\n\n");
		appendRunTable(report, soloRuns);
		report.append("## FVG Symbol Breakdown\n\n");
		for (RunSummary run : runs) {
			if (run.fvgTrades > 0) {
				appendFvgSymbolBreakdown(report, run.id, run.label);
			}
		}
		return report.toString();
	}

	private static void appendRunTable(StringBuilder report, List<RunSummary> runs) {
		report.append("| Run | Total Trades | Total PnL | Win % | PF | Max DD % | Rule | FVG Trades | FVG PnL | FVG Win % |\n");
		report.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
		for (RunSummary run : runs) {
			report.append("| `").append(run.label).append("` | ")
				.append(run.trades).append(" | ")
				.append(money(run.pnl)).append(" | ")
				.append(round(run.winRate)).append(" | ")
				.append(round(run.profitFactor)).append(" | ")
				.append(round(run.maxDrawdownPct)).append(" | ")
				.append(run.ruleViolation).append(" | ")
				.append(run.fvgTrades).append(" | ")
				.append(money(run.fvgPnl)).append(" | ")
				.append(round(run.fvgWinRate)).append(" |\n");
		}
		report.append("\n");
	}

	private static void appendFvgSymbolBreakdown(StringBuilder report, int id, String label) throws Exception {
		report.append("### ").append(label).append("\n\n");
		report.append("| Symbol | Side | Trades | PnL | Win % | Avg MFE | Avg MAE |\n");
		report.append("|---|---|---:|---:|---:|---:|---:|\n");
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("SELECT symbol, side, COUNT(*) trades, COALESCE(SUM(pnl),0) pnl, COALESCE(100.0*SUM(CASE WHEN pnl>0 THEN 1 ELSE 0 END)/NULLIF(COUNT(*),0),0) winRate, AVG(mfe) mfe, AVG(mae) mae FROM FuturesPortfolioTrades WHERE portfolioBacktestID=? AND strategyCode='FVG' GROUP BY symbol, side ORDER BY pnl DESC")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					report.append("| `").append(rs.getString("symbol")).append("` | ")
						.append(rs.getString("side")).append(" | ")
						.append(rs.getInt("trades")).append(" | ")
						.append(money(rs.getDouble("pnl"))).append(" | ")
						.append(round(rs.getDouble("winRate"))).append(" | ")
						.append(money(rs.getDouble("mfe"))).append(" | ")
						.append(money(rs.getDouble("mae"))).append(" |\n");
				}
			}
		}
		report.append("\n");
	}

	private static void copyWalIfPresent(Path sourceDb, Path analysisDb) throws Exception {
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void printRun(RunSummary run) {
		System.out.println(
			run.label
				+ " id=" + run.id
				+ " trades=" + run.trades
				+ " pnl=" + money(run.pnl)
				+ " win=" + round(run.winRate)
				+ " pf=" + round(run.profitFactor)
				+ " rule=" + run.ruleViolation
				+ " fvgTrades=" + run.fvgTrades
				+ " fvgPnl=" + money(run.fvgPnl)
				+ " fvgWin=" + round(run.fvgWinRate)
		);
	}

	private static String money(double value) {
		return String.format("$%.2f", value);
	}

	private static String round(double value) {
		return String.format("%.2f", value);
	}
}
