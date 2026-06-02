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

public class FvgContractTuningRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final List<String> SYMBOL_LIST = Arrays.asList("MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL");
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-22";
	private static final String BASE_PRESET = "bestbiasfree";
	private static final String WIP_PRESET = "wip";
	private static final String PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private static final class RunSummary {
		String label;
		int id;
		int trades;
		double pnl;
		double winRate;
		double profitFactor;
		double maxDrawdownPct;
		int ruleViolation;
		int fvgTrades;
		double fvgPnl;
		double fvgWinRate;
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
		Path analysisDb = outputDir.resolve("tradingbot-fvg-contract-tuning-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		copyWalIfPresent(sourceDb, analysisDb);

		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", futuresDataDir.toString());
		FuturesManager.initializeStore();

		List<RunSummary> portfolioRuns = new ArrayList<RunSummary>();
		List<RunSummary> soloRuns = new ArrayList<RunSummary>();
		portfolioRuns.add(runPortfolio("baseline_bestbiasfree", BASE_PRESET, SYMBOLS));

		double[] rewardRisks = new double[] { 0.55, 0.70, 0.85, 1.00 };
		int[] holdBars = new int[] { 8, 12, 18 };
		for (String symbol : SYMBOL_LIST) {
			if ("NQ".equals(symbol)) {
				continue;
			}
			for (double rewardRisk : rewardRisks) {
				for (int holdBarsValue : holdBars) {
					resetWip();
					applySourceBreakFvgOnly(symbol, 3, 20, 0.0, rewardRisk, holdBarsValue);
					soloRuns.add(runPortfolio("solo_" + symbol + "_src20_rr" + tag(rewardRisk) + "_h" + holdBarsValue, WIP_PRESET, symbol));
				}
			}
		}

		String[] candidateSymbols = new String[] { "MNQ", "M2K", "MYM", "MCL", "MGC", "MES", "ES" };
		for (String symbol : candidateSymbols) {
			RunSummary bestSolo = bestSoloFor(symbol, soloRuns);
			if (bestSolo == null || bestSolo.fvgPnl <= 0.0 || bestSolo.fvgTrades < 10) {
				continue;
			}
			double rewardRisk = rewardRiskFromLabel(bestSolo.label);
			int holdBarsValue = holdBarsFromLabel(bestSolo.label);
			resetWip();
			applySourceBreakFvgOverlay(Arrays.asList("NQ", symbol), 3, 20, 0.0, rewardRisk, holdBarsValue);
			portfolioRuns.add(runPortfolio("add_" + symbol + "_best_solo_" + bestSolo.label, WIP_PRESET, SYMBOLS));
		}

		Path reportPath = outputDir.resolve("fvg-contract-tuning-" + RUN_TAG + ".md");
		Files.write(reportPath, buildReport(analysisDb, portfolioRuns, soloRuns).getBytes(StandardCharsets.UTF_8));
		System.out.println("REPORT=" + reportPath);
		System.out.println("ANALYSIS_DB=" + analysisDb);
		System.out.println("PORTFOLIO");
		for (RunSummary run : portfolioRuns) {
			printRun(run);
		}
		System.out.println("SOLO");
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

	private static void applySourceBreakFvgOverlay(List<String> enabledSymbols, int maxTradesPerDay, int sourceRangeBars, double minSourceBreakTicks, double rewardRisk, int holdBars) {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			if (enabledSymbols.contains(symbol)) {
				applySourceBreakFvgSettings(settings, maxTradesPerDay, sourceRangeBars, minSourceBreakTicks, rewardRisk, holdBars);
			} else if (!"NQ".equals(symbol)) {
				settings.fvg.enabled = false;
			}
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applySourceBreakFvgOnly(String symbol, int maxTradesPerDay, int sourceRangeBars, double minSourceBreakTicks, double rewardRisk, int holdBars) {
		for (String candidate : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(candidate, FuturesManager.strategyPresetSlot(WIP_PRESET));
			disableAllStrategies(settings);
			if (candidate.equals(symbol)) {
				applySourceBreakFvgSettings(settings, maxTradesPerDay, sourceRangeBars, minSourceBreakTicks, rewardRisk, holdBars);
			}
			FuturesManager.saveFuturesStrategySettings(candidate, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applySourceBreakFvgSettings(FuturesManager.FuturesStrategySettings settings, int maxTradesPerDay, int sourceRangeBars, double minSourceBreakTicks, double rewardRisk, int holdBars) {
		settings.fvg.enabled = true;
		settings.fvg.maxTradesPerDay = maxTradesPerDay;
		settings.allowShorts = true;
		settings.allowFvgLongs = true;
		settings.allowFvgShorts = true;
		settings.fvgStartMinute = 600;
		settings.fvgEndMinute = 900;
		settings.fvgRetestBars = 10;
		settings.fvgMinVolumeRatio = 0.75;
		settings.fvgRequireCoreQuality = true;
		settings.fvgRequireEmaStack = true;
		settings.fvgRequireHigherTimeframeGuard = false;
		settings.fvgMinImpulseBodyPct = 45.0;
		settings.fvgMinTrendSlopeTicks = 1.0;
		settings.fvgMaxVwapDistanceTicks = 96.0;
		settings.fvgMaxEntryExtensionTicks = 28.0;
		settings.fvgMaxRetestDepthPct = 0.85;
		settings.fvgMinReclaimCloseLocation = 0.78;
		settings.fvgSourceRangeBars = sourceRangeBars;
		settings.fvgMinSourceBreakTicks = minSourceBreakTicks;
		settings.fvgAcceptanceBars = 0;
		settings.fvgRewardRisk = rewardRisk;
		settings.fvgMaxHoldBars = holdBars;
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

	private static RunSummary bestSoloFor(String symbol, List<RunSummary> soloRuns) {
		RunSummary best = null;
		for (RunSummary run : soloRuns) {
			if (!run.label.startsWith("solo_" + symbol + "_")) {
				continue;
			}
			if (best == null || run.fvgPnl > best.fvgPnl) {
				best = run;
			}
		}
		return best;
	}

	private static double rewardRiskFromLabel(String label) {
		int start = label.indexOf("_rr");
		int end = label.indexOf("_h", start);
		if (start < 0 || end < 0) return 1.0;
		return Double.parseDouble(label.substring(start + 3, end).replace("p", "."));
	}

	private static int holdBarsFromLabel(String label) {
		int start = label.lastIndexOf("_h");
		if (start < 0) return 18;
		return Integer.parseInt(label.substring(start + 2));
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

	private static String buildReport(Path analysisDb, List<RunSummary> portfolioRuns, List<RunSummary> soloRuns) {
		StringBuilder report = new StringBuilder();
		report.append("# FVG Contract Tuning Research\n\n");
		report.append("- Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(" local\n");
		report.append("- Analysis DB copy: `").append(analysisDb).append("`\n");
		report.append("- Base preset: `").append(BASE_PRESET).append("`\n");
		report.append("- Range: `").append(START_DATE).append("` to `").append(END_DATE).append("`\n\n");
		report.append("## Portfolio Runs\n\n");
		appendRunTable(report, portfolioRuns);
		report.append("## Solo Runs\n\n");
		appendRunTable(report, soloRuns);
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

	private static void copyWalIfPresent(Path sourceDb, Path analysisDb) throws Exception {
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void printRun(RunSummary run) {
		System.out.println(run.label
			+ " id=" + run.id
			+ " trades=" + run.trades
			+ " pnl=" + money(run.pnl)
			+ " win=" + round(run.winRate)
			+ " pf=" + round(run.profitFactor)
			+ " rule=" + run.ruleViolation
			+ " fvgTrades=" + run.fvgTrades
			+ " fvgPnl=" + money(run.fvgPnl)
			+ " fvgWin=" + round(run.fvgWinRate));
	}

	private static String tag(double value) {
		return String.format("%.2f", value).replace(".", "p");
	}

	private static String money(double value) {
		return String.format("$%.2f", value);
	}

	private static String round(double value) {
		return String.format("%.2f", value);
	}
}
