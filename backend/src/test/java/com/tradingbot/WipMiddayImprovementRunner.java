package com.tradingbot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;

public class WipMiddayImprovementRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K";
	private static final String START_DATE = "2025-05-26";
	private static final String END_DATE = "2026-05-13";
	private static final String PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String WIP = "wip";

	public static void main(String[] args) throws Exception {
		String variant = args.length > 0 ? args[0] : "baseline";
		boolean applyOnly = args.length > 1 && "apply-only".equalsIgnoreCase(args[1]);
		FuturesManager.createStrategyPreset(WIP, "94k");
		applyVariant(variant);
		if (applyOnly) {
			System.out.println("APPLIED_WIP_VARIANT=" + variant);
			return;
		}
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
			WIP,
			0
		);
		System.out.println("VARIANT=" + variant);
		System.out.println("RUN_ID=" + id);
		if (id <= 0) {
			return;
		}
		try (Connection conn = DatabaseManager.getConnection()) {
			printRun(conn, id);
			printMiddaySummary(conn, id);
			printBreakdown(conn, id, "strategyCode", "MIDDAY_STRATEGY");
			printBreakdown(conn, id, "symbol || '/' || strategyCode", "MIDDAY_SYMBOL_STRATEGY");
			printBreakdown(conn, id, "substr(openedAt, 12, 2)", "MIDDAY_HOUR");
			printOvertrading(conn, id);
		}
	}

	private static void applyVariant(String variant) {
		String normalized = variant == null ? "baseline" : variant.toLowerCase(Locale.ROOT);
		if ("baseline".equals(normalized)) {
			return;
		}
		if ("aft-nq".equals(normalized) || "combo".equals(normalized)) {
			FuturesManager.FuturesStrategySettings nq = FuturesManager.loadFuturesStrategySettings("NQ", FuturesManager.strategyPresetSlot(WIP));
			nq.afternoonContinuation.enabled = true;
			nq.afternoonContinuation.maxTradesPerDay = 1;
			nq.afternoonStartMinute = 780;
			nq.afternoonEndMinute = 870;
			nq.afternoonMinVolumeRatio = 0.9;
			nq.afternoonRewardRisk = 0.9;
			nq.afternoonMaxHoldBars = 35;
			FuturesManager.saveFuturesStrategySettings("NQ", FuturesManager.strategyPresetSlot(WIP), nq);
		}
		if ("rcb-mnq-nq".equals(normalized) || "combo".equals(normalized)) {
			for (String symbol : new String[] { "MNQ", "NQ" }) {
				FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP));
				settings.rangeCompressionBreakout.enabled = true;
				settings.rangeCompressionBreakout.maxTradesPerDay = 2;
				settings.rangeCompressionStartMinute = 660;
				settings.rangeCompressionEndMinute = 870;
				settings.rangeCompressionMinVolumeRatio = 0.8;
				settings.rangeCompressionRewardRisk = 0.8;
				settings.rangeCompressionMaxRiskTicks = "NQ".equals(symbol) ? 22.0 : 18.0;
				settings.rangeCompressionMaxHoldBars = 12;
				FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP), settings);
			}
		}
		if ("tlad-mnq-nq".equals(normalized) || "combo".equals(normalized)) {
			for (String symbol : new String[] { "MNQ", "NQ" }) {
				FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP));
				settings.trendLadder.enabled = true;
				settings.trendLadder.maxTradesPerDay = 2;
				settings.trendLadderStartMinute = 660;
				settings.trendLadderEndMinute = 870;
				settings.trendLadderMinVolumeRatio = 0.55;
				settings.trendLadderRewardRisk = 0.75;
				settings.trendLadderMaxRiskTicks = "NQ".equals(symbol) ? 24.0 : 18.0;
				settings.trendLadderMaxHoldBars = 12;
				FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP), settings);
			}
		}
		if ("mrvwap-mnq-nq".equals(normalized) || "combo".equals(normalized)) {
			for (String symbol : new String[] { "MNQ", "NQ" }) {
				FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP));
				settings.vwapMeanReversion.enabled = true;
				settings.vwapMeanReversion.maxTradesPerDay = 1;
				settings.meanReversionMinDistanceTicks = "NQ".equals(symbol) ? 52.0 : 42.0;
				settings.meanReversionOversoldRsi = 28.0;
				settings.meanReversionOverboughtRsi = 72.0;
				FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP), settings);
			}
		}
		if ("fvg-nq-extend".equals(normalized)) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("NQ", FuturesManager.strategyPresetSlot(WIP));
			settings.fvg.enabled = true;
			settings.fvg.maxTradesPerDay = 4;
			settings.fvgStartMinute = 660;
			settings.fvgEndMinute = 870;
			settings.fvgMinVolumeRatio = 0.25;
			settings.fvgRewardRisk = 1.0;
			FuturesManager.saveFuturesStrategySettings("NQ", FuturesManager.strategyPresetSlot(WIP), settings);
		}
		if ("fvg-nq-quality".equals(normalized)) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("NQ", FuturesManager.strategyPresetSlot(WIP));
			settings.fvg.enabled = true;
			settings.fvg.maxTradesPerDay = 3;
			settings.fvgStartMinute = 720;
			settings.fvgEndMinute = 779;
			settings.fvgMinVolumeRatio = 0.35;
			settings.fvgRewardRisk = 1.05;
			FuturesManager.saveFuturesStrategySettings("NQ", FuturesManager.strategyPresetSlot(WIP), settings);
		}
		if ("aft-early".equals(normalized)) {
			for (String symbol : new String[] { "MES", "MNQ" }) {
				FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP));
				settings.afternoonContinuation.enabled = true;
				settings.afternoonContinuation.maxTradesPerDay = "MNQ".equals(symbol) ? 2 : 1;
				settings.afternoonStartMinute = 750;
				settings.afternoonEndMinute = 870;
				settings.afternoonMinVolumeRatio = "MNQ".equals(symbol) ? 0.85 : 0.8;
				settings.afternoonRewardRisk = 0.9;
				settings.afternoonMaxHoldBars = 35;
				FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP), settings);
			}
		}
		if ("krev-bucket".equals(normalized)) {
			for (String symbol : new String[] { "MNQ", "NQ", "MGC" }) {
				FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP));
				settings.keltnerReversion.enabled = true;
				settings.keltnerReversion.maxTradesPerDay = 6;
				settings.keltnerBucketMinutes = 10;
				settings.keltnerMinVolumeRatio = "MGC".equals(symbol) ? 0.6 : 0.6;
				settings.keltnerMinBodyPct = "MGC".equals(symbol) ? 24.0 : 14.0;
				settings.keltnerMaxRiskTicks = "MGC".equals(symbol) ? 16.0 : 24.0;
				settings.keltnerMaxHoldBars = 12;
				FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP), settings);
			}
		}
		if ("trim-drag".equals(normalized)) {
			FuturesManager.FuturesStrategySettings mes = FuturesManager.loadFuturesStrategySettings("MES", FuturesManager.strategyPresetSlot(WIP));
			mes.valueAreaReclaim.enabled = false;
			mes.microScalp.enabled = false;
			FuturesManager.saveFuturesStrategySettings("MES", FuturesManager.strategyPresetSlot(WIP), mes);
		}
		if ("fvg-es-mgc".equals(normalized) || "combo".equals(normalized)) {
			for (String symbol : new String[] { "ES", "MGC" }) {
				FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP));
				settings.fvg.enabled = true;
				settings.fvg.maxTradesPerDay = 1;
				settings.fvgStartMinute = 660;
				settings.fvgEndMinute = 870;
				settings.fvgMinVolumeRatio = 0.55;
				settings.fvgMinRiskTicks = "MGC".equals(symbol) ? 12.0 : 10.0;
				settings.fvgMaxRiskTicks = "MGC".equals(symbol) ? 36.0 : 28.0;
				settings.fvgRewardRisk = 1.0;
				FuturesManager.saveFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(WIP), settings);
			}
		}
	}

	private static void printRun(Connection conn, int id) throws Exception {
		String sql = "SELECT portfolioBacktestID, totalProfit, returnPct, winRate, numTrades, profitFactor, maxDrawdownPct, "
			+ "maxIntradayLoss, dailyLossBreaches, trailingDrawdownBreaches, overlapRejections, exposureRejections, riskRejections, ruleViolation, ruleMessage "
			+ "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ?";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					System.out.println(
						"FULL trades=" + rs.getInt("numTrades")
							+ " pnl=" + round(rs.getDouble("totalProfit"))
							+ " return=" + round(rs.getDouble("returnPct"))
							+ " win=" + round(rs.getDouble("winRate"))
							+ " pf=" + round(rs.getDouble("profitFactor"))
							+ " ddPct=" + round(rs.getDouble("maxDrawdownPct"))
							+ " maxIntradayLoss=" + round(rs.getDouble("maxIntradayLoss"))
							+ " breaches=" + rs.getInt("dailyLossBreaches") + "/" + rs.getInt("trailingDrawdownBreaches")
							+ " rejects=" + rs.getInt("overlapRejections") + "/" + rs.getInt("exposureRejections") + "/" + rs.getInt("riskRejections")
							+ " violation=" + rs.getInt("ruleViolation")
							+ " message=\"" + rs.getString("ruleMessage") + "\""
					);
				}
			}
		}
	}

	private static void printMiddaySummary(Connection conn, int id) throws Exception {
		String sql = "SELECT COUNT(*) AS trades, SUM(pnl) AS pnl, "
			+ "SUM(CASE WHEN pnl > 0 THEN pnl ELSE 0 END) AS grossWin, "
			+ "SUM(CASE WHEN pnl < 0 THEN pnl ELSE 0 END) AS grossLoss, "
			+ "SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) AS wins "
			+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND midday";
		try (PreparedStatement stmt = conn.prepareStatement(sql.replace("midday", middayWhere()))) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					int trades = rs.getInt("trades");
					double wins = rs.getDouble("wins");
					double grossLoss = Math.abs(rs.getDouble("grossLoss"));
					double pf = grossLoss <= 0.0 ? 0.0 : rs.getDouble("grossWin") / grossLoss;
					System.out.println(
						"MIDDAY trades=" + trades
							+ " pnl=" + round(rs.getDouble("pnl"))
							+ " win=" + round(trades == 0 ? 0.0 : (wins * 100.0) / trades)
							+ " pf=" + round(pf)
							+ " drawdown=" + round(middayDrawdown(conn, id))
							+ " dailyLossBreaches=" + middayDailyLossBreaches(conn, id, -1000.0)
					);
				}
			}
		}
	}

	private static void printBreakdown(Connection conn, int id, String groupExpression, String label) throws Exception {
		System.out.println(label);
		String sql = "SELECT " + groupExpression + " AS bucket, COUNT(*) AS trades, SUM(pnl) AS pnl, AVG(pnl) AS avgPnl, "
			+ "SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) AS wins "
			+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND " + middayWhere()
			+ " GROUP BY bucket ORDER BY pnl DESC";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					int trades = rs.getInt("trades");
					double wins = rs.getDouble("wins");
					System.out.println(
						rs.getString("bucket")
							+ " trades=" + trades
							+ " pnl=" + round(rs.getDouble("pnl"))
							+ " avg=" + round(rs.getDouble("avgPnl"))
							+ " win=" + round(trades == 0 ? 0.0 : (wins * 100.0) / trades)
					);
				}
			}
		}
	}

	private static void printOvertrading(Connection conn, int id) throws Exception {
		String sql = "SELECT COUNT(*) AS days, AVG(trades) AS avgTrades, MAX(trades) AS maxTrades, "
			+ "SUM(CASE WHEN trades > 6 THEN 1 ELSE 0 END) AS daysOverSix, "
			+ "SUM(CASE WHEN trades > 10 THEN 1 ELSE 0 END) AS daysOverTen "
			+ "FROM (SELECT substr(openedAt, 1, 10) AS day, COUNT(*) AS trades FROM FuturesPortfolioTrades "
			+ "WHERE portfolioBacktestID = ? AND " + middayWhere() + " GROUP BY day)";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					System.out.println(
						"MIDDAY_OVERTRADING activeDays=" + rs.getInt("days")
							+ " avgTradesPerActiveDay=" + round(rs.getDouble("avgTrades"))
							+ " maxTradesDay=" + rs.getInt("maxTrades")
							+ " daysOver6=" + rs.getInt("daysOverSix")
							+ " daysOver10=" + rs.getInt("daysOverTen")
					);
				}
			}
		}
	}

	private static double middayDrawdown(Connection conn, int id) throws Exception {
		String sql = "SELECT pnl FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND " + middayWhere() + " ORDER BY openedAt, portfolioTradeID";
		double equity = 0.0;
		double peak = 0.0;
		double maxDrawdown = 0.0;
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					equity += rs.getDouble("pnl");
					peak = Math.max(peak, equity);
					maxDrawdown = Math.max(maxDrawdown, peak - equity);
				}
			}
		}
		return maxDrawdown;
	}

	private static int middayDailyLossBreaches(Connection conn, int id, double limit) throws Exception {
		String sql = "SELECT SUM(pnl) AS dayPnl FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND " + middayWhere() + " GROUP BY substr(openedAt, 1, 10)";
		int breaches = 0;
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					if (rs.getDouble("dayPnl") <= limit) {
						breaches++;
					}
				}
			}
		}
		return breaches;
	}

	private static String middayWhere() {
		return "(CAST(substr(openedAt,12,2) AS INTEGER) * 60 + CAST(substr(openedAt,15,2) AS INTEGER)) BETWEEN 660 AND 870";
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
