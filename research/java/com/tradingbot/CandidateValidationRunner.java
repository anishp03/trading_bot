package com.tradingbot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

public class CandidateValidationRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K";
	private static final String FUNDED_PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String[] SYMBOL_LIST = new String[] {"MES", "MNQ", "NQ", "MGC", "ES", "M2K"};

	public static void main(String[] args) throws Exception {
		String candidate = args.length > 0 ? args[0].trim().toLowerCase() : "highcount";
		boolean promote = false;
		for (int index = 0; index < args.length; index++) {
			if ("--promote".equalsIgnoreCase(args[index])) {
				promote = true;
			}
		}
		Map<String, FuturesManager.FuturesStrategySettings> baseSettings = loadBaseSettings();
		Map<String, FuturesManager.FuturesRiskSettings> baseRisks = loadBaseRisks();
		try {
			Map<String, FuturesManager.FuturesStrategySettings> settings = loadBaseSettings();
			Map<String, FuturesManager.FuturesRiskSettings> risks = loadBaseRisks();
			if ("finalmoney".equals(candidate)) {
				applyFinalMoneyCandidate(settings, risks, true);
			} else if ("finalsimple".equals(candidate)) {
				applyFinalMoneyCandidate(settings, risks, false);
			} else if ("quality".equals(candidate)) {
				applyQualityCandidate(settings);
			} else if ("money700".equals(candidate)) {
				applyMoney700Candidate(settings);
			} else {
				applyHighCountCandidate(settings);
			}
			save(settings, risks);
			if (promote) {
				System.out.println("PROMOTED " + candidate + " settings to saved BACKTEST strategy slot.");
				return;
			}
			System.out.println("VALIDATING " + candidate);
			int normal = run("full_normal", "2025-05-01", "2026-05-04", 1.0);
			run("full_2x_slippage", "2025-05-01", "2026-05-04", 2.0);
			run("first_half", "2025-05-01", "2025-10-31", 1.0);
			run("second_half", "2025-11-01", "2026-05-04", 1.0);
			try (Connection conn = DatabaseManager.getConnection()) {
				printBreakdown(conn, normal, "symbol", "symbol");
				printBreakdown(conn, normal, "strategyCode", "strategy");
				printBreakdown(conn, normal, "symbol, strategyCode", "symbol_strategy");
				printBreakdown(conn, normal, "substr(openedAt, 1, 7)", "monthly");
			}
		} finally {
			if (!promote) {
				restore(baseSettings, baseRisks);
			}
		}
	}

	private static int run(String label, String startDate, String endDate, double slippageTicks) throws Exception {
		int id = FuturesManager.generatePortfolioBacktest(
			SYMBOLS,
			startDate,
			endDate,
			50000.0,
			2000.0,
			1000.0,
			700.0,
			50,
			1.24,
			slippageTicks,
			3,
			50,
			5.0,
			true,
			0.0,
			FUNDED_PROFILE
		);
		try (Connection conn = DatabaseManager.getConnection()) {
			printRun(conn, id, label);
		}
		return id;
	}

	private static void applyHighCountCandidate(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyMnqLowerOpeningMomentumReward(settings.get("MNQ"));
		enableNqFvgAll(settings.get("NQ"));
		enableNqPositiveOrb(settings.get("NQ"));
		enableNqPdbShortOnly(settings.get("NQ"));
		enableFilteredMesAfternoon(settings.get("MES"));
	}

	private static void applyMoney700Candidate(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyHighCountCandidate(settings);
		FuturesManager.FuturesStrategySettings mes = settings.get("MES");
		if (mes != null) {
			mes.allowAfternoonContinuationLongs = false;
			mes.allowAfternoonContinuationShorts = true;
		}
		FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
		if (nq != null) {
			nq.fvgRewardRisk = 1.0;
		}
	}

	private static void applyQualityCandidate(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyMnqLowerOpeningMomentumReward(settings.get("MNQ"));
		enableNqFvgTenTwelve(settings.get("NQ"));
		enableNqPositiveOrb(settings.get("NQ"));
		enableFilteredMesAfternoon(settings.get("MES"));
	}

	private static void applyFinalMoneyCandidate(
		Map<String, FuturesManager.FuturesStrategySettings> settings,
		Map<String, FuturesManager.FuturesRiskSettings> risks,
		boolean allPositiveTweaks
	) {
		FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
		if (mnq != null) {
			mnq.openingMomentumRewardRisk = 0.70;
			mnq.openingMomentumPortfolioRiskMultiplier = 0.65;
			if (allPositiveTweaks) {
				mnq.sweep.maxTradesPerDay = Math.max(mnq.sweep.maxTradesPerDay, 8);
				mnq.earlySweepReclaimTicks = 4.0;
			}
		}

		FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
		if (nq != null) {
			nq.fvg.maxTradesPerDay = 4;
			nq.fvgStartMinute = 720;
			nq.fvgEndMinute = 779;
			nq.fvgSkipStartMinute = 0;
			nq.fvgSkipEndMinute = 0;
			if (allPositiveTweaks) {
				nq.allowVwapPullbackLongs = false;
			}
		}

		FuturesManager.FuturesStrategySettings es = settings.get("ES");
		if (es != null) {
			es.closeMomentum.enabled = false;
			if (allPositiveTweaks) {
				es.sweepShortSkipStartMinute = 1;
				es.sweepShortSkipEndMinute = 930;
			}
		}

		setRisk(risks.get("MNQ"), 700.0, 50);
		setRisk(risks.get("ES"), 700.0, 5);
	}

	private static void setRisk(FuturesManager.FuturesRiskSettings risk, double maxRisk, int maxContracts) {
		if (risk == null) {
			return;
		}
		risk.maxRiskPerTrade = maxRisk;
		risk.maxContracts = maxContracts;
	}

	private static void enableFvg(FuturesManager.FuturesStrategySettings s, int maxTrades) {
		if (s == null) return;
		s.fvg.enabled = true;
		s.fvg.maxTradesPerDay = Math.max(s.fvg.maxTradesPerDay, maxTrades);
		s.allowFvgLongs = true;
		s.allowFvgShorts = true;
		s.fvgMinVolumeRatio = Math.min(s.fvgMinVolumeRatio, 0.6);
		s.fvgRewardRisk = Math.max(s.fvgRewardRisk, 1.1);
		s.fvgMaxHoldBars = Math.max(s.fvgMaxHoldBars, 18);
	}

	private static void enableNqFvgAll(FuturesManager.FuturesStrategySettings s) {
		enableFvg(s, 2);
		s.fvgStartMinute = 600;
		s.fvgEndMinute = 900;
		s.fvgSkipStartMinute = 0;
		s.fvgSkipEndMinute = 0;
	}

	private static void enableNqFvgTenTwelve(FuturesManager.FuturesStrategySettings s) {
		enableFvg(s, 2);
		s.fvgStartMinute = 600;
		s.fvgEndMinute = 779;
		s.fvgSkipStartMinute = 660;
		s.fvgSkipEndMinute = 719;
	}

	private static void enableNqPositiveOrb(FuturesManager.FuturesStrategySettings s) {
		if (s == null) return;
		s.orb.enabled = true;
		s.orb.maxTradesPerDay = Math.max(s.orb.maxTradesPerDay, 3);
		s.enableOrbRetest = true;
		s.allowOrbLongs = true;
		s.allowOrbShorts = true;
		s.allowOrbRetestLongs = true;
		s.allowOrbRetestShorts = true;
		s.enableCompressedOrbBreakout = true;
		s.orbCompressedMaxRiskTicks = Math.min(s.orbCompressedMaxRiskTicks, 60.0);
		s.orbShortSkipStartMinute = 600;
		s.orbShortSkipEndMinute = 659;
	}

	private static void enableNqPdbShortOnly(FuturesManager.FuturesStrategySettings s) {
		if (s == null) return;
		s.priorDayBreakout.enabled = true;
		s.priorDayBreakout.maxTradesPerDay = Math.max(s.priorDayBreakout.maxTradesPerDay, 4);
		s.allowPriorDayBreakoutLongs = false;
		s.allowPriorDayBreakoutShorts = true;
		s.priorDayBreakoutMinVolumeRatio = Math.min(s.priorDayBreakoutMinVolumeRatio, 0.75);
		s.priorDayBreakoutRewardRisk = Math.max(s.priorDayBreakoutRewardRisk, 1.0);
	}

	private static void enableFilteredMesAfternoon(FuturesManager.FuturesStrategySettings s) {
		if (s == null) return;
		s.afternoonContinuation.enabled = true;
		s.afternoonContinuation.maxTradesPerDay = Math.max(s.afternoonContinuation.maxTradesPerDay, 3);
		s.allowAfternoonContinuationLongs = true;
		s.allowAfternoonContinuationShorts = true;
		s.afternoonStartMinute = 780;
		s.afternoonEndMinute = 920;
		s.afternoonLongStartMinute = 900;
		s.afternoonLongEndMinute = 920;
		s.afternoonShortStartMinute = 780;
		s.afternoonShortEndMinute = 920;
		s.afternoonSkipStartMinute = 840;
		s.afternoonSkipEndMinute = 899;
		s.afternoonMinVolumeRatio = Math.min(s.afternoonMinVolumeRatio, 0.8);
		s.afternoonRewardRisk = Math.min(s.afternoonRewardRisk, 0.9);
		s.afternoonMaxRiskTicks = Math.min(s.afternoonMaxRiskTicks, 48.0);
	}

	private static void applyMnqLowerOpeningMomentumReward(FuturesManager.FuturesStrategySettings s) {
		if (s != null) {
			s.openingMomentumRewardRisk = 0.78;
		}
	}

	private static Map<String, FuturesManager.FuturesStrategySettings> loadBaseSettings() {
		Map<String, FuturesManager.FuturesStrategySettings> values = new HashMap<String, FuturesManager.FuturesStrategySettings>();
		for (String symbol : SYMBOL_LIST) {
			values.put(symbol, FuturesManager.loadFuturesStrategySettings(symbol));
		}
		return values;
	}

	private static Map<String, FuturesManager.FuturesRiskSettings> loadBaseRisks() {
		Map<String, FuturesManager.FuturesRiskSettings> values = new HashMap<String, FuturesManager.FuturesRiskSettings>();
		for (String symbol : SYMBOL_LIST) {
			values.put(symbol, FuturesManager.loadFuturesRiskSettings(symbol));
		}
		return values;
	}

	private static void save(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.saveFuturesStrategySettings(symbol, settings.get(symbol));
			FuturesManager.saveFuturesRiskSettings(symbol, risks.get(symbol));
		}
	}

	private static void restore(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
		save(settings, risks);
	}

	private static void printRun(Connection conn, int id, String label) throws Exception {
		String sql = "SELECT portfolioBacktestID, fundedProfile, symbols, totalProfit, returnPct, winRate, numTrades, "
			+ "profitFactor, maxDrawdownPct, maxIntradayLoss, maxAggregateMae, dailyLossBreaches, "
			+ "trailingDrawdownBreaches, maeBreaches, ruleViolation, ruleMessage "
			+ "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ?";
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					System.out.println(
						label
							+ " id=" + rs.getInt("portfolioBacktestID")
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
							+ " message=\"" + rs.getString("ruleMessage") + "\""
					);
				}
			}
		}
	}

	private static void printBreakdown(Connection conn, int id, String groupExpression, String label) throws Exception {
		System.out.println("BREAKDOWN " + label + " run=" + id);
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
						if (column > 1) key.append("/");
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
