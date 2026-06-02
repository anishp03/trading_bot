package com.tradingbot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public class ResearchBacktestRunner {
	public static void main(String[] args) throws Exception {
		String symbols = args.length > 0 ? args[0] : "MES,MNQ,NQ,MGC,ES,M2K";
		String startDate = args.length > 1 ? args[1] : "2025-05-01";
		String endDate = args.length > 2 ? args[2] : "2026-05-04";
		double slippageTicks = args.length > 3 ? Double.parseDouble(args[3]) : 1.0;
		String fundedProfile = args.length > 4 ? args[4] : "TOPSTEP_50K_RESEARCH";
		double maxRiskPerTrade = args.length > 5 ? Double.parseDouble(args[5]) : 400.0;
		int maxContracts = args.length > 6 ? Integer.parseInt(args[6]) : 12;
		double maxAggregateMiniUnits = args.length > 7 ? Double.parseDouble(args[7]) : 5.0;
		double accountSize = args.length > 8 ? Double.parseDouble(args[8]) : 50000.0;
		double maxTrailingDrawdown = args.length > 9 ? Double.parseDouble(args[9]) : 2000.0;
		double dailyLossLimit = args.length > 10 ? Double.parseDouble(args[10]) : 1000.0;
		boolean continueAfterRuleViolation = args.length > 11 && Boolean.parseBoolean(args[11]);

		int id = FuturesManager.generatePortfolioBacktest(
			symbols,
			startDate,
			endDate,
			accountSize,
			maxTrailingDrawdown,
			dailyLossLimit,
			maxRiskPerTrade,
			maxContracts,
			1.24,
			slippageTicks,
			3,
			50,
			maxAggregateMiniUnits,
			true,
			0.0,
			fundedProfile,
			"94k",
			0,
			continueAfterRuleViolation
		);

		System.out.println("RUN_ID=" + id);
		if (id <= 0) {
			return;
		}
		try (Connection conn = DatabaseManager.getConnection()) {
			printRun(conn, id);
			printBreakdown(conn, id, "symbol, strategyCode", "symbol_strategy");
			printBreakdown(conn, id, "substr(openedAt, 1, 7)", "monthly");
		}
	}

	private static void printRun(Connection conn, int id) throws Exception {
		String sql = "SELECT portfolioBacktestID, fundedProfile, symbols, totalProfit, returnPct, winRate, numTrades, "
			+ "profitFactor, maxDrawdownPct, maxIntradayLoss, maxAggregateMae, dailyLossBreaches, "
			+ "trailingDrawdownBreaches, maeBreaches, ruleViolation, continueAfterRuleViolation, ruleMessage "
			+ "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ?";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					System.out.println(
						"RUN "
							+ rs.getInt("portfolioBacktestID")
							+ " " + rs.getString("fundedProfile")
							+ " " + rs.getString("symbols")
							+ " pnl=" + round(rs.getDouble("totalProfit"))
							+ " return=" + round(rs.getDouble("returnPct"))
							+ " trades=" + rs.getInt("numTrades")
							+ " win=" + round(rs.getDouble("winRate"))
							+ " pf=" + round(rs.getDouble("profitFactor"))
							+ " dd=" + round(rs.getDouble("maxDrawdownPct"))
							+ " intraday=" + round(rs.getDouble("maxIntradayLoss"))
							+ " mae=" + round(rs.getDouble("maxAggregateMae"))
							+ " breaches=" + rs.getInt("dailyLossBreaches") + "/" + rs.getInt("trailingDrawdownBreaches") + "/" + rs.getInt("maeBreaches")
							+ " violation=" + rs.getInt("ruleViolation")
							+ " continue=" + rs.getInt("continueAfterRuleViolation")
							+ " message=\"" + rs.getString("ruleMessage") + "\""
					);
				}
			}
		}
	}

	private static void printBreakdown(Connection conn, int id, String groupExpression, String label) throws Exception {
		System.out.println("BREAKDOWN " + label);
		String sql = "SELECT " + groupExpression + ", COUNT(*) AS trades, SUM(pnl) AS pnl, AVG(pnl) AS avgPnl, "
			+ "SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) AS wins "
			+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? GROUP BY " + groupExpression + " ORDER BY pnl DESC";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				int columnCount = rs.getMetaData().getColumnCount();
				while (rs.next()) {
					StringBuilder key = new StringBuilder();
					for (int column = 1; column <= columnCount - 4; column++) {
						if (column > 1) {
							key.append("/");
						}
						key.append(rs.getString(column));
					}
					int trades = rs.getInt("trades");
					double wins = rs.getDouble("wins");
					double winRate = trades == 0 ? 0.0 : (wins * 100.0) / trades;
					System.out.println(
						key
							+ " trades=" + trades
							+ " pnl=" + round(rs.getDouble("pnl"))
							+ " avg=" + round(rs.getDouble("avgPnl"))
							+ " win=" + round(winRate)
					);
				}
			}
		}
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
