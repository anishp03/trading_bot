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

public class Orb2TargetedRetestBranchRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final String[] ALL_SYMBOLS = new String[] { "MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL" };
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-22";
	private static final String PRESET = "biasfree92k";
	private static final String SLOT = FuturesManager.strategyPresetSlot(PRESET);
	private static final String PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private static class Variant {
		private final String name;
		private final int nqBars;
		private final int mnqBars;
		private final int m2kBars;
		private final int mclBars;

		private Variant(String name, int nqBars, int mnqBars, int m2kBars, int mclBars) {
			this.name = name;
			this.nqBars = nqBars;
			this.mnqBars = mnqBars;
			this.m2kBars = m2kBars;
			this.mclBars = mclBars;
		}
	}

	private static final Variant[] VARIANTS = new Variant[] {
		new Variant("current_all_b2", 2, 2, 2, 2),
		new Variant("m2k_only_b1", 2, 2, 1, 2),
		new Variant("m2k_mcl_b1", 2, 2, 1, 1),
		new Variant("m2k_mnq_b1", 2, 1, 1, 2),
		new Variant("all_approved_b1", 1, 1, 1, 1)
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
		Path analysisDb = outputDir.resolve("orb2-targeted-retest-branch-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		copyWalIfPresent(sourceDb, analysisDb);

		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", futuresDataDir.toString());
		FuturesManager.initializeStore();

		System.out.println("ANALYSIS_DB=" + analysisDb);
		System.out.println("variant,runId,trades,pnl,returnPct,winPct,pf,maxDdPct,orb2Trades,orb2Pnl,orb2WinPct,ruleViolation");
		for (Variant variant : VARIANTS) {
			applyCurrentThresholds();
			set("NQ", "orbRetestMinBarsAfterBreak", String.valueOf(variant.nqBars));
			set("MNQ", "orbRetestMinBarsAfterBreak", String.valueOf(variant.mnqBars));
			set("M2K", "orbRetestMinBarsAfterBreak", String.valueOf(variant.m2kBars));
			set("MCL", "orbRetestMinBarsAfterBreak", String.valueOf(variant.mclBars));
			int id = runPortfolio();
			printResult(variant.name, id);
		}
	}

	private static void copyWalIfPresent(Path sourceDb, Path analysisDb) throws Exception {
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void applyCurrentThresholds() throws Exception {
		for (String symbol : ALL_SYMBOLS) {
			set(symbol, "orbRetestAllowedSymbols", "NQ,MNQ,MCL,M2K");
			set(symbol, "orbRetestMinBreakVolumeRatio", "1.0");
			set(symbol, "orbRetestMinRetestVolumeRatio", "1.0");
			set(symbol, "orbRetestMaxExtensionPctOfRange", "0.35");
			set(symbol, "orbRetestMinBarsAfterBreak", "2");
			set(symbol, "orbRetestRequireEmaAlignment", "false");
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

	private static int runPortfolio() {
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
			true
		);
	}

	private static void printResult(String variant, int id) throws Exception {
		Result portfolio = portfolioResult(id);
		Result orb2 = strategyResult(id, "ORB2");
		System.out.println(variant
			+ "," + id
			+ "," + portfolio.trades
			+ "," + round(portfolio.pnl)
			+ "," + round(portfolio.returnPct)
			+ "," + round(portfolio.winPct)
			+ "," + round(portfolio.pf)
			+ "," + round(portfolio.maxDdPct)
			+ "," + orb2.trades
			+ "," + round(orb2.pnl)
			+ "," + round(orb2.winPct)
			+ "," + portfolio.ruleViolation);
	}

	private static Result portfolioResult(int id) throws Exception {
		Result result = new Result();
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT numTrades,totalProfit,returnPct,winRate,profitFactor,maxDrawdownPct,ruleViolation "
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
					result.ruleViolation = rs.getInt("ruleViolation");
				}
			}
		}
		return result;
	}

	private static Result strategyResult(int id, String code) throws Exception {
		Result result = new Result();
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT COUNT(*) AS trades, COALESCE(SUM(pnl),0) AS pnl, "
					 + "COALESCE(100.0 * SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0),0) AS winPct "
					 + "FROM FuturesPortfolioTrades WHERE portfolioBacktestID=? AND strategyCode=?")) {
			stmt.setInt(1, id);
			stmt.setString(2, code);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					result.trades = rs.getInt("trades");
					result.pnl = rs.getDouble("pnl");
					result.winPct = rs.getDouble("winPct");
				}
			}
		}
		return result;
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
		private int ruleViolation;
	}
}
