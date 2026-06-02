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

public class SprintBacktestRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-22";
	private static final String DEFAULT_PRESET = "biasfree92k";
	private static final String DEFAULT_PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	public static void main(String[] args) throws Exception {
		Path backendDir = Paths.get("").toAbsolutePath();
		Path sourceDb = args.length > 0 && !args[0].trim().isEmpty()
			? Paths.get(args[0]).toAbsolutePath()
			: backendDir.resolve("tradingbot.db").toAbsolutePath();
		Path futuresDataDir = args.length > 1 && !args[1].trim().isEmpty()
			? Paths.get(args[1]).toAbsolutePath()
			: backendDir.resolve("market_data/futures").toAbsolutePath();
		String preset = args.length > 2 && !args[2].trim().isEmpty() ? args[2].trim() : DEFAULT_PRESET;
		String label = args.length > 3 && !args[3].trim().isEmpty() ? args[3].trim() : "sprint";

		Path outputDir = backendDir.resolve("target/sprint-backtests");
		Files.createDirectories(outputDir);
		Path analysisDb = outputDir.resolve("tradingbot-" + safeFileName(label) + "-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		copyWalIfPresent(sourceDb, analysisDb);

		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", futuresDataDir.toString());
		FuturesManager.initializeStore();

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
			DEFAULT_PROFILE,
			preset,
			0,
			true
		);

		System.out.println("LABEL=" + label);
		System.out.println("ANALYSIS_DB=" + analysisDb);
		System.out.println("FUTURES_DATA_DIR=" + futuresDataDir);
		System.out.println("RUN_ID=" + id);
		if (id <= 0) {
			return;
		}
		try (Connection conn = DatabaseManager.getConnection()) {
			printRun(conn, id);
			printStrategy(conn, id, "ORB2");
			printStrategy(conn, id, "ORB");
			printTopStrategies(conn, id);
		}
	}

	private static void copyWalIfPresent(Path sourceDb, Path analysisDb) throws Exception {
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void printRun(Connection conn, int id) throws Exception {
		String sql = "SELECT portfolioBacktestID, fundedProfile, symbols, startingBalance, endingBalance, totalProfit, returnPct, winRate, numTrades, "
			+ "profitFactor, maxDrawdownPct, maxIntradayLoss, maxAggregateMae, dailyLossBreaches, trailingDrawdownBreaches, maeBreaches, "
			+ "ruleViolation, continueAfterRuleViolation, ruleMessage FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ?";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					System.out.println(
						"RUN id=" + rs.getInt("portfolioBacktestID")
							+ " profile=" + rs.getString("fundedProfile")
							+ " symbols=" + rs.getString("symbols")
							+ " startBalance=" + money(rs.getDouble("startingBalance"))
							+ " endingBalance=" + money(rs.getDouble("endingBalance"))
							+ " pnl=" + money(rs.getDouble("totalProfit"))
							+ " returnPct=" + round(rs.getDouble("returnPct"))
							+ " trades=" + rs.getInt("numTrades")
							+ " winPct=" + round(rs.getDouble("winRate"))
							+ " pf=" + round(rs.getDouble("profitFactor"))
							+ " maxDdPct=" + round(rs.getDouble("maxDrawdownPct"))
							+ " maxIntradayLoss=" + money(rs.getDouble("maxIntradayLoss"))
							+ " maxMae=" + money(rs.getDouble("maxAggregateMae"))
							+ " breaches=" + rs.getInt("dailyLossBreaches") + "/" + rs.getInt("trailingDrawdownBreaches") + "/" + rs.getInt("maeBreaches")
							+ " violation=" + rs.getInt("ruleViolation")
							+ " continue=" + rs.getInt("continueAfterRuleViolation")
							+ " message=\"" + clean(rs.getString("ruleMessage")) + "\""
					);
				}
			}
		}
	}

	private static void printStrategy(Connection conn, int id, String strategyCode) throws Exception {
		String sql = "SELECT COUNT(*) AS trades, COALESCE(SUM(pnl), 0) AS pnl, COALESCE(AVG(pnl), 0) AS avgPnl, "
			+ "COALESCE(100.0 * SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 0) AS winRate "
			+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND strategyCode = ?";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			stmt.setString(2, strategyCode);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					System.out.println(
						"STRATEGY code=" + strategyCode
							+ " trades=" + rs.getInt("trades")
							+ " pnl=" + money(rs.getDouble("pnl"))
							+ " avg=" + money(rs.getDouble("avgPnl"))
							+ " winPct=" + round(rs.getDouble("winRate"))
					);
				}
			}
		}
	}

	private static void printTopStrategies(Connection conn, int id) throws Exception {
		System.out.println("TOP_STRATEGIES");
		String sql = "SELECT strategyCode, COUNT(*) AS trades, SUM(pnl) AS pnl, AVG(pnl) AS avgPnl, "
			+ "100.0 * SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) / COUNT(*) AS winRate "
			+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? GROUP BY strategyCode ORDER BY pnl DESC";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					System.out.println(
						rs.getString("strategyCode")
							+ " trades=" + rs.getInt("trades")
							+ " pnl=" + money(rs.getDouble("pnl"))
							+ " avg=" + money(rs.getDouble("avgPnl"))
							+ " winPct=" + round(rs.getDouble("winRate"))
					);
				}
			}
		}
	}

	private static String safeFileName(String value) {
		return value == null ? "sprint" : value.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static String money(double value) {
		return String.format("$%.2f", value);
	}

	private static String round(double value) {
		return String.format("%.2f", value);
	}

	private static String clean(String value) {
		return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
	}
}
