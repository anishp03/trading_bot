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

public class RiskRewardSizingResearchRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final String START_DATE = "2025-05-23";
	private static final String END_DATE = "2026-05-22";
	private static final String PRESET = "bestbiasfree";
	private static final String SLOT = FuturesManager.strategyPresetSlot(PRESET);
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private static final class ProfileScenario {
		private final String label;
		private final String profile;
		private final double accountSize;
		private final double maxTrailingDrawdown;
		private final double dailyLossLimit;
		private final double maxRiskPerTrade;
		private final int maxContracts;
		private final double maxAggregateMiniUnits;

		private ProfileScenario(String label, String profile, double accountSize, double maxTrailingDrawdown, double dailyLossLimit, double maxRiskPerTrade, int maxContracts, double maxAggregateMiniUnits) {
			this.label = label;
			this.profile = profile;
			this.accountSize = accountSize;
			this.maxTrailingDrawdown = maxTrailingDrawdown;
			this.dailyLossLimit = dailyLossLimit;
			this.maxRiskPerTrade = maxRiskPerTrade;
			this.maxContracts = maxContracts;
			this.maxAggregateMiniUnits = maxAggregateMiniUnits;
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
		Path outputDir = backendDir.resolve("target/risk-reward-sizing");
		Files.createDirectories(outputDir);
		Path analysisDb = outputDir.resolve("risk-reward-sizing-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		copyWalIfPresent(sourceDb, analysisDb);

		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", futuresDataDir.toString());
		FuturesManager.initializeStore();

		System.out.println("ANALYSIS_DB=" + analysisDb);
		System.out.println("FUTURES_DATA_DIR=" + futuresDataDir);
		System.out.println("POLICY,PROFILE,RUN_ID,TRADES,PNL,AVG_TRADE,WIN_PCT,PF,MAX_DD_PCT,AVG_CONTRACTS,AVG_WIN,AVG_LOSS,MAX_WIN,MAX_LOSS,AVG_RR,RULE");
		runPolicy("current");
		applyOneRSettingsPolicy();
		runPolicy("settings_1R");
	}

	private static void copyWalIfPresent(Path sourceDb, Path analysisDb) throws Exception {
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void runPolicy(String policy) throws Exception {
		ProfileScenario[] profiles = new ProfileScenario[] {
			new ProfileScenario("50K", "TOPSTEP_50K", 50000.0, 2000.0, 1000.0, 700.0, 50, 5.0),
			new ProfileScenario("100K", "TOPSTEP_100K", 100000.0, 3000.0, 2000.0, 1400.0, 100, 10.0),
			new ProfileScenario("150K", "TOPSTEP_150K", 150000.0, 4500.0, 3000.0, 2100.0, 150, 15.0)
		};
		for (int index = 0; index < profiles.length; index++) {
			ProfileScenario scenario = profiles[index];
			int id = FuturesManager.generatePortfolioBacktest(
				SYMBOLS,
				START_DATE,
				END_DATE,
				scenario.accountSize,
				scenario.maxTrailingDrawdown,
				scenario.dailyLossLimit,
				scenario.maxRiskPerTrade,
				scenario.maxContracts,
				1.24,
				1.0,
				3,
				scenario.maxContracts,
				scenario.maxAggregateMiniUnits,
				false,
				0.0,
				scenario.profile,
				PRESET,
				0,
				true
			);
			printRun(policy, scenario.label, id);
		}
	}

	private static void applyOneRSettingsPolicy() throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("UPDATE FuturesStrategySettings SET settingValue='1.0' WHERE settingKey LIKE '" + SLOT + ".%.%RewardRisk'");
			stmt.executeUpdate("UPDATE FuturesStrategySettings SET settingValue='1.0' WHERE settingKey LIKE '" + SLOT + ".%.minRewardRisk'");
			stmt.executeUpdate("UPDATE FuturesStrategySettings SET settingValue='false' WHERE settingKey LIKE '" + SLOT + ".%.enableAdaptiveExits'");
			stmt.executeUpdate("UPDATE FuturesStrategySettings SET settingValue='0.0' WHERE settingKey LIKE '" + SLOT + ".%.adaptive%TargetBoost'");
			stmt.executeUpdate("UPDATE FuturesStrategySettings SET settingValue='1.0' WHERE settingKey LIKE '" + SLOT + ".%.adaptiveMaxRewardRisk'");
		}
	}

	private static void printRun(String policy, String profile, int id) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement summary = conn.prepareStatement(
				 "SELECT numTrades,totalProfit,winRate,profitFactor,maxDrawdownPct,ruleViolation,ruleMessage "
					 + "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID=?");
			 PreparedStatement tradeStats = conn.prepareStatement(
				 "SELECT AVG(contracts) avgContracts, "
					 + "AVG(CASE WHEN pnl > 0 THEN pnl END) avgWin, "
					 + "AVG(CASE WHEN pnl < 0 THEN pnl END) avgLoss, "
					 + "MAX(pnl) maxWin, MIN(pnl) maxLoss, "
					 + "AVG(ABS(targetPrice-entryPrice)/NULLIF(ABS(entryPrice-stopPrice),0)) avgRr "
					 + "FROM FuturesPortfolioTrades WHERE portfolioBacktestID=?")) {
			summary.setInt(1, id);
			tradeStats.setInt(1, id);
			try (ResultSet rs = summary.executeQuery(); ResultSet trades = tradeStats.executeQuery()) {
				if (rs.next() && trades.next()) {
					int tradeCount = rs.getInt("numTrades");
					double pnl = rs.getDouble("totalProfit");
					System.out.println(policy
						+ "," + profile
						+ "," + id
						+ "," + tradeCount
						+ "," + round(pnl)
						+ "," + round(tradeCount == 0 ? 0.0 : pnl / tradeCount)
						+ "," + round(rs.getDouble("winRate"))
						+ "," + round(rs.getDouble("profitFactor"))
						+ "," + round(rs.getDouble("maxDrawdownPct"))
						+ "," + round(trades.getDouble("avgContracts"))
						+ "," + round(trades.getDouble("avgWin"))
						+ "," + round(trades.getDouble("avgLoss"))
						+ "," + round(trades.getDouble("maxWin"))
						+ "," + round(trades.getDouble("maxLoss"))
						+ "," + round(trades.getDouble("avgRr"))
						+ "," + rs.getInt("ruleViolation"));
				} else {
					System.out.println(policy + "," + profile + "," + id + ",0,0,0,0,0,0,0,0,0,0,0,0,1");
				}
			}
		}
	}

	private static String round(double value) {
		return String.format("%.2f", value);
	}
}
