package com.tradingbot;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Orb2PerContractResearchRunner {
	private static final String[] SYMBOLS = new String[] { "MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL" };
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-22";
	private static final String PRESET = "biasfree92k";
	private static final String PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	public static void main(String[] args) throws Exception {
		Path backendDir = Paths.get("").toAbsolutePath();
		Path sourceDb = args.length > 0 && !args[0].trim().isEmpty()
			? Paths.get(args[0]).toAbsolutePath()
			: backendDir.resolve("tradingbot.db").toAbsolutePath();
		Path futuresDataDir = args.length > 1 && !args[1].trim().isEmpty()
			? Paths.get(args[1]).toAbsolutePath()
			: backendDir.resolve("market_data/futures").toAbsolutePath();
		Path outputDir = backendDir.resolve("target/sprint-backtests");
		Files.createDirectories(outputDir);
		Path analysisDb = outputDir.resolve("orb2-per-contract-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		copyWalIfPresent(sourceDb, analysisDb);

		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", futuresDataDir.toString());
		FuturesManager.initializeStore();
		applyOrb2OnlyAllContracts();

		System.out.println("ANALYSIS_DB=" + analysisDb);
		System.out.println("FUTURES_DATA_DIR=" + futuresDataDir);
		System.out.println("SYMBOL,TRADES,PNL,RETURN_PCT,WIN_PCT,PF,MAX_DD_PCT,DAILY_BREACHES,TRAIL_BREACHES,MAE_BREACHES,RULE_VIOLATION");
		for (String symbol : SYMBOLS) {
			int id = FuturesManager.generatePortfolioBacktest(
				symbol,
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
				true
			);
			printRun(symbol, id);
		}
	}

	private static void copyWalIfPresent(Path sourceDb, Path analysisDb) throws Exception {
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void applyOrb2OnlyAllContracts() throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("UPDATE FuturesStrategySettings SET settingValue='false' WHERE settingKey LIKE 'PRESET_BIASFREE92K.%.enabled'");
			stmt.executeUpdate("UPDATE FuturesStrategySettings SET settingValue='true' WHERE settingKey LIKE 'PRESET_BIASFREE92K.%.orb.enabled'");
			stmt.executeUpdate("UPDATE FuturesStrategySettings SET settingValue='true' WHERE settingKey LIKE 'PRESET_BIASFREE92K.%.enableOrbRetest'");
			stmt.executeUpdate("UPDATE FuturesStrategySettings SET settingValue='false' WHERE settingKey LIKE 'PRESET_BIASFREE92K.%.allowOrbLongs'");
			stmt.executeUpdate("UPDATE FuturesStrategySettings SET settingValue='false' WHERE settingKey LIKE 'PRESET_BIASFREE92K.%.allowOrbShorts'");
			stmt.executeUpdate("UPDATE FuturesStrategySettings SET settingValue='true' WHERE settingKey LIKE 'PRESET_BIASFREE92K.%.allowShorts'");
			stmt.executeUpdate("UPDATE FuturesStrategySettings SET settingValue='true' WHERE settingKey LIKE 'PRESET_BIASFREE92K.%.allowOrbRetestLongs'");
			stmt.executeUpdate("UPDATE FuturesStrategySettings SET settingValue='true' WHERE settingKey LIKE 'PRESET_BIASFREE92K.%.allowOrbRetestShorts'");
			stmt.executeUpdate("UPDATE FuturesStrategySettings SET settingValue='3' WHERE settingKey LIKE 'PRESET_BIASFREE92K.%.orb.maxTradesPerDay'");
		}
	}

	private static void printRun(String symbol, int id) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT numTrades,totalProfit,returnPct,winRate,profitFactor,maxDrawdownPct,dailyLossBreaches,trailingDrawdownBreaches,maeBreaches,ruleViolation "
					 + "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID=?")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					System.out.println(symbol
						+ "," + rs.getInt("numTrades")
						+ "," + round(rs.getDouble("totalProfit"))
						+ "," + round(rs.getDouble("returnPct"))
						+ "," + round(rs.getDouble("winRate"))
						+ "," + round(rs.getDouble("profitFactor"))
						+ "," + round(rs.getDouble("maxDrawdownPct"))
						+ "," + rs.getInt("dailyLossBreaches")
						+ "," + rs.getInt("trailingDrawdownBreaches")
						+ "," + rs.getInt("maeBreaches")
						+ "," + rs.getInt("ruleViolation"));
				} else {
					System.out.println(symbol + ",0,0.00,0.00,0.00,0.00,0.00,0,0,0,1");
				}
			}
		}
	}

	private static String round(double value) {
		return String.format("%.2f", value);
	}
}
