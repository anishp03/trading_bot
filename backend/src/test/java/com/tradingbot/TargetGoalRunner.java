package com.tradingbot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TargetGoalRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K";
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-04";
	private static final String FUNDED_PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String[] SYMBOL_LIST = new String[] {"MES", "MNQ", "NQ", "MGC", "ES", "M2K"};

	private interface Scenario {
		String name();
		void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks);
	}

	private interface ScenarioApplier {
		void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks);
	}

	private static class SimpleScenario implements Scenario {
		private final String name;
		private final ScenarioApplier applier;

		SimpleScenario(String name, ScenarioApplier applier) {
			this.name = name;
			this.applier = applier;
		}

		public String name() {
			return name;
		}

		public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
			applier.apply(settings, risks);
		}
	}

	private static class RunSummary {
		int id;
		String name;
		double pnl;
		double returnPct;
		int trades;
		double winRate;
		double profitFactor;
		double maxDrawdownPct;
		double maxIntradayLoss;
		double maxAggregateMae;
		int ruleViolation;
		String message;
	}

	public static void main(String[] args) throws Exception {
		String filter = args.length > 0 ? args[0].trim().toLowerCase() : "";
		Map<String, FuturesManager.FuturesStrategySettings> baseSettings = loadBaseSettings();
		Map<String, FuturesManager.FuturesRiskSettings> baseRisks = loadBaseRisks();
		List<RunSummary> summaries = new ArrayList<RunSummary>();
		try {
			for (Scenario scenario : scenarios()) {
				if (!filter.isEmpty() && !scenario.name().toLowerCase().contains(filter)) {
					continue;
				}
				restore(baseSettings, baseRisks);
				Map<String, FuturesManager.FuturesStrategySettings> settings = loadBaseSettings();
				Map<String, FuturesManager.FuturesRiskSettings> risks = loadBaseRisks();
				scenario.apply(settings, risks);
				save(settings, risks);
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
					FUNDED_PROFILE
				);
				RunSummary summary = loadRun(id, scenario.name());
				saveRunLabel(id, scenario.name());
				summaries.add(summary);
				System.out.println(line(summary));
			}
		} finally {
			restore(baseSettings, baseRisks);
		}
		printRankings(summaries);
	}

	private static List<Scenario> scenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		values.add(new SimpleScenario("tg_baseline_1201", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {}
		}));

		String[] filters = new String[] {"none", "es", "light", "core", "plus"};
		String[] counts = new String[] {"none", "fvg_broad", "fvg_full", "fvg_full075", "cmom", "mnqaft", "fvg_cmom", "fvg_cmom_mnqaft", "full_cmom_mnqaft", "refill_stack"};
		String[] targetModes = new String[] {"keep", "fvg075", "freq075"};
		for (final String filterMode : filters) {
			for (final String countMode : counts) {
				for (final String targetMode : targetModes) {
					values.add(new SimpleScenario("tg_" + filterMode + "_" + countMode + "_" + targetMode, new ScenarioApplier() {
						public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
							applyFilterMode(settings, filterMode);
							applyCountMode(settings, countMode);
							applyTargetMode(settings, targetMode);
						}
					}));
				}
			}
		}

		values.add(new SimpleScenario("tg_goal_push_core_full_cmom_mnqaft_omom055", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyFilterMode(settings, "core");
				applyCountMode(settings, "full_cmom_mnqaft");
				settings.get("MNQ").openingMomentumRewardRisk = 0.55;
				settings.get("NQ").fvgRewardRisk = 0.85;
			}
		}));
		values.add(new SimpleScenario("tg_goal_push_plus_full_cmom_mnqaft_omom055", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyFilterMode(settings, "plus");
				applyCountMode(settings, "full_cmom_mnqaft");
				settings.get("MNQ").openingMomentumRewardRisk = 0.55;
				settings.get("NQ").fvgRewardRisk = 0.75;
			}
		}));
		values.add(new SimpleScenario("tg_goal_push_core_refill_freq065", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyFilterMode(settings, "core");
				applyCountMode(settings, "refill_stack");
				settings.get("NQ").fvgRewardRisk = 0.65;
				settings.get("MES").afternoonRewardRisk = 0.75;
				settings.get("MNQ").afternoonRewardRisk = 0.75;
			}
		}));
		double[] lowFvgTargets = new double[] {0.40, 0.50, 0.60, 0.70};
		for (final String filterMode : new String[] {"light", "core", "plus"}) {
			for (final double target : lowFvgTargets) {
				values.add(new SimpleScenario("tg2_" + filterMode + "_full_fvg" + tag(target), new ScenarioApplier() {
					public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
						applyFilterMode(settings, filterMode);
						applyCountMode(settings, "full_cmom_mnqaft");
						settings.get("NQ").fvgRewardRisk = target;
					}
				}));
			}
		}
		for (final String filterMode : new String[] {"core", "plus"}) {
			values.add(new SimpleScenario("tg2_" + filterMode + "_scalp_refill", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
					applyFilterMode(settings, filterMode);
					enableScalpRefill(settings, 0.65);
					enableExtraPdbSweep(settings);
				}
			}));
			values.add(new SimpleScenario("tg2_" + filterMode + "_scalp_refill_fast", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
					applyFilterMode(settings, filterMode);
					enableScalpRefill(settings, 0.45);
					enableExtraPdbSweep(settings);
				}
			}));
			values.add(new SimpleScenario("tg2_" + filterMode + "_broad_scalp_fvg060", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
					applyFilterMode(settings, filterMode);
					applyCountMode(settings, "fvg_broad");
					settings.get("NQ").fvgRewardRisk = 0.60;
					enableScalpRefill(settings, 0.55);
				}
			}));
		}
		for (final double target : new double[] {0.30, 0.35, 0.40, 0.45}) {
			values.add(new SimpleScenario("tg3_core_full_fvg" + tag(target) + "_skip_fri14_long", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
					applyFilterMode(settings, "core");
					applyCountMode(settings, "full_cmom_mnqaft");
					FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
					nq.fvgRewardRisk = target;
					nq.fvgLongDowWindowSkipMask = dowMask(DayOfWeek.FRIDAY);
					nq.fvgLongDowWindowSkipStartMinute = 840;
					nq.fvgLongDowWindowSkipEndMinute = 899;
				}
			}));
		}
		return values;
	}

	private static void applyFilterMode(Map<String, FuturesManager.FuturesStrategySettings> settings, String mode) {
		if ("none".equals(mode)) {
			return;
		}
		FuturesManager.FuturesStrategySettings es = settings.get("ES");
		es.vwapShortSkipDowMask = dowMask(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY);
		if ("es".equals(mode)) {
			return;
		}
		FuturesManager.FuturesStrategySettings mgc = settings.get("MGC");
		mgc.orbRetestShortSkipDowMask = dowMask(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
		if ("light".equals(mode)) {
			return;
		}
		FuturesManager.FuturesStrategySettings mes = settings.get("MES");
		mes.afternoonShortSkipDowMask = dowMask(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY);
		FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
		nq.marketImpulsePullbackLongSkipDowMask = dowMask(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY);
		if ("core".equals(mode)) {
			return;
		}
		nq.fvgLongSkipDowMask = dowMask(DayOfWeek.THURSDAY);
		nq.fvgShortSkipDowMask = dowMask(DayOfWeek.WEDNESDAY);
		FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
		mnq.openingMomentumLongSkipDowMask = dowMask(DayOfWeek.TUESDAY);
		mnq.openingMomentumShortSkipDowMask = dowMask(DayOfWeek.THURSDAY);
	}

	private static void applyCountMode(Map<String, FuturesManager.FuturesStrategySettings> settings, String mode) {
		if ("none".equals(mode)) {
			return;
		}
		if (mode.contains("fvg") || mode.contains("full") || "refill_stack".equals(mode)) {
			FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
			nq.fvg.enabled = true;
			nq.fvg.maxTradesPerDay = 5;
			nq.fvgStartMinute = mode.contains("full") || "refill_stack".equals(mode) ? 600 : 600;
			nq.fvgEndMinute = mode.contains("full") || "refill_stack".equals(mode) ? 900 : 839;
			nq.fvgSkipStartMinute = mode.contains("full") || "refill_stack".equals(mode) ? 0 : 660;
			nq.fvgSkipEndMinute = mode.contains("full") || "refill_stack".equals(mode) ? 0 : 719;
			if (mode.contains("075")) {
				nq.fvgRewardRisk = 0.75;
			}
		}
		if (mode.contains("cmom") || "cmom".equals(mode) || "refill_stack".equals(mode)) {
			enableCloseMomentumRefill(settings.get("MES"), 3, 0.75);
			enableCloseMomentumRefill(settings.get("M2K"), 3, 0.75);
			enableCloseMomentumRefill(settings.get("MGC"), 3, 0.75);
			enableCloseMomentumRefill(settings.get("NQ"), 2, 0.85);
		}
		if (mode.contains("mnqaft") || "mnqaft".equals(mode) || "refill_stack".equals(mode)) {
			FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
			mnq.afternoonContinuation.enabled = true;
			mnq.afternoonContinuation.maxTradesPerDay = 5;
			mnq.allowAfternoonContinuationLongs = false;
			mnq.allowAfternoonContinuationShorts = true;
			mnq.afternoonShortStartMinute = 780;
			mnq.afternoonShortEndMinute = 920;
			mnq.afternoonSkipStartMinute = 840;
			mnq.afternoonSkipEndMinute = 899;
		}
		if ("refill_stack".equals(mode)) {
			enableExtraPdbSweep(settings);
		}
	}

	private static void applyTargetMode(Map<String, FuturesManager.FuturesStrategySettings> settings, String mode) {
		if ("fvg075".equals(mode) || "freq075".equals(mode)) {
			settings.get("NQ").fvgRewardRisk = 0.75;
		}
		if ("freq075".equals(mode)) {
			settings.get("MES").afternoonRewardRisk = Math.min(settings.get("MES").afternoonRewardRisk, 0.75);
			settings.get("MNQ").afternoonRewardRisk = Math.min(settings.get("MNQ").afternoonRewardRisk, 0.75);
			settings.get("MES").closeMomentumRewardRisk = Math.min(settings.get("MES").closeMomentumRewardRisk, 0.75);
			settings.get("M2K").closeMomentumRewardRisk = Math.min(settings.get("M2K").closeMomentumRewardRisk, 0.75);
		}
	}

	private static void enableCloseMomentumRefill(FuturesManager.FuturesStrategySettings settings, int maxTrades, double rewardRisk) {
		if (settings == null) {
			return;
		}
		settings.closeMomentum.enabled = true;
		settings.closeMomentum.maxTradesPerDay = Math.max(settings.closeMomentum.maxTradesPerDay, maxTrades);
		settings.allowCloseMomentumLongs = false;
		settings.allowCloseMomentumShorts = true;
		settings.closeMomentumShortStartMinute = 870;
		settings.closeMomentumVolumeRatio = Math.min(settings.closeMomentumVolumeRatio, 0.7);
		settings.closeMomentumRewardRisk = Math.min(settings.closeMomentumRewardRisk, rewardRisk);
	}

	private static void enableExtraPdbSweep(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
		nq.priorDayBreakout.enabled = true;
		nq.priorDayBreakout.maxTradesPerDay = 8;
		nq.allowPriorDayBreakoutLongs = false;
		nq.allowPriorDayBreakoutShorts = true;
		nq.priorDayBreakoutMinVolumeRatio = Math.min(nq.priorDayBreakoutMinVolumeRatio, 0.70);

		FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
		mnq.sweep.enabled = true;
		mnq.sweep.maxTradesPerDay = 5;
		mnq.enableSweepSecondChance = true;
		mnq.earlySweepReclaimTicks = 4.0;

		FuturesManager.FuturesStrategySettings mgc = settings.get("MGC");
		mgc.sweep.enabled = true;
		mgc.sweep.maxTradesPerDay = 5;
		mgc.orb.maxTradesPerDay = Math.max(mgc.orb.maxTradesPerDay, 3);
	}

	private static void enableScalpRefill(Map<String, FuturesManager.FuturesStrategySettings> settings, double rewardRisk) {
		for (String symbol : new String[] {"MES", "MNQ", "NQ", "MGC", "M2K"}) {
			FuturesManager.FuturesStrategySettings s = settings.get(symbol);
			if (s == null) {
				continue;
			}
			s.microScalp.enabled = true;
			s.microScalp.maxTradesPerDay = 12;
			s.microScalpRewardRisk = Math.min(s.microScalpRewardRisk, rewardRisk);
			s.microScalpMaxRiskTicks = Math.min(s.microScalpMaxRiskTicks, 12.0);
			s.microScalpMinVolumeRatio = Math.max(s.microScalpMinVolumeRatio, 0.85);
			s.microScalpMinBodyPct = Math.max(s.microScalpMinBodyPct, 20.0);
			s.keltnerScalp.enabled = true;
			s.keltnerScalp.maxTradesPerDay = 8;
			s.keltnerRewardRisk = Math.min(s.keltnerRewardRisk, Math.max(0.55, rewardRisk));
			s.keltnerMaxRiskTicks = Math.min(s.keltnerMaxRiskTicks, 12.0);
			s.keltnerMinVolumeRatio = Math.max(s.keltnerMinVolumeRatio, 0.9);
			s.keltnerMinBodyPct = Math.max(s.keltnerMinBodyPct, 18.0);
		}
	}

	private static int dowMask(DayOfWeek... days) {
		int mask = 0;
		for (DayOfWeek day : days) {
			mask |= 1 << day.getValue();
		}
		return mask;
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

	private static RunSummary loadRun(int id, String name) throws Exception {
		RunSummary summary = new RunSummary();
		summary.id = id;
		summary.name = name;
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT totalProfit, returnPct, winRate, numTrades, profitFactor, maxDrawdownPct, maxIntradayLoss, "
				 + "maxAggregateMae, ruleViolation, ruleMessage "
				 + "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ?")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					summary.pnl = rs.getDouble("totalProfit");
					summary.returnPct = rs.getDouble("returnPct");
					summary.trades = rs.getInt("numTrades");
					summary.winRate = rs.getDouble("winRate");
					summary.profitFactor = rs.getDouble("profitFactor");
					summary.maxDrawdownPct = rs.getDouble("maxDrawdownPct");
					summary.maxIntradayLoss = rs.getDouble("maxIntradayLoss");
					summary.maxAggregateMae = rs.getDouble("maxAggregateMae");
					summary.ruleViolation = rs.getInt("ruleViolation");
					summary.message = rs.getString("ruleMessage");
				}
			}
		}
		return summary;
	}

	private static void saveRunLabel(int id, String name) {
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement()) {
			stmt.execute("CREATE TABLE IF NOT EXISTS ResearchRunLabels (portfolioBacktestID INTEGER PRIMARY KEY, runner TEXT, scenarioName TEXT, createdAt TEXT)");
			try (PreparedStatement insert = conn.prepareStatement(
				"INSERT OR REPLACE INTO ResearchRunLabels (portfolioBacktestID, runner, scenarioName, createdAt) VALUES (?, ?, ?, datetime('now'))")) {
				insert.setInt(1, id);
				insert.setString(2, "TargetGoalRunner");
				insert.setString(3, name);
				insert.executeUpdate();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void printRankings(List<RunSummary> summaries) {
		Collections.sort(summaries, new Comparator<RunSummary>() {
			public int compare(RunSummary first, RunSummary second) {
				if (first.ruleViolation != second.ruleViolation) {
					return first.ruleViolation - second.ruleViolation;
				}
				int distanceCompare = Double.compare(targetDistance(first), targetDistance(second));
				if (distanceCompare != 0) {
					return distanceCompare;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});
		System.out.println("TOP_TARGET_DISTANCE");
		printTop(summaries, 15);

		Collections.sort(summaries, new Comparator<RunSummary>() {
			public int compare(RunSummary first, RunSummary second) {
				if (first.ruleViolation != second.ruleViolation) {
					return first.ruleViolation - second.ruleViolation;
				}
				if (first.trades != second.trades) {
					return second.trades - first.trades;
				}
				return Double.compare(second.winRate, first.winRate);
			}
		});
		System.out.println("TOP_BY_TRADES");
		printTop(summaries, 15);

		Collections.sort(summaries, new Comparator<RunSummary>() {
			public int compare(RunSummary first, RunSummary second) {
				if (first.ruleViolation != second.ruleViolation) {
					return first.ruleViolation - second.ruleViolation;
				}
				int winCompare = Double.compare(second.winRate, first.winRate);
				if (winCompare != 0) {
					return winCompare;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});
		System.out.println("TOP_BY_WIN");
		printTop(summaries, 15);

		Collections.sort(summaries, new Comparator<RunSummary>() {
			public int compare(RunSummary first, RunSummary second) {
				if (first.ruleViolation != second.ruleViolation) {
					return first.ruleViolation - second.ruleViolation;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});
		System.out.println("TOP_BY_PROFIT");
		printTop(summaries, 15);
	}

	private static double targetDistance(RunSummary summary) {
		double tradeMiss = Math.max(0.0, 800.0 - summary.trades) / 6.0;
		double winMiss = Math.max(0.0, 65.0 - summary.winRate) * 4.0;
		double profitMiss = Math.max(0.0, 60000.0 - summary.pnl) / 4000.0;
		return tradeMiss + winMiss + profitMiss;
	}

	private static void printTop(List<RunSummary> summaries, int limit) {
		int printed = 0;
		for (RunSummary summary : summaries) {
			if (summary.ruleViolation != 0) {
				continue;
			}
			System.out.println(line(summary));
			printed++;
			if (printed >= limit) {
				break;
			}
		}
	}

	private static String line(RunSummary summary) {
		return summary.id + " " + summary.name
			+ " pnl=" + round(summary.pnl)
			+ " trades=" + summary.trades
			+ " win=" + round(summary.winRate)
			+ " pf=" + round(summary.profitFactor)
			+ " dd=" + round(summary.maxDrawdownPct)
			+ " intraday=" + round(summary.maxIntradayLoss)
			+ " mae=" + round(summary.maxAggregateMae)
			+ " violation=" + summary.ruleViolation
			+ " distance=" + round(targetDistance(summary))
			+ " msg=\"" + (summary.message == null ? "" : summary.message) + "\"";
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	private static String tag(double value) {
		return String.valueOf(Math.round(value * 100.0));
	}
}
