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
import java.util.LinkedHashMap;
import java.util.Map;

public class ConsistencyImprovementResearchRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final String[] ALL_SYMBOLS = new String[] { "MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL" };
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-06-17";
	private static final String PROFILE = "TOPSTEP_50K";
	private static final String PRESET = "bestbiasfree";
	private static final String SLOT = FuturesManager.strategyPresetSlot(PRESET);
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private interface ScenarioAction {
		void apply() throws Exception;
	}

	private static class Scenario {
		private final String name;
		private final String thesis;
		private final boolean dtmEnabled;
		private final ScenarioAction action;

		private Scenario(String name, String thesis, boolean dtmEnabled, ScenarioAction action) {
			this.name = name;
			this.thesis = thesis;
			this.dtmEnabled = dtmEnabled;
			this.action = action;
		}
	}

	public static void main(String[] args) throws Exception {
		Path backendDir = Paths.get("").toAbsolutePath();
		Path sourceDb = args.length > 0 && !args[0].trim().isEmpty()
			? Paths.get(args[0]).toAbsolutePath()
			: backendDir.resolve("target/consistency-research/shared-settings-source.db").toAbsolutePath();
		Path futuresDataDir = args.length > 1 && !args[1].trim().isEmpty()
			? Paths.get(args[1]).toAbsolutePath()
			: backendDir.resolve("market_data/futures").toAbsolutePath();

		Path outputDir = backendDir.resolve("target/consistency-research");
		Files.createDirectories(outputDir);
		Path analysisDb = outputDir.resolve("tradingbot-consistency-research-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		copyWalIfPresent(sourceDb, analysisDb);

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
		ensureLabelTable();

		Map<String, String> baseSettings = loadSettings();
		System.out.println("ANALYSIS_DB=" + analysisDb);
		System.out.println("FUTURES_DATA_DIR=" + futuresDataDir);
		System.out.println("scenario,runId,dtmEnabled,trades,pnl,winPct,pf,maxDdPct,posDayPct,posWeekPct,posMonthPct,avgWin,avgLoss,payoff,avgRR,dailyBreaches,maeBreaches,ruleViolation,top3MonthPct,omomTrades,omomPnl,omomPayoff,thesis");
		for (Scenario scenario : scenarios()) {
			try {
				resetSettings(baseSettings);
				scenario.action.apply();
				int id = runPortfolio(scenario);
				labelRun(id, scenario);
				printResult(scenario, id);
			} catch (Exception e) {
				System.err.println("Scenario failed: " + scenario.name + " - " + e.getMessage());
				e.printStackTrace(System.err);
				System.out.println(csv(scenario.name)
					+ ",,," + "ERROR,,,,,,,,,,,,,,,,,,,"
					+ csv(scenario.thesis));
			}
		}
	}

	private static Scenario[] scenarios() {
		return new Scenario[] {
			new Scenario("baseline_dtm_on", "Current high-count settings with DTM enabled.", true, noop()),
			new Scenario("baseline_dtm_off", "Current high-count settings with DTM disabled to isolate DTM contribution.", false, noop()),
			new Scenario("omom_off_all", "Remove the high-frequency low-payoff OMOM module entirely.", true, new ScenarioAction() {
				public void apply() throws Exception { setModuleAll("openingMomentum", false); }
			}),
			new Scenario("omom_off_mes_m2k", "Remove the worst OMOM contract branches while leaving MNQ/MGC/NQ/MYM intact.", true, new ScenarioAction() {
				public void apply() throws Exception {
					set("MES", "openingMomentum.enabled", "false");
					set("M2K", "openingMomentum.enabled", "false");
				}
			}),
			new Scenario("omom_rr1_all", "Raise OMOM planned reward toward 1R on every enabled contract.", true, new ScenarioAction() {
				public void apply() throws Exception { setAll("openingMomentumRewardRisk", "1.0"); }
			}),
			new Scenario("omom_rr12_all", "Test whether OMOM can support a true higher payoff target.", true, new ScenarioAction() {
				public void apply() throws Exception { setAll("openingMomentumRewardRisk", "1.2"); }
			}),
			new Scenario("omom_strict_rr1", "Require stronger OMOM candle/volume quality before using a 1R target.", true, new ScenarioAction() {
				public void apply() throws Exception {
					setAll("openingMomentumRewardRisk", "1.0");
					setAll("openingMomentumVolumeRatio", "0.80");
					setAll("openingMomentumLongVolumeRatio", "0.80");
					setAll("openingMomentumShortVolumeRatio", "0.80");
					setAll("openingMomentumMinBodyPct", "35.0");
				}
			}),
			new Scenario("omom_strict_low_branches_off", "Use strict OMOM only on non-MES/M2K branches.", true, new ScenarioAction() {
				public void apply() throws Exception {
					setAll("openingMomentumRewardRisk", "1.0");
					setAll("openingMomentumVolumeRatio", "0.80");
					setAll("openingMomentumMinBodyPct", "35.0");
					set("MES", "openingMomentum.enabled", "false");
					set("M2K", "openingMomentum.enabled", "false");
				}
			}),
			new Scenario("weak_reversion_breakout_off", "Remove negative-expectancy small modules: PDB, KREV, VPB, and MIM.", true, new ScenarioAction() {
				public void apply() throws Exception {
					setModuleAll("priorDayBreakout", false);
					setModuleAll("keltnerReversion", false);
					setModuleAll("valueAreaReclaim", false);
					setModuleAll("marketIntradayMomentum", false);
					setAll("liquidityReclaimSourceCodes", "FVG,VWAP,AFT,SWEEP,SHDW");
				}
			}),
			new Scenario("liqrec_core_sources_only", "Keep LIQREC, but only from stronger source families.", true, new ScenarioAction() {
				public void apply() throws Exception {
					setAll("liquidityReclaimSourceCodes", "FVG,VWAP,AFT,SWEEP");
				}
			}),
			new Scenario("high_payoff_core", "Favor modules with healthier payoff; remove OMOM/CMOM/AFT/MIM/PDB/KREV/VPB.", true, new ScenarioAction() {
				public void apply() throws Exception {
					setModuleAll("openingMomentum", false);
					setModuleAll("closeMomentum", false);
					setModuleAll("afternoonContinuation", false);
					setModuleAll("marketIntradayMomentum", false);
					setModuleAll("priorDayBreakout", false);
					setModuleAll("keltnerReversion", false);
					setModuleAll("valueAreaReclaim", false);
					setAll("liquidityReclaimSourceCodes", "FVG,VWAP,SWEEP");
				}
			}),
			new Scenario("risk_throttle_frequency", "Keep setups, but cut frequency-expansion risk and contract pressure.", true, new ScenarioAction() {
				public void apply() throws Exception {
					setAll("openingMomentumPortfolioRiskMultiplier", "0.25");
					setAll("microShadowPortfolioRiskMultiplier", "0.08");
					setAll("microEchoPortfolioRiskMultiplier", "0.06");
					setAll("winnerFollowThroughPortfolioRiskMultiplier", "0.06");
				}
			})
		};
	}

	private static ScenarioAction noop() {
		return new ScenarioAction() {
			public void apply() {}
		};
	}

	private static void copyWalIfPresent(Path sourceDb, Path analysisDb) throws Exception {
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static Map<String, String> loadSettings() throws Exception {
		FuturesManager.initializeStore();
		Map<String, String> settings = new LinkedHashMap<String, String>();
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("SELECT settingKey, settingValue FROM FuturesStrategySettings ORDER BY settingKey");
			 ResultSet rs = stmt.executeQuery()) {
			while (rs.next()) {
				settings.put(rs.getString("settingKey"), rs.getString("settingValue"));
			}
		}
		return settings;
	}

	private static void resetSettings(Map<String, String> settings) throws Exception {
		FuturesManager.initializeStore();
		ensureLabelTable();
		try (Connection conn = DatabaseManager.getConnection();
			 Statement clear = conn.createStatement();
			 PreparedStatement insert = conn.prepareStatement("INSERT INTO FuturesStrategySettings (settingKey, settingValue) VALUES (?, ?)")) {
			clear.executeUpdate("DELETE FROM FuturesStrategySettings");
			for (Map.Entry<String, String> entry : settings.entrySet()) {
				insert.setString(1, entry.getKey());
				insert.setString(2, entry.getValue());
				insert.addBatch();
			}
			insert.executeBatch();
		}
	}

	private static void ensureLabelTable() throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement()) {
			stmt.execute("CREATE TABLE IF NOT EXISTS ResearchRunLabels (portfolioBacktestID INTEGER PRIMARY KEY, runner TEXT, scenarioName TEXT, thesis TEXT, createdAt TEXT)");
		}
	}

	private static void setModuleAll(String module, boolean enabled) throws Exception {
		for (String symbol : ALL_SYMBOLS) {
			set(symbol, module + ".enabled", Boolean.toString(enabled));
		}
	}

	private static void setAll(String key, String value) throws Exception {
		for (String symbol : ALL_SYMBOLS) {
			set(symbol, key, value);
		}
	}

	private static void set(String symbol, String key, String value) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("INSERT OR REPLACE INTO FuturesStrategySettings (settingKey, settingValue) VALUES (?, ?)")) {
			stmt.setString(1, SLOT + "." + symbol + "." + key);
			stmt.setString(2, value);
			stmt.executeUpdate();
		}
	}

	private static int runPortfolio(Scenario scenario) {
		return FuturesManager.generatePortfolioBacktest(
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
			scenario.dtmEnabled
		);
	}

	private static void labelRun(int id, Scenario scenario) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("INSERT OR REPLACE INTO ResearchRunLabels (portfolioBacktestID, runner, scenarioName, thesis, createdAt) VALUES (?, ?, ?, ?, ?)")) {
			stmt.setInt(1, id);
			stmt.setString(2, "ConsistencyImprovementResearchRunner");
			stmt.setString(3, scenario.name);
			stmt.setString(4, scenario.thesis);
			stmt.setString(5, LocalDateTime.now().toString());
			stmt.executeUpdate();
		}
	}

	private static void printResult(Scenario scenario, int id) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "WITH daily AS (SELECT substr(openedAt,1,10) day, SUM(pnl) pnl FROM FuturesPortfolioTrades WHERE portfolioBacktestID=? GROUP BY day), "
				 + "weekly AS (SELECT substr(openedAt,1,4)||'-W'||strftime('%W', substr(openedAt,1,10)) week, SUM(pnl) pnl FROM FuturesPortfolioTrades WHERE portfolioBacktestID=? GROUP BY week), "
				 + "monthly AS (SELECT substr(openedAt,1,7) month, SUM(pnl) pnl FROM FuturesPortfolioTrades WHERE portfolioBacktestID=? GROUP BY month), "
				 + "ranked_monthly AS (SELECT pnl, ROW_NUMBER() OVER (ORDER BY pnl DESC) rank_pos FROM monthly), "
				 + "omom AS (SELECT * FROM FuturesPortfolioTrades WHERE portfolioBacktestID=? AND COALESCE(NULLIF(sourceStrategyCode,''), strategyCode)='OMOM') "
				 + "SELECT b.numTrades,b.totalProfit,b.winRate,b.profitFactor,b.maxDrawdownPct,b.dailyLossBreaches,b.maeBreaches,b.ruleViolation, "
				 + "100.0*(SELECT SUM(pnl>0) FROM daily)/(SELECT COUNT(*) FROM daily) posDayPct, "
				 + "100.0*(SELECT SUM(pnl>0) FROM weekly)/(SELECT COUNT(*) FROM weekly) posWeekPct, "
				 + "100.0*(SELECT SUM(pnl>0) FROM monthly)/(SELECT COUNT(*) FROM monthly) posMonthPct, "
				 + "(SELECT AVG(CASE WHEN pnl>0 THEN pnl END) FROM FuturesPortfolioTrades WHERE portfolioBacktestID=?) avgWin, "
				 + "(SELECT AVG(CASE WHEN pnl<0 THEN pnl END) FROM FuturesPortfolioTrades WHERE portfolioBacktestID=?) avgLoss, "
				 + "(SELECT AVG(CASE WHEN pnl>0 THEN pnl END)/ABS(AVG(CASE WHEN pnl<0 THEN pnl END)) FROM FuturesPortfolioTrades WHERE portfolioBacktestID=?) payoff, "
				 + "(SELECT AVG(CASE WHEN ABS(entryPrice-stopPrice)>0 THEN ABS(targetPrice-entryPrice)/ABS(entryPrice-stopPrice) END) FROM FuturesPortfolioTrades WHERE portfolioBacktestID=?) avgRR, "
				 + "100.0*(SELECT SUM(pnl) FROM ranked_monthly WHERE rank_pos<=3)/NULLIF((SELECT SUM(pnl) FROM monthly),0) top3MonthPct, "
				 + "(SELECT COUNT(*) FROM omom) omomTrades, COALESCE((SELECT SUM(pnl) FROM omom),0) omomPnl, "
				 + "(SELECT AVG(CASE WHEN pnl>0 THEN pnl END)/ABS(AVG(CASE WHEN pnl<0 THEN pnl END)) FROM omom) omomPayoff "
				 + "FROM FuturesPortfolioBacktests b WHERE b.portfolioBacktestID=?")) {
			for (int i = 1; i <= 9; i++) {
				stmt.setInt(i, id);
			}
			try (ResultSet rs = stmt.executeQuery()) {
				if (!rs.next()) {
					System.out.println(scenario.name + "," + id + "," + scenario.dtmEnabled + ",missing");
					return;
				}
				System.out.println(csv(scenario.name)
					+ "," + id
					+ "," + scenario.dtmEnabled
					+ "," + rs.getInt("numTrades")
					+ "," + round(rs.getDouble("totalProfit"))
					+ "," + round(rs.getDouble("winRate"))
					+ "," + round(rs.getDouble("profitFactor"))
					+ "," + round(rs.getDouble("maxDrawdownPct"))
					+ "," + round(rs.getDouble("posDayPct"))
					+ "," + round(rs.getDouble("posWeekPct"))
					+ "," + round(rs.getDouble("posMonthPct"))
					+ "," + round(rs.getDouble("avgWin"))
					+ "," + round(rs.getDouble("avgLoss"))
					+ "," + round(rs.getDouble("payoff"))
					+ "," + round(rs.getDouble("avgRR"))
					+ "," + rs.getInt("dailyLossBreaches")
					+ "," + rs.getInt("maeBreaches")
					+ "," + rs.getInt("ruleViolation")
					+ "," + round(rs.getDouble("top3MonthPct"))
					+ "," + rs.getInt("omomTrades")
					+ "," + round(rs.getDouble("omomPnl"))
					+ "," + round(rs.getDouble("omomPayoff"))
					+ "," + csv(scenario.thesis));
			}
		}
	}

	private static String round(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return "";
		}
		return String.format("%.2f", value);
	}

	private static String csv(String value) {
		String clean = value == null ? "" : value.replace("\"", "\"\"");
		return "\"" + clean + "\"";
	}
}
