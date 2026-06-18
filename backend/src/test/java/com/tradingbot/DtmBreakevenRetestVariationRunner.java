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

public class DtmBreakevenRetestVariationRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final String[] ALL_SYMBOLS = new String[] { "MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL" };
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-06-17";
	private static final String PROFILE = "TOPSTEP_50K";
	private static final String PRESET = "bestbiasfree";
	private static final String SLOT = FuturesManager.strategyPresetSlot(PRESET);
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private static class Variant {
		private final String name;
		private final boolean dtmEnabled;
		private final double dtmBreakevenTriggerR;
		private final boolean disableLegacyManagedStops;

		private Variant(String name, boolean dtmEnabled, double dtmBreakevenTriggerR, boolean disableLegacyManagedStops) {
			this.name = name;
			this.dtmEnabled = dtmEnabled;
			this.dtmBreakevenTriggerR = dtmBreakevenTriggerR;
			this.disableLegacyManagedStops = disableLegacyManagedStops;
		}
	}

	private static final Variant[] VARIANTS = new Variant[] {
		new Variant("current_dtm_be075", true, 0.75, false),
		new Variant("dtm_delayed_be125", true, 1.25, false),
		new Variant("dtm_delayed_be200", true, 2.00, false),
		new Variant("dtm_no_breakeven_900", true, 9.00, false),
		new Variant("dtm_off_current_legacy", false, 0.75, false),
		new Variant("dtm_off_legacy_stops_disabled", false, 0.75, true)
	};

	public static void main(String[] args) throws Exception {
		Path backendDir = Paths.get("").toAbsolutePath();
		Path sourceDb = args.length > 0 && !args[0].trim().isEmpty()
			? Paths.get(args[0]).toAbsolutePath()
			: backendDir.resolve("tradingbot.db").toAbsolutePath();
		Path futuresDataDir = args.length > 1 && !args[1].trim().isEmpty()
			? Paths.get(args[1]).toAbsolutePath()
			: backendDir.resolve("market_data/futures").toAbsolutePath();

		Path outputDir = backendDir.resolve("target/dtm-breakeven-retest");
		Files.createDirectories(outputDir);
		Path analysisDb = outputDir.resolve("tradingbot-dtm-breakeven-retest-" + RUN_TAG + ".db");
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

		System.out.println("ANALYSIS_DB=" + analysisDb);
		System.out.println("FUTURES_DATA_DIR=" + futuresDataDir);
		System.out.println("variant,runId,dtmEnabled,dtmBreakevenTriggerR,trades,pnl,returnPct,winPct,pf,maxDdPct,maxIntradayLoss,maxMae,stopTrades,managedStopTrades,lossCutTrades,targetTrades,dtmBreakevenTrades,dtmTrailTrades,dtmPartialTrades,dtmEarlyCutTrades,dtmExtensionTrades,ruleViolation");
		for (Variant variant : VARIANTS) {
			System.setProperty("tradingbot.dtm.breakevenTriggerR", String.valueOf(variant.dtmBreakevenTriggerR));
			applyVariantSettings(variant);
			int id = runPortfolio(variant);
			printResult(variant, id);
		}
	}

	private static void copyWalIfPresent(Path sourceDb, Path analysisDb) throws Exception {
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void applyVariantSettings(Variant variant) throws Exception {
		for (String symbol : ALL_SYMBOLS) {
			if (variant.disableLegacyManagedStops) {
				set(symbol, "managedStopBreakevenTriggerR", "2.5");
				set(symbol, "managedStopTrailTriggerR", "3.0");
				set(symbol, "enableManagedGivebackExit", "false");
				set(symbol, "enableEarlyLossCut", "false");
			}
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

	private static int runPortfolio(Variant variant) {
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
			variant.dtmEnabled
		);
	}

	private static void printResult(Variant variant, int id) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement runStmt = conn.prepareStatement(
				 "SELECT numTrades,totalProfit,returnPct,winRate,profitFactor,maxDrawdownPct,maxIntradayLoss,maxAggregateMae,ruleViolation "
					 + "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID=?")) {
			runStmt.setInt(1, id);
			try (ResultSet rs = runStmt.executeQuery()) {
				if (!rs.next()) {
					System.out.println(variant.name + "," + id + "," + variant.dtmEnabled + "," + variant.dtmBreakevenTriggerR + ",missing");
					return;
				}
				ExitMix mix = exitMix(conn, id);
				System.out.println(variant.name
					+ "," + id
					+ "," + variant.dtmEnabled
					+ "," + round(variant.dtmBreakevenTriggerR)
					+ "," + rs.getInt("numTrades")
					+ "," + round(rs.getDouble("totalProfit"))
					+ "," + round(rs.getDouble("returnPct"))
					+ "," + round(rs.getDouble("winRate"))
					+ "," + round(rs.getDouble("profitFactor"))
					+ "," + round(rs.getDouble("maxDrawdownPct"))
					+ "," + round(rs.getDouble("maxIntradayLoss"))
					+ "," + round(rs.getDouble("maxAggregateMae"))
					+ "," + mix.stopTrades
					+ "," + mix.managedStopTrades
					+ "," + mix.lossCutTrades
					+ "," + mix.targetTrades
					+ "," + mix.dtmBreakevenTrades
					+ "," + mix.dtmTrailTrades
					+ "," + mix.dtmPartialTrades
					+ "," + mix.dtmEarlyCutTrades
					+ "," + mix.dtmExtensionTrades
					+ "," + rs.getInt("ruleViolation"));
			}
		}
	}

	private static ExitMix exitMix(Connection conn, int id) throws Exception {
		ExitMix mix = new ExitMix();
		try (PreparedStatement stmt = conn.prepareStatement(
			"SELECT exitReason, COALESCE(tradeNotes, '') AS tradeNotes FROM FuturesPortfolioTrades WHERE portfolioBacktestID=?")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					String exitReason = clean(rs.getString("exitReason")).toLowerCase();
					String notes = clean(rs.getString("tradeNotes"));
					if (exitReason.contains("stop loss")) mix.stopTrades++;
					if (exitReason.contains("managed stop")) mix.managedStopTrades++;
					if (exitReason.contains("adaptive loss cut")) mix.lossCutTrades++;
					if (exitReason.contains("target")) mix.targetTrades++;
					if (notes.contains("DTM_MOVE_STOP_BREAKEVEN")) mix.dtmBreakevenTrades++;
					if (notes.contains("DTM_TRAIL_STOP")) mix.dtmTrailTrades++;
					if (notes.contains("DTM_PARTIAL_TARGET") || notes.contains("DTM_PARTIAL_HALF_RUNNER_EXTENDED")) mix.dtmPartialTrades++;
					if (notes.contains("DTM_CUT_EARLY_THESIS_FAILED")) mix.dtmEarlyCutTrades++;
					if (notes.contains("DTM_EXTEND_TARGET_CONTINUATION") || notes.contains("DTM_EXTEND_ONE_CONTRACT_RUNNER")) mix.dtmExtensionTrades++;
				}
			}
		}
		return mix;
	}

	private static String clean(String value) {
		return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
	}

	private static String round(double value) {
		return String.format("%.2f", value);
	}

	private static class ExitMix {
		private int stopTrades;
		private int managedStopTrades;
		private int lossCutTrades;
		private int targetTrades;
		private int dtmBreakevenTrades;
		private int dtmTrailTrades;
		private int dtmPartialTrades;
		private int dtmEarlyCutTrades;
		private int dtmExtensionTrades;
	}
}
