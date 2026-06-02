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

public class IfvgResearchRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final List<String> SYMBOL_LIST = Arrays.asList("MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL");
	private static final String[] PROFILES = new String[] {
		"INVERSE_FINAL_HEAVY",
		"INVERSE_FINAL_HEAVY_STRUCT20",
		"INVERSE_FINAL_HEAVY_STRUCT40",
		"INVERSE_LIGHT",
		"INVERSE_LIGHT_STRUCT20",
		"BASE",
		"TREND_CONFIRMED"
	};
	private static final String[] SIDES = new String[] { "BOTH", "LONG", "SHORT" };
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-22";
	private static final String BASE_PRESET = "bestbiasfree";
	private static final String WIP_PRESET = "wip";
	private static final String PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private static final class Candidate {
		String symbol;
		String profile;
		String side;
		int trades;
		double pnl;
		double winRate;
		double profitFactor;
	}

	private static final class RunSummary {
		String label;
		int id;
		int trades;
		double pnl;
		double winRate;
		double profitFactor;
		double maxDrawdownPct;
		int ruleViolation;
		int ifvgTrades;
		double ifvgPnl;
		double ifvgWinRate;
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
		Path analysisDb = outputDir.resolve("tradingbot-ifvg-research-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		copyWalIfPresent(sourceDb, analysisDb);

		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", futuresDataDir.toString());
		FuturesManager.initializeStore();

		List<RunSummary> soloRuns = new ArrayList<RunSummary>();
		List<RunSummary> portfolioRuns = new ArrayList<RunSummary>();
		portfolioRuns.add(runPortfolio("baseline_bestbiasfree", BASE_PRESET, SYMBOLS));

		for (String symbol : SYMBOL_LIST) {
			for (String profile : PROFILES) {
				for (String side : SIDES) {
					resetWip();
					applyIfvgOnly(symbol, profile, side);
					RunSummary run = runPortfolio("solo_" + symbol + "_" + profile + "_" + side, WIP_PRESET, symbol);
					soloRuns.add(run);
					printRun(run);
				}
			}
		}

		List<Candidate> candidates = candidateList(soloRuns);
		for (Candidate candidate : candidates) {
			resetWip();
			applyIfvgOverlay(candidate.symbol, candidate.profile, candidate.side);
			RunSummary run = runPortfolio("add_" + candidate.symbol + "_" + candidate.profile + "_" + candidate.side, WIP_PRESET, SYMBOLS);
			portfolioRuns.add(run);
			printRun(run);
		}

		Path reportPath = outputDir.resolve("ifvg-research-" + RUN_TAG + ".md");
		Files.write(reportPath, buildReport(analysisDb, soloRuns, portfolioRuns, candidates).getBytes(StandardCharsets.UTF_8));
		System.out.println("REPORT=" + reportPath);
		System.out.println("ANALYSIS_DB=" + analysisDb);
		System.out.println("CANDIDATES=" + candidates.size());
	}

	private static List<Candidate> candidateList(List<RunSummary> soloRuns) {
		List<Candidate> candidates = new ArrayList<Candidate>();
		for (RunSummary run : soloRuns) {
			if (run.ifvgTrades < 20 || run.ifvgPnl < 500.0 || run.profitFactor < 1.10 || run.ifvgWinRate < 48.0) {
				continue;
			}
			String[] parts = run.label.split("_");
			if (parts.length < 4) {
				continue;
			}
			Candidate candidate = new Candidate();
			candidate.symbol = parts[1];
			candidate.side = parts[parts.length - 1];
			candidate.profile = run.label.substring(("solo_" + candidate.symbol + "_").length(), run.label.length() - ("_" + candidate.side).length());
			candidate.trades = run.ifvgTrades;
			candidate.pnl = run.ifvgPnl;
			candidate.winRate = run.ifvgWinRate;
			candidate.profitFactor = run.profitFactor;
			candidates.add(candidate);
		}
		return candidates;
	}

	private static void resetWip() {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(BASE_PRESET));
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyIfvgOnly(String symbol, String profile, String side) {
		for (String candidate : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(candidate, FuturesManager.strategyPresetSlot(WIP_PRESET));
			disableAllStrategies(settings);
			if (candidate.equals(symbol)) {
				applyIfvgSettings(settings, profile, side);
			}
			FuturesManager.saveFuturesStrategySettings(candidate, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyIfvgOverlay(String symbol, String profile, String side) {
		for (String candidate : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(candidate, FuturesManager.strategyPresetSlot(WIP_PRESET));
			if (candidate.equals(symbol)) {
				applyIfvgSettings(settings, profile, side);
			} else if (!"NQ".equals(candidate)) {
				settings.fvg.enabled = false;
			}
			FuturesManager.saveFuturesStrategySettings(candidate, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyIfvgSettings(FuturesManager.FuturesStrategySettings settings, String profile, String side) {
		settings.fvg.enabled = true;
		settings.fvgTradeInversions = true;
		settings.fvg.maxTradesPerDay = 3;
		settings.allowShorts = true;
		settings.allowFvgLongs = !"SHORT".equals(side);
		settings.allowFvgShorts = !"LONG".equals(side);
		boolean inverseLight = profile.startsWith("INVERSE_LIGHT");
		boolean inverseHeavy = profile.startsWith("INVERSE_FINAL_HEAVY");
		boolean structure20 = profile.endsWith("STRUCT20");
		boolean structure40 = profile.endsWith("STRUCT40");
		settings.fvgStartMinute = inverseLight ? 570 : 600;
		settings.fvgEndMinute = inverseLight ? 930 : 900;
		settings.fvgRetestBars = 10;
		settings.fvgMinWidthTicks = 4.0;
		settings.fvgMinVolumeRatio = inverseLight ? 0.50 : 0.75;
		settings.fvgMinRiskTicks = 16.0;
		settings.fvgMaxRiskTicks = 48.0;
		settings.fvgRewardRisk = "BASE".equals(profile) || "TREND_CONFIRMED".equals(profile) ? 1.0 : 1.2;
		settings.fvgMaxHoldBars = 18;
		settings.fvgRequireCoreQuality = true;
		settings.fvgRequireEmaStack = "TREND_CONFIRMED".equals(profile) || inverseHeavy;
		settings.fvgRequireHigherTimeframeGuard = false;
		settings.fvgRequireInversionStructureBreak = structure20 || structure40;
		settings.fvgInversionBreakBars = structure40 ? 60 : (structure20 ? 40 : 10);
		settings.fvgInversionStructureBars = structure40 ? 40 : 20;
		settings.fvgMinInversionBodyPct = structure20 || structure40 ? 55.0 : 0.0;
		settings.fvgMinImpulseBodyPct = inverseLight ? 35.0 : 45.0;
		settings.fvgMinReclaimBodyPct = 0.0;
		settings.fvgMinReclaimTicks = 0.0;
		settings.fvgMaxRetestDepthPct = inverseHeavy ? 0.85 : 0.0;
		settings.fvgMinReclaimCloseLocation = inverseHeavy ? 0.78 : 0.0;
		settings.fvgMaxPriorMoveTicks = 0.0;
		settings.fvgSourceMode = "NONE";
		settings.fvgSourceRangeBars = 0;
		settings.fvgMinSourceBreakTicks = 0.0;
		settings.fvgAcceptanceBars = 0;
		settings.fvgMinTrendSlopeTicks = "TREND_CONFIRMED".equals(profile) || inverseHeavy ? 1.0 : 0.0;
		settings.fvgMaxVwapDistanceTicks = "TREND_CONFIRMED".equals(profile) || inverseHeavy ? 96.0 : 0.0;
		settings.fvgMaxEntryExtensionTicks = inverseLight ? 0.0 : 28.0;
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
			try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) trades, COALESCE(SUM(pnl),0) pnl, COALESCE(100.0*SUM(CASE WHEN pnl>0 THEN 1 ELSE 0 END)/NULLIF(COUNT(*),0),0) winRate FROM FuturesPortfolioTrades WHERE portfolioBacktestID=? AND strategyCode='IFVG'")) {
				stmt.setInt(1, id);
				try (ResultSet rs = stmt.executeQuery()) {
					if (rs.next()) {
						summary.ifvgTrades = rs.getInt("trades");
						summary.ifvgPnl = rs.getDouble("pnl");
						summary.ifvgWinRate = rs.getDouble("winRate");
					}
				}
			}
		}
		return summary;
	}

	private static String buildReport(Path analysisDb, List<RunSummary> soloRuns, List<RunSummary> portfolioRuns, List<Candidate> candidates) {
		StringBuilder report = new StringBuilder();
		report.append("# IFVG Research\n\n");
		report.append("- Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(" local\n");
		report.append("- Analysis DB copy: `").append(analysisDb).append("`\n");
		report.append("- Base preset: `").append(BASE_PRESET).append("` stayed unchanged; all edits were run through `").append(WIP_PRESET).append("` in the DB copy.\n");
		report.append("- Range: `").append(START_DATE).append("` to `").append(END_DATE).append("`\n");
		report.append("- Candidate threshold: at least 20 IFVG trades, IFVG PnL >= $500, PF >= 1.10, and IFVG win rate >= 48% in solo mode.\n\n");
		report.append("## Portfolio Runs\n\n");
		appendRunTable(report, portfolioRuns);
		report.append("## Candidate Solo Passes\n\n");
		if (candidates.isEmpty()) {
			report.append("No IFVG solo run cleared the candidate threshold.\n\n");
		} else {
			report.append("| Symbol | Profile | Side | Solo IFVG Trades | Solo IFVG PnL | Solo IFVG Win % | Solo PF |\n");
			report.append("|---|---|---|---:|---:|---:|---:|\n");
			for (Candidate candidate : candidates) {
				report.append("| ").append(candidate.symbol)
					.append(" | `").append(candidate.profile).append("` | ")
					.append(candidate.side).append(" | ")
					.append(candidate.trades).append(" | ")
					.append(money(candidate.pnl)).append(" | ")
					.append(round(candidate.winRate)).append(" | ")
					.append(round(candidate.profitFactor)).append(" |\n");
			}
			report.append("\n");
		}
		report.append("## Solo IFVG Runs\n\n");
		appendRunTable(report, soloRuns);
		return report.toString();
	}

	private static void appendRunTable(StringBuilder report, List<RunSummary> runs) {
		report.append("| Run | Total Trades | Total PnL | Win % | PF | Max DD % | Rule | IFVG Trades | IFVG PnL | IFVG Win % |\n");
		report.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
		for (RunSummary run : runs) {
			report.append("| `").append(run.label).append("` | ")
				.append(run.trades).append(" | ")
				.append(money(run.pnl)).append(" | ")
				.append(round(run.winRate)).append(" | ")
				.append(round(run.profitFactor)).append(" | ")
				.append(round(run.maxDrawdownPct)).append(" | ")
				.append(run.ruleViolation).append(" | ")
				.append(run.ifvgTrades).append(" | ")
				.append(money(run.ifvgPnl)).append(" | ")
				.append(round(run.ifvgWinRate)).append(" |\n");
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
			+ " ifvgTrades=" + run.ifvgTrades
			+ " ifvgPnl=" + money(run.ifvgPnl)
			+ " ifvgWin=" + round(run.ifvgWinRate));
	}

	private static String money(double value) {
		return String.format("$%.2f", value);
	}

	private static String round(double value) {
		return String.format("%.2f", value);
	}
}
