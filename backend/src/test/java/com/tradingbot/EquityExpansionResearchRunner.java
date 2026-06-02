package com.tradingbot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EquityExpansionResearchRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K";
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-04";
	private static final String FUNDED_PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String[] SYMBOL_LIST = new String[] {"MES", "MNQ", "NQ", "MGC", "ES", "M2K"};

	private interface Scenario {
		String name();
		default String profile() { return FUNDED_PROFILE; }
		default boolean useSavedRisk() { return true; }
		default double accountSize() { return 50000.0; }
		default double maxTrailingDrawdown() { return 2000.0; }
		default double dailyLossLimit() { return 1000.0; }
		default double maxRiskPerTrade() { return 400.0; }
		default int maxContracts() { return 12; }
		default double maxAggregateMiniUnits() { return 5.0; }
		void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks);
	}

	private static class RunSummary {
		int id;
		String name;
		String profile;
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
		boolean promote = false;
		for (int index = 0; index < args.length; index++) {
			if ("--promote".equalsIgnoreCase(args[index])) {
				promote = true;
			}
		}

		Map<String, FuturesManager.FuturesStrategySettings> baseSettings = loadBaseSettings();
		Map<String, FuturesManager.FuturesRiskSettings> baseRisks = loadBaseRisks();
		List<Scenario> scenarios = scenarios();
		List<RunSummary> summaries = new ArrayList<RunSummary>();
		try {
			for (int index = 0; index < scenarios.size(); index++) {
				Scenario scenario = scenarios.get(index);
				restore(baseSettings, baseRisks);
				Map<String, FuturesManager.FuturesStrategySettings> settings = loadBaseSettings();
				Map<String, FuturesManager.FuturesRiskSettings> risks = loadBaseRisks();
				scenario.apply(settings, risks);
				save(settings, risks);

				int id = FuturesManager.generatePortfolioBacktest(
					SYMBOLS,
					START_DATE,
					END_DATE,
					scenario.accountSize(),
					scenario.maxTrailingDrawdown(),
					scenario.dailyLossLimit(),
					scenario.maxRiskPerTrade(),
					scenario.maxContracts(),
					1.24,
					1.0,
					3,
					50,
					scenario.maxAggregateMiniUnits(),
					scenario.useSavedRisk(),
					0.0,
					scenario.profile()
				);
				RunSummary summary = loadRun(id, scenario.name(), scenario.profile());
				summaries.add(summary);
				printSummary(summary);
				printBreakdown(id, "symbol", "symbol");
				printBreakdown(id, "symbol, strategyCode", "symbol_strategy");
			}
		} finally {
			restore(baseSettings, baseRisks);
		}

		RunSummary bestFunded = bestFunded(summaries);
		RunSummary bestAll = bestAll(summaries);
		if (bestFunded != null) {
			System.out.println("BEST_FUNDED " + line(bestFunded));
		}
		if (bestAll != null) {
			System.out.println("BEST_ALL " + line(bestAll));
		}
		if (promote && bestFunded != null && bestFunded.pnl > 33767.71) {
			for (int index = 0; index < scenarios.size(); index++) {
				Scenario scenario = scenarios.get(index);
				if (scenario.name().equals(bestFunded.name)) {
					Map<String, FuturesManager.FuturesStrategySettings> settings = loadBaseSettings();
					Map<String, FuturesManager.FuturesRiskSettings> risks = loadBaseRisks();
					scenario.apply(settings, risks);
					save(settings, risks);
					System.out.println("PROMOTED " + bestFunded.name + " run=" + bestFunded.id);
					break;
				}
			}
		}
	}

	private static List<Scenario> scenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		values.add(new Scenario() {
			public String name() { return "baseline_707_replay"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {}
		});
		values.add(new Scenario() {
			public String name() { return "disable_es_krev_only"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				disableESKrev(settings);
			}
		});
		values.add(new Scenario() {
			public String name() { return "disable_es_krev_es_vwap_13_15"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				disableESKrev(settings);
				keepESVwapMoneyWindow(settings);
			}
		});
		values.add(new Scenario() {
			public String name() { return "bad_window_cleanup"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				disableESKrev(settings);
				keepESVwapMoneyWindow(settings);
				trimMNQBadLateVwap(settings);
			}
		});
		values.add(new Scenario() {
			public String name() { return "bad_window_cleanup_orb_long_10"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				disableESKrev(settings);
				keepESVwapMoneyWindow(settings);
				trimMNQBadLateVwap(settings);
				skipOrbLongTen(settings);
			}
		});
		values.add(new Scenario() {
			public String name() { return "bad_window_cleanup_sweep_short_1345"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				disableESKrev(settings);
				keepESVwapMoneyWindow(settings);
				trimMNQBadLateVwap(settings);
				skipSweepShort1345(settings);
			}
		});
		values.add(new Scenario() {
			public String name() { return "bad_window_cleanup_omom_long_10"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				disableESKrev(settings);
				keepESVwapMoneyWindow(settings);
				trimMNQBadLateVwap(settings);
				skipMNQOmomLongTen(settings);
			}
		});
		values.add(new Scenario() {
			public String name() { return "bad_window_cleanup_all_skips"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				disableESKrev(settings);
				keepESVwapMoneyWindow(settings);
				trimMNQBadLateVwap(settings);
				skipOrbLongTen(settings);
				skipSweepShort1345(settings);
				skipMNQOmomLongTen(settings);
			}
		});
		values.add(new Scenario() {
			public String name() { return "all_skips_mnq_omom_rr_100"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyAllSkips(settings);
				settings.get("MNQ").openingMomentumRewardRisk = 1.0;
			}
		});
		values.add(new Scenario() {
			public String name() { return "all_skips_mnq_omom_rr_110"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyAllSkips(settings);
				settings.get("MNQ").openingMomentumRewardRisk = 1.1;
			}
		});
		values.add(new Scenario() {
			public String name() { return "all_skips_mnq_omom_volume_070"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyAllSkips(settings);
				settings.get("MNQ").openingMomentumVolumeRatio = 0.7;
			}
		});
		values.add(new Scenario() {
			public String name() { return "all_skips_mnq_omom_volume_090"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyAllSkips(settings);
				settings.get("MNQ").openingMomentumVolumeRatio = 0.9;
			}
		});
		values.add(new Scenario() {
			public String name() { return "all_skips_mnq_omom_max3"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				applyAllSkips(settings);
				settings.get("MNQ").openingMomentum.maxTradesPerDay = 3;
			}
		});
		values.add(new Scenario() {
			public String name() { return "bad_window_cleanup_mgc_pdb_skip_10"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				disableESKrev(settings);
				keepESVwapMoneyWindow(settings);
				trimMNQBadLateVwap(settings);
				FuturesManager.FuturesStrategySettings mgc = settings.get("MGC");
				mgc.priorDayBreakoutShortSkipStartMinute = 600;
				mgc.priorDayBreakoutShortSkipEndMinute = 659;
			}
		});
		values.add(new Scenario() {
			public String name() { return "bad_window_cleanup_mgc_525"; }
			public double maxRiskPerTrade() { return 700.0; }
			public int maxContracts() { return 30; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				disableESKrev(settings);
				keepESVwapMoneyWindow(settings);
				trimMNQBadLateVwap(settings);
				bumpRisk(risks.get("MGC"), 525.0, 30);
			}
		});
		values.add(new Scenario() {
			public String name() { return "bad_window_cleanup_mgc_550"; }
			public double maxRiskPerTrade() { return 700.0; }
			public int maxContracts() { return 30; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				disableESKrev(settings);
				keepESVwapMoneyWindow(settings);
				trimMNQBadLateVwap(settings);
				bumpRisk(risks.get("MGC"), 550.0, 30);
			}
		});
		values.add(new Scenario() {
			public String name() { return "scale_existing_edges"; }
			public double maxRiskPerTrade() { return 700.0; }
			public int maxContracts() { return 30; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				bumpRisk(risks.get("MNQ"), 700.0, 30);
				bumpRisk(risks.get("MGC"), 700.0, 30);
				bumpRisk(risks.get("NQ"), 700.0, 2);
				bumpRisk(risks.get("ES"), 520.0, 3);
				bumpRisk(risks.get("MES"), 520.0, 30);
				bumpRisk(risks.get("M2K"), 520.0, 30);
			}
		});
		values.add(new Scenario() {
			public String name() { return "scale_existing_edges_no_es_krev"; }
			public double maxRiskPerTrade() { return 700.0; }
			public int maxContracts() { return 30; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				disableESKrev(settings);
				bumpRisk(risks.get("MNQ"), 700.0, 30);
				bumpRisk(risks.get("MGC"), 700.0, 30);
				bumpRisk(risks.get("NQ"), 700.0, 2);
				bumpRisk(risks.get("ES"), 520.0, 3);
				bumpRisk(risks.get("MES"), 520.0, 30);
				bumpRisk(risks.get("M2K"), 520.0, 30);
			}
		});
		values.add(new Scenario() {
			public String name() { return "scale_mnq_mgc_nq_no_es_krev"; }
			public double maxRiskPerTrade() { return 700.0; }
			public int maxContracts() { return 30; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				disableESKrev(settings);
				bumpRisk(risks.get("MNQ"), 700.0, 30);
				bumpRisk(risks.get("MGC"), 650.0, 30);
				bumpRisk(risks.get("NQ"), 600.0, 2);
			}
		});
		values.add(new Scenario() {
			public String name() { return "nq_size_and_existing_stack"; }
			public double maxRiskPerTrade() { return 700.0; }
			public int maxContracts() { return 30; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				bumpRisk(risks.get("NQ"), 700.0, 2);
			}
		});
		values.add(new Scenario() {
			public String name() { return "mgc_money_scale_only"; }
			public double maxRiskPerTrade() { return 700.0; }
			public int maxContracts() { return 30; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				bumpRisk(risks.get("MGC"), 700.0, 30);
			}
		});
		values.add(new Scenario() {
			public String name() { return "mnq_mgc_money_scale_only"; }
			public double maxRiskPerTrade() { return 700.0; }
			public int maxContracts() { return 30; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				bumpRisk(risks.get("MNQ"), 700.0, 30);
				bumpRisk(risks.get("MGC"), 700.0, 30);
			}
		});
		values.add(new Scenario() {
			public String name() { return "kill_es_krev_expand_equity_momentum"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				disableESKrev(settings);
				enableEquityMomentum(settings, risks);
			}
		});
		values.add(new Scenario() {
			public String name() { return "mnq_playbook_on_equity_futures"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				copyMomentumPlaybook(settings.get("MNQ"), settings.get("MES"), 3);
				copyMomentumPlaybook(settings.get("MNQ"), settings.get("NQ"), 2);
				copyMomentumPlaybook(settings.get("MNQ"), settings.get("ES"), 2);
				copyMomentumPlaybook(settings.get("MNQ"), settings.get("M2K"), 3);
				disableESKrev(settings);
				bumpRisk(risks.get("MES"), 520.0, 30);
				bumpRisk(risks.get("NQ"), 600.0, 2);
				bumpRisk(risks.get("ES"), 520.0, 3);
				bumpRisk(risks.get("M2K"), 520.0, 30);
			}
		});
		values.add(new Scenario() {
			public String name() { return "full_equity_frequency_stack"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : new String[] {"MES", "NQ", "ES", "M2K"}) {
					FuturesManager.FuturesStrategySettings s = settings.get(symbol);
					enableCoreStack(s);
					enableOpeningMomentum(s, symbol, "NQ".equals(symbol) ? 2 : 4);
					s.vwapPullback.enabled = true;
					s.vwapPullback.maxTradesPerDay = Math.max(s.vwapPullback.maxTradesPerDay, 4);
					s.vwapReclaim.enabled = true;
					s.vwapReclaim.maxTradesPerDay = Math.max(s.vwapReclaim.maxTradesPerDay, 2);
					s.afternoonContinuation.enabled = true;
					s.afternoonContinuation.maxTradesPerDay = Math.max(s.afternoonContinuation.maxTradesPerDay, 2);
					s.marketIntradayMomentum.enabled = true;
					s.marketIntradayMomentum.maxTradesPerDay = Math.max(s.marketIntradayMomentum.maxTradesPerDay, 2);
					s.fvg.enabled = true;
					s.fvg.maxTradesPerDay = 1;
					s.enableEarlyLossCut = true;
					s.earlyLossCutBars = 12;
					s.earlyLossCutR = 0.5;
					s.openMaeRiskMultiplier = 1.4;
				}
				disableESKrev(settings);
				bumpRisk(risks.get("MES"), 600.0, 40);
				bumpRisk(risks.get("NQ"), 650.0, 2);
				bumpRisk(risks.get("ES"), 600.0, 3);
				bumpRisk(risks.get("M2K"), 600.0, 40);
			}
		});
		values.add(new Scenario() {
			public String name() { return "quality_money_scale"; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				disableESKrev(settings);
				for (String symbol : SYMBOL_LIST) {
					FuturesManager.FuturesStrategySettings s = settings.get(symbol);
					s.sweep.enabled = true;
					s.priorDayBreakout.enabled = true;
					s.vwapPullback.enabled = true;
					s.orb.enabled = true;
					s.enableCompressedOrbBreakout = true;
					s.sweep.maxTradesPerDay = Math.max(s.sweep.maxTradesPerDay, 4);
					s.priorDayBreakout.maxTradesPerDay = Math.max(s.priorDayBreakout.maxTradesPerDay, 4);
					s.vwapPullback.maxTradesPerDay = Math.max(s.vwapPullback.maxTradesPerDay, 4);
					s.minRewardRisk = Math.max(s.minRewardRisk, 1.2);
					s.enableAdaptiveExits = true;
					s.enableEarlyLossCut = true;
				}
				bumpRisk(risks.get("MNQ"), 700.0, 30);
				bumpRisk(risks.get("MGC"), 650.0, 30);
				bumpRisk(risks.get("NQ"), 600.0, 2);
				bumpRisk(risks.get("ES"), 520.0, 3);
				bumpRisk(risks.get("MES"), 520.0, 30);
				bumpRisk(risks.get("M2K"), 520.0, 30);
			}
		});
		values.add(new Scenario() {
			public String name() { return "aggressive_but_funded_guarded"; }
			public double maxRiskPerTrade() { return 700.0; }
			public int maxContracts() { return 30; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : SYMBOL_LIST) {
					FuturesManager.FuturesStrategySettings s = settings.get(symbol);
					enableCoreStack(s);
					enableOpeningMomentum(s, symbol, isMicro(symbol) ? 5 : 2);
					s.sweep.enabled = true;
					s.sweep.maxTradesPerDay = Math.max(s.sweep.maxTradesPerDay, 5);
					s.vwapPullback.enabled = true;
					s.vwapPullback.maxTradesPerDay = Math.max(s.vwapPullback.maxTradesPerDay, 5);
					s.vwapReclaim.enabled = true;
					s.vwapReclaim.maxTradesPerDay = Math.max(s.vwapReclaim.maxTradesPerDay, 2);
					s.afternoonContinuation.enabled = true;
					s.afternoonContinuation.maxTradesPerDay = Math.max(s.afternoonContinuation.maxTradesPerDay, 2);
					s.fvg.enabled = true;
					s.closeMomentum.enabled = true;
					s.closeMomentum.maxTradesPerDay = Math.max(s.closeMomentum.maxTradesPerDay, 2);
					s.enableEarlyLossCut = true;
					s.earlyLossCutBars = 10;
					s.earlyLossCutR = 0.45;
					s.openMaeRiskMultiplier = 1.6;
				}
				disableESKrev(settings);
				for (String symbol : SYMBOL_LIST) {
					bumpRisk(risks.get(symbol), 700.0, isMicro(symbol) ? 50 : 5);
				}
			}
		});
		values.add(new Scenario() {
			public String name() { return "paper_100k_probe_not_promotable"; }
			public String profile() { return "CUSTOM"; }
			public boolean useSavedRisk() { return false; }
			public double maxTrailingDrawdown() { return 25000.0; }
			public double dailyLossLimit() { return 10000.0; }
			public double maxRiskPerTrade() { return 1800.0; }
			public int maxContracts() { return 80; }
			public double maxAggregateMiniUnits() { return 50.0; }
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : SYMBOL_LIST) {
					FuturesManager.FuturesStrategySettings s = settings.get(symbol);
					enableCoreStack(s);
					enableOpeningMomentum(s, symbol, isMicro(symbol) ? 8 : 3);
					s.sweep.enabled = true;
					s.sweep.maxTradesPerDay = 6;
					s.priorDayBreakout.enabled = true;
					s.priorDayBreakout.maxTradesPerDay = 6;
					s.vwapPullback.enabled = true;
					s.vwapPullback.maxTradesPerDay = 6;
					s.vwapReclaim.enabled = true;
					s.vwapReclaim.maxTradesPerDay = 3;
					s.afternoonContinuation.enabled = true;
					s.afternoonContinuation.maxTradesPerDay = 3;
					s.marketIntradayMomentum.enabled = true;
					s.marketIntradayMomentum.maxTradesPerDay = 3;
					s.fvg.enabled = true;
					s.fvg.maxTradesPerDay = 2;
					s.closeMomentum.enabled = true;
					s.closeMomentum.maxTradesPerDay = 2;
					s.enableAdaptiveExits = true;
					s.enableEarlyLossCut = true;
					s.openMaeRiskMultiplier = 1.0;
				}
			}
		});
		return values;
	}

	private static void enableEquityMomentum(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
		enableOpeningMomentum(settings.get("NQ"), "NQ", 2);
		enableOpeningMomentum(settings.get("ES"), "ES", 2);
		enableOpeningMomentum(settings.get("MES"), "MES", 4);
		enableOpeningMomentum(settings.get("M2K"), "M2K", 3);
		for (String symbol : new String[] {"NQ", "ES", "MES", "M2K"}) {
			FuturesManager.FuturesStrategySettings s = settings.get(symbol);
			s.vwapPullback.enabled = true;
			s.vwapPullback.maxTradesPerDay = Math.max(s.vwapPullback.maxTradesPerDay, 3);
			s.sweep.enabled = true;
			s.sweep.maxTradesPerDay = Math.max(s.sweep.maxTradesPerDay, 3);
			s.enableEarlyLossCut = true;
			s.openMaeRiskMultiplier = Math.max(s.openMaeRiskMultiplier, 1.2);
		}
		bumpRisk(risks.get("NQ"), 500.0, 2);
		bumpRisk(risks.get("ES"), 450.0, 3);
		bumpRisk(risks.get("MES"), 500.0, 30);
		bumpRisk(risks.get("M2K"), 450.0, 30);
	}

	private static void enableCoreStack(FuturesManager.FuturesStrategySettings s) {
		s.orb.enabled = true;
		s.sweep.enabled = true;
		s.priorDayBreakout.enabled = true;
		s.vwapPullback.enabled = true;
		s.closeMomentum.enabled = true;
		s.allowShorts = true;
		s.requireHigherTimeframeGuard = true;
		s.enableSweepSecondChance = true;
		s.enableLateSweep = true;
	}

	private static void copyMomentumPlaybook(FuturesManager.FuturesStrategySettings from, FuturesManager.FuturesStrategySettings to, int maxOmomTrades) {
		enableCoreStack(to);
		to.openingMomentum.enabled = true;
		to.openingMomentum.maxTradesPerDay = maxOmomTrades;
		to.openingMomentumAllowMultiplePerSide = true;
		to.openingMomentumRangeMinutes = from.openingMomentumRangeMinutes;
		to.openingMomentumBucketMinutes = from.openingMomentumBucketMinutes;
		to.openingMomentumVolumeRatio = Math.min(from.openingMomentumVolumeRatio, 0.55);
		to.openingMomentumRewardRisk = Math.max(from.openingMomentumRewardRisk, 0.85);
		to.openingMomentumLongStartMinute = from.openingMomentumLongStartMinute;
		to.openingMomentumLongEndMinute = from.openingMomentumLongEndMinute;
		to.openingMomentumShortStartMinute = from.openingMomentumShortStartMinute;
		to.openingMomentumShortEndMinute = from.openingMomentumShortEndMinute;
		to.openingMomentumMaxRiskTicks = Math.min(to.openingMomentumMaxRiskTicks, 80.0);
		to.vwapPullback.enabled = true;
		to.vwapPullback.maxTradesPerDay = Math.max(to.vwapPullback.maxTradesPerDay, 4);
		to.vwapReclaim.enabled = true;
		to.vwapReclaim.maxTradesPerDay = Math.max(to.vwapReclaim.maxTradesPerDay, 2);
		to.afternoonContinuation.enabled = true;
		to.afternoonContinuation.maxTradesPerDay = Math.max(to.afternoonContinuation.maxTradesPerDay, 2);
		to.fvg.enabled = true;
		to.fvg.maxTradesPerDay = Math.max(to.fvg.maxTradesPerDay, 1);
		to.enableAdaptiveExits = true;
		to.enableEarlyLossCut = true;
		to.earlyLossCutBars = 12;
		to.earlyLossCutR = 0.5;
	}

	private static void enableOpeningMomentum(FuturesManager.FuturesStrategySettings s, String symbol, int maxTrades) {
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
		s.openingMomentumShortEndMinute = 650;
		s.openingMomentumMaxRiskTicks = isMicro(symbol) ? 220.0 : 80.0;
		s.allowOpeningMomentumLongs = true;
		s.allowOpeningMomentumShorts = true;
	}

	private static void disableESKrev(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		FuturesManager.FuturesStrategySettings es = settings.get("ES");
		if (es != null) {
			es.keltnerReversion.enabled = false;
			es.keltnerScalp.enabled = false;
		}
	}

	private static void keepESVwapMoneyWindow(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		FuturesManager.FuturesStrategySettings es = settings.get("ES");
		if (es != null) {
			es.vwapStartMinute = 780;
			es.vwapEndMinute = 930;
			es.vwapSkipStartMinute = 840;
			es.vwapSkipEndMinute = 899;
		}
	}

	private static void trimMNQBadLateVwap(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
		if (mnq != null) {
			mnq.vwapEndMinute = 899;
		}
	}

	private static void skipOrbLongTen(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings s = settings.get(symbol);
			if (s != null) {
				s.orbLongSkipStartMinute = 600;
				s.orbLongSkipEndMinute = 659;
			}
		}
	}

	private static void skipSweepShort1345(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings s = settings.get(symbol);
			if (s != null) {
				s.sweepShortSkipStartMinute = 825;
				s.sweepShortSkipEndMinute = 839;
			}
		}
	}

	private static void skipMNQOmomLongTen(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
		if (mnq != null) {
			mnq.openingMomentumLongSkipStartMinute = 599;
			mnq.openingMomentumLongSkipEndMinute = 659;
		}
	}

	private static void applyAllSkips(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		disableESKrev(settings);
		keepESVwapMoneyWindow(settings);
		trimMNQBadLateVwap(settings);
		skipOrbLongTen(settings);
		skipSweepShort1345(settings);
		skipMNQOmomLongTen(settings);
	}

	private static void bumpRisk(FuturesManager.FuturesRiskSettings risk, double maxRisk, int maxContracts) {
		if (risk == null) {
			return;
		}
		risk.maxRiskPerTrade = Math.max(risk.maxRiskPerTrade, maxRisk);
		risk.maxContracts = Math.max(risk.maxContracts, maxContracts);
	}

	private static boolean isMicro(String symbol) {
		return "MES".equals(symbol) || "MNQ".equals(symbol) || "M2K".equals(symbol) || "MGC".equals(symbol);
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

	private static RunSummary bestFunded(List<RunSummary> summaries) {
		RunSummary best = null;
		for (RunSummary summary : summaries) {
			if (!FUNDED_PROFILE.equals(summary.profile) || summary.ruleViolation != 0) {
				continue;
			}
			if (best == null || summary.pnl > best.pnl) {
				best = summary;
			}
		}
		return best;
	}

	private static RunSummary bestAll(List<RunSummary> summaries) {
		RunSummary best = null;
		for (RunSummary summary : summaries) {
			if (summary.ruleViolation != 0) {
				continue;
			}
			if (best == null || summary.pnl > best.pnl) {
				best = summary;
			}
		}
		return best;
	}

	private static RunSummary loadRun(int id, String name, String profile) throws Exception {
		RunSummary summary = new RunSummary();
		summary.id = id;
		summary.name = name;
		summary.profile = profile;
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

	private static void printSummary(RunSummary summary) {
		System.out.println("RUN " + line(summary));
	}

	private static String line(RunSummary summary) {
		return summary.name
			+ " id=" + summary.id
			+ " profile=" + summary.profile
			+ " pnl=" + round(summary.pnl)
			+ " return=" + round(summary.returnPct)
			+ " trades=" + summary.trades
			+ " win=" + round(summary.winRate)
			+ " pf=" + round(summary.profitFactor)
			+ " dd=" + round(summary.maxDrawdownPct)
			+ " intraday=" + round(summary.maxIntradayLoss)
			+ " mae=" + round(summary.maxAggregateMae)
			+ " violation=" + summary.ruleViolation
			+ " message=\"" + summary.message + "\"";
	}

	private static void printBreakdown(int id, String groupExpression, String label) throws Exception {
		System.out.println("BREAKDOWN " + label + " run=" + id);
		String sql = "SELECT " + groupExpression + ", COUNT(*) AS trades, SUM(pnl) AS pnl, AVG(pnl) AS avgPnl, "
			+ "SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) AS wins "
			+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? GROUP BY " + groupExpression + " ORDER BY pnl DESC";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
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
