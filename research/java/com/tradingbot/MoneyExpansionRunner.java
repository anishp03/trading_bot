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

public class MoneyExpansionRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K";
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-04";
	private static final String FUNDED_PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String[] SYMBOL_LIST = new String[] {"MES", "MNQ", "NQ", "MGC", "ES", "M2K"};
	private static final double BASELINE_PNL = 38309.37;
	private static final int BASELINE_TRADES = 720;
	private static final double BASELINE_WIN = 56.67;

	private interface Scenario {
		String name();
		default double slippageTicks() { return 1.0; }
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
			List<Scenario> scenarios = scenarios();
			for (int index = 0; index < scenarios.size(); index++) {
				Scenario scenario = scenarios.get(index);
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
		values.add(new Scenario() {
			public String name() { return "baseline_money700"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {}
		});

		values.add(riskScenario("risk_mgc600", "MGC", 600.0, 40));
		values.add(riskScenario("risk_mgc700", "MGC", 700.0, 50));
		values.add(riskScenario("risk_mgc510", "MGC", 510.0, 30));
		values.add(riskScenario("risk_mgc525", "MGC", 525.0, 30));
		values.add(riskScenario("risk_mgc550", "MGC", 550.0, 35));
		values.add(riskScenario("risk_mnq696x50", "MNQ", 696.0, 50));
		values.add(riskScenario("risk_mnq700x50", "MNQ", 700.0, 50));
		values.add(riskScenario("risk_mes700x50", "MES", 700.0, 50));
		values.add(riskScenario("risk_nq375x1", "NQ", 375.0, 1));
		values.add(riskScenario("risk_nq400x1", "NQ", 400.0, 1));
		values.add(riskScenario("risk_nq500x2", "NQ", 500.0, 2));
		values.add(riskScenario("risk_nq700x2", "NQ", 700.0, 2));
		values.add(riskScenario("risk_es400x2", "ES", 400.0, 2));
		values.add(riskScenario("risk_es400x3", "ES", 400.0, 3));
		values.add(riskScenario("risk_es500x3", "ES", 500.0, 3));
		values.add(riskScenario("risk_es500x5", "ES", 500.0, 5));
		values.add(riskScenario("risk_es600x5", "ES", 600.0, 5));
		values.add(riskScenario("risk_es700x3", "ES", 700.0, 3));
		values.add(riskScenario("risk_es700x5", "ES", 700.0, 5));
		values.add(new Scenario() {
			public String name() { return "risk_mgc600_nq500_es500"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				setRisk(risks.get("MGC"), 600.0, 40);
				setRisk(risks.get("NQ"), 500.0, 2);
				setRisk(risks.get("ES"), 500.0, 3);
			}
		});
		values.add(new Scenario() {
			public String name() { return "risk_quality_caps"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				setRisk(risks.get("MNQ"), 700.0, 50);
				setRisk(risks.get("MGC"), 650.0, 50);
				setRisk(risks.get("NQ"), 550.0, 2);
				setRisk(risks.get("ES"), 500.0, 3);
				setRisk(risks.get("MES"), 350.0, 20);
				setRisk(risks.get("M2K"), 400.0, 50);
			}
		});
		values.add(new Scenario() {
			public String name() { return "risk_aggressive_caps"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				setRisk(risks.get("MNQ"), 700.0, 50);
				setRisk(risks.get("MGC"), 700.0, 50);
				setRisk(risks.get("NQ"), 700.0, 2);
				setRisk(risks.get("ES"), 700.0, 3);
				setRisk(risks.get("MES"), 500.0, 30);
				setRisk(risks.get("M2K"), 500.0, 50);
			}
		});

		values.add(new Scenario() {
			public String name() { return "win_omom_rr050"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.50;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_omom_rr055"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.55;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_omom_rr060"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.60;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_omom_rr065"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.65;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_omom_rr070"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_omom_rr075"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.75;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_omom_rr090"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.90;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_nq_fvg_rr060"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("NQ").fvgRewardRisk = 0.60;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_nq_fvg_rr065"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("NQ").fvgRewardRisk = 0.65;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_nq_fvg_rr070"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("NQ").fvgRewardRisk = 0.70;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_nq_fvg_rr075"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("NQ").fvgRewardRisk = 0.75;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_nq_fvg_rr085"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("NQ").fvgRewardRisk = 0.85;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_nq_fvg_rr115"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("NQ").fvgRewardRisk = 1.15;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_fast_omom070_fvg085"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
				settings.get("NQ").fvgRewardRisk = 0.85;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_fast_omom060_fvg075"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.60;
				settings.get("NQ").fvgRewardRisk = 0.75;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_fast_omom055_fvg065"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.55;
				settings.get("NQ").fvgRewardRisk = 0.65;
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_fast_omom050_fvg060"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.50;
				settings.get("NQ").fvgRewardRisk = 0.60;
			}
		});
		values.add(new Scenario() {
			public String name() { return "quality_nq_fvg_skip11"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("NQ").fvgSkipStartMinute = 660;
				settings.get("NQ").fvgSkipEndMinute = 719;
			}
		});
		values.add(new Scenario() {
			public String name() { return "quality_nq_fvg_10_12_only"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				nq.fvgEndMinute = 779;
				nq.fvgSkipStartMinute = 660;
				nq.fvgSkipEndMinute = 719;
			}
		});
		values.add(new Scenario() {
			public String name() { return "quality_nq_fvg_12_only"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				nq.fvgStartMinute = 720;
				nq.fvgEndMinute = 779;
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_omom070_es500x3"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
				setRisk(risks.get("ES"), 500.0, 3);
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_omom070_es700x3"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
				setRisk(risks.get("ES"), 700.0, 3);
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_omom060_es500x3"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.60;
				setRisk(risks.get("ES"), 500.0, 3);
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_omom070_mgc525"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
				setRisk(risks.get("MGC"), 525.0, 30);
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_omom070_mgc525_es500x3"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
				setRisk(risks.get("MGC"), 525.0, 30);
				setRisk(risks.get("ES"), 500.0, 3);
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_skip11_omom070_es500x3"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
				settings.get("NQ").fvgSkipStartMinute = 660;
				settings.get("NQ").fvgSkipEndMinute = 719;
				setRisk(risks.get("ES"), 500.0, 3);
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_skip11_omom060_es500x3"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.60;
				settings.get("NQ").fvgSkipStartMinute = 660;
				settings.get("NQ").fvgSkipEndMinute = 719;
				setRisk(risks.get("ES"), 500.0, 3);
			}
		});
		values.add(new Scenario() {
			public String name() { return "win_skip11_omom055_fvg065_es500x3"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.55;
				settings.get("NQ").fvgRewardRisk = 0.65;
				settings.get("NQ").fvgSkipStartMinute = 660;
				settings.get("NQ").fvgSkipEndMinute = 719;
				setRisk(risks.get("ES"), 500.0, 3);
			}
		});
		values.add(new Scenario() {
			public String name() { return "expand_cmom_max2"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : SYMBOL_LIST) {
					settings.get(symbol).closeMomentum.enabled = true;
					settings.get(symbol).closeMomentum.maxTradesPerDay = Math.max(settings.get(symbol).closeMomentum.maxTradesPerDay, 2);
				}
			}
		});
		values.add(new Scenario() {
			public String name() { return "expand_sweep_quality"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : new String[] {"MNQ", "NQ", "MGC", "ES"}) {
					FuturesManager.FuturesStrategySettings s = settings.get(symbol);
					s.sweep.enabled = true;
					s.sweep.maxTradesPerDay = Math.max(s.sweep.maxTradesPerDay, 6);
					s.earlySweepReclaimTicks = Math.min(s.earlySweepReclaimTicks, 4.0);
					s.lateSweepReclaimTicks = Math.min(s.lateSweepReclaimTicks, 6.0);
					s.sweepCloseLocation = Math.min(s.sweepCloseLocation, 0.55);
					s.lateSweepCloseLocation = Math.min(s.lateSweepCloseLocation, 0.42);
				}
			}
		});
		values.add(new Scenario() {
			public String name() { return "expand_vwap_quality"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : new String[] {"MNQ", "NQ", "MGC", "ES"}) {
					FuturesManager.FuturesStrategySettings s = settings.get(symbol);
					s.vwapPullback.enabled = true;
					s.vwapPullback.maxTradesPerDay = Math.max(s.vwapPullback.maxTradesPerDay, 6);
					s.vwapMinVolumeRatio = Math.min(s.vwapMinVolumeRatio, 0.95);
					s.vwapMaxDistanceTicks = Math.max(s.vwapMaxDistanceTicks, 44.0);
				}
			}
		});
		values.add(new Scenario() {
			public String name() { return "quality_combo_skip11_omom070_es500_cmom_sweep"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
				settings.get("NQ").fvgSkipStartMinute = 660;
				settings.get("NQ").fvgSkipEndMinute = 719;
				setRisk(risks.get("ES"), 500.0, 3);
				for (String symbol : SYMBOL_LIST) {
					settings.get(symbol).closeMomentum.enabled = true;
					settings.get(symbol).closeMomentum.maxTradesPerDay = Math.max(settings.get(symbol).closeMomentum.maxTradesPerDay, 2);
				}
				for (String symbol : new String[] {"MNQ", "NQ", "MGC", "ES"}) {
					FuturesManager.FuturesStrategySettings s = settings.get(symbol);
					s.sweep.enabled = true;
					s.sweep.maxTradesPerDay = Math.max(s.sweep.maxTradesPerDay, 6);
					s.earlySweepReclaimTicks = Math.min(s.earlySweepReclaimTicks, 4.0);
					s.lateSweepReclaimTicks = Math.min(s.lateSweepReclaimTicks, 6.0);
				}
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_mnq50_omom070"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
				setRisk(risks.get("MNQ"), 700.0, 50);
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_mnq50_omom055_fvg065"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.55;
				settings.get("NQ").fvgRewardRisk = 0.65;
				setRisk(risks.get("MNQ"), 700.0, 50);
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_mnq50_es500x3_omom070"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
				setRisk(risks.get("MNQ"), 700.0, 50);
				setRisk(risks.get("ES"), 500.0, 3);
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_mnq50_es700x3_omom070"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
				setRisk(risks.get("MNQ"), 700.0, 50);
				setRisk(risks.get("ES"), 700.0, 3);
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_mnq50_es700x5_omom070"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
				setRisk(risks.get("MNQ"), 700.0, 50);
				setRisk(risks.get("ES"), 700.0, 5);
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_mnq50_skip11_omom070_es500x3"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
				settings.get("NQ").fvgSkipStartMinute = 660;
				settings.get("NQ").fvgSkipEndMinute = 719;
				setRisk(risks.get("MNQ"), 700.0, 50);
				setRisk(risks.get("ES"), 500.0, 3);
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_mnq50_skip11_omom055_fvg065_es500x3"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.55;
				settings.get("NQ").fvgRewardRisk = 0.65;
				settings.get("NQ").fvgSkipStartMinute = 660;
				settings.get("NQ").fvgSkipEndMinute = 719;
				setRisk(risks.get("MNQ"), 700.0, 50);
				setRisk(risks.get("ES"), 500.0, 3);
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_mnq50_fvg12max4_es700x5_omom070"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				nq.fvg.maxTradesPerDay = 4;
				nq.fvgStartMinute = 720;
				nq.fvgEndMinute = 779;
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
				setRisk(risks.get("MNQ"), 700.0, 50);
				setRisk(risks.get("ES"), 700.0, 5);
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_mnq50_es500_mes700_omom070"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
				setRisk(risks.get("MNQ"), 700.0, 50);
				setRisk(risks.get("ES"), 500.0, 3);
				setRisk(risks.get("MES"), 700.0, 50);
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_money_base"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_fvg12max6"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				settings.get("NQ").fvg.maxTradesPerDay = 6;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_fvg12max8"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				settings.get("NQ").fvg.maxTradesPerDay = 8;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_fvg10_12max4"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				nq.fvg.maxTradesPerDay = 4;
				nq.fvgStartMinute = 600;
				nq.fvgEndMinute = 779;
				nq.fvgSkipStartMinute = 660;
				nq.fvgSkipEndMinute = 719;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_fvg10_12max6"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				nq.fvg.maxTradesPerDay = 6;
				nq.fvgStartMinute = 600;
				nq.fvgEndMinute = 779;
				nq.fvgSkipStartMinute = 660;
				nq.fvgSkipEndMinute = 719;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_fvg10_12max8"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				nq.fvg.maxTradesPerDay = 8;
				nq.fvgStartMinute = 600;
				nq.fvgEndMinute = 779;
				nq.fvgSkipStartMinute = 660;
				nq.fvgSkipEndMinute = 719;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_fvg10_13max6"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				nq.fvg.maxTradesPerDay = 6;
				nq.fvgStartMinute = 600;
				nq.fvgEndMinute = 839;
				nq.fvgSkipStartMinute = 660;
				nq.fvgSkipEndMinute = 719;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_add_mnq_cmom1"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				enableMnqCloseMomentum(settings.get("MNQ"), 1, 36.0, 1.1);
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_add_mnq_cmom2"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				enableMnqCloseMomentum(settings.get("MNQ"), 2, 32.0, 1.0);
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_add_mnq_cmom3_loose"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				enableMnqCloseMomentum(settings.get("MNQ"), 3, 28.0, 0.9);
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_mes_aft5"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_mes_cmom2"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				settings.get("MES").closeMomentum.maxTradesPerDay = 2;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_nq_mim3"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				settings.get("NQ").marketIntradayMomentum.enabled = true;
				settings.get("NQ").marketIntradayMomentum.maxTradesPerDay = 3;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_m2k_afternoon"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				FuturesManager.FuturesStrategySettings m2k = settings.get("M2K");
				m2k.afternoonContinuation.enabled = true;
				m2k.afternoonContinuation.maxTradesPerDay = 3;
				m2k.afternoonStartMinute = 780;
				m2k.afternoonEndMinute = 920;
				m2k.afternoonMinVolumeRatio = 0.8;
				m2k.afternoonRewardRisk = 0.9;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_no_es_cmom"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				settings.get("ES").closeMomentum.enabled = false;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_refill_combo"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				nq.fvg.maxTradesPerDay = 6;
				nq.fvgStartMinute = 600;
				nq.fvgEndMinute = 779;
				nq.fvgSkipStartMinute = 660;
				nq.fvgSkipEndMinute = 719;
				enableMnqCloseMomentum(settings.get("MNQ"), 2, 32.0, 1.0);
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
				settings.get("NQ").marketIntradayMomentum.enabled = true;
				settings.get("NQ").marketIntradayMomentum.maxTradesPerDay = 3;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_mgc700_mae3"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				setRisk(risks.get("MGC"), 700.0, 50);
				settings.get("MGC").openMaeRiskMultiplier = 3.0;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_mgc700_mae4"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				setRisk(risks.get("MGC"), 700.0, 50);
				settings.get("MGC").openMaeRiskMultiplier = 4.0;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_mgc650_no_orb2"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				setRisk(risks.get("MGC"), 650.0, 50);
				settings.get("MGC").enableOrbRetest = false;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_mgc700_no_orb2"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				setRisk(risks.get("MGC"), 700.0, 50);
				settings.get("MGC").enableOrbRetest = false;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_mgc700_no_orb2_refill"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				setRisk(risks.get("MGC"), 700.0, 50);
				settings.get("MGC").enableOrbRetest = false;
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				nq.fvg.maxTradesPerDay = 6;
				nq.fvgStartMinute = 600;
				nq.fvgEndMinute = 839;
				nq.fvgSkipStartMinute = 660;
				nq.fvgSkipEndMinute = 719;
				nq.marketIntradayMomentum.enabled = true;
				nq.marketIntradayMomentum.maxTradesPerDay = 3;
				settings.get("MES").afternoonContinuation.maxTradesPerDay = 5;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_no_es_cmom_mgc700_mae3"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				settings.get("ES").closeMomentum.enabled = false;
				setRisk(risks.get("MGC"), 700.0, 50);
				settings.get("MGC").openMaeRiskMultiplier = 3.0;
			}
		});
		values.add(new Scenario() {
			public String name() { return "clean_no_es_cmom_refill_mim"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyCleanMoneyBase(settings, risks);
				settings.get("ES").closeMomentum.enabled = false;
				settings.get("NQ").marketIntradayMomentum.enabled = true;
				settings.get("NQ").marketIntradayMomentum.maxTradesPerDay = 3;
			}
		});
		values.add(new Scenario() {
			public String name() { return "dev_base_no_es_cmom"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
			}
		});
		values.add(new Scenario() {
			public String name() { return "dev_nq_fvg_rr115"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("NQ").fvgRewardRisk = 1.15;
			}
		});
		values.add(new Scenario() {
			public String name() { return "dev_nq_fvg_rr130"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("NQ").fvgRewardRisk = 1.30;
			}
		});
		values.add(new Scenario() {
			public String name() { return "dev_nq_fvg_rr150"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("NQ").fvgRewardRisk = 1.50;
			}
		});
		values.add(new Scenario() {
			public String name() { return "dev_nq_fvg_hold30_rr130"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("NQ").fvgRewardRisk = 1.30;
				settings.get("NQ").fvgMaxHoldBars = 30;
			}
		});
		values.add(new Scenario() {
			public String name() { return "dev_mnq_omom075"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("MNQ").openingMomentumRewardRisk = 0.75;
			}
		});
		values.add(new Scenario() {
			public String name() { return "dev_mnq_omom078"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("MNQ").openingMomentumRewardRisk = 0.78;
			}
		});
		values.add(new Scenario() {
			public String name() { return "dev_mnq_omom085"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("MNQ").openingMomentumRewardRisk = 0.85;
			}
		});
		values.add(new Scenario() {
			public String name() { return "dev_mnq_fvg_expand"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.fvg.maxTradesPerDay = 3;
				mnq.fvgEndMinute = 900;
				mnq.fvgRewardRisk = 1.15;
			}
		});
		values.add(new Scenario() {
			public String name() { return "dev_mnq_sweep_expand"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.sweep.maxTradesPerDay = 8;
				mnq.earlySweepReclaimTicks = 4.0;
				mnq.lateSweepReclaimTicks = 8.0;
				mnq.lateSweepCloseLocation = 0.35;
			}
		});
		values.add(new Scenario() {
			public String name() { return "dev_mnq_vwap_late_reopen"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("MNQ").vwapEndMinute = 930;
			}
		});
		values.add(new Scenario() {
			public String name() { return "dev_es_skip_sweep_shorts"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("ES").sweepShortSkipStartMinute = 1;
				settings.get("ES").sweepShortSkipEndMinute = 930;
			}
		});
		values.add(new Scenario() {
			public String name() { return "dev_nq_disable_vwap_longs"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("NQ").allowVwapPullbackLongs = false;
			}
		});
		values.add(new Scenario() {
			public String name() { return "dev_adaptive_mnq_nq"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				for (String symbol : new String[] {"MNQ", "NQ"}) {
					FuturesManager.FuturesStrategySettings s = settings.get(symbol);
					s.enableAdaptiveExits = true;
					s.adaptiveMaxRewardRisk = 1.8;
				}
			}
		});
		values.add(new Scenario() {
			public String name() { return "dev_combo_targets_sides"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("NQ").fvgRewardRisk = 1.30;
				settings.get("NQ").fvgMaxHoldBars = 30;
				settings.get("MNQ").fvg.maxTradesPerDay = 3;
				settings.get("MNQ").fvgEndMinute = 900;
				settings.get("MNQ").fvgRewardRisk = 1.15;
				settings.get("ES").sweepShortSkipStartMinute = 1;
				settings.get("ES").sweepShortSkipEndMinute = 930;
				settings.get("NQ").allowVwapPullbackLongs = false;
			}
		});
		values.add(new Scenario() {
			public String name() { return "alloc_mnq_omom055"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("MNQ").openingMomentumPortfolioRiskMultiplier = 0.55;
			}
		});
		values.add(new Scenario() {
			public String name() { return "alloc_mnq_omom065"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("MNQ").openingMomentumPortfolioRiskMultiplier = 0.65;
			}
		});
		values.add(new Scenario() {
			public String name() { return "alloc_mnq_omom080"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("MNQ").openingMomentumPortfolioRiskMultiplier = 0.80;
			}
		});
		values.add(new Scenario() {
			public String name() { return "alloc_mnq_omom100"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("MNQ").openingMomentumPortfolioRiskMultiplier = 1.00;
			}
		});
		values.add(new Scenario() {
			public String name() { return "alloc_mnq_omom065_es_skip"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("MNQ").openingMomentumPortfolioRiskMultiplier = 0.65;
				settings.get("ES").sweepShortSkipStartMinute = 1;
				settings.get("ES").sweepShortSkipEndMinute = 930;
			}
		});
		values.add(new Scenario() {
			public String name() { return "alloc_mnq_omom080_es_skip"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("MNQ").openingMomentumPortfolioRiskMultiplier = 0.80;
				settings.get("ES").sweepShortSkipStartMinute = 1;
				settings.get("ES").sweepShortSkipEndMinute = 930;
			}
		});
		values.add(new Scenario() {
			public String name() { return "alloc_mnq_omom065_all_positive"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyDevMoneyBase(settings, risks);
				settings.get("MNQ").openingMomentumPortfolioRiskMultiplier = 0.65;
				settings.get("ES").sweepShortSkipStartMinute = 1;
				settings.get("ES").sweepShortSkipEndMinute = 930;
				settings.get("NQ").allowVwapPullbackLongs = false;
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.sweep.maxTradesPerDay = 8;
				mnq.earlySweepReclaimTicks = 4.0;
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_risk_caps_fast_omom070"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("MNQ").openingMomentumRewardRisk = 0.70;
				setRisk(risks.get("MGC"), 650.0, 50);
				setRisk(risks.get("NQ"), 550.0, 2);
				setRisk(risks.get("ES"), 500.0, 3);
			}
		});
		values.add(new Scenario() {
			public String name() { return "money_risk_caps_fvg115"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				settings.get("NQ").fvgRewardRisk = 1.15;
				setRisk(risks.get("MGC"), 650.0, 50);
				setRisk(risks.get("NQ"), 550.0, 2);
				setRisk(risks.get("ES"), 500.0, 3);
			}
		});
		values.add(new Scenario() {
			public String name() { return "stress_2x_current"; }
			public double slippageTicks() { return 2.0; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {}
		});
		return values;
	}

	private static Scenario riskScenario(final String name, final String symbol, final double risk, final int maxContracts) {
		return new Scenario() {
			public String name() { return name; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				setRisk(risks.get(symbol), risk, maxContracts);
			}
		};
	}

	private static void setRisk(FuturesManager.FuturesRiskSettings risk, double maxRisk, int maxContracts) {
		if (risk == null) {
			return;
		}
		risk.maxRiskPerTrade = maxRisk;
		risk.maxContracts = maxContracts;
	}

	private static void applyCleanMoneyBase(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
		FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
		nq.fvg.maxTradesPerDay = 4;
		nq.fvgStartMinute = 720;
		nq.fvgEndMinute = 779;
		nq.fvgSkipStartMinute = 0;
		nq.fvgSkipEndMinute = 0;
		settings.get("MNQ").openingMomentumRewardRisk = 0.70;
		setRisk(risks.get("MNQ"), 700.0, 50);
		setRisk(risks.get("ES"), 700.0, 5);
	}

	private static void applyDevMoneyBase(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
		applyCleanMoneyBase(settings, risks);
		settings.get("ES").closeMomentum.enabled = false;
	}

	private static void enableMnqCloseMomentum(FuturesManager.FuturesStrategySettings settings, int maxTrades, double minMoveTicks, double minVolumeRatio) {
		settings.closeMomentum.enabled = true;
		settings.closeMomentum.maxTradesPerDay = maxTrades;
		settings.closeMomentumLongStartMinute = 870;
		settings.closeMomentumShortStartMinute = 870;
		settings.closeMomentumMinMoveTicks = minMoveTicks;
		settings.closeMomentumVolumeRatio = minVolumeRatio;
		settings.closeMomentumRewardRisk = 0.85;
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
			@Override
			public int compare(RunSummary first, RunSummary second) {
				if (first.ruleViolation != second.ruleViolation) {
					return first.ruleViolation - second.ruleViolation;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});
		System.out.println("TOP_BY_PNL");
		printTop(summaries, 8);
		Collections.sort(summaries, new Comparator<RunSummary>() {
			@Override
			public int compare(RunSummary first, RunSummary second) {
				if (first.ruleViolation != second.ruleViolation) {
					return first.ruleViolation - second.ruleViolation;
				}
				return Double.compare(second.winRate, first.winRate);
			}
		});
		System.out.println("TOP_BY_WIN");
		printTop(summaries, 8);
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
			+ " pnlDelta=" + round(summary.pnl - BASELINE_PNL)
			+ " return=" + round(summary.returnPct)
			+ " trades=" + summary.trades
			+ " tradeDelta=" + (summary.trades - BASELINE_TRADES)
			+ " win=" + round(summary.winRate)
			+ " winDelta=" + round(summary.winRate - BASELINE_WIN)
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
