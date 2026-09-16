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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class WindowFullSampleResearchRunner {
	private static final String RUNNER = "WindowFullSampleResearchRunner";
	private static final String RESEARCH_RELAXED_WINDOWS_PROPERTY = "tradingbot.research.relaxedWindows";
	private static final String BASE_PRESET = "94k";
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final List<String> SYMBOL_LIST = Arrays.asList("MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL");
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-22";
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private static final class RunSummary {
		private String scenario;
		private int id;
		private int trades;
		private double pnl;
		private double winRate;
		private double profitFactor;
		private int ruleViolation;
		private String ruleMessage;
	}

	public static void main(String[] args) throws Exception {
		Path backendDir = Paths.get("").toAbsolutePath();
		Path sourceDb = args.length > 0
			? Paths.get(args[0]).toAbsolutePath()
			: backendDir.resolve("tradingbot.db").toAbsolutePath();
		Path outputDir = backendDir.resolve("target/window-strategy-analysis");
		Files.createDirectories(outputDir);
		Path analysisDb = outputDir.resolve("tradingbot-window-fullsample-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", backendDir.resolve("market_data/futures").toString());
		FuturesManager.initializeStore();

		RunSummary baseline = runExisting("fullsample_baseline_current_94k", BASE_PRESET, "");
		RunSummary removed = runUnnecessaryRemoved();

		StringBuilder report = new StringBuilder();
		report.append("# Full-Sample Window Removal Check\n\n");
		report.append("- Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(" local\n");
		report.append("- Analysis DB copy: `").append(analysisDb).append("`\n");
		report.append("- Risk mode: `CUSTOM` with large daily/trailing limits; position/risk sizing otherwise mirrors the 50k research setup.\n\n");
		appendRun(report, baseline);
		appendRun(report, removed);
		report.append("\n## Removed-Window Strategy Breakdown\n\n");
		appendStrategyBreakdown(report, removed.id);
		Path reportPath = outputDir.resolve("window-fullsample-analysis-" + RUN_TAG + ".md");
		Files.write(reportPath, report.toString().getBytes("UTF-8"));

		System.out.println("REPORT=" + reportPath);
		System.out.println("ANALYSIS_DB=" + analysisDb);
		printRun(baseline);
		printRun(removed);
	}

	private static RunSummary runUnnecessaryRemoved() throws Exception {
		String preset = "analysis_fullsample_unnecessary_windows_removed_" + RUN_TAG;
		copyPresetRows(BASE_PRESET, preset);
		String slot = FuturesManager.strategyPresetSlot(preset);
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, slot);
			relaxUnnecessaryWindowCandidates(settings);
			FuturesManager.saveFuturesStrategySettings(symbol, slot, settings);
		}
		return runExisting("fullsample_unnecessary_windows_removed", preset, "VWAP,VRCL,KELT,KREV,MRVWAP,SWEEP");
	}

	private static RunSummary runExisting(String scenario, String preset, String relaxedHardWindowCodes) throws Exception {
		int id = runBacktest(preset, relaxedHardWindowCodes);
		labelRun(id, scenario);
		RunSummary summary = loadRunSummary(id);
		summary.scenario = scenario;
		return summary;
	}

	private static int runBacktest(String preset, String relaxedHardWindowCodes) {
		String previous = System.getProperty(RESEARCH_RELAXED_WINDOWS_PROPERTY);
		if (relaxedHardWindowCodes == null || relaxedHardWindowCodes.trim().isEmpty()) {
			System.clearProperty(RESEARCH_RELAXED_WINDOWS_PROPERTY);
		} else {
			System.setProperty(RESEARCH_RELAXED_WINDOWS_PROPERTY, relaxedHardWindowCodes);
		}
		try {
			return FuturesManager.generatePortfolioBacktest(
				SYMBOLS,
				START_DATE,
				END_DATE,
				50000.0,
				100000.0,
				100000.0,
				700.0,
				50,
				1.24,
				1.0,
				3,
				50,
				5.0,
				false,
				0.0,
				"CUSTOM",
				preset,
				0,
				true
			);
		} finally {
			if (previous == null) {
				System.clearProperty(RESEARCH_RELAXED_WINDOWS_PROPERTY);
			} else {
				System.setProperty(RESEARCH_RELAXED_WINDOWS_PROPERTY, previous);
			}
		}
	}

	private static void relaxUnnecessaryWindowCandidates(FuturesManager.FuturesStrategySettings settings) {
		settings.sweepShortSkipStartMinute = 0;
		settings.sweepShortSkipEndMinute = 0;
		settings.fvgStartMinute = 570;
		settings.fvgEndMinute = 930;
		settings.fvgSkipStartMinute = 0;
		settings.fvgSkipEndMinute = 0;
		settings.fvgLongSkipDowMask = 0;
		settings.fvgShortSkipDowMask = 0;
		settings.fvgLongDowWindowSkipMask = 0;
		settings.fvgLongDowWindowSkipStartMinute = 0;
		settings.fvgLongDowWindowSkipEndMinute = 0;
		settings.fvgShortDowWindowSkipMask = 0;
		settings.fvgShortDowWindowSkipStartMinute = 0;
		settings.fvgShortDowWindowSkipEndMinute = 0;
		settings.priorDayBreakoutStartMinute = 570;
		settings.priorDayBreakoutEndMinute = 930;
		settings.priorDayBreakoutLongSkipStartMinute = 0;
		settings.priorDayBreakoutLongSkipEndMinute = 0;
		settings.priorDayBreakoutShortSkipStartMinute = 0;
		settings.priorDayBreakoutShortSkipEndMinute = 0;
		settings.priorDayBreakoutShortSecondSkipStartMinute = 0;
		settings.priorDayBreakoutShortSecondSkipEndMinute = 0;
		settings.priorDayBreakoutShortThirdSkipStartMinute = 0;
		settings.priorDayBreakoutShortThirdSkipEndMinute = 0;
		settings.vwapStartMinute = 0;
		settings.vwapEndMinute = 0;
		settings.vwapSkipStartMinute = 0;
		settings.vwapSkipEndMinute = 0;
		settings.vwapShortSkipDowMask = 0;
		settings.valueAreaStartMinute = 570;
		settings.valueAreaEndMinute = 920;
		settings.microScalpStartMinute = 570;
		settings.microScalpEndMinute = 920;
		settings.microScalpLongStartMinute = 0;
		settings.microScalpLongEndMinute = 0;
		settings.microScalpShortStartMinute = 0;
		settings.microScalpShortEndMinute = 0;
		settings.microScalpSkipStartMinute = 0;
		settings.microScalpSkipEndMinute = 0;
		settings.microScalpSkipDowMask = 0;
		settings.microShadowStartMinute = 570;
		settings.microShadowEndMinute = 920;
		settings.microEchoStartMinute = 570;
		settings.microEchoEndMinute = 920;
		settings.winnerFollowThroughStartMinute = 570;
		settings.winnerFollowThroughEndMinute = 920;
	}

	private static void copyPresetRows(String sourcePreset, String targetPreset) throws Exception {
		String sourceSlot = FuturesManager.strategyPresetSlot(sourcePreset);
		String targetSlot = FuturesManager.strategyPresetSlot(targetPreset);
		String sourcePrefix = sourceSlot + ".";
		String targetPrefix = targetSlot + ".";
		try (Connection conn = DatabaseManager.getConnection()) {
			conn.setAutoCommit(false);
			try (PreparedStatement delete = conn.prepareStatement("DELETE FROM FuturesStrategySettings WHERE settingKey LIKE ?");
				 PreparedStatement insert = conn.prepareStatement("INSERT OR REPLACE INTO FuturesStrategySettings (settingKey, settingValue) SELECT ? || substr(settingKey, ?), settingValue FROM FuturesStrategySettings WHERE settingKey LIKE ?")) {
				delete.setString(1, targetPrefix + "%");
				delete.executeUpdate();
				insert.setString(1, targetPrefix);
				insert.setInt(2, sourcePrefix.length() + 1);
				insert.setString(3, sourcePrefix + "%");
				insert.executeUpdate();
				conn.commit();
			} catch (Exception e) {
				conn.rollback();
				throw e;
			} finally {
				conn.setAutoCommit(true);
			}
		}
	}

	private static RunSummary loadRunSummary(int id) throws Exception {
		RunSummary summary = new RunSummary();
		summary.id = id;
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("SELECT numTrades,totalProfit,winRate,profitFactor,ruleViolation,ruleMessage FROM FuturesPortfolioBacktests WHERE portfolioBacktestID=?")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					summary.trades = rs.getInt("numTrades");
					summary.pnl = rs.getDouble("totalProfit");
					summary.winRate = rs.getDouble("winRate");
					summary.profitFactor = rs.getDouble("profitFactor");
					summary.ruleViolation = rs.getInt("ruleViolation");
					summary.ruleMessage = rs.getString("ruleMessage");
				}
			}
		}
		return summary;
	}

	private static void labelRun(int id, String scenario) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 Statement create = conn.createStatement()) {
			create.execute("CREATE TABLE IF NOT EXISTS ResearchRunLabels (portfolioBacktestID INTEGER PRIMARY KEY, runner TEXT, scenarioName TEXT, createdAt TEXT)");
		}
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("INSERT OR REPLACE INTO ResearchRunLabels (portfolioBacktestID, runner, scenarioName, createdAt) VALUES (?, ?, ?, datetime('now'))")) {
			stmt.setInt(1, id);
			stmt.setString(2, RUNNER);
			stmt.setString(3, scenario);
			stmt.executeUpdate();
		}
	}

	private static void appendRun(StringBuilder report, RunSummary run) {
		report.append("- `").append(run.scenario).append("`: ")
			.append(run.trades).append(" trades, ")
			.append(money(run.pnl)).append(" PnL, ")
			.append(money(run.trades == 0 ? 0.0 : run.pnl / run.trades)).append("/trade, ")
			.append(round(run.winRate)).append("% win, PF ")
			.append(round(run.profitFactor)).append(", rule ")
			.append(run.ruleViolation == 0 ? "clean" : run.ruleMessage)
			.append("\n");
	}

	private static void appendStrategyBreakdown(StringBuilder report, int id) throws Exception {
		report.append("| Strategy | Trades | PnL | Avg/trade | Win % |\n");
		report.append("|---|---:|---:|---:|---:|\n");
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("SELECT strategyCode, COUNT(*) AS trades, SUM(pnl) AS pnl, AVG(pnl) AS avgPnl, 100.0 * SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) / COUNT(*) AS winRate FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? GROUP BY strategyCode ORDER BY pnl DESC")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					report.append("| `").append(rs.getString("strategyCode")).append("`")
						.append(" | ").append(rs.getInt("trades"))
						.append(" | ").append(money(rs.getDouble("pnl")))
						.append(" | ").append(money(rs.getDouble("avgPnl")))
						.append(" | ").append(round(rs.getDouble("winRate")))
						.append(" |\n");
				}
			}
		}
	}

	private static void printRun(RunSummary run) {
		System.out.println(run.scenario + " id=" + run.id + " pnl=" + round(run.pnl) + " trades=" + run.trades + " win=" + round(run.winRate) + " pf=" + round(run.profitFactor));
	}

	private static String money(double value) {
		return "$" + round(value);
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
