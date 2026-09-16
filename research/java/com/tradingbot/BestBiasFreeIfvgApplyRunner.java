package com.tradingbot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class BestBiasFreeIfvgApplyRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final String PRESET = "bestbiasfree";
	private static final String SLOT = FuturesManager.strategyPresetSlot(PRESET);
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-22";
	private static final String PROFILE = "TOPSTEP_50K_RESEARCH";

	public static void main(String[] args) throws Exception {
		FuturesManager.initializeStore();
		disableMclIfvg();
		System.out.println("SCENARIO=baseline_mcl_ifvg_off");
		int baselineId = runPortfolio();
		System.out.println("RUN_ID=" + baselineId);
		if (baselineId > 0) {
			printRun(baselineId);
			printBreakdown(baselineId);
		}
		applyMclIfvg();
		printMclConfig();
		System.out.println("SCENARIO=official_mcl_ifvg_on");
		int id = runPortfolio();
		System.out.println("RUN_ID=" + id);
		if (id > 0) {
			printRun(id);
			printBreakdown(id);
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

	private static void disableMclIfvg() {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MCL", SLOT);
		settings.fvg.enabled = false;
		settings.ifvg.enabled = false;
		FuturesManager.saveFuturesStrategySettings("MCL", SLOT, settings);
	}

	private static void applyMclIfvg() {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MCL", SLOT);
		settings.fvg.enabled = false;
		settings.ifvg.enabled = true;
		settings.ifvg.maxTradesPerDay = 3;
		settings.allowFvgLongs = true;
		settings.allowFvgShorts = false;
		settings.fvgTradeInversions = false;
		settings.fvgRequireInversionStructureBreak = true;
		settings.fvgInversionBreakBars = 60;
		settings.fvgInversionStructureBars = 40;
		settings.fvgMinInversionBodyPct = 55.0;
		settings.fvgStartMinute = 600;
		settings.fvgEndMinute = 900;
		settings.fvgRetestBars = 10;
		settings.fvgMinWidthTicks = 4.0;
		settings.fvgMinVolumeRatio = 0.75;
		settings.fvgMinRiskTicks = 16.0;
		settings.fvgMaxRiskTicks = 48.0;
		settings.fvgRewardRisk = 1.2;
		settings.fvgMaxHoldBars = 18;
		settings.fvgRequireCoreQuality = true;
		settings.fvgRequireEmaStack = true;
		settings.fvgRequireHigherTimeframeGuard = false;
		settings.fvgMinImpulseBodyPct = 45.0;
		settings.fvgMinReclaimBodyPct = 0.0;
		settings.fvgMinReclaimTicks = 0.0;
		settings.fvgMaxRetestDepthPct = 0.85;
		settings.fvgMinReclaimCloseLocation = 0.78;
		settings.fvgMaxPriorMoveTicks = 0.0;
		settings.fvgSourceMode = "NONE";
		settings.fvgSourceRangeBars = 0;
		settings.fvgMinSourceBreakTicks = 0.0;
		settings.fvgAcceptanceBars = 0;
		settings.fvgAcceptanceMinCloseLocation = 0.0;
		settings.fvgAcceptanceRequireReclaimExtremeBreak = false;
		settings.fvgMinTrendSlopeTicks = 1.0;
		settings.fvgMaxVwapDistanceTicks = 96.0;
		settings.fvgMaxEntryExtensionTicks = 28.0;
		FuturesManager.saveFuturesStrategySettings("MCL", SLOT, settings);
	}

	private static void printMclConfig() {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MCL", SLOT);
		System.out.println(
			"MCL_CONFIG"
				+ " fvgEnabled=" + settings.fvg.enabled
				+ " ifvgEnabled=" + settings.ifvg.enabled
				+ " ifvgMaxTrades=" + settings.ifvg.maxTradesPerDay
				+ " longs=" + settings.allowFvgLongs
				+ " shorts=" + settings.allowFvgShorts
				+ " structureBreak=" + settings.fvgRequireInversionStructureBreak
				+ " breakBars=" + settings.fvgInversionBreakBars
				+ " structureBars=" + settings.fvgInversionStructureBars
				+ " name=IFVG"
		);
	}

	private static void printRun(int id) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				"SELECT portfolioBacktestID,totalProfit,winRate,numTrades,profitFactor,maxDrawdownPct,ruleViolation "
					+ "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID=?")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					System.out.println(
						"RUN"
							+ " id=" + rs.getInt("portfolioBacktestID")
							+ " pnl=" + round(rs.getDouble("totalProfit"))
							+ " trades=" + rs.getInt("numTrades")
							+ " win=" + round(rs.getDouble("winRate"))
							+ " pf=" + round(rs.getDouble("profitFactor"))
							+ " dd=" + round(rs.getDouble("maxDrawdownPct"))
							+ " violation=" + rs.getInt("ruleViolation")
					);
				}
			}
		}
	}

	private static void printBreakdown(int id) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				"SELECT symbol,strategyCode,COUNT(*) AS trades,SUM(pnl) AS pnl,"
					+ "SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) AS wins "
					+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID=? "
					+ "GROUP BY symbol,strategyCode ORDER BY symbol,strategyCode")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					int trades = rs.getInt("trades");
					double wins = rs.getDouble("wins");
					double winRate = trades == 0 ? 0.0 : wins * 100.0 / trades;
					System.out.println(
						"BREAKDOWN"
							+ " symbol=" + rs.getString("symbol")
							+ " strategy=" + rs.getString("strategyCode")
							+ " trades=" + trades
							+ " pnl=" + round(rs.getDouble("pnl"))
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
