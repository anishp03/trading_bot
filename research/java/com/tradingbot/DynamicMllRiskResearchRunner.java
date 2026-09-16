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

public class DynamicMllRiskResearchRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final String DEFAULT_START_DATE = "2024-05-01";
	private static final String DEFAULT_END_DATE = "2026-05-22";
	private static final String PRESET = "bestbiasfree";
	private static final boolean USE_SAVED_RISK = false;
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private static final class AccountProfile {
		private final String label;
		private final String fundedProfile;
		private final double accountSize;
		private final double maxTrailingDrawdown;
		private final double dailyLossLimit;
		private final double maxRiskPerTrade;
		private final int maxContracts;
		private final double maxAggregateMiniUnits;

		private AccountProfile(String label, String fundedProfile, double accountSize, double maxTrailingDrawdown, double dailyLossLimit, double maxRiskPerTrade, int maxContracts, double maxAggregateMiniUnits) {
			this.label = label;
			this.fundedProfile = fundedProfile;
			this.accountSize = accountSize;
			this.maxTrailingDrawdown = maxTrailingDrawdown;
			this.dailyLossLimit = dailyLossLimit;
			this.maxRiskPerTrade = maxRiskPerTrade;
			this.maxContracts = maxContracts;
			this.maxAggregateMiniUnits = maxAggregateMiniUnits;
		}
	}

	private static final class Scenario {
		private final String label;
		private final boolean dynamicMll;
		private final double maxMultiplier;
		private final boolean scaleAggregateMiniUnits;

		private Scenario(String label, boolean dynamicMll, double maxMultiplier, boolean scaleAggregateMiniUnits) {
			this.label = label;
			this.dynamicMll = dynamicMll;
			this.maxMultiplier = maxMultiplier;
			this.scaleAggregateMiniUnits = scaleAggregateMiniUnits;
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
		String startDate = args.length > 2 && !args[2].trim().isEmpty() ? args[2].trim() : DEFAULT_START_DATE;
		String endDate = args.length > 3 && !args[3].trim().isEmpty() ? args[3].trim() : DEFAULT_END_DATE;
		Path outputDir = backendDir.resolve("target/dynamic-mll-risk");
		Files.createDirectories(outputDir);
		Path analysisDb = outputDir.resolve("dynamic-mll-risk-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		copyWalIfPresent(sourceDb, analysisDb);

		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", futuresDataDir.toString());
		FuturesManager.initializeStore();

		System.out.println("ANALYSIS_DB=" + analysisDb);
		System.out.println("FUTURES_DATA_DIR=" + futuresDataDir);
		System.out.println("SYMBOLS=" + SYMBOLS);
		System.out.println("DATES=" + startDate + ".." + endDate);
		System.out.println("PRESET=" + PRESET);
		System.out.println("USE_SAVED_RISK=" + USE_SAVED_RISK);
		System.out.println("POLICY,PROFILE,RUN_ID,TRADES,PNL,RETURN_PCT,AVG_TRADE,WIN_PCT,PF,MAX_DD_PCT,MAX_INTRADAY_LOSS,MAX_MAE,MAX_UNITS,RISK_REJ,EXPOSURE_REJ,BREACHES,RULE");

		AccountProfile[] profiles = new AccountProfile[] {
			new AccountProfile("50K", "TOPSTEP_50K_RESEARCH", 50000.0, 2000.0, 1000.0, 700.0, 50, 5.0),
			new AccountProfile("100K", "TOPSTEP_100K_RESEARCH", 100000.0, 3000.0, 2000.0, 1400.0, 100, 10.0),
			new AccountProfile("150K", "TOPSTEP_150K_RESEARCH", 150000.0, 4500.0, 3000.0, 2100.0, 150, 15.0)
		};

		Scenario[] scenarios = new Scenario[] {
			new Scenario("baseline_current_eod_mll", false, 1.0, false),
			new Scenario("dynamic_mll_risk_1_25x", true, 1.25, false),
			new Scenario("dynamic_mll_risk_1_50x", true, 1.50, false),
			new Scenario("dynamic_mll_risk_2_00x", true, 2.00, false),
			new Scenario("dynamic_mll_risk_units_1_50x", true, 1.50, true),
			new Scenario("dynamic_mll_risk_units_2_00x", true, 2.00, true)
		};

		for (int profileIndex = 0; profileIndex < profiles.length; profileIndex++) {
			AccountProfile profile = profiles[profileIndex];
			System.out.println("PROFILE_PARAMS," + profile.label + ",accountSize=" + round(profile.accountSize)
				+ ",mll=" + round(profile.maxTrailingDrawdown)
				+ ",dll=" + round(profile.dailyLossLimit)
				+ ",maxRisk=" + round(profile.maxRiskPerTrade)
				+ ",maxUnits=" + round(profile.maxAggregateMiniUnits));
			for (int index = 0; index < scenarios.length; index++) {
				Scenario scenario = scenarios[index];
				int id = runScenario(profile, scenario, startDate, endDate);
				printRun(scenario.label, profile.label, id);
			}
		}
	}

	private static int runScenario(AccountProfile profile, Scenario scenario, String startDate, String endDate) {
		if (scenario.dynamicMll) {
			return FuturesManager.generateDynamicMllRiskPortfolioBacktest(
				SYMBOLS,
				startDate,
				endDate,
				profile.accountSize,
				profile.maxTrailingDrawdown,
				profile.dailyLossLimit,
				profile.maxRiskPerTrade,
				profile.maxContracts,
				1.24,
				1.0,
				3,
				profile.maxContracts,
				profile.maxAggregateMiniUnits,
				USE_SAVED_RISK,
				0.0,
				profile.fundedProfile,
				PRESET,
				0,
				true,
				true,
				true,
				scenario.maxMultiplier,
				scenario.scaleAggregateMiniUnits
			);
		}
		return FuturesManager.generatePortfolioBacktest(
			SYMBOLS,
			startDate,
			endDate,
			profile.accountSize,
			profile.maxTrailingDrawdown,
			profile.dailyLossLimit,
			profile.maxRiskPerTrade,
			profile.maxContracts,
			1.24,
			1.0,
			3,
			profile.maxContracts,
			profile.maxAggregateMiniUnits,
			USE_SAVED_RISK,
			0.0,
			profile.fundedProfile,
			PRESET,
			0,
			true,
			true,
			true
		);
	}

	private static void copyWalIfPresent(Path sourceDb, Path analysisDb) throws Exception {
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void printRun(String label, String profile, int id) throws Exception {
		if (id <= 0) {
			System.out.println(label + "," + profile + "," + id + ",0,0,0,0,0,0,0,0,0,0,0,0,1");
			return;
		}
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT portfolioBacktestID,totalProfit,returnPct,winRate,numTrades,profitFactor,maxDrawdownPct,maxIntradayLoss,maxAggregateMae,maxConcurrentMiniUnits,riskRejections,exposureRejections,dailyLossBreaches,trailingDrawdownBreaches,maeBreaches,ruleViolation "
					 + "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID=?")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					int trades = rs.getInt("numTrades");
					System.out.println(label
						+ "," + profile
						+ "," + rs.getInt("portfolioBacktestID")
						+ "," + trades
						+ "," + round(rs.getDouble("totalProfit"))
						+ "," + round(rs.getDouble("returnPct"))
						+ "," + round(trades == 0 ? 0.0 : rs.getDouble("totalProfit") / trades)
						+ "," + round(rs.getDouble("winRate"))
						+ "," + round(rs.getDouble("profitFactor"))
						+ "," + round(rs.getDouble("maxDrawdownPct"))
						+ "," + round(rs.getDouble("maxIntradayLoss"))
						+ "," + round(rs.getDouble("maxAggregateMae"))
						+ "," + round(rs.getDouble("maxConcurrentMiniUnits"))
						+ "," + rs.getInt("riskRejections")
						+ "," + rs.getInt("exposureRejections")
						+ "," + rs.getInt("dailyLossBreaches") + "/" + rs.getInt("trailingDrawdownBreaches") + "/" + rs.getInt("maeBreaches")
						+ "," + rs.getInt("ruleViolation"));
				}
			}
		}
		printStrategyBreakdown(label, profile, id);
	}

	private static void printStrategyBreakdown(String label, String profile, int id) throws Exception {
		System.out.println("STRATEGY_BREAKDOWN " + label + " " + profile);
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT strategyCode, COUNT(*) AS trades, COALESCE(SUM(pnl),0) AS pnl, COALESCE(AVG(pnl),0) AS avgPnl, "
					 + "COALESCE(100.0 * SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0),0) AS winRate "
					 + "FROM FuturesPortfolioTrades WHERE portfolioBacktestID=? GROUP BY strategyCode ORDER BY pnl DESC LIMIT 10")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					System.out.println(label
						+ ",profile=" + profile
						+ ",strategy=" + rs.getString("strategyCode")
						+ ",trades=" + rs.getInt("trades")
						+ ",pnl=" + round(rs.getDouble("pnl"))
						+ ",avg=" + round(rs.getDouble("avgPnl"))
						+ ",win=" + round(rs.getDouble("winRate")));
				}
			}
		}
	}

	private static String round(double value) {
		return String.format("%.2f", value);
	}
}
