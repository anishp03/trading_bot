package com.tradingbot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProfitPreservingCountRunner {
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
		int overlapRejections;
		int riskRejections;
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
		values.add(new SimpleScenario("ppc_baseline_51k", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {}
		}));

		values.add(new SimpleScenario("ppc_nq_fvg_1030_12", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqFvg1030AndNoon(settings.get("NQ"), 1.0);
			}
		}));
		values.add(new SimpleScenario("ppc_nq_fvg_1030_12_rr115", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqFvg1030AndNoon(settings.get("NQ"), 1.15);
			}
		}));
		values.add(new SimpleScenario("ppc_nq_fvg_1030_12_rr130", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqFvg1030AndNoon(settings.get("NQ"), 1.30);
			}
		}));
		values.add(new SimpleScenario("ppc_nq_fvg_1230_only", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				nq.fvg.maxTradesPerDay = 4;
				nq.fvgStartMinute = 750;
				nq.fvgEndMinute = 779;
				nq.fvgSkipStartMinute = 0;
				nq.fvgSkipEndMinute = 0;
			}
		}));

		values.add(new SimpleScenario("ppc_nq_ipb_1130_1259", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpb(settings.get("NQ"), 690, 779, 1.1, 1.5);
			}
		}));
		values.add(new SimpleScenario("ppc_nq_ipb_1200_1259", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpb(settings.get("NQ"), 720, 779, 1.1, 1.5);
			}
		}));
		values.add(new SimpleScenario("ppc_nq_ipb_1230_only", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpb(settings.get("NQ"), 750, 779, 1.1, 1.5);
			}
		}));
		values.add(new SimpleScenario("ppc_nq_ipb_1130_1259_rr130", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpb(settings.get("NQ"), 690, 779, 1.3, 1.5);
			}
		}));
		values.add(new SimpleScenario("ppc_nq_ipb_1130_1259_vol175", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpb(settings.get("NQ"), 690, 779, 1.1, 1.75);
			}
		}));

		values.add(new SimpleScenario("ppc_omom_short_end646", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumShortEndMinute = 646;
			}
		}));
		values.add(new SimpleScenario("ppc_omom_long_end659", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumLongEndMinute = 659;
			}
		}));
		values.add(new SimpleScenario("ppc_omom_end_filters", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumShortEndMinute = 646;
				settings.get("MNQ").openingMomentumLongEndMinute = 659;
			}
		}));
		values.add(new SimpleScenario("ppc_mnq_earlycut12r060", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.earlyLossCutBars = 12;
				mnq.earlyLossCutR = 0.60;
			}
		}));
		values.add(new SimpleScenario("ppc_mnq_earlycut9r055", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.earlyLossCutBars = 9;
				mnq.earlyLossCutR = 0.55;
			}
		}));
		values.add(new SimpleScenario("ppc_mnq_omom_risk200", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumMaxRiskTicks = 200.0;
			}
		}));
		values.add(new SimpleScenario("ppc_mnq_omom_risk190", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumMaxRiskTicks = 190.0;
			}
		}));
		values.add(new SimpleScenario("ppc_mnq_omom_risk180", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumMaxRiskTicks = 180.0;
			}
		}));
		values.add(new SimpleScenario("ppc_nq_ipb_sep130_mim110", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
			}
		}));
		values.add(new SimpleScenario("ppc_nq_ipb_sep145_mim110", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.45, 1.10, 1.5);
			}
		}));
		values.add(new SimpleScenario("ppc_nq_ipb_sep130_mim100", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.00, 1.5);
			}
		}));
		values.add(new SimpleScenario("ppc_nq_ipb_sep130_skip720_749", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				enableNqMiddayIpbSeparate(nq, 690, 779, 1.30, 1.10, 1.5);
				setNqIpbSkip(nq, 720, 749);
			}
		}));
		values.add(new SimpleScenario("ppc_nq_ipb_sep145_skip720_749", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				enableNqMiddayIpbSeparate(nq, 690, 779, 1.45, 1.10, 1.5);
				setNqIpbSkip(nq, 720, 749);
			}
		}));

		values.add(new SimpleScenario("ppc_combo_ipb_fvg", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpb(settings.get("NQ"), 690, 779, 1.1, 1.5);
				enableNqFvg1030AndNoon(settings.get("NQ"), 1.0);
			}
		}));
		values.add(new SimpleScenario("ppc_combo_ipb_fvg_rr", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpb(settings.get("NQ"), 690, 779, 1.3, 1.5);
				enableNqFvg1030AndNoon(settings.get("NQ"), 1.15);
			}
		}));
		values.add(new SimpleScenario("ppc_combo_ipb_fvg_mes_cmom", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpb(settings.get("NQ"), 690, 779, 1.1, 1.5);
				enableNqFvg1030AndNoon(settings.get("NQ"), 1.0);
				settings.get("MES").closeMomentum.maxTradesPerDay = 2;
			}
		}));
		values.add(new SimpleScenario("ppc_combo_ipb_fvg_mes_aft5", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpb(settings.get("NQ"), 690, 779, 1.1, 1.5);
				enableNqFvg1030AndNoon(settings.get("NQ"), 1.0);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new SimpleScenario("ppc_combo_filters_ipb_fvg", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumShortEndMinute = 646;
				settings.get("MNQ").openingMomentumLongEndMinute = 659;
				enableNqMiddayIpb(settings.get("NQ"), 690, 779, 1.1, 1.5);
				enableNqFvg1030AndNoon(settings.get("NQ"), 1.0);
			}
		}));
		values.add(new SimpleScenario("ppc_combo_filters_ipb_fvg_mes", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumShortEndMinute = 646;
				settings.get("MNQ").openingMomentumLongEndMinute = 659;
				enableNqMiddayIpb(settings.get("NQ"), 690, 779, 1.1, 1.5);
				enableNqFvg1030AndNoon(settings.get("NQ"), 1.0);
				settings.get("MES").closeMomentum.maxTradesPerDay = 2;
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new SimpleScenario("ppc_combo_earlycut_ipb_fvg", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.earlyLossCutBars = 12;
				mnq.earlyLossCutR = 0.60;
				enableNqMiddayIpb(settings.get("NQ"), 690, 779, 1.1, 1.5);
				enableNqFvg1030AndNoon(settings.get("NQ"), 1.0);
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_ipb_risk200", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumMaxRiskTicks = 200.0;
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_ipb_mes_aft5", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_ipb_fvg_mes_aft5", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
				enableNqFvg1030AndNoon(settings.get("NQ"), 1.0);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_ipb_omom055", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.55;
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_ipb_omom050", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.50;
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_ipb_omom050_mes_aft5", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.50;
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_ipb_omom050_fvg_mes_aft5", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.50;
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
				enableNqFvg1030AndNoon(settings.get("NQ"), 1.0);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_ipb_omom050_risk200", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentumRewardRisk = 0.50;
				mnq.openingMomentumMaxRiskTicks = 200.0;
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_ipb_omom050_risk200_mes_aft5", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentumRewardRisk = 0.50;
				mnq.openingMomentumMaxRiskTicks = 200.0;
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_ipb_omom050_risk200_fvg_mes_aft5", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentumRewardRisk = 0.50;
				mnq.openingMomentumMaxRiskTicks = 200.0;
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
				enableNqFvg1030AndNoon(settings.get("NQ"), 1.0);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new SimpleScenario("ppc_count_broadfvg_omom050_ipb_mes", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.50;
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
				enableNqFvgBroadQuality(settings.get("NQ"), 1.0);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new SimpleScenario("ppc_count_broadfvg075_omom050_ipb_mes", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.50;
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
				enableNqFvgBroadQuality(settings.get("NQ"), 0.75);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new SimpleScenario("ppc_count_broadfvg_ipb_mes", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
				enableNqFvgBroadQuality(settings.get("NQ"), 1.0);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new SimpleScenario("ppc_count_broadfvg075_ipb_mes", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
				enableNqFvgBroadQuality(settings.get("NQ"), 0.75);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new SimpleScenario("ppc_count_broadfvg_ipb_mes_risk200", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumMaxRiskTicks = 200.0;
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
				enableNqFvgBroadQuality(settings.get("NQ"), 1.0);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new SimpleScenario("ppc_es_vwap_short_tgt50", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("ES").vwapShortMaxTargetTicks = 50.0;
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_ipb_omom050_esvwap50", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.50;
				settings.get("ES").vwapShortMaxTargetTicks = 50.0;
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_ipb_omom050_risk200_esvwap50", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentumRewardRisk = 0.50;
				mnq.openingMomentumMaxRiskTicks = 200.0;
				settings.get("ES").vwapShortMaxTargetTicks = 50.0;
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_ipb_omom050_risk200_esvwap50_mes_aft5", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentumRewardRisk = 0.50;
				mnq.openingMomentumMaxRiskTicks = 200.0;
				settings.get("ES").vwapShortMaxTargetTicks = 50.0;
				enableNqMiddayIpbSeparate(settings.get("NQ"), 690, 779, 1.30, 1.10, 1.5);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_skip_risk200", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumMaxRiskTicks = 200.0;
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				enableNqMiddayIpbSeparate(nq, 690, 779, 1.30, 1.10, 1.5);
				setNqIpbSkip(nq, 720, 749);
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_skip_risk200_mes_aft5", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumMaxRiskTicks = 200.0;
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				enableNqMiddayIpbSeparate(nq, 690, 779, 1.30, 1.10, 1.5);
				setNqIpbSkip(nq, 720, 749);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new SimpleScenario("ppc_combo_sep_skip_risk200_fvg_mes_aft5", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumMaxRiskTicks = 200.0;
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				enableNqMiddayIpbSeparate(nq, 690, 779, 1.30, 1.10, 1.5);
				setNqIpbSkip(nq, 720, 749);
				enableNqFvg1030AndNoon(nq, 1.0);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		}));
		values.add(new Scenario() {
			public String name() { return "ppc_stress_combo_ipb_fvg"; }
			public double slippageTicks() { return 2.0; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableNqMiddayIpb(settings.get("NQ"), 690, 779, 1.1, 1.5);
				enableNqFvg1030AndNoon(settings.get("NQ"), 1.0);
			}
		});
		addMatrixScenarios(values);
		return values;
	}

	private static void addMatrixScenarios(List<Scenario> values) {
		final double[] omomTargets = new double[] {0.50, 0.55, 0.70};
		final double[] riskCaps = new double[] {0.0, 200.0};
		final double[] ipbTargets = new double[] {1.30, 1.45, 1.60};
		final String[] ipbModes = new String[] {"full", "skip"};
		final String[] fvgModes = new String[] {"none", "shelf", "broad"};
		final boolean[] mesAftModes = new boolean[] {false, true};
		for (final double omomTarget : omomTargets) {
			for (final double riskCap : riskCaps) {
				for (final double ipbTarget : ipbTargets) {
					for (final String ipbMode : ipbModes) {
						for (final String fvgMode : fvgModes) {
							for (final boolean mesAft : mesAftModes) {
								values.add(new SimpleScenario(
									"mx_o" + tag(omomTarget)
										+ "_r" + (riskCap <= 0.0 ? "base" : tag(riskCap))
										+ "_i" + tag(ipbTarget)
										+ "_" + ipbMode
										+ "_f" + fvgMode
										+ "_mes" + (mesAft ? "1" : "0"),
									new ScenarioApplier() {
										public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
											FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
											mnq.openingMomentumRewardRisk = omomTarget;
											if (riskCap > 0.0) {
												mnq.openingMomentumMaxRiskTicks = riskCap;
											}
											FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
											enableNqMiddayIpbSeparate(nq, 690, 779, ipbTarget, 1.10, 1.5);
											if ("skip".equals(ipbMode)) {
												setNqIpbSkip(nq, 720, 749);
											}
											if ("shelf".equals(fvgMode)) {
												enableNqFvg1030AndNoon(nq, 1.0);
											} else if ("broad".equals(fvgMode)) {
												enableNqFvgBroadQuality(nq, 1.0);
											}
											if (mesAft) {
												settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
											}
										}
									}
								));
							}
						}
					}
				}
			}
		}
	}

	private static void enableNqFvg1030AndNoon(FuturesManager.FuturesStrategySettings nq, double rewardRisk) {
		nq.fvg.enabled = true;
		nq.fvg.maxTradesPerDay = 5;
		nq.fvgStartMinute = 630;
		nq.fvgEndMinute = 779;
		nq.fvgSkipStartMinute = 660;
		nq.fvgSkipEndMinute = 719;
		nq.fvgRewardRisk = rewardRisk;
	}

	private static void enableNqFvgBroadQuality(FuturesManager.FuturesStrategySettings nq, double rewardRisk) {
		nq.fvg.enabled = true;
		nq.fvg.maxTradesPerDay = 5;
		nq.fvgStartMinute = 600;
		nq.fvgEndMinute = 839;
		nq.fvgSkipStartMinute = 660;
		nq.fvgSkipEndMinute = 719;
		nq.fvgRewardRisk = rewardRisk;
	}

	private static void enableNqMiddayIpb(FuturesManager.FuturesStrategySettings nq, int startMinute, int endMinute, double rewardRisk, double minVolumeRatio) {
		nq.marketIntradayMomentum.enabled = true;
		nq.marketIntradayMomentum.maxTradesPerDay = 2;
		nq.marketImpulsePullbackStartMinute = startMinute;
		nq.marketImpulsePullbackEndMinute = endMinute;
		nq.marketImpulsePullbackSkipStartMinute = 0;
		nq.marketImpulsePullbackSkipEndMinute = 0;
		nq.marketIntradayMomentumRewardRisk = rewardRisk;
		nq.marketIntradayMomentumMinVolumeRatio = minVolumeRatio;
	}

	private static void enableNqMiddayIpbSeparate(FuturesManager.FuturesStrategySettings nq, int startMinute, int endMinute, double impulseRewardRisk, double mimRewardRisk, double minVolumeRatio) {
		nq.marketIntradayMomentum.enabled = true;
		nq.marketIntradayMomentum.maxTradesPerDay = 2;
		nq.marketImpulsePullbackStartMinute = startMinute;
		nq.marketImpulsePullbackEndMinute = endMinute;
		nq.marketImpulsePullbackSkipStartMinute = 0;
		nq.marketImpulsePullbackSkipEndMinute = 0;
		nq.marketImpulsePullbackRewardRisk = impulseRewardRisk;
		nq.marketIntradayMomentumRewardRisk = mimRewardRisk;
		nq.marketIntradayMomentumMinVolumeRatio = minVolumeRatio;
	}

	private static void setNqIpbSkip(FuturesManager.FuturesStrategySettings nq, int startMinute, int endMinute) {
		nq.marketImpulsePullbackSkipStartMinute = startMinute;
		nq.marketImpulsePullbackSkipEndMinute = endMinute;
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
				 + "maxAggregateMae, overlapRejections, riskRejections, ruleViolation, ruleMessage "
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
					summary.overlapRejections = rs.getInt("overlapRejections");
					summary.riskRejections = rs.getInt("riskRejections");
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
				insert.setString(2, "ProfitPreservingCountRunner");
				insert.setString(3, name);
				insert.executeUpdate();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static String line(RunSummary summary) {
		return summary.id + " " + summary.name
			+ " pnl=" + round(summary.pnl)
			+ " return=" + round(summary.returnPct)
			+ " trades=" + summary.trades
			+ " win=" + round(summary.winRate)
			+ " pf=" + round(summary.profitFactor)
			+ " dd=" + round(summary.maxDrawdownPct)
			+ " intraday=" + round(summary.maxIntradayLoss)
			+ " mae=" + round(summary.maxAggregateMae)
			+ " overlapReject=" + summary.overlapRejections
			+ " riskReject=" + summary.riskRejections
			+ " violation=" + summary.ruleViolation
			+ " msg=\"" + (summary.message == null ? "" : summary.message) + "\"";
	}

	private static void printRankings(List<RunSummary> summaries) {
		Collections.sort(summaries, new Comparator<RunSummary>() {
			public int compare(RunSummary first, RunSummary second) {
				return Double.compare(second.pnl, first.pnl);
			}
		});
		System.out.println("TOP_BY_PROFIT");
		for (int index = 0; index < Math.min(12, summaries.size()); index++) {
			System.out.println(line(summaries.get(index)));
		}

		Collections.sort(summaries, new Comparator<RunSummary>() {
			public int compare(RunSummary first, RunSummary second) {
				int tradeCompare = Integer.compare(second.trades, first.trades);
				if (tradeCompare != 0) {
					return tradeCompare;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});
		System.out.println("TOP_BY_TRADES_WITH_PROFIT_GUARD");
		int printed = 0;
		for (int index = 0; index < summaries.size() && printed < 12; index++) {
			RunSummary summary = summaries.get(index);
			if (summary.pnl >= 48000.0 && summary.ruleViolation == 0) {
				System.out.println(line(summary));
				printed++;
			}
		}
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	private static String tag(double value) {
		return String.valueOf(Math.round(value * 100.0));
	}
}
