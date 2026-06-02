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

public class FvgSideCandidateRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final List<String> SYMBOL_LIST = Arrays.asList("MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL");
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-22";
	private static final String BASE_PRESET = "bestbiasfree";
	private static final String WIP_PRESET = "wip";
	private static final String PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private static final class Candidate {
		final String symbol;
		final boolean longs;
		final boolean shorts;
		final double rewardRisk;
		final int holdBars;

		Candidate(String symbol, boolean longs, boolean shorts, double rewardRisk, int holdBars) {
			this.symbol = symbol;
			this.longs = longs;
			this.shorts = shorts;
			this.rewardRisk = rewardRisk;
			this.holdBars = holdBars;
		}
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
		Path analysisDb = outputDir.resolve("tradingbot-fvg-side-candidates-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		copyWalIfPresent(sourceDb, analysisDb);

		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", futuresDataDir.toString());
		FuturesManager.initializeStore();

		List<RunSummary> runs = new ArrayList<RunSummary>();
		runs.add(runPortfolio("baseline_bestbiasfree", BASE_PRESET));

		Candidate mgcShort = new Candidate("MGC", false, true, 0.70, 12);
		Candidate mclLong = new Candidate("MCL", true, false, 1.00, 18);
		Candidate esShort = new Candidate("ES", false, true, 0.85, 18);
		Candidate m2kShort = new Candidate("M2K", false, true, 0.55, 8);

		runs.add(runCandidate("add_mgc_short", mgcShort));
		runs.add(runCandidate("add_mcl_long", mclLong));
		runs.add(runCandidate("add_es_short", esShort));
		runs.add(runCandidate("add_m2k_short", m2kShort));
		runs.add(runCandidate("add_mgc_short_mcl_long", mgcShort, mclLong));
		runs.add(runCandidate("add_mgc_mcl_es_shorts", mgcShort, mclLong, esShort));
		runs.add(runCandidate("add_mgc_mcl_es_m2k", mgcShort, mclLong, esShort, m2kShort));

		Path reportPath = outputDir.resolve("fvg-side-candidates-" + RUN_TAG + ".md");
		Files.write(reportPath, buildReport(analysisDb, runs).getBytes(StandardCharsets.UTF_8));
		System.out.println("REPORT=" + reportPath);
		System.out.println("ANALYSIS_DB=" + analysisDb);
		for (RunSummary run : runs) {
			printRun(run);
		}
	}

	private static RunSummary runCandidate(String label, Candidate... candidates) throws Exception {
		resetWip();
		for (Candidate candidate : candidates) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(candidate.symbol, FuturesManager.strategyPresetSlot(WIP_PRESET));
			applyCandidateSettings(settings, candidate);
			FuturesManager.saveFuturesStrategySettings(candidate.symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
		return runPortfolio(label, WIP_PRESET);
	}

	private static void resetWip() {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(BASE_PRESET));
			FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP_PRESET), settings);
		}
	}

	private static void applyCandidateSettings(FuturesManager.FuturesStrategySettings settings, Candidate candidate) {
		settings.fvg.enabled = true;
		settings.fvg.maxTradesPerDay = 3;
		settings.allowShorts = true;
		settings.allowFvgLongs = candidate.longs;
		settings.allowFvgShorts = candidate.shorts;
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
		settings.fvgSourceRangeBars = 20;
		settings.fvgMinSourceBreakTicks = 0.0;
		settings.fvgAcceptanceBars = 0;
		settings.fvgRewardRisk = candidate.rewardRisk;
		settings.fvgMaxHoldBars = candidate.holdBars;
	}

	private static RunSummary runPortfolio(String label, String preset) throws Exception {
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

	private static String buildReport(Path analysisDb, List<RunSummary> runs) {
		StringBuilder report = new StringBuilder();
		report.append("# FVG Side Candidate Research\n\n");
		report.append("- Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(" local\n");
		report.append("- Analysis DB copy: `").append(analysisDb).append("`\n");
		report.append("- Base preset: `").append(BASE_PRESET).append("`\n");
		report.append("- Range: `").append(START_DATE).append("` to `").append(END_DATE).append("`\n\n");
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
		return report.toString();
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

	private static String money(double value) {
		return String.format("$%.2f", value);
	}

	private static String round(double value) {
		return String.format("%.2f", value);
	}
}
