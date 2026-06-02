package com.tradingbot;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LiqrecResearchRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final String[] SYMBOL_LIST = new String[] { "MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL" };
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-22";
	private static final String PROFILE = "TOPSTEP_50K";
	private static final String PRESET = "bestbiasfree";
	private static final String SLOT = FuturesManager.strategyPresetSlot(PRESET);
	private static final String SOURCES = "FVG,VWAP,AFT,SWEEP,PDB,KREV,SHDW,VPB";
	private static final int SOURCE_RUN_ID = 2268;
	private static final String SOURCE_DB_PROPERTY = "liqrec.source.db";
	private static final boolean RUN_QUALITATIVE_RISK = Boolean.parseBoolean(System.getProperty("liqrec.qualitativeRisk", "true"));
	private static final boolean RUN_DTM = Boolean.parseBoolean(System.getProperty("liqrec.dtm", "false"));
	private static final String DEFAULT_SOURCE_DB = "/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/backups/tradingbot_pre_bestbiasfree_20260528_082625.db";
	private static final String SOURCE_COMPONENT_FILTER = "strategyCode IN ('FVG','VWAP','AFT','SWEEP','PDB','KREV','SHDW','VPB') AND symbol IN ('MES','MNQ','NQ','MGC','ES','M2K','MYM','MCL')";

	private interface ScenarioConfig {
		void apply(FuturesManager.FuturesStrategySettings settings, String symbol);
	}

	private static class Scenario {
		final String name;
		final ScenarioConfig config;
		final boolean copySourceSettings;
		final double riskMultiplier;
		final String symbolRiskMultipliers;
		final String symbolMaxContracts;

		Scenario(String name, ScenarioConfig config) {
			this(name, false, config);
		}

		Scenario(String name, boolean copySourceSettings, ScenarioConfig config) {
			this(name, copySourceSettings, 1.0, config);
		}

		Scenario(String name, boolean copySourceSettings, double riskMultiplier, ScenarioConfig config) {
			this(name, copySourceSettings, riskMultiplier, "", config);
		}

		Scenario(String name, boolean copySourceSettings, double riskMultiplier, String symbolRiskMultipliers, ScenarioConfig config) {
			this(name, copySourceSettings, riskMultiplier, symbolRiskMultipliers, "", config);
		}

		Scenario(String name, boolean copySourceSettings, double riskMultiplier, String symbolRiskMultipliers, String symbolMaxContracts, ScenarioConfig config) {
			this.name = name;
			this.config = config;
			this.copySourceSettings = copySourceSettings;
			this.riskMultiplier = riskMultiplier;
			this.symbolRiskMultipliers = symbolRiskMultipliers == null ? "" : symbolRiskMultipliers;
			this.symbolMaxContracts = symbolMaxContracts == null ? "" : symbolMaxContracts;
		}
	}

	private static class Summary {
		int id;
		String name;
		double pnl;
		int trades;
		double winRate;
		double profitFactor;
		double drawdownPct;
		double maxIntradayLoss;
		double maxAggregateMae;
		int dailyLossBreaches;
		int trailingDrawdownBreaches;
		int maeBreaches;
		int ruleViolation;
		int overlapRejections;
		int exposureRejections;
		int riskRejections;
		int liqrecTrades;
		double liqrecPnl;
		double liqrecWinRate;
	}

	public static void main(String[] args) throws Exception {
		DatabaseManager.initializeDatabase();
		FuturesManager.initializeStore();
		requireResearchDatabase();
		importSourceRunIfAvailable();
		printSourceRunBenchmark();

		List<Scenario> scenarios = new ArrayList<Scenario>();
		scenarios.add(new Scenario("baseline_liqrec_off", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				setLiqrec(settings, false, false, 570, 930);
			}
		}));
		scenarios.add(new Scenario("liqrec_on_dedupe_all_day", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				setLiqrec(settings, true, false, 570, 930);
			}
		}));
		scenarios.add(new Scenario("liqrec_on_duplicates_all_day", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("liqrec_on_duplicates_after_11", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				setLiqrec(settings, true, true, 660, 930);
			}
		}));
		scenarios.add(new Scenario("liqrec_on_duplicates_midday", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				setLiqrec(settings, true, true, 690, 855);
			}
		}));
		scenarios.add(new Scenario("liqrec_positive_symbols_no_mcl", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				setLiqrec(settings, !"MCL".equals(symbol), true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("liqrec_es_mnq_mgc_m2k_mym", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				boolean enabled = Arrays.asList("ES", "MNQ", "MGC", "M2K", "MYM").contains(symbol);
				setLiqrec(settings, enabled, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("liqrec_only_direct_all_day", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("liqrec_only_direct_no_mgc", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, !"MGC".equals(symbol), true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("liqrec_only_direct_no_mgc_no_es", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				boolean enabled = !"MGC".equals(symbol) && !"ES".equals(symbol);
				setLiqrec(settings, enabled, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("liqrec_source_sleeve_targets_mnq_mes_mcl", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				enableSourceSleeve(settings, symbol);
				boolean enabled = Arrays.asList("MNQ", "MES", "MCL").contains(symbol);
				setLiqrec(settings, enabled, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("liqrec_source_sleeve_targets_mnq_mes", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				enableSourceSleeve(settings, symbol);
				boolean enabled = Arrays.asList("MNQ", "MES").contains(symbol);
				setLiqrec(settings, enabled, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("liqrec_source_sleeve_targets_mnq_mes_mcl_after11", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				enableSourceSleeve(settings, symbol);
				boolean enabled = Arrays.asList("MNQ", "MES", "MCL").contains(symbol);
				setLiqrec(settings, enabled, true, 660, 930);
			}
		}));
		scenarios.add(new Scenario("liqrec_component_sleeve_duplicates", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				setLiqrec(settings, true, true, 570, 930);
				enableSourceSleeve(settings, symbol);
			}
		}));
		scenarios.add(new Scenario("liqrec_component_sleeve_no_mcl", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				setLiqrec(settings, !"MCL".equals(symbol), true, 570, 930);
				if (!"MCL".equals(symbol)) {
					enableSourceSleeve(settings, symbol);
				}
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_all_day", true, new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_all_day", true, new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_after_11", true, new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 660, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_after_11_mnq30", true, 1.0, "", "MNQ=30", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 660, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_after_11_mnq24", true, 1.0, "", "MNQ=24", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 660, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_all_day_mnq40", true, 1.0, "", "MNQ=40", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_all_day_mnq36", true, 1.0, "", "MNQ=36", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_all_day_mnq32", true, 1.0, "", "MNQ=32", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_all_day_mnq30", true, 1.0, "", "MNQ=30", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_all_day_mnq29", true, 1.0, "", "MNQ=29", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_all_day_mnq28", true, 1.0, "", "MNQ=28", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_all_day_mnq27", true, 1.0, "", "MNQ=27", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_all_day_mnq24", true, 1.0, "", "MNQ=24", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_all_day_mnq30_no_mcl", true, 1.0, "", "MNQ=30", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, !"MCL".equals(symbol), true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_all_day_mnq30_no_m2k_mcl", true, 1.0, "", "MNQ=30", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				boolean enabled = !"M2K".equals(symbol) && !"MCL".equals(symbol);
				setLiqrec(settings, enabled, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_no_m2k_mcl", true, new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				boolean enabled = !"M2K".equals(symbol) && !"MCL".equals(symbol);
				setLiqrec(settings, enabled, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_all_day_risk095", true, 0.95, new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_all_day_risk090", true, 0.90, new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_all_day_risk085", true, 0.85, new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_mnq075_mcl080", true, 1.0, "MNQ=0.75,MCL=0.80", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_mnq080_mcl080", true, 1.0, "MNQ=0.80,MCL=0.80", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));
		scenarios.add(new Scenario("source2268_exact_settings_liqrec_only_mnq075_mcl070", true, 1.0, "MNQ=0.75,MCL=0.70", new ScenarioConfig() {
			@Override
			public void apply(FuturesManager.FuturesStrategySettings settings, String symbol) {
				disableBaseModules(settings);
				setLiqrec(settings, true, true, 570, 930);
			}
		}));

		System.out.println("DB=" + DatabaseManager.getDatabasePath());
		System.out.println("WINDOW=" + START_DATE + ".." + END_DATE + " profile=" + PROFILE + " preset=" + PRESET
			+ " qualitativeRisk=" + RUN_QUALITATIVE_RISK
			+ " dtm=" + RUN_DTM);
		for (Scenario scenario : scenarios) {
			if (!shouldRunScenario(scenario.name)) {
				continue;
			}
			applyScenario(scenario);
			Summary summary = runScenario(scenario.name);
			printSummary(summary);
			printStrategyBreakdown(summary.id);
			printLiqrecSymbolBreakdown(summary.id);
		}
	}

	private static boolean shouldRunScenario(String scenarioName) {
		String filter = System.getProperty("liqrec.scenarios", "").trim();
		if (filter.length() == 0) {
			return true;
		}
		for (String part : filter.split(",")) {
			if (scenarioName.equals(part.trim())) {
				return true;
			}
		}
		return false;
	}

	private static void applyScenario(Scenario scenario) throws Exception {
		if (scenario.copySourceSettings) {
			copySourceSettingsToSlot(SOURCE_RUN_ID, SLOT);
		}
		if (scenario.riskMultiplier > 0.0 && Math.abs(scenario.riskMultiplier - 1.0) > 0.000001) {
			scaleSlotRisk(SLOT, scenario.riskMultiplier);
		}
		if (scenario.symbolRiskMultipliers.length() > 0) {
			scaleSymbolSlotRisk(SLOT, scenario.symbolRiskMultipliers);
		}
		if (scenario.symbolMaxContracts.length() > 0) {
			setSymbolMaxContracts(SLOT, scenario.symbolMaxContracts);
		}
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, SLOT);
			scenario.config.apply(settings, symbol);
			FuturesManager.saveFuturesStrategySettings(symbol, SLOT, settings);
		}
	}

	private static void scaleSlotRisk(String targetSlot, double multiplier) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement update = conn.prepareStatement("UPDATE FuturesStrategySettings SET settingValue = CAST(CAST(settingValue AS REAL) * ? AS TEXT) WHERE settingKey LIKE ?")) {
			update.setDouble(1, multiplier);
			update.setString(2, targetSlot + ".%.risk.maxRiskPerTrade");
			update.executeUpdate();
		}
	}

	private static void scaleSymbolSlotRisk(String targetSlot, String symbolMultipliers) throws Exception {
		for (String part : symbolMultipliers.split(",")) {
			String[] pieces = part.split("=", 2);
			if (pieces.length != 2) {
				continue;
			}
			String symbol = pieces[0].trim().toUpperCase();
			double multiplier = Double.parseDouble(pieces[1].trim());
			try (Connection conn = DatabaseManager.getConnection();
				 PreparedStatement update = conn.prepareStatement("UPDATE FuturesStrategySettings SET settingValue = CAST(CAST(settingValue AS REAL) * ? AS TEXT) WHERE settingKey = ?")) {
				update.setDouble(1, multiplier);
				update.setString(2, targetSlot + "." + symbol + ".risk.maxRiskPerTrade");
				update.executeUpdate();
			}
		}
	}

	private static void setSymbolMaxContracts(String targetSlot, String symbolMaxContracts) throws Exception {
		for (String part : symbolMaxContracts.split(",")) {
			String[] pieces = part.split("=", 2);
			if (pieces.length != 2) {
				continue;
			}
			String symbol = pieces[0].trim().toUpperCase();
			int maxContracts = Integer.parseInt(pieces[1].trim());
			try (Connection conn = DatabaseManager.getConnection();
				 PreparedStatement update = conn.prepareStatement("UPDATE FuturesStrategySettings SET settingValue = ? WHERE settingKey = ?")) {
				update.setString(1, Integer.toString(maxContracts));
				update.setString(2, targetSlot + "." + symbol + ".risk.maxContracts");
				update.executeUpdate();
			}
		}
	}

	private static void requireResearchDatabase() {
		String dbPath = DatabaseManager.getDatabasePath();
		if (dbPath == null || !dbPath.contains("/target/")) {
			throw new IllegalStateException("LiqrecResearchRunner mutates strategy settings; run it only against a copied DB under backend/target with -Dtradingbot.db.path=...");
		}
	}

	private static void importSourceRunIfAvailable() throws Exception {
		try (Connection conn = DatabaseManager.getConnection()) {
			if (portfolioRunExists(conn, SOURCE_RUN_ID)) {
				return;
			}
			String sourcePath = System.getProperty(SOURCE_DB_PROPERTY, DEFAULT_SOURCE_DB);
			File sourceDb = new File(sourcePath);
			if (!sourceDb.isFile()) {
				System.out.println("SOURCE2268_IMPORT_SKIPPED missing=" + sourcePath);
				return;
			}
			try (PreparedStatement attach = conn.prepareStatement("ATTACH DATABASE ? AS source2268")) {
				attach.setString(1, sourceDb.getAbsolutePath());
				attach.executeUpdate();
			}
			try {
				copySourceRunRows(conn);
			} finally {
				try (Statement detach = conn.createStatement()) {
					detach.executeUpdate("DETACH DATABASE source2268");
				}
			}
		}
	}

	private static boolean portfolioRunExists(Connection conn, int portfolioBacktestId) throws Exception {
		try (PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ? LIMIT 1")) {
			stmt.setInt(1, portfolioBacktestId);
			try (ResultSet rs = stmt.executeQuery()) {
				return rs.next();
			}
		}
	}

	private static void copySourceRunRows(Connection conn) throws Exception {
		try (PreparedStatement stmt = conn.prepareStatement("INSERT OR IGNORE INTO FuturesPortfolioBacktests SELECT * FROM source2268.FuturesPortfolioBacktests WHERE portfolioBacktestID = ?")) {
			stmt.setInt(1, SOURCE_RUN_ID);
			stmt.executeUpdate();
		}
		try (PreparedStatement stmt = conn.prepareStatement("INSERT OR IGNORE INTO FuturesPortfolioTrades SELECT * FROM source2268.FuturesPortfolioTrades WHERE portfolioBacktestID = ?")) {
			stmt.setInt(1, SOURCE_RUN_ID);
			stmt.executeUpdate();
		}
		try (PreparedStatement stmt = conn.prepareStatement("INSERT OR REPLACE INTO FuturesPortfolioBacktestSettings SELECT * FROM source2268.FuturesPortfolioBacktestSettings WHERE portfolioBacktestID = ?")) {
			stmt.setInt(1, SOURCE_RUN_ID);
			stmt.executeUpdate();
		}
	}

	private static void copySourceSettingsToSlot(int sourceRunId, String targetSlot) throws Exception {
		try (Connection conn = DatabaseManager.getConnection()) {
			try (PreparedStatement delete = conn.prepareStatement("DELETE FROM FuturesStrategySettings WHERE settingKey LIKE ?")) {
				delete.setString(1, targetSlot + ".%");
				delete.executeUpdate();
			}
			try (PreparedStatement insert = conn.prepareStatement("INSERT OR REPLACE INTO FuturesStrategySettings (settingKey, settingValue) SELECT ? || settingKey, settingValue FROM FuturesPortfolioBacktestSettings WHERE portfolioBacktestID = ?")) {
				insert.setString(1, targetSlot + ".");
				insert.setInt(2, sourceRunId);
				int copied = insert.executeUpdate();
				if (copied <= 0) {
					throw new IllegalStateException("No source settings copied for portfolioBacktestID=" + sourceRunId);
				}
			}
		}
	}

	private static void printSourceRunBenchmark() throws Exception {
		try (Connection conn = DatabaseManager.getConnection()) {
			if (!portfolioRunExists(conn, SOURCE_RUN_ID)) {
				System.out.println("SOURCE2268_CANONICAL missing=true");
				return;
			}
			try (PreparedStatement stmt = conn.prepareStatement(
					"SELECT COUNT(*) AS trades, COALESCE(SUM(pnl),0) AS pnl, "
					+ "SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) AS wins, "
					+ "COALESCE(SUM(CASE WHEN pnl > 0 THEN pnl ELSE 0 END),0) AS grossProfit, "
					+ "ABS(COALESCE(SUM(CASE WHEN pnl < 0 THEN pnl ELSE 0 END),0)) AS grossLoss "
					+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND " + SOURCE_COMPONENT_FILTER)) {
				stmt.setInt(1, SOURCE_RUN_ID);
				try (ResultSet rs = stmt.executeQuery()) {
					if (rs.next()) {
						int trades = rs.getInt("trades");
						double winRate = trades == 0 ? 0.0 : (rs.getDouble("wins") * 100.0) / trades;
						double grossLoss = rs.getDouble("grossLoss");
						double profitFactor = grossLoss <= 0.0 ? rs.getDouble("grossProfit") : rs.getDouble("grossProfit") / grossLoss;
						System.out.println("SOURCE2268_CANONICAL trades=" + trades
							+ " pnl=" + round(rs.getDouble("pnl"))
							+ " win=" + round(winRate)
							+ " pf=" + round(profitFactor));
					}
				}
			}
		}
	}

	private static Summary runScenario(String name) throws Exception {
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
			RUN_QUALITATIVE_RISK,
			RUN_DTM
		);
		labelRun(id, name);
		Summary summary = loadSummary(id);
		summary.name = name;
		return summary;
	}

	private static void setLiqrec(FuturesManager.FuturesStrategySettings settings, boolean enabled, boolean duplicates, int startMinute, int endMinute) {
		settings.liquidityReclaim.enabled = enabled;
		settings.liquidityReclaim.maxTradesPerDay = 50;
		settings.liquidityReclaimSourceCodes = SOURCES;
		settings.liquidityReclaimAllowDuplicates = duplicates;
		settings.liquidityReclaimStartMinute = startMinute;
		settings.liquidityReclaimEndMinute = endMinute;
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
	}

	private static void enableSourceSleeve(FuturesManager.FuturesStrategySettings settings, String symbol) {
		settings.afternoonContinuation.enabled = true;
		settings.afternoonContinuation.maxTradesPerDay = Math.max(settings.afternoonContinuation.maxTradesPerDay, 5);
		if ("MNQ".equals(symbol) || "NQ".equals(symbol)) {
			settings.fvg.enabled = true;
			settings.fvg.maxTradesPerDay = Math.max(settings.fvg.maxTradesPerDay, 6);
			settings.keltnerReversion.enabled = true;
			settings.keltnerReversion.maxTradesPerDay = Math.max(settings.keltnerReversion.maxTradesPerDay, 8);
		} else if ("MGC".equals(symbol)) {
			settings.keltnerReversion.enabled = true;
			settings.keltnerReversion.maxTradesPerDay = Math.max(settings.keltnerReversion.maxTradesPerDay, 8);
		} else if ("ES".equals(symbol)) {
			settings.vwapPullback.enabled = true;
			settings.vwapPullback.maxTradesPerDay = Math.max(settings.vwapPullback.maxTradesPerDay, 6);
			settings.sweep.enabled = true;
			settings.sweep.maxTradesPerDay = Math.max(settings.sweep.maxTradesPerDay, 6);
		} else if ("M2K".equals(symbol)) {
			settings.valueAreaReclaim.enabled = true;
			settings.valueAreaReclaim.maxTradesPerDay = Math.max(settings.valueAreaReclaim.maxTradesPerDay, 6);
		} else if ("MYM".equals(symbol)) {
			settings.sweep.enabled = true;
			settings.sweep.maxTradesPerDay = Math.max(settings.sweep.maxTradesPerDay, 6);
			settings.microShadow.enabled = true;
			settings.microShadow.maxTradesPerDay = Math.max(settings.microShadow.maxTradesPerDay, 6);
		} else if ("MES".equals(symbol)) {
			settings.microShadow.enabled = true;
			settings.microShadow.maxTradesPerDay = Math.max(settings.microShadow.maxTradesPerDay, 6);
		} else if ("MCL".equals(symbol)) {
			settings.priorDayBreakout.enabled = true;
			settings.priorDayBreakout.maxTradesPerDay = Math.max(settings.priorDayBreakout.maxTradesPerDay, 6);
		}
	}

	private static Summary loadSummary(int id) throws Exception {
		Summary summary = new Summary();
		summary.id = id;
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("SELECT * FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ?")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					summary.pnl = rs.getDouble("totalProfit");
					summary.trades = rs.getInt("numTrades");
					summary.winRate = rs.getDouble("winRate");
					summary.profitFactor = rs.getDouble("profitFactor");
					summary.drawdownPct = rs.getDouble("maxDrawdownPct");
					summary.maxIntradayLoss = rs.getDouble("maxIntradayLoss");
					summary.maxAggregateMae = rs.getDouble("maxAggregateMae");
					summary.dailyLossBreaches = rs.getInt("dailyLossBreaches");
					summary.trailingDrawdownBreaches = rs.getInt("trailingDrawdownBreaches");
					summary.maeBreaches = rs.getInt("maeBreaches");
					summary.ruleViolation = rs.getInt("ruleViolation");
					summary.overlapRejections = rs.getInt("overlapRejections");
					summary.exposureRejections = rs.getInt("exposureRejections");
					summary.riskRejections = rs.getInt("riskRejections");
				}
			}
		}
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) AS trades, COALESCE(SUM(pnl), 0) AS pnl, SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) AS wins FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND strategyCode = 'LIQREC'")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					summary.liqrecTrades = rs.getInt("trades");
					summary.liqrecPnl = rs.getDouble("pnl");
					summary.liqrecWinRate = summary.liqrecTrades == 0 ? 0.0 : (rs.getDouble("wins") * 100.0) / summary.liqrecTrades;
				}
			}
		}
		return summary;
	}

	private static void printSummary(Summary summary) {
		System.out.println("SCENARIO " + summary.name
			+ " id=" + summary.id
			+ " pnl=" + round(summary.pnl)
			+ " trades=" + summary.trades
			+ " win=" + round(summary.winRate)
			+ " pf=" + round(summary.profitFactor)
			+ " dd=" + round(summary.drawdownPct)
			+ " intraday=" + round(summary.maxIntradayLoss)
			+ " mae=" + round(summary.maxAggregateMae)
			+ " breaches=" + summary.dailyLossBreaches + "/" + summary.trailingDrawdownBreaches + "/" + summary.maeBreaches
			+ " violation=" + summary.ruleViolation
			+ " rejects=" + summary.overlapRejections + "/" + summary.exposureRejections + "/" + summary.riskRejections
			+ " liqrecTrades=" + summary.liqrecTrades
			+ " liqrecPnl=" + round(summary.liqrecPnl)
			+ " liqrecWin=" + round(summary.liqrecWinRate));
	}

	private static void printStrategyBreakdown(int id) throws Exception {
		System.out.println("STRATEGY_BREAKDOWN id=" + id);
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("SELECT strategyCode, COUNT(*) AS trades, COALESCE(SUM(pnl), 0) AS pnl, AVG(pnl) AS avgPnl, SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) AS wins FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? GROUP BY strategyCode ORDER BY pnl DESC")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					int trades = rs.getInt("trades");
					double winRate = trades == 0 ? 0.0 : (rs.getDouble("wins") * 100.0) / trades;
					System.out.println("  " + rs.getString("strategyCode")
						+ " trades=" + trades
						+ " pnl=" + round(rs.getDouble("pnl"))
						+ " avg=" + round(rs.getDouble("avgPnl"))
						+ " win=" + round(winRate));
				}
			}
		}
	}

	private static void printLiqrecSymbolBreakdown(int id) throws Exception {
		System.out.println("LIQREC_SYMBOL_BREAKDOWN id=" + id);
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("SELECT symbol, COUNT(*) AS trades, COALESCE(SUM(pnl), 0) AS pnl, AVG(pnl) AS avgPnl, SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) AS wins FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND strategyCode = 'LIQREC' GROUP BY symbol ORDER BY pnl DESC")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					int trades = rs.getInt("trades");
					double winRate = trades == 0 ? 0.0 : (rs.getDouble("wins") * 100.0) / trades;
					System.out.println("  " + rs.getString("symbol")
						+ " trades=" + trades
						+ " pnl=" + round(rs.getDouble("pnl"))
						+ " avg=" + round(rs.getDouble("avgPnl"))
						+ " win=" + round(winRate));
				}
			}
		}
	}

	private static void labelRun(int id, String label) {
		if (id <= 0) {
			return;
		}
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("UPDATE FuturesPortfolioBacktests SET dataSource = dataSource || ' | liqrec-research:' || ? WHERE portfolioBacktestID = ?")) {
			stmt.setString(1, label);
			stmt.setInt(2, id);
			stmt.executeUpdate();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
