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

public class TradeCountSprintRunner {
	private static final String CORE_SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K";
	private static final String GC_SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,GC";
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-04";
	private static final String FUNDED_PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String[] CORE_LIST = new String[] {"MES", "MNQ", "NQ", "MGC", "ES", "M2K"};
	private static final String[] ALL_LIST = new String[] {"MES", "MNQ", "NQ", "MGC", "ES", "M2K", "GC"};
	private static final String[] EQUITY_LIST = new String[] {"MES", "MNQ", "NQ", "ES", "M2K"};
	private static final String[] EQUITY_EXPANSION_LIST = new String[] {"MES", "NQ", "ES", "M2K"};
	private static final double BASELINE_PNL = 36204.74;
	private static final int BASELINE_TRADES = 434;

	private interface Scenario {
		String name();
		default String symbols() { return CORE_SYMBOLS; }
		default double slippageTicks() { return 1.0; }
		default boolean useSavedRisk() { return true; }
		default double maxRiskPerTrade() { return 700.0; }
		default int maxContracts() { return 50; }
		default double maxAggregateMiniUnits() { return 5.0; }
		void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks);
	}

	private static class RunSummary {
		int id;
		String name;
		String symbols;
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
		String nameFilter = args.length > 0 ? args[0].trim().toLowerCase() : "";
		Map<String, FuturesManager.FuturesStrategySettings> baseSettings = loadBaseSettings();
		Map<String, FuturesManager.FuturesRiskSettings> baseRisks = loadBaseRisks();
		List<RunSummary> summaries = new ArrayList<RunSummary>();
		try {
			List<Scenario> scenarios = scenarios();
			for (int index = 0; index < scenarios.size(); index++) {
				Scenario scenario = scenarios.get(index);
				if (!nameFilter.isEmpty() && !scenario.name().toLowerCase().contains(nameFilter)) {
					continue;
				}
				restore(baseSettings, baseRisks);
				Map<String, FuturesManager.FuturesStrategySettings> settings = loadBaseSettings();
				Map<String, FuturesManager.FuturesRiskSettings> risks = loadBaseRisks();
				scenario.apply(settings, risks);
				save(settings, risks);
				int id = FuturesManager.generatePortfolioBacktest(
					scenario.symbols(),
					START_DATE,
					END_DATE,
					50000.0,
					2000.0,
					1000.0,
					scenario.maxRiskPerTrade(),
					scenario.maxContracts(),
					1.24,
					scenario.slippageTicks(),
					3,
					50,
					scenario.maxAggregateMiniUnits(),
					scenario.useSavedRisk(),
					0.0,
					FUNDED_PROFILE
				);
				RunSummary summary = loadRun(id, scenario.name(), scenario.symbols());
				summaries.add(summary);
				System.out.println(line(summary));
			}
		} finally {
			restore(baseSettings, baseRisks);
		}

		Collections.sort(summaries, new Comparator<RunSummary>() {
			@Override
			public int compare(RunSummary first, RunSummary second) {
				if (first.ruleViolation != second.ruleViolation) {
					return first.ruleViolation - second.ruleViolation;
				}
				if (first.trades != second.trades) {
					return second.trades - first.trades;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});
		System.out.println("TOP_BY_TRADES");
		printTop(summaries, 10);

		Collections.sort(summaries, new Comparator<RunSummary>() {
			@Override
			public int compare(RunSummary first, RunSummary second) {
				if (first.ruleViolation != second.ruleViolation) {
					return first.ruleViolation - second.ruleViolation;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});
		System.out.println("TOP_BY_PNL");
		printTop(summaries, 10);
	}

	private static List<Scenario> scenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		values.add(new Scenario() {
			public String name() { return "baseline_replay"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {}
		});
		values.add(new Scenario() {
			public String name() { return "mnq_omom_max7"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentum.maxTradesPerDay = 7;
			}
		});
		values.add(new Scenario() {
			public String name() { return "mnq_omom_max10"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentum.maxTradesPerDay = 10;
			}
		});
		values.add(new Scenario() {
			public String name() { return "mnq_omom_max7_short_1100"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentum.maxTradesPerDay = 7;
				mnq.openingMomentumShortEndMinute = 660;
			}
		});
		values.add(new Scenario() {
			public String name() { return "mnq_omom_max8_short_1130"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentum.maxTradesPerDay = 8;
				mnq.openingMomentumShortEndMinute = 690;
			}
		});
		values.add(new Scenario() {
			public String name() { return "mnq_omom_max7_allow_1000_longs"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentum.maxTradesPerDay = 7;
				mnq.openingMomentumLongSkipStartMinute = 630;
				mnq.openingMomentumLongSkipEndMinute = 659;
			}
		});
		values.add(new Scenario() {
			public String name() { return "mnq_omom_max7_no_long_skip"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentum.maxTradesPerDay = 7;
				mnq.openingMomentumLongSkipStartMinute = 0;
				mnq.openingMomentumLongSkipEndMinute = 0;
			}
		});
		values.add(new Scenario() {
			public String name() { return "mnq_omom_max7_lower_rr"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentum.maxTradesPerDay = 7;
				mnq.openingMomentumRewardRisk = 0.78;
			}
		});
		values.add(new Scenario() {
			public String name() { return "equity_aft_playbook"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : EQUITY_EXPANSION_LIST) {
					enableAfternoon(settings.get(symbol), isMini(symbol) ? 2 : 3);
				}
			}
		});
		values.add(new Scenario() {
			public String name() { return "equity_fvg_playbook"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : EQUITY_EXPANSION_LIST) {
					enableFvg(settings.get(symbol), isMini(symbol) ? 1 : 2);
				}
			}
		});
		values.add(new Scenario() {
			public String name() { return "equity_pdb_playbook"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : EQUITY_EXPANSION_LIST) {
					enablePdb(settings.get(symbol), 4);
				}
			}
		});
		values.add(new Scenario() {
			public String name() { return "equity_vwap_reclaim_playbook"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : EQUITY_EXPANSION_LIST) {
					enableVwapReclaim(settings.get(symbol), 2);
				}
			}
		});
		values.add(new Scenario() {
			public String name() { return "micro_equity_omom_playbook"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableOpeningMomentum(settings.get("MES"), "MES", 4);
				enableOpeningMomentum(settings.get("M2K"), "M2K", 4);
			}
		});
		values.add(new Scenario() {
			public String name() { return "all_equity_omom_playbook"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableOpeningMomentum(settings.get("MES"), "MES", 4);
				enableOpeningMomentum(settings.get("M2K"), "M2K", 4);
				enableOpeningMomentum(settings.get("NQ"), "NQ", 3);
				enableOpeningMomentum(settings.get("ES"), "ES", 2);
				settings.get("MNQ").openingMomentum.maxTradesPerDay = 7;
			}
		});
		values.add(new Scenario() {
			public String name() { return "orb_retest_equity_playbook"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : EQUITY_EXPANSION_LIST) {
					enableOrbRetest(settings.get(symbol), isMini(symbol) ? 3 : 4);
				}
			}
		});
		values.add(new Scenario() {
			public String name() { return "mim_ipb_equity_playbook"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : EQUITY_EXPANSION_LIST) {
					enableMimIpb(settings.get(symbol), isMini(symbol) ? 2 : 3);
				}
			}
		});
		values.add(new Scenario() {
			public String name() { return "m2k_core_stack"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableCoreStack(settings.get("M2K"), 4);
			}
		});
		values.add(new Scenario() {
			public String name() { return "mes_core_stack"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableCoreStack(settings.get("MES"), 4);
			}
		});
		values.add(new Scenario() {
			public String name() { return "nq_count_stack"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				enableCoreStack(nq, 3);
				enableAfternoon(nq, 2);
				enableFvg(nq, 2);
				enablePdb(nq, 4);
				enableOpeningMomentum(nq, "NQ", 4);
				enableMimIpb(nq, 2);
			}
		});
		values.add(new Scenario() {
			public String name() { return "es_count_stack"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings es = settings.get("ES");
				enableCoreStack(es, 3);
				enableAfternoon(es, 2);
				enableFvg(es, 1);
				enablePdb(es, 3);
				enableOpeningMomentum(es, "ES", 2);
				enableMimIpb(es, 2);
			}
		});
		values.add(new Scenario() {
			public String name() { return "micro_scalp_probe"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : new String[] {"MES", "MNQ", "MGC", "M2K"}) {
					enableMicroScalp(settings.get(symbol), 6);
				}
			}
		});
		values.add(new Scenario() {
			public String name() { return "keltner_probe"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : EQUITY_LIST) {
					enableKeltner(settings.get(symbol), isMini(symbol) ? 3 : 6);
				}
				enableKeltner(settings.get("MGC"), 4);
			}
		});
		values.add(new Scenario() {
			public String name() { return "gc_mgc_clone_risk250"; }
			public String symbols() { return GC_SYMBOLS; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				copyMgcPlaybookToGc(settings.get("MGC"), settings.get("GC"));
				setRisk(risks.get("GC"), 250.0, 1);
			}
		});
		values.add(new Scenario() {
			public String name() { return "gc_mgc_clone_risk400"; }
			public String symbols() { return GC_SYMBOLS; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				copyMgcPlaybookToGc(settings.get("MGC"), settings.get("GC"));
				setRisk(risks.get("GC"), 400.0, 1);
			}
		});
		values.add(new Scenario() {
			public String name() { return "count_stack_plus_gc"; }
			public String symbols() { return GC_SYMBOLS; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				copyMgcPlaybookToGc(settings.get("MGC"), settings.get("GC"));
				setRisk(risks.get("GC"), 250.0, 1);
				settings.get("MNQ").openingMomentum.maxTradesPerDay = 7;
				for (String symbol : EQUITY_EXPANSION_LIST) {
					enableAfternoon(settings.get(symbol), isMini(symbol) ? 2 : 3);
					enableFvg(settings.get(symbol), isMini(symbol) ? 1 : 2);
					enablePdb(settings.get(symbol), 3);
				}
			}
		});
		values.add(new Scenario() {
			public String name() { return "full_700_probe_saved_risk"; }
			public String symbols() { return GC_SYMBOLS; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				copyMgcPlaybookToGc(settings.get("MGC"), settings.get("GC"));
				setRisk(risks.get("GC"), 250.0, 1);
				for (String symbol : ALL_LIST) {
					FuturesManager.FuturesStrategySettings s = settings.get(symbol);
					enableCoreStack(s, isMini(symbol) ? 4 : 6);
					enableAfternoon(s, isMini(symbol) ? 2 : 3);
					enableFvg(s, isMini(symbol) ? 1 : 2);
					enablePdb(s, 4);
					enableMimIpb(s, isMini(symbol) ? 2 : 3);
					if (!"GC".equals(symbol)) {
						enableOpeningMomentum(s, symbol, isMini(symbol) ? 3 : 5);
					}
					s.enableEarlyLossCut = true;
					s.earlyLossCutBars = 10;
					s.earlyLossCutR = 0.5;
				}
				settings.get("MNQ").openingMomentum.maxTradesPerDay = 8;
			}
		});
		values.add(new Scenario() {
			public String name() { return "full_700_probe_low_risk"; }
			public String symbols() { return GC_SYMBOLS; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				copyMgcPlaybookToGc(settings.get("MGC"), settings.get("GC"));
				for (String symbol : ALL_LIST) {
					FuturesManager.FuturesStrategySettings s = settings.get(symbol);
					enableCoreStack(s, isMini(symbol) ? 5 : 7);
					enableAfternoon(s, isMini(symbol) ? 3 : 4);
					enableFvg(s, isMini(symbol) ? 2 : 3);
					enablePdb(s, 5);
					enableMimIpb(s, isMini(symbol) ? 3 : 4);
					if (!"GC".equals(symbol)) {
						enableOpeningMomentum(s, symbol, isMini(symbol) ? 4 : 6);
					}
					s.enableEarlyLossCut = true;
					s.earlyLossCutBars = 8;
					s.earlyLossCutR = 0.45;
					s.openMaeRiskMultiplier = Math.max(1.0, s.openMaeRiskMultiplier);
				}
				settings.get("MNQ").openingMomentum.maxTradesPerDay = 10;
				setRisk(risks.get("MNQ"), 450.0, 30);
				setRisk(risks.get("MGC"), 325.0, 30);
				setRisk(risks.get("NQ"), 250.0, 1);
				setRisk(risks.get("ES"), 225.0, 2);
				setRisk(risks.get("MES"), 250.0, 20);
				setRisk(risks.get("M2K"), 250.0, 20);
				setRisk(risks.get("GC"), 180.0, 1);
			}
		});
		values.add(new Scenario() {
			public String name() { return "quality_count_combo"; }
			public String symbols() { return GC_SYMBOLS; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				copyMgcPlaybookToGc(settings.get("MGC"), settings.get("GC"));
				setRisk(risks.get("GC"), 250.0, 1);
				settings.get("MNQ").openingMomentum.maxTradesPerDay = 7;
				enableAfternoon(settings.get("NQ"), 2);
				enableFvg(settings.get("NQ"), 2);
				enablePdb(settings.get("NQ"), 4);
				enableOrbRetest(settings.get("M2K"), 3);
				enablePdb(settings.get("M2K"), 3);
				enableAfternoon(settings.get("M2K"), 2);
				enableFvg(settings.get("M2K"), 1);
				enablePdb(settings.get("ES"), 2);
				enableFvg(settings.get("MES"), 1);
			}
		});
		values.add(new Scenario() {
			public String name() { return "refined_nq_fvg_orb9_mes_aft"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqFvgAll(settings.get("NQ"));
				enableNqPositiveOrb(settings.get("NQ"));
				enableFilteredMesAfternoon(settings.get("MES"));
			}
		});
		values.add(new Scenario() {
			public String name() { return "refined_nq_fvg_orb9_mes_aft_mnq_rr"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyMnqLowerOpeningMomentumReward(settings.get("MNQ"));
				enableNqFvgAll(settings.get("NQ"));
				enableNqPositiveOrb(settings.get("NQ"));
				enableFilteredMesAfternoon(settings.get("MES"));
			}
		});
		values.add(new Scenario() {
			public String name() { return "refined_700_nq_fvg_orb9_pdb_short_mes_aft_mnq_rr"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyMnqLowerOpeningMomentumReward(settings.get("MNQ"));
				enableNqFvgAll(settings.get("NQ"));
				enableNqPositiveOrb(settings.get("NQ"));
				enableNqPdbShortOnly(settings.get("NQ"));
				enableFilteredMesAfternoon(settings.get("MES"));
			}
		});
		values.add(new Scenario() {
			public String name() { return "refined_money700_nq_fvg_orb9_pdb_short_mes_aft_short_mnq_rr"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyMnqLowerOpeningMomentumReward(settings.get("MNQ"));
				enableNqFvgAll(settings.get("NQ"));
				enableNqPositiveOrb(settings.get("NQ"));
				enableNqPdbShortOnly(settings.get("NQ"));
				enableFilteredMesAfternoon(settings.get("MES"));
				FuturesManager.FuturesStrategySettings mes = settings.get("MES");
				mes.allowAfternoonContinuationLongs = false;
				mes.allowAfternoonContinuationShorts = true;
			}
		});
		values.add(new Scenario() {
			public String name() { return "refined_money700_nq_fvg_rr090"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyMoney700(settings);
				settings.get("NQ").fvgRewardRisk = 0.9;
			}
		});
		values.add(new Scenario() {
			public String name() { return "refined_money700_nq_fvg_rr100"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyMoney700(settings);
				settings.get("NQ").fvgRewardRisk = 1.0;
			}
		});
		values.add(new Scenario() {
			public String name() { return "refined_money700_nq_fvg_rr140"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyMoney700(settings);
				settings.get("NQ").fvgRewardRisk = 1.4;
			}
		});
		values.add(new Scenario() {
			public String name() { return "refined_money700_nq_fvg_hold12"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyMoney700(settings);
				settings.get("NQ").fvgMaxHoldBars = 12;
			}
		});
		values.add(new Scenario() {
			public String name() { return "refined_money700_nq_fvg_hold30"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyMoney700(settings);
				settings.get("NQ").fvgMaxHoldBars = 30;
			}
		});
		values.add(new Scenario() {
			public String name() { return "refined_700_plus_es_pdb"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyMnqLowerOpeningMomentumReward(settings.get("MNQ"));
				enableNqFvgAll(settings.get("NQ"));
				enableNqPositiveOrb(settings.get("NQ"));
				enableNqPdbShortOnly(settings.get("NQ"));
				enableFilteredMesAfternoon(settings.get("MES"));
				enablePdb(settings.get("ES"), 3);
			}
		});
		values.add(new Scenario() {
			public String name() { return "refined_quality_nq_fvg_10_12_orb9_mes_aft_mnq_rr"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyMnqLowerOpeningMomentumReward(settings.get("MNQ"));
				enableNqFvgTenTwelve(settings.get("NQ"));
				enableNqPositiveOrb(settings.get("NQ"));
				enableFilteredMesAfternoon(settings.get("MES"));
			}
		});
		values.add(new Scenario() {
			public String name() { return "refined_quality_es_fvg_long_nq_fvg_10_12_orb9_mes_aft_mnq_rr"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyMnqLowerOpeningMomentumReward(settings.get("MNQ"));
				enableNqFvgTenTwelve(settings.get("NQ"));
				enableNqPositiveOrb(settings.get("NQ"));
				enableFilteredMesAfternoon(settings.get("MES"));
				enableEsFvgLongTenTwelve(settings.get("ES"));
			}
		});
		values.add(new Scenario() {
			public String name() { return "refined_700_quality_es_fvg_long_es_pdb_nq_fvg_10_12_orb9_mes_aft_mnq_rr"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyMnqLowerOpeningMomentumReward(settings.get("MNQ"));
				enableNqFvgTenTwelve(settings.get("NQ"));
				enableNqPositiveOrb(settings.get("NQ"));
				enableNqPdbShortOnly(settings.get("NQ"));
				enableFilteredMesAfternoon(settings.get("MES"));
				enableEsFvgLongTenTwelve(settings.get("ES"));
				enablePdb(settings.get("ES"), 3);
			}
		});
		return values;
	}

	private static void applyMoney700(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyMnqLowerOpeningMomentumReward(settings.get("MNQ"));
		enableNqFvgAll(settings.get("NQ"));
		enableNqPositiveOrb(settings.get("NQ"));
		enableNqPdbShortOnly(settings.get("NQ"));
		enableFilteredMesAfternoon(settings.get("MES"));
		FuturesManager.FuturesStrategySettings mes = settings.get("MES");
		mes.allowAfternoonContinuationLongs = false;
		mes.allowAfternoonContinuationShorts = true;
	}

	private static void enableCoreStack(FuturesManager.FuturesStrategySettings s, int maxTrades) {
		if (s == null) {
			return;
		}
		s.orb.enabled = true;
		s.orb.maxTradesPerDay = Math.max(s.orb.maxTradesPerDay, maxTrades);
		s.enableOrbRetest = true;
		s.allowOrbLongs = true;
		s.allowOrbShorts = true;
		s.allowOrbRetestLongs = true;
		s.allowOrbRetestShorts = true;
		s.sweep.enabled = true;
		s.sweep.maxTradesPerDay = Math.max(s.sweep.maxTradesPerDay, maxTrades);
		s.enableEarlySweep = true;
		s.enableLateSweep = true;
		s.enableSweepSecondChance = true;
		s.vwapPullback.enabled = true;
		s.vwapPullback.maxTradesPerDay = Math.max(s.vwapPullback.maxTradesPerDay, maxTrades);
		s.closeMomentum.enabled = true;
		s.closeMomentum.maxTradesPerDay = Math.max(s.closeMomentum.maxTradesPerDay, 2);
		s.requireHigherTimeframeGuard = true;
		s.allowShorts = true;
		s.enableCompressedOrbBreakout = true;
	}

	private static void enableOrbRetest(FuturesManager.FuturesStrategySettings s, int maxTrades) {
		if (s == null) {
			return;
		}
		s.orb.enabled = true;
		s.orb.maxTradesPerDay = Math.max(s.orb.maxTradesPerDay, maxTrades);
		s.enableOrbRetest = true;
		s.allowOrbLongs = true;
		s.allowOrbShorts = true;
		s.allowOrbRetestLongs = true;
		s.allowOrbRetestShorts = true;
		s.enableCompressedOrbBreakout = true;
		s.orbCompressedMaxRiskTicks = Math.min(s.orbCompressedMaxRiskTicks, 60.0);
	}

	private static void enableOpeningMomentum(FuturesManager.FuturesStrategySettings s, String symbol, int maxTrades) {
		if (s == null) {
			return;
		}
		s.openingMomentum.enabled = true;
		s.openingMomentum.maxTradesPerDay = Math.max(s.openingMomentum.maxTradesPerDay, maxTrades);
		s.openingMomentumAllowMultiplePerSide = true;
		s.openingMomentumRangeMinutes = 10;
		s.openingMomentumBucketMinutes = 15;
		s.openingMomentumVolumeRatio = 0.5;
		s.openingMomentumRewardRisk = 0.88;
		s.openingMomentumMaxHoldBars = 120;
		s.openingMomentumLongStartMinute = 570;
		s.openingMomentumLongEndMinute = 660;
		s.openingMomentumShortStartMinute = 620;
		s.openingMomentumShortEndMinute = 649;
		s.openingMomentumMaxRiskTicks = isMini(symbol) ? 80.0 : 220.0;
		s.allowOpeningMomentumLongs = true;
		s.allowOpeningMomentumShorts = true;
		s.openingMomentumLongSkipStartMinute = 599;
		s.openingMomentumLongSkipEndMinute = 659;
	}

	private static void enableAfternoon(FuturesManager.FuturesStrategySettings s, int maxTrades) {
		if (s == null) {
			return;
		}
		s.afternoonContinuation.enabled = true;
		s.afternoonContinuation.maxTradesPerDay = Math.max(s.afternoonContinuation.maxTradesPerDay, maxTrades);
		s.allowAfternoonContinuationLongs = true;
		s.allowAfternoonContinuationShorts = true;
		s.afternoonStartMinute = 780;
		s.afternoonEndMinute = 920;
		s.afternoonMinVolumeRatio = Math.min(s.afternoonMinVolumeRatio, 0.8);
		s.afternoonRewardRisk = Math.min(s.afternoonRewardRisk, 0.9);
		s.afternoonMaxRiskTicks = Math.min(s.afternoonMaxRiskTicks, 48.0);
	}

	private static void enableFvg(FuturesManager.FuturesStrategySettings s, int maxTrades) {
		if (s == null) {
			return;
		}
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

	private static void enableEsFvgLongTenTwelve(FuturesManager.FuturesStrategySettings s) {
		enableFvg(s, 2);
		s.allowFvgLongs = true;
		s.allowFvgShorts = false;
		s.fvgStartMinute = 600;
		s.fvgEndMinute = 779;
		s.fvgSkipStartMinute = 660;
		s.fvgSkipEndMinute = 719;
	}

	private static void enableNqPositiveOrb(FuturesManager.FuturesStrategySettings s) {
		enableOrbRetest(s, 3);
		s.orbShortSkipStartMinute = 600;
		s.orbShortSkipEndMinute = 659;
	}

	private static void enableNqPdbShortOnly(FuturesManager.FuturesStrategySettings s) {
		enablePdb(s, 4);
		s.allowPriorDayBreakoutLongs = false;
		s.allowPriorDayBreakoutShorts = true;
	}

	private static void enableFilteredMesAfternoon(FuturesManager.FuturesStrategySettings s) {
		enableAfternoon(s, 3);
		s.afternoonStartMinute = 780;
		s.afternoonEndMinute = 920;
		s.afternoonLongStartMinute = 900;
		s.afternoonLongEndMinute = 920;
		s.afternoonShortStartMinute = 780;
		s.afternoonShortEndMinute = 920;
		s.afternoonSkipStartMinute = 840;
		s.afternoonSkipEndMinute = 899;
	}

	private static void applyMnqLowerOpeningMomentumReward(FuturesManager.FuturesStrategySettings s) {
		if (s != null) {
			s.openingMomentumRewardRisk = 0.78;
		}
	}

	private static void enablePdb(FuturesManager.FuturesStrategySettings s, int maxTrades) {
		if (s == null) {
			return;
		}
		s.priorDayBreakout.enabled = true;
		s.priorDayBreakout.maxTradesPerDay = Math.max(s.priorDayBreakout.maxTradesPerDay, maxTrades);
		s.allowPriorDayBreakoutLongs = true;
		s.allowPriorDayBreakoutShorts = true;
		s.priorDayBreakoutMinVolumeRatio = Math.min(s.priorDayBreakoutMinVolumeRatio, 0.75);
		s.priorDayBreakoutRewardRisk = Math.max(s.priorDayBreakoutRewardRisk, 1.0);
	}

	private static void enableVwapReclaim(FuturesManager.FuturesStrategySettings s, int maxTrades) {
		if (s == null) {
			return;
		}
		s.vwapReclaim.enabled = true;
		s.vwapReclaim.maxTradesPerDay = Math.max(s.vwapReclaim.maxTradesPerDay, maxTrades);
		s.allowVwapReclaimLongs = true;
		s.allowVwapReclaimShorts = true;
		s.vwapReclaimMinVolumeRatio = Math.min(s.vwapReclaimMinVolumeRatio, 0.9);
		s.vwapReclaimRewardRisk = Math.max(s.vwapReclaimRewardRisk, 0.9);
	}

	private static void enableMimIpb(FuturesManager.FuturesStrategySettings s, int maxTrades) {
		if (s == null) {
			return;
		}
		s.marketIntradayMomentum.enabled = true;
		s.marketIntradayMomentum.maxTradesPerDay = Math.max(s.marketIntradayMomentum.maxTradesPerDay, maxTrades);
		s.allowMarketIntradayMomentumLongs = true;
		s.allowMarketIntradayMomentumShorts = true;
		s.marketIntradayMomentumMinVolumeRatio = Math.min(s.marketIntradayMomentumMinVolumeRatio, 0.6);
		s.marketIntradayMomentumRewardRisk = Math.max(s.marketIntradayMomentumRewardRisk, 0.8);
	}

	private static void enableMicroScalp(FuturesManager.FuturesStrategySettings s, int maxTrades) {
		if (s == null) {
			return;
		}
		s.microScalp.enabled = true;
		s.microScalp.maxTradesPerDay = Math.max(s.microScalp.maxTradesPerDay, maxTrades);
		s.microScalpMinVolumeRatio = Math.min(s.microScalpMinVolumeRatio, 0.65);
		s.microScalpRewardRisk = Math.max(0.8, s.microScalpRewardRisk);
		s.microScalpMaxRiskTicks = Math.min(s.microScalpMaxRiskTicks, 18.0);
	}

	private static void enableKeltner(FuturesManager.FuturesStrategySettings s, int maxTrades) {
		if (s == null) {
			return;
		}
		s.keltnerScalp.enabled = true;
		s.keltnerScalp.maxTradesPerDay = Math.max(s.keltnerScalp.maxTradesPerDay, maxTrades);
		s.allowKeltnerScalpLongs = true;
		s.allowKeltnerScalpShorts = true;
		s.keltnerMinVolumeRatio = Math.min(s.keltnerMinVolumeRatio, 0.75);
		s.keltnerRewardRisk = Math.max(0.85, s.keltnerRewardRisk);
	}

	private static void copyMgcPlaybookToGc(FuturesManager.FuturesStrategySettings from, FuturesManager.FuturesStrategySettings to) {
		if (from == null || to == null) {
			return;
		}
		enableCoreStack(to, 3);
		to.openingMomentum.enabled = false;
		to.vwapPullback.enabled = from.vwapPullback.enabled;
		to.vwapPullback.maxTradesPerDay = Math.max(1, from.vwapPullback.maxTradesPerDay);
		to.priorDayBreakout.enabled = from.priorDayBreakout.enabled;
		to.priorDayBreakout.maxTradesPerDay = Math.max(3, from.priorDayBreakout.maxTradesPerDay);
		to.sweep.enabled = from.sweep.enabled;
		to.sweep.maxTradesPerDay = Math.max(3, from.sweep.maxTradesPerDay);
		to.closeMomentum.enabled = from.closeMomentum.enabled;
		to.closeMomentum.maxTradesPerDay = Math.max(1, from.closeMomentum.maxTradesPerDay);
		to.keltnerReversion.enabled = false;
		to.keltnerScalp.enabled = false;
		to.enableAdaptiveExits = true;
		to.enableEarlyLossCut = true;
		to.earlyLossCutBars = 10;
		to.earlyLossCutR = 0.65;
		to.openMaeRiskMultiplier = 2.2;
		to.maxInitialRiskTicks = Math.min(120.0, Math.max(80.0, from.maxInitialRiskTicks));
	}

	private static boolean isMini(String symbol) {
		return "ES".equals(symbol) || "NQ".equals(symbol) || "GC".equals(symbol);
	}

	private static void setRisk(FuturesManager.FuturesRiskSettings risk, double maxRisk, int maxContracts) {
		if (risk == null) {
			return;
		}
		risk.maxRiskPerTrade = maxRisk;
		risk.maxContracts = maxContracts;
	}

	private static Map<String, FuturesManager.FuturesStrategySettings> loadBaseSettings() {
		Map<String, FuturesManager.FuturesStrategySettings> values = new HashMap<String, FuturesManager.FuturesStrategySettings>();
		for (String symbol : ALL_LIST) {
			values.put(symbol, FuturesManager.loadFuturesStrategySettings(symbol));
		}
		return values;
	}

	private static Map<String, FuturesManager.FuturesRiskSettings> loadBaseRisks() {
		Map<String, FuturesManager.FuturesRiskSettings> values = new HashMap<String, FuturesManager.FuturesRiskSettings>();
		for (String symbol : ALL_LIST) {
			values.put(symbol, FuturesManager.loadFuturesRiskSettings(symbol));
		}
		return values;
	}

	private static void save(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
		for (String symbol : ALL_LIST) {
			FuturesManager.saveFuturesStrategySettings(symbol, settings.get(symbol));
			FuturesManager.saveFuturesRiskSettings(symbol, risks.get(symbol));
		}
	}

	private static void restore(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
		save(settings, risks);
	}

	private static RunSummary loadRun(int id, String name, String symbols) throws Exception {
		RunSummary summary = new RunSummary();
		summary.id = id;
		summary.name = name;
		summary.symbols = symbols;
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
			+ " symbols=" + summary.symbols
			+ " pnl=" + round(summary.pnl)
			+ " pnlDelta=" + round(summary.pnl - BASELINE_PNL)
			+ " return=" + round(summary.returnPct)
			+ " trades=" + summary.trades
			+ " tradeDelta=" + (summary.trades - BASELINE_TRADES)
			+ " win=" + round(summary.winRate)
			+ " pf=" + round(summary.profitFactor)
			+ " dd=" + round(summary.maxDrawdownPct)
			+ " intraday=" + round(summary.maxIntradayLoss)
			+ " mae=" + round(summary.maxAggregateMae)
			+ " violation=" + summary.ruleViolation
			+ " message=\"" + summary.message + "\"";
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
