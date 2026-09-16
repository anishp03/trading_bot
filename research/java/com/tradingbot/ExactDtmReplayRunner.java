package com.tradingbot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ExactDtmReplayRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-06-06";
	private static final String PROFILE = "TOPSTEP_50K";
	private static final String PRESET = "bestbiasfree";

	public static void main(String[] args) throws Exception {
		Path backendDir = Paths.get("").toAbsolutePath();
		Path sourceDb = args.length > 0 && !args[0].trim().isEmpty()
			? Paths.get(args[0]).toAbsolutePath()
			: backendDir.resolve("tradingbot.db").toAbsolutePath();
		Path futuresDataDir = args.length > 1 && !args[1].trim().isEmpty()
			? Paths.get(args[1]).toAbsolutePath()
			: backendDir.resolve("market_data/futures").toAbsolutePath();

		Path outputDir = backendDir.resolve("target/exact-dtm-replay");
		Files.createDirectories(outputDir);
		Path analysisDb = outputDir.resolve("tradingbot-exact-dtm-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);

		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", futuresDataDir.toString());
		System.setProperty("tradingbot.dtm.dynamicProtectiveOrders", "true");
		System.setProperty("tradingbot.dtm.oneContractExtension", "false");
		System.setProperty("tradingbot.dtm.experimentalHalfRunner", "true");
		System.setProperty("tradingbot.dtm.experimentalHalfRunnerTrigger", "partial");
		System.setProperty("tradingbot.dtm.experimentalHalfRunnerStop", "tight");
		System.setProperty("tradingbot.dtm.experimentalHalfRunnerMinContracts", "2");
		System.setProperty("tradingbot.dtm.experimentalHalfRunnerTargetMode", "any");
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
			PROFILE,
			PRESET,
			0,
			true,
			true,
			true
		);

		System.out.println("ANALYSIS_DB=" + analysisDb);
		try (Connection conn = DatabaseManager.getConnection()) {
			if (id <= 0) {
				id = latestPortfolioBacktestId(conn);
			}
			System.out.println("RUN_ID=" + id);
			printRun(conn, id);
			printJuneSlice(conn, id);
		}
	}

	private static int latestPortfolioBacktestId(Connection conn) throws Exception {
		try (PreparedStatement stmt = conn.prepareStatement("SELECT COALESCE(MAX(portfolioBacktestID), -1) AS id FROM FuturesPortfolioBacktests");
			 ResultSet rs = stmt.executeQuery()) {
			return rs.next() ? rs.getInt("id") : -1;
		}
	}

	private static void printRun(Connection conn, int id) throws Exception {
		String sql = "SELECT totalProfit, winRate, numTrades, profitFactor, maxDrawdownPct, maxIntradayLoss, maxAggregateMae, "
			+ "dailyLossBreaches, trailingDrawdownBreaches, maeBreaches, ruleViolation, ruleMessage "
			+ "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ?";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					System.out.println(
						"RUN pnl=" + round(rs.getDouble("totalProfit"))
							+ " trades=" + rs.getInt("numTrades")
							+ " winRate=" + round(rs.getDouble("winRate"))
							+ " pf=" + round(rs.getDouble("profitFactor"))
							+ " maxDdPct=" + round(rs.getDouble("maxDrawdownPct"))
							+ " maxIntradayLoss=" + round(rs.getDouble("maxIntradayLoss"))
							+ " maxAggregateMae=" + round(rs.getDouble("maxAggregateMae"))
							+ " breaches=" + rs.getInt("dailyLossBreaches") + "/" + rs.getInt("trailingDrawdownBreaches") + "/" + rs.getInt("maeBreaches")
							+ " violation=" + rs.getInt("ruleViolation")
							+ " message=" + clean(rs.getString("ruleMessage"))
					);
				}
			}
		}
	}

	private static void printJuneSlice(Connection conn, int id) throws Exception {
		String sql = "SELECT symbol, strategyCode, side, contracts, openedAt, closedAt, entryPrice, exitPrice, pnl, exitReason "
			+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? "
			+ "AND substr(openedAt, 1, 10) BETWEEN '2026-06-03' AND '2026-06-05' "
			+ "ORDER BY openedAt, portfolioTradeID";
		double pnl = 0.0;
		int trades = 0;
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					trades++;
					double tradePnl = rs.getDouble("pnl");
					pnl += tradePnl;
					System.out.println(
						"SLICE_TRADE "
							+ rs.getString("openedAt")
							+ " " + rs.getString("symbol")
							+ " " + rs.getString("strategyCode")
							+ " " + rs.getString("side")
							+ " contracts=" + rs.getInt("contracts")
							+ " entry=" + round(rs.getDouble("entryPrice"))
							+ " exit=" + round(rs.getDouble("exitPrice"))
							+ " pnl=" + round(tradePnl)
							+ " reason=" + clean(rs.getString("exitReason"))
					);
				}
			}
		}
		System.out.println("SLICE_SUMMARY trades=" + trades + " pnl=" + round(pnl));
	}

	private static String clean(String value) {
		return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
