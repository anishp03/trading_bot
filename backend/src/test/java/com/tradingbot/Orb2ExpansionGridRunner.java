package com.tradingbot;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Orb2ExpansionGridRunner {
	private static final String[] SYMBOLS = new String[] { "MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL" };
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-22";
	private static final String PRESET = "biasfree92k";
	private static final String SLOT = FuturesManager.strategyPresetSlot(PRESET);
	private static final String PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private static class Variant {
		private final String name;
		private final boolean longs;
		private final boolean shorts;
		private final double breakVolumeRatio;
		private final double retestVolumeRatio;
		private final double maxExtensionPct;
		private final int minBarsAfterBreak;
		private final boolean requireEma;

		private Variant(String name, boolean longs, boolean shorts, double breakVolumeRatio, double retestVolumeRatio, double maxExtensionPct, int minBarsAfterBreak, boolean requireEma) {
			this.name = name;
			this.longs = longs;
			this.shorts = shorts;
			this.breakVolumeRatio = breakVolumeRatio;
			this.retestVolumeRatio = retestVolumeRatio;
			this.maxExtensionPct = maxExtensionPct;
			this.minBarsAfterBreak = minBarsAfterBreak;
			this.requireEma = requireEma;
		}
	}

	private static final Variant[] VARIANTS = new Variant[] {
		new Variant("base_both_v100_ext035_b2", true, true, 1.00, 1.00, 0.35, 2, false),
		new Variant("base_long_v100_ext035_b2", true, false, 1.00, 1.00, 0.35, 2, false),
		new Variant("base_short_v100_ext035_b2", false, true, 1.00, 1.00, 0.35, 2, false),
		new Variant("loose_both_v085_ext050_b1", true, true, 0.85, 0.85, 0.50, 1, false),
		new Variant("loose_long_v085_ext050_b1", true, false, 0.85, 0.85, 0.50, 1, false),
		new Variant("loose_short_v085_ext050_b1", false, true, 0.85, 0.85, 0.50, 1, false),
		new Variant("vol125_both_ext035_b2", true, true, 1.25, 1.25, 0.35, 2, false),
		new Variant("vol125_long_ext035_b2", true, false, 1.25, 1.25, 0.35, 2, false),
		new Variant("vol125_short_ext035_b2", false, true, 1.25, 1.25, 0.35, 2, false),
		new Variant("ema_both_v100_ext035_b2", true, true, 1.00, 1.00, 0.35, 2, true),
		new Variant("tight_both_v100_ext025_b2", true, true, 1.00, 1.00, 0.25, 2, false),
		new Variant("faster_both_v100_ext035_b1", true, true, 1.00, 1.00, 0.35, 1, false)
	};

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
		Path analysisDb = outputDir.resolve("orb2-expansion-grid-" + RUN_TAG + ".db");
		Path csvPath = outputDir.resolve("orb2-expansion-grid-" + RUN_TAG + ".csv");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		copyWalIfPresent(sourceDb, analysisDb);

		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", futuresDataDir.toString());
		FuturesManager.initializeStore();
		applyOrb2OnlyPreset();

		System.out.println("ANALYSIS_DB=" + analysisDb);
		System.out.println("CSV=" + csvPath);
		try (PrintWriter csv = new PrintWriter(new FileWriter(csvPath.toFile()))) {
			csv.println("symbol,variant,runId,trades,pnl,returnPct,winPct,pf,maxDdPct,dailyBreaches,trailBreaches,maeBreaches,ruleViolation");
			for (String symbol : SYMBOLS) {
				for (Variant variant : VARIANTS) {
					applyVariant(symbol, variant);
					int id = run(symbol);
					Result result = result(id);
					String row = symbol
						+ "," + variant.name
						+ "," + id
						+ "," + result.trades
						+ "," + round(result.pnl)
						+ "," + round(result.returnPct)
						+ "," + round(result.winPct)
						+ "," + round(result.pf)
						+ "," + round(result.maxDdPct)
						+ "," + result.dailyBreaches
						+ "," + result.trailBreaches
						+ "," + result.maeBreaches
						+ "," + result.ruleViolation;
					csv.println(row);
					System.out.println(row);
				}
			}
		}
		printTop(csvPath);
	}

	private static void copyWalIfPresent(Path sourceDb, Path analysisDb) throws Exception {
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void applyOrb2OnlyPreset() throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("UPDATE FuturesStrategySettings SET settingValue=? WHERE settingKey LIKE ?")) {
			stmt.setString(1, "false");
			stmt.setString(2, SLOT + ".%.enabled");
			stmt.executeUpdate();
		}
		for (String symbol : SYMBOLS) {
			set(symbol, "orb.enabled", "true");
			set(symbol, "enableOrbRetest", "true");
			set(symbol, "allowOrbLongs", "false");
			set(symbol, "allowOrbShorts", "false");
			set(symbol, "allowShorts", "true");
			set(symbol, "orb.maxTradesPerDay", "3");
		}
	}

	private static void applyVariant(String symbol, Variant variant) throws Exception {
		set(symbol, "allowOrbRetestLongs", String.valueOf(variant.longs));
		set(symbol, "allowOrbRetestShorts", String.valueOf(variant.shorts));
		set(symbol, "orbRetestAllowedSymbols", symbol);
		set(symbol, "orbRetestMinBreakVolumeRatio", String.valueOf(variant.breakVolumeRatio));
		set(symbol, "orbRetestMinRetestVolumeRatio", String.valueOf(variant.retestVolumeRatio));
		set(symbol, "orbRetestMaxExtensionPctOfRange", String.valueOf(variant.maxExtensionPct));
		set(symbol, "orbRetestMinBarsAfterBreak", String.valueOf(variant.minBarsAfterBreak));
		set(symbol, "orbRetestRequireEmaAlignment", String.valueOf(variant.requireEma));
	}

	private static void set(String symbol, String key, String value) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("INSERT OR REPLACE INTO FuturesStrategySettings (settingKey, settingValue) VALUES (?, ?)")) {
			stmt.setString(1, SLOT + "." + symbol + "." + key);
			stmt.setString(2, value);
			stmt.executeUpdate();
		}
	}

	private static int run(String symbol) {
		return FuturesManager.generatePortfolioBacktest(
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
	}

	private static Result result(int id) throws Exception {
		Result result = new Result();
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT numTrades,totalProfit,returnPct,winRate,profitFactor,maxDrawdownPct,dailyLossBreaches,trailingDrawdownBreaches,maeBreaches,ruleViolation "
					 + "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID=?")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					result.trades = rs.getInt("numTrades");
					result.pnl = rs.getDouble("totalProfit");
					result.returnPct = rs.getDouble("returnPct");
					result.winPct = rs.getDouble("winRate");
					result.pf = rs.getDouble("profitFactor");
					result.maxDdPct = rs.getDouble("maxDrawdownPct");
					result.dailyBreaches = rs.getInt("dailyLossBreaches");
					result.trailBreaches = rs.getInt("trailingDrawdownBreaches");
					result.maeBreaches = rs.getInt("maeBreaches");
					result.ruleViolation = rs.getInt("ruleViolation");
				}
			}
		}
		return result;
	}

	private static void printTop(Path csvPath) throws Exception {
		System.out.println("TOP_POSITIVE_VARIANTS");
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT symbols AS symbol, portfolioBacktestID, numTrades, totalProfit, returnPct, winRate, profitFactor, maxDrawdownPct, ruleViolation "
					 + "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID > 0 AND numTrades > 0 AND totalProfit > 0 ORDER BY totalProfit DESC LIMIT 20");
			 ResultSet rs = stmt.executeQuery()) {
			while (rs.next()) {
				System.out.println(
					rs.getString("symbol")
						+ " run=" + rs.getInt("portfolioBacktestID")
						+ " trades=" + rs.getInt("numTrades")
						+ " pnl=" + round(rs.getDouble("totalProfit"))
						+ " return=" + round(rs.getDouble("returnPct"))
						+ " win=" + round(rs.getDouble("winRate"))
						+ " pf=" + round(rs.getDouble("profitFactor"))
						+ " dd=" + round(rs.getDouble("maxDrawdownPct"))
						+ " violation=" + rs.getInt("ruleViolation")
				);
			}
		}
	}

	private static String round(double value) {
		return String.format("%.2f", value);
	}

	private static class Result {
		private int trades;
		private double pnl;
		private double returnPct;
		private double winPct;
		private double pf;
		private double maxDdPct;
		private int dailyBreaches;
		private int trailBreaches;
		private int maeBreaches;
		private int ruleViolation;
	}
}
