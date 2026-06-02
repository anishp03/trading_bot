package com.tradingbot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WinTradeCountSprintRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K";
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-04";
	private static final String FUNDED_PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String[] SYMBOL_LIST = new String[] {"MES", "MNQ", "NQ", "MGC", "ES", "M2K"};

	private interface Scenario {
		String name();
		default double slippageTicks() { return 1.0; }
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

	private interface ScenarioApplier {
		void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks);
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
					scenario.slippageTicks(),
					3,
					50,
					5.0,
					true,
					0.0,
					FUNDED_PROFILE
				);
				RunSummary summary = loadRun(id, scenario.name());
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
		values.add(new SimpleScenario("baseline_51k", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {}
		}));

		double[] omomTargets = new double[] {0.50, 0.55, 0.60};
		double[] fvgTargets = new double[] {0.40, 0.50, 0.60, 0.75};
		for (final double omomTarget : omomTargets) {
			for (final double fvgTarget : fvgTargets) {
				values.add(new SimpleScenario("hc_targets_omom" + tag(omomTarget) + "_fvg" + tag(fvgTarget), new ScenarioApplier() {
					public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
						applyHighCount(settings, true);
						applyWinTargets(settings, omomTarget, fvgTarget, 0.75, 0.50);
					}
				}));
				values.add(new SimpleScenario("hc_shortmes_targets_omom" + tag(omomTarget) + "_fvg" + tag(fvgTarget), new ScenarioApplier() {
					public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
						applyHighCount(settings, false);
						applyWinTargets(settings, omomTarget, fvgTarget, 0.75, 0.50);
					}
				}));
			}
		}

		values.add(new SimpleScenario("hc_all_lowest_targets", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyHighCount(settings, true);
				applyWinTargets(settings, 0.50, 0.40, 0.75, 0.50);
				enableHighWinRefills(settings);
			}
		}));
		values.add(new SimpleScenario("hc_shortmes_all_lowest_targets", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyHighCount(settings, false);
				applyWinTargets(settings, 0.50, 0.40, 0.75, 0.50);
				enableHighWinRefills(settings);
			}
		}));
		values.add(new SimpleScenario("hc_no_es_vwap_lowest_targets", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyHighCount(settings, true);
				applyWinTargets(settings, 0.50, 0.40, 0.75, 0.50);
				FuturesManager.FuturesStrategySettings es = settings.get("ES");
				es.vwapPullback.enabled = false;
				enableHighWinRefills(settings);
			}
		}));
		values.add(new SimpleScenario("hc_no_mgc_orb2_lowest_targets", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyHighCount(settings, true);
				applyWinTargets(settings, 0.50, 0.40, 0.75, 0.50);
				FuturesManager.FuturesStrategySettings mgc = settings.get("MGC");
				mgc.enableOrbRetest = false;
				enableHighWinRefills(settings);
			}
		}));
		values.add(new SimpleScenario("hc_quality_remove_lowwin_lowest_targets", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyHighCount(settings, false);
				applyWinTargets(settings, 0.50, 0.40, 0.75, 0.50);
				settings.get("ES").vwapPullback.enabled = false;
				settings.get("MGC").enableOrbRetest = false;
				enableHighWinRefills(settings);
			}
		}));

		values.add(new SimpleScenario("count_refill_cmom_sweep_pdb_low_targets", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyWinTargets(settings, 0.50, 0.40, 0.75, 0.50);
				enableHighWinRefills(settings);
			}
		}));
		values.add(new SimpleScenario("count_refill_plus_nq_fvg10_12_low_targets", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqFvgTenTwelve(settings.get("NQ"));
				applyWinTargets(settings, 0.50, 0.40, 0.75, 0.50);
				enableHighWinRefills(settings);
			}
		}));

		int[] fvgCaps = new int[] {2, 3, 4, 5};
		double[] rescueOmomTargets = new double[] {0.55, 0.60, 0.65};
		double[] rescueFvgTargets = new double[] {0.60, 0.75, 0.90, 1.00};
		double[] minRewardTargets = new double[] {0.75, 0.90, 1.00};
		for (final int fvgCap : fvgCaps) {
			for (final double omomTarget : rescueOmomTargets) {
				for (final double fvgTarget : rescueFvgTargets) {
					for (final double minRewardTarget : minRewardTargets) {
						values.add(new SimpleScenario("rescue_cap" + fvgCap + "_omom" + tag(omomTarget) + "_fvg" + tag(fvgTarget) + "_min" + tag(minRewardTarget), new ScenarioApplier() {
							public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
								applyHighCountWithFvgCap(settings, false, fvgCap);
								applyProfitRescueTargets(settings, omomTarget, fvgTarget, minRewardTarget);
								enableHighWinRefills(settings);
							}
						}));
					}
				}
			}
		}

		return values;
	}

	private static void applyHighCount(Map<String, FuturesManager.FuturesStrategySettings> settings, boolean allowMesAfternoonLongs) {
		enableNqFvgAll(settings.get("NQ"));
		enableNqPositiveOrb(settings.get("NQ"));
		enableFilteredMesAfternoon(settings.get("MES"));
		FuturesManager.FuturesStrategySettings mes = settings.get("MES");
		mes.allowAfternoonContinuationLongs = allowMesAfternoonLongs;
		mes.allowAfternoonContinuationShorts = true;
	}

	private static void applyHighCountWithFvgCap(Map<String, FuturesManager.FuturesStrategySettings> settings, boolean allowMesAfternoonLongs, int fvgCap) {
		applyHighCount(settings, allowMesAfternoonLongs);
		FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
		nq.fvg.maxTradesPerDay = fvgCap;
	}

	private static void applyWinTargets(
		Map<String, FuturesManager.FuturesStrategySettings> settings,
		double openingMomentumTarget,
		double fvgTarget,
		double minRewardTarget,
		double frequencyTarget
	) {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings s = settings.get(symbol);
			if (s == null) {
				continue;
			}
			s.openingMomentumRewardRisk = openingMomentumTarget;
			s.fvgRewardRisk = fvgTarget;
			s.priorDayBreakoutRewardRisk = Math.min(s.priorDayBreakoutRewardRisk, fvgTarget);
			s.vwapReclaimRewardRisk = Math.min(s.vwapReclaimRewardRisk, fvgTarget);
			s.afternoonRewardRisk = Math.min(s.afternoonRewardRisk, frequencyTarget);
			s.closeMomentumRewardRisk = Math.min(s.closeMomentumRewardRisk, frequencyTarget);
			s.marketIntradayMomentumRewardRisk = Math.min(s.marketIntradayMomentumRewardRisk, frequencyTarget);
			s.keltnerRewardRisk = Math.min(s.keltnerRewardRisk, 0.35);
			s.microScalpRewardRisk = Math.min(s.microScalpRewardRisk, 0.35);
			s.minRewardRisk = Math.min(s.minRewardRisk, minRewardTarget);
			s.enableAdaptiveExits = false;
		}
	}

	private static void applyProfitRescueTargets(
		Map<String, FuturesManager.FuturesStrategySettings> settings,
		double openingMomentumTarget,
		double fvgTarget,
		double minRewardTarget
	) {
		FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
		mnq.openingMomentumRewardRisk = openingMomentumTarget;
		mnq.enableAdaptiveExits = false;

		FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
		nq.fvgRewardRisk = fvgTarget;
		nq.minRewardRisk = Math.min(nq.minRewardRisk, minRewardTarget);
		nq.priorDayBreakoutRewardRisk = Math.min(nq.priorDayBreakoutRewardRisk, Math.max(0.75, fvgTarget));
		nq.vwapReclaimRewardRisk = Math.min(nq.vwapReclaimRewardRisk, Math.max(0.75, fvgTarget));
		nq.enableAdaptiveExits = false;

		FuturesManager.FuturesStrategySettings mes = settings.get("MES");
		mes.afternoonRewardRisk = Math.min(mes.afternoonRewardRisk, 0.75);
		mes.closeMomentumRewardRisk = Math.min(mes.closeMomentumRewardRisk, 0.75);

		FuturesManager.FuturesStrategySettings m2k = settings.get("M2K");
		m2k.closeMomentumRewardRisk = Math.min(m2k.closeMomentumRewardRisk, 0.75);
	}

	private static void enableHighWinRefills(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
		mnq.sweep.enabled = true;
		mnq.sweep.maxTradesPerDay = 5;
		mnq.enableLateSweep = true;
		mnq.enableSweepSecondChance = true;
		mnq.earlySweepReclaimTicks = 4.0;
		mnq.lateSweepReclaimTicks = 10.0;
		mnq.lateSweepCloseLocation = 0.35;

		FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
		nq.priorDayBreakout.enabled = true;
		nq.priorDayBreakout.maxTradesPerDay = 5;
		nq.allowPriorDayBreakoutLongs = false;
		nq.allowPriorDayBreakoutShorts = true;
		nq.priorDayBreakoutMinVolumeRatio = 0.75;
		nq.vwapPullback.enabled = true;
		nq.vwapPullback.maxTradesPerDay = 5;
		nq.allowVwapPullbackLongs = false;
		nq.allowVwapPullbackShorts = true;

		FuturesManager.FuturesStrategySettings mes = settings.get("MES");
		mes.closeMomentum.enabled = true;
		mes.closeMomentum.maxTradesPerDay = 3;
		mes.allowCloseMomentumLongs = false;
		mes.allowCloseMomentumShorts = true;
		mes.closeMomentumShortStartMinute = 885;
		mes.closeMomentumMinMoveTicks = 20.0;
		mes.closeMomentumVolumeRatio = 0.7;

		FuturesManager.FuturesStrategySettings m2k = settings.get("M2K");
		m2k.closeMomentum.enabled = true;
		m2k.closeMomentum.maxTradesPerDay = 3;
		m2k.allowCloseMomentumLongs = false;
		m2k.allowCloseMomentumShorts = true;
		m2k.closeMomentumShortStartMinute = 870;
		m2k.closeMomentumMinMoveTicks = 16.0;
		m2k.closeMomentumVolumeRatio = 0.6;

		FuturesManager.FuturesStrategySettings mgc = settings.get("MGC");
		mgc.orb.enabled = true;
		mgc.orb.maxTradesPerDay = 3;
		mgc.allowOrbLongs = true;
		mgc.allowOrbShorts = true;
		mgc.sweep.enabled = true;
		mgc.sweep.maxTradesPerDay = 5;
	}

	private static void enableNqFvgAll(FuturesManager.FuturesStrategySettings s) {
		if (s == null) return;
		s.fvg.enabled = true;
		s.fvg.maxTradesPerDay = 5;
		s.allowFvgLongs = true;
		s.allowFvgShorts = true;
		s.fvgMinVolumeRatio = Math.min(s.fvgMinVolumeRatio, 0.6);
		s.fvgStartMinute = 600;
		s.fvgEndMinute = 900;
		s.fvgSkipStartMinute = 0;
		s.fvgSkipEndMinute = 0;
	}

	private static void enableNqFvgTenTwelve(FuturesManager.FuturesStrategySettings s) {
		enableNqFvgAll(s);
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
				 "SELECT totalProfit, returnPct, winRate, numTrades, profitFactor, maxDrawdownPct, maxIntradayLoss, maxAggregateMae, ruleViolation, ruleMessage "
				 + "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ?")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					summary.pnl = rs.getDouble("totalProfit");
					summary.returnPct = rs.getDouble("returnPct");
					summary.winRate = rs.getDouble("winRate");
					summary.trades = rs.getInt("numTrades");
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

	private static void printRankings(List<RunSummary> summaries) {
		Collections.sort(summaries, new Comparator<RunSummary>() {
			public int compare(RunSummary first, RunSummary second) {
				if (first.ruleViolation != second.ruleViolation) {
					return first.ruleViolation - second.ruleViolation;
				}
				double firstDistance = targetDistance(first);
				double secondDistance = targetDistance(second);
				int distanceCompare = Double.compare(firstDistance, secondDistance);
				if (distanceCompare != 0) {
					return distanceCompare;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});
		System.out.println("TOP_TARGET_DISTANCE");
		printTop(summaries, 10);

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
		printTop(summaries, 10);

		Collections.sort(summaries, new Comparator<RunSummary>() {
			public int compare(RunSummary first, RunSummary second) {
				if (first.ruleViolation != second.ruleViolation) {
					return first.ruleViolation - second.ruleViolation;
				}
				int winCompare = Double.compare(second.winRate, first.winRate);
				if (winCompare != 0) {
					return winCompare;
				}
				return second.trades - first.trades;
			}
		});
		System.out.println("TOP_BY_WIN");
		printTop(summaries, 10);
	}

	private static double targetDistance(RunSummary summary) {
		double tradeMiss = Math.max(0.0, 800.0 - summary.trades) / 8.0;
		double winMiss = Math.max(0.0, 65.0 - summary.winRate);
		return tradeMiss + (winMiss * 3.0);
	}

	private static void printTop(List<RunSummary> summaries, int limit) {
		int printed = 0;
		for (RunSummary summary : summaries) {
			if (printed >= limit) {
				break;
			}
			if (summary.ruleViolation != 0) {
				continue;
			}
			System.out.println(line(summary));
			printed++;
		}
	}

	private static String line(RunSummary summary) {
		return "RUN "
			+ summary.name
			+ " id=" + summary.id
			+ " pnl=" + round(summary.pnl)
			+ " return=" + round(summary.returnPct)
			+ " trades=" + summary.trades
			+ " win=" + round(summary.winRate)
			+ " pf=" + round(summary.profitFactor)
			+ " dd=" + round(summary.maxDrawdownPct)
			+ " intraday=" + round(summary.maxIntradayLoss)
			+ " mae=" + round(summary.maxAggregateMae)
			+ " violation=" + summary.ruleViolation
			+ " distance=" + round(targetDistance(summary))
			+ " message=\"" + summary.message + "\"";
	}

	private static String tag(double value) {
		return String.valueOf((int) Math.round(value * 100.0));
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
