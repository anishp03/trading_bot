package com.tradingbot;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class BosRetestBacktestRunner {
	private static final String BASE_PRESET = "bestbiasfree";
	private static final String WIP_PRESET = "wip";
	private static final String WIP_SLOT = FuturesManager.strategyPresetSlot(WIP_PRESET);
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final String[] SYMBOL_LIST = new String[] { "MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL" };
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-22";
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private static final class Summary {
		private String label;
		private int id;
		private int trades;
		private double pnl;
		private double winRate;
		private double profitFactor;
		private double maxDrawdownPct;
		private int dailyLossBreaches;
		private int trailingDrawdownBreaches;
		private int maeBreaches;
		private int bosTrades;
		private double bosPnl;
		private double bosWinRate;
		private double bosAvgPnl;
	}

	public static void main(String[] args) throws Exception {
		Path backendDir = Paths.get("").toAbsolutePath();
		Path sourceDb = args.length > 0 && args[0] != null && !args[0].trim().isEmpty()
			? Paths.get(args[0]).toAbsolutePath()
			: backendDir.resolve("tradingbot.db").toAbsolutePath();
		Path futuresDataDir = args.length > 1 && args[1] != null && !args[1].trim().isEmpty()
			? Paths.get(args[1]).toAbsolutePath()
			: backendDir.resolve("market_data/futures").toAbsolutePath();
		Path outputDir = backendDir.resolve("target/bos-retest-backtest");
		Files.createDirectories(outputDir);
		Path analysisDb = outputDir.resolve("tradingbot-bosrt-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		copySidecar(sourceDb, analysisDb, "-wal");
		copySidecar(sourceDb, analysisDb, "-shm");

		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", futuresDataDir.toString());
		FuturesManager.initializeStore();

		System.out.println("ANALYSIS_DB=" + analysisDb);
		System.out.println("FUTURES_DATA_DIR=" + futuresDataDir);
		System.out.println("WINDOW=" + START_DATE + ".." + END_DATE + " base=" + BASE_PRESET + " wip=" + WIP_PRESET);

		Summary baseline = run("BASELINE_BESTBIASFREE", BASE_PRESET);
		printSummary(baseline);

		resetWipFromBase();
		configureBosRetestSolo();
		Summary solo = run("SOLO_BOSRT", WIP_PRESET);
		printSummary(solo);
		printSymbolBreakdown(solo.id);

		resetWipFromBase();
		enableBosRetestOverlay();
		Summary additive = run("BESTBIASFREE_PLUS_BOSRT", WIP_PRESET);
		printSummary(additive);
		printSymbolBreakdown(additive.id);
	}

	private static void copySidecar(Path sourceDb, Path analysisDb, String suffix) throws Exception {
		File source = new File(sourceDb.toString() + suffix);
		if (source.exists() && source.length() > 0L) {
			Files.copy(source.toPath(), Paths.get(analysisDb.toString() + suffix), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void resetWipFromBase() {
		String result = FuturesManager.createStrategyPreset(WIP_PRESET, BASE_PRESET);
		if (result == null || !result.contains("\"success\":true")) {
			throw new IllegalStateException("Failed to reset WIP preset from " + BASE_PRESET + ": " + result);
		}
	}

	private static void configureBosRetestSolo() throws Exception {
		for (int index = 0; index < SYMBOL_LIST.length; index++) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(SYMBOL_LIST[index], WIP_SLOT);
			disableAllStrategyToggles(settings);
			enableBosRetest(settings);
			FuturesManager.saveFuturesStrategySettings(SYMBOL_LIST[index], WIP_SLOT, settings);
		}
	}

	private static void enableBosRetestOverlay() throws Exception {
		for (int index = 0; index < SYMBOL_LIST.length; index++) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(SYMBOL_LIST[index], WIP_SLOT);
			enableBosRetest(settings);
			FuturesManager.saveFuturesStrategySettings(SYMBOL_LIST[index], WIP_SLOT, settings);
		}
	}

	private static void enableBosRetest(FuturesManager.FuturesStrategySettings settings) {
		settings.bosRetest.enabled = true;
		settings.bosRetest.maxTradesPerDay = 2;
		settings.allowBosRetestLongs = true;
		settings.allowBosRetestShorts = true;
		settings.bosRetestRequireHigherTimeframeAlignment = true;
		settings.bosRetestMinRewardRisk = 1.50;
		settings.bosRetestMinDisplacementBodyPct = 55.0;
		settings.bosRetestMinDisplacementRangeRatio = 1.25;
		settings.bosRetestMaxRiskTicks = 60.0;
	}

	private static void disableAllStrategyToggles(FuturesManager.FuturesStrategySettings settings) throws Exception {
		Field[] fields = FuturesManager.FuturesStrategySettings.class.getFields();
		for (int index = 0; index < fields.length; index++) {
			Field field = fields[index];
			Object value = field.get(settings);
			if (value instanceof FuturesManager.StrategyToggle) {
				FuturesManager.StrategyToggle toggle = (FuturesManager.StrategyToggle) value;
				toggle.enabled = false;
				toggle.maxTradesPerDay = 0;
			}
		}
	}

	private static Summary run(String label, String preset) throws Exception {
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
			"CUSTOM",
			preset,
			0,
			true,
			true,
			true
		);
		labelRun(id, label);
		return loadSummary(id, label);
	}

	private static void labelRun(int id, String label) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("UPDATE FuturesPortfolioBacktests SET dataSource = COALESCE(dataSource, '') || ? WHERE portfolioBacktestID = ?")) {
			stmt.setString(1, " | BOSRT_BACKTEST:" + label);
			stmt.setInt(2, id);
			stmt.executeUpdate();
		}
	}

	private static Summary loadSummary(int id, String label) throws Exception {
		Summary summary = new Summary();
		summary.id = id;
		summary.label = label;
		try (Connection conn = DatabaseManager.getConnection()) {
			try (PreparedStatement stmt = conn.prepareStatement(
					"SELECT totalProfit, winRate, numTrades, profitFactor, maxDrawdownPct, dailyLossBreaches, trailingDrawdownBreaches, maeBreaches "
						+ "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ?")) {
				stmt.setInt(1, id);
				try (ResultSet rs = stmt.executeQuery()) {
					if (rs.next()) {
						summary.pnl = rs.getDouble("totalProfit");
						summary.winRate = rs.getDouble("winRate");
						summary.trades = rs.getInt("numTrades");
						summary.profitFactor = rs.getDouble("profitFactor");
						summary.maxDrawdownPct = rs.getDouble("maxDrawdownPct");
						summary.dailyLossBreaches = rs.getInt("dailyLossBreaches");
						summary.trailingDrawdownBreaches = rs.getInt("trailingDrawdownBreaches");
						summary.maeBreaches = rs.getInt("maeBreaches");
					}
				}
			}
			try (PreparedStatement stmt = conn.prepareStatement(
					"SELECT COUNT(*) AS trades, COALESCE(SUM(pnl),0) AS pnl, COALESCE(AVG(pnl),0) AS avgPnl, "
						+ "COALESCE(100.0 * SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 0) AS winRate "
						+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND strategyCode = 'BOSRT'")) {
				stmt.setInt(1, id);
				try (ResultSet rs = stmt.executeQuery()) {
					if (rs.next()) {
						summary.bosTrades = rs.getInt("trades");
						summary.bosPnl = rs.getDouble("pnl");
						summary.bosAvgPnl = rs.getDouble("avgPnl");
						summary.bosWinRate = rs.getDouble("winRate");
					}
				}
			}
		}
		return summary;
	}

	private static void printSummary(Summary summary) {
		System.out.println("SUMMARY label=" + summary.label
			+ " id=" + summary.id
			+ " pnl=" + round(summary.pnl)
			+ " trades=" + summary.trades
			+ " win=" + round(summary.winRate)
			+ " pf=" + round(summary.profitFactor)
			+ " dd=" + round(summary.maxDrawdownPct)
			+ " dailyLossBreaches=" + summary.dailyLossBreaches
			+ " trailingBreaches=" + summary.trailingDrawdownBreaches
			+ " maeBreaches=" + summary.maeBreaches
			+ " bosTrades=" + summary.bosTrades
			+ " bosPnl=" + round(summary.bosPnl)
			+ " bosAvg=" + round(summary.bosAvgPnl)
			+ " bosWin=" + round(summary.bosWinRate));
	}

	private static void printSymbolBreakdown(int id) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT symbol, COUNT(*) AS trades, COALESCE(SUM(pnl),0) AS pnl, COALESCE(AVG(pnl),0) AS avgPnl, "
					 + "COALESCE(100.0 * SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 0) AS winRate "
					 + "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND strategyCode = 'BOSRT' "
					 + "GROUP BY symbol ORDER BY pnl DESC")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					System.out.println("BOSRT_SYMBOL id=" + id
						+ " symbol=" + rs.getString("symbol")
						+ " trades=" + rs.getInt("trades")
						+ " pnl=" + round(rs.getDouble("pnl"))
						+ " avg=" + round(rs.getDouble("avgPnl"))
						+ " win=" + round(rs.getDouble("winRate")));
				}
			}
		}
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
