package com.tradingbot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MicroContractExpansionRunner {
	private static final String BASE_SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K";
	private static final String EXPANDED_SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final String START_DATE = "2024-05-01";
	private static final String PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String WIP = "wip";
	private static final String MICRO_BASELINE = "mcl_mym_baseline_20260526";
	private static final String HEALTH_BASELINE = "m2k_refinement_20260526";
	private static final String CONTRACT_HEALTH_BASELINE = "contract_health_20260526";
	private static final String CONTRACT_HEALTH_PHASE2 = "contract_health_phase2_20260526";
	private static final String CONTRACT_HEALTH_PHASE3 = "contract_health_phase3_20260526";
	private static final String CONTRACT_HEALTH_PHASE4 = "contract_health_phase4_20260526";
	private static final String CONTRACT_HEALTH_PHASE5 = "contract_health_phase5_20260526";
	private static final String CONTRACT_HEALTH_PHASE6 = "contract_health_phase6_20260526";
	private static final String CONTRACT_HEALTH_PHASE7 = "contract_health_phase7_20260526";
	private static final String CONTRACT_HEALTH_PHASE8 = "contract_health_phase8_20260526";
	private static final String CONTRACT_HEALTH_PHASE9 = "contract_health_phase9_20260526";
	private static final String CONTRACT_HEALTH_PHASE10 = "contract_health_phase10_20260526";
	private static final String WIP_SLOT = FuturesManager.strategyPresetSlot(WIP);
	private static final String MICRO_BASELINE_SLOT = FuturesManager.strategyPresetSlot(MICRO_BASELINE);
	private static final String HEALTH_BASELINE_SLOT = FuturesManager.strategyPresetSlot(HEALTH_BASELINE);
	private static final String CONTRACT_HEALTH_BASELINE_SLOT = FuturesManager.strategyPresetSlot(CONTRACT_HEALTH_BASELINE);
	private static final String CONTRACT_HEALTH_PHASE2_SLOT = FuturesManager.strategyPresetSlot(CONTRACT_HEALTH_PHASE2);
	private static final String CONTRACT_HEALTH_PHASE3_SLOT = FuturesManager.strategyPresetSlot(CONTRACT_HEALTH_PHASE3);
	private static final String CONTRACT_HEALTH_PHASE4_SLOT = FuturesManager.strategyPresetSlot(CONTRACT_HEALTH_PHASE4);
	private static final String CONTRACT_HEALTH_PHASE5_SLOT = FuturesManager.strategyPresetSlot(CONTRACT_HEALTH_PHASE5);
	private static final String CONTRACT_HEALTH_PHASE6_SLOT = FuturesManager.strategyPresetSlot(CONTRACT_HEALTH_PHASE6);
	private static final String CONTRACT_HEALTH_PHASE7_SLOT = FuturesManager.strategyPresetSlot(CONTRACT_HEALTH_PHASE7);
	private static final String CONTRACT_HEALTH_PHASE8_SLOT = FuturesManager.strategyPresetSlot(CONTRACT_HEALTH_PHASE8);
	private static final String CONTRACT_HEALTH_PHASE9_SLOT = FuturesManager.strategyPresetSlot(CONTRACT_HEALTH_PHASE9);
	private static final String CONTRACT_HEALTH_PHASE10_SLOT = FuturesManager.strategyPresetSlot(CONTRACT_HEALTH_PHASE10);
	private static final String CONTROL_SLOT = FuturesManager.strategyPresetSlot("94k");
	private static final int ORIGINAL_BASELINE_SOURCE_ID = 3;
	private static final double ORIGINAL_WIN_RATE_TARGET = 74.93;

	private interface Scenario {
		String name();
		void apply() throws Exception;
	}

	private interface ScenarioAction {
		void apply() throws Exception;
	}

	private interface SettingsConfigurer {
		void configure(FuturesManager.FuturesStrategySettings settings);
	}

	private static class Summary {
		int id;
		String name;
		String symbols;
		double pnl;
		int trades;
		double winRate;
		double profitFactor;
		double drawdownPct;
		double maxIntradayLoss;
		double maxAggregateMae;
		int dailyLossBreaches;
		int trailingDrawdownBreaches;
		int ruleViolation;
		double mymPnl;
		int mymTrades;
		double mclPnl;
		int mclTrades;
	}

	private static class CandidateResult {
		String symbol;
		Scenario scenario;
		Summary summary;

		CandidateResult(String symbol, Scenario scenario, Summary summary) {
			this.symbol = symbol;
			this.scenario = scenario;
			this.summary = summary;
		}
	}

	public static void main(String[] args) throws Exception {
		String command = args.length > 0 ? args[0].trim().toLowerCase() : "sweep";
		String endDate = args.length > 1 ? args[1].trim() : LocalDate.now().toString();
		if ("update-data".equals(command)) {
			String symbols = args.length > 2 ? args[2].trim() : EXPANDED_SYMBOLS;
			int maxContractsPerSymbol = args.length > 3 ? Integer.parseInt(args[3].trim()) : 12;
			System.out.println(FuturesConnectionManager.updateBacktestData(symbols, START_DATE, endDate, "ohlcv-1m", maxContractsPerSymbol));
			return;
		}
		if ("seed".equals(command)) {
			seedPresetRows();
			System.out.println("Seeded MCL/MYM preset rows.");
			return;
		}
		if ("individual-sweep".equals(command) || "individual".equals(command)) {
			runIndividualSweep(endDate);
			return;
		}
		if ("deep-sweep".equals(command) || "deep".equals(command)) {
			runDeepSweep(endDate);
			return;
		}
		if ("focused-sweep".equals(command) || "focused".equals(command)) {
			runFocusedSweep(endDate);
			return;
		}
		if ("power-sweep".equals(command) || "power".equals(command)) {
			runPowerSweep(endDate);
			return;
		}
		if ("quality-sweep".equals(command) || "quality".equals(command)) {
			runQualitySweep(endDate);
			return;
		}
		if ("live-rules-sweep".equals(command) || "live-rules".equals(command) || "rules".equals(command)) {
			runLiveRulesSweep(endDate);
			return;
		}
		if ("module-sweep".equals(command) || "modules".equals(command)) {
			runModuleSweep(endDate);
			return;
		}
			if ("guarded-sweep".equals(command) || "guarded".equals(command)) {
				runGuardedSweep(endDate);
				return;
			}
			if ("source-guarded-sweep".equals(command) || "source-guarded".equals(command) || "original-guarded".equals(command)) {
				runSourceGuardedSweep(endDate);
				return;
			}
			if ("profit-sweep".equals(command) || "profit".equals(command) || "contribution".equals(command)) {
				runProfitSweep(endDate);
				return;
			}
			if ("exit-sweep".equals(command) || "exits".equals(command) || "managed-exits".equals(command)) {
				runExitDisciplineSweep(endDate);
				return;
			}
			if ("exit-combo".equals(command) || "combo-exits".equals(command) || "managed-exit-combo".equals(command)) {
				runExitComboSweep(endDate);
				return;
			}
			if ("custom-module-sweep".equals(command) || "custom-modules".equals(command) || "contract-modules".equals(command)) {
				runCustomModuleResearchSweep(endDate);
				return;
			}
			if ("deficiency-sweep".equals(command) || "deficiency".equals(command) || "laggard-sweep".equals(command) || "repair".equals(command)) {
				runDeficiencySweep(endDate);
				return;
			}
			if ("m2k-refine".equals(command) || "m2k-refinement".equals(command) || "refine-m2k".equals(command)) {
				runM2kRefinementSweep(endDate);
				return;
			}
			if ("contract-health".equals(command) || "health-sweep".equals(command) || "health".equals(command)) {
				runContractHealthSweep(endDate);
				return;
			}
			if ("phase2-health".equals(command) || "weak-contract-health".equals(command) || "all-contract-health".equals(command) || "health2".equals(command)) {
				runPhase2ContractHealthSweep(endDate);
				return;
			}
			if ("phase3-health".equals(command) || "health3".equals(command) || "layered-health".equals(command)) {
				runPhase3ContractHealthSweep(endDate);
				return;
			}
			if ("phase4-health".equals(command) || "health4".equals(command) || "laggard-health".equals(command)) {
				runPhase4ContractHealthSweep(endDate);
				return;
			}
			if ("phase5-health".equals(command) || "health5".equals(command) || "exit-health".equals(command)) {
				runPhase5ContractHealthSweep(endDate);
				return;
			}
			if ("phase6-health".equals(command) || "health6".equals(command) || "shadow-source-health".equals(command)) {
				runPhase6ContractHealthSweep(endDate);
				return;
			}
			if ("phase7-health".equals(command) || "health7".equals(command) || "structure-health".equals(command)) {
				runPhase7ContractHealthSweep(endDate);
				return;
			}
			if ("phase8-health".equals(command) || "health8".equals(command) || "laggard-balance-health".equals(command)) {
				runPhase8ContractHealthSweep(endDate);
				return;
			}
			if ("phase9-health".equals(command) || "health9".equals(command) || "stacked-health".equals(command)) {
				runPhase9ContractHealthSweep(endDate);
				return;
			}
			if ("phase10-health".equals(command) || "health10".equals(command) || "breadth-trend-health".equals(command)) {
				runPhase10ContractHealthSweep(endDate);
				return;
			}
			runSweep(endDate);
		}

	private static void runSweep(String endDate) throws Exception {
		seedPresetRows();
		applyAcceptedWipTrim();
		Summary baseline = runPortfolio("baseline_6_wip", BASE_SYMBOLS, endDate);
		System.out.println(line(baseline));

		List<Summary> summaries = new ArrayList<Summary>();
		for (Scenario scenario : scenarios()) {
			seedPresetRows();
			applyAcceptedWipTrim();
			scenario.apply();
			Summary summary = runPortfolio(scenario.name(), EXPANDED_SYMBOLS, endDate);
			summaries.add(summary);
			System.out.println(line(summary));
			printSymbolBreakdown(summary.id);
		}

		Collections.sort(summaries, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int violationCompare = Integer.compare(first.ruleViolation, second.ruleViolation);
				if (violationCompare != 0) {
					return violationCompare;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});

		System.out.println("RANKINGS");
		for (int index = 0; index < summaries.size(); index++) {
			System.out.println((index + 1) + ". " + line(summaries.get(index)));
		}

		Summary best = summaries.isEmpty() ? null : summaries.get(0);
		boolean accepted = best != null
			&& best.ruleViolation == 0
			&& best.dailyLossBreaches == 0
			&& best.trailingDrawdownBreaches == 0
			&& best.pnl > baseline.pnl
			&& best.trades > baseline.trades
			&& best.mymPnl >= 0.0
			&& best.mclPnl >= 0.0
			&& (best.mymTrades + best.mclTrades) > 0;

		if (accepted) {
			seedPresetRows();
			applyAcceptedWipTrim();
			scenarioByName(best.name).apply();
			System.out.println("APPLIED_WIP_EXPANSION=" + best.name);
		} else {
			seedPresetRows();
			applyAcceptedWipTrim();
			applyDisabled("MYM");
			applyDisabled("MCL");
			System.out.println("APPLIED_WIP_EXPANSION=none");
		}
		System.out.println("ACCEPTED=" + accepted);
		if (best != null) {
			System.out.println("BEST=" + line(best));
		}
	}

	private static void runDeepSweep(String endDate) throws Exception {
		seedPresetRows();
		applyAcceptedWipTrim();
		Summary baseline = runPortfolio("baseline_6_wip", BASE_SYMBOLS, endDate);
		System.out.println("BASELINE " + line(baseline));

		List<CandidateResult> mymResults = runNamedCandidates("MYM", deepScenarios("MYM"), endDate, "DEEP_SOLO");
		List<CandidateResult> mclResults = runNamedCandidates("MCL", deepScenarios("MCL"), endDate, "DEEP_SOLO");
		List<CandidateResult> mymTop = topIndividual(mymResults, 8);
		List<CandidateResult> mclTop = topIndividual(mclResults, 8);

		System.out.println("DEEP_TOP MYM");
		printCandidateRankings(mymTop);
		System.out.println("DEEP_TOP MCL");
		printCandidateRankings(mclTop);

		List<Summary> validations = new ArrayList<Summary>();
		for (CandidateResult mym : mymTop) {
			for (CandidateResult mcl : mclTop) {
				seedPresetRows();
				applyAcceptedWipTrim();
				mym.scenario.apply();
				mcl.scenario.apply();
				String name = "deep_pair_" + mym.scenario.name() + "__" + mcl.scenario.name();
				Summary summary = runPortfolio(name, EXPANDED_SYMBOLS, endDate);
				validations.add(summary);
				System.out.println("DEEP_PAIR " + line(summary));
				printNewSymbolStrategyBreakdown(summary.id);
			}
		}

		seedPresetRows();
		applyAcceptedWipTrim();
		applyCurrentBestMicroStack();
		Summary currentBest = runPortfolio("deep_pair_current_best_micro_stack", EXPANDED_SYMBOLS, endDate);
		validations.add(currentBest);
		System.out.println("DEEP_PAIR " + line(currentBest));
		printNewSymbolStrategyBreakdown(currentBest.id);

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstScore = acceptedPortfolioScore(first, baseline) ? 0 : 1;
				int secondScore = acceptedPortfolioScore(second, baseline) ? 0 : 1;
				if (firstScore != secondScore) {
					return firstScore - secondScore;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});

		System.out.println("DEEP_PAIR_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". accepted=" + acceptedPortfolioScore(summary, baseline) + " " + line(summary));
		}

		Summary best = validations.isEmpty() ? null : validations.get(0);
		boolean accepted = best != null && acceptedPortfolioScore(best, baseline);
		if (accepted) {
			seedPresetRows();
			applyAcceptedWipTrim();
			applyDeepValidationByName(best.name, mymTop, mclTop);
			System.out.println("APPLIED_WIP_DEEP_EXPANSION=" + best.name);
		} else {
			seedPresetRows();
			applyAcceptedWipTrim();
			applyCurrentBestMicroStack();
			System.out.println("APPLIED_WIP_DEEP_EXPANSION=current_best_micro_stack");
		}
		System.out.println("ACCEPTED=" + accepted);
		if (best != null) {
			System.out.println("BEST_DEEP_PAIR=" + line(best));
		}
	}

	private static void runFocusedSweep(String endDate) throws Exception {
		seedPresetRows();
		applyAcceptedWipTrim();
		Summary baseline = runPortfolio("focused_baseline_6_wip", BASE_SYMBOLS, endDate);
		System.out.println("FOCUSED_BASELINE " + line(baseline));

		List<CandidateResult> mymResults = runNamedCandidates("MYM", focusedMymScenarios(), endDate, "FOCUSED_SOLO");
		List<CandidateResult> mclResults = runNamedCandidates("MCL", focusedMclScenarios(), endDate, "FOCUSED_SOLO");
		List<CandidateResult> mymTop = topIndividual(mymResults, 10);
		List<CandidateResult> mclTop = topIndividual(mclResults, 10);

		System.out.println("FOCUSED_TOP MYM");
		printCandidateRankings(mymTop);
		System.out.println("FOCUSED_TOP MCL");
		printCandidateRankings(mclTop);

		List<Summary> validations = new ArrayList<Summary>();
		for (CandidateResult mym : mymTop) {
			for (CandidateResult mcl : mclTop) {
				seedPresetRows();
				applyAcceptedWipTrim();
				mym.scenario.apply();
				mcl.scenario.apply();
				String name = "focused_pair_" + mym.scenario.name() + "__" + mcl.scenario.name();
				Summary summary = runPortfolio(name, EXPANDED_SYMBOLS, endDate);
				validations.add(summary);
				System.out.println("FOCUSED_PAIR " + line(summary));
				printNewSymbolStrategyBreakdown(summary.id);
			}
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstScore = acceptedPortfolioScore(first, baseline) ? 0 : 1;
				int secondScore = acceptedPortfolioScore(second, baseline) ? 0 : 1;
				if (firstScore != secondScore) {
					return firstScore - secondScore;
				}
				int pnlCompare = Double.compare(second.pnl, first.pnl);
				if (pnlCompare != 0) {
					return pnlCompare;
				}
				return Integer.compare(second.trades, first.trades);
			}
		});

		System.out.println("FOCUSED_PAIR_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". accepted=" + acceptedPortfolioScore(summary, baseline) + " " + line(summary));
		}

		Summary best = validations.isEmpty() ? null : validations.get(0);
		System.out.println("RESEARCH_ONLY=true");
		System.out.println("APPLIED_WIP_FOCUSED_EXPANSION=none");
		if (best != null) {
			System.out.println("BEST_FOCUSED_PAIR=" + line(best));
			printSymbolBreakdown(best.id);
			printNewSymbolStrategyBreakdown(best.id);
		}
		seedPresetRows();
		applyAcceptedWipTrim();
		applyDisabled("MYM");
		applyDisabled("MCL");
	}

	private static void runPowerSweep(String endDate) throws Exception {
		seedPresetRows();
		applyAcceptedWipTrim();
		Summary baseline = runPortfolio("power_baseline_6_wip", BASE_SYMBOLS, endDate);
		System.out.println("POWER_BASELINE " + line(baseline));

		List<CandidateResult> mymResults = runNamedCandidates("MYM", powerMymScenarios(), endDate, "POWER_SOLO");
		List<CandidateResult> mclResults = runNamedCandidates("MCL", powerMclScenarios(), endDate, "POWER_SOLO");
		List<CandidateResult> mymTop = topIndividual(mymResults, 8);
		List<CandidateResult> mclTop = topIndividual(mclResults, 8);

		System.out.println("POWER_TOP MYM");
		printCandidateRankings(mymTop);
		System.out.println("POWER_TOP MCL");
		printCandidateRankings(mclTop);

		List<Summary> validations = new ArrayList<Summary>();
		for (CandidateResult mym : mymTop) {
			for (CandidateResult mcl : mclTop) {
				seedPresetRows();
				applyAcceptedWipTrim();
				mym.scenario.apply();
				mcl.scenario.apply();
				String name = "power_pair_" + mym.scenario.name() + "__" + mcl.scenario.name();
				Summary summary = runPortfolio(name, EXPANDED_SYMBOLS, endDate);
				validations.add(summary);
				System.out.println("POWER_PAIR strict=" + acceptedPortfolioScore(summary, baseline) + " " + line(summary));
				printNewSymbolStrategyBreakdown(summary.id);
			}
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstViolation = first.ruleViolation + first.dailyLossBreaches + first.trailingDrawdownBreaches;
				int secondViolation = second.ruleViolation + second.dailyLossBreaches + second.trailingDrawdownBreaches;
				if (firstViolation != secondViolation) {
					return firstViolation - secondViolation;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});

		System.out.println("POWER_PAIR_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". strict=" + acceptedPortfolioScore(summary, baseline) + " " + line(summary));
		}

		Summary best = validations.isEmpty() ? null : validations.get(0);
		System.out.println("RESEARCH_ONLY=true");
		System.out.println("APPLIED_WIP_POWER_EXPANSION=none");
		if (best != null) {
			System.out.println("BEST_POWER_PAIR=" + line(best));
			printSymbolBreakdown(best.id);
			printNewSymbolStrategyBreakdown(best.id);
		}
		seedPresetRows();
		applyAcceptedWipTrim();
		applyDisabled("MYM");
		applyDisabled("MCL");
	}

	private static void runQualitySweep(String endDate) throws Exception {
		seedPresetRows();
		applyAcceptedWipTrim();
		Summary baseline = runPortfolio("quality_baseline_6_wip", BASE_SYMBOLS, endDate);
		System.out.println("QUALITY_BASELINE " + line(baseline));

		List<CandidateResult> mymResults = runNamedCandidates("MYM", qualityMymScenarios(), endDate, "QUALITY_SOLO");
		List<CandidateResult> mclResults = runNamedCandidates("MCL", qualityMclScenarios(), endDate, "QUALITY_SOLO");
		List<CandidateResult> mymTop = topIndividual(mymResults, 8);
		List<CandidateResult> mclTop = topIndividual(mclResults, 8);

		System.out.println("QUALITY_TOP MYM");
		printCandidateRankings(mymTop);
		System.out.println("QUALITY_TOP MCL");
		printCandidateRankings(mclTop);

		List<Summary> validations = new ArrayList<Summary>();
		for (CandidateResult mym : mymTop) {
			for (CandidateResult mcl : mclTop) {
				seedPresetRows();
				applyAcceptedWipTrim();
				mym.scenario.apply();
				mcl.scenario.apply();
				String name = "quality_pair_" + mym.scenario.name() + "__" + mcl.scenario.name();
				Summary summary = runPortfolio(name, EXPANDED_SYMBOLS, endDate);
				validations.add(summary);
				System.out.println("QUALITY_PAIR score=" + qualityScore(summary, baseline) + " strict=" + acceptedPortfolioScore(summary, baseline) + " " + line(summary));
				printNewSymbolStrategyBreakdown(summary.id);
			}
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				return Double.compare(qualityScore(second, baseline), qualityScore(first, baseline));
			}
		});

		System.out.println("QUALITY_PAIR_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". score=" + qualityScore(summary, baseline) + " strict=" + acceptedPortfolioScore(summary, baseline) + " " + line(summary));
		}

		Summary best = validations.isEmpty() ? null : validations.get(0);
		System.out.println("RESEARCH_ONLY=true");
		System.out.println("APPLIED_WIP_QUALITY_EXPANSION=none");
		if (best != null) {
			System.out.println("BEST_QUALITY_PAIR=" + line(best));
			printSymbolBreakdown(best.id);
			printNewSymbolStrategyBreakdown(best.id);
		}
		seedPresetRows();
		applyAcceptedWipTrim();
		applyDisabled("MYM");
		applyDisabled("MCL");
	}

	private static void runLiveRulesSweep(String endDate) throws Exception {
		seedPresetRows();
		applyAcceptedWipTrim();
		Summary baseline = runPortfolio("live_rules_baseline_6_wip", BASE_SYMBOLS, endDate);
		System.out.println("LIVE_RULES_BASELINE " + line(baseline));

		List<Summary> validations = new ArrayList<Summary>();
		for (Scenario scenario : liveRuleScenarios()) {
			seedPresetRows();
			applyAcceptedWipTrim();
			scenario.apply();
			Summary summary = runPortfolio("live_rules_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("LIVE_RULES_PAIR score=" + liveRulesScore(summary, baseline) + " strict=" + acceptedPortfolioScore(summary, baseline) + " " + line(summary));
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				return Double.compare(liveRulesScore(second, baseline), liveRulesScore(first, baseline));
			}
		});

		System.out.println("LIVE_RULES_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". score=" + liveRulesScore(summary, baseline) + " strict=" + acceptedPortfolioScore(summary, baseline) + " " + line(summary));
		}

		Summary best = validations.isEmpty() ? null : validations.get(0);
		System.out.println("RESEARCH_ONLY=true");
		System.out.println("APPLIED_WIP_LIVE_RULES_EXPANSION=none");
		if (best != null) {
			System.out.println("BEST_LIVE_RULES_PAIR=" + line(best));
			printSymbolBreakdown(best.id);
			printNewSymbolStrategyBreakdown(best.id);
		}
		seedPresetRows();
		applyAcceptedWipTrim();
		applyDisabled("MYM");
		applyDisabled("MCL");
	}

	private static void runModuleSweep(String endDate) throws Exception {
		seedPresetRows();
		applyAcceptedWipTrim();
		Summary baseline = runPortfolio("module_baseline_6_wip", BASE_SYMBOLS, endDate);
		System.out.println("MODULE_BASELINE " + line(baseline));

		List<Summary> validations = new ArrayList<Summary>();
		for (Scenario scenario : moduleExpansionScenarios()) {
			seedPresetRows();
			applyAcceptedWipTrim();
			scenario.apply();
			Summary summary = runPortfolio("module_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("MODULE_PAIR score=" + liveRulesScore(summary, baseline) + " strict=" + acceptedPortfolioScore(summary, baseline) + " " + line(summary));
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				return Double.compare(liveRulesScore(second, baseline), liveRulesScore(first, baseline));
			}
		});

		System.out.println("MODULE_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". score=" + liveRulesScore(summary, baseline) + " strict=" + acceptedPortfolioScore(summary, baseline) + " " + line(summary));
		}

		Summary best = validations.isEmpty() ? null : validations.get(0);
		System.out.println("RESEARCH_ONLY=true");
		System.out.println("APPLIED_WIP_MODULE_EXPANSION=none");
		if (best != null) {
			System.out.println("BEST_MODULE_PAIR=" + line(best));
			printSymbolBreakdown(best.id);
			printNewSymbolStrategyBreakdown(best.id);
		}
		seedPresetRows();
		applyAcceptedWipTrim();
		applyDisabled("MYM");
		applyDisabled("MCL");
	}

	private static void runGuardedSweep(String endDate) throws Exception {
		seedPresetRows();
		applyAcceptedWipTrim();
		Summary baseline = runPortfolio("guarded_baseline_6_wip", BASE_SYMBOLS, endDate);
		System.out.println("GUARDED_BASELINE " + line(baseline));

		List<Summary> validations = new ArrayList<Summary>();
		for (Scenario scenario : moduleExpansionScenarios()) {
			if (!isGuardedScenario(scenario.name())) {
				continue;
			}
			seedPresetRows();
			applyAcceptedWipTrim();
			scenario.apply();
			Summary summary = runPortfolio("guarded_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("GUARDED_PAIR score=" + liveRulesScore(summary, baseline) + " strict=" + acceptedPortfolioScore(summary, baseline) + " " + line(summary));
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				return Double.compare(liveRulesScore(second, baseline), liveRulesScore(first, baseline));
			}
		});
		System.out.println("GUARDED_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". score=" + liveRulesScore(summary, baseline) + " strict=" + acceptedPortfolioScore(summary, baseline) + " " + line(summary));
		}
		Summary best = validations.isEmpty() ? null : validations.get(0);
		System.out.println("RESEARCH_ONLY=true");
		System.out.println("APPLIED_WIP_GUARDED_EXPANSION=none");
		if (best != null) {
			System.out.println("BEST_GUARDED_PAIR=" + line(best));
			printSymbolBreakdown(best.id);
			printNewSymbolStrategyBreakdown(best.id);
		}
		seedPresetRows();
		applyAcceptedWipTrim();
		applyDisabled("MYM");
		applyDisabled("MCL");
	}

	private static void runSourceGuardedSweep(String endDate) throws Exception {
		seedPresetRows();
		Summary baseline = runPortfolio("source3_baseline_6", BASE_SYMBOLS, endDate, ORIGINAL_BASELINE_SOURCE_ID);
		System.out.println("SOURCE_GUARDED_BASELINE targetWin=" + ORIGINAL_WIN_RATE_TARGET + " " + line(baseline));

		List<Summary> validations = new ArrayList<Summary>();
		for (Scenario scenario : moduleExpansionScenarios()) {
			if (!isGuardedScenario(scenario.name())) {
				continue;
			}
			seedPresetRows();
			scenario.apply();
			Summary summary = runPortfolio("source3_guarded_" + scenario.name(), EXPANDED_SYMBOLS, endDate, ORIGINAL_BASELINE_SOURCE_ID);
			validations.add(summary);
			System.out.println("SOURCE_GUARDED_PAIR targetWinPass=" + (summary.winRate >= ORIGINAL_WIN_RATE_TARGET) + " score=" + sourceQualityScore(summary, baseline) + " strict=" + acceptedPortfolioScore(summary, baseline) + " " + line(summary));
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				return Double.compare(sourceQualityScore(second, baseline), sourceQualityScore(first, baseline));
			}
		});
		System.out.println("SOURCE_GUARDED_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". targetWinPass=" + (summary.winRate >= ORIGINAL_WIN_RATE_TARGET) + " score=" + sourceQualityScore(summary, baseline) + " strict=" + acceptedPortfolioScore(summary, baseline) + " " + line(summary));
		}
		Summary best = validations.isEmpty() ? null : validations.get(0);
		System.out.println("RESEARCH_ONLY=true");
		System.out.println("APPLIED_WIP_SOURCE_GUARDED_EXPANSION=none");
		if (best != null) {
			System.out.println("BEST_SOURCE_GUARDED_PAIR=" + line(best));
			printSymbolBreakdown(best.id);
			printNewSymbolStrategyBreakdown(best.id);
		}
		seedPresetRows();
		applyDisabled("MYM");
		applyDisabled("MCL");
	}

	private static void runProfitSweep(String endDate) throws Exception {
		seedPresetRows();
		applyAcceptedWipTrim();
		Summary baseline = runPortfolio("profit_baseline_6_wip", BASE_SYMBOLS, endDate);
		System.out.println("PROFIT_BASELINE " + line(baseline));

		List<Summary> validations = new ArrayList<Summary>();
		for (Scenario scenario : profitExpansionScenarios()) {
			seedPresetRows();
			applyAcceptedWipTrim();
			scenario.apply();
			Summary summary = runPortfolio("profit_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("PROFIT_PAIR score=" + profitContributionScore(summary, baseline) + " " + line(summary));
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				return Double.compare(profitContributionScore(second, baseline), profitContributionScore(first, baseline));
			}
		});
		System.out.println("PROFIT_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". score=" + profitContributionScore(summary, baseline) + " " + line(summary));
		}
		Summary best = validations.isEmpty() ? null : validations.get(0);
		System.out.println("RESEARCH_ONLY=true");
		System.out.println("APPLIED_WIP_PROFIT_EXPANSION=none");
		if (best != null) {
			System.out.println("BEST_PROFIT_PAIR=" + line(best));
			printSymbolBreakdown(best.id);
			printNewSymbolStrategyBreakdown(best.id);
		}
		seedPresetRows();
		applyAcceptedWipTrim();
		applyDisabled("MYM");
		applyDisabled("MCL");
	}

	private static void runExitDisciplineSweep(String endDate) throws Exception {
		seedPresetRows();
		applyAcceptedWipTrim();
		Summary baseline = runPortfolio("exit_baseline_6_wip", BASE_SYMBOLS, endDate);
		System.out.println("EXIT_BASELINE " + line(baseline));

		seedPresetRows();
		applyAcceptedWipTrim();
		applyProfitHighAft20Mim();
		Summary currentBest = runPortfolio("exit_current_best_micro_stack", EXPANDED_SYMBOLS, endDate);
		System.out.println("EXIT_CURRENT_BEST " + line(currentBest));
		printNewSymbolStrategyBreakdown(currentBest.id);

		List<Scenario> scenarios = exitDisciplineScenarios();
		List<Summary> validations = new ArrayList<Summary>();
		for (Scenario scenario : scenarios) {
			seedPresetRows();
			applyAcceptedWipTrim();
			scenario.apply();
			Summary summary = runPortfolio("exit_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("EXIT_PAIR score=" + profitContributionScore(summary, baseline) + " beatsCurrent=" + exitDisciplineBeatsCurrent(summary, currentBest, baseline) + " " + line(summary));
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstScore = exitDisciplineBeatsCurrent(first, currentBest, baseline) ? 0 : 1;
				int secondScore = exitDisciplineBeatsCurrent(second, currentBest, baseline) ? 0 : 1;
				if (firstScore != secondScore) {
					return firstScore - secondScore;
				}
				return Double.compare(profitContributionScore(second, baseline), profitContributionScore(first, baseline));
			}
		});

		System.out.println("EXIT_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". beatsCurrent=" + exitDisciplineBeatsCurrent(summary, currentBest, baseline) + " score=" + profitContributionScore(summary, baseline) + " " + line(summary));
		}

		Summary best = validations.isEmpty() ? currentBest : validations.get(0);
		boolean accepted = exitDisciplineBeatsCurrent(best, currentBest, baseline);
		seedPresetRows();
		applyAcceptedWipTrim();
		if (accepted) {
			applyExitDisciplineScenarioByName(best.name.replaceFirst("^exit_", ""), scenarios);
			System.out.println("APPLIED_WIP_EXIT_EXPANSION=" + best.name);
		} else {
			applyProfitHighAft20Mim();
			best = currentBest;
			System.out.println("APPLIED_WIP_EXIT_EXPANSION=current_best_micro_stack");
		}
		System.out.println("ACCEPTED=" + accepted);
		System.out.println("BEST_EXIT_PAIR=" + line(best));
		printSymbolBreakdown(best.id);
		printNewSymbolStrategyBreakdown(best.id);
	}

	private static boolean exitDisciplineBeatsCurrent(Summary summary, Summary currentBest, Summary baseline) {
		return summary != null
			&& currentBest != null
			&& summary.ruleViolation == 0
			&& summary.dailyLossBreaches == 0
			&& summary.trailingDrawdownBreaches == 0
			&& summary.pnl > currentBest.pnl + 1.0
			&& summary.trades >= currentBest.trades
			&& summary.winRate >= currentBest.winRate - 0.20
			&& summary.drawdownPct <= currentBest.drawdownPct + 0.01
			&& summary.maxIntradayLoss >= currentBest.maxIntradayLoss - 0.01
			&& summary.maxAggregateMae >= currentBest.maxAggregateMae - 0.01
			&& summary.mymPnl >= currentBest.mymPnl - 1.0
			&& summary.mclPnl >= currentBest.mclPnl - 1.0;
	}

	private static void runExitComboSweep(String endDate) throws Exception {
		seedPresetRows();
		applyAcceptedWipTrim();
		Summary baseline = runPortfolio("exit_combo_baseline_6_wip", BASE_SYMBOLS, endDate);
		System.out.println("EXIT_COMBO_BASELINE " + line(baseline));

		seedPresetRows();
		applyAcceptedWipTrim();
		applyProfitHighAft20Mim();
		Summary currentBest = runPortfolio("exit_combo_current_best_micro_stack", EXPANDED_SYMBOLS, endDate);
		System.out.println("EXIT_COMBO_CURRENT_BEST " + line(currentBest));

		List<Scenario> scenarios = exitComboScenarios();
		List<Summary> validations = new ArrayList<Summary>();
		for (Scenario scenario : scenarios) {
			seedPresetRows();
			applyAcceptedWipTrim();
			scenario.apply();
			Summary summary = runPortfolio("exit_combo_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("EXIT_COMBO_PAIR beatsCurrent=" + exitDisciplineBeatsCurrent(summary, currentBest, baseline) + " " + line(summary));
			printNewSymbolStrategyBreakdown(summary.id);
		}
		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstScore = exitDisciplineBeatsCurrent(first, currentBest, baseline) ? 0 : 1;
				int secondScore = exitDisciplineBeatsCurrent(second, currentBest, baseline) ? 0 : 1;
				if (firstScore != secondScore) {
					return firstScore - secondScore;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});
		System.out.println("EXIT_COMBO_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". beatsCurrent=" + exitDisciplineBeatsCurrent(summary, currentBest, baseline) + " " + line(summary));
		}
		Summary best = validations.isEmpty() ? currentBest : validations.get(0);
		boolean accepted = exitDisciplineBeatsCurrent(best, currentBest, baseline);
		seedPresetRows();
		applyAcceptedWipTrim();
		if (accepted) {
			applyExitDisciplineScenarioByName(best.name.replaceFirst("^exit_combo_", ""), scenarios);
			System.out.println("APPLIED_WIP_EXIT_COMBO=" + best.name);
		} else {
			applyProfitHighAft20Mim();
			best = currentBest;
			System.out.println("APPLIED_WIP_EXIT_COMBO=current_best_micro_stack");
		}
		System.out.println("ACCEPTED=" + accepted);
		System.out.println("BEST_EXIT_COMBO=" + line(best));
		printSymbolBreakdown(best.id);
		printNewSymbolStrategyBreakdown(best.id);
	}

	private static void runCustomModuleResearchSweep(String endDate) throws Exception {
		copyStrategySlot(MICRO_BASELINE_SLOT, WIP_SLOT);
		Summary current = runPortfolio("custom_module_checkpoint", EXPANDED_SYMBOLS, endDate);
		System.out.println("CUSTOM_MODULE_CHECKPOINT " + line(current));

		List<Summary> validations = new ArrayList<Summary>();
		List<Scenario> scenarios = customModuleResearchScenarios();
		for (Scenario scenario : scenarios) {
			copyStrategySlot(MICRO_BASELINE_SLOT, WIP_SLOT);
			scenario.apply();
			Summary summary = runPortfolio("custom_module_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("CUSTOM_MODULE_CASE beatsCurrent=" + customModuleBeatsCurrent(summary, current) + " " + line(summary));
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstAccepted = customModuleBeatsCurrent(first, current) ? 0 : 1;
				int secondAccepted = customModuleBeatsCurrent(second, current) ? 0 : 1;
				if (firstAccepted != secondAccepted) {
					return Integer.compare(firstAccepted, secondAccepted);
				}
				return Double.compare(customModuleResearchScore(second, current), customModuleResearchScore(first, current));
			}
		});

		System.out.println("CUSTOM_MODULE_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". beatsCurrent=" + customModuleBeatsCurrent(summary, current) + " score=" + round(customModuleResearchScore(summary, current)) + " " + line(summary));
		}
		if (!validations.isEmpty()) {
			Summary best = validations.get(0);
			System.out.println("BEST_CUSTOM_MODULE=" + line(best));
			printSymbolBreakdown(best.id);
			printNewSymbolStrategyBreakdown(best.id);
		}
		copyStrategySlot(MICRO_BASELINE_SLOT, WIP_SLOT);
		System.out.println("RESTORED_WIP_FROM=" + MICRO_BASELINE);
		System.out.println("RESEARCH_ONLY=true");
	}

	private static boolean customModuleBeatsCurrent(Summary summary, Summary current) {
		return summary != null
			&& current != null
			&& summary.ruleViolation == 0
			&& summary.dailyLossBreaches == 0
			&& summary.trailingDrawdownBreaches == 0
			&& summary.pnl > current.pnl
			&& summary.trades >= current.trades
			&& summary.profitFactor >= current.profitFactor - 0.02
			&& summary.winRate >= current.winRate - 0.20
			&& summary.drawdownPct <= current.drawdownPct + 0.25
			&& summary.maxIntradayLoss >= current.maxIntradayLoss - 50.0
			&& summary.maxAggregateMae >= current.maxAggregateMae - 50.0;
	}

	private static double customModuleResearchScore(Summary summary, Summary current) {
		if (summary == null || current == null) {
			return Double.NEGATIVE_INFINITY;
		}
		double score = summary.pnl - current.pnl;
		score += (summary.trades - current.trades) * 5.0;
		score += (summary.winRate - current.winRate) * 150.0;
		score += (summary.profitFactor - current.profitFactor) * 1500.0;
		score -= Math.max(0.0, summary.drawdownPct - current.drawdownPct) * 2500.0;
		score -= Math.max(0.0, current.maxIntradayLoss - summary.maxIntradayLoss) * 2.0;
		score -= Math.max(0.0, current.maxAggregateMae - summary.maxAggregateMae) * 2.0;
		return score;
	}

	private static void runDeficiencySweep(String endDate) throws Exception {
		copyStrategySlot(MICRO_BASELINE_SLOT, WIP_SLOT);
		Summary current = runPortfolio("deficiency_checkpoint", EXPANDED_SYMBOLS, endDate);
		System.out.println("DEFICIENCY_CHECKPOINT " + line(current));
		printTargetSymbolBreakdown(current.id);
		printTargetStrategyBreakdown(current.id);

		List<Summary> validations = new ArrayList<Summary>();
		List<Scenario> scenarios = deficiencyResearchScenarios();
		for (Scenario scenario : scenarios) {
			copyStrategySlot(MICRO_BASELINE_SLOT, WIP_SLOT);
			scenario.apply();
			Summary summary = runPortfolio("deficiency_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("DEFICIENCY_CASE beatsCurrent=" + deficiencyBeatsCurrent(summary, current) + " score=" + round(deficiencyScore(summary, current)) + " " + line(summary));
			printTargetSymbolBreakdown(summary.id);
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstAccepted = deficiencyBeatsCurrent(first, current) ? 0 : 1;
				int secondAccepted = deficiencyBeatsCurrent(second, current) ? 0 : 1;
				if (firstAccepted != secondAccepted) {
					return Integer.compare(firstAccepted, secondAccepted);
				}
				return Double.compare(deficiencyScore(second, current), deficiencyScore(first, current));
			}
		});

		System.out.println("DEFICIENCY_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". beatsCurrent=" + deficiencyBeatsCurrent(summary, current) + " score=" + round(deficiencyScore(summary, current)) + " " + line(summary));
		}

		boolean accepted = !validations.isEmpty() && deficiencyBeatsCurrent(validations.get(0), current);
		copyStrategySlot(MICRO_BASELINE_SLOT, WIP_SLOT);
		if (accepted) {
			String scenarioName = validations.get(0).name.replaceFirst("^deficiency_", "");
			applyDeficiencyScenarioByName(scenarioName, scenarios);
			Summary applied = runPortfolio("deficiency_applied_" + scenarioName, EXPANDED_SYMBOLS, endDate);
			System.out.println("APPLIED_WIP_DEFICIENCY=" + scenarioName);
			System.out.println("BEST_DEFICIENCY=" + line(applied));
			printTargetSymbolBreakdown(applied.id);
			printTargetStrategyBreakdown(applied.id);
		} else {
			System.out.println("RESTORED_WIP_FROM=" + MICRO_BASELINE);
			System.out.println("NO_DEFICIENCY_CANDIDATE_BEAT_CHECKPOINT=true");
			if (!validations.isEmpty()) {
				Summary best = validations.get(0);
				System.out.println("BEST_REJECTED_DEFICIENCY=" + line(best));
				printTargetSymbolBreakdown(best.id);
				printTargetStrategyBreakdown(best.id);
			}
		}
	}

	private static boolean deficiencyBeatsCurrent(Summary summary, Summary current) {
		return summary != null
			&& current != null
			&& summary.ruleViolation == 0
			&& summary.dailyLossBreaches == 0
			&& summary.trailingDrawdownBreaches == 0
			&& summary.pnl > current.pnl
			&& summary.trades >= current.trades
			&& summary.winRate >= current.winRate - 0.20
			&& summary.profitFactor >= current.profitFactor - 0.04
			&& summary.drawdownPct <= current.drawdownPct + 0.30
			&& summary.maxIntradayLoss >= current.maxIntradayLoss - 75.0
			&& summary.maxAggregateMae >= current.maxAggregateMae - 75.0
			&& (summary.mymTrades + summary.mclTrades) >= (current.mymTrades + current.mclTrades);
	}

	private static double deficiencyScore(Summary summary, Summary current) {
		if (summary == null || current == null) {
			return Double.NEGATIVE_INFINITY;
		}
		double score = summary.pnl - current.pnl;
		score += (summary.trades - current.trades) * 10.0;
		score += ((summary.mymTrades + summary.mclTrades) - (current.mymTrades + current.mclTrades)) * 18.0;
		score += (summary.winRate - current.winRate) * 200.0;
		score += (summary.profitFactor - current.profitFactor) * 1800.0;
		score -= Math.max(0.0, summary.drawdownPct - current.drawdownPct) * 3000.0;
		score -= Math.max(0.0, current.maxIntradayLoss - summary.maxIntradayLoss) * 2.5;
		score -= Math.max(0.0, current.maxAggregateMae - summary.maxAggregateMae) * 2.5;
		if (summary.ruleViolation != 0 || summary.dailyLossBreaches != 0 || summary.trailingDrawdownBreaches != 0) {
			score -= 100000.0;
		}
		return score;
	}

	private static void applyDeficiencyScenarioByName(String name, List<Scenario> scenarios) throws Exception {
		for (Scenario scenario : scenarios) {
			if (scenario.name().equals(name)) {
				scenario.apply();
				return;
			}
		}
		throw new IllegalArgumentException("Unknown deficiency scenario " + name);
	}

	private static void runM2kRefinementSweep(String endDate) throws Exception {
		copyStrategySlot(MICRO_BASELINE_SLOT, WIP_SLOT);
		Summary current = runPortfolio("m2k_refine_checkpoint", EXPANDED_SYMBOLS, endDate);
		System.out.println("M2K_REFINE_CHECKPOINT " + line(current));
		printTargetSymbolBreakdown(current.id);

		List<Summary> validations = new ArrayList<Summary>();
		List<Scenario> scenarios = m2kRefinementScenarios();
		for (Scenario scenario : scenarios) {
			copyStrategySlot(MICRO_BASELINE_SLOT, WIP_SLOT);
			scenario.apply();
			Summary summary = runPortfolio("m2k_refine_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("M2K_REFINE_CASE beatsCurrent=" + m2kRefinementBeatsCurrent(summary, current) + " score=" + round(m2kRefinementScore(summary, current)) + " " + line(summary));
			printTargetSymbolBreakdown(summary.id);
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstAccepted = m2kRefinementBeatsCurrent(first, current) ? 0 : 1;
				int secondAccepted = m2kRefinementBeatsCurrent(second, current) ? 0 : 1;
				if (firstAccepted != secondAccepted) {
					return Integer.compare(firstAccepted, secondAccepted);
				}
				return Double.compare(m2kRefinementScore(second, current), m2kRefinementScore(first, current));
			}
		});

		System.out.println("M2K_REFINE_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". beatsCurrent=" + m2kRefinementBeatsCurrent(summary, current) + " score=" + round(m2kRefinementScore(summary, current)) + " " + line(summary));
		}

		boolean accepted = !validations.isEmpty() && m2kRefinementBeatsCurrent(validations.get(0), current);
		copyStrategySlot(MICRO_BASELINE_SLOT, WIP_SLOT);
		if (accepted) {
			String scenarioName = validations.get(0).name.replaceFirst("^m2k_refine_", "");
			applyDeficiencyScenarioByName(scenarioName, scenarios);
			Summary applied = runPortfolio("m2k_refine_applied_" + scenarioName, EXPANDED_SYMBOLS, endDate);
			System.out.println("APPLIED_WIP_M2K_REFINEMENT=" + scenarioName);
			System.out.println("BEST_M2K_REFINEMENT=" + line(applied));
			printTargetSymbolBreakdown(applied.id);
			printTargetStrategyBreakdown(applied.id);
		} else {
			System.out.println("RESTORED_WIP_FROM=" + MICRO_BASELINE);
			System.out.println("NO_M2K_REFINEMENT_CANDIDATE_BEAT_CHECKPOINT=true");
			if (!validations.isEmpty()) {
				Summary best = validations.get(0);
				System.out.println("BEST_REJECTED_M2K_REFINEMENT=" + line(best));
				printTargetSymbolBreakdown(best.id);
				printTargetStrategyBreakdown(best.id);
			}
		}
	}

	private static boolean m2kRefinementBeatsCurrent(Summary summary, Summary current) {
		return summary != null
			&& current != null
			&& summary.ruleViolation == 0
			&& summary.dailyLossBreaches == 0
			&& summary.trailingDrawdownBreaches == 0
			&& summary.pnl > current.pnl
			&& summary.trades >= current.trades
			&& summary.winRate >= current.winRate - 0.60
			&& summary.profitFactor >= current.profitFactor - 0.08
			&& summary.drawdownPct <= current.drawdownPct + 0.30
			&& summary.maxIntradayLoss >= current.maxIntradayLoss - 75.0
			&& summary.maxAggregateMae >= current.maxAggregateMae - 75.0;
	}

	private static double m2kRefinementScore(Summary summary, Summary current) {
		if (summary == null || current == null) {
			return Double.NEGATIVE_INFINITY;
		}
		double score = summary.pnl - current.pnl;
		score += (summary.trades - current.trades) * 12.0;
		score += (summary.winRate - current.winRate) * 150.0;
		score += (summary.profitFactor - current.profitFactor) * 1200.0;
		score -= Math.max(0.0, summary.drawdownPct - current.drawdownPct) * 2500.0;
		if (summary.ruleViolation != 0 || summary.dailyLossBreaches != 0 || summary.trailingDrawdownBreaches != 0) {
			score -= 100000.0;
		}
		return score;
	}

	private static void runContractHealthSweep(String endDate) throws Exception {
		copyStrategySlot(HEALTH_BASELINE_SLOT, WIP_SLOT);
		Summary current = runPortfolio("health_checkpoint", EXPANDED_SYMBOLS, endDate);
		System.out.println("HEALTH_CHECKPOINT " + line(current));
		printTargetSymbolBreakdown(current.id);
		printTargetStrategyBreakdown(current.id);

		List<Summary> validations = new ArrayList<Summary>();
		List<Scenario> scenarios = contractHealthScenarios();
		for (Scenario scenario : scenarios) {
			copyStrategySlot(HEALTH_BASELINE_SLOT, WIP_SLOT);
			scenario.apply();
			Summary summary = runPortfolio("health_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("HEALTH_CASE beatsCurrent=" + contractHealthBeatsCurrent(summary, current) + " score=" + round(contractHealthScore(summary, current)) + " " + line(summary));
			printTargetSymbolBreakdown(summary.id);
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstAccepted = contractHealthBeatsCurrent(first, current) ? 0 : 1;
				int secondAccepted = contractHealthBeatsCurrent(second, current) ? 0 : 1;
				if (firstAccepted != secondAccepted) {
					return Integer.compare(firstAccepted, secondAccepted);
				}
				return Double.compare(contractHealthScore(second, current), contractHealthScore(first, current));
			}
		});

		System.out.println("HEALTH_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". beatsCurrent=" + contractHealthBeatsCurrent(summary, current) + " score=" + round(contractHealthScore(summary, current)) + " " + line(summary));
		}

		boolean accepted = !validations.isEmpty() && contractHealthBeatsCurrent(validations.get(0), current);
		copyStrategySlot(HEALTH_BASELINE_SLOT, WIP_SLOT);
		if (accepted) {
			String scenarioName = validations.get(0).name.replaceFirst("^health_", "");
			applyDeficiencyScenarioByName(scenarioName, scenarios);
			Summary applied = runPortfolio("health_applied_" + scenarioName, EXPANDED_SYMBOLS, endDate);
			System.out.println("APPLIED_WIP_CONTRACT_HEALTH=" + scenarioName);
			System.out.println("BEST_CONTRACT_HEALTH=" + line(applied));
			printTargetSymbolBreakdown(applied.id);
			printTargetStrategyBreakdown(applied.id);
		} else {
			System.out.println("RESTORED_WIP_FROM=" + HEALTH_BASELINE);
			System.out.println("NO_CONTRACT_HEALTH_CANDIDATE_BEAT_CHECKPOINT=true");
			if (!validations.isEmpty()) {
				Summary best = validations.get(0);
				System.out.println("BEST_REJECTED_CONTRACT_HEALTH=" + line(best));
				printTargetSymbolBreakdown(best.id);
				printTargetStrategyBreakdown(best.id);
			}
		}
	}

	private static boolean contractHealthBeatsCurrent(Summary summary, Summary current) {
		return summary != null
			&& current != null
			&& summary.ruleViolation == 0
			&& summary.dailyLossBreaches == 0
			&& summary.trailingDrawdownBreaches == 0
			&& summary.pnl > current.pnl
			&& summary.trades >= 1053
			&& summary.winRate >= current.winRate
			&& summary.profitFactor >= current.profitFactor - 0.04
			&& summary.drawdownPct <= current.drawdownPct + 0.20
			&& summary.maxIntradayLoss >= current.maxIntradayLoss - 50.0
			&& summary.maxAggregateMae >= current.maxAggregateMae - 50.0;
	}

	private static double contractHealthScore(Summary summary, Summary current) {
		if (summary == null || current == null) {
			return Double.NEGATIVE_INFINITY;
		}
		double score = summary.pnl - current.pnl;
		score += (summary.winRate - current.winRate) * 500.0;
		score += (summary.profitFactor - current.profitFactor) * 1600.0;
		score += (summary.trades - current.trades) * 6.0;
		score -= Math.max(0.0, summary.drawdownPct - current.drawdownPct) * 2500.0;
		if (summary.ruleViolation != 0 || summary.dailyLossBreaches != 0 || summary.trailingDrawdownBreaches != 0) {
			score -= 100000.0;
		}
		return score;
	}

	private static void runPhase2ContractHealthSweep(String endDate) throws Exception {
		copyStrategySlot(CONTRACT_HEALTH_BASELINE_SLOT, WIP_SLOT);
		Summary current = runPortfolio("phase2_health_checkpoint", EXPANDED_SYMBOLS, endDate);
		System.out.println("PHASE2_HEALTH_CHECKPOINT " + line(current));
		printTargetSymbolBreakdown(current.id);
		printTargetStrategyBreakdown(current.id);

		List<Summary> validations = new ArrayList<Summary>();
		List<Scenario> scenarios = phase2ContractHealthScenarios();
		for (Scenario scenario : scenarios) {
			copyStrategySlot(CONTRACT_HEALTH_BASELINE_SLOT, WIP_SLOT);
			scenario.apply();
			Summary summary = runPortfolio("phase2_health_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("PHASE2_HEALTH_CASE beatsCurrent=" + phase2ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase2ContractHealthScore(summary, current)) + " " + line(summary));
			printTargetSymbolBreakdown(summary.id);
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstAccepted = phase2ContractHealthBeatsCurrent(first, current) ? 0 : 1;
				int secondAccepted = phase2ContractHealthBeatsCurrent(second, current) ? 0 : 1;
				if (firstAccepted != secondAccepted) {
					return Integer.compare(firstAccepted, secondAccepted);
				}
				return Double.compare(phase2ContractHealthScore(second, current), phase2ContractHealthScore(first, current));
			}
		});

		System.out.println("PHASE2_HEALTH_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". beatsCurrent=" + phase2ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase2ContractHealthScore(summary, current)) + " " + line(summary));
		}

		boolean accepted = !validations.isEmpty() && phase2ContractHealthBeatsCurrent(validations.get(0), current);
		copyStrategySlot(CONTRACT_HEALTH_BASELINE_SLOT, WIP_SLOT);
		if (accepted) {
			String scenarioName = validations.get(0).name.replaceFirst("^phase2_health_", "");
			applyDeficiencyScenarioByName(scenarioName, scenarios);
			Summary applied = runPortfolio("phase2_health_applied_" + scenarioName, EXPANDED_SYMBOLS, endDate);
			copyStrategySlot(WIP_SLOT, CONTRACT_HEALTH_PHASE2_SLOT);
			System.out.println("APPLIED_WIP_PHASE2_CONTRACT_HEALTH=" + scenarioName);
			System.out.println("SAVED_PRESET=" + CONTRACT_HEALTH_PHASE2 + " rows=" + countSlotRows(CONTRACT_HEALTH_PHASE2_SLOT) + " diffVsWip=" + diffSlotRows(WIP_SLOT, CONTRACT_HEALTH_PHASE2_SLOT));
			System.out.println("BEST_PHASE2_CONTRACT_HEALTH=" + line(applied));
			printTargetSymbolBreakdown(applied.id);
			printTargetStrategyBreakdown(applied.id);
		} else {
			System.out.println("RESTORED_WIP_FROM=" + CONTRACT_HEALTH_BASELINE);
			System.out.println("NO_PHASE2_CONTRACT_HEALTH_CANDIDATE_BEAT_CHECKPOINT=true");
			if (!validations.isEmpty()) {
				Summary best = validations.get(0);
				System.out.println("BEST_REJECTED_PHASE2_CONTRACT_HEALTH=" + line(best));
				printTargetSymbolBreakdown(best.id);
				printTargetStrategyBreakdown(best.id);
			}
		}
	}

	private static void runPhase3ContractHealthSweep(String endDate) throws Exception {
		copyStrategySlot(CONTRACT_HEALTH_PHASE2_SLOT, WIP_SLOT);
		Summary current = runPortfolio("phase3_health_checkpoint", EXPANDED_SYMBOLS, endDate);
		System.out.println("PHASE3_HEALTH_CHECKPOINT " + line(current));
		printTargetSymbolBreakdown(current.id);
		printTargetStrategyBreakdown(current.id);

		List<Summary> validations = new ArrayList<Summary>();
		List<Scenario> scenarios = phase3ContractHealthScenarios();
		for (Scenario scenario : scenarios) {
			copyStrategySlot(CONTRACT_HEALTH_PHASE2_SLOT, WIP_SLOT);
			scenario.apply();
			Summary summary = runPortfolio("phase3_health_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("PHASE3_HEALTH_CASE beatsCurrent=" + phase2ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase2ContractHealthScore(summary, current)) + " " + line(summary));
			printTargetSymbolBreakdown(summary.id);
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstAccepted = phase2ContractHealthBeatsCurrent(first, current) ? 0 : 1;
				int secondAccepted = phase2ContractHealthBeatsCurrent(second, current) ? 0 : 1;
				if (firstAccepted != secondAccepted) {
					return Integer.compare(firstAccepted, secondAccepted);
				}
				return Double.compare(phase2ContractHealthScore(second, current), phase2ContractHealthScore(first, current));
			}
		});

		System.out.println("PHASE3_HEALTH_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". beatsCurrent=" + phase2ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase2ContractHealthScore(summary, current)) + " " + line(summary));
		}

		boolean accepted = !validations.isEmpty() && phase2ContractHealthBeatsCurrent(validations.get(0), current);
		copyStrategySlot(CONTRACT_HEALTH_PHASE2_SLOT, WIP_SLOT);
		if (accepted) {
			String scenarioName = validations.get(0).name.replaceFirst("^phase3_health_", "");
			applyDeficiencyScenarioByName(scenarioName, scenarios);
			Summary applied = runPortfolio("phase3_health_applied_" + scenarioName, EXPANDED_SYMBOLS, endDate);
			copyStrategySlot(WIP_SLOT, CONTRACT_HEALTH_PHASE3_SLOT);
			System.out.println("APPLIED_WIP_PHASE3_CONTRACT_HEALTH=" + scenarioName);
			System.out.println("SAVED_PRESET=" + CONTRACT_HEALTH_PHASE3 + " rows=" + countSlotRows(CONTRACT_HEALTH_PHASE3_SLOT) + " diffVsWip=" + diffSlotRows(WIP_SLOT, CONTRACT_HEALTH_PHASE3_SLOT));
			System.out.println("BEST_PHASE3_CONTRACT_HEALTH=" + line(applied));
			printTargetSymbolBreakdown(applied.id);
			printTargetStrategyBreakdown(applied.id);
		} else {
			System.out.println("RESTORED_WIP_FROM=" + CONTRACT_HEALTH_PHASE2);
			System.out.println("NO_PHASE3_CONTRACT_HEALTH_CANDIDATE_BEAT_CHECKPOINT=true");
			if (!validations.isEmpty()) {
				Summary best = validations.get(0);
				System.out.println("BEST_REJECTED_PHASE3_CONTRACT_HEALTH=" + line(best));
				printTargetSymbolBreakdown(best.id);
				printTargetStrategyBreakdown(best.id);
			}
		}
	}

	private static void runPhase4ContractHealthSweep(String endDate) throws Exception {
		copyStrategySlot(CONTRACT_HEALTH_PHASE3_SLOT, WIP_SLOT);
		Summary current = runPortfolio("phase4_health_checkpoint", EXPANDED_SYMBOLS, endDate);
		System.out.println("PHASE4_HEALTH_CHECKPOINT " + line(current));
		printTargetSymbolBreakdown(current.id);
		printTargetStrategyBreakdown(current.id);

		List<Summary> validations = new ArrayList<Summary>();
		List<Scenario> scenarios = phase4ContractHealthScenarios();
		for (Scenario scenario : scenarios) {
			copyStrategySlot(CONTRACT_HEALTH_PHASE3_SLOT, WIP_SLOT);
			scenario.apply();
			Summary summary = runPortfolio("phase4_health_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("PHASE4_HEALTH_CASE beatsCurrent=" + phase4ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase4ContractHealthScore(summary, current)) + " " + line(summary));
			printTargetSymbolBreakdown(summary.id);
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstAccepted = phase4ContractHealthBeatsCurrent(first, current) ? 0 : 1;
				int secondAccepted = phase4ContractHealthBeatsCurrent(second, current) ? 0 : 1;
				if (firstAccepted != secondAccepted) {
					return Integer.compare(firstAccepted, secondAccepted);
				}
				return Double.compare(phase4ContractHealthScore(second, current), phase4ContractHealthScore(first, current));
			}
		});

		System.out.println("PHASE4_HEALTH_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". beatsCurrent=" + phase4ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase4ContractHealthScore(summary, current)) + " " + line(summary));
		}

		boolean accepted = !validations.isEmpty() && phase4ContractHealthBeatsCurrent(validations.get(0), current);
		copyStrategySlot(CONTRACT_HEALTH_PHASE3_SLOT, WIP_SLOT);
		if (accepted) {
			String scenarioName = validations.get(0).name.replaceFirst("^phase4_health_", "");
			applyDeficiencyScenarioByName(scenarioName, scenarios);
			Summary applied = runPortfolio("phase4_health_applied_" + scenarioName, EXPANDED_SYMBOLS, endDate);
			copyStrategySlot(WIP_SLOT, CONTRACT_HEALTH_PHASE4_SLOT);
			System.out.println("APPLIED_WIP_PHASE4_CONTRACT_HEALTH=" + scenarioName);
			System.out.println("SAVED_PRESET=" + CONTRACT_HEALTH_PHASE4 + " rows=" + countSlotRows(CONTRACT_HEALTH_PHASE4_SLOT) + " diffVsWip=" + diffSlotRows(WIP_SLOT, CONTRACT_HEALTH_PHASE4_SLOT));
			System.out.println("BEST_PHASE4_CONTRACT_HEALTH=" + line(applied));
			printTargetSymbolBreakdown(applied.id);
			printTargetStrategyBreakdown(applied.id);
		} else {
			System.out.println("RESTORED_WIP_FROM=" + CONTRACT_HEALTH_PHASE3);
			System.out.println("NO_PHASE4_CONTRACT_HEALTH_CANDIDATE_BEAT_CHECKPOINT=true");
			if (!validations.isEmpty()) {
				Summary best = validations.get(0);
				System.out.println("BEST_REJECTED_PHASE4_CONTRACT_HEALTH=" + line(best));
				printTargetSymbolBreakdown(best.id);
				printTargetStrategyBreakdown(best.id);
			}
		}
	}

	private static boolean phase4ContractHealthBeatsCurrent(Summary summary, Summary current) {
		if (summary == null || current == null) {
			return false;
		}
		double pnlGain = summary.pnl - current.pnl;
		boolean winAcceptable = summary.winRate >= current.winRate - (pnlGain >= 1800.0 ? 0.60 : 0.25);
		boolean tradeAcceptable = summary.trades >= current.trades || pnlGain >= 1600.0;
		return summary.ruleViolation == 0
			&& summary.dailyLossBreaches == 0
			&& summary.trailingDrawdownBreaches == 0
			&& pnlGain > 0.0
			&& tradeAcceptable
			&& winAcceptable
			&& summary.profitFactor >= current.profitFactor - 0.10
			&& summary.drawdownPct <= current.drawdownPct + 0.30
			&& summary.maxIntradayLoss >= current.maxIntradayLoss - 120.0
			&& summary.maxAggregateMae >= current.maxAggregateMae - 120.0
			&& summary.mymPnl >= current.mymPnl - 300.0
			&& summary.mclPnl >= current.mclPnl - 300.0;
	}

	private static double phase4ContractHealthScore(Summary summary, Summary current) {
		if (summary == null || current == null) {
			return Double.NEGATIVE_INFINITY;
		}
		double score = summary.pnl - current.pnl;
		score += (summary.trades - current.trades) * 10.0;
		score += (summary.winRate - current.winRate) * 750.0;
		score += (summary.profitFactor - current.profitFactor) * 2000.0;
		score += ((summary.mymPnl - current.mymPnl) + (summary.mclPnl - current.mclPnl)) * 0.45;
		score += ((summary.mymTrades - current.mymTrades) + (summary.mclTrades - current.mclTrades)) * 24.0;
		score -= Math.max(0.0, summary.drawdownPct - current.drawdownPct) * 3500.0;
		score -= Math.max(0.0, current.maxIntradayLoss - summary.maxIntradayLoss) * 2.4;
		score -= Math.max(0.0, current.maxAggregateMae - summary.maxAggregateMae) * 2.4;
		if (summary.ruleViolation != 0 || summary.dailyLossBreaches != 0 || summary.trailingDrawdownBreaches != 0) {
			score -= 100000.0;
		}
		return score;
	}

	private static void runPhase5ContractHealthSweep(String endDate) throws Exception {
		copyStrategySlot(CONTRACT_HEALTH_PHASE4_SLOT, WIP_SLOT);
		Summary current = runPortfolio("phase5_health_checkpoint", EXPANDED_SYMBOLS, endDate);
		System.out.println("PHASE5_HEALTH_CHECKPOINT " + line(current));
		printTargetSymbolBreakdown(current.id);
		printTargetStrategyBreakdown(current.id);

		List<Summary> validations = new ArrayList<Summary>();
		List<Scenario> scenarios = phase5ContractHealthScenarios();
		for (Scenario scenario : scenarios) {
			copyStrategySlot(CONTRACT_HEALTH_PHASE4_SLOT, WIP_SLOT);
			scenario.apply();
			Summary summary = runPortfolio("phase5_health_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("PHASE5_HEALTH_CASE beatsCurrent=" + phase4ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase4ContractHealthScore(summary, current)) + " " + line(summary));
			printTargetSymbolBreakdown(summary.id);
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstAccepted = phase4ContractHealthBeatsCurrent(first, current) ? 0 : 1;
				int secondAccepted = phase4ContractHealthBeatsCurrent(second, current) ? 0 : 1;
				if (firstAccepted != secondAccepted) {
					return Integer.compare(firstAccepted, secondAccepted);
				}
				return Double.compare(phase4ContractHealthScore(second, current), phase4ContractHealthScore(first, current));
			}
		});

		System.out.println("PHASE5_HEALTH_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". beatsCurrent=" + phase4ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase4ContractHealthScore(summary, current)) + " " + line(summary));
		}

		boolean accepted = !validations.isEmpty() && phase4ContractHealthBeatsCurrent(validations.get(0), current);
		copyStrategySlot(CONTRACT_HEALTH_PHASE4_SLOT, WIP_SLOT);
		if (accepted) {
			String scenarioName = validations.get(0).name.replaceFirst("^phase5_health_", "");
			applyDeficiencyScenarioByName(scenarioName, scenarios);
			Summary applied = runPortfolio("phase5_health_applied_" + scenarioName, EXPANDED_SYMBOLS, endDate);
			copyStrategySlot(WIP_SLOT, CONTRACT_HEALTH_PHASE5_SLOT);
			System.out.println("APPLIED_WIP_PHASE5_CONTRACT_HEALTH=" + scenarioName);
			System.out.println("SAVED_PRESET=" + CONTRACT_HEALTH_PHASE5 + " rows=" + countSlotRows(CONTRACT_HEALTH_PHASE5_SLOT) + " diffVsWip=" + diffSlotRows(WIP_SLOT, CONTRACT_HEALTH_PHASE5_SLOT));
			System.out.println("BEST_PHASE5_CONTRACT_HEALTH=" + line(applied));
			printTargetSymbolBreakdown(applied.id);
			printTargetStrategyBreakdown(applied.id);
		} else {
			System.out.println("RESTORED_WIP_FROM=" + CONTRACT_HEALTH_PHASE4);
			System.out.println("NO_PHASE5_CONTRACT_HEALTH_CANDIDATE_BEAT_CHECKPOINT=true");
			if (!validations.isEmpty()) {
				Summary best = validations.get(0);
				System.out.println("BEST_REJECTED_PHASE5_CONTRACT_HEALTH=" + line(best));
				printTargetSymbolBreakdown(best.id);
				printTargetStrategyBreakdown(best.id);
			}
		}
	}

	private static void runPhase6ContractHealthSweep(String endDate) throws Exception {
		copyStrategySlot(CONTRACT_HEALTH_PHASE5_SLOT, WIP_SLOT);
		Summary current = runPortfolio("phase6_health_checkpoint", EXPANDED_SYMBOLS, endDate);
		System.out.println("PHASE6_HEALTH_CHECKPOINT " + line(current));
		printTargetSymbolBreakdown(current.id);
		printTargetStrategyBreakdown(current.id);

		List<Summary> validations = new ArrayList<Summary>();
		List<Scenario> scenarios = phase6ContractHealthScenarios();
		for (Scenario scenario : scenarios) {
			copyStrategySlot(CONTRACT_HEALTH_PHASE5_SLOT, WIP_SLOT);
			scenario.apply();
			Summary summary = runPortfolio("phase6_health_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("PHASE6_HEALTH_CASE beatsCurrent=" + phase4ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase4ContractHealthScore(summary, current)) + " " + line(summary));
			printTargetSymbolBreakdown(summary.id);
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstAccepted = phase4ContractHealthBeatsCurrent(first, current) ? 0 : 1;
				int secondAccepted = phase4ContractHealthBeatsCurrent(second, current) ? 0 : 1;
				if (firstAccepted != secondAccepted) {
					return Integer.compare(firstAccepted, secondAccepted);
				}
				return Double.compare(phase4ContractHealthScore(second, current), phase4ContractHealthScore(first, current));
			}
		});

		System.out.println("PHASE6_HEALTH_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". beatsCurrent=" + phase4ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase4ContractHealthScore(summary, current)) + " " + line(summary));
		}

		boolean accepted = !validations.isEmpty() && phase4ContractHealthBeatsCurrent(validations.get(0), current);
		copyStrategySlot(CONTRACT_HEALTH_PHASE5_SLOT, WIP_SLOT);
		if (accepted) {
			String scenarioName = validations.get(0).name.replaceFirst("^phase6_health_", "");
			applyDeficiencyScenarioByName(scenarioName, scenarios);
			Summary applied = runPortfolio("phase6_health_applied_" + scenarioName, EXPANDED_SYMBOLS, endDate);
			copyStrategySlot(WIP_SLOT, CONTRACT_HEALTH_PHASE6_SLOT);
			System.out.println("APPLIED_WIP_PHASE6_CONTRACT_HEALTH=" + scenarioName);
			System.out.println("SAVED_PRESET=" + CONTRACT_HEALTH_PHASE6 + " rows=" + countSlotRows(CONTRACT_HEALTH_PHASE6_SLOT) + " diffVsWip=" + diffSlotRows(WIP_SLOT, CONTRACT_HEALTH_PHASE6_SLOT));
			System.out.println("BEST_PHASE6_CONTRACT_HEALTH=" + line(applied));
			printTargetSymbolBreakdown(applied.id);
			printTargetStrategyBreakdown(applied.id);
		} else {
			System.out.println("RESTORED_WIP_FROM=" + CONTRACT_HEALTH_PHASE5);
			System.out.println("NO_PHASE6_CONTRACT_HEALTH_CANDIDATE_BEAT_CHECKPOINT=true");
			if (!validations.isEmpty()) {
				Summary best = validations.get(0);
				System.out.println("BEST_REJECTED_PHASE6_CONTRACT_HEALTH=" + line(best));
				printTargetSymbolBreakdown(best.id);
				printTargetStrategyBreakdown(best.id);
			}
		}
	}

	private static void runPhase7ContractHealthSweep(String endDate) throws Exception {
		copyStrategySlot(CONTRACT_HEALTH_PHASE6_SLOT, WIP_SLOT);
		Summary current = runPortfolio("phase7_health_checkpoint", EXPANDED_SYMBOLS, endDate);
		System.out.println("PHASE7_HEALTH_CHECKPOINT " + line(current));
		printTargetSymbolBreakdown(current.id);
		printTargetStrategyBreakdown(current.id);

		List<Summary> validations = new ArrayList<Summary>();
		List<Scenario> scenarios = phase7ContractHealthScenarios();
		for (Scenario scenario : scenarios) {
			copyStrategySlot(CONTRACT_HEALTH_PHASE6_SLOT, WIP_SLOT);
			scenario.apply();
			Summary summary = runPortfolio("phase7_health_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("PHASE7_HEALTH_CASE beatsCurrent=" + phase7ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase7ContractHealthScore(summary, current)) + " " + line(summary));
			printTargetSymbolBreakdown(summary.id);
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstAccepted = phase7ContractHealthBeatsCurrent(first, current) ? 0 : 1;
				int secondAccepted = phase7ContractHealthBeatsCurrent(second, current) ? 0 : 1;
				if (firstAccepted != secondAccepted) {
					return Integer.compare(firstAccepted, secondAccepted);
				}
				return Double.compare(phase7ContractHealthScore(second, current), phase7ContractHealthScore(first, current));
			}
		});

		System.out.println("PHASE7_HEALTH_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". beatsCurrent=" + phase7ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase7ContractHealthScore(summary, current)) + " " + line(summary));
		}

		boolean accepted = !validations.isEmpty() && phase7ContractHealthBeatsCurrent(validations.get(0), current);
		copyStrategySlot(CONTRACT_HEALTH_PHASE6_SLOT, WIP_SLOT);
		if (accepted) {
			String scenarioName = validations.get(0).name.replaceFirst("^phase7_health_", "");
			applyDeficiencyScenarioByName(scenarioName, scenarios);
			Summary applied = runPortfolio("phase7_health_applied_" + scenarioName, EXPANDED_SYMBOLS, endDate);
			copyStrategySlot(WIP_SLOT, CONTRACT_HEALTH_PHASE7_SLOT);
			System.out.println("APPLIED_WIP_PHASE7_CONTRACT_HEALTH=" + scenarioName);
			System.out.println("SAVED_PRESET=" + CONTRACT_HEALTH_PHASE7 + " rows=" + countSlotRows(CONTRACT_HEALTH_PHASE7_SLOT) + " diffVsWip=" + diffSlotRows(WIP_SLOT, CONTRACT_HEALTH_PHASE7_SLOT));
			System.out.println("BEST_PHASE7_CONTRACT_HEALTH=" + line(applied));
			printTargetSymbolBreakdown(applied.id);
			printTargetStrategyBreakdown(applied.id);
		} else {
			System.out.println("RESTORED_WIP_FROM=" + CONTRACT_HEALTH_PHASE6);
			System.out.println("NO_PHASE7_CONTRACT_HEALTH_CANDIDATE_BEAT_CHECKPOINT=true");
			if (!validations.isEmpty()) {
				Summary best = validations.get(0);
				System.out.println("BEST_REJECTED_PHASE7_CONTRACT_HEALTH=" + line(best));
				printTargetSymbolBreakdown(best.id);
				printTargetStrategyBreakdown(best.id);
			}
		}
	}

	private static boolean phase7ContractHealthBeatsCurrent(Summary summary, Summary current) {
		if (summary == null || current == null) {
			return false;
		}
		double pnlGain = summary.pnl - current.pnl;
		double mymAverage = averagePnl(summary.mymPnl, summary.mymTrades);
		double currentMymAverage = averagePnl(current.mymPnl, current.mymTrades);
		double mclAverage = averagePnl(summary.mclPnl, summary.mclTrades);
		double currentMclAverage = averagePnl(current.mclPnl, current.mclTrades);
		boolean winAcceptable = summary.winRate >= current.winRate - (pnlGain >= 2000.0 ? 0.55 : 0.20);
		boolean tradeAcceptable = summary.trades >= current.trades || pnlGain >= 1800.0;
		boolean targetContributionAcceptable = (summary.mymTrades + summary.mclTrades) >= (current.mymTrades + current.mclTrades)
			|| (summary.mymPnl + summary.mclPnl) >= (current.mymPnl + current.mclPnl + 400.0);
		boolean mymQualityAcceptable = summary.mymPnl >= current.mymPnl - 150.0
			&& (mymAverage >= currentMymAverage - 18.0 || summary.mymPnl >= current.mymPnl + 250.0);
		boolean mclQualityAcceptable = summary.mclPnl >= current.mclPnl - 150.0
			&& (mclAverage >= currentMclAverage - 45.0 || summary.mclPnl >= current.mclPnl + 250.0);
		return summary.ruleViolation == 0
			&& summary.dailyLossBreaches == 0
			&& summary.trailingDrawdownBreaches == 0
			&& pnlGain > 0.0
			&& tradeAcceptable
			&& targetContributionAcceptable
			&& winAcceptable
			&& mymQualityAcceptable
			&& mclQualityAcceptable
			&& summary.profitFactor >= current.profitFactor - 0.08
			&& summary.drawdownPct <= current.drawdownPct + 0.30
			&& summary.maxIntradayLoss >= current.maxIntradayLoss - 120.0
			&& summary.maxAggregateMae >= current.maxAggregateMae - 120.0;
	}

	private static double phase7ContractHealthScore(Summary summary, Summary current) {
		if (summary == null || current == null) {
			return Double.NEGATIVE_INFINITY;
		}
		double mymAverage = averagePnl(summary.mymPnl, summary.mymTrades);
		double currentMymAverage = averagePnl(current.mymPnl, current.mymTrades);
		double mclAverage = averagePnl(summary.mclPnl, summary.mclTrades);
		double currentMclAverage = averagePnl(current.mclPnl, current.mclTrades);
		double score = summary.pnl - current.pnl;
		score += (summary.trades - current.trades) * 9.0;
		score += ((summary.mymTrades + summary.mclTrades) - (current.mymTrades + current.mclTrades)) * 28.0;
		score += ((summary.mymPnl - current.mymPnl) + (summary.mclPnl - current.mclPnl)) * 0.55;
		score += (mymAverage - currentMymAverage) * 16.0;
		score += (mclAverage - currentMclAverage) * 8.0;
		score += (summary.winRate - current.winRate) * 700.0;
		score += (summary.profitFactor - current.profitFactor) * 1900.0;
		score -= Math.max(0.0, summary.drawdownPct - current.drawdownPct) * 3500.0;
		score -= Math.max(0.0, current.maxIntradayLoss - summary.maxIntradayLoss) * 2.4;
		score -= Math.max(0.0, current.maxAggregateMae - summary.maxAggregateMae) * 2.4;
		if (summary.ruleViolation != 0 || summary.dailyLossBreaches != 0 || summary.trailingDrawdownBreaches != 0) {
			score -= 100000.0;
		}
		return score;
	}

	private static double averagePnl(double pnl, int trades) {
		return trades <= 0 ? -1000.0 : pnl / trades;
	}

	private static void runPhase8ContractHealthSweep(String endDate) throws Exception {
		copyStrategySlot(CONTRACT_HEALTH_PHASE6_SLOT, WIP_SLOT);
		Summary current = runPortfolio("phase8_health_checkpoint", EXPANDED_SYMBOLS, endDate);
		System.out.println("PHASE8_HEALTH_CHECKPOINT " + line(current));
		printTargetSymbolBreakdown(current.id);
		printTargetStrategyBreakdown(current.id);

		List<Summary> validations = new ArrayList<Summary>();
		List<Scenario> scenarios = phase8ContractHealthScenarios();
		for (Scenario scenario : scenarios) {
			copyStrategySlot(CONTRACT_HEALTH_PHASE6_SLOT, WIP_SLOT);
			scenario.apply();
			Summary summary = runPortfolio("phase8_health_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("PHASE8_HEALTH_CASE beatsCurrent=" + phase8ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase8ContractHealthScore(summary, current)) + " " + line(summary));
			printTargetSymbolBreakdown(summary.id);
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstAccepted = phase8ContractHealthBeatsCurrent(first, current) ? 0 : 1;
				int secondAccepted = phase8ContractHealthBeatsCurrent(second, current) ? 0 : 1;
				if (firstAccepted != secondAccepted) {
					return Integer.compare(firstAccepted, secondAccepted);
				}
				return Double.compare(phase8ContractHealthScore(second, current), phase8ContractHealthScore(first, current));
			}
		});

		System.out.println("PHASE8_HEALTH_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". beatsCurrent=" + phase8ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase8ContractHealthScore(summary, current)) + " " + line(summary));
		}

		boolean accepted = !validations.isEmpty() && phase8ContractHealthBeatsCurrent(validations.get(0), current);
		copyStrategySlot(CONTRACT_HEALTH_PHASE6_SLOT, WIP_SLOT);
		if (accepted) {
			String scenarioName = validations.get(0).name.replaceFirst("^phase8_health_", "");
			applyDeficiencyScenarioByName(scenarioName, scenarios);
			Summary applied = runPortfolio("phase8_health_applied_" + scenarioName, EXPANDED_SYMBOLS, endDate);
			copyStrategySlot(WIP_SLOT, CONTRACT_HEALTH_PHASE8_SLOT);
			System.out.println("APPLIED_WIP_PHASE8_CONTRACT_HEALTH=" + scenarioName);
			System.out.println("SAVED_PRESET=" + CONTRACT_HEALTH_PHASE8 + " rows=" + countSlotRows(CONTRACT_HEALTH_PHASE8_SLOT) + " diffVsWip=" + diffSlotRows(WIP_SLOT, CONTRACT_HEALTH_PHASE8_SLOT));
			System.out.println("BEST_PHASE8_CONTRACT_HEALTH=" + line(applied));
			printTargetSymbolBreakdown(applied.id);
			printTargetStrategyBreakdown(applied.id);
		} else {
			System.out.println("RESTORED_WIP_FROM=" + CONTRACT_HEALTH_PHASE6);
			System.out.println("NO_PHASE8_CONTRACT_HEALTH_CANDIDATE_BEAT_CHECKPOINT=true");
			if (!validations.isEmpty()) {
				Summary best = validations.get(0);
				System.out.println("BEST_REJECTED_PHASE8_CONTRACT_HEALTH=" + line(best));
				printTargetSymbolBreakdown(best.id);
				printTargetStrategyBreakdown(best.id);
			}
		}
	}

	private static boolean phase8ContractHealthBeatsCurrent(Summary summary, Summary current) {
		if (summary == null || current == null) {
			return false;
		}
		double pnlGain = summary.pnl - current.pnl;
		boolean winAcceptable = summary.winRate >= current.winRate - (pnlGain >= 1500.0 ? 0.45 : 0.20);
		boolean tradeAcceptable = summary.trades >= current.trades || pnlGain >= 1500.0;
		return summary.ruleViolation == 0
			&& summary.dailyLossBreaches == 0
			&& summary.trailingDrawdownBreaches == 0
			&& pnlGain > 0.0
			&& tradeAcceptable
			&& winAcceptable
			&& summary.profitFactor >= current.profitFactor - 0.08
			&& summary.drawdownPct <= current.drawdownPct + 0.30
			&& summary.maxIntradayLoss >= current.maxIntradayLoss - 120.0
			&& summary.maxAggregateMae >= current.maxAggregateMae - 120.0
			&& summary.mymPnl >= current.mymPnl - 200.0
			&& summary.mclPnl >= current.mclPnl - 200.0;
	}

	private static double phase8ContractHealthScore(Summary summary, Summary current) {
		if (summary == null || current == null) {
			return Double.NEGATIVE_INFINITY;
		}
		double score = summary.pnl - current.pnl;
		score += (summary.trades - current.trades) * 8.0;
		score += (summary.winRate - current.winRate) * 650.0;
		score += (summary.profitFactor - current.profitFactor) * 1900.0;
		score += ((summary.mymPnl - current.mymPnl) + (summary.mclPnl - current.mclPnl)) * 0.25;
		score -= Math.max(0.0, summary.drawdownPct - current.drawdownPct) * 3500.0;
		score -= Math.max(0.0, current.maxIntradayLoss - summary.maxIntradayLoss) * 2.4;
		score -= Math.max(0.0, current.maxAggregateMae - summary.maxAggregateMae) * 2.4;
		if (summary.ruleViolation != 0 || summary.dailyLossBreaches != 0 || summary.trailingDrawdownBreaches != 0) {
			score -= 100000.0;
		}
		return score;
	}

	private static void runPhase9ContractHealthSweep(String endDate) throws Exception {
		copyStrategySlot(CONTRACT_HEALTH_PHASE8_SLOT, WIP_SLOT);
		Summary current = runPortfolio("phase9_health_checkpoint", EXPANDED_SYMBOLS, endDate);
		System.out.println("PHASE9_HEALTH_CHECKPOINT " + line(current));
		printTargetSymbolBreakdown(current.id);
		printTargetStrategyBreakdown(current.id);

		List<Summary> validations = new ArrayList<Summary>();
		List<Scenario> scenarios = phase9ContractHealthScenarios();
		for (Scenario scenario : scenarios) {
			copyStrategySlot(CONTRACT_HEALTH_PHASE8_SLOT, WIP_SLOT);
			scenario.apply();
			Summary summary = runPortfolio("phase9_health_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("PHASE9_HEALTH_CASE beatsCurrent=" + phase8ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase8ContractHealthScore(summary, current)) + " " + line(summary));
			printTargetSymbolBreakdown(summary.id);
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstAccepted = phase8ContractHealthBeatsCurrent(first, current) ? 0 : 1;
				int secondAccepted = phase8ContractHealthBeatsCurrent(second, current) ? 0 : 1;
				if (firstAccepted != secondAccepted) {
					return Integer.compare(firstAccepted, secondAccepted);
				}
				return Double.compare(phase8ContractHealthScore(second, current), phase8ContractHealthScore(first, current));
			}
		});

		System.out.println("PHASE9_HEALTH_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". beatsCurrent=" + phase8ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase8ContractHealthScore(summary, current)) + " " + line(summary));
		}

		boolean accepted = !validations.isEmpty() && phase8ContractHealthBeatsCurrent(validations.get(0), current);
		copyStrategySlot(CONTRACT_HEALTH_PHASE8_SLOT, WIP_SLOT);
		if (accepted) {
			String scenarioName = validations.get(0).name.replaceFirst("^phase9_health_", "");
			applyDeficiencyScenarioByName(scenarioName, scenarios);
			Summary applied = runPortfolio("phase9_health_applied_" + scenarioName, EXPANDED_SYMBOLS, endDate);
			copyStrategySlot(WIP_SLOT, CONTRACT_HEALTH_PHASE9_SLOT);
			System.out.println("APPLIED_WIP_PHASE9_CONTRACT_HEALTH=" + scenarioName);
			System.out.println("SAVED_PRESET=" + CONTRACT_HEALTH_PHASE9 + " rows=" + countSlotRows(CONTRACT_HEALTH_PHASE9_SLOT) + " diffVsWip=" + diffSlotRows(WIP_SLOT, CONTRACT_HEALTH_PHASE9_SLOT));
			System.out.println("BEST_PHASE9_CONTRACT_HEALTH=" + line(applied));
			printTargetSymbolBreakdown(applied.id);
			printTargetStrategyBreakdown(applied.id);
		} else {
			System.out.println("RESTORED_WIP_FROM=" + CONTRACT_HEALTH_PHASE8);
			System.out.println("NO_PHASE9_CONTRACT_HEALTH_CANDIDATE_BEAT_CHECKPOINT=true");
			if (!validations.isEmpty()) {
				Summary best = validations.get(0);
				System.out.println("BEST_REJECTED_PHASE9_CONTRACT_HEALTH=" + line(best));
				printTargetSymbolBreakdown(best.id);
				printTargetStrategyBreakdown(best.id);
			}
		}
	}

	private static void runPhase10ContractHealthSweep(String endDate) throws Exception {
		copyStrategySlot(CONTRACT_HEALTH_PHASE8_SLOT, WIP_SLOT);
		Summary current = runPortfolio("phase10_health_checkpoint", EXPANDED_SYMBOLS, endDate);
		System.out.println("PHASE10_HEALTH_CHECKPOINT " + line(current));
		printTargetSymbolBreakdown(current.id);
		printTargetStrategyBreakdown(current.id);

		List<Summary> validations = new ArrayList<Summary>();
		List<Scenario> scenarios = phase10ContractHealthScenarios();
		for (Scenario scenario : scenarios) {
			copyStrategySlot(CONTRACT_HEALTH_PHASE8_SLOT, WIP_SLOT);
			scenario.apply();
			Summary summary = runPortfolio("phase10_health_" + scenario.name(), EXPANDED_SYMBOLS, endDate);
			validations.add(summary);
			System.out.println("PHASE10_HEALTH_CASE beatsCurrent=" + phase10ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase10ContractHealthScore(summary, current)) + " " + line(summary));
			printTargetSymbolBreakdown(summary.id);
			printNewSymbolStrategyBreakdown(summary.id);
		}

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstAccepted = phase10ContractHealthBeatsCurrent(first, current) ? 0 : 1;
				int secondAccepted = phase10ContractHealthBeatsCurrent(second, current) ? 0 : 1;
				if (firstAccepted != secondAccepted) {
					return Integer.compare(firstAccepted, secondAccepted);
				}
				return Double.compare(phase10ContractHealthScore(second, current), phase10ContractHealthScore(first, current));
			}
		});

		System.out.println("PHASE10_HEALTH_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". beatsCurrent=" + phase10ContractHealthBeatsCurrent(summary, current) + " score=" + round(phase10ContractHealthScore(summary, current)) + " " + line(summary));
		}

		boolean accepted = !validations.isEmpty() && phase10ContractHealthBeatsCurrent(validations.get(0), current);
		copyStrategySlot(CONTRACT_HEALTH_PHASE8_SLOT, WIP_SLOT);
		if (accepted) {
			String scenarioName = validations.get(0).name.replaceFirst("^phase10_health_", "");
			applyDeficiencyScenarioByName(scenarioName, scenarios);
			Summary applied = runPortfolio("phase10_health_applied_" + scenarioName, EXPANDED_SYMBOLS, endDate);
			copyStrategySlot(WIP_SLOT, CONTRACT_HEALTH_PHASE10_SLOT);
			System.out.println("APPLIED_WIP_PHASE10_CONTRACT_HEALTH=" + scenarioName);
			System.out.println("SAVED_PRESET=" + CONTRACT_HEALTH_PHASE10 + " rows=" + countSlotRows(CONTRACT_HEALTH_PHASE10_SLOT) + " diffVsWip=" + diffSlotRows(WIP_SLOT, CONTRACT_HEALTH_PHASE10_SLOT));
			System.out.println("BEST_PHASE10_CONTRACT_HEALTH=" + line(applied));
			printTargetSymbolBreakdown(applied.id);
			printTargetStrategyBreakdown(applied.id);
		} else {
			System.out.println("RESTORED_WIP_FROM=" + CONTRACT_HEALTH_PHASE8);
			System.out.println("NO_PHASE10_CONTRACT_HEALTH_CANDIDATE_BEAT_CHECKPOINT=true");
			if (!validations.isEmpty()) {
				Summary best = validations.get(0);
				System.out.println("BEST_REJECTED_PHASE10_CONTRACT_HEALTH=" + line(best));
				printTargetSymbolBreakdown(best.id);
				printTargetStrategyBreakdown(best.id);
			}
		}
	}

	private static boolean phase10ContractHealthBeatsCurrent(Summary summary, Summary current) {
		if (summary == null || current == null) {
			return false;
		}
		double pnlGain = summary.pnl - current.pnl;
		double targetPnlGain = (summary.mymPnl + summary.mclPnl) - (current.mymPnl + current.mclPnl);
		int targetTradeGain = (summary.mymTrades + summary.mclTrades) - (current.mymTrades + current.mclTrades);
		boolean winAcceptable = summary.winRate >= current.winRate - (pnlGain >= 2500.0 ? 0.60 : 0.25);
		boolean tradeAcceptable = summary.trades >= current.trades || pnlGain >= 1800.0;
		boolean targetContributionAcceptable = targetPnlGain >= 300.0 || (targetTradeGain >= 6 && targetPnlGain >= 0.0);
		return summary.ruleViolation == 0
			&& summary.dailyLossBreaches == 0
			&& summary.trailingDrawdownBreaches == 0
			&& pnlGain > 0.0
			&& tradeAcceptable
			&& targetContributionAcceptable
			&& winAcceptable
			&& summary.profitFactor >= current.profitFactor - 0.10
			&& summary.drawdownPct <= current.drawdownPct + 0.35
			&& summary.maxIntradayLoss >= current.maxIntradayLoss - 140.0
			&& summary.maxAggregateMae >= current.maxAggregateMae - 140.0
			&& summary.mymPnl >= current.mymPnl - 250.0
			&& summary.mclPnl >= current.mclPnl - 250.0;
	}

	private static double phase10ContractHealthScore(Summary summary, Summary current) {
		if (summary == null || current == null) {
			return Double.NEGATIVE_INFINITY;
		}
		double mymAverage = averagePnl(summary.mymPnl, summary.mymTrades);
		double currentMymAverage = averagePnl(current.mymPnl, current.mymTrades);
		double mclAverage = averagePnl(summary.mclPnl, summary.mclTrades);
		double currentMclAverage = averagePnl(current.mclPnl, current.mclTrades);
		double score = summary.pnl - current.pnl;
		score += ((summary.mymPnl - current.mymPnl) + (summary.mclPnl - current.mclPnl)) * 0.65;
		score += ((summary.mymTrades + summary.mclTrades) - (current.mymTrades + current.mclTrades)) * 34.0;
		score += (summary.trades - current.trades) * 7.0;
		score += (mymAverage - currentMymAverage) * 12.0;
		score += (mclAverage - currentMclAverage) * 10.0;
		score += (summary.winRate - current.winRate) * 800.0;
		score += (summary.profitFactor - current.profitFactor) * 2100.0;
		score -= Math.max(0.0, summary.drawdownPct - current.drawdownPct) * 3800.0;
		score -= Math.max(0.0, current.maxIntradayLoss - summary.maxIntradayLoss) * 2.6;
		score -= Math.max(0.0, current.maxAggregateMae - summary.maxAggregateMae) * 2.6;
		if (summary.ruleViolation != 0 || summary.dailyLossBreaches != 0 || summary.trailingDrawdownBreaches != 0) {
			score -= 100000.0;
		}
		return score;
	}

	private static boolean phase2ContractHealthBeatsCurrent(Summary summary, Summary current) {
		if (summary == null || current == null) {
			return false;
		}
		double pnlGain = summary.pnl - current.pnl;
		boolean winAcceptable = summary.winRate >= current.winRate - (pnlGain >= 1500.0 ? 0.45 : 0.10);
		boolean tradeAcceptable = summary.trades >= current.trades || pnlGain >= 1800.0;
		return summary.ruleViolation == 0
			&& summary.dailyLossBreaches == 0
			&& summary.trailingDrawdownBreaches == 0
			&& pnlGain > 0.0
			&& tradeAcceptable
			&& winAcceptable
			&& summary.profitFactor >= current.profitFactor - 0.08
			&& summary.drawdownPct <= current.drawdownPct + 0.25
			&& summary.maxIntradayLoss >= current.maxIntradayLoss - 100.0
			&& summary.maxAggregateMae >= current.maxAggregateMae - 100.0
			&& summary.mymPnl >= current.mymPnl - 250.0
			&& summary.mclPnl >= current.mclPnl - 250.0;
	}

	private static double phase2ContractHealthScore(Summary summary, Summary current) {
		if (summary == null || current == null) {
			return Double.NEGATIVE_INFINITY;
		}
		double score = summary.pnl - current.pnl;
		score += (summary.trades - current.trades) * 8.0;
		score += (summary.winRate - current.winRate) * 650.0;
		score += (summary.profitFactor - current.profitFactor) * 1800.0;
		score += ((summary.mymPnl - current.mymPnl) + (summary.mclPnl - current.mclPnl)) * 0.35;
		score += ((summary.mymTrades - current.mymTrades) + (summary.mclTrades - current.mclTrades)) * 18.0;
		score -= Math.max(0.0, summary.drawdownPct - current.drawdownPct) * 3000.0;
		score -= Math.max(0.0, current.maxIntradayLoss - summary.maxIntradayLoss) * 2.0;
		score -= Math.max(0.0, current.maxAggregateMae - summary.maxAggregateMae) * 2.0;
		if (summary.ruleViolation != 0 || summary.dailyLossBreaches != 0 || summary.trailingDrawdownBreaches != 0) {
			score -= 100000.0;
		}
		return score;
	}

	private static List<Scenario> phase2ContractHealthScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "mym_shadow_size_m020", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.20, 0.10, 0.10);
			}
		});
		add(values, "mym_shadow_size_m025", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.25, 0.10, 0.10);
			}
		});
		add(values, "mym_shadow_size_m030", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.30, 0.10, 0.10);
			}
		});
		add(values, "mym_shadow_size_m025_no_echo", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setMicroEchoMode("MYM", false, false, false);
				setFrequencyRiskMultipliers("MYM", 0.25, 0.10, 0.10);
			}
		});
		add(values, "mym_shadow_dense_m020", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroShadowQuality("MYM", false, true, 8, 570, 920, 12, 80.0, 0.80, 0.65, 0.25, 10);
				setFrequencyRiskMultipliers("MYM", 0.20, 0.10, 0.10);
			}
		});
		add(values, "mym_shadow_dense_m025_no_echo", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setMicroEchoMode("MYM", false, false, false);
				enableMicroShadowQuality("MYM", false, true, 8, 570, 920, 12, 80.0, 0.80, 0.65, 0.25, 10);
				setFrequencyRiskMultipliers("MYM", 0.25, 0.10, 0.10);
			}
		});
		add(values, "mym_idx_short_shadow_m020", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymIndexConfirmation(3, false, true, 590, 820, 20, 12, 95.0, 0.95, 0.60, 25.0, 0.50, 35);
				setFrequencyRiskMultipliers("MYM", 0.20, 0.10, 0.10);
			}
		});
		add(values, "mym_orb_retest_long_shadow_m020", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymOrbRetest(1, true, false, 590, 690, 2.0, 5.0, 110.0, 0.95, 0.60, 25.0, 45);
				setFrequencyRiskMultipliers("MYM", 0.20, 0.10, 0.10);
			}
		});
		add(values, "mcl_crude_open_long_strict", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclCrudeSessionOpen(1, true, false, 540, 550, 551, 630, 2.0, 20.0, 1.05, 0.70, 24.0, 35);
			}
		});
		add(values, "mcl_eia_late_confirmed", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclEiaContinuation(1, true, true, 626, 630, 660, 750, 1.0, 24.0, 1.25, 0.75, 18.0, 45);
			}
		});
		add(values, "mcl_exit_runner", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyManagedExitProfile("MCL", 0.75, 1.25, 0.65, 8.0, true, 1.35, 0.35, 3);
			}
		});
		add(values, "nq_ipb_quality_only", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyNqMomentumHealth(false, 0.95, 1.05, 0.80, 40.0, false);
			}
		});
		add(values, "nq_disable_ipb_keep_mim", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyNqMomentumHealth(true, 0.95, 1.05, 0.85, 40.0, false);
			}
		});
		add(values, "nq_orb_short_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyNqOrbShortQuality(28.0, 110.0, 90.0, 450.0);
			}
		});
		add(values, "mes_mild_avg_lift", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMesAverageWinLift(0.70, 0.78, 20.0, 600.0, false);
			}
		});
		add(values, "mes_shadow_scaled_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMesAverageWinLift(0.66, 0.75, 20.0, 575.0, false);
				setFrequencyRiskMultipliers("MES", 0.20, 0.10, 0.10);
			}
		});
		add(values, "es_vwap_quality_runner", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyEsVwapHealth(0.85, 6.0, 36.0, 1.35, true);
			}
		});
		add(values, "es_wft_quality_probe", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableWinnerFollowThroughQuality("ES", true, true, "ORB,VWAP,SWEEP", 50.0, 0.95, 2, 250.0, 0.75, 18.0, 0.56, 0.44, 12);
				setFrequencyRiskMultipliers("ES", 0.14, 0.10, 0.18);
			}
		});
		add(values, "combo_mym_shadow_nq_orb_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.25, 0.10, 0.10);
				applyNqOrbShortQuality(28.0, 110.0, 90.0, 450.0);
			}
		});
		add(values, "combo_mym_shadow_mes_mild", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.25, 0.10, 0.10);
				applyMesAverageWinLift(0.70, 0.78, 20.0, 600.0, false);
			}
		});
		add(values, "combo_mym_shadow_nq_mes_es", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.25, 0.10, 0.10);
				applyNqOrbShortQuality(28.0, 110.0, 90.0, 450.0);
				applyMesAverageWinLift(0.66, 0.75, 20.0, 575.0, false);
				applyEsVwapHealth(0.85, 6.0, 36.0, 1.35, true);
			}
		});
		return values;
	}

	private static List<Scenario> phase3ContractHealthScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "mym_shadow_m035", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.35, 0.10, 0.10);
			}
		});
		add(values, "mym_shadow_m040", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.40, 0.10, 0.10);
			}
		});
		add(values, "mym_shadow_m045", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.45, 0.10, 0.10);
			}
		});
		add(values, "mym_shadow_m050", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.50, 0.10, 0.10);
			}
		});
		add(values, "mcl_exit_runner", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyManagedExitProfile("MCL", 0.75, 1.25, 0.65, 8.0, true, 1.35, 0.35, 3);
			}
		});
		add(values, "mym_m035_mcl_runner", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.35, 0.10, 0.10);
				applyManagedExitProfile("MCL", 0.75, 1.25, 0.65, 8.0, true, 1.35, 0.35, 3);
			}
		});
		add(values, "mym_m040_mcl_runner", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.40, 0.10, 0.10);
				applyManagedExitProfile("MCL", 0.75, 1.25, 0.65, 8.0, true, 1.35, 0.35, 3);
			}
		});
		add(values, "mym_m045_mcl_runner", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.45, 0.10, 0.10);
				applyManagedExitProfile("MCL", 0.75, 1.25, 0.65, 8.0, true, 1.35, 0.35, 3);
			}
		});
		add(values, "mym_m040_mcl_runner_no_echo", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setMicroEchoMode("MYM", false, false, false);
				setFrequencyRiskMultipliers("MYM", 0.40, 0.10, 0.10);
				applyManagedExitProfile("MCL", 0.75, 1.25, 0.65, 8.0, true, 1.35, 0.35, 3);
			}
		});
		add(values, "mym_dense_m035_mcl_runner", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroShadowQuality("MYM", false, true, 8, 570, 920, 12, 80.0, 0.80, 0.65, 0.25, 10);
				setFrequencyRiskMultipliers("MYM", 0.35, 0.10, 0.10);
				applyManagedExitProfile("MCL", 0.75, 1.25, 0.65, 8.0, true, 1.35, 0.35, 3);
			}
		});
		add(values, "nq_disable_ipb_low_risk", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyNqMomentumHealth(true, 0.95, 1.05, 0.85, 40.0, false, 350.0);
			}
		});
		add(values, "nq_ipb_quality_low_risk", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyNqMomentumHealth(false, 0.95, 1.05, 0.80, 40.0, false, 350.0);
			}
		});
		add(values, "mes_tiny_avg_lift", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMesAverageWinLift(0.55, 0.70, 14.0, 450.0, false);
			}
		});
		add(values, "m2k_runner_r700", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(700.0, 0.74, 0.68, 20.0, false, false);
			}
		});
		add(values, "mym_m030_mcl_runner_m2k700", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.30, 0.10, 0.10);
				applyManagedExitProfile("MCL", 0.75, 1.25, 0.65, 8.0, true, 1.35, 0.35, 3);
				applyM2kProfitScale(700.0, 0.74, 0.68, 20.0, false, false);
			}
		});
		add(values, "mym_m033_mcl_runner_m2k700", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.33, 0.10, 0.10);
				applyManagedExitProfile("MCL", 0.75, 1.25, 0.65, 8.0, true, 1.35, 0.35, 3);
				applyM2kProfitScale(700.0, 0.74, 0.68, 20.0, false, false);
			}
		});
		add(values, "mym_m035_mcl_runner_m2k700", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.35, 0.10, 0.10);
				applyManagedExitProfile("MCL", 0.75, 1.25, 0.65, 8.0, true, 1.35, 0.35, 3);
				applyM2kProfitScale(700.0, 0.74, 0.68, 20.0, false, false);
			}
		});
		add(values, "mym_dense_m035_mcl_runner_m2k700", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroShadowQuality("MYM", false, true, 8, 570, 920, 12, 80.0, 0.80, 0.65, 0.25, 10);
				setFrequencyRiskMultipliers("MYM", 0.35, 0.10, 0.10);
				applyManagedExitProfile("MCL", 0.75, 1.25, 0.65, 8.0, true, 1.35, 0.35, 3);
				applyM2kProfitScale(700.0, 0.74, 0.68, 20.0, false, false);
			}
		});
		add(values, "mym_m040_mcl_runner_m2k700", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.40, 0.10, 0.10);
				applyManagedExitProfile("MCL", 0.75, 1.25, 0.65, 8.0, true, 1.35, 0.35, 3);
				applyM2kProfitScale(700.0, 0.74, 0.68, 20.0, false, false);
			}
		});
		return values;
	}

	private static List<Scenario> phase4ContractHealthScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "mym_shadow_m036", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.36, 0.10, 0.10);
			}
		});
		add(values, "mym_shadow_m037", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.37, 0.10, 0.10);
			}
		});
		add(values, "mym_no_echo_shadow_m036", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setMicroEchoMode("MYM", false, false, false);
				setFrequencyRiskMultipliers("MYM", 0.36, 0.10, 0.10);
			}
		});
		add(values, "mcl_runner_gb115_25", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyManagedExitProfile("MCL", 0.65, 1.15, 0.55, 8.0, true, 1.15, 0.25, 2);
			}
		});
		add(values, "mcl_runner_gb125_25", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyManagedExitProfile("MCL", 0.70, 1.20, 0.60, 8.0, true, 1.25, 0.25, 2);
			}
		});
		add(values, "mcl_shadow_short_strict", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroShadowQuality("MCL", false, true, 4, 570, 920, 12, 28.0, 0.75, 0.75, 0.30, 10);
				setFrequencyRiskMultipliers("MCL", 0.12, 0.10, 0.10);
			}
		});
		add(values, "mcl_wft_after_winners", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableWinnerFollowThroughQuality("MCL", true, true, "ORB,AFT,CMOM,MIM", 26.0, 0.85, 4, 75.0, 0.70, 14.0, 0.55, 0.45, 10);
				setFrequencyRiskMultipliers("MCL", 0.14, 0.10, 0.12);
			}
		});
		add(values, "mcl_risk_950", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				upsertRisk(WIP_SLOT, "MCL", 950.0, 50);
			}
		});
		add(values, "m2k_r725", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(725.0, 0.74, 0.68, 20.0, false, false);
			}
		});
		add(values, "m2k_r750", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(750.0, 0.74, 0.68, 20.0, false, false);
			}
		});
		add(values, "m2k_r775", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(775.0, 0.76, 0.68, 20.0, false, false);
			}
		});
		add(values, "m2k_wft_r700", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(700.0, 0.74, 0.68, 20.0, false, false);
				enableWinnerFollowThroughQuality("M2K", true, true, "OMOM,CMOM,VPB", 22.0, 0.70, 5, 45.0, 0.70, 10.0, 0.55, 0.45, 10);
				setFrequencyRiskMultipliers("M2K", 0.14, 0.10, 0.12);
			}
		});
		add(values, "mes_shadow_scale_m025", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MES", 0.25, 0.10, 0.10);
			}
		});
		add(values, "mes_r450_shadow_m025", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				upsertRisk(WIP_SLOT, "MES", 450.0, 12);
				setFrequencyRiskMultipliers("MES", 0.25, 0.10, 0.10);
			}
		});
		add(values, "mes_wft_after_omom", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableWinnerFollowThroughQuality("MES", true, true, "OMOM,CMOM,AFT", 18.0, 0.70, 5, 20.0, 0.72, 10.0, 0.55, 0.45, 10);
				setFrequencyRiskMultipliers("MES", 0.14, 0.10, 0.12);
			}
		});
		add(values, "nq_r375", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				upsertRisk(WIP_SLOT, "NQ", 375.0, 1);
			}
		});
		add(values, "nq_r400", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				upsertRisk(WIP_SLOT, "NQ", 400.0, 1);
			}
		});
		add(values, "nq_fvg_boost_r375", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("NQ", WIP_SLOT);
				settings.fvgRewardRisk = Math.max(settings.fvgRewardRisk, 1.08);
				settings.fvgMinVolumeRatio = Math.max(settings.fvgMinVolumeRatio, 0.55);
				settings.fvgMaxRiskTicks = Math.max(settings.fvgMaxRiskTicks, 52.0);
				saveTunedSettings("NQ", settings);
				upsertRisk(WIP_SLOT, "NQ", 375.0, 1);
			}
		});
		add(values, "nq_ipb_trim_r400", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyNqMomentumHealth(true, 0.95, 1.05, 0.85, 40.0, false, 400.0);
			}
		});
		add(values, "es_wft_after_vwap", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableWinnerFollowThroughQuality("ES", true, true, "ORB,VWAP,SWEEP", 32.0, 0.85, 3, 150.0, 0.80, 18.0, 0.56, 0.44, 10);
				setFrequencyRiskMultipliers("ES", 0.14, 0.10, 0.12);
			}
		});
		add(values, "es_quality_expansion_wft", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyEsQualityExpansion(700.0, true, true, true);
			}
		});
		add(values, "combo_mym036_mcl115_m2k725", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.36, 0.10, 0.10);
				applyManagedExitProfile("MCL", 0.65, 1.15, 0.55, 8.0, true, 1.15, 0.25, 2);
				applyM2kProfitScale(725.0, 0.74, 0.68, 20.0, false, false);
			}
		});
		add(values, "combo_mym036_m2k750_mes_wft", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.36, 0.10, 0.10);
				applyM2kProfitScale(750.0, 0.74, 0.68, 20.0, false, false);
				enableWinnerFollowThroughQuality("MES", true, true, "OMOM,CMOM,AFT", 18.0, 0.70, 5, 20.0, 0.72, 10.0, 0.55, 0.45, 10);
				setFrequencyRiskMultipliers("MES", 0.14, 0.10, 0.12);
			}
		});
		add(values, "combo_m2k725_mes_es_wft", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(725.0, 0.74, 0.68, 20.0, false, false);
				enableWinnerFollowThroughQuality("MES", true, true, "OMOM,CMOM,AFT", 18.0, 0.70, 5, 20.0, 0.72, 10.0, 0.55, 0.45, 10);
				setFrequencyRiskMultipliers("MES", 0.14, 0.10, 0.12);
				enableWinnerFollowThroughQuality("ES", true, true, "ORB,VWAP,SWEEP", 32.0, 0.85, 3, 150.0, 0.80, 18.0, 0.56, 0.44, 10);
				setFrequencyRiskMultipliers("ES", 0.14, 0.10, 0.12);
			}
		});
		add(values, "combo_balanced_all_laggards", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.36, 0.10, 0.10);
				applyManagedExitProfile("MCL", 0.65, 1.15, 0.55, 8.0, true, 1.15, 0.25, 2);
				applyM2kProfitScale(725.0, 0.74, 0.68, 20.0, false, false);
				enableWinnerFollowThroughQuality("MES", true, true, "OMOM,CMOM,AFT", 18.0, 0.70, 5, 20.0, 0.72, 10.0, 0.55, 0.45, 10);
				setFrequencyRiskMultipliers("MES", 0.14, 0.10, 0.12);
				enableWinnerFollowThroughQuality("ES", true, true, "ORB,VWAP,SWEEP", 32.0, 0.85, 3, 150.0, 0.80, 18.0, 0.56, 0.44, 10);
				setFrequencyRiskMultipliers("ES", 0.14, 0.10, 0.12);
				upsertRisk(WIP_SLOT, "NQ", 375.0, 1);
			}
		});
		add(values, "combo_profit_push_no_nq", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setFrequencyRiskMultipliers("MYM", 0.36, 0.10, 0.10);
				applyManagedExitProfile("MCL", 0.65, 1.15, 0.55, 8.0, true, 1.15, 0.25, 2);
				applyM2kProfitScale(750.0, 0.74, 0.68, 20.0, false, false);
				enableWinnerFollowThroughQuality("MES", true, true, "OMOM,CMOM,AFT", 18.0, 0.70, 5, 20.0, 0.72, 10.0, 0.55, 0.45, 10);
				setFrequencyRiskMultipliers("MES", 0.14, 0.10, 0.12);
				enableWinnerFollowThroughQuality("ES", true, true, "ORB,VWAP,SWEEP", 32.0, 0.85, 3, 150.0, 0.80, 18.0, 0.56, 0.44, 10);
				setFrequencyRiskMultipliers("ES", 0.14, 0.10, 0.12);
			}
		});
		return values;
	}

	private static List<Scenario> phase5ContractHealthScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "m2k_open_mult_078", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(800.0, 0.78, 0.68, 20.0, false, false);
			}
		});
		add(values, "m2k_open_mult_080", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(825.0, 0.80, 0.68, 20.0, false, false);
			}
		});
		add(values, "m2k_open_mult_082", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(850.0, 0.82, 0.68, 20.0, false, false);
			}
		});
		add(values, "m2k_loss_cut_b6_r045", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("M2K", 6, 0.45, 0.10);
			}
		});
		add(values, "m2k_loss_cut_b8_r055", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("M2K", 8, 0.55, 0.15);
			}
		});
		add(values, "m2k_loss_cut_b10_r060", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("M2K", 10, 0.60, 0.15);
			}
		});
		add(values, "m2k_managed_giveback", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyManagedExitProfile("M2K", 0.65, 1.05, 0.50, 6.0, true, 1.10, 0.25, 2);
			}
		});
		add(values, "nq_loss_cut_b6_r045", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("NQ", 6, 0.45, 0.10);
			}
		});
		add(values, "nq_loss_cut_b8_r055", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("NQ", 8, 0.55, 0.15);
			}
		});
		add(values, "nq_loss_cut_b10_r060", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("NQ", 10, 0.60, 0.15);
			}
		});
		add(values, "nq_managed_giveback", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyManagedExitProfile("NQ", 0.65, 1.05, 0.50, 6.0, true, 1.10, 0.25, 2);
			}
		});
		add(values, "nq_adaptive_loss_cut", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("NQ", WIP_SLOT);
				settings.enableAdaptiveExits = true;
				settings.adaptiveMinVolumeRatio = 1.08;
				settings.adaptiveMinBodyPct = 28.0;
				settings.adaptiveTrendTargetBoost = 0.20;
				settings.adaptiveVolumeTargetBoost = 0.15;
				settings.adaptiveBodyTargetBoost = 0.10;
				settings.adaptiveMaxRewardRisk = 2.1;
				settings.enableEarlyLossCut = true;
				settings.earlyLossCutBars = 8;
				settings.earlyLossCutR = 0.55;
				settings.earlyLossCutMinFavorableR = 0.15;
				saveTunedSettings("NQ", settings);
			}
		});
		add(values, "es_loss_cut_b6_r045", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("ES", 6, 0.45, 0.10);
			}
		});
		add(values, "es_loss_cut_b8_r055", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("ES", 8, 0.55, 0.15);
			}
		});
		add(values, "es_loss_cut_b10_r060", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("ES", 10, 0.60, 0.15);
			}
		});
		add(values, "es_managed_giveback", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyManagedExitProfile("ES", 0.65, 1.05, 0.50, 6.0, true, 1.10, 0.25, 2);
			}
		});
		add(values, "mym_loss_cut_b8_r045", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("MYM", 8, 0.45, 0.10);
			}
		});
		add(values, "mym_no_echo", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setMicroEchoMode("MYM", false, false, false);
			}
		});
		add(values, "combo_m2k_es_loss_cuts", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("M2K", 8, 0.55, 0.15);
				applyGenericLossCutProfile("ES", 8, 0.55, 0.15);
			}
		});
		add(values, "combo_nq_es_loss_cuts", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("NQ", 8, 0.55, 0.15);
				applyGenericLossCutProfile("ES", 8, 0.55, 0.15);
			}
		});
		add(values, "combo_m2k_nq_es_loss_cuts", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("M2K", 8, 0.55, 0.15);
				applyGenericLossCutProfile("NQ", 8, 0.55, 0.15);
				applyGenericLossCutProfile("ES", 8, 0.55, 0.15);
			}
		});
		add(values, "combo_m2k080_es_loss_cut", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(825.0, 0.80, 0.68, 20.0, false, false);
				applyGenericLossCutProfile("ES", 8, 0.55, 0.15);
			}
		});
		add(values, "combo_m2k080_nq_adaptive_es_loss", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(825.0, 0.80, 0.68, 20.0, false, false);
				FuturesManager.FuturesStrategySettings nq = FuturesManager.loadFuturesStrategySettings("NQ", WIP_SLOT);
				nq.enableAdaptiveExits = true;
				nq.adaptiveMinVolumeRatio = 1.08;
				nq.adaptiveMinBodyPct = 28.0;
				nq.adaptiveMaxRewardRisk = 2.1;
				nq.enableEarlyLossCut = true;
				nq.earlyLossCutBars = 8;
				nq.earlyLossCutR = 0.55;
				nq.earlyLossCutMinFavorableR = 0.15;
				saveTunedSettings("NQ", nq);
				applyGenericLossCutProfile("ES", 8, 0.55, 0.15);
			}
		});
		return values;
	}

	private static List<Scenario> phase6ContractHealthScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "mym_shadow_add_lorb_source", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setMicroShadowSourceCodes("MYM", "KREV,ORB2,SWEEP,SWEEP2,PDB,VRCL,VWAP,CMOM,LORB");
			}
		});
		add(values, "mym_shadow_add_lorb_m037", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setMicroShadowSourceCodes("MYM", "KREV,ORB2,SWEEP,SWEEP2,PDB,VRCL,VWAP,CMOM,LORB");
				setFrequencyRiskMultipliers("MYM", 0.37, 0.10, 0.10);
			}
		});
		add(values, "mym_shadow_lorb_more_slots", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroShadowQuality("MYM", false, true, 10, 570, 920, 10, 80.0, 0.80, 0.80, 0.40, 10);
				setMicroShadowSourceCodes("MYM", "KREV,ORB2,SWEEP,SWEEP2,PDB,VRCL,VWAP,CMOM,LORB");
				setFrequencyRiskMultipliers("MYM", 0.35, 0.10, 0.10);
			}
		});
		add(values, "mym_shadow_sources_short_m020", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroShadowQuality("MYM", false, true, 12, 570, 920, 10, 70.0, 0.80, 0.75, 0.30, 10);
				setMicroShadowSourceCodes("MYM", "KREV,ORB2,SWEEP,SWEEP2,PDB,VRCL,VWAP,CMOM,ORB,OMOM,MIM,AFT,FVG,LORB");
				setFrequencyRiskMultipliers("MYM", 0.20, 0.10, 0.10);
			}
		});
		add(values, "mym_shadow_sources_short_m030", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroShadowQuality("MYM", false, true, 12, 570, 920, 10, 70.0, 0.80, 0.80, 0.35, 10);
				setMicroShadowSourceCodes("MYM", "KREV,ORB2,SWEEP,SWEEP2,PDB,VRCL,VWAP,CMOM,ORB,OMOM,MIM,AFT,FVG,LORB");
				setFrequencyRiskMultipliers("MYM", 0.30, 0.10, 0.10);
			}
		});
		add(values, "mym_shadow_sources_both_m015", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroShadowQuality("MYM", true, true, 12, 570, 920, 10, 65.0, 0.78, 0.85, 0.45, 10);
				setMicroShadowSourceCodes("MYM", "KREV,ORB2,SWEEP,SWEEP2,PDB,VRCL,VWAP,CMOM,ORB,OMOM,MIM,AFT,FVG,LORB");
				setFrequencyRiskMultipliers("MYM", 0.15, 0.10, 0.10);
			}
		});
		add(values, "mym_shadow_sources_both_m020", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroShadowQuality("MYM", true, true, 12, 570, 920, 10, 65.0, 0.78, 0.90, 0.50, 10);
				setMicroShadowSourceCodes("MYM", "KREV,ORB2,SWEEP,SWEEP2,PDB,VRCL,VWAP,CMOM,ORB,OMOM,MIM,AFT,FVG,LORB");
				setFrequencyRiskMultipliers("MYM", 0.20, 0.10, 0.10);
			}
		});
		add(values, "mcl_mgc_shadow_long_m010", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroShadowQuality("MCL", true, false, 6, 570, 920, 12, 26.0, 0.75, 0.80, 0.30, 10);
				setMicroShadowSourceCodes("MCL", "KREV,ORB2,SWEEP,SWEEP2,PDB,VRCL,VWAP,CMOM,ORB,OMOM,MIM,AFT,FVG,LORB");
				setFrequencyRiskMultipliers("MCL", 0.10, 0.10, 0.10);
			}
		});
		add(values, "mcl_mgc_shadow_short_m010", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroShadowQuality("MCL", false, true, 6, 570, 920, 12, 26.0, 0.75, 0.80, 0.30, 10);
				setMicroShadowSourceCodes("MCL", "KREV,ORB2,SWEEP,SWEEP2,PDB,VRCL,VWAP,CMOM,ORB,OMOM,MIM,AFT,FVG,LORB");
				setFrequencyRiskMultipliers("MCL", 0.10, 0.10, 0.10);
			}
		});
		add(values, "mcl_mgc_shadow_both_m008", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroShadowQuality("MCL", true, true, 8, 570, 920, 12, 24.0, 0.72, 0.85, 0.40, 10);
				setMicroShadowSourceCodes("MCL", "KREV,ORB2,SWEEP,SWEEP2,PDB,VRCL,VWAP,CMOM,ORB,OMOM,MIM,AFT,FVG,LORB");
				setFrequencyRiskMultipliers("MCL", 0.08, 0.10, 0.10);
			}
		});
		add(values, "combo_mym_short_m020_mcl_long_m010", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroShadowQuality("MYM", false, true, 12, 570, 920, 10, 70.0, 0.80, 0.75, 0.30, 10);
				setMicroShadowSourceCodes("MYM", "KREV,ORB2,SWEEP,SWEEP2,PDB,VRCL,VWAP,CMOM,ORB,OMOM,MIM,AFT,FVG,LORB");
				setFrequencyRiskMultipliers("MYM", 0.20, 0.10, 0.10);
				enableMicroShadowQuality("MCL", true, false, 6, 570, 920, 12, 26.0, 0.75, 0.80, 0.30, 10);
				setMicroShadowSourceCodes("MCL", "KREV,ORB2,SWEEP,SWEEP2,PDB,VRCL,VWAP,CMOM,ORB,OMOM,MIM,AFT,FVG,LORB");
				setFrequencyRiskMultipliers("MCL", 0.10, 0.10, 0.10);
			}
		});
		add(values, "combo_mym_both_m015_mcl_both_m008", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroShadowQuality("MYM", true, true, 12, 570, 920, 10, 65.0, 0.78, 0.85, 0.45, 10);
				setMicroShadowSourceCodes("MYM", "KREV,ORB2,SWEEP,SWEEP2,PDB,VRCL,VWAP,CMOM,ORB,OMOM,MIM,AFT,FVG,LORB");
				setFrequencyRiskMultipliers("MYM", 0.15, 0.10, 0.10);
				enableMicroShadowQuality("MCL", true, true, 8, 570, 920, 12, 24.0, 0.72, 0.85, 0.40, 10);
				setMicroShadowSourceCodes("MCL", "KREV,ORB2,SWEEP,SWEEP2,PDB,VRCL,VWAP,CMOM,ORB,OMOM,MIM,AFT,FVG,LORB");
				setFrequencyRiskMultipliers("MCL", 0.08, 0.10, 0.10);
			}
		});
		return values;
	}

	private static List<Scenario> phase7ContractHealthScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "mym_idx_short_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymIndexConfirmation(3, false, true, 590, 820, 20, 12, 95.0, 0.95, 0.60, 25.0, 0.50, 35);
			}
		});
		add(values, "mym_idx_short_dense_safe", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymIndexConfirmation(4, false, true, 570, 920, 15, 10, 110.0, 0.85, 0.50, 18.0, 0.35, 40);
			}
		});
		add(values, "mym_idx_both_balanced", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymIndexConfirmation(3, true, true, 570, 920, 20, 12, 95.0, 0.88, 0.55, 20.0, 0.50, 35);
			}
		});
		add(values, "mym_idx_both_strict_body", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymIndexConfirmation(3, true, true, 580, 880, 20, 12, 95.0, 0.95, 0.65, 28.0, 0.60, 35);
			}
		});
		add(values, "mym_orb_retest_long_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymOrbRetest(1, true, false, 590, 690, 2.0, 5.0, 110.0, 0.95, 0.60, 25.0, 45);
			}
		});
		add(values, "mym_orb_retest_both_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymOrbRetest(2, true, true, 590, 720, 2.0, 6.0, 115.0, 0.92, 0.55, 22.0, 45);
			}
		});
		add(values, "mym_lorb_short_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableLateOrbContinuation("MYM", false, true, 660, 900, 2, 0.85, 90.0, 1.05, 45);
			}
		});
		add(values, "mym_lorb_both_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableLateOrbContinuation("MYM", true, true, 660, 900, 2, 0.90, 90.0, 1.05, 45);
			}
		});
		add(values, "mcl_eia_default_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclEiaContinuation(2, true, true, 626, 630, 660, 750, 1.0, 24.0, 1.50, 0.0, 0.0, 60);
			}
		});
		add(values, "mcl_eia_fast_breakout", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclEiaContinuation(2, true, true, 625, 630, 632, 660, 1.0, 22.0, 1.00, 0.60, 8.0, 30);
			}
		});
		add(values, "mcl_crude_open_long_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclCrudeSessionOpen(1, true, false, 540, 550, 551, 630, 2.0, 20.0, 1.05, 0.65, 18.0, 35);
			}
		});
		add(values, "mcl_crude_open_both_balanced", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclCrudeSessionOpen(2, true, true, 540, 550, 551, 660, 2.0, 22.0, 1.05, 0.55, 18.0, 40);
			}
		});
		add(values, "mcl_crude_open_dense_body", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclCrudeSessionOpen(3, true, true, 540, 552, 553, 675, 1.0, 26.0, 1.00, 0.35, 20.0, 55);
			}
		});
		add(values, "mcl_lorb_long_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableLateOrbContinuation("MCL", true, false, 660, 900, 2, 0.80, 42.0, 1.05, 35);
			}
		});
		add(values, "mcl_fvg_long_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableFvg("MCL", true, false, 600, 900, 2, 6.0, 32.0, 0.60, 1.05);
			}
		});
		add(values, "mcl_pdb_long_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enablePriorDayBreakout("MCL", true, false, 610, 890, 2, 18.0, 8.0, 0.70, 36.0, 1.05);
			}
		});
		add(values, "mcl_micro_scalp_strict", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroScalpQuality("MCL", true, true, 4, 600, 900, 45, 0.85, 20.0, 0.90, 26.0, 0.80, 8);
			}
		});
		add(values, "mcl_quality_size_1000", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				upsertRisk(WIP_SLOT, "MCL", 1000.0, 50);
			}
		});
		add(values, "mcl_quality_size_1100", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				upsertRisk(WIP_SLOT, "MCL", 1100.0, 50);
			}
		});
		add(values, "combo_mym_idx_short_mcl_crude_open", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymIndexConfirmation(3, false, true, 590, 820, 20, 12, 95.0, 0.95, 0.60, 25.0, 0.50, 35);
				enableMclCrudeSessionOpen(1, true, false, 540, 550, 551, 630, 2.0, 20.0, 1.05, 0.65, 18.0, 35);
			}
		});
		add(values, "combo_mym_orb2_mcl_eia", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymOrbRetest(1, true, false, 590, 690, 2.0, 5.0, 110.0, 0.95, 0.60, 25.0, 45);
				enableMclEiaContinuation(2, true, true, 626, 630, 660, 750, 1.0, 24.0, 1.50, 0.0, 0.0, 60);
			}
		});
		add(values, "combo_custom_modules_balanced", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymIndexConfirmation(3, true, true, 570, 920, 20, 12, 95.0, 0.88, 0.55, 20.0, 0.50, 35);
				enableMymOrbRetest(2, true, true, 590, 720, 2.0, 6.0, 115.0, 0.92, 0.55, 22.0, 45);
				enableMclEiaContinuation(2, true, true, 626, 630, 660, 750, 1.0, 24.0, 1.50, 0.0, 0.0, 60);
				enableMclCrudeSessionOpen(2, true, true, 540, 550, 551, 660, 2.0, 22.0, 1.05, 0.55, 18.0, 40);
			}
		});
		add(values, "combo_custom_modules_dense_safe", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymIndexConfirmation(4, false, true, 570, 920, 15, 10, 110.0, 0.85, 0.50, 18.0, 0.35, 40);
				enableMymOrbRetest(2, true, true, 590, 720, 2.0, 6.0, 115.0, 0.92, 0.55, 22.0, 45);
				enableMclEiaContinuation(2, true, true, 626, 630, 660, 750, 1.0, 24.0, 1.50, 0.0, 0.0, 60);
				enableMclCrudeSessionOpen(3, true, true, 540, 552, 553, 675, 1.0, 26.0, 1.00, 0.35, 20.0, 55);
			}
		});
		add(values, "combo_structure_plus_mcl_fvg_pdb", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymIndexConfirmation(3, false, true, 590, 820, 20, 12, 95.0, 0.95, 0.60, 25.0, 0.50, 35);
				enableMclCrudeSessionOpen(1, true, false, 540, 550, 551, 630, 2.0, 20.0, 1.05, 0.65, 18.0, 35);
				enableFvg("MCL", true, false, 600, 900, 2, 6.0, 32.0, 0.60, 1.05);
				enablePriorDayBreakout("MCL", true, false, 610, 890, 2, 18.0, 8.0, 0.70, 36.0, 1.05);
			}
		});
		return values;
	}

	private static List<Scenario> phase8ContractHealthScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "m2k_open_mult_079", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(810.0, 0.79, 0.68, 20.0, false, false);
			}
		});
		add(values, "m2k_open_mult_0785_rr070", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(815.0, 0.785, 0.70, 22.0, false, false);
			}
		});
		add(values, "m2k_disable_vpb_keep_open078", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(800.0, 0.78, 0.68, 20.0, false, false);
				disableValueArea("M2K");
			}
		});
		add(values, "m2k_soft_loss_cut_open078", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(800.0, 0.78, 0.68, 20.0, false, false);
				applyGenericLossCutProfile("M2K", 12, 0.65, 0.20);
			}
		});
		add(values, "mes_disable_shadow_avg_lift", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMesAverageWinLift(0.70, 0.78, 20.0, 600.0, true);
			}
		});
		add(values, "mes_open075_rr080", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMesAverageWinLift(0.75, 0.80, 24.0, 650.0, false);
			}
		});
		add(values, "mes_open070_rr080_no_shadow", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMesAverageWinLift(0.70, 0.80, 24.0, 650.0, true);
			}
		});
		add(values, "nq_disable_ipb_low_risk", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyNqMomentumHealth(true, 0.95, 1.05, 0.85, 40.0, false, 350.0);
			}
		});
		add(values, "nq_fvg_reward_125", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyNqFvgRewardProfile(1.25, 0.55, 58.0, 450.0, false);
			}
		});
		add(values, "nq_fvg_reward_130_low_risk", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyNqFvgRewardProfile(1.30, 0.60, 60.0, 350.0, false);
			}
		});
		add(values, "nq_fvg_adaptive_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyNqFvgRewardProfile(1.25, 0.60, 58.0, 400.0, true);
			}
		});
		add(values, "es_fvg_pdb_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyEsQualityExpansion(500.0, true, true, false);
			}
		});
		add(values, "es_vwap_runner_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyEsVwapHealth(0.85, 6.0, 36.0, 1.35, true);
			}
		});
		add(values, "combo_m2k079_mes_no_shadow", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(810.0, 0.79, 0.68, 20.0, false, false);
				applyMesAverageWinLift(0.70, 0.78, 20.0, 600.0, true);
			}
		});
		add(values, "combo_m2k079_nq_fvg125", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(810.0, 0.79, 0.68, 20.0, false, false);
				applyNqFvgRewardProfile(1.25, 0.55, 58.0, 450.0, false);
			}
		});
		add(values, "combo_m2k079_es_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(810.0, 0.79, 0.68, 20.0, false, false);
				applyEsQualityExpansion(500.0, true, true, false);
			}
		});
		add(values, "combo_m2k079_mes_nq", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(810.0, 0.79, 0.68, 20.0, false, false);
				applyMesAverageWinLift(0.70, 0.78, 20.0, 600.0, true);
				applyNqFvgRewardProfile(1.25, 0.55, 58.0, 450.0, false);
			}
		});
		add(values, "combo_laggards_balanced", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(810.0, 0.79, 0.68, 20.0, false, false);
				applyMesAverageWinLift(0.70, 0.78, 20.0, 600.0, true);
				applyNqFvgRewardProfile(1.25, 0.55, 58.0, 450.0, false);
				applyEsQualityExpansion(500.0, true, true, false);
			}
		});
		return values;
	}

	private static List<Scenario> phase9ContractHealthScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "m2k_loss_cut_b10_r060", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("M2K", 10, 0.60, 0.20);
			}
		});
		add(values, "m2k_loss_cut_b14_r070", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("M2K", 14, 0.70, 0.20);
			}
		});
		add(values, "m2k_loss_cut_b16_r075", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("M2K", 16, 0.75, 0.20);
			}
		});
		add(values, "m2k_managed_giveback_soft", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyManagedExitProfile("M2K", 0.75, 1.15, 0.55, 8.0, true, 1.25, 0.35, 3);
			}
		});
		add(values, "nq_quality_mode_trim_ipb", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyNqMomentumHealth(true, 0.95, 1.05, 0.85, 40.0, false, 350.0);
			}
		});
		add(values, "nq_quality_mode_trim_ipb_m2k_b10", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("M2K", 10, 0.60, 0.20);
				applyNqMomentumHealth(true, 0.95, 1.05, 0.85, 40.0, false, 350.0);
			}
		});
		add(values, "es_vwap_quality_mode", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyEsVwapHealth(0.85, 6.0, 36.0, 1.35, true);
			}
		});
		add(values, "mes_high_profit_guarded", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMesAverageWinLift(0.75, 0.80, 24.0, 650.0, false);
			}
		});
		add(values, "mes_high_profit_guarded_m2k_b10", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyGenericLossCutProfile("M2K", 10, 0.60, 0.20);
				applyMesAverageWinLift(0.75, 0.80, 24.0, 650.0, false);
			}
		});
		add(values, "nq_es_quality_stack", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyNqMomentumHealth(true, 0.95, 1.05, 0.85, 40.0, false, 350.0);
				applyEsVwapHealth(0.85, 6.0, 36.0, 1.35, true);
			}
		});
		add(values, "quality_profit_combo_mes_nq", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMesAverageWinLift(0.75, 0.80, 24.0, 650.0, false);
				applyNqMomentumHealth(true, 0.95, 1.05, 0.85, 40.0, false, 350.0);
			}
		});
		return values;
	}

	private static List<Scenario> phase10ContractHealthScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "mym_breadth_both_balanced", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymBreadthConfirmation(6, true, true, 585, 900, 18, 12, 2, 95.0, 0.95, 0.65, 22.0, 0.75, 35);
			}
		});
		add(values, "mym_breadth_short_dense", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymBreadthConfirmation(8, false, true, 575, 915, 14, 10, 2, 105.0, 0.90, 0.58, 18.0, 0.55, 32);
			}
		});
		add(values, "mym_breadth_long_balanced", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymBreadthConfirmation(6, true, false, 585, 870, 18, 12, 2, 90.0, 1.00, 0.62, 20.0, 0.70, 38);
			}
		});
		add(values, "mym_breadth_both_strict", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymBreadthConfirmation(5, true, true, 590, 860, 20, 14, 3, 85.0, 1.05, 0.70, 26.0, 0.90, 42);
			}
		});
		add(values, "mym_breadth_wider_window_guarded", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymBreadthConfirmation(10, true, true, 570, 920, 12, 8, 2, 100.0, 0.85, 0.55, 16.0, 0.45, 28);
				applyLossCutProfile("MYM", 8, 0.55, 0.15);
			}
		});
		add(values, "mcl_trend_both_balanced", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclTrendContinuation(6, true, true, 570, 900, 30, 12, 1.0, 18.0, 30.0, 1.10, 0.70, 18.0, 0.60, 40);
			}
		});
		add(values, "mcl_trend_long_balanced", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclTrendContinuation(6, true, false, 570, 900, 25, 12, 1.0, 16.0, 28.0, 1.15, 0.65, 16.0, 0.55, 42);
			}
		});
		add(values, "mcl_trend_short_guarded", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclTrendContinuation(5, false, true, 570, 870, 25, 12, 1.0, 18.0, 28.0, 1.05, 0.72, 20.0, 0.70, 35);
			}
		});
		add(values, "mcl_trend_dense_guarded", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclTrendContinuation(10, true, true, 555, 915, 15, 8, 0.5, 14.0, 26.0, 0.95, 0.58, 14.0, 0.40, 28);
				applyLossCutProfile("MCL", 8, 0.55, 0.15);
			}
		});
		add(values, "mym_breadth_mcl_trend_balanced", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymBreadthConfirmation(6, true, true, 585, 900, 18, 12, 2, 95.0, 0.95, 0.65, 22.0, 0.75, 35);
				enableMclTrendContinuation(6, true, true, 570, 900, 30, 12, 1.0, 18.0, 30.0, 1.10, 0.70, 18.0, 0.60, 40);
			}
		});
		add(values, "mym_short_mcl_long_density", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymBreadthConfirmation(8, false, true, 575, 915, 14, 10, 2, 105.0, 0.90, 0.58, 18.0, 0.55, 32);
				enableMclTrendContinuation(6, true, false, 570, 900, 25, 12, 1.0, 16.0, 28.0, 1.15, 0.65, 16.0, 0.55, 42);
			}
		});
		add(values, "mym_wide_mcl_dense_guarded", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymBreadthConfirmation(10, true, true, 570, 920, 12, 8, 2, 100.0, 0.85, 0.55, 16.0, 0.45, 28);
				enableMclTrendContinuation(10, true, true, 555, 915, 15, 8, 0.5, 14.0, 26.0, 0.95, 0.58, 14.0, 0.40, 28);
				applyLossCutProfile("BOTH", 8, 0.55, 0.15);
			}
		});
		add(values, "mym_breadth_plus_existing_structure", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymBreadthConfirmation(6, true, true, 585, 900, 18, 12, 2, 95.0, 0.95, 0.65, 22.0, 0.75, 35);
				enableMymIndexConfirmation(3, false, true, 590, 820, 20, 12, 95.0, 0.95, 0.60, 25.0, 0.50, 35);
				enableMymOrbRetest(2, true, true, 590, 720, 2.0, 6.0, 115.0, 0.92, 0.55, 22.0, 45);
			}
		});
		add(values, "mcl_trend_plus_crude_structure", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclTrendContinuation(6, true, true, 570, 900, 30, 12, 1.0, 18.0, 30.0, 1.10, 0.70, 18.0, 0.60, 40);
				enableMclCrudeSessionOpen(2, true, true, 540, 550, 551, 660, 2.0, 22.0, 1.05, 0.55, 18.0, 40);
				enableMclEiaContinuation(2, true, true, 626, 630, 660, 750, 1.0, 24.0, 1.50, 0.0, 0.0, 60);
			}
		});
		add(values, "new_contract_breadth_trend_full_stack", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymBreadthConfirmation(8, true, true, 575, 915, 14, 10, 2, 100.0, 0.92, 0.58, 18.0, 0.55, 32);
				enableMclTrendContinuation(8, true, true, 560, 900, 20, 10, 0.5, 16.0, 28.0, 1.00, 0.62, 16.0, 0.50, 35);
				enableMymIndexConfirmation(3, false, true, 590, 820, 20, 12, 95.0, 0.95, 0.60, 25.0, 0.50, 35);
				enableMclCrudeSessionOpen(2, true, true, 540, 550, 551, 660, 2.0, 22.0, 1.05, 0.55, 18.0, 40);
				applyLossCutProfile("BOTH", 10, 0.60, 0.20);
			}
		});
		return values;
	}

	private static List<Scenario> contractHealthScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "clean_mym_mcl_wft_bad_addons", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", true, false, true);
				setWinnerFollowThroughMode("MCL", false, false, false);
			}
		});
		add(values, "clean_all_new_contract_wft", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", false, false, false);
				setWinnerFollowThroughMode("MCL", false, false, false);
			}
		});
		add(values, "clean_wft_mym_giveback_fast", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", true, false, true);
				setWinnerFollowThroughMode("MCL", false, false, false);
				applyManagedExitProfile("MYM", 0.65, 1.00, 0.45, 6.0, true, 0.85, 0.30, 2);
			}
		});
		add(values, "clean_wft_mym_losscut8", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", true, false, true);
				setWinnerFollowThroughMode("MCL", false, false, false);
				applyLossCutProfile("MYM", 8, 0.50, 0.10);
			}
		});
		add(values, "clean_wft_mym_shadow_short_strict", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", true, false, true);
				setWinnerFollowThroughMode("MCL", false, false, false);
				enableMicroShadowQuality("MYM", false, true, 4, 570, 920, 20, 70.0, 0.85, 0.75, 0.50, 12);
			}
		});
		add(values, "clean_wft_mym_shadow_short_dense", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", true, false, true);
				setWinnerFollowThroughMode("MCL", false, false, false);
				enableMicroShadowQuality("MYM", false, true, 8, 570, 920, 12, 80.0, 0.80, 0.65, 0.25, 10);
			}
		});
		add(values, "clean_wft_mym_shadow_short_no_echo", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", false, false, false);
				setWinnerFollowThroughMode("MCL", false, false, false);
				setMicroEchoMode("MYM", false, false, false);
				setMicroEchoMode("MCL", false, false, false);
				enableMicroShadowQuality("MYM", false, true, 8, 570, 920, 12, 80.0, 0.80, 0.65, 0.25, 10);
			}
		});
		add(values, "clean_wft_disable_nq_orb_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", true, false, true);
				setWinnerFollowThroughMode("MCL", false, false, false);
				disableNqOrbLongs();
			}
		});
		add(values, "clean_wft_es_losscut", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", true, false, true);
				setWinnerFollowThroughMode("MCL", false, false, false);
				applyGenericLossCutProfile("ES", 10, 0.55, 0.15);
			}
		});
		add(values, "clean_wft_nq_orb_es_losscut", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", true, false, true);
				setWinnerFollowThroughMode("MCL", false, false, false);
				disableNqOrbLongs();
				applyGenericLossCutProfile("ES", 10, 0.55, 0.15);
			}
		});
		add(values, "clean_wft_mes_disable_shadow", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", true, false, true);
				setWinnerFollowThroughMode("MCL", false, false, false);
				disableMicroShadow("MES");
			}
		});
		add(values, "wft_quality_mym_mcl", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableWinnerFollowThroughQuality("MYM", true, true, "ORB,OMOM,CMOM,SWEEP", 45.0, 0.85, 4, 150.0, 0.70, 25.0, 0.58, 0.42, 10);
				enableWinnerFollowThroughQuality("MCL", true, true, "ORB,AFT,CMOM,MIM", 20.0, 0.90, 4, 175.0, 0.70, 18.0, 0.56, 0.44, 10);
			}
		});
		add(values, "wft_quality_mym_mcl_off", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableWinnerFollowThroughQuality("MYM", true, true, "ORB,OMOM,CMOM,SWEEP", 45.0, 0.85, 4, 150.0, 0.70, 25.0, 0.58, 0.42, 10);
				setWinnerFollowThroughMode("MCL", false, false, false);
			}
		});
		add(values, "wft_quality_mym_mcl_off_nq_orb", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableWinnerFollowThroughQuality("MYM", true, true, "ORB,OMOM,CMOM,SWEEP", 45.0, 0.85, 4, 150.0, 0.70, 25.0, 0.58, 0.42, 10);
				setWinnerFollowThroughMode("MCL", false, false, false);
				disableNqOrbLongs();
			}
		});
		add(values, "wft_quality_short_shadow_nq_orb", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", true, false, true);
				setWinnerFollowThroughMode("MCL", false, false, false);
				enableMicroShadowQuality("MYM", false, true, 4, 570, 920, 20, 70.0, 0.85, 0.75, 0.50, 12);
				disableNqOrbLongs();
			}
		});
		add(values, "clean_wft_nq_orb_mym800_mcl900", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", true, false, true);
				setWinnerFollowThroughMode("MCL", false, false, false);
				disableNqOrbLongs();
				upsertRisk(WIP_SLOT, "MYM", 800.0, 50);
				upsertRisk(WIP_SLOT, "MCL", 900.0, 50);
			}
		});
		add(values, "clean_wft_nq_orb_mym1000_mcl1000", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", true, false, true);
				setWinnerFollowThroughMode("MCL", false, false, false);
				disableNqOrbLongs();
				upsertRisk(WIP_SLOT, "MYM", 1000.0, 50);
				upsertRisk(WIP_SLOT, "MCL", 1000.0, 50);
			}
		});
		add(values, "shadow_short_nq_orb_mym800_mcl900", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", true, false, true);
				setWinnerFollowThroughMode("MCL", false, false, false);
				enableMicroShadowQuality("MYM", false, true, 4, 570, 920, 20, 70.0, 0.85, 0.75, 0.50, 12);
				disableNqOrbLongs();
				upsertRisk(WIP_SLOT, "MYM", 800.0, 50);
				upsertRisk(WIP_SLOT, "MCL", 900.0, 50);
			}
		});
		add(values, "shadow_short_nq_orb_mym1000_mcl1000", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				setWinnerFollowThroughMode("MYM", true, false, true);
				setWinnerFollowThroughMode("MCL", false, false, false);
				enableMicroShadowQuality("MYM", false, true, 4, 570, 920, 20, 70.0, 0.85, 0.75, 0.50, 12);
				disableNqOrbLongs();
				upsertRisk(WIP_SLOT, "MYM", 1000.0, 50);
				upsertRisk(WIP_SLOT, "MCL", 1000.0, 50);
			}
		});
		return values;
	}

	private static List<Scenario> m2kRefinementScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "m2k_soft_r500_m055_rr060_b12", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(500.0, 0.55, 0.60, 12.0, false, false);
			}
		});
		add(values, "m2k_soft_r550_m060_rr060_b12", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(550.0, 0.60, 0.60, 12.0, false, false);
			}
		});
		add(values, "m2k_mid_r600_m065_rr062_b14", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(600.0, 0.65, 0.62, 14.0, false, false);
			}
		});
		add(values, "m2k_best_r650_m070_rr065_b18", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(650.0, 0.70, 0.65, 18.0, false, false);
			}
		});
		add(values, "m2k_mid_plus_wft_confirmed", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(600.0, 0.65, 0.62, 14.0, false, false);
				enableWinnerFollowThroughQuality("MYM", true, true, "ORB,OMOM,CMOM,SWEEP", 90.0, 0.85, 4, 45.0, 0.65, 18.0, 0.54, 0.46, 12);
				enableWinnerFollowThroughQuality("MCL", true, true, "ORB,AFT,CMOM,MIM", 30.0, 0.90, 4, 75.0, 0.70, 16.0, 0.55, 0.45, 14);
			}
		});
		add(values, "m2k_best_plus_wft_confirmed", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(650.0, 0.70, 0.65, 18.0, false, false);
				enableWinnerFollowThroughQuality("MYM", true, true, "ORB,OMOM,CMOM,SWEEP", 90.0, 0.85, 4, 45.0, 0.65, 18.0, 0.54, 0.46, 12);
				enableWinnerFollowThroughQuality("MCL", true, true, "ORB,AFT,CMOM,MIM", 30.0, 0.90, 4, 75.0, 0.70, 16.0, 0.55, 0.45, 14);
			}
		});
		add(values, "m2k_best_plus_wft_echo_probe", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(650.0, 0.70, 0.65, 18.0, false, false);
				enableWinnerFollowThroughQuality("MYM", true, true, "ORB,OMOM,CMOM,SWEEP", 70.0, 0.70, 8, 0.0, 0.55, 10.0, 0.52, 0.48, 8);
				enableWinnerFollowThroughQuality("MCL", true, true, "ORB,AFT,CMOM,MIM", 26.0, 0.75, 8, 0.0, 0.55, 8.0, 0.52, 0.48, 8);
				enableMicroEchoQuality("MYM", true, true, 8, 570, 920, 8, 2, 3, 70.0, 0.70, 0.55, 0.0);
				enableMicroEchoQuality("MCL", true, true, 8, 570, 920, 8, 2, 3, 28.0, 0.75, 0.55, 0.0);
			}
		});
		add(values, "m2k_best_plus_nq_keep_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(650.0, 0.70, 0.65, 18.0, false, false);
				FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("NQ", WIP_SLOT);
				settings.fvgRewardRisk = Math.max(settings.fvgRewardRisk, 1.20);
				settings.marketImpulsePullbackRewardRisk = Math.max(settings.marketImpulsePullbackRewardRisk, 0.90);
				saveTunedSettings("NQ", settings);
			}
		});
		add(values, "m2k_best_plus_es_wft_only", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(650.0, 0.70, 0.65, 18.0, false, false);
				enableWinnerFollowThroughQuality("ES", true, true, "ORB,VWAP,SWEEP", 32.0, 0.90, 2, 150.0, 0.75, 18.0, 0.55, 0.45, 12);
			}
		});
		add(values, "m2k_best_plus_micro_count_minimal", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(650.0, 0.70, 0.65, 18.0, false, false);
				enableMicroEchoQuality("MYM", true, true, 4, 570, 900, 12, 3, 2, 65.0, 0.75, 0.65, 0.25);
				enableMicroEchoQuality("MCL", true, true, 4, 570, 900, 12, 3, 2, 26.0, 0.80, 0.65, 0.25);
			}
		});
		add(values, "m2k_best_plus_mym_index_shadow_strict", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(650.0, 0.70, 0.65, 18.0, false, false);
				enableMicroShadowQuality("MYM", true, true, 4, 570, 920, 20, 70.0, 0.85, 0.75, 0.50, 12);
			}
		});
		add(values, "m2k_best_plus_mym_index_shadow_dense", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(650.0, 0.70, 0.65, 18.0, false, false);
				enableMicroShadowQuality("MYM", true, true, 8, 570, 920, 12, 80.0, 0.80, 0.65, 0.25, 10);
			}
		});
		add(values, "m2k_best_plus_mym_index_shadow_wft_echo", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(650.0, 0.70, 0.65, 18.0, false, false);
				enableMicroShadowQuality("MYM", true, true, 8, 570, 920, 12, 80.0, 0.80, 0.65, 0.25, 10);
				enableWinnerFollowThroughQuality("MYM", true, true, "ORB,OMOM,CMOM,SWEEP,SHDW", 70.0, 0.70, 8, 0.0, 0.55, 10.0, 0.52, 0.48, 8);
				enableMicroEchoQuality("MYM", true, true, 8, 570, 920, 8, 2, 3, 70.0, 0.70, 0.55, 0.0);
			}
		});
		return values;
	}

	private static List<Scenario> deficiencyResearchScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "new_contract_wft_after_confirmed_winners", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableWinnerFollowThroughQuality("MYM", true, true, "ORB,OMOM,CMOM,SWEEP", 90.0, 0.85, 4, 45.0, 0.65, 18.0, 0.54, 0.46, 12);
				enableWinnerFollowThroughQuality("MCL", true, true, "ORB,AFT,CMOM,MIM", 30.0, 0.90, 4, 75.0, 0.70, 16.0, 0.55, 0.45, 14);
			}
		});
		add(values, "new_contract_wft_echo_count_probe", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableWinnerFollowThroughQuality("MYM", true, true, "ORB,OMOM,CMOM,SWEEP", 70.0, 0.70, 8, 0.0, 0.55, 10.0, 0.52, 0.48, 8);
				enableWinnerFollowThroughQuality("MCL", true, true, "ORB,AFT,CMOM,MIM", 26.0, 0.75, 8, 0.0, 0.55, 8.0, 0.52, 0.48, 8);
				enableMicroEchoQuality("MYM", true, true, 8, 570, 920, 8, 2, 3, 70.0, 0.70, 0.55, 0.0);
				enableMicroEchoQuality("MCL", true, true, 8, 570, 920, 8, 2, 3, 28.0, 0.75, 0.55, 0.0);
			}
		});
		add(values, "new_contract_strict_breakout_adds", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enablePriorDayBreakout("MYM", true, true, 610, 890, 2, 95.0, 22.0, 0.85, 95.0, 1.0);
				enableFvg("MYM", true, false, 600, 900, 2, 12.0, 85.0, 0.70, 1.0);
				enablePriorDayBreakout("MCL", true, false, 610, 890, 2, 18.0, 8.0, 0.70, 36.0, 1.0);
				enableFvg("MCL", true, false, 600, 900, 2, 6.0, 32.0, 0.60, 1.0);
			}
		});
		add(values, "new_contract_momentum_density_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				boostNewContractMomentumQuality(800.0, 1100.0, 0.90, 0.95, 25.0, 18.0);
			}
		});
		add(values, "new_contract_momentum_density_with_wft", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				boostNewContractMomentumQuality(800.0, 1100.0, 0.90, 0.95, 25.0, 18.0);
				enableWinnerFollowThroughQuality("MYM", true, true, "ORB,OMOM,CMOM,SWEEP", 80.0, 0.80, 5, 35.0, 0.65, 14.0, 0.54, 0.46, 10);
				enableWinnerFollowThroughQuality("MCL", true, true, "ORB,AFT,CMOM,MIM", 30.0, 0.85, 5, 60.0, 0.65, 12.0, 0.54, 0.46, 10);
			}
		});
		add(values, "new_contract_strict_micro_scalp_probe", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMicroScalpQuality("MYM", true, true, 4, 600, 900, 45, 0.90, 55.0, 0.85, 30.0, 1.0, 8);
				enableMicroScalpQuality("MCL", true, true, 4, 600, 900, 45, 0.85, 20.0, 0.85, 26.0, 0.8, 8);
			}
		});
		add(values, "m2k_scale_avg_profit_v1", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(650.0, 0.70, 0.65, 18.0, false, false);
			}
		});
		add(values, "m2k_scale_avg_profit_v2_losscut", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(800.0, 0.85, 0.72, 24.0, true, true);
			}
		});
		add(values, "m2k_filter_noise_scale_winners", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(800.0, 0.85, 0.70, 28.0, true, true);
				disableValueArea("M2K");
			}
		});
		add(values, "mes_scale_avg_profit_v1", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMesProfitScale(550.0, 0.62, 0.72, 18.0, false);
			}
		});
		add(values, "mes_scale_avg_profit_no_shadow", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMesProfitScale(650.0, 0.75, 0.75, 24.0, true);
			}
		});
		add(values, "nq_quality_profit_repair", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyNqQualityRepair(false, false, 1.25, 0.95, 450.0);
			}
		});
		add(values, "nq_remove_low_win_long_noise", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyNqQualityRepair(true, true, 1.20, 1.00, 450.0);
			}
		});
		add(values, "es_quality_expansion_fvg_pdb", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyEsQualityExpansion(500.0, true, true, false);
			}
		});
		add(values, "es_quality_expansion_wft", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyEsQualityExpansion(500.0, true, false, true);
			}
		});
		add(values, "portfolio_m2k_mes_scale_combo", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyM2kProfitScale(650.0, 0.70, 0.65, 18.0, false, false);
				applyMesProfitScale(550.0, 0.62, 0.72, 18.0, false);
			}
		});
		add(values, "portfolio_new_contracts_m2k_scale_combo", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				boostNewContractMomentumQuality(800.0, 1100.0, 0.90, 0.95, 25.0, 18.0);
				applyM2kProfitScale(650.0, 0.70, 0.65, 18.0, false, false);
			}
		});
		add(values, "portfolio_new_contracts_wft_m2k_mes", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableWinnerFollowThroughQuality("MYM", true, true, "ORB,OMOM,CMOM,SWEEP", 80.0, 0.80, 5, 35.0, 0.65, 14.0, 0.54, 0.46, 10);
				enableWinnerFollowThroughQuality("MCL", true, true, "ORB,AFT,CMOM,MIM", 30.0, 0.85, 5, 60.0, 0.65, 12.0, 0.54, 0.46, 10);
				applyM2kProfitScale(650.0, 0.70, 0.65, 18.0, false, false);
				applyMesProfitScale(550.0, 0.62, 0.72, 18.0, false);
			}
		});
		add(values, "portfolio_full_laggard_quality_repair", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				boostNewContractMomentumQuality(800.0, 1100.0, 0.90, 0.95, 25.0, 18.0);
				applyM2kProfitScale(650.0, 0.70, 0.65, 18.0, false, false);
				applyMesProfitScale(550.0, 0.62, 0.72, 18.0, false);
				applyNqQualityRepair(false, false, 1.25, 0.95, 450.0);
				applyEsQualityExpansion(500.0, true, true, true);
			}
		});
		return values;
	}

	private static List<Scenario> customModuleResearchScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "mcl_eia_immediate_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclEiaContinuation(1, true, false, 625, 630, 632, 655, 1.0, 18.0, 0.90, 0.75, 12.0, 24);
			}
		});
		add(values, "mcl_eia_immediate_both", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclEiaContinuation(2, true, true, 625, 630, 632, 655, 1.0, 20.0, 0.90, 0.75, 12.0, 24);
			}
		});
		add(values, "mcl_eia_25min_breakout", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclEiaContinuation(2, true, true, 625, 630, 632, 660, 1.0, 22.0, 1.00, 0.60, 8.0, 30);
			}
		});
		add(values, "mcl_eia_late_momentum", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclEiaContinuation(1, true, true, 630, 660, 850, 925, 1.0, 24.0, 0.90, 0.45, 8.0, 30);
			}
		});
		add(values, "mcl_crude_open_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclCrudeSessionOpen(1, true, false, 540, 550, 551, 630, 2.0, 20.0, 0.95, 0.65, 18.0, 35);
			}
		});
		add(values, "mcl_crude_open_both", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclCrudeSessionOpen(2, true, true, 540, 550, 551, 660, 2.0, 22.0, 0.95, 0.55, 15.0, 40);
			}
		});
		add(values, "mym_index_confirmation_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymIndexConfirmation(2, true, false, 570, 900, 20, 12, 95.0, 0.90, 0.55, 20.0, 0.5, 35);
			}
		});
		add(values, "mym_index_confirmation_both", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymIndexConfirmation(3, true, true, 570, 920, 20, 12, 95.0, 0.85, 0.50, 18.0, 0.5, 35);
			}
		});
		add(values, "mym_orb_retest_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymOrbRetest(1, true, false, 590, 690, 2.0, 5.0, 110.0, 0.90, 0.55, 20.0, 45);
			}
		});
		add(values, "mym_orb_retest_both", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMymOrbRetest(2, true, true, 590, 720, 2.0, 6.0, 115.0, 0.90, 0.50, 18.0, 45);
			}
		});
		add(values, "mcl_eia_mym_idx_combo", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclEiaContinuation(2, true, true, 625, 630, 632, 660, 1.0, 22.0, 1.00, 0.60, 8.0, 30);
				enableMymIndexConfirmation(2, true, false, 570, 900, 20, 12, 95.0, 0.90, 0.55, 20.0, 0.5, 35);
			}
		});
		add(values, "mcl_open_mym_retest_combo", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclCrudeSessionOpen(2, true, true, 540, 550, 551, 660, 2.0, 22.0, 0.95, 0.55, 15.0, 40);
				enableMymOrbRetest(2, true, true, 590, 720, 2.0, 6.0, 115.0, 0.90, 0.50, 18.0, 45);
			}
		});
		add(values, "all_custom_modules_controlled", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				enableMclEiaContinuation(2, true, true, 625, 630, 632, 660, 1.0, 22.0, 1.00, 0.60, 8.0, 30);
				enableMclCrudeSessionOpen(1, true, false, 540, 550, 551, 630, 2.0, 20.0, 0.95, 0.65, 18.0, 35);
				enableMymIndexConfirmation(2, true, false, 570, 900, 20, 12, 95.0, 0.90, 0.55, 20.0, 0.5, 35);
				enableMymOrbRetest(1, true, false, 590, 690, 2.0, 5.0, 110.0, 0.90, 0.55, 20.0, 45);
			}
		});
		return values;
	}

	private static List<Scenario> exitComboScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "mym_stop65_mcl_giveback115_25_b2", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				applyManagedExitProfile("MYM", 0.65, 1.00, 0.45, 6.0, false, 0.95, 0.45, 3);
				applyManagedExitProfile("MCL", 0.75, 1.15, 0.55, 8.0, true, 1.15, 0.25, 2);
			}
		});
		add(values, "mym_stop65_mcl_giveback115_35_b2", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				applyManagedExitProfile("MYM", 0.65, 1.00, 0.45, 6.0, false, 0.95, 0.45, 3);
				applyManagedExitProfile("MCL", 0.75, 1.15, 0.55, 8.0, true, 1.15, 0.35, 2);
			}
		});
		add(values, "mym_stop65_mcl_giveback095_25_b2", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				applyManagedExitProfile("MYM", 0.65, 1.00, 0.45, 6.0, false, 0.95, 0.45, 3);
				applyManagedExitProfile("MCL", 0.75, 1.15, 0.55, 8.0, true, 0.95, 0.25, 2);
			}
		});
		add(values, "mym_stop65_only", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				applyManagedExitProfile("MYM", 0.65, 1.00, 0.45, 6.0, false, 0.95, 0.45, 3);
			}
		});
		add(values, "mcl_giveback115_25_only", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				applyManagedExitProfile("MCL", 0.75, 1.15, 0.55, 8.0, true, 1.15, 0.25, 2);
			}
		});
		return values;
	}

	private static List<Scenario> exitDisciplineScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "current_best_micro_stack", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
			}
		});
		for (final String symbol : new String[] {"MYM", "MCL", "BOTH"}) {
			for (final double trigger : new double[] {0.75, 0.95, 1.15}) {
				for (final double giveback : new double[] {0.25, 0.35, 0.45}) {
					for (final int minBars : new int[] {2, 4}) {
						add(values, "giveback_" + symbol.toLowerCase() + "_t" + compact(trigger) + "_g" + compact(giveback) + "_b" + minBars, new ScenarioAction() {
							@Override
							public void apply() throws Exception {
								applyProfitHighAft20Mim();
								applyManagedExitProfile(symbol, 0.75, 1.15, 0.55, 8.0, true, trigger, giveback, minBars);
							}
						});
					}
				}
			}
		}
		for (final String symbol : new String[] {"MYM", "MCL", "BOTH"}) {
			for (final double[] profile : new double[][] {
				{0.55, 0.90, 0.35, 4.0},
				{0.65, 1.00, 0.45, 6.0},
				{0.75, 1.15, 0.55, 8.0}
			}) {
				add(values, "stop_" + symbol.toLowerCase() + "_be" + compact(profile[0]) + "_tr" + compact(profile[1]) + "_d" + compact(profile[2]), new ScenarioAction() {
					@Override
					public void apply() throws Exception {
						applyProfitHighAft20Mim();
						applyManagedExitProfile(symbol, profile[0], profile[1], profile[2], profile[3], false, 0.95, 0.45, 3);
					}
				});
				add(values, "stop_giveback_" + symbol.toLowerCase() + "_be" + compact(profile[0]) + "_tr" + compact(profile[1]) + "_d" + compact(profile[2]), new ScenarioAction() {
					@Override
					public void apply() throws Exception {
						applyProfitHighAft20Mim();
						applyManagedExitProfile(symbol, profile[0], profile[1], profile[2], profile[3], true, 0.95, 0.35, 3);
					}
				});
			}
		}
		for (final String symbol : new String[] {"MYM", "MCL", "BOTH"}) {
			for (final int bars : new int[] {5, 8, 10}) {
				for (final double cutR : new double[] {0.45, 0.60}) {
					add(values, "losscut_" + symbol.toLowerCase() + "_b" + bars + "_r" + compact(cutR), new ScenarioAction() {
						@Override
						public void apply() throws Exception {
							applyProfitHighAft20Mim();
							applyLossCutProfile(symbol, bars, cutR, 0.15);
						}
					});
				}
			}
		}
		return values;
	}

	private static void applyExitDisciplineScenarioByName(String name, List<Scenario> scenarios) throws Exception {
		for (Scenario scenario : scenarios) {
			if (scenario.name().equals(name)) {
				scenario.apply();
				return;
			}
		}
		throw new IllegalArgumentException("Unknown exit discipline scenario " + name);
	}

	private static boolean isGuardedScenario(String name) {
		return "strict_base".equals(name)
			|| "strict_mcl_mim_long_only_strict".equals(name)
			|| "strict_mym_sweep_short_only".equals(name)
			|| "strict_mym_sweep_short_mcl_mim_long".equals(name)
			|| "strict_mym_sweep_short_rsi70".equals(name)
				|| "strict_mym_sweep_short_rsi68_mcl_mim_strict".equals(name)
				|| "strict_mym_sweep_short_rsi70_mcl_mim_strict".equals(name)
				|| "strict_mym_sweep_short_body8_mcl_mim_strict".equals(name)
				|| "strict_mym_sweep_short_body10_mcl_mim_strict".equals(name)
				|| "strict_mym_sweep_short_body12_mcl_mim_strict".equals(name)
				|| "strict_mym_sweep_short_body15_mcl_mim_strict".equals(name)
				|| "quality_no_orb_body45_sweep_body10_mcl_mim_strict".equals(name)
				|| "quality_high_body45_sweep_body10_mcl_mim_strict".equals(name)
				|| "quality_high_body45_sweep_body10_mcl_mim_exit_discipline".equals(name)
				|| "strict_mym_sweep_body10_mcl_aft20_mim_strict".equals(name)
				|| "quality_no_orb_body45_sweep_body10_mcl_aft20_mim".equals(name)
				|| "quality_high_body45_sweep_body10_mcl_aft20_mim".equals(name)
				|| "strict_combo_mcl_r1100".equals(name)
				|| "strict_combo_mcl_r1400".equals(name)
				|| "strict_combo_mym_r800".equals(name)
			|| "strict_combo_mym800_mcl1100".equals(name)
			|| "high_body45_sweep_rsi70_mcl_mim_strict".equals(name);
	}

	private static List<CandidateResult> runNamedCandidates(String symbol, List<Scenario> scenarios, String endDate, String prefix) throws Exception {
		List<CandidateResult> results = new ArrayList<CandidateResult>();
		for (Scenario scenario : scenarios) {
			seedPresetRows();
			applyAcceptedWipTrim();
			if ("MYM".equals(symbol)) {
				applyDisabled("MCL");
			} else {
				applyDisabled("MYM");
			}
			scenario.apply();
			Summary summary = runPortfolio("solo_" + scenario.name(), symbol, endDate);
			results.add(new CandidateResult(symbol, scenario, summary));
			System.out.println(prefix + " " + line(summary));
			if (summary.trades > 0 && summary.pnl > 0.0) {
				printNewSymbolStrategyBreakdown(summary.id);
			}
		}
		return results;
	}

	private static void applyDeepValidationByName(String name, List<CandidateResult> mymTop, List<CandidateResult> mclTop) throws Exception {
		if ("deep_pair_current_best_micro_stack".equals(name)) {
			applyCurrentBestMicroStack();
			return;
		}
		for (CandidateResult mym : mymTop) {
			for (CandidateResult mcl : mclTop) {
				String pairName = "deep_pair_" + mym.scenario.name() + "__" + mcl.scenario.name();
				if (pairName.equals(name)) {
					mym.scenario.apply();
					mcl.scenario.apply();
					return;
				}
			}
		}
		throw new IllegalArgumentException("Unknown deep validation pair " + name);
	}

	private static void runIndividualSweep(String endDate) throws Exception {
		seedPresetRows();
		applyAcceptedWipTrim();
		Summary baseline = runPortfolio("baseline_6_wip", BASE_SYMBOLS, endDate);
		System.out.println("BASELINE " + line(baseline));

		List<CandidateResult> mymResults = runIndividualCandidates("MYM", endDate);
		List<CandidateResult> mclResults = runIndividualCandidates("MCL", endDate);
		List<CandidateResult> mymTop = topIndividual(mymResults, 4);
		List<CandidateResult> mclTop = topIndividual(mclResults, 4);

		System.out.println("TOP_INDIVIDUAL MYM");
		printCandidateRankings(mymTop);
		System.out.println("TOP_INDIVIDUAL MCL");
		printCandidateRankings(mclTop);

		List<Summary> validations = new ArrayList<Summary>();
		for (CandidateResult mym : mymTop) {
			for (CandidateResult mcl : mclTop) {
				seedPresetRows();
				applyAcceptedWipTrim();
				mym.scenario.apply();
				mcl.scenario.apply();
				String name = "pair_" + mym.scenario.name() + "__" + mcl.scenario.name();
				Summary summary = runPortfolio(name, EXPANDED_SYMBOLS, endDate);
				validations.add(summary);
				System.out.println("PAIR " + line(summary));
				printNewSymbolStrategyBreakdown(summary.id);
			}
		}

		seedPresetRows();
		applyAcceptedWipTrim();
		scenarioByName("cmom_tight_short_combo").apply();
		Summary acceptedPocket = runPortfolio("pair_existing_cmom_tight_short_combo", EXPANDED_SYMBOLS, endDate);
		validations.add(acceptedPocket);
		System.out.println("PAIR " + line(acceptedPocket));
		printNewSymbolStrategyBreakdown(acceptedPocket.id);

		Collections.sort(validations, new Comparator<Summary>() {
			@Override
			public int compare(Summary first, Summary second) {
				int firstScore = acceptedPortfolioScore(first, baseline) ? 0 : 1;
				int secondScore = acceptedPortfolioScore(second, baseline) ? 0 : 1;
				if (firstScore != secondScore) {
					return firstScore - secondScore;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});

		System.out.println("PAIR_RANKINGS");
		for (int index = 0; index < validations.size(); index++) {
			Summary summary = validations.get(index);
			System.out.println((index + 1) + ". accepted=" + acceptedPortfolioScore(summary, baseline) + " " + line(summary));
		}

		Summary best = validations.isEmpty() ? null : validations.get(0);
		boolean accepted = best != null && acceptedPortfolioScore(best, baseline);
		if (accepted) {
			seedPresetRows();
			applyAcceptedWipTrim();
			applyValidationByName(best.name, mymTop, mclTop);
			System.out.println("APPLIED_WIP_INDIVIDUAL_EXPANSION=" + best.name);
		} else {
			seedPresetRows();
			applyAcceptedWipTrim();
			applyDisabled("MYM");
			applyDisabled("MCL");
			System.out.println("APPLIED_WIP_INDIVIDUAL_EXPANSION=none");
		}
		System.out.println("ACCEPTED=" + accepted);
		if (best != null) {
			System.out.println("BEST_PAIR=" + line(best));
		}
	}

	private static List<CandidateResult> runIndividualCandidates(String symbol, String endDate) throws Exception {
		List<CandidateResult> results = new ArrayList<CandidateResult>();
		for (Scenario scenario : individualScenarios(symbol)) {
			seedPresetRows();
			applyAcceptedWipTrim();
			if ("MYM".equals(symbol)) {
				applyDisabled("MCL");
			} else {
				applyDisabled("MYM");
			}
			scenario.apply();
			Summary summary = runPortfolio("solo_" + scenario.name(), symbol, endDate);
			results.add(new CandidateResult(symbol, scenario, summary));
			System.out.println("SOLO " + line(summary));
		}
		return results;
	}

	private static List<CandidateResult> topIndividual(List<CandidateResult> results, int limit) {
		List<CandidateResult> positive = new ArrayList<CandidateResult>();
		for (CandidateResult result : results) {
			if (acceptedIndividualScore(result)) {
				positive.add(result);
			}
		}
		Collections.sort(positive, new Comparator<CandidateResult>() {
			@Override
			public int compare(CandidateResult first, CandidateResult second) {
				return Double.compare(symbolPnl(second.summary, second.symbol), symbolPnl(first.summary, first.symbol));
			}
		});
		if (positive.size() <= limit) {
			return positive;
		}
		return new ArrayList<CandidateResult>(positive.subList(0, limit));
	}

	private static boolean acceptedIndividualScore(CandidateResult result) {
		return result != null
			&& result.summary != null
			&& result.summary.ruleViolation == 0
			&& result.summary.dailyLossBreaches == 0
			&& result.summary.trailingDrawdownBreaches == 0
			&& symbolTrades(result.summary, result.symbol) > 0
			&& symbolPnl(result.summary, result.symbol) > 0.0;
	}

	private static boolean acceptedPortfolioScore(Summary summary, Summary baseline) {
		return summary != null
			&& baseline != null
			&& summary.ruleViolation == 0
			&& summary.dailyLossBreaches == 0
			&& summary.trailingDrawdownBreaches == 0
			&& summary.pnl > baseline.pnl
			&& summary.trades > baseline.trades
			&& summary.mymTrades > 0
			&& summary.mymPnl > 0.0
			&& summary.mclTrades > 0
			&& summary.mclPnl > 0.0
			&& summary.drawdownPct <= baseline.drawdownPct + 0.01
			&& summary.maxIntradayLoss >= baseline.maxIntradayLoss - 0.01
			&& summary.maxAggregateMae >= baseline.maxAggregateMae - 0.01;
	}

	private static double qualityScore(Summary summary, Summary baseline) {
		if (summary == null || baseline == null) {
			return -999999.0;
		}
		double score = summary.pnl - baseline.pnl;
		score += (summary.trades - baseline.trades) * 6.0;
		score += (summary.winRate - baseline.winRate) * 175.0;
		score += (summary.profitFactor - baseline.profitFactor) * 1200.0;
		score -= Math.max(0.0, summary.drawdownPct - baseline.drawdownPct) * 2500.0;
		score -= Math.max(0.0, baseline.maxIntradayLoss - summary.maxIntradayLoss) * 2.0;
		score -= Math.max(0.0, baseline.maxAggregateMae - summary.maxAggregateMae) * 2.0;
		score -= (summary.ruleViolation + summary.dailyLossBreaches + summary.trailingDrawdownBreaches) * 100000.0;
		if (summary.mymPnl <= 0.0 || summary.mclPnl <= 0.0 || summary.mymTrades <= 0 || summary.mclTrades <= 0) {
			score -= 50000.0;
		}
		return round(score);
	}

	private static double liveRulesScore(Summary summary, Summary baseline) {
		if (summary == null || baseline == null) {
			return -999999.0;
		}
		double score = summary.pnl - baseline.pnl;
		score += (summary.trades - baseline.trades) * 4.0;
		score += (summary.winRate - baseline.winRate) * 225.0;
		score += (summary.profitFactor - baseline.profitFactor) * 1600.0;
		score -= Math.max(0.0, summary.drawdownPct - baseline.drawdownPct) * 3500.0;
		score -= Math.max(0.0, baseline.maxIntradayLoss - summary.maxIntradayLoss) * 2.0;
		score -= Math.max(0.0, baseline.maxAggregateMae - summary.maxAggregateMae) * 2.0;
		score -= (summary.ruleViolation + summary.dailyLossBreaches + summary.trailingDrawdownBreaches) * 100000.0;
		double mymAverage = summary.mymTrades <= 0 ? -1000.0 : summary.mymPnl / summary.mymTrades;
		double mclAverage = summary.mclTrades <= 0 ? -1000.0 : summary.mclPnl / summary.mclTrades;
		score -= Math.max(0.0, 35.0 - mymAverage) * 40.0;
		score -= Math.max(0.0, 75.0 - mclAverage) * 25.0;
		if (summary.mymPnl <= 0.0 || summary.mclPnl <= 0.0 || summary.mymTrades <= 0 || summary.mclTrades <= 0) {
			score -= 50000.0;
		}
		return round(score);
	}

	private static double sourceQualityScore(Summary summary, Summary baseline) {
		if (summary == null || baseline == null) {
			return -999999.0;
		}
		double score = summary.pnl - baseline.pnl;
		score += (summary.trades - baseline.trades) * 5.0;
		score += (summary.winRate - ORIGINAL_WIN_RATE_TARGET) * 1200.0;
		score += (summary.profitFactor - baseline.profitFactor) * 1800.0;
		score -= Math.max(0.0, summary.drawdownPct - baseline.drawdownPct) * 3500.0;
		score -= Math.max(0.0, baseline.maxIntradayLoss - summary.maxIntradayLoss) * 2.0;
		score -= Math.max(0.0, baseline.maxAggregateMae - summary.maxAggregateMae) * 2.0;
		score -= (summary.ruleViolation + summary.dailyLossBreaches + summary.trailingDrawdownBreaches) * 100000.0;
		if (summary.mymPnl <= 0.0 || summary.mclPnl <= 0.0 || summary.mymTrades <= 0 || summary.mclTrades <= 0) {
			score -= 50000.0;
		}
		if (summary.winRate < ORIGINAL_WIN_RATE_TARGET) {
			score -= (ORIGINAL_WIN_RATE_TARGET - summary.winRate) * 10000.0;
		}
		return round(score);
	}

	private static double profitContributionScore(Summary summary, Summary baseline) {
		if (summary == null || baseline == null) {
			return -999999.0;
		}
		double score = summary.pnl - baseline.pnl;
		score += (summary.trades - baseline.trades) * 10.0;
		score += (summary.winRate - baseline.winRate) * 300.0;
		score += (summary.profitFactor - baseline.profitFactor) * 1800.0;
		score -= Math.max(0.0, summary.drawdownPct - baseline.drawdownPct) * 1700.0;
		score -= Math.max(0.0, baseline.maxIntradayLoss - summary.maxIntradayLoss) * 2.0;
		score -= Math.max(0.0, baseline.maxAggregateMae - summary.maxAggregateMae) * 2.0;
		score -= (summary.ruleViolation + summary.dailyLossBreaches + summary.trailingDrawdownBreaches) * 100000.0;
		double mymAverage = summary.mymTrades <= 0 ? -1000.0 : summary.mymPnl / summary.mymTrades;
		double mclAverage = summary.mclTrades <= 0 ? -1000.0 : summary.mclPnl / summary.mclTrades;
		score -= Math.max(0.0, 65.0 - mymAverage) * 18.0;
		score -= Math.max(0.0, 110.0 - mclAverage) * 12.0;
		if (summary.mymPnl <= 0.0 || summary.mclPnl <= 0.0 || summary.mymTrades <= 0 || summary.mclTrades <= 0) {
			score -= 50000.0;
		}
		return round(score);
	}


	private static void printCandidateRankings(List<CandidateResult> candidates) {
		for (int index = 0; index < candidates.size(); index++) {
			CandidateResult candidate = candidates.get(index);
			System.out.println((index + 1) + ". " + candidate.scenario.name() + " " + line(candidate.summary));
			try {
				printNewSymbolStrategyBreakdown(candidate.summary.id);
			} catch (Exception ignored) {
			}
		}
	}

	private static void applyValidationByName(String name, List<CandidateResult> mymTop, List<CandidateResult> mclTop) throws Exception {
		if ("pair_existing_cmom_tight_short_combo".equals(name)) {
			scenarioByName("cmom_tight_short_combo").apply();
			return;
		}
		for (CandidateResult mym : mymTop) {
			for (CandidateResult mcl : mclTop) {
				String pairName = "pair_" + mym.scenario.name() + "__" + mcl.scenario.name();
				if (pairName.equals(name)) {
					mym.scenario.apply();
					mcl.scenario.apply();
					return;
				}
			}
		}
		throw new IllegalArgumentException("Unknown validation pair " + name);
	}

	private static List<Scenario> scenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		values.add(new Scenario() {
			public String name() { return "expanded_disabled"; }
			public void apply() throws Exception {
				applyDisabled("MYM");
				applyDisabled("MCL");
			}
		});
		values.add(new Scenario() {
			public String name() { return "mym_m2k_only"; }
			public void apply() throws Exception {
				applyTemplate("MYM", "M2K", 260.0, 25);
				applyDisabled("MCL");
			}
		});
		values.add(new Scenario() {
			public String name() { return "mym_mes_only"; }
			public void apply() throws Exception {
				applyTemplate("MYM", "MES", 260.0, 25);
				applyDisabled("MCL");
			}
		});
		values.add(new Scenario() {
			public String name() { return "mcl_mgc_only"; }
			public void apply() throws Exception {
				applyDisabled("MYM");
				applyTemplate("MCL", "MGC", 491.0, 30);
			}
		});
		values.add(new Scenario() {
			public String name() { return "mcl_mgc_mym_m2k"; }
			public void apply() throws Exception {
				applyTemplate("MYM", "M2K", 260.0, 25);
				applyTemplate("MCL", "MGC", 491.0, 30);
			}
		});
		values.add(new Scenario() {
			public String name() { return "mcl_mgc_mym_mes"; }
			public void apply() throws Exception {
				applyTemplate("MYM", "MES", 260.0, 25);
				applyTemplate("MCL", "MGC", 491.0, 30);
			}
		});
		values.add(new Scenario() {
			public String name() { return "opening_only_balanced"; }
			public void apply() throws Exception {
				applyOpeningOnly("MYM", "M2K", 240.0, 20);
				applyOpeningOnly("MCL", "MGC", 360.0, 20);
			}
		});
		values.add(new Scenario() {
			public String name() { return "opening_close_balanced"; }
			public void apply() throws Exception {
				applyOpeningClose("MYM", "M2K", 260.0, 24);
				applyOpeningClose("MCL", "MGC", 420.0, 24);
			}
		});
		values.add(new Scenario() {
			public String name() { return "mcl_quality_mym_m2k"; }
			public void apply() throws Exception {
				applyTemplate("MYM", "M2K", 260.0, 25);
				applyMclQuality();
			}
		});
		values.add(new Scenario() {
			public String name() { return "mcl_aggressive_mym_m2k"; }
			public void apply() throws Exception {
				applyTemplate("MYM", "M2K", 320.0, 35);
				applyTemplate("MCL", "MGC", 650.0, 50);
			}
		});
		values.add(new Scenario() {
			public String name() { return "mym_cmom14_only"; }
			public void apply() throws Exception {
				applyCloseMomentum14("MYM", "M2K", 240.0, 20, true, true);
				applyDisabled("MCL");
			}
		});
		values.add(new Scenario() {
			public String name() { return "mym_cmom14_short_only"; }
			public void apply() throws Exception {
				applyCloseMomentum14("MYM", "M2K", 220.0, 18, false, true);
				applyDisabled("MCL");
			}
		});
		values.add(new Scenario() {
			public String name() { return "mcl_cmom14_short_only"; }
			public void apply() throws Exception {
				applyDisabled("MYM");
				applyCloseMomentum14("MCL", "MGC", 320.0, 18, false, true);
			}
		});
		values.add(new Scenario() {
			public String name() { return "cmom14_combo"; }
			public void apply() throws Exception {
				applyCloseMomentum14("MYM", "M2K", 240.0, 20, true, true);
				applyCloseMomentum14("MCL", "MGC", 320.0, 18, false, true);
			}
		});
		values.add(new Scenario() {
			public String name() { return "cmom14_short_combo"; }
			public void apply() throws Exception {
				applyCloseMomentum14("MYM", "M2K", 220.0, 18, false, true);
				applyCloseMomentum14("MCL", "MGC", 320.0, 18, false, true);
			}
		});
		values.add(new Scenario() {
			public String name() { return "mym_cmom1450_short_only"; }
			public void apply() throws Exception {
				applyCloseMomentumWindow("MYM", "M2K", 220.0, 18, false, true, 890, 909);
				applyDisabled("MCL");
			}
		});
		values.add(new Scenario() {
			public String name() { return "cmom_tight_short_combo"; }
			public void apply() throws Exception {
				applyCloseMomentumWindow("MYM", "M2K", 220.0, 18, false, true, 890, 909);
				applyCloseMomentum14("MCL", "MGC", 320.0, 18, false, true);
			}
		});
		return values;
	}

	private static List<Scenario> individualScenarios(final String symbol) {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, symbol + "_disabled", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyDisabled(symbol);
			}
		});

		for (final String template : new String[] {"MES", "MNQ", "NQ", "MGC", "ES", "M2K"}) {
			add(values, symbol + "_template_" + template.toLowerCase(), new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyTemplate(symbol, template, baseRiskDollars(symbol), 18);
				}
			});
		}

		int[][] closeWindows = new int[][] {
			{840, 869},
			{870, 899},
			{890, 909},
			{900, 925},
			{870, 925}
		};
		double[] closeMoves = "MCL".equals(symbol) ? new double[] {18.0, 22.0, 30.0} : new double[] {12.0, 16.0, 24.0};
		boolean[][] closeSides = new boolean[][] {
			{false, true},
			{true, false},
			{true, true}
		};
		for (int[] window : closeWindows) {
			final int start = window[0];
			final int end = window[1];
			for (boolean[] side : closeSides) {
				final boolean allowLong = side[0];
				final boolean allowShort = side[1];
				for (double move : closeMoves) {
					final double minMove = move;
					double[] riskValues = start >= 870 ? closeRiskDollars(symbol) : new double[] {baseRiskDollars(symbol)};
					for (double riskValue : riskValues) {
						final double risk = riskValue;
						final int maxContracts = risk > baseRiskDollars(symbol) ? 25 : 18;
						add(values, symbol + "_cmom_" + sideLabel(allowLong, allowShort) + "_" + start + "_" + end + "_m" + compact(minMove) + "_r" + compact(risk), new ScenarioAction() {
							@Override
							public void apply() throws Exception {
								applyCloseMomentumCustom(symbol, risk, maxContracts, allowLong, allowShort, start, end, 1, minMove, closeVolumeRatio(symbol), closeRewardRisk(symbol));
							}
						});
					}
				}
			}
		}

		int[][] openingWindows = new int[][] {
			{570, 599},
			{600, 629},
			{620, 649},
			{630, 659},
			{570, 660}
		};
		for (int[] window : openingWindows) {
			final int start = window[0];
			final int end = window[1];
			for (boolean[] side : new boolean[][] {{false, true}, {true, false}}) {
				final boolean allowLong = side[0];
				final boolean allowShort = side[1];
				add(values, symbol + "_omom_" + sideLabel(allowLong, allowShort) + "_" + start + "_" + end, new ScenarioAction() {
					@Override
					public void apply() throws Exception {
						applyOpeningMomentumCustom(symbol, baseRiskDollars(symbol), 18, allowLong, allowShort, start, end, 3, openingRiskTicks(symbol), 0.55, "MCL".equals(symbol) ? 0.85 : 0.88);
					}
				});
			}
		}

		int[][] afternoonWindows = new int[][] {
			{750, 809},
			{780, 839},
			{840, 899},
			{900, 920}
		};
		for (int[] window : afternoonWindows) {
			final int start = window[0];
			final int end = window[1];
			for (boolean[] side : new boolean[][] {{false, true}, {true, false}}) {
				final boolean allowLong = side[0];
				final boolean allowShort = side[1];
				add(values, symbol + "_aft_" + sideLabel(allowLong, allowShort) + "_" + start + "_" + end, new ScenarioAction() {
					@Override
					public void apply() throws Exception {
						applyAfternoonCustom(symbol, baseRiskDollars(symbol), 18, allowLong, allowShort, start, end, 2);
					}
				});
			}
		}

		int[][] fvgWindows = new int[][] {
			{600, 719},
			{720, 779},
			{780, 839},
			{840, 900}
		};
		for (int[] window : fvgWindows) {
			final int start = window[0];
			final int end = window[1];
			for (boolean[] side : new boolean[][] {{false, true}, {true, false}}) {
				final boolean allowLong = side[0];
				final boolean allowShort = side[1];
				add(values, symbol + "_fvg_" + sideLabel(allowLong, allowShort) + "_" + start + "_" + end, new ScenarioAction() {
					@Override
					public void apply() throws Exception {
						applyFvgCustom(symbol, baseRiskDollars(symbol), 18, allowLong, allowShort, start, end, 2);
					}
				});
			}
		}

		for (boolean[] side : new boolean[][] {{false, true}, {true, false}}) {
			final boolean allowLong = side[0];
			final boolean allowShort = side[1];
			add(values, symbol + "_pdb_" + sideLabel(allowLong, allowShort) + "_610_890", new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyPdbCustom(symbol, baseRiskDollars(symbol), 18, allowLong, allowShort, 610, 890, 3);
				}
			});
			add(values, symbol + "_vwap_reclaim_" + sideLabel(allowLong, allowShort), new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyVwapReclaimCustom(symbol, baseRiskDollars(symbol), 18, allowLong, allowShort, 660, 900, 2);
				}
			});
			add(values, symbol + "_rcb_" + sideLabel(allowLong, allowShort), new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyRangeCompressionCustom(symbol, baseRiskDollars(symbol), 18, allowLong, allowShort, 660, 900, 2);
				}
			});
			add(values, symbol + "_tlad_" + sideLabel(allowLong, allowShort), new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyTrendLadderCustom(symbol, baseRiskDollars(symbol), 18, allowLong, allowShort, 660, 900, 2);
				}
			});
			add(values, symbol + "_mscalp_" + sideLabel(allowLong, allowShort), new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyMicroScalpCustom(symbol, baseRiskDollars(symbol), 18, allowLong, allowShort, 600, 900, 3);
				}
			});
		}
		return values;
	}

	private static List<Scenario> deepScenarios(final String symbol) {
		List<Scenario> values = new ArrayList<Scenario>();
		if ("MYM".equals(symbol)) {
			addMymDeepScenarios(values);
		} else if ("MCL".equals(symbol)) {
			addMclDeepScenarios(values);
		}
		return values;
	}

	private static List<Scenario> focusedMymScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "MYM_focused_orb_m2k_long_r320", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyTemplateModule("MYM", "M2K", "ORB", true, false, 320.0, 25);
			}
		});
		add(values, "MYM_focused_orb_m2k_long_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyTemplateModule("MYM", "M2K", "ORB", true, false, 420.0, 30);
			}
		});
		add(values, "MYM_focused_orb_es_long_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyTemplateModule("MYM", "ES", "ORB", true, false, 420.0, 30);
			}
		});
		add(values, "MYM_focused_orb_nq_long_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyTemplateModule("MYM", "NQ", "ORB", true, false, 420.0, 30);
			}
		});
		add(values, "MYM_focused_stack_cmom_omom_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymStack(420.0, 30, true, 890, 909, 1, 12.0, 0.70, 0.85, true, 570, 660, 0.55, 0.88, false, 620, 649);
			}
		});
		add(values, "MYM_focused_stack_cmom_omom_omomshort_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymStack(420.0, 30, true, 890, 909, 1, 12.0, 0.70, 0.85, true, 570, 660, 0.55, 0.88, true, 620, 649);
			}
		});
		add(values, "MYM_focused_orb_m2k_stack_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymOrbM2kStack(420.0, false, false);
			}
		});
		add(values, "MYM_focused_orb_m2k_stack_omomshort_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymOrbM2kStack(420.0, true, false);
			}
		});
		add(values, "MYM_focused_orb_m2k_stack_wft_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymOrbM2kStack(420.0, false, true);
			}
		});
		add(values, "MYM_focused_orb_m2k_stack_omomshort_wft_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymOrbM2kStack(420.0, true, true);
			}
		});
		return values;
	}

	private static List<Scenario> focusedMclScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "MCL_focused_aft_long_r320", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMclStack(320.0, 25, false, 870, 899, 1, 22.0, 0.65, 0.80, true, 900, 920, 2, 0.70, 0.85, 35.0, false, false, false, false);
			}
		});
		add(values, "MCL_focused_cmom_short_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMclStack(420.0, 30, true, 870, 899, 1, 18.0, 0.65, 0.80, false, 900, 920, 2, 0.70, 0.85, 35.0, false, false, false, false);
			}
		});
		add(values, "MCL_focused_aft_cmom_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMclStack(420.0, 30, true, 870, 899, 1, 18.0, 0.65, 0.80, true, 900, 920, 2, 0.70, 0.85, 35.0, false, false, false, false);
			}
		});
		add(values, "MCL_focused_aft_cmom_fvg_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMclStack(420.0, 30, true, 870, 899, 1, 18.0, 0.65, 0.80, true, 900, 920, 2, 0.70, 0.85, 35.0, false, true, false, false);
			}
		});
		add(values, "MCL_focused_aft_cmom_orblate_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMclStack(420.0, 30, true, 870, 899, 1, 18.0, 0.65, 0.80, true, 900, 920, 2, 0.70, 0.85, 35.0, true, false, false, false);
			}
		});
		add(values, "MCL_focused_aft_cmom_fvg_orblate_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMclStack(420.0, 30, true, 870, 899, 1, 18.0, 0.65, 0.80, true, 900, 920, 2, 0.70, 0.85, 35.0, true, true, false, false);
			}
		});
		add(values, "MCL_focused_aft_cmom_fvg_orblate_wft_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMclStack(420.0, 30, true, 870, 899, 1, 18.0, 0.65, 0.80, true, 900, 920, 2, 0.70, 0.85, 35.0, true, true, false, false);
				enableWinnerFollowThrough("MCL", true, true, "AFT,CMOM,FVG,ORB", 18.0, 0.70, 4);
			}
		});
		add(values, "MCL_focused_es_orblate_long_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyTemplateModule("MCL", "ES", "ORB_LATE", true, false, 420.0, 30);
			}
		});
		add(values, "MCL_focused_es_orb_long_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyTemplateModule("MCL", "ES", "ORB", true, false, 420.0, 30);
			}
		});
		add(values, "MCL_focused_nq_fvg_long_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyTemplateModule("MCL", "NQ", "FVG", true, false, 420.0, 30);
			}
		});
		add(values, "MCL_focused_mnq_fvg_long_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyTemplateModule("MCL", "MNQ", "FVG", true, false, 420.0, 30);
			}
		});
		add(values, "MCL_focused_es_fvg_long_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyTemplateModule("MCL", "ES", "FVG", true, false, 420.0, 30);
			}
		});
		add(values, "MCL_focused_es_vwap_short_r420", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyTemplateModule("MCL", "ES", "VWAP", false, true, 420.0, 30);
			}
		});
		return values;
	}

	private static List<Scenario> powerMymScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		for (final double risk : new double[] {520.0, 650.0, 800.0}) {
			final int maxContracts = risk >= 650.0 ? 50 : 40;
			add(values, "MYM_power_orb_m2k_stack_r" + compact(risk) + "_c" + maxContracts, new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyMymOrbM2kStack(risk, maxContracts, false, false);
				}
			});
			add(values, "MYM_power_orb_m2k_stack_omomshort_r" + compact(risk) + "_c" + maxContracts, new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyMymOrbM2kStack(risk, maxContracts, true, false);
				}
			});
		}
		for (final double risk : new double[] {650.0, 800.0}) {
			add(values, "MYM_power_orb_m2k_only_r" + compact(risk) + "_c50", new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyTemplateModule("MYM", "M2K", "ORB", true, false, risk, 50);
				}
			});
			add(values, "MYM_power_stack_cmom_omom_r" + compact(risk) + "_c50", new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyMymStack(risk, 50, true, 890, 909, 1, 12.0, 0.70, 0.85, true, 570, 660, 0.55, 0.88, false, 620, 649);
				}
			});
		}
		return values;
	}

	private static List<Scenario> powerMclScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		for (final double risk : new double[] {520.0, 650.0, 800.0}) {
			final int maxContracts = risk >= 650.0 ? 50 : 40;
			add(values, "MCL_power_aft_cmom_orblate_r" + compact(risk) + "_c" + maxContracts, new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyMclStack(risk, maxContracts, true, 870, 899, 1, 18.0, 0.65, 0.80, true, 900, 920, 2, 0.70, 0.85, 35.0, true, false, false, false);
				}
			});
			add(values, "MCL_power_es_orb_long_r" + compact(risk) + "_c" + maxContracts, new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyTemplateModule("MCL", "ES", "ORB", true, false, risk, maxContracts);
				}
			});
			add(values, "MCL_power_es_orblate_long_r" + compact(risk) + "_c" + maxContracts, new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyTemplateModule("MCL", "ES", "ORB_LATE", true, false, risk, maxContracts);
				}
			});
		}
		add(values, "MCL_power_aft_cmom_r650_c50", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMclStack(650.0, 50, true, 870, 899, 1, 18.0, 0.65, 0.80, true, 900, 920, 2, 0.70, 0.85, 35.0, false, false, false, false);
			}
		});
		add(values, "MCL_power_es_vwap_long_r420_c30", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyTemplateModule("MCL", "ES", "VWAP", true, false, 420.0, 30);
			}
		});
		return values;
	}

	private static List<Scenario> qualityMymScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		for (final double risk : new double[] {650.0, 800.0}) {
			for (final double body : new double[] {25.0, 35.0, 45.0}) {
				add(values, "MYM_quality_orb_cmom_omom_r" + compact(risk) + "_body" + compact(body), new ScenarioAction() {
					@Override
					public void apply() throws Exception {
						applyMymOrbM2kStack(risk, 50, false, false);
						applyQualityFilters("MYM", body, body, body, 0.0, 0.70, 0.55, 0.0, 0.0);
					}
				});
				add(values, "MYM_quality_orb_cmom_omom_short_r" + compact(risk) + "_body" + compact(body), new ScenarioAction() {
					@Override
					public void apply() throws Exception {
						applyMymOrbM2kStack(risk, 50, true, false);
						applyQualityFilters("MYM", body, body, body, 0.0, 0.70, 0.60, 0.0, 0.0);
					}
				});
			}
		}
		add(values, "MYM_quality_omomlong_cmom_r800_body35", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymStack(800.0, 50, true, 890, 909, 1, 12.0, 0.75, 0.85, true, 570, 660, 0.60, 0.88, false, 620, 649);
				applyQualityFilters("MYM", 0.0, 35.0, 35.0, 0.0, 0.75, 0.60, 0.0, 0.0);
			}
		});
		add(values, "MYM_quality_omomlong_only_r800_body35", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymStack(800.0, 50, false, 890, 909, 1, 12.0, 0.70, 0.85, true, 570, 660, 0.60, 0.88, false, 620, 649);
				applyQualityFilters("MYM", 0.0, 35.0, 0.0, 0.0, 0.0, 0.60, 0.0, 0.0);
			}
		});
		return values;
	}

	private static List<Scenario> qualityMclScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		for (final double risk : new double[] {650.0, 800.0}) {
			for (final double body : new double[] {20.0, 30.0, 40.0}) {
				add(values, "MCL_quality_aft_cmom_orblate_r" + compact(risk) + "_body" + compact(body), new ScenarioAction() {
					@Override
					public void apply() throws Exception {
						applyMclStack(risk, 50, true, 870, 899, 1, 18.0, 0.75, 0.80, true, 900, 920, 2, 0.80, 0.85, 35.0, true, false, false, false);
						applyQualityFilters("MCL", body, 0.0, body, body, 0.75, 0.0, 0.80, 0.0);
					}
				});
				add(values, "MCL_quality_orblate_only_r" + compact(risk) + "_body" + compact(body), new ScenarioAction() {
					@Override
					public void apply() throws Exception {
						applyTemplateModule("MCL", "ES", "ORB_LATE", true, false, risk, 50);
						applyQualityFilters("MCL", body, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
					}
				});
			}
		}
		add(values, "MCL_quality_aft_cmom_r800_body30", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMclStack(800.0, 50, true, 870, 899, 1, 18.0, 0.75, 0.80, true, 900, 920, 2, 0.80, 0.85, 35.0, false, false, false, false);
				applyQualityFilters("MCL", 0.0, 0.0, 30.0, 30.0, 0.75, 0.0, 0.80, 0.0);
			}
		});
		return values;
	}

	private static List<Scenario> liveRuleScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "mym_orb_cmom350_omom_body45__mcl_orblate_cmom90", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, true, true, 350.0, 0.70, true, 45.0, 45.0, 45.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(800.0, 50, true, false, 90.0, 0.65, 0.0, 20.0, 0.0, 0.0);
			}
		});
		add(values, "mym_orb_cmom300_omom_body45__mcl_orblate_cmom90", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, true, true, 300.0, 0.70, true, 45.0, 45.0, 45.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(800.0, 50, true, false, 90.0, 0.65, 0.0, 20.0, 0.0, 0.0);
			}
		});
		add(values, "mym_orb_cmom250_omom_body45__mcl_orblate_cmom90", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, true, true, 250.0, 0.70, true, 45.0, 45.0, 45.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(800.0, 50, true, false, 90.0, 0.65, 0.0, 20.0, 0.0, 0.0);
			}
		});
		add(values, "mym_orb_cmom350_no_omom__mcl_orblate_cmom90", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, true, true, 350.0, 0.70, false, 45.0, 0.0, 45.0, 0.0, 0.85, 0.0);
				applyMclLiveRules(800.0, 50, true, false, 90.0, 0.65, 0.0, 20.0, 0.0, 0.0);
			}
		});
		add(values, "mym_cmom350_omom_body45_no_orb__mcl_orblate_cmom90", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, false, true, 350.0, 0.70, true, 0.0, 45.0, 45.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(800.0, 50, true, false, 90.0, 0.65, 0.0, 20.0, 0.0, 0.0);
			}
		});
		add(values, "mym_orb_cmom350_omom_body35__mcl_orblate_cmom90_aft40", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, true, true, 350.0, 0.70, true, 35.0, 35.0, 35.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(800.0, 50, true, true, 90.0, 0.65, 0.85, 20.0, 0.0, 40.0);
			}
		});
		add(values, "mym_orb_cmom350_omom_body45__mcl_orblate_cmom90_aft40", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, true, true, 350.0, 0.70, true, 45.0, 45.0, 45.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(800.0, 50, true, true, 90.0, 0.65, 0.85, 20.0, 0.0, 40.0);
			}
		});
		add(values, "mym_cmom350_omom_body45_no_orb__mcl_orblate_cmom90_aft40", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, false, true, 350.0, 0.70, true, 0.0, 45.0, 45.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(800.0, 50, true, true, 90.0, 0.65, 0.85, 20.0, 0.0, 40.0);
			}
		});
		add(values, "mym_orb_cmom350_no_omom_body45__mcl_orblate_cmom90_aft40", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, true, true, 350.0, 0.70, false, 45.0, 0.0, 45.0, 0.0, 0.85, 0.0);
				applyMclLiveRules(800.0, 50, true, true, 90.0, 0.65, 0.85, 20.0, 0.0, 40.0);
			}
		});
		add(values, "mym_orb_cmom350_omom_body45__mcl_orblate_only", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, true, true, 350.0, 0.70, true, 45.0, 45.0, 45.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(800.0, 50, false, false, 0.0, 0.0, 0.0, 20.0, 0.0, 0.0);
			}
		});
		add(values, "mym_orb_cmom350_omom_body45__mcl_orblate_cmom120", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, true, true, 350.0, 0.70, true, 45.0, 45.0, 45.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(800.0, 50, true, false, 120.0, 0.65, 0.0, 20.0, 0.0, 0.0);
			}
		});
		add(values, "mym_orb_cmom350_omom_body45_r800__mcl_orblate_cmom90", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(800.0, 50, true, true, 350.0, 0.70, true, 45.0, 45.0, 45.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(800.0, 50, true, false, 90.0, 0.65, 0.0, 20.0, 0.0, 0.0);
			}
		});
		add(values, "size_mym_body45_r650__mcl_aft40_r1100", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, true, true, 350.0, 0.70, true, 45.0, 45.0, 45.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(1100.0, 50, true, true, 90.0, 0.65, 0.85, 20.0, 0.0, 40.0);
			}
		});
		add(values, "size_mym_body45_r650__mcl_aft40_r1400", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, true, true, 350.0, 0.70, true, 45.0, 45.0, 45.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(1400.0, 50, true, true, 90.0, 0.65, 0.85, 20.0, 0.0, 40.0);
			}
		});
		add(values, "size_mym_body45_r800__mcl_aft40_r1100", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(800.0, 50, true, true, 350.0, 0.70, true, 45.0, 45.0, 45.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(1100.0, 50, true, true, 90.0, 0.65, 0.85, 20.0, 0.0, 40.0);
			}
		});
		add(values, "size_mym_body35_r650__mcl_aft40_r1100", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, true, true, 350.0, 0.70, true, 35.0, 35.0, 35.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(1100.0, 50, true, true, 90.0, 0.65, 0.85, 20.0, 0.0, 40.0);
			}
		});
		add(values, "size_mym_no_orb_r650__mcl_aft40_r1400", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, false, true, 350.0, 0.70, true, 0.0, 45.0, 45.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(1400.0, 50, true, true, 90.0, 0.65, 0.85, 20.0, 0.0, 40.0);
			}
		});
		return values;
	}

	private static List<Scenario> profitExpansionScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "strict_body35_sweep10_mcl_aft20_mim", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitStrictAft20Mim();
			}
		});
		add(values, "high_body45_sweep10_mcl_aft20_mim", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
			}
		});
		add(values, "custom_high_eia_only", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				enableMclEiaContinuation(2, true, true, 626, 630, 660, 750, 1.0, 24.0, 1.50, 0.0, 0.0, 60);
			}
		});
		add(values, "custom_high_eia_crude_open", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				enableMclEiaContinuation(2, true, true, 626, 630, 660, 750, 1.0, 24.0, 1.50, 0.0, 0.0, 60);
				enableMclCrudeSessionOpen(2, true, true, 540, 550, 551, 660, 2.0, 22.0, 1.10, 0.35, 20.0, 45);
			}
		});
		add(values, "custom_high_mym_index_orb2", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				enableMymIndexConfirmation(3, true, true, 570, 920, 20, 12, 90.0, 0.85, 0.55, 20.0, 0.50, 35);
				enableMymOrbRetest(2, true, true, 590, 690, 2.0, 5.0, 110.0, 0.90, 0.55, 20.0, 45);
			}
		});
		add(values, "custom_high_mym_idx_short_dense", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				enableMymIndexConfirmation(4, false, true, 570, 920, 15, 10, 110.0, 0.85, 0.45, 15.0, 0.25, 40);
			}
		});
		add(values, "custom_high_mym_idx_short_quality", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				enableMymIndexConfirmation(3, false, true, 590, 820, 20, 12, 95.0, 0.95, 0.60, 25.0, 0.50, 35);
			}
		});
		add(values, "custom_high_mym_idx_short_mcl_open", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				enableMymIndexConfirmation(4, false, true, 570, 920, 15, 10, 110.0, 0.85, 0.45, 15.0, 0.25, 40);
				enableMclCrudeSessionOpen(2, true, true, 540, 552, 553, 675, 1.0, 26.0, 1.00, 0.25, 15.0, 55);
			}
		});
		add(values, "custom_high_all_default", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				enableMclEiaContinuation(2, true, true, 626, 630, 660, 750, 1.0, 24.0, 1.50, 0.0, 0.0, 60);
				enableMclCrudeSessionOpen(2, true, true, 540, 550, 551, 660, 2.0, 22.0, 1.10, 0.35, 20.0, 45);
				enableMymIndexConfirmation(3, true, true, 570, 920, 20, 12, 90.0, 0.85, 0.55, 20.0, 0.50, 35);
				enableMymOrbRetest(2, true, true, 590, 690, 2.0, 5.0, 110.0, 0.90, 0.55, 20.0, 45);
			}
		});
		add(values, "custom_high_all_dense", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				enableMclEiaContinuation(4, true, true, 626, 630, 660, 750, 1.0, 24.0, 1.50, 0.0, 0.0, 60);
				enableMclCrudeSessionOpen(3, true, true, 540, 552, 553, 675, 1.0, 26.0, 1.00, 0.25, 15.0, 55);
				enableMymIndexConfirmation(4, true, true, 570, 920, 15, 10, 110.0, 0.85, 0.45, 15.0, 0.25, 40);
				enableMymOrbRetest(3, true, true, 585, 720, 2.0, 7.0, 120.0, 0.85, 0.45, 15.0, 50);
			}
		});
		add(values, "custom_no_orb_all_dense", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitNoOrbAft20Mim();
				enableMclEiaContinuation(4, true, true, 626, 630, 660, 750, 1.0, 24.0, 1.50, 0.0, 0.0, 60);
				enableMclCrudeSessionOpen(3, true, true, 540, 552, 553, 675, 1.0, 26.0, 1.00, 0.25, 15.0, 55);
				enableMymIndexConfirmation(4, true, true, 570, 920, 15, 10, 110.0, 0.85, 0.45, 15.0, 0.25, 40);
				enableMymOrbRetest(3, true, true, 585, 720, 2.0, 7.0, 120.0, 0.85, 0.45, 15.0, 50);
			}
		});
		add(values, "no_orb_body45_sweep10_mcl_aft20_mim", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitNoOrbAft20Mim();
			}
		});
		add(values, "high_body45_sweep10_mcl_aft20_mim_mym_r800", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				upsertRisk(WIP_SLOT, "MYM", 800.0, 50);
			}
		});
		add(values, "high_body45_sweep10_mcl_aft20_mim_mcl_r1100", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				upsertRisk(WIP_SLOT, "MCL", 1100.0, 50);
			}
		});
		add(values, "high_body45_sweep10_mcl_aft20_mim_mcl_r1400", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				upsertRisk(WIP_SLOT, "MCL", 1400.0, 50);
			}
		});
		add(values, "high_body45_sweep10_mcl_aft20_mim_mym800_mcl1100", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				upsertRisk(WIP_SLOT, "MYM", 800.0, 50);
				upsertRisk(WIP_SLOT, "MCL", 1100.0, 50);
			}
		});
		add(values, "no_orb_body45_sweep10_mcl_aft20_mim_mcl_r1100", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitNoOrbAft20Mim();
				upsertRisk(WIP_SLOT, "MCL", 1100.0, 50);
			}
		});
		add(values, "high_body45_sweep10_mcl_aft20_mim_mym_wft", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				enableWinnerFollowThrough("MYM", true, true, "ORB,OMOM,CMOM,SWEEP", 45.0, 0.70, 4);
			}
		});
		add(values, "high_body45_sweep10_mcl_aft20_mim_mcl_wft", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				enableWinnerFollowThrough("MCL", true, false, "AFT,CMOM,MIM,ORB", 22.0, 0.75, 4);
			}
		});
		add(values, "high_body45_sweep10_mcl_aft20_mim_both_wft", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyProfitHighAft20Mim();
				enableWinnerFollowThrough("MYM", true, true, "ORB,OMOM,CMOM,SWEEP", 45.0, 0.70, 4);
				enableWinnerFollowThrough("MCL", true, false, "AFT,CMOM,MIM,ORB", 22.0, 0.75, 4);
			}
		});
		add(values, "high_body45_sweep10_mcl_aft20_mim_no_mcl_mim", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyHighProfitLiveRulesBase();
				setAfternoonMaxSignalRange("MCL", 20.0);
				enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 10.0);
				setSweepShortMaxRsi("MYM", 70.0);
				setSweepShortMinBody("MYM", 10.0);
			}
		});
		add(values, "high_body45_no_sweep_mcl_aft20_mim", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyHighProfitLiveRulesBase();
				setAfternoonMaxSignalRange("MCL", 20.0);
				enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
			}
		});
		add(values, "looser_body35_sweep10_mcl_aft20_mim", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyMymLiveRules(650.0, 50, true, true, 350.0, 0.70, true, 35.0, 35.0, 45.0, 0.60, 0.85, 0.88);
				applyMclLiveRules(800.0, 50, true, true, 90.0, 0.65, 0.85, 20.0, 0.0, 40.0);
				setAfternoonMaxSignalRange("MCL", 20.0);
				enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 10.0);
				setSweepShortMaxRsi("MYM", 70.0);
				setSweepShortMinBody("MYM", 10.0);
				enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
			}
		});
		return values;
	}

	private static void applyProfitStrictAft20Mim() throws Exception {
		applyStrictLiveRulesBase();
		setAfternoonMaxSignalRange("MCL", 20.0);
		enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 10.0);
		setSweepShortMaxRsi("MYM", 70.0);
		setSweepShortMinBody("MYM", 10.0);
		enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
	}

	private static void applyProfitHighAft20Mim() throws Exception {
		applyHighProfitLiveRulesBase();
		setAfternoonMaxSignalRange("MCL", 20.0);
		enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 10.0);
		setSweepShortMaxRsi("MYM", 70.0);
		setSweepShortMinBody("MYM", 10.0);
		enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
	}

	private static void applyProfitNoOrbAft20Mim() throws Exception {
		applyMymLiveRules(650.0, 50, false, true, 350.0, 0.70, true, 0.0, 45.0, 45.0, 0.60, 0.85, 0.88);
		applyMclLiveRules(800.0, 50, true, true, 90.0, 0.65, 0.85, 20.0, 0.0, 40.0);
		setAfternoonMaxSignalRange("MCL", 20.0);
		enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 10.0);
		setSweepShortMaxRsi("MYM", 70.0);
		setSweepShortMinBody("MYM", 10.0);
		enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
	}

	private static List<Scenario> moduleExpansionScenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		add(values, "strict_base", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
			}
		});
		add(values, "high_body45_base", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyHighProfitLiveRulesBase();
			}
		});
		add(values, "strict_mym_lorb_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableLateOrbContinuation("MYM", true, false, 660, 900, 2, 0.75, 90.0, 1.0, 45);
			}
		});
		add(values, "strict_mym_lorb_short", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableLateOrbContinuation("MYM", false, true, 660, 900, 2, 0.75, 90.0, 1.0, 45);
			}
		});
		add(values, "strict_mym_ipb_both", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableMarketImpulse("MYM", true, true, 2, 140.0, 60.0, 0.65, 90.0, 0.85);
			}
		});
		add(values, "strict_mym_vwap_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableVwapPullback("MYM", true, false, 600, 900, 2, 0.85, 1.2, 85.0, 120.0);
			}
		});
		add(values, "strict_mym_vwap_short", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableVwapPullback("MYM", false, true, 600, 900, 2, 0.85, 1.2, 85.0, 120.0);
			}
		});
		add(values, "strict_mym_pdb_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enablePriorDayBreakout("MYM", true, false, 610, 890, 2, 80.0, 30.0, 0.75, 100.0, 1.0);
			}
		});
		add(values, "strict_mym_pdb_short", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enablePriorDayBreakout("MYM", false, true, 610, 890, 2, 80.0, 30.0, 0.75, 100.0, 1.0);
			}
		});
		add(values, "strict_mym_fvg_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableFvg("MYM", true, false, 600, 900, 2, 12.0, 70.0, 0.65, 1.0);
			}
		});
		add(values, "strict_mym_aft_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableAfternoonContinuation("MYM", true, false, 780, 920, 2, 0.85, 90.0, 0.85, 35.0);
			}
		});
		add(values, "strict_mym_sweep_late", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableLiquiditySweep("MYM", true, true, 2, false, true, 8.0, 0.58, 32.0);
			}
		});
		add(values, "strict_mcl_lorb_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableLateOrbContinuation("MCL", true, false, 660, 900, 2, 0.75, 42.0, 1.0, 35);
			}
		});
		add(values, "strict_mcl_lorb_short", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableLateOrbContinuation("MCL", false, true, 660, 900, 2, 0.75, 42.0, 1.0, 35);
			}
		});
		add(values, "strict_mcl_ipb_both", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableMarketImpulse("MCL", true, true, 2, 45.0, 16.0, 0.65, 34.0, 0.85);
			}
		});
		add(values, "strict_mcl_mim_long_only", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableMarketImpulse("MCL", true, false, 1, 45.0, 16.0, 0.65, 34.0, 0.85);
			}
		});
		add(values, "strict_mcl_mim_long_only_rr100", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableMarketImpulse("MCL", true, false, 1, 45.0, 16.0, 0.65, 34.0, 1.0);
			}
		});
		add(values, "strict_mcl_mim_long_only_strict", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
			}
		});
		add(values, "strict_mcl_vwap_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableVwapPullback("MCL", true, false, 600, 900, 2, 0.80, 0.8, 30.0, 42.0);
			}
		});
		add(values, "strict_mcl_vwap_short", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableVwapPullback("MCL", false, true, 600, 900, 2, 0.80, 0.8, 30.0, 42.0);
			}
		});
		add(values, "strict_mcl_vrcl_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableVwapReclaim("MCL", true, false, 2, 0.80, 30.0, 0.85, 30);
			}
		});
		add(values, "strict_mcl_pdb_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enablePriorDayBreakout("MCL", true, false, 610, 890, 2, 18.0, 8.0, 0.65, 36.0, 1.0);
			}
		});
		add(values, "strict_mcl_pdb_short", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enablePriorDayBreakout("MCL", false, true, 610, 890, 2, 18.0, 8.0, 0.65, 36.0, 1.0);
			}
		});
		add(values, "strict_mcl_fvg_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableFvg("MCL", true, false, 600, 900, 2, 6.0, 32.0, 0.55, 1.0);
			}
		});
		add(values, "strict_mcl_sweep_late", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableLiquiditySweep("MCL", true, true, 2, false, true, 8.0, 0.58, 30.0);
			}
		});
		add(values, "strict_mym_sweep_short_only", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 32.0);
			}
		});
		add(values, "strict_mym_sweep_short_mcl_mim_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 32.0);
				enableMarketImpulse("MCL", true, false, 1, 45.0, 16.0, 0.65, 34.0, 0.85);
			}
		});
		add(values, "strict_mym_sweep_short_rsi70", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 32.0);
				setSweepShortMaxRsi("MYM", 70.0);
			}
		});
		add(values, "strict_mym_sweep_short_rsi68_mcl_mim_strict", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 32.0);
				setSweepShortMaxRsi("MYM", 68.0);
				enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
			}
		});
			add(values, "strict_mym_sweep_short_rsi70_mcl_mim_strict", new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyStrictLiveRulesBase();
					enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 32.0);
					setSweepShortMaxRsi("MYM", 70.0);
					enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
				}
			});
			for (final double bodyPct : new double[] {8.0, 10.0, 12.0, 15.0}) {
				add(values, "strict_mym_sweep_short_body" + compact(bodyPct) + "_mcl_mim_strict", new ScenarioAction() {
					@Override
					public void apply() throws Exception {
						applyStrictLiveRulesBase();
						enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, bodyPct);
						setSweepShortMaxRsi("MYM", 70.0);
						setSweepShortMinBody("MYM", bodyPct);
						enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
					}
				});
			}
			add(values, "quality_no_orb_body45_sweep_body10_mcl_mim_strict", new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyMymLiveRules(650.0, 50, false, true, 350.0, 0.70, true, 0.0, 45.0, 45.0, 0.60, 0.85, 0.88);
					applyMclLiveRules(800.0, 50, true, true, 90.0, 0.65, 0.85, 20.0, 0.0, 40.0);
					enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 10.0);
					setSweepShortMaxRsi("MYM", 70.0);
					setSweepShortMinBody("MYM", 10.0);
					enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
				}
			});
			add(values, "quality_high_body45_sweep_body10_mcl_mim_strict", new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyHighProfitLiveRulesBase();
					enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 10.0);
					setSweepShortMaxRsi("MYM", 70.0);
					setSweepShortMinBody("MYM", 10.0);
					enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
				}
			});
			add(values, "quality_high_body45_sweep_body10_mcl_mim_exit_discipline", new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyHighProfitLiveRulesBase();
					applyLiveExitDiscipline("MYM");
					applyLiveExitDiscipline("MCL");
					enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 10.0);
					setSweepShortMaxRsi("MYM", 70.0);
					setSweepShortMinBody("MYM", 10.0);
					enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
				}
			});
			add(values, "strict_mym_sweep_body10_mcl_aft20_mim_strict", new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyStrictLiveRulesBase();
					setAfternoonMaxSignalRange("MCL", 20.0);
					enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 10.0);
					setSweepShortMaxRsi("MYM", 70.0);
					setSweepShortMinBody("MYM", 10.0);
					enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
				}
			});
			add(values, "quality_no_orb_body45_sweep_body10_mcl_aft20_mim", new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyMymLiveRules(650.0, 50, false, true, 350.0, 0.70, true, 0.0, 45.0, 45.0, 0.60, 0.85, 0.88);
					applyMclLiveRules(800.0, 50, true, true, 90.0, 0.65, 0.85, 20.0, 0.0, 40.0);
					setAfternoonMaxSignalRange("MCL", 20.0);
					enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 10.0);
					setSweepShortMaxRsi("MYM", 70.0);
					setSweepShortMinBody("MYM", 10.0);
					enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
				}
			});
			add(values, "quality_high_body45_sweep_body10_mcl_aft20_mim", new ScenarioAction() {
				@Override
				public void apply() throws Exception {
					applyHighProfitLiveRulesBase();
					setAfternoonMaxSignalRange("MCL", 20.0);
					enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 10.0);
					setSweepShortMaxRsi("MYM", 70.0);
					setSweepShortMinBody("MYM", 10.0);
					enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
				}
			});
			add(values, "strict_combo_mcl_r1100", new ScenarioAction() {
				@Override
				public void apply() throws Exception {
				applyStrictLiveRulesBase();
				upsertRisk(WIP_SLOT, "MCL", 1100.0, 50);
				enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 32.0);
				enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
			}
		});
		add(values, "strict_combo_mcl_r1400", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				upsertRisk(WIP_SLOT, "MCL", 1400.0, 50);
				enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 32.0);
				enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
			}
		});
		add(values, "strict_combo_mym_r800", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				upsertRisk(WIP_SLOT, "MYM", 800.0, 50);
				enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 32.0);
				enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
			}
		});
		add(values, "strict_combo_mym800_mcl1100", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				upsertRisk(WIP_SLOT, "MYM", 800.0, 50);
				upsertRisk(WIP_SLOT, "MCL", 1100.0, 50);
				enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 32.0);
				enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
			}
		});
		add(values, "strict_mym_pdb_long_quality_v1", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enablePriorDayBreakout("MYM", true, false, 610, 890, 2, 100.0, 20.0, 0.90, 85.0, 1.0);
			}
		});
		add(values, "strict_mym_pdb_long_quality_v2", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enablePriorDayBreakout("MYM", true, false, 610, 890, 2, 120.0, 15.0, 1.00, 75.0, 1.0);
			}
		});
		add(values, "high_body45_mcl_mim_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyHighProfitLiveRulesBase();
				enableMarketImpulse("MCL", true, false, 1, 45.0, 16.0, 0.65, 34.0, 0.85);
			}
		});
		add(values, "high_body45_sweep_rsi70_mcl_mim_strict", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyHighProfitLiveRulesBase();
				enableLiquiditySweep("MYM", false, true, 2, false, false, 8.0, 0.58, 32.0);
				setSweepShortMaxRsi("MYM", 70.0);
				enableMarketImpulse("MCL", true, false, 1, 60.0, 20.0, 0.75, 30.0, 0.90);
			}
		});
		add(values, "strict_both_ipb", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableMarketImpulse("MYM", true, true, 2, 140.0, 60.0, 0.65, 90.0, 0.85);
				enableMarketImpulse("MCL", true, true, 2, 45.0, 16.0, 0.65, 34.0, 0.85);
			}
		});
		add(values, "strict_both_lorb_long", new ScenarioAction() {
			@Override
			public void apply() throws Exception {
				applyStrictLiveRulesBase();
				enableLateOrbContinuation("MYM", true, false, 660, 900, 2, 0.75, 90.0, 1.0, 45);
				enableLateOrbContinuation("MCL", true, false, 660, 900, 2, 0.75, 42.0, 1.0, 35);
			}
		});
		return values;
	}

	private static void addMymDeepScenarios(List<Scenario> values) {
		final String symbol = "MYM";
		int[][] closeWindows = new int[][] {
			{890, 909},
			{900, 925},
			{890, 925},
			{880, 909}
		};
		for (int[] window : closeWindows) {
			final int start = window[0];
			final int end = window[1];
			for (final int maxTrades : new int[] {1, 2}) {
				for (final double risk : new double[] {220.0, 320.0, 420.0}) {
					for (final double volume : new double[] {0.55, 0.70}) {
						for (final double reward : new double[] {0.75, 0.85, 1.0}) {
							add(values, symbol + "_deep_cmom_short_" + start + "_" + end + "_t" + maxTrades + "_r" + compact(risk) + "_v" + compact(volume) + "_rr" + compact(reward), new ScenarioAction() {
								@Override
								public void apply() throws Exception {
									applyMymStack(risk, riskContracts(risk, 18), true, start, end, maxTrades, 12.0, volume, reward, false, 570, 660, 0.55, 0.88, false, 620, 649);
								}
							});
						}
					}
				}
			}
		}
		for (final int[] window : new int[][] {{570, 599}, {570, 660}, {600, 629}}) {
			for (final double risk : new double[] {220.0, 320.0, 420.0}) {
				for (final double volume : new double[] {0.40, 0.55, 0.70}) {
					for (final double reward : new double[] {0.75, 0.88, 1.0}) {
						add(values, symbol + "_deep_omom_long_" + window[0] + "_" + window[1] + "_r" + compact(risk) + "_v" + compact(volume) + "_rr" + compact(reward), new ScenarioAction() {
							@Override
							public void apply() throws Exception {
								applyMymStack(risk, riskContracts(risk, 18), false, 890, 909, 1, 12.0, 0.70, 0.85, true, window[0], window[1], volume, reward, false, 620, 649);
							}
						});
					}
				}
			}
		}
		for (final double risk : new double[] {220.0, 320.0, 420.0}) {
			for (final double shortVolume : new double[] {0.45, 0.55, 0.70}) {
				add(values, symbol + "_deep_omom_short_620_649_r" + compact(risk) + "_v" + compact(shortVolume), new ScenarioAction() {
					@Override
					public void apply() throws Exception {
						applyMymStack(risk, riskContracts(risk, 18), false, 890, 909, 1, 12.0, 0.70, 0.85, false, 570, 660, 0.55, 0.88, true, 620, 649, shortVolume, 0.88);
					}
				});
			}
		}
		for (final double risk : new double[] {320.0, 420.0}) {
			for (final int cmomTrades : new int[] {1, 2}) {
				for (final boolean includeShortOmom : new boolean[] {false, true}) {
					add(values, symbol + "_deep_stack_cmom_omomlong" + (includeShortOmom ? "_omomshort" : "") + "_ct" + cmomTrades + "_r" + compact(risk), new ScenarioAction() {
						@Override
						public void apply() throws Exception {
							applyMymStack(risk, riskContracts(risk, 25), true, 890, 909, cmomTrades, 12.0, 0.70, 0.85, true, 570, 660, 0.55, 0.88, includeShortOmom, 620, 649);
						}
					});
				}
			}
		}
		for (final String template : new String[] {"MNQ", "NQ", "MGC", "ES", "M2K"}) {
			for (final String module : new String[] {"FVG", "ORB", "VWAP", "VRCL", "PDB", "VPB", "MSCALP"}) {
				for (final boolean allowLong : new boolean[] {true, false}) {
					final boolean allowShort = !allowLong;
					add(values, symbol + "_deep_template_" + template.toLowerCase() + "_" + module.toLowerCase() + "_" + sideLabel(allowLong, allowShort), new ScenarioAction() {
						@Override
						public void apply() throws Exception {
							applyTemplateModule(symbol, template, module, allowLong, allowShort, 320.0, 25);
						}
					});
				}
			}
		}
	}

	private static void addMclDeepScenarios(List<Scenario> values) {
		final String symbol = "MCL";
		for (final double risk : new double[] {320.0, 420.0, 520.0}) {
			for (final int maxTrades : new int[] {1, 2, 3}) {
				for (final double volume : new double[] {0.50, 0.70, 0.90}) {
					for (final double reward : new double[] {0.75, 0.85, 1.0}) {
						add(values, symbol + "_deep_aft_long_900_920_t" + maxTrades + "_r" + compact(risk) + "_v" + compact(volume) + "_rr" + compact(reward), new ScenarioAction() {
							@Override
							public void apply() throws Exception {
								applyMclStack(risk, riskContracts(risk, 18), false, 870, 899, 1, 22.0, 0.65, 0.8, true, 900, 920, maxTrades, volume, reward, 35.0, false, false, false, false);
							}
						});
					}
				}
			}
		}
		for (final double risk : new double[] {320.0, 420.0, 520.0}) {
			for (final int maxTrades : new int[] {1, 2}) {
				for (final int[] window : new int[][] {{870, 899}, {860, 899}, {870, 889}}) {
					for (final double volume : new double[] {0.45, 0.65, 0.80}) {
						for (final double reward : new double[] {0.8, 1.0}) {
							add(values, symbol + "_deep_cmom_short_" + window[0] + "_" + window[1] + "_t" + maxTrades + "_r" + compact(risk) + "_v" + compact(volume) + "_rr" + compact(reward), new ScenarioAction() {
								@Override
								public void apply() throws Exception {
									applyMclStack(risk, riskContracts(risk, 18), true, window[0], window[1], maxTrades, 18.0, volume, reward, false, 900, 920, 2, 0.70, 0.85, 35.0, false, false, false, false);
								}
							});
						}
					}
				}
			}
		}
		for (final double risk : new double[] {320.0, 420.0, 520.0}) {
			for (final boolean includeOrbLate : new boolean[] {false, true}) {
				for (final boolean includeFvgLong : new boolean[] {false, true}) {
					for (final boolean includeVwapShort : new boolean[] {false, true}) {
						if (!includeOrbLate && !includeFvgLong && !includeVwapShort) {
							continue;
						}
						add(values, symbol + "_deep_stack_aft_cmom" + (includeOrbLate ? "_orblate" : "") + (includeFvgLong ? "_fvg" : "") + (includeVwapShort ? "_vwap" : "") + "_r" + compact(risk), new ScenarioAction() {
							@Override
							public void apply() throws Exception {
								applyMclStack(risk, riskContracts(risk, 18), true, 870, 899, 1, 18.0, 0.65, 0.8, true, 900, 920, 2, 0.70, 0.85, 35.0, includeOrbLate, includeFvgLong, includeVwapShort, false);
							}
						});
					}
				}
			}
		}
		for (final String template : new String[] {"MES", "MNQ", "NQ", "ES", "M2K"}) {
			for (final String module : new String[] {"FVG", "ORB", "ORB_LATE", "VWAP", "VRCL", "PDB", "KREV", "MSCALP"}) {
				for (final boolean allowLong : new boolean[] {true, false}) {
					final boolean allowShort = !allowLong;
					add(values, symbol + "_deep_template_" + template.toLowerCase() + "_" + module.toLowerCase() + "_" + sideLabel(allowLong, allowShort), new ScenarioAction() {
						@Override
						public void apply() throws Exception {
							applyTemplateModule(symbol, template, module, allowLong, allowShort, 420.0, 25);
						}
					});
				}
			}
		}
	}

	private static void add(List<Scenario> values, final String name, final ScenarioAction action) {
		values.add(new Scenario() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public void apply() throws Exception {
				action.apply();
			}
		});
	}

	private static Scenario scenarioByName(String name) {
		for (Scenario scenario : scenarios()) {
			if (scenario.name().equals(name)) {
				return scenario;
			}
		}
		throw new IllegalArgumentException("Unknown scenario " + name);
	}

	private static void seedPresetRows() throws Exception {
		applyDisabled("MYM");
		applyDisabled("MCL");
	}

	private static void saveDisabledPresetRows(String symbol, String slot) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MNQ", CONTROL_SLOT);
		disableAll(settings);
		FuturesManager.saveFuturesStrategySettings(symbol, slot, settings);
		upsertRisk(slot, symbol, 400.0, 50);
	}

	private static void applyAcceptedWipTrim() {
		FuturesManager.FuturesStrategySettings mes = FuturesManager.loadFuturesStrategySettings("MES", WIP_SLOT);
		mes.valueAreaReclaim.enabled = false;
		mes.microScalp.enabled = false;
		FuturesManager.saveFuturesStrategySettings("MES", WIP_SLOT, mes);
	}

	private static void applyDisabled(String symbol) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MNQ", WIP_SLOT);
		disableAll(settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, symbol, 400.0, 50);
	}

	private static void applyTemplate(String symbol, String templateSymbol, double risk, int maxContracts) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(templateSymbol, WIP_SLOT);
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, symbol, risk, maxContracts);
	}

	private static void applyOpeningOnly(String symbol, String templateSymbol, double risk, int maxContracts) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(templateSymbol, WIP_SLOT);
		disableAll(settings);
		settings.openingMomentum.enabled = true;
		settings.openingMomentum.maxTradesPerDay = 3;
		settings.allowOpeningMomentumLongs = true;
		settings.allowOpeningMomentumShorts = true;
		settings.openingMomentumVolumeRatio = "MCL".equals(symbol) ? 0.45 : 0.55;
		settings.openingMomentumRewardRisk = "MCL".equals(symbol) ? 0.85 : 0.9;
		settings.openingMomentumMaxRiskTicks = "MYM".equals(symbol) ? 120.0 : 42.0;
		settings.openingMomentumMaxHoldBars = 70;
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, symbol, risk, maxContracts);
	}

	private static void applyOpeningClose(String symbol, String templateSymbol, double risk, int maxContracts) throws Exception {
		applyOpeningOnly(symbol, templateSymbol, risk, maxContracts);
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.closeMomentum.enabled = true;
		settings.closeMomentum.maxTradesPerDay = 1;
		settings.allowCloseMomentumLongs = true;
		settings.allowCloseMomentumShorts = true;
		settings.closeMomentumRewardRisk = "MCL".equals(symbol) ? 0.8 : 0.85;
		settings.closeMomentumVolumeRatio = 0.65;
		settings.closeMomentumMinMoveTicks = "MYM".equals(symbol) ? 90.0 : 22.0;
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
	}

	private static void applyCloseMomentum14(String symbol, String templateSymbol, double risk, int maxContracts, boolean allowLong, boolean allowShort) throws Exception {
		applyCloseMomentumWindow(symbol, templateSymbol, risk, maxContracts, allowLong, allowShort, 870, 899);
	}

	private static void applyCloseMomentumWindow(String symbol, String templateSymbol, double risk, int maxContracts, boolean allowLong, boolean allowShort, int startMinute, int endMinute) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(templateSymbol, WIP_SLOT);
		disableAll(settings);
		settings.allowShorts = allowShort;
		settings.closeMomentum.enabled = true;
		settings.closeMomentum.maxTradesPerDay = 1;
		settings.allowCloseMomentumLongs = allowLong;
		settings.allowCloseMomentumShorts = allowShort;
		settings.closeMomentumLongStartMinute = startMinute;
		settings.closeMomentumLongEndMinute = endMinute;
		settings.closeMomentumShortStartMinute = startMinute;
		settings.closeMomentumShortEndMinute = endMinute;
		settings.closeMomentumVolumeRatio = "MCL".equals(symbol) ? 0.65 : 0.7;
		settings.closeMomentumRewardRisk = "MCL".equals(symbol) ? 0.8 : 0.85;
		settings.closeMomentumMinMoveTicks = "MYM".equals(symbol) ? 16.0 : 22.0;
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, symbol, risk, maxContracts);
	}

	private static void applyCloseMomentumCustom(String symbol, double risk, int maxContracts, boolean allowLong, boolean allowShort, int startMinute, int endMinute, int maxTrades, double minMoveTicks, double volumeRatio, double rewardRisk) throws Exception {
		FuturesManager.FuturesStrategySettings settings = disabledTemplate(symbol);
		settings.allowShorts = allowShort;
		settings.closeMomentum.enabled = true;
		settings.closeMomentum.maxTradesPerDay = maxTrades;
		settings.allowCloseMomentumLongs = allowLong;
		settings.allowCloseMomentumShorts = allowShort;
		settings.closeMomentumLongStartMinute = startMinute;
		settings.closeMomentumLongEndMinute = endMinute;
		settings.closeMomentumShortStartMinute = startMinute;
		settings.closeMomentumShortEndMinute = endMinute;
		settings.closeMomentumVolumeRatio = volumeRatio;
		settings.closeMomentumRewardRisk = rewardRisk;
		settings.closeMomentumMinMoveTicks = minMoveTicks;
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, symbol, risk, maxContracts);
	}

	private static void applyMymStack(
		double risk,
		int maxContracts,
		boolean enableCloseMomentum,
		int closeStart,
		int closeEnd,
		int closeMaxTrades,
		double closeMinMoveTicks,
		double closeVolumeRatio,
		double closeRewardRisk,
		boolean enableOpeningLong,
		int openingLongStart,
		int openingLongEnd,
		double openingLongVolumeRatio,
		double openingLongRewardRisk,
		boolean enableOpeningShort,
		int openingShortStart,
		int openingShortEnd
	) throws Exception {
		applyMymStack(risk, maxContracts, enableCloseMomentum, closeStart, closeEnd, closeMaxTrades, closeMinMoveTicks, closeVolumeRatio, closeRewardRisk, enableOpeningLong, openingLongStart, openingLongEnd, openingLongVolumeRatio, openingLongRewardRisk, enableOpeningShort, openingShortStart, openingShortEnd, 0.55, 0.88);
	}

	private static void applyMymStack(
		double risk,
		int maxContracts,
		boolean enableCloseMomentum,
		int closeStart,
		int closeEnd,
		int closeMaxTrades,
		double closeMinMoveTicks,
		double closeVolumeRatio,
		double closeRewardRisk,
		boolean enableOpeningLong,
		int openingLongStart,
		int openingLongEnd,
		double openingLongVolumeRatio,
		double openingLongRewardRisk,
		boolean enableOpeningShort,
		int openingShortStart,
		int openingShortEnd,
		double openingShortVolumeRatio,
		double openingShortRewardRisk
	) throws Exception {
		applyStack("MYM", risk, maxContracts, new SettingsConfigurer() {
			@Override
			public void configure(FuturesManager.FuturesStrategySettings settings) {
				if (enableCloseMomentum) {
					settings.allowShorts = true;
					settings.closeMomentum.enabled = true;
					settings.closeMomentum.maxTradesPerDay = closeMaxTrades;
					settings.allowCloseMomentumLongs = false;
					settings.allowCloseMomentumShorts = true;
					settings.closeMomentumShortStartMinute = closeStart;
					settings.closeMomentumShortEndMinute = closeEnd;
					settings.closeMomentumLongStartMinute = closeStart;
					settings.closeMomentumLongEndMinute = closeEnd;
					settings.closeMomentumMinMoveTicks = closeMinMoveTicks;
					settings.closeMomentumVolumeRatio = closeVolumeRatio;
					settings.closeMomentumRewardRisk = closeRewardRisk;
				}
				if (enableOpeningLong || enableOpeningShort) {
					settings.openingMomentum.enabled = true;
					settings.openingMomentum.maxTradesPerDay = enableOpeningLong && enableOpeningShort ? 5 : 3;
					settings.openingMomentumAllowMultiplePerSide = true;
					settings.openingMomentumRangeMinutes = 10;
					settings.openingMomentumBucketMinutes = 15;
					settings.openingMomentumLongVolumeRatio = openingLongVolumeRatio;
					settings.openingMomentumShortVolumeRatio = openingShortVolumeRatio;
					settings.openingMomentumRewardRisk = Math.max(openingLongRewardRisk, openingShortRewardRisk);
					settings.openingMomentumMaxRiskTicks = 150.0;
					settings.openingMomentumMaxHoldBars = 90;
					settings.allowOpeningMomentumLongs = enableOpeningLong;
					settings.allowOpeningMomentumShorts = enableOpeningShort;
					settings.openingMomentumLongStartMinute = openingLongStart;
					settings.openingMomentumLongEndMinute = openingLongEnd;
					settings.openingMomentumShortStartMinute = openingShortStart;
					settings.openingMomentumShortEndMinute = openingShortEnd;
					settings.allowShorts = settings.allowShorts || enableOpeningShort;
				}
			}
		});
	}

	private static void applyMymOrbM2kStack(double risk, boolean includeOpeningShort, boolean includeWinnerFollowThrough) throws Exception {
		applyMymOrbM2kStack(risk, riskContracts(risk, 25), includeOpeningShort, includeWinnerFollowThrough);
	}

	private static void applyMymOrbM2kStack(double risk, int maxContracts, boolean includeOpeningShort, boolean includeWinnerFollowThrough) throws Exception {
		applyStack("MYM", risk, maxContracts, new SettingsConfigurer() {
			@Override
			public void configure(FuturesManager.FuturesStrategySettings settings) {
				settings.orb.enabled = true;
				settings.orb.maxTradesPerDay = 1;
				settings.allowOrbLongs = true;
				settings.allowOrbShorts = false;
				settings.enableOrbRetest = false;
				settings.enableCompressedOrbBreakout = true;
				settings.maxInitialRiskTicks = Math.max(settings.maxInitialRiskTicks, 180.0);
				settings.orbCompressedMaxRiskTicks = Math.max(settings.orbCompressedMaxRiskTicks, 140.0);

				settings.allowShorts = true;
				settings.closeMomentum.enabled = true;
				settings.closeMomentum.maxTradesPerDay = 1;
				settings.allowCloseMomentumLongs = false;
				settings.allowCloseMomentumShorts = true;
				settings.closeMomentumShortStartMinute = 890;
				settings.closeMomentumShortEndMinute = 909;
				settings.closeMomentumLongStartMinute = 890;
				settings.closeMomentumLongEndMinute = 909;
				settings.closeMomentumMinMoveTicks = 12.0;
				settings.closeMomentumVolumeRatio = 0.70;
				settings.closeMomentumRewardRisk = 0.85;

				settings.openingMomentum.enabled = true;
				settings.openingMomentum.maxTradesPerDay = includeOpeningShort ? 5 : 3;
				settings.openingMomentumAllowMultiplePerSide = true;
				settings.openingMomentumRangeMinutes = 10;
				settings.openingMomentumBucketMinutes = 15;
				settings.openingMomentumLongVolumeRatio = 0.55;
				settings.openingMomentumShortVolumeRatio = 0.55;
				settings.openingMomentumRewardRisk = 0.88;
				settings.openingMomentumMaxRiskTicks = 150.0;
				settings.openingMomentumMaxHoldBars = 90;
				settings.allowOpeningMomentumLongs = true;
				settings.allowOpeningMomentumShorts = includeOpeningShort;
				settings.openingMomentumLongStartMinute = 570;
				settings.openingMomentumLongEndMinute = 660;
				settings.openingMomentumShortStartMinute = 620;
				settings.openingMomentumShortEndMinute = 649;

				if (includeWinnerFollowThrough) {
					settings.winnerFollowThrough.enabled = true;
					settings.winnerFollowThrough.maxTradesPerDay = 4;
					settings.allowWinnerFollowThroughLongs = true;
					settings.allowWinnerFollowThroughShorts = true;
					settings.winnerFollowThroughSourceCodes = "ORB,OMOM,CMOM";
					settings.winnerFollowThroughStartMinute = 570;
					settings.winnerFollowThroughEndMinute = 920;
					settings.winnerFollowThroughDelayBars = 1;
					settings.winnerFollowThroughMinSourcePnl = 0.0;
					settings.winnerFollowThroughMinVolumeRatio = 0.55;
					settings.winnerFollowThroughMaxRiskTicks = 40.0;
					settings.winnerFollowThroughRewardRisk = 0.70;
					settings.winnerFollowThroughMaxHoldBars = 10;
				}
			}
		});
	}

	private static void applyMclStack(
		double risk,
		int maxContracts,
		boolean enableCloseMomentum,
		int closeStart,
		int closeEnd,
		int closeMaxTrades,
		double closeMinMoveTicks,
		double closeVolumeRatio,
		double closeRewardRisk,
		boolean enableAfternoonLong,
		int afternoonStart,
		int afternoonEnd,
		int afternoonMaxTrades,
		double afternoonVolumeRatio,
		double afternoonRewardRisk,
		double afternoonMaxRiskTicks,
		boolean includeLateOrbLong,
		boolean includeFvgLong,
		boolean includeVwapShort,
		boolean includeVwapReclaimShort
	) throws Exception {
		applyStack("MCL", risk, maxContracts, new SettingsConfigurer() {
			@Override
			public void configure(FuturesManager.FuturesStrategySettings settings) {
				if (enableCloseMomentum) {
					settings.allowShorts = true;
					settings.closeMomentum.enabled = true;
					settings.closeMomentum.maxTradesPerDay = closeMaxTrades;
					settings.allowCloseMomentumLongs = false;
					settings.allowCloseMomentumShorts = true;
					settings.closeMomentumShortStartMinute = closeStart;
					settings.closeMomentumShortEndMinute = closeEnd;
					settings.closeMomentumLongStartMinute = closeStart;
					settings.closeMomentumLongEndMinute = closeEnd;
					settings.closeMomentumMinMoveTicks = closeMinMoveTicks;
					settings.closeMomentumVolumeRatio = closeVolumeRatio;
					settings.closeMomentumRewardRisk = closeRewardRisk;
				}
				if (enableAfternoonLong) {
					settings.afternoonContinuation.enabled = true;
					settings.afternoonContinuation.maxTradesPerDay = afternoonMaxTrades;
					settings.allowAfternoonContinuationLongs = true;
					settings.allowAfternoonContinuationShorts = false;
					settings.afternoonStartMinute = afternoonStart;
					settings.afternoonEndMinute = afternoonEnd;
					settings.afternoonLongStartMinute = afternoonStart;
					settings.afternoonLongEndMinute = afternoonEnd;
					settings.afternoonShortStartMinute = afternoonStart;
					settings.afternoonShortEndMinute = afternoonEnd;
					settings.afternoonMinVolumeRatio = afternoonVolumeRatio;
					settings.afternoonRewardRisk = afternoonRewardRisk;
					settings.afternoonMaxRiskTicks = afternoonMaxRiskTicks;
					settings.afternoonMaxHoldBars = 35;
				}
				if (includeLateOrbLong) {
					settings.orb.enabled = true;
					settings.orb.maxTradesPerDay = 1;
					settings.allowOrbLongs = true;
					settings.allowOrbShorts = false;
					settings.enableOrbRetest = false;
					settings.enableCompressedOrbBreakout = true;
					settings.orbLongSkipStartMinute = 570;
					settings.orbLongSkipEndMinute = 659;
					settings.orbCompressedMaxRiskTicks = 55.0;
					settings.maxInitialRiskTicks = Math.max(settings.maxInitialRiskTicks, 70.0);
				}
				if (includeFvgLong) {
					settings.fvg.enabled = true;
					settings.fvg.maxTradesPerDay = 2;
					settings.allowFvgLongs = true;
					settings.allowFvgShorts = false;
					settings.fvgStartMinute = 660;
					settings.fvgEndMinute = 839;
					settings.fvgMinVolumeRatio = 0.45;
					settings.fvgMinRiskTicks = 8.0;
					settings.fvgMaxRiskTicks = 36.0;
					settings.fvgRewardRisk = 1.0;
					settings.fvgMaxHoldBars = 18;
				}
				if (includeVwapShort) {
					settings.allowShorts = true;
					settings.vwapPullback.enabled = true;
					settings.vwapPullback.maxTradesPerDay = 2;
					settings.allowVwapPullbackLongs = false;
					settings.allowVwapPullbackShorts = true;
					settings.vwapStartMinute = 600;
					settings.vwapEndMinute = 899;
					settings.vwapMinVolumeRatio = 0.7;
					settings.vwapMaxRiskTicks = 34.0;
					settings.minRewardRisk = Math.max(settings.minRewardRisk, 1.1);
				}
				if (includeVwapReclaimShort) {
					settings.allowShorts = true;
					settings.vwapReclaim.enabled = true;
					settings.vwapReclaim.maxTradesPerDay = 2;
					settings.allowVwapReclaimLongs = false;
					settings.allowVwapReclaimShorts = true;
					settings.vwapStartMinute = 600;
					settings.vwapEndMinute = 899;
					settings.vwapReclaimMinVolumeRatio = 0.7;
					settings.vwapReclaimMaxRiskTicks = 30.0;
					settings.vwapReclaimRewardRisk = 0.9;
				}
			}
		});
	}

	private static void applyStack(String symbol, double risk, int maxContracts, SettingsConfigurer configurer) throws Exception {
		FuturesManager.FuturesStrategySettings settings = disabledTemplate(symbol);
		configurer.configure(settings);
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, symbol, risk, maxContracts);
	}

	private static void enableWinnerFollowThrough(String symbol, boolean allowLong, boolean allowShort, String sourceCodes, double maxRiskTicks, double rewardRisk, int maxTrades) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.winnerFollowThrough.enabled = true;
		settings.winnerFollowThrough.maxTradesPerDay = maxTrades;
		settings.allowWinnerFollowThroughLongs = allowLong;
		settings.allowWinnerFollowThroughShorts = allowShort;
		settings.winnerFollowThroughSourceCodes = sourceCodes;
		settings.winnerFollowThroughStartMinute = 570;
		settings.winnerFollowThroughEndMinute = 920;
		settings.winnerFollowThroughDelayBars = 1;
		settings.winnerFollowThroughMinSourcePnl = 0.0;
		settings.winnerFollowThroughMinVolumeRatio = 0.55;
		settings.winnerFollowThroughMaxRiskTicks = maxRiskTicks;
		settings.winnerFollowThroughRewardRisk = rewardRisk;
		settings.winnerFollowThroughMaxHoldBars = 10;
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
	}

	private static void enableWinnerFollowThroughQuality(
		String symbol,
		boolean allowLong,
		boolean allowShort,
		String sourceCodes,
		double maxRiskTicks,
		double rewardRisk,
		int maxTrades,
		double minSourcePnl,
		double minVolumeRatio,
		double minBodyPct,
		double longCloseLocation,
		double shortCloseLocation,
		int maxHoldBars
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.winnerFollowThrough.enabled = true;
		settings.winnerFollowThrough.maxTradesPerDay = maxTrades;
		settings.allowWinnerFollowThroughLongs = allowLong;
		settings.allowWinnerFollowThroughShorts = allowShort;
		settings.allowShorts = settings.allowShorts || allowShort;
		settings.winnerFollowThroughSourceCodes = sourceCodes;
		settings.winnerFollowThroughStartMinute = 570;
		settings.winnerFollowThroughEndMinute = 920;
		settings.winnerFollowThroughDelayBars = 1;
		settings.winnerFollowThroughMinSourcePnl = minSourcePnl;
		settings.winnerFollowThroughMinVolumeRatio = minVolumeRatio;
		settings.winnerFollowThroughMaxRiskTicks = maxRiskTicks;
		settings.winnerFollowThroughRewardRisk = rewardRisk;
		settings.winnerFollowThroughMinTrendSlopeTicks = 0.25;
		settings.winnerFollowThroughMinBodyPct = minBodyPct;
		settings.winnerFollowThroughLongCloseLocation = longCloseLocation;
		settings.winnerFollowThroughShortCloseLocation = shortCloseLocation;
		settings.winnerFollowThroughLongMinRsi = 49.0;
		settings.winnerFollowThroughShortMaxRsi = 51.0;
		settings.winnerFollowThroughMaxHoldBars = maxHoldBars;
		saveTunedSettings(symbol, settings);
	}

	private static void enableMicroEchoQuality(
		String symbol,
		boolean allowLong,
		boolean allowShort,
		int maxTrades,
		int startMinute,
		int endMinute,
		int bucketMinutes,
		int delayMinutes,
		int maxDelays,
		double maxRiskTicks,
		double rewardRisk,
		double minVolumeRatio,
		double minTrendSlopeTicks
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.microEcho.enabled = true;
		settings.microEcho.maxTradesPerDay = maxTrades;
		settings.allowMicroEchoLongs = allowLong;
		settings.allowMicroEchoShorts = allowShort;
		settings.allowShorts = settings.allowShorts || allowShort;
		settings.microEchoStartMinute = startMinute;
		settings.microEchoEndMinute = endMinute;
		settings.microEchoBucketMinutes = bucketMinutes;
		settings.microEchoDelayMinutes = delayMinutes;
		settings.microEchoMaxDelays = maxDelays;
		settings.microEchoMaxRiskTicks = maxRiskTicks;
		settings.microEchoRewardRisk = rewardRisk;
		settings.microEchoMinVolumeRatio = minVolumeRatio;
		settings.microEchoMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.microEchoMaxHoldBars = 8;
		settings.microEchoMinRealizedDayPnl = 0.0;
		saveTunedSettings(symbol, settings);
	}

	private static void enableMicroShadowQuality(
		String symbol,
		boolean allowLong,
		boolean allowShort,
		int maxTrades,
		int startMinute,
		int endMinute,
		int bucketMinutes,
		double maxRiskTicks,
		double rewardRisk,
		double minVolumeRatio,
		double minTrendSlopeTicks,
		int maxHoldBars
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.microShadow.enabled = true;
		settings.microShadow.maxTradesPerDay = maxTrades;
		settings.allowMicroShadowLongs = allowLong;
		settings.allowMicroShadowShorts = allowShort;
		settings.allowShorts = settings.allowShorts || allowShort;
		settings.microShadowStartMinute = startMinute;
		settings.microShadowEndMinute = endMinute;
		settings.microShadowBucketMinutes = bucketMinutes;
		settings.microShadowMaxRiskTicks = maxRiskTicks;
		settings.microShadowRewardRisk = rewardRisk;
		settings.microShadowMinVolumeRatio = minVolumeRatio;
		settings.microShadowMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.microShadowMaxHoldBars = maxHoldBars;
		saveTunedSettings(symbol, settings);
	}

	private static void enableMicroScalpQuality(
		String symbol,
		boolean allowLong,
		boolean allowShort,
		int maxTrades,
		int startMinute,
		int endMinute,
		int bucketMinutes,
		double minVolumeRatio,
		double maxRiskTicks,
		double rewardRisk,
		double minBodyPct,
		double minTrendSlopeTicks,
		int maxHoldBars
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.microScalp.enabled = true;
		settings.microScalp.maxTradesPerDay = maxTrades;
		settings.allowMicroScalpLongs = allowLong;
		settings.allowMicroScalpShorts = allowShort;
		settings.allowShorts = settings.allowShorts || allowShort;
		settings.microScalpStartMinute = startMinute;
		settings.microScalpEndMinute = endMinute;
		settings.microScalpLongStartMinute = startMinute;
		settings.microScalpLongEndMinute = endMinute;
		settings.microScalpShortStartMinute = startMinute;
		settings.microScalpShortEndMinute = endMinute;
		settings.microScalpBucketMinutes = bucketMinutes;
		settings.microScalpMinVolumeRatio = minVolumeRatio;
		settings.microScalpMaxRiskTicks = maxRiskTicks;
		settings.microScalpRewardRisk = rewardRisk;
		settings.microScalpMinBodyPct = minBodyPct;
		settings.microScalpMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.microScalpMaxHoldBars = maxHoldBars;
		saveTunedSettings(symbol, settings);
	}

	private static void setWinnerFollowThroughMode(String symbol, boolean enabled, boolean allowLong, boolean allowShort) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.winnerFollowThrough.enabled = enabled;
		settings.allowWinnerFollowThroughLongs = allowLong;
		settings.allowWinnerFollowThroughShorts = allowShort;
		settings.allowShorts = settings.allowShorts || allowShort;
		saveTunedSettings(symbol, settings);
	}

	private static void setMicroEchoMode(String symbol, boolean enabled, boolean allowLong, boolean allowShort) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.microEcho.enabled = enabled;
		settings.allowMicroEchoLongs = allowLong;
		settings.allowMicroEchoShorts = allowShort;
		settings.allowShorts = settings.allowShorts || allowShort;
		saveTunedSettings(symbol, settings);
	}

	private static void disableMicroShadow(String symbol) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.microShadow.enabled = false;
		saveTunedSettings(symbol, settings);
	}

	private static void disableNqOrbLongs() {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("NQ", WIP_SLOT);
		settings.allowOrbLongs = false;
		saveTunedSettings("NQ", settings);
	}

	private static void applyGenericLossCutProfile(String symbol, int bars, double cutR, double minFavorableR) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.enableEarlyLossCut = true;
		settings.earlyLossCutBars = bars;
		settings.earlyLossCutR = cutR;
		settings.earlyLossCutMinFavorableR = minFavorableR;
		saveTunedSettings(symbol, settings);
	}

	private static void boostNewContractMomentumQuality(
		double mymRisk,
		double mclRisk,
		double mymRewardRisk,
		double mclRewardRisk,
		double mymMinBodyPct,
		double mclMinBodyPct
	) throws Exception {
		FuturesManager.FuturesStrategySettings mym = FuturesManager.loadFuturesStrategySettings("MYM", WIP_SLOT);
		mym.openingMomentum.enabled = true;
		mym.openingMomentum.maxTradesPerDay = Math.max(mym.openingMomentum.maxTradesPerDay, 5);
		mym.openingMomentumAllowMultiplePerSide = true;
		mym.allowOpeningMomentumLongs = true;
		mym.allowOpeningMomentumShorts = true;
		mym.allowShorts = true;
		mym.openingMomentumLongVolumeRatio = 0.58;
		mym.openingMomentumShortVolumeRatio = 0.65;
		mym.openingMomentumVolumeRatio = 0.58;
		mym.openingMomentumRewardRisk = mymRewardRisk;
		mym.openingMomentumMinBodyPct = mymMinBodyPct;
		mym.openingMomentumMaxRiskTicks = Math.max(mym.openingMomentumMaxRiskTicks, 150.0);
		mym.closeMomentum.enabled = true;
		mym.closeMomentum.maxTradesPerDay = Math.max(mym.closeMomentum.maxTradesPerDay, 2);
		mym.allowCloseMomentumLongs = false;
		mym.allowCloseMomentumShorts = true;
		mym.closeMomentumMinMoveTicks = 12.0;
		mym.closeMomentumVolumeRatio = 0.68;
		mym.closeMomentumRewardRisk = Math.max(0.90, mymRewardRisk);
		mym.closeMomentumMinBodyPct = mymMinBodyPct;
		mym.sweep.maxTradesPerDay = Math.max(mym.sweep.maxTradesPerDay, 3);
		mym.sweepShortMinBodyPct = Math.max(mym.sweepShortMinBodyPct, mymMinBodyPct);
		saveTunedSettings("MYM", mym);
		upsertRisk(WIP_SLOT, "MYM", mymRisk, 50);

		FuturesManager.FuturesStrategySettings mcl = FuturesManager.loadFuturesStrategySettings("MCL", WIP_SLOT);
		mcl.closeMomentum.enabled = true;
		mcl.closeMomentum.maxTradesPerDay = Math.max(mcl.closeMomentum.maxTradesPerDay, 2);
		mcl.allowCloseMomentumLongs = false;
		mcl.allowCloseMomentumShorts = true;
		mcl.allowShorts = true;
		mcl.closeMomentumMinMoveTicks = 20.0;
		mcl.closeMomentumVolumeRatio = 0.65;
		mcl.closeMomentumRewardRisk = Math.max(0.85, mclRewardRisk);
		mcl.closeMomentumMinBodyPct = mclMinBodyPct;
		mcl.afternoonContinuation.enabled = true;
		mcl.afternoonContinuation.maxTradesPerDay = Math.max(mcl.afternoonContinuation.maxTradesPerDay, 3);
		mcl.allowAfternoonContinuationLongs = true;
		mcl.afternoonMinVolumeRatio = 0.75;
		mcl.afternoonRewardRisk = mclRewardRisk;
		mcl.afternoonMinBodyPct = mclMinBodyPct;
		mcl.afternoonMaxRiskTicks = Math.max(mcl.afternoonMaxRiskTicks, 35.0);
		mcl.marketIntradayMomentum.enabled = true;
		mcl.marketIntradayMomentum.maxTradesPerDay = Math.max(mcl.marketIntradayMomentum.maxTradesPerDay, 2);
		mcl.allowMarketIntradayMomentumLongs = true;
		mcl.allowMarketIntradayMomentumShorts = false;
		mcl.marketIntradayMomentumRewardRisk = Math.max(0.85, mclRewardRisk);
		mcl.marketImpulsePullbackRewardRisk = Math.max(0.85, mclRewardRisk);
		mcl.marketIntradayMomentumMinVolumeRatio = 0.70;
		saveTunedSettings("MCL", mcl);
		upsertRisk(WIP_SLOT, "MCL", mclRisk, 50);
	}

	private static void applyM2kProfitScale(double risk, double openingRiskMultiplier, double rewardRisk, double minBodyPct, boolean lossCut, boolean adaptive) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("M2K", WIP_SLOT);
		settings.openingMomentumPortfolioRiskMultiplier = openingRiskMultiplier;
		settings.openingMomentumRewardRisk = rewardRisk;
		settings.openingMomentumMinBodyPct = minBodyPct;
		settings.openingMomentumMaxRiskTicks = Math.max(settings.openingMomentumMaxRiskTicks, 90.0);
		settings.openingMomentumAllowMultiplePerSide = true;
		settings.closeMomentumRewardRisk = Math.max(settings.closeMomentumRewardRisk, rewardRisk);
		settings.marketIntradayMomentumRewardRisk = Math.max(settings.marketIntradayMomentumRewardRisk, rewardRisk);
		settings.enableAdaptiveExits = adaptive;
		if (adaptive) {
			settings.adaptiveMinVolumeRatio = 1.05;
			settings.adaptiveMinBodyPct = minBodyPct;
			settings.adaptiveTrendTargetBoost = 0.20;
			settings.adaptiveVolumeTargetBoost = 0.15;
			settings.adaptiveBodyTargetBoost = 0.10;
			settings.adaptiveMaxRewardRisk = 1.8;
		}
		if (lossCut) {
			settings.enableEarlyLossCut = true;
			settings.earlyLossCutBars = 8;
			settings.earlyLossCutR = 0.55;
			settings.earlyLossCutMinFavorableR = 0.15;
		}
		saveTunedSettings("M2K", settings);
		upsertRisk(WIP_SLOT, "M2K", risk, 50);
	}

	private static void applyMesProfitScale(double risk, double openingRiskMultiplier, double rewardRisk, double minBodyPct, boolean disableShadow) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MES", WIP_SLOT);
		settings.openingMomentumPortfolioRiskMultiplier = openingRiskMultiplier;
		settings.openingMomentumRewardRisk = rewardRisk;
		settings.openingMomentumMinBodyPct = minBodyPct;
		settings.openingMomentumMaxRiskTicks = Math.max(settings.openingMomentumMaxRiskTicks, 80.0);
		settings.closeMomentumRewardRisk = Math.max(settings.closeMomentumRewardRisk, rewardRisk);
		settings.afternoonRewardRisk = Math.max(settings.afternoonRewardRisk, rewardRisk);
		settings.afternoonMinBodyPct = Math.max(settings.afternoonMinBodyPct, minBodyPct);
		settings.enableAdaptiveExits = true;
		settings.adaptiveMinVolumeRatio = 1.08;
		settings.adaptiveMinBodyPct = minBodyPct;
		settings.adaptiveMaxRewardRisk = 1.9;
		settings.enableEarlyLossCut = true;
		settings.earlyLossCutBars = 10;
		settings.earlyLossCutR = 0.60;
		settings.earlyLossCutMinFavorableR = 0.15;
		if (disableShadow) {
			settings.microShadow.enabled = false;
		}
		saveTunedSettings("MES", settings);
		upsertRisk(WIP_SLOT, "MES", risk, 50);
	}

	private static void applyNqQualityRepair(boolean disableOrbLongs, boolean disableImpulsePullbacks, double fvgRewardRisk, double impulseRewardRisk, double risk) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("NQ", WIP_SLOT);
		if (disableOrbLongs) {
			settings.allowOrbLongs = false;
		}
		if (disableImpulsePullbacks) {
			settings.marketImpulsePullbackSkipStartMinute = 615;
			settings.marketImpulsePullbackSkipEndMinute = 885;
		}
		settings.fvgRewardRisk = Math.max(settings.fvgRewardRisk, fvgRewardRisk);
		settings.fvgMaxRiskTicks = Math.max(settings.fvgMaxRiskTicks, 60.0);
		settings.marketIntradayMomentumRewardRisk = Math.max(settings.marketIntradayMomentumRewardRisk, impulseRewardRisk);
		settings.marketImpulsePullbackRewardRisk = Math.max(settings.marketImpulsePullbackRewardRisk, impulseRewardRisk);
		settings.enableAdaptiveExits = true;
		settings.adaptiveMinVolumeRatio = 1.15;
		settings.adaptiveMinBodyPct = 35.0;
		settings.adaptiveMaxRewardRisk = 2.3;
		settings.enableEarlyLossCut = true;
		settings.earlyLossCutBars = 8;
		settings.earlyLossCutR = 0.55;
		settings.earlyLossCutMinFavorableR = 0.15;
		saveTunedSettings("NQ", settings);
		upsertRisk(WIP_SLOT, "NQ", risk, 1);
	}

	private static void applyEsQualityExpansion(double risk, boolean addFvg, boolean addPdb, boolean addWinnerFollowThrough) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("ES", WIP_SLOT);
		if (addFvg) {
			settings.fvg.enabled = true;
			settings.fvg.maxTradesPerDay = Math.max(settings.fvg.maxTradesPerDay, 2);
			settings.allowFvgLongs = true;
			settings.allowFvgShorts = true;
			settings.fvgMinWidthTicks = 5.0;
			settings.fvgMinVolumeRatio = 0.65;
			settings.fvgMaxRiskTicks = 32.0;
			settings.fvgRewardRisk = 1.15;
		}
		if (addPdb) {
			settings.priorDayBreakout.enabled = true;
			settings.priorDayBreakout.maxTradesPerDay = Math.max(settings.priorDayBreakout.maxTradesPerDay, 2);
			settings.allowPriorDayBreakoutLongs = true;
			settings.allowPriorDayBreakoutShorts = false;
			settings.priorDayBreakoutMinBreakTicks = 10.0;
			settings.priorDayBreakoutRetestTicks = 4.0;
			settings.priorDayBreakoutMinVolumeRatio = 0.75;
			settings.priorDayBreakoutMaxRiskTicks = 34.0;
			settings.priorDayBreakoutRewardRisk = 1.05;
		}
		settings.enableAdaptiveExits = true;
		settings.adaptiveMinVolumeRatio = 1.10;
		settings.adaptiveMinBodyPct = 30.0;
		settings.adaptiveMaxRewardRisk = 2.0;
		saveTunedSettings("ES", settings);
		if (addWinnerFollowThrough) {
			enableWinnerFollowThroughQuality("ES", true, true, "ORB,VWAP,SWEEP,PDB,FVG", 32.0, 0.90, 3, 150.0, 0.70, 16.0, 0.55, 0.45, 12);
		}
		upsertRisk(WIP_SLOT, "ES", risk, 1);
	}

	private static void setFrequencyRiskMultipliers(String symbol, double shadowMultiplier, double echoMultiplier, double winnerFollowThroughMultiplier) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		if (shadowMultiplier >= 0.0) {
			settings.microShadowPortfolioRiskMultiplier = shadowMultiplier;
		}
		if (echoMultiplier >= 0.0) {
			settings.microEchoPortfolioRiskMultiplier = echoMultiplier;
		}
		if (winnerFollowThroughMultiplier >= 0.0) {
			settings.winnerFollowThroughPortfolioRiskMultiplier = winnerFollowThroughMultiplier;
		}
		saveTunedSettings(symbol, settings);
	}

	private static void setMicroShadowSourceCodes(String symbol, String sourceCodes) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.microShadowSourceCodes = sourceCodes == null ? "" : sourceCodes;
		saveTunedSettings(symbol, settings);
	}

	private static void applyNqMomentumHealth(boolean disableImpulsePullbacks, double impulseRewardRisk, double mimRewardRisk, double minVolumeRatio, double maxRiskTicks, boolean adaptive) throws Exception {
		applyNqMomentumHealth(disableImpulsePullbacks, impulseRewardRisk, mimRewardRisk, minVolumeRatio, maxRiskTicks, adaptive, 450.0);
	}

	private static void applyNqMomentumHealth(boolean disableImpulsePullbacks, double impulseRewardRisk, double mimRewardRisk, double minVolumeRatio, double maxRiskTicks, boolean adaptive, double risk) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("NQ", WIP_SLOT);
		settings.marketIntradayMomentum.enabled = true;
		settings.marketIntradayMomentum.maxTradesPerDay = disableImpulsePullbacks ? 1 : Math.max(settings.marketIntradayMomentum.maxTradesPerDay, 2);
		settings.marketImpulsePullbackRewardRisk = Math.max(settings.marketImpulsePullbackRewardRisk, impulseRewardRisk);
		settings.marketIntradayMomentumRewardRisk = Math.max(settings.marketIntradayMomentumRewardRisk, mimRewardRisk);
		settings.marketIntradayMomentumMinVolumeRatio = Math.max(settings.marketIntradayMomentumMinVolumeRatio, minVolumeRatio);
		settings.marketIntradayMomentumMaxRiskTicks = Math.min(settings.marketIntradayMomentumMaxRiskTicks, maxRiskTicks);
		if (disableImpulsePullbacks) {
			settings.marketImpulsePullbackSkipStartMinute = 615;
			settings.marketImpulsePullbackSkipEndMinute = 885;
		}
		settings.enableAdaptiveExits = adaptive;
		if (adaptive) {
			settings.adaptiveMinVolumeRatio = 1.10;
			settings.adaptiveMinBodyPct = 30.0;
			settings.adaptiveMaxRewardRisk = 2.0;
		}
		saveTunedSettings("NQ", settings);
		upsertRisk(WIP_SLOT, "NQ", risk, 1);
	}

	private static void applyNqFvgRewardProfile(double rewardRisk, double minVolumeRatio, double maxRiskTicks, double risk, boolean adaptive) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("NQ", WIP_SLOT);
		settings.fvg.enabled = true;
		settings.fvgRewardRisk = Math.max(settings.fvgRewardRisk, rewardRisk);
		settings.fvgMinVolumeRatio = Math.max(settings.fvgMinVolumeRatio, minVolumeRatio);
		settings.fvgMaxRiskTicks = Math.max(settings.fvgMaxRiskTicks, maxRiskTicks);
		settings.enableAdaptiveExits = adaptive;
		if (adaptive) {
			settings.adaptiveMinVolumeRatio = 1.10;
			settings.adaptiveMinBodyPct = 30.0;
			settings.adaptiveMaxRewardRisk = 2.1;
		}
		saveTunedSettings("NQ", settings);
		upsertRisk(WIP_SLOT, "NQ", risk, 1);
	}

	private static void applyNqOrbShortQuality(double minBodyPct, double maxSignalRangeTicks, double maxBreakoutExtensionTicks, double risk) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("NQ", WIP_SLOT);
		settings.allowOrbLongs = false;
		settings.allowOrbShorts = true;
		settings.orbMinBodyPct = Math.max(settings.orbMinBodyPct, minBodyPct);
		settings.orbMaxSignalRangeTicks = maxSignalRangeTicks;
		settings.orbMaxBreakoutExtensionTicks = maxBreakoutExtensionTicks;
		settings.enableAdaptiveExits = true;
		settings.adaptiveMinVolumeRatio = 1.10;
		settings.adaptiveMinBodyPct = 32.0;
		settings.adaptiveMaxRewardRisk = 2.0;
		saveTunedSettings("NQ", settings);
		upsertRisk(WIP_SLOT, "NQ", risk, 1);
	}

	private static void applyMesAverageWinLift(double openingRiskMultiplier, double rewardRisk, double minBodyPct, double risk, boolean disableShadow) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MES", WIP_SLOT);
		settings.openingMomentumPortfolioRiskMultiplier = openingRiskMultiplier;
		settings.openingMomentumRewardRisk = Math.max(settings.openingMomentumRewardRisk, rewardRisk);
		settings.openingMomentumMinBodyPct = Math.max(settings.openingMomentumMinBodyPct, minBodyPct);
		settings.closeMomentumRewardRisk = Math.max(settings.closeMomentumRewardRisk, rewardRisk);
		settings.afternoonRewardRisk = Math.max(settings.afternoonRewardRisk, rewardRisk);
		settings.enableAdaptiveExits = true;
		settings.adaptiveMinVolumeRatio = 1.08;
		settings.adaptiveMinBodyPct = Math.max(24.0, minBodyPct);
		settings.adaptiveMaxRewardRisk = 1.9;
		if (disableShadow) {
			settings.microShadow.enabled = false;
		}
		saveTunedSettings("MES", settings);
		upsertRisk(WIP_SLOT, "MES", risk, 50);
	}

	private static void applyEsVwapHealth(double minVolumeRatio, double minTrendSlopeTicks, double maxRiskTicks, double minRewardRisk, boolean adaptive) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("ES", WIP_SLOT);
		settings.vwapPullback.enabled = true;
		settings.allowVwapPullbackLongs = false;
		settings.allowVwapPullbackShorts = true;
		settings.vwapMinVolumeRatio = Math.max(settings.vwapMinVolumeRatio, minVolumeRatio);
		settings.vwapMinTrendSlopeTicks = Math.max(settings.vwapMinTrendSlopeTicks, minTrendSlopeTicks);
		settings.vwapMaxRiskTicks = Math.min(settings.vwapMaxRiskTicks, maxRiskTicks);
		settings.minRewardRisk = Math.max(settings.minRewardRisk, minRewardRisk);
		settings.enableAdaptiveExits = adaptive;
		if (adaptive) {
			settings.adaptiveMinVolumeRatio = 1.10;
			settings.adaptiveMinBodyPct = 32.0;
			settings.adaptiveMaxRewardRisk = 2.1;
		}
		saveTunedSettings("ES", settings);
		upsertRisk(WIP_SLOT, "ES", 500.0, 1);
	}

	private static void disableValueArea(String symbol) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.valueAreaReclaim.enabled = false;
		saveTunedSettings(symbol, settings);
	}

	private static void applyMymLiveRules(
		double risk,
		int maxContracts,
		boolean includeOrb,
		boolean includeCloseMomentum,
		double closeMinMoveTicks,
		double closeVolumeRatio,
		boolean includeOpeningLong,
		double orbBodyPct,
		double openingBodyPct,
		double closeBodyPct,
		double openingVolumeRatio,
		double closeRewardRisk,
		double openingRewardRisk
	) throws Exception {
		applyMymOrbM2kStack(risk, maxContracts, false, false);
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MYM", WIP_SLOT);
		settings.orb.enabled = includeOrb;
		settings.orb.maxTradesPerDay = includeOrb ? 1 : 0;
		settings.allowOrbLongs = includeOrb;
		settings.allowOrbShorts = false;
		settings.orbMinBodyPct = Math.max(0.0, orbBodyPct);
		settings.orbMaxSignalRangeTicks = includeOrb ? 60.0 : 0.0;
		settings.orbMaxVwapDistanceTicks = includeOrb ? 5.0 : 0.0;
		settings.orbMaxBreakoutExtensionTicks = 0.0;
		settings.orbMaxTrendSlopeTicks = 0.0;

		settings.closeMomentum.enabled = includeCloseMomentum;
		settings.closeMomentum.maxTradesPerDay = includeCloseMomentum ? 1 : 0;
		settings.allowCloseMomentumLongs = false;
		settings.allowCloseMomentumShorts = includeCloseMomentum;
		settings.closeMomentumShortStartMinute = 890;
		settings.closeMomentumShortEndMinute = 909;
		settings.closeMomentumLongStartMinute = 890;
		settings.closeMomentumLongEndMinute = 909;
		settings.closeMomentumMinMoveTicks = closeMinMoveTicks;
		settings.closeMomentumVolumeRatio = closeVolumeRatio;
		settings.closeMomentumRewardRisk = closeRewardRisk;
		settings.closeMomentumMinBodyPct = Math.max(0.0, closeBodyPct);

		settings.openingMomentum.enabled = includeOpeningLong;
		settings.openingMomentum.maxTradesPerDay = includeOpeningLong ? 3 : 0;
		settings.openingMomentumAllowMultiplePerSide = true;
		settings.allowOpeningMomentumLongs = includeOpeningLong;
		settings.allowOpeningMomentumShorts = false;
		settings.openingMomentumLongStartMinute = 570;
		settings.openingMomentumLongEndMinute = 660;
		settings.openingMomentumLongVolumeRatio = openingVolumeRatio;
		settings.openingMomentumShortVolumeRatio = 0.0;
		settings.openingMomentumVolumeRatio = openingVolumeRatio;
		settings.openingMomentumRewardRisk = openingRewardRisk;
		settings.openingMomentumMinBodyPct = Math.max(0.0, openingBodyPct);

		tuneForSymbol("MYM", settings);
		FuturesManager.saveFuturesStrategySettings("MYM", WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, "MYM", risk, maxContracts);
	}

	private static void applyMclLiveRules(
		double risk,
		int maxContracts,
		boolean includeCloseMomentum,
		boolean includeAfternoon,
		double closeMinMoveTicks,
		double closeVolumeRatio,
		double afternoonVolumeRatio,
		double orbBodyPct,
		double closeBodyPct,
		double afternoonBodyPct
	) throws Exception {
		applyMclStack(risk, maxContracts, includeCloseMomentum, 870, 899, 1, closeMinMoveTicks, closeVolumeRatio, 0.80, includeAfternoon, 900, 920, includeAfternoon ? 2 : 0, afternoonVolumeRatio, 0.85, 35.0, true, false, false, false);
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MCL", WIP_SLOT);
		settings.orbMinBodyPct = Math.max(0.0, orbBodyPct);
		settings.closeMomentumMinBodyPct = Math.max(0.0, closeBodyPct);
		settings.afternoonMinBodyPct = Math.max(0.0, afternoonBodyPct);
		settings.closeMomentumMinMoveTicks = closeMinMoveTicks;
		settings.closeMomentumVolumeRatio = closeVolumeRatio;
		settings.afternoonMinVolumeRatio = afternoonVolumeRatio;
		settings.allowOpeningMomentumLongs = false;
		settings.allowOpeningMomentumShorts = false;
		settings.openingMomentum.enabled = false;
		tuneForSymbol("MCL", settings);
		FuturesManager.saveFuturesStrategySettings("MCL", WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, "MCL", risk, maxContracts);
	}

	private static void applyStrictLiveRulesBase() throws Exception {
		applyMymLiveRules(650.0, 50, true, true, 350.0, 0.70, true, 35.0, 35.0, 35.0, 0.60, 0.85, 0.88);
		applyMclLiveRules(800.0, 50, true, true, 90.0, 0.65, 0.85, 20.0, 0.0, 40.0);
	}

	private static void applyHighProfitLiveRulesBase() throws Exception {
		applyMymLiveRules(650.0, 50, true, true, 350.0, 0.70, true, 45.0, 45.0, 45.0, 0.60, 0.85, 0.88);
		applyMclLiveRules(800.0, 50, true, true, 90.0, 0.65, 0.85, 20.0, 0.0, 40.0);
	}

	private static void saveTunedSettings(String symbol, FuturesManager.FuturesStrategySettings settings) {
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
	}

	private static void enableMymBreadthConfirmation(
		int maxTrades,
		boolean allowLong,
		boolean allowShort,
		int startMinute,
		int endMinute,
		int bucketMinutes,
		int lookbackBars,
		int minAlignedMarkets,
		double maxRiskTicks,
		double rewardRisk,
		double minVolumeRatio,
		double minBodyPct,
		double minTrendSlopeTicks,
		int maxHoldBars
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MYM", WIP_SLOT);
		settings.mymBreadthConfirmation.enabled = true;
		settings.mymBreadthConfirmation.maxTradesPerDay = maxTrades;
		settings.allowMymBreadthLongs = allowLong;
		settings.allowMymBreadthShorts = allowShort;
		settings.allowShorts = settings.allowShorts || allowShort;
		settings.mymBreadthStartMinute = startMinute;
		settings.mymBreadthEndMinute = endMinute;
		settings.mymBreadthBucketMinutes = bucketMinutes;
		settings.mymBreadthLookbackBars = lookbackBars;
		settings.mymBreadthMinAlignedMarkets = minAlignedMarkets;
		settings.mymBreadthMaxRiskTicks = maxRiskTicks;
		settings.mymBreadthRewardRisk = rewardRisk;
		settings.mymBreadthMinVolumeRatio = minVolumeRatio;
		settings.mymBreadthMinBodyPct = minBodyPct;
		settings.mymBreadthMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.mymBreadthMaxHoldBars = maxHoldBars;
		saveTunedSettings("MYM", settings);
	}

	private static void enableMclTrendContinuation(
		int maxTrades,
		boolean allowLong,
		boolean allowShort,
		int startMinute,
		int endMinute,
		int bucketMinutes,
		int lookbackBars,
		double breakoutBufferTicks,
		double minOpenMoveTicks,
		double maxRiskTicks,
		double rewardRisk,
		double minVolumeRatio,
		double minBodyPct,
		double minTrendSlopeTicks,
		int maxHoldBars
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MCL", WIP_SLOT);
		settings.mclTrendContinuation.enabled = true;
		settings.mclTrendContinuation.maxTradesPerDay = maxTrades;
		settings.allowMclTrendLongs = allowLong;
		settings.allowMclTrendShorts = allowShort;
		settings.allowShorts = settings.allowShorts || allowShort;
		settings.mclTrendStartMinute = startMinute;
		settings.mclTrendEndMinute = endMinute;
		settings.mclTrendBucketMinutes = bucketMinutes;
		settings.mclTrendLookbackBars = lookbackBars;
		settings.mclTrendBreakoutBufferTicks = breakoutBufferTicks;
		settings.mclTrendMinOpenMoveTicks = minOpenMoveTicks;
		settings.mclTrendMaxRiskTicks = maxRiskTicks;
		settings.mclTrendRewardRisk = rewardRisk;
		settings.mclTrendMinVolumeRatio = minVolumeRatio;
		settings.mclTrendMinBodyPct = minBodyPct;
		settings.mclTrendMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.mclTrendMaxHoldBars = maxHoldBars;
		saveTunedSettings("MCL", settings);
	}

	private static void enableMclEiaContinuation(
		int maxTrades,
		boolean allowLong,
		boolean allowShort,
		int rangeStartMinute,
		int rangeEndMinute,
		int startMinute,
		int endMinute,
		double bufferTicks,
		double stopTicks,
		double rewardRisk,
		double minVolumeRatio,
		double minBodyPct,
		int maxHoldBars
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MCL", WIP_SLOT);
		settings.mclEiaContinuation.enabled = true;
		settings.mclEiaContinuation.maxTradesPerDay = maxTrades;
		settings.allowMclEiaLongs = allowLong;
		settings.allowMclEiaShorts = allowShort;
		settings.mclEiaRangeStartMinute = rangeStartMinute;
		settings.mclEiaRangeEndMinute = rangeEndMinute;
		settings.mclEiaStartMinute = startMinute;
		settings.mclEiaEndMinute = endMinute;
		settings.mclEiaBreakoutBufferTicks = bufferTicks;
		settings.mclEiaStopTicks = stopTicks;
		settings.mclEiaRewardRisk = rewardRisk;
		settings.mclEiaMinVolumeRatio = minVolumeRatio;
		settings.mclEiaMinBodyPct = minBodyPct;
		settings.mclEiaMaxHoldBars = maxHoldBars;
		saveTunedSettings("MCL", settings);
	}

	private static void enableMclCrudeSessionOpen(
		int maxTrades,
		boolean allowLong,
		boolean allowShort,
		int rangeStartMinute,
		int rangeEndMinute,
		int startMinute,
		int endMinute,
		double bufferTicks,
		double stopTicks,
		double rewardRisk,
		double minVolumeRatio,
		double minBodyPct,
		int maxHoldBars
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MCL", WIP_SLOT);
		settings.mclCrudeSessionOpen.enabled = true;
		settings.mclCrudeSessionOpen.maxTradesPerDay = maxTrades;
		settings.allowMclCrudeOpenLongs = allowLong;
		settings.allowMclCrudeOpenShorts = allowShort;
		settings.mclCrudeOpenRangeStartMinute = rangeStartMinute;
		settings.mclCrudeOpenRangeEndMinute = rangeEndMinute;
		settings.mclCrudeOpenStartMinute = startMinute;
		settings.mclCrudeOpenEndMinute = endMinute;
		settings.mclCrudeOpenBreakoutBufferTicks = bufferTicks;
		settings.mclCrudeOpenStopTicks = stopTicks;
		settings.mclCrudeOpenRewardRisk = rewardRisk;
		settings.mclCrudeOpenMinVolumeRatio = minVolumeRatio;
		settings.mclCrudeOpenMinBodyPct = minBodyPct;
		settings.mclCrudeOpenMaxHoldBars = maxHoldBars;
		saveTunedSettings("MCL", settings);
	}

	private static void enableMymIndexConfirmation(
		int maxTrades,
		boolean allowLong,
		boolean allowShort,
		int startMinute,
		int endMinute,
		int bucketMinutes,
		int lookbackBars,
		double maxRiskTicks,
		double rewardRisk,
		double minVolumeRatio,
		double minBodyPct,
		double minTrendSlopeTicks,
		int maxHoldBars
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MYM", WIP_SLOT);
		settings.mymIndexConfirmation.enabled = true;
		settings.mymIndexConfirmation.maxTradesPerDay = maxTrades;
		settings.allowMymIndexConfirmationLongs = allowLong;
		settings.allowMymIndexConfirmationShorts = allowShort;
		settings.mymIndexConfirmationStartMinute = startMinute;
		settings.mymIndexConfirmationEndMinute = endMinute;
		settings.mymIndexConfirmationBucketMinutes = bucketMinutes;
		settings.mymIndexConfirmationLookbackBars = lookbackBars;
		settings.mymIndexConfirmationMaxRiskTicks = maxRiskTicks;
		settings.mymIndexConfirmationRewardRisk = rewardRisk;
		settings.mymIndexConfirmationMinVolumeRatio = minVolumeRatio;
		settings.mymIndexConfirmationMinBodyPct = minBodyPct;
		settings.mymIndexConfirmationMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.mymIndexConfirmationMaxHoldBars = maxHoldBars;
		saveTunedSettings("MYM", settings);
	}

	private static void enableMymOrbRetest(
		int maxTrades,
		boolean allowLong,
		boolean allowShort,
		int startMinute,
		int endMinute,
		double bufferTicks,
		double retestTicks,
		double maxRiskTicks,
		double rewardRisk,
		double minVolumeRatio,
		double minBodyPct,
		int maxHoldBars
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MYM", WIP_SLOT);
		settings.mymOrbRetest.enabled = true;
		settings.mymOrbRetest.maxTradesPerDay = maxTrades;
		settings.allowMymOrbRetestLongs = allowLong;
		settings.allowMymOrbRetestShorts = allowShort;
		settings.mymOrbRetestStartMinute = startMinute;
		settings.mymOrbRetestEndMinute = endMinute;
		settings.mymOrbRetestBreakoutBufferTicks = bufferTicks;
		settings.mymOrbRetestRetestTicks = retestTicks;
		settings.mymOrbRetestMaxRiskTicks = maxRiskTicks;
		settings.mymOrbRetestRewardRisk = rewardRisk;
		settings.mymOrbRetestMinVolumeRatio = minVolumeRatio;
		settings.mymOrbRetestMinBodyPct = minBodyPct;
		settings.mymOrbRetestMaxHoldBars = maxHoldBars;
		saveTunedSettings("MYM", settings);
	}

	private static void enableLateOrbContinuation(
		String symbol,
		boolean allowLong,
		boolean allowShort,
		int startMinute,
		int endMinute,
		int maxTrades,
		double minVolumeRatio,
		double maxRiskTicks,
		double rewardRisk,
		int maxHoldBars
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.lateOrbContinuation.enabled = true;
		settings.lateOrbContinuation.maxTradesPerDay = maxTrades;
		settings.allowLateOrbContinuationLongs = allowLong;
		settings.allowLateOrbContinuationShorts = allowShort;
		settings.allowShorts = settings.allowShorts || allowShort;
		settings.lateOrbContinuationStartMinute = startMinute;
		settings.lateOrbContinuationEndMinute = endMinute;
		settings.lateOrbContinuationMinVolumeRatio = minVolumeRatio;
		settings.lateOrbContinuationMaxRiskTicks = maxRiskTicks;
		settings.lateOrbContinuationRewardRisk = rewardRisk;
		settings.lateOrbContinuationMaxHoldBars = maxHoldBars;
		saveTunedSettings(symbol, settings);
	}

	private static void enableMarketImpulse(
		String symbol,
		boolean allowLong,
		boolean allowShort,
		int maxTrades,
		double minOpenMoveTicks,
		double minLateMoveTicks,
		double minVolumeRatio,
		double maxRiskTicks,
		double rewardRisk
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.marketIntradayMomentum.enabled = true;
		settings.marketIntradayMomentum.maxTradesPerDay = maxTrades;
		settings.allowMarketIntradayMomentumLongs = allowLong;
		settings.allowMarketIntradayMomentumShorts = allowShort;
		settings.allowShorts = settings.allowShorts || allowShort;
		settings.marketIntradayMomentumMinOpenMoveTicks = minOpenMoveTicks;
		settings.marketIntradayMomentumMinLateMoveTicks = minLateMoveTicks;
		settings.marketIntradayMomentumMinVolumeRatio = minVolumeRatio;
		settings.marketIntradayMomentumMaxRiskTicks = maxRiskTicks;
		settings.marketIntradayMomentumRewardRisk = rewardRisk;
		settings.marketImpulsePullbackRewardRisk = rewardRisk;
		settings.marketImpulsePullbackStartMinute = 615;
		settings.marketImpulsePullbackEndMinute = 885;
		saveTunedSettings(symbol, settings);
	}

	private static void enableVwapPullback(
		String symbol,
		boolean allowLong,
		boolean allowShort,
		int startMinute,
		int endMinute,
		int maxTrades,
		double minVolumeRatio,
		double minTrendSlopeTicks,
		double maxRiskTicks,
		double maxDistanceTicks
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.vwapPullback.enabled = true;
		settings.vwapPullback.maxTradesPerDay = maxTrades;
		settings.allowVwapPullbackLongs = allowLong;
		settings.allowVwapPullbackShorts = allowShort;
		settings.allowShorts = settings.allowShorts || allowShort;
		settings.vwapStartMinute = startMinute;
		settings.vwapEndMinute = endMinute;
		settings.vwapMinVolumeRatio = minVolumeRatio;
		settings.vwapMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.vwapMaxRiskTicks = maxRiskTicks;
		settings.vwapMaxDistanceTicks = maxDistanceTicks;
		settings.minRewardRisk = Math.max(settings.minRewardRisk, 1.0);
		saveTunedSettings(symbol, settings);
	}

	private static void enableVwapReclaim(
		String symbol,
		boolean allowLong,
		boolean allowShort,
		int maxTrades,
		double minVolumeRatio,
		double maxRiskTicks,
		double rewardRisk,
		int bucketMinutes
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.vwapReclaim.enabled = true;
		settings.vwapReclaim.maxTradesPerDay = maxTrades;
		settings.allowVwapReclaimLongs = allowLong;
		settings.allowVwapReclaimShorts = allowShort;
		settings.allowShorts = settings.allowShorts || allowShort;
		settings.vwapReclaimMinVolumeRatio = minVolumeRatio;
		settings.vwapReclaimMaxRiskTicks = maxRiskTicks;
		settings.vwapReclaimRewardRisk = rewardRisk;
		settings.vwapReclaimBucketMinutes = bucketMinutes;
		saveTunedSettings(symbol, settings);
	}

	private static void enablePriorDayBreakout(
		String symbol,
		boolean allowLong,
		boolean allowShort,
		int startMinute,
		int endMinute,
		int maxTrades,
		double minBreakTicks,
		double retestTicks,
		double minVolumeRatio,
		double maxRiskTicks,
		double rewardRisk
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.priorDayBreakout.enabled = true;
		settings.priorDayBreakout.maxTradesPerDay = maxTrades;
		settings.allowPriorDayBreakoutLongs = allowLong;
		settings.allowPriorDayBreakoutShorts = allowShort;
		settings.allowShorts = settings.allowShorts || allowShort;
		settings.priorDayBreakoutStartMinute = startMinute;
		settings.priorDayBreakoutEndMinute = endMinute;
		settings.priorDayBreakoutMinBreakTicks = minBreakTicks;
		settings.priorDayBreakoutRetestTicks = retestTicks;
		settings.priorDayBreakoutMinVolumeRatio = minVolumeRatio;
		settings.priorDayBreakoutMaxRiskTicks = maxRiskTicks;
		settings.priorDayBreakoutRewardRisk = rewardRisk;
		saveTunedSettings(symbol, settings);
	}

	private static void enableFvg(
		String symbol,
		boolean allowLong,
		boolean allowShort,
		int startMinute,
		int endMinute,
		int maxTrades,
		double minWidthTicks,
		double maxRiskTicks,
		double minVolumeRatio,
		double rewardRisk
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.fvg.enabled = true;
		settings.fvg.maxTradesPerDay = maxTrades;
		settings.allowFvgLongs = allowLong;
		settings.allowFvgShorts = allowShort;
		settings.allowShorts = settings.allowShorts || allowShort;
		settings.fvgStartMinute = startMinute;
		settings.fvgEndMinute = endMinute;
		settings.fvgMinWidthTicks = minWidthTicks;
		settings.fvgMaxRiskTicks = maxRiskTicks;
		settings.fvgMinRiskTicks = Math.max(4.0, minWidthTicks);
		settings.fvgMinVolumeRatio = minVolumeRatio;
		settings.fvgRewardRisk = rewardRisk;
		settings.fvgMaxHoldBars = 18;
		saveTunedSettings(symbol, settings);
	}

	private static void enableAfternoonContinuation(
		String symbol,
		boolean allowLong,
		boolean allowShort,
		int startMinute,
		int endMinute,
		int maxTrades,
		double minVolumeRatio,
		double maxRiskTicks,
		double rewardRisk,
		double minBodyPct
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.afternoonContinuation.enabled = true;
		settings.afternoonContinuation.maxTradesPerDay = maxTrades;
		settings.allowAfternoonContinuationLongs = allowLong;
		settings.allowAfternoonContinuationShorts = allowShort;
		settings.allowShorts = settings.allowShorts || allowShort;
		settings.afternoonStartMinute = startMinute;
		settings.afternoonEndMinute = endMinute;
		settings.afternoonLongStartMinute = allowLong ? startMinute : 0;
		settings.afternoonLongEndMinute = allowLong ? endMinute : 0;
		settings.afternoonShortStartMinute = allowShort ? startMinute : 0;
		settings.afternoonShortEndMinute = allowShort ? endMinute : 0;
		settings.afternoonMinVolumeRatio = minVolumeRatio;
		settings.afternoonMaxRiskTicks = maxRiskTicks;
		settings.afternoonRewardRisk = rewardRisk;
		settings.afternoonMinBodyPct = minBodyPct;
		settings.afternoonMaxHoldBars = 35;
		saveTunedSettings(symbol, settings);
	}

	private static void enableLiquiditySweep(
		String symbol,
		boolean allowLong,
		boolean allowShort,
		int maxTrades,
		boolean earlySweep,
		boolean lateSweep,
		double reclaimTicks,
		double closeLocation,
		double minBodyPct
	) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.sweep.enabled = true;
		settings.sweep.maxTradesPerDay = maxTrades;
		settings.allowShorts = settings.allowShorts || allowShort;
		settings.enableEarlySweep = allowLong && earlySweep;
		settings.enableLateSweep = allowLong && lateSweep;
		settings.enableSweepSecondChance = allowLong && earlySweep;
		settings.earlySweepReclaimTicks = reclaimTicks;
		settings.lateSweepReclaimTicks = reclaimTicks;
		settings.sweepCloseLocation = closeLocation;
		settings.lateSweepCloseLocation = closeLocation;
		settings.minBodyPct = minBodyPct;
		if (!allowShort) {
			settings.sweepShortSkipStartMinute = 570;
			settings.sweepShortSkipEndMinute = 930;
		}
		saveTunedSettings(symbol, settings);
	}

	private static void setSweepShortMaxRsi(String symbol, double maxRsi) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.sweepShortMaxRsi = maxRsi;
		saveTunedSettings(symbol, settings);
	}

	private static void setSweepShortMinBody(String symbol, double minBodyPct) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.sweepShortMinBodyPct = minBodyPct;
		saveTunedSettings(symbol, settings);
	}

	private static void setAfternoonMaxSignalRange(String symbol, double maxRangeTicks) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.afternoonMaxSignalRangeTicks = maxRangeTicks;
		saveTunedSettings(symbol, settings);
	}

	private static void applyLiveExitDiscipline(String symbol) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		applyLiveExitDiscipline(settings);
		saveTunedSettings(symbol, settings);
	}

	private static void applyManagedExitProfile(
		String symbol,
		double breakevenTriggerR,
		double trailTriggerR,
		double trailDistanceR,
		double minTrailTicks,
		boolean enableGiveback,
		double givebackTriggerR,
		double givebackR,
		int givebackMinBars
	) {
		if ("BOTH".equals(symbol)) {
			applyManagedExitProfile("MYM", breakevenTriggerR, trailTriggerR, trailDistanceR, minTrailTicks, enableGiveback, givebackTriggerR, givebackR, givebackMinBars);
			applyManagedExitProfile("MCL", breakevenTriggerR, trailTriggerR, trailDistanceR, minTrailTicks, enableGiveback, givebackTriggerR, givebackR, givebackMinBars);
			return;
		}
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.managedStopBreakevenTriggerR = breakevenTriggerR;
		settings.managedStopTrailTriggerR = trailTriggerR;
		settings.managedStopTrailDistanceR = trailDistanceR;
		settings.managedStopMinTrailTicks = minTrailTicks;
		settings.enableManagedGivebackExit = enableGiveback;
		settings.managedGivebackTriggerR = givebackTriggerR;
		settings.managedGivebackR = givebackR;
		settings.managedGivebackMinBars = givebackMinBars;
		saveTunedSettings(symbol, settings);
	}

	private static void applyLossCutProfile(String symbol, int bars, double cutR, double minFavorableR) {
		if ("BOTH".equals(symbol)) {
			applyLossCutProfile("MYM", bars, cutR, minFavorableR);
			applyLossCutProfile("MCL", bars, cutR, minFavorableR);
			return;
		}
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.enableEarlyLossCut = true;
		settings.earlyLossCutBars = bars;
		settings.earlyLossCutR = cutR;
		settings.earlyLossCutMinFavorableR = minFavorableR;
		saveTunedSettings(symbol, settings);
	}

	private static void applyLiveExitDiscipline(FuturesManager.FuturesStrategySettings settings) {
		settings.enableAdaptiveExits = true;
		settings.adaptiveMinVolumeRatio = 1.10;
		settings.adaptiveMinBodyPct = 32.0;
		settings.adaptiveTrendTargetBoost = 0.25;
		settings.adaptiveVolumeTargetBoost = 0.20;
		settings.adaptiveBodyTargetBoost = 0.15;
		settings.adaptiveMaxRewardRisk = 2.2;
		settings.enableEarlyLossCut = true;
		settings.earlyLossCutBars = 10;
		settings.earlyLossCutR = 0.60;
		settings.earlyLossCutMinFavorableR = 0.20;
	}

	private static void applyQualityFilters(String symbol, double orbBodyPct, double openingBodyPct, double closeBodyPct, double afternoonBodyPct, double closeVolumeRatio, double openingVolumeRatio, double afternoonVolumeRatio, double shortMaxRsi) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, WIP_SLOT);
		settings.orbMinBodyPct = Math.max(0.0, orbBodyPct);
		settings.openingMomentumMinBodyPct = Math.max(0.0, openingBodyPct);
		settings.closeMomentumMinBodyPct = Math.max(0.0, closeBodyPct);
		settings.afternoonMinBodyPct = Math.max(0.0, afternoonBodyPct);
		if (closeVolumeRatio > 0.0) {
			settings.closeMomentumVolumeRatio = closeVolumeRatio;
		}
		if (openingVolumeRatio > 0.0) {
			settings.openingMomentumVolumeRatio = openingVolumeRatio;
			settings.openingMomentumLongVolumeRatio = openingVolumeRatio;
			settings.openingMomentumShortVolumeRatio = openingVolumeRatio;
		}
		if (afternoonVolumeRatio > 0.0) {
			settings.afternoonMinVolumeRatio = afternoonVolumeRatio;
		}
		if (shortMaxRsi > 0.0) {
			settings.openingMomentumShortMaxRsi = shortMaxRsi;
		}
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
	}

	private static void applyTemplateModule(String symbol, String templateSymbol, String module, boolean allowLong, boolean allowShort, double risk, int maxContracts) throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(templateSymbol, WIP_SLOT);
		disableAll(settings);
		settings.allowShorts = allowShort;
		if ("ORB".equals(module) || "ORB_LATE".equals(module)) {
			settings.orb.enabled = true;
			settings.orb.maxTradesPerDay = 1;
			settings.allowOrbLongs = allowLong;
			settings.allowOrbShorts = allowShort;
			settings.enableOrbRetest = false;
			settings.enableCompressedOrbBreakout = true;
			if ("ORB_LATE".equals(module)) {
				settings.orbLongSkipStartMinute = 570;
				settings.orbLongSkipEndMinute = 659;
				settings.orbShortSkipStartMinute = 570;
				settings.orbShortSkipEndMinute = 659;
			}
		} else if ("ORB2".equals(module)) {
			settings.orb.enabled = true;
			settings.orb.maxTradesPerDay = 2;
			settings.allowOrbLongs = false;
			settings.allowOrbShorts = false;
			settings.enableOrbRetest = true;
			settings.allowOrbRetestLongs = allowLong;
			settings.allowOrbRetestShorts = allowShort;
		} else if ("FVG".equals(module)) {
			settings.fvg.enabled = true;
			settings.fvg.maxTradesPerDay = 2;
			settings.allowFvgLongs = allowLong;
			settings.allowFvgShorts = allowShort;
		} else if ("VWAP".equals(module)) {
			settings.vwapPullback.enabled = true;
			settings.vwapPullback.maxTradesPerDay = 2;
			settings.allowVwapPullbackLongs = allowLong;
			settings.allowVwapPullbackShorts = allowShort;
		} else if ("VRCL".equals(module)) {
			settings.vwapReclaim.enabled = true;
			settings.vwapReclaim.maxTradesPerDay = 2;
			settings.allowVwapReclaimLongs = allowLong;
			settings.allowVwapReclaimShorts = allowShort;
		} else if ("PDB".equals(module)) {
			settings.priorDayBreakout.enabled = true;
			settings.priorDayBreakout.maxTradesPerDay = 3;
			settings.allowPriorDayBreakoutLongs = allowLong;
			settings.allowPriorDayBreakoutShorts = allowShort;
		} else if ("VPB".equals(module)) {
			settings.valueAreaReclaim.enabled = true;
			settings.valueAreaReclaim.maxTradesPerDay = 3;
			settings.allowValueAreaLongs = allowLong;
			settings.allowValueAreaShorts = allowShort;
		} else if ("KREV".equals(module)) {
			settings.keltnerReversion.enabled = true;
			settings.keltnerReversion.maxTradesPerDay = 4;
			settings.allowKeltnerScalpLongs = allowLong;
			settings.allowKeltnerScalpShorts = allowShort;
		} else if ("MSCALP".equals(module)) {
			settings.microScalp.enabled = true;
			settings.microScalp.maxTradesPerDay = 4;
			settings.allowMicroScalpLongs = allowLong;
			settings.allowMicroScalpShorts = allowShort;
		}
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, symbol, risk, maxContracts);
	}

	private static void applyCurrentBestMicroStack() throws Exception {
		applyMymStack(320.0, 25, true, 890, 909, 1, 12.0, 0.70, 0.85, false, 570, 660, 0.55, 0.88, false, 620, 649);
		applyMclStack(320.0, 18, false, 870, 899, 1, 22.0, 0.65, 0.80, true, 900, 920, 2, 0.70, 0.85, 35.0, false, false, false, false);
	}

	private static int riskContracts(double risk, int defaultContracts) {
		if (risk >= 500.0) {
			return Math.max(defaultContracts, 35);
		}
		if (risk >= 400.0) {
			return Math.max(defaultContracts, 30);
		}
		if (risk >= 300.0) {
			return Math.max(defaultContracts, 25);
		}
		return defaultContracts;
	}

	private static void applyOpeningMomentumCustom(String symbol, double risk, int maxContracts, boolean allowLong, boolean allowShort, int startMinute, int endMinute, int maxTrades, double maxRiskTicks, double volumeRatio, double rewardRisk) throws Exception {
		FuturesManager.FuturesStrategySettings settings = disabledTemplate(symbol);
		settings.allowShorts = allowShort;
		settings.openingMomentum.enabled = true;
		settings.openingMomentum.maxTradesPerDay = maxTrades;
		settings.openingMomentumAllowMultiplePerSide = true;
		settings.openingMomentumRangeMinutes = 10;
		settings.openingMomentumBucketMinutes = 15;
		settings.openingMomentumVolumeRatio = volumeRatio;
		settings.openingMomentumRewardRisk = rewardRisk;
		settings.openingMomentumMaxRiskTicks = maxRiskTicks;
		settings.openingMomentumMaxHoldBars = 90;
		settings.allowOpeningMomentumLongs = allowLong;
		settings.allowOpeningMomentumShorts = allowShort;
		settings.openingMomentumLongStartMinute = startMinute;
		settings.openingMomentumLongEndMinute = endMinute;
		settings.openingMomentumShortStartMinute = startMinute;
		settings.openingMomentumShortEndMinute = endMinute;
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, symbol, risk, maxContracts);
	}

	private static void applyAfternoonCustom(String symbol, double risk, int maxContracts, boolean allowLong, boolean allowShort, int startMinute, int endMinute, int maxTrades) throws Exception {
		FuturesManager.FuturesStrategySettings settings = disabledTemplate(symbol);
		settings.allowShorts = allowShort;
		settings.afternoonContinuation.enabled = true;
		settings.afternoonContinuation.maxTradesPerDay = maxTrades;
		settings.allowAfternoonContinuationLongs = allowLong;
		settings.allowAfternoonContinuationShorts = allowShort;
		settings.afternoonStartMinute = startMinute;
		settings.afternoonEndMinute = endMinute;
		settings.afternoonLongStartMinute = startMinute;
		settings.afternoonLongEndMinute = endMinute;
		settings.afternoonShortStartMinute = startMinute;
		settings.afternoonShortEndMinute = endMinute;
		settings.afternoonMinVolumeRatio = "MCL".equals(symbol) ? 0.7 : 0.75;
		settings.afternoonRewardRisk = "MCL".equals(symbol) ? 0.85 : 0.9;
		settings.afternoonMaxRiskTicks = "MCL".equals(symbol) ? 35.0 : 90.0;
		settings.afternoonMaxHoldBars = 35;
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, symbol, risk, maxContracts);
	}

	private static void applyFvgCustom(String symbol, double risk, int maxContracts, boolean allowLong, boolean allowShort, int startMinute, int endMinute, int maxTrades) throws Exception {
		FuturesManager.FuturesStrategySettings settings = disabledTemplate(symbol);
		settings.allowShorts = allowShort;
		settings.fvg.enabled = true;
		settings.fvg.maxTradesPerDay = maxTrades;
		settings.allowFvgLongs = allowLong;
		settings.allowFvgShorts = allowShort;
		settings.fvgStartMinute = startMinute;
		settings.fvgEndMinute = endMinute;
		settings.fvgMinVolumeRatio = "MCL".equals(symbol) ? 0.45 : 0.55;
		settings.fvgMinRiskTicks = "MCL".equals(symbol) ? 8.0 : 18.0;
		settings.fvgMaxRiskTicks = "MCL".equals(symbol) ? 32.0 : 90.0;
		settings.fvgRewardRisk = 1.0;
		settings.fvgMaxHoldBars = 18;
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, symbol, risk, maxContracts);
	}

	private static void applyPdbCustom(String symbol, double risk, int maxContracts, boolean allowLong, boolean allowShort, int startMinute, int endMinute, int maxTrades) throws Exception {
		FuturesManager.FuturesStrategySettings settings = disabledTemplate(symbol);
		settings.allowShorts = allowShort;
		settings.priorDayBreakout.enabled = true;
		settings.priorDayBreakout.maxTradesPerDay = maxTrades;
		settings.allowPriorDayBreakoutLongs = allowLong;
		settings.allowPriorDayBreakoutShorts = allowShort;
		settings.priorDayBreakoutStartMinute = startMinute;
		settings.priorDayBreakoutEndMinute = endMinute;
		settings.priorDayBreakoutMinVolumeRatio = "MCL".equals(symbol) ? 0.65 : 0.75;
		settings.priorDayBreakoutMaxRiskTicks = "MCL".equals(symbol) ? 42.0 : 130.0;
		settings.priorDayBreakoutRewardRisk = 1.0;
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, symbol, risk, maxContracts);
	}

	private static void applyVwapReclaimCustom(String symbol, double risk, int maxContracts, boolean allowLong, boolean allowShort, int startMinute, int endMinute, int maxTrades) throws Exception {
		FuturesManager.FuturesStrategySettings settings = disabledTemplate(symbol);
		settings.allowShorts = allowShort;
		settings.vwapReclaim.enabled = true;
		settings.vwapReclaim.maxTradesPerDay = maxTrades;
		settings.allowVwapReclaimLongs = allowLong;
		settings.allowVwapReclaimShorts = allowShort;
		settings.vwapStartMinute = startMinute;
		settings.vwapEndMinute = endMinute;
		settings.vwapReclaimMinVolumeRatio = "MCL".equals(symbol) ? 0.7 : 0.85;
		settings.vwapReclaimMaxRiskTicks = "MCL".equals(symbol) ? 30.0 : 90.0;
		settings.vwapReclaimRewardRisk = 0.9;
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, symbol, risk, maxContracts);
	}

	private static void applyRangeCompressionCustom(String symbol, double risk, int maxContracts, boolean allowLong, boolean allowShort, int startMinute, int endMinute, int maxTrades) throws Exception {
		FuturesManager.FuturesStrategySettings settings = disabledTemplate(symbol);
		settings.allowShorts = allowShort;
		settings.rangeCompressionBreakout.enabled = true;
		settings.rangeCompressionBreakout.maxTradesPerDay = maxTrades;
		settings.allowRangeCompressionLongs = allowLong;
		settings.allowRangeCompressionShorts = allowShort;
		settings.rangeCompressionStartMinute = startMinute;
		settings.rangeCompressionEndMinute = endMinute;
		settings.rangeCompressionBars = 5;
		settings.rangeCompressionBucketMinutes = 12;
		settings.rangeCompressionMaxAtrRatio = 0.65;
		settings.rangeCompressionMinVolumeRatio = "MCL".equals(symbol) ? 0.7 : 0.8;
		settings.rangeCompressionMaxRiskTicks = "MCL".equals(symbol) ? 30.0 : 80.0;
		settings.rangeCompressionRewardRisk = 0.75;
		settings.rangeCompressionMaxHoldBars = 10;
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, symbol, risk, maxContracts);
	}

	private static void applyTrendLadderCustom(String symbol, double risk, int maxContracts, boolean allowLong, boolean allowShort, int startMinute, int endMinute, int maxTrades) throws Exception {
		FuturesManager.FuturesStrategySettings settings = disabledTemplate(symbol);
		settings.allowShorts = allowShort;
		settings.trendLadder.enabled = true;
		settings.trendLadder.maxTradesPerDay = maxTrades;
		settings.allowTrendLadderLongs = allowLong;
		settings.allowTrendLadderShorts = allowShort;
		settings.trendLadderStartMinute = startMinute;
		settings.trendLadderEndMinute = endMinute;
		settings.trendLadderBucketMinutes = 12;
		settings.trendLadderMinVolumeRatio = "MCL".equals(symbol) ? 0.55 : 0.55;
		settings.trendLadderMaxRiskTicks = "MCL".equals(symbol) ? 28.0 : 80.0;
		settings.trendLadderRewardRisk = 0.75;
		settings.trendLadderPullbackTicks = "MCL".equals(symbol) ? 8.0 : 18.0;
		settings.trendLadderMaxHoldBars = 12;
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, symbol, risk, maxContracts);
	}

	private static void applyMicroScalpCustom(String symbol, double risk, int maxContracts, boolean allowLong, boolean allowShort, int startMinute, int endMinute, int maxTrades) throws Exception {
		FuturesManager.FuturesStrategySettings settings = disabledTemplate(symbol);
		settings.allowShorts = allowShort;
		settings.microScalp.enabled = true;
		settings.microScalp.maxTradesPerDay = maxTrades;
		settings.allowMicroScalpLongs = allowLong;
		settings.allowMicroScalpShorts = allowShort;
		settings.microScalpStartMinute = startMinute;
		settings.microScalpEndMinute = endMinute;
		settings.microScalpLongStartMinute = startMinute;
		settings.microScalpLongEndMinute = endMinute;
		settings.microScalpShortStartMinute = startMinute;
		settings.microScalpShortEndMinute = endMinute;
		settings.microScalpMinVolumeRatio = "MCL".equals(symbol) ? 0.65 : 0.65;
		settings.microScalpMaxRiskTicks = "MCL".equals(symbol) ? 24.0 : 60.0;
		settings.microScalpRewardRisk = 0.8;
		settings.microScalpMaxHoldBars = 8;
		tuneForSymbol(symbol, settings);
		FuturesManager.saveFuturesStrategySettings(symbol, WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, symbol, risk, maxContracts);
	}

	private static FuturesManager.FuturesStrategySettings disabledTemplate(String symbol) {
		String template = "MCL".equals(symbol) ? "MGC" : "M2K";
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(template, WIP_SLOT);
		disableAll(settings);
		return settings;
	}

	private static double baseRiskDollars(String symbol) {
		return "MCL".equals(symbol) ? 320.0 : 220.0;
	}

	private static double[] closeRiskDollars(String symbol) {
		return "MCL".equals(symbol) ? new double[] {240.0, 320.0, 420.0} : new double[] {180.0, 220.0, 320.0};
	}

	private static double openingRiskTicks(String symbol) {
		return "MCL".equals(symbol) ? 42.0 : 120.0;
	}

	private static double closeVolumeRatio(String symbol) {
		return "MCL".equals(symbol) ? 0.65 : 0.70;
	}

	private static double closeRewardRisk(String symbol) {
		return "MCL".equals(symbol) ? 0.80 : 0.85;
	}

	private static String sideLabel(boolean allowLong, boolean allowShort) {
		if (allowLong && allowShort) {
			return "both";
		}
		return allowLong ? "long" : "short";
	}

	private static String compact(double value) {
		if (Math.abs(value - Math.rint(value)) < 0.000001) {
			return String.valueOf((int) Math.rint(value));
		}
		return String.valueOf(value).replace(".", "p");
	}

	private static void applyMclQuality() throws Exception {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("MGC", WIP_SLOT);
		settings.microScalp.enabled = false;
		settings.valueAreaReclaim.enabled = false;
		settings.orb.maxTradesPerDay = 1;
		settings.openingMomentum.maxTradesPerDay = 3;
		settings.openingMomentumVolumeRatio = 0.55;
		settings.openingMomentumRewardRisk = 0.9;
		settings.closeMomentum.enabled = true;
		settings.closeMomentum.maxTradesPerDay = 1;
		settings.closeMomentumRewardRisk = 0.8;
		tuneForSymbol("MCL", settings);
		FuturesManager.saveFuturesStrategySettings("MCL", WIP_SLOT, settings);
		upsertRisk(WIP_SLOT, "MCL", 420.0, 25);
	}

	private static void tuneForSymbol(String symbol, FuturesManager.FuturesStrategySettings settings) {
		if ("MYM".equals(symbol)) {
			settings.maxInitialRiskTicks = 180.0;
			settings.orbCompressedMaxRiskTicks = 140.0;
			settings.orbRetestMaxRiskTicks = 220.0;
			settings.openingMomentumMaxRiskTicks = Math.max(settings.openingMomentumMaxRiskTicks, 110.0);
			settings.rangeCompressionMaxRiskTicks = Math.max(settings.rangeCompressionMaxRiskTicks, 80.0);
			settings.keltnerMaxRiskTicks = Math.max(settings.keltnerMaxRiskTicks, 80.0);
			settings.microScalpMaxRiskTicks = Math.max(settings.microScalpMaxRiskTicks, 60.0);
			settings.microShadowMaxRiskTicks = Math.max(settings.microShadowMaxRiskTicks, 70.0);
			settings.microEchoMaxRiskTicks = Math.max(settings.microEchoMaxRiskTicks, 70.0);
			settings.mymBreadthMaxRiskTicks = Math.max(settings.mymBreadthMaxRiskTicks, 80.0);
		}
		if ("MCL".equals(symbol)) {
			settings.maxInitialRiskTicks = 70.0;
			settings.orbCompressedMaxRiskTicks = 55.0;
			settings.orbRetestMaxRiskTicks = 120.0;
			settings.openingMomentumMaxRiskTicks = clamp(settings.openingMomentumMaxRiskTicks, 22.0, 55.0);
			settings.rangeCompressionMaxRiskTicks = clamp(settings.rangeCompressionMaxRiskTicks, 18.0, 50.0);
			settings.keltnerMaxRiskTicks = clamp(settings.keltnerMaxRiskTicks, 12.0, 45.0);
			settings.microScalpMaxRiskTicks = clamp(settings.microScalpMaxRiskTicks, 8.0, 32.0);
			settings.microShadowMaxRiskTicks = clamp(settings.microShadowMaxRiskTicks, 10.0, 36.0);
			settings.microEchoMaxRiskTicks = clamp(settings.microEchoMaxRiskTicks, 8.0, 35.0);
			settings.mclTrendMaxRiskTicks = clamp(settings.mclTrendMaxRiskTicks, 10.0, 40.0);
		}
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static void disableAll(FuturesManager.FuturesStrategySettings settings) {
		settings.orb.enabled = false;
		settings.lateOrbContinuation.enabled = false;
		settings.openingMomentum.enabled = false;
		settings.sweep.enabled = false;
		settings.priorDayBreakout.enabled = false;
		settings.vwapPullback.enabled = false;
		settings.vwapReclaim.enabled = false;
		settings.vwapMeanReversion.enabled = false;
		settings.fvg.enabled = false;
		settings.closeMomentum.enabled = false;
		settings.afternoonContinuation.enabled = false;
		settings.marketIntradayMomentum.enabled = false;
		settings.keltnerScalp.enabled = false;
		settings.keltnerReversion.enabled = false;
		settings.microScalp.enabled = false;
		settings.microShadow.enabled = false;
		settings.microEcho.enabled = false;
		settings.winnerFollowThrough.enabled = false;
		settings.trendLadder.enabled = false;
		settings.rangeCompressionBreakout.enabled = false;
		settings.valueAreaReclaim.enabled = false;
		settings.mclEiaContinuation.enabled = false;
		settings.mclCrudeSessionOpen.enabled = false;
		settings.mymIndexConfirmation.enabled = false;
		settings.mymOrbRetest.enabled = false;
		settings.mymBreadthConfirmation.enabled = false;
		settings.mclTrendContinuation.enabled = false;
	}

	private static void copyStrategySlot(String fromSlot, String toSlot) throws Exception {
		String fromPrefix = fromSlot + ".";
		String toPrefix = toSlot + ".";
		List<String[]> values = new ArrayList<String[]>();
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement select = conn.prepareStatement("SELECT settingKey, settingValue FROM FuturesStrategySettings WHERE settingKey LIKE ?")) {
			select.setString(1, fromPrefix + "%");
			try (ResultSet rs = select.executeQuery()) {
				while (rs.next()) {
					String key = rs.getString("settingKey");
					values.add(new String[] { toPrefix + key.substring(fromPrefix.length()), rs.getString("settingValue") });
				}
			}
		}
		if (values.isEmpty()) {
			throw new IllegalStateException("Missing source strategy slot: " + fromSlot);
		}
		try (Connection conn = DatabaseManager.getConnection()) {
			conn.setAutoCommit(false);
			try (PreparedStatement delete = conn.prepareStatement("DELETE FROM FuturesStrategySettings WHERE settingKey LIKE ?");
				 PreparedStatement insert = conn.prepareStatement("INSERT OR REPLACE INTO FuturesStrategySettings (settingKey, settingValue) VALUES (?, ?)")) {
				delete.setString(1, toPrefix + "%");
				delete.executeUpdate();
				for (String[] value : values) {
					insert.setString(1, value[0]);
					insert.setString(2, value[1]);
					insert.addBatch();
				}
				insert.executeBatch();
				conn.commit();
			} catch (Exception e) {
				conn.rollback();
				throw e;
			} finally {
				conn.setAutoCommit(true);
			}
		}
	}

	private static int countSlotRows(String slot) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM FuturesStrategySettings WHERE settingKey LIKE ?")) {
			stmt.setString(1, slot + ".%");
			try (ResultSet rs = stmt.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		}
	}

	private static int diffSlotRows(String firstSlot, String secondSlot) throws Exception {
		String firstPrefix = firstSlot + ".";
		String secondPrefix = secondSlot + ".";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT COUNT(*) FROM ("
					 + "SELECT REPLACE(settingKey, ?, '') AS k, settingValue FROM FuturesStrategySettings WHERE settingKey LIKE ? "
					 + "EXCEPT "
					 + "SELECT REPLACE(settingKey, ?, '') AS k, settingValue FROM FuturesStrategySettings WHERE settingKey LIKE ?"
					 + ")")) {
			stmt.setString(1, firstPrefix);
			stmt.setString(2, firstPrefix + "%");
			stmt.setString(3, secondPrefix);
			stmt.setString(4, secondPrefix + "%");
			try (ResultSet rs = stmt.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		}
	}

	private static void upsertRisk(String slot, String symbol, double maxRiskPerTrade, int maxContracts) throws Exception {
		String prefix = slot + "." + symbol + ".risk.";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("INSERT OR REPLACE INTO FuturesStrategySettings (settingKey, settingValue) VALUES (?, ?)")) {
			insert(stmt, prefix + "accountSize", "50000.0");
			insert(stmt, prefix + "maxTrailingDrawdown", "2500.0");
			insert(stmt, prefix + "dailyLossLimit", "500.0");
			insert(stmt, prefix + "maxRiskPerTrade", String.valueOf(maxRiskPerTrade));
			insert(stmt, prefix + "maxContracts", String.valueOf(maxContracts));
			insert(stmt, prefix + "commissionPerContract", "1.24");
			insert(stmt, prefix + "slippageTicks", "1.0");
			insert(stmt, prefix + "profitTarget", "0.0");
			stmt.executeBatch();
		}
	}

	private static void insert(PreparedStatement stmt, String key, String value) throws Exception {
		stmt.setString(1, key);
		stmt.setString(2, value);
		stmt.addBatch();
	}

	private static Summary runPortfolio(String name, String symbols, String endDate) throws Exception {
		return runPortfolio(name, symbols, endDate, 0);
	}

	private static Summary runPortfolio(String name, String symbols, String endDate, int sourcePortfolioBacktestId) throws Exception {
		int id = FuturesManager.generatePortfolioBacktest(
			symbols,
			START_DATE,
			endDate,
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
			sourcePortfolioBacktestId
		);
		Summary summary = loadSummary(id);
		summary.name = name;
		summary.symbols = symbols;
		labelRun(id, name);
		return summary;
	}

	private static Summary loadSummary(int id) throws Exception {
		Summary summary = new Summary();
		summary.id = id;
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("SELECT * FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ?")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					summary.pnl = rs.getDouble("totalProfit");
					summary.trades = rs.getInt("numTrades");
					summary.winRate = rs.getDouble("winRate");
					summary.profitFactor = rs.getDouble("profitFactor");
					summary.drawdownPct = rs.getDouble("maxDrawdownPct");
					summary.maxIntradayLoss = rs.getDouble("maxIntradayLoss");
					summary.maxAggregateMae = rs.getDouble("maxAggregateMae");
					summary.dailyLossBreaches = rs.getInt("dailyLossBreaches");
					summary.trailingDrawdownBreaches = rs.getInt("trailingDrawdownBreaches");
					summary.ruleViolation = rs.getInt("ruleViolation");
				}
			}
		}
		loadSymbolContribution(summary, "MYM");
		loadSymbolContribution(summary, "MCL");
		return summary;
	}

	private static void loadSymbolContribution(Summary summary, String symbol) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) AS trades, COALESCE(SUM(pnl), 0.0) AS pnl FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND symbol = ?")) {
			stmt.setInt(1, summary.id);
			stmt.setString(2, symbol);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					if ("MYM".equals(symbol)) {
						summary.mymTrades = rs.getInt("trades");
						summary.mymPnl = rs.getDouble("pnl");
					} else if ("MCL".equals(symbol)) {
						summary.mclTrades = rs.getInt("trades");
						summary.mclPnl = rs.getDouble("pnl");
					}
				}
			}
		}
	}

	private static int symbolTrades(Summary summary, String symbol) {
		if ("MYM".equals(symbol)) {
			return summary.mymTrades;
		}
		if ("MCL".equals(symbol)) {
			return summary.mclTrades;
		}
		return 0;
	}

	private static double symbolPnl(Summary summary, String symbol) {
		if ("MYM".equals(symbol)) {
			return summary.mymPnl;
		}
		if ("MCL".equals(symbol)) {
			return summary.mclPnl;
		}
		return 0.0;
	}

	private static void printSymbolBreakdown(int id) throws Exception {
		System.out.println("SYMBOL_BREAKDOWN run=" + id);
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT symbol, COUNT(*) AS trades, ROUND(SUM(pnl), 2) AS pnl, ROUND(AVG(pnl), 2) AS avgPnl, "
					 + "ROUND(SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) AS winRate "
					 + "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? GROUP BY symbol ORDER BY SUM(pnl) DESC")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					System.out.println(
						rs.getString("symbol")
							+ " trades=" + rs.getInt("trades")
							+ " pnl=" + round(rs.getDouble("pnl"))
							+ " avg=" + round(rs.getDouble("avgPnl"))
							+ " win=" + round(rs.getDouble("winRate"))
					);
				}
			}
		}
	}

	private static void printTargetSymbolBreakdown(int id) throws Exception {
		System.out.println("TARGET_SYMBOL_BREAKDOWN run=" + id);
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT symbol, COUNT(*) AS trades, ROUND(SUM(pnl), 2) AS pnl, ROUND(AVG(pnl), 2) AS avgPnl, "
					 + "ROUND(SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) AS winRate "
					 + "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND symbol IN ('MYM', 'MCL', 'ES', 'M2K', 'MES', 'NQ') "
					 + "GROUP BY symbol ORDER BY SUM(pnl) DESC")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					System.out.println(
						rs.getString("symbol")
							+ " trades=" + rs.getInt("trades")
							+ " pnl=" + round(rs.getDouble("pnl"))
							+ " avg=" + round(rs.getDouble("avgPnl"))
							+ " win=" + round(rs.getDouble("winRate"))
					);
				}
			}
		}
	}

	private static void printNewSymbolStrategyBreakdown(int id) throws Exception {
		System.out.println("NEW_SYMBOL_STRATEGY run=" + id);
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT symbol, strategyCode, side, COUNT(*) AS trades, ROUND(SUM(pnl), 2) AS pnl, ROUND(AVG(pnl), 2) AS avgPnl, "
					 + "ROUND(SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) AS winRate "
					 + "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND symbol IN ('MYM', 'MCL') "
					 + "GROUP BY symbol, strategyCode, side ORDER BY SUM(pnl) DESC")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					System.out.println(
						rs.getString("symbol")
							+ "/" + rs.getString("strategyCode")
							+ "/" + rs.getString("side")
							+ " trades=" + rs.getInt("trades")
							+ " pnl=" + round(rs.getDouble("pnl"))
							+ " avg=" + round(rs.getDouble("avgPnl"))
							+ " win=" + round(rs.getDouble("winRate"))
					);
				}
			}
		}
	}

	private static void printTargetStrategyBreakdown(int id) throws Exception {
		System.out.println("TARGET_STRATEGY_BREAKDOWN run=" + id);
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT symbol, strategyCode, side, COUNT(*) AS trades, ROUND(SUM(pnl), 2) AS pnl, ROUND(AVG(pnl), 2) AS avgPnl, "
					 + "ROUND(SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) AS winRate "
					 + "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? AND symbol IN ('MYM', 'MCL', 'ES', 'M2K', 'MES', 'NQ') "
					 + "GROUP BY symbol, strategyCode, side ORDER BY SUM(pnl) DESC")) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					System.out.println(
						rs.getString("symbol")
							+ "/" + rs.getString("strategyCode")
							+ "/" + rs.getString("side")
							+ " trades=" + rs.getInt("trades")
							+ " pnl=" + round(rs.getDouble("pnl"))
							+ " avg=" + round(rs.getDouble("avgPnl"))
							+ " win=" + round(rs.getDouble("winRate"))
					);
				}
			}
		}
	}

	private static void labelRun(int id, String name) {
		if (id <= 0) {
			return;
		}
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("INSERT INTO ResearchRunLabels (portfolioBacktestID, runner, scenarioName, createdAt) VALUES (?, ?, ?, datetime('now'))")) {
			stmt.setInt(1, id);
			stmt.setString(2, "MicroContractExpansionRunner");
			stmt.setString(3, name);
			stmt.executeUpdate();
		} catch (Exception ignored) {
		}
	}

	private static String line(Summary summary) {
		return summary.name
			+ " id=" + summary.id
			+ " symbols=" + summary.symbols
			+ " trades=" + summary.trades
			+ " pnl=" + round(summary.pnl)
			+ " win=" + round(summary.winRate)
			+ " pf=" + round(summary.profitFactor)
			+ " ddPct=" + round(summary.drawdownPct)
			+ " maxIntradayLoss=" + round(summary.maxIntradayLoss)
			+ " maxAggMae=" + round(summary.maxAggregateMae)
			+ " breaches=" + summary.dailyLossBreaches + "/" + summary.trailingDrawdownBreaches
			+ " violation=" + summary.ruleViolation
			+ " MYM=" + summary.mymTrades + "/" + round(summary.mymPnl)
			+ " MCL=" + summary.mclTrades + "/" + round(summary.mclPnl);
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
