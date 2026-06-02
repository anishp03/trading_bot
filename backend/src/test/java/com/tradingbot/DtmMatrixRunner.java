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

public class DtmMatrixRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-22";
	private static final String PRESET = "bestbiasfree";
	private static final String PROFILE = "TOPSTEP_50K";
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	public static void main(String[] args) throws Exception {
		Path backendDir = Paths.get("").toAbsolutePath();
		Path sourceDb = args.length > 0 && !args[0].trim().isEmpty()
			? Paths.get(args[0]).toAbsolutePath()
			: backendDir.resolve("tradingbot.db").toAbsolutePath();
		Path futuresDataDir = args.length > 1 && !args[1].trim().isEmpty()
			? Paths.get(args[1]).toAbsolutePath()
			: backendDir.resolve("market_data/futures").toAbsolutePath();
		String label = args.length > 2 && !args[2].trim().isEmpty() ? args[2].trim() : "dtm-matrix";

		Path outputDir = backendDir.resolve("target/dtm-matrix");
		Files.createDirectories(outputDir);
		Path analysisDb = outputDir.resolve("tradingbot-" + safeFileName(label) + "-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		copyWalIfPresent(sourceDb, analysisDb);

		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", futuresDataDir.toString());
		FuturesManager.initializeStore();

		System.out.println("ANALYSIS_DB=" + analysisDb);
		runCase("baseline", false, false, false);
		runCase("dtm_safe_checkpoint", true, false, false);
		runCase("dtm_dynamic_tp_sl", true, true, false);
		runCase("dtm_one_contract_extension", true, false, true);
		runCase("dtm_dynamic_and_one_contract", true, true, true);
		runCase("dtm_selected_upgrade", true, true, false);
		runCase("dtm_finalized_half_runner", true, true, false, true, "partial", "tight", 2, "any");
		runCase("dtm_experimental_half_runner_partial_tight", true, true, false, true, "partial", "tight");
		runCase("dtm_experimental_half_runner_partial_balanced", true, true, false, true, "partial", "balanced");
		runCase("dtm_experimental_half_runner_partial_loose", true, true, false, true, "partial", "loose");
		runCase("dtm_experimental_half_runner_min11_any", true, true, false, true, "partial", "tight", 11, "any");
		runCase("dtm_experimental_half_runner_min11_medium", true, true, false, true, "partial", "tight", 11, "medium");
		runCase("dtm_experimental_half_runner_target", true, true, false, true, "target");
		runCase("dtm_experimental_half_runner_acceptance", true, true, false, true, "acceptance");
	}

	private static void runCase(String label, boolean dtm, boolean dynamicProtectiveOrders, boolean oneContractExtension) throws Exception {
		runCase(label, dtm, dynamicProtectiveOrders, oneContractExtension, false);
	}

	private static void runCase(String label, boolean dtm, boolean dynamicProtectiveOrders, boolean oneContractExtension, boolean experimentalHalfRunner) throws Exception {
		runCase(label, dtm, dynamicProtectiveOrders, oneContractExtension, experimentalHalfRunner, "partial");
	}

	private static void runCase(String label, boolean dtm, boolean dynamicProtectiveOrders, boolean oneContractExtension, boolean experimentalHalfRunner, String experimentalTrigger) throws Exception {
		runCase(label, dtm, dynamicProtectiveOrders, oneContractExtension, experimentalHalfRunner, experimentalTrigger, "tight");
	}

	private static void runCase(String label, boolean dtm, boolean dynamicProtectiveOrders, boolean oneContractExtension, boolean experimentalHalfRunner, String experimentalTrigger, String experimentalStop) throws Exception {
		runCase(label, dtm, dynamicProtectiveOrders, oneContractExtension, experimentalHalfRunner, experimentalTrigger, experimentalStop, 2, "any");
	}

	private static void runCase(String label, boolean dtm, boolean dynamicProtectiveOrders, boolean oneContractExtension, boolean experimentalHalfRunner, String experimentalTrigger, String experimentalStop, int experimentalMinContracts, String experimentalTargetMode) throws Exception {
		System.setProperty("tradingbot.dtm.dynamicProtectiveOrders", Boolean.toString(dynamicProtectiveOrders));
		System.setProperty("tradingbot.dtm.oneContractExtension", Boolean.toString(oneContractExtension));
		System.setProperty("tradingbot.dtm.experimentalHalfRunner", Boolean.toString(experimentalHalfRunner));
		System.setProperty("tradingbot.dtm.experimentalHalfRunnerTrigger", experimentalTrigger == null ? "partial" : experimentalTrigger);
		System.setProperty("tradingbot.dtm.experimentalHalfRunnerStop", experimentalStop == null ? "tight" : experimentalStop);
		System.setProperty("tradingbot.dtm.experimentalHalfRunnerMinContracts", Integer.toString(experimentalMinContracts));
		System.setProperty("tradingbot.dtm.experimentalHalfRunnerTargetMode", experimentalTargetMode == null ? "any" : experimentalTargetMode);
		int id = FuturesManager.generatePortfolioBacktest(
			SYMBOLS,
			START_DATE,
			END_DATE,
			50000.0,
			2500.0,
			1250.0,
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
			dtm
		);
		try (Connection conn = DatabaseManager.getConnection()) {
			printRun(conn, label, id, dtm, dynamicProtectiveOrders, oneContractExtension, experimentalHalfRunner, experimentalTrigger, experimentalStop, experimentalMinContracts, experimentalTargetMode);
			printExitReasons(conn, id);
			printDtmActions(conn, id);
		}
	}

	private static void printRun(Connection conn, String label, int id, boolean dtm, boolean dynamicProtectiveOrders, boolean oneContractExtension, boolean experimentalHalfRunner, String experimentalTrigger, String experimentalStop, int experimentalMinContracts, String experimentalTargetMode) throws Exception {
		String sql = "SELECT totalProfit, winRate, numTrades, profitFactor, maxDrawdownPct, maxIntradayLoss, maxAggregateMae, dailyLossBreaches, trailingDrawdownBreaches, maeBreaches, ruleViolation, ruleMessage, portfolioSettingsJson "
			+ "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ?";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					System.out.println(
						"CASE label=" + label
							+ " id=" + id
							+ " dtm=" + dtm
							+ " dynamicTpSl=" + dynamicProtectiveOrders
							+ " oneContractExtension=" + oneContractExtension
							+ " experimentalHalfRunner=" + experimentalHalfRunner
							+ " experimentalTrigger=" + clean(experimentalTrigger)
							+ " experimentalStop=" + clean(experimentalStop)
							+ " experimentalMinContracts=" + experimentalMinContracts
							+ " experimentalTargetMode=" + clean(experimentalTargetMode)
							+ " pnl=" + round(rs.getDouble("totalProfit"))
							+ " trades=" + rs.getInt("numTrades")
							+ " winRate=" + round(rs.getDouble("winRate"))
							+ " pf=" + round(rs.getDouble("profitFactor"))
							+ " maxDdPct=" + round(rs.getDouble("maxDrawdownPct"))
							+ " maxIntradayLoss=" + round(rs.getDouble("maxIntradayLoss"))
							+ " maxAggregateMae=" + round(rs.getDouble("maxAggregateMae"))
							+ " dailyLossBreaches=" + rs.getInt("dailyLossBreaches")
							+ " trailingBreaches=" + rs.getInt("trailingDrawdownBreaches")
							+ " maeBreaches=" + rs.getInt("maeBreaches")
							+ " violation=" + rs.getInt("ruleViolation")
							+ " dtmEvals=" + jsonNumberText(rs.getString("portfolioSettingsJson"), "dtmEvaluations")
							+ " dtmL2=" + jsonNumberText(rs.getString("portfolioSettingsJson"), "dtmOrderFlowAvailable")
							+ " dtmL2Missing=" + jsonNumberText(rs.getString("portfolioSettingsJson"), "dtmOrderFlowMissing")
							+ " dtmDecisions=" + jsonNumberText(rs.getString("portfolioSettingsJson"), "dtmDecisions")
							+ " dtmBreakeven=" + jsonNumberText(rs.getString("portfolioSettingsJson"), "dtmBreakevenMoves")
							+ " dtmPartials=" + jsonNumberText(rs.getString("portfolioSettingsJson"), "dtmPartialActions")
							+ " dtmTrails=" + jsonNumberText(rs.getString("portfolioSettingsJson"), "dtmTrailActions")
							+ " dtmEarlyCuts=" + jsonNumberText(rs.getString("portfolioSettingsJson"), "dtmEarlyCuts")
							+ " dtmTargetExt=" + jsonNumberText(rs.getString("portfolioSettingsJson"), "dtmTargetExtensions")
							+ " dtmOneContractExt=" + jsonNumberText(rs.getString("portfolioSettingsJson"), "dtmOneContractExtensions")
							+ " message=\"" + clean(rs.getString("ruleMessage")) + "\""
					);
				} else {
					System.out.println("CASE label=" + label + " id=" + id + " missing");
				}
			}
		}
	}

	private static void printDtmActions(Connection conn, int id) throws Exception {
		String sql = "SELECT json_extract(value, '$.actionCode') AS actionCode, COUNT(*) AS actions "
			+ "FROM FuturesPortfolioTrades, json_each(FuturesPortfolioTrades.tradeNotes, '$.dtmTimeline') "
			+ "WHERE portfolioBacktestID = ? GROUP BY actionCode ORDER BY actions DESC";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					String actionCode = clean(rs.getString("actionCode"));
					if (actionCode.length() == 0) {
						continue;
					}
					System.out.println(
						"DTM_ACTION id=" + id
							+ " action=\"" + actionCode + "\""
							+ " count=" + rs.getInt("actions")
					);
				}
			}
		} catch (Exception ignored) {
			// Older trade note rows are plain text; action counts are best-effort diagnostics only.
		}
	}

	private static void printExitReasons(Connection conn, int id) throws Exception {
		String sql = "SELECT exitReason, COUNT(*) AS trades, COALESCE(SUM(pnl), 0) AS pnl "
			+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? GROUP BY exitReason ORDER BY trades DESC, pnl DESC LIMIT 8";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					System.out.println(
						"EXIT id=" + id
							+ " reason=\"" + clean(rs.getString("exitReason")) + "\""
							+ " trades=" + rs.getInt("trades")
							+ " pnl=" + round(rs.getDouble("pnl"))
					);
				}
			}
		}
	}

	private static void copyWalIfPresent(Path sourceDb, Path analysisDb) throws Exception {
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String clean(String value) {
		return value == null ? "" : value.replace('\n', ' ').replace('"', '\'');
	}

	private static String safeFileName(String value) {
		return clean(value).replaceAll("[^A-Za-z0-9._-]+", "-");
	}

	private static String jsonNumberText(String json, String key) {
		if (json == null || key == null || key.trim().isEmpty()) {
			return "0";
		}
		String needle = "\"" + key + "\":";
		int index = json.indexOf(needle);
		if (index < 0) {
			return "0";
		}
		int start = index + needle.length();
		int end = start;
		while (end < json.length()) {
			char c = json.charAt(end);
			if ((c >= '0' && c <= '9') || c == '-' || c == '.') {
				end++;
			} else {
				break;
			}
		}
		return end > start ? json.substring(start, end) : "0";
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
