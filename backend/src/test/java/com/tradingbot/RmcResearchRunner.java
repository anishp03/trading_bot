package com.tradingbot;

import java.io.File;
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
import java.util.List;

public class RmcResearchRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final String[] SYMBOL_LIST = new String[] { "MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL" };
	private static final String START_DATE = System.getProperty("rmc.startDate", "2025-05-01");
	private static final String END_DATE = System.getProperty("rmc.endDate", "2026-06-04");
	private static final String PROFILE = "TOPSTEP_50K";
	private static final String RMC_PRESET = "wip";
	private static final String BASELINE_PRESET = "bestbiasfree";
	private static final String RMC_SLOT = FuturesManager.strategyPresetSlot(RMC_PRESET);
	private static final String BASELINE_SLOT = FuturesManager.strategyPresetSlot(BASELINE_PRESET);
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private static class Summary {
		int id;
		String name;
		RmcProfile profile;
		double bufferTicks;
		double pnl;
		int trades;
		double winRate;
		double profitFactor;
		double drawdownPct;
		int ruleViolation;
		int rmcTrades;
		double rmcPnl;
		double rmcWinRate;
	}

	private enum RmcProfile {
		BASE("base"),
		SHALLOW_RETRACE_58("shallow_retrace_58"),
		STRONG_IMPULSE("strong_impulse"),
		COMMODITY_ONLY("commodity_only"),
		COMMODITY_SHALLOW_62("commodity_shallow_62");

		final String label;

		RmcProfile(String label) {
			this.label = label;
		}
	}

	public static void main(String[] args) throws Exception {
		Path backendDir = Paths.get("").toAbsolutePath();
		Path sourceDb = args.length > 0 && !args[0].trim().isEmpty()
			? Paths.get(args[0]).toAbsolutePath()
			: backendDir.resolve("tradingbot.db").toAbsolutePath();
		Path futuresDataDir = args.length > 1 && !args[1].trim().isEmpty()
			? Paths.get(args[1]).toAbsolutePath()
			: backendDir.resolve("market_data/futures").toAbsolutePath();
		String label = args.length > 2 && !args[2].trim().isEmpty() ? args[2].trim() : "rmc-research";

		Path outputDir = backendDir.resolve("target/rmc-research");
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
		System.out.println("WINDOW=" + START_DATE + ".." + END_DATE + " profile=" + PROFILE + " dtm=true qualitativeRisk=true");
		printLevel2Coverage();

		List<Summary> standalone = new ArrayList<Summary>();
		int maxBuffer = Math.max(0, Integer.parseInt(System.getProperty("rmc.maxBufferTicks", "3")));
		for (RmcProfile profile : RmcProfile.values()) {
			for (int buffer = 0; buffer <= maxBuffer; buffer++) {
				applyRmcOnlySettings(buffer, profile);
				Summary summary = runPortfolio("rmc_" + profile.label + "_buffer_" + buffer, RMC_PRESET, buffer, profile);
				standalone.add(summary);
				printSummary(summary);
				printRmcExitBreakdown(summary.id);
			}
		}

		Summary selected = selectMinimalRobustBuffer(standalone);
		if (selected == null) {
			System.out.println("RMC_SELECTION skipped additive test: no standalone buffer met positive-PnL, PF>1.0, sample-size, and no-rule-violation criteria.");
			return;
		}
		System.out.println("RMC_SELECTION profile=" + selected.profile.label + " bufferTicks=" + selected.bufferTicks + " sourceRunId=" + selected.id + " reason=minimal robust standalone profile");

		Summary baseline = runPortfolio("bestbiasfree_baseline", BASELINE_PRESET, -1.0, null);
		printSummary(baseline);
		applyBestBiasFreePlusRmcSettings(selected.bufferTicks, selected.profile);
		Summary additive = runPortfolio("bestbiasfree_plus_rmc_" + selected.profile.label + "_buffer_" + ((int) selected.bufferTicks), BASELINE_PRESET, selected.bufferTicks, selected.profile);
		printSummary(additive);
		printRmcExitBreakdown(additive.id);
		System.out.println("RMC_ADDITIVE_DELTA pnl=" + round(additive.pnl - baseline.pnl)
			+ " trades=" + (additive.trades - baseline.trades)
			+ " pfDelta=" + round(additive.profitFactor - baseline.profitFactor)
			+ " winDelta=" + round(additive.winRate - baseline.winRate));
	}

	private static void applyRmcOnlySettings(double bufferTicks, RmcProfile profile) throws Exception {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, RMC_SLOT);
			disableBaseModules(settings);
			if (isRmcProfileSymbolEnabled(profile, symbol)) {
				enableRmc(settings, symbol, bufferTicks, profile);
			}
			FuturesManager.saveFuturesStrategySettings(symbol, RMC_SLOT, settings);
		}
	}

	private static void applyBestBiasFreePlusRmcSettings(double bufferTicks, RmcProfile profile) throws Exception {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, BASELINE_SLOT);
			if (isRmcProfileSymbolEnabled(profile, symbol)) {
				enableRmc(settings, symbol, bufferTicks, profile);
			}
			FuturesManager.saveFuturesStrategySettings(symbol, BASELINE_SLOT, settings);
		}
	}

	private static boolean isRmcProfileSymbolEnabled(RmcProfile profile, String symbol) {
		return profile != RmcProfile.COMMODITY_ONLY && profile != RmcProfile.COMMODITY_SHALLOW_62
			|| "MCL".equals(symbol) || "MGC".equals(symbol);
	}

	private static void enableRmc(FuturesManager.FuturesStrategySettings settings, String symbol, double bufferTicks, RmcProfile profile) {
		settings.rangeMidpointContinuation.enabled = true;
		settings.rangeMidpointContinuation.maxTradesPerDay = "MNQ".equals(symbol) || "NQ".equals(symbol) ? 10 : 6;
		settings.allowRangeMidpointLongs = true;
		settings.allowRangeMidpointShorts = true;
		settings.rangeMidpointStartMinute = 570;
		settings.rangeMidpointEndMinute = 930;
		settings.rangeMidpointImpulseBars = 45;
		settings.rangeMidpointPullbackBars = 8;
		settings.rangeMidpointBucketMinutes = 60;
		settings.rangeMidpointMinImpulseTicks = "MCL".equals(symbol) ? 12.0 : 28.0;
		settings.rangeMidpointMinRetracePct = 0.40;
		settings.rangeMidpointMaxRetracePct = 0.78;
		settings.rangeMidpointMidpointBufferTicks = bufferTicks;
		settings.rangeMidpointMaxCloseExtensionPct = 0.35;
		settings.rangeMidpointMaxPullbackVolumeRatio = 1.20;
		settings.rangeMidpointMinBodyPct = 45.0;
		settings.rangeMidpointMinVolumeRatio = 0.70;
		settings.rangeMidpointMinCloseLocation = 0.62;
		settings.rangeMidpointMaxRiskTicks = "MCL".equals(symbol) ? 45.0 : 72.0;
		settings.rangeMidpointRewardRisk = 1.15;
		settings.rangeMidpointMaxHoldBars = 80;
		settings.rangeMidpointMaxSpreadTicks = 3.0;
		settings.rangeMidpointMinDepthImbalance = 0.22;
		settings.rangeMidpointMinTapeDelta = "MCL".equals(symbol) ? 20.0 : 45.0;
		settings.rangeMidpointMinOrderFlowVotes = 2;
		settings.rangeMidpointMinOrderFlowConfidence = 0.45;
		applyRmcProfileOverrides(settings, symbol, profile);
	}

	private static void applyRmcProfileOverrides(FuturesManager.FuturesStrategySettings settings, String symbol, RmcProfile profile) {
		if (profile == RmcProfile.SHALLOW_RETRACE_58) {
			settings.rangeMidpointMaxRetracePct = 0.58;
		} else if (profile == RmcProfile.STRONG_IMPULSE) {
			settings.rangeMidpointMinImpulseTicks = "MCL".equals(symbol) ? 40.0 : 150.0;
			settings.rangeMidpointMaxRetracePct = 0.65;
		} else if (profile == RmcProfile.COMMODITY_SHALLOW_62) {
			settings.rangeMidpointMaxRetracePct = 0.62;
		}
	}

	private static void disableBaseModules(FuturesManager.FuturesStrategySettings settings) {
		settings.orb.enabled = false;
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

	private static Summary runPortfolio(String name, String preset, double bufferTicks, RmcProfile profile) throws Exception {
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
		labelRun(id, name);
		Summary summary = loadSummary(id);
		summary.name = name;
		summary.profile = profile;
		summary.bufferTicks = bufferTicks;
		return summary;
	}

	private static Summary selectMinimalRobustBuffer(List<Summary> summaries) {
		Summary best = null;
		for (Summary summary : summaries) {
			if (summary == null || summary.ruleViolation != 0 || summary.rmcTrades < 10 || summary.rmcPnl <= 0.0 || summary.profitFactor <= 1.0) {
				continue;
			}
			if (best == null || summary.rmcPnl > best.rmcPnl) {
				best = summary;
			}
		}
		if (best == null) {
			return null;
		}
		double robustPnlFloor = best.rmcPnl * 0.80;
		for (Summary summary : summaries) {
			if (summary.ruleViolation == 0 && summary.rmcTrades >= 10 && summary.rmcPnl >= robustPnlFloor && summary.profitFactor > 1.0) {
				return summary;
			}
		}
		return best;
	}

	private static Summary loadSummary(int id) throws Exception {
		Summary summary = new Summary();
		summary.id = id;
		try (Connection conn = DatabaseManager.getConnection()) {
			try (PreparedStatement stmt = conn.prepareStatement(
					"SELECT totalProfit, winRate, numTrades, profitFactor, maxDrawdownPct, ruleViolation "
					+ "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ?")) {
				stmt.setInt(1, id);
				try (ResultSet rs = stmt.executeQuery()) {
					if (rs.next()) {
						summary.pnl = rs.getDouble("totalProfit");
						summary.winRate = rs.getDouble("winRate");
						summary.trades = rs.getInt("numTrades");
						summary.profitFactor = rs.getDouble("profitFactor");
						summary.drawdownPct = rs.getDouble("maxDrawdownPct");
						summary.ruleViolation = rs.getInt("ruleViolation");
					}
				}
			}
			try (PreparedStatement stmt = conn.prepareStatement(
					"SELECT COUNT(*) AS trades, COALESCE(SUM(pnl),0) AS pnl, "
					+ "COALESCE(100.0 * SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 0) AS winRate "
					+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND strategyCode = 'RMC'")) {
				stmt.setInt(1, id);
				try (ResultSet rs = stmt.executeQuery()) {
					if (rs.next()) {
						summary.rmcTrades = rs.getInt("trades");
						summary.rmcPnl = rs.getDouble("pnl");
						summary.rmcWinRate = rs.getDouble("winRate");
					}
				}
			}
		}
		return summary;
	}

	private static void labelRun(int id, String name) throws Exception {
		if (id <= 0) {
			return;
		}
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("UPDATE FuturesPortfolioBacktests SET dataSource = dataSource || ? WHERE portfolioBacktestID = ?")) {
			stmt.setString(1, " | RMC_RESEARCH:" + name);
			stmt.setInt(2, id);
			stmt.executeUpdate();
		}
	}

	private static void printSummary(Summary summary) {
		System.out.println("SUMMARY name=" + summary.name
			+ " id=" + summary.id
			+ " profile=" + (summary.profile == null ? "none" : summary.profile.label)
			+ " bufferTicks=" + summary.bufferTicks
			+ " pnl=" + round(summary.pnl)
			+ " trades=" + summary.trades
			+ " win=" + round(summary.winRate)
			+ " pf=" + round(summary.profitFactor)
			+ " dd=" + round(summary.drawdownPct)
			+ " violation=" + summary.ruleViolation
			+ " rmcTrades=" + summary.rmcTrades
			+ " rmcPnl=" + round(summary.rmcPnl)
			+ " rmcWin=" + round(summary.rmcWinRate));
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

	private static void printRmcExitBreakdown(int id) throws Exception {
		if (id <= 0) {
			return;
		}
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT symbol, exitReason, COUNT(*) AS trades, COALESCE(SUM(pnl),0) AS pnl "
				 + "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND strategyCode = 'RMC' "
				 + "GROUP BY symbol, exitReason ORDER BY pnl DESC")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					System.out.println("RMC_BREAKDOWN id=" + id
						+ " symbol=" + rs.getString("symbol")
						+ " exit=\"" + clean(rs.getString("exitReason")) + "\""
						+ " trades=" + rs.getInt("trades")
						+ " pnl=" + round(rs.getDouble("pnl")));
				}
			}
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
		}
	}

	private static void copyWalIfPresent(Path sourceDb, Path analysisDb) throws Exception {
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String safeFileName(String value) {
		return value == null ? "rmc-research" : value.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static String clean(String value) {
		return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
