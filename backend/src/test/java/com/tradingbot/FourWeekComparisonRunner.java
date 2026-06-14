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

public class FourWeekComparisonRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final String PRESET = "bestbiasfree";
	private static final String PROFILE = "TOPSTEP_50K";
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	public static void main(String[] args) throws Exception {
		Path backendDir = Paths.get("").toAbsolutePath();
		Path sourceDb = args.length > 0 && !args[0].trim().isEmpty()
			? Paths.get(args[0]).toAbsolutePath()
			: backendDir.resolve("tradingbot.db").toAbsolutePath();
		Path futuresDataDir = args.length > 1 && !args[1].trim().isEmpty()
			? Paths.get(args[1]).toAbsolutePath()
			: backendDir.resolve("market_data/futures").toAbsolutePath();
		String startDate = args.length > 2 && !args[2].trim().isEmpty() ? args[2].trim() : "2026-05-07";
		String endDate = args.length > 3 && !args[3].trim().isEmpty() ? args[3].trim() : "2026-06-04";
		String label = args.length > 4 && !args[4].trim().isEmpty() ? args[4].trim() : "four-week-comparison";
		boolean refreshData = args.length > 5 && Boolean.parseBoolean(args[5]);
		int sourcePortfolioBacktestId = args.length > 6 && !args[6].trim().isEmpty() ? Integer.parseInt(args[6].trim()) : 0;

		Path outputDir = backendDir.resolve("target/four-week-comparison");
		Files.createDirectories(outputDir);
		Path analysisDb = outputDir.resolve("tradingbot-" + safeFileName(label) + "-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		copyWalIfPresent(sourceDb, analysisDb);

		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", futuresDataDir.toString());
		FuturesManager.initializeStore();

		System.out.println("ANALYSIS_DB=" + analysisDb);
		System.out.println("FUTURES_DATA_DIR=" + futuresDataDir);
		System.out.println("REQUESTED_WINDOW=" + startDate + ".." + endDate);
		System.out.println("SYMBOLS=" + SYMBOLS);
		System.out.println("PRESET=" + PRESET);
		System.out.println("PROFILE=" + PROFILE);
		System.out.println("SOURCE_PORTFOLIO_BACKTEST_ID=" + sourcePortfolioBacktestId);

		if (refreshData) {
			System.out.println("REFRESH_REQUESTED=true");
			System.out.println("REFRESH_RESULT=" + FuturesMarketDataStore.refreshBacktestMarketData(SYMBOLS, startDate, endDate, 1));
		} else {
			System.out.println("REFRESH_REQUESTED=false");
		}

		int id = FuturesManager.generatePortfolioBacktest(
			SYMBOLS,
			startDate,
			endDate,
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
			PRESET,
			sourcePortfolioBacktestId,
			true,
			true,
			true
		);

		System.out.println("RUN_ID=" + id);
		if (id <= 0) {
			return;
		}
		try (Connection conn = DatabaseManager.getConnection()) {
			printRun(conn, id);
			printBreakdown(conn, id, "symbol", "symbol");
			printBreakdown(conn, id, "strategyCode", "strategy");
			printBreakdown(conn, id, "substr(openedAt, 1, 10)", "day");
			printExitReasons(conn, id);
		}
	}

	private static void copyWalIfPresent(Path sourceDb, Path analysisDb) throws Exception {
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void printRun(Connection conn, int id) throws Exception {
		String sql = "SELECT portfolioBacktestID, fundedProfile, symbols, startDate, endDate, startingBalance, endingBalance, totalProfit, returnPct, winRate, numTrades, "
			+ "profitFactor, maxDrawdownPct, maxIntradayLoss, maxAggregateMae, dailyLossBreaches, trailingDrawdownBreaches, maeBreaches, ruleViolation, ruleMessage "
			+ "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ?";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					System.out.println(
						"RUN id=" + rs.getInt("portfolioBacktestID")
							+ " profile=" + rs.getString("fundedProfile")
							+ " window=" + rs.getString("startDate") + ".." + rs.getString("endDate")
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
							+ " message=\"" + clean(rs.getString("ruleMessage")) + "\""
					);
				}
			}
		}
	}

	private static void printBreakdown(Connection conn, int id, String groupExpression, String label) throws Exception {
		System.out.println("BREAKDOWN " + label);
		String sql = "SELECT " + groupExpression + " AS key, COUNT(*) AS trades, COALESCE(SUM(pnl), 0) AS pnl, COALESCE(AVG(pnl), 0) AS avgPnl, "
			+ "COALESCE(100.0 * SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 0) AS winRate "
			+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? GROUP BY " + groupExpression + " ORDER BY pnl DESC";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					System.out.println(
						label + "=" + clean(rs.getString("key"))
							+ " trades=" + rs.getInt("trades")
							+ " pnl=" + money(rs.getDouble("pnl"))
							+ " avg=" + money(rs.getDouble("avgPnl"))
							+ " winPct=" + round(rs.getDouble("winRate"))
					);
				}
			}
		}
	}

	private static void printExitReasons(Connection conn, int id) throws Exception {
		System.out.println("BREAKDOWN exitReason");
		String sql = "SELECT exitReason, COUNT(*) AS trades, COALESCE(SUM(pnl), 0) AS pnl "
			+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? GROUP BY exitReason ORDER BY pnl DESC";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					System.out.println("exitReason=\"" + clean(rs.getString("exitReason")) + "\" trades=" + rs.getInt("trades") + " pnl=" + money(rs.getDouble("pnl")));
				}
			}
		}
	}

	private static String safeFileName(String value) {
		return clean(value).toLowerCase().replaceAll("[^a-z0-9._-]+", "-");
	}

	private static String clean(String value) {
		return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
	}

	private static String money(double value) {
		return String.format("%.2f", value);
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
